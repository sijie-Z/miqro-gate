package com.miqroera.miqrokey.controlplane.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public skill catalog view (P2.3): metadata only, never the package bytes.
 * Download grants are managed on the admin view; the catalog itself is visible
 * to every signed-in user.
 */
public record SkillView(UUID id, String name, String description, String version, String author, String license,
        List<String> tags, String contentSha256, long contentBytes, String status, Instant createdAt) {

    /** Shown as "X MB" in the UI. */
    public BigDecimal contentMegabytes() {
        return BigDecimal.valueOf(contentBytes).movePointLeft(6).setScale(1, java.math.RoundingMode.HALF_UP);
    }
}
