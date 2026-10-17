-- Accounts that look like one person's aliases: the same email apart from a "+tag" in
-- the local part (name+wle2025@example.org and name@example.org), and for Gmail apart
-- from dots. Organizers made them because an account could be in one contest only.
-- Read-only. Run it on production, or on a copy (scripts/run-with-prod-dump.sh
-- --dump-only), before V62: it reads users.contest_id / users.roles.
--
--   docker exec -i wlxjury-local-dev mariadb -uroot -p... wlxjury < scripts/report-alias-accounts.sql
--
-- A group is a candidate to merge into one account only if its accounts never share a
-- contest (query 2), and they are one person: the same name or wiki account, not an
-- organizer's mailbox with a tag per juror (wlm+juror1@, wlm+juror2@: different people).

-- 1. Every account of every group, with the contests it is in, voted in or was assigned to.
WITH acc AS (
  SELECT u.id, u.email, u.fullname, u.wiki_account, u.roles, u.contest_id, u.created_at,
         LOWER(SUBSTRING_INDEX(u.email, '@', -1)) AS domain,
         LOWER(SUBSTRING_INDEX(SUBSTRING_INDEX(u.email, '@', 1), '+', 1)) AS local_base
  FROM users u
  WHERE u.email LIKE '%@%' AND u.deleted_at IS NULL
), norm AS (
  SELECT acc.*,
         CONCAT(IF(domain IN ('gmail.com', 'googlemail.com'), REPLACE(local_base, '.', ''), local_base),
                '@', IF(domain = 'googlemail.com', 'gmail.com', domain)) AS person
  FROM acc
), grp AS (
  SELECT person FROM norm GROUP BY person HAVING COUNT(*) > 1
), contests AS (
  SELECT DISTINCT x.user_id, x.contest_id FROM (
    SELECT id AS user_id, contest_id FROM users WHERE contest_id IS NOT NULL
    UNION ALL
    SELECT DISTINCT s.jury_id, r.contest_id FROM selection s JOIN rounds r ON r.id = s.round_id
    UNION ALL
    SELECT ru.user_id, r.contest_id FROM round_user ru JOIN rounds r ON r.id = ru.round_id
  ) x
)
SELECT n.person, n.id, n.email, n.fullname, n.wiki_account, n.roles, n.contest_id,
       (SELECT GROUP_CONCAT(c.contest_id ORDER BY c.contest_id) FROM contests c WHERE c.user_id = n.id) AS all_contests,
       (SELECT COUNT(*) FROM selection s WHERE s.jury_id = n.id) AS selections,
       n.created_at
FROM norm n JOIN grp g ON g.person = n.person
ORDER BY n.person, n.id;

-- 2. Pairs of accounts of one group in the same contest: not mergeable (selection and
--    round_user rows would collide, and it may be two people).
WITH acc AS (
  SELECT u.id,
         LOWER(SUBSTRING_INDEX(u.email, '@', -1)) AS domain,
         LOWER(SUBSTRING_INDEX(SUBSTRING_INDEX(u.email, '@', 1), '+', 1)) AS local_base
  FROM users u
  WHERE u.email LIKE '%@%' AND u.deleted_at IS NULL
), norm AS (
  SELECT id, CONCAT(IF(domain IN ('gmail.com', 'googlemail.com'), REPLACE(local_base, '.', ''), local_base),
                    '@', IF(domain = 'googlemail.com', 'gmail.com', domain)) AS person
  FROM acc
), contests AS (
  SELECT DISTINCT x.user_id, x.contest_id FROM (
    SELECT id AS user_id, contest_id FROM users WHERE contest_id IS NOT NULL
    UNION ALL
    SELECT DISTINCT s.jury_id, r.contest_id FROM selection s JOIN rounds r ON r.id = s.round_id
    UNION ALL
    SELECT ru.user_id, r.contest_id FROM round_user ru JOIN rounds r ON r.id = ru.round_id
  ) x
)
SELECT a.person, a.id AS account1, b.id AS account2,
       GROUP_CONCAT(ca.contest_id ORDER BY ca.contest_id) AS shared_contests
FROM norm a
JOIN norm b ON b.person = a.person AND b.id > a.id
JOIN contests ca ON ca.user_id = a.id
JOIN contests cb ON cb.user_id = b.id AND cb.contest_id = ca.contest_id
GROUP BY a.person, a.id, b.id
ORDER BY a.person, a.id, b.id;

-- 3. Totals: groups, their accounts, and groups without the plain (untagged) email.
WITH acc AS (
  SELECT u.id, LOWER(u.email) AS email,
         LOWER(SUBSTRING_INDEX(u.email, '@', -1)) AS domain,
         LOWER(SUBSTRING_INDEX(SUBSTRING_INDEX(u.email, '@', 1), '+', 1)) AS local_base
  FROM users u
  WHERE u.email LIKE '%@%' AND u.deleted_at IS NULL
), norm AS (
  SELECT id, email, CONCAT(local_base, '@', domain) AS plain,
         CONCAT(IF(domain IN ('gmail.com', 'googlemail.com'), REPLACE(local_base, '.', ''), local_base),
                '@', IF(domain = 'googlemail.com', 'gmail.com', domain)) AS person
  FROM acc
), grp AS (
  SELECT person, COUNT(*) AS accounts, SUM(email = plain) AS plain_accounts
  FROM norm GROUP BY person HAVING COUNT(*) > 1
)
SELECT COUNT(*) AS groups_, SUM(accounts) AS accounts, SUM(plain_accounts = 0) AS groups_without_plain_email
FROM grp;
