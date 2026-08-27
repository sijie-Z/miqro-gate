package com.miqroera.miqrokey.gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.gateway.vkey.AuthContext;
import com.miqroera.miqrokey.gateway.vkey.AuthFailureException;
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
 * credential record and decrypts the ACTIVE version's ciphertext — which the
 * snapshot loader already loaded at refresh time — in memory on a dedicated
 * bounded-elastic scheduler.
 *
 * <p>
 * The hot path performs NO database access: the ciphertext, base URL, and auth
 * scheme all come from the versioned read-only snapshot. The plaintext secret
 * is zero-filled immediately after building the header value.
 * </p>
 */
public final class JdbcCredentialInjector implements CredentialInjector {

    private static final Logger log = LoggerFactory.getLogger(JdbcCredentialInjector.class);

    private final RouteSnapshotProvider routeSnapshotProvider;
    private final ObjectProvider<KeyEncryptionProvider> keyEncryptionProvider;
    private final Scheduler scheduler;
    private final ObjectMapper objectMapper;

    public JdbcCredentialInjector(RouteSnapshotProvider routeSnapshotProvider,
            ObjectProvider<KeyEncryptionProvider> keyEncryptionProvider, Scheduler scheduler,
            ObjectMapper objectMapper) {
        this.routeSnapshotProvider = routeSnapshotProvider;
        this.keyEncryptionProvider = keyEncryptionProvider;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
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
            EncryptedSecret encrypted = credential.encryptedSecret();
            if (encrypted == null) {
                log.warn("No ACTIVE credential version in route snapshot for credential {}",
                        ctx.binding().credentialId());
                return Mono.error(new AuthFailureException(HttpStatus.BAD_GATEWAY, "route_unavailable",
                        "Upstream credential has no ACTIVE version"));
            }
            KeyEncryptionProvider crypto = keyEncryptionProvider.getIfAvailable();
            if (crypto == null) {
                // Crypto subsystem unavailable (persistence disabled): fail closed.
                return Mono.error(new AuthFailureException(HttpStatus.BAD_GATEWAY, "route_unavailable",
                        "Credential decryption is not available"));
            }
            AuthScheme scheme = parseAuthScheme(credential.authScheme());
            return Mono.fromCallable(() -> {
                byte[] secret = crypto.decrypt(encrypted, ctx.tenantId(), ctx.binding().credentialId());
                try {
                    String value = switch (scheme.type()) {
                        case "raw" -> new String(secret, StandardCharsets.UTF_8);
                        default -> "Bearer " + new String(secret, StandardCharsets.UTF_8);
                    };
                    return new InjectedCredential(credential.baseUrl(), scheme.header(), value);
                } finally {
                    SecretWiping.clearArray(secret);
                }
            }).subscribeOn(scheduler);
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
