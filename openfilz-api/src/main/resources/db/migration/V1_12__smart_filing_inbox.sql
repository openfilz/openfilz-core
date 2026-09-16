-- ============================================================
-- V1_12: Smart filing — the per-user Inbox convention
-- Design: openfilz-enterprise/docs/smart-reorganization.md §13.5.
--
-- A user may have one Inbox folder: a document dropped there means "I don't
-- want to think about where this goes" — the whole library becomes the filing
-- scope and the Inbox itself is never a destination. The folder is an ordinary
-- folder at the user's root, created on demand and named in the user's
-- language; the row only remembers which one it is. Null = the user has none.
-- ============================================================
ALTER TABLE user_ai_preferences ADD COLUMN IF NOT EXISTS inbox_folder_id UUID;

COMMENT ON COLUMN user_ai_preferences.inbox_folder_id IS
    'The user''s smart-filing Inbox folder (documents.id); null when the user has none. The folder is never deleted by turning the Inbox off.';
