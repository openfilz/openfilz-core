-- ============================================================================
-- V1_16 — Full parent_id index (and two other lookups that had no index at all)
--
-- V1_2 replaced idx_documents_parent_id with a partial index `WHERE active = true`.
-- That index only serves active-only listings. Everything else that filters on
-- parent_id carries no `active` predicate and cannot use it, so each of those
-- ran a sequential scan of `documents`:
--   * the self-referencing FK fk_parent ... ON DELETE CASCADE (V1_0) — Postgres
--     checks `... WHERE parent_id = $1` once per deleted row, so a bulk purge of
--     N rows (recycle-bin "empty", scheduler cleanup) was O(N²);
--   * DocumentRepository.findByParentId* — hard delete, recycle-bin purge, folder copy;
--   * the recursive CTEs of DocumentSoftDeleteDAO (soft delete, restore,
--     descendant ids, restore size), which must see inactive rows.
--
-- The partial index stays: it is smaller and still the best fit for the
-- active-only listings. This full one covers the rest.
--
-- user_favorites(doc_id): fk_favorite_document ... ON DELETE CASCADE (V1_1) checks
-- `doc_id = $1` on every documents delete; the primary key (email, doc_id) does
-- not serve a lookup by doc_id alone.
--
-- documents(storage_path): TUS finalize checks existsByStoragePath (`WHERE
-- storage_path = $1`) on every resumable upload — unindexed until now.
--
-- Plain CREATE INDEX on purpose: Flyway runs each migration inside a transaction
-- and CREATE INDEX CONCURRENTLY cannot run in one (same choice as V1_2 / V1_15).
-- The table is locked for writes while each index builds — seconds, even on a
-- large documents table.
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_documents_parent_id_all ON documents (parent_id);

CREATE INDEX IF NOT EXISTS idx_user_favorites_doc_id ON user_favorites (doc_id);

CREATE INDEX IF NOT EXISTS idx_documents_storage_path ON documents (storage_path);
