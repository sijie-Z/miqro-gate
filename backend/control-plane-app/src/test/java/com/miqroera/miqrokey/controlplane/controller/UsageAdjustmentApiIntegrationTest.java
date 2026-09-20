package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usage-adjustment API against real PostgreSQL (#709): the append-only ledger
 * is exercised end to end, because the parts that matter most only exist in SQL
 * — the partial unique index that makes a retried submission idempotent, and
 * the grouped-delta subquery the "net cannot go negative" rule reads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Usage adjustment API integration tests (PostgreSQL)")
class UsageAdjustmentApiIntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REQUEST_ID = "gw-adj-0001";

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdminUsageApiIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Cookie adminSession;
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(AdminUsageApiIntegrationTest.BootstrapHelper.secret(),
                                        "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> body = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) body.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
        insertUsageEvent(REQUEST_ID, 1_000L, 500L);
    }

    @Test
    @DisplayName("a correction is appended and read back through the list endpoint")
    void correctionIsAppendedAndListed() throws Exception {
        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-200," + "\"reason\":\"上游账单修正\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.adjustmentType").value("USAGE"))
                .andExpect(jsonPath("$.outputTokensDelta").value(-200));

        mockMvc.perform(
                get("/api/v1/admin/usage-adjustments").param("gatewayRequestId", REQUEST_ID).cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reason").value("上游账单修正"));
    }

    @Test
    @DisplayName("a retried submission under the same idempotency key does not book twice")
    void idempotencyKeyMakesRetriesSafe() throws Exception {
        String body = "{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-200,"
                + "\"reason\":\"上游账单修正\",\"idempotencyKey\":\"retry-1\"}";
        MvcResult first = append(body).andExpect(status().isCreated()).andReturn();
        Map<?, ?> firstRow = objectMapper.readValue(first.getResponse().getContentAsString(), Map.class);

        append(body).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(firstRow.get("id").toString()));

        // The partial unique index is what actually prevents the second row.
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM usage_adjustments", new MapSqlParameterSource(),
                Long.class);
        org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(1L);
    }

    @Test
    @DisplayName("a correction that would drive a dimension negative is refused, and nothing is written")
    void negativeNetIsRefused() throws Exception {
        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-400," + "\"reason\":\"第一次修正\"}")
                .andExpect(status().isCreated());

        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-200," + "\"reason\":\"过度修正\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADJUSTMENT_WOULD_GO_NEGATIVE"));

        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM usage_adjustments", new MapSqlParameterSource(),
                Long.class);
        org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(1L);
    }

    @Test
    @DisplayName("a reversal restores the net, and the ledger keeps both rows")
    void reversalRestoresTheNet() throws Exception {
        MvcResult created = append(
                "{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-400," + "\"reason\":\"上游账单修正\"}")
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> original = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);

        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"reason\":\"撤销前次错误调整\"," + "\"reversalOfId\":\""
                + original.get("id") + "\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversalOfId").value(original.get("id").toString()))
                .andExpect(jsonPath("$.outputTokensDelta").value(400));

        // Net is back to the observed 500, so a 600 correction is refused again —
        // proving the reversal is actually netted, not merely recorded.
        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-600," + "\"reason\":\"再次过度修正\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADJUSTMENT_WOULD_GO_NEGATIVE"));

        mockMvc.perform(
                get("/api/v1/admin/usage-adjustments").param("gatewayRequestId", REQUEST_ID).cookie(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("an unknown request id is a 404, not a silent write")
    void unknownRequestIdIsNotFound() throws Exception {
        append("{\"gatewayRequestId\":\"nope\",\"outputTokensDelta\":-1,\"reason\":\"查不到\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USAGE_EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("an adjustment moves the net column and the summary, and leaves the observed counts alone")
    void adjustmentMovesNetButNotObserved() throws Exception {
        mockMvc.perform(get("/api/v1/admin/usage/records").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].inputTokens").value(1_000))
                .andExpect(jsonPath("$.items[0].netInputTokens").value(1_000))
                .andExpect(jsonPath("$.items[0].adjusted").value(false));

        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-200," + "\"reason\":\"上游账单修正\"}")
                .andExpect(status().isCreated());

        // Observed is exactly what the gateway recorded and never moves; net carries
        // the
        // correction. Both are exposed so a reader cannot mistake one for the other.
        mockMvc.perform(get("/api/v1/admin/usage/records").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].inputTokens").value(1_000))
                .andExpect(jsonPath("$.items[0].outputTokens").value(500))
                .andExpect(jsonPath("$.items[0].netInputTokens").value(1_000))
                .andExpect(jsonPath("$.items[0].netOutputTokens").value(300))
                .andExpect(jsonPath("$.items[0].adjusted").value(true));
    }

    @Test
    @DisplayName("reversing an adjustment returns the totals but keeps the row flagged")
    void reversalRestoresObservedTotals() throws Exception {
        MvcResult created = append(
                "{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-200," + "\"reason\":\"上游账单修正\"}")
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> original = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);

        mockMvc.perform(get("/api/v1/admin/usage/records").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].netOutputTokens").value(300));

        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"reason\":\"撤销前次调整\",\"reversalOfId\":\""
                + original.get("id") + "\"}").andExpect(status().isCreated());

        // The deltas net back to zero, so the net returns to the observed count. The
        // row stays flagged all the same (#774): "corrected and put back" is not the
        // same fact as "nobody ever touched this", and the append-only ledger exists
        // to keep the first one visible. A client holding both counts can work out for
        // itself whether they differ — this flag carries what it cannot work out.
        mockMvc.perform(get("/api/v1/admin/usage/records").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].adjusted").value(true))
                .andExpect(jsonPath("$.items[0].netOutputTokens").value(500))
                .andExpect(jsonPath("$.items[0].outputTokens").value(500));
    }

    @Test
    @DisplayName("the same adjustment cannot be reversed twice")
    void secondReversalOfTheSameAdjustmentIsRefused() throws Exception {
        MvcResult created = append(
                "{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-200,\"reason\":\"上游账单修正\"}")
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> original = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);

        String reversal = "{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"reason\":\"撤销前次错误调整\",\"reversalOfId\":\""
                + original.get("id") + "\"}";
        append(reversal).andExpect(status().isCreated());

        // The first reversal already cancelled the correction. Negating the same
        // original a second time does not restore anything — it pushes the net
        // above the observed fact (500 -> 300 -> 500 -> 700) and reports the
        // customer as having spent tokens nobody ever measured.
        append(reversal).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_ALREADY_REVERSED"));

        mockMvc.perform(get("/api/v1/admin/usage/records").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outputTokens").value(500))
                .andExpect(jsonPath("$.items[0].netOutputTokens").value(500));

        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM usage_adjustments", new MapSqlParameterSource(),
                Long.class);
        org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(2L);
    }

    private org.springframework.test.web.servlet.ResultActions append(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/usage-adjustments").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, csrfCookie).header("X-CSRF-Token", csrfToken).content(body));
    }

    private void insertUsageEvent(String gatewayRequestId, Long inputTokens, Long outputTokens) {
        jdbc.update("""
                INSERT INTO usage_event
                    (id, tenant_id, virtual_key_id, project_id, provider_product_id, model_id,
                     gateway_request_id, input_tokens, output_tokens, cache_level, occurred_at)
                VALUES
                    (:id, :tenantId, :keyId, :projectId, :productId, 'test-model',
                     :gatewayRequestId, :input, :output, 'UPSTREAM', now())
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT)
                        .addValue("keyId", UUID.randomUUID()).addValue("projectId", UUID.randomUUID())
                        .addValue("productId", UUID.randomUUID()).addValue("gatewayRequestId", gatewayRequestId)
                        .addValue("input", inputTokens).addValue("output", outputTokens));
    }

    /**
     * Child-first, matching the canonical migration set; adjustments precede their
     * anchor.
     */
    private void reset() {
        for (String table : List.of("usage_adjustments", "usage_event", "cache_hit_event", "price_snapshot",
                "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                "project_provider_grant_models", "project_provider_grants", "unattributed_policy",
                "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                "project_memberships", "project_repositories", "projects", "provider_products", "providers",
                "admin_audit_events", "team_memberships", "teams", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering above is child-first for the canonical migration set.
            }
        }
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null) {
            return null;
        }
        for (Cookie c : r.getResponse().getCookies()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }
}
