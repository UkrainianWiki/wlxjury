-- A round's manual track label (round lineage plan, docs/plans/2026-11-10-round-lineage.md §2.3 and
-- decision 4; built in slice A, §8.1): it names the round's track, and later rounds without a label
-- of their own inherit it; identical labels share a track; "Main" puts the track in the Main group;
-- NULL (or empty) is the automatic track (services.RoundLineage, rule v4). Set on the round form, for
-- new and existing rounds, by its own setter (db.scalikejdbc.Round.setTrack, created_at kept).
--
-- Numbered V115: V114 is the round change log (#178), merged before it. Decision 17's created_at
-- change stays with the rest of the lineage plan's phase 1.
--
-- Schema only: one nullable column, no data changes. Rerunnable (ADD COLUMN IF NOT EXISTS). No
-- ALGORITHM clause: MariaDB adds a last nullable column instantly where it can (round: one row per
-- round, milliseconds otherwise). A 60 s lock_wait_timeout (a long transaction or backup: it fails
-- with nothing changed).
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS track VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL;

-- An older binary runs on this schema: it neither reads nor writes the column, so its rounds keep
-- their labels and new ones get none. Undo, after reopen (the labels are lost):
--   ALTER TABLE round DROP COLUMN IF EXISTS track;
--   DELETE FROM flyway_schema_history WHERE version = '115';
