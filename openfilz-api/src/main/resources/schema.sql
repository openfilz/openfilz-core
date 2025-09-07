CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; -- For UUID generation

CREATE TABLE IF NOT EXISTS documents (
         id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
         name VARCHAR(255) NOT NULL,
         type VARCHAR(50) NOT NULL, -- 'FILE' or 'FOLDER'
         content_type VARCHAR(100), -- MIME type for files
         size BIGINT, -- Size in bytes for files
         parent_id UUID,
         storage_path VARCHAR(255), -- Path in local FS or object key in S3
         metadata JSONB,
         created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
         updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
         created_by VARCHAR(255),
         updated_by VARCHAR(255),
         CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES documents (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_documents_parent_id ON documents (parent_id);
CREATE INDEX IF NOT EXISTS idx_documents_name ON documents (name);
CREATE INDEX IF NOT EXISTS idx_documents_type ON documents (type);
CREATE INDEX IF NOT EXISTS idx_documents_metadata ON documents USING GIN (metadata); -- For JSONB searching


-- Audit Log Table
CREATE TABLE IF NOT EXISTS audit_logs (
          id SERIAL PRIMARY KEY,
          timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
          user_principal VARCHAR(255),
          action VARCHAR(255) NOT NULL,
          resource_type VARCHAR(100),
          resource_id UUID,
          details JSONB
);