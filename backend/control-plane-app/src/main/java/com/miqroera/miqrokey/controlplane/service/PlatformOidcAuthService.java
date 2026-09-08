package com.miqroera.miqrokey.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

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

    private final AuthProperties authProperties;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RestClient http;

    public PlatformOidcAuthService(AuthProperties authProperties, UserRepository userRepository,
            PasswordHasher passwordHasher, SessionService sessionService, AuditService auditService,
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.http = RestClient.create();
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
            internalUserId = provisionUser(tenantId, identity);
            provisioned = true;
        }
        User user = userRepository.findById(internalUserId)
                .orElseThrow(() -> new OAuthFlowException("ACCOUNT_UNLINKED"));
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

    private String exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", authProperties.getPlatformOidcRedirectUri());
        form.add("client_id", authProperties.getPlatformOidcClientId());
        form.add("client_secret", authProperties.getPlatformOidcClientSecret());
        String body = http.post().uri(URI.create(authProperties.getPlatformOidcTokenUri()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class);
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

    private OidcIdentity fetchUserinfo(String accessToken) {
        String body = http.get().uri(URI.create(authProperties.getPlatformOidcUserinfoUri()))
                .header("Authorization", "Bearer " + accessToken).retrieve().body(String.class);
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

    private UUID provisionUser(UUID tenantId, OidcIdentity identity) {
        String base = sanitizeUsername(identity.username() != null ? identity.username() : identity.sub());
        userRepository.lockTenantForBootstrap(tenantId);
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
        User user = new User(UUID.randomUUID(), tenantId, username, displayName, hash, UserRole.USER, UserStatus.ACTIVE,
                false, 0, null, null, 0, now, now);
        userRepository.insert(user);
        try {
            insertLink(tenantId, user.id(), identity.sub());
        } catch (DuplicateKeyException e) {
            // Concurrent first login for the same sub: reuse the winner's link.
            UUID existing = findLinkedUser(tenantId, identity.sub()).orElse(null);
            if (existing == null) {
                throw e;
            }
        }
        return user.id();
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
