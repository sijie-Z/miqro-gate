package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two admins editing the same resource at the same time (PH42).
 *
 * <p>
 * The scenario is the one an operator actually hits: admin A and admin B both
 * have the edit form open. A saves a change to one field, B saves a change to a
 * different field. Neither request may erase the other admin's committed field,
 * and the later writer must be told about the conflict instead of being handed a
 * {@code 200} for a write that silently dropped A's work.
 *
 * <p>
 * The interleave is made deterministic with a row lock held on a second
 * connection (the same barrier the repo already uses in
 * {@code WebhookAlertApiIntegrationTest.deleteWaitsForConcurrentRuleInsert}):
 * A's write is committed while B's UPDATE is already blocked on the row, so B is
 * guaranteed to be working from the snapshot it read before A committed.
 *
 * <p>
 * The webhook-endpoint case is the control: the identical interleave against
 * {@code WebhookEndpointService} (compare-and-set since #475) reports the
 * conflict, which shows the harness can produce the signal and that the
 * alert-rule behaviour is the outlier.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Concurrent edits of the same resource (PH42)")
class AlertRuleConcurrentEditIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdminProviderApiIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString());
        registry.add("miqrokey.control.provider-client.allowed-cidrs", () -> "127.0.0.0/8");
        registry.add("miqrokey.alerts.evaluation-interval-ms", () -> "3600000");
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
    private ExecutorService io;

    @BeforeEach
    void setUp() throws Exception {
        resetData();
        io = Executors.newSingleThreadExecutor();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BootstrapRequest(AdminProviderApiIntegrationTest.BootstrapHelper.secret(),
                                        "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
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
    }

    @AfterEach
    void tearDown() {
        if (io != null) {
            io.shutdownNow();
        }
        resetData();
    }

    /**
     * A renames the rule, B (whose form was loaded before that) edits only the
     * threshold. B's PATCH carries no {@code name}, so it must not write one.
     */
    @Test
    @DisplayName("a PATCH that races another admin's rename must not resurrect the old name (lost update)")
    void concurrentPatchDoesNotClobberTheOtherAdminsField() throws Exception {
        String ruleId = createRule("预算阈值告警", "0.5");
        UUID ruleUuid = UUID.fromString(ruleId);

        Connection conn = jdbc.getJdbcTemplate().getDataSource().getConnection();
        int status;
        try {
            // A: rename, not yet committed — holds the row lock.
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE alert_rules SET name = ?, version = version + 1, updated_at = now() WHERE id = ?")) {
                ps.setString(1, "renamed-by-A");
                ps.setObject(2, ruleUuid);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            // B: PATCH {"threshold": 0.9} — must block on A's row lock.
            Future<Integer> pending = io.submit(() -> mockMvc
                    .perform(patch("/api/v1/admin/alert-rules/" + ruleId).contentType(MediaType.APPLICATION_JSON)
                            .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                            .content("{\"threshold\":0.9}"))
                    .andReturn().getResponse().getStatus());
            Thread.sleep(700);
            assertThat(pending.isDone()).as("B's PATCH must wait for A's row lock, not read past it").isFalse();

            conn.commit();
            status = pending.get(15, TimeUnit.SECONDS);
        } finally {
            conn.close();
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, threshold, version FROM alert_rules WHERE id = :id",
                new MapSqlParameterSource("id", ruleUuid));
        assertThat(row.get("name")).as("admin A's committed rename must survive B's unrelated PATCH")
                .isEqualTo("renamed-by-A");
        if (status == 200) {
            // Accepted without a conflict signal: then B's own field must be the
            // only thing that changed.
            assertThat((BigDecimal) row.get("threshold")).isEqualByComparingTo("0.9");
        } else {
            // Conflict signal (the #475 contract): B is told to refresh and its
            // change is not applied at all.
            assertThat(status).as("a lost update must be a 409 CONCURRENT_MODIFICATION, never a silent 200")
                    .isEqualTo(409);
            assertThat((BigDecimal) row.get("threshold")).isEqualByComparingTo("0.5");
        }
    }

    /**
     * Control: the identical interleave against the resource whose update is
     * already a compare-and-set (#475). Same harness, same timing — the conflict
     * is surfaced.
     */
    @Test
    @DisplayName("control: the same race on a webhook endpoint is a 409, not a silent overwrite (#475)")
    void concurrentPatchOnWebhookEndpointIsReported() throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(Map.of("name", "control-endpoint", "url",
                                "http://127.0.0.1:9/hook", "secret", "whsec-ph42-control"))))
                .andExpect(status().isOk()).andReturn();
        String endpointId = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id")
                .toString();
        UUID endpointUuid = UUID.fromString(endpointId);

        Connection conn = jdbc.getJdbcTemplate().getDataSource().getConnection();
        int status;
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE webhook_endpoints SET name = ?, version = version + 1, updated_at = now() WHERE id = ?")) {
                ps.setString(1, "renamed-by-A");
                ps.setObject(2, endpointUuid);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            Future<Integer> pending = io.submit(() -> mockMvc
                    .perform(patch("/api/v1/admin/webhooks/" + endpointId).contentType(MediaType.APPLICATION_JSON)
                            .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                            .content("{\"timeoutMs\":5000}"))
                    .andReturn().getResponse().getStatus());
            Thread.sleep(700);
            assertThat(pending.isDone()).as("the PATCH must wait for the row lock").isFalse();

            conn.commit();
            status = pending.get(15, TimeUnit.SECONDS);
        } finally {
            conn.close();
        }

        assertThat(status).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT name FROM webhook_endpoints WHERE id = :id",
                new MapSqlParameterSource("id", endpointUuid), String.class)).isEqualTo("renamed-by-A");
    }

    // ------------------------------------------------------------------

    private String createRule(String name, String threshold) throws Exception {
        MvcResult created = mockMvc
                .perform(post("/api/v1/admin/alert-rules").contentType(MediaType.APPLICATION_JSON)
                        .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", name, "type", "USAGE_MISSING_RATE", "threshold",
                                        new BigDecimal(threshold), "dedupeMinutes", 60))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    private void resetData() {
        for (String table : List.of("webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
                "export_tasks", "usage_deletions", "usage_event", "cache_hit_event", "price_snapshot",
                "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                "project_provider_grant_models", "project_provider_grants", "unattributed_policy",
                "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                "project_memberships", "project_repositories", "projects", "provider_products", "providers",
                "admin_audit_events", "user_sessions", "users")) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // child-first ordering for the canonical migration set
            }
        }
    }

    private static Cookie cookie(MvcResult result, String name) {
        Cookie[] cookies = result.getResponse().getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }
}
