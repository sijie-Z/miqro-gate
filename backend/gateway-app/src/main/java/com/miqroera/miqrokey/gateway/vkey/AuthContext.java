package com.miqroera.miqrokey.gateway.vkey;

import com.miqroera.miqrokey.domain.route.RouteSnapshot;

import java.util.Set;
import java.util.UUID;

/**
 * Authenticated request context: the resolved virtual key, its single ACTIVE
 * label binding, the key's allowed model set, and the route snapshot version
 * the resolution ran against — so model authorization reads all four inputs
 * (signed catalog, upstream models, grant models, key models) from one
 * consistent snapshot. Immutable; safe to share across the reactive chain.
 */
public record AuthContext(RouteSnapshot.KeyRecord key, RouteSnapshot.BindingRecord binding, Set<String> models,
        RouteSnapshot snapshot) {

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
