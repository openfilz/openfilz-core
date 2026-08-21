-- ============================================================
-- V1_6: Per-user AI (LLM) settings — BYOK (bring your own key)
-- A user can override the server-default chat model with their
-- own provider + API key. The key is stored AES-256-GCM
-- encrypted with the server-side AI_SETTINGS_ENCRYPTION_KEY.
-- Only the CHAT model is user-selectable: embeddings stay on
-- the server-configured model (vector_store is 768-dim).
-- ============================================================
CREATE TABLE IF NOT EXISTS user_ai_settings (
    user_email        VARCHAR(255) PRIMARY KEY,
    provider          VARCHAR(32)  NOT NULL,  -- OPENAI | ANTHROPIC | GOOGLE | OPENAI_COMPATIBLE
    model             VARCHAR(128) NOT NULL,
    base_url          VARCHAR(512),           -- OPENAI_COMPATIBLE only
    api_key_encrypted TEXT         NOT NULL,  -- base64(iv || ciphertext+tag), AES-256-GCM
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
