package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.domain.crypto.ConsumerJwtVerifier;
import com.miqroera.miqrokey.domain.model.ApiConsumer;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authentication for the external-system channel (ADR-0010/0011), protecting
 * {@code /api/v1/billing/**}. A valid SYSTEM_ADMIN session also passes (#724);
 * otherwise the presented credential is either an API key (SHA-256 digest match
 * against an ACTIVE consumer) or an RS256 JWT (verified against the consumer's
 * configured public key, {@code sub} = consumer name). The consumer identity is
 * exposed as a request attribute for the billing controllers.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** Request attribute holding the authenticated consumer id. */
    public static final String CONSUMER_ATTR = "apiConsumerId";
    /** Request attribute holding the consumer's tenant id. */
    public static final String TENANT_ATTR = "apiConsumerTenantId";

    /** Path prefix of the external-system channel. */
    public static final String BILLING_PATH = "/api/v1/billing";
    private static final String KEY_PREFIX = "mqk_api_";

    /** Upper bound for a client-supplied identifier echoed into a log line. */
    private static final int LOG_VALUE_MAX = 64;

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
        String path = RequestPaths.lookupPath(request);
        if (!path.startsWith(BILLING_PATH)) {
            chain.doFilter(request, response);
            return;
        }
        // Portal sessions pass through only as SYSTEM_ADMIN (#724): the billing
        // channel is admin-session OR consumer credential — never "any logged-in
        // user", which would leak tenant-wide billing data to a plain USER.
        if (userContext.isAuthenticated() && userContext.getUser().role() == UserRole.SYSTEM_ADMIN) {
            chain.doFilter(request, response);
            return;
        }
        Credential credential = extractCredential(request);
        if (credential != null) {
            ApiConsumer consumer = null;
            // The X-API-Key header is API-key-only; Authorization: Bearer splits
            // by prefix (mqk_api_… = API key, otherwise an RS256 JWT).
            if (credential.origin() == Origin.API_KEY_HEADER) {
                consumer = authenticateApiKey(credential.value(), request);
            } else if (credential.value().startsWith(KEY_PREFIX)) {
                consumer = authenticateApiKey(credential.value(), request);
            } else {
                consumer = authenticateJwt(credential.value(), request);
            }
            if (consumer != null) {
                // Issue #316 channel scope: the billing channel requires
                // billing:read (null scope = full access). Fail closed.
                if (!consumer.allows("billing:read")) {
                    forbidden(request, response, "CONSUMER_SCOPE_DENIED", consumer);
                    return;
                }
                request.setAttribute(CONSUMER_ATTR, consumer.id());
                request.setAttribute(TENANT_ATTR, consumer.tenantId());
                chain.doFilter(request, response);
                return;
            }
        }
        // #724: an authenticated non-admin without a usable consumer credential
        // is forbidden (not unauthenticated) — and never falls back to the
        // session's tenant.
        if (userContext.isAuthenticated()) {
            forbidden(request, response, "SESSION_WITHOUT_CONSUMER_CREDENTIAL", null);
            return;
        }
        // A credential that was presented has already been recorded with its own
        // reason by the authenticator; only a credential-less call needs a line
        // here, otherwise every rejection would be logged twice.
        if (credential == null) {
            LOG.warn("Billing channel unauthorized [requestId={}, reason=NO_CREDENTIAL]", forLog(requestId(request)));
        }
        unauthorized(request, response);
    }

    /**
     * The rejection is recorded with the correlation id only: the presented
     * credential is never logged (a rejected key can still be a live secret
     * mistyped at the wrong endpoint), and the consumer id is logged only once the
     * credential has been accepted.
     */
    private ApiConsumer authenticateApiKey(String key, HttpServletRequest request) {
        ApiConsumer consumer = consumerRepository.findByKeyDigest(sha256(key)).orElse(null);
        if (consumer == null) {
            LOG.warn("Billing channel rejected [requestId={}, reason=UNKNOWN_API_KEY]", forLog(requestId(request)));
        }
        return consumer;
    }

    private ApiConsumer authenticateJwt(String token, HttpServletRequest request) {
        String subject = ConsumerJwtVerifier.extractSubject(token);
        if (subject == null) {
            LOG.warn("Billing channel rejected [requestId={}, reason=JWT_SUBJECT_MISSING]", forLog(requestId(request)));
            return null;
        }
        ApiConsumer consumer = consumerRepository.findByName(subject).orElse(null);
        if (consumer == null) {
            LOG.warn("Billing channel rejected [requestId={}, reason=UNKNOWN_CONSUMER, consumer={}]",
                    forLog(requestId(request)), forLog(subject));
            return null;
        }
        if (!"ACTIVE".equals(consumer.status())) {
            LOG.warn("Billing channel rejected [requestId={}, reason=CONSUMER_NOT_ACTIVE, consumerId={}]",
                    forLog(requestId(request)), consumer.id());
            return null;
        }
        if (!consumer.hasJwtKey()) {
            LOG.warn("Billing channel rejected [requestId={}, reason=NO_JWT_KEY, consumerId={}]",
                    forLog(requestId(request)), consumer.id());
            return null;
        }
        if (!jwtVerifier.verify(token, consumer.jwtPublicKeyPem(), subject)) {
            LOG.warn("Billing channel rejected [requestId={}, reason=JWT_SIGNATURE_INVALID, consumerId={}]",
                    forLog(requestId(request)), consumer.id());
            return null;
        }
        return consumer;
    }

    /**
     * Every client-supplied value that reaches a structured log line goes through
     * this — the {@code sub} claim, which is still unverified at that point, and
     * the {@code X-Request-Id} header, which is unverified by definition. Control
     * characters are flattened so a crafted value cannot forge extra log lines; the
     * delimiters of the surrounding {@code [key=value, …]} structure are
     * neutralized so it cannot close that structure early and read as a second
     * event; and the value is bounded so an oversized one cannot turn every
     * rejection into a log-amplification vector. This applies to the log only — the
     * envelope echoes the caller's token verbatim (contract §2), via
     * {@link #requestId}.
     *
     * <p>
     * The rule itself lives in {@link LogValues} so the auth and audit log lines
     * share it; this channel keeps its own tighter bound. That class is the shared
     * definition, not yet the only one — see its javadoc for the copies that still
     * drift.
     * </p>
     */
    private static String forLog(String value) {
        return LogValues.forLog(value, LOG_VALUE_MAX);
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

    /**
     * The reason token and the consumer identity (known only after a credential was
     * accepted) go to the log; the envelope stays as generic as before, so a caller
     * still cannot enumerate which capability it is missing.
     */
    private static void forbidden(HttpServletRequest request, HttpServletResponse response, String reason,
            ApiConsumer consumer) throws IOException {
        if (consumer == null) {
            LOG.warn("Billing channel forbidden [requestId={}, reason={}]", forLog(requestId(request)), reason);
        } else {
            LOG.warn("Billing channel forbidden [requestId={}, reason={}, consumerId={}, tenantId={}]",
                    forLog(requestId(request)), reason, consumer.id(), consumer.tenantId());
        }
        writeProblem(request, response, HttpServletResponse.SC_FORBIDDEN, "Scope denied", "CONSUMER_SCOPE_DENIED",
                "该消费者未被授予 billing:read 能力。");
    }

    private static void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeProblem(request, response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required", "UNAUTHORIZED",
                "A valid API key or portal session is required.");
    }

    /**
     * Contract §2 requires every error response — filter rejections included — to
     * carry a unique {@code requestId}. Billing consumers have no session cookie to
     * fall back on, so this token is their only correlation handle.
     */
    private static void writeProblem(HttpServletRequest request, HttpServletResponse response, int status, String title,
            String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ProblemJson.of(status, title, code, detail, requestId(request)));
    }

    static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        // #445: this header is client-controlled. It reaches the response only through
        // ProblemJson.of, which serializes the envelope instead of splicing it.
        return header != null && !header.isBlank() ? header : java.util.UUID.randomUUID().toString();
    }
}
