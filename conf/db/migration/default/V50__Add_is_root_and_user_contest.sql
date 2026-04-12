-- Add root flag to users (replaces the 'root' value in the roles column)
ALTER TABLE users ADD COLUMN is_root BOOLEAN NOT NULL DEFAULT FALSE;

-- Join table: a user can belong to multiple contests with different roles
CREATE TABLE user_contest (
  user_id    BIGINT UNSIGNED NOT NULL,
  contest_id BIGINT UNSIGNED NOT NULL,
  role       VARCHAR(255)    NOT NULL,
  PRIMARY KEY (user_id, contest_id),
  CONSTRAINT fk_uc_user    FOREIGN KEY (user_id)    REFERENCES users(id)    ON DELETE CASCADE,
  CONSTRAINT fk_uc_contest FOREIGN KEY (contest_id) REFERENCES contest_jury(id)  ON DELETE CASCADE
);
