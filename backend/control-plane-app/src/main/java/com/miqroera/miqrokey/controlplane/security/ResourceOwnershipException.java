package com.miqroera.miqrokey.controlplane.security;

/**
 * Thrown when a user attempts to access a resource they do not own.
 *
 * <p>
 * Unlike {@link AuthenticationException} (which maps to 401), this exception
 * maps to 404 to prevent resource enumeration via IDOR. The message is always
 * generic ("Resource not found.") and never reveals whether the resource
 * exists.
 * </p>
 */
public class ResourceOwnershipException extends RuntimeException {
    public ResourceOwnershipException(String message) {
        super(message);
    }
}
