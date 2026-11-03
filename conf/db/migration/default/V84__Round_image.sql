-- A round's images, one row per round and file (docs/plans/2026-11-02-round-images-two-step.md,
-- P1): "which images does round R have" without grouping every juror's votes. Until now a
-- round's files were the distinct page ids of its vote rows (and of vote_aside, V63, for files
-- deleted on Commons while it was open).
--
--  * status: 'in' (the file is in the round: it has vote rows there), 'deleted' (deleted on
--    Commons in an open round: its votes are in vote_aside), 'dq' (disqualified, P1b: votes in
--    vote_aside; nothing writes it yet).
--  * added_how: how the row was added, not why the file qualifies (D6): 'fill' and 'list' (a
--    round's creation, by its filters or its pasted list), 'new_files' ("Distribute new
--    files"), 'manual' (votes inserted outside a distribution job: a file moved from another
--    round, scripts, tests), 'repair' (the round_image_check job found it missing), 'legacy'
--    (this backfill). added_by_job_id: the distribution job, whose params.settings hold the
--    filters. added_at: NULL for the backfilled rows.
--  * dq_*: P1b's disqualification (code, detail, by whom, when); NULL until then.
--  * monument_id: a copy of images.monument_id (as vote's, V48), in its collation (V55.1's
--    utf8mb4_general_ci: compared and joined with it and with monument.id), kept with it
--    (ImageJdbc.updateMonumentIdIn); region: ImageDbNew.regionOf's expression over it.
--  * The primary key (round_id, page_id) serves membership, the anti-joins and a round's scan
--    by page id; round_image_page_id_idx the foreign key and a file's rounds. Rows go with
--    their round or image (ON DELETE CASCADE).
--
-- The app keeps it in step with vote and vote_aside in the same transactions as their writes
-- (RoundImageJdbc). Old code ignores the table: after running an older release, run the
-- round_image_check job (root, "Check round images") before relying on it again.
--
-- Backfill: every (round, file) pair of vote as 'in', then the pairs only in vote_aside as
-- 'deleted', of rounds and images that exist; 'legacy', no job, no time. Pairs of a missing
-- round or image (vote.round_id has no foreign key; vote_aside has none) are left out: nothing
-- shows them. One statement each; a rerun (after a failure part-way) adds only what is
-- missing and changes no row (the no-op ON DUPLICATE KEY UPDATE keeps an existing row as it is).

CREATE TABLE IF NOT EXISTS round_image (
  round_id        BIGINT UNSIGNED NOT NULL,
  page_id         BIGINT          NOT NULL,
  status          VARCHAR(16)     NOT NULL DEFAULT 'in',
  added_how       VARCHAR(16)     NOT NULL DEFAULT 'legacy',
  added_by_job_id BIGINT UNSIGNED NULL DEFAULT NULL,
  added_at        TIMESTAMP(3)    NULL DEFAULT NULL,
  dq_code         VARCHAR(16)     NULL DEFAULT NULL,
  dq_detail       VARCHAR(255)    NULL DEFAULT NULL,
  dq_by           BIGINT UNSIGNED NULL DEFAULT NULL,
  dq_at           TIMESTAMP(3)    NULL DEFAULT NULL,
  monument_id     VARCHAR(255)    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  region          VARCHAR(3) AS (NULLIF(LEFT(SUBSTRING_INDEX(monument_id, '-', 1), 3), '')) STORED,
  PRIMARY KEY (round_id, page_id),
  KEY round_image_page_id_idx (page_id),
  CONSTRAINT round_image_round_id_fk FOREIGN KEY (round_id) REFERENCES round (id) ON DELETE CASCADE,
  CONSTRAINT round_image_page_id_fk FOREIGN KEY (page_id) REFERENCES images (page_id) ON DELETE CASCADE,
  CONSTRAINT round_image_status_chk CHECK (BINARY status IN ('in', 'dq', 'deleted')),
  CONSTRAINT round_image_added_how_chk
    CHECK (BINARY added_how IN ('fill', 'list', 'new_files', 'manual', 'repair', 'legacy'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- 1. The files with votes: a loose scan of vote_round_page_rate_idx (V56).
INSERT INTO round_image (round_id, page_id, status, added_how, monument_id)
  SELECT p.round_id, p.page_id, 'in', 'legacy', i.monument_id
  FROM (SELECT DISTINCT v.round_id, v.page_id FROM vote v) p
  JOIN round r ON r.id = p.round_id
  JOIN images i ON i.page_id = p.page_id
  ORDER BY p.round_id, p.page_id
ON DUPLICATE KEY UPDATE round_id = round_image.round_id;

-- 2. The files with votes only moved aside (deleted on Commons while the round was open).
INSERT INTO round_image (round_id, page_id, status, added_how, monument_id)
  SELECT p.round_id, p.page_id, 'deleted', 'legacy', i.monument_id
  FROM (SELECT DISTINCT d.round_id, d.page_id FROM vote_aside d) p
  JOIN round r ON r.id = p.round_id
  JOIN images i ON i.page_id = p.page_id
  ORDER BY p.round_id, p.page_id
ON DUPLICATE KEY UPDATE round_id = round_image.round_id;
