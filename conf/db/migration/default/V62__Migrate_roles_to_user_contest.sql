-- Move users.contest_id / users.roles into user_contest and users.is_root (V61), then
-- drop the two columns.
--
-- users.roles may hold a comma-separated list (the code split it); root anywhere in it
-- makes the user root. The other roles, as written, become the role of the user's
-- membership in users.contest_id. Skipped: users without a contest or a role, and a
-- contest_id of a contest that no longer exists (fk_uc_contest would reject it); those
-- accounts stay, without a membership. A user's earlier contests (users.contest_id was
-- sometimes moved on to the next contest) are not added back, only the current one.
--
-- Check on production first (the memberships this skips for a missing contest):
--   SELECT COUNT(*) FROM users u LEFT JOIN contest_jury c ON c.id = u.contest_id
--   WHERE u.contest_id IS NOT NULL AND c.id IS NULL;
--
-- INSERT IGNORE and one final ALTER: a run stopped half way is redone without duplicates.
UPDATE users SET is_root = TRUE
WHERE FIND_IN_SET('root', REPLACE(roles, ' ', '')) > 0;

INSERT IGNORE INTO user_contest (user_id, contest_id, role)
SELECT r.id, r.contest_id, r.role
FROM (
  SELECT id, contest_id,
         TRIM(BOTH ',' FROM REPLACE(CONCAT(',', REPLACE(roles, ' ', ''), ','), ',root,', ',')) AS role
  FROM users
  WHERE contest_id IS NOT NULL AND roles IS NOT NULL
) r
JOIN contest_jury c ON c.id = r.contest_id
WHERE r.role != '';

ALTER TABLE users DROP COLUMN contest_id, DROP COLUMN roles;
