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
 * API-key authentication for the external-system channel (ADR-0010), protecting
 * {@code /api/v1/billing/**}. A valid session (portal admin) also passes;
 * otherwise the presented key is hashed and matched against an ACTIVE consumer.
 * The consumer identity is exposed as a request attribute for the billing
 * controllers.
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
    private final UserContext userContext;

    public ApiKeyAuthFilter(ApiConsumerRepository consumerRepository, UserContext userContext) {
        this.consumerRepository = consumerRepository;
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
        String key = extractKey(request);
        if (key != null && key.startsWith(KEY_PREFIX)) {
            ApiConsumer consumer = consumerRepository.findByKeyDigest(sha256(key)).orElse(null);
            if (consumer != null) {
                request.setAttribute(CONSUMER_ATTR, consumer.id());
                request.setAttribute(TENANT_ATTR, consumer.tenantId());
                chain.doFilter(request, response);
                return;
            }
        }
        unauthorized(response);
    }

    private static String extractKey(HttpServletRequest request) {
        String header = request.getHeader("X-API-Key");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length()).trim();
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
