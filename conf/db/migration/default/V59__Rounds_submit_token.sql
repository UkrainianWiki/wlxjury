-- The new-round form carries a one-time submission token (a UUID). The round is
-- created with it, and the unique index lets one submission create one round only: a
-- resubmit (double click, a browser retry after a timeout, Back and Submit) finds the
-- round of its token instead of creating another, also when two arrive at once (the
-- second insert fails on the index). NULL for rounds created without a token (older
-- rounds, scripts, the API); a unique index allows any number of NULLs.
ALTER TABLE rounds
  ADD COLUMN IF NOT EXISTS submit_token VARCHAR(36) NULL DEFAULT NULL,
  ADD UNIQUE INDEX IF NOT EXISTS rounds_submit_token_uidx (submit_token);
