package com.miqroera.miqrokey.adapters.moonshot;

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

@DisplayName("MoonshotKimiAdapter (G3.5)")
class MoonshotKimiAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Official documented bases (2026-08-26). */
    private static final URI KIMI_CODE_OPENAI_BASE = URI.create("https://api.kimi.com/coding/v1");
    private static final URI KIMI_CODE_ANTHROPIC_BASE = URI.create("https://api.kimi.com/coding");
    private static final URI MOONSHOT_BASE = URI.create("https://api.moonshot.cn/v1");

    private final MoonshotKimiAdapter kimiCode = MoonshotKimiAdapter.kimiCodeMember(MAPPER);
    private final MoonshotKimiAdapter payg = MoonshotKimiAdapter.paygApi(MAPPER);

    @Test
    @DisplayName("both adapterIds and protocol sets match the signed catalog")
    void identityAndProtocolsMatchSignedCatalog() {
        assertThat(kimiCode.adapterId()).isEqualTo("moonshot-kimi-code-member");
        assertThat(kimiCode.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.ANTHROPIC_MESSAGES);
        assertThat(payg.adapterId()).isEqualTo("moonshot-payg-api");
        assertThat(payg.protocols()).containsExactly(ProtocolFamily.OPENAI_COMPATIBLE);
    }

    @Test
    @DisplayName("resolve strips inbound credential headers and preserves everything else")
    void resolveStripsCredentialsAndPreservesOthers() {
        RouteContext route = route(MOONSHOT_BASE, ProtocolFamily.OPENAI_COMPATIBLE);
        InboundRequest request = new InboundRequest("POST", "/v1/chat/completions",
                Map.of("stream", List.of("true"), "q", List.of("a b&c")),
                Map.of("Authorization", List.of("Bearer sk-client-key"), "X-Api-Key", List.of("sk-client-key-2"),
                        "api-key", List.of("sk-client-key-3"), "Content-Type", List.of("application/json"),
                        "X-Trace-Id", List.of("trace-123")));

        TargetRequest target = payg.resolve(route, request);

        assertThat(target.method()).isEqualTo("POST");
        assertThat(target.origin()).isEqualTo(MOONSHOT_BASE);
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
        // Kimi Code OpenAI: .../coding/v1 + /chat/completions (official docs).
        TargetRequest kimiOpenAi = kimiCode.resolve(route(KIMI_CODE_OPENAI_BASE, ProtocolFamily.OPENAI_COMPATIBLE),
                request("/v1/chat/completions"));
        assertThat(kimiOpenAi.origin()).isEqualTo(KIMI_CODE_OPENAI_BASE);
        assertThat(kimiOpenAi.path()).isEqualTo("/chat/completions");

        // Kimi Code Anthropic: .../coding/ + /v1/messages (official docs).
        TargetRequest kimiAnthropic = kimiCode
                .resolve(route(KIMI_CODE_ANTHROPIC_BASE, ProtocolFamily.ANTHROPIC_MESSAGES), request("/v1/messages"));
        assertThat(kimiAnthropic.origin()).isEqualTo(KIMI_CODE_ANTHROPIC_BASE);
        assertThat(kimiAnthropic.path()).isEqualTo("/v1/messages");

        // Moonshot PAYG: .../moonshot.cn/v1 + /chat/completions.
        TargetRequest moonshot = payg.resolve(route(MOONSHOT_BASE, ProtocolFamily.OPENAI_COMPATIBLE),
                request("/v1/chat/completions"));
        assertThat(moonshot.origin()).isEqualTo(MOONSHOT_BASE);
        assertThat(moonshot.path()).isEqualTo("/chat/completions");
    }

    @Test
    @DisplayName("resolve returns an empty query string when the inbound request has none")
    void resolveEmptyQuery() {
        TargetRequest target = payg.resolve(route(MOONSHOT_BASE, ProtocolFamily.OPENAI_COMPATIBLE),
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
                .isEqualTo("credential rejected by Moonshot API");
        assertThat(payg.validateCredential(new StubClient(response(403, "forbidden"))).block().message())
                .isEqualTo("credential rejected by Moonshot API");
        assertThat(payg.validateCredential(new StubClient(response(429, "rate limit"))).block().message())
                .isEqualTo("rate limited by Moonshot API");
        assertThat(payg.validateCredential(new StubClient(response(500, "boom"))).block().message())
                .isEqualTo("Moonshot API returned HTTP 500");
        assertThat(payg.validateCredential(new StubClient(response(401, "unauthorized"))).block().valid()).isFalse();
    }

    @Test
    @DisplayName("fetchModels parses id + name and tolerates unknown fields")
    void fetchModelsParsesOfficialShape() {
        String body = "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"kimi-k2.7\",\"object\":\"model\",\"name\":\"Kimi K2.7\"},"
                + "{\"id\":\"kimi-k2-turbo-preview\",\"object\":\"model\"},"
                + "{\"created\":12345}],\"unknown_future_field\":true}";
        StubClient client = new StubClient(new ProviderResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8)));

        ModelCatalogSnapshot snapshot = payg.fetchModels(client).block();

        assertThat(client.requests.get(0).path()).isEqualTo("/models");
        assertThat(snapshot.providerProductId()).isEqualTo("moonshot-payg-api");
        assertThat(snapshot.fetchedAt()).isNotNull();
        assertThat(snapshot.models()).containsExactly(new ModelDefinition("kimi-k2.7", "Kimi K2.7"),
                new ModelDefinition("kimi-k2-turbo-preview"));
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
    @DisplayName("fetchPlanStatus reads the official balance endpoint for PAYG")
    void fetchPlanStatusParsesOfficialBalance() {
        // Official Moonshot balance shape (2026-08-26).
        String body = "{\"code\":0,\"data\":{\"available_balance\":49.58894,\"voucher_balance\":46.58893,"
                + "\"cash_balance\":3.00001},\"scode\":\"0x0\",\"status\":true}";
        UUID subscriptionId = UUID.randomUUID();
        StubClient client = new StubClient(new ProviderResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8)));

        PlanSnapshot snapshot = payg
                .fetchPlanStatus(client, new SubscriptionContext(subscriptionId, SubscriptionKind.PAYG, null)).block();

        assertThat(client.requests.get(0).path()).isEqualTo("/users/me/balance");
        assertThat(snapshot.subscriptionId()).isEqualTo(subscriptionId.toString());
        assertThat(snapshot.kind()).isEqualTo(SubscriptionKind.PAYG);
        assertThat(snapshot.total()).isEqualByComparingTo("49.58894");
        assertThat(snapshot.remaining()).isEqualByComparingTo("49.58894");
        // Moonshot does not report used/period; null rather than fake zeros.
        assertThat(snapshot.used()).isNull();
        assertThat(snapshot.periodStart()).isNull();
        assertThat(snapshot.periodEnd()).isNull();
        assertThat(snapshot.sharedPool()).isFalse();
        assertThat(snapshot.source()).isEqualTo(PlanDataSource.OFFICIAL_API);
    }

    @Test
    @DisplayName("fetchPlanStatus fails on non-2xx and on unparseable balance bodies")
    void fetchPlanStatusFailureModes() {
        assertThatThrownBy(() -> payg.fetchPlanStatus(new StubClient(response(500, "boom")),
                new SubscriptionContext(UUID.randomUUID(), SubscriptionKind.PAYG, null)).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/users/me/balance");
        assertThatThrownBy(() -> payg.fetchPlanStatus(new StubClient(response(200, "<html>not json</html>")),
                new SubscriptionContext(UUID.randomUUID(), SubscriptionKind.PAYG, null)).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not parseable");
    }

    @Test
    @DisplayName("fetchPlanStatus returns UNAVAILABLE without HTTP for the Kimi Code membership")
    void fetchPlanStatusUnavailableForMembership() {
        StubClient client = new StubClient(response(200, "{}"));
        PlanSnapshot snapshot = kimiCode.fetchPlanStatus(client,
                new SubscriptionContext(UUID.randomUUID(), SubscriptionKind.INDIVIDUAL_PLAN, null)).block();
        assertThat(client.requests).isEmpty();
        assertThat(snapshot.kind()).isEqualTo(SubscriptionKind.INDIVIDUAL_PLAN);
        assertThat(snapshot.total()).isNull();
        assertThat(snapshot.remaining()).isNull();
        assertThat(snapshot.sharedPool()).isFalse();
        assertThat(snapshot.source()).isEqualTo(PlanDataSource.UNAVAILABLE);
    }

    @Test
    @DisplayName("capabilities declare plan kinds and balance only for PAYG")
    void capabilitiesPerProduct() {
        assertCapabilities(payg, false, true);
        assertCapabilities(kimiCode, true, false);
    }

    @Test
    @DisplayName("createUsageObserver returns a Moonshot observer bound to the context")
    void usageObserverIsBoundToContext() {
        UsageContext context = new UsageContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "gw-req-1", null, ProtocolFamily.OPENAI_COMPATIBLE,
                Instant.now());

        UsageObserver observer = payg.createUsageObserver(context);

        assertThat(observer).isInstanceOf(MoonshotKimiUsageObserver.class);
        assertThat(((MoonshotKimiUsageObserver) observer).context()).isSameAs(context);
    }

    private static RouteContext route(URI base, ProtocolFamily family) {
        return new RouteContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), family, base);
    }

    private static InboundRequest request(String path) {
        return new InboundRequest("POST", path, Map.of(), Map.of("Content-Type", List.of("application/json")));
    }

    private void assertCapabilities(MoonshotKimiAdapter adapter, boolean plan, boolean balance) {
        AdapterCapabilities capabilities = adapter.capabilities();
        assertThat(capabilities.streaming()).isTrue();
        assertThat(capabilities.modelDiscovery()).isTrue();
        assertThat(capabilities.balance()).isEqualTo(balance);
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
