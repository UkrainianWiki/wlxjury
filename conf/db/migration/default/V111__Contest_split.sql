-- Splitting reused contests (docs/plans/2026-11-18-contest-series.md §4.4–§4.6; PR CY-4; CA9–CA13 and
-- CA23 decided by the maintainer 2026-11-20): root confirms one contest's plan (its clusters of rounds
-- to move into contests of the right series and year), and a contest_split job runs it
-- (services.series.ContestSplit, ContestSplitHandler). Planned as V112 (V111 for CY-2's series_admin);
-- CY-4 merges first, so it took the next free number.
--
--  * contest_split: one confirmation of root's. contest_id: the source contest; job_id: its job; plan:
--    what root confirmed (JSON: per move the target series and year, the contest to create or the
--    existing one root confirmed, the rounds, the matching categories, the active rounds root ticked
--    to pause, the accounts root ticked for Admin); plan_hash: its SHA-256; dump_ref: the operation id
--    of the dump taken before it (CA12, optional: the runbook's); batch_id: the contest_change batch
--    of its split_out/split_in rows; undone_at/undone_by: its Undo.
--  * contest_split_item: what a split changed, each with its old and new values (JSON), so that Undo
--    reverses it: kind `series` (a series it created), `contest` (a target it created, retired by
--    Undo) or `contest_existing` (an existing target root confirmed), `paused` (an active round it
--    paused for the move), `membership` (a role it gave in a target; Undo removes it if unused),
--    `skipped` (an account it gave none, and why: listed), `round` (a moved round: contest, number,
--    exclude_rounds), `exclude_cut` (a staying round whose exclude_rounds lost moved rounds),
--    `comments` and `jobs` (moved by round: the count), `current_round` (the source's, cleared),
--    `files` (the matching files linked into the target: the count; Undo keeps the links, CA10).
--    No foreign keys to contests, rounds or accounts: the record outlives them.
--
-- Plain tables, no change to existing ones. Rerunnable: CREATE TABLE IF NOT EXISTS. A 60 s
-- lock_wait_timeout for each statement.
SET STATEMENT lock_wait_timeout = 60 FOR
CREATE TABLE IF NOT EXISTS contest_split (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  contest_id  BIGINT UNSIGNED NOT NULL,
  job_id      BIGINT UNSIGNED NULL DEFAULT NULL,
  plan        LONGTEXT        NOT NULL,
  plan_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  dump_ref    VARCHAR(255)    NULL DEFAULT NULL,
  batch_id    BIGINT UNSIGNED NULL DEFAULT NULL,
  created_by  BIGINT UNSIGNED NOT NULL,
  created_at  TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  undone_at   TIMESTAMP(3)    NULL DEFAULT NULL,
  undone_by   BIGINT UNSIGNED NULL DEFAULT NULL,
  PRIMARY KEY (id),
  KEY contest_split_contest_idx (contest_id, id),
  KEY contest_split_job_idx (job_id),
  CONSTRAINT contest_split_plan_chk CHECK (JSON_VALID(plan))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

SET STATEMENT lock_wait_timeout = 60 FOR
CREATE TABLE IF NOT EXISTS contest_split_item (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  split_id    BIGINT UNSIGNED NOT NULL,
  move_index  INT             NOT NULL,
  kind        VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  subject_id  BIGINT UNSIGNED NULL DEFAULT NULL,
  contest_id  BIGINT UNSIGNED NULL DEFAULT NULL,
  old_value   LONGTEXT        NULL DEFAULT NULL,
  new_value   LONGTEXT        NULL DEFAULT NULL,
  created_at  TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY contest_split_item_split_idx (split_id, id),
  KEY contest_split_item_subject_idx (kind, subject_id),
  CONSTRAINT contest_split_item_split_fk FOREIGN KEY (split_id) REFERENCES contest_split (id) ON DELETE CASCADE,
  CONSTRAINT contest_split_item_kind_chk
    CHECK (kind IN ('series', 'contest', 'contest_existing', 'paused', 'membership', 'skipped', 'round',
                    'exclude_cut', 'comments', 'jobs', 'current_round', 'files')),
  CONSTRAINT contest_split_item_old_chk CHECK (old_value IS NULL OR JSON_VALID(old_value)),
  CONSTRAINT contest_split_item_new_chk CHECK (new_value IS NULL OR JSON_VALID(new_value))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- A pre-V111 binary runs on this schema: it reads neither table (the moved rounds stay where the
-- splits put them; their old contest-qualified links no longer redirect). Undo, after reopen (the
-- record of the splits is lost, so Undo them first if they may be needed):
--   DROP TABLE IF EXISTS contest_split_item;
--   DROP TABLE IF EXISTS contest_split;
--   DELETE FROM flyway_schema_history WHERE version = '111';
