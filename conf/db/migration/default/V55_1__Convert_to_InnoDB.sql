-- Convert the jury tool's tables to InnoDB.
--
-- Production's tables are MyISAM (created before InnoDB was MariaDB's default; MyISAM
-- tables stay MyISAM). On MyISAM there are no transactions (a rollback is a no-op),
-- foreign keys are ignored, and every write locks its whole table: a vote's UPDATE on
-- `selection` blocks every gallery read of it. The test schema (B44 baseline) has always
-- been InnoDB, and V56 / V58 (ALGORITHM = INPLACE, LOCK = NONE; a foreign key) need it.
-- Flyway sorts 55.1 between V55 and V56, so this runs before them.
--
-- Each table is rebuilt as InnoDB, ROW_FORMAT = DYNAMIC (long VARCHAR/TEXT off-page,
-- index keys up to 3072 bytes) and utf8mb4 / utf8mb4_general_ci: the server's default and
-- the test schema's. Production mixes utf8mb3_general_ci with images' utf8mb4_unicode_ci;
-- converting costs nothing extra (the table is copied anyway) and lets joins between
-- images and the other tables on monument ids use indexes. TEXT columns become
-- MEDIUMTEXT (MariaDB keeps their capacity in characters); the stored data is the same.
-- Checked on a copy of production: every index fits (the longest is 1020 bytes), each
-- UNIQUE key stays unique, AUTO_INCREMENT values are kept, and the tables without a
-- PRIMARY KEY (selection, criteria, criteria_rate, category_members, round_user) have a
-- UNIQUE NOT NULL key that InnoDB uses as the clustered index. No live table has a
-- FULLTEXT index.
--
-- A table that is already InnoDB, DYNAMIC and utf8mb4_general_ci is skipped, so this is
-- a no-op on the test schema, and on production after a conversion run by hand before
-- the deploy (the runbook in docs/plans/2026-10-07-prod-like-benchmark.md).
--
-- Cost: a full copy of each table, writes to it blocked meanwhile. Measured on a copy of
-- production (3.0M selection rows, 960k images; 2 CPUs, MariaDB's default 128M buffer
-- pool, a laptop SSD): 2.5 minutes in all, of which selection 77 s and images 51 s. The
-- datadir grows from 1.35 GB to 2.6 GB (InnoDB tables and indexes are about twice the
-- size) and peaks at 3.2 GB while selection is copied. The app is down meanwhile when
-- Flyway runs this at startup.
-- Legacy tables the app doesn't use (selection_backup, rounds_test, monuments_all,
-- museum, schema_version, play_evolutions, user_contest) are left as they are.

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `users` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `contest_jury` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contest_jury');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `rounds` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rounds');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `round_user` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'round_user');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `category` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'category');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `category_members` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'category_members');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `comment` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `criteria` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'criteria');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `criteria_rate` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'criteria_rate');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `monument` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'monument');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `images` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'images');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(ENGINE <> 'InnoDB' OR ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_general_ci',
  'ALTER TABLE `selection` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'selection');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

-- Fresh statistics for the optimizer (InnoDB's persistent statistics start from the copy)
ANALYZE TABLE users, contest_jury, rounds, round_user, category, category_members, comment,
  criteria, criteria_rate, monument, images, selection;
