package com.miqroera.miqrokey.domain.model;

/**
 * Lifecycle of a quota rule. DISABLED keeps the plan for later re-use while
 * removing it from the watermark view's warning semantics (the row still shows
 * its current usage).
 */
public enum QuotaRuleStatus {
    ACTIVE, DISABLED
}
