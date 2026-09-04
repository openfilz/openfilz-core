-- ============================================================
-- V1_10: Smart filing on upload
-- Design: openfilz-enterprise/docs/smart-reorganization.md §13.
--
-- A filing is a one-item reorganisation plan produced automatically after
-- ingestion and applied at once: same table, new provenance columns.
--   origin      PROPOSAL (chat / MCP proposal, the default) or AUTO_FILE
--   document_id the single document of an AUTO_FILE plan (its filing record)
-- Statuses gain SKIPPED (no confident destination) and UNDONE (moved back).
-- ============================================================
ALTER TABLE ai_reorganization_plans ADD COLUMN IF NOT EXISTS origin VARCHAR(16) NOT NULL DEFAULT 'PROPOSAL';
ALTER TABLE ai_reorganization_plans ADD COLUMN IF NOT EXISTS document_id UUID;
ALTER TABLE ai_reorganization_plans ADD COLUMN IF NOT EXISTS details JSONB;   -- filing decision: stage, confidence, reason, from

CREATE INDEX IF NOT EXISTS idx_ai_reorganization_plans_document
    ON ai_reorganization_plans(document_id, created_at DESC) WHERE document_id IS NOT NULL;

-- Per-user smart-filing switch (remembered from the upload area). Separate from
-- user_ai_settings, which is the BYOK row and requires a key.
CREATE TABLE IF NOT EXISTS user_ai_preferences (
    user_email             VARCHAR(255) PRIMARY KEY,
    auto_file              BOOLEAN NOT NULL DEFAULT false,
    auto_file_new_folders  BOOLEAN NOT NULL DEFAULT true,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
