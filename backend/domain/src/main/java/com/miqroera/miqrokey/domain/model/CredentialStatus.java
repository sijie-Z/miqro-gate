package com.miqroera.miqrokey.domain.model;

/** Upstream credential lifecycle status. */
public enum CredentialStatus {
    PENDING_VALIDATION, ACTIVE, DRAINING, DISABLED, INVALID
}
