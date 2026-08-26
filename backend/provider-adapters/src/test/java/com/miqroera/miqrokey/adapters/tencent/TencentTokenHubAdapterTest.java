package com.miqroera.miqrokey.adapters.tencent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.spi.AdapterCapabilities;
import com.miqroera.miqrokey.spi.CredentialCheck;
import com.miqroera.miqrokey.spi.CredentialInjection;
import com.miqroera.miqrokey.spi.InboundRequest;
import com.miqroera.miqrokey.spi.ModelCatalogSnapshot;
import com.miqroera.miqrokey.spi.ModelDefinition;
import com.miqroera.miqrokey.spi.PlanDataSource;
import com.miqroera.miqrokey.spi.PlanSnapshot;
import com.miqroera.miqrokey.spi.ProtocolFamily;
import com.miqroera.miqrokey.spi.ProviderClient;
import com.miqroera.miqrokey.spi.ProviderRequest;
import com.miqroera.miqrokey.spi.ProviderResponse;
import com.miqroera.miqrokey.spi.RouteContext;
import com.miqroera.miqrokey.spi.SubscriptionContext;
import com.miqroera.miqrokey.spi.SubscriptionKind;
import com.miqroera.miqrokey.spi.TargetRequest;
import com.miqroera.miqrokey.spi.UsageContext;
import com.miqroera.miqrokey.spi.UsageObserver;
import com.miqroera.miqrokey.spi.UsageSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TencentTokenHubAdapter (G3.2)")
class TencentTokenHubAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Official documented OpenAI-compatible bases (2026-08-25). */
    private static final URI CODING_OPENAI_BASE = URI.create("https://api.lkeap.cloud.tencent.com/coding/v3");
    private static final URI CODING_ANTHROPIC_BASE = URI.create("https://api.lkeap.cloud.tencent.com/coding/anthropic");
    private static final URI PLAN_PERSONAL_BASE = URI.create("https://api.lkeap.cloud.tencent.com/plan/v3");
    private static final URI PLAN_ENTERPRISE_BASE = URI.create("https://tokenhub.tencentmaas.com/plan/v3");
    private static final URI PAYG_BASE = URI.create("https://tokenhub.tencentmaas.com");

    private final TencentTokenHubAdapter codingPlan = TencentTokenHubAdapter.codingPlan(MAPPER);
    private final TencentTokenHubAdapter tokenPlanPersonal = TencentTokenHubAdapter.tokenPlanPersonal(MAPPER);
    private final TencentTokenHubAdapter enterprisePro = TencentTokenHubAdapter.enterprisePro(MAPPER);
    private final TencentTokenHubAdapter enterpriseLite = TencentTokenHubAdapter.enterpriseLite(MAPPER);
    private final TencentTokenHubAdapter paygApi = TencentTokenHubAdapter.paygApi(MAPPER);

    @Test
    @DisplayName("all five adapterIds and protocol sets match the signed catalog")
    void identityAndProtocolsMatchSignedCatalog() {
        assertThat(codingPlan.adapterId()).isEqualTo("tencent-coding-plan");
        assertThat(codingPlan.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.ANTHROPIC_MESSAGES);
        assertThat(tokenPlanPersonal.adapterId()).isEqualTo("tencent-token-plan-personal");
        assertThat(tokenPlanPersonal.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.VENDOR_NATIVE);
        assertThat(enterprisePro.adapterId()).isEqualTo("tencent-token-plan-enterprise-pro");
        assertThat(enterprisePro.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.VENDOR_NATIVE);
        assertThat(enterpriseLite.adapterId()).isEqualTo("tencent-token-plan-enterprise-lite");
        assertThat(enterpriseLite.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.VENDOR_NATIVE);
        assertThat(paygApi.adapterId()).isEqualTo("tencent-payg-api");
        assertThat(paygApi.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.VENDOR_NATIVE);
    }

    @Test
    @DisplayName("resolve strips inbound credential headers and preserves everything else (PAYG)")
    void resolveStripsCredentialsAndPreservesOthers() {
        RouteContext route = route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE);
        InboundRequest request = new InboundRequest("POST", "/v1/chat/completions",
                Map.of("stream", List.of("true"), "q", List.of("a b&c")),
                Map.of("Authorization", List.of("Bearer sk-client-key"), "X-Api-Key", List.of("sk-client-key-2"),
                        "api-key", List.of("sk-client-key-3"), "Content-Type", List.of("application/json"),
                        "X-Trace-Id", List.of("trace-123")));

        TargetRequest target = paygApi.resolve(route, request);

        assertThat(target.method()).isEqualTo("POST");
        assertThat(target.origin()).isEqualTo(PAYG_BASE);
        assertThat(target.path()).isEqualTo("/v1/chat/completions");
        // The decoded query map is re-encoded into a raw query string (pair
        // order is not significant — InboundRequest copies the map unordered).
        assertThat(target.query().split("&")).containsExactlyInAnyOrder("stream=true", "q=a%20b%26c");
        // All three client credential headers are stripped; never forwarded.
        assertThat(target.headers()).doesNotContainKeys("authorization", "x-api-key", "api-key");
        assertThat(target.headers()).containsEntry("content-type", "application/json").containsEntry("x-trace-id",
                "trace-123");
    }

    @Test
    @DisplayName("resolve strips the OpenAI /v1 prefix on /v3-suffixed plan bases and keeps it on PAYG")
    void resolveNormalizesOpenAiV1PrefixOnPlanBases() {
        assertThat(codingPlan
                .resolve(route(CODING_OPENAI_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/v1/chat/completions"))
                .path()).isEqualTo("/chat/completions");
        assertThat(tokenPlanPersonal
                .resolve(route(PLAN_PERSONAL_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/v1/chat/completions"))
                .path()).isEqualTo("/chat/completions");
        assertThat(enterprisePro
                .resolve(route(PLAN_ENTERPRISE_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/v1/chat/completions"))
                .path()).isEqualTo("/chat/completions");
        assertThat(enterpriseLite
                .resolve(route(PLAN_ENTERPRISE_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/v1/chat/completions"))
                .path()).isEqualTo("/chat/completions");
        // PAYG root base keeps OpenAI SDK paths verbatim.
        assertThat(paygApi.resolve(route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/v1/chat/completions"))
                .path()).isEqualTo("/v1/chat/completions");
        // A path without the /v1 prefix is never touched.
        assertThat(codingPlan
                .resolve(route(CODING_OPENAI_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/chat/completions"))
                .path()).isEqualTo("/chat/completions");
    }

    @Test
    @DisplayName("resolve keeps Anthropic Messages paths verbatim on Coding Plan")
    void resolveKeepsAnthropicMessagesPath() {
        TargetRequest target = codingPlan.resolve(route(CODING_ANTHROPIC_BASE, ProtocolFamily.ANTHROPIC_MESSAGES),
                request("/v1/messages"));
        assertThat(target.origin()).isEqualTo(CODING_ANTHROPIC_BASE);
        assertThat(target.path()).isEqualTo("/v1/messages");
    }

    @Test
    @DisplayName("resolve normalizes the models path per product")
    void resolveNormalizesModelsPath() {
        assertThat(codingPlan
                .resolve(route(CODING_OPENAI_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/v1/models")).path())
                .isEqualTo("/models");
        assertThat(paygApi.resolve(route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/v1/models")).path())
                .isEqualTo("/v1/models");
    }

    @Test
    @DisplayName("resolve returns an empty query string when the inbound request has none")
    void resolveEmptyQuery() {
        TargetRequest target = paygApi.resolve(route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE),
                new InboundRequest("GET", "/v1/models", Map.of(), Map.of("X-Trace-Id", List.of("t1"))));
        assertThat(target.query()).isEmpty();
    }

    @Test
    @DisplayName("credentialInjection declares Bearer Authorization and the strip set")
    void credentialInjectionContract() {
        CredentialInjection injection = codingPlan.credentialInjection(null);
        assertThat(injection.headerName()).isEqualTo("Authorization");
        assertThat(injection.prefix()).isEqualTo("Bearer ");
        assertThat(injection.stripInboundHeaders()).containsExactlyInAnyOrder("authorization", "x-api-key", "api-key");
    }

    @Test
    @DisplayName("validateCredential probes the normalized models path per product")
    void validateCredentialProbesNormalizedModelsPath() {
        assertProbePath(paygApi, "/v1/models");
        assertProbePath(codingPlan, "/models");
        assertProbePath(tokenPlanPersonal, "/models");
        assertProbePath(enterprisePro, "/models");
        assertProbePath(enterpriseLite, "/models");
    }

    @Test
    @DisplayName("validateCredential maps 401/403 to credential-rejected and 429 to rate-limited")
    void validateCredentialRejectionReasons() {
        assertThat(paygApi.validateCredential(new StubClient(response(401, "unauthorized"))).block().message())
                .isEqualTo("credential rejected by Tencent Cloud API");
        assertThat(paygApi.validateCredential(new StubClient(response(403, "forbidden"))).block().message())
                .isEqualTo("credential rejected by Tencent Cloud API");
        assertThat(paygApi.validateCredential(new StubClient(response(429, "rate limit"))).block().message())
                .isEqualTo("rate limited by Tencent Cloud API");
        assertThat(paygApi.validateCredential(new StubClient(response(500, "boom"))).block().message())
                .isEqualTo("Tencent Cloud API returned HTTP 500");
        assertThat(paygApi.validateCredential(new StubClient(response(401, "unauthorized"))).block().valid()).isFalse();
    }

    @Test
    @DisplayName("fetchModels parses the documented TokenHub shape (id + name) and tolerates unknown fields")
    void fetchModelsParsesTokenHubShape() {
        String body = "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"hy3\",\"object\":\"model\",\"name\":\"Hy3\",\"created\":1710000000,"
                + "\"status\":\"online\",\"unknown_future_field\":true},"
                + "{\"id\":\"glm-5\",\"object\":\"model\",\"name\":\"GLM-5\",\"status\":\"pre-offline\"},"
                + "{\"id\":\"kimi-k2.5\",\"object\":\"model\"},{\"created\":12345}],\"unknown_future_field\":true}";
        StubClient client = new StubClient(new ProviderResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8)));

        ModelCatalogSnapshot snapshot = paygApi.fetchModels(client).block();

        assertThat(client.requests.get(0).path()).isEqualTo("/v1/models");
        assertThat(snapshot.providerProductId()).isEqualTo("tencent-payg-api");
        assertThat(snapshot.fetchedAt()).isNotNull();
        assertThat(snapshot.models()).containsExactly(new ModelDefinition("hy3", "Hy3"),
                new ModelDefinition("glm-5", "GLM-5"), new ModelDefinition("kimi-k2.5"));
    }

    @Test
    @DisplayName("fetchModels tolerates the OpenAI display_name variant")
    void fetchModelsToleratesDisplayNameVariant() {
        String body = "{\"data\":[{\"id\":\"deepseek-v4-pro\",\"display_name\":\"DeepSeek-V4-Pro\"}]}";
        ModelCatalogSnapshot snapshot = paygApi
                .fetchModels(new StubClient(new ProviderResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8))))
                .block();
        assertThat(snapshot.models()).containsExactly(new ModelDefinition("deepseek-v4-pro", "DeepSeek-V4-Pro"));
    }

    @Test
    @DisplayName("fetchModels fails on non-2xx and on unparseable bodies; empty data yields an empty catalog")
    void fetchModelsFailureModes() {
        assertThatThrownBy(() -> paygApi.fetchModels(new StubClient(response(500, "boom"))).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/v1/models")
                .hasMessageContaining("500");
        assertThatThrownBy(() -> paygApi.fetchModels(new StubClient(response(200, "<html>not json</html>"))).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not parseable");
        ModelCatalogSnapshot empty = paygApi.fetchModels(new StubClient(response(200, "{\"data\": \"not-an-array\"}")))
                .block();
        assertThat(empty.models()).isEmpty();
    }

    @Test
    @DisplayName("fetchPlanStatus returns UNAVAILABLE without any HTTP call for all five products")
    void fetchPlanStatusUnavailableWithoutHttpCall() {
        // Tencent documents no balance/usage API (console only); the adapter
        // must not invent values and must not call a nonexistent endpoint.
        assertUnavailable(paygApi, SubscriptionKind.PAYG, false);
        assertUnavailable(codingPlan, SubscriptionKind.INDIVIDUAL_PLAN, false);
        assertUnavailable(tokenPlanPersonal, SubscriptionKind.INDIVIDUAL_PLAN, false);
        assertUnavailable(enterprisePro, SubscriptionKind.ENTERPRISE_PLAN, true);
        assertUnavailable(enterpriseLite, SubscriptionKind.ENTERPRISE_PLAN, true);
    }

    @Test
    @DisplayName("capabilities declare streaming, model discovery and plan kinds; no balance or request id")
    void capabilitiesPerProduct() {
        assertCapabilities(paygApi, false, false);
        assertCapabilities(codingPlan, true, false);
        assertCapabilities(tokenPlanPersonal, true, false);
        assertCapabilities(enterprisePro, true, true);
        assertCapabilities(enterpriseLite, true, true);
    }

    @Test
    @DisplayName("createUsageObserver returns a Tencent observer bound to the context")
    void usageObserverIsBoundToContext() {
        UsageContext context = new UsageContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "gw-req-1", null, ProtocolFamily.OPENAI_COMPATIBLE,
                Instant.now());

        UsageObserver observer = paygApi.createUsageObserver(context);

        assertThat(observer).isInstanceOf(TencentUsageObserver.class);
        assertThat(((TencentUsageObserver) observer).context()).isSameAs(context);
    }

    private static RouteContext route(URI base, ProtocolFamily family) {
        return new RouteContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), family, base);
    }

    private static InboundRequest request(String path) {
        return new InboundRequest("POST", path, Map.of(), Map.of("Content-Type", List.of("application/json")));
    }

    private void assertProbePath(TencentTokenHubAdapter adapter, String expectedPath) {
        StubClient client = new StubClient(
                new ProviderResponse(200, Map.of(), "{\"data\":[]}".getBytes(StandardCharsets.UTF_8)));
        CredentialCheck check = adapter.validateCredential(client).block();
        assertThat(client.requests).hasSize(1);
        assertThat(client.requests.get(0).method()).isEqualTo("GET");
        assertThat(client.requests.get(0).path()).isEqualTo(expectedPath);
        assertThat(client.requests.get(0).query()).isEmpty();
        assertThat(check.valid()).isTrue();
        assertThat(check.message()).isNull();
        assertThat(check.checkedAt()).isNotNull();
    }

    private void assertUnavailable(TencentTokenHubAdapter adapter, SubscriptionKind kind, boolean sharedPool) {
        StubClient client = new StubClient(response(200, "{}"));
        PlanSnapshot snapshot = adapter.fetchPlanStatus(client, new SubscriptionContext(UUID.randomUUID(), kind, null))
                .block();
        assertThat(client.requests).isEmpty();
        assertThat(snapshot.kind()).isEqualTo(kind);
        assertThat(snapshot.total()).isNull();
        assertThat(snapshot.used()).isNull();
        assertThat(snapshot.remaining()).isNull();
        assertThat(snapshot.periodStart()).isNull();
        assertThat(snapshot.periodEnd()).isNull();
        assertThat(snapshot.sharedPool()).isEqualTo(sharedPool);
        assertThat(snapshot.source()).isEqualTo(PlanDataSource.UNAVAILABLE);
        assertThat(snapshot.fetchedAt()).isNotNull();
    }

    private void assertCapabilities(TencentTokenHubAdapter adapter, boolean plan, boolean teamPlan) {
        AdapterCapabilities capabilities = adapter.capabilities();
        assertThat(capabilities.streaming()).isTrue();
        assertThat(capabilities.modelDiscovery()).isTrue();
        assertThat(capabilities.balance()).isFalse();
        assertThat(capabilities.plan()).isEqualTo(plan);
        assertThat(capabilities.teamPlan()).isEqualTo(teamPlan);
        assertThat(capabilities.requestId()).isFalse();
        assertThat(capabilities.usageLocation()).isEqualTo(UsageSource.PROVIDER_RESPONSE);
    }

    private static ProviderResponse response(int status, String body) {
        return new ProviderResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Fake {@link ProviderClient} recording requests and returning one response.
     */
    private static final class StubClient implements ProviderClient {

        private final ProviderResponse response;
        private final List<ProviderRequest> requests = new ArrayList<>();

        StubClient(ProviderResponse response) {
            this.response = response;
        }

        @Override
        public Mono<ProviderResponse> exchange(ProviderRequest request) {
            requests.add(request);
            return Mono.just(response);
        }
    }
}
