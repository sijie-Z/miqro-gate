-- ============================================================================
-- 22. model_approval.reason: applicant's stated justification for the request
--     (V4 created the table without a reason column; the approval UI shows it
--      to reviewers and keeps it in the audit summary)
-- ============================================================================
ALTER TABLE model_approval
    ADD COLUMN reason varchar(500);
