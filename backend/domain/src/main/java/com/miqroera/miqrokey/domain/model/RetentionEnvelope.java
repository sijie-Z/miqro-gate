package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One encrypted retention event (ADR-0014 v3 + 增补 2026-09-15): envelope
 * metadata in plaintext, the collected message text ONLY as ciphertext.
 * {@code direction} distinguishes the user side (INPUT) from the model reply
 * (OUTPUT). Never carries plaintext, system prompts or tool payloads.
 */
public record RetentionEnvelope(UUID eventId, UUID tenantId, UUID userId, UUID virtualKeyId, String wireProtocol,
        String gatewayRequestId, Instant occurredAt, String keyVersion, byte[] ciphertext, byte[] nonce,
        int textCharCount, boolean truncated, RetentionDirection direction) {

    /** Compatibility constructor: INPUT side, truncated flag explicit. */
    public RetentionEnvelope(UUID eventId, UUID tenantId, UUID userId, UUID virtualKeyId, String wireProtocol,
            String gatewayRequestId, Instant occurredAt, String keyVersion, byte[] ciphertext, byte[] nonce,
            int textCharCount, boolean truncated) {
        this(eventId, tenantId, userId, virtualKeyId, wireProtocol, gatewayRequestId, occurredAt, keyVersion,
                ciphertext, nonce, textCharCount, truncated, RetentionDirection.INPUT);
    }

    /** Compatibility constructor: INPUT side, not truncated (#367). */
    public RetentionEnvelope(UUID eventId, UUID tenantId, UUID userId, UUID virtualKeyId, String wireProtocol,
            String gatewayRequestId, Instant occurredAt, String keyVersion, byte[] ciphertext, byte[] nonce,
            int textCharCount) {
        this(eventId, tenantId, userId, virtualKeyId, wireProtocol, gatewayRequestId, occurredAt, keyVersion,
                ciphertext, nonce, textCharCount, false, RetentionDirection.INPUT);
    }

    public RetentionEnvelope {
        ciphertext = ciphertext.clone();
        nonce = nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }
}
