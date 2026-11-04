-- A round's file disqualifications and requalifications, one row per action and file, append
-- only (docs/plans/2026-11-02-round-images-two-step.md, §14 "Design choices" and §15): who did
-- what, when, with which code, and the file's round_image status before and after. Until now a
-- requalify cleared round_image's dq_* and left only the `coi` log line, and the status before
-- the disqualify was lost (§14's known limitation: a file deleted on Commons while its round was
-- open came back `in` instead of `deleted`).
--
--  * action: 'disqualify' or 'requalify'.
--  * code: a disqualify's dq_code (coi | min_mpx | category | date | manual), a requalify's reason
--    (mistaken_match | conflict_ended): codes from fixed lists, never free text
--    (secure-coding §13). Checked by the code, not here: P3 adds codes, and a CHECK change on a
--    growing table would copy it.
--  * prior_status: the row's status before the action (a disqualify: 'in' or 'deleted', read
--    before its votes were moved aside; a requalify: 'dq'). new_status: after it ('dq'; a
--    requalify: 'in', 'deleted', or NULL when the file had no votes left and its row went).
--  * created_by: the admin (users.id; no foreign key: an account deleted later keeps its
--    history), created_at: when.
--
-- Written only in the action's own transaction, after its round_image rows, last in the lock
-- order (round → images → vote → criteria_rate → vote_aside → round_image → round_image_event),
-- with the round locked for update first (RoundWrites). The foreign key to round follows
-- round_image's (a deleted round takes its history with it); its check share-locks the round
-- row, which the writer already holds for update. No foreign key to images: its check would
-- share-lock the image row after round_image, out of the lock order; an image deleted takes its
-- round_image rows (their cascade) and leaves its events, which nothing reads without them.
--
-- round_image_event_round_page_idx (round_id, page_id, id): a file's history in order (ids grow
-- with time: each action holds its round's lock), its latest event (the requalify's prior
-- status), and a round's history.
--
-- New and empty: CREATE TABLE only, no backfill (rows from before V88 have no event; a requalify
-- of one falls back to the behaviour before V88). Rerunnable (IF NOT EXISTS). Same DDL on
-- MariaDB 10.6 and 10.11 (TIMESTAMP explicit, db practices §2.6).

CREATE TABLE IF NOT EXISTS round_image_event (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  round_id     BIGINT UNSIGNED NOT NULL,
  page_id      BIGINT          NOT NULL,
  action       VARCHAR(16)     NOT NULL,
  code         VARCHAR(16)     NOT NULL,
  prior_status VARCHAR(16)     NOT NULL,
  new_status   VARCHAR(16)     NULL DEFAULT NULL,
  created_by   BIGINT UNSIGNED NULL DEFAULT NULL,
  created_at   TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY round_image_event_round_page_idx (round_id, page_id, id),
  CONSTRAINT round_image_event_round_id_fk FOREIGN KEY (round_id) REFERENCES round (id) ON DELETE CASCADE,
  CONSTRAINT round_image_event_action_chk CHECK (BINARY action IN ('disqualify', 'requalify')),
  CONSTRAINT round_image_event_prior_status_chk CHECK (BINARY prior_status IN ('in', 'dq', 'deleted')),
  CONSTRAINT round_image_event_new_status_chk CHECK (new_status IS NULL OR BINARY new_status IN ('in', 'dq', 'deleted'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
