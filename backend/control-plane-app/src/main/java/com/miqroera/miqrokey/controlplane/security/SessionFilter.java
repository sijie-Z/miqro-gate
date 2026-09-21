package com.miqroera.miqrokey.controlplane.security;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserSession;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Servlet filter that validates the session token on every request to
 * {@code /api/v1/**} (except public endpoints).
 *
 * <p>
 * Additional checks performed on every authenticated request:
 * <ol>
 * <li><b>User status:</b> DISABLED users are rejected (401). LOCKED users whose
 * lock has not expired are rejected (401).</li>
 * <li><b>Idle timeout:</b> If the session's {@code lastSeenAt} exceeds the
 * configured idle timeout, the session is revoked and the request is rejected
 * (401).</li>
 * <li><b>Absolute expiry:</b> Checked via {@link UserSession#isValid}.</li>
 * <li><b>Password change gate:</b> If {@code mustChangePassword} is true, only
 * {@code /api/v1/auth/password}, {@code /api/v1/auth/logout},
 * {@code /api/v1/auth/me}, and {@code /api/v1/auth/csrf} are allowed.</li>
 * </ol>
 * </p>
 */
public class SessionFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(SessionFilter.class);

    private final SessionService sessionService;
    private final UserRepository userRepository;
    private final UserContext userContext;
    private final AuthProperties authProperties;

    /** Public paths that do not require authentication. */
    private static final String[] PUBLIC_PATHS = {"/api/v1/auth/login", "/api/v1/auth/bootstrap",
            "/api/v1/auth/register", "/api/v1/auth/registration-status", "/api/v1/auth/oauth/providers",
            "/api/v1/auth/oauth/start", "/api/v1/auth/oauth/callback"};

    public SessionFilter(SessionService sessionService, UserRepository userRepository, UserContext userContext,
            AuthProperties authProperties) {
        this.sessionService = sessionService;
        this.userRepository = userRepository;
        this.userContext = userContext;
        this.authProperties = authProperties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;
        String path = RequestPaths.lookupPath(httpReq);

        // Skip public paths
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Only apply to API paths
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        String rawToken = sessionService.extractSessionToken(httpReq);
        if (rawToken == null) {
            // Machine channels authenticate without a session: the external-system
            // channel (/api/v1/billing) via ApiKeyAuthFilter and the open admin
            // surface (/api/v1/admin-api, ADR-0015) via AdminApiKeyAuthFilter —
            // let both through here, they enforce their own credentials.
            if (path.startsWith(ApiKeyAuthFilter.BILLING_PATH) || path.startsWith(AdminApiKeyAuthFilter.OPEN_PATH)) {
                chain.doFilter(httpReq, httpRes);
                return;
            }
            sendUnauthorized(httpRes, "Authentication required", "UNAUTHORIZED");
            return;
        }

        Optional<UserSession> sessionOpt = sessionService.findByToken(rawToken);
        if (sessionOpt.isEmpty() || !sessionOpt.get().isValid(Instant.now())) {
            sendUnauthorized(httpRes, "Session expired or invalid", "SESSION_INVALID");
            return;
        }

        UserSession session = sessionOpt.get();
        Optional<User> userOpt = userRepository.findById(session.userId());
        if (userOpt.isEmpty()) {
            sendUnauthorized(httpRes, "User not found", "UNAUTHORIZED");
            return;
        }

        User user = userOpt.get();
        Instant now = Instant.now();

        // --- User status checks ---
        if (user.status() == UserStatus.DISABLED) {
            // Revoke the session so it cannot be replayed
            try {
                sessionService.revokeSession(session.id());
            } catch (Exception e) {
                // PH45: the 401 is still the right answer to this request, but a
                // revocation that failed leaves the session usable although the
                // account is disabled — that must not happen silently.
                LOG.error("Session revocation failed for a DISABLED account [userId={}, sessionId={}]", user.id(),
                        session.id(), e);
            }
            sendUnauthorized(httpRes, "Account disabled", "UNAUTHORIZED");
            return;
        }
        // #445: LOCKED with a null deadline is an indefinite admin lock; the
        // old condition only recognized timed (auto-lock) entries and let an
        // admin-locked account keep using its session.
        if (user.status() == UserStatus.LOCKED && (user.lockedUntil() == null || now.isBefore(user.lockedUntil()))) {
            try {
                sessionService.revokeSession(session.id());
            } catch (Exception e) {
                // PH45: same as the DISABLED branch — an unrevoked session of a
                // locked account stays replayable, so the failure is recorded.
                LOG.error("Session revocation failed for a LOCKED account [userId={}, sessionId={}]", user.id(),
                        session.id(), e);
            }
            sendUnauthorized(httpRes, "Account locked", "UNAUTHORIZED");
            return;
        }

        // --- Idle timeout ---
        Duration idleTime = Duration.between(session.lastSeenAt(), now);
        if (idleTime.compareTo(authProperties.getSessionIdleTimeout()) > 0) {
            sessionService.revokeSession(session.id());
            sendUnauthorized(httpRes, "Session expired due to inactivity", "SESSION_EXPIRED");
            return;
        }

        // --- mustChangePassword gate ---
        if (user.mustChangePassword()) {
            if (!AuthenticationService.PASSWORD_CHANGE_ALLOWED.contains(path)) {
                sendUnauthorized(httpRes, "Password change required", "PASSWORD_CHANGE_REQUIRED");
                return;
            }
        }

        // Store context
        userContext.setUser(user);
        userContext.setSession(session);

        // Touch last_seen_at (fire-and-forget)
        try {
            sessionService.touch(session.id());
        } catch (Exception e) {
            LOG.debug("Failed to touch session: {}", e.getMessage());
        }

        chain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse httpRes, String title, String code) throws IOException {
        String requestId = java.util.UUID.randomUUID().toString();
        try {
            httpRes.setStatus(401);
            httpRes.setContentType("application/problem+json");
            httpRes.getWriter().write(ProblemJson.of(401, title, code, null, requestId));
        } catch (Exception e) {
            LOG.error("Failed to write unauthorized response", e);
        }
    }

    private boolean isPublicPath(String path) {
        for (String pp : PUBLIC_PATHS) {
            if (path.equals(pp))
                return true;
        }
        return false;
    }

    @Override
    public void destroy() {
    }
}
