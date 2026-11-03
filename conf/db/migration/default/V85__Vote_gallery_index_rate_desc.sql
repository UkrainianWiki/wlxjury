-- A juror's gallery pages their images ORDER BY rate DESC, monument_id ASC, page_id ASC
-- (GalleryService; ImageDbNew's deferred join). V48's (jury_id, round_id, rate, monument_id,
-- page_id) index, vote_jury_round_rate_mon_page_idx since V82, was meant to give that order,
-- but the directions are mixed, and before MariaDB 10.8 index keys are ascending only: every
-- page sorted all the juror's rows of the round (~19k on the Gatling fixture). The deferred
-- join keeps that sort to the narrow index entries; with the rate column stored descending,
-- the index is read in the page's order and the sort goes away.
--
-- MariaDB 10.8 or later (production's next server, Ubuntu 24.04's 10.11): leaves vote with one
-- gallery index, vote_jury_round_ratedesc_mon_page_idx (jury_id, round_id, rate DESC,
-- monument_id, page_id), online (INPLACE, LOCK=NONE), and refreshes vote's statistics. By the
-- state it finds (the same condition as the app's start check, db.Migrations
-- .galleryIndexLeftAscending: some gallery index has rate ascending):
--   * only the old index: the swap (the new index is built before the old one goes);
--   * the new index descending and the old one still there: drops the old one;
--   * the new index ascending (made before 10.8, e.g. from a dump of a 10.11 database
--     restored on 10.6 and moved on again): rebuilt descending, the old one dropped if there;
--   * the new index descending alone (done), or neither index: nothing runs.
--
-- Before 10.8 (production's 10.6 until the move): changes nothing and leaves a warning
-- ("DB: V85 ..." in Flyway's log). There DESC is parsed and ignored: the index would be
-- ascending under a descending name, and neither an in-place upgrade (mariadb-upgrade) nor a
-- dump restored on 10.11 would make it descending. The migration is still recorded as
-- applied, so after the move to 10.8+ it must run once more (docs/deployment.md,
-- "MariaDB 10.11: V85's gallery index"); this script is safe to run again as it is.
--
-- vote_aside and vote_orphan keep their copies of the old index: nothing pages them.
-- (EXECUTE IMMEDIATE takes no subquery: the checks go through variables.)

SET @v85_server = CAST(SUBSTRING_INDEX(VERSION(), '.', 1) AS UNSIGNED) * 1000
  + CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(), '.', 2), '.', -1) AS UNSIGNED);

-- the old index's rate column (always ascending): 1 if the index is there
SET @v85_old = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote'
    AND INDEX_NAME = 'vote_jury_round_rate_mon_page_idx' AND COLUMN_NAME = 'rate');

-- the new index's rate collation: 'D', 'A', or NULL if the index isn't there
SET @v85_new = (SELECT MAX(COLLATION) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote'
    AND INDEX_NAME = 'vote_jury_round_ratedesc_mon_page_idx' AND COLUMN_NAME = 'rate');

SET @v85_sql = CASE
  WHEN @v85_server < 10008 THEN NULL
  WHEN @v85_new = 'A' AND @v85_old > 0 THEN
    'ALTER TABLE vote DROP INDEX vote_jury_round_rate_mon_page_idx, DROP INDEX vote_jury_round_ratedesc_mon_page_idx,
       ADD INDEX vote_jury_round_ratedesc_mon_page_idx (jury_id, round_id, rate DESC, monument_id, page_id),
       ALGORITHM = INPLACE, LOCK = NONE'
  WHEN @v85_new = 'A' THEN
    'ALTER TABLE vote DROP INDEX vote_jury_round_ratedesc_mon_page_idx,
       ADD INDEX vote_jury_round_ratedesc_mon_page_idx (jury_id, round_id, rate DESC, monument_id, page_id),
       ALGORITHM = INPLACE, LOCK = NONE'
  WHEN @v85_new = 'D' AND @v85_old > 0 THEN
    'ALTER TABLE vote DROP INDEX vote_jury_round_rate_mon_page_idx, ALGORITHM = INPLACE, LOCK = NONE'
  WHEN @v85_new IS NULL AND @v85_old > 0 THEN
    'ALTER TABLE vote
       ADD INDEX vote_jury_round_ratedesc_mon_page_idx (jury_id, round_id, rate DESC, monument_id, page_id),
       DROP INDEX vote_jury_round_rate_mon_page_idx,
       ALGORITHM = INPLACE, LOCK = NONE'
END;

EXECUTE IMMEDIATE IF(@v85_server < 10008,
  'SIGNAL SQLSTATE ''01000'' SET MESSAGE_TEXT = ''V85: vote gallery index left ascending (needs MariaDB 10.8+); run its ALTER again after the server move''',
  'DO 0');

EXECUTE IMMEDIATE COALESCE(@v85_sql, 'DO 0');

EXECUTE IMMEDIATE IF(@v85_sql IS NULL, 'DO 0', 'ANALYZE TABLE vote');

SET @v85_server = NULL, @v85_old = NULL, @v85_new = NULL, @v85_sql = NULL;
