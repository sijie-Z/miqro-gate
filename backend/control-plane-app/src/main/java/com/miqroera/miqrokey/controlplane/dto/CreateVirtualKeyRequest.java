package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Self-service Virtual Key creation request (api-contract §4). A key is fixedly
 * bound to one user, one provider product (via the credential grant), one
 * purpose, and one or MORE projects (ADR-0018: {@code projectIds}, first entry
 * = primary).
 *
 * @param projectId
 *            legacy single-project field; used only when {@code projectIds} is
 *            absent/empty
 * @param projectIds
 *            every project the key may serve; the first one is the primary (its
 *            grant is the explicitly chosen {@code credentialGrantId})
 * @param allowedModels
 *            model IDs to authorize on the key; empty/absent grants all models
 *            of the credential grant
 * @param cachePolicy
 *            {@code "DISABLED"} (default) or {@code "ENABLED"} (ADR-0008 opt-in
 *            caching)
 */
public record CreateVirtualKeyRequest(@Size(max = 200) String name, UUID projectId, List<UUID> projectIds,
        @NotNull UUID providerProductId, @NotNull UUID credentialGrantId, @NotNull VirtualKeyPurpose purpose,
        List<@Size(max = 128) String> allowedModels, @Pattern(regexp = "DISABLED|ENABLED") String cachePolicy) {
}
