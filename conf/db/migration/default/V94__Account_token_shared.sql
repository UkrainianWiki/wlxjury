-- Shareable set-password links (the user, 2026-11-05): an invitation or a password link that an
-- admin or root creates to copy and pass on personally, instead of having it mailed. The same
-- kind of row as a mailed one (32 random bytes, only the SHA-256 stored, single use, expiring,
-- bound to the account and the address it had), marked here:
--  * shared: the link was shown to the account's admin or root to pass on, not mailed. Accepting
--    it sets the password, ends every session and API token of the account and unlinks its
--    identities as a mailed one does, but proves no email (nobody showed they read a mail to the
--    address), so it clears users.email_verified_at and wiki_account_verified_at, and gives only
--    the pending roles its creator may still give (services.AccountTokenService.proveEmail).
-- ALGORITHM=INSTANT: metadata only, existing rows (all mailed) read the default; the statement
-- fails rather than copies. Rerunnable.
ALTER TABLE account_token
  ADD COLUMN IF NOT EXISTS shared BOOLEAN NOT NULL DEFAULT FALSE,
  ALGORITHM = INSTANT;
