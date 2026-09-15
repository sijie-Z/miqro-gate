package com.miqroera.miqrokey.controlplane.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everything a self-service user may pick when creating a Virtual Key
 * ({@code GET /api/v1/me/grants}): their projects (with routing tags), the
 * active credential grants per project (with the granted model IDs and the
 * provider product's display identity), and the available purposes.
 */
public record MeGrantsResponse(List<ProjectOption> projects, List<GrantOption> grants, List<String> purposes) {

    public record ProjectOption(UUID id, String code, String name, String projectTag) {
    }

    /**
     * {@code providerProductCode}/{@code providerProductName} are display fields
     * (null when the product row is missing): a raw product UUID tells the user
     * nothing when picking an entitlement.
     */
    public record GrantOption(UUID id, UUID projectId, UUID providerProductId, Set<String> models,
            String providerProductCode, String providerProductName) {
    }
}
