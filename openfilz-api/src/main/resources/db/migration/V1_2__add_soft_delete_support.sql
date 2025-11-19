-- Add soft delete support for documents table
-- This enables recycle bin functionality where deleted items can be restored

ALTER TABLE documents
    ADD COLUMN active boolean default true;

DROP INDEX IF EXISTS idx_documents_parent_id;
DROP INDEX IF EXISTS idx_documents_name;
DROP INDEX IF EXISTS idx_documents_type;
DROP INDEX IF EXISTS idx_documents_metadata;

CREATE INDEX idx_documents_parent_id ON documents (parent_id) where active = true;
CREATE INDEX idx_documents_name ON documents (name) where active = true;
CREATE INDEX idx_documents_type ON documents (type) where active = true;
CREATE INDEX idx_documents_metadata ON documents USING GIN (metadata) where active = true;

create table recycle_bin(
     id UUID PRIMARY KEY,
     deleted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
     deleted_by VARCHAR(255),
     CONSTRAINT fk_trash_document FOREIGN KEY (id)
         REFERENCES documents(id) ON DELETE CASCADE
);

