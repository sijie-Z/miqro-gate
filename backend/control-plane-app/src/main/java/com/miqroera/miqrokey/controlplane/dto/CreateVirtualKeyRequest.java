package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Self-service Virtual Key creation request (api-contract §4). A key is fixedly
 * bound to one project, one provider product (via the credential grant), one
 * purpose, and optionally a model allowlist.
 *
 * @param allowedModels
 *            model IDs to authorize on the key; empty/absent grants all models
 *            of the credential grant
 * @param cachePolicy
 *            {@code "DISABLED"} (default) or {@code "ENABLED"} (ADR-0008 opt-in
 *            caching)
 */
public record CreateVirtualKeyRequest(@Size(max = 200) String name, @NotNull UUID projectId,
        @NotNull UUID providerProductId, @NotNull UUID credentialGrantId, @NotNull VirtualKeyPurpose purpose,
        List<@Size(max = 128) String> allowedModels, @Pattern(regexp = "DISABLED|ENABLED") String cachePolicy) {
}
