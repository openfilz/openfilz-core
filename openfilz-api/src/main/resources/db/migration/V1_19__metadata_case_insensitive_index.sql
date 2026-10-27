-- ============================================================================
-- V1_19 — Case-insensitive metadata search
--
-- Metadata filters (GraphQL listFolder / searchDocuments `metadata.<key>`, REST
-- ids-by-metadata, AI searchByMetadata) now match keys AND values ignoring case:
-- they all read `(lower(metadata::text)::jsonb)` (SqlUtils.caseInsensitiveMetadata),
-- with `@>` for exact values and `?&` for the keys of a pattern. This GIN index
-- on that very expression keeps both operators indexed; the expression in the
-- queries must stay textually identical to it.
--
-- idx_documents_metadata (V1_0, on the raw column) served the old case-sensitive
-- `metadata @> ...` and nothing else: dropped, it would only slow writes down.
--
-- Plain CREATE INDEX on purpose (Flyway runs the migration in a transaction, see V1_16).
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_documents_metadata_ci ON documents USING GIN ((lower(metadata::text)::jsonb));

DROP INDEX IF EXISTS idx_documents_metadata;
