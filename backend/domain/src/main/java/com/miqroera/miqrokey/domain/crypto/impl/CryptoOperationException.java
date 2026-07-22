package com.miqroera.miqrokey.domain.crypto.impl;

public class CryptoOperationException extends RuntimeException {

    public CryptoOperationException(String message) {
        super(message);
    }

    public CryptoOperationException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String toString() {
        return "CryptoOperationException[" + getMessage() + "]";
    }
}
