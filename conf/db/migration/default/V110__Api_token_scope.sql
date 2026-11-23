-- A personal API token's scope (support diagnostics plan, docs/plans/2026-11-18-support-diagnostics.md
-- §3.4 and DG3, decided by the maintainer 2026-11-18; PR D-1b). Chosen when the token is created
-- on the profile page (db.scalikejdbc.ApiTokenJdbc, api.ApiAuth):
--
--  * full: what every token did before, the default and the value of every existing token;
--  * local_read: read-only, from the server only. The API takes the token on GET only, and only
--    from the server itself (a literal loopback address and no X-Forwarded-For or Forwarded
--    header: never through Apache). For the maintainer's wlxjury-diag tool over ssh.
--
-- Planned as V108; V108 went to the quick fixes' NOT NULL round_user.active and V109 to the contest
-- series' CY-1, so it took the next free number at its merge.
--
-- Values checked byte for byte (ascii_bin, a CHECK), as round.status (V105). Rerunnable: ADD COLUMN
-- IF NOT EXISTS, the constraint dropped and added again. The table copy takes milliseconds
-- (production: a few rows); a 60 s lock_wait_timeout (a long transaction or backup: the statement
-- fails with nothing changed).
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE api_token
  ADD COLUMN IF NOT EXISTS scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'full' AFTER prefix;

SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE api_token DROP CONSTRAINT IF EXISTS api_token_scope_chk,
  ADD CONSTRAINT api_token_scope_chk CHECK (BINARY scope IN ('full', 'local_read'));

-- An older binary runs on this schema: it ignores the column, so a local_read token would act as a
-- full one there. Undo, after reopen (revoke the read-only tokens first, so none gains writes):
--   UPDATE api_token SET revoked_at = NOW(3) WHERE scope <> 'full' AND revoked_at IS NULL;
--   ALTER TABLE api_token DROP CONSTRAINT IF EXISTS api_token_scope_chk, DROP COLUMN IF EXISTS scope;
--   DELETE FROM flyway_schema_history WHERE version = '110';
