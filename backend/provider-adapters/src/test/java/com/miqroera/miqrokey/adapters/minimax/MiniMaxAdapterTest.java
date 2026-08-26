package com.miqroera.miqrokey.adapters.minimax;

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

@DisplayName("MiniMaxAdapter (G3.4)")
class MiniMaxAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Official documented OpenAI-compatible base (2026-08-26). */
    private static final URI OPENAI_BASE = URI.create("https://api.minimax.io/v1");

    private final MiniMaxAdapter personal = MiniMaxAdapter.tokenPlanPersonal(MAPPER);
    private final MiniMaxAdapter team = MiniMaxAdapter.tokenPlanTeam(MAPPER);
    private final MiniMaxAdapter payg = MiniMaxAdapter.paygApi(MAPPER);

    @Test
    @DisplayName("all three adapterIds and protocol sets match the signed catalog")
    void identityAndProtocolsMatchSignedCatalog() {
        assertThat(personal.adapterId()).isEqualTo("minimax-token-plan-personal");
        assertThat(personal.protocols()).containsExactly(ProtocolFamily.OPENAI_COMPATIBLE);
        assertThat(team.adapterId()).isEqualTo("minimax-token-plan-team");
        assertThat(team.protocols()).containsExactly(ProtocolFamily.OPENAI_COMPATIBLE);
        assertThat(payg.adapterId()).isEqualTo("minimax-payg-api");
        assertThat(payg.protocols()).containsExactly(ProtocolFamily.OPENAI_COMPATIBLE);
    }

    @Test
    @DisplayName("resolve strips inbound credential headers and preserves everything else")
    void resolveStripsCredentialsAndPreservesOthers() {
        RouteContext route = route(OPENAI_BASE);
        InboundRequest request = new InboundRequest("POST", "/v1/chat/completions",
                Map.of("stream", List.of("true"), "q", List.of("a b&c")),
                Map.of("Authorization", List.of("Bearer sk-client-key"), "X-Api-Key", List.of("sk-client-key-2"),
                        "api-key", List.of("sk-client-key-3"), "Content-Type", List.of("application/json"),
                        "X-Trace-Id", List.of("trace-123")));

        TargetRequest target = payg.resolve(route, request);

        assertThat(target.method()).isEqualTo("POST");
        assertThat(target.origin()).isEqualTo(OPENAI_BASE);
        assertThat(target.path()).isEqualTo("/chat/completions");
        // The decoded query map is re-encoded into a raw query string (pair
        // order is not significant — InboundRequest copies the map unordered).
        assertThat(target.query().split("&")).containsExactlyInAnyOrder("stream=true", "q=a%20b%26c");
        assertThat(target.headers()).doesNotContainKeys("authorization", "x-api-key", "api-key");
        assertThat(target.headers()).containsEntry("content-type", "application/json").containsEntry("x-trace-id",
                "trace-123");
    }

    @Test
    @DisplayName("resolve strips the OpenAI /v1 prefix because the base ends in /v1")
    void resolveStripsOpenAiV1Prefix() {
        // Official docs: .../v1/chat/completions and .../v1/models.
        assertThat(payg.resolve(route(OPENAI_BASE), request("/v1/chat/completions")).path())
                .isEqualTo("/chat/completions");
        assertThat(payg.resolve(route(OPENAI_BASE), request("/v1/models")).path()).isEqualTo("/models");
        // A path without the /v1 prefix is never touched.
        assertThat(payg.resolve(route(OPENAI_BASE), request("/chat/completions")).path())
                .isEqualTo("/chat/completions");
    }

    @Test
    @DisplayName("resolve returns an empty query string when the inbound request has none")
    void resolveEmptyQuery() {
        TargetRequest target = payg.resolve(route(OPENAI_BASE),
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
    @DisplayName("validateCredential probes the documented /models endpoint")
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
                .isEqualTo("credential rejected by MiniMax API");
        assertThat(payg.validateCredential(new StubClient(response(403, "forbidden"))).block().message())
                .isEqualTo("credential rejected by MiniMax API");
        assertThat(payg.validateCredential(new StubClient(response(429, "rate limit"))).block().message())
                .isEqualTo("rate limited by MiniMax API");
        assertThat(payg.validateCredential(new StubClient(response(500, "boom"))).block().message())
                .isEqualTo("MiniMax API returned HTTP 500");
        assertThat(payg.validateCredential(new StubClient(response(401, "unauthorized"))).block().valid()).isFalse();
    }

    @Test
    @DisplayName("fetchModels parses the documented list-models shape (id/object/created/owned_by)")
    void fetchModelsParsesOfficialShape() {
        String body = "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"MiniMax-M3\",\"object\":\"model\",\"created\":1700000000,\"owned_by\":\"minimax\"},"
                + "{\"id\":\"MiniMax-M2.7\",\"object\":\"model\",\"created\":1690000000,\"owned_by\":\"minimax\"},"
                + "{\"id\":\"abp-image-generator\",\"object\":\"model\"}],\"unknown_future_field\":true}";
        StubClient client = new StubClient(new ProviderResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8)));

        ModelCatalogSnapshot snapshot = payg.fetchModels(client).block();

        assertThat(client.requests.get(0).path()).isEqualTo("/models");
        assertThat(snapshot.providerProductId()).isEqualTo("minimax-payg-api");
        assertThat(snapshot.fetchedAt()).isNotNull();
        // Documented shape carries no display name.
        assertThat(snapshot.models()).containsExactly(new ModelDefinition("MiniMax-M3"),
                new ModelDefinition("MiniMax-M2.7"), new ModelDefinition("abp-image-generator"));
    }

    @Test
    @DisplayName("fetchModels tolerates the OpenAI name variant")
    void fetchModelsToleratesNameVariant() {
        String body = "{\"data\":[{\"id\":\"MiniMax-M3\",\"name\":\"MiniMax M3\"}]}";
        ModelCatalogSnapshot snapshot = payg
                .fetchModels(new StubClient(new ProviderResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8))))
                .block();
        assertThat(snapshot.models()).containsExactly(new ModelDefinition("MiniMax-M3", "MiniMax M3"));
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
        // MiniMax documents no balance/usage API (console only); the adapter
        // must not invent values and must not call a nonexistent endpoint.
        assertUnavailable(payg, SubscriptionKind.PAYG, false);
        assertUnavailable(personal, SubscriptionKind.INDIVIDUAL_PLAN, false);
        // Team: per-member Subscription Key + shared Credits pool.
        assertUnavailable(team, SubscriptionKind.TEAM_PLAN, true);
    }

    @Test
    @DisplayName("capabilities declare streaming, model discovery and plan kinds")
    void capabilitiesPerProduct() {
        assertCapabilities(payg, false, false);
        assertCapabilities(personal, true, false);
        assertCapabilities(team, true, true);
    }

    @Test
    @DisplayName("createUsageObserver returns a MiniMax observer bound to the context")
    void usageObserverIsBoundToContext() {
        UsageContext context = new UsageContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "gw-req-1", null, ProtocolFamily.OPENAI_COMPATIBLE,
                Instant.now());

        UsageObserver observer = payg.createUsageObserver(context);

        assertThat(observer).isInstanceOf(MiniMaxUsageObserver.class);
        assertThat(((MiniMaxUsageObserver) observer).context()).isSameAs(context);
    }

    private static RouteContext route(URI base) {
        return new RouteContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ProtocolFamily.OPENAI_COMPATIBLE, base);
    }

    private static InboundRequest request(String path) {
        return new InboundRequest("POST", path, Map.of(), Map.of("Content-Type", List.of("application/json")));
    }

    private void assertUnavailable(MiniMaxAdapter adapter, SubscriptionKind kind, boolean sharedPool) {
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

    private void assertCapabilities(MiniMaxAdapter adapter, boolean plan, boolean teamPlan) {
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
