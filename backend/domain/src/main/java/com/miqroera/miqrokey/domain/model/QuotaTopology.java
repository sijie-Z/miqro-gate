package com.miqroera.miqrokey.domain.model;

/** How quota is structured for a provider product. */
public enum QuotaTopology {
    NONE, GLOBAL_SHARED, MEMBER_ISOLATED, DEDICATED_PLUS_SHARED, KEY_CAPPED
}
