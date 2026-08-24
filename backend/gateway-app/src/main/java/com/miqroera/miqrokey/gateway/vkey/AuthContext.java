package com.miqroera.miqrokey.gateway.vkey;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;

import java.util.Set;
import java.util.UUID;

/**
 * Authenticated request context: the resolved virtual key, its single ACTIVE
 * label binding, and the key's allowed model set. Immutable; safe to share
 * across the reactive chain.
 */
public record AuthContext(RouteSnapshot.KeyRecord key, RouteSnapshot.BindingRecord binding, Set<String> models) {

    public UUID tenantId() {
        return key.tenantId();
    }

    public UUID projectId() {
        return binding.projectId();
    }

    public UUID productId() {
        return binding.productId();
    }
}
