-- Round settings change log (the maintainer's decision of 2026-11-25): each saved change of a round's
-- settings writes a contest_change entry, in the transaction of the save, with who (actor_id, an
-- account id; never an email) and the old and new values of only the settings that changed
-- (db.scalikejdbc.RoundChanges). A save that changes nothing writes none.
--
-- Planned after master's V113; the unmerged list repair branch holds V114 (it widens the same CHECK
-- with files_repaired and files_repair_undone, kept here so that either order of the two merges
-- leaves every kind allowed). Whichever of the two merges second renumbers to above master's top
-- first: Flyway never takes a lower version after a higher one has run (outOfOrder off).
--
--  * contest_change's kind CHECK (V112, whose values it keeps) gains round_created (the round form's
--    new round), round_changed (the round form's edit; a start or stop by an account that doesn't
--    judge the round; the jurors per file set on the round's jurors form) and round_filters_reapplied
--    (the round form's "Re-apply filters" on a round held in review). detail: {"round": id,
--    "number": n, "path": "create" | "edit" | "reapply" | "start" | "stop" | "jurors",
--    "changed": {field: {"from": old, "to": new}}}; a creation's "from" is null.
--
-- Schema only: no data changes. Rerunnable: the CHECK dropped if there and added again
-- (contest_change: a few hundred rows; milliseconds; writes wait for it). A 60 s lock_wait_timeout
-- (a long transaction or backup: it fails with nothing changed).
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE contest_change DROP CONSTRAINT IF EXISTS contest_change_kind_chk,
  ADD CONSTRAINT contest_change_kind_chk
    CHECK (kind IN ('created', 'identity', 'series_assigned', 'series_unassigned', 'series_changed',
                    'split_out', 'split_in', 'split_undone', 'retired', 'unretired',
                    'round_juror_stopped', 'round_juror_reopened', 'round_juror_closed',
                    'files_repaired', 'files_repair_undone',
                    'round_created', 'round_changed', 'round_filters_reapplied'));

-- A pre-V115 binary runs on this schema: it writes none of the new kinds; its round statistics page
-- reads the kinds it knows only, and its change log shows the new rows by message keys it lacks.
-- Undo, after reopen (the rounds' settings entries are lost):
--   DELETE FROM contest_change WHERE kind IN ('round_created', 'round_changed', 'round_filters_reapplied');
--   ALTER TABLE contest_change DROP CONSTRAINT IF EXISTS contest_change_kind_chk,
--     ADD CONSTRAINT contest_change_kind_chk CHECK (kind IN ('created', 'identity', 'series_assigned',
--     'series_unassigned', 'series_changed', 'split_out', 'split_in', 'split_undone', 'retired', 'unretired',
--     'round_juror_stopped', 'round_juror_reopened', 'round_juror_closed'));
--   (with 'files_repaired', 'files_repair_undone' too where V114, the list repair, has run)
--   DELETE FROM flyway_schema_history WHERE version = '115';
