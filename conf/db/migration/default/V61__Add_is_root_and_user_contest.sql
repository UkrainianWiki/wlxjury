-- A user can belong to several contests, with a role in each: user_contest replaces
-- users.contest_id and users.roles (moved and dropped by V62). One account per email
-- (users.email stays unique): adding an existing email to another contest adds a row
-- here instead of a new account, so organizers no longer need "name+contest@" aliases.
--
-- root is not a contest role: it moves to users.is_root, and a root user needs no rows.
-- role is what users.roles held (jury, organizer, admin, prejury, ...).
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_root BOOLEAN NOT NULL DEFAULT FALSE;

-- Production still has the user_contest of an older multi-contest attempt (MyISAM, an id
-- column, soft-deleted rows, a unique key that allows several roles per contest), which
-- V55.1 leaves alone. CREATE TABLE IF NOT EXISTS would keep it, and V62 would then add
-- to its stale rows. Rename it aside, rows kept; this table has no id column. A second
-- leftover would make the rename fail and stop the migration rather than reuse it.
SET @ddl = (SELECT IF(COUNT(*) > 0, 'RENAME TABLE user_contest TO user_contest_legacy', 'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_contest' AND COLUMN_NAME = 'id');
EXECUTE IMMEDIATE @ddl;

CREATE TABLE IF NOT EXISTS user_contest (
  user_id    BIGINT UNSIGNED NOT NULL,
  contest_id BIGINT UNSIGNED NOT NULL,
  role       VARCHAR(255)    NOT NULL,
  PRIMARY KEY (user_id, contest_id),
  KEY idx_user_contest_contest (contest_id, role),
  CONSTRAINT fk_uc_user    FOREIGN KEY (user_id)    REFERENCES users(id)        ON DELETE CASCADE,
  CONSTRAINT fk_uc_contest FOREIGN KEY (contest_id) REFERENCES contest_jury(id) ON DELETE CASCADE
);
