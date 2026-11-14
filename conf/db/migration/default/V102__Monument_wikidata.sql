-- A monument's Wikidata item (the maintainer's decision D6 of 2026-11-13): `Q` and its number,
-- as the list rows' `wikidata` parameter gives it (uk.wikipedia's ВЛП-рядок and ВЛЗ-рядок; on
-- the list of 68-104-0222, fetched 2026-11-13, on 357 of 357 rows). scalawiki maps no list
-- column to it, so it arrives in the parsed monument's otherParams; the monument list import
-- ("Update monument lists", MonumentListImportHandler, MonumentJdbc.batchInsert) stores it,
-- normalized (MonumentJdbc.wikidataOf: anything but `Q` and digits is NULL), and the monument
-- page links https://www.wikidata.org/wiki/Q... . Existing rows stay NULL until their lists are
-- imported again.
--
-- VARCHAR(16): Wikidata's ids are under 10 digits today (Q1 to ~Q135000000).
-- No index: read only with the monument, by its primary key.
--
-- V102 was planned for round lineage's round.status, which moved to V103
-- (docs/plans/2026-11-02-roadmap.md, "Migration numbers": numbers are assigned at merge).
--
-- ALGORITHM = INSTANT, metadata only (monument has 132,089 rows on production's copy; existing
-- rows read the default NULL; the statement fails rather than copies). Rerunnable: IF NOT
-- EXISTS (a rerun leaves a note, no change). A 60 s lock_wait_timeout for the metadata lock (a
-- long transaction or backup: the statement fails with nothing changed). If it fails, Flyway
-- records V102 as failed (success = 0) and the next start refuses: delete that row (or run
-- Flyway's repair) and start again.
--
-- A pre-V102 binary runs on the schema with the column (it never names it; its imports leave it
-- as it is: the upsert lists its columns).
-- Undo: ALTER TABLE monument DROP COLUMN wikidata, ALGORITHM = INSTANT;
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE monument
  ADD COLUMN IF NOT EXISTS wikidata VARCHAR(16) NULL DEFAULT NULL,
  ALGORITHM = INSTANT;
