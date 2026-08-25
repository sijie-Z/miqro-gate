package com.miqroera.miqrokey.adapters.deepseek;

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

@DisplayName("DeepSeekPaygAdapter (G3.1)")
class DeepSeekPaygAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final URI BASE_URL = URI.create("https://api.deepseek.com");

    private final DeepSeekPaygAdapter adapter = new DeepSeekPaygAdapter(MAPPER);

    @Test
    @DisplayName("adapterId matches the signed catalog and protocols are declared")
    void identityAndProtocols() {
        assertThat(adapter.adapterId()).isEqualTo("deepseek-payg-api");
        assertThat(adapter.protocols()).containsExactlyInAnyOrder(ProtocolFamily.OPENAI_COMPATIBLE,
                ProtocolFamily.ANTHROPIC_MESSAGES);
    }

    @Test
    @DisplayName("resolve strips inbound auth headers and preserves everything else")
    void resolveStripsAuthHeaders() {
        RouteContext route = new RouteContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ProtocolFamily.OPENAI_COMPATIBLE, BASE_URL);
        InboundRequest request = new InboundRequest("POST", "/chat/completions",
                Map.of("stream", List.of("true"), "q", List.of("a b&c")),
                Map.of("Authorization", List.of("Bearer sk-client-key"), "X-Api-Key", List.of("sk-client-key-2"),
                        "api-key", List.of("sk-client-key-3"), "Content-Type", List.of("application/json"),
                        "X-Trace-Id", List.of("trace-123")));

        TargetRequest target = adapter.resolve(route, request);

        assertThat(target.method()).isEqualTo("POST");
        assertThat(target.origin()).isEqualTo(BASE_URL);
        assertThat(target.path()).isEqualTo("/chat/completions");
        // The decoded query map is re-encoded into a raw query string (pair
        // order is not significant — InboundRequest copies the map unordered).
        assertThat(target.query().split("&")).containsExactlyInAnyOrder("stream=true", "q=a%20b%26c");
        // All three client credential headers are stripped; never forwarded.
        assertThat(target.headers()).doesNotContainKeys("authorization", "x-api-key", "api-key");
        assertThat(target.headers()).containsEntry("content-type", "application/json").containsEntry("x-trace-id",
                "trace-123");
    }

    @Test
    @DisplayName("resolve returns an empty query string when the inbound request has none")
    void resolveEmptyQuery() {
        RouteContext route = new RouteContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ProtocolFamily.OPENAI_COMPATIBLE, BASE_URL);

        TargetRequest target = adapter.resolve(route,
                new InboundRequest("GET", "/models", Map.of(), Map.of("X-Trace-Id", List.of("t1"))));

        assertThat(target.query()).isEmpty();
    }

    @Test
    @DisplayName("credentialInjection declares Bearer Authorization and the strip set")
    void credentialInjectionContract() {
        CredentialInjection injection = adapter.credentialInjection(null);

        assertThat(injection.headerName()).isEqualTo("Authorization");
        assertThat(injection.prefix()).isEqualTo("Bearer ");
        assertThat(injection.stripInboundHeaders()).containsExactlyInAnyOrder("authorization", "x-api-key", "api-key");
    }

    @Test
    @DisplayName("validateCredential calls GET /models with no query and maps 2xx to valid")
    void validateCredentialAcceptsOnSuccess() {
        StubClient client = new StubClient(
                new ProviderResponse(200, Map.of(), "{\"data\":[]}".getBytes(StandardCharsets.UTF_8)));

        CredentialCheck check = adapter.validateCredential(client).block();

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
        assertThat(adapter.validateCredential(new StubClient(response(401, "unauthorized"))).block().message())
                .isEqualTo("credential rejected by DeepSeek API");
        assertThat(adapter.validateCredential(new StubClient(response(403, "forbidden"))).block().message())
                .isEqualTo("credential rejected by DeepSeek API");
        assertThat(adapter.validateCredential(new StubClient(response(429, "rate limit"))).block().message())
                .isEqualTo("rate limited by DeepSeek API");
        assertThat(adapter.validateCredential(new StubClient(response(500, "boom"))).block().message())
                .isEqualTo("DeepSeek API returned HTTP 500");
    }

    @Test
    @DisplayName("validateCredential never reports valid on failure")
    void validateCredentialNeverValidOnFailure() {
        CredentialCheck check = adapter.validateCredential(new StubClient(response(401, "unauthorized"))).block();
        assertThat(check.valid()).isFalse();
    }

    @Test
    @DisplayName("fetchModels parses id and display_name, tolerating unknown fields")
    void fetchModelsParsesOfficialShape() {
        String body = "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"deepseek-chat\",\"object\":\"model\",\"owned_by\":\"deepseek\","
                + "\"display_name\":\"DeepSeek-V3.2\"},"
                + "{\"id\":\"deepseek-reasoner\",\"object\":\"model\",\"owned_by\":\"deepseek\"},"
                + "{\"created\":12345}],\"unknown_future_field\":true}";
        StubClient client = new StubClient(new ProviderResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8)));

        ModelCatalogSnapshot snapshot = adapter.fetchModels(client).block();

        assertThat(snapshot.providerProductId()).isEqualTo("deepseek-payg-api");
        assertThat(snapshot.fetchedAt()).isNotNull();
        assertThat(snapshot.models()).containsExactly(new ModelDefinition("deepseek-chat", "DeepSeek-V3.2"),
                new ModelDefinition("deepseek-reasoner"));
    }

    @Test
    @DisplayName("fetchModels fails on non-2xx and on unparseable bodies; empty data yields an empty catalog")
    void fetchModelsFailureModes() {
        assertThatThrownBy(() -> adapter.fetchModels(new StubClient(response(500, "boom"))).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/models").hasMessageContaining("500");
        assertThatThrownBy(() -> adapter.fetchModels(new StubClient(response(200, "<html>not json</html>"))).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not parseable");
        // A data field that is not an array contributes no models (tolerated, not
        // fatal).
        ModelCatalogSnapshot empty = adapter.fetchModels(new StubClient(response(200, "{\"data\": \"not-an-array\"}")))
                .block();
        assertThat(empty.models()).isEmpty();
    }

    @Test
    @DisplayName("fetchPlanStatus parses total_balance as PAYG remaining")
    void fetchPlanStatusParsesBalance() {
        String body = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"110.00\","
                + "\"granted_balance\":\"10.00\",\"topped_up_balance\":\"100.00\"}],"
                + "\"unknown_future_field\":true}";
        UUID subscriptionId = UUID.randomUUID();
        StubClient client = new StubClient(new ProviderResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8)));

        PlanSnapshot snapshot = adapter
                .fetchPlanStatus(client, new SubscriptionContext(subscriptionId, SubscriptionKind.PAYG, null)).block();

        assertThat(client.requests.get(0).path()).isEqualTo("/user/balance");
        assertThat(snapshot.subscriptionId()).isEqualTo(subscriptionId.toString());
        assertThat(snapshot.kind()).isEqualTo(SubscriptionKind.PAYG);
        assertThat(snapshot.total()).isEqualByComparingTo("110.00");
        assertThat(snapshot.remaining()).isEqualByComparingTo("110.00");
        // DeepSeek does not report used/period; null rather than fake zeros.
        assertThat(snapshot.used()).isNull();
        assertThat(snapshot.periodStart()).isNull();
        assertThat(snapshot.periodEnd()).isNull();
        assertThat(snapshot.sharedPool()).isFalse();
        assertThat(snapshot.source()).isEqualTo(PlanDataSource.OFFICIAL_API);
    }

    @Test
    @DisplayName("fetchPlanStatus tolerates an empty balance_infos list and fails on non-2xx")
    void fetchPlanStatusEdgeCases() {
        PlanSnapshot snapshot = adapter.fetchPlanStatus(new StubClient(response(200, "{\"balance_infos\":[]}")),
                new SubscriptionContext(UUID.randomUUID(), SubscriptionKind.PAYG, null)).block();
        assertThat(snapshot.total()).isNull();
        assertThat(snapshot.remaining()).isNull();

        assertThatThrownBy(() -> adapter.fetchPlanStatus(new StubClient(response(500, "boom")),
                new SubscriptionContext(UUID.randomUUID(), SubscriptionKind.PAYG, null)).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/user/balance");
    }

    @Test
    @DisplayName("createUsageObserver returns a DeepSeek observer bound to the context")
    void usageObserverIsBoundToContext() {
        UsageContext context = new UsageContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "gw-req-1", null, ProtocolFamily.OPENAI_COMPATIBLE,
                Instant.now());

        UsageObserver observer = adapter.createUsageObserver(context);

        assertThat(observer).isInstanceOf(DeepSeekUsageObserver.class);
        assertThat(((DeepSeekUsageObserver) observer).context()).isSameAs(context);
    }

    @Test
    @DisplayName("capabilities declare streaming, model discovery, balance and request id")
    void capabilitiesDeclared() {
        AdapterCapabilities capabilities = adapter.capabilities();

        assertThat(capabilities.streaming()).isTrue();
        assertThat(capabilities.modelDiscovery()).isTrue();
        assertThat(capabilities.balance()).isTrue();
        assertThat(capabilities.plan()).isFalse();
        assertThat(capabilities.teamPlan()).isFalse();
        assertThat(capabilities.requestId()).isTrue();
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
