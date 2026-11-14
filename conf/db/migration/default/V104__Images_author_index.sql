-- An index on images (author): a round's list filtered by author (a round list's `author=`, staff
-- only: ImageDbNew.SelectionQuery.byAuthor, `s.page_id IN (SELECT ia.page_id FROM images ia WHERE
-- ia.author = ? AND BINARY ia.author = ?)`), the contest files page's author filter
-- (ImageJdbc.categoryAuthorFiles, which reads the author's files first) and the conflicts of
-- interest (CoiMembers.files, `author IN (...)`). The round filters proposal v4, decision 4 = A.
--
-- Measured on production's copy (rehearsal, MariaDB 10.6.22, 256 MB buffer pool; 1,029,265 images,
-- 195,752 with an author, the heaviest 7,654 files; 2026-11-14), the index built there by hand for
-- the measurement and dropped after it. ANALYZE FORMAT=JSON, 3 runs each, ms (first run cold), with
-- the index and with IGNORE INDEX (images_author_idx):
--   largest juror list (r1727, 22,577 files) x heaviest author (1,949 files in it), count:
--                                                     89 / 48 / 28     vs  81 / 66 / 62
--     its page (deferred join's inner select):        64 / 23 / 24     vs  23 / 24 / 24 (same plan)
--   largest juror list x a typical author (2,520), count: 70 / 14 / 14 vs  62 / 65 / 64
--     its page:                                       21 / 14 / 17     vs  23 / 23 / 26
--   typical juror list (r855, 1,642 files), count:    394 / 6 / 6      vs   6 /  7 /  6 (same plan)
--     its page:                                       44 /  9 /  9     vs  11 /  9 / 10 (same plan)
--   all the jurors' list of a 29k round (r855), count: 18 /  7 /  7    vs 523 / 101 / 116
--     its region counts:                              30 / 14 / 12     vs 122 /  87 /  80
--   contest files author filter (categoryAuthorFiles):  7 /  7 /  8    vs 6,978 / 7,301 / 7,229
--     (its earlier shape, from the category's members: 97-279 either way)
--   CoiMembers.files (2 members):                     37 / 30 / 29     vs 515 / 100 /  95
--   CoiMembers.roundFiles (round_image first):        81 / 69 / 63     vs  76 /  58 /  59 (same plan)
-- MariaDB chooses per statement: where it reads the juror's rows first it does so with or without
-- the index, at the same speed. A shape driven from the author index (images first, or vote by its
-- unique key) was no faster for a juror's list (largest x heaviest page 24-27 ms) and wasn't taken.
-- The ALTER took about 8 s there (wall time, with docker exec).
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
-- (ALGORITHM = INPLACE, LOCK = NONE: reads and writes go on; about 8 s on production's copy; its
-- size wasn't read, estimated at 20-35 MB). A 60 s lock_wait_timeout for the metadata lock at its start and end (a long
-- transaction or backup: the statement fails with nothing changed). If it fails, Flyway records
-- V104 as failed (success = 0) and the next start refuses: delete that row (or run Flyway's
-- repair) and start again. Then ANALYZE TABLE images, so the optimizer has the new index's
-- statistics at once, under the same lock_wait_timeout (its metadata lock at the end).
--
-- A pre-V104 binary runs on the schema with the index (it never names it).
-- Undo: ALTER TABLE images DROP INDEX images_author_idx, ALGORITHM = INPLACE, LOCK = NONE;
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE images
  ADD INDEX IF NOT EXISTS images_author_idx (author),
  ALGORITHM = INPLACE, LOCK = NONE;

SET STATEMENT lock_wait_timeout = 60 FOR ANALYZE TABLE images;
