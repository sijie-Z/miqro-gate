package com.miqroera.miqrokey.domain.usage;

/**
 * Window kind of a quota snapshot. Providers report different accounting
 * windows (period/5h/weekly/monthly); {@code UNKNOWN} is used when the source
 * does not state one.
 */
public enum QuotaWindow {
    PERIOD, ROLLING_5H, WEEKLY, MONTHLY, UNKNOWN
}
