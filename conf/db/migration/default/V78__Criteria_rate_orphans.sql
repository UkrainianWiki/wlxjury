-- Criteria rates whose vote is gone ("orphans"): criteria_rate.selection has no foreign
-- key, and until now every vote delete but the conflicts-of-interest removal left the
-- vote's rates behind (removing a file or the unrated votes from a round, votes dropped
-- when moved to another round, or moved aside and back for a file deleted and restored on
-- Commons). A vote is in selection, or moved aside in selection_deleted (V63) with its id
-- and rates kept; a rate whose id is in neither belongs to no vote. Nothing reads them.
-- Archive them, then delete them; the app now deletes a vote's rates with the vote
-- (SelectionJdbc.deleteVotes).
--
-- Check on production first (the rows this archives and deletes):
--   SELECT COUNT(*) FROM criteria_rate cr
--   WHERE NOT EXISTS (SELECT 1 FROM selection s WHERE s.id = cr.selection)
--     AND NOT EXISTS (SELECT 1 FROM selection_deleted d WHERE d.id = cr.selection);

-- 1. Archive: the same columns (and the unique id), all data kept. Idempotent.
CREATE TABLE IF NOT EXISTS criteria_rate_orphans LIKE criteria_rate;

INSERT IGNORE INTO criteria_rate_orphans
  SELECT cr.* FROM criteria_rate cr
  WHERE NOT EXISTS (SELECT 1 FROM selection s WHERE s.id = cr.selection)
    AND NOT EXISTS (SELECT 1 FROM selection_deleted d WHERE d.id = cr.selection);

-- 2. Delete them. A rerun finds none.
DELETE cr FROM criteria_rate cr
  WHERE NOT EXISTS (SELECT 1 FROM selection s WHERE s.id = cr.selection)
    AND NOT EXISTS (SELECT 1 FROM selection_deleted d WHERE d.id = cr.selection);
