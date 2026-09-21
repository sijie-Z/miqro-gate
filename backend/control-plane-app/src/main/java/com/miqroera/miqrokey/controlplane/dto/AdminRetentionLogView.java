package com.miqroera.miqrokey.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One decrypted retention-log row for the admin console (ADR-0014 §8): envelope
 * metadata plus the decrypted message text and its MD5 (the
 * Tencent-console-style {@code DataMd5} column). {@code text} is null when the
 * row's key version can no longer be decrypted.
 */
public record AdminRetentionLogView(UUID eventId, UUID userId, String userName, UUID virtualKeyId, String wireProtocol,
        String direction, String gatewayRequestId, Instant occurredAt, int textCharCount, boolean truncated,
        String dataMd5, String text) {
}
