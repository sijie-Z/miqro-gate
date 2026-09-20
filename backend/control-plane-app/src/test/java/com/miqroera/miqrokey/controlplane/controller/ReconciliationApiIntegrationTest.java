package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * validation, the report list, and the audit trail. The provider-specific
 * parsers stay WAITING_FOR_SAMPLE.
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
        return postReport(body, windowFrom, windowTo);
    }

    private MvcResult postReport(byte[] body, Instant from, Instant to) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/reconciliations").param("providerCode", productCode)
                .param("currency", "USD").param("windowFrom", from.toString()).param("windowTo", to.toString())
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(body)).andReturn();
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

        // Audit trail: created + succeeded, never content. Note what is awaited above
        // and what is asserted here: `awaitSucceeded` is a barrier for "the report
        // finished", not for "its audit row landed" — the run commits the status first
        // and records the audit in a second statement (#1061), so counting straight
        // after a status poll is the race CI caught. Wait on the count itself.
        assertThat(awaitCount("RECONCILIATION_CREATED", 2)).as("RECONCILIATION_CREATED events").isTrue();
        assertThat(awaitCount("RECONCILIATION_SUCCEEDED", 2)).as("RECONCILIATION_SUCCEEDED events").isTrue();
    }

    /**
     * The report window is half-open, like every other usage window in the product:
     * {@code windowFrom} inclusive, {@code windowTo} exclusive. A usage row landing
     * exactly on the upper bound is billed by the next report, so counting it here
     * as well reports it twice and makes the report's local side disagree with the
     * usage export for the same nominal window.
     */
    @Test
    @DisplayName("window boundary: from inclusive, to exclusive — same as the usage export")
    void windowBoundaryIsHalfOpen() throws Exception {
        seedUsage("req-at-from", "m-at-from", windowFrom, 10L, 5L);
        seedUsage("req-before", "m-before", windowFrom.minusSeconds(1), 10L, 5L);
        seedUsage("req-at-to", "m-at-to", windowTo, 10L, 5L);

        String bill = "{\"provider_request_id\":\"req-ghost\",\"occurred_at\":\"" + occurred
                + "\",\"model_id\":\"ghost\",\"amount\":\"1.00\",\"currency\":\"USD\",\"provider_row_ref\":\"bill-1\"}\n";
        MvcResult created = postReport(bill.getBytes(StandardCharsets.UTF_8));
        assertThat(created.getResponse().getStatus()).isEqualTo(202);
        String reportId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // Only the row exactly on `windowFrom` is inside: the one before it is
        // out, and the one on `windowTo` belongs to the next window.
        assertThat(awaitSucceeded(reportId)).contains("\"SUCCEEDED\"").contains("\"unmatchedLocal\":1");
        String local = mockMvc
                .perform(get("/api/v1/admin/reconciliations/" + reportId + "/rows").param("state", "UNMATCHED_LOCAL")
                        .cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(local).contains("\"modelId\":\"m-at-from\"").doesNotContain("m-at-to");
    }

    /**
     * The other half of {@link #windowBoundaryIsHalfOpen()}: that test proves the
     * row on {@code windowTo} is *not* counted by this window, this one proves the
     * next window does count it. Together they pin "once, not never" — an exclusive
     * upper bound that silently drops the row would satisfy the first assertion
     * alone.
     *
     * <p>
     * The bill line carries a provider_request_id on purpose: an id-less line could
     * reach the same local row through the 5-minute bucket level and report PARTIAL
     * instead, which would make this test pass for the wrong reason.
     */
    @Test
    @DisplayName("window boundary: the row on windowTo is billed by the next window")
    void rowOnWindowToBelongsToTheNextReport() throws Exception {
        seedUsage("req-at-to", "m-at-to", windowTo, 10L, 5L);
        String bill = "{\"provider_request_id\":\"req-ghost\",\"occurred_at\":\"" + occurred
                + "\",\"model_id\":\"ghost\",\"amount\":\"1.00\",\"currency\":\"USD\",\"provider_row_ref\":\"bill-1\"}\n";

        MvcResult created = postReport(bill.getBytes(StandardCharsets.UTF_8), windowTo,
                windowTo.plus(1, ChronoUnit.HOURS));
        assertThat(created.getResponse().getStatus()).isEqualTo(202);
        String reportId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        assertThat(awaitSucceeded(reportId)).contains("\"SUCCEEDED\"").contains("\"unmatchedLocal\":1");
        String local = mockMvc
                .perform(get("/api/v1/admin/reconciliations/" + reportId + "/rows").param("state", "UNMATCHED_LOCAL")
                        .cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(local).contains("\"modelId\":\"m-at-to\"");
    }

    /**
     * The contract promises 「同 (providerCode, window, currency, uploadSha256) 重复导入
     * 返回既有报告（不重复执行）」 (docs/api-contract.md). A sequential re-upload honours it —
     * {@link #fourStateReport()} covers that — but the lookup and the insert are a
     * check-then-act pair over an autocommit connection, so two uploads that are in
     * flight at the same time both miss the lookup and both insert.
     *
     * <p>
     * The interleaving is forced, not raced for: a table-level {@code SHARE} lock
     * suspends every INSERT (ROW EXCLUSIVE) while leaving the dedupe SELECT (ACCESS
     * SHARE) free, so both requests are guaranteed to be inside {@code create()} at
     * the same time before either row can land.
     * </p>
     */
    @Test
    @DisplayName("concurrent duplicate upload: one report, not two")
    void concurrentDuplicateUploadCreatesOneReport() throws Exception {
        byte[] body = billJsonl().getBytes(StandardCharsets.UTF_8);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection lock = jdbc.getJdbcTemplate().getDataSource().getConnection();
        try {
            lock.setAutoCommit(false);
            try (Statement statement = lock.createStatement()) {
                statement.execute("LOCK TABLE reconciliation_reports IN SHARE MODE");
            }
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> uploads = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                uploads.add(pool.submit(() -> {
                    go.await();
                    return postReport(body);
                }));
            }
            go.countDown();
            boolean bothQueuedOnInsert = awaitBlockedInserts(2);
            lock.rollback();

            List<String> reportIds = new ArrayList<>();
            for (Future<MvcResult> upload : uploads) {
                MvcResult result = upload.get(60, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus())
                        .as("create response: %s", result.getResponse().getContentAsString()).isEqualTo(202);
                reportIds.add(objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .get("id").asText());
            }
            assertThat(bothQueuedOnInsert)
                    .as("both uploads must still be in flight — parked on a lock — when the table lock is released")
                    .isTrue();

            int rows = jdbc.queryForObject(
                    "SELECT count(*) FROM reconciliation_reports WHERE tenant_id = :tenantId AND upload_sha256 = :sha",
                    new MapSqlParameterSource("tenantId", TENANT_ID).addValue("sha", sha256Hex(body)), Integer.class);
            assertThat(reportIds.get(0))
                    .as("both concurrent callers of one upload must see the same report (rows stored: %d)", rows)
                    .isEqualTo(reportIds.get(1));
            assertThat(rows).as("one upload content must produce exactly one report").isEqualTo(1);
            assertThat(eventCount("RECONCILIATION_CREATED")).as("one report, one audit event").isEqualTo(1);
        } finally {
            try {
                lock.rollback();
            } catch (SQLException ignored) {
                // the connection is going away anyway
            }
            try {
                lock.close();
            } catch (SQLException ignored) {
                // the connection is going away anyway
            }
            pool.shutdownNow();
        }
    }

    /**
     * Waits until {@code expected} backends are queued on a lock; false on timeout.
     */
    private boolean awaitBlockedInserts(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted",
                    new MapSqlParameterSource(), Integer.class);
            if (waiting != null && waiting >= expected) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
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

    @Test
    @DisplayName("list: newest-first tenant reports, limit bounds, no cross-tenant leak")
    void listReports() throws Exception {
        // Empty tenant renders an empty list, not a 404.
        mockMvc.perform(get("/api/v1/admin/reconciliations").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reports.length()").value(0));

        seedUsage("req-1", "m-1", occurred, 10L, 5L);
        MvcResult first = postReport(billJsonl().getBytes(StandardCharsets.UTF_8));
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        awaitSucceeded(firstId);
        Thread.sleep(50); // distinct created_at for the ordering assertion
        MvcResult second = postReport(gzip(billJsonl().getBytes(StandardCharsets.UTF_8)));
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        awaitSucceeded(secondId);

        // Newest first, with the same metadata view as GET /{id}.
        mockMvc.perform(get("/api/v1/admin/reconciliations").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reports.length()").value(2))
                .andExpect(jsonPath("$.reports[0].id").value(secondId))
                .andExpect(jsonPath("$.reports[1].id").value(firstId))
                .andExpect(jsonPath("$.reports[0].providerCode").value(productCode))
                .andExpect(jsonPath("$.reports[0].currency").value("USD"))
                .andExpect(jsonPath("$.reports[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.reports[0].matched").value(1))
                .andExpect(jsonPath("$.reports[0].amountDiff").value("5.00000000"));

        // limit is honored and bounded (no silent clamp).
        mockMvc.perform(get("/api/v1/admin/reconciliations").param("limit", "1").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reports.length()").value(1))
                .andExpect(jsonPath("$.reports[0].id").value(secondId));
        mockMvc.perform(get("/api/v1/admin/reconciliations").param("limit", "0").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RECONCILIATION_PARAM_INVALID"));
        mockMvc.perform(get("/api/v1/admin/reconciliations").param("limit", "101").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RECONCILIATION_PARAM_INVALID"));

        // A foreign tenant's report never leaks into this tenant's list.
        UUID foreignTenant = UUID.randomUUID();
        UUID adminId = jdbc.queryForObject("SELECT id FROM users LIMIT 1", new MapSqlParameterSource(), UUID.class);
        jdbc.update("INSERT INTO tenants (id, code, name) VALUES (:id, :code, 'List IT B')",
                new MapSqlParameterSource("id", foreignTenant).addValue("code",
                        "list-it-" + foreignTenant.toString().substring(0, 8)));
        try {
            jdbc.update("""
                    INSERT INTO reconciliation_reports (id, tenant_id, created_by, provider_code, currency,
                        window_from, window_to, status, upload_sha256, upload_bytes, created_at)
                    VALUES (:id, :tenantId, :createdBy, :code, 'USD', :from, :to, 'PENDING', :sha, 1, now())
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", foreignTenant)
                            .addValue("createdBy", adminId).addValue("code", productCode)
                            .addValue("from", java.sql.Timestamp.from(windowFrom))
                            .addValue("to", java.sql.Timestamp.from(windowTo)).addValue("sha", "0".repeat(64)));
            mockMvc.perform(
                    get("/api/v1/admin/reconciliations").param("limit", "100").cookie(sessionCookie, csrfCookie))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.reports.length()").value(2));
        } finally {
            jdbc.update("DELETE FROM reconciliation_reports WHERE tenant_id = :tenantId",
                    new MapSqlParameterSource("tenantId", foreignTenant));
            jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", foreignTenant));
        }
    }

    @Test
    @DisplayName("export CSV: declared column order, values equal to the page detail, state filter, audit")
    void exportCsv() throws Exception {
        seedUsage("req-1", "m-1", occurred, 10L, 5L);
        seedUsage("req-local-only", "m-2", occurred, 7L, 3L);
        seedUsage(null, "m-3", occurred.plusSeconds(60), 2L, 2L);

        MvcResult created = postReport(billJsonl().getBytes(StandardCharsets.UTF_8));
        assertThat(created.getResponse().getStatus()).isEqualTo(202);
        String reportId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        awaitSucceeded(reportId);

        // Source of truth for the export: the detail rows the page renders. The
        // fixture yields six detail rows — the metadata totals in
        // fourStateReport() (matched 1 / partial 1 / unmatchedLocal 1 /
        // unmatchedProvider 3) are the four-state summary, not the row count.
        JsonNode pageRows = objectMapper.readTree(mockMvc
                .perform(get("/api/v1/admin/reconciliations/" + reportId + "/rows").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("rows");
        List<String> verdicts = new ArrayList<>();
        pageRows.forEach(row -> verdicts.add(row.get("verdict").asText()));
        assertThat(verdicts).containsExactlyInAnyOrder("MATCHED", "PARTIAL", "UNMATCHED_LOCAL", "UNMATCHED_PROVIDER",
                "UNMATCHED_PROVIDER", "UNMATCHED_PROVIDER");
        int expectedRows = pageRows.size();

        MvcResult export = performExport(reportId, null);
        assertThat(export.getResponse().getContentType()).startsWith("text/csv");
        assertThat(export.getResponse().getHeader("Content-Disposition"))
                .startsWith("attachment; filename=\"reconciliation-" + reportId);
        assertThat(export.getResponse().getHeader("X-MiQroKey-Rows")).isEqualTo(Integer.toString(expectedRows));
        assertThat(export.getResponse().getHeader("X-MiQroKey-Truncated")).isNull();

        // Column order is pinned literally: a reorder in the service fails here.
        List<List<String>> csv = parseCsv(export.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(csv.get(0)).containsExactly("report_id", "provider_code", "row_no", "verdict", "matched_by",
                "provider_row_ref", "local_ref", "detail_model_id", "detail_amount", "detail_currency",
                "detail_occurred_at", "detail_status", "detail_bucket_key", "detail_provider_count",
                "detail_local_count");
        // One CSV row per detail row, cell for cell, same row_no order.
        assertThat(csv).hasSize(pageRows.size() + 1);
        for (int i = 0; i < pageRows.size(); i++) {
            assertThat(csv.get(i + 1)).as("export row %s mirrors detail row %s", i + 1, i + 1)
                    .containsExactlyElementsOf(expectedCells(reportId, pageRows.get(i)));
        }

        // state narrows the export exactly like the page filter, and the declared
        // row count follows the filtered result (header row excluded).
        MvcResult partialExport = performExport(reportId, "PARTIAL");
        List<List<String>> partial = parseCsv(partialExport.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(partial).hasSize(2);
        assertThat(partial.get(1).get(3)).isEqualTo("PARTIAL");
        assertThat(partial.get(1).get(12)).startsWith(productCode + "@");
        assertThat(partialExport.getResponse().getHeader("X-MiQroKey-Rows"))
                .isEqualTo(Integer.toString(partial.size() - 1));

        // Every download is audited with its own exported row count, before the
        // body: the unfiltered export (every detail row) and the PARTIAL one.
        assertThat(eventCount("RECONCILIATION_EXPORT")).isEqualTo(2);
        List<Map<String, Object>> audits = jdbc
                .queryForList("SELECT target_type, target_id, change_summary::text FROM admin_audit_events"
                        + " WHERE action = 'RECONCILIATION_EXPORT'", new MapSqlParameterSource());
        assertThat(audits).hasSize(2);
        List<Integer> auditedRows = new ArrayList<>();
        for (Map<String, Object> audit : audits) {
            assertThat(audit.get("target_type")).isEqualTo("RECONCILIATION");
            assertThat(audit.get("target_id").toString()).isEqualTo(reportId);
            JsonNode summary = objectMapper.readTree((String) audit.get("change_summary"));
            assertThat(summary.get("truncated").asBoolean()).isFalse();
            auditedRows.add(summary.get("rows").asInt());
        }
        assertThat(auditedRows).containsExactlyInAnyOrder(expectedRows, partial.size() - 1);
    }

    @Test
    @DisplayName("export CSV: a signed amount exports as the number the page shows, not guarded text")
    void exportCsvSignedAmount() throws Exception {
        seedUsage("req-1", "m-1", occurred, 10L, 5L);

        // Refund/adjustment lines: the canonical parser accepts any non-empty
        // amount, so a leading sign reaches the detail JSON verbatim. The export
        // must reproduce it as-is — a spreadsheet-guarded "'-12.34" would both
        // disagree with the page and turn the amount column into text.
        String bill = "{\"provider_request_id\":\"req-1\",\"occurred_at\":\"" + occurred + "\",\"model_id\":\"m-1\","
                + "\"amount\":\"-12.34\",\"currency\":\"USD\",\"provider_row_ref\":\"bill-1\"}\n"
                + "{\"provider_request_id\":\"req-ghost\",\"occurred_at\":\"" + occurred + "\",\"model_id\":\"ghost\","
                + "\"amount\":\"+3.00\",\"currency\":\"USD\",\"provider_row_ref\":\"bill-2\"}\n";
        MvcResult created = postReport(bill.getBytes(StandardCharsets.UTF_8));
        assertThat(created.getResponse().getStatus()).isEqualTo(202);
        String reportId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        awaitSucceeded(reportId);

        JsonNode pageRows = objectMapper.readTree(mockMvc
                .perform(get("/api/v1/admin/reconciliations/" + reportId + "/rows").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("rows");
        // One detail row per bill line, no bucket line (both lines carry an id)
        // and no unmatched local (the seeded usage matched).
        assertThat(pageRows).hasSize(2);

        List<List<String>> csv = parseCsv(
                performExport(reportId, null).getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(csv).hasSize(3);
        Map<String, String> amountByVerdict = new HashMap<>();
        for (int i = 0; i < pageRows.size(); i++) {
            List<String> cells = csv.get(i + 1);
            assertThat(cells).as("export row %s mirrors detail row %s", i + 1, i + 1)
                    .containsExactlyElementsOf(expectedCells(reportId, pageRows.get(i)));
            amountByVerdict.put(pageRows.get(i).get("verdict").asText(), cells.get(8)); // detail_amount
        }
        assertThat(amountByVerdict.get("MATCHED")).isEqualTo("-12.34");
        assertThat(amountByVerdict.get("UNMATCHED_PROVIDER")).isEqualTo("+3.00");
        // A plain decimal literal: no apostrophe, no quoting, equal to the value.
        assertThat(new BigDecimal(amountByVerdict.get("MATCHED"))).isEqualByComparingTo("-12.34");
        assertThat(new BigDecimal(amountByVerdict.get("UNMATCHED_PROVIDER"))).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("export CSV: empty result, unknown state, 5 万行 cap, cross-tenant 404")
    void exportEdges() throws Exception {
        seedUsage("req-1", "m-1", occurred, 10L, 5L);
        // One MATCHED row, so a state filter has a truthful empty answer.
        String oneLine = "{\"provider_request_id\":\"req-1\",\"occurred_at\":\"" + occurred
                + "\",\"model_id\":\"m-1\",\"amount\":\"1.00\",\"currency\":\"USD\",\"provider_row_ref\":\"bill-1\"}\n";
        MvcResult created = postReport(oneLine.getBytes(StandardCharsets.UTF_8));
        String reportId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        awaitSucceeded(reportId);

        // No matching rows is an explicit empty export: header only, 0 rows, no cap
        // flag.
        MvcResult empty = performExport(reportId, "UNMATCHED_LOCAL");
        assertThat(empty.getResponse().getHeader("X-MiQroKey-Rows")).isEqualTo("0");
        assertThat(empty.getResponse().getHeader("X-MiQroKey-Truncated")).isNull();
        List<List<String>> emptyCsv = parseCsv(empty.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(emptyCsv).hasSize(1);
        assertThat(emptyCsv.get(0).get(0)).isEqualTo("report_id");

        // An unknown state fails before any row or audit write: still the single
        // event from the empty export above.
        mockMvc.perform(get("/api/v1/admin/reconciliations/" + reportId + "/export").param("state", "MATCHEDX")
                .cookie(sessionCookie, csrfCookie)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_PARAM_INVALID"));
        assertThat(eventCount("RECONCILIATION_EXPORT")).isEqualTo(1);

        // A foreign tenant's report is not exportable: same 404 as an unknown id.
        UUID foreignTenant = UUID.randomUUID();
        UUID adminId = jdbc.queryForObject("SELECT id FROM users LIMIT 1", new MapSqlParameterSource(), UUID.class);
        jdbc.update("INSERT INTO tenants (id, code, name) VALUES (:id, :code, 'Export IT B')",
                new MapSqlParameterSource("id", foreignTenant).addValue("code",
                        "export-it-" + foreignTenant.toString().substring(0, 8)));
        try {
            UUID foreignReport = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO reconciliation_reports (id, tenant_id, created_by, provider_code, currency,
                        window_from, window_to, status, upload_sha256, upload_bytes, created_at)
                    VALUES (:id, :tenantId, :createdBy, :code, 'USD', :from, :to, 'PENDING', :sha, 1, now())
                    """,
                    new MapSqlParameterSource("id", foreignReport).addValue("tenantId", foreignTenant)
                            .addValue("createdBy", adminId).addValue("code", productCode)
                            .addValue("from", java.sql.Timestamp.from(windowFrom))
                            .addValue("to", java.sql.Timestamp.from(windowTo)).addValue("sha", "0".repeat(64)));
            mockMvc.perform(
                    get("/api/v1/admin/reconciliations/" + foreignReport + "/export").cookie(sessionCookie, csrfCookie))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECONCILIATION_NOT_FOUND"));
        } finally {
            jdbc.update("DELETE FROM reconciliation_reports WHERE tenant_id = :tenantId",
                    new MapSqlParameterSource("tenantId", foreignTenant));
            jdbc.update("DELETE FROM tenants WHERE id = :id", new MapSqlParameterSource("id", foreignTenant));
        }

        // 5 万行 cap: one row past it truncates and says so (rows stay the lowest
        // row_no).
        jdbc.update("""
                INSERT INTO reconciliation_rows (id, report_id, tenant_id, row_no, verdict, provider_row_ref, detail)
                SELECT gen_random_uuid(), :reportId, :tenantId, 100000 + g, 'UNMATCHED_PROVIDER', 'bulk-' || g,
                    '{"modelId":"m-bulk"}'::jsonb
                FROM generate_series(1, 50010) AS g
                """, new MapSqlParameterSource("reportId", UUID.fromString(reportId)).addValue("tenantId", TENANT_ID));
        MvcResult capped = performExport(reportId, null);
        assertThat(capped.getResponse().getHeader("X-MiQroKey-Rows")).isEqualTo("50000");
        assertThat(capped.getResponse().getHeader("X-MiQroKey-Truncated")).isEqualTo("true");
        String body = capped.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Header + exactly the cap, ending on the last row inside it.
        assertThat(body.chars().filter(c -> c == '\n').count()).isEqualTo(50_001);
        String lastLine = body.stripTrailing();
        lastLine = lastLine.substring(lastLine.lastIndexOf('\n') + 1);
        assertThat(lastLine.split(",")[2]).isEqualTo("149999");
    }

    private MvcResult performExport(String reportId, String state) throws Exception {
        var request = get("/api/v1/admin/reconciliations/" + reportId + "/export").cookie(sessionCookie, csrfCookie);
        if (state != null) {
            request = request.param("state", state);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    /**
     * Detail-row values in declared export order, read from the API the page uses.
     */
    private List<String> expectedCells(String reportId, JsonNode row) {
        JsonNode detail = row.path("detail");
        return List.of(reportId, productCode, row.get("rowNo").asText(), row.get("verdict").asText(),
                text(row, "matchedBy"), text(row, "providerRowRef"), text(row, "localRef"), text(detail, "modelId"),
                text(detail, "amount"), text(detail, "currency"), text(detail, "occurredAt"), text(detail, "status"),
                text(detail, "bucketKey"), text(detail, "providerCount"), text(detail, "localCount"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    /**
     * Minimal RFC 4180 reader for the subset csvCell emits (BOM, quoting, ""
     * doubling).
     */
    private static List<List<String>> parseCsv(String csv) {
        List<List<String>> lines = new ArrayList<>();
        List<String> line = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (i == 0 && c == '﻿') {
                continue;
            }
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',' || c == '\n') {
                line.add(cell.toString());
                cell.setLength(0);
                if (c == '\n') {
                    lines.add(line);
                    line = new ArrayList<>();
                }
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (!line.isEmpty() || cell.length() > 0) {
            line.add(cell.toString());
            lines.add(line);
        }
        return lines;
    }

    /**
     * Poll an audit count (async run writes status and audit separately).
     *
     * <p>
     * Use this whenever the assertion is *about an audit count* and what was
     * awaited before it is the report status: the status and the audit row are
     * written in separate statements, status first, so the status poll is not a
     * barrier for the row. #1061 is the case in the wild — `fourStateReport`
     * counted one `RECONCILIATION_SUCCEEDED` where two were expected, on a loaded
     * runner, with every other assertion in the file green.
     *
     * <p>
     * Calls whose audit write is synchronous with the request that produced it —
     * the export downloads, the `CREATED` row inside the uploading request — need
     * no poll; they are asserted directly on purpose.
     */
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
