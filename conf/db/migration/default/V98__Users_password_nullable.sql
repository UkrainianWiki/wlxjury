-- users.password NULL: an account without a password (an invitee who hasn't accepted the
-- invitation yet, an account with a wiki account only). Since S2 (26.11, commit ddf45d50) a new
-- account is created with none (UserService.createAccount); the invitation's link sets it.
--
-- V2 creates the column nullable, so every fresh database (the tests', a new install) already
-- has it so. Production's users table is older than Flyway: its password column came from the
-- Play evolution 7.sql of 2015 (`ALTER TABLE users add column password varchar(255) not null`),
-- and stayed NOT NULL. There, adding a person whose email no account has failed with a server
-- error ("Column 'password' cannot be null") from the 26.11 deploy, and Import Users skipped them.
-- NULL is the value the code reads as "no password" (User.password None: mayTakeAccessRole, the
-- invitation's acceptance, the Wikimedia placeholder); an empty string would read as a password.
--
-- Applied by hand on production on 2026-11-12 (the same ALTER); this records it, and fixes any
-- other database whose column is still NOT NULL. Only a NOT NULL column is changed: elsewhere the
-- statement is DO 0 and nothing is rebuilt, so it is rerunnable.
--
-- MariaDB 10.6 can't make a column nullable in place without a rebuild (ALGORITHM = INSTANT is
-- refused): INPLACE with LOCK = NONE rebuilds the table online (users is small: under a second
-- on production's copy), reads and writes go on; existing values are kept. A 60 s
-- lock_wait_timeout for the metadata lock (a long transaction or backup: the statement fails
-- with nothing changed). The column keeps the table's character set and collation, as before.
--
-- Undo (only while no account has a NULL password):
--   ALTER TABLE users MODIFY password VARCHAR(255) NOT NULL;

-- (EXECUTE IMMEDIATE takes no subquery: the check goes through a variable)
SET @v98_password_not_null = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'password'
    AND IS_NULLABLE = 'NO') = 1;

EXECUTE IMMEDIATE IF(@v98_password_not_null,
  'SET STATEMENT lock_wait_timeout = 60 FOR ALTER TABLE users MODIFY password VARCHAR(255) NULL DEFAULT NULL, ALGORITHM = INPLACE, LOCK = NONE',
  'DO 0');
