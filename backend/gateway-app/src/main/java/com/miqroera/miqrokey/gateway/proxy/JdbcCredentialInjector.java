package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
import com.miqroera.miqrokey.route.CredentialSecretLoader;
import com.miqroera.miqrokey.route.RouteSnapshotProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;

/**
 * Production {@link CredentialInjector}: resolves the route snapshot's
 * credential record and decrypts the ACTIVE credential version on a dedicated
 * bounded-elastic scheduler (blocking JDBC + AES must never run on the event
 * loop).
 *
 * <p>
 * The plaintext secret is zero-filled immediately after building the header
 * value. The header value itself is a {@link String} (HTTP headers cannot carry
 * mutable bytes); it lives only inside the forwarded request.
 * </p>
 */
public final class JdbcCredentialInjector implements CredentialInjector {

    private static final Logger log = LoggerFactory.getLogger(JdbcCredentialInjector.class);

    private final ObjectProvider<CredentialSecretLoader> loaderProvider;
    private final Scheduler scheduler;
    private final ObjectMapper objectMapper;
    private final RouteSnapshotProvider routeSnapshotProvider;

    public JdbcCredentialInjector(ObjectProvider<CredentialSecretLoader> loaderProvider, Scheduler scheduler,
            ObjectMapper objectMapper, RouteSnapshotProvider routeSnapshotProvider) {
        this.loaderProvider = loaderProvider;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
        this.routeSnapshotProvider = routeSnapshotProvider;
    }

    @Override
    public Mono<InjectedCredential> resolve(AuthContext ctx) {
        return Mono.defer(() -> {
            RouteSnapshot.CredentialRecord credential = routeSnapshotProvider.current()
                    .credential(ctx.binding().credentialId());
            if (credential == null) {
                log.warn("No credential record in route snapshot for credential {}", ctx.binding().credentialId());
                return Mono.error(new AuthFailureException(HttpStatus.BAD_GATEWAY, "route_unavailable",
                        "Upstream credential is not available"));
            }
            CredentialSecretLoader loader = loaderProvider.getIfAvailable();
            if (loader == null) {
                return Mono.error(new AuthFailureException(HttpStatus.BAD_GATEWAY, "route_unavailable",
                        "Credential decryption is not available"));
            }
            AuthScheme scheme = parseAuthScheme(credential.authScheme());
            return Mono.fromCallable(() -> {
                byte[] secret = loader.loadActiveSecret(ctx.tenantId(), ctx.binding().credentialId());
                if (secret == null) {
                    return null;
                }
                try {
                    String value = switch (scheme.type()) {
                        case "raw" -> new String(secret, StandardCharsets.UTF_8);
                        default -> "Bearer " + new String(secret, StandardCharsets.UTF_8);
                    };
                    return new InjectedCredential(credential.baseUrl(), scheme.header(), value);
                } finally {
                    SecretWiping.clearArray(secret);
                }
            }).subscribeOn(scheduler)
                    .flatMap(value -> value != null
                            ? Mono.just(value)
                            : Mono.error(new AuthFailureException(HttpStatus.BAD_GATEWAY, "route_unavailable",
                                    "Upstream credential has no ACTIVE version")));
        });
    }

    /**
     * Parses the product's {@code auth_scheme} jsonb, e.g.
     * {@code {"type":"bearer","header":"authorization"}} or
     * {@code {"type":"raw","header":"x-api-key"}}. Unknown types default to bearer;
     * missing headers default to {@code authorization} (bearer) or
     * {@code x-api-key} (raw).
     */
    private AuthScheme parseAuthScheme(String authSchemeJson) {
        if (authSchemeJson == null || authSchemeJson.isBlank()) {
            return new AuthScheme("bearer", "authorization");
        }
        try {
            JsonNode node = objectMapper.readTree(authSchemeJson);
            String type = node.path("type").asText("bearer");
            if (!"bearer".equals(type) && !"raw".equals(type)) {
                log.warn("Unknown auth_scheme type '{}'; falling back to bearer", type);
                type = "bearer";
            }
            String header = node.path("header").asText(null);
            if (header == null || header.isBlank()) {
                header = "raw".equals(type) ? "x-api-key" : "authorization";
            }
            return new AuthScheme(type, header);
        } catch (Exception e) {
            log.warn("Malformed auth_scheme jsonb; falling back to bearer/authorization");
            return new AuthScheme("bearer", "authorization");
        }
    }

    private record AuthScheme(String type, String header) {
    }
}
