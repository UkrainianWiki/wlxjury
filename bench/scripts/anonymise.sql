-- Anonymise a restored production dump, in the benchmark container, right after the
-- restore (bench/scripts/bench.sh restore-dump runs it). Ids, roles and contest links
-- stay as they are; personal data becomes synthetic, with the same shape (non-null stays
-- non-null, free text keeps its length), so the data size doesn't change.
--
-- Every account's password becomes 'bench<id>', hashed as the app hashes it
-- (User.hash: hex SHA-1 of the password), so the benchmark logs in as real jurors and
-- organizers. Login is by email: juror<id>@bench.test, org<id>@bench.test, root<id>@bench.test.
--
-- Kept as is (public Commons / Wikipedia data): images (titles, authors, descriptions),
-- monument, monuments_all, category.

-- Accounts
UPDATE users SET
  email = CONCAT(CASE WHEN roles LIKE '%root%' THEN 'root'
                      WHEN roles LIKE '%admin%' OR roles LIKE '%organizer%' THEN 'org'
                      ELSE 'juror' END, id, '@bench.test'),
  fullname = CONCAT(CASE WHEN roles LIKE '%root%' THEN 'Root '
                         WHEN roles LIKE '%admin%' OR roles LIKE '%organizer%' THEN 'Organizer '
                         ELSE 'Juror ' END, id),
  password = SHA1(CONCAT('bench', id)),
  wiki_account = IF(wiki_account IS NULL OR wiki_account = '', wiki_account, CONCAT('BenchWikiUser', id));

-- Comments: author name and free text
UPDATE comment SET
  username = CONCAT('User ', user_id),
  body = LEFT(REPEAT('Lorem ipsum dolor sit amet. ', 1 + CHAR_LENGTH(body) DIV 28), CHAR_LENGTH(body));

-- Contest invitation texts may name or sign as an organizer
UPDATE contest_jury SET greeting =
  LEFT(REPEAT('Hello, {{RecipientName}}! Please log in at {{JuryToolLink}}. ', 1 + CHAR_LENGTH(greeting) DIV 60),
       CHAR_LENGTH(greeting))
WHERE greeting IS NOT NULL;

-- Legacy tables with personal data
UPDATE selection_backup SET email = CONCAT('juror', COALESCE(jury_id, 0), '@bench.test') WHERE email IS NOT NULL;
UPDATE museum SET
  director = IF(director IS NULL, NULL, CONCAT('Director ', id)),
  curator  = IF(curator  IS NULL, NULL, CONCAT('Curator ', id)),
  phones   = IF(phones   IS NULL, NULL, '+380 00 000 0000'),
  email    = IF(email    IS NULL, NULL, CONCAT('museum', id, '@bench.test'));

-- Checks: each must return 0
SELECT 'users not anonymised' AS chk, COUNT(*) AS n FROM users WHERE email NOT LIKE '%@bench.test'
UNION ALL SELECT 'selection_backup emails', COUNT(*) FROM selection_backup WHERE email IS NOT NULL AND email NOT LIKE '%@bench.test'
UNION ALL SELECT 'museum emails', COUNT(*) FROM museum WHERE email IS NOT NULL AND email NOT LIKE '%@bench.test';
