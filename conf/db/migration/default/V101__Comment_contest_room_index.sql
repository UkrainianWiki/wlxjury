-- An index on comment (contest_id, room): a file's comments in a contest, as its large view
-- reads them (CommentJdbc.findBySubjectAndContest), their number on a page's thumbnails (one
-- grouped query by room, CommentJdbc.countsByRoom), and a round's list of only the files with
-- comments (ImageDbNew.SelectionQuery.commentsIn: a semi-join `page_id IN (SELECT room FROM
-- comment WHERE contest_id = ?)`, and the region and tab counts' join of the contest's commented
-- files). Until now comment had its primary key and the foreign key's index on contest_id only,
-- so each of these read all the contest's comments. Production's size (rehearsal copy, V98):
-- 20,822 comments, at most ~2,000 in a contest.
--
-- V101 was planned for round lineage's round.status, which moved to V101 (docs/plans/2026-11-02-roadmap.md,
-- "Migration numbers": numbers are assigned at merge, and this merges first).
--
-- Rerunnable: the index is added only where it isn't there (DO 0 otherwise: no ALTER, no
-- metadata lock). One ALTER, online (ALGORITHM = INPLACE, LOCK = NONE: reads and writes go on;
-- well under a second on 21k rows). A 60 s lock_wait_timeout for the metadata lock at its start
-- and end (a long transaction or backup: the statement fails with nothing changed). If it fails,
-- Flyway records V101 as failed (success = 0) and the next start refuses: delete that row (or
-- run Flyway's repair) and start again; the script reads the state again.
--
-- The foreign key's own index (FK_comment_contest, on contest_id) stays: the new one starts with
-- contest_id, so it could serve the key, but nothing gains from moving it.
-- A pre-V101 binary runs on the schema with the index (it never names it).
-- Undo: ALTER TABLE comment DROP INDEX comment_contest_room_idx, ALGORITHM = INPLACE, LOCK = NONE;
-- (EXECUTE IMMEDIATE takes no subquery: the check goes through a variable.)

SET @v101_has = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME = 'comment_contest_room_idx');

EXECUTE IMMEDIATE IF(@v101_has > 0, 'DO 0',
  'SET STATEMENT lock_wait_timeout = 60 FOR ALTER TABLE comment ADD INDEX comment_contest_room_idx (contest_id, room), ALGORITHM = INPLACE, LOCK = NONE');

SET @v101_has = NULL;
