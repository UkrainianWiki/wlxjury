-- N1 hotfix (role allowlist): one-off data fix for production's user_contest.
-- Reviewed script for a person to run by hand with an account that may UPDATE wlxjury;
-- not a Flyway migration (production is at schema 66, and the followups branch already
-- owns V67-V75; the forward fix adds V76 with the same UPDATE and a CHECK constraint).
--
-- Run it after the hotfix release is deployed (the code is safe without it: a role that is
-- not exactly one allowlisted value grants nothing and its membership is inactive), and
-- before the release with V76: V76 fails, on purpose, on any value other than these.
--
-- The column's collation is case-insensitive and PAD SPACE, so every comparison is BINARY.

-- 0. Before: the rows that are not exactly one contest role. Expected on 2026-11-02:
--    19 x 'jury1' (contests 16, 23, 27, 36) and 3 x 'jury-' (contest 64), nothing else.
SELECT user_id, contest_id, role, HEX(role) AS role_hex
FROM user_contest
WHERE BINARY role NOT IN ('no', 'jury', 'organizer', 'admin', 'board', 'auditor', 'staff')
ORDER BY contest_id, user_id;

-- 1. The jurors disabled by hand in a DB browser become 'no' (a member without access),
--    as the users page disables a member. Expected: 22 rows affected.
START TRANSACTION;
UPDATE user_contest SET role = 'no' WHERE BINARY role IN ('jury1', 'jury-');
-- check the count printed above (22), then:
COMMIT;
-- (or ROLLBACK; if the count differs, and look at query 0's rows first)

-- 2. After: anything still listed here is unexpected (root, a comma, a space, another case,
--    an unknown word). Do not reset it blindly: record it, check the account's
--    POST /users/save in the Apache access logs of every jury vhost (N1), and decide. Expected:
--    no rows.
SELECT user_id, contest_id, role, HEX(role) AS role_hex
FROM user_contest
WHERE BINARY role NOT IN ('no', 'jury', 'organizer', 'admin', 'board', 'auditor', 'staff')
ORDER BY contest_id, user_id;
