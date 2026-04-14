-- Covering index for roundUserStat query:
--   SELECT jury_id, rate, count(1) FROM selection WHERE round_id = ? GROUP BY jury_id, rate
-- Without this index the query does an idx_selection_round_page range-scan (380k entries)
-- then reads actual rows for jury_id and rate.  With this index all three columns are in
-- the index leaf pages, so the query becomes an index-only scan (~10× faster cold cache).
CREATE INDEX idx_selection_round_jury_rate ON selection(round_id, jury_id, rate);

ANALYZE TABLE selection;
