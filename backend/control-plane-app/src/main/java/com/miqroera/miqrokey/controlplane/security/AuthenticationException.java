package com.miqroera.miqrokey.controlplane.security;

/**
 * Thrown when authentication fails. The message is always a generic user-facing
 * error — never reveals whether the username exists or the specific reason for
 * failure.
 */
public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}
