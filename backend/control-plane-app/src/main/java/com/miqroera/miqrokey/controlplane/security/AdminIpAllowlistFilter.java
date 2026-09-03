package com.miqroera.miqrokey.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Management-portal IP allowlist (F05, security §6: "管理门户支持配置 IP 白名单"). When
 * {@code miqrokey.control.admin-access.ip-allowlist} is configured, every
 * portal request must originate from an allowlisted address; an empty allowlist
 * keeps the pre-F05 behavior (no restriction).
 *
 * <h2>Proxied deployments</h2> The effective client address comes from
 * {@code X-Forwarded-For} only when the direct peer is one of the trusted
 * proxies ({@code miqrokey.control.admin-access.trusted-proxies}) — an
 * untrusted direct caller cannot forge the header to bypass the list.
 *
 * <h2>Exemptions</h2> The external-system billing channel
 * ({@code /api/v1/billing/**}, API-key/JWT authenticated) and the one-time
 * bootstrap endpoint are outside the portal allowlist semantics.
 */
public class AdminIpAllowlistFilter extends OncePerRequestFilter {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final List<IpCidrMatcher> allowlist;
    private final List<IpCidrMatcher> trustedProxies;

    public AdminIpAllowlistFilter(List<IpCidrMatcher> allowlist, List<IpCidrMatcher> trustedProxies) {
        this.allowlist = allowlist;
        this.trustedProxies = trustedProxies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (allowlist.isEmpty() || isExempt(request) || allowlisted(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(403);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                + "\"code\":\"IP_NOT_ALLOWED\",\"requestId\":\"" + requestId(request) + "\"}");
    }

    /** Billing channel and the guarded one-time bootstrap stay reachable. */
    private static boolean isExempt(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/billing/") || path.equals("/api/v1/auth/bootstrap");
    }

    private boolean allowlisted(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        boolean trustedPeer = trustedProxies.stream().anyMatch(m -> m.matches(peer));
        if (trustedPeer) {
            String forwarded = firstForwarded(request.getHeader(X_FORWARDED_FOR));
            if (forwarded != null) {
                return allowlist.stream().anyMatch(m -> m.matches(forwarded));
            }
        }
        return allowlist.stream().anyMatch(m -> m.matches(peer));
    }

    /** Leftmost address of X-Forwarded-For (the client that started the chain). */
    private static String firstForwarded(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String first = header.split(",", -1)[0].trim();
        return first.isEmpty() || "unknown".equalsIgnoreCase(first) ? null : first;
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
