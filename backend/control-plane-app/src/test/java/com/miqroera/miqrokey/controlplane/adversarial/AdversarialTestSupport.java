package com.miqroera.miqrokey.controlplane.adversarial;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared helpers for the rc.20 adversarial integration batch.
 *
 * <p>
 * The single most important part is {@link #resetAll}: these tests run against
 * the process-wide Testcontainers PostgreSQL shared by every integration class
 * ({@link com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest}),
 * so anything a class leaves behind becomes the next class's 401 ("already
 * bootstrapped"). Every class in this package resets in {@code @BeforeEach}
 * <em>and</em> {@code @AfterEach}; the table list is the union of the existing
 * per-class lists, ordered child-first.
 * </p>
 */
final class AdversarialTestSupport {

    /** Bootstrap secret shared by every class in this package. */
    private static final String SECRET = "test-bootstrap-secret-min-16chars";
    private static final Path SECRET_FILE;

    static {
        try {
            SECRET_FILE = Files.createTempFile("adversarial-bootstrap-secret", ".txt");
            Files.writeString(SECRET_FILE, SECRET);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Child-first delete of every table this batch touches, plus the canonical set
     * the existing suites already reset. Unknown tables and FK ordering problems
     * are ignored row-by-row: the next {@code DELETE} in the list covers what a
     * failed one left, and an actually-left-behind row shows up as a red test
     * rather than as a hidden state.
     */
    static final List<String> RESET_TABLES = List.of(
            // Alerting / webhooks (V12/V15/V24/V27)
            "webhook_delivery_attempts", "alert_events", "alert_rules", "webhook_endpoints",
            // SkillHub (V16/V44/V49)
            "skill_revisions", "skill_access", "skills",
            // MCP (V20/V21/V25/V28/V29/V30/V33/V46)
            "mcp_access_log", "mcp_access_grants", "mcp_service_access", "mcp_route_rule", "mcp_tool_revisions",
            "mcp_tool_retry_policy", "mcp_tools", "mcp_resilience_policy", "mcp_services",
            // Quota (V9/V23/V26/V59)
            "quota_enforcement", "quota_rules", "quota_default_template", "quota_snapshots",
            // Usage facts, cache, pricing (V5/V6/V8/V54/V55/V63)
            "usage_adjustments", "usage_deletions", "request_usage_records", "request_context_evidence", "usage_event",
            "cache_hit_event", "cache_entry", "price_snapshot",
            // Keys and grants (V1/V4/V7/V56/V57)
            "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
            "project_provider_grant_models", "project_provider_grants", "model_catalog", "unattributed_policy",
            "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
            "project_memberships", "team_memberships", "project_repositories", "projects", "teams", "provider_products",
            "providers",
            // Identity (V1/V2/V3)
            "admin_audit_events", "user_sessions", "users");

    private AdversarialTestSupport() {
    }

    static void resetAll(NamedParameterJdbcTemplate jdbc) {
        for (String table : RESET_TABLES) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Child-first order covers the canonical FK set; anything else is
                // retried by a later DELETE in the list or surfaces as a red test.
            }
        }
    }

    static Path secretFile() {
        return SECRET_FILE;
    }

    static String secret() {
        return SECRET;
    }
}
