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

-- User, Teams, Organization, Owners & Shares
CREATE TABLE IF NOT EXISTS users (
         id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
         email VARCHAR(255),
         name VARCHAR(255) NOT NULL,
         type VARCHAR(50) NOT NULL -- 'USER', 'TEAM', 'ORGANIZATION'
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users ON users (email);

CREATE TABLE IF NOT EXISTS user_team (
         user_id UUID not null,
         team_id UUID not null,
         PRIMARY KEY(user_id, team_id),
         CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
         CONSTRAINT fk_team FOREIGN KEY (team_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS team_org (
         team_id UUID not null,
         org_id UUID not null,
         PRIMARY KEY(team_id, org_id),
         CONSTRAINT fk_team_org FOREIGN KEY (team_id) REFERENCES users (id) ON DELETE CASCADE,
         CONSTRAINT fk_org FOREIGN KEY (org_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS doc_owner (
        doc_id UUID not null,
        owner_id UUID not null,
        PRIMARY KEY(doc_id, owner_id),
        CONSTRAINT fk_doc FOREIGN KEY (doc_id) REFERENCES documents (id) ON DELETE CASCADE,
        CONSTRAINT fk_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS doc_share (
         doc_id UUID not null,
         user_id UUID not null,
         type VARCHAR(10) NOT NULL,
         PRIMARY KEY(doc_id, user_id),
         CONSTRAINT fk_shared_doc FOREIGN KEY (doc_id) REFERENCES documents (id) ON DELETE CASCADE,
         CONSTRAINT fk_shared_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);