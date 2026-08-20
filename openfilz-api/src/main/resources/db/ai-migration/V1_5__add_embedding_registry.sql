-- ============================================================
-- V1_5: Embedding model registry
-- Records which embedding model produced the vectors stored in
-- vector_store. Vectors from different embedding models live in
-- incomparable vector spaces, so the embedding model is a
-- one-time deployment decision: EmbeddingRegistryGuard compares
-- the configured model against this record at startup and
-- refuses to start (or warns) on a mismatch while indexed
-- vectors exist.
-- ============================================================
CREATE TABLE IF NOT EXISTS ai_embedding_registry (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    provider VARCHAR(50) NOT NULL,
    model VARCHAR(255) NOT NULL,
    dimensions INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
