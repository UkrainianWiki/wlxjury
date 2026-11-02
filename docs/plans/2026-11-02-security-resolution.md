# Security resolution plan (2026-11-02)

Resolves the items of [the consolidated report](../security/2026-11-02-rereview-report.md)
(IDs N*, O*, P* as there). Batches are ordered by risk. The workflow is the follow-ups plan's
("Running the fixes with agents" in `docs/plans/2026-11-01-open-followups.md`); what differs is
listed under "Running the batches".

## Batches at a glance

| Batch | Items | Risk | Effort | Owner |
|---|---|---|---|---|
| S1 | N1 hotfix, backport, production data check | High | high | app + ops |
| S2 | Account integrity: O2, N2 (current password), N4, N7, N5, P14, P18, P20, P7 | Medium | high | app |
| S3 | Sessions: O3, N2 (invalidation, tokens), P16 rotation procedure | Medium | high | app |
| S4 | Throttling: P1, N6, P13 (failed tokens) | Medium | medium | app (or infra) |
| S5 | Output and log hygiene: N3, N8, N9, N11/P12 | Low | medium | app |
| S6 | App hardening leftovers: P5, P6, P8 (code part), P9 (P7 done in S2) | Low | medium | app |
| S7 | Headers and CSP: P4, P11, P3, P17 | Low | medium | app |
| S8 | Infra: O1, P2, P15, `useSSL` | Medium (O1) | — (infra plan) | infra |
| S9 | Ops: P10, P8 count, secret rotation run, production checks | Low | — (user) | ops |
| S10 | Info and dependencies: N10, P13 (revocation), P16 docs, P19 | Info | low–medium | app |

S1 goes first and alone. S2 and S3 change the same files (`LoginController`, `UserController`,
`Secured`, `User`) and run one after the other. S4–S7 and S10 can follow in any order. S8 runs
on the infra track; O1 must be done before the followups branch is deployed.

## S1: N1 hotfix (role allowlist; root only from `users.is_root`)

**Items:** N1, and the `USER_ID_<n>` value that the same path stores.

**Design** (defence in depth: form, controller, DAO, reader):

1. **One allowlist.** `User.ContestRoles: Set[String]` = `FORM_ROLES` (no, jury, organizer,
   admin, board, auditor, staff), plus any legacy role production still holds that the code reads
   (e.g. `prejury`: decide from the S1 data check, decision 1). `root` and `USER_ID_*` are never
   in it.
2. **Form.** `editUserForm`'s `"roles"` gets `verifying("error.invalid", r =>
   User.ContestRoles.contains(r) || r == User.ROOT_ROLE)`: exact match, no trimming, no case
   folding. A bad value is a 400 with the form; nothing is written.
3. **Controller.** `updateAccount` and the `id = 0` branch of `saveUser` accept `root` only from
   a root editor, and then only through `User.setRoot` / `isRoot` (never into `user_contest`).
   Any other non-allowlisted value is refused before any write (also covering a caller that skips
   the form). `UserService.addToContest` takes the role only if it is in `ContestRoles`, else
   refuses (`IllegalArgumentException` mapped to 400).
4. **DAO.** `UserContestJdbc.createMembership` and `updateRole` `require(ContestRoles.contains(role))`.
   `User.create` creates a membership only with an allowlisted role.
5. **Reader.** `UserContest.roles` stops splitting on commas: it is `Set(role)` if `role` is in
   `ContestRoles`, else empty (the membership is then inactive), with one WARN line on
   `security.roles` (`accountId`, `contestId`, no value). `User.inContest` additionally removes
   `root` and `USER_ID_*` from `membership.roles`. Root comes only from `users.is_root`
   (`User.extract`), as today.
6. **Data.** A migration (next free number at merge time; api-auth took V75, and B9–B11 add none
   so far) that, with `BINARY` comparison (the table is latin1 with a PAD SPACE, case-insensitive
   collation: `'root '` and `'Root'` must not slip through), copies every row whose role is not
   exactly one allowlisted value into `v<N>_roles_reset` (user, contest, old role, time) and sets
   the role to `no`. Then, if decision 2 says so, a `CHECK (role IN (...))` constraint
   (MariaDB 10.2+ enforces it; adding a role later needs a migration).

**Files:** `app/db/scalikejdbc/User.scala`, `UserContestJdbc.scala`,
`app/controllers/UserController.scala`, `app/services/UserService.scala`,
`conf/db/migration/default/V<N>__User_contest_role_allowlist.sql`, CLAUDE.md (role sentence in
"Authentication & Authorization").

**Specs (negative first):** a new `controllers.RoleAssignmentSpec` (truncation, like
`MultiContestUserSpec`): an admin's `/users/save` with each of `jury,root`, ` root`, `root `,
`ROOT`, `admin,root`, `USER_ID_1`, an unknown word, and an empty string is a 400 and changes
neither `user_contest` nor `users.is_root`, for (a) the admin's own id, (b) another member,
(c) `id = 0` with a new email, (d) `id = 0` with an existing account's email; an organizer's and
a juror's role change is ignored as today; root setting `root` sets `is_root` and writes no
`user_contest` row; each allowlisted role still saves. `UserContestSpec`: a stored `jury,root`
or `root` row yields no role and no root (and the membership is inactive); `createMembership` /
`updateRole` refuse `root`. A migration spec on a fixture with bad rows (moved to the audit table,
set to `no`; good rows unchanged). Rerun `MultiContestUserSpec`, `UserControllerSpec`,
`SecuredSpec`, `MutationAuthorizationSpec`, `LoginControllerSpec`.

**Checks:** `Test/compile`; the specs above via `scripts/agent-check.py`.

**Backport to the deployed release.** Production ran `0.13-authz-6fa5b56fb021` on 2026-10-27
(confirm with `readlink /opt/wlxjury/current`). The followups branch is not deployable as is
(O1), so a hotfix is needed:

- branch `ukwikidev/hotfix-role-allowlist` from the deployed commit (`6fa5b56f`, or whatever
  `current` names); apply items 1–5 only (no migration: the data check below replaces it on the
  server, and the reader change makes bad rows harmless); `RoleAssignmentSpec` and
  `UserContestSpec` on that branch;
- build, deploy with `wlxjury-release` as usual (the user runs the sudo steps), smoke-test over
  ssh on `127.0.0.1:9000`;
- merge the hotfix branch into `ukwikidev/followups` (or cherry-pick), then add the migration
  there.

**Production data check (read-only; the user runs it with a SELECT-only DB login):**

```sql
-- 1. Membership rows that are not exactly one known role (comma lists, root, spaces, case, markers)
SELECT user_id, contest_id, role, HEX(role) AS role_hex
FROM user_contest
WHERE BINARY role NOT IN ('no','jury','organizer','admin','board','auditor','staff','prejury');

-- 2. Every root account, to compare with the accounts that should be root
SELECT id, created_at, is_root FROM users WHERE is_root = 1 ORDER BY id;

-- 3. The roles in use, to settle the allowlist (decision 1)
SELECT BINARY role AS role, COUNT(*) FROM user_contest GROUP BY BINARY role ORDER BY 2 DESC;
```

Any row of query 1 that contains `root`, and any unexpected account in query 2, is an incident:
keep the rows, take the account's root away and its memberships to `no` only after recording
them, and look for that account's `POST /users/save` in the Apache access logs of all jury
vhosts. V62 made every pre-V62 `users.roles` containing `root` into `is_root = 1`, so query 2
also covers exploitation under the 2024 `master`. Restores to a pre-0.13 release must not be
used until this is resolved (that code is vulnerable too).

**Decisions (user):**

1. The allowlist: `FORM_ROLES` only, or also legacy roles found by query 3 (e.g. `prejury`).
2. A DB `CHECK` constraint on `user_contest.role`, or code checks only.
3. Hotfix deploy now, or wait for the next release (only if queries 1–2 are clean and every
   contest admin is trusted; not recommended).
4. Bad rows: reset to `no` with an audit table (recommended), or fail the migration and fix by
   hand.

**Dependencies:** none on B9 (ImageDbNew), B10 (benchmark) or B11 (ranks); none of them touches
these files. Take the migration number at merge time.

### Batch S1 results (2026-11-02)

**Decisions taken (user):** allowlist = `FORM_ROLES` only (`prejury` is read nowhere in the
code, only in tests, which now use `no`); a binary-exact `CHECK` constraint; hotfix now; the
legacy `jury1` / `jury-` rows (production: 19 + 3, jurors disabled by hand) become `no`
directly, **no audit table**; any other bad value fails the migration for a person to look at.

**As implemented** (branch `ukwikidev/security-s1`, from `ukwikidev/security-rereview`):

1. `User.ContestRoles = FORM_ROLES.toSet`, `User.isContestRole`, and
   `User.mayAssignRoles(editor, roles)` (each a contest role, or root from a root editor).
2. Form: `"roles" -> optional(text.verifying("error.invalid", isContestRole || == root))`.
3. Controller: `saveUser` refuses (400 with the form, WARN `security.roles` with account and
   target id, no value) any role `mayAssignRoles` rejects before any branch writes; the `id = 0`
   branch also maps the service's `RoleNotAssignable` (only it) to that 400; `updateAccount` writes
   `user_contest` only for `isContestRole`. Root still goes only through `User.setRoot`.
   `UserService.addToContest` throws `UserService.RoleNotAssignable` unless `mayAssignRoles(creator, formUser.roles)`.
4. DAO: `createMembership`, `updateRole` and `User.create` (before inserting the user) `require`
   an allowlisted role.
5. Reader: `UserContest.roles` is `Set(role)` if allowlisted, else empty (inactive membership,
   one WARN on `security.roles` per row read, ids only); `User.inContest` intersects with
   `ContestRoles`; `User.contestMembers` with a role filter also keeps only exact matches (the
   SQL `IN` is case- and trailing-space-insensitive).
6. `V76__User_contest_role_allowlist.sql`: `UPDATE user_contest SET role = 'no' WHERE BINARY
   role IN ('jury1', 'jury-')`, then `DROP CONSTRAINT IF EXISTS` +
   `ADD CONSTRAINT chk_user_contest_role CHECK (BINARY role IN ('no', 'jury', 'organizer',
   'admin', 'board', 'auditor', 'staff'))`. Verified on the Testcontainers MariaDB 10.6:
   the constraint refuses `root`, `Root`, `root `, ` root`, `jury,root`, `Jury`, `jury `,
   `admin `, `USER_ID_1`, `jury1`, `jury-`, `prejury`, `superuser` and `''` on insert and on
   update; MariaDB validates existing rows on `ADD CONSTRAINT` (error 4025), so any other
   value fails the migration and stays as it was (no explicit guard needed).

**Deviation:** an empty `roles` value is "no role given" (Play's `optional` cannot tell it from
the absent field a non-admin's form sends), not a 400; it writes nothing for an admin (spec'd).
Behaviour change: an admin's `id = 0` save with `root` used to create a juror silently; it is
now a 400 creating nothing (`MultiContestUserSpec` updated).

**Specs:** `controllers.RoleAssignmentSpec` (15: admin's own id, another member, `id = 0` new
and existing email, a root user's form, empty role, each allowlisted role, another contest,
organizer/juror, root sets `is_root` without a membership, root refused non-roles,
`addToContest`, a stored `jury,root` row: no root, inactive, 403), `UserContestSpec` (+DAO
`require`s, `User.create`), `UserContestRolesSpec` (reader, `inContest`),
`db.UserContestRoleMigrationSpec` (constraint, reset, failure on other values, rerun),
plus `UserControllerSpec`, `UserDbSpec`, `MultiContestUserSpec`, `SecuredSpec`,
`MutationAuthorizationSpec`, `LoginControllerSpec`, `PasswordStorageSpec`,
`ConvertToInnoDbMigrationSpec` (runs V76 after V53), `ProductionConfigSpec`, `RoundSpec`,
`CoiMembersSpec`, `ImageDistributorSpec`, `MigrationNamingSpec`.

**Backport:** see `docs/security/2026-11-02-role-allowlist-hotfix.md` on
`ukwikidev/hotfix-role-allowlist` (from `6fa5b56f`): items 1–5 with the deployed form's four
roles (`no`, `jury`, `organizer`, `admin`; a subset of V76's list), no migration; the data
script `docs/security/2026-11-02-role-allowlist-data.sql` there does V76's `UPDATE` by hand.

**Merging the hotfix into the main line** (reviewer's recommendation; not done yet): S1 is a
superset of the hotfix, and a plain merge conflicts (and duplicates `object UserContest`), so
```bash
git merge -s ours ukwikidev/hotfix-role-allowlist
git checkout ukwikidev/hotfix-role-allowlist -- docs/security/2026-11-02-role-allowlist-*
git commit
```
keeping this branch's code (`FORM_ROLES` with seven roles) and taking the hotfix's runbook docs.

**V76 release runbook:** before deploying the release that carries V76, run query 3 above, or
the hotfix data script's final SELECT (`docs/security/2026-11-02-role-allowlist-data.sql`, step
4): it must list nothing (it catches e.g. `prejury`, which query 1 did not report). V76's
`UPDATE` commits before the `CHECK` is added, so a bad row fails the migration after that
commit: Flyway records a failed V76, the app does not start, and readiness stays down. Then run
`sudo wlxjury-release restore OPERATION` (the release changes the schema, so a dump was taken),
fix the rows by hand, and deploy again.

**S1 review fixes** (independent review: no path to root or an un-allowlisted role in either
tree; the backport code is safe to deploy as is):

| Finding | Fix | Commit |
|---|---|---|
| Medium: the runbook lacked the `is_root` audit; role queries alone don't prove N1 unused (an exploiter can reset their own membership; Apache logs give only time and IP of `POST /users/save`) | Data script step 1 and deploy note step 4: `SELECT id, created_at, is_root FROM users WHERE is_root = 1`, compared with the known ids 3, 651, 1167, 1307, 1752; any other id is an incident | hotfix `729e0cf5` |
| Low: `COMMIT` could run unchecked if the file were piped into `mysql` | One statement at a time, never piped; `COMMIT` a separate step after "22 rows affected", else `ROLLBACK` | hotfix `729e0cf5` |
| Low: smoke test used a real member and `jury,root` first | A throwaway member; `roles=root` first (expect 400 and a `security.roles` "refused a role that is not assignable" line), `jury,root` only then; check its `is_root` and role afterwards | hotfix `729e0cf5` |
| Nit: deploy note's file names differed from the built ZIP | Uses `wlxjury-0.13-authz-rolefix-303c80e0a6cb.zip` and its SHA-256; says the ZIP is built from `303c80e0` and later docs commits don't change it | hotfix `729e0cf5` |
| Nit: `saveUser` mapped every `IllegalArgumentException` from `createNewUser` to a role refusal | `UserService.RoleNotAssignable` (an `IllegalArgumentException`) thrown by `addToContest`, the only one caught; `RoleAssignmentSpec` checks another one reaches the error handler (`RoleAssignmentSpec`, `UserControllerSpec`, `MultiContestUserSpec`: 37 passed) | `10bb190d` |
| Low: no V76 release runbook | "V76 release runbook" above | this commit |
| Merge advice | "Merging the hotfix into the main line" above (`-s ours`, then the hotfix's docs) | this commit |

## S2: Account integrity

**Items:** O2, N2 (current password), N4, N7, N5, P14, P18, P20, P7 (added by the user); and the reviewer's out-of-scope
note (an account with no memberships and no work history becomes password-editable by any admin
who adds it by email).

**Decisions (user, 2026-11-02):**

1. **Invitations only.** Open sign-up ends (`/signup` is gone: 404). Accounts are created by an
   admin/organizer adding a person (or by root); a new account gets no password and an invitation
   link by mail; following it proves the email and sets the password. An empty database is set
   up without a public form (P20).
2. **Email verification.** Email ownership is proven by accepting an invitation or a password
   link, and on any email change: the new address must confirm a link before it takes effect.
   Changing one's own email or password needs the current password (N2).
3. **Unverified flags.** `users.email_verified_at` and `users.wiki_account_verified_at` (NULL =
   unverified). How existing accounts are marked: see the migration below.
4. **O2.** An organizer's add of an email that matches an existing unverified account does not
   hand the role to whoever holds that account's password: it sends an invitation; accepting it
   sets a new password and invalidates the old one, the account's sessions and its API tokens.
   A match by wiki account never attaches a role to an account whose wiki account is unverified.
   Admins see the unverified state in the users list and the edit page.
5. **Postponed (not in S2):** resolving duplicate wiki accounts (a later contests rework with an
   admin UI for merging accounts); users editing their own wiki account (the WIP OAuth login,
   which will verify it; until then users can't change their own wiki account; admins can, and it
   stays unverified); the mail sender address and a greeting cap (N5): only the existing mail path
   is kept working, for invitations.
6. **N7.** With sign-up gone, invitation acceptance, the password link and the email change must
   not reveal whether an email has an account.

**Design:**

- **Access roles need a proven holder.** An access role (`jury`, `organizer`, `admin`) is attached
  to an existing account directly only if nobody but the email's owner can sign in to it:
  `User.mayTakeAccessRole` = root, or `email_verified_at` set, or no password at all (an invitee
  who hasn't accepted yet, a wiki-only placeholder). Otherwise:
  - an add by email (the users page, "Import users") records a **pending invitation**: no
    membership yet; the invitation (an `account_token` row with the contest and role) is mailed
    to the account's address; the users page lists pending invitations; accepting it creates the
    membership;
  - an add by wiki account only is **not added** (counted and said so: add the person by email);
  - a role change in the edit form to an access role becomes a pending invitation (the old role
    stays until it is accepted); without an email it is refused.
  Roles without access (`no`, `board`, `auditor`, `staff`) join directly: they hand out nothing.
- **New accounts** (an add whose email or wiki account matches nobody): created with the
  membership as before, **without a password** (`users.password` NULL: nobody can sign in), and,
  for an access role and an email, an invitation. The contest's greeting, when on, is the
  invitation's text, with the link in place of `{{Password}}` (appended if the template lacks
  it); otherwise a standard invitation text. A verified account joining gets the greeting as
  before ("use your existing password").
- **Tokens** (`account_token`, V77): purposes `invitation`, `password_reset`, `email_change`;
  32 random bytes base64url, stored only as SHA-256 (as API tokens), single use (`used_at`),
  revocable (`revoked_at`), expiring (invitation and password link 7 days, email change 24
  hours), bound to the account and to the address it was sent to (refused if the account's email
  changed since). Never logged: log lines name a token by id, an account by id.
- **Acceptance** (`GET /invitation/:token` shows the form, `POST /invitation` with the CSRF
  token): one transaction, the token row locked: new bcrypt hash, `email_verified_at` set,
  `auth_version` + 1 (every session of the account ends), all its API tokens revoked, the
  account's live invitations sent to the same address applied (memberships) and marked used,
  other unused links revoked. The browser is then signed in. Any refused link (unknown, used,
  expired, revoked, another address, a deleted account) shows the same page, 404, no-store,
  `Referrer-Policy: no-referrer`.
- **Sessions:** `users.auth_version` (V77, default 0); a session carries `authVersion` (absent =
  0, for sessions from before the deploy) and `Secured.sessionUser` refuses a mismatch. This is the
  part of S3's design S2 needs (acceptance, email change); S3 adds `accountId`, `issuedAt`, the
  idle limit and bumps on password changes.
- **Own profile (N2):** a new password or a new email needs the current password (400, nothing
  changed, otherwise). A new email is not written: a confirmation link goes to it
  (`/email/confirm/:token`, a page with a POST button); confirming sets it, verified, and bumps
  `auth_version`. The answer is the same whether the address is taken or not (no mail is sent
  if it is; N7). One's own wiki account is read-only (decision 5).
- **Other accounts:** nobody sets another account's password any more (admins and root: 400);
  root's "reset password" mails a password link instead (it used to set a random password and
  say it was mailed, which it never was). An email change of another account: immediate (and a
  new invitation) for an invitee without a password; otherwise root's goes through the
  confirmation link and an admin's is refused (ask the person; this also closes the reviewer's
  note: an admin can no longer take over an account added by email). A wiki account set by an
  admin is validated, normalised (MediaWiki rules: `User:` prefix dropped, underscores as
  spaces, first letter upper case, no `#<>[]|{}/@:=`, at most 85 characters), refused if another
  account has it, and unverified.
- **P7 (added to S2 by the user, option (a)):** root's "Reset password" on the edit page (it
  stored a random password, mailed nothing and said it had, locking the user out) mails a
  single-use, expiring "set your password" link (purpose `password_reset`, the same tokens and
  page as invitations, CSRF on the form); the old password works until the link is used; root never
  sees a password; the flash says a link was sent; log lines carry account ids only. It stays
  root-only; contest admins get the equivalent, scoped like invitations, only as "send the
  invitation again" for an unverified member or pending invitee of their own contest.
- **Re-sending an invitation** (`POST /users/:id/invite?contestId=`): an admin of that contest,
  for a member or a pending invitee of it with an unverified email; never a root account; the
  previous unused invitations into that contest are revoked, the pending role carried over.
- **Bootstrap (P20):** `/setup` exists only while `users` is empty and `wlxjury.setup.token`
  (`WLXJURY_SETUP_TOKEN`, at least 32 characters) is set; otherwise 404. Its form takes the token
  (compared in constant time), an email and a password; the first root is created under a
  database lock (`GET_LOCK`), so of two concurrent submits one creates it and the other gets the
  404. The token is never logged. The login page of an empty database says to use the setup.
- **N4:** links to Commons on the users page URL-encode the wiki account; sign-in and sessions by
  wiki account need exactly one (non-deleted) account with it (an ambiguous name signs in no one:
  use the email). **P14/P18:** `byUserName` ignores deleted accounts and looks up the trimmed,
  lowercased email.
- **N5:** unchanged (postponed): invitation mails go through the existing `SendMail` path,
  `From` the inviter as before. A mail that fails is logged (account id) and counted in the
  page's message; the invitation can be sent again.

**Migration V77** (the highest on this branch is V76; take the next free number at merge time):
`users.email_verified_at`, `users.wiki_account_verified_at` (TIMESTAMP(3) NULL),
`users.auth_version` (INT UNSIGNED NOT NULL DEFAULT 0); table `account_token`. **Existing
accounts:** root accounts with an email get `email_verified_at = NOW(3)` (the operators); every
other account stays unverified, and no wiki account is verified (nothing has verified one).
This is the safer default: the first time an existing account gets an access role in a contest
(added again next year, or a role change), its owner accepts an invitation (one click, a new
password) before the role applies; its current memberships keep working meanwhile. The
alternative (open decision 1 below) would mark accounts with memberships or votes as verified.
Reversal: the columns and the table can be dropped; nothing existing is rewritten.

**Files:** `LoginController`, `UserController`, `UserService`, `AccountTokenService` (new),
`AccountTokenJdbc` (new), `User`, `Secured`, `ImageProxyController`, `ErrorHandler`,
`CsrfErrorHandler`, views (`login`, `editUser`, `users`, new `invitation`, `emailConfirm`,
`setup`, `accountLinkInvalid`), messages (four files), routes, V77, `CLAUDE.md`,
`docs/deployment.md`.

**Specs:** `AccountInvitationSpec` (new: sign-up gone; tokens hashed, single use, expiring,
bound to account and address; acceptance takes an unverified account over: old password,
sessions and API tokens end; pending roles applied; same page for every refused link; the
O2 cases by email and by wiki account; role edits; re-send scoped to the admin's contest, never
root; mail failure counted; log lines without emails or tokens), `OwnAccountSpec` (new: N2,
email confirmation, N7, own wiki account read-only, others' passwords refused, admins' email
changes), `SetupSpec` (new: P20), `db.AccountVerificationMigrationSpec` (new), plus
`LoginControllerSpec`, `PasswordStorageSpec`, `UserControllerSpec`, `UserDbSpec`,
`MultiContestUserSpec`, `RoleAssignmentSpec`, `SecuredSpec`, `MutationAuthorizationSpec`,
`ApiTokenPageSpec`, `ProductionConfigSpec`, `views.*`.

**Open decisions (user):**

1. Existing accounts: keep all non-root accounts unverified (implemented), or mark those with
   memberships or votes as verified, so that returning jurors join new contests without an
   invitation (accepts any past pre-claim as genuine).
2. Ambiguous wiki-account sign-in: refused (implemented); run the read-only count
   `SELECT wiki_account, COUNT(*) FROM users WHERE deleted_at IS NULL AND wiki_account IS NOT
   NULL GROUP BY wiki_account HAVING COUNT(*) > 1` on production before the deploy to see who
   would have to sign in by email.
3. Notify the old address of an email change (not done).
4. Invitation and link lifetimes (7 days / 24 hours, constants).

**Dependencies:** after S1 (same files). Mail delivery needs the server's SMTP settings (ops);
the invitations make it necessary for every new juror.

### Batch S2 results (2026-11-02)

**Branch** `ukwikidev/security-s2` (from `ukwikidev/security-s1`): plan `56119b64`, implementation
`fd0fa793`, these results. Migration **V77** (`V77__Account_verification_and_tokens.sql`; take the
next free number at merge time). Not reviewed yet (S2 gets a fresh independent reviewer).

**As implemented:** the design above, with these details and deviations:

- `User.mayTakeAccessRole` (root, verified email, or no password) gates every way an access role
  reaches an existing account: `UserService.addToContest` (add row, import) and the edit form's
  role change. Results: `NewAccount(user, invitationSent)`, `JoinedContest`, `AlreadyMember`,
  `Invited(user, sent)` (pending), `WikiAccountUnverified` (not added); the users page's flash
  counts each, and invitations that could not be mailed.
- A pending invitation is an `account_token` row with `contest_id` and `role`; no membership exists
  until acceptance (`UserContestJdbc.setMembership`, an upsert, so a role-change invitation updates
  the existing membership). A direct role change revokes pending role invitations into that contest
  (`AccountTokenJdbc.revokePendingRoles`), so an old link cannot bring back a replaced role.
- New accounts are created without a password even if the form or a caller passes one (the old
  `createAccount` honoured an organizer-chosen password). A new root account made by root's form
  gets a password link (root accounts are never invited).
- The greeting: when a contest uses one, the invitation is the greeting with the link in place of
  `{{Password}}` (appended if missing); otherwise a standard invitation text (`invitation.body`).
- Sessions: `Secured.AuthVersion` (`auth_version`) in the session, checked in `sessionUser` (also
  `ErrorHandler`/`CsrfErrorHandler` now go through `sessionUserOf`); the thumbnail proxy's viewer
  cache is keyed by name and version (`ImageProxyController.viewerKey`). Sign-in, acceptance and
  setup redirect to `/` (POST-redirect-GET) with the new version.
- Acceptance, root's password link and the email confirmation run their statements in one
  transaction with the token row locked (`SELECT ... FOR UPDATE`); the acceptance transaction uses
  `User.quietTx` (no SQL log of the password hash).
- `/setup`: `User.createFirstRoot` runs `GET_LOCK` / count / insert / `RELEASE_LOCK` on one
  auto-commit connection (the insert is committed before the lock is released; a transaction
  would release it before commit).
- `resetPassword` (P7) and `reinvite` run on the blocking pool (they send mail).
- Wiki account names: validated and normalised on every admin change and on adds/imports, so an
  import of `User:Board_two` now stores `Board two` (`CoiAuthorizationSpec` updated); stored legacy
  values are left as they are. Ambiguity uses the column's case-insensitive collation.
- Tests: `play.mailer.mock = yes` in `test/resources/application.conf`; specs read links from
  `controllers.RecordingMail` (a recording `SMTPOrWikiMail`); `account_token` is in the truncated
  tables.
- Not done (deviations): no notice to the old address on an email change (open decision 3); own
  password change does not bump `auth_version` (S3); unused message keys of the old sign-up
  (`signup.signup`, `signup.email.taken`, `sign.up.now`, `not.a.member`) are removed from all four
  files.

**Specs** (one sbt JVM at a time, through `scripts/agent-check.py`; no full suite):

| Run | Specs | Result | Log |
|---|---|---|---|
| 1 | the four new specs | 37: 6 failed (login page in tests without a CSRF token; acceptance answered the start page, 200/403, instead of a redirect; per-test app not truncated in `SetupSpec`), fixed | `target/checks/20261102-142756-27208.log` |
| 2 | new specs, `LoginControllerSpec`, `UserControllerSpec`, `UserDbSpec`, `MultiContestUserSpec`, `RoleAssignmentSpec`, `SecuredSpec`, `MutationAuthorizationSpec`, `PasswordStorageSpec`, `ApiTokenPageSpec`, `ProductionConfigSpec`, `views.*` (incl. `MessagesCompletenessSpec`), `UserContestRoleMigrationSpec`, `MigrationNamingSpec`, `ConvertToInnoDbMigrationSpec`, `ThemeSwitchSpec`, `SignOutAndSwitchSpec`, `ImageProxyFileCheckSpec`, `ApiSpec`, `CoiAuthorizationSpec`, `ErrorHandlerSpec`, `ApiTokenJdbcSpec`, `UserPasswordSpec`, `UserContestSpec`, `UserContestRolesSpec` | 483: 478 passed; `SecuredSpec` 4 errors (its mocked session had no `auth_version` stub) and `CoiAuthorizationSpec` 1 failure (the normalised wiki name), both test-only fixes | `target/checks/20261102-143104-17144.log` |
| 3 | `SecuredSpec`, `CoiAuthorizationSpec` | 35 passed | `target/checks/20261102-143701-15928.log` |

New specs: `controllers.AccountInvitationSpec` (17), `controllers.OwnAccountSpec` (13),
`controllers.SetupSpec` (4), `db.AccountVerificationMigrationSpec` (3). Updated:
`LoginControllerSpec` (sign-up tests replaced: empty-DB login page, auth version in the session),
`PasswordStorageSpec` (sign-up paths replaced by invitation acceptance; own changes with the current
password), `MultiContestUserSpec` and `RoleAssignmentSpec` (members verified; an admin can no longer
set another's password), `SecuredSpec`, `CoiAuthorizationSpec`.

**Open decisions (user):** as listed above: (1) how existing accounts are marked (all non-root
unverified, implemented); (2) ambiguous wiki sign-in refused (run the duplicate count before the
deploy); (3) notice to the old address; (4) link lifetimes. Plus: (5) whether contest admins should
also get a "reset password" for verified members of their own contest (now root only; admins have
"send the invitation again" for unverified members, which is equivalent there).

## S3: Sessions

**Items:** O3, N2 (second half), P16 (rotation procedure).

**Design:** the session holds `accountId`, `authVersion` and `issuedAt` (not the email or wiki
name: this also ends N4's session switch). `users.auth_version` (migration, default 0);
`Secured.sessionUserOf` refuses a session whose version differs or whose `issuedAt` is older than
the absolute limit. Sign-out with "all devices", a password change or reset, an email change and
root's "end sessions" bump the version. Idle timeout: Play's cookie `maxAge` plus a `lastSeen`
refreshed at most every few minutes (avoids a new cookie on every request). On a password change
or reset, the user's API tokens are revoked or listed for revocation (decision 3). Old-format
sessions after the deploy: accepted once and upgraded, or signed out (decision 4). Document key
rotation in `docs/deployment.md` (a new key ends every session).

**Specs:** `SecuredSpec` (stale version refused; past the absolute limit refused; idle past the
limit refused; an old-format cookie handled per decision 4); `SignOutAndSwitchSpec` (sign-out
everywhere ends a second session); `PasswordStorageSpec` (after a password change the other
session is refused); `ApiTokenPageSpec` (tokens per decision 3); `LoginControllerSpec`.
CSRF-enabled router tests stay green.

**Decisions (user):** (1) absolute lifetime (e.g. 30 days, or 12 hours during judging); (2) idle
timeout (e.g. 8 hours); (3) revoke API tokens on password change, or only offer; (4) old cookies at
deploy.

**Dependencies:** after S2. Gatling simulations sign in through the form; check that
`test/gatling` still signs in (no run unless the session code changes per-request cost).

## S4: Throttling

**Items:** P1, N6, P13 (failed tokens).

**Design:** decision 1 between (a) in the app: a bounded in-memory limiter keyed by client
address (Play's resolved remote address behind the trusted proxy) and by account, with
exponential back-off and a 429 page/JSON with `Retry-After`, applied before the bcrypt turn; the
single-instance assumption is documented already (`docs/deployment.md:227`); or (b) fail2ban on a
dedicated `security.auth` log line plus Apache. Same limiter for unknown/invalid API tokens. N6:
set `play.filters.csrf.header.protectHeaders = null` so that `/auth` and `/signup` need the token
too (check that the login page always sets it), or check `Origin`/`Sec-Fetch-Site` there.

**Specs:** `LoginControllerSpec` (the N+1st failure from one address is a 429; a different
account from the same address also; success resets; the limiter is bounded); `ApiSpec` (failed
tokens limited); `CsrfTestApp` router spec: a cookieless `POST /auth` without a token is refused.

**Decisions (user):** (1) app vs. fail2ban; (2) limits (e.g. 10 failures per 15 minutes per
address, 5 per account).

**Dependencies:** after S3 if both touch `LoginController` in the same week; otherwise free.

## S5: Output and log hygiene

**Items:** N3, N8, N9, N11/P12.

**Design:** N3: `Csv.writeRow` prefixes a cell that starts with `= + - @`, tab or CR with `'`
(user text only; numbers the app writes are unchanged). N8: build the caption with
`HtmlFormat.escape(file.title)` inside the attribute, or create the link in `gallery-init.js` with
`textContent`. N9: `job.error` stores a message key plus a log reference; the exception text goes
only to the log. N11: slow-query warnings without bound values (a custom
`queryCompletionListener`/`queryFailureListener`, or values masked); `loggingSQLErrors` likewise.

**Specs:** `CsvSpec` (each leading character prefixed, in header and rows); `GallerySpec` (a title
with `<` renders as text in the caption); `JobControllerSpec` (a failing job's JSON shows a key
and a reference, never the exception text); a logging spec with a list appender (a slow statement
logs no bound value).

**Dependencies:** **after B11 (ranks)** if B11 changes `Csv.scala` or the CSV columns; after B9
merges (it touches `RoundList`/`ImageDbNew`, not these files, but avoid parallel `Test/compile`
on one branch).

## S6: App hardening leftovers

**Items:** P5, P6, P8 (code), P9. (P7 is done in S2: see below.)

**Design:** P5: snap `px` to the configured thumbnail widths (redirect others) and count misses
per user. P6: decision 1 (should round deactivation stop voting?); if yes, both vote actions check
`round_user.active`. P7: **done in S2** (user decision (a)): root's button mails a single-use,
expiring set-password link (S2's tokens); the old password works until it is used. P8: after a grace period (decision 3), a migration replaces 40-hex hashes with an
unusable value; affected users reset through S2's invitation path. P9: refuse an original-file URL
whose host isn't `upload.wikimedia.org` (or `wikiBaseUrl`'s) before fetching.

**Specs:** `LocalImageCacheServiceSpec`/image proxy spec (off-set width redirected; foreign host
refused); `MutationAuthorizationSpec` (deactivated juror's vote refused, if decided);
`UserControllerSpec` (reset-password behaviour); `PasswordStorageSpec` (a retired legacy hash
can't sign in).

**Dependencies:** P5 after B10's benchmark run (do not change the proxy while it measures).

## S7: Headers and CSP

**Items:** P4, P11, P3, P17.

**Design:** one `SecurityHeadersSpec` on real responses (a page, a JSON error, `/api/docs`, an
image) asserting CSP, `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy` and a new
`Permissions-Policy` (camera, microphone, geolocation, payment off); move the CSP to
`play.filters.csp` with the same policy (`ContentSecurityPolicySpec` and `InlineScriptHashesSpec`
follow); P3 as a longer track: template styles to classes, then unitegallery's inline styles, then
drop `'unsafe-inline'` from `style-src`; narrow `img-src` to the Commons hosts if the local cache
and proxy allow. P17: `scripts/ui-shots.py shoot --start-server --csp` on `/api/docs` once.

**Decisions (user):** whether to take P3 now (screenshots in light, dark, auto) or later.

**Dependencies:** the CSP never gets wider (invariant). Screenshots need Chrome; one heavy run at a
time.

## S8: Infra (for the infra plan)

**Items:** O1, P2 (HSTS), P15 (logback), `useSSL` if the DB is ever remote.

Not done here: they belong to `docs/plans/2026-11-01-infra-in-repo.md` (branch
`ukwikidev/infra-in-repo`). Mapping:

- **O1:** until the roles move, the user adds B5's block (`wlxjury.allowedHosts =
  ${WLXJURY_ALLOWED_HOSTS}`, `play.filters.hosts.allowed = []`, the three `secure` lines) to
  `wlxjury-infra`'s `production.conf.j2` and `WLXJURY_ALLOWED_HOSTS` to `runtime.env`, **before**
  deploying the followups branch. After the infra plan's Step 3, `ProductionConfigSpec` reads the
  template itself, which ends the drift (see the practices plan's parity guard).
- **P2:** `Header always set Strict-Transport-Security "max-age=31536000"` in the SSL vhost
  template (a later `includeSubDomains` only after checking every subdomain); add it to the infra
  plan's Step 2 or 3 container test (`test_check_vhosts.py`).
- **P15:** the infra plan's Step 4 (`conf/logback-production.xml` with `ApiTokenService`, `coi`).
- **`useSSL`:** only if `WLXJURY_DB_HOST` is not local; record the choice in the template.

**Decision (user):** patch `wlxjury-infra` now for O1/P2, or wait for the infra plan's Steps 2–4
(then the followups branch waits too).

## S9: Ops

**Items:** P10, P8 (count), key rotation, the S1 data check.

- P10: delete `C:\scala\wlxjury-bench-work\proddata\*.sql.gz` and the seven `wlxjury-bench-*`
  volumes once B10 no longer needs them (decision: keep an anonymised fixture instead).
- P8: `SELECT COUNT(*) FROM users WHERE password REGEXP '^[0-9a-f]{40}$';` (read-only) before S6
  decides the grace period.
- Key rotation after S3 is deployed, following the new procedure, if any session may have leaked
  (decision).
- S1's queries, and again after S1's migration runs.

## S10: Info and dependencies

**Items:** N10, P13 (root revocation, duplicate token), P16 (stale texts), P19.

Remove `play-pac4j`/pac4j from `build.sbt` and the CLAUDE.md "pac4j via play-pac4j" line; update
`pekko-http` within 1.x (rerun the image cache specs); note unitegallery/raty as vendored, inputs
escaped (S5); fix the stale texts listed in the previous doc's item 16 and mark the
`local-images-review-followups.md` table; P19 (an append-only `audit_event` table for role,
account and contest changes) is a feature: decide whether to schedule it (it would have made N1
traceable).

## Running the batches

As in the follow-ups plan ("Setup", "Roles", "The cycle for each batch"), with these differences:

- **Branch:** S1's hotfix on `ukwikidev/hotfix-role-allowlist` from the deployed commit; its
  forward port and S2–S7, S10 on a `ukwikidev/security-fixes` branch from `ukwikidev/followups`
  after B9 merges (or on `ukwikidev/followups` itself, one batch after another).
- **Effort:** S1, S2, S3 high (authorization, migrations, sessions); others medium; S10 docs low.
- **Review:** S1, S2, S3 each get a fresh independent reviewer with the diff, this plan's batch
  text, the invariants below and the check summary, plus one follow-up review if fixes are
  material. S4–S7: one review.
- **One sbt JVM at a time** across agents (the `/c/scala/.sbt-jvm.lock` directory convention);
  no tests while B10's benchmark runs.
- **Results:** each batch appends "Batch Sn results" here and updates the status column of the
  consolidated report's table (see the practices plan's maintenance section).

**Invariants every reviewer checks** (the follow-ups plan's list, plus):

- A role written anywhere is exactly one allowlisted value; root comes only from
  `users.is_root`; nothing splits a role string.
- Negative authorization cases exist for every changed permission (another contest's admin, an
  organizer, a juror, an inactive membership, a signed-out request).
- No new public route; every new POST has the CSRF token; no mutation on GET.
- No personal data (email, name) in new log lines; account id only.
- Migrations are reversible by a recorded audit table or a documented manual step.
