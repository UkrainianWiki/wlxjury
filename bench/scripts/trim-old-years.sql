-- Phase 5: a copy with only the last two contest years (2024 and 2025), to measure how
-- much the data size matters. Run on a copy only (bench/scripts/bench.sh), at V53.
--
-- Deletes the older contests' rounds with their jurors, selections, criteria rates and
-- comments, the category membership of their image categories, and the images no
-- remaining contest uses (neither in a remaining contest's category nor in a remaining
-- round). Monuments (shared by all years' contests of a country) and legacy tables stay.
-- Then rebuilds the trimmed tables so their size on disk reflects the rows left.

CREATE TEMPORARY TABLE keep_contests (id BIGINT UNSIGNED PRIMARY KEY, category_id BIGINT UNSIGNED)
  SELECT id, category_id FROM contest_jury WHERE year >= 2024;
CREATE TEMPORARY TABLE keep_rounds (id BIGINT UNSIGNED PRIMARY KEY)
  SELECT r.id FROM rounds r JOIN keep_contests c ON c.id = r.contest_id;

DELETE s FROM selection s LEFT JOIN keep_rounds k ON k.id = s.round_id WHERE k.id IS NULL;
DELETE cr FROM criteria_rate cr LEFT JOIN selection s ON s.id = cr.selection WHERE s.id IS NULL;
DELETE ru FROM round_user ru LEFT JOIN keep_rounds k ON k.id = ru.round_id WHERE k.id IS NULL;
DELETE cm FROM comment cm LEFT JOIN keep_contests c ON c.id = cm.contest_id WHERE c.id IS NULL;
DELETE r FROM rounds r LEFT JOIN keep_rounds k ON k.id = r.id WHERE k.id IS NULL;
DELETE m FROM category_members m LEFT JOIN keep_contests c ON c.category_id = m.category_id WHERE c.id IS NULL;
DELETE i FROM images i
  WHERE NOT EXISTS (SELECT 1 FROM category_members m WHERE m.page_id = i.page_id)
    AND NOT EXISTS (SELECT 1 FROM selection s WHERE s.page_id = i.page_id);
DELETE c FROM contest_jury c LEFT JOIN keep_contests k ON k.id = c.id WHERE k.id IS NULL;

OPTIMIZE TABLE selection, criteria_rate, round_user, comment, rounds, category_members, images, contest_jury;

SELECT table_name, table_rows, ROUND((data_length + index_length) / 1048576) AS mb
FROM information_schema.TABLES WHERE table_schema = DATABASE()
  AND table_name IN ('selection', 'images', 'category_members', 'criteria_rate', 'rounds', 'monument')
ORDER BY mb DESC;
