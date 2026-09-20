package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicFixtures;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract coverage for the LLM-side circuit breaker (#741) with the feature
 * enabled and deliberately small thresholds.
 *
 * <p>
 * The load-bearing assertion in the fast-fail test is that the upstream is
 * never contacted while the breaker is OPEN — the failure loop must not keep
 * feeding the broken dependency. The probe test pins the recovery path: after
 * the open window one probe is released and its success closes the breaker.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "spring.main.web-application-type=reactive", "miqrokey.gateway.circuit-breaker.enabled=true",
        "miqrokey.gateway.circuit-breaker.min-requests=2", "miqrokey.gateway.circuit-breaker.open-seconds=1",
        "miqrokey.gateway.circuit-breaker.probe-count=1", "miqrokey.gateway.circuit-breaker.probe-success=1"})
@AutoConfigureWebTestClient
@Import(GatewayAuthTestConfig.class)
@DisplayName("LLM circuit breaker enabled (#741)")
class LlmCircuitBreakerIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private LlmCircuitBreakerRegistry registry;

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockProvider::getBaseUrl);
    }

    @AfterAll
    static void stopMockProvider() {
        mockProvider.close();
    }

    @AfterEach
    void resetMockProvider() {
        mockProvider.reset();
    }

    @BeforeEach
    void resetBreaker() {
        // The registry is a context singleton: without this, an OPEN bucket
        // left by one test would fast-fail the next test's first call.
        registry.reset();
    }

    private void upstreamFailsWith(int status) {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(status)
                .contentType("application/json").body("{\"error\":{\"type\":\"upstream_broken\"}}").build());
    }

    private void upstreamSucceeds() {
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(AnthropicFixtures.RESPONSE_BASIC).build());
    }

    private WebTestClient.ResponseSpec call() {
        return webTestClient.post().uri("/v1/messages").bodyValue(AnthropicFixtures.REQUEST_NON_STREAMING).exchange();
    }

    /**
     * How long to wait for a breaker state change to become visible on the wire.
     * The window is {@code open-seconds=1} and each attempt costs one call, so 40
     * attempts of 50ms is comfortably past it while still failing loudly instead of
     * hanging.
     */
    private static final int MAX_ATTEMPTS = 40;

    private static final long RETRY_PAUSE_MS = 50;

    /**
     * Drives calls until the gateway observably rejects one, and returns that
     * rejection's body.
     *
     * <p>
     * #934: the open transition is a side effect of the response chain. A call's
     * outcome is recorded in {@code afterCall}, which can land <em>after</em> the
     * client already holds the response — so the Nth call may begin before the
     * (N-1)th has been counted. Asserting "call three is rejected" therefore
     * asserts a scheduling order that nothing enforces, and it is why this test
     * failed roughly one CI run in a hundred with {@code expected:&lt;503&gt; but
     * was:&lt;500&gt;}. Waiting for the observable consequence instead removes the
     * assumption without weakening the contract: what is still asserted is "while
     * open, the gateway fails fast and costs the broken upstream nothing".
     * </p>
     *
     * <p>
     * The step semantics — how many failures open the window, when the half-open
     * probe is released, when a probe success closes it — are pinned exactly and
     * without wall-clock elsewhere: {@code McpCircuitBreakerTest} drives the state
     * machine through an injected {@link java.time.Clock}, and
     * {@code LlmCircuitBreakerRegistryTest} pins the probe's release and recovery
     * through the registry. This class only pins the HTTP shape, so it should not
     * re-encode those steps with sleeps — doing that is what made it flaky.
     * </p>
     */
    private byte[] awaitRejection() throws Exception {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            EntityExchangeResult<byte[]> result = call().expectBody().returnResult();
            if (result.getStatus().value() == 503) {
                return result.getResponseBody();
            }
            // Still relayed: that call reached the upstream and its outcome feeds
            // the breaker, so this loop converges rather than spins.
            Thread.sleep(RETRY_PAUSE_MS);
        }
        throw new AssertionError("the breaker never rejected a call within " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Waits for a call to be answered (bounded; the rationale is above). The
     * recovery test uses it twice — once for the released probe, once for the
     * closure that probe produces — so it reports what was observed rather than
     * assuming which of the two it was waiting for.
     */
    private void awaitSuccess() throws Exception {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            EntityExchangeResult<byte[]> result = call().expectBody().returnResult();
            if (result.getStatus().is2xxSuccessful()) {
                return;
            }
            Thread.sleep(RETRY_PAUSE_MS);
        }
        throw new AssertionError("no call was answered within " + MAX_ATTEMPTS + " attempts");
    }

    @Test
    @DisplayName("once open the gateway fails fast with 503 circuit_open and never contacts the upstream (#934)")
    void tripsAndFailsFast() throws Exception {
        upstreamFailsWith(500);

        // Control: a relayed failure is answered by the upstream and comes back
        // verbatim. Both calls have completed, so counting them is not racy.
        call().expectStatus().isEqualTo(500);
        call().expectStatus().isEqualTo(500);
        assertThat(mockProvider.getCapturedRequests()).hasSize(2);

        byte[] response = awaitRejection();
        assertThat(OBJECT_MAPPER.readTree(response).path("error").path("type").asText()).isEqualTo("circuit_open");
        assertThat(OBJECT_MAPPER.readTree(response).path("type").asText()).isEqualTo("error");

        // Invariant, independent of the order outcomes were recorded in: every call
        // so far either was relayed (and therefore reached the upstream) or was
        // rejected (and therefore did not). Nothing else is admissible.
        int contacts = mockProvider.getCapturedRequests().size();

        // Still open (open-seconds=1): another call is rejected, and still costs
        // the broken dependency nothing.
        call().expectStatus().isEqualTo(503);
        assertThat(mockProvider.getCapturedRequests()).hasSize(contacts);
    }

    @Test
    @DisplayName("the half-open probe is released after the open window and its success closes the breaker (#934)")
    void probeRecoversAndCloses() throws Exception {
        upstreamFailsWith(500);
        awaitRejection(); // open — bounded, not "the third call"
        int contactsWhileOpen = mockProvider.getCapturedRequests().size();

        upstreamSucceeds();
        // Two observable transitions, not one. The probe being *answered* is not
        // the breaker being *closed*: closure is produced by the probe's own
        // afterCall, and with probe-count=1 the OPEN->HALF_OPEN transition has
        // already consumed the single probe slot — so until that outcome lands,
        // the next call is rejected. Waiting for the second success is what makes
        // this line order-independent; asserting on the first one instead is the
        // same "the previous call's afterCall must have landed" assumption this
        // test was fixed to stop making.
        awaitSuccess(); // the probe itself, answered by the recovered upstream
        awaitSuccess(); // closed again: ordinary traffic flows

        // Deliberately NOT followed by an immediate "and it stays closed" call.
        // Asserting that would require the probe's afterCall to have landed within
        // open-seconds: past that, the #451 half-open recycle releases a fresh
        // probe, so the second success above can come from a breaker that is
        // HALF_OPEN rather than CLOSED, and the next call is rejected — the same
        // "the previous afterCall must have landed" assumption this test was fixed
        // to drop, just with a wider window. "A probe success closes the breaker"
        // is a step semantic, and it is pinned by LlmCircuitBreakerRegistryTest
        // (plus McpCircuitBreakerTest) rather than re-encoded here.
        assertThat(mockProvider.getCapturedRequests().size()).isGreaterThanOrEqualTo(contactsWhileOpen + 2);
    }

    @Test
    @DisplayName("non-error statuses (404) feed the breaker as successes, never opening it")
    void nonErrorStatusDoesNotTrip() {
        upstreamFailsWith(404);

        for (int i = 0; i < 4; i++) {
            call().expectStatus().isEqualTo(404);
        }
        assertThat(mockProvider.getCapturedRequests()).hasSize(4);
    }
}
