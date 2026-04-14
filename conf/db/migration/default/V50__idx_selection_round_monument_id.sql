-- Covering index for byRegionStat query:
--   SELECT DISTINCT m.adm0
--   FROM selection s
--   JOIN monument m ON m.id = s.monument_id
--   WHERE s.round_id = ?   [AND s.jury_id = ?]
--
-- With this index MariaDB does an index-range scan on round_id and
-- reads monument_id from the index leaf pages (no row access).
CREATE INDEX idx_selection_round_monument_id ON selection(round_id, monument_id);

ANALYZE TABLE selection;
