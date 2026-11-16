# Joining duplicate accounts (plan only, 2026-11-03; brought up to date 2026-11-16)

The user's decision on duplicate wiki accounts (2026-11-03): an account chooser now (built on
`ukwikidev/oauth-login-rebased`: `OAuthChooserController`), and joining accounts later. This plan
covers the join; no join code exists yet. Context: S2 left duplicates unresolved ("a later
contests rework with an admin UI for merging accounts"); the OAuth port's note
([2026-11-03-oauth-login-port.md](2026-11-03-oauth-login-port.md)) has the chooser.

**Status (2026-11-16).** The rules below were decided on 2026-11-03 and stay as they are (one
wording corrected by the review of this update: the users' rows are locked first, not last; see
"Lock order"). The maintainer asked for the join again on 2026-11-16, with adding existing
accounts ([2026-11-16-existing-accounts.md](2026-11-16-existing-accounts.md), whose edit-form
panel links here), and decided J1–J3 the same day ("Decisions", end of this file): **no decision
is open.** "Since 2026-11-03" lists what changed around the plan and how the build follows it;
"PRs" the order.

## Requests

- **Where:** a "Request to join these accounts" action on the chooser page (after the user proved
  control of the account they chose, and of another listed one by the same proofs: its password, or
  a link mailed to its verified address) and on the own profile ("I have another account": prove it
  the same way).
- **What it records** (a new table, `account_join_request`): both account ids, the proof of each
  (`password`, `mail_link`, `session`: the account signed in; with the time), the email the user
  chose to keep (one of the two accounts' verified addresses, or none if neither is verified), who
  asked, the state (`pending`, `done`, `refused`, `cancelled`), and who decided.
- The user can cancel a pending request; a request expires after 30 days.

## Who performs a join

- **Root**, or an **admin of every contest both accounts belong to** (an account in a contest the
  admin doesn't administer: root only). From a request, or from a duplicates report (accounts with
  the same normalised wiki account, or the same verified email, listed per contest for its admins
  and in full for root).
- **Joins without the user's proof** (from the report): the account kept is the one with the
  verified email (with two verified: the performer chooses, J2); the other account's
  sign-in methods are retired (password set to the retired value, identities unlinked (J3), links
  revoked); both addresses get a notice (`UserService.systemSender`, masked addresses, no link).
- **Joins from a request:** the kept account is the one whose email the user chose; the same
  retirement and notices.

## What gets merged (into the kept account; the other is marked deleted, `deleted_at`, never reused)

- **Memberships:** per contest, one row. If both have one: the higher-access role wins
  (`admin` > `organizer` > `jury` > the roles without access), and the preview lists the conflict;
  an inactive role (`no`) loses to any other.
- **Round assignments** (`round_user`): union; one active row per round.
- **Votes** (`vote`, `vote_aside`, `criteria_rate`, kept consistent with `round_image` as the
  write-path plan's lock order requires): moved to the kept account. A **collision** (both voted on
  the same file in the same round) is listed in the preview and refuses the join (J1).
- **Conflicts of interest:** CoI keys on `users.wiki_account`, so nothing moves; the kept account's
  wiki account is the joined one's, and the preview shows a CoI change if the names differ. Its
  verification goes only with the name it proves (review of #240): the name kept is verified only
  if the account it came from verified that name, or both accounts have that name and either
  verified it; a verified name A never makes a joined unverified name B verified (verification
  gates role matching by wiki name, security-resolution S2).
- **Comments** (`comment`): author moved.
- **API tokens:** revoked on both accounts (shown once, never moved).
- **Identities** (`user_identity`): moved to the kept account (a Google or Wikimedia sign-in then
  opens it), each listed in the preview with its provider and username. In a join without the
  user's proof none moves: they are unlinked and their holder re-links (J3).
- **Sessions:** ended on both (`auth_version` bumped).
- **Links** (`account_token`): every unused one of both revoked.
- **Audit:** one `security.account` line per join (`account_joined keptAccountId= joinedAccountId=
  by= requestId=` with counts per table, ids only), and the request row kept.

## Safety

- **Preview** before anything is written: both accounts' memberships, rounds, vote counts, the
  conflicts (role, vote collisions, which refuse the join (J1), CoI name change), the
  identities that move or are unlinked (provider and username; J3), and what the user chose; the
  performer
  confirms it (POST with CSRF).
- **One job** (`account_join`, `services.jobs.JobScheduler`): the round-first rule and the lock order
  of the write-path plan (`round → images → vote → criteria_rate → vote_aside → round_image`), one
  round per step, each step one transaction; the users' rows locked last (superseded 2026-11-16: first, see "Lock order" below); the job refuses to start
  while a round of either account is being distributed (`round.distributing`).
  `RoundImageInvariants.violations` checked per round after its step.
- **Votes during the job** (review of #240): a juror's vote is row-level and never takes the round's
  lock (`RoundWrites`), so a vote by the retiring account can commit after its round's step and
  stay on that account. The first step therefore ends both accounts' sessions and refuses the
  retiring account's sign-in and votes while the join runs, and the last step, under the users'
  row locks, moves whatever rows the retiring account still has (a sweep of every round) before it
  retires it.
- Idempotent steps (a retried step finds its rows moved). **Cancel** only before the first round
  step commits; after it, a failed step is retried (Retry), never cancelled, since moved votes and
  assignments are not moved back (review of #240). Until the last step both accounts stay
  usable apart from the retiring account's refused votes.

## Since 2026-11-03 (2026-11-16)

No rule above changes. What the build has to follow now:

- **Memberships** (`user_contest`, V61/V62; the role CHECK of V76): one row per account and
  contest. "Every contest both accounts belong to" (who performs a join) is read as
  `UserController.canEditAccount`'s set: contests the account is a member of or worked in
  (`User.contestsWorkedIn`), so the join's performer rule and the edit rule agree. If multi-role
  ([2026-11-10-multi-role-plan.md](2026-11-10-multi-role-plan.md), a role set per membership)
  merges first, the "higher-access role wins" rule needs restating for sets under its rule J
  (Juror stays alone); ask then, not now.
- **Round assignments** (`round_user`, with `active` for stopped jurors, S6): "union; one active
  row per round" means one row per round, active if either account's row is; the preview lists the
  rounds where one of the two was stopped.
- **Other jobs on the same rounds** (the round editing plan, #345/#346): adding a juror runs a
  `round_rebalance` job that shares the round's dedupe key (`round_distribution:round:R`) and pauses
  the round while it runs (`freeze_count > 0`, a vote gets 409 `round_paused`); reassigning unrated
  files (its step 3) will do the same. A join step for round R locks R's row (`SELECT ... FOR
  UPDATE`) and, under that lock, checks that no active job holds R's key and `freeze_count = 0`;
  otherwise it ends the step without writing and is retried. Creating a `round_rebalance` (or a
  distribution) and freezing a round take the round's row too, so the check and a new job can't
  both pass. While a join runs, adding either account to any round is refused, checked under the
  round lock in the three places that insert `round_user` rows (`RoundService.insert`,
  `RoundRebalanceService.add` with its in-review insert, `RoundRebalanceHandler`'s "stop" phase;
  round creation drops the retiring account and keeps the kept one); the join refuses to start
  while a queued or running `round_rebalance` has either account as its add. A P3
  round held in review (`status = paused`, `distributing = 1`, P3-3) counts as being distributed
  for the start check: the preview names it ("finish or cancel the review first").
- **`round_image`:** invariant 8 (every row has votes or is `dq`) ended with P3-1 (#347): rows
  without votes stay. A join moves votes between two jurors of the same file and round, so a step
  adds or removes no `round_image` row and drops no vote (J1); where two unrated rows become one
  the file's assigned count changes, so the step marks it dirty (`round_image_dirty`, V89) for
  the recompute. `RoundImageInvariants` (without
  8) still runs per round after its step.
- **Votes:** `vote` (`selection` until V82), `vote_aside` (votes set aside with their file; no
  reason column; restore and requalify move them back to the same juror, so they move with the
  account), `vote_orphan`, and `criteria_rate` (by vote id: it follows its vote). An unrated row
  (`rate` 0, `voted_at` NULL) is an assignment: when both accounts have one for the same file and
  round, one stays and nothing is lost (not a J1 collision). `vote.voted_at` (V97) moves with the
  vote unchanged.
  - **Unique keys** (review fix): `vote`, `vote_aside` and `vote_orphan` each have a unique
    `(page_id, jury_id, round_id)` key (V82), so a moved row would clash where the kept account
    already has one for that file and round. In `vote` and `vote_aside` such a pair is a J1
    collision (refused) unless both rows are unrated. The retiring account's own `vote_orphan`
    rows stay under its id (they are outside every round's results; the retired row is kept);
    nothing is moved into `vote_orphan`. A join therefore drops no vote, and `criteria_rate`
    follows its vote by vote id (nothing goes to `criteria_rate_orphan`).
  - **A collision found after the start** can't arise from votes (the retiring account's votes
    and new assignments of either are refused while the join runs); if a step finds one anyway,
    it writes nothing for that round and fails the job (Retry after it is fixed by hand).
- **Comments** (`comment`, its `(contest_id, room)` index of V101): `user_id` moves, as decided.
- **Retirement is the first writer of `users.deleted_at`** (review fix): nothing deletes an
  account today, `users.email`'s unique key still holds a deleted row's address, and some
  readers don't filter `deleted_at` (`matchAccount`'s email path,
  `User.findByEmail(...).headOption`; `addToContest` after `User.lockRow`). So the retirement
  clears the retired account's `users.email` (the old address in the change log row's
  `old_value`), revokes its tokens; the email path and `addToContest` (in the caller, next to
  `mayJoin`; `lockRow`'s SQL unchanged) treat a deleted row as none; every `round_user` insert
  re-checks the account under its users-row lock; each reader of `users` is checked and listed in
  the PR. It is built once, in the existing-accounts plan's A-4 ("Retiring an account"), and J-2
  calls it.
- **One active join per account** (review fix): the job's dedupe key is
  `account_join:user:<kept id>`; its start, under both accounts' users-row locks, refuses an
  account that is in another active join (kept or retiring), and recomputes the preview (a preview
  that changed since it was shown is shown again, not run).
- **Lock order** (review fix): a join step and the retirement take `job` (the step's own row) →
  `users` (both accounts, ascending id) → `user_contest` / `account_token` → `round` → `images` →
  `vote` → `round_user` (shared, §7.1) → `criteria_rate` → `vote_aside` → `round_image` → ...
  This replaces the Safety section's "the users' rows locked last". Why: account transactions
  (`addToContest`, share links, the link acceptances) take `users` before memberships and links and
  never lock a round. Round-side code takes `users` rows only through `round_user`'s foreign key
  (V37), as shared locks: round creation (`RoundService.insert`: `addUsersIn`, then `Round.lock`)
  takes them users-first, which agrees; adding a juror (`RoundRebalanceService.add`, its in-review
  insert, `RoundRebalanceHandler`'s "stop" phase) takes them round-first, which would invert it.
  So while a join runs, adding either account to any round is refused (checked under the round
  lock in those three places), the join refuses to start while a queued or running
  `round_rebalance` has either account as its add, and round creation drops the retiring account
  ("Other jobs on the same rounds" above). A planned addition to db practices §7.1, made with J-2:
  "`users` (ascending id) → `user_contest`, `account_token` come before `round` in a transaction
  that holds both (the join, the retirement); a round-first transaction never inserts a
  `round_user` row for an account in an active join".
- **Who-did-it columns stay:** `account_change.actor_id`, `account_token.created_by`,
  `job.created_by`, `round_image.dq_by`, `round_image_event.created_by`,
  `round.status_changed_by` (V105) and the criteria catalogue's `created_by` keep naming the
  retired account (its row stays, `deleted_at` set, so the name still shows); the join is a record
  of its own (below).
- **Account change log** (`AccountChangeLog`, `account_change`, V96): its field CHECK gains
  `account_joined` (the join's migration); one row on each account, the other's id in `comment`,
  so both edit pages show it. The retired account's history stays under its id.
- **Admin email override** (2026-11-12, `UserService.changeEmailByAdmin`,
  `users.email_set_by_admin_at`, V96): an address an admin set is unverified until its owner proves
  it, so it never counts as "the verified email" when choosing the kept account.
- **Shared links and Set password** (V94, stack/94): "every unused link revoked" covers shared
  ones; a password an admin set leaves the account's email unverified, which the kept-account
  rule already handles.
- **OAuth is not on in production** (26.11's option 2): no wiki account there is verified and
  few identities exist, so J3's re-link costs little until Wikimedia and Google sign-in are
  enabled.
- **Screening portions are normal:** one person may hold several juror accounts in a screening
  round on purpose (separate shares). The duplicates report pairs accounts only by its two
  criteria (the same normalised wiki account, the same verified email), never by name or by
  sharing a round, and a performer's **Keep separate** on a pair (a row of `account_join_request`
  in state `kept_apart`) hides it for good. Nothing suggests a join by itself; the only other
  entry is an admin's explicit choice in the edit form's `wiki.account.taken` panel.
- **How many duplicates** (the production copy, V101 dump of 2026-11-15): 86 groups of accounts
  with the same wiki account (case-insensitive), 278 accounts; 45 pairs, and groups of 3 to 11
  (most from the V61 merge of 2026-11-11: legacy one-account-per-contest rows with different
  emails, each with a password and a verified email). No wiki account is verified there. So the
  report lists **groups**, not only pairs, and a group of N is resolved by N−1 joins into one
  kept account, one job each (the kept account chosen once for the group, J2's rule); Keep
  separate applies per pair, so a screening portion can stay apart from the rest of its group.
  Whether to join a whole group in one job is left for later, if the per-pair joins prove slow
  in practice.

## Placement

With the contests rework (where S2 put duplicates), or as its own batch before it if the user
wants it earlier (recommended only if the chooser shows many duplicates in practice). **Decided
2026-11-16** (A4 of the [existing-accounts plan](2026-11-16-existing-accounts.md)): start now;
J-1 by a second agent in parallel with that plan's A-1 and A-2; then A-3, A-4, J-2, J-3; round
editing step 3 starts when the maintainer says, alongside.

## PRs

| # | PR | Size |
|---|---|---|
| J-1 | `account_join_request` (with `kept_apart`) and `account_change`'s `account_joined`, the next free migration (V107 if the existing-accounts plan's A-4 takes V106); the duplicates report (read-only; per contest for its admins, in full for root) with Keep separate | M, ~2–3 days |
| J-2 | The preview and the `account_join` job (steps under the lock order above, the votes sweep, collisions refused (J1), one active join per account, A-4's retirement reused, notices, the audit line, the change log rows), per J1–J3; db practices §7.1's addition; the edit panel's "Join" link | L, ~6–8 days |
| J-3 | Requests: the chooser's and the own profile's "I have another account", the proofs, cancel and expiry | M, ~3 days |

J-2 changes who may act on accounts: negative cases (an admin of only one of the two accounts'
contests; an organizer; a root account) and the job's restart and Retry cases.

## Invariant

A join always ends with exactly one active account, with one membership per contest (the user,
2026-11-03): the other account is retired (deleted, its sign-in methods gone), and every contest of
either has one `user_contest` row of the kept account.

## Decisions (J1–J3, decided by the maintainer 2026-11-16)

1 and 2 were deferred by the user on 2026-11-03, 3 came from the review of #240 (2026-11-13). No
decision of this plan is open.

**J1. A vote collision: always refuse.** A collision is the same file in the same round with a
row of both accounts, in `vote` or in `vote_aside`, where at least one of the two is rated (two
unrated rows are one assignment: one stays). Any collision blocks the join, open or finished
round; the preview lists them (round, file, both rates), and the accounts stay separate until
they are fixed by hand. No vote is dropped by a join. It happens mostly where one person was given
two shares on purpose (screening portions), so it is rare in a join someone asked for.
*Considered:* keeping the kept account's vote in an open round with the other to `vote_orphan`
(and refusing in a finished round until accepted); keeping the higher or newer vote.

**J2. Two verified emails and no request: the performer chooses** in the preview, with no
preselection, root and admins alike; both addresses get the notice (`UserService.systemSender`,
masked, no link). *Considered:* preselecting the account with more votes; only a request.

**J3. Identities in a join without the user's proof: unlink, re-link.** A join from the
duplicates report moves no identity: it unlinks them (tombstones, as an email change does), and
their holder links again from the kept account's own profile after signing in to it; the preview
and the audit line count them. A join from a request (the user proved both accounts) moves every
identity. *Considered:* moving only identities that prove the kept account (a Wikimedia identity
of its verified wiki account, a Google identity of its verified email).
