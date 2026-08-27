package com.miqroera.miqrokey.adapters.catalog;

/**
 * Thrown when a catalog fails signature verification. Never carries catalog
 * content beyond a fixed description.
 */
public class CatalogSignatureException extends Exception {

    public CatalogSignatureException(String message) {
        super(message);
    }

    public CatalogSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
