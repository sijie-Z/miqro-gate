package com.miqroera.miqrokey.controlplane.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * Path normalization for security decisions (issue #723).
 *
 * <p>
 * Spring MVC matches a request to a handler using the <em>lookup path</em>:
 * semicolon path parameters ({@code ;...}) are removed and percent-encoding is
 * decoded before the URI is compared against a controller mapping. Security
 * filters and interceptors that decide on {@code request.getRequestURI()}
 * instead compare a different value than the one the framework routed on, so
 * {@code /api/v1/admin;x/users} reaches an admin handler while a raw
 * {@code startsWith("/api/v1/admin/")} check still returns false — the
 * deny-by-default gate is skipped (live-proven 2026-09-17).
 * </p>
 *
 * <p>
 * Every path-based security decision must read the path through this helper so
 * the gate and the router always see the same string. Two extra passes over the
 * decoded path close the remaining gaps of the framework helper's ordering (it
 * strips semicolon content <em>before</em> decoding, so an encoded {@code %3B}
 * survives it) and of container-level normalization (duplicate slashes are
 * collapsed before mapping; no route here distinguishes them).
 * </p>
 */
public final class RequestPaths {

    private static final UrlPathHelper HELPER = UrlPathHelper.defaultInstance;

    private RequestPaths() {
    }

    /**
     * The application-relative lookup path — decoded, with semicolon content
     * removed — mirroring what Spring matches handlers against.
     */
    public static String lookupPath(HttpServletRequest request) {
        String path = HELPER.getPathWithinApplication(request);
        if (path == null || path.isEmpty()) {
            return "/";
        }
        path = stripSemicolonContent(path);
        if (path.indexOf("//") >= 0 || path.isEmpty()) {
            path = collapseSlashes(path);
        }
        return path.isEmpty() ? "/" : path;
    }

    /**
     * Removes {@code ;...} up to the next {@code /} (the '/' itself is kept),
     * mirroring {@code UrlPathHelper}'s semicolon-content rule — applied to the
     * decoded path so percent-encoded semicolons cannot slip through.
     */
    private static String stripSemicolonContent(String path) {
        if (path.indexOf(';') < 0) {
            return path;
        }
        StringBuilder out = new StringBuilder(path.length());
        boolean skipping = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (skipping) {
                if (c == '/') {
                    skipping = false;
                } else {
                    continue;
                }
            }
            if (c == ';') {
                skipping = true;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String collapseSlashes(String path) {
        StringBuilder collapsed = new StringBuilder(path.length());
        char previous = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/' && previous == '/') {
                continue;
            }
            collapsed.append(c);
            previous = c;
        }
        return collapsed.toString();
    }
}
