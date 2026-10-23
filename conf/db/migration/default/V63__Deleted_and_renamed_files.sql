-- Files deleted on Commons (services.imports.CommonsFileCheck). The image row stays:
-- deleting it would cascade to its votes (FK_selection_page_id, V58) and its category
-- links. deleted_at marks it, and its selection rows move to selection_deleted, so the
-- galleries, counts and results, which all read selection, no longer show it, with no
-- change to their queries. The rows keep their ids and columns. An import that finds the
-- file on Commons again moves them back (services.imports.DeletedFiles.restore).
ALTER TABLE images ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP(3) NULL DEFAULT NULL;

-- The same columns and indexes as selection; CREATE TABLE ... LIKE copies no foreign keys.
-- Rows are moved with INSERT ... SELECT *, so a migration that changes selection's columns
-- must change selection_deleted's too.
CREATE TABLE IF NOT EXISTS selection_deleted LIKE selection;

-- The former titles of files renamed on Commons (CommonsFileCheck, and the image import),
-- so a thumbnail requested under a former name (a page rendered before the rename, the
-- other blue/green instance, a restart) is still found by its page id. A title is a
-- file's at most once: a later rename of another file from it replaces it.
CREATE TABLE IF NOT EXISTS image_renames (
  title VARCHAR(255) NOT NULL,
  page_id BIGINT NOT NULL,
  renamed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (title),
  KEY image_renames_page_id_idx (page_id)
);
