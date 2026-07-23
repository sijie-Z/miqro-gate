package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserSession;
import com.miqroera.miqrokey.domain.repository.UserSessionRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages server-side authentication sessions.
 *
 * <p>
 * Sessions are identified by a random 256-bit token. The raw token is set as an
 * HttpOnly/Secure/SameSite cookie. Only the SHA-256 digest of the token is
 * stored in the database. CSRF tokens follow the same pattern.
 * </p>
 */
@Service
@Transactional
public class SessionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    static final int TOKEN_BYTES = 32;

    private final UserSessionRepository sessionRepository;
    private final AuthProperties authProperties;

    public SessionService(UserSessionRepository sessionRepository, AuthProperties authProperties) {
        this.sessionRepository = sessionRepository;
        this.authProperties = authProperties;
    }

    /**
     * Create a new session for a user.
     *
     * @return both the raw session token (HttpOnly) and CSRF token (JS-readable)
     */
    public SessionToken createSession(User user) {
        byte[] sessionBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(sessionBytes);
        byte[] csrfBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(csrfBytes);

        byte[] tokenDigest = sha256(sessionBytes);
        byte[] csrfDigest = sha256(csrfBytes);

        Instant now = Instant.now();
        UserSession session = new UserSession(UUID.randomUUID(), user.tenantId(), user.id(), tokenDigest, csrfDigest,
                now, now, now.plus(authProperties.getSessionAbsoluteTimeout()), null);
        sessionRepository.insert(session);

        String sessionToken = bytesToHex(sessionBytes);
        String csrfToken = bytesToHex(csrfBytes);

        // Zero-fill raw byte arrays
        Arrays.fill(sessionBytes, (byte) 0);
        Arrays.fill(csrfBytes, (byte) 0);

        return new SessionToken(sessionToken, csrfToken);
    }

    /** Look up a session by raw token value (hashes and queries the DB). */
    @Transactional(readOnly = true)
    public Optional<UserSession> findByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        byte[] tokenBytes = hexToBytes(rawToken);
        if (tokenBytes == null || tokenBytes.length != TOKEN_BYTES) {
            return Optional.empty();
        }
        byte[] digest = sha256(tokenBytes);
        Arrays.fill(tokenBytes, (byte) 0);
        return sessionRepository.findByTokenDigest(digest);
    }

    /** Revoke a single session. */
    public void revokeSession(UUID sessionId) {
        sessionRepository.revoke(sessionId, Instant.now());
    }

    /** Revoke all sessions for a user except the current one. */
    public void revokeOtherSessions(UUID userId, UUID currentSessionId) {
        Instant now = Instant.now();
        for (UserSession s : sessionRepository.findActiveByUserId(userId, now)) {
            if (!s.id().equals(currentSessionId)) {
                sessionRepository.revoke(s.id(), now);
            }
        }
    }

    /** Touch a session (update last_seen_at). */
    public void touch(UUID sessionId) {
        sessionRepository.touch(sessionId, Instant.now());
    }

    /** Set session and CSRF cookies on the response. */
    public void setCookies(HttpServletResponse response, SessionToken tokens, Instant expiresAt) {
        long maxAge = Math.max(0, expiresAt.getEpochSecond() - Instant.now().getEpochSecond());
        boolean secure = authProperties.isCookieSecure();

        Cookie sessionCookie = new Cookie(authProperties.getSessionCookieName(), tokens.sessionToken());
        sessionCookie.setHttpOnly(true);
        sessionCookie.setSecure(secure);
        sessionCookie.setPath("/");
        sessionCookie.setMaxAge((int) maxAge);
        sessionCookie.setAttribute("SameSite", "Strict");
        response.addCookie(sessionCookie);

        Cookie csrfCookie = new Cookie(authProperties.getCsrfCookieName(), tokens.csrfToken());
        csrfCookie.setHttpOnly(false);
        csrfCookie.setSecure(secure);
        csrfCookie.setPath("/");
        csrfCookie.setMaxAge((int) maxAge);
        csrfCookie.setAttribute("SameSite", "Strict");
        response.addCookie(csrfCookie);
    }

    /** Clear both session cookies with identical security attributes. */
    public void clearCookies(HttpServletResponse response) {
        boolean secure = authProperties.isCookieSecure();

        Cookie sessionCookie = new Cookie(authProperties.getSessionCookieName(), "");
        sessionCookie.setHttpOnly(true);
        sessionCookie.setSecure(secure);
        sessionCookie.setPath("/");
        sessionCookie.setMaxAge(0);
        sessionCookie.setAttribute("SameSite", "Strict");
        response.addCookie(sessionCookie);

        Cookie csrfCookie = new Cookie(authProperties.getCsrfCookieName(), "");
        csrfCookie.setHttpOnly(false);
        csrfCookie.setSecure(secure);
        csrfCookie.setPath("/");
        csrfCookie.setMaxAge(0);
        csrfCookie.setAttribute("SameSite", "Strict");
        response.addCookie(csrfCookie);
    }

    /** Extract session token from request cookie. */
    public String extractSessionToken(HttpServletRequest request) {
        if (request.getCookies() == null)
            return null;
        for (Cookie c : request.getCookies()) {
            if (authProperties.getSessionCookieName().equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /** Verify that a CSRF header token matches a session's CSRF digest. */
    public boolean verifyCsrf(String headerToken, byte[] csrfDigest) {
        if (headerToken == null || headerToken.isBlank() || csrfDigest == null) {
            return false;
        }
        byte[] tokenBytes = hexToBytes(headerToken);
        if (tokenBytes == null)
            return false;
        byte[] computed = sha256(tokenBytes);
        Arrays.fill(tokenBytes, (byte) 0);
        boolean match = MessageDigest.isEqual(computed, csrfDigest);
        Arrays.fill(computed, (byte) 0);
        return match;
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0)
            return null;
        try {
            byte[] result = new byte[hex.length() / 2];
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return result;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
