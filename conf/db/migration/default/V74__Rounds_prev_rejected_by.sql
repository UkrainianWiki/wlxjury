-- "Exclude files rejected by at least N jurors": a round from a yes/no previous round leaves
-- out the files with N or more rejections (rate -1) across the previous rounds' votes, e.g.
-- after a screening where jurors only reject the few bad files. NULL: no filter.
ALTER TABLE rounds ADD COLUMN IF NOT EXISTS prev_rejected_by INT NULL;
