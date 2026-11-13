-- Drops vote's four single-column indexes, all from V7 (selection_*_index, renamed by V82):
--   * vote_round_idx (round_id) and vote_jury_id_idx (jury_id): each a prefix of a composite
--     index that serves the same lookups (round_id leads vote_round_rate_idx,
--     vote_round_jury_rate_idx, vote_round_monument_id_idx and vote_round_page_rate_idx; jury_id
--     leads the juror gallery index, vote_jury_round_rate_mon_page_idx, or
--     vote_jury_round_ratedesc_mon_page_idx after V85/V99 on 10.8+). MariaDB 10.6 picks an
--     index-merge intersection of the two for a juror's rows in a round (estimating 487 rows
--     where there are 20,501) and reads far more than the gallery index would.
--   * vote_rate_idx (rate): no plan uses it. Without it the optimizer can't pick a round_id and
--     rate intersection either (seen when only jury_id's index was dropped).
--   * vote_page_id_idx (page_id): a prefix of vote_page_jury_round_uidx (page_id, jury_id,
--     round_id). The 8 statements that used it read that key or vote_round_page_rate_idx instead,
--     at the same speed (within 0.4 ms).
-- No code names any of them (no index hints).
--
-- The foreign key: vote's only one, vote_page_id_fk (page_id -> images, ON DELETE CASCADE), was
-- served by vote_page_id_idx (V58's selection_page_id_index). InnoDB moves it to
-- vote_page_jury_round_uidx, which starts with page_id, in the same ALTER: the key stays, an
-- image's delete still deletes its votes and a vote for a missing image is still refused (1452).
-- From then on that unique key can't be dropped while the foreign key exists (1553).
--
-- Benchmarks of 2026-11-13 on production's copy (MariaDB 10.6.22, 3.35M votes; 596 and then 636
-- vote statements rebuilt from the code, the write paths included): the 42 statements with the
-- round and juror intersection are 3-10x faster (a large round's gallery page 107 -> 28 ms, a
-- large view step 197 -> 43 ms, a 2026 round's page 27 -> 4 ms); no statement slower, no new
-- intersection. Dropping only one of the round and juror indexes, or turning
-- index_merge_intersection off, made others slower. A round's vote inserts ~14% and deletes
-- ~21% faster, a rate update ~10% (noisy); ~335 MB of index freed (152 MB the round and juror
-- indexes, 121.9 MB page_id's, 60.6 MB rate's).
--
-- The indexes to drop are listed once, below; each is dropped only where it exists, so the
-- script is rerunnable (where none is left, nothing runs: DO 0, no ALTER and no metadata lock).
-- One ALTER, online and metadata-only (ALGORITHM = INPLACE, LOCK = NONE; ~0.5 s on production's
-- copy). A 60 s lock_wait_timeout for the metadata lock at its start and end (a long transaction
-- or backup: the statement fails with nothing changed). If it fails, Flyway records V100 as
-- failed (success = 0) and the next start refuses: delete that row (or run Flyway's repair) and
-- start again; the script reads the state again.
--
-- vote_aside and vote_orphan keep their copies (vote_aside_round_idx, ...): nothing pages them.
-- A pre-V100 binary runs on the schema without these indexes (it never names them).
-- Undo (rebuilds the four online, some seconds each on 3.35M rows):
--   ALTER TABLE vote ADD INDEX vote_rate_idx (rate), ADD INDEX vote_round_idx (round_id),
--     ADD INDEX vote_jury_id_idx (jury_id), ADD INDEX vote_page_id_idx (page_id),
--     ALGORITHM = INPLACE, LOCK = NONE;
-- (EXECUTE IMMEDIATE takes no subquery: the check goes through a variable.)

-- 'DROP INDEX a, DROP INDEX b, ...' for those of the listed indexes that exist, NULL if none does
SET @v100_drop = (SELECT GROUP_CONCAT(CONCAT('DROP INDEX ', INDEX_NAME) ORDER BY INDEX_NAME SEPARATOR ', ')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote' AND SEQ_IN_INDEX = 1
    AND INDEX_NAME IN ('vote_round_idx', 'vote_jury_id_idx', 'vote_rate_idx', 'vote_page_id_idx'));

EXECUTE IMMEDIATE IF(@v100_drop IS NULL, 'DO 0',
  CONCAT('SET STATEMENT lock_wait_timeout = 60 FOR ALTER TABLE vote ', @v100_drop,
    ', ALGORITHM = INPLACE, LOCK = NONE'));

SET @v100_drop = NULL;
