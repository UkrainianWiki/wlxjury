-- vote.rate and vote_aside.rate NOT NULL DEFAULT 0 (the user's decision of 2026-11-05, roadmap
-- queue item 6, docs/plans/2026-11-02-round-images-two-step.md §18). The columns were nullable
-- only because V1 made them so: nothing the app writes is NULL (a new vote is 0, unrated; a vote
-- is an integer the round takes). The code's NULL handling (a NULL rate read as 0, unrated) goes
-- with this migration. V53's COALESCE(rate, 0) stays: migrations are immutable.
--
-- vote_aside keeps vote's columns (V63: rows move with INSERT ... SELECT *), so it changes too.
-- vote_orphan (V82's archive of votes of no round, nothing reads it) keeps its nullable column.
--
-- Pre-deploy, read-only (the plan's deploy note): SELECT COUNT(*) FROM vote WHERE rate IS NULL,
-- the same on vote_aside, and SELECT @@GLOBAL.sql_mode (STRICT_TRANS_TABLES: without strict mode
-- MariaDB refuses an in-place NOT NULL, and the statement fails before it changes anything).
--
-- 1. A NULL rate becomes 0, unrated, as the code read it. On the rate index; rerunnable.
UPDATE vote SET rate = 0 WHERE rate IS NULL;
UPDATE vote_aside SET rate = 0 WHERE rate IS NULL;

-- 2. NOT NULL DEFAULT 0, online: ALGORITHM=INPLACE, LOCK=NONE rebuilds each table while it is
--    written (the statement fails rather than copies or locks the table; not INSTANT, which a
--    NULL to NOT NULL change can't be). Each only while its column is still nullable, so a rerun
--    rebuilds nothing. (EXECUTE IMMEDIATE takes no subquery: the checks go through variables.)
SET @v91_vote = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote' AND COLUMN_NAME = 'rate');
EXECUTE IMMEDIATE IF(@v91_vote = 'YES',
  'ALTER TABLE vote MODIFY rate INT NOT NULL DEFAULT 0, ALGORITHM = INPLACE, LOCK = NONE',
  'DO 0');

SET @v91_aside = (SELECT IS_NULLABLE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote_aside' AND COLUMN_NAME = 'rate');
EXECUTE IMMEDIATE IF(@v91_aside = 'YES',
  'ALTER TABLE vote_aside MODIFY rate INT NOT NULL DEFAULT 0, ALGORITHM = INPLACE, LOCK = NONE',
  'DO 0');

SET @v91_vote = NULL, @v91_aside = NULL;
