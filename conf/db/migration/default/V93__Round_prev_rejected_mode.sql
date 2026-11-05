-- How a next round's "rejected by at least N jurors" (prev_rejected_by, V74) picks files from its
-- previous rounds' votes (org.intracer.wmua.RejectedMode; the user, 2026-11-05,
-- docs/plans/2026-11-02-round-images-two-step.md §18): 'exclude' leaves out the files with N or more
-- rejections (a yes/no round's "no", a rated round's ✗), as the filter did before; 'only' keeps only
-- them, to review the rejected files in a round of their own.
--
-- 1. The column, 'exclude' for new rounds and every existing one (their meaning unchanged):
--    ALGORITHM=INSTANT, metadata only (existing rows read the default; the statement fails rather
--    than copies). Rerunnable.
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS prev_rejected_mode VARCHAR(8) NOT NULL DEFAULT 'exclude',
  ALGORITHM = INSTANT;

-- 2. The allowlist, compared byte for byte (db practices §1): one statement, so a failure can't
--    leave the column without its check; rerunnable. MariaDB checks the existing rows (all
--    'exclude') as it adds it, which copies `round`, one row per round: milliseconds (as V90's).
ALTER TABLE round DROP CONSTRAINT IF EXISTS round_prev_rejected_mode_chk,
  ADD CONSTRAINT round_prev_rejected_mode_chk CHECK (BINARY prev_rejected_mode IN ('exclude', 'only'));
