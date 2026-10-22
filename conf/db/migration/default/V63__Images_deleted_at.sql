-- Files deleted on Commons (services.imports.CommonsFileCheck). The image row stays:
-- deleting it would cascade to its votes (FK_selection_page_id, V58) and its category
-- links. deleted_at marks it, and its selection rows move to selection_deleted, so the
-- galleries, counts and results, which all read selection, no longer show it, with no
-- change to their queries. The rows keep their ids and columns; to restore an image:
--   UPDATE images SET deleted_at = NULL WHERE page_id = ?;
--   INSERT IGNORE INTO selection SELECT * FROM selection_deleted WHERE page_id = ?;
--   DELETE FROM selection_deleted WHERE page_id = ?;
ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP(3) NULL DEFAULT NULL;

-- The same columns and indexes as selection; CREATE TABLE ... LIKE copies no foreign keys.
-- Rows are moved with INSERT ... SELECT *, so a migration that changes selection's columns
-- must change selection_deleted's too.
CREATE TABLE IF NOT EXISTS selection_deleted LIKE selection;
