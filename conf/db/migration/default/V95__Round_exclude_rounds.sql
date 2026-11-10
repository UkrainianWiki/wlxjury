-- "Leave out files that are in round(s)" (the maintainer's decision of 2026-11-10, for a Wiki Loves
-- Earth Ukraine round Q-00-1: Q-00's files that didn't pass into Q-01). A round's distribution (its
-- creation, a resubmit or retry, "Distribute new files") leaves out every file that has a row in one
-- of these rounds' round_image (V84), whatever its status and votes there. Stored like `previous`
-- (V52): the ids of other rounds of the round's contest, comma-separated, in the form's order
-- (db.scalikejdbc.Round.excludeRounds); NULL, every existing round: none (their files unchanged).
--
-- Plain statements, as V93's (MigrationSchemaSpec reads them): neither depends on the session's
-- sql_mode (a nullable column added; a check over NULLs, its patterns without a backslash); the app writes the column under the
-- server's STRICT_TRANS_TABLES (db practices §1.8), and the round form keeps it within 255.
--
-- 1. The column, NULL for every round: ALGORITHM=INSTANT, metadata only (existing rows read the
--    default; the statement fails rather than copies). Rerunnable.
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS exclude_rounds VARCHAR(255) NULL DEFAULT NULL,
  ALGORITHM = INSTANT;

-- 2. Its form, checked byte for byte (db practices §1): round ids (digits, no leading zero) joined by
--    single commas, or NULL; no empty string, space, sign or line break. PCRE's $ also matches
--    before a trailing newline, so a second test refuses any byte but a digit or a comma (a line
--    break among them). No backslash in either pattern: the statement reads the same under any
--    sql_mode, NO_BACKSLASH_ESCAPES included. One statement, so a failure can't leave the column
--    without its check; rerunnable. MariaDB checks the existing rows (all NULL) as it adds it,
--    which copies `round`, one row per round: milliseconds (as V93's).
ALTER TABLE round DROP CONSTRAINT IF EXISTS round_exclude_rounds_chk,
  ADD CONSTRAINT round_exclude_rounds_chk
    CHECK (exclude_rounds IS NULL OR (BINARY exclude_rounds REGEXP '^[1-9][0-9]{0,18}(,[1-9][0-9]{0,18})*$'
      AND NOT (BINARY exclude_rounds REGEXP '[^0-9,]')));
