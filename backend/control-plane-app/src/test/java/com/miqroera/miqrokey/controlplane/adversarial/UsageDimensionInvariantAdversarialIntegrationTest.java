package com.miqroera.miqrokey.controlplane.adversarial;

import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dimension invariant of the usage summary: the same usage facts, grouped by
 * different dimensions, must produce the same totals.
 *
 * <p>
 * The seam this targets is the SQL-to-aggregator one: each dimension groups
 * rows differently ({@code project}, {@code cache_level}, {@code day}, …), and
 * every grouping that drops a row, duplicates one, or prices one twice turns
 * into a total that disagrees with the other dimensions. The strongest form of
 * the check is the cross-dimension equality asserted here; the historical red
 * of this family was #1206 (cache_level double-counted cached savings — each
 * level's row was priced against the key's total hit count). That fix has
 * landed, so this class stands as its regression guard.
 * </p>
 *
 * <p>
 * The fixtures are real rows in the real PostgreSQL (never mocks): two
 * projects, two keys on two users of one team, two provider products with
 * different price snapshots, usage on two days in UPSTREAM and COALESCED form,
 * one model with no price at all, and two cache entries — one whose key
 * received both an L1 and an L2 hit (the #1206 shape), one
 * hits-only-unpriced-model.
 * </p>
 */
@Tag("integration")
@DisplayName("Adversarial: usage totals are invariant across grouping dimensions (PostgreSQL)")
class UsageDimensionInvariantAdversarialIntegrationTest extends AbstractAdversarialIntegrationTest {

    private static final String MODEL_PRICED = "model-alpha";
    private static final String MODEL_UNPRICED = "model-beta";

    private final Fixture fx = new Fixture();

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;
    private Instant from;
    private Instant to;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(AdversarialTestSupport.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        String tempPassword = map(boot).get("temporaryPassword").toString();
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());

        Instant day1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant day2 = day1.minus(2, ChronoUnit.DAYS);
        from = day2.minus(1, ChronoUnit.DAYS);
        to = Instant.now().plus(1, ChronoUnit.HOURS);

        fx.seed();
        fx.insertUsage(fx.keyA, fx.projA, "req-u1", 1_000_000L, 500_000L, MODEL_PRICED, fx.prod1, day1, "UPSTREAM");
        fx.insertUsage(fx.keyB, fx.projB, "req-u2", 2_000_000L, 0L, MODEL_PRICED, fx.prod2, day2, "UPSTREAM");
        fx.insertUsage(fx.keyA, fx.projA, "req-u3", 100L, 50L, MODEL_UNPRICED, fx.prod2, day1, "UPSTREAM");
        fx.insertUsage(fx.keyB, fx.projB, "req-u4", 300_000L, 0L, MODEL_PRICED, fx.prod2, day2, "COALESCED");
        // Cache key hit at both levels (#1206 shape): 2 L1 hits on day 1, 1 L2 on day
        // 2.
        fx.insertCacheEntryAndHits(fx.keyA, fx.projA, fx.prod1, MODEL_PRICED, 1000L, 500L, 2, 1, day1, day2);
        // A hit whose cached model carries no price at all: the saving is a lower
        // bound.
        fx.insertCacheEntryAndHits(fx.keyB, fx.projB, fx.prod2, MODEL_UNPRICED, 1000L, 0L, 1, 0, day2, day2);
    }

    // ------------------------------------------------------------------
    // the anchor: exact expected values, on the reference dimension
    // ------------------------------------------------------------------

    @Test
    @DisplayName("groupBy=project reports the exact expected totals and per-project split")
    void projectTotalsAreExact() throws Exception {
        JsonNode summary = summary("PROJECT", Map.of());
        JsonNode totals = summary.get("totals");

        assertThat(totals.get("tokens").get("input").asLong()).isEqualTo(3_300_100L);
        assertThat(totals.get("tokens").get("output").asLong()).isEqualTo(500_050L);
        assertThat(totals.get("requests").get("upstream").asLong()).isEqualTo(3L);
        assertThat(totals.get("requests").get("coalesced").asLong()).isEqualTo(1L);
        assertThat(totals.get("requests").get("l1Hit").asLong()).isEqualTo(3L);
        assertThat(totals.get("requests").get("l2Hit").asLong()).isEqualTo(1L);
        assertThat(decimal(totals.get("cost").get("upstreamPaid"))).isEqualByComparingTo("3.00");
        assertThat(decimal(totals.get("cost").get("gatewayObserved"))).isEqualByComparingTo("3.15");
        assertThat(decimal(totals.get("cost").get("savedByGatewayCache"))).isEqualByComparingTo("0.006");
        assertThat(totals.get("pricingStatus").asText()).isEqualTo("PARTIAL");
        assertThat(totals.get("unpriced").get("unpricedEvents").asLong()).isEqualTo(1L);
        assertThat(totals.get("unpriced").get("unpricedHitEvents").asLong()).isEqualTo(1L);
        assertThat(totals.get("unpriced").get("inputTokens").asLong()).isEqualTo(100L);
        assertThat(totals.get("unpriced").get("outputTokens").asLong()).isEqualTo(50L);

        List<JsonNode> groups = groups(summary);
        assertThat(groups).hasSize(2);
        JsonNode projectA = groups.get(0);
        JsonNode projectB = groups.get(1);
        assertThat(projectA.get("label").asText()).isEqualTo("Project A");
        assertThat(projectA.get("tokens").get("input").asLong()).isEqualTo(1_000_100L);
        assertThat(decimal(projectA.get("cost").get("upstreamPaid"))).isEqualByComparingTo("2.00");
        assertThat(projectB.get("tokens").get("input").asLong()).isEqualTo(2_300_000L);
        assertThat(decimal(projectB.get("cost").get("upstreamPaid"))).isEqualByComparingTo("1.00");
        assertThat(decimal(projectB.get("cost").get("gatewayObserved"))).isEqualByComparingTo("1.15");
    }

    // ------------------------------------------------------------------
    // the invariant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("usage totals (tokens, requests, costs, pricing gap) are equal across every dimension")
    void usageTotalsAreDimensionInvariant() throws Exception {
        Snap reference = snap(summary("PROJECT", Map.of()).get("totals"));
        Map<String, Snap> readings = new LinkedHashMap<>();
        for (String dimension : List.of("PROJECT", "VIRTUAL_KEY", "CACHE_LEVEL", "DAY", "USER", "TEAM", "MODEL",
                "PRODUCT", "MONTH")) {
            readings.put(dimension, snap(summary(dimension, Map.of()).get("totals")));
        }

        for (Map.Entry<String, Snap> entry : readings.entrySet()) {
            String dimension = entry.getKey();
            Snap actual = entry.getValue();
            assertThat(actual.inputTokens()).as("%s input tokens vs PROJECT", dimension)
                    .isEqualTo(reference.inputTokens());
            assertThat(actual.outputTokens()).as("%s output tokens vs PROJECT", dimension)
                    .isEqualTo(reference.outputTokens());
            assertThat(actual.upstream()).as("%s upstream requests vs PROJECT", dimension)
                    .isEqualTo(reference.upstream());
            assertThat(actual.coalesced()).as("%s coalesced requests vs PROJECT", dimension)
                    .isEqualTo(reference.coalesced());
            assertThat(actual.l1Hit()).as("%s L1 hits vs PROJECT", dimension).isEqualTo(reference.l1Hit());
            assertThat(actual.l2Hit()).as("%s L2 hits vs PROJECT", dimension).isEqualTo(reference.l2Hit());
            assertThat(actual.upstreamPaid()).as("%s upstreamPaid vs PROJECT", dimension)
                    .isEqualByComparingTo(reference.upstreamPaid());
            assertThat(actual.gatewayObserved()).as("%s gatewayObserved vs PROJECT", dimension)
                    .isEqualByComparingTo(reference.gatewayObserved());
            assertThat(actual.pricingStatus()).as("%s pricingStatus vs PROJECT", dimension)
                    .isEqualTo(reference.pricingStatus());
            assertThat(actual.unpricedEvents()).as("%s unpricedEvents vs PROJECT", dimension)
                    .isEqualTo(reference.unpricedEvents());
            assertThat(actual.unavailableEvents()).as("%s unavailableEvents vs PROJECT", dimension)
                    .isEqualTo(reference.unavailableEvents());
        }
    }

    @Test
    @DisplayName("cached saving and unpriced -hit counts are equal across every dimension except cache_level")
    void hitReadingsAreDimensionInvariantOutsideCacheLevel() throws Exception {
        Snap reference = snap(summary("PROJECT", Map.of()).get("totals"));
        for (String dimension : List.of("VIRTUAL_KEY", "DAY", "USER", "TEAM", "MODEL", "PRODUCT", "MONTH")) {
            Snap actual = snap(summary(dimension, Map.of()).get("totals"));
            assertThat(actual.savedByGatewayCache()).as("%s savedByGatewayCache vs PROJECT", dimension)
                    .isEqualByComparingTo(reference.savedByGatewayCache());
            assertThat(actual.unpricedHitEvents()).as("%s unpricedHitEvents vs PROJECT", dimension)
                    .isEqualTo(reference.unpricedHitEvents());
        }
    }

    /**
     * #1206, the shape this class was born red on: one cache key carrying both an
     * L1 and an L2 hit used to be priced at the key's <em>total</em> hit count for
     * every level, so the two rows together reported the saving twice. The landed
     * fix prices each level's row by its own hits; this test (green since) pins
     * that: the CACHE_LEVEL cut must agree with every other dimension's cut.
     */
    @Test
    @DisplayName("REGRESSION #1206: cache_level totals agree with the other dimensions' totals")
    void cacheLevelTotalsAgreeWithOtherDimensions() throws Exception {
        Snap reference = snap(summary("PROJECT", Map.of()).get("totals"));
        Snap cacheLevel = snap(summary("CACHE_LEVEL", Map.of()).get("totals"));

        assertThat(cacheLevel.savedByGatewayCache())
                .as("CACHE_LEVEL savedByGatewayCache=%s vs PROJECT=%s (2 L1 + 1 L2 hits on one key, "
                        + "each hit worth 0.002; the total must be 0.006 — #1206 used to double it)",
                        cacheLevel.savedByGatewayCache(), reference.savedByGatewayCache())
                .isEqualByComparingTo(reference.savedByGatewayCache());
        assertThat(cacheLevel.unpricedHitEvents()).as("CACHE_LEVEL unpricedHitEvents vs PROJECT")
                .isEqualTo(reference.unpricedHitEvents());
    }

    // ------------------------------------------------------------------
    // the cross drill-down: sums of one dimension under a filter must equal
    // the filtered reading of another dimension (provider/model/day crosses)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("per-product day sums and per-project model sums equal the filtered reference readings")
    void crossDimensionDrilldownsAgree() throws Exception {
        JsonNode byProduct = summary("PRODUCT", Map.of());
        for (JsonNode group : groups(byProduct)) {
            UUID productId = UUID.fromString(group.get("groupKey").asText());
            Snap productReading = snap(group);
            Snap daysUnderProduct = sumGroups(summary("DAY", Map.of("providerProductId", productId.toString())));
            assertThat(daysUnderProduct.gatewayObserved())
                    .as("sum of DAY groups under product %s vs the PRODUCT group", productId)
                    .isEqualByComparingTo(productReading.gatewayObserved());
            assertThat(daysUnderProduct.inputTokens()).as("day-summed input tokens under product %s", productId)
                    .isEqualTo(productReading.inputTokens());
        }

        JsonNode byProject = summary("PROJECT", Map.of());
        for (JsonNode group : groups(byProject)) {
            UUID projectId = UUID.fromString(group.get("groupKey").asText());
            Snap projectReading = snap(group);
            Snap modelsUnderProject = sumGroups(summary("MODEL", Map.of("projectId", projectId.toString())));
            assertThat(modelsUnderProject.gatewayObserved())
                    .as("sum of MODEL groups under project %s vs the PROJECT group", projectId)
                    .isEqualByComparingTo(projectReading.gatewayObserved());
            assertThat(modelsUnderProject.savedByGatewayCache())
                    .as("model-summed cached saving under project %s", projectId)
                    .isEqualByComparingTo(projectReading.savedByGatewayCache());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private JsonNode summary(String groupBy, Map<String, String> extraParams) throws Exception {
        var request = get("/api/v1/admin/usage/summary").cookie(adminSession).param("groupBy", groupBy)
                .param("from", from.toString()).param("to", to.toString());
        for (Map.Entry<String, String> param : extraParams.entrySet()) {
            request = request.param(param.getKey(), param.getValue());
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return body(result);
    }

    private static List<JsonNode> groups(JsonNode summary) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode group : summary.get("groups")) {
            result.add(group);
        }
        return result;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.decimalValue();
    }

    /** The fields the invariant is asserted on, read once from a JSON node. */
    private record Snap(long upstream, long coalesced, long l1Hit, long l2Hit, long inputTokens, long outputTokens,
            BigDecimal upstreamPaid, BigDecimal gatewayObserved, BigDecimal savedByGatewayCache, String pricingStatus,
            long unpricedEvents, long unavailableEvents, long unpricedHitEvents) {

        static Snap of(JsonNode node) {
            JsonNode requests = node.get("requests");
            JsonNode tokens = node.get("tokens");
            JsonNode cost = node.get("cost");
            JsonNode unpriced = node.get("unpriced");
            return new Snap(requests.get("upstream").asLong(), requests.get("coalesced").asLong(),
                    requests.get("l1Hit").asLong(), requests.get("l2Hit").asLong(), tokens.get("input").asLong(),
                    tokens.get("output").asLong(), cost.get("upstreamPaid").decimalValue(),
                    cost.get("gatewayObserved").decimalValue(), cost.get("savedByGatewayCache").decimalValue(),
                    node.get("pricingStatus").asText(), unpriced.get("unpricedEvents").asLong(),
                    unpriced.get("unavailableEvents").asLong(), unpriced.get("unpricedHitEvents").asLong());
        }
    }

    private static Snap snap(JsonNode node) {
        return Snap.of(node);
    }

    /** Sums the group entries of a summary response (additive fields only). */
    private static Snap sumGroups(JsonNode summary) {
        long upstream = 0;
        long coalesced = 0;
        long l1 = 0;
        long l2 = 0;
        long input = 0;
        long output = 0;
        BigDecimal upstreamPaid = BigDecimal.ZERO;
        BigDecimal observed = BigDecimal.ZERO;
        BigDecimal saved = BigDecimal.ZERO;
        long unpricedEvents = 0;
        long unavailableEvents = 0;
        long unpricedHits = 0;
        for (JsonNode group : groups(summary)) {
            Snap snap = Snap.of(group);
            upstream += snap.upstream();
            coalesced += snap.coalesced();
            l1 += snap.l1Hit();
            l2 += snap.l2Hit();
            input += snap.inputTokens();
            output += snap.outputTokens();
            upstreamPaid = upstreamPaid.add(snap.upstreamPaid());
            observed = observed.add(snap.gatewayObserved());
            saved = saved.add(snap.savedByGatewayCache());
            unpricedEvents += snap.unpricedEvents();
            unavailableEvents += snap.unavailableEvents();
            unpricedHits += snap.unpricedHitEvents();
        }
        return new Snap(upstream, coalesced, l1, l2, input, output, upstreamPaid, observed, saved, null, unpricedEvents,
                unavailableEvents, unpricedHits);
    }

    // ------------------------------------------------------------------
    // fixture: real rows in the real database
    // ------------------------------------------------------------------

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID prod1 = UUID.randomUUID();
        final UUID prod2 = UUID.randomUUID();
        final UUID projA = UUID.randomUUID();
        final UUID projB = UUID.randomUUID();
        final UUID userA = UUID.randomUUID();
        final UUID userB = UUID.randomUUID();
        final UUID keyA = UUID.randomUUID();
        final UUID keyB = UUID.randomUUID();
        final UUID teamId = UUID.randomUUID();
        final UUID subA = UUID.randomUUID();
        final UUID subB = UUID.randomUUID();
        final UUID credA = UUID.randomUUID();
        final UUID credB = UUID.randomUUID();
        final UUID grantA = UUID.randomUUID();
        final UUID grantB = UUID.randomUUID();

        void seed() {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'adv-provider', 'Adv Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            insertProduct(prod1, "adv-product-1", "Adv Product 1");
            insertProduct(prod2, "adv-product-2", "Adv Product 2");
            insertPrices(prod1, MODEL_PRICED, "1.00", "2.00");
            insertPrices(prod2, MODEL_PRICED, "0.50", "1.00");
            // MODEL_UNPRICED deliberately has no price snapshot anywhere.
            insertProject(projA, "ADVA", "Project A", "adv-a");
            insertProject(projB, "ADVB", "Project B", "adv-b");
            insertUser(userA, "adv_user_a", "Adv User A");
            insertUser(userB, "adv_user_b", "Adv User B");
            insertGrantChain(subA, credA, grantA, projA, prod1);
            insertGrantChain(subB, credB, grantB, projB, prod2);
            insertKey(keyA, "pk-adv-a", userA, projA, grantA, credA, "key-a");
            insertKey(keyB, "pk-adv-b", userB, projB, grantB, credB, "key-b");
            jdbc.update("""
                    INSERT INTO teams (id, tenant_id, name, version)
                    VALUES (:id, :tenantId, 'Adv Team', 0)
                    """, new MapSqlParameterSource("id", teamId).addValue("tenantId", tenantId));
            for (UUID member : List.of(userA, userB)) {
                jdbc.update("""
                        INSERT INTO team_memberships (tenant_id, team_id, user_id)
                        VALUES (:tenantId, :teamId, :userId)
                        """, new MapSqlParameterSource("tenantId", tenantId).addValue("teamId", teamId)
                        .addValue("userId", member));
            }
        }

        /** The credential/grant chain the virtual_keys consistency trigger demands. */
        private void insertGrantChain(UUID subscriptionId, UUID credentialId, UUID grantId, UUID projectId,
                UUID productId) {
            jdbc.update("""
                    INSERT INTO upstream_subscriptions
                        (id, tenant_id, provider_product_id, name, billing_mode, status, version)
                    VALUES (:id, :tenantId, :productId, 'Sub', 'PAYG', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", subscriptionId).addValue("tenantId", tenantId)
                    .addValue("productId", productId));
            jdbc.update("""
                    INSERT INTO upstream_credentials (id, tenant_id, subscription_id, credential_name, status, version)
                    VALUES (:id, :tenantId, :subscriptionId, 'Cred', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", credentialId).addValue("tenantId", tenantId)
                    .addValue("subscriptionId", subscriptionId));
            jdbc.update("""
                    INSERT INTO project_provider_grants
                        (id, tenant_id, project_id, provider_product_id, upstream_credential_id, status, created_by,
                         version)
                    VALUES (:id, :tenantId, :projectId, :productId, :credentialId, 'ACTIVE',
                            '00000000-0000-0000-0000-000000000000', 0)
                    """,
                    new MapSqlParameterSource("id", grantId).addValue("tenantId", tenantId)
                            .addValue("projectId", projectId).addValue("productId", productId)
                            .addValue("credentialId", credentialId));
        }

        private void insertProduct(UUID productId, String code, String name) {
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:id, :providerId, :code, :name, 'PAYG', 'SINGLE_SHARED', '["messages"]',
                            '[{"url":"https://api.adv.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, new MapSqlParameterSource("id", productId).addValue("providerId", providerId)
                    .addValue("code", code).addValue("name", name));
        }

        private void insertPrices(UUID productId, String model, String input, String output) {
            Instant effective = Instant.now().minus(3, ChronoUnit.DAYS);
            for (Map.Entry<String, String> price : List.of(Map.entry("INPUT", input), Map.entry("OUTPUT", output))) {
                jdbc.update("""
                        INSERT INTO price_snapshot (id, provider_product_id, model_id, token_type, currency,
                                                    unit_price, effective_from, source)
                        VALUES (gen_random_uuid(), :productId, :model, :type, 'CNY', :price, :effective, 'MANUAL')
                        """,
                        new MapSqlParameterSource("productId", productId).addValue("model", model)
                                .addValue("type", price.getKey()).addValue("price", new BigDecimal(price.getValue()))
                                .addValue("effective", Timestamp.from(effective)));
            }
        }

        private void insertProject(UUID projectId, String code, String name, String tag) {
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:id, :tenantId, :code, :name, 'ACTIVE', :tag, 0)
                    """, new MapSqlParameterSource("id", projectId).addValue("tenantId", tenantId)
                    .addValue("code", code).addValue("name", name).addValue("tag", tag));
        }

        private void insertUser(UUID userId, String username, String displayName) {
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                       must_change_password, version)
                    VALUES (:id, :tenantId, :username, :displayName, :hash, 'USER', 'ACTIVE', FALSE, 0)
                    """,
                    new MapSqlParameterSource("id", userId).addValue("tenantId", tenantId)
                            .addValue("username", username).addValue("displayName", displayName)
                            .addValue("hash", passwordHasher.hash("NewSecurePass1!")));
        }

        private void insertKey(UUID keyId, String publicKeyId, UUID userId, UUID projectId, UUID grantId,
                UUID credentialId, String name) {
            jdbc.update("""
                    INSERT INTO virtual_keys
                        (id, tenant_id, public_key_id, secret_digest, display_prefix, last_four, user_id, project_id,
                         grant_id, upstream_credential_id, purpose, name, cache_policy, status, version)
                    VALUES (:id, :tenantId, :publicKeyId, decode('00', 'hex'), 'pre', '0001', :userId, :projectId,
                            :grantId, :credentialId, 'CLAUDE_CODE', :name, 'DISABLED', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", keyId).addValue("tenantId", tenantId)
                    .addValue("publicKeyId", publicKeyId).addValue("userId", userId).addValue("projectId", projectId)
                    .addValue("grantId", grantId).addValue("credentialId", credentialId).addValue("name", name));
        }

        void insertUsage(UUID keyId, UUID projectId, String providerRequestId, long input, long output, String model,
                UUID productId, Instant occurredAt, String cacheLevel) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         model_id, cache_level, input_tokens, output_tokens, total_tokens, is_complete,
                         usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :providerRequestId, :keyId, :projectId, :productId, :model, :cacheLevel,
                            :input, :output, :total, TRUE, FALSE, :gatewayId, :occurredAt)
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("providerRequestId", providerRequestId).addValue("keyId", keyId)
                            .addValue("projectId", projectId).addValue("productId", productId).addValue("model", model)
                            .addValue("cacheLevel", cacheLevel).addValue("input", input).addValue("output", output)
                            .addValue("total", input + output).addValue("gatewayId", UUID.randomUUID().toString())
                            .addValue("occurredAt", Timestamp.from(occurredAt)));
        }

        /**
         * One cache entry plus its hit events; the level lives on the events
         * (deduplicated per key+level+second), the cached response's usage on the
         * entry's {@code meta_json} — exactly the shape the gateway writes.
         */
        void insertCacheEntryAndHits(UUID keyId, UUID projectId, UUID productId, String model, long inputTokens,
                long outputTokens, int l1Hits, int l2Hits, Instant l1At, Instant l2At) {
            String keyHex = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
            String meta = "{\"usage\":{\"inputTokens\":" + inputTokens + ",\"outputTokens\":" + outputTokens + "}}";
            jdbc.update("""
                    INSERT INTO cache_entry
                        (id, tenant_id, cache_key, virtual_key_id, project_id, provider_product_id, model_id,
                         status_code, body, meta_json, hit_count_l1, hit_count_l2, created_at, updated_at)
                    VALUES (:id, :tenantId, decode(:keyHex, 'hex'), :keyId, :projectId, :productId, :model, 200,
                            decode('', 'hex'), CAST(:meta AS jsonb), :l1, :l2, now(), now())
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("keyHex", keyHex).addValue("keyId", keyId).addValue("projectId", projectId)
                            .addValue("productId", productId).addValue("model", model).addValue("meta", meta)
                            .addValue("l1", l1Hits).addValue("l2", l2Hits));
            insertHitEvents(keyId, projectId, productId, keyHex, "L1_HIT", l1Hits, l1At);
            insertHitEvents(keyId, projectId, productId, keyHex, "L2_HIT", l2Hits, l2At);
        }

        private void insertHitEvents(UUID keyId, UUID projectId, UUID productId, String keyHex, String level, int hits,
                Instant at) {
            for (int i = 0; i < hits; i++) {
                jdbc.update("""
                        INSERT INTO cache_hit_event
                            (id, tenant_id, cache_key, virtual_key_id, project_id, provider_product_id, level,
                             occurred_at, gateway_request_id, created_at)
                        VALUES (:id, :tenantId, decode(:keyHex, 'hex'), :keyId, :projectId, :productId, :level,
                                :occurredAt, :greq, now())
                        """,
                        new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                                .addValue("keyHex", keyHex).addValue("keyId", keyId).addValue("projectId", projectId)
                                .addValue("productId", productId).addValue("level", level)
                                .addValue("occurredAt", Timestamp.from(at.plusSeconds(i)))
                                .addValue("greq", UUID.randomUUID().toString()));
            }
        }
    }
}
