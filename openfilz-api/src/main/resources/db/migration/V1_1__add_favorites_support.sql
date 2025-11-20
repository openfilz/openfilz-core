-- Add support for user favorites
CREATE TABLE IF NOT EXISTS user_favorites (
      email VARCHAR(255) NOT NULL,
      doc_id UUID NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (email, doc_id),
      CONSTRAINT fk_favorite_document FOREIGN KEY (doc_id)
          REFERENCES documents(id) ON DELETE CASCADE
);

-- Add comment for documentation
COMMENT ON TABLE user_favorites IS 'Stores user favorite files and folders';
COMMENT ON COLUMN user_favorites.email IS 'User email or identifier from JWT token';
COMMENT ON COLUMN user_favorites.doc_id IS 'Reference to document (file or folder)';
