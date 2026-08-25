package com.miqroera.miqrokey.spi;

/**
 * A single model as reported by a provider product.
 *
 * @param id
 *            provider model id used in requests
 * @param displayName
 *            optional human-readable name (may equal {@code id})
 */
public record ModelDefinition(String id, String displayName) {

    public ModelDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    public ModelDefinition(String id) {
        this(id, id);
    }
}
