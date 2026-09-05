package com.miqroera.miqrokey.domain.model;

/**
 * Compliance-retention switch of one tenant (ADR-0014 v3 Accepted, V31).
 * Everything is DEFAULT OFF: a missing row means no request content is ever
 * collected. When enabled, only user message text in scope
 * ({@code USER_TEXT_ONLY}, P1 default) may be captured into a密文 envelope — the
 * CLAUDE.md body-storage red line is waived only behind this row.
 */
public record RetentionConfig(boolean enabled, String contentScope, String keyVersion, long version) {

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
    }

    public static RetentionConfig disabled() {
        return new RetentionConfig(false, USER_TEXT_ONLY, "v1", 0);
    }
}
