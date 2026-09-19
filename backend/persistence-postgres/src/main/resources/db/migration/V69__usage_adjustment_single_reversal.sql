-- One original adjustment carries at most one reversal.
--
-- A reversal row negates the deltas of the row it points at, and every read
-- path nets an event as `observed + SUM(all deltas)` (V63). Nothing used to stop
-- a second reversal of the same original, and booking one does not undo twice:
-- the second negation subtracts a correction that is already gone, so the net
-- ends up *above* the observed fact by the size of the original — 500 observed
-- output tokens with a -200 correction reverse to 500, and reverse again to 700.
-- The reported usage of the row is then a number nobody ever measured, and it
-- flows into the detail list, the summary, the export and billing alike.
--
-- UsageAdjustmentService now refuses the second attempt with
-- ADJUSTMENT_ALREADY_REVERSED (a 400 the operator can act on) under the
-- per-event advisory lock. This partial unique index is the structural
-- guarantee behind that check: a path that writes the ledger without going
-- through the service cannot corrupt the reading either. It mirrors the
-- idempotency index introduced with the ledger in V63.
CREATE UNIQUE INDEX uq_usage_adjustments_reversal_of ON usage_adjustments (tenant_id, reversal_of_id)
    WHERE reversal_of_id IS NOT NULL;
