package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authentication for the external-system channel (ADR-0010/0011), protecting
 * {@code /api/v1/billing/**}. A valid session (portal admin) also passes;
 * otherwise the presented credential is either an API key (SHA-256 digest match
 * against an ACTIVE consumer) or an RS256 JWT (verified against the consumer's
 * configured public key, {@code sub} = consumer name). The consumer identity is
 * exposed as a request attribute for the billing controllers.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the authenticated consumer id. */
    public static final String CONSUMER_ATTR = "apiConsumerId";
    /** Request attribute holding the consumer's tenant id. */
    public static final String TENANT_ATTR = "apiConsumerTenantId";

    /** Path prefix of the external-system channel. */
    public static final String BILLING_PATH = "/api/v1/billing";
    private static final String KEY_PREFIX = "mqk_api_";

    private final ApiConsumerRepository consumerRepository;
    private final ConsumerJwtVerifier jwtVerifier;
    private final UserContext userContext;

    public ApiKeyAuthFilter(ApiConsumerRepository consumerRepository, ConsumerJwtVerifier jwtVerifier,
            UserContext userContext) {
        this.consumerRepository = consumerRepository;
        this.jwtVerifier = jwtVerifier;
        this.userContext = userContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(BILLING_PATH)) {
            chain.doFilter(request, response);
            return;
        }
        // Portal admin session passes through.
        if (userContext.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        Credential credential = extractCredential(request);
        if (credential != null) {
            ApiConsumer consumer = null;
            // The X-API-Key header is API-key-only; Authorization: Bearer splits
            // by prefix (mqk_api_… = API key, otherwise an RS256 JWT).
            if (credential.origin() == Origin.API_KEY_HEADER) {
                consumer = authenticateApiKey(credential.value());
            } else if (credential.value().startsWith(KEY_PREFIX)) {
                consumer = authenticateApiKey(credential.value());
            } else {
                consumer = authenticateJwt(credential.value());
            }
            if (consumer != null) {
                // Issue #316 channel scope: the billing channel requires
                // billing:read (null scope = full access). Fail closed.
                if (!consumer.allows("billing:read")) {
                    forbidden(response);
                    return;
                }
                request.setAttribute(CONSUMER_ATTR, consumer.id());
                request.setAttribute(TENANT_ATTR, consumer.tenantId());
                chain.doFilter(request, response);
                return;
            }
        }
        unauthorized(response);
    }

    private ApiConsumer authenticateApiKey(String key) {
        return consumerRepository.findByKeyDigest(sha256(key)).orElse(null);
    }

    private ApiConsumer authenticateJwt(String token) {
        String subject = ConsumerJwtVerifier.extractSubject(token);
        if (subject == null) {
            return null;
        }
        ApiConsumer consumer = consumerRepository.findByName(subject).orElse(null);
        if (consumer == null || !"ACTIVE".equals(consumer.status()) || !consumer.hasJwtKey()) {
            return null;
        }
        if (!jwtVerifier.verify(token, consumer.jwtPublicKeyPem(), subject)) {
            return null;
        }
        return consumer;
    }

    /** Where the presented credential came from. */
    private enum Origin {
        API_KEY_HEADER, BEARER
    }

    private record Credential(String value, Origin origin) {
    }

    private static Credential extractCredential(HttpServletRequest request) {
        String apiKeyHeader = request.getHeader("X-API-Key");
        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            return new Credential(apiKeyHeader.trim(), Origin.API_KEY_HEADER);
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return new Credential(auth.substring("Bearer ".length()).trim(), Origin.BEARER);
        }
        return null;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write("""
                        {"type":"about:blank","title":"Scope denied","status":403,"code":"CONSUMER_SCOPE_DENIED","detail":"该消费者未被授予 billing:read 能力。"}
                        """
                        .trim());
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write("""
                        {"type":"about:blank","title":"Authentication required","status":401,"code":"UNAUTHORIZED","detail":"A valid API key or portal session is required."}
                        """
                        .trim());
    }
}
