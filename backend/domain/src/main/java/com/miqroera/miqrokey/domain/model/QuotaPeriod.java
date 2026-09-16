package com.miqroera.miqrokey.domain.model;

/**
 * Reset window of a quota rule. Windows are UTC calendar slices: DAILY = the
 * current UTC day, WEEKLY = the UTC week starting Monday, MONTHLY = the UTC
 * calendar month (same convention as the monthly budget), YEARLY = the UTC
 * calendar year starting Jan 1 (#683, Tencent quota-management alignment).
 */
public enum QuotaPeriod {
    DAILY, WEEKLY, MONTHLY, YEARLY
}
