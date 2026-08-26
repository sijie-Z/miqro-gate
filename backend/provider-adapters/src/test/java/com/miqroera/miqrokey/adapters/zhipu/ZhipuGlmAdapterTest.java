package com.miqroera.miqrokey.adapters.zhipu;

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

@DisplayName("ZhipuGlmAdapter (G3.3)")
class ZhipuGlmAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Official documented bases (2026-08-26): Coding Plan and PAYG use different
     * OpenAI-compatible bases; Anthropic entry is a shared base.
     */
    private static final URI PAYG_BASE = URI.create("https://open.bigmodel.cn/api/paas/v4");
    private static final URI CODING_OPENAI_BASE = URI.create("https://open.bigmodel.cn/api/coding/paas/v4");
    private static final URI ANTHROPIC_BASE = URI.create("https://open.bigmodel.cn/api/anthropic");

    private final ZhipuGlmAdapter personal = ZhipuGlmAdapter.codingPlanPersonal(MAPPER);
    private final ZhipuGlmAdapter team = ZhipuGlmAdapter.codingPlanTeam(MAPPER);
    private final ZhipuGlmAdapter payg = ZhipuGlmAdapter.paygApi(MAPPER);

    @Test
    @DisplayName("all three adapterIds and protocol sets match the signed catalog")
    void identityAndProtocolsMatchSignedCatalog() {
        assertThat(personal.adapterId()).isEqualTo("zhipu-coding-plan-personal");
        assertThat(personal.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.ANTHROPIC_MESSAGES);
        assertThat(team.adapterId()).isEqualTo("zhipu-coding-plan-team");
        assertThat(team.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.ANTHROPIC_MESSAGES);
        assertThat(payg.adapterId()).isEqualTo("zhipu-payg-api");
        assertThat(payg.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.ANTHROPIC_MESSAGES);
    }

    @Test
    @DisplayName("resolve strips inbound credential headers and preserves everything else")
    void resolveStripsCredentialsAndPreservesOthers() {
        RouteContext route = route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE);
        InboundRequest request = new InboundRequest("POST", "/v1/chat/completions", Map.of("stream", List.of("true")),
                Map.of("Authorization", List.of("Bearer sk-client-key"), "X-Api-Key", List.of("sk-client-key-2"),
                        "api-key", List.of("sk-client-key-3"), "Content-Type", List.of("application/json"),
                        "X-Trace-Id", List.of("trace-123")));

        TargetRequest target = payg.resolve(route, request);

        assertThat(target.method()).isEqualTo("POST");
        assertThat(target.origin()).isEqualTo(PAYG_BASE);
        assertThat(target.path()).isEqualTo("/chat/completions");
        assertThat(target.query()).isEqualTo("stream=true");
        assertThat(target.headers()).doesNotContainKeys("authorization", "x-api-key", "api-key");
        assertThat(target.headers()).containsEntry("content-type", "application/json").containsEntry("x-trace-id",
                "trace-123");
    }

    @Test
    @DisplayName("resolve strips the OpenAI /v1 prefix because the base ends in /v4")
    void resolveStripsOpenAiV1Prefix() {
        assertThat(payg.resolve(route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/v1/chat/completions"))
                .path()).isEqualTo("/chat/completions");
        assertThat(payg.resolve(route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/v1/models")).path())
                .isEqualTo("/models");
        // A path without the /v1 prefix is never touched.
        assertThat(
                payg.resolve(route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE), request("/chat/completions")).path())
                .isEqualTo("/chat/completions");
    }

    @Test
    @DisplayName("resolve keeps Anthropic Messages paths verbatim")
    void resolveKeepsAnthropicMessagesPath() {
        TargetRequest target = payg.resolve(route(ANTHROPIC_BASE, ProtocolFamily.ANTHROPIC_MESSAGES),
                request("/v1/messages"));
        assertThat(target.origin()).isEqualTo(ANTHROPIC_BASE);
        assertThat(target.path()).isEqualTo("/v1/messages");
    }

    @Test
    @DisplayName("resolve maps documented per-product bases to the official full endpoints")
    void resolveOfficialEndpointMapping() {
        // PAYG: .../api/paas/v4 + /chat/completions (official docs).
        TargetRequest paygTarget = payg.resolve(route(PAYG_BASE, ProtocolFamily.OPENAI_COMPATIBLE),
                request("/v1/chat/completions"));
        assertThat(paygTarget.origin()).isEqualTo(PAYG_BASE);
        assertThat(paygTarget.path()).isEqualTo("/chat/completions");

        // Coding Plan: .../api/coding/paas/v4 + /chat/completions (official docs).
        TargetRequest codingTarget = personal.resolve(route(CODING_OPENAI_BASE, ProtocolFamily.OPENAI_COMPATIBLE),
                request("/v1/chat/completions"));
        assertThat(codingTarget.origin()).isEqualTo(CODING_OPENAI_BASE);
        assertThat(codingTarget.path()).isEqualTo("/chat/completions");

        // Anthropic: .../api/anthropic + /v1/messages (official docs).
        TargetRequest anthropicTarget = team.resolve(route(ANTHROPIC_BASE, ProtocolFamily.ANTHROPIC_MESSAGES),
                request("/v1/messages"));
        assertThat(anthropicTarget.origin()).isEqualTo(ANTHROPIC_BASE);
        assertThat(anthropicTarget.path()).isEqualTo("/v1/messages");
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
    @DisplayName("validateCredential probes /models after /v1 prefix normalization")
    void validateCredentialProbesNormalizedModelsPath() {
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
                .isEqualTo("credential rejected by Zhipu GLM API");
        assertThat(payg.validateCredential(new StubClient(response(403, "forbidden"))).block().message())
                .isEqualTo("credential rejected by Zhipu GLM API");
        assertThat(payg.validateCredential(new StubClient(response(429, "rate limit"))).block().message())
                .isEqualTo("rate limited by Zhipu GLM API");
        assertThat(payg.validateCredential(new StubClient(response(500, "boom"))).block().message())
                .isEqualTo("Zhipu GLM API returned HTTP 500");
        assertThat(payg.validateCredential(new StubClient(response(401, "unauthorized"))).block().valid()).isFalse();
    }

    @Test
    @DisplayName("fetchModels parses id + name and tolerates unknown fields")
    void fetchModelsParsesOfficialShape() {
        String body = "{\"object\":\"list\",\"data\":[" + "{\"id\":\"glm-5\",\"object\":\"model\",\"name\":\"GLM-5\"},"
                + "{\"id\":\"glm-5-turbo\",\"object\":\"model\"},"
                + "{\"created\":12345}],\"unknown_future_field\":true}";
        StubClient client = new StubClient(new ProviderResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8)));

        ModelCatalogSnapshot snapshot = payg.fetchModels(client).block();

        assertThat(client.requests.get(0).path()).isEqualTo("/models");
        assertThat(snapshot.providerProductId()).isEqualTo("zhipu-payg-api");
        assertThat(snapshot.models()).containsExactly(new ModelDefinition("glm-5", "GLM-5"),
                new ModelDefinition("glm-5-turbo"));
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
    @DisplayName("fetchPlanStatus returns UNAVAILABLE without any HTTP call")
    void fetchPlanStatusUnavailableWithoutHttpCall() {
        StubClient client = new StubClient(response(200, "{}"));
        PlanSnapshot snapshot = payg
                .fetchPlanStatus(client, new SubscriptionContext(UUID.randomUUID(), SubscriptionKind.PAYG, null))
                .block();
        assertThat(client.requests).isEmpty();
        assertThat(snapshot.kind()).isEqualTo(SubscriptionKind.PAYG);
        assertThat(snapshot.total()).isNull();
        assertThat(snapshot.used()).isNull();
        assertThat(snapshot.remaining()).isNull();
        assertThat(snapshot.sharedPool()).isFalse();
        assertThat(snapshot.source()).isEqualTo(PlanDataSource.UNAVAILABLE);
        assertThat(snapshot.fetchedAt()).isNotNull();
    }

    @Test
    @DisplayName("capabilities declare streaming, model discovery and plan kinds")
    void capabilitiesPerProduct() {
        assertCapabilities(payg, false, false);
        assertCapabilities(personal, true, false);
        assertCapabilities(team, true, true);
    }

    @Test
    @DisplayName("createUsageObserver returns a Zhipu observer bound to the context")
    void usageObserverIsBoundToContext() {
        UsageContext context = new UsageContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "gw-req-1", null, ProtocolFamily.OPENAI_COMPATIBLE,
                Instant.now());

        UsageObserver observer = payg.createUsageObserver(context);

        assertThat(observer).isInstanceOf(ZhipuGlmUsageObserver.class);
        assertThat(((ZhipuGlmUsageObserver) observer).context()).isSameAs(context);
    }

    private static RouteContext route(URI base, ProtocolFamily family) {
        return new RouteContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), family, base);
    }

    private static InboundRequest request(String path) {
        return new InboundRequest("POST", path, Map.of(), Map.of("Content-Type", List.of("application/json")));
    }

    private void assertCapabilities(ZhipuGlmAdapter adapter, boolean plan, boolean teamPlan) {
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
