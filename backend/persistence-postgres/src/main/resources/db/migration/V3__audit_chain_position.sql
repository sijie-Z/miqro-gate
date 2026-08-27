-- ============================================================================
-- V3: Database-monotonic chain position for admin_audit_events.
--
-- Problem: JVM clock and random UUID ordering are not causally ordered under
-- concurrent writers. A later writer with an older Instant/UUID can be
-- inserted after the current head, causing the next writer to select the
-- wrong predecessor row and fork the audit hash chain.
--
-- Solution: Add a database-assigned chain_position backed by a sequence.
-- The sequence is monotonically increasing and assigned at INSERT time
-- within the same transaction that holds pg_advisory_xact_lock. Head
-- selection uses ORDER BY chain_position DESC — the highest position IS
-- the most recent committed event regardless of JVM clock skew.
--
-- Conventions:
--   - Do NOT edit existing migrations; this is an append-only V3.
--   - chain_position is non-null with a UNIQUE constraint.
--   - Existing rows (none in production at this point) are assigned
--     sequential values in (created_at, id) order, then the sequence is
--     advanced past the maximum.
--   - New rows use DEFAULT nextval('admin_audit_events_chain_seq').
-- ============================================================================

-- 1. Create a dedicated sequence for chain-position assignment.
CREATE SEQUENCE IF NOT EXISTS admin_audit_events_chain_seq;

-- 2. Add the column (nullable temporarily to populate existing rows).
ALTER TABLE admin_audit_events
    ADD COLUMN IF NOT EXISTS chain_position BIGINT;

-- 3. Assign sequential values to any existing rows in causal order.
--    Uses a PL/pgSQL loop because PostgreSQL does not support ORDER BY in
--    UPDATE.  For an empty production table this loop runs zero iterations.
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT id FROM admin_audit_events ORDER BY created_at, id
    LOOP
        UPDATE admin_audit_events
           SET chain_position = nextval('admin_audit_events_chain_seq')
         WHERE id = rec.id;
    END LOOP;
END $$;

-- 4. Set the default for new rows so application INSERTs omit the column.
ALTER TABLE admin_audit_events
    ALTER COLUMN chain_position SET DEFAULT nextval('admin_audit_events_chain_seq');

-- 5. Make the column non-null now that all rows have a value.
ALTER TABLE admin_audit_events
    ALTER COLUMN chain_position SET NOT NULL;

-- 6. Enforce uniqueness — no two events can occupy the same chain position.
ALTER TABLE admin_audit_events
    ADD CONSTRAINT uq_admin_audit_events_chain_position UNIQUE (chain_position);

-- 7. Advance the sequence past the maximum assigned value so new rows
--    never collide with an existing position.
--    Empty table: set to 1 with is_called=false → first nextval() returns 1.
--    Non-empty table: set to MAX(chain_position) with is_called=true → next
--    nextval() is max+1.  Both branches are safe regardless of sequence
--    MINVALUE (PostgreSQL rejects setval(…, 0) on a sequence with MINVALUE 1).
DO $$
DECLARE
    max_pos BIGINT;
BEGIN
    SELECT MAX(chain_position) INTO max_pos FROM admin_audit_events;
    IF max_pos IS NULL THEN
        PERFORM setval('admin_audit_events_chain_seq', 1, false);
    ELSE
        PERFORM setval('admin_audit_events_chain_seq', max_pos);
    END IF;
END $$;

-- 8. Bind the sequence lifecycle to the column it supports.
--    Dropping chain_position (or the table) automatically drops the sequence.
ALTER SEQUENCE admin_audit_events_chain_seq OWNED BY admin_audit_events.chain_position;
