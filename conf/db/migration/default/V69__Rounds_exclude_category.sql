-- Keep the round's category exclusion for background creation and later redistribution.
-- Existing rows default to NULL; historical filter recovery is a separate data repair.
ALTER TABLE rounds ADD COLUMN IF NOT EXISTS exclude_category VARCHAR(4000) DEFAULT NULL;
