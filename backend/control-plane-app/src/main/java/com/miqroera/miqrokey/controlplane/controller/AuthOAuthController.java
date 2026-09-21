package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.service.PlatformOidcAuthService;
import com.miqroera.miqrokey.controlplane.service.PlatformOidcAuthService.ProviderInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Platform OIDC login endpoints (P0a, ADR-0017). All three paths are public:
 * the browser needs them before any session exists. {@code /start} issues the
 * state cookie and redirects to the platform; {@code /callback} completes the
 * code exchange and establishes a normal portal session; errors bounce back to
 * the login page with an ASCII {@code oauth_error} code.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth")
public class AuthOAuthController {

    private final PlatformOidcAuthService oidcService;

    public AuthOAuthController(PlatformOidcAuthService oidcService) {
        this.oidcService = oidcService;
    }

    /** Enabled providers for the login page (empty when the feature is off). */
    @GetMapping("/providers")
    public List<ProviderInfo> providers() {
        return oidcService.providerInfo().map(List::of).orElseGet(List::of);
    }

    /** Starts the authorization-code flow (redirects to the platform). */
    @GetMapping("/start")
    public void start(HttpServletRequest request, HttpServletResponse response) {
        try {
            response.sendRedirect(oidcService.start(request, response));
        } catch (PlatformOidcAuthService.OAuthFlowException e) {
            redirectToLogin(response, e.code());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Platform callback: exchanges the code and creates the portal session. */
    @GetMapping("/callback")
    public void callback(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(required = false) String code, @RequestParam(required = false) String state) {
        try {
            String target = oidcService.complete(request, response, code, state);
            response.sendRedirect(target);
        } catch (PlatformOidcAuthService.OAuthFlowException e) {
            redirectToLogin(response, e.code());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void redirectToLogin(HttpServletResponse response, String errorCode) {
        try {
            response.sendRedirect("/login-new?oauth_error=" + errorCode);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
