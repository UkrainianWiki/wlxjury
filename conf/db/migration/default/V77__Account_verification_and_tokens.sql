-- Account integrity (security batch S2): verified emails and wiki accounts, a session version,
-- and the single-use links that prove an email (services.AccountTokenService).
--
--  * email_verified_at: when the account's email was proven, by accepting an invitation or a
--    password link sent to it, or by confirming an email change. NULL: unverified. An
--    unverified account that has a password gets an access role (jury, organizer, admin) only
--    through an invitation (User.mayTakeAccessRole), so that a role an organizer gives an
--    email never goes to someone who registered that email without owning it.
--  * wiki_account_verified_at: when the wiki account was proven (the planned Commons OAuth
--    login). NULL: unverified, which every wiki account is for now; a match by wiki account
--    never attaches an access role to an unverified one.
--  * auth_version: a session carries the version it was signed in with (absent: 0) and is
--    refused once it differs (controllers.Secured.sessionUser). Accepting a link that sets the
--    password, and an email change, add one: every session of the account ends.
--
-- Existing accounts: root accounts with an email are marked verified (the operators); every
-- other account stays unverified (the safer default: its owner accepts an invitation the first
-- time it is given an access role again). No wiki account is verified.
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS email_verified_at        TIMESTAMP(3) NULL DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS wiki_account_verified_at TIMESTAMP(3) NULL DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS auth_version             INT UNSIGNED NOT NULL DEFAULT 0;

UPDATE users SET email_verified_at = NOW(3)
WHERE is_root = 1 AND email IS NOT NULL AND email_verified_at IS NULL;

-- A link mailed to an account (db.scalikejdbc.AccountTokenJdbc):
--  * purpose: 'invitation' (an organizer's add; sets the password), 'password_reset' (root's
--    "reset password"; sets the password), 'email_change' (confirms a new address).
--  * token_hash: the SHA-256 (hex) of the link's token, the only form it is stored in.
--  * email: the address the link was sent to, which accepting it proves; an invitation or a
--    password link is refused once the account's email is another one.
--  * contest_id, role: an invitation's contest, and the role a pending invitation gives once
--    accepted (NULL: the membership exists already). The role is a contest role (V76's list).
--  * created_by: the account that sent it (an organizer, root, or the account itself).
--  * used_at, revoked_at: single use; a newer link or an acceptance revokes the others.
CREATE TABLE IF NOT EXISTS account_token (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED NOT NULL,
  purpose     VARCHAR(16)     NOT NULL,
  token_hash  CHAR(64)        NOT NULL,
  email       VARCHAR(190)    NOT NULL,
  contest_id  BIGINT UNSIGNED NULL DEFAULT NULL,
  role        VARCHAR(255)    NULL DEFAULT NULL,
  created_by  BIGINT UNSIGNED NULL DEFAULT NULL,
  created_at  TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at  TIMESTAMP(3)    NOT NULL,
  used_at     TIMESTAMP(3)    NULL DEFAULT NULL,
  revoked_at  TIMESTAMP(3)    NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY account_token_hash_uidx (token_hash),
  KEY account_token_user_idx (user_id, purpose),
  KEY account_token_contest_idx (contest_id),
  CONSTRAINT chk_account_token_purpose
    CHECK (BINARY purpose IN ('invitation', 'password_reset', 'email_change')),
  CONSTRAINT chk_account_token_role
    CHECK (role IS NULL OR BINARY role IN ('no', 'jury', 'organizer', 'admin', 'board', 'auditor', 'staff')),
  CONSTRAINT fk_account_token_user    FOREIGN KEY (user_id)    REFERENCES users(id)        ON DELETE CASCADE,
  CONSTRAINT fk_account_token_contest FOREIGN KEY (contest_id) REFERENCES contest_jury(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
