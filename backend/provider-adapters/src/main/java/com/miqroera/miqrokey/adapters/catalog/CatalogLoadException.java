package com.miqroera.miqrokey.adapters.catalog;

/**
 * Thrown when a catalog cannot be loaded at all (missing resources, signature
 * failure, schema violation). The message is safe to surface to operators.
 */
public class CatalogLoadException extends RuntimeException {

    public CatalogLoadException(String message) {
        super(message);
    }

    public CatalogLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
