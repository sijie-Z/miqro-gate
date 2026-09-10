package com.miqroera.miqrokey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One encrypted retention event (ADR-0014 v3): envelope metadata in plaintext,
 * the collected user-message text ONLY as ciphertext. Never carry the plain
 * text, prompts of other roles, tool payloads or model replies.
 */
public record RetentionEnvelope(UUID eventId, UUID tenantId, UUID userId, UUID virtualKeyId, String wireProtocol,
        String gatewayRequestId, Instant occurredAt, String keyVersion, byte[] ciphertext, byte[] nonce,
        int textCharCount, boolean truncated) {

    /** Compatibility constructor: not truncated (#367). */
    public RetentionEnvelope(UUID eventId, UUID tenantId, UUID userId, UUID virtualKeyId, String wireProtocol,
            String gatewayRequestId, Instant occurredAt, String keyVersion, byte[] ciphertext, byte[] nonce,
            int textCharCount) {
        this(eventId, tenantId, userId, virtualKeyId, wireProtocol, gatewayRequestId, occurredAt, keyVersion,
                ciphertext, nonce, textCharCount, false);
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
