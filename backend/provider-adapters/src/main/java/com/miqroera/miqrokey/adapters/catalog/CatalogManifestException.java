package com.miqroera.miqrokey.adapters.catalog;

/**
 * Thrown when a catalog manifest violates the schema. The message lists every
 * violation found; it never echoes catalog payload content beyond field names
 * and offending values, which are configuration data, not secrets.
 */
public class CatalogManifestException extends Exception {

    public CatalogManifestException(String message) {
        super(message);
    }

    public CatalogManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
