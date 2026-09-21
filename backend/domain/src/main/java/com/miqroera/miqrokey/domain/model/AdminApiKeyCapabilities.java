package com.miqroera.miqrokey.domain.model;

import java.util.List;
import java.util.Set;

/**
 * F60 batch 3 capability codes for admin API key scopes (ADR-0015 增补). A key
 * with {@code null} scope keeps full access; a scoped key is limited to the
 * listed groups. The open surface maps each request path to exactly one
 * capability (see the auth filter enforcement table).
 */
public final class AdminApiKeyCapabilities {

    public static final String USAGE_READ = "usage:read";
    public static final String ALERTS_WRITE = "alerts:write";
    public static final String EXPORTS_CREATE = "exports:create";
    public static final String VKEYS_DELEGATE = "vkeys:delegate";

    private static final Set<String> ALL = Set.of(USAGE_READ, ALERTS_WRITE, EXPORTS_CREATE, VKEYS_DELEGATE);

    private AdminApiKeyCapabilities() {
    }

    /** Every entry must be a known code; duplicates and nulls are rejected. */
    public static boolean isValid(List<String> capabilities) {
        if (capabilities == null) {
            return true;
        }
        Set<String> seen = java.util.HashSet.newHashSet(capabilities.size());
        for (String code : capabilities) {
            if (code == null || !ALL.contains(code) || !seen.add(code)) {
                return false;
            }
        }
        return true;
    }
}
