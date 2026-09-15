package com.miqroera.miqrokey.controlplane.client;

/** Sanitized price-source failure (message never carries the URL or body). */
public class PriceSourceException extends RuntimeException {

    private final String code;

    public PriceSourceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
