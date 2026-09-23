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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing/quota semantics of a partially priced window, asserted as the
 * existing contract (no semantics changed here): a COST watermark must carry
 * {@code pricingStatus} + {@code unpriced} rather than pass a lower bound off
 * as a total, and the two endpoints describing the same window — the quota rule
 * view and the usage summary — must agree on that status.
 *
 * <p>
 * The cross-endpoint consistency assertion is the seam: it is the one property
 * that neither endpoint's own tests can see, and a divergence between them is
 * exactly how "unknown" starts reading as "free" on one page but not the other.
 * </p>
 */
@Tag("integration")
@DisplayName("Adversarial: COST quota pricing status matches the usage summary (PostgreSQL)")
class CostQuotaPricingAdversarialIntegrationTest extends AbstractAdversarialIntegrationTest {

    private static final String PRICED_MODEL = "model-alpha";
    private static final String UNPRICED_MODEL = "model-beta";

    private final Fixture fx = new Fixture();

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;
    private UUID adminUserId;

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
        Map<String, Object> bootBody = map(boot);
        adminUserId = UUID.fromString(bootBody.get("userId").toString());
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest(bootBody.get("temporaryPassword").toString(), "NewSecurePass1!"))))
                .andExpect(status().isOk());

        fx.seedCatalogAndKey();
    }

    @Test
    @DisplayName("partially priced window: PARTIAL with the unpriced event counted, on both endpoints")
    void partialPricingIsReportedConsistently() throws Exception {
        fx.insertPrices(new BigDecimal("1.00"), new BigDecimal("2.00")); // 1M in + 0.5M out => 2.00
        fx.insertUsage(fx.keyId, PRICED_MODEL, 1_000_000L, 500_000L);
        fx.insertUsage(fx.keyId, UNPRICED_MODEL, 11L, 7L);

        JsonNode watermark = quotaWatermark(100);
        assertThat(watermark.get("used").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(watermark.get("level").asText()).isEqualTo("NORMAL");
        assertThat(watermark.get("pricingStatus").asText()).isEqualTo("PARTIAL");
        assertThat(watermark.get("unpriced").get("unpricedEvents").asLong()).isEqualTo(1L);

        JsonNode summary = usageSummary();
        assertThat(summary.get("pricingStatus").asText())
                .as("the quota watermark and the usage summary describe the same window the same way")
                .isEqualTo(watermark.get("pricingStatus").asText());
        assertThat(summary.get("unpriced").get("unpricedEvents").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("fully unpriced window: UNAVAILABLE with a zero that is not 'free', on both endpoints")
    void unpricedWindowIsUnavailableNotFree() throws Exception {
        fx.insertUsage(fx.keyId, UNPRICED_MODEL, 1_000_000L, 500_000L);

        JsonNode watermark = quotaWatermark(100);
        assertThat(watermark.get("used").decimalValue()).isEqualByComparingTo("0");
        assertThat(watermark.get("pricingStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(watermark.get("unpriced").get("unpricedEvents").asLong()).isEqualTo(1L);
        assertThat(watermark.get("unpriced").get("inputTokens").asLong()).isEqualTo(1_000_000L);
        assertThat(watermark.get("unpriced").get("outputTokens").asLong()).isEqualTo(500_000L);

        JsonNode summary = usageSummary();
        assertThat(summary.get("pricingStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(summary.get("unpriced").get("unpricedEvents").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("fully priced window: COMPLETE with an all-zero gap and the exact used amount")
    void pricedWindowIsComplete() throws Exception {
        fx.insertPrices(new BigDecimal("1.00"), new BigDecimal("2.00"));
        fx.insertUsage(fx.keyId, PRICED_MODEL, 1_000_000L, 500_000L);

        JsonNode watermark = quotaWatermark(100);
        assertThat(watermark.get("used").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(watermark.get("pricingStatus").asText()).isEqualTo("COMPLETE");
        JsonNode gap = watermark.get("unpriced");
        assertThat(gap.get("unpricedEvents").asLong()).isZero();
        assertThat(gap.get("unavailableEvents").asLong()).isZero();
        assertThat(gap.get("inputTokens").asLong()).isZero();
        assertThat(gap.get("outputTokens").asLong()).isZero();

        JsonNode summary = usageSummary();
        assertThat(summary.get("pricingStatus").asText()).isEqualTo("COMPLETE");
        assertThat(summary.get("cost").get("upstreamPaid").decimalValue()).isEqualByComparingTo("2.00");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** PUTs the COST rule and returns the watermark the response carries. */
    private JsonNode quotaWatermark(long limitValue) throws Exception {
        String body = "{\"scopeType\":\"USER\",\"scopeId\":\"" + adminUserId + "\",\"metric\":\"COST\","
                + "\"period\":\"MONTHLY\",\"limitValue\":" + limitValue + ",\"warnPercent\":80,\"action\":\"REJECT\"}";
        ResultActions put = mockMvc.perform(put("/api/v1/admin/quota-rules").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult result = put.andExpect(status().isOk()).andReturn();
        return body(result);
    }

    /** The same window the monthly quota covers, read through the usage API. */
    private JsonNode usageSummary() throws Exception {
        Instant now = Instant.now();
        MvcResult result = mockMvc.perform(get("/api/v1/admin/usage/summary").cookie(adminSession)
                .param("groupBy", "PROJECT").param("from", now.minus(1, ChronoUnit.DAYS).toString())
                .param("to", now.plus(1, ChronoUnit.HOURS).toString())).andExpect(status().isOk()).andReturn();
        return body(result).get("totals");
    }

    // ------------------------------------------------------------------

    private final class Fixture {
        final UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID providerId = UUID.randomUUID();
        final UUID productId = UUID.randomUUID();
        final UUID projectId = UUID.randomUUID();
        final UUID subscriptionId = UUID.randomUUID();
        final UUID credentialId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        UUID keyId;

        void seedCatalogAndKey() throws Exception {
            jdbc.update("""
                    INSERT INTO providers (id, slug, display_name, status, version)
                    VALUES (:id, 'adv-cost-provider', 'Adv Cost Provider', 'ACTIVE', 0)
                    """, new MapSqlParameterSource("id", providerId));
            jdbc.update("""
                    INSERT INTO provider_products
                        (id, provider_id, product_code, display_name, billing_mode, credential_topology,
                         supported_wire_protocols, base_url_templates, auth_scheme, implementation_status, version)
                    VALUES (:id, :providerId, 'adv-cost-product', 'Adv Cost Product', 'PAYG', 'SINGLE_SHARED',
                            '["messages"]', '[{"url":"https://api.adv.example"}]', '{"type":"bearer"}', 'VERIFIED', 0)
                    """, new MapSqlParameterSource("id", productId).addValue("providerId", providerId));
            jdbc.update("""
                    INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                    VALUES (:id, :tenantId, 'ADVCOST', 'Adv Cost', 'ACTIVE', 'adv-cost', 0)
                    """, new MapSqlParameterSource("id", projectId).addValue("tenantId", tenantId));
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
            jdbc.update("""
                    INSERT INTO project_provider_grant_models (tenant_id, grant_id, model_id)
                    VALUES (:tenantId, :grantId, :model)
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("grantId", grantId).addValue("model",
                    PRICED_MODEL));

            MvcResult created = mockMvc.perform(post("/api/v1/me/virtual-keys").cookie(adminSession, adminCsrf)
                    .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("name", "adv-cost-key", "projectId",
                            projectId.toString(), "providerProductId", productId.toString(), "credentialGrantId",
                            grantId.toString(), "purpose", "CLAUDE_CODE", "allowedModels", List.of(PRICED_MODEL)))))
                    .andExpect(status().isCreated()).andReturn();
            keyId = UUID.fromString(body(created).get("id").asText());
        }

        void insertPrices(BigDecimal input, BigDecimal output) {
            for (Map.Entry<String, BigDecimal> price : List.of(Map.entry("INPUT", input),
                    Map.entry("OUTPUT", output))) {
                jdbc.update("""
                        INSERT INTO price_snapshot (id, provider_product_id, model_id, token_type, currency,
                                                    unit_price, effective_from, source)
                        VALUES (gen_random_uuid(), :productId, :model, :type, 'CNY', :price,
                                now() - interval '1 hour', 'MANUAL')
                        """, new MapSqlParameterSource("productId", productId).addValue("model", PRICED_MODEL)
                        .addValue("type", price.getKey()).addValue("price", price.getValue()));
            }
        }

        void insertUsage(UUID keyId, String model, long input, long output) {
            jdbc.update("""
                    INSERT INTO usage_event
                        (id, tenant_id, provider_request_id, virtual_key_id, project_id, provider_product_id,
                         model_id, cache_level, input_tokens, output_tokens, total_tokens, is_complete,
                         usage_missing, gateway_request_id, occurred_at)
                    VALUES (:id, :tenantId, :providerRequestId, :keyId, :projectId, :productId, :model, 'UPSTREAM',
                            :input, :output, :total, TRUE, FALSE, :gatewayId, now())
                    """,
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                            .addValue("providerRequestId", UUID.randomUUID().toString()).addValue("keyId", keyId)
                            .addValue("projectId", projectId).addValue("productId", productId).addValue("model", model)
                            .addValue("input", input).addValue("output", output).addValue("total", input + output)
                            .addValue("gatewayId", UUID.randomUUID().toString()));
        }
    }
}
