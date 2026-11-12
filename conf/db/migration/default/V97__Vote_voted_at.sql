-- When a juror last changed their vote on a file (the maintainer's design A of 2026-11-12: a juror's
-- "Newest votes first" / "Oldest votes first"). vote.voted_at is set by the app whenever a vote's
-- rate changes to another value (VoteJdbc.rateIn, CriteriaRate.updateRateIn: the database's
-- NOW(3)), NULL again when the vote is cleared to unrated (0), unchanged by a rate to the same
-- value; a vote inserted rated (VoteJdbc.create, batchInsert) gets the time it was given or NOW(3).
-- Existing rows stay NULL: voted before the column, they count as older than any timed vote
-- (a separate backfill from the logs may fill them later).
--
-- TIMESTAMP(3) NULL DEFAULT NULL, written out: with explicit_defaults_for_timestamp off (MariaDB
-- before 10.10's default, production's 10.6) a TIMESTAMP column declared without NULL would get
-- NOT NULL and could get DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP; declared NULL it
-- gets no automatic default or update on any server (VoteVotedAtMigrationSpec).
--
-- vote_aside gets the same column in the same place: rows move between vote and vote_aside with
-- INSERT/REPLACE ... SELECT * (ImageJdbc.moveVotesAside / moveVotesBack), which needs the same
-- columns in the same order, and keeps a moved vote's time. vote_orphan (V82's archive, nothing
-- writes or reads it) is left as it is.
--
-- 1. The columns: ALGORITHM = INSTANT, metadata only (existing rows read the default NULL; the
--    statement fails rather than copies). Rerunnable. A 60 s lock_wait_timeout for the metadata
--    lock (a long transaction or backup: the statement fails with nothing changed).
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE vote
  ADD COLUMN IF NOT EXISTS voted_at TIMESTAMP(3) NULL DEFAULT NULL,
  ALGORITHM = INSTANT;

SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE vote_aside
  ADD COLUMN IF NOT EXISTS voted_at TIMESTAMP(3) NULL DEFAULT NULL,
  ALGORITHM = INSTANT;

-- 2. A juror's votes in a round by vote time: online (INPLACE, LOCK = NONE: the statement fails
--    rather than copies or locks the table while it is built). Rerunnable.
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE vote
  ADD INDEX IF NOT EXISTS vote_jury_round_voted_at_idx (jury_id, round_id, voted_at),
  ALGORITHM = INPLACE, LOCK = NONE;
