package com.miqroera.miqrokey.gateway.vkey;

import org.springframework.http.HttpStatus;

/**
 * Authentication/routing failure carrying the HTTP status and a stable error
 * code for the proxy's error envelope. Thrown by {@link VirtualKeyResolver} and
 * the credential injector; caught in the controllers.
 */
public final class AuthFailureException extends RuntimeException {

    private final int status;
    private final String code;

    public AuthFailureException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status.value();
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
