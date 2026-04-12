# Multi-Contest User Membership Design

**Date:** 2026-04-12  
**Status:** Approved

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

### New table (V50 migration)

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

### Data migration (V51 migration)

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
