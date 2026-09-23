package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.service.ExportTaskService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The raw-usage export is bounded in rows and reads its window a page at a time
 * (#1403).
 *
 * <p>
 * Before this, {@code readRows} had no {@code LIMIT} at all and the whole
 * window was materialised twice — once as a {@code List<Map>} and again as the
 * finished {@code byte[]}. On 400 000 real rows that was an OutOfMemoryError at
 * 19 s under the production heap flags, and {@code -XX:+ExitOnOutOfMemoryError}
 * turned it into a full control-plane outage from a single admin POST. The
 * three tests here pin the three things that failure was made of: the page walk
 * must not lose or repeat rows, a window past the cap must be refused rather
 * than silently short, and a window exactly at the cap must still succeed.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Usage export: bounded pages and the row cap (PostgreSQL)")
class ExportTaskBoundedReadIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * The instant every seeded row is measured from; well inside the window below.
     */
    private static final Instant BASE = Instant.now().minusSeconds(3600);

    /**
     * A task that renders this many rows is doing 101 JDBC pages and ~12 MB of CSV;
     * the 10 s the sibling export tests allow would be timing the machine.
     */
    private static final int POLL_ITERATIONS = 160;

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
                                "bnd_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
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
        reset();
    }

    @Test
    @DisplayName("a tie group wider than a page survives the walk: no row is lost and none is exported twice")
    void rowsSharingOneInstantSurvivePageBoundaries() throws Exception {
        // Every row at the SAME instant, and more of them than two full pages, so the
        // walk has to resume twice inside one tie group. A cursor on occurred_at alone
        // cannot: page two of an occurred_at-only cursor comes back empty and the
        // export ends at 500 rows. This is #1368's bug class, here in the export path.
        int rows = ExportTaskService.EXPORT_CHUNK * 2 + 200;
        seed(rows, BASE, "INTERVAL '0 seconds'");

        UUID taskId = runExport(BASE.minusSeconds(60), BASE.plusSeconds(60));

        assertThat(rowCountOf(taskId)).isEqualTo(rows);
        List<String> requestIds = columnOf(csv(taskId), "gatewayRequestId");
        assertThat(requestIds).as("one line per row, and not one more").hasSize(rows);
        Set<String> distinct = new HashSet<>(requestIds);
        assertThat(distinct).as("a row repeated across a page boundary").hasSize(rows);
        assertThat(distinct).contains("gw-1", "gw-" + rows);
    }

    @Test
    @DisplayName("a window past the cap fails the task and names the cap, instead of exporting a short file")
    void windowPastTheCapIsRefused() throws Exception {
        seed(ExportTaskService.EXPORT_MAX_ROWS + 1, BASE, "(g * INTERVAL '1 millisecond')");

        UUID taskId = createExport(BASE.minusSeconds(60), BASE.plusSeconds(120));
        assertThat(awaitTerminal(taskId)).as("a capped export must not report success").isEqualTo("FAILED");
        assertThat(errorMessageOf(taskId)).contains(String.valueOf(ExportTaskService.EXPORT_MAX_ROWS));
        assertThat(fileBytesOf(taskId)).as("a refused task must not leave a half-written artifact").isNull();

        // The refusal has to be survivable and actionable: the message tells the admin
        // to narrow the period, so a narrow period over the same table must now work.
        // This is also the availability half of #1403 — the old code took the JVM with
        // it, so nothing after this point could have run at all.
        UUID narrow = runExport(BASE, BASE.plusMillis(3));
        assertThat(rowCountOf(narrow)).isEqualTo(2);
    }

    @Test
    @DisplayName("a window exactly at the cap succeeds: the off-by-one runs the other way too")
    void windowAtTheCapSucceeds() throws Exception {
        seed(ExportTaskService.EXPORT_MAX_ROWS, BASE, "(g * INTERVAL '1 millisecond')");

        UUID taskId = runExport(BASE.minusSeconds(60), BASE.plusSeconds(120));

        // The loop asks for one row past the cap to witness an overflow. If that
        // witness request is off by one the other way, this legitimate export is
        // refused — a cap-sized window is exactly where that shows up.
        assertThat(rowCountOf(taskId)).isEqualTo(ExportTaskService.EXPORT_MAX_ROWS);
        assertThat(csv(taskId).strip().split("\n")).hasSize(ExportTaskService.EXPORT_MAX_ROWS + 1);
    }

    // -------------------------------------------------------------------

    /**
     * Seeds {@code count} usage rows, all sharing one model and shifted from
     * {@code base} by {@code offsetSql} — {@code INTERVAL '0 seconds'} pins every
     * row to a single instant (the tie group above), while a per-row millisecond
     * offset gives each page its own index range so the boundary tests measure the
     * cap and not a sort.
     */
    private void seed(int count, Instant base, String offsetSql) {
        jdbc.update("""
                INSERT INTO usage_event (id, tenant_id, virtual_key_id, project_id, provider_product_id, model_id,
                    provider_request_id, gateway_request_id, input_tokens, output_tokens, client_ip,
                    is_complete, usage_missing, occurred_at)
                SELECT gen_random_uuid(), :tenantId, :keyId, :projectId, :productId, 'bulk-model',
                       'prov-' || g, 'gw-' || g, 1000, 500, '198.51.100.7', TRUE, FALSE,
                       (:occurredAt)::timestamptz + %s
                FROM generate_series(1, :count) AS g
                """.formatted(offsetSql),
                new MapSqlParameterSource("tenantId", TENANT_ID).addValue("keyId", UUID.randomUUID())
                        .addValue("projectId", UUID.randomUUID()).addValue("productId", UUID.randomUUID())
                        .addValue("occurredAt", java.sql.Timestamp.from(base)).addValue("count", count));
    }

    private UUID createExport(Instant from, Instant to) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/exports").param("format", "CSV").param("from", from.toString())
                        .param("to", to.toString()).cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isAccepted()).andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID runExport(Instant from, Instant to) throws Exception {
        UUID taskId = createExport(from, to);
        assertThat(awaitTerminal(taskId)).isEqualTo("SUCCEEDED");
        return taskId;
    }

    private String awaitTerminal(UUID taskId) throws Exception {
        for (int i = 0; i < POLL_ITERATIONS; i++) {
            String state = jdbc.queryForObject("SELECT status FROM export_tasks WHERE id = :id",
                    new MapSqlParameterSource("id", taskId), String.class);
            if (!"RUNNING".equals(state) && !"PENDING".equals(state)) {
                return state;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("export task " + taskId + " never finished");
    }

    private long rowCountOf(UUID taskId) {
        return jdbc.queryForObject("SELECT row_count FROM export_tasks WHERE id = :id",
                new MapSqlParameterSource("id", taskId), Long.class);
    }

    private String errorMessageOf(UUID taskId) {
        return jdbc.queryForObject("SELECT error_message FROM export_tasks WHERE id = :id",
                new MapSqlParameterSource("id", taskId), String.class);
    }

    private byte[] fileBytesOf(UUID taskId) {
        return jdbc.queryForObject("SELECT file_bytes FROM export_tasks WHERE id = :id",
                new MapSqlParameterSource("id", taskId), byte[].class);
    }

    private String csv(UUID taskId) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(fileBytesOf(taskId)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * One column of every data row, located by header name — the sibling export
     * test's rule (#754): a shifted column must not be able to satisfy an assertion
     * by landing under the wrong name.
     */
    private static List<String> columnOf(String csv, String name) {
        String[] lines = csv.strip().split("\n");
        String[] header = lines[0].split(",", -1);
        int index = List.of(header).indexOf(name);
        assertThat(index).as("column " + name + " must be in the header").isNotNegative();
        return java.util.Arrays.stream(lines, 1, lines.length).map(line -> line.split(",", -1)[index]).toList();
    }

    /** Child-first, matching the canonical set. */
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
