-- Repairing a contest's file list (root's "Repair file list" page, services.FileListRepair).
--
-- A contest's file list is its contest_jury.category_id row with the files in category_members.
-- Since 2017 an import found or added that row by title, so every unnamed import shared the row
-- of the empty title, and a source set by title could give a contest another contest's row. The
-- guards of the shared-category fix stop new damage; root repairs the old lists one contest at a
-- time: the files the contest's own rounds show (the rounds root counts) go into its own
-- "#contest N" row, which becomes its list. Rounds, round_image and votes never change.
--
--  * list_repair: one row per confirmed repair. old_category_id: the contest's list before it;
--    source_category_id: the row the files came from (the old list, or, for a contest already on
--    its own row, the shared row its rounds' files were left in); new_category_id: its own row.
--    rounds_counted: the ids of the rounds whose files were counted, as a JSON array. files_added:
--    how many files the repair put into the own row (list_repair_file lists them, for Undo).
--    list_hash: SHA-256 of the own row's sorted page ids just after the repair: Undo is refused
--    if the list changed since (a later import or repair). undone_at/undone_by: its Undo. Actor
--    ids without foreign keys, as contest_change.actor_id; category ids without them too (the
--    entry outlives a row).
--  * list_repair_file: the files a repair added to the own row (and only those: files already
--    there are not listed), which its Undo takes out again.
--  * contest_change's kind CHECK (V112, whose values it keeps) gains files_repaired and
--    files_repair_undone; detail {"repair", "oldRow", "sourceRow", "newRow", "rounds", "stays",
--    "leaves", "added", "own"} (an undo: {"repair", "oldRow", "newRow", "removed"}).
--
-- Schema only: no data changes. Rerunnable: CREATE TABLE IF NOT EXISTS; the CHECK dropped if there
-- and added again (contest_change: a few hundred rows; milliseconds). A 60 s lock_wait_timeout for
-- each statement (a long transaction or backup: it fails with nothing changed).
SET STATEMENT lock_wait_timeout = 60 FOR
CREATE TABLE IF NOT EXISTS list_repair (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  contest_id         BIGINT UNSIGNED NOT NULL,
  old_category_id    BIGINT UNSIGNED NULL DEFAULT NULL,
  source_category_id BIGINT UNSIGNED NOT NULL,
  new_category_id    BIGINT UNSIGNED NOT NULL,
  rounds_counted     TEXT            NOT NULL,
  files_added        INT             NOT NULL,
  list_hash          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_by         BIGINT UNSIGNED NOT NULL,
  created_at         TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  undone_by          BIGINT UNSIGNED NULL DEFAULT NULL,
  undone_at          TIMESTAMP(3)    NULL DEFAULT NULL,
  PRIMARY KEY (id),
  KEY list_repair_contest_idx (contest_id, id),
  KEY list_repair_source_idx (source_category_id),
  CONSTRAINT list_repair_rounds_chk CHECK (JSON_VALID(rounds_counted)),
  CONSTRAINT list_repair_contest_fk FOREIGN KEY (contest_id) REFERENCES contest_jury (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

SET STATEMENT lock_wait_timeout = 60 FOR
CREATE TABLE IF NOT EXISTS list_repair_file (
  repair_id BIGINT UNSIGNED NOT NULL,
  page_id   BIGINT          NOT NULL,
  PRIMARY KEY (repair_id, page_id),
  CONSTRAINT list_repair_file_repair_fk FOREIGN KEY (repair_id) REFERENCES list_repair (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE contest_change DROP CONSTRAINT IF EXISTS contest_change_kind_chk,
  ADD CONSTRAINT contest_change_kind_chk
    CHECK (kind IN ('created', 'identity', 'series_assigned', 'series_unassigned', 'series_changed',
                    'split_out', 'split_in', 'split_undone', 'retired', 'unretired',
                    'round_juror_stopped', 'round_juror_reopened', 'round_juror_closed',
                    'files_repaired', 'files_repair_undone'));

-- A pre-V114 binary runs on this schema: it never names list_repair and writes none of the new
-- kinds; it shows their log rows by message keys it lacks. Undo, after reopen (the repairs' records
-- are lost; the repaired contests keep their own lists, Undo them first to restore the old ones):
--   DELETE FROM contest_change WHERE kind IN ('files_repaired', 'files_repair_undone');
--   ALTER TABLE contest_change DROP CONSTRAINT IF EXISTS contest_change_kind_chk,
--     ADD CONSTRAINT contest_change_kind_chk CHECK (kind IN ('created', 'identity', 'series_assigned',
--     'series_unassigned', 'series_changed', 'split_out', 'split_in', 'split_undone', 'retired', 'unretired',
--     'round_juror_stopped', 'round_juror_reopened', 'round_juror_closed'));
--   DROP TABLE IF EXISTS list_repair_file;
--   DROP TABLE IF EXISTS list_repair;
--   DELETE FROM flyway_schema_history WHERE version = '114';
