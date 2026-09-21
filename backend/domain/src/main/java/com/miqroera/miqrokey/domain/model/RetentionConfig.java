package com.miqroera.miqrokey.domain.model;

/**
 * Compliance-retention switch of one tenant (ADR-0014 v3 Accepted, V31).
 * Everything is DEFAULT OFF: a missing row means no request content is ever
 * collected. When enabled, only user message text in scope
 * ({@code USER_TEXT_ONLY}, P1 default) may be captured into a密文 envelope — the
 * CLAUDE.md body-storage red line is waived only behind this row.
 */
public record RetentionConfig(boolean enabled, String contentScope, String keyVersion, long version,
        int maxContentBytes) {

    /** Default per-tenant capture cap (256 KiB). */
    public static final int DEFAULT_MAX_CONTENT_BYTES = 262144;
    public static final int MIN_MAX_CONTENT_BYTES = 1024;
    public static final int MAX_MAX_CONTENT_BYTES = 4194304;

    /** Compatibility constructor: default content cap (#367). */
    public RetentionConfig(boolean enabled, String contentScope, String keyVersion, long version) {
        this(enabled, contentScope, keyVersion, version, DEFAULT_MAX_CONTENT_BYTES);
    }

    public static final String USER_TEXT_ONLY = "USER_TEXT_ONLY";

    public RetentionConfig {
        if (contentScope == null || contentScope.isBlank()) {
            contentScope = USER_TEXT_ONLY;
        }
        if (!USER_TEXT_ONLY.equals(contentScope)) {
            throw new IllegalArgumentException("unsupported retention content scope: " + contentScope);
        }
        if (keyVersion == null || keyVersion.isBlank()) {
            throw new IllegalArgumentException("retention key version is required");
        }
        if (maxContentBytes < MIN_MAX_CONTENT_BYTES || maxContentBytes > MAX_MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException(
                    "maxContentBytes must be " + MIN_MAX_CONTENT_BYTES + ".." + MAX_MAX_CONTENT_BYTES);
        }
    }

    public static RetentionConfig disabled() {
        return new RetentionConfig(false, USER_TEXT_ONLY, "v1", 0, DEFAULT_MAX_CONTENT_BYTES);
    }
}
