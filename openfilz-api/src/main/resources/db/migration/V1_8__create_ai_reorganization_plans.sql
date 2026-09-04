-- ============================================================
-- V1_8: AI-assisted document reorganisation plans
--
-- A plan is a validated proposal ("move these documents into this new
-- folder hierarchy") produced by the AI tools (chat assistant or an
-- external MCP agent) and applied later, once the user has confirmed it —
-- from the chat proposal card, or by the agent calling
-- applyReorganizationPlan. Lives in the main migration set (not
-- db/ai-migration) because the MCP server can run without the in-app chat.
-- ============================================================
CREATE TABLE IF NOT EXISTS ai_reorganization_plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by      VARCHAR(255) NOT NULL,
    conversation_id UUID,                    -- chat conversation that proposed it (null for MCP agents)
    root_folder_id  UUID,                    -- null = the root level
    status          VARCHAR(32)  NOT NULL,   -- PROPOSED, APPLIED, PARTIALLY_APPLIED, FAILED, DISCARDED
    plan            JSONB        NOT NULL,   -- validated plan view (items, target paths, issues)
    result          JSONB,                   -- per-item outcome once applied
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    applied_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ai_reorganization_plans_created_by ON ai_reorganization_plans(created_by, created_at DESC);
