-- ============================================================================
-- V1_14 — Workflows: parallel review (docs/workflows.md §3–4)
--
-- A status carrying a `review` block gets one task per reviewer instead of one
-- task for all candidates. The tasks of one review round share `review_group`,
-- which is how the engine counts the votes of that round only (a document sent
-- back to the same status starts a new round). Idempotent, like V1_11.
-- ============================================================================

ALTER TABLE workflow_task ADD COLUMN IF NOT EXISTS review_group UUID;
CREATE INDEX IF NOT EXISTS idx_wf_task_review_group ON workflow_task (review_group) WHERE review_group IS NOT NULL;

COMMENT ON COLUMN workflow_task.review_group IS 'Workflows: the parallel review round this task is one vote of (null for an ordinary task)';
