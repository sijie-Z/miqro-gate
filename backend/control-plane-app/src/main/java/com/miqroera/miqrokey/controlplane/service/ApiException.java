package com.miqroera.miqrokey.controlplane.service;

import org.springframework.http.HttpStatus;

/**
 * Business-rule violation in a control-plane service. Carries the HTTP status
 * and a stable error {@code code} so the API can return RFC 9457 Problem
 * Details without leaking internals.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
