-- Retiring an empty account (docs/plans/2026-11-16-existing-accounts.md, "Retiring an account",
-- PR A-4; decision A3 by the maintainer 2026-11-16): the account change log's field CHECK (V96,
-- widened by V106 with `account_joined`, which it keeps) gains `account_retired`. One row on the
-- retired account (its former address in old_value, the account added instead in comment) and one
-- on the account added instead (the retired one in comment). The retirement itself writes
-- users.deleted_at (the first writer of it) and needs no other schema change.
--
-- One statement, rerunnable (the CHECK dropped if there and added again); MariaDB checks the
-- existing rows as it adds it, which copies account_change (production: a few hundred rows,
-- milliseconds; writes wait for the copy). A 60 s lock_wait_timeout for the metadata lock (a long
-- transaction or backup: the statement fails with nothing changed).
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE account_change DROP CONSTRAINT IF EXISTS account_change_field_chk,
  ADD CONSTRAINT account_change_field_chk
    CHECK (BINARY field IN ('email', 'password', 'role', 'name', 'wiki_account', 'root', 'account_created',
                            'invitation', 'invitation_cancelled', 'password_link', 'share_link', 'sessions_ended',
                            'account_joined', 'account_retired'));

-- A pre-V107 binary runs on this schema (it writes none of the new field; it shows a retired
-- account's rows by the field's message key, which it lacks). Undo, after reopen: the
-- `account_retired` rows go (the retired accounts stay retired: users.deleted_at set, no email,
-- no memberships; an older release never signs them in and lists them nowhere but root's all
-- users page). The DELETE removes the only copy of the retired accounts' former addresses (their
-- users.email is cleared): export those rows first if they may be needed, e.g.
--   SELECT * FROM account_change WHERE field = 'account_retired' INTO OUTFILE '/tmp/account_retired.csv';
-- (or mysqldump --where="field = 'account_retired'" of account_change). Then:
--   DELETE FROM account_change WHERE field = 'account_retired';
--   ALTER TABLE account_change DROP CONSTRAINT IF EXISTS account_change_field_chk,
--     ADD CONSTRAINT account_change_field_chk CHECK (BINARY field IN ('email', 'password', 'role',
--     'name', 'wiki_account', 'root', 'account_created', 'invitation', 'invitation_cancelled',
--     'password_link', 'share_link', 'sessions_ended', 'account_joined'));
--   DELETE FROM flyway_schema_history WHERE version = '107';
