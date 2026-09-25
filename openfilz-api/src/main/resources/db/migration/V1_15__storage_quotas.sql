-- ============================================================================
-- V1_15 — Storage quotas: per-user override (docs/admin-guide.md "Storage quotas")
--
-- The deployment-wide defaults stay in configuration (openfilz.quota.file-upload,
-- openfilz.quota.user, openfilz.quota.total). A row here replaces the default
-- per-user limit for one user: quota_mb > 0 is that user's own limit, 0 exempts
-- the user (no limit). Deleting the row gives the user the default back.
--
-- `username` is the principal storage is charged to — the same value the
-- documents' created_by column carries (the e-mail claim of the caller).
-- Usage is still computed on the fly from documents; nothing is stored here.
-- ============================================================================

CREATE TABLE IF NOT EXISTS user_storage_quota (
    username    VARCHAR(255) PRIMARY KEY,
    quota_mb    BIGINT       NOT NULL CHECK (quota_mb >= 0),
    updated_by  VARCHAR(255),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

COMMENT ON TABLE user_storage_quota IS 'Storage quotas: per-user override of openfilz.quota.user (0 = no limit for this user)';

-- The quota check sums a user's active files on every upload.
CREATE INDEX IF NOT EXISTS idx_documents_created_by_active_files
    ON documents (created_by) WHERE type = 'FILE' AND active = true;
