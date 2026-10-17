-- Move users.contest_id / users.roles into user_contest and users.is_root (V61), then
-- drop the two columns.
--
-- users.roles may hold a comma-separated list (the code split it); root anywhere in it
-- makes the user root. Of the other roles, the one the old code acted on becomes the
-- role of the user's membership in users.contest_id: admin, else organizer, else jury,
-- else the first one ("jury, organizer" was treated as an organizer: the organizer home
-- page, and not among the jurors a new round offers). Skipped: users without a contest
-- or a role, and a contest_id of a contest that no longer exists (fk_uc_contest would
-- reject it); those accounts stay, without a membership.
--
-- users.contest_id was sometimes moved on to the next contest, so a user also becomes a
-- member of every other contest with a round they are assigned to (round_user, also a
-- stopped assignment): as organizer if one of those rows says organizer, otherwise as
-- jury. round_user.role never gives admin, and it may hold a USER_ID_ marker, which is
-- read as jury. The users.contest_id membership is inserted first and keeps its role.
--
-- Those older contests become reachable again, so first every round left marked active
-- (or frozen with its saved flag on) that will no longer be current is turned off: an
-- old round still marked active would take votes again. A round stays as it is only if
--  * no later contest of the same name and country exists (it is not last year's
--    WLM Ukraine next to this year's), and
--  * one of its jurors could open it before: users.contest_id was the round's contest
--    for a user assigned to it (round_user) or voting in it (selection, for legacy
--    rounds). A round nobody could reach was closed in effect already.
-- The rule reads the data, not the date the migration runs on. A frozen round
-- (freeze_count > 0) also stays off when its last freeze is released.
--
-- The rounds it turns off, with their flags, are kept in v62_rounds_turned_off. To turn
-- one back on as it was:
--   UPDATE rounds r JOIN v62_rounds_turned_off t ON t.round_id = r.id
--   SET r.active = t.active, r.active_before_freeze = t.active_before_freeze
--   WHERE r.id = ...;
--
-- Check on production first (the memberships this skips for a missing contest, and the
-- rounds it turns off: the SELECT of the INSERT into v62_rounds_turned_off):
--   SELECT COUNT(*) FROM users u LEFT JOIN contest_jury c ON c.id = u.contest_id
--   WHERE u.contest_id IS NOT NULL AND c.id IS NULL;
--
-- INSERT IGNORE and one final ALTER: a run stopped half way is redone without duplicates,
-- and v62_rounds_turned_off keeps the flags the first run saw.
CREATE TABLE IF NOT EXISTS v62_rounds_turned_off (
  round_id             BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  active               TINYINT(1)      NULL,
  active_before_freeze TINYINT(1)      NULL
);

INSERT IGNORE INTO v62_rounds_turned_off (round_id, active, active_before_freeze)
SELECT r.id, r.active, r.active_before_freeze
FROM rounds r
JOIN contest_jury c ON c.id = r.contest_id
WHERE (r.active OR (r.freeze_count > 0 AND r.active_before_freeze))
  AND NOT (
    NOT EXISTS (SELECT 1 FROM contest_jury later
                WHERE later.name = c.name AND later.country = c.country AND later.year > c.year)
    AND (EXISTS (SELECT 1 FROM round_user ru JOIN users u ON u.id = ru.user_id
                 WHERE ru.round_id = r.id AND u.contest_id = r.contest_id)
         OR EXISTS (SELECT 1 FROM selection s JOIN users u ON u.id = s.jury_id
                    WHERE s.round_id = r.id AND u.contest_id = r.contest_id))
  );

UPDATE rounds r
JOIN v62_rounds_turned_off t ON t.round_id = r.id
SET r.active = FALSE,
    r.active_before_freeze = IF(r.freeze_count > 0, 0, r.active_before_freeze);

UPDATE users SET is_root = TRUE
WHERE FIND_IN_SET('root', REPLACE(roles, ' ', '')) > 0;

INSERT IGNORE INTO user_contest (user_id, contest_id, role)
SELECT r.id, r.contest_id,
       CASE
         WHEN FIND_IN_SET('admin', r.roles) > 0     THEN 'admin'
         WHEN FIND_IN_SET('organizer', r.roles) > 0 THEN 'organizer'
         WHEN FIND_IN_SET('jury', r.roles) > 0      THEN 'jury'
         ELSE SUBSTRING_INDEX(r.roles, ',', 1)
       END
FROM (
  SELECT id, contest_id,
         TRIM(BOTH ',' FROM REPLACE(CONCAT(',', REPLACE(roles, ' ', ''), ','), ',root,', ',')) AS roles
  FROM users
  WHERE contest_id IS NOT NULL AND roles IS NOT NULL
) r
JOIN contest_jury c ON c.id = r.contest_id
WHERE r.roles != '';

INSERT IGNORE INTO user_contest (user_id, contest_id, role)
SELECT ru.user_id, r.contest_id, IF(MAX(ru.role = 'organizer'), 'organizer', 'jury')
FROM round_user ru
JOIN rounds r ON r.id = ru.round_id
JOIN contest_jury c ON c.id = r.contest_id
JOIN users u ON u.id = ru.user_id
GROUP BY ru.user_id, r.contest_id;

ALTER TABLE users DROP COLUMN contest_id, DROP COLUMN roles;
