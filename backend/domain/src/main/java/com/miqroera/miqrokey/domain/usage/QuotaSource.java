package com.miqroera.miqrokey.domain.usage;

/** Authority level of a quota snapshot (provider-adapter-contract §6). */
public enum QuotaSource {
    OFFICIAL_API, LOCAL_ESTIMATE, UNAVAILABLE
}
