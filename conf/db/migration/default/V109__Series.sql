-- Contest series (docs/plans/2026-11-18-contest-series.md §3.1, §3.4; PR CY-1; CA1, CA8, CA15
-- decided by the maintainer 2026-11-18): a series ties one contest per year across years
-- ("Wiki Loves Monuments in Nepal"); root creates, renames, retires series and assigns contests to
-- them (db.scalikejdbc.SeriesJdbc, services.series.ContestSeries). The previous year's contest is
-- derived, never stored: the live contest of the same series with the next lower year.
--
--  * series: name; contest_type, a scalawiki ContestType code ("wlm", "wle") or NULL; country, a
--    country name, "International", or NULL; campaign, the Commons campaign config code if any;
--    who created and retired it and when (no foreign keys: the row outlives an account). A retired
--    series keeps its contests, which stay live; nothing is created in it. The generated live_name
--    is the name while the series is live, and UNIQUE (live_name) keeps one live series per name
--    (the column's utf8mb4_general_ci collation: case and accents aside, as the menus show them).
--  * contest_jury gains series_id (a nullable foreign key, ON DELETE RESTRICT: a series with
--    contests can't be deleted), created_at and created_by (NULL for contests created before this
--    migration; created_at has no ON UPDATE), retired_at and retired_by (a retired contest gives
--    its members no access and is hidden from menus and lists). The generated live_series_id is
--    series_id while the contest is not retired, and UNIQUE (live_series_id, year) keeps one live
--    contest per series and year (CA15); NULLs (no series, or retired) never collide.
--  * contest_change: what root did to contests and series (CA8). contest_id NULL: a row about the
--    series itself (kinds created, identity, retired, unretired: its creation, renaming,
--    retirement); otherwise about the contest, with its series at the time. batch_id: the rows of
--    one bulk assignment (the id of its first row), which "Undo batch" reverses. detail: JSON (old
--    and new values, the series moved from). Every row is about a contest or a series (a CHECK). No
--    foreign keys: the log outlives contests, series and accounts.
--
-- Rerunnable: CREATE TABLE IF NOT EXISTS, ADD COLUMN / KEY / FOREIGN KEY IF NOT EXISTS. The
-- contest_jury change copies the table (production: ~630 rows, milliseconds; writes wait for it);
-- the foreign key takes a metadata lock on series for a moment. A 60 s lock_wait_timeout for each
-- statement (a long transaction or backup: the statement fails with nothing changed).
SET STATEMENT lock_wait_timeout = 60 FOR
CREATE TABLE IF NOT EXISTS series (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name          VARCHAR(255)    NOT NULL,
  contest_type  VARCHAR(32)     NULL DEFAULT NULL,
  country       VARCHAR(255)    NULL DEFAULT NULL,
  campaign      VARCHAR(32)     NULL DEFAULT NULL,
  created_by    BIGINT UNSIGNED NULL DEFAULT NULL,
  created_at    TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  retired_at    TIMESTAMP(3)    NULL DEFAULT NULL,
  retired_by    BIGINT UNSIGNED NULL DEFAULT NULL,
  live_name     VARCHAR(255) AS (IF(retired_at IS NULL, name, NULL)) PERSISTENT,
  PRIMARY KEY (id),
  UNIQUE KEY series_live_name_uidx (live_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE contest_jury
  ADD COLUMN IF NOT EXISTS series_id      BIGINT UNSIGNED NULL DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS created_at     TIMESTAMP(3)    NULL DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS created_by     BIGINT UNSIGNED NULL DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS retired_at     TIMESTAMP(3)    NULL DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS retired_by     BIGINT UNSIGNED NULL DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS live_series_id BIGINT UNSIGNED AS (IF(retired_at IS NULL, series_id, NULL)) PERSISTENT,
  ADD KEY IF NOT EXISTS contest_jury_series_id_idx (series_id),
  ADD UNIQUE KEY IF NOT EXISTS contest_jury_live_series_year_uidx (live_series_id, year),
  ADD CONSTRAINT contest_jury_series_id_fk FOREIGN KEY IF NOT EXISTS (series_id) REFERENCES series (id) ON DELETE RESTRICT;

SET STATEMENT lock_wait_timeout = 60 FOR
CREATE TABLE IF NOT EXISTS contest_change (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  contest_id  BIGINT UNSIGNED NULL DEFAULT NULL,
  series_id   BIGINT UNSIGNED NULL DEFAULT NULL,
  actor_id    BIGINT UNSIGNED NOT NULL,
  kind        VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  batch_id    BIGINT UNSIGNED NULL DEFAULT NULL,
  detail      LONGTEXT        NULL DEFAULT NULL,
  comment     VARCHAR(500)    NULL DEFAULT NULL,
  created_at  TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY contest_change_contest_idx (contest_id, id),
  KEY contest_change_series_idx (series_id, id),
  KEY contest_change_batch_idx (batch_id),
  CONSTRAINT contest_change_kind_chk
    CHECK (kind IN ('created', 'identity', 'series_assigned', 'series_unassigned', 'series_changed',
                    'split_out', 'split_in', 'split_undone', 'retired', 'unretired')),
  CONSTRAINT contest_change_detail_chk CHECK (detail IS NULL OR JSON_VALID(detail)),
  CONSTRAINT contest_change_subject_chk CHECK (contest_id IS NOT NULL OR series_id IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- A pre-V109 binary runs on this schema: it reads contest_jury by its own columns and writes none
-- of the new ones (a contest it adds has no series, created_at NULL). Undo, after reopen (the series
-- assignments and the log are lost; retired contests become live again: un-retire them first if
-- two of one series and year would collide, which no longer matters once the key is gone):
--   ALTER TABLE contest_jury DROP FOREIGN KEY IF EXISTS contest_jury_series_id_fk,
--     DROP KEY IF EXISTS contest_jury_live_series_year_uidx, DROP KEY IF EXISTS contest_jury_series_id_idx,
--     DROP COLUMN IF EXISTS live_series_id, DROP COLUMN IF EXISTS series_id, DROP COLUMN IF EXISTS created_at,
--     DROP COLUMN IF EXISTS created_by, DROP COLUMN IF EXISTS retired_at, DROP COLUMN IF EXISTS retired_by;
--   DROP TABLE IF EXISTS contest_change;
--   DROP TABLE IF EXISTS series;
--   DELETE FROM flyway_schema_history WHERE version = '109';
