-- round.created_at (V3: TIMESTAMP NOT NULL), users.created_at (V2: timestamp DEFAULT
-- CURRENT_TIMESTAMP) and account_token.expires_at (V77: TIMESTAMP(3) NOT NULL) state no full
-- definition, so the server fills it in. MariaDB 10.10 turned explicit_defaults_for_timestamp
-- on: a database created on 10.10+ (a fresh 10.11 test or dev database) gets
--   round.created_at          timestamp NOT NULL                 (no default)
--   users.created_at          timestamp NULL DEFAULT current_timestamp()
--   account_token.expires_at  timestamp(3) NOT NULL              (no default)
-- where one created on 10.6, production's, has
--   round.created_at          timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp()
--   users.created_at          timestamp NOT NULL DEFAULT current_timestamp()
--   account_token.expires_at  timestamp(3) NOT NULL DEFAULT '0000-00-00 00:00:00.000'
-- (docs/plans/2026-11-03-flyway.md, section 4). This gives a 10.10+ database 10.6's
-- definitions, so every server has one schema whatever its explicit_defaults_for_timestamp
-- (docs/plans/2026-10-19-mariadb-10.11.md). The app always writes round.created_at and
-- account_token.expires_at; the defaults only make the schemas the same.
--
-- Only a column still in the 10.10+ form is changed: on a database created on 10.6 (production,
-- before or after the move: an in-place upgrade and a dump both keep the definitions) every
-- statement is DO 0, and nothing is rebuilt. Production's users.created_at also has
-- ON UPDATE current_timestamp() (an older schema's); it is left as it is.

-- (EXECUTE IMMEDIATE takes no subquery: the checks go through variables)
SET @v86_round_no_default = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'round' AND COLUMN_NAME = 'created_at'
    AND COLUMN_DEFAULT IS NULL) = 1;

EXECUTE IMMEDIATE IF(@v86_round_no_default,
  'ALTER TABLE round MODIFY created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP',
  'DO 0');

-- users: rows with NULL first (only where the column is nullable: on a NOT NULL column
-- "IS NULL" also matches zero dates)
SET @v86_users_nullable = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'created_at'
    AND IS_NULLABLE = 'YES') = 1;

EXECUTE IMMEDIATE IF(@v86_users_nullable,
  'UPDATE users SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL',
  'DO 0');

EXECUTE IMMEDIATE IF(@v86_users_nullable,
  'ALTER TABLE users MODIFY created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP',
  'DO 0');

SET @v86_token_no_default = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_token' AND COLUMN_NAME = 'expires_at'
    AND COLUMN_DEFAULT IS NULL) = 1;

EXECUTE IMMEDIATE IF(@v86_token_no_default,
  'ALTER TABLE account_token MODIFY expires_at TIMESTAMP(3) NOT NULL DEFAULT ''0000-00-00 00:00:00.000''',
  'DO 0');

SET @v86_round_no_default = NULL, @v86_users_nullable = NULL, @v86_token_no_default = NULL;
