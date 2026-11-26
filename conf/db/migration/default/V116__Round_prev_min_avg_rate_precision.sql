-- The round form's one cut control (the cut picker, PR #237; regional winners plan
-- docs/plans/2026-11-11-regional-winners.md §10.4): a top N and "average file rating at least X" are
-- the same cut, and the form stores a rated source's cut as its score threshold in
-- round.prev_min_avg_rate (services.CutPicker.threshold: the shortest decimal, at most 4 places,
-- that keeps file N and leaves out file N + 1). DECIMAL(4,2) held 2 places up to 99.99: a cut
-- between 9.909 (109/11) and 9.9 needs 9.909, one between 98/99 (0.98989…) and 97/98 (0.98979…)
-- needs 0.9898, and a sum or criteria score can pass 100. DECIMAL(10,4) holds 4 places up to
-- 999999.9999, so every cut between two averages of up to about 100 votes per file (they differ by
-- at least 1/9900 > 0.0001) is stored exactly.
--
-- Numbered V116: production has V115 (the round track label); no open PR adds V116.
--
-- Schema only: the column widened, its values kept exactly (a widening MODIFY converts 7.50 to
-- 7.5000), still nullable with no default. Rerunnable (the same MODIFY again changes nothing).
-- round has one row per round (a few hundred): the table copy takes milliseconds. A 60 s
-- lock_wait_timeout (a long transaction or backup: it fails with nothing changed).
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE round MODIFY COLUMN prev_min_avg_rate DECIMAL(10, 4) NULL DEFAULT NULL;

-- An older binary runs on this schema: it reads the column as a BigDecimal and writes 2-place values,
-- both of which DECIMAL(10,4) holds.
-- Undo, after reopen (a value with more than 2 places is rounded to 2, which may move its cut; one of
-- 100 or more is refused by the MODIFY: set those rounds' thresholds first):
--   UPDATE round SET prev_min_avg_rate = ROUND(prev_min_avg_rate, 2), created_at = created_at
--     WHERE prev_min_avg_rate <> ROUND(prev_min_avg_rate, 2);
--   ALTER TABLE round MODIFY COLUMN prev_min_avg_rate DECIMAL(4, 2) NULL DEFAULT NULL;
--   DELETE FROM flyway_schema_history WHERE version = '116';
