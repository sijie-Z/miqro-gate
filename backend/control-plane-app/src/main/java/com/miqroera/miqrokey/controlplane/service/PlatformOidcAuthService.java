package com.miqroera.miqrokey.controlplane.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.controlplane.security.SessionService;
import com.miqroera.miqrokey.controlplane.security.SessionToken;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Platform OIDC login (P0a, ADR-0017): an authorization-code Relying Party
 * against the platform's OAuth2 endpoints ({@code /oauth2/authorize|token},
 * {@code /oauth2/userinfo} — Forge test contract, 2026-09-08). The {@code sub}
 * claim is the stable platform identity: it is stored in
 * {@code user_identity_link} (V31) against the internal gateway user, which is
 * auto-provisioned on first login when configured. Sessions reuse the normal
 * portal session machinery, so CSRF/origin rules apply afterwards.
 */
@Service
public class PlatformOidcAuthService {

    /** Same single-tenant seed the rest of the auth stack uses. */
    static final UUID SEED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String STATE_COOKIE = "MIQROKEY_OAUTH_STATE";
    private static final int STATE_MAX_AGE_SECONDS = 600;
    private static final int USERNAME_MAX = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The OIDC transport, shared for the life of the process so its connections are
     * pooled and reused.
     *
     * <p>
     * {@code java.net.http} rather than {@code RestClient} because the teardown is
     * observable there (#1300): {@code cancel(true)} on the future returned by
     * {@code sendAsync} closes the socket, while interrupting a virtual thread
     * parked on a {@code RestClient} read does not — measured against a peer that
     * drips a body, the abandoned connection was still open ≈45.5 s after the
     * budget expired (1.09 s after the probe JVM's own teardown request), until
     * that JVM exited.
     *
     * <p>
     * Redirects are not followed: the token and userinfo URIs are configured per
     * deployment, so a redirect from them is a misconfiguration, and following one
     * would forward the form body (client secret included) wherever it points.
     */
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private final AuthProperties authProperties;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public PlatformOidcAuthService(AuthProperties authProperties, UserRepository userRepository,
            PasswordHasher passwordHasher, SessionService sessionService, AuditService auditService,
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.authProperties = authProperties;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Whether the feature is switched on. */
    public boolean isEnabled() {
        return authProperties.isPlatformOidcEnabled();
    }

    /** Login-page descriptor (empty when disabled). */
    public Optional<ProviderInfo> providerInfo() {
        if (!isEnabled()) {
            return Optional.empty();
        }
        return Optional
                .of(new ProviderInfo(authProperties.getPlatformOidcIdpCode(), authProperties.getPlatformOidcName()));
    }

    public record ProviderInfo(String code, String name) {
    }

    /**
     * Starts the code flow: stores a short-lived state cookie and returns the
     * platform authorize URL.
     */
    public String start(HttpServletRequest request, HttpServletResponse response) {
        requireConfigured();
        String state = UUID.randomUUID().toString();
        Cookie stateCookie = new Cookie(STATE_COOKIE, state);
        stateCookie.setHttpOnly(true);
        stateCookie.setSecure(authProperties.isCookieSecure());
        stateCookie.setPath("/");
        stateCookie.setMaxAge(STATE_MAX_AGE_SECONDS);
        response.addCookie(stateCookie);
        return UriComponentsBuilder.fromUriString(authProperties.getPlatformOidcAuthorizeUri())
                .queryParam("response_type", "code").queryParam("client_id", authProperties.getPlatformOidcClientId())
                .queryParam("redirect_uri", authProperties.getPlatformOidcRedirectUri())
                .queryParam("scope", authProperties.getPlatformOidcScope()).queryParam("state", state).build().encode()
                .toUriString();
    }

    /**
     * Completes the code flow and establishes a portal session. Returns the
     * internal redirect target; throws {@link OAuthFlowException} with an ASCII
     * error code when the flow fails (controller maps it to a login-page redirect).
     */
    public String complete(HttpServletRequest request, HttpServletResponse response, String code, String state) {
        requireConfigured();
        String expected = readCookie(request, STATE_COOKIE);
        if (state == null || !state.equals(expected)) {
            throw new OAuthFlowException("STATE_MISMATCH");
        }
        clearStateCookie(response);
        String accessToken = exchangeCode(code);
        OidcIdentity identity = fetchUserinfo(accessToken);

        UUID tenantId = SEED_TENANT_ID;
        UUID internalUserId = findLinkedUser(tenantId, identity.sub()).orElse(null);
        boolean provisioned = false;
        if (internalUserId == null) {
            if (!authProperties.isPlatformOidcAutoProvision()) {
                throw new OAuthFlowException("ACCOUNT_UNLINKED");
            }
            Provisioned result = provisionUser(tenantId, identity);
            internalUserId = result.userId();
            // #1028 (owner ruling C): only a real INSERT is a provisioning. Adopting
            // the link a concurrent first login just committed is a plain login — one
            // account created must not be recorded as two provisionings.
            provisioned = result.created();
        }
        User user = userRepository.findById(internalUserId)
                .orElseThrow(() -> new OAuthFlowException("ACCOUNT_UNLINKED"));
        // #730: mirror the local-login semantics (AuthenticationService.login and
        // the per-request SessionFilter): a DISABLED account, or a LOCKED account
        // whose lock has not expired, must not silently receive a session here.
        if (user.status() == UserStatus.DISABLED || (user.status() == UserStatus.LOCKED
                && (user.lockedUntil() == null || Instant.now().isBefore(user.lockedUntil())))) {
            throw new OAuthFlowException("ACCOUNT_UNAVAILABLE");
        }
        SessionToken tokens = sessionService.createSession(user);
        Instant sessionExpires = Instant.now().plus(authProperties.getSessionAbsoluteTimeout());
        sessionService.setCookies(response, tokens, sessionExpires);
        auditService.record(tenantId, user.id(), provisioned ? "OAUTH_PROVISION" : "OAUTH_LOGIN", "USER", user.id(),
                "{\"idp\":\"" + safeJson(authProperties.getPlatformOidcIdpCode()) + "\",\"sub\":\""
                        + safeJson(identity.sub()) + "\"}",
                request.getHeader("X-Request-Id"));
        return "/app/keys";
    }

    /** ASCII error codes surfaced through the login-page redirect. */
    public static class OAuthFlowException extends RuntimeException {
        private final String code;

        public OAuthFlowException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    // ------------------------------------------------------------------

    private void requireConfigured() {
        if (!isEnabled()) {
            throw new OAuthFlowException("PROVIDER_UNKNOWN");
        }
        if (blank(authProperties.getPlatformOidcAuthorizeUri()) || blank(authProperties.getPlatformOidcTokenUri())
                || blank(authProperties.getPlatformOidcUserinfoUri()) || blank(authProperties.getPlatformOidcClientId())
                || blank(authProperties.getPlatformOidcClientSecret())
                || blank(authProperties.getPlatformOidcRedirectUri())) {
            throw new OAuthFlowException("CONFIG_INCOMPLETE");
        }
    }

    /**
     * Runs one outbound OIDC call under a wall-clock budget (#1294) and gives the
     * connection back when the budget expires (#1300).
     *
     * <p>
     * The budget has to be enforced from outside the request: neither transport
     * offers a whole-operation bound — what looks like one in the logs is a
     * per-read <em>idle</em> deadline, so a peer that keeps a stalled response
     * alive one byte at a time is never idle long enough to trip it, and the
     * callback's Tomcat thread is held for as long as the peer likes. The request
     * therefore carries no {@code timeout()} of its own either: on both transports
     * that one stops at the response headers, and the body read afterwards would
     * again be unbounded.
     *
     * <p>
     * Abandoning the call must also release the socket, or every expired budget
     * leaves an upstream connection behind for the life of the JVM. Measured on the
     * peer: {@code cancel(true)} on the future from {@code sendAsync} closes the
     * connection about a second after the budget expires (1.05 s), while the
     * previous shape (a virtual thread parked on a blocking read, interrupted) left
     * it open ≈45.5 s past the budget, until the JVM exited.
     *
     * <p>
     * The connection, not a reader thread, is what accumulates. After eight
     * abandoned calls in the previous shape the peer's own accounting shows eight
     * connections still open, one per abandoned call, and a
     * {@code jcmd Thread.dump_to_file} dump — a format that does list virtual
     * threads, so an absence in it is meaningful — holds <em>no</em> surviving
     * {@code oidc-outbound-*} reader. That absence is consistent with the
     * {@code cancel(true)} on both paths having interrupted each reader; the dump
     * shows none outlived its call, though the interrupt itself was not observed.
     * What stays behind is the socket, each one pinning a handler thread on the IdP
     * side. With this transport the same eight abandons leave none open; see
     * {@code PlatformOidcAbandonedConnectionAccumulationTest}.
     *
     * <p>
     * An overrunning call is reported as {@code failureCode} — the same ASCII code
     * the caller already uses when the exchange itself fails — so the login page
     * sees an ordinary flow failure rather than a hung request.
     */
    private String withinBudget(String failureCode, HttpRequest request) {
        Duration budget = authProperties.getPlatformOidcHttpTimeout();
        CompletableFuture<HttpResponse<String>> call = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            HttpResponse<String> response = call.get(budget.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() / 100 != 2) {
                throw new OidcUpstreamException("OIDC endpoint answered with status " + response.statusCode());
            }
            return response.body();
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new OAuthFlowException(failureCode);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new OAuthFlowException(failureCode);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            // Connect refused, TLS failure, reset: java.net.http reports these as
            // checked IOExceptions, where RestClient raised an unchecked
            // ResourceAccessException. Kept unchecked so an unreachable IdP still
            // fails the callback the way it did before — see OidcUpstreamException.
            throw new OidcUpstreamException("OIDC call failed: " + cause);
        }
    }

    /**
     * An upstream failure that is not a flow outcome: the IdP answered something
     * other than 2xx, or the call could not be made at all.
     *
     * <p>
     * Unchecked on purpose, and deliberately not an {@link OAuthFlowException}:
     * that is how this path behaved before (#1294 kept it: "a non-2xx is still a
     * transport exception today"), and {@code AuthOAuthController} turns it into a
     * 500 rather than a redirect to the login page. Whether an IdP error should
     * instead land on the login page as a flow code is a product decision, not part
     * of bounding the call (#1300) — this class keeps the old answer while making
     * the reason visible.
     */
    private static final class OidcUpstreamException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        OidcUpstreamException(String message) {
            super(message);
        }
    }

    private String exchangeCode(String code) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(authProperties.getPlatformOidcTokenUri()))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(tokenFormBody(code), StandardCharsets.UTF_8)).build();
        String body = withinBudget("AUTH_ERROR", request);
        try {
            JsonNode node = objectMapper.readTree(body == null ? "" : body);
            String token = text(node.get("access_token"));
            if (token == null) {
                throw new OAuthFlowException("AUTH_ERROR");
            }
            return token;
        } catch (OAuthFlowException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthFlowException("AUTH_ERROR");
        }
    }

    /**
     * The token-endpoint body, encoded by hand now that no form converter sits in
     * the transport: every value goes through {@link URLEncoder}, so a client
     * secret containing {@code +}, {@code /} or {@code =} (the base64 alphabet, and
     * what most IdPs issue) survives the wire intact.
     */
    private String tokenFormBody(String code) {
        return "grant_type=authorization_code" + "&code=" + encode(code) + "&redirect_uri="
                + encode(authProperties.getPlatformOidcRedirectUri()) + "&client_id="
                + encode(authProperties.getPlatformOidcClientId()) + "&client_secret="
                + encode(authProperties.getPlatformOidcClientSecret());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private OidcIdentity fetchUserinfo(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(authProperties.getPlatformOidcUserinfoUri()))
                .header("Authorization", "Bearer " + accessToken).GET().build();
        String body = withinBudget("USERINFO_INVALID", request);
        try {
            JsonNode node = objectMapper.readTree(body == null ? "" : body);
            String sub = text(node.get("sub"));
            String username = text(node.get("username"));
            String nickname = text(node.get("nickname"));
            if (sub == null) {
                throw new OAuthFlowException("USERINFO_INVALID");
            }
            return new OidcIdentity(sub, username, nickname);
        } catch (OAuthFlowException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthFlowException("USERINFO_INVALID");
        }
    }

    private Optional<UUID> findLinkedUser(UUID tenantId, String sub) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT internal_user_id FROM user_identity_link
                    WHERE tenant_id = :tenantId AND idp = :idp AND platform_user_id = :sub
                    """,
                    new MapSqlParameterSource("tenantId", tenantId)
                            .addValue("idp", authProperties.getPlatformOidcIdpCode()).addValue("sub", sub),
                    UUID.class));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private void insertLink(UUID tenantId, UUID internalUserId, String sub) {
        jdbc.update("""
                INSERT INTO user_identity_link (id, tenant_id, internal_user_id, idp, platform_user_id)
                VALUES (:id, :tenantId, :internalUserId, :idp, :sub)
                """,
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenantId", tenantId)
                        .addValue("internalUserId", internalUserId)
                        .addValue("idp", authProperties.getPlatformOidcIdpCode()).addValue("sub", sub));
    }

    /**
     * Auto-provisions the internal user for a platform identity on first login.
     *
     * <p>
     * #1028: the tenant lock is only worth taking while the check and the insert
     * are still inside it, so the read-modify-write runs in one transaction. Under
     * autocommit {@code FOR UPDATE} is released at the end of its own statement,
     * and two concurrent first logins could both see the same username free and
     * collide on {@code uq_users_tenant_username} — surfacing as a 500 rather than
     * as the intended {@code USERNAME_CONFLICT}.
     *
     * <p>
     * The link is re-read once the lock is held: a concurrent winner is adopted
     * there, which also keeps this request from leaving an unlinked user row behind
     * (#730).
     *
     * @return the user this login belongs to plus whether <em>this request</em>
     *         created it — the audit action depends on that difference (#1028)
     */
    private Provisioned provisionUser(UUID tenantId, OidcIdentity identity) {
        try {
            return transactionTemplate.execute(status -> {
                String base = sanitizeUsername(identity.username() != null ? identity.username() : identity.sub());
                userRepository.lockTenantForBootstrap(tenantId);
                Optional<UUID> linked = findLinkedUser(tenantId, identity.sub());
                if (linked.isPresent()) {
                    return new Provisioned(linked.get(), false);
                }
                String username = base;
                int attempt = 0;
                while (attempt++ < 6) {
                    if (!userRepository.existsByTenantIdAndUsername(tenantId, username)) {
                        break;
                    }
                    username = base + "_" + attempt;
                }
                if (userRepository.existsByTenantIdAndUsername(tenantId, username)) {
                    throw new OAuthFlowException("USERNAME_CONFLICT");
                }
                String displayName = identity.nickname() != null ? identity.nickname() : username;
                byte[] randomPassword = HexFormat.of().formatHex(randomBytes(24))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] hash = passwordHasher.hash(new String(randomPassword, java.nio.charset.StandardCharsets.UTF_8));
                Instant now = Instant.now();
                User user = new User(UUID.randomUUID(), tenantId, username, displayName, hash, UserRole.USER,
                        UserStatus.ACTIVE, false, 0, null, null, 0, now, now);
                userRepository.insert(user);
                try {
                    insertLink(tenantId, user.id(), identity.sub());
                } catch (DuplicateKeyException e) {
                    // The link was written by a path that does not take this lock (the
                    // row lock is database-wide, so a second instance is covered — this
                    // is defensive). The failed statement aborted the transaction, so
                    // the winner cannot be looked up in here. Roll this attempt back —
                    // the just-inserted user row included — and let the caller adopt
                    // the winner's link.
                    throw new LinkRacedException();
                }
                return new Provisioned(user.id(), true);
            });
        } catch (LinkRacedException e) {
            return new Provisioned(findLinkedUser(tenantId, identity.sub())
                    .orElseThrow(() -> new OAuthFlowException("ACCOUNT_UNLINKED")), false);
        }
    }

    /**
     * Which user the login belongs to, and whether this request created that user
     * ({@code created == false} means it adopted a link that already existed).
     */
    private record Provisioned(UUID userId, boolean created) {
    }

    /**
     * Signals that a concurrent writer won the identity link; see
     * {@link #provisionUser}.
     */
    private static final class LinkRacedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static String sanitizeUsername(String raw) {
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < USERNAME_MAX; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
        }
        while (sb.length() > 0 && sb.charAt(0) == '_') {
            sb.deleteCharAt(0);
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.deleteCharAt(sb.length() - 1);
        }
        if (sb.length() == 0) {
            return "u_" + HexFormat.of().formatHex(randomBytes(4));
        }
        return sb.toString();
    }

    private static byte[] randomBytes(int n) {
        byte[] out = new byte[n];
        RANDOM.nextBytes(out);
        return out;
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private void clearStateCookie(HttpServletResponse response) {
        Cookie stale = new Cookie(STATE_COOKIE, "");
        stale.setPath("/");
        stale.setMaxAge(0);
        stale.setHttpOnly(true);
        stale.setSecure(authProperties.isCookieSecure());
        response.addCookie(stale);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String v = node.asText().trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static String safeJson(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record OidcIdentity(String sub, String username, String nickname) {
    }
}
