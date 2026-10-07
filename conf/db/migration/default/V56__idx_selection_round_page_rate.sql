-- /roundstat counts a binary round's selected images:
--   SELECT COUNT(DISTINCT page_id) FROM selection WHERE round_id = ? AND rate = 1
-- idx_selection_round_rate (round_id, rate) found the rows but not their page_id, so
-- it read every selected row of the round. Adding rate to V55's (round_id, page_id)
-- index makes that count a scan of the index alone, in page_id order.
--
-- Not (round_id, rate, page_id): the optimizer then also picks that covering index for
-- the per-image sums of a round (byRoundMerged: SUM(rate) ... GROUP BY page_id, behind
-- image distribution and the round edit page), which in rate order took 5 s instead of
-- 0.9 s on the Gatling fixture; (round_id, page_id, rate) takes it to 0.5 s.
--
-- It replaces idx_selection_round_page, its prefix, so the index count stays the same.
ALTER TABLE selection
  ADD INDEX IF NOT EXISTS idx_selection_round_page_rate (round_id, page_id, rate),
  DROP INDEX IF EXISTS idx_selection_round_page,
  ALGORITHM = INPLACE, LOCK = NONE;

ANALYZE TABLE selection;
