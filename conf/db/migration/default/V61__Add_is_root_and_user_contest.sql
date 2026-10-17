-- A user can belong to several contests, with a role in each: user_contest replaces
-- users.contest_id and users.roles (moved and dropped by V62). One account per email
-- (users.email stays unique): adding an existing email to another contest adds a row
-- here instead of a new account, so organizers no longer need "name+contest@" aliases.
--
-- root is not a contest role: it moves to users.is_root, and a root user needs no rows.
-- role is what users.roles held (jury, organizer, admin, prejury, ...).
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_root BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS user_contest (
  user_id    BIGINT UNSIGNED NOT NULL,
  contest_id BIGINT UNSIGNED NOT NULL,
  role       VARCHAR(255)    NOT NULL,
  PRIMARY KEY (user_id, contest_id),
  KEY idx_user_contest_contest (contest_id, role),
  CONSTRAINT fk_uc_user    FOREIGN KEY (user_id)    REFERENCES users(id)        ON DELETE CASCADE,
  CONSTRAINT fk_uc_contest FOREIGN KEY (contest_id) REFERENCES contest_jury(id) ON DELETE CASCADE
);
