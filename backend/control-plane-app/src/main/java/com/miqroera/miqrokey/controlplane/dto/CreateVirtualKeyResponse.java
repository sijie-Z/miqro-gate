package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response of Virtual Key creation and rotation (api-contract §4).
 *
 * <p>
 * {@code secret} is the full one-time key string: it is returned exactly once
 * and the server never stores or re-serves it. {@code display} is a safe
 * preview (prefix + {@code …} + last four) for later reference.
 * </p>
 */
public record CreateVirtualKeyResponse(UUID id, String secret, String baseUrl, String display, boolean shownOnce,
        Instant createdAt, long version) {
}
