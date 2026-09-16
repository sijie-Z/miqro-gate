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
    private final Long retryAfterSeconds;

    public AuthFailureException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    /**
     * @param retryAfterSeconds
     *            when the condition can lift on its own (quota windows, #684):
     *            emitted as the {@code Retry-After} header so clients back off
     *            instead of hammering; null for permanent failures.
     */
    public AuthFailureException(HttpStatus status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status.value();
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Seconds until the client may retry, or null when there is no such hint. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
