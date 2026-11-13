-- The vote-time orders (a juror's "Newest votes first" / "Oldest votes first", sort=latest-vote /
-- oldest-vote) read V97's vote.voted_at. No index had it, so every page read all the juror's vote
-- rows of the round from the table (the worst case, 33k votes: ~125k pages for page 1). With
-- voted_at as the juror gallery index's last column the deferred join's inner query reads only
-- the index (~140 pages). The ORDER BY sorts by expressions (unrated last, then the time), so the
-- sort stays; it now sorts index entries. Benchmark of 2026-11-13 on production's copy (MariaDB
-- 10.6, 3.35M votes): page 1 82 -> 26 ms, a large view step 243 -> 80 ms in the worst case, the
-- 2026 rounds 2-4x faster, the same as file order. A separate (jury_id, round_id, voted_at)
-- index was never chosen and made some plans worse. A vote write costs the same: voted_at only
-- changes with rate, which the index already has.
--
-- The index keeps its name, as the rest of the code finds it by name (V85, db.Migrations, the
-- specs' SharedTestDb.voteGalleryIndex). Its target, by server:
--   * before MariaDB 10.8 (production's 10.6): vote_jury_round_rate_mon_page_idx
--     (jury_id, round_id, rate, monument_id, page_id, voted_at); a descending-named index there
--     (from a dump of a 10.8+ database) keeps its name, with voted_at added;
--   * MariaDB 10.8 or later: V85's target with voted_at, vote_jury_round_ratedesc_mon_page_idx
--     (jury_id, round_id, rate DESC, monument_id, page_id, voted_at), the only gallery index.
-- So on 10.8+ this script does V85's swap too, from any state V85 handles. After the move to
-- 10.11 it is this script that runs again, not V85 (docs/deployment.md, "MariaDB 10.11: the vote
-- gallery index (V85, V99)"): V85 run again would build its index without voted_at (V85 can't
-- change: Flyway validates its checksum). The app's start warns in both cases (an ascending rate
-- on 10.8+, a gallery index without voted_at last: db.Migrations).
--
-- By the state it finds, as V85 (information_schema; the index's exact columns in order):
--   * the target already there, and no other gallery index: nothing runs (rerunnable);
--   * before 10.8: each gallery index without the trailing voted_at is rebuilt with it, under
--     its own name;
--   * 10.8+: the descending index with voted_at and the old one still there: the old one
--     dropped; any other state with a gallery index: the gallery indexes dropped and the target
--     built, in one ALTER (the old indexes serve reads until it commits);
--   * neither gallery index: nothing runs (as V85: not created from nothing).
-- One ALTER, online (ALGORITHM = INPLACE, LOCK = NONE: reads and writes go on; ~12.6 s on 3.35M
-- rows), then ANALYZE TABLE vote. A 60 s lock_wait_timeout for the metadata lock at its start and
-- end (a long transaction or backup: the statement fails with nothing changed). If it fails,
-- Flyway records V99 as failed (success = 0) and the next start refuses: delete that row (or run
-- Flyway's repair) and start again; the script reads the state again.
--
-- Before 10.8 it also warns, after the ALTER ("DB: V99 ..." in Flyway's log): rate is left
-- ascending, so after the move this script must run once more, as V85 had to.
--
-- vote_aside and vote_orphan keep their copies of the old index: nothing pages them (as V85).
-- Undo (10.6): ALTER TABLE vote DROP INDEX vote_jury_round_rate_mon_page_idx,
--   ADD INDEX vote_jury_round_rate_mon_page_idx (jury_id, round_id, rate, monument_id, page_id),
--   ALGORITHM = INPLACE, LOCK = NONE;
-- (EXECUTE IMMEDIATE takes no subquery: the checks go through variables.)

SET @v99_server = CAST(SUBSTRING_INDEX(VERSION(), '.', 1) AS UNSIGNED) * 1000
  + CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(), '.', 2), '.', -1) AS UNSIGNED);

SET @v99_target = 'jury_id,round_id,rate,monument_id,page_id,voted_at';

-- the statement's start; the clauses for the state follow
SET @v99_alter_vote = 'SET STATEMENT lock_wait_timeout = 60 FOR ALTER TABLE vote ';

-- each gallery index's columns in order, NULL if the index isn't there
SET @v99_old = (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote'
    AND INDEX_NAME = 'vote_jury_round_rate_mon_page_idx');

SET @v99_new = (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote'
    AND INDEX_NAME = 'vote_jury_round_ratedesc_mon_page_idx');

-- the descending-named index's rate collation: 'D', 'A', or NULL if the index isn't there
SET @v99_new_rate = (SELECT MAX(COLLATION) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote'
    AND INDEX_NAME = 'vote_jury_round_ratedesc_mon_page_idx' AND COLUMN_NAME = 'rate');

SET @v99_alter = CASE
  WHEN @v99_server < 10008 THEN NULLIF(CONCAT_WS(', ',
    IF(@v99_old <> @v99_target,
      'DROP INDEX vote_jury_round_rate_mon_page_idx,
       ADD INDEX vote_jury_round_rate_mon_page_idx (jury_id, round_id, rate, monument_id, page_id, voted_at)', NULL),
    IF(@v99_new <> @v99_target,
      'DROP INDEX vote_jury_round_ratedesc_mon_page_idx,
       ADD INDEX vote_jury_round_ratedesc_mon_page_idx (jury_id, round_id, rate DESC, monument_id, page_id, voted_at)', NULL)), '')
  WHEN @v99_new = @v99_target AND @v99_new_rate = 'D' THEN
    IF(@v99_old IS NULL, NULL, 'DROP INDEX vote_jury_round_rate_mon_page_idx')
  WHEN @v99_old IS NOT NULL OR @v99_new IS NOT NULL THEN CONCAT_WS(', ',
    IF(@v99_old IS NULL, NULL, 'DROP INDEX vote_jury_round_rate_mon_page_idx'),
    IF(@v99_new IS NULL, NULL, 'DROP INDEX vote_jury_round_ratedesc_mon_page_idx'),
    'ADD INDEX vote_jury_round_ratedesc_mon_page_idx (jury_id, round_id, rate DESC, monument_id, page_id, voted_at)')
END;

SET @v99_sql = IF(@v99_alter IS NULL, NULL, CONCAT(@v99_alter_vote, @v99_alter, ', ALGORITHM = INPLACE, LOCK = NONE'));

EXECUTE IMMEDIATE COALESCE(@v99_sql, 'DO 0');

-- after the ALTER: only once the index has voted_at (a failed ALTER stops the script before this)
EXECUTE IMMEDIATE IF(@v99_server < 10008 AND COALESCE(@v99_old, @v99_new) IS NOT NULL,
  'SIGNAL SQLSTATE ''01000'' SET MESSAGE_TEXT = ''V99: vote gallery index has voted_at, rate left ascending (needs MariaDB 10.8+); run V99 again after the server move''',
  'DO 0');

EXECUTE IMMEDIATE IF(@v99_sql IS NULL, 'DO 0', 'ANALYZE TABLE vote');

SET @v99_server = NULL, @v99_target = NULL, @v99_alter_vote = NULL, @v99_old = NULL, @v99_new = NULL, @v99_new_rate = NULL,
  @v99_alter = NULL, @v99_sql = NULL;
