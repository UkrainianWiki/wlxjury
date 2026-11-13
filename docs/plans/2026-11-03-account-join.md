# Joining duplicate accounts (plan only, 2026-11-03)

The user's decision on duplicate wiki accounts (2026-11-03): an account chooser now (built on
`ukwikidev/oauth-login-rebased`: `OAuthChooserController`), and joining accounts later. This plan
covers the join; no join code exists yet. Context: S2 left duplicates unresolved ("a later
contests rework with an admin UI for merging accounts"); the OAuth port's note
([2026-11-03-oauth-login-port.md](2026-11-03-oauth-login-port.md)) has the chooser.

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
  verified email (with two verified: a deferred question, below); the other account's
  sign-in methods are retired (password set to the retired value, identities moved, links
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
  the same file in the same round) is listed in the preview; rule: keep the kept account's vote,
  is a deferred question (below).
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
  user's proof, which identities may move is deferred question 3 (below).
- **Sessions:** ended on both (`auth_version` bumped).
- **Links** (`account_token`): every unused one of both revoked.
- **Audit:** one `security.account` line per join (`account_joined keptAccountId= joinedAccountId=
  by= requestId=` with counts per table, ids only), and the request row kept.

## Safety

- **Preview** before anything is written: both accounts' memberships, rounds, vote counts, the
  conflicts (role, vote collisions, CoI name change), the identities that move (provider and
  username), and what the user chose; the performer
  confirms it (POST with CSRF).
- **One job** (`account_join`, `services.jobs.JobScheduler`): the round-first rule and the lock order
  of the write-path plan (`round → images → vote → criteria_rate → vote_aside → round_image`), one
  round per step, each step one transaction; the users' rows locked last; the job refuses to start
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

## Placement

With the contests rework (where S2 put duplicates), or as its own batch before it if the user
wants it earlier (recommended only if the chooser shows many duplicates in practice).

## Invariant

A join always ends with exactly one active account, with one membership per contest (the user,
2026-11-03): the other account is retired (deleted, its sign-in methods gone), and every contest of
either has one `user_contest` row of the kept account.

## Deferred questions

Decide when the join is planned: 1 and 2 deferred by the user (2026-11-03), 3 from the review of
#240 (2026-11-13).

1. **A vote collision** (the same person was a juror twice in a round and voted on the same file
   with both accounts). Deferred by the user (2026-11-03): decide when the join is planned. Options
   discussed so far:
   - in an open round, keep the kept account's vote and move the other to `vote_orphan`, with a
     log line;
   - in a finished round, refuse the join and list the collisions in the preview, until an
     organizer explicitly accepts the change to published results, or leaves the accounts
     separate.
2. **Two verified emails and no request:** which account is kept. Deferred by the user
   (2026-11-03): decide when the join is planned. Option discussed so far: root decides in the
   preview (no automatic choice).
3. **Identities in a join without the user's proof** (from the duplicates report; CodeRabbit on
   #240). A report can pair accounts by an unverified wiki name, so moving the other account's
   identities to the kept one could open the kept account (its password and roles) to whoever
   linked that name's placeholder. Not decided; options:
   - such a join moves no identity: it unlinks them (tombstones), and their holder links again
     from the kept account's own profile after signing in to it;
   - an identity moves only if the kept account's wiki account is verified by its holder and is
     the identity's name (Wikimedia), or the identity's verified email is the kept account's
     (Google).
   Joins from a request (the user proved both accounts) move every identity.
