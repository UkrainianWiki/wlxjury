-- A round's manual track label (round lineage plan, docs/plans/2026-11-10-round-lineage.md §2.3 and
-- decision 4; built in slice A, §8.1): it names the round's track, and later rounds without a label
-- of their own inherit it; identical labels share a track; "Main" puts the track in the Main group;
-- NULL (or empty) is the automatic track (services.RoundLineage, rule v4). Set on the round form, for
-- new and existing rounds, by its own setter (db.scalikejdbc.Round.setTrack, created_at kept).
--
-- A label change is logged (§8.1) through the round change log (V114, db.scalikejdbc.RoundChanges)
-- as a contest_change kind of its own, round_track_changed, beside the save's other entry: the round
-- form's creation, edit or re-apply. detail as V114's round entries: {"round": id, "number": n,
-- "path": "create" | "edit" | "reapply", "changed": {"track": {"from": old, "to": new}}}.
--
-- Numbered V115: V114 is the round change log (#178), merged before it. Decision 17's created_at
-- change stays with the rest of the lineage plan's phase 1.
--
-- Schema only: one nullable column and the kind CHECK (V114's values kept), no data changes.
-- Rerunnable (ADD COLUMN IF NOT EXISTS; the CHECK dropped if there and added again). No ALGORITHM
-- clause: MariaDB adds a last nullable column instantly where it can (round: one row per round,
-- milliseconds otherwise); the CHECK copies contest_change (a few hundred rows). A 60 s
-- lock_wait_timeout (a long transaction or backup: it fails with nothing changed).
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS track VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL;

SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE contest_change DROP CONSTRAINT IF EXISTS contest_change_kind_chk,
  ADD CONSTRAINT contest_change_kind_chk
    CHECK (kind IN ('created', 'identity', 'series_assigned', 'series_unassigned', 'series_changed',
                    'split_out', 'split_in', 'split_undone', 'retired', 'unretired',
                    'round_juror_stopped', 'round_juror_reopened', 'round_juror_closed',
                    'round_created', 'round_changed', 'round_filters_reapplied', 'round_track_changed'));

-- An older binary runs on this schema: it neither reads nor writes the column, so its rounds keep
-- their labels and new ones get none; its change log shows a label entry by a message key it lacks.
-- Undo, after reopen (the labels and their entries are lost):
--   DELETE FROM contest_change WHERE kind = 'round_track_changed';
--   ALTER TABLE contest_change DROP CONSTRAINT IF EXISTS contest_change_kind_chk,
--     ADD CONSTRAINT contest_change_kind_chk CHECK (kind IN ('created', 'identity', 'series_assigned',
--     'series_unassigned', 'series_changed', 'split_out', 'split_in', 'split_undone', 'retired', 'unretired',
--     'round_juror_stopped', 'round_juror_reopened', 'round_juror_closed', 'round_created', 'round_changed',
--     'round_filters_reapplied'));
--   ALTER TABLE round DROP COLUMN IF EXISTS track;
--   DELETE FROM flyway_schema_history WHERE version = '115';
