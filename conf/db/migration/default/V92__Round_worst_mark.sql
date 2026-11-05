-- A rated round's worst mark (✗), a per-round option, off by default (the user's decisions of
-- 2026-11-05, roadmap queue item 6, docs/plans/2026-11-02-round-images-two-step.md §18): with
-- it, a juror may give a file ✗, stored as vote.rate = -1, which counts as 0 in every average and
-- total and as a vote toward the juror's progress. 0 is still unrated, the stars 1…N unchanged;
-- a yes/no round keeps its -1/0/1 and never has the option, nor does a criteria round (the
-- server refuses both). ALGORITHM=INSTANT: metadata only, existing rows read the default; the
-- statement fails rather than copies. Rerunnable.
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS worst_mark BOOLEAN NOT NULL DEFAULT FALSE,
  ALGORITHM = INSTANT;
