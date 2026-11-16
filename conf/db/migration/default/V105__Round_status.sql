-- A round's lifecycle state (round lineage plan §2.6, built as P3-2 of the round-editing plan,
-- docs/plans/2026-11-16-round-editing.md §6 and §10): draft | paused | active | finalized |
-- cancelled, with who changed it and when. `active` and `distributing` stay written in step with
-- it (db.scalikejdbc.Round's writers), so Round.openToJurors, activeRounds and openCond keep
-- reading them. NULL until the backfill below; a reader takes a NULL status from the flags
-- (Round.lifecycle: draft if distributing, active if open, else paused).
--
--   draft     distributing = 1, active = 0: the creation's fill runs, or failed, or never completed
--   paused    distributing = 1, active = 0: a round in review (filled, not distributed yet; the
--             two-step plan's P3); distributing = 0, active = 0: stopped, or finished
--   active    distributing = 0, active = 1 (or frozen while active)
--   cancelled a creation job an admin cancelled (Cancel itself is round lineage's)
--   finalized not written yet (Montage parity)
--
-- status_changed_by: who acted (a start or stop, a Cancel); for a job's write, who queued the job
-- (its creator, the reviewer who distributed, an admin reinstating a cancelled creation); NULL for
-- this backfill.
--
-- Plain statements, as V95's: neither depends on the session's sql_mode.
--
-- 1. The columns, NULL for every round. Rerunnable. No ALGORITHM clause: step 2 copies `round`
--    anyway (one row per round, milliseconds). round lineage's `track` and `created_at` change come
--    later.
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS status_changed_at TIMESTAMP(3) NULL DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS status_changed_by BIGINT UNSIGNED NULL DEFAULT NULL;

-- 2. Its values, checked byte for byte. One statement, rerunnable; MariaDB checks the existing rows
--    (all NULL) as it adds it, which copies `round`, one row per round: milliseconds (as V95's).
ALTER TABLE round DROP CONSTRAINT IF EXISTS round_status_chk,
  ADD CONSTRAINT round_status_chk
    CHECK (status IS NULL OR BINARY status IN ('draft', 'paused', 'active', 'finalized', 'cancelled'));

-- 3. The backfill, from the flags and the round's latest round_distribution job (the highest id
--    of its subject, as services.jobs.JobJdbc.latestBySubjects reads it). A `cancelled` round whose
--    creation never completed (distributing = 1) is left alone, so this UPDATE can be rerun as it
--    is: after a rollback to a binary from before this migration and forward again (its starts,
--    stops and creations leave the status stale), rerun it, then the round_image_check job. One the
--    older binary finished meanwhile (distributing = 0) takes its flags' status again.
--
--   distributing = 1, latest job of mode `distribute`, or a fill queued on a round in review
--     (`fromReview`), or a fill done                               -> paused (in review)
--   distributing = 1, latest job cancelled                         -> cancelled
--   distributing = 1, otherwise (queued, running, failed; or none) -> draft
--   distributing = 0, active = 1 or frozen while active            -> active
--   distributing = 0, otherwise                                    -> paused
UPDATE round r
  LEFT JOIN (SELECT j.subject_id, j.status,
                    JSON_VALUE(j.params, '$.mode') AS mode,
                    JSON_EXTRACT(j.params, '$.fromReview') AS from_review
             FROM job j
             JOIN (SELECT subject_id, MAX(id) AS id FROM job
                   WHERE kind = 'round_distribution' AND subject_type = 'round' GROUP BY subject_id) latest
               ON latest.id = j.id) lj
    ON lj.subject_id = r.id
SET r.status = CASE
      WHEN r.distributing = 1 AND (lj.mode = 'distribute' OR lj.from_review = 'true'
                                   OR (lj.mode = 'fill' AND lj.status = 'done')) THEN 'paused'
      WHEN r.distributing = 1 AND lj.status = 'cancelled' THEN 'cancelled'
      WHEN r.distributing = 1 THEN 'draft'
      WHEN r.active = 1 OR (r.freeze_count > 0 AND r.active_before_freeze = 1) THEN 'active'
      ELSE 'paused'
    END,
    r.created_at = r.created_at
WHERE r.status IS NULL OR BINARY r.status <> 'cancelled' OR r.distributing = 0;
