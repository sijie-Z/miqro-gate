package com.miqroera.miqrokey.controlplane.dto;

import java.util.UUID;

/**
 * One project a Virtual Key is bound to (ADR-0018). {@code projectTag} is the
 * label that must be appended to the key string to route requests under this
 * project ({@code null} only for legacy rows a migration has not backfilled).
 */
public record BoundProjectView(UUID projectId, String projectTag) {
}
