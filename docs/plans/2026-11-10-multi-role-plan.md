# Several roles per person in one contest, and pre-screening rounds (plan v2)

Date: 2026-11-10. This is a read-only design. Nothing was changed.
- **History.** Revised the same day after an independent review (R1-R21), a follow-up review ("follow-up N"), a
  final check ("final N") and a second independent review (edit-user removals, root + Juror holes, check 7). All their
  findings were checked against the code and applied.
- **Every open decision is now answered** (O1-O14, §12). The biggest change is the new rule **"Juror stays alone"**
  (O14, §2). It removes the privacy machinery that was needed only for organizers or admins who vote in jury rounds.

- **Code read:** `C:/scala/wlxjury-followups` at `c9805b31` ("Mirror of stack/96-organizer-docs d3aefd50", PR #279).
  File:line references are at that commit.
- **Assumed landed first:**
  - `stack/97-root-account-scope` (`C:/scala/wlxjury-root-scope`, `51e30c56`). Root becomes a separate checkbox,
    contest-role writes never touch `users.is_root`, and root writes are locked (`User.setRootLocked`).
  - **Before 26.11, as their own stack PRs:**
    - the large-view JSON author fix (R1);
    - "Yes/No" for "+/-" (O12);
    - **the `juryOrgView` flag removed** (another branch). The app stops reading and writing it, and the column
      stays until V97 drops it. Before that deploy, a DB admin lists the rounds that have it (check 6).
- **Inputs:**
  - v1 of this plan (superseded, not kept). Only its site numbering (D/Z/U/V) is reused; §3 says what applies.
  - The Montage comparison `docs/plans/2026-11-10-roles-prejury-montage.md` (written against v1).
  - An earlier COI UI plan (superseded, not kept): the message keys this plan reuses are inlined in §6.
- "Inferred" marks a conclusion the code does not state directly.

## 0. Decisions (maintainer, 2026-11-10; binding)

| # | Decision |
|---|---|
| 1 | **Design S.** One MariaDB `SET` column, `roles`, on `user_contest` and on `account_token`. It holds the access roles and the COI groups, shown as two groups. Admin + Organizer together is allowed. |
| 2 | **Round types.** A **Pre-screening** round is always Yes/No, voted by assigned Pre-screeners. A **Jury** round is Yes/No or Rated, voted by assigned Jurors. Yes/No only is enforced on the server. |
| 3 | **Visibility.** Pre-screening round: pre-screeners see each other's votes. Statistics and results only through Admin or Organizer. Jury round: a juror sees their own votes (today's rule). |
| 4 | **Timing.** After 26.11, in one release. 26.11 keeps #278 and documents the workaround. |
| 5 | **Import on an existing member:** union, never a removal; root untouched. |
| 6 | **Invitation acceptance:** union. |
| 7 | **Picker.** A new round never pre-ticks Organizer/Admin members. Role labels are shown. Candidates follow the round type. |
| 8 | **Terms.** "Organizer" stays. "Pre-screening round" and "Pre-screener" are final. Translations may use local terms. |
| J | **Juror stays alone** (O14). Juror can't be combined with Admin, Organizer or root. Pre-screener combines freely with Admin and Organizer. The COI groups (board, auditor, staff) combine with anything. It is enforced at every write and by a DB CHECK (§2.1). |
| T | **Code names follow the UI term.** The values are `prescreener` and `prescreening`, the constant is `PRESCREENER_ROLE`, and the message keys use the same words. `prejury` is not used. Stored names that differ from the UI label (`jury` = "Juror", `binary` = "Yes/No") stay as they are: renaming them would touch many tables and old rows for no user gain. |
| W | **Rating wording.** There are two axes: the round type (Pre-screening / Jury) and the rating (Yes/No / Rated). The UI and the docs say "Yes/No". New code says "yes/no"; the internal `binary` names stay. "Screening" is never used for a vote method. |
| O | The answers to O1-O14 are in §12. |

## 1. Data model and migration

### 1.1 Values

| Column | Values | Notes |
|---|---|---|
| `user_contest.roles` | `SET('jury','prescreener','organizer','admin','board','auditor','staff')`, `NOT NULL DEFAULT ''`, CHECK "juror alone" | `''` = today's `no`. Add future members **at the end only** (a SET stores a bit per member in definition order). |
| `account_token.roles` | `SET('jury','prescreener','organizer','admin')`, NULL = no role, `CHECK (roles IS NULL OR roles <> '')`, CHECK "juror alone" | `''` is never stored: the filters `role IS NOT NULL` (`AccountTokenJdbc.scala:161, 191, 205`) mean "carries a role" (R7). |
| `round.round_type` | `VARCHAR(16) ascii_bin NOT NULL DEFAULT 'jury'`, `CHECK (round_type IN ('jury','prescreening'))` | Replaces `round.roles`. The table has been `round` since V82. |

### 1.2 Why a new `round_type` column and not `round.roles`

- **`round.roles` has no live meaning.**
  - It is written on create (`Round.scala:350`), but `Round.extract` (`:298-…`) never reads it, so every loaded round
    has `Set("jury")` (`:28`).
  - The form posts a hidden `jury` (`editRound.scala.html:65`) and accepts any contest role (`EditRound.scala:198`).
    So a crafted POST can store `board` today, harmlessly.
  - `Round.availableJurors` (`:83-84`) has no caller in `app/`; only `RoundSpec.scala:38` calls it.
- **Its value would name a role where the UI names a type** (decision T). It is also free text without a CHECK, and old
  2015 values (commit 8028e0b3) would become live.
- **`round_type`** takes the UI's words and gets a CHECK. Every existing round becomes `jury`, which is what the code
  reads today. `Round.voterRole` is derived from the type: jury → `jury`, prescreening → `prescreener`.

### 1.3 Legacy `prejury`

V76's CHECK refuses `prejury` (`UserContestRoleMigrationSpec.scala:39`). The 2026-11-02 audit expected no such rows
(`docs/history/security/2026-11-02-role-allowlist-data.sql:21-23, 39-46`), and check 3 confirms. Any `round.roles`
values are recorded (check 4) and dropped. New code refuses `roundType=prejury` and `roles=prejury`.

### 1.4 Migration: V96 adds, V97 drops; each safe to rerun

- MariaDB DDL autocommits, so a failed file leaves partial state.
- **V96 is purely additive.** It adds the new columns and constraints, fills them and verifies them. It keeps
  V76/V77's CHECKs, so its UPDATEs can always read `role` again. When V96 is rerun, its UPDATEs recompute `roles`
  from `role` for every row, and V97 never drops unfilled data.
- **V97 only drops:** the old CHECKs first, then the columns, all `IF EXISTS`.
- Both ship in one deploy. The new code reads only the new columns.

```sql
-- V96__Roles_set_and_round_type.sql (additive; every step rerunnable)
ALTER TABLE user_contest ADD COLUMN IF NOT EXISTS roles
  SET('jury','prescreener','organizer','admin','board','auditor','staff')
  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '' AFTER role;
-- strict for this statement only, keeping the session's other modes (copied from V91:25-26):
--   SET @v96_set = CONCAT('SET STATEMENT sql_mode = ''',
--     CONCAT_WS(',', NULLIF(@@SESSION.sql_mode, ''), 'STRICT_ALL_TABLES'), ''' FOR ');
--   EXECUTE IMMEDIATE CONCAT(@v96_set, '<UPDATE>')
UPDATE user_contest SET roles = IF(BINARY role = 'no', '', role);              -- through the line above
-- verify (V83/V85's EXECUTE IMMEDIATE IF(@bad > 0, 'SIGNAL SQLSTATE ''45000'' …', 'DO 0')):
--   @bad = rows WHERE BINARY role <> 'no' AND (roles = '' OR BINARY roles <> BINARY role)
--   @root_jurors = check 7's rows: root accounts with a 'jury' membership or a live token with 'jury',
--     deleted accounts counted too (as check 7): SIGNAL if > 0
ALTER TABLE user_contest DROP CONSTRAINT IF EXISTS chk_user_contest_juror_alone;
ALTER TABLE user_contest ADD CONSTRAINT chk_user_contest_juror_alone CHECK (NOT (
  FIND_IN_SET('jury', roles) > 0 AND (FIND_IN_SET('organizer', roles) > 0 OR FIND_IN_SET('admin', roles) > 0)));

ALTER TABLE account_token ADD COLUMN IF NOT EXISTS roles SET('jury','prescreener','organizer','admin')
  CHARACTER SET ascii COLLATE ascii_bin NULL AFTER role;
UPDATE account_token SET roles = IF(BINARY role IN ('jury','organizer','admin'), role, NULL);   -- strict, as above
-- 'no'/board/auditor/staff become NULL; SIGNAL if such a token is still live
-- (used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW(3), AccountTokenJdbc.scala:65)
ALTER TABLE account_token DROP CONSTRAINT IF EXISTS chk_account_token_roles;
ALTER TABLE account_token ADD CONSTRAINT chk_account_token_roles CHECK (roles IS NULL OR roles <> '');
ALTER TABLE account_token DROP CONSTRAINT IF EXISTS chk_account_token_juror_alone;
ALTER TABLE account_token ADD CONSTRAINT chk_account_token_juror_alone CHECK (roles IS NULL OR NOT (
  FIND_IN_SET('jury', roles) > 0 AND (FIND_IN_SET('organizer', roles) > 0 OR FIND_IN_SET('admin', roles) > 0)));

ALTER TABLE round ADD COLUMN IF NOT EXISTS round_type VARCHAR(16)
  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'jury';
ALTER TABLE round DROP CONSTRAINT IF EXISTS chk_round_type;
ALTER TABLE round ADD CONSTRAINT chk_round_type CHECK (round_type IN ('jury','prescreening'));
```

```sql
-- V97__Drop_single_role_columns.sql
ALTER TABLE user_contest DROP CONSTRAINT IF EXISTS chk_user_contest_role;      -- V76
ALTER TABLE user_contest DROP COLUMN IF EXISTS role;   -- idx_user_contest_contest becomes (contest_id); fk_uc_contest keeps it
ALTER TABLE account_token DROP CONSTRAINT IF EXISTS chk_account_token_role;    -- V77:69-70
ALTER TABLE account_token DROP COLUMN IF EXISTS role;
ALTER TABLE round DROP COLUMN IF EXISTS roles;          -- never read back (Round.extract)
ALTER TABLE round DROP COLUMN IF EXISTS jury_org_view;  -- unread since 26.11 (the flag's removal; check 6 ran then)
```

- MariaDB removes a dropped column from a multi-column index. `idx_user_contest_contest` (`V61:25`) therefore stays as
  `fk_uc_contest`'s index, and the spec asserts it.
- `ascii_bin` keeps V76's exactness. The app's `require(isContestRole)` stays the first guard for runtime writes, and
  check 1 covers the server's mode for those.
- The "juror alone" CHECK can't see `users.is_root`, a cross-table condition. Root + Juror is guarded in the app
  (§2.1) from B1, the same batch as V96 (§11), and V96 refuses to run while one exists (check 7).
- **Spec `RolesSetMigrationSpec`:**
  - Mapping, and the empty set. **N:** strict mode refuses `Jury`/`prejury`.
  - **N:** both "juror alone" CHECKs refuse `jury,organizer` and `jury,admin`; `prescreener,organizer,board` is
    accepted.
  - A dead `board` token becomes NULL; a live one SIGNALs. **N:** a root juror SIGNALs, and so does a live `jury`
    token of a root account (a deleted root account too).
  - `round_type` defaults to `jury`. **N:** `prejury`/`Prescreening` are refused.
  - **Failure and recovery through `db.Migrations`:** inject a failure into V96 (and separately V97). Assert
    `success = 0`, a refused start, and a rerun after the repair that ends like a clean run.
- Role filters become `FIND_IN_SET(?, roles)` over a contest's rows (`(contest_id)` index). The cost is negligible.

### 1.5 Runbook and rollback (O1: dump restore only)

**If V96 or V97 fails.** On MariaDB, Flyway records the failed migration with `success = 0` and refuses to migrate
until it is repaired. `db.Migrations` has no repair, and with `validateOnMigrate = true` (`Migrations.scala:38`)
neither binary starts. The old binary also refuses **any** V96 or V97 history row: there are no
`ignoreMigrationPatterns` for future versions (`Migrations.scala:32-33, 42, 86`). A DB admin runs these steps (the
maintainer has no DB login):

1. Read the app log for the failing statement or SIGNAL text. Fix the cause in the data: for example revoke a live
   board token, or resolve a root juror (check 7).
2. `SELECT installed_rank, version, success FROM flyway_schema_history WHERE version IN ('96','97');`
3. `DELETE FROM flyway_schema_history WHERE version = '96' AND success = 0;` (or `'97'`). This is what
   `flyway repair` does.
4. Restart the new binary. V96 and V97 rerun safely.

**Rolling back the release, at any point** (also after a V97 failure) means **restoring the pre-deploy dump**. There
is no revert script.
- **Votes, rounds and role changes made since the deploy are lost and must be redone.** Say so plainly in the deploy
  notes and to the contest's admins.
- The dump is taken right before the deploy. So a rollback decided at deploy time, before anyone votes, loses nothing.

This runbook goes into the release's deploy notes, next to the dump step.

## 2. Roles and authorization

### 2.1 The rule "Juror stays alone" (decision J)

| Combination in one contest | Allowed? |
|---|---|
| Juror + Organizer, Juror + Admin | **No** |
| Juror on a **root** account (any contest) | **No.** Root is admin of every contest (`hasContestRole`, `User.scala:109-110`; `isAdmin`, `:112-114`), so root + Juror would be Admin + Juror. |
| Juror + Pre-screener | Yes |
| Pre-screener + Organizer / Admin / root | Yes |
| Any + Board / Audit committee / Staff | Yes |
| Admin + Organizer | Yes (decision 1) |

- **One predicate:** `User.rolesConflict(roles: Set[String], isRoot: Boolean): Option[String]` returns a message key or
  none. It runs at every write, and the DB CHECK (§1.4) is the backstop for the membership part.
- **One lock for every role write.** Each role write (edit, union, acceptance) first locks the user row
  (`User.lockRow`, `User.scala:885`) and reads `is_root` there. That is the row `setRootLocked` locks
  (`SELECT … FOR UPDATE`, `User.scala:792-…`), so a root write and a role write can't race past each other. Today
  `UserContestJdbc.updateRole` (`:81-86`) and `setMembership` (`:73-78`) take no lock; D7-D9 add it. (The lines in
  these three bullets are at PR #280's mirror `1350f0d8`, which has the root changes.)
- **Root, both directions refused.** Refusing only one direction would let the other order create the forbidden state:
  - making someone root while they hold a Juror membership in any contest gives `error.root.juror`. A live invitation
    with `jury` refuses too (`error.root.juror.invited`): it was created before the account was root and would add
    Juror on acceptance. Refusing, not revoking, because a silent revoke would hide from the inviter why their
    invitation stopped working. The message says the fix: untick the pending Juror role (§3.4 revokes it), then
    tick Root;
  - giving a root account a Juror role gives `error.role.juror.root`;
  - acceptance reads `is_root` under its lock (`AccountTokenService.scala:148, 179`) and drops a `jury` role on a root
    account (`invitation_role_dropped reason=juror_root`), whatever was checked when the invitation was made.
- **One form submit, one transaction.** Today the edit form writes root in its own transaction (`setRootLocked`,
  `UserController.scala:514-525`; `WriteTx.write`, `User.scala:793`), then the role (`updateRole`, `:626`). Ticking
  Root and setting Juror in one submit passes the root write (the stored role isn't Juror yet), then the role write
  is refused: root committed without the role. Two transactions in either order leave half a form, because
  `setRootLocked` also refuses for reasons no up-front check covers (`root.needs.verified.email`,
  `root.changed.meanwhile`: a deleted target, the actor no longer root, a concurrent change).
  - **So the whole account update is one `WriteTx.write`:** name, language and sort (`User.updateUser`), email
    (`User.setEmail`, `unlinkIdentities`, the re-invite on an email change, or the email-change link's token;
    `UserController.scala:593-607`; the whole block `:581-610`), wiki account and its identities, roles, `revokePendingRoles`, root and every
    token. Any refusal throws and rolls back all of it, so nothing of the form is written. No order rule is needed.
  - **Lock order:** the body first locks the actor's and the target's `users` rows once, in id order (as
    `setRootLocked` does today, `User.scala:794-797`). A variant `setRootLockedIn(actorId, targetId, root)(implicit
    session)` uses those already-locked rows and takes no lock of its own; `setRootLocked` wraps it (lock, then
    `setRootLockedIn`) for its other callers. Then one `rolesConflict(newRoles, newIsRoot)` on the final state (with
    Root ticked, also the other contests' Juror memberships and live `jury` invitations), then the writes.
  - **Tokens:** `AccountTokenService.create` logs `account_token_created … tokenId=…` inside its body
    (`AccountTokenService.scala:68-71`). An in-transaction variant only inserts and returns `(id, token)`. The token
    value leaves the transaction only as the body's return value, never through outside state, so a deadlock retry
    makes a fresh token and nothing stale escapes.
  - **After the commit only:** every mail (invitation, email-change link), its `account_token_created` line and the
    other log lines (`root_changed`, `oauth_unlinked_by_admin`, the refusal), as WriteTx's rule requires
    (`WriteTx.scala:12-15`). The deadlock retry stays on the combined transaction (the body re-reads everything
    inside it).
  - **Outside it, as today:** another account's password is set last, in its own transaction
    (`setPasswordByAdmin`, `UserController.scala:527-535`); one's own new password keeps its quiet transaction
    (`setPasswordHash`, `User.scala:821-…`, settings that never log the hash), written after the commit.
- **What it removes.** Organizers, admins and root never vote in jury rounds. So jury rounds need no special privacy
  rule beyond today's: a juror sees their own votes, and organizer roles see everything but can't vote. No
  `Own(stats)`, no counts-only statistics, no `currentRoundStat` filter, no admin-stop logging. Today's #278 workaround
  (Juror for the pre-screening round, Organizer again after it) remains a replacement, not a combination.

### 2.2 Predicates

| Predicate | Definition | Replaces |
|---|---|---|
| `Round.roundType`, `isPrescreening`, `voterRole` | From `round_type` | `Round.roles` |
| `User.mayVoteIn(round)` (pure) | `hasRole(round.voterRole) && contestId.contains(round.contestId)` | `User.scala:147-148` |
| Vote endpoints | `VotePermission` = `mayVoteIn`, plus `openToJurors && !RoundUser.isStopped` (`GalleryController.scala:318`, `LargeViewController.scala:64`) and own vote rows only (`VoteJdbc.rate`). The denial names `Set(round.voterRole)` (`Secured.scala:323`). | – |
| `User.VoterRoles` | `{jury, prescreener}`, for role sets and denial texts | `User.scala:167` |
| `User.canViewOrgInfo(round)` | Root, or Admin/Organizer in the round's contest. Its `juryOrgView` branch is gone with the 26.11 removal, so it no longer depends on the round type. | `User.scala:139-142` |
| `GalleryService.votesIn(user, round)` | `user.mayVoteIn(round) && User.isRoundJuror(round.getId, user.getId)` (vote rows count for legacy rounds, `User.scala:533-537`) | new |
| `GalleryService.roundView(user, round): Option[RoundView]` | `Org`, `Peer`, `Own` or **None** (table below). **Computed once per request** and passed to `isNotAuthorized` and the views (or memoized), so `isRoundJuror` runs once. | the vote-visibility sites (§5) |
| `GalleryService.isNotAuthorized` (`:37-56`) | Takes the view and adds `view.isEmpty` **inside** it. That covers the gallery, which calls it directly (`GalleryController.scala:141`: list, by-rate, file list, CSV), and everything behind `mayViewRound` (`:18-19`): `regions` (`GalleryController.scala:286`), the large view (`LargeViewController.scala:123`) and comments (`ImageDiscussionController.scala:54`). | `:37-56` |

`roundView`:

| Round | Who | View |
|---|---|---|
| Jury | `canViewOrgInfo` (Admin, Organizer, root) | `Org`: as today |
| Jury | otherwise, `votesIn` | `Own`: today's juror |
| Pre-screening | `canViewOrgInfo` (Admin, Organizer, root), whether or not they vote | `Org`: statistics and results through that role (decision 3); authors as the role allows |
| Pre-screening | otherwise, `votesIn` | `Peer`: own votes, and each other voter's votes in the round. No `asUserId == 0` (the overall), no statistics, no authors. |
| Any | otherwise | **None.** This covers a member whose role was removed but who keeps a `round_user` row (`Round.activeRounds(user)`, `Round.scala:417-439`, ignores roles), for example in a jury round (R9). (`GalleryService.scala:54`'s `juryOrgView` clause goes with the 26.11 removal.) |

- **Authors** follow today's rule, `canViewOrgInfo` (O4 and O9: no hiding). Pure jurors and pure pre-screeners don't
  see them; the pre-26.11 JSON fix enforces this in the large view's JSON.
- **The access path is unchanged.** A viewer without `canViewOrgInfo` reaches a round only through
  `Round.activeRounds(user)`: active, not distributing, with an active `round_user` row. So `Peer` exists while the
  pre-screening round is open to the voter. A view after it closes is deferred to the V95 stopped-round share (O5),
  which must extend `roundView` and not bypass `isNotAuthorized`.
- **Changing roles mid-round.** Replacing Juror with Pre-screener for someone who has voted in a still-active jury
  round makes `roundView` None there, so they no longer see their own votes in it (admin guide, §8).

## 3. Per-site code changes

These lines are at `c9805b31`. Every site that reads or writes the single role (v1 numbered them D1-D18, Z1-Z14,
U1-U17, V1-V9; v1 is not kept) changes mechanically: `role: String` becomes `roles: Set[String]`, so the compiler
lists those sites. The entries below are the ones that change more than that, or are new.

### 3.1 Data and roles

| # | Site | Change |
|---|---|---|
| D1/D2 | `UserContestJdbc.scala:7-26, 36-41` | `UserContest(roles: Set[String])`; `active` unchanged (`:25`) |
| D7-D9 | `UserContestJdbc.scala:63-85` | `createMembership(roles)`, `addRoles` (union), `setRoles`, each locking the user row (`User.lockRow`), reading `is_root` there and calling `rolesConflict` first (§2.1) |
| D10 | `User.scala:85-86` `contestRole` | Removed. `UserService.scala:69`, U4 and U11 use `contestRoles`. `RoundService.scala:125` and `Round.scala:639` write `round.voterRole` from B1 (always `jury` until B4). |
| D11 | `User.scala:164-213` | `PRESCREENER_ROLE` in `ContestRoles`, `ACCESS_ROLES`, `VoterRoles` and `COI_ROLES` (O3) (B2). **Offered** in `FORM_ROLES`, `IMPORT_ROLES` and `ShareableRoles` only from B5. `AccessFormRoles`, `CoiFormRoles`. `rolesConflict` (§2.1). `NO_ROLE` is a legacy POST value meaning the empty set. |
| D12a | One admin accessor, `UserContestJdbc.adminContestIds(userId)` | Used by `AccountTokenService.scala:346-349`, `ShareLinkService.scala:67-68` and `UserController.scala:151, 203`, so that a later scope grant table (comparison §4.3) merges in at one place. Root stays account-level. |
| D13 | `User.scala:463-487` `contestMembers` | `FIND_IN_SET` OR; the exact filter (`:485-486`) stays |
| D16 | `User.scala:892-900` | `loadVoters(contestId, role, ids)` replaces both `loadJurors`; `Round.availableJurors` becomes `Round.candidates` |
| D17 | `CoiMembers.scala:16-17, 37-45` | `FIND_IN_SET` OR over `COI_ROLES`; `Member.roles: Set` |
| D17a | `CoiMembers.describe` in `round_image.dq_detail` (VARCHAR 255, `RoundConflicts.scala:139`, `RoundImageJdbc.scala:54`) and the job's `coiTitles` (`RoundDistributionHandler.scala:52-53, 156`) | Store codes (`account — jury,board`, account truncated to fit) and translate on display; old rows parse the same way |
| D18 | `AccountTokenJdbc` | `roles: Set[String]`, NULL when empty; `role IS NOT NULL` becomes `roles IS NOT NULL` (`:161, 191, 205`) |
| R1 | `Round.scala:28, 298-330, 342-360` | `roundType` read and written; `updateRound` (`:399-403`) keeps the type immutable |
| X1 | Not found by the compiler | Raw SQL: `GatlingExternalTarget.scala:45` (`uc.role LIKE`), `RoleAssignmentSpec.scala:71`. Test callers: `RoundSpec.scala:38`, `UserDbSpec.scala:60`, `GatlingDbSetup.scala:58, 66`. Gatling's cache key hashes the migrations (`GatlingDbCache.scala:36-55`): bump `FixtureVersion` only if `GatlingDbSetup.load` writes differently. |
| X2 | `prescreener` in POSTs before B5 | `isContestRole` would accept it from B2 (`UserController.scala:772`, `User.mayAssignRoles`). Until B5 these use `OfferedRoles` (= `ContestRoles` minus `prescreener`). `EditRound.scala:198` accepts only `jury` from B1 (U20). |

### 3.2 Authorization, voting, start page

| # | Site | Change |
|---|---|---|
| Z7 | `User.scala:147-148`, `Secured.scala:320-324` | `mayVoteIn` per §2.2 |
| Z8/Z9/Z13/Z14 | `AccountTokenService.scala:346-349`, `ShareLinkService.scala:67-68`, `UserController.scala:151, 203, 804` | Through D12a, or `roles.contains` |
| Z10 | `ShareLinkService.scala:36, 86-87` | `ShareableRoles = {jury, prescreener, organizer}` (B5). `TargetRole` as today for any admin role. **New refusal:** a link whose pending roles ∪ the membership's roles break "juror alone" (`error.role.juror.alone`). |
| Z12 | `LoginResults.scala:18-40` start page (O6) | (1) an active round the user votes in (`Round.activeRounds(u).filter(u.mayVoteIn)`) → its gallery; (2) organizer → statistics; (3) a voter with no Admin/Organizer/root → `no.round.yet`; (4) root → contests; (5) admin → users; (6) the denial. Jurors are always alone now, so the changes concern pre-screeners only: (a) Organizer/Admin + Pre-screener with an active pre-screening round → its gallery; (b) a pure pre-screener behaves like a juror; (c) Admin or root + Pre-screener without an active round → users or contests, not `no.round.yet`. Existing users see no change. |
| P1 | `Secured.scala:450` `ViewerRoles` | Add `prescreener`. It is also the default permission of every `withAuth*()`: `GalleryController.scala:101, 115, 282`, `LargeViewController.scala:35, 45`, `ImageDiscussionController.scala:33`, `LoginController.scala:39, 288`, `MonumentController.scala:18, 23`, `UserController.scala:295, 803`. Nothing is widened: each checks again. |
| P2 | `GalleryService.scala:48` | Add `PRESCREENER_ROLE` |
| P3 | `ImageDiscussionController.scala:36` `commenterRoles` | Add `prescreener` |
| P4 | The 5 vote-control sites: `GalleryTiles.scala:13`, `TileStars.scala:14`, `keyboardNav.scala.html:15`, `large.scala.html:21`, `navigationButtons.scala.html:101` | `user.mayVoteIn(round)` |
| P5 | `navbar.scala.html:119` | `user.mayVoteIn(round)` |
| — | `RoundController.scala:499-560` (statistics pages) | Unchanged. A pure pre-screener isn't in their `rolePermission` (`:501, 539`); an Organizer/Admin pre-screener is `Org`. |

### 3.3 Rounds and the picker (O10, O11)

| # | Site | Change |
|---|---|---|
| U17 | `RoundService.scala:125`, `Round.scala:639` | `round_user.role` = `round.voterRole` (B1) |
| U18 | `RoundService.scala:68` | `loadVoters(contestId, round.voterRole, jurorIds)`. **Any id that isn't a candidate is refused, and nothing is created.** The message names each person and why: `error.round.voter.not.candidate`, for example "Ilya can't vote in a pre-screening round: not a pre-screener of this contest". This covers a stale page or a crafted request (O11). |
| U19 | `RoundController.scala:100-101, 132, 286` | **The list shows only those who can vote in the round's type** (O11): jurors for a jury round, pre-screeners for a pre-screening round. Pre-ticked: candidates who are neither organizer nor admin (`isAdmin(Some(contestId))`, `User.scala:112-114`, so root isn't either). For a jury round that is everyone, since jurors are alone. An existing round keeps `findRoundJurors`. |
| U20 | `EditRound.scala:198, 231, 256, 316`; `editRound.scala.html:65` | B4: the hidden `roles` becomes `roundType` (allowlist; a legacy `roles=jury` maps to `jury`; `prejury` refused). B1 interim: `roles=jury` maps to Jury, and nothing else is accepted. |
| U21 | `editRound.scala.html:93-97` | Labels "Name (Organizer, Pre-screener)"; a legend per type; the not-ticked help in pre-screening rounds |
| U22 | `rounds.scala.html` | **Two buttons** (O10): "New jury round" and "New pre-screening round" (`?roundType=prescreening`). The form shows the type fixed. A type column. |

### 3.4 User administration: every write applies "juror alone"

| Write path | Site | Rule |
|---|---|---|
| Edit user | `UserController.scala:354-…` (`updateUser`) | **Sets the whole set, not a union:** unticking a role removes it. The new set and the Root box are checked and written in one transaction with root, before the rest of the form, as root is today (§2.1). Otherwise the form returns with `error.role.juror.alone` / `error.role.juror.root` / `error.root.juror` and nothing is written. |
| Edit user: removal | `updateUser`, `AccountTokenJdbc.revokePendingRoles` | **Removing a role is immediate**, also on an account that needs an invitation, and revokes the pending roles for that contest (`revokePendingRoles`, as today at `UserController.scala:575`). |
| Edit user: pending invitation | `updateUser`, `UserService.invite` / `reinvite` | **A changed set while an invitation is pending re-invites** with the new pending set (`invite` revokes the older one, `UserService.scala:180`); `reinvite` carries the pending set. Unticking every pending role just revokes. `invite` is split: the token is created in the edit's transaction (§2.1), the mail is sent after the commit. |
| Users page add row (O7) | `UserService.addToContest` (`:59-…`) | **The same union as import:** new set = current ∪ listed, checked as below |
| Import (decision 5) | `UserService.addToContest`, `UserController.importUsers` | Per row: current ∪ listed. The check also counts the contest's **pending** roles (live invitations): current ∪ pending ∪ listed. Otherwise Juror imported onto a pending Organizer (or the reverse) would pass, and the later acceptance would silently drop the invited role. If it breaks the rule (Juror onto an Organizer, Organizer onto a Juror, Juror onto root), **that row is refused** with a message and counted (`users.roles.refused`, with names). Otherwise `RolesAdded(member, added, accessRestored)`. |
| Access restored (O8) | the same | Allowed. **Reported separately: "Access restored: N" with the names** (`users.access.restored`). |
| Invitation creation | `UserService.invite`, `AccountTokenService` | The invitation's roles ∪ the membership **after this edit's removals** must pass, else refused (`error.role.juror.alone`). Example: Juror → Organizer on an account that needs an invitation removes `jury` now and invites for `{organizer}`; checked against the stored `{jury}` it would be refused. |
| Invitation acceptance (decision 6) | `AccountTokenService.scala:263-275` | Union, never a removal. If the union now breaks the rule (the membership changed since the invitation, or the account became root), the conflicting roles are not added. The rest is applied, the drop is logged (`invitation_role_dropped reason=juror_alone` / `juror_root`) and the welcome page shows `invitation.role.not.added`. |
| Share links | `ShareLinkService` (Z10) | Refused when the pending roles ∪ the membership break the rule |
| Root checkbox | `UserController` (root-scope), `User.setRootLocked` | Refused while the account holds Juror in any contest (`error.root.juror`) or has a live invitation with `jury` (`error.root.juror.invited`), checked under its lock (§2.1) |
| Role write on a root account | D7-D9 | Juror refused (`error.role.juror.root`) |
| DB | §1.4 | Backstop CHECKs on `user_contest.roles` and `account_token.roles` |

`addedMessage` (`UserController.scala:634-649`) gains `users.roles.added`, `users.access.restored` and
`users.roles.refused`.

## 4. Round types and the round form

| Rule | Where it's enforced |
|---|---|
| The type is fixed at creation | `updateRound` writes only name, average rule and worst mark (`Round.scala:399-403`); the form shows the type read-only |
| Pre-screening ⇒ Yes/No | `roundMapping.verifying("error.round.prescreening.yesno", …)` (`EditRound.scala:216-223`), and a `require` in `RoundService.submit`. Comments-only (rates 0) is refused too. |
| Voters match the type | U18, U19 |
| API | `/api` is mounted (`conf/routes:172-173`; CLAUDE.md's "not served" is outdated). Its contest endpoints are root-only (`Api.scala:52`) and it has no round endpoint. |
| Previous rounds | A jury round may build on a pre-screening round; `sameRateType` (`EditRound.scala:218-222`) compares ratings only |

Existing data: every round becomes `jury`. Legacy rounds without `round_user` rows keep working through `isRoundJuror`
and `User.distributionJurors` (`User.scala:582-584`). The rating label "Yes/No" comes with 26.11 (O12).

## 5. Visibility sites (pre-screening only)

Jury rounds keep today's behaviour. `Org` and `Own` are today's `canViewOrgInfo` true and false, because no voter
holds an organizer role there (§2.1). The changes are for `Peer` and `None`.

| Site (`c9805b31`) | Change |
|---|---|
| `GalleryService.scala:37-56` `isNotAuthorized` | adds `view.isEmpty` (§2.2) |
| `GalleryService.scala:31-35` `viewAs` | `Peer`: any round voter, never `0` |
| `GalleryController.scala:200-202` all jurors (list, CSV) | `Org` only (as today) |
| `GalleryController.scala:281-290` `regions` JSON | as `viewAs`; `asUserId=0` refused for `Peer` |
| `LargeViewController.scala:157` per-file votes | `Org` or `Peer` |
| `ImageDiscussionController.scala:55` commenting on others' files | `Org` or `Peer` |
| `navbar.scala.html:131-139` juror menu | Today it needs `asUserId != self` and `canViewOrgInfo` (`:131`). The menu shows only for `Org` and `Peer`; `Own` (a pure juror) gets none. For those two the `asUserId != self` clause is lifted, so a voting Organizer/Admin pre-screener (`Org`) and a pure pre-screener (`Peer`) get it on their own gallery too. `Org`: as today, with "all jurors". `Peer`: the round's voters, self first, no "all jurors". |
| `navbar.scala.html:102, 105`, `roundStat.scala.html:9, 12`, `RoundController.scala:513, 521, 546` | Unchanged: `canViewOrgInfo`, which `Peer` lacks |
| Author sites (`GalleryController.scala:216`, `large.scala.html:31`, the large-view JSON) | Unchanged (`canViewOrgInfo`; the JSON per the pre-26.11 fix). N spec: `Peer` gets no `author`. |

- **Comments (O13)** stay shared per file and contest (`Comment.scala:90-96`, `LargeViewController.scala:185`). The
  guides say so: pre-screeners, organizers and later jurors read each other's comments.
- **The V95 stopped-round share** applies to jury rounds only. A view after close for pre-screeners belongs there
  (O5).
- **`secure-coding.md:603`:** add "Juror stays alone", the Peer rule for pre-screening rounds, and "authors follow
  `canViewOrgInfo`".

## 6. UI and messages

- **Edit user / add row / import:** two checkbox fieldsets. "Role in WLX Jury": Juror, Pre-screener, Organizer,
  Admin. "Conflict of interest": Board, Audit committee, Staff. Root is its own checkbox. The help text states the
  rule (`role.access.help`).
- **Users page:** columns "Role in WLX Jury" and "Conflict of interest". Invitations show "Pre-screener, Organizer
  (pending)".
- **Round form:** type fixed; only candidates of that type; legend and not-ticked help per type.
- **Rounds list:** two "New … round" buttons and a type column.

Drafts; all need native review, fr and ru especially:

| Key | en | fr | ru | uk |
|---|---|---|---|---|
| `role.access.label` | Role in WLX Jury | Rôle dans WLX Jury | Роль в WLX Jury | Роль у WLX Jury |
| `coi.role.label` | Conflict of interest | Conflit d''intérêts | Конфликт интересов | Конфлікт інтересів |
| `role.no` (changed from "No role") | No access | Aucun accès | Без доступа | Без доступу |
| `coi.person.as` | {0} ({1}) | {0} ({1}) | {0} ({1}) | {0} ({1}) |
| `role.prescreener` | Pre-screener | Présélectionneur | Член предварительного жюри | Член попереднього журі |
| `role.prescreener.help` | Votes yes/no in the pre-screening rounds they are assigned to, and sees the other pre-screeners' votes there. Sees no statistics or results unless also an Organizer or Admin. | Vote oui/non dans les tours de présélection auxquels il est affecté et y voit les votes des autres. Ne voit ni statistiques ni résultats, sauf s''il est aussi organisateur ou administrateur. | Голосует «да/нет» в назначенных ему раундах предварительного жюри и видит там голоса остальных. Статистику и результаты видит, только если он также организатор или администратор. | Голосує «так/ні» в призначених йому раундах попереднього журі й бачить там голоси інших. Статистику й результати бачить, лише якщо він також організатор або адміністратор. |
| `role.access.help` | A juror only votes: Juror can't be combined with Organizer, Admin or Root. Pre-screener can be combined with Organizer or Admin. | Un juré ne fait que voter : Juré ne peut pas être combiné avec Organisateur, Administrateur ou Super-administrateur. Présélectionneur peut l''être avec Organisateur ou Administrateur. | Член жюри только голосует: эту роль нельзя совмещать с ролями организатора, администратора или суперадминистратора. Член предварительного жюри может быть также организатором или администратором. | Член журі лише голосує: цю роль не можна поєднувати з ролями організатора, адміністратора чи суперадміністратора. Член попереднього журі може бути також організатором або адміністратором. |
| `error.role.juror.alone` | {0}: a juror can''t also be an organizer or admin in the same contest. Keep one of the roles. | {0} : un juré ne peut pas être aussi organisateur ou administrateur dans le même concours. Gardez un seul de ces rôles. | {0}: член жюри не может быть также организатором или администратором в том же конкурсе. Оставьте одну из ролей. | {0}: член журі не може бути також організатором чи адміністратором у тому самому конкурсі. Залиште одну з ролей. |
| `error.role.juror.root` | {0} is a root user (every contest), so they can''t be a juror. | {0} est super-administrateur (tous les concours) et ne peut donc pas être juré. | {0} — суперадминистратор (все конкурсы), поэтому не может быть членом жюри. | {0} — суперадміністратор (усі конкурси), тому не може бути членом журі. |
| `error.root.juror` | {0} is a juror in {1}, so they can''t be made root. Remove the Juror role there first. | {0} est juré dans {1} et ne peut donc pas devenir super-administrateur. Retirez d''abord ce rôle. | {0} — член жюри в {1}, поэтому не может стать суперадминистратором. Сначала снимите эту роль. | {0} — член журі в {1}, тому не може стати суперадміністратором. Спершу зніміть цю роль. |
| `error.root.juror.invited` | {0} has a pending invitation as a juror in {1}, so they can''t be made root. Untick the pending Juror role there first. | {0} a une invitation en attente comme juré dans {1} et ne peut donc pas devenir super-administrateur. Décochez d''abord ce rôle en attente. | У {0} есть неподтверждённое приглашение членом жюри в {1}, поэтому нельзя сделать его суперадминистратором. Сначала снимите там эту ожидающую роль. | {0} має непідтверджене запрошення членом журі в {1}, тому його не можна зробити суперадміністратором. Спершу зніміть там цю роль, що очікує. |
| `invitation.role.not.added` | The {0} role in {1} wasn''t added: a juror can''t also be an organizer or admin. | Le rôle {0} dans {1} n''a pas été ajouté : un juré ne peut pas être aussi organisateur ou administrateur. | Роль «{0}» в {1} не добавлена: член жюри не может быть также организатором или администратором. | Роль «{0}» у {1} не додано: член журі не може бути також організатором чи адміністратором. |
| `users.roles.refused` | Not changed (a juror can''t also be an organizer or admin): {0}. | Non modifiés (un juré ne peut pas être aussi organisateur ou administrateur) : {0}. | Не изменены (член жюри не может быть также организатором или администратором): {0}. | Не змінено (член журі не може бути також організатором чи адміністратором): {0}. |
| `users.roles.added` | {0,choice,0#No member|1#1 member|1<{0,number,integer} members} got more roles. | {0,choice,0#Aucun membre n''a|1#1 membre a|1<{0,number,integer} membres ont} reçu des rôles en plus. | Дополнительные роли получили: {0,number,integer}. | Додаткові ролі отримали: {0,number,integer}. |
| `users.access.restored` (O8) | Access restored: {0} ({1}). | Accès rétabli : {0} ({1}). | Доступ восстановлен: {0} ({1}). | Доступ відновлено: {0} ({1}). |
| `round.type`, `.jury`, `.prescreening` | Round type / Jury round / Pre-screening round | Type de tour / Tour du jury / Tour de présélection | Тип раунда / Раунд жюри / Раунд предварительного жюри | Тип раунду / Раунд журі / Раунд попереднього журі |
| `round.type.prescreening.help` | Pre-screeners vote yes/no and see each other's votes. Its selected files can feed a jury round. | Les présélectionneurs votent oui/non et voient les votes des autres. Ses fichiers retenus peuvent alimenter un tour du jury. | Члены предварительного жюри голосуют «да/нет» и видят голоса друг друга. Отобранные файлы можно передать в раунд жюри. | Члени попереднього журі голосують «так/ні» й бачать голоси одне одного. Відібрані файли можна передати в раунд журі. |
| `round.new.jury`, `round.new.prescreening` | New jury round / New pre-screening round | Nouveau tour du jury / Nouveau tour de présélection | Новый раунд жюри / Новый раунд предварительного жюри | Новий раунд журі / Новий раунд попереднього журі |
| `round.voters.jury`, `round.voters.prescreening` | Jurors of this round / Pre-screeners of this round | Jurés de ce tour / Présélectionneurs de ce tour | Члены жюри этого раунда / Члены предварительного жюри этого раунда | Члени журі цього раунду / Члени попереднього журі цього раунду |
| `round.voters.not.ticked` | Pre-screeners who are also organizers or admins are not ticked. Tick them to make them vote in this round. | Les présélectionneurs qui sont aussi organisateurs ou administrateurs ne sont pas cochés. Cochez-les pour qu''ils votent dans ce tour. | Члены предварительного жюри, которые также организаторы или администраторы, не отмечены. Отметьте их, чтобы они голосовали в этом раунде. | Члени попереднього журі, які також є організаторами чи адміністраторами, не позначені. Позначте їх, щоб вони голосували в цьому раунді. |
| `error.round.prescreening.yesno` | A pre-screening round is a yes/no round. | Un tour de présélection est un tour oui/non. | Раунд предварительного жюри — это раунд «да/нет». | Раунд попереднього журі — це раунд «так/ні». |
| `error.round.voter.not.candidate` (O11) | Nothing was created. {0} can''t vote in a {1}: not a {2} of this contest. | Rien n''a été créé. {0} ne peut pas voter dans un {1} : pas {2} de ce concours. | Ничего не создано. {0} не может голосовать в раунде «{1}»: не является «{2}» этого конкурса. | Нічого не створено. {0} не може голосувати в раунді «{1}»: не є «{2}» цього конкурсу. |

The keys use `''` for an apostrophe, as MessageFormat requires. The first four rows come from the earlier COI UI
plan (not kept); `role.board`, `role.auditor`, `role.staff` and `root.flag` keep their keys and texts. Register
`round.type.*` in `MessagesCompletenessSpec`.

## 7. Invitations, import and share links: summary

See §3.4 for every write. **Import, the add row and invitation acceptance** use union and never remove a role. Edit
user sets the whole set: a removal is immediate and revokes pending roles, and a change while an invitation is pending
re-invites. No role write touches root's flag (only the Root box does), and every write applies "juror alone".

## 8. Docs to update (sections only)

- `docs/user/admin-guide.md`:
  - §Roles (`:37`: the two groups, Pre-screener, **Juror stays alone**, root)
  - §The users page (`:64`)
  - §Adding a person (`:83`)
  - §Adding many people (`:102`: union, refused rows, access restored with names)
  - §Changing a person (`:116`: replacing Juror with Pre-screener mid-round hides that person's own votes in a
    still-active jury round)
  - §Invitations / §Sharing a link (`:135`, `:167`)
  - §Kinds of rounds (`:354`)
  - §Creating a round (`:393`: two buttons, candidates, pre-ticks)
  - §What jurors see (`:634`)
  - §Leaving conflicts of interest out (`:645`: pre-screeners count)
  - comments are shared (O13)
- `docs/user/organizer-guide.md`:
  - §Who can do what (`:17`)
  - §Where to start (`:37`)
  - §Kinds of rounds (`:52`)
  - §Statistics (`:70`)
  - §What a conflict of interest is (`:191`)
  - comments (O13)
- `docs/user/juror-guide.md`: §What you see after signing in (`:114`); §Kinds of rounds (`:150`): a "Pre-screening
  rounds" subsection; comments (`:360`).
- **26.11:** the workaround in admin-guide §Kinds of rounds (Juror during the pre-screening round, Organizer again after
  it).
- The release's what's-new page and deploy notes: the runbook, and "a rollback restores the dump; votes since the deploy
  are lost".
- `CLAUDE.md`: "User roles" (SET, Pre-screener, juror alone); "REST API" (`/api` is mounted).
- `secure-coding.md:603`.
- Roadmap "Migration numbers".

## 9. Tests

Negative cases are marked **N**.

| Area | Spec | Cases |
|---|---|---|
| Migration | `RolesSetMigrationSpec` | §1.4, failure and repair through `db.Migrations` included |
| Juror alone | new `JurorAloneSpec` | For each write path (edit, add row, import, invitation creation, acceptance, share link, root checkbox, role write on root): **N:** Juror + Organizer, Juror + Admin, Juror on root, root for a juror. Each refusal shows its message and changes nothing. Allowed: Juror + Pre-screener, Pre-screener + Organizer/Admin/root, Juror + Board, Admin + Organizer. Acceptance: the conflicting role is dropped and logged, the rest applied; a `jury` invitation accepted after the account became root drops `jury` (`juror_root`). **One submit (one transaction):** **N:** Root ticked + role Juror writes neither; **N:** Juror → Organizer + Root on an account with a password but no verified email (`root.needs.verified.email`) writes nothing: Juror kept, pending roles not revoked, no invitation or mail; allowed: Juror → Organizer + Root ticked ends as root + Organizer. **N:** Root while a live `jury` invitation exists (`error.root.juror.invited`). Edit: unticking a role removes it at once and revokes pending roles; a changed set with a pending invitation re-invites; Juror → Organizer on an account that needs an invitation removes `jury` now and invites `{organizer}` (not refused). Concurrency: making someone root and giving them Juror at the same time can't both succeed (row lock). |
| Roles | `RoleValuesSpec`, `RoleAssignmentSpec`, `UserContestSpec` | Repeatable `roles`, legacy single value, `no` = empty set. **N:** `prejury`, junk, `root` in `roles`; a crafted `prescreener` before B5 (X2) |
| Votes | `MutationAuthorizationSpec`, `VoteSpec` | A pre-screener votes in an assigned pre-screening round; Juror + Pre-screener votes in both types. **N:** a pre-screener in a jury round; a juror in a pre-screening round even with seeded vote rows; organizer, admin, root; a pre-screener made Organizer only; a paused `round_user`. #278's case holds. |
| Vote controls | view specs (P4) | **N:** no buttons for an organizer, or for a juror in a pre-screening round |
| Peer view | `PrescreeningViewSpec` | Another voter's gallery and per-file votes; the juror menu on their own gallery, for a `Peer` and for a voting Organizer + Pre-screener (`Org`, with "all jurors"). **N:** `asUserId=0` on the gallery, by-rate, file list, CSV and `regions` JSON; the statistics pages; the author box and large-view JSON `author`; a non-voter `asUserId` (404); an unassigned pre-screener; a removed pre-screener with a `round_user` row, and a pure pre-screener with a `round_user` row in a jury round, each on the gallery, file-list, CSV, large view and `regions` routes; after the round closes (O5); another contest. Organizer + Pre-screener: `Org` with statistics. |
| Jury rounds unchanged | `OwnVotesSpec` | Still passes; no organizer can be a round voter |
| Round form | `RoundSpec`, `RoundControllerSpec`, `EditRoundSpec` | Only candidates of the type are listed; pre-ticks (Organizer/Admin/root pre-screeners not ticked). **N:** a submitted non-candidate refuses everything and names the person and why (O11); pre-screening with rates 5 or 0; `roundType=prejury`/junk; B1-B3 `roles` other than `jury`; a type change on edit ignored. |
| Start page | `LoginControllerSpec` | Z12's cases (a)-(c); existing users unchanged. **N:** a paused voter isn't sent to that gallery. |
| Import, invitations, links | `ImportUsersSpec`, `AccountInvitationSpec`, `ShareLinkSpec`, `MultiContestUserSpec` | Union; counts for added, refused (with names) and access restored (with names); never removes; root untouched; tokens never `''`. **N:** an inviter no longer admin; a link for Admin+X. |
| COI | `CoiMembersSpec`, `CoiRoundSpec`, `RoundConflictsAuthorizationSpec` | Pre-screener counted (O3); Juror + Board once with both labels; `dq_detail` codes. **N:** `''` not counted. |
| Browser | `pages.spec.js`, `docs-screenshots.spec.js` | Both round types' forms, edit user (rule text), import |

Per CLAUDE.md: compile first, then the affected specs per batch, and one full suite at the release boundary.

## 10. Size

| Part | Days |
|---|---|
| S core (data, write paths, invitations, two groups, two columns, 4 languages) | 3.5 |
| V96/V97 with CHECKs, failure and repair spec via `db.Migrations`, runbook, production checks (no revert script) | 1 |
| "Juror stays alone" at every write, root both ways (row lock on role writes, live `jury` invitations, acceptance on root, one check per submit), removal and re-invite rules, messages, `JurorAloneSpec` | 1.25 |
| Pre-screener role and votes (P1-P5), start page | 0.5 |
| Round type, form, picker, named refusal | 1 |
| `roundView` (Org/Peer/Own/None, once per request), Peer and its menu, negative specs (no jury-round blinding, no statistics templates) | 1 |
| Import union and three counts with names, admin accessor, `dq_detail` codes | 0.75 |
| Docs, screenshots, review cycles | 1.25 |
| **Total** | **≈ 10.25 days (L).** Down from 10.5: −0.5 without a revert script, −1 without jury-round blinding and the statistics templates, +1.25 for "juror alone" (+0.25 of it for the second review's root and edit cases). Not included: "assign around" (+1.5, later, O3). |

## 11. Implementation sequence

Each batch is a stack PR with its own review and affected specs. All of them ship in one release (decision 4).
**Nothing offers several roles or Pre-screener in the UI before B5**, and B5 brings the app-side Juror + Organizer/Admin
checks together with that UI; until then one role per membership can't break them, and the DB CHECK is there from B1.
**Root + Juror is different:** the DB CHECK can't see `is_root`, and today's Root box plus a role can produce it. So its
small app check lands in B1, next to V96's check, and no batch allows it.

| Batch | Content | Gate |
|---|---|---|
| B0 (26.11) | Workaround docs. Outside this plan, their own pre-26.11 PRs: the large-view JSON fix, "Yes/No" (O12), the `juryOrgView` removal (code only). | docs review |
| B1 | V96/V97 (including the "juror alone" CHECKs), SETs, `Round.roundType`, `contestRole` removed, `round_user.role = voterRole`, the interim `roles=jury` (U20), D12a, `dq_detail` codes, X1, the runbook. **Root + Juror in the app (§2.1):** role writes lock the user row and refuse Juror on root; `setRootLocked` refuses a Juror membership or live `jury` token; acceptance drops `jury` on root; the edit form's roles and root in one transaction (`setRootLockedIn`); its messages ×4 (`error.role.juror.root`, `error.root.juror`, `error.root.juror.invited`) | migration spec (failure and repair), DAO specs, `JurorAloneSpec`'s root cases |
| B2 | `PRESCREENER_ROLE` (not offered, X2), `mayVoteIn`, `VoterRoles`, `ViewerRoles`, P2-P5, start page | vote and start-page specs |
| B3 | `roundView` and `isNotAuthorized`, the Peer sites and menu | `PrescreeningViewSpec`, `OwnVotesSpec` |
| B4 | Round types: two buttons, type-only candidates, named refusal, the yes/no rule | round specs, axe |
| B5 | Admin write paths with "juror alone" everywhere (§3.4; root from B1), removal and re-invite rules, checkbox groups (Pre-screener offered), import union and counts, invitations, share links, two columns, messages ×4 | `JurorAloneSpec`, role, import, invitation and link specs |
| B6 | Docs, screenshots, CLAUDE.md, secure-coding rule; full suite once | docs review, full suite |

## 12. Decisions (all answered by the maintainer, 2026-11-10)

No open decisions remain.

- **O1: dump restore only.** V96 adds and V97 drops; the runbook covers a failure going forward (§1.5). A rollback at any
  point, including after a V97 failure, restores the pre-deploy dump. Votes made since the deploy are lost and must be
  redone. No revert script.
- **O2: run all seven checks before the deploy; block on 2, 3 and 7.** The account has no DB login, so a DB admin runs:
  1. `SELECT @@GLOBAL.sql_mode;` (for runtime writes; the migration sets its own)
  2. `SELECT role, (used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW(3)) AS live, COUNT(*) FROM account_token WHERE BINARY role IN ('no','board','auditor','staff') GROUP BY role, live;` (no live rows expected) **blocks**
  3. `SELECT role, HEX(role), COUNT(*) FROM user_contest WHERE BINARY role NOT IN ('no','jury','organizer','admin','board','auditor','staff') GROUP BY role, HEX(role);` (none: no `prejury`, V76 has run) **blocks**
  4. `SELECT roles, COUNT(*) FROM round GROUP BY roles;` (record any value other than `jury`)
  5. `SELECT uc.contest_id, COUNT(DISTINCT uc.user_id) FROM user_contest uc JOIN round r ON r.contest_id = uc.contest_id JOIN vote s ON s.round_id = r.id AND s.jury_id = uc.user_id WHERE uc.role IN ('organizer','admin') GROUP BY uc.contest_id;` (organizers with votes, for the record)
  6. **Before the 26.11 deploy** (it removes `juryOrgView` from the code):
     `SELECT id, contest_id, active FROM round WHERE jury_org_view ORDER BY id;` None expected, since nothing in the UI
     sets the flag. Any round found loses its jurors' organizer view with 26.11. Record the list for the release
     notes; V97 then drops the column.
  7. **Root jurors** (memberships and live tokens; deleted accounts counted too, exactly as V96's `@root_jurors`) **blocks**:
     `SELECT u.id, u.email, uc.contest_id, 'membership' AS kind FROM users u JOIN user_contest uc ON uc.user_id = u.id WHERE u.is_root AND BINARY uc.role = 'jury' UNION ALL SELECT u.id, u.email, t.contest_id, CONCAT('token ', t.id) FROM users u JOIN account_token t ON t.user_id = u.id WHERE u.is_root AND BINARY t.role = 'jury' AND t.used_at IS NULL AND t.revoked_at IS NULL AND t.expires_at > NOW(3);`
     - Deleted accounts count because the rule is on stored rows and a DB admin may restore an account; no app code sets or
       clears `users.deleted_at`, so none are expected.
     - Admin or organizer + Juror in one contest is impossible today: one row per (user, contest), `PRIMARY KEY`, V61.
     - **If a membership is found:** for each account, the maintainer either sets that contest's membership to
       "No access" (votes are kept, as #278 keeps them; it also revokes the contest's pending roles) or removes root.
       **A live token:** the DB admin revokes it (`UPDATE account_token SET revoked_at = NOW(3) WHERE id = …`). A
       deleted account's rows are fixed the same way by the DB admin in SQL. This must be done before the deploy:
       V96 refuses to run while one remains (§1.4), so the rule holds from the first start.
- **O3 = yes now:** Pre-screener is in `COI_ROLES`. "Assign around" comes later as a round option if a contest asks.
- **O4 = no**, and **O9 = no author hiding:** authors follow `canViewOrgInfo` ("it's there on Commons").
- **O5 = defer** a pre-screener's view after the round closes to the V95 stopped-round share. It must extend
  `roundView`.
- **O6 = as planned,** simplified by "juror alone": voting round first, then today's order. Only pre-screeners see a
  change (Z12).
- **O7:** the users page add row uses the same union as import.
- **O8 = allowed:** an import or add row may give access back to a "No access" member. It is reported separately:
  "Access restored: N" with the names.
- **O10:** two buttons on the rounds list; the type is fixed on the form.
- **O11:** the form lists only people who can vote in that type. Anyone else the server receives (a stale page, a crafted
  request) is refused with a message naming who and why, and nothing is created.
- **O12 = in 26.11,** its own stack PR ("Yes/No", the 2 screenshot renames, admin-guide `:475`). Not in this plan.
- **O13:** comments stay shared per file and contest, documented in the guides.
- **O14 → "Juror stays alone"** (decision J, §2.1). It replaces the earlier privacy rule for admins and organizers who
  vote. Removed with it:
  - `Own(stats)` and the counts-only statistics templates;
  - the `currentRoundStat` statistics filter;
  - the logging of a voting admin's stop;
  - the "admin blinding is advisory" note;
  - the voting-first start page for admins and organizers in jury rounds.
