package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.controller.AdminProviderApiIntegrationTest.BootstrapHelper;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.repository.PriceSnapshotRepository;
import com.miqroera.miqrokey.domain.usage.PriceTokenType;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Freezing the price basis onto history (#710 / F21-A) against real PostgreSQL.
 *
 * <p>
 * The property that carries the whole feature is <b>"priced as of the event,
 * not as of now"</b>, so the fixture deliberately plants a later price change:
 * a backfill that looked at current prices would pick it up and fail.
 * </p>
 *
 * <p>
 * The second property is that "no price" must stay distinguishable from "free"
 * — an unpriced row keeps NULL prices and is marked UNAVAILABLE, never zero.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Usage price backfill integration tests (PostgreSQL)")
class UsagePriceBackfillIntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROVIDER = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final String MODEL = "backfill-model";
    private static final String OTHER_MODEL = "backfill-unpriced-model";
    private static final String ZERO_TOKEN_MODEL = "backfill-zero-token-model";

    /** Event happened two hours ago. */
    private static final Instant EVENT_AT = Instant.now().minusSeconds(2 * 3600);
    /** Price in force at the event: three hours ago. */
    private static final Instant PRICE_BEFORE_EVENT = Instant.now().minusSeconds(3 * 3600);
    /** A price change *after* the event: one hour ago. Must not be picked up. */
    private static final Instant PRICE_AFTER_EVENT = Instant.now().minusSeconds(3600);

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
    @Autowired
    PriceSnapshotRepository priceSnapshotRepository;

    private Cookie sessionCookie;
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        reset();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(BootstrapHelper.secret(),
                                "bf_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
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

        jdbc.update("""
                INSERT INTO providers (id, slug, display_name, status, version)
                VALUES (:id, 'backfill-provider', 'Backfill Provider', 'ACTIVE', 0)
                """, new MapSqlParameterSource("id", PROVIDER));
        jdbc.update("""
                INSERT INTO provider_products
                    (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                     supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                VALUES (:id, :providerId, 'backfill-product', 'Backfill Product', 'PAYG', 'SINGLE_SHARED',
                        '["messages"]', '[{"url":"https://api.test.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                """, new MapSqlParameterSource("id", PRODUCT).addValue("providerId", PROVIDER));
    }

    @Test
    @DisplayName("prices as of the event, not as of now — a later price change must not leak in")
    void pricesAsOfTheEventNotNow() throws Exception {
        seedEvent(MODEL, EVENT_AT);
        priceAllDimensions(MODEL, PRICE_BEFORE_EVENT, "1.00", "4.00", "0.10", "0.20");
        // The decoy: cheaper/dearer prices that only became effective after the event.
        priceAllDimensions(MODEL, PRICE_AFTER_EVENT, "9.99", "9.99", "9.99", "9.99");

        Map<String, Object> result = backfill();
        assertThat(result.get("scanned")).isEqualTo(1);
        assertThat(result.get("complete")).isEqualTo(1);
        assertThat(result.get("unavailable")).isEqualTo(0);

        Map<String, Object> row = priceColumnsOf(MODEL);
        assertThat(row.get("price_input")).isEqualTo(new BigDecimal("1.0000000000"));
        assertThat(row.get("price_output")).isEqualTo(new BigDecimal("4.0000000000"));
        assertThat(row.get("price_status")).isEqualTo("COMPLETE");
        assertThat(row.get("price_effective_from")).isNotNull();
    }

    @Test
    @DisplayName("the backfill's verdict matches the repository's as-of lookup for the same row")
    void agreesWithTheRepositoryLookup() throws Exception {
        seedEvent(MODEL, EVENT_AT);
        priceAllDimensions(MODEL, PRICE_BEFORE_EVENT, "1.00", "4.00", "0.10", "0.20");

        backfill();

        // The tie-break rule is written in two places (repository + backfill SQL); this
        // is
        // what keeps them honest, rather than a comment asking future readers to be
        // careful.
        BigDecimal fromRepository = priceSnapshotRepository.findLatestAt(PRODUCT, MODEL, PriceTokenType.INPUT, EVENT_AT)
                .map(p -> p.unitPrice()).orElseThrow();
        assertThat(priceColumnsOf(MODEL).get("price_input")).isEqualTo(fromRepository);
    }

    @Test
    @DisplayName("an unpriced row is UNAVAILABLE with NULL prices — never a zero price")
    void unpricedRowStaysUnavailableAndIsNotZero() throws Exception {
        seedEvent(OTHER_MODEL, EVENT_AT);

        Map<String, Object> result = backfill();
        assertThat(result.get("unavailable")).isEqualTo(1);
        assertThat(result.get("complete")).isEqualTo(0);

        Map<String, Object> row = priceColumnsOf(OTHER_MODEL);
        assertThat(row.get("price_status")).isEqualTo("UNAVAILABLE");
        // "price unknown" must not be recorded as "free".
        assertThat(row.get("price_input")).isNull();
        assertThat(row.get("price_output")).isNull();
        assertThat(row.get("price_effective_from")).isNull();
    }

    @Test
    @DisplayName("only some dimensions priced yields PARTIAL, and the missing ones stay NULL")
    void partiallyPricedRowIsPartial() throws Exception {
        seedEvent(MODEL, EVENT_AT);
        price(MODEL, "INPUT", PRICE_BEFORE_EVENT, "1.00");

        Map<String, Object> result = backfill();
        assertThat(result.get("partial")).isEqualTo(1);

        Map<String, Object> row = priceColumnsOf(MODEL);
        assertThat(row.get("price_status")).isEqualTo("PARTIAL");
        assertThat(row.get("price_input")).isEqualTo(new BigDecimal("1.0000000000"));
        assertThat(row.get("price_output")).isNull();
    }

    @Test
    @DisplayName("re-running is a no-op: already-decided rows are never re-evaluated")
    void secondRunDoesNothing() throws Exception {
        seedEvent(OTHER_MODEL, EVENT_AT);

        assertThat(backfill().get("scanned")).isEqualTo(1);
        // Not even the UNAVAILABLE row is revisited — re-running must not be able to
        // revise a decision that was already made.
        assertThat(backfill().get("scanned")).isEqualTo(0);
    }

    @Test
    @DisplayName("a price that becomes effective later does not retroactively price an event")
    void pricePublishedAfterTheEventDoesNotApply() throws Exception {
        seedEvent(MODEL, EVENT_AT);
        // The event happened before any price existed.
        priceAllDimensions(MODEL, PRICE_AFTER_EVENT, "1.00", "4.00", "0.10", "0.20");

        backfill();

        Map<String, Object> row = priceColumnsOf(MODEL);
        assertThat(row.get("price_status")).isEqualTo("UNAVAILABLE");
        assertThat(row.get("price_input")).isNull();
    }

    @Test
    @DisplayName("the base cost is frozen, and an unpriced event stays NULL rather than becoming 0")
    void baseCostIsFrozenAndNeverFaked() throws Exception {
        seedEvent(MODEL, EVENT_AT);
        seedEvent(OTHER_MODEL, EVENT_AT);
        priceAllDimensions(MODEL, PRICE_BEFORE_EVENT, "1.00", "4.00", "0.10", "0.20");

        backfill();

        // (1000 x 1.00 + 500 x 4.00) / 1e6
        assertThat(priceColumnsOf(MODEL).get("base_cost_amount")).isEqualTo(new BigDecimal("0.0030000000"));
        // The unpriced event must NOT be recorded as free: NULL is the fact "we could
        // not price
        // this", and a 0 here would be indistinguishable from a genuine zero price.
        assertThat(priceColumnsOf(OTHER_MODEL).get("base_cost_amount")).isNull();
    }

    @Test
    @DisplayName("a dimension the event never used does not make it partial")
    void unusedDimensionDoesNotMakeRowPartial() throws Exception {
        // The shape every event has on a catalogue that simply has no cache_creation
        // price:
        // input/output priced, and no cache tokens at all. Judging by price
        // availability alone
        // called this PARTIAL — and flagged a dimension that never took part in the
        // sum.
        seedEvent(MODEL, EVENT_AT);
        price(MODEL, "INPUT", PRICE_BEFORE_EVENT, "1.00");
        price(MODEL, "OUTPUT", PRICE_BEFORE_EVENT, "4.00");

        backfill();

        Map<String, Object> row = priceColumnsOf(MODEL);
        assertThat(row.get("price_status")).isEqualTo("COMPLETE");
        // And the base cost covers both priced dimensions.
        assertThat(row.get("base_cost_amount")).isEqualTo(new BigDecimal("0.0030000000"));
    }

    @Test
    @DisplayName("a dimension the event DID use, but has no price, makes it partial")
    void usedButUnpricedDimensionMakesRowPartial() throws Exception {
        jdbc.update("""
                INSERT INTO usage_event (id, tenant_id, virtual_key_id, project_id, provider_product_id, model_id,
                    gateway_request_id, input_tokens, output_tokens, cache_creation_input_tokens,
                    is_complete, usage_missing, occurred_at)
                VALUES (:id, :tenantId, :keyId, :projectId, :productId, :modelId,
                    'gw-cache-create', 1000, 500, 200, TRUE, FALSE, :occurredAt)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT)
                        .addValue("keyId", UUID.randomUUID()).addValue("projectId", UUID.randomUUID())
                        .addValue("productId", PRODUCT).addValue("modelId", MODEL)
                        .addValue("occurredAt", java.sql.Timestamp.from(EVENT_AT)));
        price(MODEL, "INPUT", PRICE_BEFORE_EVENT, "1.00");
        price(MODEL, "OUTPUT", PRICE_BEFORE_EVENT, "4.00");

        backfill();

        // cache_creation tokens exist and have no price, so this one really is short.
        assertThat(priceColumnsOf(MODEL).get("price_status")).isEqualTo("PARTIAL");
    }

    // -------------------------------------------------------------------
    // #771: rows stamped before base_cost_amount existed

    @Test
    @DisplayName("completes the base cost of rows stamped before the column existed (#771)")
    void completesBaseCostOfRowsStampedBeforeTheColumnExisted() throws Exception {
        seedStampedEvent(MODEL, "COMPLETE", 1000L, 500L, "1.00", "4.00");

        Map<String, Object> result = backfill();

        // Nothing was re-evaluated — there was no decision left to make, only an
        // amount left to write.
        assertThat(result.get("scanned")).isEqualTo(0);
        assertThat(result.get("baseCostFilled")).isEqualTo(1);
        Map<String, Object> row = priceColumnsOf(MODEL);
        // (1000 x 1.00 + 500 x 4.00) / 1e6
        assertThat(row.get("base_cost_amount")).isEqualTo(new BigDecimal("0.0030000000"));
        // Completing the record must not revise it.
        assertThat(row.get("price_status")).isEqualTo("COMPLETE");
        assertThat(row.get("price_input")).isEqualTo(new BigDecimal("1.0000000000"));
    }

    @Test
    @DisplayName("the completion derives from the row's frozen prices, not from the catalogue")
    void completionDerivesFromFrozenPricesNotTheCatalogue() throws Exception {
        seedStampedEvent(MODEL, "COMPLETE", 1000L, 500L, "1.00", "4.00");
        // Published only now: re-deriving from the catalogue would pick this up and
        // silently inflate an amount whose whole point is to be frozen.
        priceAllDimensions(MODEL, PRICE_AFTER_EVENT, "9.99", "9.99", "9.99", "9.99");

        backfill();

        assertThat(priceColumnsOf(MODEL).get("base_cost_amount")).isEqualTo(new BigDecimal("0.0030000000"));
    }

    @Test
    @DisplayName("rows that cannot be priced keep NULL — the completion never invents a zero")
    void completionLeavesUnpriceableRowsNull() throws Exception {
        // UNAVAILABLE: its NULL records that no price was in force.
        seedStampedEvent(OTHER_MODEL, "UNAVAILABLE", 1000L, 500L, null, null);
        // COMPLETE with no tokens at all: nothing to sum, so nothing derivable — and
        // it must not be re-selected for ever.
        seedStampedEvent(ZERO_TOKEN_MODEL, "COMPLETE", null, null, null, null);

        Map<String, Object> result = backfill();

        assertThat(result.get("baseCostFilled")).isEqualTo(0);
        assertThat(priceColumnsOf(OTHER_MODEL).get("base_cost_amount")).isNull();
        assertThat(priceColumnsOf(ZERO_TOKEN_MODEL).get("base_cost_amount")).isNull();
    }

    @Test
    @DisplayName("the completion is idempotent: a second pass has nothing left to do")
    void completionIsIdempotent() throws Exception {
        seedStampedEvent(MODEL, "COMPLETE", 1000L, 500L, "1.00", "4.00");

        assertThat(backfill().get("baseCostFilled")).isEqualTo(1);
        assertThat(backfill().get("baseCostFilled")).isEqualTo(0);
    }

    // -------------------------------------------------------------------

    private Map<String, Object> backfill() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/usage-price-backfill")
                .param("from", Instant.now().minusSeconds(86400).toString()).param("to", Instant.now().toString())
                .cookie(sessionCookie, csrfCookie).header("X-CSRF-Token", csrfToken)).andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(res.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    private void seedEvent(String model, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO usage_event (id, tenant_id, virtual_key_id, project_id, provider_product_id, model_id,
                    gateway_request_id, input_tokens, output_tokens, is_complete, usage_missing, occurred_at)
                VALUES (:id, :tenantId, :keyId, :projectId, :productId, :modelId,
                    :requestId, 1000, 500, TRUE, FALSE, :occurredAt)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT)
                        .addValue("keyId", UUID.randomUUID()).addValue("projectId", UUID.randomUUID())
                        .addValue("productId", PRODUCT).addValue("modelId", model).addValue("requestId", "gw-" + model)
                        .addValue("occurredAt", java.sql.Timestamp.from(occurredAt)));
    }

    private void priceAllDimensions(String model, Instant effectiveFrom, String input, String output, String cacheRead,
            String cacheCreation) {
        price(model, "INPUT", effectiveFrom, input);
        price(model, "OUTPUT", effectiveFrom, output);
        price(model, "CACHE_READ", effectiveFrom, cacheRead);
        price(model, "CACHE_CREATION", effectiveFrom, cacheCreation);
    }

    private void price(String model, String tokenType, Instant effectiveFrom, String unitPrice) {
        jdbc.update("""
                INSERT INTO price_snapshot
                    (id, provider_product_id, model_id, token_type, currency, unit_price, effective_from, source)
                VALUES (:id, :productId, :modelId, :tokenType, 'CNY', :unitPrice, :effectiveFrom, 'MANUAL')
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("productId", PRODUCT)
                        .addValue("modelId", model).addValue("tokenType", tokenType)
                        .addValue("unitPrice", new BigDecimal(unitPrice))
                        .addValue("effectiveFrom", java.sql.Timestamp.from(effectiveFrom)));
    }

    private Map<String, Object> priceColumnsOf(String model) {
        return jdbc.queryForMap("""
                SELECT price_input, price_output, price_status, price_effective_from, base_cost_amount
                  FROM usage_event WHERE tenant_id = :tenantId AND model_id = :modelId
                """, new MapSqlParameterSource("tenantId", TENANT).addValue("modelId", model));
    }

    /**
     * A row in the state #771 leaves behind: the price basis was frozen, but the
     * frozen <em>amount</em> is missing because the column did not exist yet.
     */
    private void seedStampedEvent(String model, String status, Long inputTokens, Long outputTokens, String priceInput,
            String priceOutput) {
        jdbc.update("""
                INSERT INTO usage_event (id, tenant_id, virtual_key_id, project_id, provider_product_id, model_id,
                    gateway_request_id, input_tokens, output_tokens, is_complete, usage_missing, occurred_at,
                    price_input, price_output, price_currency, price_effective_from, price_source, price_status)
                VALUES (:id, :tenantId, :keyId, :projectId, :productId, :modelId,
                    :requestId, :inputTokens, :outputTokens, TRUE, FALSE, :occurredAt,
                    :priceInput, :priceOutput, 'CNY', :occurredAt, 'MANUAL', :status)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", TENANT)
                        .addValue("keyId", UUID.randomUUID()).addValue("projectId", UUID.randomUUID())
                        .addValue("productId", PRODUCT).addValue("modelId", model).addValue("requestId", "gw-" + model)
                        .addValue("inputTokens", inputTokens).addValue("outputTokens", outputTokens)
                        .addValue("occurredAt", java.sql.Timestamp.from(EVENT_AT))
                        .addValue("priceInput", priceInput == null ? null : new BigDecimal(priceInput))
                        .addValue("priceOutput", priceOutput == null ? null : new BigDecimal(priceOutput))
                        .addValue("status", status));
    }

    /** Child-first; usage rows precede the price rows they reference. */
    private void reset() {
        for (String table : List.of("usage_event", "price_snapshot", "provider_products", "providers",
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
}
