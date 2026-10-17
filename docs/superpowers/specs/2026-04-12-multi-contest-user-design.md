# Multi-Contest User Membership Design

**Date:** 2026-04-12  
**Status:** Implemented (finished 2026-10-17, see the last two sections)

## Problem

A user can currently belong to only one contest (`users.contest_id`). Roles are stored as a single string on the `users` row. The goal is to allow a user to be a member of multiple contests with a different role in each.

## Constraints

- Roles are per-contest (a user can be `jury` in one contest and `organizer` in another)
- After login, the app shows the user's last active contest (stored in session); a switcher UI allows changing to another contest
- User creation remains contest-scoped: admins create users within their current contest
- `root` users remain contest-independent — no `user_contest` rows, role determined separately

## Approach

New `user_contest` join table (mirrors existing `round_user` pattern). Remove `contest_id` and `roles` from `users`. Session stores `current_contest_id`. `User` case class retains its `contestId: Option[Long]` and `roles: Set[String]` fields — they are now populated dynamically from the join table on each request, so auth logic is unchanged.

## Section 1: Data Model

### New table (V61 migration, written as V50)

```sql
-- Store root flag directly on users, replacing the roles column for root users
ALTER TABLE users ADD COLUMN is_root BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE user_contest (
  user_id    BIGINT UNSIGNED NOT NULL,
  contest_id BIGINT UNSIGNED NOT NULL,
  role       VARCHAR(255)    NOT NULL,
  PRIMARY KEY (user_id, contest_id),
  CONSTRAINT fk_uc_user    FOREIGN KEY (user_id)    REFERENCES users(id)    ON DELETE CASCADE,
  CONSTRAINT fk_uc_contest FOREIGN KEY (contest_id) REFERENCES contest(id)  ON DELETE CASCADE
);
```

### Data migration (V62 migration, written as V51)

```sql
-- Mark existing root users
UPDATE users SET is_root = TRUE WHERE roles = 'root';

-- Migrate non-root contest memberships
INSERT INTO user_contest (user_id, contest_id, role)
SELECT id, contest_id, roles FROM users
WHERE contest_id IS NOT NULL AND roles != '' AND roles != 'root';

ALTER TABLE users DROP COLUMN contest_id;
ALTER TABLE users DROP COLUMN roles;
```

The existing `round_user` table and all round-level assignment logic are untouched.

## Section 2: User Loading & Session

### Session keys

- `username` — existing, unchanged
- `current_contest_id` — new; the contest the user is currently operating in

### `userFromRequest` (in `Secured`)

Updated flow:
1. Load `User` by username from `users` table (as today — no `contest_id`/`roles` columns)
2. If user is `root`: populate `roles = Set("root")`, `contestId = None` — done
3. Otherwise: read `current_contest_id` from session
   - If present: look up `user_contest` for `(user_id, current_contest_id)`, populate `user.contestId` and `user.roles`
   - If absent: load first contest from `UserContest.findByUser(userId)`, use it, and store `current_contest_id` in the session response

### New DAO: `UserContestJdbc`

```scala
case class UserContest(userId: Long, contestId: Long, role: String)

object UserContestJdbc extends CRUDMapper[UserContest] {
  def findByUser(userId: Long): Seq[UserContest]
  def findByContest(contestId: Long): Seq[UserContest]
  def create(userId: Long, contestId: Long, role: String): Unit
  def updateRole(userId: Long, contestId: Long, role: String): Unit
  def delete(userId: Long, contestId: Long): Unit
}
```

## Section 3: Contest Switcher UI

### New action: `UserController.switchContest(contestId: Long)`

1. Verifies `UserContestJdbc.findByUser(user.getId)` contains `contestId` (prevents unauthorized switching)
2. Stores `contestId` in `current_contest_id` session key
3. Redirects to `LoginController.index`

### Nav template

The shared nav/header template renders a dropdown of the user's contests. For users with one contest it shows a static label. `withAuth` fetches `UserContestJdbc.findByUser` once per request and passes it to views as an implicit or explicit parameter: `Seq[(contestId: Long, role: String, contestName: String)]`.

## Section 4: User Creation & Authorization

### User creation

`UserService.createUser`: after `User.create(toCreate)`, insert into `user_contest(user_id, contest_id, role)`. The `importUsers` flow is unchanged — users are created in the admin's current contest with role `jury`.

### Authorization (unchanged call sites)

`isInContest`, `contestPermission`, and `roundPermission` in `Secured` all operate on `user.contestId` and `user.roles` — now session-scoped and populated from `user_contest`. No changes to these methods.

### Updated DAO queries

| Current | Updated |
|---|---|
| `User.findByContest(id)` — `WHERE contest_id = ?` | JOIN with `user_contest` |
| `User.loadJurors(contestId)` — `WHERE contest_id = ? AND roles = 'jury'` | JOIN with `user_contest WHERE role = 'jury'` |
| `User.updateUser(...)` — updates `roles` column on `users` | Updates `role` on `user_contest` for `(userId, currentContestId)` |

### Root users

`root` has no `user_contest` rows. In `userFromRequest`, if `user.isRoot` is `true` (from `users.is_root`), `contestId` stays `None` and `roles = Set("root")`. `User.extract` reads the new `is_root` column and sets `roles = Set(User.ROOT_ROLE)` when true. `User.isAdmin` and `isRoot` checks are unchanged.

## Out of Scope

- Changing the `round_user` assignment flow
- Multi-contest batch operations
- Contest membership management UI beyond user creation and the switcher

## Changes while finishing (2026-10-17)

Rebased onto the background-distribution / deployment line (production is at V53; the next release adds V54–V60), so the migrations are now **V61** (`is_root`, `user_contest`) and **V62** (data move, drop `users.contest_id` / `users.roles`). V50 and V51 were already taken.

- **V62** skips memberships of contests that no longer exist (they would fail `fk_uc_contest`), treats `root` anywhere in a comma-separated `roles` as root, copies the other roles as written (`UserContest.roles` splits a list), and is safe to re-run (INSERT IGNORE, one final ALTER). `users.contest_id` was sometimes moved on to the next contest, so V62 also makes a user a member of every contest with a round they are assigned to in `round_user` (organizer if a row says so, otherwise jury; never admin), after the `contest_id` membership, which keeps its role. Because that reopens old contests to their jurors, V62 first turns off every round of a contest whose year is not the year the migration runs in (a frozen round's saved flag too). Tested from production's V53 MyISAM schema through the whole chain in `ConvertToInnoDbMigrationSpec`.
- **`User.create` writes the membership** when `contestId` and a contest role are set, so every creation path (round fixtures, `AddUsers`, `GlobalRefactor`, Gatling setup, tests) keeps working. A `root` role sets `is_root`, which also fixes the first-user sign-up.
- **Users are loaded in a contest**: `User.contestMembers` (behind `findByContest`, `loadJurors`, `findByContestAndRoles`, `findInContest`) returns each user with `contestId` and their role there. `User.contestRole` replaces `roles.head` for `round_user` rows: a role set with the `USER_ID_` marker has no reliable head.
- **An existing email joins the contest** (`UserService.createUser`, used by the add-user row and mass import): the existing account gets a membership, keeps its password, and gets the greeting with "the password you already use" instead of a new password. An existing member keeps their role and gets no mail.
- **Editing a shared account**: the edited user is loaded from the database, never from the form's contest field (before, an admin could edit any user by posting their own contest id). An admin edits members of their current contest. Name, email, wiki account and password are shared by all of the user's contests, so a contest admin may change them only if they are an admin of every one of those contests; otherwise only the role in their own contest changes. Root may change anything and sets root with the role select.
- **Switcher**: `GET /switch-contest/:contestId` (no CSRF exemption needed; it only changes which of the user's own contests the session shows), one query for the contest names. Login starts in the user's latest contest.

## Accounts made to get around one contest per user

Because an account could be in one contest only and emails are unique, organizers created extra accounts for people already in the tool, usually with a tagged address (`name+wle2025@example.org`). After this change they are not needed: adding the plain address again adds a membership. The existing ones are left as they are by the migration, on purpose:

- **They may be different people.** A tag per juror on an organizer's mailbox (`wlm+juror1@`, `wlm+juror2@`) is a common way to make several jurors' logins; merging those would mix different people's votes.
- **Same-contest pairs can't merge**: their `selection` (unique page/juror/round) and `round_user` rows would collide, and two accounts in one contest are most likely two people, or one person deliberately in two roles.
- **Merging is not undoable** once `selection.jury_id` is rewritten, and it changes which login and password a person uses.
- **Unmerged aliases still work**: each keeps its own contests and history.

`scripts/report-alias-accounts.sql` (read-only, for production or a copy, before V62) lists the groups (by email without `+tag`, and without dots for Gmail), each account's contests (its `contest_id` and the contests it voted in or was assigned to), the pairs that share a contest, and totals.

Recommended handling:

1. Deploy multi-contest membership; from then on, organizers invite the plain address.
2. Run the report on a production copy and triage: merge a group only when its accounts never share a contest and are clearly one person (same name or wiki account, the plain address exists or the person confirms).
3. Merge, after the InnoDB conversion (V55.1) and with no round distribution running, in one transaction per pair, keeping the plain-address account:

   ```sql
   START TRANSACTION;
   -- must return 0: the two accounts share no contest
   SELECT COUNT(*) FROM user_contest k JOIN user_contest d USING (contest_id)
   WHERE k.user_id = @keep AND d.user_id = @drop;
   INSERT IGNORE INTO user_contest (user_id, contest_id, role)
     SELECT @keep, contest_id, role FROM user_contest WHERE user_id = @drop;
   UPDATE selection  SET jury_id = @keep WHERE jury_id = @drop;
   UPDATE round_user SET user_id = @keep WHERE user_id = @drop;
   UPDATE comment    SET user_id = @keep WHERE user_id = @drop;
   DELETE FROM users WHERE id = @drop; -- its user_contest rows go with it
   COMMIT;
   ```

   (The overlap check above only sees memberships; the report's query 2 also covers contests an account voted in after its `contest_id` moved on.) Then tell the person to log in with the kept address and its password, or reset it.
4. Leave the rest alone.

If merges turn out to be frequent, a root-only "merge into" action with the same checks would replace the SQL.
