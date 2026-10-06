-- Background jobs (services.jobs): long work runs off the HTTP request threads as a job of
-- small steps, each its own short transaction, scheduled in turn on a few worker threads.
-- Generic: one table for every kind of job. Only round_distribution exists now; Commons
-- image imports and monument list imports are planned (docs/plans/2026-10-07-background-
-- distribution.md, "Future job kinds").
--
--  * kind, params, state: what the job is (kind-specific inputs, JSON) and where it is
--    (kind-specific resume cursor, JSON, written in the same transaction as each step's
--    work, so a retry or a restart continues from the last committed step).
--  * subject_type, subject_id: what it works on (a round, a contest).
--  * One active job per subject: active_dedupe_key is dedupe_key while the job is queued
--    or running and NULL otherwise; the unique index on it lets a second request for the
--    same subject fail its insert and attach to the active job instead, also across app
--    instances. A failed job is not active: it neither blocks nor is replaced silently;
--    the service decides (a round distribution resumes its failed job).
--  * owner, heartbeat_at: a lease. A running job belongs to the app instance that claimed
--    it while its heartbeat is fresh; another instance (or the same one after a restart)
--    reclaims it once the heartbeat is older than the lease.
CREATE TABLE IF NOT EXISTS job (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  kind              VARCHAR(64)     NOT NULL,
  contest_id        BIGINT UNSIGNED NULL,
  subject_type      VARCHAR(32)     NULL,
  subject_id        BIGINT          NULL,
  dedupe_key        VARCHAR(190)    NULL,
  status            VARCHAR(16)     NOT NULL,
  params            JSON            NOT NULL,
  state             JSON            NOT NULL,
  progress_done     BIGINT          NULL,
  progress_total    BIGINT          NULL,
  progress_unit     VARCHAR(32)     NULL,
  error             TEXT            NULL,
  attempts          INT             NOT NULL DEFAULT 0,
  created_by        BIGINT UNSIGNED NULL,
  owner             VARCHAR(64)     NULL,
  created_at        TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at        TIMESTAMP(3)    NULL DEFAULT NULL,
  updated_at        TIMESTAMP(3)    NULL DEFAULT NULL,
  finished_at       TIMESTAMP(3)    NULL DEFAULT NULL,
  heartbeat_at      TIMESTAMP(3)    NULL DEFAULT NULL,
  active_dedupe_key VARCHAR(190) AS (IF(status IN ('queued', 'running'), dedupe_key, NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY job_active_dedupe_uidx (active_dedupe_key),
  KEY job_status_kind_idx (status, kind),
  KEY job_contest_created_idx (contest_id, created_at),
  KEY job_subject_idx (subject_type, subject_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- A round's own visibility and freezing, kept on the round (the job only drives them):
--  * distributing: a round being created stays hidden from jurors (inactive, no votes, no
--    activation) until its distribution has passed the final count check. It stays set if
--    the creation fails or is cancelled, so a partial round is never shown; finishing the
--    distribution (Retry, or "Distribute new files") clears it and starts the round.
--  * freeze_count, active_before_freeze: the round is a previous round of that many active
--    distributions, which read its votes, so it is inactive (frozen) meanwhile. The first
--    freeze saves its active flag, the last release restores it. Counting per round lets
--    several distributions from the same round (special nominations after a coordination
--    call) overlap without one unfreezing the round under another.
ALTER TABLE rounds
  ADD COLUMN IF NOT EXISTS distributing TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS freeze_count INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS active_before_freeze TINYINT(1) NULL DEFAULT NULL;
