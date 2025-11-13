-- Add support for user favorites
CREATE TABLE IF NOT EXISTS user_favorites (
    id SERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    document_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_user_favorite UNIQUE(user_id, document_id),
    CONSTRAINT fk_favorite_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

-- Create indexes for performance
CREATE INDEX idx_user_favorites_user_id ON user_favorites(user_id);
CREATE INDEX idx_user_favorites_document_id ON user_favorites(document_id);

-- Add comment for documentation
COMMENT ON TABLE user_favorites IS 'Stores user favorite files and folders';
COMMENT ON COLUMN user_favorites.user_id IS 'User email or identifier from JWT token';
COMMENT ON COLUMN user_favorites.document_id IS 'Reference to document (file or folder)';
