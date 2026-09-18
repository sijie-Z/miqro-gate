package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Editing a price must not move history (#710 / F21-A).
 *
 * <p>
 * Two properties, deliberately separated because they have different strengths:
 * </p>
 * <ol>
 * <li><b>A later price does not re-price earlier usage.</b> This is the promise
 * users feel — change today's price, last month's bill stays put.</li>
 * <li><b>Rewriting a historical price row does not move already-frozen
 * usage.</b> This one only holds because the event carries its own price: an
 * as-of lookup would happily pick up the edited row. It is the test that proves
 * the frozen column is actually being used rather than merely a fallback that
 * happens to look right.</li>
 * </ol>
 *
 * <p>
 * Both are asserted on the aggregate <em>and</em> on the per-row detail list:
 * the two read the same price expression (#710), so a cost that moves on one
 * and not the other would be a bug in itself, and the agreement is asserted
 * directly rather than assumed.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Frozen price basis keeps history stable (#710)")
class PriceBasisCostStabilityIntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String MODEL = "stability-model";

    /** The call happened two hours ago, priced at 1.00/M in and 2.00/M out. */
    private static final Instant EVENT_AT = Instant.now().minusSeconds(2 * 3600);
    private static final Instant PRICE_AT_EVENT = Instant.now().minusSeconds(3 * 3600);
    /** A price change published after the call. */
    private static final Instant PRICE_LATER = Instant.now().minusSeconds(3600);

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
    private UUID provider;
    private UUID product;

    @BeforeEach
    void setUp() throws Exception {
        reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "stab_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
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

        provider = UUID.randomUUID();
        product = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO providers (id, slug, display_name, status, version)
                VALUES (:id, :slug, 'Stability Provider', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", provider).addValue("slug", "stab-" + provider));
        jdbc.update("""
                INSERT INTO provider_products
                    (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                     supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                VALUES (:id, :providerId, :code, 'Stability Product', 'PAYG', 'SINGLE_SHARED',
                        '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                """, new MapSqlParameterSource("id", product).addValue("providerId", provider).addValue("code",
                "stab-" + product));

        // 1000 input + 500 output, priced at 1.00 and 2.00 per million → 0.002 total.
        seedEvent();
        price("INPUT", PRICE_AT_EVENT, "1.00");
        price("OUTPUT", PRICE_AT_EVENT, "2.00");
        backfill();
    }

    @Test
    @DisplayName("a price published after the call does not re-price it")
    void laterPriceDoesNotMoveHistory() throws Exception {
        BigDecimal before = cost();
        // Guard against a vacuous pass: comparing two zeros would "prove" nothing.
        assertThat(before).as("baseline must be priced, or this test proves nothing").isGreaterThan(BigDecimal.ZERO);

        price("INPUT", PRICE_LATER, "99.00");
        price("OUTPUT", PRICE_LATER, "99.00");

        assertThat(cost()).as("history must not follow a later price").isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("rewriting the historical price row does not move already-frozen usage")
    void rewritingTheHistoricalPriceRowDoesNotMoveHistory() throws Exception {
        BigDecimal before = cost();
        // Guard against a vacuous pass: comparing two zeros would "prove" nothing.
        assertThat(before).as("baseline must be priced, or this test proves nothing").isGreaterThan(BigDecimal.ZERO);

        // Only the frozen column protects against this: an as-of lookup would read the
        // new
        // value, because it still satisfies effective_from <= occurred_at.
        jdbc.update("""
                UPDATE price_snapshot SET unit_price = 77.00
                 WHERE provider_product_id = :productId AND model_id = :modelId AND token_type = 'INPUT'
                """, new MapSqlParameterSource("productId", product).addValue("modelId", MODEL));

        assertThat(cost()).as("the event carries its own price; editing the table must not reach it")
                .isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("the frozen basis prices the call at the rates in force when it happened")
    void frozenBasisIsThePriceInForceAtTheEvent() throws Exception {
        // 1000/1e6 * 1.00 + 500/1e6 * 2.00 = 0.001 + 0.001
        assertThat(cost()).isEqualByComparingTo("0.002");
    }

    @Test
    @DisplayName("a later price does not re-price the detail row either")
    void laterPriceDoesNotMoveTheDetailRow() throws Exception {
        BigDecimal before = detailCost();
        assertThat(before).as("baseline must be priced, or this test proves nothing").isGreaterThan(BigDecimal.ZERO);

        price("INPUT", PRICE_LATER, "99.00");
        price("OUTPUT", PRICE_LATER, "99.00");

        assertThat(detailCost()).as("the detail list must not follow a later price").isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("rewriting the historical price row does not move the detail row")
    void rewritingTheHistoricalPriceRowDoesNotMoveTheDetailRow() throws Exception {
        BigDecimal before = detailCost();
        assertThat(before).as("baseline must be priced, or this test proves nothing").isGreaterThan(BigDecimal.ZERO);

        jdbc.update("""
                UPDATE price_snapshot SET unit_price = 77.00
                 WHERE provider_product_id = :productId AND model_id = :modelId AND token_type = 'INPUT'
                """, new MapSqlParameterSource("productId", product).addValue("modelId", MODEL));

        assertThat(detailCost()).as("the detail row is priced from what the event carries")
                .isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("the detail row and the aggregate read the same basis and agree (#710)")
    void detailRowAndAggregateAgree() throws Exception {
        Map<?, ?> row = detailRow();
        BigDecimal detail = new BigDecimal(String.valueOf(row.get("cost")));

        assertThat(detail).as("1000/1e6 * 1.00 + 500/1e6 * 2.00").isEqualByComparingTo("0.002");
        assertThat(row.get("priced")).as("a fully priced row must say so").isEqualTo(true);
        assertThat(detail).as("detail and summary must not disagree").isEqualByComparingTo(cost());
    }

    // -------------------------------------------------------------------

    private BigDecimal cost() throws Exception {
        MvcResult res = mockMvc
                .perform(get("/api/v1/admin/usage/summary").param("groupBy", "model").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> body = objectMapper.readValue(res.getResponse().getContentAsString(StandardCharsets.UTF_8),
                Map.class);
        Map<?, ?> totals = (Map<?, ?>) body.get("totals");
        Map<?, ?> cost = (Map<?, ?>) totals.get("cost");
        return new BigDecimal(String.valueOf(cost.get("upstreamPaid")));
    }

    /** The one seeded event as the detail endpoint reports it. */
    private Map<?, ?> detailRow() throws Exception {
        MvcResult res = mockMvc
                .perform(get("/api/v1/admin/usage/records").param("size", "10").cookie(sessionCookie, csrfCookie))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> body = objectMapper.readValue(res.getResponse().getContentAsString(StandardCharsets.UTF_8),
                Map.class);
        List<?> items = (List<?>) body.get("items");
        assertThat(items).as("the seeded event must be listed, or a cost comparison is vacuous").hasSize(1);
        return (Map<?, ?>) items.get(0);
    }

    private BigDecimal detailCost() throws Exception {
        return new BigDecimal(String.valueOf(detailRow().get("cost")));
    }

    private void backfill() throws Exception {
        mockMvc.perform(post("/api/v1/admin/usage-price-backfill")
                .param("from", Instant.now().minusSeconds(86400).toString()).param("to", Instant.now().toString())
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk());
    }

    private void seedEvent() {
        jdbc.update("""
                INSERT INTO usage_event (id, tenant_id, virtual_key_id, project_id, provider_product_id, model_id,
                    gateway_request_id, input_tokens, output_tokens, is_complete, usage_missing, occurred_at)
                VALUES (:id, :tenantId, :keyId, :projectId, :productId, :modelId,
                    'gw-stab-1', 1000, 500, TRUE, FALSE, :occurredAt)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT)
                        .addValue("keyId", UUID.randomUUID()).addValue("projectId", UUID.randomUUID())
                        .addValue("productId", product).addValue("modelId", MODEL)
                        .addValue("occurredAt", java.sql.Timestamp.from(EVENT_AT)));
    }

    private void price(String tokenType, Instant effectiveFrom, String unitPrice) {
        jdbc.update("""
                INSERT INTO price_snapshot
                    (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source)
                VALUES (:id, :productId, :modelId, :tokenType, 'CNY', :unitPrice, :effectiveFrom, 'MANUAL')
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", product)
                        .addValue("modelId", MODEL).addValue("tokenType", tokenType)
                        .addValue("unitPrice", new BigDecimal(unitPrice))
                        .addValue("effectiveFrom", java.sql.Timestamp.from(effectiveFrom)));
    }

    /**
     * Child-first, matching the canonical set the other control-plane ITs use.
     * Deleting only this class's own rows would still leave them unseen by a later
     * class's wholesale reset, and a narrower list here would trip on rows another
     * class left behind (that is exactly how a whole-table delete in a persistence
     * test broke the full suite once).
     */
    /**
     * Cleans up after itself, not only before the next test.
     *
     * <p>
     * Leaving these rows behind is not harmless: a later test class deletes
     * {@code providers} in its own setup, and a surviving {@code provider_products}
     * row from here fails that delete — silently, where the cleanup swallows errors
     * — which then leaves <em>its</em> provider row behind for the next class to
     * collide with on a shared slug.
     * </p>
     */
    @AfterEach
    void tearDown() {
        reset();
    }

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
