-- A juror must have at most one selection row per image per round.
-- Collapse any pre-existing duplicates, then enforce it with a unique index.
--
-- For each (page_id, jury_id, round_id) group a single row is kept: a non-deleted
-- row over a deleted one, then a rated row over an unrated one, then the earliest id.
-- Every other row in the group (a "dup" that has a "better" peer) is removed.

DELETE cr FROM criteria_rate cr
JOIN selection dup ON dup.id = cr.selection
JOIN selection keep
  ON  keep.page_id  = dup.page_id
  AND keep.jury_id  = dup.jury_id
  AND keep.round_id = dup.round_id
  AND keep.id <> dup.id
  AND ((keep.deleted_at IS NULL) > (dup.deleted_at IS NULL)
       OR ((keep.deleted_at IS NULL) = (dup.deleted_at IS NULL)
           AND ((keep.rate <> 0) > (dup.rate <> 0)
                OR ((keep.rate <> 0) = (dup.rate <> 0) AND keep.id < dup.id))));

DELETE dup FROM selection dup
JOIN selection keep
  ON  keep.page_id  = dup.page_id
  AND keep.jury_id  = dup.jury_id
  AND keep.round_id = dup.round_id
  AND keep.id <> dup.id
  AND ((keep.deleted_at IS NULL) > (dup.deleted_at IS NULL)
       OR ((keep.deleted_at IS NULL) = (dup.deleted_at IS NULL)
           AND ((keep.rate <> 0) > (dup.rate <> 0)
                OR ((keep.rate <> 0) = (dup.rate <> 0) AND keep.id < dup.id))));

CREATE UNIQUE INDEX selection_page_jury_round_uidx ON selection (page_id, jury_id, round_id);
