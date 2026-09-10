-- ============================================================================
-- 42. Bill reconciliation reports (issue #334, F19 contract draft v0).
--     Canonical-upload reconciliation results: one report row per import plus
--     its four-state detail rows. Read-only results - usage_event is never
--     written by reconciliation, and the uploaded file content is never
--     stored (only its SHA-256 and size).
-- ============================================================================
CREATE TABLE reconciliation_reports (
    id                  uuid          PRIMARY KEY,
    tenant_id           uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    created_by          uuid          NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    provider_code       varchar(64)   NOT NULL,
    currency            varchar(8)    NOT NULL,
    window_from         timestamptz   NOT NULL,
    window_to           timestamptz   NOT NULL,
    status              varchar(16)   NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    upload_sha256       varchar(64),
    upload_bytes        bigint,
    total_rows          integer,
    matched             integer,
    partial_buckets     integer,
    unmatched_provider  integer,
    unmatched_local     integer,
    line_error_count    integer,
    amount_diff         numeric(24, 8),
    error_message       varchar(500),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    finished_at         timestamptz
);

-- Idempotent re-upload: the same provider + window + file content resolves to
-- one report (lookup path).
CREATE INDEX idx_reconciliation_reports_dedupe
    ON reconciliation_reports (tenant_id, provider_code, window_from, window_to, upload_sha256);
CREATE INDEX idx_reconciliation_reports_recent
    ON reconciliation_reports (tenant_id, created_at DESC);

CREATE TABLE reconciliation_rows (
    id                uuid        PRIMARY KEY,
    report_id         uuid        NOT NULL REFERENCES reconciliation_reports (id) ON DELETE CASCADE,
    tenant_id         uuid        NOT NULL,
    row_no            integer     NOT NULL,
    verdict           varchar(32) NOT NULL
                      CHECK (verdict IN ('MATCHED', 'PARTIAL', 'UNMATCHED_PROVIDER', 'UNMATCHED_LOCAL')),
    matched_by        varchar(32),
    provider_row_ref  varchar(256),
    local_ref         varchar(256),
    detail            jsonb,
    UNIQUE (report_id, row_no)
);
