-- A juror must have at most one selection row per image per round.
-- Collapse any pre-existing duplicates, then enforce it with a unique index.
--
-- For each (page_id, jury_id, round_id) group a single row is kept: a non-deleted
-- row over a deleted one, then a rated row over an unrated one, then the earliest id.
-- Every other row in the group (a "dup" that has a "better" peer) is removed.
--
-- selection.rate is nullable, so the "rated" test is COALESCE(rate, 0) <> 0: a bare
-- rate <> 0 would yield NULL for a NULL-rate row, the comparisons built on it would
-- all be NULL (not true), no row in the group would be recognised as the "better"
-- peer, the dups would survive, and CREATE UNIQUE INDEX below would then fail.

DELETE cr FROM criteria_rate cr
JOIN selection dup ON dup.id = cr.selection
JOIN selection keep
  ON  keep.page_id  = dup.page_id
  AND keep.jury_id  = dup.jury_id
  AND keep.round_id = dup.round_id
  AND keep.id <> dup.id
  AND ((keep.deleted_at IS NULL) > (dup.deleted_at IS NULL)
       OR ((keep.deleted_at IS NULL) = (dup.deleted_at IS NULL)
           AND ((COALESCE(keep.rate, 0) <> 0) > (COALESCE(dup.rate, 0) <> 0)
                OR ((COALESCE(keep.rate, 0) <> 0) = (COALESCE(dup.rate, 0) <> 0)
                    AND keep.id < dup.id))));

DELETE dup FROM selection dup
JOIN selection keep
  ON  keep.page_id  = dup.page_id
  AND keep.jury_id  = dup.jury_id
  AND keep.round_id = dup.round_id
  AND keep.id <> dup.id
  AND ((keep.deleted_at IS NULL) > (dup.deleted_at IS NULL)
       OR ((keep.deleted_at IS NULL) = (dup.deleted_at IS NULL)
           AND ((COALESCE(keep.rate, 0) <> 0) > (COALESCE(dup.rate, 0) <> 0)
                OR ((COALESCE(keep.rate, 0) <> 0) = (COALESCE(dup.rate, 0) <> 0)
                    AND keep.id < dup.id))));

CREATE UNIQUE INDEX selection_page_jury_round_uidx ON selection (page_id, jury_id, round_id);

-- V45e added a non-unique index on the same three columns purely for lookup speed;
-- the unique index above now serves that access path, so drop the redundant one.
DROP INDEX IF EXISTS idx_selection_page_jury_round ON selection;
