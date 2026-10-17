-- Move users.contest_id / users.roles into user_contest and users.is_root (V61), then
-- drop the two columns.
--
-- users.roles may hold a comma-separated list (the code split it); root anywhere in it
-- makes the user root. The other roles, as written, become the role of the user's
-- membership in users.contest_id. Skipped: users without a contest or a role, and a
-- contest_id of a contest that no longer exists (fk_uc_contest would reject it); those
-- accounts stay, without a membership.
--
-- users.contest_id was sometimes moved on to the next contest, so a user also becomes a
-- member of every other contest with a round they are assigned to (round_user, also a
-- stopped assignment): as organizer if one of those rows says organizer, otherwise as
-- jury. round_user.role never gives admin, and it may hold a USER_ID_ marker, which is
-- read as jury. The users.contest_id membership is inserted first and keeps its role.
--
-- Those older contests become reachable again, so first every round of a contest from
-- another year than the current one is turned off: an old round still marked active
-- would take votes again. A frozen round (freeze_count > 0) also stays off when its
-- last freeze is released.
--
-- Check on production first (the memberships this skips for a missing contest):
--   SELECT COUNT(*) FROM users u LEFT JOIN contest_jury c ON c.id = u.contest_id
--   WHERE u.contest_id IS NOT NULL AND c.id IS NULL;
--
-- INSERT IGNORE and one final ALTER: a run stopped half way is redone without duplicates.
UPDATE rounds r
JOIN contest_jury c ON c.id = r.contest_id
SET r.active = FALSE,
    r.active_before_freeze = IF(r.freeze_count > 0, 0, r.active_before_freeze)
WHERE c.year <> YEAR(CURDATE());

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

INSERT IGNORE INTO user_contest (user_id, contest_id, role)
SELECT ru.user_id, r.contest_id, IF(MAX(ru.role = 'organizer'), 'organizer', 'jury')
FROM round_user ru
JOIN rounds r ON r.id = ru.round_id
JOIN contest_jury c ON c.id = r.contest_id
JOIN users u ON u.id = ru.user_id
GROUP BY ru.user_id, r.contest_id;

ALTER TABLE users DROP COLUMN contest_id, DROP COLUMN roles;
