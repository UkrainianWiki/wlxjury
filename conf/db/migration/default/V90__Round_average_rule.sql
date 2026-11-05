-- How a rated round scores a file from its jurors' rates (org.intracer.wmua.AverageRule; the user's
-- decisions of 2026-11-05, docs/plans/2026-11-02-round-images-two-step.md §17 "P2b results"): the
-- round's one option for it, used by every view of its results and by a next round's top-N and
-- "min average rate" from it.
--  * 'rated':    the rate sum over the jurors who rated the file (rate > 0), unrated votes left out;
--  * 'assigned': over the jurors the file is assigned to (its vote rows), unrated counted as 0;
--  * 'sum':      the rate sum itself (a file a juror skipped gets nothing for it).
-- A yes/no round has no average (its lists count approvals and rejections): its value plays no part.
-- It replaces the legacy optional_rate flag (2015, never on the round form), which divided the sum
-- by the round's active jurors: the same for every file, so its order was the sum's. The code no
-- longer reads optional_rate; the column stays for a rollback (a later clean-up drops it).
--
-- 1. The column, with the default for new rounds and every existing one: ALGORITHM=INSTANT, metadata
--    only (existing rows read the default; the statement fails rather than copies). Rerunnable.
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS average_rule VARCHAR(16) NOT NULL DEFAULT 'rated',
  ALGORITHM = INSTANT;

-- 2. An optional_rate round becomes a 'sum' round: the same order, and it now shows the sum (before,
--    the sum over the round's active jurors). `round` is small (one row per round). created_at is
--    set to itself: production's has ON UPDATE CURRENT_TIMESTAMP (V86).
UPDATE round SET average_rule = 'sum', created_at = created_at
  WHERE optional_rate = 1 AND BINARY average_rule = 'rated';

-- 3. The allowlist, compared byte for byte (db practices §1): one statement, so a failure can't
--    leave the column without its check; rerunnable. MariaDB checks the existing rows (all 'rated'
--    or 'sum' here) as it adds it.
ALTER TABLE round DROP CONSTRAINT IF EXISTS round_average_rule_chk,
  ADD CONSTRAINT round_average_rule_chk CHECK (BINARY average_rule IN ('rated', 'assigned', 'sum'));
