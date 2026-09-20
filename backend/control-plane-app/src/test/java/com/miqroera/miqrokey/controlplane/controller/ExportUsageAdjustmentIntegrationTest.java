package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usage export carries adjustments, and its columns line up (#709, #754).
 *
 * <p>
 * Values are asserted <em>by column name</em> rather than by searching the raw
 * text. The earlier header/row drift survived because the only assertion was
 * "the CSV contains local_caliber_note" — which stays true however badly the
 * columns are shifted. Reading by name is what actually fails when they are.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Usage export: adjustment marker and column alignment (PostgreSQL)")
class ExportUsageAdjustmentIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String REQUEST_ID = "gw-exp-1";
    private static final String CLIENT_IP = "203.0.113.9";

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

    @BeforeEach
    void setUp() throws Exception {
        reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "exp_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
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
        seedUsage();
    }

    @AfterEach
    void tearDown() {
        reset();
    }

    @Test
    @DisplayName("the CSV header and its data rows have the same columns, and each name holds its own value")
    void csvColumnsLineUp() throws Exception {
        String csv = exportCsv();
        String[] lines = csv.strip().split("\n");
        assertThat(lines).as("header + one data row").hasSize(2);

        String[] header = lines[0].split(",", -1);
        String[] data = lines[1].split(",", -1);
        assertThat(data).as("data row must carry exactly the declared columns").hasSameSizeAs(header);

        Map<String, String> byName = byName(header, data);
        // The values below are the ones that used to land one column to the left.
        assertThat(byName.get("clientIp")).isEqualTo(CLIENT_IP);
        assertThat(byName.get("isComplete")).isEqualTo("true");
        assertThat(byName.get("modelId")).isEqualTo("export-model");
        assertThat(byName.get("gatewayRequestId")).isEqualTo(REQUEST_ID);
    }

    @Test
    @DisplayName("an unadjusted export reports net equal to observed and adjusted=false")
    void exportWithoutAdjustments() throws Exception {
        Map<String, String> row = firstRow(exportCsv());
        assertThat(row.get("inputTokens")).isEqualTo("1000");
        assertThat(row.get("outputTokens")).isEqualTo("500");
        assertThat(row.get("netInputTokens")).isEqualTo("1000");
        assertThat(row.get("netOutputTokens")).isEqualTo("500");
        assertThat(row.get("adjusted")).isEqualTo("false");
    }

    @Test
    @DisplayName("an adjusted export carries the net counts and the adjusted marker")
    void exportWithAdjustment() throws Exception {
        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-200," + "\"reason\":\"上游账单修正\"}")
                .andExpect(status().isCreated());

        Map<String, String> row = firstRow(exportCsv());
        // The observed fact is untouched; the net carries the correction.
        assertThat(row.get("outputTokens")).isEqualTo("500");
        assertThat(row.get("netOutputTokens")).isEqualTo("300");
        assertThat(row.get("netInputTokens")).isEqualTo("1000");
        assertThat(row.get("adjusted")).isEqualTo("true");
    }

    @Test
    @DisplayName("a reversal returns the export to the observed counts but keeps the marker")
    void exportAfterReversal() throws Exception {
        MvcResult created = append(
                "{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-200," + "\"reason\":\"上游账单修正\"}")
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> original = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);

        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"reason\":\"撤销前次调整\",\"reversalOfId\":\""
                + original.get("id") + "\"}").andExpect(status().isCreated());

        Map<String, String> row = firstRow(exportCsv());
        assertThat(row.get("netOutputTokens")).isEqualTo("500");
        // Same rule as the records list (#774): the export must not report a
        // corrected-then-reversed row as one nobody ever touched.
        assertThat(row.get("adjusted")).isEqualTo("true");
    }

    // -------------------------------------------------------------------
    // #716: the task-level declaration of whether the numbers were corrected

    @Test
    @DisplayName("an untouched export declares NONE, and says so inside the file")
    void untouchedExportDeclaresNone() throws Exception {
        UUID taskId = runExport();

        assertThat(adjustmentLevelOf(taskId)).isEqualTo("NONE");
        // Stated in the file too, so a consumer that only has the artifact knows
        // whether the net columns are worth reading.
        assertThat(firstRow(decompress(taskId)).get("local_caliber_note")).contains("adjustments=none");
    }

    @Test
    @DisplayName("a corrected export declares PRESENT, and says so inside the file")
    void correctedExportDeclaresPresent() throws Exception {
        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-200," + "\"reason\":\"上游账单修正\"}")
                .andExpect(status().isCreated());

        UUID taskId = runExport();

        assertThat(adjustmentLevelOf(taskId)).isEqualTo("PRESENT");
        assertThat(firstRow(decompress(taskId)).get("local_caliber_note")).contains("adjustments=present");
    }

    @Test
    @DisplayName("a reversed correction still declares PRESENT: the rows were touched")
    void reversedCorrectionStillDeclaresPresent() throws Exception {
        MvcResult created = append(
                "{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"outputTokensDelta\":-200," + "\"reason\":\"上游账单修正\"}")
                .andExpect(status().isCreated()).andReturn();
        Map<?, ?> original = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
        append("{\"gatewayRequestId\":\"" + REQUEST_ID + "\",\"reason\":\"撤销前次调整\",\"reversalOfId\":\""
                + original.get("id") + "\"}").andExpect(status().isCreated());

        // The net counts are back to the observed ones, so only this declaration
        // still tells the consumer the file came from corrected rows (#774).
        assertThat(adjustmentLevelOf(runExport())).isEqualTo("PRESENT");
    }

    // -------------------------------------------------------------------

    /**
     * Zips the header with a data row so assertions read by column name. A name
     * missing from the data row surfaces as {@code <missing>} rather than silently
     * comparing against the wrong column.
     */
    private static Map<String, String> byName(String[] header, String[] data) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            byName.put(header[i], i < data.length ? data[i] : "<missing>");
        }
        return byName;
    }

    private Map<String, String> firstRow(String csv) {
        String[] lines = csv.strip().split("\n");
        assertThat(lines).as("header + at least one data row").hasSizeGreaterThanOrEqualTo(2);
        return byName(lines[0].split(",", -1), lines[1].split(",", -1));
    }

    private org.springframework.test.web.servlet.ResultActions append(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/usage-adjustments").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken).content(body));
    }

    private void seedUsage() {
        jdbc.update("""
                INSERT INTO usage_event (id, tenant_id, virtual_key_id, project_id, provider_product_id, model_id,
                    provider_request_id, gateway_request_id, input_tokens, output_tokens, client_ip,
                    is_complete, usage_missing, occurred_at)
                VALUES (:id, :tenantId, :keyId, :projectId, :productId, 'export-model',
                    'prov-exp-1', :gatewayRequestId, 1000, 500, :clientIp,
                    TRUE, FALSE, now() - interval '1 hour')
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT_ID)
                        .addValue("keyId", UUID.randomUUID()).addValue("projectId", UUID.randomUUID())
                        .addValue("productId", UUID.randomUUID()).addValue("gatewayRequestId", REQUEST_ID)
                        .addValue("clientIp", CLIENT_IP));
    }

    private String exportCsv() throws Exception {
        return decompress(runExport());
    }

    /** Runs an export to completion and hands back its task id. */
    private UUID runExport() throws Exception {
        String from = java.time.Instant.now().minusSeconds(2 * 86400).toString();
        String to = java.time.Instant.now().toString();
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/exports").param("format", "CSV").param("from", from).param("to", to)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isAccepted()).andReturn();
        UUID taskId = UUID
                .fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        for (int i = 0; i < 40; i++) {
            String state = jdbc.queryForObject("SELECT status FROM export_tasks WHERE id = :id",
                    new MapSqlParameterSource("id", taskId), String.class);
            if ("SUCCEEDED".equals(state)) {
                return taskId;
            }
            if ("FAILED".equals(state)) {
                throw new AssertionError("export failed");
            }
            Thread.sleep(250);
        }
        throw new AssertionError("export did not finish in time");
    }

    /** The task-level adjustment declaration (#716). */
    private String adjustmentLevelOf(UUID taskId) {
        return jdbc.queryForObject("SELECT adjustment_level FROM export_tasks WHERE id = :id",
                new MapSqlParameterSource("id", taskId), String.class);
    }

    private String decompress(UUID taskId) throws Exception {
        byte[] gz = jdbc.queryForObject("SELECT file_bytes FROM export_tasks WHERE id = :id",
                new MapSqlParameterSource("id", taskId), byte[].class);
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Child-first, matching the canonical set; adjustments precede their anchor.
     */
    private void reset() {
        for (String table : List.of("usage_adjustments", "export_tasks", "usage_event", "admin_audit_events",
                "user_sessions", "users")) {
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
}
