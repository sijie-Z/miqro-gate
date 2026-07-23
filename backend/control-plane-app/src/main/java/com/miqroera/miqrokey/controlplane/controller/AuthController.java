package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.config.AuthProperties;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapResponse;
import com.miqroera.miqrokey.controlplane.dto.CsrfResponse;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginResponse;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.controlplane.dto.UserResponse;
import com.miqroera.miqrokey.controlplane.security.AuthenticationException;
import com.miqroera.miqrokey.controlplane.security.AuthenticationService;
import com.miqroera.miqrokey.controlplane.security.AuthenticationService.UserView;
import com.miqroera.miqrokey.controlplane.security.SessionService;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserSession;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Authentication controller: login, logout, me, password, bootstrap, and CSRF.
 *
 * <p>
 * All responses use RFC 9457 {@code application/problem+json} for errors and
 * {@code application/json} for success. No password, token, or secret ever
 * appears in response bodies beyond the one-time bootstrap temporary password.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationService authenticationService;
    private final SessionService sessionService;
    private final UserContext userContext;
    private final AuthProperties authProperties;

    public AuthController(AuthenticationService authenticationService, SessionService sessionService,
            UserContext userContext, AuthProperties authProperties) {
        this.authenticationService = authenticationService;
        this.sessionService = sessionService;
        this.userContext = userContext;
        this.authProperties = authProperties;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpReq,
            HttpServletResponse httpRes) {
        String requestId = resolveRequestId(httpReq);
        try {
            AuthenticationService.LoginResult result = authenticationService.login(request.username(),
                    request.password(), requestId);
            sessionService.setCookies(httpRes, result.tokens(), result.sessionExpires());

            UserView u = result.user();
            LoginResponse resp = new LoginResponse(u.id().toString(), u.username(), u.displayName(), u.role().name(),
                    u.mustChangePassword(), result.sessionExpires());
            return ResponseEntity.ok(resp);
        } catch (AuthenticationException e) {
            return problemResponse(401, "UNAUTHORIZED", "Authentication failed", e.getMessage(), requestId);
        }
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<?> bootstrap(@Valid @RequestBody BootstrapRequest request, HttpServletRequest httpReq,
            HttpServletResponse httpRes) {
        String requestId = resolveRequestId(httpReq);
        try {
            AuthenticationService.BootstrapResult result = authenticationService.bootstrap(request.bootstrapSecret(),
                    request.username(), request.displayName(), requestId);
            sessionService.setCookies(httpRes, result.tokens(), result.sessionExpires());

            BootstrapResponse resp = new BootstrapResponse(result.user().id().toString(), result.user().username(),
                    result.temporaryPassword(), true, result.sessionExpires());
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        } catch (AuthenticationException e) {
            return problemResponse(401, "UNAUTHORIZED", "Bootstrap failed", e.getMessage(), requestId);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest httpReq, HttpServletResponse httpRes) {
        if (!userContext.isAuthenticated()) {
            return problemResponse(401, "UNAUTHORIZED", "Not authenticated", null, resolveRequestId(httpReq));
        }
        String requestId = resolveRequestId(httpReq);
        authenticationService.logout(userContext.getUser(), userContext.getSession().id(), requestId);
        sessionService.clearCookies(httpRes);
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        if (!userContext.isAuthenticated()) {
            return problemResponse(401, "UNAUTHORIZED", "Not authenticated", null, null);
        }
        User u = userContext.getUser();
        UserSession s = userContext.getSession();
        UserResponse resp = new UserResponse(u.id().toString(), u.username(), u.displayName(), u.role().name(),
                u.status().name(), u.mustChangePassword(), u.lastLoginAt(), s != null ? s.expiresAt() : null);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody PasswordChangeRequest request,
            HttpServletRequest httpReq) {
        if (!userContext.isAuthenticated()) {
            return problemResponse(401, "UNAUTHORIZED", "Not authenticated", null, resolveRequestId(httpReq));
        }
        String requestId = resolveRequestId(httpReq);
        try {
            authenticationService.changePassword(userContext.getUser(), userContext.getSession().id(),
                    request.currentPassword(), request.newPassword(), requestId);
            return ResponseEntity.ok(Map.of("message", "Password changed. Other sessions have been revoked."));
        } catch (AuthenticationException e) {
            return problemResponse(400, "PASSWORD_CHANGE_FAILED", "Password change failed", e.getMessage(), requestId);
        }
    }

    @GetMapping("/csrf")
    public ResponseEntity<?> csrfToken(HttpServletRequest httpReq) {
        if (!userContext.isAuthenticated() || userContext.getSession() == null) {
            return problemResponse(401, "UNAUTHORIZED", "Not authenticated", null, resolveRequestId(httpReq));
        }

        // Extract the raw CSRF token from the cookie using the configured cookie name
        String csrfCookieName = authProperties.getCsrfCookieName();
        String csrfToken = "";
        if (httpReq.getCookies() != null) {
            for (Cookie c : httpReq.getCookies()) {
                if (csrfCookieName.equals(c.getName())) {
                    csrfToken = c.getValue();
                    break;
                }
            }
        }

        CsrfResponse resp = new CsrfResponse(csrfToken, userContext.getSession().expiresAt());
        return ResponseEntity.ok(resp);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ResponseEntity<Map<String, Object>> problemResponse(int status, String code, String title,
            String detail, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        body.put("code", code);
        if (detail != null)
            body.put("detail", detail);
        if (requestId != null)
            body.put("requestId", requestId);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null ? header : UUID.randomUUID().toString();
    }
}
