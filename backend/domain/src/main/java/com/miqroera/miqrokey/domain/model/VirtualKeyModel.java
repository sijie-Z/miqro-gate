package com.miqroera.miqrokey.domain.model;

import java.util.UUID;

/**
 * A model authorization snapshot captured at Virtual Key creation time. Actual
 * availability still requires intersection with the current Grant.
 */
public record VirtualKeyModel(UUID tenantId, UUID virtualKeyId, String modelId) {
}
