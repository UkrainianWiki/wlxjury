-- /roundstat counts a binary round's selected images:
--   SELECT COUNT(DISTINCT page_id) FROM selection WHERE round_id = ? AND rate = 1
-- No index had (round_id, rate, page_id) together, so the query read every selection
-- row of the round. With this index it is a range scan of the index alone.
-- It replaces idx_selection_round_rate (V46), a prefix of it that still serves
-- WHERE round_id = ? AND rate = ? lookups, so the selection table keeps its index
-- count and a vote updates no more index entries than before.
ALTER TABLE selection
  ADD INDEX IF NOT EXISTS idx_selection_round_rate_page (round_id, rate, page_id),
  DROP INDEX IF EXISTS idx_selection_round_rate,
  ALGORITHM = INPLACE, LOCK = NONE;

ANALYZE TABLE selection;
