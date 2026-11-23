-- round_user.active NOT NULL DEFAULT TRUE; a NULL becomes FALSE, stopped (the maintainer's decision
-- of 2026-11-20: the NULL rows belong to old rounds, closed long ago). V37 created the column
-- nullable; V38's copy wrote TRUE, so the NULLs came from later writes of the app. Until now the
-- tool disagreed with itself about such a row: the juror's own pages said "juror stopped"
-- (Round.activeRounds, RoundUser.isNotActive) and the round statistics showed the juror stopped,
-- but the large view still opened for voting and a vote was still taken (RoundUser.isStopped,
-- FALSE only). The code's NULL handling goes with this migration. Every writer sets TRUE or FALSE;
-- the default is for a row written without it.
--
-- Pre-deploy, read-only: the NULL rows of rounds open to jurors now, whose jurors this stops (the
-- PR's deploy note), and SHOW PROCESSLIST (no backup or long transaction running: the rebuild
-- waits for them at its start and end).
--
-- 1. A NULL becomes FALSE, stopped. Rerunnable.
UPDATE round_user SET active = FALSE WHERE active IS NULL;

-- 2. NOT NULL DEFAULT TRUE, online: ALGORITHM=INPLACE, LOCK=NONE rebuilds round_user while it is
--    written (the statement fails rather than copies or locks the table; a NULL to NOT NULL change
--    can't be INSTANT). Its indexes are kept, V55's idx_round_user_round_active on
--    (round_id, active) among them. Only while the column is still nullable, so a rerun rebuilds
--    nothing. Run with strict mode added to the session's sql_mode (an in-place NOT NULL needs it)
--    and a 60 s lock_wait_timeout (the rebuild's metadata lock at its start and end waits at most
--    that long for a long transaction or a backup, then fails with nothing changed), for that
--    statement only (SET STATEMENT ... FOR), as V91 does.
SET @v108_set = CONCAT('SET STATEMENT sql_mode = ''',
  CONCAT_WS(',', NULLIF(@@SESSION.sql_mode, ''), 'STRICT_TRANS_TABLES'), ''', lock_wait_timeout = 60 FOR ');

SET @v108_active = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'round_user' AND COLUMN_NAME = 'active');
EXECUTE IMMEDIATE IF(@v108_active = 'YES',
  CONCAT(@v108_set, 'ALTER TABLE round_user MODIFY active BOOLEAN NOT NULL DEFAULT TRUE, ALGORITHM = INPLACE, LOCK = NONE'),
  'DO 0');

SET @v108_active = NULL, @v108_set = NULL;

-- A pre-V108 binary runs on this schema (it writes TRUE or FALSE, never NULL). Undo, after reopen
-- (the rows set to FALSE stay FALSE, which an older release shows as stopped on the juror's pages):
--   ALTER TABLE round_user MODIFY active BOOLEAN NULL DEFAULT NULL, ALGORITHM = INPLACE, LOCK = NONE;
--   DELETE FROM flyway_schema_history WHERE version = '108';
