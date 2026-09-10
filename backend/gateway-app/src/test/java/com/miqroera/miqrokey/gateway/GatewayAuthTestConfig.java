package com.miqroera.miqrokey.gateway;

import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.gateway.proxy.CredentialInjector;
import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import com.miqroera.miqrokey.route.RouteSnapshotProvider;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.InMemoryRouteSnapshotProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Test wiring for authenticated gateway contract tests.
 *
 * <ul>
 * <li>{@link VirtualKeyCrypto} over a fixed HMAC key ring (matches
 * {@link GatewayTestKeys} fixtures).</li>
 * <li>An in-memory route snapshot provider seeded with the fixture keys
 * ({@code miqrokey.gateway.persistence.enabled=false} must be set on the test
 * class; the database-backed {@code RouteSnapshotConfig} is then skipped and
 * the JDBC credential injector backs off).</li>
 * <li>A fixed-value {@link CredentialInjector} pointing at the mock upstream
 * ({@code miqrokey.gateway.upstream.url}), so tests never touch real
 * credentials.</li>
 * <li>A {@link WebTestClientBuilderCustomizer} that presents the fixture
 * virtual key as a default {@code Authorization} header — individual requests
 * can override it for negative auth tests.</li>
 * </ul>
 *
 * <p>
 * Test beans are {@link Primary} so they win over the feature-config fallbacks
 * regardless of bean-registration order.
 * </p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class GatewayAuthTestConfig {

    /** Header/value injected into the upstream request in contract tests. */
    public static final String UPSTREAM_CREDENTIAL_HEADER = "authorization";
    public static final String UPSTREAM_CREDENTIAL_VALUE = "Bearer sk-test-upstream-key";

    @Bean
    @Primary
    public VirtualKeyCrypto gatewayTestVirtualKeyCrypto() {
        return GatewayTestKeys.crypto();
    }

    @Bean
    @Primary
    public RouteSnapshotProvider gatewayTestRouteSnapshotProvider(Environment environment) {
        return new InMemoryRouteSnapshotProvider(GatewayTestKeys.snapshot(requiredUpstreamUrl(environment),
                GatewayTestKeys.DEFAULT_KEY, GatewayTestKeys.OTHER_KEY, GatewayTestKeys.GRANT_LIMITED_KEY,
                GatewayTestKeys.UPSTREAM_LIMITED_KEY, GatewayTestKeys.NO_UPSTREAM_KEY,
                GatewayTestKeys.UNKNOWN_PRODUCT_KEY));
    }

    @Bean
    @Primary
    public CredentialInjector gatewayTestCredentialInjector(Environment environment) {
        String baseUrl = requiredUpstreamUrl(environment);
        return ctx -> Mono.just(new CredentialInjector.InjectedCredential(baseUrl, UPSTREAM_CREDENTIAL_HEADER,
                UPSTREAM_CREDENTIAL_VALUE));
    }

    /**
     * Contract tests target the local mock over plain http, so the G2.6 SSRF guard
     * is configured to allow loopback explicitly (the production default rejects
     * it). {@code GatewaySecurityHardeningTest} deliberately does not import this
     * config and exercises the strict path.
     */
    /**
     * #320 deterministic decryptor for the MCP backend-auth contract tests: the
     * secured fixture's ciphertext yields a fixed plaintext; the broken fixture's
     * all-zero ciphertext (and anything else) throws, exercising the fail-closed
     * path. Never used for real secrets (tests only).
     */
    // Deliberately NOT @Primary: suites that need their own cipher stand-in
    // (e.g. RetentionCaptureTest.FakeCrypto) declare a @Primary bean, and the
    // ObjectProvider-based consumers resolve that primary deterministically.
    @Bean
    public com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider gatewayTestKeyEncryptionProvider() {
        return new com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider() {
            @Override
            public com.miqroera.miqrokey.domain.crypto.EncryptedSecret encrypt(byte[] plaintext,
                    java.util.UUID tenantId, java.util.UUID credentialId) {
                return new com.miqroera.miqrokey.domain.crypto.EncryptedSecret(plaintext.clone(), new byte[]{1}, "v1");
            }

            @Override
            public String activeKeyVersion() {
                return "v1";
            }

            @Override
            public com.miqroera.miqrokey.domain.crypto.EncryptedSecret reEncrypt(
                    com.miqroera.miqrokey.domain.crypto.EncryptedSecret secret, java.util.UUID tenantId,
                    java.util.UUID credentialId) {
                return secret;
            }

            @Override
            public byte[] decrypt(com.miqroera.miqrokey.domain.crypto.EncryptedSecret secret, java.util.UUID tenantId,
                    java.util.UUID credentialId) {
                byte[] ciphertext = secret.ciphertext();
                if (ciphertext.length == 3 && ciphertext[0] == 7 && ciphertext[1] == 7 && ciphertext[2] == 7) {
                    return GatewayTestKeys.MCP_SECURED_BACKEND_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                throw new IllegalStateException("test decryptor rejects this ciphertext");
            }
        };
    }

    @Bean
    @Primary
    public UpstreamTargetValidator gatewayTestUpstreamTargetValidator() {
        return new UpstreamTargetValidator(List.of("127.0.0.0/8", "::1/128"));
    }

    @Bean
    public WebTestClientBuilderCustomizer gatewayTestClientCustomizer() {
        return builder -> builder.defaultHeader("Authorization", "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented());
    }

    private static String requiredUpstreamUrl(Environment environment) {
        String baseUrl = environment.getProperty("miqrokey.gateway.upstream.url");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "miqrokey.gateway.upstream.url must be set on tests importing GatewayAuthTestConfig");
        }
        return baseUrl;
    }
}
