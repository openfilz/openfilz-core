-- ============================================================
-- V1_9: Document insights
--
-- One row per FILE document, derived from its content and kept apart from the
-- user-owned metadata JSON (recomputable, never PATCH-merged, no metadata audit).
--   tier 1 = the file's own metadata as Tika found it (title, author, dates,
--            page count, language) — written by the indexing / embedding pass
--   tier 2 = AI-derived category, summary, keywords, entities
--            (openfilz.ai.insights.active)
-- Design: openfilz-enterprise/docs/smart-reorganization.md §4.
-- Main migration set: tier 1 needs neither the chat nor pgvector.
-- ============================================================
CREATE TABLE IF NOT EXISTS ai_document_insights (
    document_id      UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
    -- tier 1: embedded file metadata (Tika), deterministic
    file_title       VARCHAR(512),
    file_author      VARCHAR(255),
    file_created_at  TIMESTAMPTZ,
    file_modified_at TIMESTAMPTZ,
    page_count       INT,
    language         VARCHAR(8),          -- BCP-47 primary tag; Tika when present, else tier 2
    -- tier 2: AI-derived
    category         VARCHAR(64),         -- one of openfilz.ai.insights.categories
    summary          VARCHAR(600),
    keywords         TEXT[],
    entities         JSONB,               -- {"client":"ACME","invoice_number":"F-2026-0042","period":"2026-03"}
    -- provenance
    tier             INT NOT NULL DEFAULT 1,             -- 1 = file metadata only, 2 = AI enrichment done
    model            VARCHAR(255),                       -- provider:model that produced tier 2
    prompt_version   INT,                                -- bump to force re-enrichment
    status           VARCHAR(16) NOT NULL DEFAULT 'DONE', -- PENDING, DONE, FAILED, SKIPPED (tier 2)
    error            VARCHAR(512),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_document_insights_category ON ai_document_insights(category);
CREATE INDEX IF NOT EXISTS idx_ai_document_insights_status ON ai_document_insights(status) WHERE status <> 'DONE';
