package com.miqroera.miqrokey.domain.model;

/** Immutable credential version lifecycle status. */
public enum CredentialVersionStatus {
    PENDING_VALIDATION, ACTIVE, DRAINING, RETIRED, INVALID
}
