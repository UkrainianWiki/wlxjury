-- The account change log (the maintainer's decision of 2026-11-12): every change an admin or root
-- makes to another person's account, for the contest's admins and root to read
-- (db.scalikejdbc.AccountChangeJdbc, services.AccountChangeLog). One row per changed thing:
--
--  * user_id: whose account (whom); actor_id: who made the change (an account id, no foreign key:
--    the row outlives a removed actor); contest_id: the contest the change was made in (NULL: by
--    root outside a contest), no foreign key either (a removed contest keeps its admins' history
--    readable to root).
--  * field: what changed (`email`, `password`, `role`, `name`, `wiki_account`, `root`,
--    `account_created`, `invitation`, `invitation_cancelled`, `password_link`, `share_link`,
--    `sessions_ended`); old_value and new_value as shown to the contest's admins (an email, a
--    name, a role; NULL where nothing applies). They hold email addresses: only the contest's
--    admins and root read them.
--  * mail_sent: whether this change mailed someone (an invitation, the notice to the former
--    address, a password link); NULL where no mail applies.
--  * comment: the editor's optional comment for the log (the edit form's, at most 500 characters).
--
-- Rows go with their account (ON DELETE CASCADE): the log holds the person's addresses.
-- Indexes: a contest's changes, newest first (the "Account changes" page), and an account's (its
-- edit page). A new, empty table: no copy, rerunnable.
CREATE TABLE IF NOT EXISTS account_change (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  actor_id    BIGINT UNSIGNED NOT NULL,
  contest_id  BIGINT UNSIGNED NULL DEFAULT NULL,
  field       VARCHAR(32)     NOT NULL,
  old_value   VARCHAR(255)    NULL DEFAULT NULL,
  new_value   VARCHAR(255)    NULL DEFAULT NULL,
  mail_sent   BOOLEAN         NULL DEFAULT NULL,
  comment     VARCHAR(500)    NULL DEFAULT NULL,
  created_at  TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY account_change_contest_id_created_at_idx (contest_id, created_at),
  KEY account_change_user_id_created_at_idx (user_id, created_at),
  CONSTRAINT account_change_field_chk
    CHECK (BINARY field IN ('email', 'password', 'role', 'name', 'wiki_account', 'root', 'account_created',
                            'invitation', 'invitation_cancelled', 'password_link', 'share_link', 'sessions_ended')),
  CONSTRAINT account_change_user_id_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- An invitation an admin added without mailing it ("Send an invitation email" unticked): kept so
-- that its role waits and the admin can send it, or create a link to share, from the users page,
-- which shows it as not sent (db.scalikejdbc.AccountToken.unsent). Its token was never shown to
-- anyone. Existing rows: mailed or shared, as before (0). ALGORITHM=INSTANT: metadata only
-- (existing rows read the default); the statement fails rather than copies. Rerunnable.
ALTER TABLE account_token
  ADD COLUMN IF NOT EXISTS unsent BOOLEAN NOT NULL DEFAULT FALSE,
  ALGORITHM = INSTANT;
