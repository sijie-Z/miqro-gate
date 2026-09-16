package com.miqroera.miqrokey.domain.model;

/**
 * What happens when a quota rule's live watermark reaches EXCEEDED (#684,
 * ADR-0020). ALERT is the historical behaviour (watermark + webhook only);
 * REJECT makes the gateway answer {@code 429 quota_exceeded} for requests in
 * the rule's scope until the window resets or the limit is raised — the "soft
 * landing" agreed with the product owner (Tencent parity: quota at 100% rejects
 * new requests instead of failing open).
 */
public enum QuotaAction {
    ALERT, REJECT
}
