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
 * untrusted direct caller cannot forge the header to bypass the list. Within a
 * trusted chain the address is derived by walking the header from the RIGHT
 * (the nearest proxy appends last — #445): every trusted-proxy entry is
 * skipped, and the first non-trusted entry is the client the nearest trusted
 * proxy actually observed. Client-supplied text can therefore never become the
 * decided address, for both append-style ({@code $proxy_add_x_forwarded_for})
 * and replace-style proxy configurations.
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
        String client = effectiveClientAddress(request);
        return client != null && allowlist.stream().anyMatch(m -> m.matches(client));
    }

    /**
     * The address the allowlist must decide on (#445): the direct peer unless it is
     * one of our trusted proxies, in which case the X-Forwarded-For chain is walked
     * from the RIGHT — trusted-proxy entries are skipped and the first non-trusted
     * entry (what the nearest trusted proxy actually saw) wins. Rightmost-walk is
     * correct for both append- and replace-style proxies; trusting the LEFTMOST
     * entry let any client prepend a forged allowlisted address.
     */
    private String effectiveClientAddress(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!isTrusted(peer)) {
            return peer;
        }
        String header = request.getHeader(X_FORWARDED_FOR);
        if (header == null || header.isBlank()) {
            return peer;
        }
        String[] parts = header.split(",", -1);
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].trim();
            if (candidate.isEmpty() || "unknown".equalsIgnoreCase(candidate)) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        // The whole chain is our own infrastructure — fall back to the peer.
        return peer;
    }

    private boolean isTrusted(String address) {
        return address != null && trustedProxies.stream().anyMatch(m -> m.matches(address));
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        String value = header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
        // #445: same escaping the sibling filters use — the header is
        // client-controlled and must not break out of the JSON string.
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
