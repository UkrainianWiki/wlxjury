-- An index on comment (contest_id, room): a file's comments in a contest, as its large view
-- reads them (CommentJdbc.findBySubjectAndContest), their number on a page's thumbnails (one
-- grouped query by room, CommentJdbc.countsByRoom), and a round's list of only the files with
-- comments (ImageDbNew.SelectionQuery.commentsIn: a semi-join `page_id IN (SELECT room FROM
-- comment WHERE contest_id = ?)`, and the region and tab counts' join of the contest's commented
-- files). Until now comment had its primary key and the foreign key's index on contest_id only,
-- so each of these read all the contest's comments. Production's size (rehearsal copy, V98):
-- 20,822 comments, at most ~2,000 in a contest.
--
-- V101 was planned for round lineage's round.status, which moved to V102
-- (docs/plans/2026-11-02-roadmap.md, "Migration numbers": numbers are assigned at merge, and this
-- merges first).
--
-- comment's own index on contest_id alone goes in the same ALTER: the new index starts with
-- contest_id, so it serves the foreign key FK_comment_contest (V26) and every lookup the old one
-- did. A fresh database never keeps it: V26's foreign key created that index implicitly, and
-- InnoDB drops such an index by itself once another index can serve the key, which the ADD here
-- is. A database restored from a dump has it as an explicit KEY, which InnoDB keeps, so without the
-- DROP an upgraded schema would differ from a fresh one (FlywayUpgradeSpec). Every non-unique
-- index of exactly (contest_id) is dropped, whatever its name, and only where the new index is
-- there or added in the same statement; the foreign key stays, served by the new index. Where
-- comment.contest_id has drifted to an int (production), the foreign key to contest_jury's bigint
-- id can't be there; an index of (contest_id) goes the same way, and nothing else is touched.
--
-- Rerunnable: by the state it finds (information_schema), the index added where it isn't there,
-- the single-column ones dropped where they are; neither left: DO 0 (no ALTER, no metadata
-- lock). One ALTER, online (ALGORITHM = INPLACE, LOCK = NONE: reads and writes go on; well under
-- a second on 21k rows). A 60 s lock_wait_timeout for the metadata lock at its start and end (a
-- long transaction or backup: the statement fails with nothing changed). If it fails, Flyway
-- records V101 as failed (success = 0) and the next start refuses: delete that row (or run
-- Flyway's repair) and start again; the script reads the state again.
--
-- A pre-V101 binary runs on the schema with the index (it never names either).
-- Undo: ALTER TABLE comment ADD INDEX FK_comment_contest (contest_id),
--   DROP INDEX comment_contest_room_idx, ALGORITHM = INPLACE, LOCK = NONE;
-- (EXECUTE IMMEDIATE takes no subquery: the checks go through variables.)

-- the statement's start; the clauses for the state follow
SET @v101_alter_comment = 'SET STATEMENT lock_wait_timeout = 60 FOR ALTER TABLE comment ';

-- 1 if the new index is there, else 0
SET @v101_has = (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME = 'comment_contest_room_idx');

-- 'DROP INDEX `a`, ...' for each non-unique index of exactly (contest_id), NULL if none
SET @v101_drop = (SELECT GROUP_CONCAT(CONCAT('DROP INDEX `', t.INDEX_NAME, '`') ORDER BY t.INDEX_NAME SEPARATOR ', ')
  FROM (SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols, MIN(NON_UNIQUE) AS non_unique
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment'
        GROUP BY INDEX_NAME) t
  WHERE t.cols = 'contest_id' AND t.non_unique = 1);

SET @v101_alter = NULLIF(CONCAT_WS(', ',
  IF(@v101_has > 0, NULL, 'ADD INDEX comment_contest_room_idx (contest_id, room)'),
  @v101_drop), '');

EXECUTE IMMEDIATE IF(@v101_alter IS NULL, 'DO 0',
  CONCAT(@v101_alter_comment, @v101_alter,
    ', ALGORITHM = INPLACE, LOCK = NONE'));

SET @v101_alter_comment = NULL, @v101_has = NULL, @v101_drop = NULL, @v101_alter = NULL;
