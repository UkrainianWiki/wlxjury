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
  wiki account is the joined one's (verified if either was), and the preview shows a CoI change if
  the names differ.
- **Comments** (`comment`): author moved.
- **API tokens:** revoked on both accounts (shown once, never moved).
- **Identities** (`user_identity`): moved to the kept account (a Google or Wikimedia sign-in then
  opens it).
- **Sessions:** ended on both (`auth_version` bumped).
- **Links** (`account_token`): every unused one of both revoked.
- **Audit:** one `security.account` line per join (`account_joined keptAccountId= joinedAccountId=
  by= requestId=` with counts per table, ids only), and the request row kept.

## Safety

- **Preview** before anything is written: both accounts' memberships, rounds, vote counts, the
  conflicts (role, vote collisions, CoI name change), and what the user chose; the performer
  confirms it (POST with CSRF).
- **One job** (`account_join`, `services.jobs.JobScheduler`): the round-first rule and the lock order
  of the write-path plan (`round → images → vote → criteria_rate → vote_aside → round_image`), one
  round per step, each step one transaction; the users' rows locked last; the job refuses to start
  while a round of either account is being distributed (`round.distributing`), and a vote by either
  account during the job waits on the round's lock. `RoundImageInvariants.violations` checked per
  round after its step.
- Idempotent steps (a retried step finds its rows moved); Cancel leaves both accounts usable (the
  other account is retired only in the last step).

## Placement

With the contests rework (where S2 put duplicates), or as its own batch before it if the user
wants it earlier (recommended only if the chooser shows many duplicates in practice).

## Invariant

A join always ends with exactly one active account, with one membership per contest (the user,
2026-11-03): the other account is retired (deleted, its sign-in methods gone), and every contest of
either has one `user_contest` row of the kept account.

## Deferred questions

Both deferred by the user (2026-11-03): decide when the join is planned.

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
