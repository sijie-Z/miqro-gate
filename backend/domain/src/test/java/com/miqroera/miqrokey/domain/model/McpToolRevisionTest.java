package com.miqroera.miqrokey.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F16 revision snapshot semantics (V33): the field-level diff is computed on
 * read (issue #354) and the baseline revision has nothing to diff against.
 */
@DisplayName("McpToolRevision")
class McpToolRevisionTest {

    @Test
    @DisplayName("changedFieldsVs reports each differing field, in a stable order")
    void changedFieldsVsDetects() {
        McpToolRevision baseline = revision(1, "a", "GET", "/x");
        McpToolRevision edited = revision(2, "b", "POST", "/x");

        assertThat(baseline.changedFieldsVs(null)).isEmpty();
        assertThat(edited.changedFieldsVs(baseline)).containsExactly("description", "method");
        assertThat(revision(3, "b", "POST", "/x").changedFieldsVs(edited)).isEmpty();
        assertThat(revision(4, "b", "POST", "/y").changedFieldsVs(edited)).containsExactly("path");
    }

    @Test
    @DisplayName("the compatibility constructor leaves the computed diff empty")
    void compatibilityConstructor() {
        McpToolRevision stored = new McpToolRevision(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "desc",
                "GET", "/x", UUID.randomUUID(), Instant.now(), null);

        assertThat(stored.changedFields()).isEmpty();
    }

    private static McpToolRevision revision(long number, String description, String method, String path) {
        return new McpToolRevision(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), number, description, method,
                path, UUID.randomUUID(), Instant.now(), Instant.now());
    }
}
