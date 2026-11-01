-- Personal API tokens (db.scalikejdbc.ApiTokenJdbc): a command-line tool calls the REST API
-- (/api/...) as its user with "Authorization: Bearer wlxj_...", with that user's memberships
-- and roles. A user creates and revokes their own on their profile page.
--
--  * token_hash: the SHA-256 (hex) of the whole token, the only form it is stored in; a
--    request's token is found by its hash. The token itself is shown once, when created.
--  * prefix: "wlxj_" and the token's first 6 characters, to tell tokens apart in the list
--    and in log lines.
--  * expires_at NULL: never expires. revoked_at set: revoked, kept for the list.
--  * last_used_at: updated at most once a minute per token.
CREATE TABLE IF NOT EXISTS api_token (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL,
  name         VARCHAR(100)    NOT NULL,
  token_hash   CHAR(64)        NOT NULL,
  prefix       VARCHAR(16)     NOT NULL,
  created_at   TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at   TIMESTAMP(3)    NULL DEFAULT NULL,
  last_used_at TIMESTAMP(3)    NULL DEFAULT NULL,
  revoked_at   TIMESTAMP(3)    NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY api_token_hash_uidx (token_hash),
  KEY api_token_user_idx (user_id),
  CONSTRAINT fk_api_token_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
