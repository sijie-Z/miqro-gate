package com.miqroera.miqrokey.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service Virtual Key metadata update (api-contract §4, #582): rename. The
 * key's bindings, models and secret are immutable through this endpoint — only
 * the display name changes (audited with from/to).
 */
public record UpdateVirtualKeyRequest(@NotBlank @Size(max = 200) String name) {
}
