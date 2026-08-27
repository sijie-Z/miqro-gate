package com.miqroera.miqrokey.domain.usage;

/**
 * Unit of a quota snapshot value. {@code UNKNOWN} when the source does not
 * state one.
 */
public enum QuotaUnit {
    POINTS, TOKENS, REQUESTS, CURRENCY, UNKNOWN
}
