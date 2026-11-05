-- A round's per-file vote counts on round_image, kept in the background
-- (docs/plans/2026-11-02-round-images-two-step.md, §4 "Counts" and §16 "P2a results"; decision
-- W6 of docs/plans/2026-11-03-write-path-simplification.md): no count delta in the vote's
-- transaction; a juror's vote marks its file dirty, a bulk writer its round, and a background
-- recompute (db.scalikejdbc.RoundCounts, modules.RoundCountsTask) brings the counts in line
-- with the votes. Shadow only until P2b: nothing reads the counts yet.
--
--  * round_image counts, over the pair's `vote` rows (a `deleted` or `dq` row's votes are in
--    vote_aside: 0): assigned (vote rows), voted (rate <> 0), approvals (rate > 0), rejections
--    (rate < 0), rate_sum (SUM(rate)); a NULL rate counts as unrated. Unrated = assigned - voted.
--    A criteria vote's rate is its criteria's sum once all are rated, else 0 (U5), so voted is
--    the criteria-aware count. Signed INT: assigned - voted never goes out of range.
--  * round_image_dirty (round_id, page_id): the files whose votes a juror changed since their
--    counts were last computed (VoteJdbc.rate, CriteriaRate.updateRate: an upsert, last in the
--    vote's transaction). The recompute locks a window's entries FOR UPDATE before it reads
--    the votes and deletes those it locked after writing the counts, so a vote is either read or
--    leaves its entry for the next pass. No foreign keys: a check would share-lock the round (or
--    the image) in every vote, and a juror's vote never locks the round. Entries of a round that
--    no longer exists are dropped by the recompute.
--  * round.counts_gen / counts_gen_done: the round's generation, bumped by every bulk writer of
--    its votes in its own transaction (it holds the round for update already: no new lock), and
--    the generation the last full recount of the round started from. A round is dirty while
--    counts_gen_done < counts_gen; a recount that started from G sets counts_gen_done = G at its
--    end, so a bulk write during the recount (between two of its windows) leaves the round dirty.
--
-- No backfill: every existing round reads counts_gen 1 and counts_gen_done 0 (the defaults), so
-- every round is dirty after the deploy and the recompute fills its counts in the background,
-- open rounds first, in windows of round_image rows (one short transaction each). New rounds
-- start dirty too.
--
-- ALGORITHM=INSTANT: the columns are added to the metadata only (MariaDB 10.3+; existing rows
-- read the defaults), no copy of round_image or round and no lock beyond the metadata lock; the
-- statement fails instead of copying. round_image_dirty is new and empty. Rerunnable: ADD COLUMN
-- IF NOT EXISTS, CREATE TABLE IF NOT EXISTS. Same DDL on MariaDB 10.6 and 10.11.

ALTER TABLE round_image
  ADD COLUMN IF NOT EXISTS assigned   INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS voted      INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS approvals  INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS rejections INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS rate_sum   INT NOT NULL DEFAULT 0,
  ALGORITHM = INSTANT;

ALTER TABLE round
  ADD COLUMN IF NOT EXISTS counts_gen      BIGINT UNSIGNED NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS counts_gen_done BIGINT UNSIGNED NOT NULL DEFAULT 0,
  ALGORITHM = INSTANT;

CREATE TABLE IF NOT EXISTS round_image_dirty (
  round_id BIGINT UNSIGNED NOT NULL,
  page_id  BIGINT          NOT NULL,
  PRIMARY KEY (round_id, page_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
