-- An index on images (author): a round's list filtered by author (a round list's `author=`, staff
-- only: ImageDbNew.SelectionQuery.byAuthor, `s.page_id IN (SELECT ia.page_id FROM images ia WHERE
-- ia.author = ? AND BINARY ia.author = ?)`), and the contest files page's author filter
-- (ImageJdbc.categoryAuthorFiles), which can read the author's files by it, then the list's rows
-- of each (QueryPlanSpec's seed: all the jurors' list does; one juror's list kept reading the
-- juror's rows there). Without it MariaDB read the list's vote rows and looked up each file's author: on
-- production's copy (rehearsal, MariaDB 10.6.22, 2026-11-14) 5-9 ms per statement for a typical
-- juror's list (1.6k files), 440-498 ms cold and 21-29 ms warm for the largest one (22.6k files),
-- 68-76 ms for all the jurors' list of a 29k round, and a large view step runs 5-7 such
-- statements. Read by it, a statement reads the author's files only: at most 7,654 (the heaviest
-- author there; 195,752 of 1,029,265 images have an author, 1,800 distinct). The round filters
-- proposal v4, decision 4 = A.
--
-- V103 is reserved for round lineage's round.status (docs/plans/2026-11-02-roadmap.md, "Migration
-- numbers"): this script takes V104 and leaves the gap. Flyway runs versions in order and needs no
-- consecutive numbers (V45 was never there). With production's settings (outOfOrder off, no
-- ignoreMigrationPatterns) a database that has V104 refuses a V103 added after it: whichever of the
-- two deploys second must be renumbered above the other (FlywayUpgradeSpec checks both).
--
-- Key length: varchar(255) in utf8mb4 is at most 1,020 bytes, under the 3,072 of InnoDB's DYNAMIC
-- row format, which V55_1 gave images. A full-column index, not a prefix: the filter compares the
-- whole value.
--
-- Rerunnable: ADD INDEX IF NOT EXISTS (a rerun leaves a note, no change). One ALTER, online
-- (ALGORITHM = INPLACE, LOCK = NONE: reads and writes go on; an index on 1.03M rows, 81% of them
-- NULL, is expected to take seconds and some 20-35 MB: not measured there, as no DDL runs on the
-- rehearsal copy). A 60 s lock_wait_timeout for the metadata lock at its start and end (a long
-- transaction or backup: the statement fails with nothing changed). If it fails, Flyway records
-- V104 as failed (success = 0) and the next start refuses: delete that row (or run Flyway's
-- repair) and start again. Then ANALYZE TABLE images, so the optimizer has the new index's
-- statistics at once.
--
-- A pre-V104 binary runs on the schema with the index (it never names it).
-- Undo: ALTER TABLE images DROP INDEX images_author_idx, ALGORITHM = INPLACE, LOCK = NONE;
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE images
  ADD INDEX IF NOT EXISTS images_author_idx (author),
  ALGORITHM = INPLACE, LOCK = NONE;

ANALYZE TABLE images;
