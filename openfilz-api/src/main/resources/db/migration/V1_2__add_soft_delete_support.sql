-- Add soft delete support for documents table
-- This enables recycle bin functionality where deleted items can be restored

ALTER TABLE documents
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN deleted_by VARCHAR(255);

-- Create index for efficient querying of deleted items
-- This index only includes rows where deleted_at is NOT NULL (deleted items)
CREATE INDEX idx_documents_deleted_at ON documents(deleted_at) WHERE deleted_at IS NOT NULL;

-- Create index for efficient querying of active (non-deleted) items
-- This index helps filter out deleted items in normal queries
CREATE INDEX idx_documents_not_deleted ON documents(id) WHERE deleted_at IS NULL;

-- Add comment to document the soft delete columns
COMMENT ON COLUMN documents.deleted_at IS 'Timestamp when the document was soft-deleted (moved to recycle bin)';
COMMENT ON COLUMN documents.deleted_by IS 'User who deleted the document';
