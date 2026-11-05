-- ============================================================================
-- V1_20: OnlyOffice editor sessions
--
-- The save callback used to overwrite the document unconditionally, so a write
-- made while an editor was open (web replace, version restore, PDF tool) lost
-- its place as the current content when the session saved. A row records what
-- content a session started from, so the callback can tell.
--
-- document_key  : the key OnlyOffice carries back on each callback
-- base_marker   : the content the session's next save is based on (the storage
--                 version id, or the storage path where versioning is off)
-- conflict_copy : the copy holding this session's work once it has diverged,
--                 so that its later saves update that copy
-- ============================================================================

CREATE TABLE IF NOT EXISTS onlyoffice_editor_session (
    document_key  VARCHAR(128) PRIMARY KEY,
    document_id   UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    base_marker   TEXT NOT NULL,
    conflict_copy UUID,
    opened_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_onlyoffice_editor_session_document
    ON onlyoffice_editor_session (document_id);
