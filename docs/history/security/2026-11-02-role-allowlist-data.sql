-- N1 hotfix (role allowlist): root audit and one-off data fix for production's user_contest.
-- Reviewed script for a person to run by hand with an account that may UPDATE wlxjury;
-- not a Flyway migration (production is at schema 66, and the followups branch already
-- owns V67-V75; the forward fix adds V76 with the same UPDATE and a CHECK constraint).
--
-- Paste ONE STATEMENT AT A TIME into an interactive mysql session. Never pipe this file into
-- mysql: step 3's COMMIT is a separate step that follows a check of the row count.
--
-- Run it after the hotfix release is deployed (the code is safe without it: a role that is
-- not exactly one allowlisted value grants nothing and its membership is inactive), and
-- before the release with V76: V76 fails, on purpose, on any value other than these.
--
-- The column's collation is case-insensitive and PAD SPACE, so every comparison is BINARY.

-- 1. The root accounts. Known on 2026-11-02: ids 3, 651, 1167, 1307, 1752 (one is stale and
--    may be revoked). ANY OTHER id is an incident: keep the row, record it, look for
--    POST /users/save in the Apache access logs of every jury vhost (time and client IP only:
--    no request body, no account), then take root away. The role queries below do not prove
--    N1 was never used: an exploiter could have set their own membership back to a clean role.
SELECT id, created_at, is_root FROM users WHERE is_root = 1 ORDER BY id;

-- 2. Before: the rows that are not exactly one contest role. Expected on 2026-11-02:
--    20 x 'jury1' (contests 16, 23, 27, 36) and 3 x 'jury-' (contest 64), nothing else.
SELECT user_id, contest_id, role, HEX(role) AS role_hex
FROM user_contest
WHERE BINARY role NOT IN ('no', 'jury', 'organizer', 'admin', 'board', 'auditor', 'staff')
ORDER BY contest_id, user_id;

-- 3. The jurors disabled by hand in a DB browser become 'no' (a member without access), as
--    the users page disables a member.
-- 3a.
START TRANSACTION;
-- 3b. Expected: "Query OK, 23 rows affected".
UPDATE user_contest SET role = 'no' WHERE BINARY role IN ('jury1', 'jury-');
-- 3c. ONLY if 3b reported 23 rows affected:
COMMIT;
--     Otherwise, instead of COMMIT:  ROLLBACK;  and look at step 2's rows first.

-- 4. After: anything still listed here is unexpected (root, a comma, a space, another case,
--    an unknown word such as prejury). Do not reset it blindly: record it, check the account's
--    POST /users/save in the Apache access logs, and decide. Expected: no rows. V76 (the
--    forward release) fails on any row this lists.
SELECT user_id, contest_id, role, HEX(role) AS role_hex
FROM user_contest
WHERE BINARY role NOT IN ('no', 'jury', 'organizer', 'admin', 'board', 'auditor', 'staff')
ORDER BY contest_id, user_id;
