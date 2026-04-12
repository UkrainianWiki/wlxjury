-- Mark existing root users
UPDATE users SET is_root = TRUE WHERE roles = 'root';

-- Migrate all non-root contest memberships to the join table
INSERT INTO user_contest (user_id, contest_id, role)
SELECT id, contest_id, roles
FROM users
WHERE contest_id IS NOT NULL
  AND roles IS NOT NULL
  AND roles != ''
  AND roles != 'root';

-- Remove the columns now stored in user_contest / is_root
ALTER TABLE users DROP COLUMN contest_id;
ALTER TABLE users DROP COLUMN roles;
