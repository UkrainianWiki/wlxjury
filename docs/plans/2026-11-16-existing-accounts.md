# Adding an existing account (plan, 2026-11-16; A1–A3 decided by the maintainer 2026-11-16)

The maintainer asked for this on 2026-11-16, after an admin's Edit-user save was refused with
"Another account already has this wiki account." (`wiki.account.taken`,
`UserController.saveUser`, the `User.otherAccountsWithWikiAccount` check). Three ways to add an
account that already exists to the current contest, plus a way out of that refusal. Joining two
accounts is the [account join plan](2026-11-03-account-join.md) (decided 2026-11-03; J1–J3
decided 2026-11-16); this plan links to it. No code yet.

**Decided by the maintainer, 2026-11-16:** A1 every account (the search finds all accounts, for
admins as for root, names and masked emails); A2 confirm any account (an unverified wiki account
is added after the admin confirms the matched account, wherever it is); A3 retire, opt-in; A4
start now (order under "PRs"). **No decision is open.**

## Today

- **Add by email** (`UserService.addToContest`, the add row and "Import users"): an account with
  the address joins if it may take the role (`User.mayTakeAccessRole`: root, verified email, or no
  password), otherwise the role waits for an invitation to its address (`Invited`). If the address
  was set by an admin and this creator may not hand that account a password
  (`UserService.invitationRefused`), nothing is created (`Invited(_, Mail.NotPossible)`). The admin
  never sees the other account; the result counts say whether it was new or existing.
- **Add by wiki account only** (no email): the account with that wiki account joins only if its
  wiki account is verified; otherwise nothing is added (`WikiAccountUnverified`, a
  `security.account` line `add_refused reason=wiki_account_unverified`), and the admin is told to
  add the person by email. This is secure coding §14's rule ("a wiki-account match never attaches
  an access role to an unverified wiki account"; S2, register N4/H1): a wiki name stored here may
  be a legacy self-set name or an admin's typo, so it doesn't say who holds the account. A role
  without access (`board`, `auditor`, `staff`, `no`) is given either way.
- **No wiki account is verified in production** (0 on the copy, counts below). Only a
  Wikimedia sign-in by the same name verifies one (`User.verifyWikiAccount`,
  `setVerifiedWikiAccount`, from `IdentityLinker`), and production runs without OAuth sign-in (the
  26.11 deploy's option 2). So every add by wiki account alone of an existing account with an
  access role is refused today.
- **Wiki accounts are not unique in the database.** `users.wiki_account` has a plain index
  (`idx_users_wiki_account`, V55), not a unique key; uniqueness is checked by the application
  (`otherAccountsWithWikiAccount`) and legacy duplicates exist: 86 groups on the copy (below).
  `matchAccount` takes the first match by id. (Follow-up, not in this plan: the comment in
  `UserController.saveUser` before `User.setWikiAccount`, about a concurrent save hitting "the
  unique key", is wrong: there is none.)
- **Edit user:** a wiki account another non-deleted account has is refused (`wiki.account.taken`),
  without saying which account; nothing else is offered.
- **No search.** An admin finds an account only on their contest's users page (its members) or
  by knowing its email; root has `/admin/allUsers`.
- **Who may change an account** (`UserController.canEditAccount`): root, the account itself, or
  an admin of every contest the account is in or worked in. Seeing an account and adding it to a
  contest don't need that (an add by email works for any account today). Neither decision below
  changes `canEditAccount` or `ShareLinkService`'s rule.
- **No account is ever deleted.** Nothing writes `users.deleted_at` today; readers filter it in
  some places (`otherAccountsWithWikiAccount`, `matchAccount`'s wiki path) but not others
  (`matchAccount`'s email path, `User.findByEmail(...).headOption`; `User.lockRow` in
  `addToContest`), and `users.email`'s unique key still holds the address of a deleted row.

**Counts from the production copy** (the V101 dump of 2026-11-15, run by the coordinator):

| Query | Count |
|---|---|
| accounts (none deleted) | 2,778 |
| verified wiki accounts (`wiki_account_verified_at IS NOT NULL`) | 0 |
| placeholders (`password IS NULL AND email IS NULL AND deleted_at IS NULL`) | 0 |
| accounts with a wiki account | 579 |
| ... of which also with an email | 578 |
| duplicate wiki accounts (case-insensitive, non-empty) | 86 groups, 278 accounts |

Group sizes: 2 accounts ×45, 3 ×17, 4 ×12, 5 ×2, 6 ×3, 7 ×1, 8 ×3, 9 ×1, 10 ×1, 11 ×1.

**One example** (the case that started this plan): a wiki account on 3 accounts, each with a
password and a verified email, created by the V61 merge on 2026-11-11 (legacy one-account-per-
contest rows with different emails), in different contests (about 14,900 votes across 5 contests
on one, a few hundred on each of the others); an admin's edit giving that wiki account to a 4th
account is refused.

**What the counts change:**
- **Placeholders help nobody today** (0 of them); they need no path of their own (A2's
  confirmation covers them).
- **Adding by wiki account always meets an account with a password and an email** (578 of 579),
  never verified: A2's confirmation case.
- **The real need is finding the existing accounts** (section a's search, section c's panel)
  **and the join**: 86 legacy groups, up to 11 accounts each, which the duplicates report shows
  and only joins resolve. Some groups may be screening portions (one person with several juror
  accounts on purpose), so the report's **Keep separate** is needed from its first version.

## a. Pick from a list (Users page) — A1 decided: every account

- **Where:** a "Find an existing account" box on the Users page (`/admin/users`), next to the add
  row, for root and the current contest's admins (`Secured.inCurrentContest`). Signed out: the
  usual 401/login; an organizer, a juror or a member with an inactive role gets the denial
  (`onUnAuthorizedJson`, logged on `security.authz`) and no box.
- **Who finds which account** (A1, the maintainer, 2026-11-16): root and admins alike find every
  non-deleted account.
- **Search:** name or wiki account by any part of at least 3 characters, case-insensitive; email
  by the **whole address only** for accounts outside the contests the actor administers (by a part
  of it within them; root: by a part for all), so the masked results can't be used to rebuild an
  address one character at a time. At most 20 results. `%`, `_` and the escape character `!` in
  the text are escaped with `!` and every `LIKE` says `ESCAPE '!'` (a search for `%` matches a
  literal `%`, never everything; a backslash is an ordinary character, under
  `NO_BACKSLASH_ESCAPES` too: review L2 of A-1). Root accounts are found by root only (L1). A script endpoint
  (`withAuthJson`) taking a **POST with the CSRF token**, so the searched text (a name, an
  address) never lands in access logs, proxy logs or the browser history; the page and the browser
  tests share it. The results link to the edit page only where `editableUser` would open it.
- **Each result shows:** name; email masked (`UserService.maskEmail`: `a***@example.org`, or "no
  email"), in full only for accounts that are members or live invitees of a contest the actor
  administers (they already see it on that users page) and for root; wiki account (with "verified"
  if it is); the contests the actor administers that it is in, with its role there; whether it is
  already in the current contest. Root also sees its other contests. Never shown to an admin: its
  other contests (not even a count), its identities, sign-in times. Where the email is masked, an
  address typed into the name or wiki account is masked too, and such a name or wiki account
  (with an `@`) is matched by a part only within the actor's contests (L3).
- **Logged:** each search at INFO on `security.account` by ids only (`account_search
  byAccountId= contestId= results= length=`; never the text), so probing shows up.
- **Add:** a role select (the add row's) and **Add to this contest**, a POST with the CSRF token
  carrying the account id and the role. A new service method,
  `UserService.addAccountToContest(creator, accountId, contest, role)`, shares `addToContest`'s
  body after the match: under the account's row lock it checks the account is not deleted (the
  check in the caller, next to `mayJoin`; `User.lockRow`'s SQL stays as it is), then applies the
  **email path's** rule (the actor picked this account, as when they type its email): it joins if
  `mayTakeAccessRole`, otherwise the role waits for an invitation to its address (`Invited`,
  mailed by `UserService.systemSender` only if asked; "Create a link to share" only where
  `ShareLinkService` allows it, which for an account with ties outside the actor's contests it
  doesn't; `Mail.NotPossible` as today). `AlreadyMember` as today. The account change log records
  the role as an add does (`AccountChangeJdbc.Role`), with the comment `from search`.
- **The owner is told, never by a mail nobody chose** (M1 after A-1's security review, as
  corrected by the maintainer 2026-11-16: "please do not send emails without approval"): every add
  that gives a membership or an invitation is a notice in the tool for the owner
  (`services.AccountNotices`, read from the change log row marked `from search`; a bar after their
  next sign-in, the list on their own profile, dismissed per browser with a cookie, no migration):
  "You were added to X by Y, with the role R", and what the role does for them. The add form
  always offers **Tell its owner by email** (unticked by default), for every account (no
  distinction by the account's other contests, which would tell the admin about them); the mail
  goes from `UserService.systemSender` only if ticked. Logged on `security.account`
  (`account_added_notice ... outcome=sent|failed|not_sent|no_email inApp=true`); the page says
  what happened ("by email" only when it was sent). The contest's greeting on a direct join is
  mailed only with "Send an invitation email", as on the add row.
- **Placeholders** (no password, no email) found by search: treated as today, as section c's add
  by id: the admin picked the account by id, so it joins (`mayTakeAccessRole`: no password) with no
  wiki-name confirmation; built so in A-1. Section b's confirmation is for the add row's wiki
  account match, where the admin did not pick the account.
- **Refused** (deleted, an id that doesn't exist, a root account for anyone but root): the 404 of
  a missing resource.
- **Risk of A1, stated:** an admin can now find any account by name or wiki account and add it to
  their contest without knowing its email. Secure coding §14's residual risk (an admin adds a
  verified account with no ties and then shares a reset for it) no longer needs the address;
  `ShareLinkService`'s rule is unchanged, so an account with ties elsewhere still can't be handed a
  link or a password by that admin. An admin also learns which names have accounts, with masked
  addresses. Accepted by the maintainer (2026-11-16); secure coding §14 records it in A-1. **What
  remains after M1:** the owner sees a notice in the tool when they sign in (a mail only if the
  admin ticked it), so an owner who doesn't sign in may not learn of the add; once the account is
  a member or a live invitee of the admin's contest, the admin sees its full address (on the users
  page and in the search), as for anyone added by email.
- **Considered:** members and invitees of the actor's contests only (the earlier recommendation),
  plus contests worked in, active members only.
- **Screening portions:** a person with several juror accounts in one round is normal (they get
  separate shares on purpose). The results show the accounts side by side and never call any of
  them a duplicate or suggest a join.

## b. An unverified wiki account — A2 decided: confirm any account

The maintainer asked to allow adding by wiki account when the matched account's wiki account is
unverified, and decided (2026-11-16) that the admin confirms the matched account wherever it is.
**This supersedes S2's refusal for this path** (secure coding §14: "a wiki-account match never
attaches an access role to an unverified wiki account"). **The risk, plainly:** the account's
stored wiki name and its full name are self-set or typed by an admin, so they don't prove who
holds it; after the confirmation the role goes to whoever controls the matched account (its
password, its email), which may not be the person the admin meant: someone who once typed that
wiki name for their own account, or an account another contest's admin filled in with a typo.
With an access role (juror, organizer, admin) that person votes in, or sees, the admin's contest.
The confirmation, the log line and the email path's rule (an invitation where the account can't
take the role directly) are what is left between them.

- **The flow:** the add row (or "Import users", below) given a wiki account and no email, matching
  one or more non-deleted accounts with an unverified wiki account, adds nothing yet. It shows the
  matches **by name only**; the admin picks the one they mean (several matches: they choose among
  them, none preselected; all are visible to them under A1), ticks "This is the person I mean" and
  confirms (POST with CSRF, the account id). Then the masked email is shown with the result (as
  the search shows it anyway, A1), and the add is section a's `addAccountToContest` (the email
  path's rule).
- **A-3 must route placeholders** found by the add row's wiki account (no password, no email)
  through this confirmation: today `matchAccount`'s wiki path refuses them only because their wiki
  account is unverified; A-3's change must not let them join unconfirmed. (A placeholder picked by
  id from the search joins as in section a.)
- **Placeholders** (no password, no email, no Wikimedia identity now or before, `deleted_at IS
  NULL`) take the same confirmation; none exist today. After the add, an admin of the new contest
  who doesn't administer the account's other contests can't make a link or set a password for it
  (`ShareLinkService`'s rule); only root can, until Wikimedia sign-in is on. The result says so
  ("this person signs in with a link from root").
- **Logged:** the account change log (`Role`, comment `wiki account unverified, confirmed by the
  admin`), and a `security.account` line by ids only (`add_confirmed_unverified_wiki accountId=
  byAccountId= contestId= role= matches= placeholder=`).
- **Import users** (bulk) never confirms row by row: rows that match an unverified wiki account
  are counted as now, with a link to the Users page where each is confirmed.
- **Secure coding §14** gets the new rule's text, its risk and its guard specs in A-3 (the rule
  changes, so the doc changes with it).
- **Considered:** confirmation only for accounts in the actor's contests; placeholders only; a role
  pending until a Wikimedia sign-in proves the name; showing no address. A1/A2 settle the masked
  address: it is shown.

**A-3 as built (2026-11-17, `stack/186-unverified-wiki-add`, no migration).** `addToContest`'s
refusal for the wiki path (`WikiAccountUnverified`) now carries the accounts the admin may confirm
(`UserService.wikiMatches`: every non-deleted account with the wiki account, root accounts to root
only); the add row's save renders the confirmation page (`views.wikiConfirm`, 200) instead of
redirecting, and `POST /admin/users/add-wiki-confirmed` (`UserController.confirmWikiAccount`)
requires the pick and the box (else the page again, 400), reads the matches again (an id they
don't hold now: 404) and calls `UserService.addConfirmedWikiAccount`, which is
`addAccountToContest` with the comment `WikiConfirmedComment`. The owner's notice reads both tool
comments (`UserService.NoticeComments`), and the edit form refuses both as typed comments. The
match itself is unchanged (`matchAccount` takes the first account by id; that one already in the
contest is "already in the contest", a verified one joins directly), and a role without access
still joins directly without the confirmation, as before (the refusal it supersedes was only for
access roles). That leaves a residual risk the maintainer accepted on 2026-11-18 (keep and
document): such a membership changed later on Edit user to an access role gets it by the email's
rule alone (a placeholder or an account with a verified email directly), with no confirmation, owner
notice or A-3 log line; secure coding §14 states it. The page's names are `AccountSearch.describe`'s,
with "already in this contest" for a member, and the name typed in the add row ("You typed: …");
the result's address is read before the add. Both mails start unticked on the page (the add row's
"Send an invitation email" was chosen for another recipient). Logged: `wiki_confirm_page
byAccountId= contestId= matches=` for each showing, and `add_confirmed_unverified_wiki` with
`outcome=` and `verified=` too. A match that is only a root account, for an admin, keeps the
earlier "add them by email" message, a small oracle accepted with the review (2026-11-18).

## c. From the edit form

When Edit user refuses a save as `wiki.account.taken`:

- **Show the other account(s)** (up to 10 others in a legacy group): name, email masked as in the
  search (in full for accounts in the actor's contests, and for root), wiki account (verified or
  not), its contests the actor administers with roles, and whether it has votes, comments or round
  assignments, as counts **within the contests the actor administers only** (root: all). None is
  preselected.
- **Offer, in this order:**
  1. **Add that account to this contest instead** (with the role the form had): section a's add by
     id; no wiki-name confirmation (the admin picked the account by id from the panel).
  2. **Retire the account being edited** (only with the add in 1, opt-in, A3): an unticked
     checkbox. Its rules are under "Retiring an account" below.
  3. **Join the two accounts** (the [account join plan](2026-11-03-account-join.md)): a link to
     its preview, shown only to who may perform a join (root, or an admin of every contest both
     are in or worked in), and only once the join is built. *(Built with J-2, 2026-11-17: "Join the
     two accounts…" per other account; the kept account is chosen in the preview.)*
  4. **Keep them separate** (go back to the form, the wiki account left as it was).
- **No suggestion otherwise:** nothing in the users page, the rounds or the reports suggests a
  join or a retire for accounts that merely look alike (the same name, the same round). One
  person with several juror accounts in a screening round is normal; this panel appears only when
  an admin explicitly typed a wiki account another account has.
- **Note, not in scope:** a screening portion account can't carry its holder's wiki account (the
  application keeps names unique), so the conflict-of-interest filter doesn't cover it. Recorded
  for a later CoI change; this plan doesn't change it.

## Retiring an account (A-4; A3 decided: opt-in; reused by the join's J-2)

The first writer of `users.deleted_at`, so it is built once here and the join's retirement
([account join plan](2026-11-03-account-join.md), "Since 2026-11-03") calls the same code.
**A3, decided 2026-11-16:** offered as a separate, unticked checkbox with "Add that account
instead". *Considered:* no retire (role `no` only), root only.

- **Who and which account:** the actor may edit the account (`canEditAccount`), and the account is
  not the actor, not root, and has no active membership or live invitation with a role other than
  `jury` or `organizer` (the rule of `ShareLinkService.handOverRefusal`'s `TargetRole`): an admin
  account is retired by root only.
- **Empty:** no `vote`, `vote_aside` or `vote_orphan` row, no criteria rate, no comment, no
  `round_user` row, no identity, no API token, and every membership in a contest the actor
  administers.
- **Not during a round job:** refused while a `round_distribution` or `round_rebalance` job holds a
  round of the account's contests, so a distribution can't hand files to an account being retired.
  *(2026-11-17, the maintainer: a round held in review, `distributing = 1` without a job, or one
  whose creation failed, doesn't refuse; the `round_user` re-checks below cover it.)*
- **One transaction:** lock the account's row (`User.lockRow`), delete its memberships, pending
  invitations and links, then check emptiness again (votes, rounds, comments: a vote or an
  assignment that committed first refuses the retire, and nothing is written); then set
  `deleted_at`, **clear `users.email`** (the old address goes to the change log row's `old_value`,
  so `users.email`'s unique key frees the address for the person's real account and it can never
  sign in to the retired one), clear the password, revoke API tokens and unused links, end the
  sessions (`auth_version` bumped).
- **`round_user` inserts re-check the account** (review fix): `round_user.user_id` references
  `users` (V37's foreign key), so an insert takes a shared lock on the users row after the round's
  lock. Each insert re-checks the account under that lock (`INSERT ... SELECT ... FROM users WHERE
  id = ? AND deleted_at IS NULL`, or a `LOCK IN SHARE MODE` read of `deleted_at`) in
  `RoundService.insert` (`addUsersIn`), `RoundRebalanceService.add` (both paths, the in-review
  insert included) and `RoundRebalanceHandler`'s "stop" phase; a deleted juror is skipped (round
  creation) or refused (an add). Votes need none: they have no foreign key to `users`, and files
  are assigned only through `round_user`.
- **`invite` must refuse a deleted row** (A-1's review): it locks the account's row in its own
  transaction (`UserService.invite`), after an add's join transaction decided on an invitation;
  it must check `deleted_at` there and refuse, so an account retired in between is never invited.
- **Readers:** `matchAccount`'s email path and `addToContest` (in the caller, next to `mayJoin`;
  `User.lockRow`'s SQL unchanged) treat a deleted row as none; distribution steps drop deleted
  accounts from a round's jurors. The PR lists every reader of `users` it checked (sign-in by email
  and wiki account, `findByEmail`, `findByAccount`, `contestMembers`, the jurors' lists,
  `ShareLinkService`, `IdentityLinker`, the search) and what each does with a deleted row.
- **Logged:** `account_change` field `account_retired` (a migration of the field CHECK) on the
  retired account, and a row on the account added instead naming it (`comment`); a
  `security.account` line by ids only (`account_retired accountId= byAccountId= contestId=
  keptAccountId=`).
- **CoI:** the retired account's wiki account leaves the conflict-of-interest filter (`CoiMembers`
  reads members); the panel shows that change before the confirm if the two accounts' names differ.

**A-4 as built (2026-11-16, `stack/178-retire-empty-account`, V107).** `services.AccountRetirement`
(`retire`, its own transaction retried on a deadlock; `retireIn`, in the caller's, for J-2), the
checkbox on each "Add that account instead" form of the panel (`retireAccountId`, unticked; where
the account can't be retired for its emptiness, an admin role or a round job, the panel says why
instead; nothing for an account the actor may not change). The add runs first and the retirement
only if the add gave the other account a membership or an invitation (two transactions: an add
that commits and a retirement refused meanwhile leaves the add, and the page says why the account
was not retired). The rules are read again on the POST, so a forged id is refused alike (named by
its id only); an admin may retire only an account tied to the contest it is done from (a membership
in any role, or a live invitation; the review of 2026-11-17: an account with no ties passes "every
contest it is in" on an empty set). Both users rows, the retired and the kept account's, are locked
in id order (two crossed retirements: the second is refused); a new comment re-checks its author
like a `round_user` insert (`CommentJdbc.createLive`); an invitation that finds the account retired
after the add committed is reported on the page (`Mail.Retired`). M4 as the maintainer decided on 2026-11-17 (after a first build that refused on
`distributing = 1` too): only a queued or running `round_distribution` or `round_rebalance` job in
the account's contests refuses; a round held in review (P3) or whose creation failed doesn't,
since every `round_user` insert re-checks the account (the review's Distribute included). `round_user` inserts re-check the account under a shared lock
(`User.lockLive`) in `RoundService.insert`, the review's jury (`reviewJury`, a fourth insert,
refused as "jurors changed"), `RoundRebalanceService.add` (both paths) and the handler's "stop"
phase (before its freeze). Readers changed: the email match of an add, `addExisting` under its row
lock (a deleted row is none: a new account), `invite` (refused), `User.distributionJurors`, root's
all users page (`User.findAllLive`), and the edit save (refused under its row lock); the others
already left deleted rows out, or find a retired account through nothing it still has (no
membership, `round_user`, vote, address or live link).

## Migrations

- **A-4** (retire): `account_change`'s field CHECK (V96, widened by J-1's V106 with
  `account_joined`, which it keeps) gains `account_retired`, the next free number at merge (V107
  today).
- The join plan's J-1 took V106 (`account_join_request`, the field `account_joined`); A-4 takes
  the next free number (V107 today).
- A-1..A-3 need no migration (the role and comment columns exist).

## PRs (both plans, in order)

| # | PR | Plan | Size | Migration |
|---|---|---|---|---|
| A-1 | Users page search for every account (POST, CSRF, escaped `LIKE`, whole-address email match outside the actor's contests, masked emails, INFO log by ids) and "Add to this contest" by id (`addAccountToContest`, the deleted check under the lock); secure coding §14's residual risk updated; negative specs: signed out, organizer, juror, inactive member get nothing; no full address of an account outside the actor's contests; no part-of-address match outside them | this, a | M (~2–3 days) | – |
| A-2 | Edit user's `wiki.account.taken` panel: the other account(s), counts within the actor's contests, "Add that account instead" (A-1's add by id), "Keep separate" | this, c | M (~2 days) | – |
| A-3 | Adding by an unverified wiki account (A2): matches by name, the pick and confirmation, then A-1's add; placeholders the same way; the `security.account` line; secure coding §14's new rule and its risk | this, b | M (~2 days) | – |
| A-4 | Retiring an account (A3, opt-in): guards, the round-job refusal, one transaction, email cleared, `round_user` inserts re-checking the account, the deleted-row readers, the CoI change shown; the panel's "Retire" | this, c | M (~2–3 days) | next free, V107 today |
| J-1 | Duplicates report (read-only, groups of 2 to 11 accounts), "Keep separate" per pair; `account_join_request` | join | M (~2–3 days) | V106 (`stack/173-duplicates-report`) |
| J-2 | Join preview, the `account_join` job (reusing A-4's retirement), notices, audit; the edit panel's "Join" link | join | L (~6–8 days) | – |
| J-3 | Requests from the chooser and the own profile, with proofs | join | M (~3 days) | – |

About 8–10 days for A-1..A-4, about 11–14 for J-1..J-3. A-2 and A-3 follow A-1 (they reuse its
add by id and masking); J-1 is independent of all of them; A-4 before J-2 (J-2 reuses it). Every
PR gets the usual review cycle; A-1, A-3, A-4 and J-2 change authorization and get negative cases.

## Queue (A4, decided by the maintainer 2026-11-16)

Start now, in this order: **A-1** (search and "Add to this contest"), then **A-2** (the taken
panel), with **J-1** (the duplicates report, Keep separate) built in parallel by a second agent;
then **A-3**, **A-4**, **J-2**, **J-3**. The round editing plan's step 3 (reassign unrated)
starts when the maintainer says, alongside. The counts (86 legacy duplicate groups, up to 11
accounts; no placeholder; every wiki-only add of an existing account refused) are why finding
accounts and the duplicates report come first. *Considered:* everything after round editing
step 3; the join first.
