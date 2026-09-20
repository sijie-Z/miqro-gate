package com.miqroera.miqrokey.gateway.proxy;

import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import com.miqroera.miqrokey.testing.ChatFixtures;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.InMemoryRouteSnapshotProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quota soft-landing gate contract (#684, ADR-0020): an exceeded REJECT rule
 * blocks the covered user/project with {@code 429 quota_exceeded} — before any
 * upstream call — while unaffected keys keep working; the 429 carries a
 * {@code Retry-After} hint derived from the rule's window end; clearing the
 * verdict in the snapshot restores traffic with no other action.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false", "miqrokey.cache.enabled=false",
        "spring.main.web-application-type=reactive"})
@AutoConfigureWebTestClient
@Import(GatewayAuthTestConfig.class)
@DisplayName("Quota soft-landing gate contract")
class QuotaGateContractTest {

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockProvider::getBaseUrl);
    }

    @AfterAll
    static void stopMockProvider() {
        mockProvider.close();
    }

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private InMemoryRouteSnapshotProvider snapshotProvider;
    @Autowired
    private Environment environment;

    @BeforeEach
    @AfterEach
    void resetSnapshot() {
        // Shared contract-test context: never leak blocked scopes across tests.
        snapshotProvider.install(GatewayTestKeys.snapshot(upstreamUrl()));
        mockProvider.reset();
    }

    private String upstreamUrl() {
        String url = environment.getProperty("miqrokey.gateway.upstream.url");
        assertThat(url).isNotBlank();
        return url;
    }

    private WebTestClient.ResponseSpec postChat(String presentedKey) {
        return webTestClient.post().uri("/v1/chat/completions").header("Authorization", "Bearer " + presentedKey)
                .bodyValue(ChatFixtures.REQUEST_NON_STREAMING).exchange();
    }

    @Test
    @DisplayName("a user-scope verdict rejects every key of that user before any upstream call")
    void blockedUserRejected() {
        snapshotProvider.install(
                GatewayTestKeys.snapshotWithQuotaBlocks(upstreamUrl(), Set.of(GatewayTestKeys.DEFAULT_KEY.userId()),
                        Set.of(), GatewayTestKeys.DEFAULT_KEY, GatewayTestKeys.OTHER_KEY));

        postChat(GatewayTestKeys.DEFAULT_KEY.presented()).expectStatus().isEqualTo(429).expectHeader()
                .value("Retry-After", value -> assertThat(Long.parseLong(value)).isBetween(1L, 3600L)).expectBody()
                .jsonPath("$.error.type").isEqualTo("quota_exceeded");
        assertThat(mockProvider.getCapturedRequests()).isEmpty();

        // The other key's user is unaffected.
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
        postChat(GatewayTestKeys.OTHER_KEY.presented()).expectStatus().isOk();
    }

    @Test
    @DisplayName("a project-scope verdict blocks the project's keys and clears with the next snapshot")
    void blockedProjectRejectedAndCleared() {
        snapshotProvider.install(GatewayTestKeys.snapshotWithQuotaBlocks(upstreamUrl(), Set.of(),
                Set.of(GatewayTestKeys.DEFAULT_KEY.projectId()), GatewayTestKeys.DEFAULT_KEY));

        postChat(GatewayTestKeys.DEFAULT_KEY.presented()).expectStatus().isEqualTo(429).expectBody()
                .jsonPath("$.error.type").isEqualTo("quota_exceeded");
        // /v1/models honors the same gate (and the same hint).
        webTestClient.get().uri("/v1/models")
                .header("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented()).exchange().expectStatus()
                .isEqualTo(429).expectHeader().exists("Retry-After");

        // Verdict cleared (limit raised / window reset) -> traffic resumes.
        mockProvider.configure(AnthropicMockProvider.ResponseConfig.builder().statusCode(200)
                .contentType("application/json").body(ChatFixtures.RESPONSE_BASIC).build());
        snapshotProvider.install(GatewayTestKeys.snapshot(upstreamUrl(), GatewayTestKeys.DEFAULT_KEY));
        postChat(GatewayTestKeys.DEFAULT_KEY.presented()).expectStatus().isOk();
    }
}
