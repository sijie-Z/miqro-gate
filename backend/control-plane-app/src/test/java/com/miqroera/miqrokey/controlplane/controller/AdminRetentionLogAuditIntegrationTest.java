package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.config.TestCryptoConfig;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.service.AdminRetentionLogService;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.RetentionEnvelope;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retention-log read auditing ({@code RETENTION_LOG_VIEW} /
 * {@code RETENTION_LOG_EXPORT}, ADR-0014 §8, api-contract §8): every authorized
 * read of the retained content is itself an auditable admin action, rejected
 * callers leave no event behind, and the audit chain never receives the
 * retained body.
 *
 * <p>
 * The real {@link KeyEncryptionProvider} is imported so the retained plaintext
 * genuinely travels through the authorized read path; otherwise the "no content
 * in the audit chain" assertions would be vacuously true.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestCryptoConfig.class)
@Tag("integration")
@DisplayName("Retention log read-audit integration tests (PostgreSQL)")
class AdminRetentionLogAuditIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file", () -> BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    KeyEncryptionProvider crypto;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private UUID adminUserId;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** Second tenant: rows here must never reach the first tenant's reader. */
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID FOREIGN_USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @BeforeEach
    void setUp() throws Exception {
        clean();
        jdbc.update("INSERT INTO tenants (id, code, name) VALUES (:id, 'retention-audit-b', 'Retention audit B')",
                new MapSqlParameterSource("id", OTHER_TENANT_ID));

        String username = "adm_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper
                                .writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(), username, "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) bootBody.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());

        adminUserId = jdbc.queryForObject("SELECT id FROM users WHERE username = :username",
                new MapSqlParameterSource("username", username), UUID.class);
        // Bootstrap itself audits: drop those rows so per-action counts are exact.
        jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    /** FK-safe order: retention_log/users reference tenants, not the reverse. */
    private void clean() {
        for (String table : List.of("retention_log", "retention_config", "admin_audit_events", "user_sessions",
                "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
        jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", OTHER_TENANT_ID));
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

    /**
     * Writes a retention row exactly as the optional consumer would: AEAD-sealed.
     */
    private void seedRetentionRow(UUID tenantId, UUID userId, String plainText, String direction) {
        EncryptedSecret secret = crypto.encrypt(plainText.getBytes(StandardCharsets.UTF_8), tenantId,
                RetentionEnvelope.AAD_ID);
        jdbc.update("""
                INSERT INTO retention_log (event_id, tenant_id, user_id, virtual_key_id, wire_protocol, direction,
                                           gateway_request_id, occurred_at, key_version, ciphertext, nonce,
                                           text_char_count, truncated)
                VALUES (:eventId, :tenantId, :userId, :virtualKeyId, 'OPENAI_CHAT', :direction, :gatewayRequestId,
                        :occurredAt, :keyVersion, :ciphertext, :nonce, :textCharCount, FALSE)
                """,
                new MapSqlParameterSource().addValue("eventId", UUID.randomUUID()).addValue("tenantId", tenantId)
                        .addValue("userId", userId).addValue("virtualKeyId", UUID.randomUUID())
                        .addValue("direction", direction).addValue("gatewayRequestId", "req-" + UUID.randomUUID())
                        .addValue("occurredAt", java.sql.Timestamp.from(Instant.now()))
                        .addValue("keyVersion", secret.keyVersion()).addValue("ciphertext", secret.ciphertext())
                        .addValue("nonce", secret.nonce()).addValue("textCharCount", plainText.length()));
    }

    private long countEvents(String action) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Long.class);
        return count == null ? 0L : count;
    }

    private MapSqlParameterSource latestEvent(String action) {
        return jdbc.queryForObject("""
                SELECT tenant_id, actor_id, target_type, target_id, change_summary::text AS summary
                FROM admin_audit_events WHERE action = :action
                ORDER BY chain_position DESC LIMIT 1
                """, new MapSqlParameterSource("action", action),
                (rs, n) -> new MapSqlParameterSource().addValue("tenant", rs.getObject("tenant_id"))
                        .addValue("actor", rs.getObject("actor_id")).addValue("targetType", rs.getString("target_type"))
                        .addValue("targetId", rs.getObject("target_id")).addValue("summary", rs.getString("summary")));
    }

    /** The strongest form of "the chain carries no content": scan every summary. */
    private void assertNoContentInAuditChain(String needle) {
        Long leaked = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_events WHERE change_summary::text LIKE :needle",
                new MapSqlParameterSource("needle", "%" + needle + "%"), Long.class);
        assertThat(leaked).as("no audit summary may contain the retained content").isZero();
    }

    private JsonNode summaryOf(String action) throws Exception {
        return objectMapper.readTree((String) latestEvent(action).getValue("summary"));
    }

    private void assertAdminActor(String action) {
        MapSqlParameterSource event = latestEvent(action);
        assertThat((UUID) event.getValue("actor")).as("actor is the calling admin").isEqualTo(adminUserId);
        assertThat((UUID) event.getValue("tenant")).as("event lands in the caller's tenant").isEqualTo(TENANT_ID);
        assertThat(event.getValue("targetType")).isEqualTo("RETENTION_LOG");
        assertThat(event.getValue("targetId")).isNull();
    }

    // ------------------------------------------------------------------
    // ① authorized read → RETENTION_LOG_VIEW
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listing retention logs writes one RETENTION_LOG_VIEW event for the calling admin")
    void listIsAudited() throws Exception {
        String body = "RETENTION-BODY-" + UUID.randomUUID();
        seedRetentionRow(TENANT_ID, adminUserId, body, "OUTPUT");
        seedRetentionRow(TENANT_ID, adminUserId, "RETENTION-BODY-" + UUID.randomUUID(), "INPUT");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/retention-logs").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2))).andReturn();

        // The plaintext really does travel through the authorized read path...
        assertThat(result.getResponse().getContentAsString()).contains(body);

        // ...and that read is itself audited exactly once.
        assertThat(countEvents("RETENTION_LOG_VIEW")).isEqualTo(1);
        assertAdminActor("RETENTION_LOG_VIEW");
        assertThat(summaryOf("RETENTION_LOG_VIEW").get("rows").asInt()).isEqualTo(2);

        // The content is in the response, never in the chain.
        assertNoContentInAuditChain(body);
    }

    @Test
    @DisplayName("the audited row count reflects the returned page, not the table")
    void filteredListAuditsReturnedCount() throws Exception {
        seedRetentionRow(TENANT_ID, adminUserId, "RETENTION-BODY-" + UUID.randomUUID(), "OUTPUT");
        seedRetentionRow(TENANT_ID, adminUserId, "RETENTION-BODY-" + UUID.randomUUID(), "INPUT");

        mockMvc.perform(get("/api/v1/admin/retention-logs").param("direction", "INPUT").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));

        assertThat(summaryOf("RETENTION_LOG_VIEW").get("rows").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rejected filter is not audited as a successful view")
    void invalidFilterIsNotAudited() throws Exception {
        seedRetentionRow(TENANT_ID, adminUserId, "RETENTION-BODY-" + UUID.randomUUID(), "OUTPUT");

        // A malformed timestamp is rejected with 400 PARAM_INVALID before the
        // handler body ever reaches auditService.record.
        mockMvc.perform(get("/api/v1/admin/retention-logs").param("from", "not-a-timestamp").cookie(sessionCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAM_INVALID"));

        assertThat(countEvents("RETENTION_LOG_VIEW")).isZero();
    }

    @Test
    @DisplayName("an unknown direction is a client error like every other rejected filter value")
    void invalidDirectionIsABadRequest() throws Exception {
        seedRetentionRow(TENANT_ID, adminUserId, "RETENTION-BODY-" + UUID.randomUUID(), "OUTPUT");

        // Same endpoint, same class of bad filter input as `from=not-a-timestamp`
        // above, which answers 400 PARAM_INVALID. A misspelled direction has to land
        // in the same bucket: it is a caller mistake, not a server fault.
        mockMvc.perform(get("/api/v1/admin/retention-logs").param("direction", "SIDEWAYS").cookie(sessionCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAM_INVALID"));

        assertThat(countEvents("RETENTION_LOG_VIEW")).isZero();
    }

    // ------------------------------------------------------------------
    // ② authorized export → RETENTION_LOG_EXPORT
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CSV export writes one RETENTION_LOG_EXPORT event and is not recorded as a view")
    void exportIsAudited() throws Exception {
        String body = "RETENTION-EXPORT-" + UUID.randomUUID();
        seedRetentionRow(TENANT_ID, adminUserId, body, "OUTPUT");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/retention-logs/export").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/csv")))
                .andExpect(header().doesNotExist("X-MiQroKey-Truncated")).andReturn();

        String csv = result.getResponse().getContentAsString();
        assertThat(csv).contains("event_id,occurred_at,user_id,user_name").contains(body);
        assertThat(csv.lines().count()).as("header plus one data row").isEqualTo(2);

        assertThat(countEvents("RETENTION_LOG_EXPORT")).isEqualTo(1);
        assertThat(countEvents("RETENTION_LOG_VIEW")).as("an export is not a view").isZero();
        assertAdminActor("RETENTION_LOG_EXPORT");
        JsonNode summary = summaryOf("RETENTION_LOG_EXPORT");
        assertThat(summary.get("rows").asInt()).isEqualTo(1);
        assertThat(summary.get("truncated").asBoolean()).isFalse();

        assertNoContentInAuditChain(body);
    }

    @Test
    @DisplayName("view then export records two distinct actions, one event each")
    void viewAndExportAreDistinctActions() throws Exception {
        seedRetentionRow(TENANT_ID, adminUserId, "RETENTION-BODY-" + UUID.randomUUID(), "OUTPUT");

        mockMvc.perform(get("/api/v1/admin/retention-logs").cookie(sessionCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/retention-logs/export").cookie(sessionCookie)).andExpect(status().isOk());

        assertThat(countEvents("RETENTION_LOG_VIEW")).isEqualTo(1);
        assertThat(countEvents("RETENTION_LOG_EXPORT")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ③ cross-tenant isolation of both the data and its audit trail
    // ------------------------------------------------------------------

    @Test
    @DisplayName("another tenant's retention rows are neither served nor counted for this admin")
    void foreignTenantRowsAreInvisibleAndUnaudited() throws Exception {
        String foreign = "RETENTION-FOREIGN-" + UUID.randomUUID();
        seedRetentionRow(OTHER_TENANT_ID, FOREIGN_USER_ID, foreign, "OUTPUT");

        MvcResult list = mockMvc.perform(get("/api/v1/admin/retention-logs").cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0))).andReturn();
        assertThat(list.getResponse().getContentAsString()).doesNotContain(foreign);

        MvcResult export = mockMvc.perform(get("/api/v1/admin/retention-logs/export").cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(export.getResponse().getContentAsString()).doesNotContain(foreign);

        // Events are attributed to the caller's tenant and report zero rows read.
        assertAdminActor("RETENTION_LOG_VIEW");
        assertAdminActor("RETENTION_LOG_EXPORT");
        assertThat(summaryOf("RETENTION_LOG_VIEW").get("rows").asInt()).isZero();
        assertThat(summaryOf("RETENTION_LOG_EXPORT").get("rows").asInt()).isZero();
        assertNoContentInAuditChain(foreign);
    }

    // ------------------------------------------------------------------
    // ④ denied callers leave no audit event
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a USER-role session is rejected with 403 and no retention event is written")
    void userRoleIsRejectedWithoutAudit() throws Exception {
        jdbc.update("UPDATE users SET role = 'USER' WHERE id = :id", new MapSqlParameterSource("id", adminUserId));

        mockMvc.perform(get("/api/v1/admin/retention-logs").cookie(sessionCookie)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/admin/retention-logs/export").cookie(sessionCookie))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(countEvents("RETENTION_LOG_VIEW")).isZero();
        assertThat(countEvents("RETENTION_LOG_EXPORT")).isZero();
    }

    @Test
    @DisplayName("anonymous requests are rejected with 401 and no retention event is written")
    void anonymousIsRejectedWithoutAudit() throws Exception {
        mockMvc.perform(get("/api/v1/admin/retention-logs")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/admin/retention-logs/export")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(countEvents("RETENTION_LOG_VIEW")).isZero();
        assertThat(countEvents("RETENTION_LOG_EXPORT")).isZero();
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

    @Test
    @DisplayName("#1023: the export streams across page boundaries without losing rows")
    void exportStreamsAcrossPageBoundaries() throws Exception {
        // One full page plus a partial one: the reader has to ask for a second page
        // with
        // the keyset cursor, not just serve whatever one query happened to return.
        int rows = AdminRetentionLogService.EXPORT_CHUNK + 30;
        EncryptedSecret secret = crypto.encrypt("bulk".getBytes(StandardCharsets.UTF_8), TENANT_ID,
                RetentionEnvelope.AAD_ID);
        jdbc.update("""
                INSERT INTO retention_log (event_id, tenant_id, user_id, virtual_key_id, wire_protocol, direction,
                                           gateway_request_id, occurred_at, key_version, ciphertext, nonce,
                                           text_char_count, truncated)
                SELECT gen_random_uuid(), :tenantId, :userId, gen_random_uuid(), 'OPENAI_CHAT', 'OUTPUT',
                       'bulk-' || g, now() - (g || ' seconds')::interval, :keyVersion, :ciphertext, :nonce,
                       4, FALSE
                FROM generate_series(1, :n) AS g
                """,
                new MapSqlParameterSource().addValue("tenantId", TENANT_ID).addValue("userId", adminUserId)
                        .addValue("keyVersion", secret.keyVersion()).addValue("ciphertext", secret.ciphertext())
                        .addValue("nonce", secret.nonce()).addValue("n", rows));

        MvcResult result = mockMvc.perform(get("/api/v1/admin/retention-logs/export").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/csv"))).andReturn();

        String csv = result.getResponse().getContentAsString();
        assertThat(csv.lines().count()).as("header plus every seeded row").isEqualTo(rows + 1L);
        assertThat(csv).contains("bulk-" + rows + ",");
        assertThat(countEvents("RETENTION_LOG_EXPORT")).isEqualTo(1);
    }
}
