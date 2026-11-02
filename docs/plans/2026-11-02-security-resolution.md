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
accounts (user decision 1, after the review):** `email_verified_at = NOW(3)` for root accounts
with an email (the operators) and for every non-deleted account with an email that has a
membership with an access role (jury, organizer, admin), a round assignment (`round_user`) or a
vote (`selection`, `selection_deleted`): accounts organizers added and people used. Memberships
with only `no`, `board`, `auditor` or `staff` do not count: they grant nothing, and board members
are often placeholders added by wiki account for conflicts of interest, never signed in. Every
other account stays unverified; no wiki account is verified (H1's fix refuses wiki-only matches
for access roles anyway). **The user accepted the risk that a past pre-claim on such an account is
taken as genuine** (the S1 role audit found nothing suspicious). Returning jurors added by email
join new contests directly, as before; unverified ones get an invitation first. Reversal: the
columns and the table can be dropped; nothing existing is rewritten.

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

**Decisions (user, answered after the review):**

1. Existing accounts with memberships or votes are verified (see the migration above).
2. Ambiguous wiki-account sign-in stays refused. **Pre-deploy checklist item** (production
   queries are deferred by the user): the read-only count `SELECT wiki_account, COUNT(*) FROM
   users WHERE deleted_at IS NULL AND wiki_account IS NOT NULL GROUP BY wiki_account HAVING
   COUNT(*) > 1`, to see who will have to sign in by email.
3. The old address is told of an email change: a short mail, no link, the new address masked
   (`n***@example.org`), "contact an organizer if this was not you".
4. Link lifetimes stay 7 days (invitation, password link) and 24 hours (email change).
5. Root's "reset password" stays root-only.
6. SMTP is required (`docs/deployment.md`, "Accounts, invitations and the first account").
7. **Link host:** mailed links use the host the request came to (a WLM juror gets a
   `jury.wlm.org.ua` link), safely: `request.host` (Apache's `ProxyPreserveHost On` passes the
   client's `Host`; no forwarded-host header is read) is used only if it is named exactly in
   `wlxjury.allowedHosts` (no suffix pattern, no port); otherwise, and for mail sent without a
   request (a job), `wlxjury.host`. The scheme is always `wlxjury.host`'s (https in production).
   `services.LinkHost`, an implicit the controllers supply; `UserService.linkUrl`.

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
- Not done (deviations): own password change does not bump `auth_version` (S3); (the notice to the
  old address was added in the review fixes below); unused message keys of the old sign-up
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

**Open decisions (user):** none left (answered after the review: see "Decisions (user, answered
after the review)" above).

### S2 review fixes (2026-11-02)

Independent review: "safe to merge after H1, H2, M1, M2". All findings fixed, with the user's
later decisions (above) in the same pass:

| Finding | Fix | Spec |
|---|---|---|
| H1: a wiki-account match still joined verified-email or passwordless accounts with an access role | A wiki-only match joins with an access role only if `wikiAccountVerified` (never yet); otherwise `WikiAccountUnverified`, whatever the account's email or password | `AccountReviewFixesSpec` H1 |
| H2: a stale session (by login name) could resolve to another account later given that address | The session carries `account_id` (sign-in, acceptance, setup); a session of another id is refused. Sessions from before S2 have none and resolve only to version 0: new accounts get a random non-zero `auth_version` (`User.newAuthVersion`), and `setEmail` / `setWikiAccount` bump it on a change. Chosen over a migration-time id cutoff, which would refuse every test session and need a stored cutoff; this way existing accounts keep their sessions and only re-addressed or new accounts are out of an old cookie's reach | `AccountReviewFixesSpec` H2 (old cookie and a recreated address; a signed-in session and a later account with its old address; bumps) |
| M1: acceptance left other purposes' links live | Acceptance revokes every unused link of the account, every purpose (`AllPurposes`), after applying the pending invitations | `AccountReviewFixesSpec` M1 |
| M2: the link was substituted into admin-editable greeting text | `{{Password}}` becomes a fixed text (`invitation.password.placeholder`); the link is only in the app's `invitation.link` paragraph after the greeting | `AccountReviewFixesSpec` M2 |
| L1: an API token created while acceptance ends the session | `ApiTokenJdbc.createIfVersion`: inserted only while `users.auth_version` is the session's; otherwise the session is ended | `AccountReviewFixesSpec` L1 |
| L2: root's form made an unverified account with a password root | 400 `root.needs.verified.email` | `AccountReviewFixesSpec` L2 |
| L3: tokens in URL paths reach log lines | `Secured.logPath` masks `/invitation/*` and `/email/confirm/*` in `ErrorHandler`, `CsrfErrorHandler` and denial lines; `docs/deployment.md` names the Apache access-log exposure | `AccountReviewFixesSpec` L3 |
| L4: no cancel; acceptance applied roles of an inviter since removed | `POST /users/:id/invitation/cancel?contestId=` (an admin of that contest, CSRF), a button on the pending list; at acceptance each pending role is applied only if its sender is root or still an admin of that contest (an invitation is the sender's act; adding people is admins' only) | `AccountReviewFixesSpec` L4 (router with CSRF; another contest's admin; demoted inviter) |
| L5: email-change requests not throttled; the taken-address path was faster | One live link per account, a new one at most every 60 s; the same work for a free and a taken address (a token is created either way; only a free one is mailed, after the answer, `mailInBackground`) | `AccountReviewFixesSpec` L5, `OwnAccountSpec` N7 |
| Nit: `markUsed`'s result | Acceptance and confirmation refuse if it marks nothing; a pending invitation not marked gives no role | covered |
| Nit: `authVersion` optional in `sessionUser` / `mayView` | Both now take the session's version and account id, no defaults | compile |
| Nit: root's empty email for an account with a password created an empty-address link | 400 `error.required` | `AccountReviewFixesSpec` L2 |
| Nit: pending list showed invitations to a former address | Only those sent to the account's current address | `AccountReviewFixesSpec` nits |
| Nit: wiki sign-in with underscores | Looked up as typed and with underscores as spaces (ambiguity still refused) | `AccountReviewFixesSpec` nits |
| Nit: a passwordless account joined by email got "use your existing password" | It gets an invitation (`Invited`) | `AccountReviewFixesSpec` nits, `AccountInvitationSpec` |
| User decision 1 | V77 marks used accounts verified | `AccountVerificationMigrationSpec` (membership, round only, votes, old votes, board only, neither, deleted, root) |
| User decision 3 | `UserService.notifyEmailChanged`: the former address gets a short mail, no link, the new address masked | `AccountReviewFixesSpec` |
| User decision 7 (link host) | `LinkHost` / `UserService.linkUrl` | `AccountReviewFixesSpec` (allowed, upper case, forged, suffix pattern, port, no request; through the controller) |
| Follow-up Low 1: the old address's notice was sent "From" the account, i.e. the new address | Sent from `UserService.systemSender`: `wlxjury.mail.from` (`WLXJURY_MAIL_FROM`), else the SMTP user if an address, else `no-reply@` the host of `wlxjury.host` (documented in `docs/deployment.md`) | `AccountReviewFixesSpec` (From is the system sender, never the new address) |
| Follow-up Low 2: the underscore variant also resolved sessions, so an id-less old cookie could move to another account after a near-duplicate rename | `byUserName(..., underscoresAsSpaces)` only in `User.login`; sessions resolve the exact name | `AccountReviewFixesSpec` (rename scenario) |
| Follow-up nits | `createFirstRoot` takes `newAuthVersion()`; versions drawn from `1 + nextInt(1 << 30)`, bumps through `User.BumpAuthVersion` (back to 1 at Int's maximum); `reinvite` allowed for a verified account without a password | `AccountReviewFixesSpec` |
| Follow-up: residual risk | Old sessions keep resolving by name to version-0 accounts until their version changes: recorded in S3 as an accepted risk for the user's decision | — |

**Specs after the fixes** (no full suite): the four S2 specs, the new `controllers.AccountReviewFixesSpec`
(19) and every affected spec of the first run: 502, 497 passed, `target/checks/20261102-153155-13372.log`;
the 5 others were test-side (`SecuredSpec`'s mocked session lacked `account_id`: 4 errors;
`AccountInvitationSpec` counted the verified member's greeting as an invitation mail); after those
fixes `SecuredSpec` + `AccountInvitationSpec`: 40 passed, `target/checks/20261102-154448-14592.log`.
An earlier run of the account specs alone: `target/checks/20261102-145950-15336.log` (2 expectations
updated for the random auth version and the passwordless account's invitation). After the follow-up review's fixes: the S2 specs and the affected ones (`AccountReviewFixesSpec`,
`AccountInvitationSpec`, `OwnAccountSpec`, `SetupSpec`, `AccountVerificationMigrationSpec`,
`LoginControllerSpec`, `SecuredSpec`, `PasswordStorageSpec`, `MultiContestUserSpec`,
`RoleAssignmentSpec`, `ApiTokenPageSpec`, `MutationAuthorizationSpec`, `CoiAuthorizationSpec`,
`ImageProxyFileCheckSpec`, `MessagesCompletenessSpec`, `ProductionConfigSpec`, `UserDbSpec`): 251
passed, `target/checks/20261102-155941-28536.log`.

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

**Decided (user, 2026-11-02):** MediaWiki's model. A per-account session version (`users`
column) carried in the cookie and checked on every request; a password or email change, "sign out
everywhere" or an admin action bumps it and ends every other session. "Keep me signed in" is kept
and checked by default: a persistent cookie, absolute lifetime 1 year from sign-in, idle timeout
1 month. Unchecked: a browser-session cookie with the same server-side limits. API tokens survive
a password change (like MediaWiki's bot passwords), with a "revoke all tokens" action; an admin
forcing a reset revokes both. Ordinary redeploys keep sessions (same `PLAY_HTTP_SECRET_KEY`);
only S3's own release changes the cookie format: (4) old cookies are upgraded in place (user):
the first request with a cookie lacking the new fields stamps the current session version and the
sign-in time as now, so nobody is signed out. Accepted risk: a leaked pre-S3 cookie gets a fresh
lifetime; "sign out everywhere", a password change or a key rotation ends it.

**Accepted risk from S2 (for the user's decision):** a session from before S2 has no `account_id`
and no `auth_version`; it still resolves by its login name, but only to an account of version 0.
Every account created since (a random non-zero version) or whose email or wiki account changed
since (a bump) is out of its reach, so a stale cookie can't move to another account; but it keeps
acting as the version-0 account it names until that account's version changes. S3's in-place
upgrade of old cookies keeps this until the upgrade stamps an id; rotating the session key (P16)
would end every old session at once.

**Dependencies:** after S2. Gatling simulations sign in through the form; check that
`test/gatling` still signs in (no run unless the session code changes per-request cost).

### S3 results (2026-11-02)

**Branch** `ukwikidev/security-s3` (from `ukwikidev/followups` at 58c5a2b8). **No migration:** S2's
`users.auth_version` (V77) suffices; the times live in the signed cookie. Not reviewed yet (S3 gets a
fresh independent reviewer).

**As implemented** (the user's decisions above):

- **Session fields:** a sign-in stores `username`, `account_id`, `auth_version` (S2), `issued_at`,
  `last_seen` (epoch seconds) and, if "Keep me signed in" was checked, `keep=1`
  (`Secured.signedInSession`, `SessionLifetime.signedIn`). The login name stays in the session (S2's
  lookup by name plus the id check); dropping it is left for a later change.
- **Limits:** `controllers.SessionLifetime` with `wlxjury.session.absoluteDays = 365`, `idleDays = 30`,
  `touchMinutes = 5` (read once from the config, as `PasswordHasher.default`; validated: positive,
  touch shorter than idle). `Secured.sessionUserOf` refuses a session past either limit or with a
  malformed time; a missing time counts as now (the in-place upgrade). The thumbnail proxy checks the
  same (`ImageProxyController.serve`).
- **`modules.SessionLifetimeFilter`**, first in `play.filters.enabled` (prepended in
  `conf/application.conf`, the test config and the Gatling timing list), so the CSRF filter and the
  actions see its session: an expired session's sign-in keys (`SessionLifetime.SignInKeys`, the
  session's contest included) are taken out of the request and the browser, other keys (the CSRF
  token) kept, logged `session_expired reason=absolute|idle|malformed|old_cookie_unmatched accountId=`
  on `security.auth` (INFO); a pre-S3 cookie is upgraded in place (one lookup by its exact name, S2's
  `sessionMatches`; then `account_id`, `auth_version`, `issued_at = last_seen = now` stamped, logged
  `session_upgraded accountId=`), or signed out if it no longer resolves; `last_seen` is refreshed once
  it is 5 minutes old. Only upgrades query the database; the version check stays in `sessionUserOf`
  (one lookup per request, as before), so no per-request query was added and no Gatling pilot was run
  (`Gatling/compile` passes; the simulations sign in through the form, without the checkbox field).
- **Cookies:** Play's `session.maxAge` stays unset (a browser-session cookie). For a kept session the
  filter writes the session cookie itself, `SessionCookieBaker.encodeAsCookie` with Max-Age = the idle
  limit, capped at what is left of the absolute one; it clears the result's `newSession` so only one
  `PLAY_SESSION` cookie leaves (through the router, specs read a kept session from `cookies(result)`).
  A signed-out request goes through it too (a sign-in's new session). No session cookie is added to a
  response a shared cache may keep (Play's CSRF rule: a Cache-Control without no-store, no-cache or
  private). Upgraded pre-S3 cookies get no `keep` (they were browser-session cookies before).
- **Ending sessions** (`users.auth_version` + 1): "Sign out everywhere" (`POST /logout/everywhere`, the
  own profile's "Sessions" card, CSRF) ends every session **including this one** (the button says
  "everywhere"; it redirects to the login page); root's "End sessions" (`POST /users/:id/sessions/end`,
  root only, CSRF, a button next to "Reset password" on another account's edit page; 404 for no
  account) ends the account's sessions, root's own only if it is root's account; one's own password
  change (`User.setPasswordHash` now bumps and returns the version, in one quiet transaction) ends
  every other session and **keeps this browser signed in** with the new version (`addingToSession`).
  Logged `sessions_ended accountId= by=self|byAccountId=` and `password_changed accountId= by=self
  sessionsEnded=true` (ids only). Accepted links and email changes bump as in S2.
- **API tokens:** survive one's own password change; "Revoke all tokens" (`POST /users/tokens/revoke-all`,
  CSRF, shown in the tokens card when one is unrevoked; `ApiTokenService.revokeAllOwn`, the user's row
  locked, `api_token_revoked by=self userId tokenIds`, sharing `revokeByRoot`'s transaction). An
  accepted password link or invitation still revokes them (S2).
- **Login form:** the "Keep me signed in" checkbox (`remember`), checked on a new form, as sent on a
  re-render; a form without it (unchecked, the Gatling simulations, scripts) gets a browser-session
  cookie. Invitation acceptance and `/setup`, which have no checkbox, keep the session (the default).
- **Accepted risks (recorded):** a leaked pre-S3 cookie gets a fresh lifetime at its first request
  (user decision; "sign out everywhere", a password change or a key rotation ends it); the thumbnail
  proxy's per-viewer cache (`ViewerTtl`, 1 minute) may serve thumbnails to a just-ended session for up
  to that minute; `last_seen` is refreshed at most every 5 minutes, so the idle limit is exact to that.
- **Docs:** `docs/deployment.md` "Sessions and the signing key" (limits, what ends sessions, key
  rotation: what it ends, when, how: `runtime.env`, then the next `wlxjury-release deploy` or
  `systemctl restart wlxjury`; no secrets); `CLAUDE.md` auth section; messages in all four languages.
- **Not done here:** N6 (login CSRF, moved to "S3 or S7") is left for S7; L3's root-side check (root's
  own `auth_version` in `revokeByRoot`) is not added: revoking only reduces access.

**Specs** (one sbt JVM at a time, through `scripts/agent-check.py`; no full suite):

| Run | Specs | Result | Log |
|---|---|---|---|
| 1 | `SessionLifetimeSpec`, `SessionsSpec`, `SecuredSpec` (with `Test/compile`, `Gatling/compile`) | 4 failures in the new `SessionsSpec`, test-side (log read before the action ran; form markup; the juror's start page is a 200) | `target/checks/20261102-164204-19128.log` |
| 2 | the S3 specs and the affected ones: `LoginControllerSpec`, `ApiTokenPageSpec`, `SignOutAndSwitchSpec`, `PasswordStorageSpec`, `OwnAccountSpec`, `AccountInvitationSpec`, `AccountReviewFixesSpec`, `SetupSpec`, `ImageProxyControllerSpec`, `ImageProxyFileCheckSpec`, `MutationAuthorizationSpec`, `ThemeSwitchSpec`, `CoiAuthorizationSpec`, `LargeImageSpec`, `MultiContestUserSpec`, `RoleAssignmentSpec`, `UserControllerSpec`, `ErrorHandlerSpec`, `UserPasswordSpec`, `ApiTokenJdbcSpec`, `api.ApiSpec`, `ProductionConfigSpec`, `security.*`, `views.*` | 564: 561 passed. Fixed: a sign-in from a signed-out request did not get the persistent cookie (the filter skipped requests without a session user); a duplicate message key. Not S3: `UserPasswordSpec`'s control check (an ordinary failing statement logs its value, line 91) fails since S5 turned `scalikejdbc.global.loggingSQLErrors` off | `target/checks/20261102-164610-1804.log` |
| 3 | `SessionsSpec`, `LoginControllerSpec`, `SignOutAndSwitchSpec`, `ApiTokenPageSpec`, `MessagesCompletenessSpec` (with `Test/compile`, `Gatling/compile`) | 62 passed | `target/checks/20261102-165449-23052.log` |

New specs: `controllers.SessionLifetimeSpec` (6, no database), `controllers.SessionsSpec` (17, router
with the CSRF filter: sign out everywhere ends a second browser's session and needs the token; root's
end sessions, refused to an admin and a juror (403) and without the token; the own password change
ends the other sessions, keeps this one with the new version and the API tokens; revoke all tokens
(only the user's, CSRF); the checkbox, and Max-Age set or omitted; absolute and idle expiry, keeping the
CSRF token; refresh only after 5 minutes; a persistent cookie's Max-Age capped by the absolute limit; a
pre-S3 cookie upgraded in place; a pre-S3 cookie of another account id or an older version refused).
`SecuredSpec`: one new example (stale version, other account, absolute, idle, malformed, pre-S3 times).

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

**Decided (user, 2026-11-02):** fail2ban, not in the app; S4 is skipped here and goes to the
infra plan (`security.auth` log line it can match, limits set there). N6 (login CSRF) is not
throttling and moves to S3 or S7.

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

### S5 results (2026-11-02)

Branch `ukwikidev/security-s5-s10` (from `ukwikidev/followups` 57aa8434).

- **N3:** `Csv.text` prefixes a cell starting with `= + - @`, tab or CR with `'`; the header's
  juror names and each row's file title go through it, the app's numbers (ranks, rates, a binary
  round's `-1`) don't. Streamed and in-memory exports share `header`/`row`. `CsvSpec`: each
  character in the header and in rows, other text and `-1` unchanged.
- **N8:** the tile caption's link text is `HtmlFormat.escape(file.title)` inside the `alt`
  attribute, as `data-description` was, so unitegallery's `.html()` shows it as text.
  `GalleryDescriptionViewSpec`: a title with `&` and `<i>` is one link whose text is the title.
  No JS changed.
- **N9:** `job.error` holds a `services.jobs.JobError` (JSON: message key, numeric arguments, log
  reference). A crash stores `job.error.reference` with the reference of its ERROR log line
  (which has the exception); the distribution's count check stores `job.error.count.mismatch`
  with the round and counts. A row from before (free text) reads as `job.error.unknown`, so old
  exception texts are not shown either. The status JSON has `error` (the localised message),
  `errorKey` and `errorReference`; the status snippet shows the message. Keys in all four
  languages. `JobControllerSpec` (a job crashing with a driver-like message: JSON and snippet show
  the key's text and the reference, never the message; the log line has both); the specs that
  checked the exception text in `job.error` (`JobSchedulerSpec`, `RoundDistributionJobSpec`,
  `AppendImagesSpec`, `MonumentListImportSpec`) check the key and reference instead.
- **N11/P12:** `scalikejdbc.global.loggingSQLAndTime.warningEnabled = false` and
  `loggingSQLErrors = false`; `db.scalikejdbc.SqlLogging` (installed by `AppModule`, threshold
  `wlxjury.sql.slowStatementMillis = 1000`) logs a slow statement at WARN and a failed one at
  ERROR on `db.sql`, as the statement's template (bound values are `?`, quoted literals `'?'`)
  and, for a failure, the exception's class, SQLState and code, not its message. It uses
  ScalikeJDBC's tagged listeners, which nothing else sets (`RequestTimingFilter` keeps the
  untagged one). The INFO line per statement (with values, for `sbt run`) is unchanged;
  production's logback writes WARN and up only. A failure's exception still reaches its handler
  (`ErrorHandler` logs it with a reference). `SqlLoggingSpec` (list appender on the root logger:
  a slow and a failed statement with an email bound log the template and no value at WARN+, even
  with ScalikeJDBC's warnings switched on first; the shipped configs turn them off). `useSSL`
  stays with S8.
- **Guards (practices plan):** `security.ExportsSpec` (G8), `security.LogHygieneSpec` (G9),
  `security.ErrorTextSpec` (G13), `security.RawHtmlSinksSpec` (G5), `security.RawSqlSpec`
  (G4, G4b), on `security.SourceScan`. Allowlists are per file with a reason, and check only
  that no file outside them has a hit (so concurrent branches that remove a use don't break
  them); G13 also caps each listed file's count.
- **Checks:** one run for S5 and S10, recorded under "S10 results": the S5 specs above, the
  four job specs, `views.*` and `security.*` pass.

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

**Decided (user, 2026-11-02):** P6: a deactivated juror must not vote (both vote actions check
`round_user.active`; 409 like a stopped round). P7: option (a), a single-use password-set link reusing S2's invitation token; done in S2.
P8: grace period 3 months for legacy SHA-1 hashes, then they are replaced by an unusable value and the user sets a password through S2's link.

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

**Decided (user, 2026-11-02):** P3 (`style-src 'unsafe-inline'`) later; the rest of S7 now.

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

**Decided (user, 2026-11-02):** folded into the infra plan (section "Security items folded in" on
`ukwikidev/infra-in-repo`, 088b76e9), together with S4's fail2ban throttling. The followups branch
is not deployed before O1 is in place.

## S9: Ops

**Items:** P10, P8 (count), key rotation, the S1 data check.

- P10: delete `C:\scala\wlxjury-bench-work\proddata\*.sql.gz` and the seven `wlxjury-bench-*`
  volumes once B10 no longer needs them (decision: keep an anonymised fixture instead).
- P8: `SELECT COUNT(*) FROM users WHERE password REGEXP '^[0-9a-f]{40}$';` (read-only) before S6
  decides the grace period.
- Key rotation after S3 is deployed, following the new procedure, if any session may have leaked
  (decision).
- S1's queries, and again after S1's migration runs.

**Decided (user, 2026-11-02):** S9 skipped for now.

## S10: Info and dependencies

**Items:** N10, P13 (root revocation, duplicate token), P16 (stale texts), P19.

Remove `play-pac4j`/pac4j from `build.sbt` and the CLAUDE.md "pac4j via play-pac4j" line; update
`pekko-http` within 1.x (rerun the image cache specs); note unitegallery/raty as vendored, inputs
escaped (S5); fix the stale texts listed in the previous doc's item 16 and mark the
`local-images-review-followups.md` table; P19 (an append-only `audit_event` table for role,
account and contest changes) is a feature: decide whether to schedule it (it would have made N1
traceable).

**Decided (user, 2026-11-02):** S10 yes. P19's audit log is scheduled together with the rounds
parity plan's first gap (audit log, `docs/plans/2026-11-02-montage-rounds-parity.md`), one table
for role, account, contest and round events.

### S10 results (2026-11-02)

- **N10:** `play-pac4j` (and the unused `pac4jVersion`) removed from `build.sbt`; nothing in
  `app/`, `test/` or `conf/` referred to it, and the app and tests compile without it. CLAUDE.md's
  "pac4j via play-pac4j" line now says authentication is the app's own. **`pekko-http` is not
  updated (user, 2026-11-02):** Pekko and Pekko HTTP must stay in step with scalawiki
  (`project/Dependencies.scala`, `Library.Pekko`: `PekkoV = 1.0.3`, `PekkoHttpV = 1.0.1` on
  `origin/page-categories`, the same as here); a comment next to `PekkoVersion`/`PekkoHttpVersion`
  says so. The update is deferred to a coordinated bump, scalawiki first, then wlxjury (then rerun
  the image cache specs). unitegallery 1.7.28 and jQuery Raty 2.7.1 are listed as vendored, with
  the escaping rule, in `incident-prevention-guidelines.md` ("Browser safety"); raty gets numbers
  and empty hints only.
- **P13 (root revocation, double click):** `ApiTokenService.createOnce` runs a create in one
  transaction with the user's row locked (`ApiTokenJdbc.lockOwner`), and the 20-token cap is
  checked in the same transaction; a repeated submit of the same form is handled as the review
  fixes below describe (the form's nonce on the token, V79; the button disabled). Root has
  `GET /admin/users/:userId/tokens` (the list, linked from another account's edit page), `POST
  .../tokens/:id/revoke` and `POST .../tokens/revoke` (all), root only, CSRF by the global filter;
  `ApiTokenService.revokeByRoot` logs `api_token_revoked by=root rootId userId tokenIds` at INFO
  (ids only). New controller `ApiTokenAdminController` and view `userApiTokens` (kept out of
  `UserController`, which S2 changes; S2's `ApiTokenJdbc.revokeAll` is not duplicated). Keys in
  all four languages. `ApiTokenPageSpec`: a double click (sequential, and five at once) makes one
  token; root lists, revokes one (log line with ids, no email, name or prefix) and all; a contest
  admin, a juror and the owner get 403, signed out a redirect, root without the CSRF token 403, a
  token under another user's id or an unknown one 404, nothing revoked. The failed-token limit
  stays with S4.
- **P16:** checked against the code and fixed: the api-auth plan's denial line (it logs
  `accountId` only, `Secured.denialLogLine`); `application.conf`'s host comment (production has no
  loopback names; `/health/ready` is `+ anyhost`); `production.conf`'s `request.secure` comment
  (read only by `thumbnailUrls`' fallback, which production never takes: `wlxjury.host` is set);
  `local-images-review-followups.md`'s table (#3, #7, #8 fixed; #9, P2 deleted; #4 partial, P3
  documented) with a dated status note. The secret-rotation procedure stays with S3/S9.
- **Not done:** P19 (audit log; scheduled with the rounds-parity plan). G11 (dependency report)
  and G12 (secret scan) are not added: G11 is a release-gate task, not a spec, and G12 waits for
  the infra plan's `check-no-secrets.sh`.
- The consolidated report's status column is updated for N3, N8, N9, N10, N11/P12, P13, P16.

**Checks (S5 and S10, one run each; from Git Bash through `scripts/agent-check.py`, under the
shared sbt lock):** `Test/compile`, `Gatling/compile` and `testOnly controllers.CsvSpec
controllers.JobControllerSpec db.scalikejdbc.SqlLoggingSpec security.* services.jobs.JobSchedulerSpec
services.distribution.RoundDistributionJobSpec controllers.AppendImagesSpec
services.imports.MonumentListImportSpec controllers.ApiTokenPageSpec api.ApiSpec
controllers.MutationAuthorizationSpec views.*`: 343 tests, 342 passed, 1 failed
(`target/checks/20261102-145020-28696.log`): `SqlLoggingSpec` expected the class name to contain
`SQLException` (it is `SQLSyntaxErrorException`), and left the slow threshold at 0 for later specs;
both fixed. Rerun of `SqlLoggingSpec deployment.ProductionConfigSpec security.*
controllers.JobControllerSpec`: 31 passed (`target/checks/20261102-145709-28552.log`). No
JavaScript changed, so no Jest run. No full suite. The image cache specs were not rerun: no
dependency they use changed (`pekko-http` deferred).

### S5/S10 review fixes (2026-11-02)

The independent review found the branch safe to merge, with these fixes before release:

| Item | Finding | Fix | Check |
|---|---|---|---|
| M1 (P13) | A double click's second POST answered 409 "no token created" while the first had made one whose value nobody saw | `createOnce` revokes the user's same-name tokens created less than 30 s ago and never used (`ApiTokenJdbc.unusedSince`, locking read after the user-row lock), then creates and shows a new one: the last submit wins, one usable token, the one the browser shows. A used token is kept. Logged as `api_token_replaced userId tokenIds` (ids only). No submit-button script (optional, not done) | `ApiTokenPageSpec`: sequential double click shows a token, the only usable one, the first revoked; a used one is kept; five at once leave one usable token, shown by a response |
| L1/L2 (N8) | unitegallery's thumbnail `alt` (`setHtmlThumb`) used `htmlentitles`, which leaves `'` unescaped (a title with `'` broke out of the attribute), and with the title escaped once more showed entities (`&#x27;`, `&amp;`); the image-effect overlay inserted the title raw; `contestFiles.scala.html` gave unitegallery the raw title | Vendored patch: both sinks set `alt` to `escapeAttr(htmlToText(title))` (inert DOMParser text, quotes escaped) and escape `urlThumb`. The extra escaping in `gallery.scala.html` stays: the text panels insert the title with `.html()`. `contestFiles.scala.html` escapes the title once more too. Listed in `incident-prevention-guidelines.md` (vendored libraries) | Jest `unitegallery-alt.test.js` (`'`, `&`, `<i>`, a quote breakout, inert parsing, both sinks); `GalleryDescriptionViewSpec` (quote in the title); `ContestFilesPageSpec` (alt is html whose text is the title) |
| L5 (N3) | Neutralising only named fields left a new export to remember it | `Csv.writeRow`/`Csv.write` pass every cell through `Csv.cell`: a number (by type, or `-?\d+(\.\d+)?`) as it is, any other string through `Csv.text`, which also catches whitespace then `= + - @`. Rank ranges (`1-2`) start with a digit and are unchanged | `CsvSpec` reads the written lines back: names and titles prefixed (including leading whitespace), `-1`, `-2.5`, `1-2` unchanged, `-x`, `+1`, `@a` prefixed |
| L5 (optional) | Whole-account interpolation in log lines | `LogHygieneSpec` also flags `$user`, `${editedUser}` and the like (whole-account names) | `security.LogHygieneSpec` |
| L4 (nit) | `revokeByRoot` read then revoked outside a transaction and logged even when nothing was revoked | One transaction with the user's row locked; no log line if nothing was revoked. S2's `ApiTokenJdbc.revokeAll` arrives at the merge: `revokeByRoot` could use it for "all" then | `ApiTokenPageSpec` (root revokes one, all) |
| Nit | `createOnce` duplicated `create` | `create` takes an implicit session (default `AutoSession`); `createOnce` calls it in its transaction | `ApiTokenPageSpec`, `ApiSpec` |
| Nit | pac4j removal might evict or downgrade transitive libraries | `sbt dist` builds (205 jars in `lib/`: the runtime classpath's 203 and the app's two). `Runtime/managedClasspath` against the base's `build.sbt` (57aa8434): only `play-pac4j_2.13` 12.0.0-PLAY3.0, `pac4j-core` 6.0.0, `lombok` 1.18.30 and `findbugs-annotations` 3.0.1 are gone; no other library is added, evicted or changes version | `sbt dist` |
| L-a (follow-up review) | Replacing by name within 30 s could revoke a token the user had copied and deliberately followed with another of the same name | The replacement is keyed on the submit: `api_token.create_nonce` (V79, the SHA-256 of the form's one-time nonce); `createOnce` revokes only unused tokens created with the same nonce (a double click repeats it; a create from the response page or another tab has a new one). The name/30 s rule is gone. **Merge:** V79 is numbered after S2's V77 and C1's V78; merge those first or renumber (production validates without ignoring out-of-order migrations). Undo: drop the column | `ApiTokenPageSpec`: a same-nonce double click leaves one usable token, the one shown; a second create of the same name from the new page (fresh nonce) leaves both usable |
| L-b (follow-up review) | Under concurrency the last request sent may commit first, so the shown token could be the revoked one | `submit-once.js` (served from `'self'`, no inline script): a `data-submit-once` form's first submit disables its submit buttons and drops further submits; a page restored by Back gets them back (`pageshow`). The API token create form uses it; the server-side replacement stays as the backstop | Jest `submit-once.test.js`; `NoInlineScriptsSpec`, `InlineScriptHashesSpec` (`views.*`) |
| L3 | `createOnce` should re-check the session's `auth_version` (S3) inside its transaction | **Merge note for S2/S3:** not implemented here; at the S2 merge, check `auth_version` in `createOnce`'s transaction (and in `revokeByRoot` for root's) so a request of a session invalidated meanwhile creates nothing | — |

**Review fix checks:** Jest, whole suite in this tree (`npm ci` in `javascript-test/`, own
`node_modules`): 19 suites, 247 tests pass, `unitegallery-alt.test.js` included. sbt (under the
lock): `Test/compile`, `Gatling/compile`, `testOnly controllers.ApiTokenPageSpec controllers.CsvSpec
controllers.ContestFilesPageSpec api.ApiSpec security.* views.*`: 261 passed
(`target/checks/20261102-153956-15200.log`), then `dist` and `export Runtime/managedClasspath`;
the base's classpath in `target/checks/20261102-154639-17516.log`.

**Follow-up review fix checks (L-a, L-b):** Jest, full suite in this tree with its output kept
(`target/checks/jest-20261102-155157.log`, `npx jest --verbose`): 20 suites, 251 tests pass,
`unitegallery-alt.test.js` (7) and `submit-once.test.js` (4) among them. sbt (under the lock):
`Test/compile`, `Gatling/compile`, `testOnly controllers.ApiTokenPageSpec api.ApiSpec
db.scalikejdbc.ApiTokenJdbcSpec security.* views.*`: 230 tests, 229 passed
(`target/checks/20261102-160406-11052.log`); the one failure, `ApiTokenJdbcSpec`'s column list
without V79's `create_nonce`, is updated and passes with `db.MigrationNamingSpec` (8 passed,
`target/checks/20261102-161423-10624.log`). (The earlier Jest runs were in this tree too, outside
sbt, so no sbt log holds them.)

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
