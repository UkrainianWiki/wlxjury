-- Convert the seven legacy MyISAM tables to InnoDB (the maintainer's decision of 2026-11-24).
--
-- Why: the server's nightly database backup dumps a database with `mariadb-dump --lock-tables`
-- whenever it holds any non-InnoDB table, so every night all of this database's tables were
-- read-locked for about 40 s (a round-counts recount waited 41 s on 2026-11-24). With every table
-- InnoDB the backup uses --single-transaction and locks nothing. V55.1 left these tables as they
-- were, as the app doesn't use them: selection_backup (24.6 MB, 244k rows), museum,
-- user_contest_legacy (V61's rename of the old user_contest), rounds_test, schema_version,
-- play_evolutions and monuments_all (empty). Their data is kept, not dropped.
--
-- Engine only: ENGINE = InnoDB, ROW_FORMAT = DYNAMIC (V55.1's choice, for long keys and columns).
-- The character set is NOT converted (V55.1 converted the live tables to utf8mb4): the tables are
-- unused, a conversion could fail on a byte sequence invalid in the target set and would gain
-- nothing, and the engine change alone never alters stored text.
--
-- Rerunnable, and fine on a database without these tables (fresh installs, CI, tests): each
-- table is rebuilt only if it exists, is a base table and is not InnoDB, so a rerun is a no-op.
-- A view or a missing table gives NULL and is skipped.
--
-- Online DDL: ALTER TABLE ... ENGINE changes copy the table (ALGORITHM = COPY; not online): the
-- table is read-only meanwhile. Only selection_backup is not tiny: about 25 MB, a second or two.
-- Nothing in the app reads or writes these tables.
--
-- Pre-deploy check (read-only; on production, before the deploy):
--   SELECT TABLE_NAME, ENGINE, ROW_FORMAT, TABLE_ROWS, ROUND((DATA_LENGTH + INDEX_LENGTH) / 1048576, 1) AS mb
--   FROM information_schema.TABLES
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' AND ENGINE <> 'InnoDB';
-- It should list exactly the tables above (and none after the deploy). Free disk: about twice
-- selection_backup's size while it is copied.
--
-- Rollback (not that anyone should; the nightly backup would lock the tables again), for each
-- table that was MyISAM, and then forget the migration:
--   ALTER TABLE selection_backup ENGINE = MyISAM;   (likewise museum, user_contest_legacy,
--     rounds_test, schema_version, play_evolutions, monuments_all)
--   DELETE FROM flyway_schema_history WHERE version = '113';

SET @ddl = (SELECT IF(TABLE_TYPE = 'BASE TABLE' AND ENGINE <> 'InnoDB',
  'ALTER TABLE `selection_backup` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'selection_backup');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(TABLE_TYPE = 'BASE TABLE' AND ENGINE <> 'InnoDB',
  'ALTER TABLE `museum` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'museum');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(TABLE_TYPE = 'BASE TABLE' AND ENGINE <> 'InnoDB',
  'ALTER TABLE `user_contest_legacy` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_contest_legacy');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(TABLE_TYPE = 'BASE TABLE' AND ENGINE <> 'InnoDB',
  'ALTER TABLE `rounds_test` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rounds_test');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(TABLE_TYPE = 'BASE TABLE' AND ENGINE <> 'InnoDB',
  'ALTER TABLE `schema_version` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schema_version');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(TABLE_TYPE = 'BASE TABLE' AND ENGINE <> 'InnoDB',
  'ALTER TABLE `play_evolutions` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'play_evolutions');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');

SET @ddl = (SELECT IF(TABLE_TYPE = 'BASE TABLE' AND ENGINE <> 'InnoDB',
  'ALTER TABLE `monuments_all` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC',
  'DO 0') FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'monuments_all');
EXECUTE IMMEDIATE COALESCE(@ddl, 'DO 0');
