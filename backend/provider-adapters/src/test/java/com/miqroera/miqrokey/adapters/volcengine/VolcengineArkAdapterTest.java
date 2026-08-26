package com.miqroera.miqrokey.adapters.volcengine;

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

@DisplayName("VolcengineArkAdapter (G3.7)")
class VolcengineArkAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Official documented bases (2026-08-26; Agent Plan endpoints confirmed via the
     * cc-switch community preset, pending real-credential checks).
     */
    private static final URI CODING_ANTHROPIC_BASE = URI.create("https://ark.cn-beijing.volces.com/api/coding");
    private static final URI CODING_OPENAI_BASE = URI.create("https://ark.cn-beijing.volces.com/api/coding/v3");
    private static final URI PLAN_ANTHROPIC_BASE = URI.create("https://ark.cn-beijing.volces.com/api/plan");
    private static final URI PLAN_OPENAI_BASE = URI.create("https://ark.cn-beijing.volces.com/api/plan/v3");
    private static final URI PAYG_BASE = URI.create("https://ark.cn-beijing.volces.com/api/v3");

    private final VolcengineArkAdapter codingPlan = VolcengineArkAdapter.codingPlan(MAPPER);
    private final VolcengineArkAdapter agentPlan = VolcengineArkAdapter.agentPlan(MAPPER);
    private final VolcengineArkAdapter payg = VolcengineArkAdapter.paygApi(MAPPER);

    @Test
    @DisplayName("all three adapterIds and protocol sets match the signed catalog")
    void identityAndProtocolsMatchSignedCatalog() {
        assertThat(codingPlan.adapterId()).isEqualTo("volcengine-coding-plan");
        assertThat(codingPlan.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.ANTHROPIC_MESSAGES);
        assertThat(agentPlan.adapterId()).isEqualTo("volcengine-agent-plan");
        assertThat(agentPlan.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.ANTHROPIC_MESSAGES);
        assertThat(payg.adapterId()).isEqualTo("volcengine-payg-api");
        assertThat(payg.protocols()).containsExactly(ProtocolFamily.OPENAI_COMPATIBLE);
    }

    @Test
    @DisplayName("resolve strips inbound credential headers and preserves everything else")
    void resolveStripsCredentialsAndPreservesOthers() {
        RouteContext route = route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE);
        InboundRequest request = new InboundRequest("POST", "/v1/chat/completions",
                Map.of("stream", List.of("true"), "q", List.of("a b&c")),
                Map.of("Authorization", List.of("Bearer sk-client-key"), "X-Api-Key", List.of("sk-client-key-2"),
                        "api-key", List.of("sk-client-key-3"), "Content-Type", List.of("application/json"),
                        "X-Trace-Id", List.of("trace-123")));

        TargetRequest target = payg.resolve(route, request);

        assertThat(target.method()).isEqualTo("POST");
        assertThat(target.origin()).isEqualTo(PAYG_BASE);
        assertThat(target.path()).isEqualTo("/chat/completions");
        // The decoded query map is re-encoded into a raw query string (pair
        // order is not significant — InboundRequest copies the map unordered).
        assertThat(target.query().split("&")).containsExactlyInAnyOrder("stream=true", "q=a%20b%26c");
        assertThat(target.headers()).doesNotContainKeys("authorization", "x-api-key", "api-key");
        assertThat(target.headers()).containsEntry("content-type", "application/json").containsEntry("x-trace-id",
                "trace-123");
    }

    @Test
    @DisplayName("resolve maps documented bases to the official full endpoints")
    void resolveOfficialEndpointMapping() {
        // Coding Plan Anthropic/OpenAI (official docs).
        TargetRequest codingAnthropic = codingPlan
                .resolve(route(CODING_ANTHROPIC_BASE, ProtocolFamily.ANTHROPIC_MESSAGES), request("/v1/messages"));
        assertThat(codingAnthropic.origin()).isEqualTo(CODING_ANTHROPIC_BASE);
        assertThat(codingAnthropic.path()).isEqualTo("/v1/messages");
        TargetRequest codingOpenAi = codingPlan.resolve(route(CODING_OPENAI_BASE, ProtocolFamily.OPENAI_COMPATIBLE),
                request("/v1/chat/completions"));
        assertThat(codingOpenAi.origin()).isEqualTo(CODING_OPENAI_BASE);
        assertThat(codingOpenAi.path()).isEqualTo("/chat/completions");

        // Agent Plan Anthropic/OpenAI (community-confirmed endpoints).
        TargetRequest planAnthropic = agentPlan.resolve(route(PLAN_ANTHROPIC_BASE, ProtocolFamily.ANTHROPIC_MESSAGES),
                request("/v1/messages"));
        assertThat(planAnthropic.origin()).isEqualTo(PLAN_ANTHROPIC_BASE);
        assertThat(planAnthropic.path()).isEqualTo("/v1/messages");
        TargetRequest planOpenAi = agentPlan.resolve(route(PLAN_OPENAI_BASE, ProtocolFamily.OPENAI_COMPATIBLE),
                request("/v1/chat/completions"));
        assertThat(planOpenAi.origin()).isEqualTo(PLAN_OPENAI_BASE);
        assertThat(planOpenAi.path()).isEqualTo("/chat/completions");

        // PAYG: .../api/v3 + /chat/completions.
        TargetRequest paygTarget = payg.resolve(route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE),
                request("/v1/chat/completions"));
        assertThat(paygTarget.origin()).isEqualTo(PAYG_BASE);
        assertThat(paygTarget.path()).isEqualTo("/chat/completions");
    }

    @Test
    @DisplayName("resolve returns an empty query string when the inbound request has none")
    void resolveEmptyQuery() {
        TargetRequest target = payg.resolve(route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE),
                new InboundRequest("GET", "/v1/models", Map.of(), Map.of("X-Trace-Id", List.of("t1"))));
        assertThat(target.query()).isEmpty();
    }

    @Test
    @DisplayName("credentialInjection declares Bearer Authorization and the strip set")
    void credentialInjectionContract() {
        CredentialInjection injection = payg.credentialInjection(null);
        assertThat(injection.headerName()).isEqualTo("Authorization");
        assertThat(injection.prefix()).isEqualTo("Bearer ");
        assertThat(injection.stripInboundHeaders()).containsExactlyInAnyOrder("authorization", "x-api-key", "api-key");
    }

    @Test
    @DisplayName("validateCredential probes the normalized /models endpoint")
    void validateCredentialProbesModelsPath() {
        StubClient client = new StubClient(
                new ProviderResponse(200, Map.of(), "{\"data\":[]}".getBytes(StandardCharsets.UTF_8)));
        CredentialCheck check = payg.validateCredential(client).block();

        assertThat(client.requests).hasSize(1);
        assertThat(client.requests.get(0).method()).isEqualTo("GET");
        assertThat(client.requests.get(0).path()).isEqualTo("/models");
        assertThat(client.requests.get(0).query()).isEmpty();
        assertThat(check.valid()).isTrue();
        assertThat(check.message()).isNull();
        assertThat(check.checkedAt()).isNotNull();
    }

    @Test
    @DisplayName("validateCredential maps 401/403 to credential-rejected and 429 to rate-limited")
    void validateCredentialRejectionReasons() {
        assertThat(payg.validateCredential(new StubClient(response(401, "unauthorized"))).block().message())
                .isEqualTo("credential rejected by Volcengine Ark API");
        assertThat(payg.validateCredential(new StubClient(response(403, "forbidden"))).block().message())
                .isEqualTo("credential rejected by Volcengine Ark API");
        assertThat(payg.validateCredential(new StubClient(response(429, "rate limit"))).block().message())
                .isEqualTo("rate limited by Volcengine Ark API");
        assertThat(payg.validateCredential(new StubClient(response(500, "boom"))).block().message())
                .isEqualTo("Volcengine Ark API returned HTTP 500");
        assertThat(payg.validateCredential(new StubClient(response(401, "unauthorized"))).block().valid()).isFalse();
    }

    @Test
    @DisplayName("fetchModels parses id + name and tolerates unknown fields")
    void fetchModelsParsesOfficialShape() {
        String body = "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"doubao-seed-2.0-code\",\"object\":\"model\",\"name\":\"Doubao Seed Code\"},"
                + "{\"id\":\"kimi-k2.5\",\"object\":\"model\"},"
                + "{\"created\":12345}],\"unknown_future_field\":true}";
        StubClient client = new StubClient(new ProviderResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8)));

        ModelCatalogSnapshot snapshot = payg.fetchModels(client).block();

        assertThat(client.requests.get(0).path()).isEqualTo("/models");
        assertThat(snapshot.providerProductId()).isEqualTo("volcengine-payg-api");
        assertThat(snapshot.fetchedAt()).isNotNull();
        assertThat(snapshot.models()).containsExactly(new ModelDefinition("doubao-seed-2.0-code", "Doubao Seed Code"),
                new ModelDefinition("kimi-k2.5"));
    }

    @Test
    @DisplayName("fetchModels fails on non-2xx and on unparseable bodies; empty data yields an empty catalog")
    void fetchModelsFailureModes() {
        assertThatThrownBy(() -> payg.fetchModels(new StubClient(response(500, "boom"))).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/models").hasMessageContaining("500");
        assertThatThrownBy(() -> payg.fetchModels(new StubClient(response(200, "<html>not json</html>"))).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not parseable");
        ModelCatalogSnapshot empty = payg.fetchModels(new StubClient(response(200, "{\"data\": \"not-an-array\"}")))
                .block();
        assertThat(empty.models()).isEmpty();
    }

    @Test
    @DisplayName("fetchPlanStatus returns UNAVAILABLE without any HTTP call for all three products")
    void fetchPlanStatusUnavailableWithoutHttpCall() {
        // No confirmed official balance API for any Ark product (console only);
        // the adapter must not invent values or call a nonexistent endpoint.
        assertUnavailable(payg, SubscriptionKind.PAYG);
        assertUnavailable(codingPlan, SubscriptionKind.INDIVIDUAL_PLAN);
        assertUnavailable(agentPlan, SubscriptionKind.INDIVIDUAL_PLAN);
    }

    @Test
    @DisplayName("capabilities declare streaming, model discovery and plan kinds")
    void capabilitiesPerProduct() {
        assertCapabilities(payg, false);
        assertCapabilities(codingPlan, true);
        assertCapabilities(agentPlan, true);
    }

    @Test
    @DisplayName("createUsageObserver returns a Volcengine observer bound to the context")
    void usageObserverIsBoundToContext() {
        UsageContext context = new UsageContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "gw-req-1", null, ProtocolFamily.OPENAI_COMPATIBLE,
                Instant.now());

        UsageObserver observer = payg.createUsageObserver(context);

        assertThat(observer).isInstanceOf(VolcengineArkUsageObserver.class);
        assertThat(((VolcengineArkUsageObserver) observer).context()).isSameAs(context);
    }

    private static RouteContext route(URI base, ProtocolFamily family) {
        return new RouteContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), family, base);
    }

    private static InboundRequest request(String path) {
        return new InboundRequest("POST", path, Map.of(), Map.of("Content-Type", List.of("application/json")));
    }

    private void assertUnavailable(VolcengineArkAdapter adapter, SubscriptionKind kind) {
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
        assertThat(snapshot.sharedPool()).isFalse();
        assertThat(snapshot.source()).isEqualTo(PlanDataSource.UNAVAILABLE);
        assertThat(snapshot.fetchedAt()).isNotNull();
    }

    private void assertCapabilities(VolcengineArkAdapter adapter, boolean plan) {
        AdapterCapabilities capabilities = adapter.capabilities();
        assertThat(capabilities.streaming()).isTrue();
        assertThat(capabilities.modelDiscovery()).isTrue();
        assertThat(capabilities.balance()).isFalse();
        assertThat(capabilities.plan()).isEqualTo(plan);
        assertThat(capabilities.teamPlan()).isFalse();
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
