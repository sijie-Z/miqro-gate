package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.route.RouteSnapshotProvider;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import com.miqroera.miqrokey.testing.ChatFixtures;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.InMemoryRouteSnapshotProvider;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2.6 security hardening on the strict path: the SSRF guard with an empty
 * allowlist (production default) rejects every non-public target before any
 * connection is attempted; unsupported paths are rejected at the router; the
 * error envelope never leaks the target URL or any credential material.
 *
 * <p>
 * Unlike the contract tests, this class does <em>not</em> import
 * {@link com.miqroera.miqrokey.gateway.GatewayAuthTestConfig}: the target is a
 * mutable reference so individual tests can point the injector at private
 * addresses while the validator stays strict.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false", "miqrokey.cache.enabled=false",
        "spring.main.web-application-type=reactive"})
@Import(GatewaySecurityHardeningTest.StrictTargetConfig.class)
@DisplayName("Gateway security hardening (SSRF / paths / envelope)")
class GatewaySecurityHardeningTest {

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    /** Mutable upstream target: the mock by default; SSRF tests repoint it. */
    private static final AtomicReference<String> TARGET = new AtomicReference<>(mockProvider.getBaseUrl());

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private WebTestClient webTestClient;

    @LocalServerPort
    private int gatewayPort;

    @TestConfiguration(proxyBeanMethods = false)
    static class StrictTargetConfig {

        @Bean
        @Primary
        VirtualKeyCrypto testVirtualKeyCrypto() {
            return GatewayTestKeys.crypto();
        }

        @Bean
        @Primary
        RouteSnapshotProvider testRouteSnapshotProvider() {
            return new InMemoryRouteSnapshotProvider(
                    GatewayTestKeys.snapshot(TARGET.get(), GatewayTestKeys.DEFAULT_KEY));
        }

        @Bean
        @Primary
        CredentialInjector testCredentialInjector() {
            return ctx -> Mono.just(new CredentialInjector.InjectedCredential(TARGET.get(), "authorization",
                    "Bearer sk-test-upstream-key"));
        }

        @Bean
        @Primary
        UpstreamTargetValidator testUpstreamTargetValidator() {
            // Strict production default: no allowlisted CIDRs.
            return new UpstreamTargetValidator(List.of());
        }
    }

    @AfterAll
    static void stopMockProvider() {
        mockProvider.close();
    }

    @AfterEach
    void reset() {
        mockProvider.reset();
        TARGET.set(mockProvider.getBaseUrl());
    }

    // -------------------------------------------------------------------
    // SSRF guard — private targets never get a connection attempt
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("SSRF guard")
    class SsrfGuard {

        @Test
        @DisplayName("a loopback target is rejected with route_unavailable and no upstream contact")
        void rejectsLoopbackTarget() {
            TARGET.set("http://127.0.0.1:9");
            String body = chatRequest().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY).expectBody(String.class)
                    .returnResult().getResponseBody();
            assertThat(errorType(body)).isEqualTo("route_unavailable");
            assertThat(body).doesNotContain("127.0.0.1");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("a link-local metadata address is rejected without leakage")
        void rejectsLinkLocalTarget() {
            TARGET.set("https://169.254.169.254/latest/meta-data");
            String body = chatRequest().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY).expectBody(String.class)
                    .returnResult().getResponseBody();
            assertThat(errorType(body)).isEqualTo("route_unavailable");
            assertThat(body).doesNotContain("169.254.169.254", "latest", "meta-data");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("a private RFC1918 target is rejected")
        void rejectsPrivateTarget() {
            TARGET.set("https://10.0.0.1");
            String body = chatRequest().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY).expectBody(String.class)
                    .returnResult().getResponseBody();
            assertThat(errorType(body)).isEqualTo("route_unavailable");
            assertThat(body).doesNotContain("10.0.0.1");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("a target with credentials in the URL is rejected")
        void rejectsUserinfoTarget() {
            TARGET.set("https://attacker:secret@10.0.0.1");
            String body = chatRequest().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY).expectBody(String.class)
                    .returnResult().getResponseBody();
            assertThat(errorType(body)).isEqualTo("route_unavailable");
            assertThat(body).doesNotContain("attacker", "secret");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // Paths — only the three known POST endpoints exist
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("path and method hardening")
    class Paths {

        @Test
        @DisplayName("wrong method on an allowed path is 405")
        void rejectsWrongMethod() {
            webTestClient.get().uri("/v1/messages").exchange().expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            webTestClient.put().uri("/v1/chat/completions").exchange().expectStatus()
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("unknown /v1 paths are 404")
        void rejectsUnknownPaths() {
            webTestClient.post().uri("/v1/embeddings").exchange().expectStatus().isNotFound();
            webTestClient.post().uri("/v2/messages").exchange().expectStatus().isNotFound();
            webTestClient.post().uri("/v1/messages/extra").exchange().expectStatus().isNotFound();
            webTestClient.post().uri("/v1/messages/").exchange().expectStatus().isNotFound();
            webTestClient.post().uri("/v1/messages%2Fextra").exchange().expectStatus().isNotFound();
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("path traversal is rejected; embedded double slashes normalize to the canonical path")
        void rejectsTraversal() {
            webTestClient.post().uri("/v1/messages/../chat/completions").exchange().expectStatus().isNotFound();
            webTestClient.post().uri("/v1/../admin").exchange().expectStatus().isNotFound();
            assertThat(mockProvider.getCapturedRequests()).isEmpty();

            // Spring normalizes "//" to "/" before routing, so a double-slash
            // variant IS the canonical path: without a key it is refused at the
            // auth layer, and with a key it enters the forwarding pipeline
            // exactly like /v1/messages (here the strict SSRF validator denies
            // the loopback mock, so both surface as route_unavailable — never
            // as a 404, and never a different target).
            webTestClient.post().uri("/v1//messages").exchange().expectStatus().isUnauthorized();
            webTestClient.post().uri("/v1//messages")
                    .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                    .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange().expectStatus()
                    .isEqualTo(HttpStatus.BAD_GATEWAY);
            String normalized = chatRequest().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY).expectBody(String.class)
                    .returnResult().getResponseBody();
            assertThat(errorType(normalized)).isEqualTo("route_unavailable");
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("an unsupported path response never leaks internals")
        void unsupportedPathEnvelope() {
            byte[] body = webTestClient.post().uri("/v1/embeddings").exchange().expectStatus().isNotFound().expectBody()
                    .returnResult().getResponseBody();
            String text = new String(body, StandardCharsets.UTF_8);
            assertThat(text).doesNotContain("127.0.0.1", GatewayTestKeys.DEFAULT_KEY.presented(),
                    "sk-test-upstream-key", "stacktrace", "Exception");
        }
    }

    // -------------------------------------------------------------------
    // Oversized headers — rejected by the Netty server before routing
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("oversized headers and bodies")
    class OversizedLimits {

        @Test
        @DisplayName("a header over the 32KB limit is rejected with 431")
        void rejectsOversizedHeader() {
            String huge = "x".repeat(40 * 1024);
            HttpResponseStatus status = HttpClient.create().headers(h -> h.set("X-Huge-Value", huge)).get()
                    .uri("http://localhost:" + gatewayPort + "/v1/messages")
                    .responseSingle((res, body) -> Mono.just(res.status())).block();
            assertThat(status).isEqualTo(HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }

        @Test
        @DisplayName("a body over the 256KB proxy buffer limit is rejected with 413")
        void rejectsOversizedBody() {
            String huge = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(300 * 1024)
                    + "\"}]}";
            HttpResponseStatus status = HttpClient.create().headers(h -> {
                h.set("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented());
                h.set("Content-Type", "application/json");
            }).post().uri("http://localhost:" + gatewayPort + "/v1/messages")
                    .send(Mono.just(Unpooled.copiedBuffer(huge, StandardCharsets.UTF_8)))
                    .responseSingle((res, body) -> Mono.just(res.status())).block();
            assertThat(status).isEqualTo(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
            assertThat(mockProvider.getCapturedRequests()).isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private WebTestClient.ResponseSpec chatRequest() {
        return webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented())
                .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange();
    }

    private static String errorType(String body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            return root.path("error").path("type").asText();
        } catch (Exception e) {
            throw new IllegalStateException("unparseable error body", e);
        }
    }
}
