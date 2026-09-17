-- C2 — append-only integrity ledger.
--
-- The SHA-256 fingerprint has always been written into documents.metadata (JSONB), which is a
-- field the metadata APIs are designed to modify: PATCH /documents/{id}/metadata and
-- PUT /documents/{id}/replace-metadata can both rewrite it. A rewritable fingerprint proves
-- nothing — anyone able to alter the content could alter the value it is checked against.
--
-- This table is the reference instead. The JSONB stays populated (existing clients read it) but
-- stops being authoritative.
--
-- Deliberately NO foreign key to documents(id): like audit_logs.resource_id, an integrity record
-- must outlive the document it describes. A cascade would let a delete destroy exactly the
-- evidence that a delete happened.
CREATE TABLE IF NOT EXISTS document_integrity
(
    id            BIGSERIAL PRIMARY KEY,
    document_id   UUID         NOT NULL,
    storage_path  VARCHAR(1024),
    -- Object version in the storage backend (S3/MinIO versioning). NULL on backends without it.
    version_id    VARCHAR(255),
    algorithm     VARCHAR(32)  NOT NULL DEFAULT 'SHA-256',
    hash          VARCHAR(128) NOT NULL,
    recorded_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_by   VARCHAR(255)
);

CREATE INDEX idx_document_integrity_document ON document_integrity (document_id, recorded_at DESC);
CREATE INDEX idx_document_integrity_hash ON document_integrity (hash);

-- Immutability trigger, same shape as audit_logs (V1_3). INSERT is allowed; UPDATE and DELETE
-- are not, for anyone, including the application's own role. An append-only ledger enforced by
-- convention is not append-only.
CREATE OR REPLACE FUNCTION prevent_document_integrity_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Document integrity records are immutable and cannot be modified or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_integrity_immutable
    BEFORE UPDATE OR DELETE ON document_integrity
    FOR EACH ROW EXECUTE FUNCTION prevent_document_integrity_mutation();
