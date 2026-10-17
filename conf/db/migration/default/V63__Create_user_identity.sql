-- A Google or Wikimedia identity a user signs in with (services.oauth). Keyed by the
-- provider's stable subject (`sub`; Wikimedia's is the central user id), never by email or
-- username, which change. A user may have several, linked on first sign-in by a verified
-- email or wiki account, or by the user from their own edit page.
CREATE TABLE IF NOT EXISTS user_identity (
  provider      VARCHAR(32)     NOT NULL,
  subject       VARCHAR(255)    NOT NULL,
  user_id       BIGINT UNSIGNED NOT NULL,
  email         VARCHAR(190)    NULL,
  username      VARCHAR(255)    NULL,
  created_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at TIMESTAMP       NULL,
  PRIMARY KEY (provider, subject),
  KEY idx_user_identity_user (user_id),
  CONSTRAINT fk_ui_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
