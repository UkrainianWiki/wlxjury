-- Criteria rates whose vote is gone ("orphans"): criteria_rate.selection has no foreign
-- key, and until now every vote delete but the conflicts-of-interest removal left the
-- vote's rates behind (removing a file or the unrated votes from a round, votes dropped
-- when moved to another round, or moved aside and back for a file deleted and restored on
-- Commons); so did V58, which moved votes whose image was gone to selection_orphans. A vote
-- is in selection, or moved aside in selection_deleted (V63) with its id and rates kept; a
-- rate whose vote id is in neither belongs to no vote that is read. Nothing reads them.
-- Archive them, then delete them; the app now deletes a vote's rates with the vote
-- (SelectionJdbc.deleteVotes).
--
-- Check on production first (the rows this archives and deletes):
--   SELECT COUNT(*) FROM criteria_rate cr
--   WHERE NOT EXISTS (SELECT 1 FROM selection s WHERE s.id = cr.selection)
--     AND NOT EXISTS (SELECT 1 FROM selection_deleted d WHERE d.id = cr.selection);

-- 1. Archive: criteria_rate's columns under a key of the archive's own, so a later archive
--    of a reused criteria_rate id (its ids are not AUTO_INCREMENT) keeps both rows.
CREATE TABLE IF NOT EXISTS criteria_rate_orphan (
  archive_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  id BIGINT UNSIGNED NOT NULL,
  selection INT NOT NULL,
  criteria INT NOT NULL,
  rate INT NOT NULL,
  archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (archive_id),
  KEY criteria_rate_orphan_selection (selection)
);

INSERT INTO criteria_rate_orphan (id, selection, criteria, rate)
  SELECT cr.id, cr.selection, cr.criteria, cr.rate FROM criteria_rate cr
  WHERE NOT EXISTS (SELECT 1 FROM selection s WHERE s.id = cr.selection)
    AND NOT EXISTS (SELECT 1 FROM selection_deleted d WHERE d.id = cr.selection);

-- 2. Delete them. A rerun finds none, and so archives none again.
DELETE cr FROM criteria_rate cr
  WHERE NOT EXISTS (SELECT 1 FROM selection s WHERE s.id = cr.selection)
    AND NOT EXISTS (SELECT 1 FROM selection_deleted d WHERE d.id = cr.selection);
