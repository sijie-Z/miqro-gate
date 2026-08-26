package com.miqroera.miqrokey.gateway.vkey;

import com.miqroera.miqrokey.domain.crypto.VirtualKeyCrypto;
import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;
import com.miqroera.miqrokey.domain.route.RouteSnapshot;
import com.miqroera.miqrokey.domain.vkey.VirtualKeyParseResult;
import com.miqroera.miqrokey.domain.vkey.VirtualKeyParser;
import com.miqroera.miqrokey.route.RouteSnapshotProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Authenticates presented virtual keys on the hot path.
 *
 * <p>
 * Pipeline (all in-memory, no blocking I/O):
 * <ol>
 * <li>Credential headers: exactly one of {@code Authorization},
 * {@code x-api-key}, {@code api-key}. Zero → 401; several → 401.</li>
 * <li>Parse the label-routing format (fixed-length, strict base64url) → 404 on
 * any malformed input (uniform {@code VIRTUAL_KEY_INVALID}).</li>
 * <li>Look up the public key id in the current route snapshot → 404 when
 * unknown.</li>
 * <li>Constant-time HMAC validation against the stored digest (all key versions
 * traversed, no early exit) → 404 on mismatch.</li>
 * <li>Resolve the key's single ACTIVE label binding; the presented label must
 * equal the binding's tag → 404 on mismatch (the binding is the authorization
 * authority, the label only routes).</li>
 * </ol>
 *
 * <p>
 * Failure reasons are deliberately indistinguishable (uniform 404) so probing a
 * gateway never reveals whether a key exists, is malformed, or was revoked.
 * </p>
 */
@Component
public class VirtualKeyResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private final RouteSnapshotProvider routeSnapshotProvider;
    private final ObjectProvider<VirtualKeyCrypto> virtualKeyCrypto;

    public VirtualKeyResolver(RouteSnapshotProvider routeSnapshotProvider,
            ObjectProvider<VirtualKeyCrypto> virtualKeyCrypto) {
        this.routeSnapshotProvider = routeSnapshotProvider;
        this.virtualKeyCrypto = virtualKeyCrypto;
    }

    /**
     * Resolves the presented key to an authenticated context, or throws
     * {@link AuthFailureException}. The raw secret is zero-filled on every exit
     * path.
     */
    public AuthContext resolve(ServerHttpRequest request) {
        String presented = extractCredential(request);
        VirtualKeyParseResult parsed = VirtualKeyParser.parse(presented);
        if (!parsed.valid()) {
            return invalid();
        }
        try {
            RouteSnapshot snapshot = routeSnapshotProvider.current();
            RouteSnapshot.KeyRecord key = snapshot.key(parsed.publicKeyId());
            if (key == null) {
                return invalid();
            }
            VirtualKeyCrypto crypto = virtualKeyCrypto.getIfAvailable();
            if (crypto == null) {
                // Crypto subsystem unavailable (persistence disabled): fail closed.
                return invalid();
            }
            boolean matched = crypto.validateConstantTime(parsed.publicKeyId(), parsed.rawSecret(), key.secretDigest(),
                    key.tenantId());
            if (!matched) {
                return invalid();
            }
            RouteSnapshot.BindingRecord binding = snapshot.binding(key.keyId());
            if (binding == null) {
                return invalid();
            }
            if (binding.projectTag() == null || !binding.projectTag().equals(parsed.projectTag())) {
                // The presented label does not match the binding's tag: the key
                // is valid but not routable under this label.
                return invalid();
            }
            Set<String> models = snapshot.models(key.keyId());
            return new AuthContext(key, binding, models, snapshot);
        } finally {
            SecretWiping.clearArray(parsed.rawSecret());
        }
    }

    /**
     * Extracts the single credential header value. More than one distinct
     * credential header (e.g. both Authorization and x-api-key) is rejected —
     * ambiguity is an authentication failure.
     */
    private String extractCredential(ServerHttpRequest request) {
        List<String> values = request.getHeaders().getValuesAsList("Authorization");
        values = new java.util.ArrayList<>(values);
        for (String name : List.of("x-api-key", "api-key")) {
            List<String> extra = request.getHeaders().getValuesAsList(name);
            for (String v : extra) {
                if (v != null && !v.isBlank()) {
                    values.add(v);
                }
            }
        }
        // Also drop blank Authorization entries.
        values.removeIf(v -> v == null || v.isBlank());
        if (values.isEmpty()) {
            throw new AuthFailureException(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Missing virtual key: provide exactly one credential header (Authorization, x-api-key, or api-key)");
        }
        if (values.size() > 1) {
            throw new AuthFailureException(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Conflicting credential headers: present exactly one credential header");
        }
        String value = values.get(0).trim();
        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            value = value.substring(BEARER_PREFIX.length());
        } else if (value.regionMatches(true, 0, "Bearer", 0, "Bearer".length())) {
            // Bare "Bearer" (no space, no token): the same empty-credential class.
            value = value.substring("Bearer".length());
        }
        value = value.trim();
        if (value.isEmpty()) {
            // "Bearer " and bare "Bearer" are indistinguishable from a missing
            // credential: the prefix strip runs before the final trim so a blank
            // token never falls through to the 404 parse path.
            throw new AuthFailureException(HttpStatus.UNAUTHORIZED, "unauthorized", "Empty credential header value");
        }
        return value;
    }

    private AuthContext invalid() {
        throw new AuthFailureException(HttpStatus.NOT_FOUND, "virtual_key_invalid", "Unknown virtual key");
    }
}
