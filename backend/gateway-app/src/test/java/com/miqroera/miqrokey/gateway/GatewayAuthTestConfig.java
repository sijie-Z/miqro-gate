package com.miqroera.miqrokey.gateway;

import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.gateway.proxy.CredentialInjector;
import com.miqroera.miqrokey.route.RouteSnapshotProvider;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.InMemoryRouteSnapshotProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Mono;

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
