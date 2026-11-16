-- Joining duplicate accounts (docs/plans/2026-11-03-account-join.md, PR J-1; J1-J3 decided by the
-- maintainer 2026-11-16): the table of join requests and of pairs kept apart, and the account
-- change log's `account_joined` field. J-1 writes only `kept_apart` rows (the duplicates report's
-- "Keep separate", services.DuplicateAccounts); the join itself (J-2) and the users' requests
-- (J-3) come later and use the other states and columns.
--
-- V106 is the next free number (docs/plans/2026-11-02-roadmap.md, "Migration numbers"): the
-- existing accounts plan's A-4 (`account_retired`) takes the next free one after it, and its
-- CHECK below must keep `account_joined`.
--
-- account_join_request, one row per request or decision about a pair of accounts:
--
--  * first_account_id < second_account_id: the pair, ordered (the CHECK), so that a pair has one
--    key whichever way it is named. Rows go with their account (ON DELETE CASCADE; an account is
--    never deleted today, a retired one keeps its row with users.deleted_at set).
--  * state: `pending`, `done`, `refused`, `cancelled` (a user's request, J-3, and its outcome,
--    J-2), or `kept_apart` (a performer's "Keep separate": the pair is never listed as possible
--    duplicates again; at most one such row per pair, the unique key on the generated
--    `kept_apart`, which is 1 for that state and NULL otherwise, as a unique key allows any number
--    of NULLs).
--  * first_proof, second_proof and their times: how the user proved control of each account for
--    a request (`password`, `mail_link`, `session`: signed in to it); NULL otherwise.
--  * kept_email: the address the user chose to keep (one of the two accounts' verified ones);
--    NULL: none, or not a request.
--  * requested_by: who asked (for a request, the account that asked; for `kept_apart`, the
--    performer); decided_by: who decided (for `kept_apart`, the performer too). Account ids, no
--    foreign key: the row outlives a retired actor, as account_change.actor_id.
--  * contest_id: the contest the decision was made in (NULL: by root outside a contest, the full
--    report); no foreign key, as account_change.contest_id.
--  * expires_at: a pending request's end (30 days, J-3); NULL otherwise.
--
-- A new, empty table: rerunnable (IF NOT EXISTS). The foreign keys take a metadata lock on
-- `users` for a moment: a 60 s lock_wait_timeout (a long transaction or backup: the statement
-- fails with nothing created).
SET STATEMENT lock_wait_timeout = 60 FOR
CREATE TABLE IF NOT EXISTS account_join_request (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  first_account_id  BIGINT UNSIGNED NOT NULL,
  second_account_id BIGINT UNSIGNED NOT NULL,
  state             VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  first_proof       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  first_proved_at   TIMESTAMP(3) NULL DEFAULT NULL,
  second_proof      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  second_proved_at  TIMESTAMP(3) NULL DEFAULT NULL,
  kept_email        VARCHAR(190) NULL DEFAULT NULL,
  requested_by      BIGINT UNSIGNED NOT NULL,
  decided_by        BIGINT UNSIGNED NULL DEFAULT NULL,
  contest_id        BIGINT UNSIGNED NULL DEFAULT NULL,
  created_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  decided_at        TIMESTAMP(3) NULL DEFAULT NULL,
  expires_at        TIMESTAMP(3) NULL DEFAULT NULL,
  kept_apart        TINYINT AS (IF(state = 'kept_apart', 1, NULL)) PERSISTENT,
  PRIMARY KEY (id),
  UNIQUE KEY account_join_request_kept_apart_uidx (first_account_id, second_account_id, kept_apart),
  KEY account_join_request_second_idx (second_account_id),
  CONSTRAINT account_join_request_pair_chk CHECK (first_account_id < second_account_id),
  CONSTRAINT account_join_request_state_chk
    CHECK (state IN ('pending', 'done', 'refused', 'cancelled', 'kept_apart')),
  CONSTRAINT account_join_request_first_proof_chk
    CHECK (first_proof IS NULL OR first_proof IN ('password', 'mail_link', 'session')),
  CONSTRAINT account_join_request_second_proof_chk
    CHECK (second_proof IS NULL OR second_proof IN ('password', 'mail_link', 'session')),
  CONSTRAINT account_join_request_first_fk FOREIGN KEY (first_account_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT account_join_request_second_fk FOREIGN KEY (second_account_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- The account change log's field CHECK (V96) gains `account_joined` (the join, J-2: one row on
-- each account, the other's id in its comment). One statement, rerunnable (the CHECK dropped if
-- there and added again); MariaDB checks the existing rows as it adds it, which copies
-- account_change (production: a few hundred rows, milliseconds; writes wait for the copy). A 60 s
-- lock_wait_timeout for the metadata lock.
SET STATEMENT lock_wait_timeout = 60 FOR
ALTER TABLE account_change DROP CONSTRAINT IF EXISTS account_change_field_chk,
  ADD CONSTRAINT account_change_field_chk
    CHECK (BINARY field IN ('email', 'password', 'role', 'name', 'wiki_account', 'root', 'account_created',
                            'invitation', 'invitation_cancelled', 'password_link', 'share_link', 'sessions_ended',
                            'account_joined'));

-- A pre-V106 binary runs on this schema (it never names the table, and writes none of the new
-- field). Undo, after reopen (no `account_joined` row exists before J-2):
--   DELETE FROM account_change WHERE field = 'account_joined';
--   ALTER TABLE account_change DROP CONSTRAINT IF EXISTS account_change_field_chk,
--     ADD CONSTRAINT account_change_field_chk CHECK (BINARY field IN ('email', 'password', 'role',
--     'name', 'wiki_account', 'root', 'account_created', 'invitation', 'invitation_cancelled',
--     'password_link', 'share_link', 'sessions_ended'));
--   DROP TABLE IF EXISTS account_join_request;
--   DELETE FROM flyway_schema_history WHERE version = '106';
-- (Dropping the table forgets the pairs kept apart: the report lists them again.)
