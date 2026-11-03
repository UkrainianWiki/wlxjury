-- A Google or Wikimedia identity a user signs in with (services.oauth, OAuthController). Keyed by
-- the provider's stable subject (`sub`; Wikimedia's is the central user id), never by email or
-- username, which change; both compared exactly (utf8mb4_bin). A user may have several. Linked on
-- the first sign-in to the one account the provider-verified email or Wikimedia username names,
-- by S2's rules (IdentityLinker: never to an account someone else may hold), or by the user from
-- their own profile. Ported from the oauth-login branch's V63 (renumbered: V85 and V86 are the MariaDB 10.11 port's; S2/S3 names).
CREATE TABLE IF NOT EXISTS user_identity (
  provider      VARCHAR(32)     CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  subject       VARCHAR(191)    CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  user_id       BIGINT UNSIGNED NOT NULL,
  email         VARCHAR(190)    NULL,
  username      VARCHAR(255)    NULL,
  created_at    TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_login_at TIMESTAMP(3)    NULL DEFAULT NULL,
  -- set: unlinked (by its user, or as the account changed hands): kept as a tombstone, so that a
  -- first sign-in never links that (provider, subject) again by email or wiki account; only a
  -- link from a signed-in user's own profile does (review F2)
  unlinked_at   TIMESTAMP(3)    NULL DEFAULT NULL,
  PRIMARY KEY (provider, subject),
  KEY user_identity_user_idx (user_id),
  CONSTRAINT user_identity_user_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- The account chooser's proof by mail (OAuthController.chooseConfirm): a short, single-use link
-- to an account's verified address, before a Wikimedia identity whose name several accounts
-- have is linked to it. Widens V77's purposes, and gives the check its table-first name
-- (V77's chk_account_token_purpose): one statement, since MariaDB commits each DDL statement,
-- so a failure can't leave the table without its check.
ALTER TABLE account_token DROP CONSTRAINT chk_account_token_purpose,
  ADD CONSTRAINT account_token_purpose_chk
  CHECK (BINARY purpose IN ('invitation', 'password_reset', 'email_change', 'oauth_choose'));
