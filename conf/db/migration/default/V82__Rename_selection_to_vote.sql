-- Rename the vote tables (round-images plan, P0; docs/plans/2026-11-02-round-images-two-step.md):
-- selection -> vote, selection_deleted -> vote_aside, selection_orphans -> vote_orphan,
-- criteria_rate.selection and criteria_rate_orphan.selection -> vote_id; and rounds ->
-- round. Index and constraint names start with their table's name (CLAUDE.md "Table
-- names"). No data changes.
--
-- Every step is metadata-only in InnoDB: RENAME TABLE, RENAME INDEX and RENAME COLUMN are
-- instant (ALGORITHM=INSTANT makes a statement fail instead of copying a table). The
-- foreign key can't be renamed in place; it is dropped and added again in one statement
-- with foreign_key_checks off, which MariaDB does without copying or reading the table
-- (ALGORITHM=NOCOPY): the rows were already checked under the same key. Measured on a
-- MariaDB 10.6 container with 1M vote rows: every statement under 0.1 s, no table rebuilt.
--
-- Order: the index names and the foreign key first, which old code doesn't read; then the
-- criteria rates' column; the table names last, in one atomic RENAME TABLE. A run that
-- stops part-way leaves the old table names, so the release tool's restore (its dump
-- before the migration) or the reverse statements in the plan apply.
--
-- The index names are the same on production (the prod-v53 dump plus V54-V81) and on the
-- test schema (B44 plus V46-V81); only production's id keys differ (UNIQUE KEY `id`,
-- instead of or besides PRIMARY KEY), which are not renamed. The plan's "P0 results" has
-- the check before deploying, the recovery and the reverse SQL.

-- 1. Index names, on the old table names.
ALTER TABLE selection
  RENAME INDEX selection_page_jury_round_uidx TO vote_page_jury_round_uidx,
  RENAME INDEX selection_rate_index TO vote_rate_idx,
  RENAME INDEX selection_round_index TO vote_round_idx,
  RENAME INDEX selection_jury_id_index TO vote_jury_id_idx,
  RENAME INDEX selection_page_id_index TO vote_page_id_idx,
  RENAME INDEX idx_selection_round_rate TO vote_round_rate_idx,
  RENAME INDEX idx_selection_jury_round_rate_mon_page TO vote_jury_round_rate_mon_page_idx,
  RENAME INDEX idx_selection_round_jury_rate TO vote_round_jury_rate_idx,
  RENAME INDEX idx_selection_round_monument_id TO vote_round_monument_id_idx,
  RENAME INDEX idx_selection_round_page_rate TO vote_round_page_rate_idx,
  ALGORITHM=INSTANT;

ALTER TABLE selection_deleted
  RENAME INDEX selection_page_jury_round_uidx TO vote_aside_page_jury_round_uidx,
  RENAME INDEX selection_rate_index TO vote_aside_rate_idx,
  RENAME INDEX selection_round_index TO vote_aside_round_idx,
  RENAME INDEX selection_jury_id_index TO vote_aside_jury_id_idx,
  RENAME INDEX selection_page_id_index TO vote_aside_page_id_idx,
  RENAME INDEX idx_selection_round_rate TO vote_aside_round_rate_idx,
  RENAME INDEX idx_selection_jury_round_rate_mon_page TO vote_aside_jury_round_rate_mon_page_idx,
  RENAME INDEX idx_selection_round_jury_rate TO vote_aside_round_jury_rate_idx,
  RENAME INDEX idx_selection_round_monument_id TO vote_aside_round_monument_id_idx,
  RENAME INDEX idx_selection_round_page_rate TO vote_aside_round_page_rate_idx,
  ALGORITHM=INSTANT;

ALTER TABLE selection_orphans
  RENAME INDEX selection_page_jury_round_uidx TO vote_orphan_page_jury_round_uidx,
  RENAME INDEX selection_rate_index TO vote_orphan_rate_idx,
  RENAME INDEX selection_round_index TO vote_orphan_round_idx,
  RENAME INDEX selection_jury_id_index TO vote_orphan_jury_id_idx,
  RENAME INDEX selection_page_id_index TO vote_orphan_page_id_idx,
  RENAME INDEX idx_selection_round_rate TO vote_orphan_round_rate_idx,
  RENAME INDEX idx_selection_jury_round_rate_mon_page TO vote_orphan_jury_round_rate_mon_page_idx,
  RENAME INDEX idx_selection_round_jury_rate TO vote_orphan_round_jury_rate_idx,
  RENAME INDEX idx_selection_round_monument_id TO vote_orphan_round_monument_id_idx,
  RENAME INDEX idx_selection_round_page_rate TO vote_orphan_round_page_rate_idx,
  ALGORITHM=INSTANT;

ALTER TABLE rounds
  RENAME INDEX rounds_submit_token_uidx TO round_submit_token_uidx,
  RENAME INDEX idx_rounds_contest_active TO round_contest_active_idx,
  ALGORITHM=INSTANT;

-- 2. The foreign key's name (V58): dropped and added again in one statement. With
--    foreign_key_checks off, adding it neither copies nor reads the table; the rows
--    already satisfy it (the same key held until this statement).
SET @saved_fk_checks = @@foreign_key_checks;
SET foreign_key_checks = 0;
ALTER TABLE selection
  DROP FOREIGN KEY FK_selection_page_id,
  ADD CONSTRAINT vote_page_id_fk FOREIGN KEY (page_id) REFERENCES images (page_id) ON DELETE CASCADE,
  ALGORITHM=NOCOPY;
SET foreign_key_checks = @saved_fk_checks;

-- 3. The vote's id column of the criteria rates (old code reads `selection` here: only
--    criteria rounds' votes are affected, until step 4 ends the migration).
ALTER TABLE criteria_rate
  RENAME COLUMN selection TO vote_id,
  RENAME INDEX criteria_selection_index TO criteria_rate_vote_id_idx,
  ALGORITHM=INSTANT;

ALTER TABLE criteria_rate_orphan
  RENAME COLUMN selection TO vote_id,
  RENAME INDEX criteria_rate_orphan_selection TO criteria_rate_orphan_vote_id_idx,
  ALGORITHM=INSTANT;

-- 4. The tables, last, in one atomic statement. InnoDB carries the foreign keys over:
--    vote's to images, and round_user's to the round (FK_round_user_round_id, where it
--    exists: the test schema has it, production's MyISAM-born round_user doesn't).
RENAME TABLE selection TO vote,
             selection_deleted TO vote_aside,
             selection_orphans TO vote_orphan,
             rounds TO round;
