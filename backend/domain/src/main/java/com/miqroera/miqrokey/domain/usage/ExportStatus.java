package com.miqroera.miqrokey.domain.usage;

/** Export task lifecycle. EXPIRED tasks no longer serve downloads. */
public enum ExportStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, EXPIRED
}
