package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bill reconciliation endpoints (issue #334, F19 contract v0): canonical upload
 * → async four-state report (request-id match, bucket PARTIAL, unmatched
 * provider, row-level unmatched local), idempotent re-upload, gzip transport,
 * validation, and the audit trail. The provider-specific parsers stay
 * WAITING_FOR_SAMPLE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Reconciliation endpoints integration tests (PostgreSQL)")
class ReconciliationApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private final String adminUsername = "rec_" + UUID.randomUUID().toString().substring(0, 8);

    // Fixture anchors. Bucket-aligned (5-minute grid + 60s offset) so the
    // bucket-diff rows deterministically share one bucket.
    private final Instant occurred = Instant.ofEpochSecond((Instant.now().getEpochSecond() / 300) * 300)
            .minusSeconds(7200).plusSeconds(60);
    private final Instant windowFrom = occurred.minus(30, ChronoUnit.MINUTES);
    private final Instant windowTo = occurred.plus(30, ChronoUnit.MINUTES);
    private String productCode;
    private UUID productId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : List.of("reconciliation_rows", "reconciliation_reports", "usage_event",
                "admin_audit_events", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
        }
        Map<String, Object> product = jdbc.queryForMap(
                "SELECT id, product_code FROM provider_products ORDER BY display_name LIMIT 1",
                new MapSqlParameterSource());
        productId = (UUID) product.get("id");
        productCode = (String) product.get("product_code");

        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(BootstrapHelper.secret(), adminUsername, "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> body = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) body.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("reconciliation_rows", "reconciliation_reports", "usage_event",
                "admin_audit_events", "user_sessions", "users")) {
            jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
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

    private void seedUsage(String providerRequestId, String modelId, Instant at, Long input, Long output) {
        jdbc.update("""
                INSERT INTO usage_event (id, tenant_id, virtual_key_id, project_id, provider_product_id, model_id,
                    provider_request_id, gateway_request_id, input_tokens, output_tokens, upstream_status_code,
                    is_complete, usage_missing, occurred_at)
                VALUES (:id, :tenantId, :keyId, :projectId, :productId, :modelId, :providerRequestId,
                    :gatewayRequestId, :input, :output, 200, TRUE, FALSE, :occurredAt)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT_ID)
                        .addValue("keyId", UUID.randomUUID()).addValue("projectId", UUID.randomUUID())
                        .addValue("productId", productId).addValue("modelId", modelId)
                        .addValue("providerRequestId", providerRequestId)
                        .addValue("gatewayRequestId", "gw-" + UUID.randomUUID()).addValue("input", input)
                        .addValue("output", output).addValue("occurredAt", java.sql.Timestamp.from(at)));
    }

    /** bill: id-match line + no-id bucket line + ghost-id line + malformed line. */
    private String billJsonl() {
        return "{\"provider_request_id\":\"req-1\",\"occurred_at\":\"" + occurred + "\",\"model_id\":\"m-1\","
                + "\"amount\":\"1.00\",\"currency\":\"USD\",\"provider_row_ref\":\"bill-1\"}\n" + "{\"occurred_at\":\""
                + occurred.plusSeconds(30) + "\",\"provider_product_code\":\"" + productCode
                + "\",\"amount\":\"2.00\",\"currency\":\"USD\",\"provider_row_ref\":\"bill-2\"}\n"
                + "{\"provider_request_id\":\"req-ghost\",\"occurred_at\":\"" + occurred + "\",\"model_id\":\"ghost\","
                + "\"amount\":\"3.00\",\"currency\":\"USD\",\"provider_row_ref\":\"bill-3\"}\n"
                + "{\"provider_request_id\":\"req-bad\",\"occurred_at\":\"" + occurred + "\",\"currency\":\"USD\"}\n";
    }

    private MvcResult postReport(byte[] body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/reconciliations").param("providerCode", productCode)
                .param("currency", "USD").param("windowFrom", windowFrom.toString())
                .param("windowTo", windowTo.toString()).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_OCTET_STREAM).content(body))
                .andReturn();
    }

    private String awaitSucceeded(String reportId) throws Exception {
        for (int i = 0; i < 60; i++) {
            MvcResult result = mockMvc
                    .perform(get("/api/v1/admin/reconciliations/" + reportId).cookie(sessionCookie, csrfCookie))
                    .andExpect(status().isOk()).andReturn();
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (body.contains("\"SUCCEEDED\"") || body.contains("\"FAILED\"")) {
                return body;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("report did not finish in time");
    }

    @Test
    @DisplayName("four-state report: request-id match, bucket partial, unmatched both ways, line error")
    void fourStateReport() throws Exception {
        seedUsage("req-1", "m-1", occurred, 10L, 5L);
        seedUsage("req-local-only", "m-2", occurred, 7L, 3L);
        // Bucket counterpart: no id (model irrelevant to bucketing; level 2/3
        // cannot fire because the bill line carries neither model nor tokens).
        seedUsage(null, "m-3", occurred.plusSeconds(60), 2L, 2L);

        MvcResult created = postReport(billJsonl().getBytes(StandardCharsets.UTF_8));
        assertThat(created.getResponse().getStatus()).isEqualTo(202);
        String reportId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        String body = awaitSucceeded(reportId);
        // unmatchedProvider covers every unmatched bill row (id-less bucket row,
        // ghost id, and the error-carrying row); the bucket also surfaces as one
        // PARTIAL entry, and the local side reports one UNMATCHED_LOCAL row.
        assertThat(body).contains("\"SUCCEEDED\"").contains("\"matched\":1").contains("\"unmatchedProvider\":3")
                .contains("\"unmatchedLocal\":1").contains("\"partialBuckets\":1").contains("\"totalRows\":4")
                .contains("\"lineErrorCount\":2").contains("\"amountDiff\":\"5.00000000\"");

        // Row detail surface: each state filterable, cursor paging walks the slice.
        String matched = mockMvc
                .perform(get("/api/v1/admin/reconciliations/" + reportId + "/rows").param("state", "MATCHED")
                        .cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].matchedBy").value("REQUEST_ID")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(matched).contains("bill-1");
        mockMvc.perform(get("/api/v1/admin/reconciliations/" + reportId + "/rows").param("state", "PARTIAL")
                .cookie(sessionCookie, csrfCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1));
        String local = mockMvc
                .perform(get("/api/v1/admin/reconciliations/" + reportId + "/rows").param("state", "UNMATCHED_LOCAL")
                        .cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(local).contains("\"modelId\":\"m-2\"");
        MvcResult page1 = mockMvc
                .perform(get("/api/v1/admin/reconciliations/" + reportId + "/rows").param("limit", "2")
                        .cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2)).andReturn();
        String cursor = objectMapper.readTree(page1.getResponse().getContentAsString()).get("nextCursor").asText();
        mockMvc.perform(get("/api/v1/admin/reconciliations/" + reportId + "/rows").param("limit", "2")
                .param("cursor", cursor).cookie(sessionCookie, csrfCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2));

        // Idempotent re-upload returns the same report.
        MvcResult again = postReport(billJsonl().getBytes(StandardCharsets.UTF_8));
        assertThat(again.getResponse().getStatus()).isEqualTo(202);
        assertThat(objectMapper.readTree(again.getResponse().getContentAsString()).get("id").asText())
                .isEqualTo(reportId);

        // gzip transport of different content is a distinct report (sha differs).
        MvcResult gz = postReport(gzip(billJsonl().getBytes(StandardCharsets.UTF_8)));
        String gzId = objectMapper.readTree(gz.getResponse().getContentAsString()).get("id").asText();
        assertThat(gzId).isNotEqualTo(reportId);
        assertThat(awaitSucceeded(gzId)).contains("\"SUCCEEDED\"");

        // Audit trail: created + succeeded, never content.
        assertThat(eventCount("RECONCILIATION_CREATED")).isEqualTo(2);
        assertThat(eventCount("RECONCILIATION_SUCCEEDED")).isEqualTo(2);
    }

    @Test
    @DisplayName("validation: window, unknown provider, currency, unusable upload")
    void validation() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reconciliations").param("providerCode", productCode)
                .param("currency", "USD").param("windowFrom", occurred.toString())
                .param("windowTo", occurred.plus(40, ChronoUnit.DAYS).toString()).cookie(sessionCookie, csrfCookie)
                .header("X-CSRF-Token", csrfToken).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content("{}".getBytes(StandardCharsets.UTF_8))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_WINDOW_INVALID"));
        mockMvc.perform(
                post("/api/v1/admin/reconciliations").param("providerCode", "no-such-product").param("currency", "USD")
                        .param("windowFrom", windowFrom.toString()).param("windowTo", windowTo.toString())
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content("{}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_PROVIDER_UNKNOWN"));
        mockMvc.perform(post("/api/v1/admin/reconciliations").param("providerCode", productCode).param("currency", "US")
                .param("windowFrom", windowFrom.toString()).param("windowTo", windowTo.toString())
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content("{}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RECONCILIATION_PARAM_INVALID"));

        // Entirely unusable upload fails the async run with a clear message.
        MvcResult created = postReport("not json at all\nstill not json\n".getBytes(StandardCharsets.UTF_8));
        assertThat(created.getResponse().getStatus())
                .as("create response: %s", created.getResponse().getContentAsString()).isEqualTo(202);
        String reportId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        String body = awaitSucceeded(reportId);
        assertThat(body).contains("\"FAILED\"").contains("无可用的账单行");
        // Status and audit are separate autocommit writes on the async thread.
        assertThat(awaitCount("RECONCILIATION_FAILED", 1)).isTrue();

        // Unknown report id is a 404.
        mockMvc.perform(get("/api/v1/admin/reconciliations/" + UUID.randomUUID()).cookie(sessionCookie, csrfCookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECONCILIATION_NOT_FOUND"));
    }

    /** Poll an audit count (async run writes status and audit separately). */
    private boolean awaitCount(String action, long expected) throws Exception {
        for (int i = 0; i < 25; i++) {
            if (eventCount(action) == expected) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private long eventCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Long.class);
    }

    private static byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(input);
        }
        return out.toByteArray();
    }
}
