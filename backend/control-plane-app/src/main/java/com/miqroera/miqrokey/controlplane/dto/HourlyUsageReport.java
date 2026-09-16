package com.miqroera.miqrokey.controlplane.dto;

import java.util.List;

/**
 * Hourly usage report (#634, {@code GET /api/v1/admin/usage/hourly}): per-hour
 * token buckets for a natural-day window, always crossed with the project and
 * optionally with the user or team.
 *
 * @param date
 *            requested end date (ISO {@code YYYY-MM-DD}, in
 *            {@code tzOffsetMinutes})
 * @param days
 *            number of consecutive days covered, ending at {@code date}
 * @param dimension
 *            {@code NONE} (hour × project), {@code USER}, or {@code TEAM}
 * @param tzOffsetMinutes
 *            offset from UTC in minutes used for day and hour boundaries
 * @param rows
 *            hour buckets that saw usage, ordered by hour then labels
 */
public record HourlyUsageReport(String date, int days, String dimension, int tzOffsetMinutes,
        List<HourlyUsageRow> rows) {
}
