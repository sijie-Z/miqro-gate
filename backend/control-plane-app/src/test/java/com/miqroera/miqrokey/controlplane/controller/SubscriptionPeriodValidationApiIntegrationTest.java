package com.miqroera.miqrokey.controlplane.controller;

import tools.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The subscription-period invariant of the admin write path (#1330 review P1).
 * The create/patch endpoints let an admin write {@code periodStart/periodEnd},
 * and downstream {@code CostAllocationService.fixedCostFor} prorates the plan
 * price by {@code Duration.between(start, end)} clamped to a floor of 1 ms, so
 * a pair that is not a real interval (reversed, equal, or half-written) used to
 * multiply the price by the window measured in that single millisecond. These
 * tests pin the refusal: the two bounds are written together, and the start is
 * strictly before the end — on the merged values of a PATCH, because a
 * one-sided update can land the pair on or before the stored other end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Subscription period invariant on the admin write path (PostgreSQL)")
class SubscriptionPeriodValidationApiIntegrationTest {

    static final String MODEL = "claude-3-7-sonnet";

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

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;
    private final Fixture fx = new Fixture();

    @BeforeEach
    void setUp() throws Exception {
        fx.reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        sessionCookie = cookie(boot, "MIQROKEY_SESSION");
        csrfCookie = cookie(boot, "MIQROKEY_CSRF");
        csrfToken = csrfCookie != null ? csrfCookie.getValue() : "";
        Map<?, ?> bootBody = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        String tempPassword = (String) bootBody.get("temporaryPassword");
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fx.reset();
    }

    // ------------------------------------------------------------------
    // create path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#1330 P1: a create with periodEnd before periodStart is refused and writes nothing")
    void createRejectsReversedPeriod() throws Exception {
        fx.insertCatalogOnly();
        Map<String, Object> body = createBody();
        body.put("periodStart", "2026-09-01T00:00:00Z");
        body.put("periodEnd", "2026-08-01T00:00:00Z");

        mockMvc.perform(postSubscription(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));

        Integer written = jdbc.queryForObject("SELECT count(*) FROM upstream_subscriptions WHERE name = 'Period Plan'",
                new MapSqlParameterSource(), Integer.class);
        assertThat(written).as("subscriptions written by the refused create").isZero();
    }

    @Test
    @DisplayName("#1330 P1: a create with equal period bounds is refused (not an interval)")
    void createRejectsEqualPeriodBounds() throws Exception {
        fx.insertCatalogOnly();
        Map<String, Object> body = createBody();
        body.put("periodStart", "2026-09-01T00:00:00Z");
        body.put("periodEnd", "2026-09-01T00:00:00Z");

        mockMvc.perform(postSubscription(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
    }

    @Test
    @DisplayName("#1330 P1: a create with only one period bound is refused (half a window is no window)")
    void createRejectsOneSidedPeriod() throws Exception {
        fx.insertCatalogOnly();

        Map<String, Object> startOnly = createBody();
        startOnly.put("periodStart", "2026-09-01T00:00:00Z");
        mockMvc.perform(postSubscription(startOnly)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));

        Map<String, Object> endOnly = createBody();
        endOnly.put("periodEnd", "2026-09-01T00:00:00Z");
        mockMvc.perform(postSubscription(endOnly)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
    }

    // ------------------------------------------------------------------
    // update path — validated on the merged pair, not on the payload alone
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#1330 P1: a PATCH whose periodEnd lands on or before the stored start is refused")
    void patchRejectsEndAtOrBeforeStoredStart() throws Exception {
        fx.insertCatalogOnly();
        UUID id = createSubscriptionWithValidPeriod();

        // periodEnd before the stored start: the merged pair would be reversed.
        mockMvc.perform(patchSubscription(id, Map.of("periodEnd", "2026-07-31T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
        // periodEnd equal to the stored start: not an interval either.
        mockMvc.perform(patchSubscription(id, Map.of("periodEnd", "2026-08-01T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));

        // The refused patches leave the stored pair untouched.
        Map<String, Object> row = periodRow(id);
        assertThat(((Timestamp) row.get("period_start")).toInstant()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(((Timestamp) row.get("period_end")).toInstant()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    @DisplayName("#1330 P1: a PATCH whose periodStart lands on or after the stored end is refused")
    void patchRejectsStartAtOrAfterStoredEnd() throws Exception {
        fx.insertCatalogOnly();
        UUID id = createSubscriptionWithValidPeriod();

        // periodStart after the stored end: the merged pair would be reversed.
        mockMvc.perform(patchSubscription(id, Map.of("periodStart", "2026-09-15T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
        // periodStart equal to the stored end: not an interval either.
        mockMvc.perform(patchSubscription(id, Map.of("periodStart", "2026-09-01T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
    }

    @Test
    @DisplayName("#1330 P1: a PATCH carrying both bounds reversed or equal is refused as a pair")
    void patchRejectsReversedOrEqualPairSentTogether() throws Exception {
        fx.insertCatalogOnly();
        UUID id = createSubscriptionWithValidPeriod();

        // Both bounds arrive in the payload, so the merged pair equals it: the same
        // strictly-before check the create path applies, exercised through the
        // update path (an update path that only validated one-sided patches would
        // let this through).
        mockMvc.perform(patchSubscription(id,
                Map.of("periodStart", "2026-10-01T00:00:00Z", "periodEnd", "2026-09-01T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
        mockMvc.perform(patchSubscription(id,
                Map.of("periodStart", "2026-09-15T00:00:00Z", "periodEnd", "2026-09-15T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));

        // The refused patches leave the stored pair untouched.
        Map<String, Object> row = periodRow(id);
        assertThat(((Timestamp) row.get("period_start")).toInstant()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(((Timestamp) row.get("period_end")).toInstant()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    @DisplayName("#1330 P1: a PATCH on a subscription born without a period cannot complete it one end at a time")
    void patchRejectsOneSidedWriteWhenStoredPeriodIsNull() throws Exception {
        fx.insertCatalogOnly();
        Map<String, Object> body = createBody();
        body.put("billingMode", "PAYG");
        MvcResult created = mockMvc.perform(postSubscription(body)).andExpect(status().isOk()).andReturn();
        UUID id = createdId(created);

        // With the other end NULL a single bound cannot form an interval, so a
        // one-sided write is refused on both sides.
        mockMvc.perform(patchSubscription(id, Map.of("periodEnd", "2026-09-01T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));
        mockMvc.perform(patchSubscription(id, Map.of("periodStart", "2026-08-01T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIME_RANGE_INVALID"));

        // Both ends in one patch is the way to give it a period.
        mockMvc.perform(patchSubscription(id,
                Map.of("periodStart", "2026-08-01T00:00:00Z", "periodEnd", "2026-09-01T00:00:00Z")))
                .andExpect(status().isOk());
        Map<String, Object> row = periodRow(id);
        assertThat(((Timestamp) row.get("period_start")).toInstant()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(((Timestamp) row.get("period_end")).toInstant()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    @DisplayName("#1330 P1: an explicit JSON null on one bound keeps the stored side and the merged pair is validated")
    void patchWithExplicitNullOnOneSideValidatesTheMergedPair() throws Exception {
        fx.insertCatalogOnly();
        UUID id = createSubscriptionWithValidPeriod();

        // The PATCH is a sparse merge: an explicit `"periodEnd": null` means keep,
        // it is not a half-write. The merged pair (08-15, stored 09-01) is valid,
        // so the update goes through with the stored end unchanged.
        Map<String, Object> nullEnd = new HashMap<>();
        nullEnd.put("periodStart", "2026-08-15T00:00:00Z");
        nullEnd.put("periodEnd", null);
        mockMvc.perform(patchSubscription(id, nullEnd)).andExpect(status().isOk());

        Map<String, Object> row = periodRow(id);
        assertThat(((Timestamp) row.get("period_start")).toInstant()).isEqualTo(Instant.parse("2026-08-15T00:00:00Z"));
        assertThat(((Timestamp) row.get("period_end")).toInstant()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    // ------------------------------------------------------------------
    // the happy path stays open, and the allocation math still works
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#1330: a valid period still writes, and the fixed cost still prorates")
    void validPeriodStillWritesAndProratesFixedCost() throws Exception {
        fx.insertCatalogOnly();
        Map<String, Object> body = createBody();
        body.put("periodStart", "2026-08-01T00:00:00Z");
        body.put("periodEnd", "2026-08-11T00:00:00Z"); // 10 days
        MvcResult created = mockMvc.perform(postSubscription(body)).andExpect(status().isOk()).andReturn();
        UUID id = createdId(created);

        Map<String, Object> row = periodRow(id);
        assertThat(((Timestamp) row.get("period_start")).toInstant()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(((Timestamp) row.get("period_end")).toInstant()).isEqualTo(Instant.parse("2026-08-11T00:00:00Z"));

        fx.insertCredentialForSubscription(id);
        fx.insertPrices();
        fx.insertUsageAt(fx.projectA, "req-a-1", 1_000L, 500L, Instant.parse("2026-08-03T00:00:00Z"));

        // Half of the 10-day period: 100 * (5 / 10) = 50.00, on the single
        // project with usage in the window; metered cost 1000/1M + 500*2/1M.
        mockMvc.perform(post("/api/v1/admin/subscriptions/" + id + "/cost-allocation/allocate")
                .param("from", "2026-08-01T00:00:00Z").param("to", "2026-08-06T00:00:00Z")
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].fixedCost").value(50.0))
                .andExpect(jsonPath("$[0].usageCost").value(0.002));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The create payload every case starts from; tests add the period keys. */
    private Map<String, Object> createBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("providerProductId", fx.productId.toString());
        body.put("name", "Period Plan");
        body.put("billingMode", "FIXED_SUBSCRIPTION");
        body.put("planScope", "PERSONAL");
        body.put("subscriptionPrice", 100);
        body.put("currency", "USD");
        return body;
    }

    /**
     * Creates a subscription with the canonical valid 2026-08-01..2026-09-01
     * period.
     */
    private UUID createSubscriptionWithValidPeriod() throws Exception {
        Map<String, Object> body = createBody();
        body.put("periodStart", "2026-08-01T00:00:00Z");
        body.put("periodEnd", "2026-09-01T00:00:00Z");
        return createdId(mockMvc.perform(postSubscription(body)).andExpect(status().isOk()).andReturn());
    }

    private MockHttpServletRequestBuilder postSubscription(Map<String, Object> body) throws Exception {
        return post("/api/v1/admin/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder patchSubscription(UUID id, Map<String, Object> body) throws Exception {
        return patch("/api/v1/admin/subscriptions/" + id).contentType(MediaType.APPLICATION_JSON)
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)
                .content(objectMapper.writeValueAsString(body));
    }

    private UUID createdId(MvcResult result) throws Exception {
        return UUID.fromString(
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class).get("id").toString());
    }

    private Map<String, Object> periodRow(UUID id) {
        return jdbc.queryForMap("SELECT period_start, period_end FROM upstream_subscriptions WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    private static Cookie cookie(MvcResult r, String name) {
        if (r.getResponse().getCookies() == null)
            return null;
        for (Cookie c : r.getResponse().getCookies())
            if (name.equals(c.getName()))
                return c;
        return null;
    }

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID projectA = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();

        void reset() {
            for (String table : List.of("cost_allocations", "usage_event", "cache_hit_event", "price_snapshot",
                    "virtual_key_models", "key_project_binding", "model_approval", "virtual_keys",
                    "project_provider_grant_models", "project_provider_grants", "unattributed_policy",
                    "upstream_credential_versions", "upstream_credentials", "plan_seats", "upstream_subscriptions",
                    "project_memberships", "project_repositories", "projects", "provider_products", "providers",
                    "admin_audit_events", "user_sessions", "users")) {
                try {
                    jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
                } catch (Exception ignored) {
                    // Ordering above is child-first for the canonical migration set.
                }
            }
        }

        /** Catalog and project only — the subscription comes in through the API. */
        void insertCatalogOnly() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'test-provider', 'Test Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:productId, :providerId, 'test-product', 'Test Product', 'FIXED_SUBSCRIPTION',
                            'SINGLE_SHARED', '["messages"]', '[{"url":"https://api.test.example"}]',
                            '{"type":"bearer"}', 'VERIFIED', 0)
                    """, new MapSqlParameterSource("productId", productId).addValue("providerId", providerId));
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:a, :tenantId, 'A', 'Project A', 'ACTIVE', 'tag-a', 0)
                    """, new MapSqlParameterSource("a", projectA).addValue("tenantId", tenantId));
        }

        /**
         * The ACTIVE credential row the allocation's usage rows are attributed through.
         */
        void insertCredentialForSubscription(UUID forSubscriptionId) {
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:id, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", tenantId)
                    .addValue("subscriptionId", forSubscriptionId));
        }

        void insertPrices() {
            Instant effective = Instant.parse("2026-01-01T00:00:00Z");
            jdbc.update("""
                    INSERT INTO price_snapshot
                        (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source,
                         created_at)
                    VALUES (:id, :productId, :model, 'INPUT', 'USD', 1.00, :effective, 'MANUAL', now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("model", MODEL).addValue("effective", Timestamp.from(effective)));
            jdbc.update("""
                    INSERT INTO price_snapshot
                        (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source,
                         created_at)
                    VALUES (:id, :productId, :model, 'OUTPUT', 'USD', 2.00, :effective, 'MANUAL', now())
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", productId)
                    .addValue("model", MODEL).addValue("effective", Timestamp.from(effective)));
        }

        void insertUsageAt(UUID projectId, String providerRequestId, long input, long output, Instant occurredAt) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         credential_id, model_id, cache_level, input_tokens, output_tokens, total_tokens, latency_ms,
                         upstream_status_code, is_complete, usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :providerRequestId, '00000000-0000-0000-0000-000000000000', :projectId,
                            :productId, :credentialId, :model, 'UPSTREAM', :input, :output, :total, 42, 200, TRUE,
                            FALSE, 'greq', :occurredAt)
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("providerRequestId", providerRequestId).addValue("projectId", projectId)
                            .addValue("productId", productId).addValue("credentialId", credentialId)
                            .addValue("model", MODEL).addValue("input", input).addValue("output", output)
                            .addValue("total", input + output).addValue("occurredAt", Timestamp.from(occurredAt)));
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
