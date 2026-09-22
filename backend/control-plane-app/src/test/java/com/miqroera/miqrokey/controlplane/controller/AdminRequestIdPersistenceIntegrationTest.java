package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PH66b: the caller-supplied {@code X-Request-Id} header is echoed into
 * {@code admin_audit_events.admin_request_id} (varchar(64), V1:476) verbatim.
 * The header is unbounded at the edge (#445 / #1011 hardened only the response
 * envelope and the log line), so a value longer than the column must not be
 * able to break the audited mutation it is merely describing.
 *
 * <p>
 * Scope note: a correlation id is decoration. It is not an input to the
 * operation, and it does not appear in any validation contract. Its length must
 * therefore never decide whether an administrative action succeeds, and must
 * never decide whether that action leaves an audit trail.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("X-Request-Id persistence bounds integration tests (PostgreSQL)")
class AdminRequestIdPersistenceIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    /** Matches {@code admin_audit_events.admin_request_id} varchar(64). */
    private static final int COLUMN_WIDTH = 64;

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
    private final String adminUsername = "ph66b_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() throws Exception {
        clean();
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
        jdbc.update("DELETE FROM admin_audit_events", new MapSqlParameterSource());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : List.of("admin_api_keys", "webhook_delivery_attempts", "alert_events", "alert_rules",
                "webhook_endpoints", "model_catalog", "config_entries", "budget", "unattributed_policy",
                "project_repositories", "projects", "usage_event", "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    /**
     * Drives the audited mutation the length of {@code requestId} chars and returns
     * the raw observation so the assertion failure prints the evidence.
     */
    private String probe(int requestIdLength) throws Exception {
        // A distinctive tail proves the stored value is the HEAD of what was sent,
        // not an arbitrary 64 chars: it survives a 64-char id and is the first
        // thing dropped from a 65-char one.
        String requestId = "r".repeat(requestIdLength - 1) + "X";
        String name = "ph66b-" + requestIdLength;
        MvcResult result = mockMvc.perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken).header("X-Request-Id", requestId)
                .content("{\"name\":\"" + name + "\",\"type\":\"USAGE_SURGE\",\"threshold\":100}")).andReturn();
        long auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_events WHERE action = 'ALERT_RULE_CREATE'",
                new MapSqlParameterSource(), Long.class);
        long ruleRows = jdbc.queryForObject("SELECT count(*) FROM alert_rules WHERE name = :name",
                new MapSqlParameterSource("name", name), Long.class);
        Long storedWidth = jdbc
                .queryForObject("SELECT coalesce(max(length(admin_request_id)), -1) FROM admin_audit_events"
                        + " WHERE action = 'ALERT_RULE_CREATE'", new MapSqlParameterSource(), Long.class);
        String storedValue = jdbc.queryForObject("SELECT coalesce(max(admin_request_id), '') FROM admin_audit_events"
                + " WHERE action = 'ALERT_RULE_CREATE'", new MapSqlParameterSource(), String.class);
        boolean storedIsHead = storedValue.equals(requestId.substring(0, Math.min(requestIdLength, COLUMN_WIDTH)));
        return String.format(
                "X-Request-Id length=%d -> HTTP %d, ALERT_RULE_CREATE audit rows=%d,"
                        + " stored admin_request_id length=%d, stored is head of sent=%s, alert_rules rows=%d, body=%s",
                requestIdLength, result.getResponse().getStatus(), auditRows, storedWidth, storedIsHead, ruleRows,
                result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a 64-char X-Request-Id fits the column: mutation succeeds and is audited")
    void requestIdAtColumnWidthSucceeds() throws Exception {
        String observation = probe(COLUMN_WIDTH);
        System.out.println("PH66B-RAW control " + observation);
        assertThat(observation).contains("HTTP 200").contains("audit rows=1")
                .contains("stored admin_request_id length=64").contains("stored is head of sent=true")
                .contains("alert_rules rows=1");
    }

    @Test
    @DisplayName("a 65-char X-Request-Id must not cost the admin the mutation or its audit trail")
    void requestIdOneOverColumnWidthIsStillAccepted() throws Exception {
        String observation = probe(COLUMN_WIDTH + 1);
        System.out.println("PH66B-RAW boundary " + observation);
        assertThat(observation).as("one character past the column width").contains("HTTP 200").contains("audit rows=1")
                .contains("stored admin_request_id length=64").contains("stored is head of sent=true")
                .contains("alert_rules rows=1");
    }

    @Test
    @DisplayName("a tracing-style X-Request-Id (128 chars) must not cost the admin the mutation or its audit trail")
    void realWorldTracingRequestIdIsStillAccepted() throws Exception {
        String observation = probe(128);
        System.out.println("PH66B-RAW realistic " + observation);
        assertThat(observation).as("a 128-char correlation id, e.g. a WAF/proxy trace id").contains("HTTP 200")
                .contains("audit rows=1").contains("stored admin_request_id length=64")
                .contains("stored is head of sent=true").contains("alert_rules rows=1");
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
