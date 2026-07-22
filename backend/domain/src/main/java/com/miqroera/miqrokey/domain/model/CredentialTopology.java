package com.miqroera.miqrokey.domain.model;

/** How credentials are structured for a provider product. */
public enum CredentialTopology {
    SINGLE_SHARED, MULTI_KEY_SHARED_POOL, PER_SEAT_KEY, PER_MEMBER_SUBSCRIPTION_KEY
}
