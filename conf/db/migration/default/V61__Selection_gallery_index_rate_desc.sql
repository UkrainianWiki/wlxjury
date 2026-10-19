-- A juror's gallery pages their images ORDER BY rate DESC, monument_id ASC, page_id ASC
-- (GalleryService). V48's (jury_id, round_id, rate, monument_id, page_id) index was meant
-- to give that order, but the directions are mixed, and before MariaDB 10.8 index keys are
-- ascending only (DESC is parsed and ignored): every page sorted all the juror's rows of
-- the round (~19k on the Gatling fixture). The deferred join in ImageDbNew keeps that sort
-- to the narrow index entries; with the rate column stored descending, the index is read
-- in the page's order and the sort goes away.
--
-- Needs MariaDB 10.8+ (production: Ubuntu 24.04's 10.11). Refused on an older server:
-- there the index would be created ascending without a warning, and a dump of it would
-- carry the ascending index to a newer server too.

EXECUTE IMMEDIATE IF(
  CAST(SUBSTRING_INDEX(VERSION(), '.', 1) AS UNSIGNED) * 1000
    + CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(), '.', 2), '.', -1) AS UNSIGNED) < 10008,
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''V61 needs MariaDB 10.8 or later for descending index keys''',
  'DO 0');

ALTER TABLE selection
  ADD INDEX IF NOT EXISTS idx_selection_jury_round_ratedesc_mon_page (jury_id, round_id, rate DESC, monument_id, page_id),
  DROP INDEX IF EXISTS idx_selection_jury_round_rate_mon_page,
  ALGORITHM = INPLACE, LOCK = NONE;

ANALYZE TABLE selection;
