package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.route.JdbcRouteSnapshotLoader;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Model-approval workflow (原始设计文档 §8.2): user submits a request for an
 * additional model on a key, the admin queue shows it, approval extends the key
 * (and grant, when needed) and the route snapshot picks the model up at once.
 * Covers the whitelist auto-approval, the optimistic-lock double review, and
 * every permission/validation path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Model approval API integration tests (PostgreSQL)")
class ModelApprovalApiIntegrationTest {

    static final String TAG = "core-ai";
    static final String MODEL_A = "model-alpha";
    static final String MODEL_B = "model-beta";
    static final String MODEL_NEW = "model-gamma";
    static final String MODEL_AUTO = "model-auto";
    /** Marker model id: unique to the foreign-tenant fixture, never catalogued. */
    static final String FOREIGN_MODEL = "foreign-model-ph62";

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
        registry.add("miqrokey.gateway-base-url", () -> "https://gateway.test.internal");
        // MODEL_AUTO skips the review queue for every test in this class.
        registry.add("miqrokey.approval.whitelist-models", () -> MODEL_AUTO);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    PasswordHasher passwordHasher;

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;
    private Cookie userSession;
    private Cookie userCsrf;
    private String userCsrfToken;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());

        fx.insertRegularUser("NewSecurePass1!");
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("regular_user", "NewSecurePass1!"))))
                .andExpect(status().isOk()).andReturn();
        userSession = cookie(login, "MIQROKEY_SESSION");
        userCsrf = cookie(login, "MIQROKEY_CSRF");
        userCsrfToken = userCsrf != null ? userCsrf.getValue() : "";
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    // ------------------------------------------------------------------
    // happy path: submit -> admin queue -> approve -> snapshot
    // ------------------------------------------------------------------

    @Test
    @DisplayName("approval for a model outside the grant extends grant, key and snapshot")
    void fullClosedLoop() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);

        // User submits a request for a model the grant does not authorize.
        MvcResult submit = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW, "reason", "需要更强的编码模型"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("需要更强的编码模型")).andExpect(jsonPath("$.keyName").isNotEmpty())
                .andExpect(jsonPath("$.keyDisplay").isNotEmpty()).andReturn();
        UUID approvalId = UUID.fromString(
                (String) objectMapper.readValue(submit.getResponse().getContentAsString(), Map.class).get("id"));

        // The requester sees it in "my requests"; the admin queue shows it too.
        mockMvc.perform(get("/api/v1/me/model-approvals").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modelId").value(MODEL_NEW))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        mockMvc.perform(get("/api/v1/admin/model-approvals?status=PENDING").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(approvalId.toString()))
                .andExpect(jsonPath("$.items[0].modelId").value(MODEL_NEW))
                .andExpect(jsonPath("$.items[0].requesterName").isNotEmpty())
                .andExpect(jsonPath("$.items[0].keyName").value("claude-code-main"));

        // Approve: model joins grant and key; the snapshot reflects it at once.
        postJson("/api/v1/admin/model-approvals/" + approvalId + "/approve", Map.of("reviewNote", "granted"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewNote").value("granted"))
                .andExpect(jsonPath("$.reviewedByName").isNotEmpty());

        List<String> grantModels = grantModelIds();
        List<String> keyModels = keyModelIds(keyId);
        assertThat(grantModels).contains(MODEL_NEW);
        assertThat(keyModels).containsExactlyInAnyOrder(MODEL_A, MODEL_NEW);
        RouteSnapshot snapshot = snapshot();
        assertThat(snapshot.models(keyId)).containsExactlyInAnyOrder(MODEL_A, MODEL_NEW);
        assertThat(snapshot.grantModels(fx.grantId)).contains(MODEL_NEW);

        // Re-reviewing is a conflict (optimistic lock).
        postJson("/api/v1/admin/model-approvals/" + approvalId + "/approve", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));
        mockMvc.perform(get("/api/v1/me/model-approvals").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPROVED"));
    }

    @Test
    @DisplayName("approval for a model already on the grant only extends the key")
    void grantModelSyncDoesNotRewriteGrant() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG); // grant carries MODEL_A + MODEL_B
        UUID keyId = createKey(MODEL_A); // key starts with only MODEL_A

        MvcResult submit = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_B)).andExpect(status().isCreated())
                .andReturn();
        UUID approvalId = UUID.fromString(
                (String) objectMapper.readValue(submit.getResponse().getContentAsString(), Map.class).get("id"));
        postJson("/api/v1/admin/model-approvals/" + approvalId + "/approve", Map.of()).andExpect(status().isOk());

        assertThat(grantModelIds()).containsExactlyInAnyOrder(MODEL_A, MODEL_B);
        assertThat(keyModelIds(keyId)).containsExactlyInAnyOrder(MODEL_A, MODEL_B);
    }

    @Test
    @DisplayName("whitelisted model is auto-approved on submission and effective at once")
    void whitelistAutoApproves() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);

        MvcResult submit = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_AUTO)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewNote").value("Auto-approved: model on the approval whitelist"))
                .andReturn();
        UUID approvalId = UUID.fromString(
                (String) objectMapper.readValue(submit.getResponse().getContentAsString(), Map.class).get("id"));

        assertThat(snapshot().models(keyId)).contains(MODEL_AUTO);
        // The auto-approved request is visible in the queue as approved, and the
        // submitted event was also audited (submit + approve, same actor).
        mockMvc.perform(get("/api/v1/admin/model-approvals?status=APPROVED").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(approvalId.toString()));
    }

    // ------------------------------------------------------------------
    // reject
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reject records the note and leaves models untouched")
    void rejectKeepsModelsUntouched() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);

        MvcResult submit = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW, "reason", "want it"))
                .andExpect(status().isCreated()).andReturn();
        UUID approvalId = UUID.fromString(
                (String) objectMapper.readValue(submit.getResponse().getContentAsString(), Map.class).get("id"));

        postJson("/api/v1/admin/model-approvals/" + approvalId + "/reject", Map.of("reviewNote", "超预算，驳回"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNote").value("超预算，驳回"));
        postJson("/api/v1/admin/model-approvals/" + approvalId + "/reject", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));

        assertThat(keyModelIds(keyId)).containsExactly(MODEL_A);
        assertThat(grantModelIds()).doesNotContain(MODEL_NEW);
        mockMvc.perform(get("/api/v1/admin/model-approvals?status=REJECTED").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("REJECTED"));
    }

    // ------------------------------------------------------------------
    // permissions & validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a user cannot submit for another user's key (generic 404)")
    void idorOnForeignKey() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A); // key belongs to the bootstrap admin

        mockMvc.perform(post("/api/v1/me/model-approvals").contentType(MediaType.APPLICATION_JSON)
                .cookie(userSession, userCsrf).header("X-CSRF-Token", userCsrfToken)
                .content(objectMapper
                        .writeValueAsString(Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("KEY_NOT_FOUND"));
    }

    @Test
    @DisplayName("regular user cannot reach the admin queue")
    void userForbiddenOnAdminQueue() throws Exception {
        mockMvc.perform(get("/api/v1/admin/model-approvals").cookie(userSession)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/model-approvals").cookie(adminSession)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/model-approvals")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("submit validates model availability, duplicates and shape")
    void submitValidation() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);

        // Already available on the key.
        postJson("/api/v1/me/model-approvals", Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_A))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_ALREADY_AVAILABLE"))
                .andExpect(jsonPath("$.detail", containsString("无需重复申请")));
        // Control characters are not a model id.
        postJson("/api/v1/me/model-approvals", Map.of("virtualKeyId", keyId.toString(), "modelId", "bad\tmodel"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_INVALID"))
                .andExpect(jsonPath("$.detail", containsString("模型 ID 无效")));
        // Whitespace is trimmed; overlength is rejected by bean validation.
        postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", "  " + "x".repeat(200)))
                .andExpect(status().isBadRequest());
        // Unknown key is a generic 404.
        postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", UUID.randomUUID().toString(), "modelId", MODEL_NEW))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("duplicate pending request for the same key and model is a conflict")
    void duplicatePendingRejected() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);

        postJson("/api/v1/me/model-approvals", Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW))
                .andExpect(status().isCreated());
        postJson("/api/v1/me/model-approvals", Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PENDING"))
                .andExpect(jsonPath("$.detail", containsString("等待管理员处理")));
    }

    @Test
    @DisplayName("approve refuses when the key or the grant is no longer active")
    void approveRefusesInactiveTargets() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);
        UUID key2 = createKey(MODEL_A); // second key on the same grant, stays active

        // Revoke the key after the request: approval cannot take effect.
        MvcResult submit = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW)).andExpect(status().isCreated())
                .andReturn();
        UUID approvalId = UUID.fromString(
                (String) objectMapper.readValue(submit.getResponse().getContentAsString(), Map.class).get("id"));
        postJson("/api/v1/me/virtual-keys/" + keyId + "/revoke", Map.of()).andExpect(status().isOk());
        postJson("/api/v1/admin/model-approvals/" + approvalId + "/approve", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_NOT_ACTIVE"))
                .andExpect(jsonPath("$.detail", containsString("已吊销")))
                .andExpect(jsonPath("$.detail", containsString("驳回")));

        // Disabled grant also blocks approval for an otherwise healthy key.
        MvcResult submit2 = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", key2.toString(), "modelId", MODEL_NEW)).andExpect(status().isCreated())
                .andReturn();
        UUID approvalId2 = UUID.fromString(
                (String) objectMapper.readValue(submit2.getResponse().getContentAsString(), Map.class).get("id"));
        jdbc.update("UPDATE project_provider_grants SET status = 'DISABLED' WHERE id = :id",
                new MapSqlParameterSource("id", fx.grantId));
        postJson("/api/v1/admin/model-approvals/" + approvalId2 + "/approve", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GRANT_INACTIVE"))
                .andExpect(jsonPath("$.detail", containsString("授权已停用")))
                .andExpect(jsonPath("$.detail", containsString("驳回")));

        // A revoked key cannot receive new requests either.
        postJson("/api/v1/me/model-approvals", Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_B))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KEY_NOT_ACTIVE"));
    }

    @Test
    @DisplayName("approve on a rotated key returns an actionable Chinese message (#574 demo dead-end)")
    void approveOnRotatedKeyIsActionable() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);

        MvcResult submit = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW)).andExpect(status().isCreated())
                .andReturn();
        UUID approvalId = UUID.fromString(
                (String) objectMapper.readValue(submit.getResponse().getContentAsString(), Map.class).get("id"));

        // Rotation retires the old key (ROTATING); a pending request on it can
        // never take effect. Approval must explain the dead end and point at the
        // terminal action instead of returning a bare English error.
        postJson("/api/v1/me/virtual-keys/" + keyId + "/rotate", Map.of()).andExpect(status().isOk());
        postJson("/api/v1/admin/model-approvals/" + approvalId + "/approve", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_NOT_ACTIVE"))
                .andExpect(jsonPath("$.detail", containsString("轮换中")))
                .andExpect(jsonPath("$.detail", containsString("驳回")));

        // Reject stays available as the queue's terminal action for such rows.
        postJson("/api/v1/admin/model-approvals/" + approvalId + "/reject", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("submit refuses a model missing from the provider catalog (#506)")
    void submitRefusesUncatalogedModel() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);

        postJson("/api/v1/me/model-approvals", Map.of("virtualKeyId", keyId.toString(), "modelId", "model-ghost"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODEL_NOT_IN_CATALOG"));
        // Nothing was recorded: no request row, nothing reached the admin queue.
        assertThat(approvalCount()).isZero();
    }

    @Test
    @DisplayName("approve re-checks the catalog: a model disabled after submission is refused (#506)")
    void approveRefusesModelGoneFromCatalog() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);

        MvcResult submit = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW)).andExpect(status().isCreated())
                .andReturn();
        UUID approvalId = UUID.fromString(
                (String) objectMapper.readValue(submit.getResponse().getContentAsString(), Map.class).get("id"));
        jdbc.update("UPDATE model_catalog SET status = 'DISABLED' WHERE provider_product_id = :p AND model_id = :m",
                new MapSqlParameterSource("p", fx.productId).addValue("m", MODEL_NEW));

        postJson("/api/v1/admin/model-approvals/" + approvalId + "/approve", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_IN_CATALOG"));
        // The refusal is atomic: neither table gained the model, request stays PENDING.
        assertThat(keyModelIds(keyId)).containsExactly(MODEL_A);
        assertThat(grantModelIds()).doesNotContain(MODEL_NEW);
        mockMvc.perform(get("/api/v1/admin/model-approvals?status=PENDING").cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(approvalId.toString()));
    }

    // ------------------------------------------------------------------
    // queue pagination
    // ------------------------------------------------------------------

    @Test
    @DisplayName("admin queue paginates with an opaque keyset cursor")
    void queuePagination() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);
        UUID[] ids = new UUID[5];
        for (int i = 0; i < ids.length; i++) {
            fx.catalogModel(MODEL_NEW + "-" + i); // #506: catalog precondition
            MvcResult r = postJson("/api/v1/me/model-approvals",
                    Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW + "-" + i))
                    .andExpect(status().isCreated()).andReturn();
            ids[i] = UUID.fromString(
                    (String) objectMapper.readValue(r.getResponse().getContentAsString(), Map.class).get("id"));
        }

        Map<?, ?> page1 = page("/api/v1/admin/model-approvals?status=PENDING&size=2");
        assertThat(((List<?>) page1.get("items"))).hasSize(2);
        String cursor = (String) page1.get("nextCursor");
        assertThat(cursor).isNotNull();

        Map<?, ?> page2 = page("/api/v1/admin/model-approvals?status=PENDING&size=2&before=" + cursor);
        assertThat(((List<?>) page2.get("items"))).hasSize(2);
        String cursor2 = (String) page2.get("nextCursor");
        assertThat(cursor2).isNotNull();

        Map<?, ?> page3 = page("/api/v1/admin/model-approvals?status=PENDING&size=2&before=" + cursor2);
        assertThat(((List<?>) page3.get("items"))).hasSize(1);
        assertThat(page3.get("nextCursor")).isNull();

        // No overlap across pages: newest first, 5 distinct ids.
        List<String> seen = new java.util.ArrayList<>();
        seen.addAll(((List<?>) page1.get("items")).stream().map(i -> (String) ((Map<?, ?>) i).get("id")).toList());
        seen.addAll(((List<?>) page2.get("items")).stream().map(i -> (String) ((Map<?, ?>) i).get("id")).toList());
        seen.addAll(((List<?>) page3.get("items")).stream().map(i -> (String) ((Map<?, ?>) i).get("id")).toList());
        assertThat(seen).containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(ids).map(UUID::toString).toList());

        // Invalid cursor and out-of-range size are rejected.
        mockMvc.perform(get("/api/v1/admin/model-approvals?before=not-a-cursor").cookie(adminSession))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/model-approvals?size=0").cookie(adminSession))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAM_INVALID"));
    }

    // ------------------------------------------------------------------
    // tenant isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("admin queue shows only its own tenant's requests (#1250)")
    void queueIsTenantScoped() throws Exception {
        fx.insertProviderCatalog();
        fx.insertProjectWithGrant(TAG);
        UUID keyId = createKey(MODEL_A);

        // Tenant A's own pending request — the row that must be the only page item.
        MvcResult submit = postJson("/api/v1/me/model-approvals",
                Map.of("virtualKeyId", keyId.toString(), "modelId", MODEL_NEW)).andExpect(status().isCreated())
                .andReturn();
        UUID ownId = UUID.fromString(
                (String) objectMapper.readValue(submit.getResponse().getContentAsString(), Map.class).get("id"));

        // A PENDING request owned by a foreign tenant. It is written straight to
        // the table because no principal can authenticate outside the seed tenant
        // (AuthenticationService pins it), and the composite (tenant_id, id)
        // foreign keys force the whole parent chain to carry that tenant too.
        UUID foreignId = insertForeignPendingApproval();

        Map<?, ?> page = page("/api/v1/admin/model-approvals?status=PENDING");
        List<?> items = (List<?>) page.get("items");
        assertThat(items).hasSize(1);
        assertThat(((Map<?, ?>) items.get(0)).get("id")).isEqualTo(ownId.toString());
        assertThat(page.toString()).doesNotContain(foreignId.toString()).doesNotContain(FOREIGN_MODEL);

        // Both rows are really in the table — the page is short because the query
        // filters, not because there was nothing to leak.
        assertThat(approvalCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Map<?, ?> page(String url) throws Exception {
        MvcResult r = mockMvc.perform(get(url).cookie(adminSession)).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    private UUID createKey(String model) throws Exception {
        MvcResult r = postJson("/api/v1/me/virtual-keys",
                Map.of("name", "claude-code-main", "projectId", fx.projectId.toString(), "providerProductId",
                        fx.productId.toString(), "credentialGrantId", fx.grantId.toString(), "purpose", "CLAUDE_CODE",
                        "allowedModels", List.of(model)))
                .andExpect(status().isCreated()).andReturn();
        return UUID
                .fromString((String) objectMapper.readValue(r.getResponse().getContentAsString(), Map.class).get("id"));
    }

    private ResultActions postJson(String path, Object payload) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).content(objectMapper.writeValueAsString(payload)));
    }

    private Long approvalCount() {
        return jdbc.queryForObject("SELECT count(*) FROM model_approval", new MapSqlParameterSource(), Long.class);
    }

    /**
     * A PENDING approval belonging to a brand-new tenant, inserted row by row
     * because the {@code (tenant_id, id)} foreign keys require a same-tenant parent
     * chain. Returns the approval id.
     */
    private UUID insertForeignPendingApproval() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        UUID credential = UUID.randomUUID();
        UUID grant = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", tenant).addValue("user", user)
                .addValue("project", project).addValue("subscription", subscription).addValue("credential", credential)
                .addValue("grant", grant).addValue("key", key).addValue("approval", approval)
                .addValue("product", fx.productId).addValue("hash", passwordHasher.hash("NotARealPassword1!"))
                .addValue("publicKeyId", "pk_" + key.toString().replace("-", ""));
        jdbc.update("""
                INSERT INTO tenants (id, code, name, status, version, created_at, updated_at)
                VALUES (:tenant, :code, 'Foreign Tenant', 'ACTIVE', 0, now(), now())
                """, p.addValue("code", "foreign-" + tenant.toString().substring(0, 8)));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                   must_change_password, version)
                VALUES (:user, :tenant, 'foreign_user', 'Foreign', :hash, 'USER', 'ACTIVE', FALSE, 0)
                """, p);
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                VALUES (:project, :tenant, 'FOREIGN', 'Foreign Project', 'ACTIVE', 'foreign-tag', 0)
                """, p);
        jdbc.update("""
                INSERT INTO upstream_subscriptions
                    (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                VALUES (:subscription, :tenant, :product, 'Foreign Sub', 'PAYG', 'ACTIVE', 0)
                """, p);
        jdbc.update("""
                INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                VALUES (:credential, :tenant, :subscription, 'Foreign Cred', 'ACTIVE', 0)
                """, p);
        jdbc.update("""
                INSERT INTO project_provider_grants
                    (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                     version)
                VALUES (:grant, :tenant, :project, :product, :credential, 'ACTIVE', :user, 0)
                """, p);
        jdbc.update("""
                INSERT INTO virtual_keys (id, tenant_id, public_key_id, secret_digest, display_prefix, last_four,
                                          user_id, project_id, grant_id, upstream_credential_id, purpose, name,
                                          status, version)
                VALUES (:key, :tenant, :publicKeyId, :hash, 'fk_foreig', 'e1gn', :user, :project, :grant, :credential,
                        'CUSTOM', 'Foreign Key', 'ACTIVE', 0)
                """, p);
        jdbc.update("""
                INSERT INTO model_approval (id, tenant_id, virtual_key_id, model_id, requested_by, status, version)
                VALUES (:approval, :tenant, :key, :model, :user, 'PENDING', 0)
                """, p.addValue("model", FOREIGN_MODEL));
        return approval;
    }

    private List<String> keyModelIds(UUID keyId) {
        return jdbc.query("SELECT model_id FROM virtual_key_models WHERE virtual_key_id = :id ORDER BY model_id",
                new MapSqlParameterSource("id", keyId), (rs, i) -> rs.getString(1));
    }

    private List<String> grantModelIds() {
        return jdbc.query("SELECT model_id FROM project_provider_grant_models WHERE grant_id = :id ORDER BY model_id",
                new MapSqlParameterSource("id", fx.grantId), (rs, i) -> rs.getString(1));
    }

    private RouteSnapshot snapshot() {
        return new JdbcRouteSnapshotLoader(jdbc, objectMapper).load(1L, Instant.now());
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    /** Direct JDBC fixtures: catalog, project, grant, credential. */
    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID adminId = UUID.randomUUID();
        final UUID userId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "unattributed_policy",
                    "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                    "project_memberships", "project_repositories", "projects", "model_catalog", "provider_products",
                    "providers", "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Child-first order above covers the canonical FK set.
                }
            }
        }

        void insertRegularUser(String password) {
            MapSqlParameterSource p = new MapSqlParameterSource("id", userId).addValue("tenantId", tenantId)
                    .addValue("hash", passwordHasher.hash(password));
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                       must_change_password, version)
                    VALUES (:id, :tenantId, 'regular_user', 'Regular', :hash, 'USER', 'ACTIVE', FALSE, 0)
                    """, p);
        }

        void insertProviderCatalog() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("providerId", providerId).addValue("productId", productId);
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:providerId, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, p);
            // #506: approvals require an ACTIVE model_catalog row for the model.
            for (String model : List.of(MODEL_A, MODEL_B, MODEL_NEW, MODEL_AUTO)) {
                catalogModel(model);
            }
        }

        void catalogModel(String modelId) {
            jdbc.update("""
                    INSERT INTO model_catalog (id, provider_product_id, model_id, status, version)
                    VALUES (:id, :productId, :modelId, 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("modelId", modelId));
        }

        void insertProjectWithGrant(String tag) {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("tenantId", tenantId).addValue("projectId", projectId).addValue("subscriptionId", subscriptionId)
                    .addValue("credentialId", credentialId).addValue("grantId", grantId)
                    .addValue("productId", productId).addValue("tag", tag);
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:projectId, :tenantId, 'P1', 'Project One', 'ACTIVE', :tag, 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:subscriptionId, :tenantId, :productId, 'Sub', 'PAYG', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:credentialId, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, p);
            jdbc.update("""
                    INSERT INTO project_provider_grants
                        (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                         version)
                    VALUES (:grantId, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE', :adminId, 0)
                    """, p.addValue("adminId", adminId));
            for (String model : List.of(MODEL_A, MODEL_B)) {
                jdbc.update("""
                        INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                        VALUES (:tenantId, :grantId, :model)
                        """, new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId)
                        .addValue("model", model));
            }
        }
    }

    static class BootstrapHelper {
        static final java.nio.file.Path SECRET_FILE;
        static final String SECRET = "test-bootstrap-secret-min-16chars";
        static {
            try {
                SECRET_FILE = java.nio.file.Files.createTempFile("bootstrap-secret", ".txt");
                java.nio.file.Files.writeString(SECRET_FILE, SECRET);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        static java.nio.file.Path secretFile() {
            return SECRET_FILE;
        }
        static String secret() {
            return SECRET;
        }
    }
}
