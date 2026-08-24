package com.miqroera.miqrokey.controlplane.dto;

import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import com.miqroera.miqrokey.domain.model.VirtualKeyStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Safe metadata view of a Virtual Key for list/detail endpoints. Never contains
 * the secret — only the prefix, the last four characters, and the routing
 * label's preview.
 */
public record VirtualKeyView(UUID id, String name, VirtualKeyPurpose purpose, VirtualKeyStatus status,
        String displayPrefix, String lastFour, String display, Set<String> modelIds, UUID projectId, String projectTag,
        String cachePolicy, String baseUrl, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
}
