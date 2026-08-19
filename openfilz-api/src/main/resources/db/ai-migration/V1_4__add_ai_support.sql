-- ============================================================
-- V1_4: AI Document Chat support
-- Adds pgvector extension, conversation history table,
-- and vector_store table for Spring AI PgVectorStore.
-- ============================================================

-- Enable pgvector extension for vector similarity search
CREATE EXTENSION IF NOT EXISTS vector;

-- Enable uuid-ossp if not already present (used by Spring AI PgVectorStore)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================
-- Conversation history for AI chat sessions
-- ============================================================
CREATE TABLE IF NOT EXISTS ai_chat_conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(500),
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ai_chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES ai_chat_conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL, -- USER, ASSISTANT, SYSTEM
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_chat_messages_conversation_id ON ai_chat_messages(conversation_id);
CREATE INDEX idx_ai_chat_conversations_created_by ON ai_chat_conversations(created_by);

-- ============================================================
-- Spring AI PgVectorStore table
-- Spring AI auto-creates this, but we define it here for
-- Flyway-managed schema control.
-- Uses 768 dimensions (compatible with nomic-embed-text,
-- all-minilm, text-embedding-3-small).
-- ============================================================
CREATE TABLE IF NOT EXISTS vector_store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT,
    metadata JSON,
    embedding vector(768)
);

-- HNSW index for fast approximate nearest neighbor search
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding
    ON vector_store USING hnsw (embedding vector_cosine_ops);
