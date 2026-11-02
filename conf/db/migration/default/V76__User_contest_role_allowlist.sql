-- user_contest.role holds exactly one contest role (User.ContestRoles, the users page's
-- roles), compared byte for byte: no lists ('jury,root'), no root (users.is_root only), no
-- USER_ID_ marker, no case or trailing-space variants. The column's collation is
-- case-insensitive and PAD SPACE, so every comparison here is BINARY.
--
-- 1. Production's only other values are jurors disabled by hand in a DB browser, as
--    'jury1' or 'jury-': they become 'no' (a member without access), as the users page
--    disables a member.
UPDATE user_contest SET role = 'no' WHERE BINARY role IN ('jury1', 'jury-');

-- 2. The constraint. MariaDB checks the existing rows when it is added: any other value
--    (anything with root, a comma, a space, another case, an unknown word) fails this
--    migration on purpose, for a person to look at, rather than being reset silently.
--    Adding a role later needs a migration that replaces it.
ALTER TABLE user_contest DROP CONSTRAINT IF EXISTS chk_user_contest_role;
ALTER TABLE user_contest ADD CONSTRAINT chk_user_contest_role
  CHECK (BINARY role IN ('no', 'jury', 'organizer', 'admin', 'board', 'auditor', 'staff'));
