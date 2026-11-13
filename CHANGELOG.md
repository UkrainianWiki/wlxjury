# Changelog

The development changelog of the WLX Jury Tool, for assessing how the project developed
(user-facing release notes may follow separately). Each release has two parts: **Features**,
what organizers and jurors notice, and **Development**, the important engineering changes
(frameworks, database, tests and CI, deployment tooling, migrations), where there are
any. Each entry gives the deploy date and when the work was developed. Merge dates to
`master` are not used, because the stacked PRs merge later.
Deploy dates come from production's migration history (`flyway_schema_history.installed_on`,
read 2026-11-03): a deploy is dated by the first migration it applied. Changes without a
migration are dated "deployed by" the next deploy that contained them, or from a deploy
record. Work that is built but not deployed is under **Unreleased**. The
[earlier releases](#earlier-releases-from-git-history) are dated by when the work was
developed (its first to last commit), or "released around" a git tag, with the release name
where a tag or `version :=` in `build.sbt` gives one. Earlier deploy dates may be added later.
Plans that aren't built
yet are in the [roadmap](docs/plans/2026-11-02-roadmap.md). PR numbers are the stacked series,
from #184 on (01–59 in `docs/plans/2026-11-03-pr-series.md` on `origin/stack/series-doc`, the
later ones in the roadmap and its deploy records).

**Versioning:** from the next release on, versions are calendar-based, `YY.MM` (e.g. `26.11`),
with `.N` for an extra deploy in the same month (`26.11.1`), and release headings will read
`## 26.11 (YYYY-MM-DD)`. Past releases keep their dates, and their tag names where tags exist
(`0.8`, `0.9`, `0.10`); they are not renumbered.

## Unreleased (developed, not yet deployed)

The next release is `26.12` (`build.sbt` says `26.12-SNAPSHOT`). Nothing yet: everything developed
up to 2026-11-13 was deployed in `26.11.4`.

## 26.11.4 (2026-11-13)

Deployed 2026-11-13, build `26.11.4-0d23398ae816` (release commit `0d23398a` on `release/26.11.4`),
schema V100 (migrations V99: `voted_at` in the vote gallery index, 12.4 s; V100: `vote`'s
single-column indexes dropped, 20 ms; on production), from `26.11.3`. PRs #301-#306, and the
release tool of #297 (in `26.11.3`'s commit, installed on the server just before this deploy).
Deploy record: roadmap, "26.11.4 deploy record".

**Features**

- **One juror's vote in the large view:** in an organizer's read-only view of one juror's votes,
  the side bar shows the juror's name, then the juror's vote in the gallery's vote colours with its
  time (e.g. "Selected · Voted 12 Nov, 19:36", "Rejected", "Unrated"), where it showed only
  "Voted …" and the vote itself was at the end of the often-collapsed file details, among every
  juror's votes. A rated round keeps its read-only stars and the time, with the name added. The
  juror's own voting view and the view of all the jurors are unchanged.

**Fixes**

- **Switching rounds in one juror's view:** the round menu kept the viewed juror in every round, so
  an organizer viewing a juror's votes who switched to a round where that person is not a juror got
  the error page "This user is not a juror of the round". The menu now keeps the juror
  only in the rounds where they are a juror; for the other rounds it opens that round's view of all
  the jurors, keeping the view, region and filter.
- **Stored times shown shifted:** times the database stored were shown shifted when the app's and
  the database's time zones differ, e.g. an invitation's or a shared link's expiry 2 h early. Fixed
  for job times (the image check's summary date included), invitation, shared-link, password and
  email link times, API tokens, the account change log, the conflicts of interest history, the
  disqualification date and the "deleted on Commons" date (as `vote.voted_at` was in `26.11.3`).
  What the times enforce (expiry, the job lease) was always right: it is compared in the database.
- **By rate:** in the one-juror By rate view in a vote-time order, a vote's time appears or changes
  at once, as on the gallery's tiles; it showed only after a reload.

**Development**

- **Deployment tool** (in the repository since `26.11.3`, installed on the server on 2026-11-13,
  before this deploy; `26.11.3` was deployed with the old tool): `wlxjury-release deploy` refuses,
  before anything changes, while a background job is queued or running, and lists each one (id,
  kind, contest, status); `--allow-running-jobs` deploys anyway with a warning (the jobs resume
  after the restart). A `job` table it cannot read stops the deployment too. This replaces the
  manual check before each deploy.
- **Stored times read as instants:** `db.scalikejdbc.DbTime` reads a database-written `TIMESTAMP` as
  `UNIX_TIMESTAMP(col)` and builds the `Instant` from it, as `VoteJdbc` does for `voted_at`; nothing
  written changes, and where the zones match the reads are as before. `DbTimeZoneSpec` writes in
  one session zone and reads in another, both different from the JVM's. `RawSqlSpec` allows
  `DbTime`'s checked column names.
- **V100:** drops `vote`'s four single-column indexes from V7, named by no code: `vote_round_idx` (round_id)
  and `vote_jury_id_idx` (jury_id), prefixes of composite indexes, whose index-merge intersection MariaDB 10.6
  chose for a juror's votes in a round instead of the gallery index; `vote_rate_idx` (rate), used by no plan;
  `vote_page_id_idx` (page_id), a prefix of `vote_page_jury_round_uidx`, which now serves the foreign key
  `vote_page_id_fk` (cascade and refusal unchanged). On production's copy the intersection's statements are
  3–10x faster (a large round's gallery page 107 → 28 ms, a large view step 197 → 43 ms, a 2026 round's page
  27 → 4 ms), none slower; a round's vote inserts ~14% and deletes ~21% faster; ~335 MB freed. Metadata-only
  (`INPLACE`, `LOCK = NONE`, 20 ms on production), rerunnable. `vote_aside` and `vote_orphan` keep theirs.
- **V99:** `vote.voted_at` added as the juror gallery index's last column, the index keeping its name
  (`vote_jury_round_rate_mon_page_idx (jury_id, round_id, rate, monument_id, page_id, voted_at)` on 10.6;
  on MariaDB 10.8+ V85's `vote_jury_round_ratedesc_mon_page_idx` with `rate DESC`, V85's swap included), online
  (`INPLACE`, `LOCK = NONE`, 12.4 s on production's 3.35M votes), rerunnable from every state it reads. The
  vote-time orders read only the index: 2–4x faster on production's copy (worst page 1 82 → 26 ms, large view
  step 243 → 80 ms), as fast as file order; vote writes cost the same. After the move to 10.11 it is V99's
  script that runs again, not V85's (docs/deployment.md); the start warns also when a gallery index lacks
  `voted_at` last (`Migrations.galleryIndexState`).
- **Guides' screenshots and the Gatling fixture:** the guides' 28 screenshots regenerated from a
  clean fixture (vote times, the results bar, the one-juror block in the large view, the invitation
  mail choice, expiry times read right); the organizer guide says how the results bar looks below
  about 1700 px. The fixture's "Binary Round" is "Yes/No Round" (`fixture-v8`), and the restore from
  the cached dump finds the rounds by exact name and restores jurors in creation order, like a cold
  build.

## 26.11.3 (2026-11-12)

Deployed 2026-11-12 (evening), build `26.11.3-564e74e1a1d3` (release commit `564e74e1` on
`release/26.11.3`), schema V98 (migrations V97: `vote.voted_at`, 44 ms; V98: `users.password`
nullable, 2 ms, already fixed by hand that afternoon; on production), from `26.11.2`. PRs #296-#300,
except the release tool's job check (#297): it was installed on the server on 2026-11-13 (see
`26.11.4`). Deploy record: roadmap, "26.11.3 deploy record".

**Features**

- **Image check result on the page:** root's **Check every round's images** (Rounds page) and an admin's
  **Check round images** (Edit round) show the check's progress there, polled like a distribution, and then a
  one-line summary ("Image check (job 29, 2026-11-12): 1,823 rounds checked, no mismatches", or the
  rows added, fixed or removed, the orphaned criteria rates archived and the rounds skipped); a failed or
  cancelled check shows its status with a link to the job. No longer raw JSON at /admin/jobs/:id only.
  The all-rounds summary is root's alone.
- **Sort by vote time** (the maintainer's design A of 2026-11-12): a juror's own gallery's
  order menu adds **Newest votes first** and **Oldest votes first** (on the **All** and **Rated**
  tabs with **In file order** and **By my rating**; on a yes/no round's **Selected** and
  **Rejected** tabs with **In file order**; the **Unrated** tab stays in file order). Unrated files
  come last in both; votes cast before this release, without a time, count as older than any
  timed one; files of the same time are in file order, so pages and the large view's neighbours
  stay stable. A vote's time is when its rate last changed to another value (the database clock);
  the same rate again keeps it, a vote cleared to unrated has none. A large view vote that moves
  the file in such an order says it moved "by vote time". An organizer viewing one juror's votes
  (the juror menu) gets the same order menu, by that juror's votes and times, with **By rating**
  in place of **By my rating**; the views of all the jurors together are unchanged. In a vote-time
  order the tiles and the by-rate list show each vote's time, and the large view of one juror's
  votes shows it in every order ("Voted 12 Nov, 14:05", formatted in the browser in the viewer's
  time zone and the page's language, `vote-time.js`; "Time not recorded" for a vote without one);
  never in the views of all the jurors. Juror and organizer guides updated. On production, votes
  cast from 2026-10-28 to 2026-11-12 got their times from the access logs after the deploy, where the
  logs allow (the backfill under Development).
- **One juror's votes on the tiles:** an organizer viewing one juror's gallery sees that juror's
  vote on each tile, read-only: a yes/no round's frame and thumb (selected or rejected), a rated
  round's stars or ✗. Before, only the juror's own tiles showed their votes, so an organizer's
  view of a juror in any order showed none (not new with the vote-time orders, which made it
  matter). The views of all the jurors together keep their tallies.
- **The organizers' results bar stays on one line:** with the juror menu and the order menu it
  wrapped the pager onto a second line (Ukrainian, up to ~1600 px wide). In that bar, below
  1700 px the order menu and its links (Contests, Contest files, Users, Rounds, Statistics) are
  icons and the region menu shows the place without "Region:"; each text is still read by screen
  readers and shown as a tooltip. Other pages' bars are unchanged.

**Fixes**

- **Ukrainian:** a round that is not active shows «Ні» (no) in the rounds list's Active column, not «Немає» (absent).
- **Adding a new person failed since 26.11** with a server error, and Import Users silently skipped them (counted as
  "could not be added"): production's `users.password` was still `NOT NULL` from a 2015 Play evolution, while
  accounts are created without a password since S2 (26.11, ddf45d50; fresh and test databases had V2's nullable
  column). Fixed on production by hand on 2026-11-12 and recorded by **V98**. Adding an email that already had an
  account was not affected.
- **Import Users no longer fails silently:** a line that fails with an error is listed on the import page by line
  number and the email or wiki account entered, with an error reference that is on the server log's
  `user_import_line_failed` line (the classes and stacks of the exception and its causes, never their messages),
  and a line with an invalid wiki account name is listed as such; the other lines are added as before (each line
  in its own transactions), and the form comes back with only the lines not added, to import again. The import
  redirects to that page (post/redirect/get: a reload imports nothing again, so no second invitation mail); the
  list waits on the server for that page, once, for its admin only, up to 10 minutes and 1,000 lines (the rest
  counted; after a restart the page says how many lines it had). No mail goes out for a failed line: an
  invitation's change log entry is written with its token (the account's row locked first, as a link's
  acceptance does), and its mail is the last step; whether it was mailed is set after it, and a flag that can't
  be written is logged (`account_change_mail_flag_not_recorded`) without failing the add; the change log then
  shows the invitation's mail as "not known". The "could not be added"
  count (`users.not.added`) is gone: nothing skips lines any more.

**Development**

- **V98:** `users.password` nullable (`VARCHAR(255) NULL DEFAULT NULL`, `ALGORITHM = INPLACE, LOCK = NONE`),
  only where it is still `NOT NULL` (production's, applied there by hand on 2026-11-12); a no-op on fresh databases.
  `scripts/schema_parity.py` compares a database's columns with a freshly migrated one's and flags a column `NOT
  NULL` only in the target, for the release rehearsal on the production copy (Release checklist).
- **V97:** `vote.voted_at` and `vote_aside.voted_at`, `TIMESTAMP(3) NULL DEFAULT NULL` (no implicit
  default or ON UPDATE under any `explicit_defaults_for_timestamp`), added `ALGORITHM = INSTANT`;
  no index (the orders sort by expressions; V99 adds it to the gallery index). Existing rows stay NULL
  (the one-off backfill below filled production's recent ones). Set by `VoteJdbc.rateIn` and
  `CriteriaRate.updateRateIn` (`VoteSql.votedAt`), and by `VoteJdbc.create` / `batchInsert` for a
  rated row; moved with the vote between `vote` and `vote_aside`. Read as
  `UNIX_TIMESTAMP(voted_at)` (`VoteJdbc.votedAtEpoch`), and a given time written by
  `FROM_UNIXTIME`, so the instant is right whatever the JVM's and the database session's time
  zones (a JDBC timestamp read showed every time 2 h early with a UTC session and a Kyiv JVM;
  found and fixed before the deploy).
- **Vote times from the access logs (one-off):** `scripts/backfill/voted_at_from_logs.py` (Python
  3.8, standard library only; 48 unit tests, run on CI) fills `vote.voted_at` for votes cast before
  V97 from Apache access-log extracts (`docs/deployment.md`, "One-off: vote times from the access
  logs (after V97)"). Per round, file and juror, from the requests it can attribute with certainty,
  it takes the time the current value was first chosen in its last run of same-value requests (the
  app's rule), and writes it only where the vote still has that rate and no time, so a rerun or a
  vote changed since is never overwritten; an undo file is written alongside. Run on production
  after the deploy: 37,894 votes got their time (extracts from 2026-10-28 23:13 to 2026-11-12 17:12
  UTC; the dry run matched 37,894 and `--check` found every one with the time from the logs).

## 26.11.2 (2026-11-12)

Deployed 2026-11-12, build `26.11.2-5fee540412f4` (release commit `5fee5404` on `release/26.11.2`),
schema V96 (migration V96: `account_change`, `account_token.unsent`, `users.email_set_by_admin_at`;
111 ms on the production copy), from `26.11.1`. PRs #292-#295. Deploy record: roadmap, "26.11.2 deploy record".

**Features**

- **Admins fix jurors' emails** (the maintainer's decision of 2026-11-12): an admin who may edit
  an account, and root, change its email at once, also when it has a password. The person keeps
  the password and signs in with the new address (unverified, every session ended); a verified
  former address gets a short notice naming who changed it. One's own email change still goes
  through the link mailed to the new address.
- **Invitation mail is a choice:** adding a person, the mass import and the edit page have
  **Send an invitation email**, unticked by default; unticked, nothing is mailed, and the admin
  shares a link or sets a password instead. An invitation a role waits for is then kept, shown as
  **Not sent** in the invitations list, to send or share later.
- **Account changes:** every change an admin or root makes to another person's account (email,
  password, role, name, wiki account, accounts created, invitations, links, ended sessions), with
  whether a mail went out and an optional comment, is logged; the contest's admins and root see it
  on **Account changes** (from the users page) and on the person's edit page (V96). A change is
  logged in the account's own contest only (root's edit of an account in no contest: root's
  log). An account that can sign in (a password, Google or Wikimedia) whose address an admin set
  is invited only by who may set its password (not a fellow admin); a stale edit page doesn't
  revert an address changed meanwhile.
- **Who is signed in:** the user menu starts with the signed-in account (one person may have an
  account per contest or year): its name, wiki login, sign-in email and role in the current
  contest (root: Root), as text that the menu's arrow keys skip; long values wrap on a phone.

**Fixes**

- **Profile:** the password change form asks for the current password first, then the new one.
- **Account mails:** the email-change confirmation says what it is about (the WLX Jury account,
  the site, asked from the account's profile); the invitation without a contest names who invites.
- **Users:** the edit page no longer has the **Sort** field (root only; the maintainer's decision
  of 2026-11-12). The users list and the round form's jurors are in the order the accounts were
  added (account id), which every account without a sort value already had (a round's
  distribution already went by account id). `users.sort` stays in the database, no longer read
  or written.
- **Addresses ending in `/`:** a page address with a trailing slash that the site doesn't have,
  e.g. `/auth/` (typed or pasted), was the "page not found" page; it now redirects (302) to the
  address without the slash, the query kept (signed out, the page then asks to sign in as usual).
  Only within the site: an address that would lead elsewhere (`//host/`, encoded slashes or
  backslashes) stays a 404, as does a form sent to such an address.
- **Users page:** in the add-person row the role select and the add button sat at the top of the
  row, level with the field labels, not with the inputs (since the Bootstrap 5 templates hid the
  role's label). The role has its label again and the button the label's space, so both are level
  with the inputs, also when an error shows under a field.
- **Profile:** saving one's own profile goes back to the page it was opened from (the user menu's
  **Profile** link carries it; kept when a save is refused, and on the profile when the save has
  a notice to show, e.g. an email change's link sent). Only a page of the site, never the profile
  itself, sign-out or a contest switch; otherwise the save goes where it did before.
- **Jurors with last year's membership:** the contest menu no longer lists a contest where the
  person is only a juror and no round is open to them (the current contest always shows; an
  organizer or admin keeps all theirs), so with one contest left there is no menu. For a juror, the
  start page (and a gallery or large view without a round) opens a round of their latest newer
  contest (a higher contest id) when the current one has none, and the session goes on there;
  never an older one, whose final round usually stays open. "No round configured yet" no longer
  links to the start page, which showed it again: it has **Logout** and the person's other
  contests to switch to (last year's open round among them).
- **Gallery order:** a juror's Unrated, Selected and Rejected tabs no longer offer **By my rating**:
  every file there has the same rating, so the order menu's place says **In file order** as plain
  text. The order chosen on the other tabs is kept in the tabs' links (and the remembered order is
  unchanged), so going back to **All** shows it again.

**Development**

- **Migration V96:** table `account_change` (indexes on `(contest_id, created_at)` and
  `(user_id, created_at)`, rows deleted with their account; history, not a security control),
  `account_token.unsent` and `users.email_set_by_admin_at` (both `ALGORITHM = INSTANT`). Rollback
  after reopen in `FlywayUpgradeSpec`: the live unsent invitations revoked, the columns and the
  table dropped.

## 26.11.1 (2026-11-12)

Deployed 2026-11-12, build `26.11.1-cc645bcf6f0c` (release commit `cc645bcf` on `release/26.11.1`),
schema V95 (no migrations), from `26.11`.

**Features**

- **Dark mode:** secondary buttons and the pager's links have a visible light border and text
  (at least 3:1 and 4.5:1 on the dark greys), with distinct hover, pressed and disabled states;
  they were grey on grey.
- **Light mode:** secondary buttons have darker text (`#343a40` instead of `#6c757d`), so they no
  longer look disabled.

## 26.11 (2026-11-11)

Deployed 2026-11-11, build `26.11-c291debdc684` (release commit `c291debd` on `release/26.11`),
V71–V95 in one go, from `f9c31ce5` (V70); the earlier plan to ship V71–V81 on their own was
dropped (decided 2026-11-05: that branch removed admin-set passwords before
the shareable links that replace them existed). The sections below are its parts, in the order
they were developed. Deploy record: roadmap, "26.11 deploy record".

### Developed first (V71–V81)

Developed 2026-10-31 to 2026-11-02, PRs #211–#231.

**Features**

- **Light, dark and auto colour modes**, on a refreshed layout; the choice is kept per user (#211–#214, V71).
- **Yes/no rounds:** the all-jurors gallery shows each file's approvals, and "selected by at
  least N" filters by them (#215, V72–V74).
- **REST API** under `/api` with Swagger UI: an API for root only, with root's tokens on the
  profile page (#218, V75).
- **Accounts:** accounts are created by invitation only (no open sign-up); an email change is
  confirmed from the new address (#225, V77).
- **Sessions:** "Keep me signed in", "Sign out everywhere", and an idle and an absolute
  lifetime (#229, V80).
- **Criteria rounds:** misplaced or duplicate criteria votes are refused, and sums stay
  consistent (#226, V78).
- **Stopped jurors** can no longer vote in that round. The image proxy serves only the page
  sizes (#230, V81).
- **Faster organizer views:** ranked and grouped all-jurors pages (#222, #224), live at deploy;
  the new round-image read path (`roundImage.read`) is off at deploy.
- **Security hardening** of roles, host names and cookies, sessions, password storage,
  output escaping, logs and exports, and security headers (#219–#231). The role fix is already
  in production as the 2026-11-02 hotfix (#223, V76).

**Development**

- One self-hosted layout on Bootstrap 5 (#211–#214). One database pool (#216). No inline
  scripts, for a strict content security policy (#217).
- Security rules, a review checklist and guard specs (#228).
- **Migrations:** V71 user theme; V72–V74 binary aggregate votes; V75 API tokens; V76 role
  allowlist; V77 account verification and tokens; V78 criteria vote integrity; V79 API token create
  nonce; V80 session upgrade window; V81 legacy password retirement.

### Developed second (V82–V84)

Developed 2026-11-02 to 2026-11-03, PRs #232–#236.

**Features**

- **Criteria catalogue:** admins pick each round's criteria from a shared catalogue (#235, V83).
- **Round images:** each round keeps its list of files, and root's "Check every round's images"
  repairs any drift (#236, V84).

**Development**

- Tables renamed: `selection` to `vote`, `rounds` to `round` (#232, V82).
- Flyway 13.12.0, run by the app through `db.Migrations` (#234). A faster test suite (#233).
- **Migrations:** V82 table renames; V83 criteria catalogue; V84 `round_image`.

### Developed third (V85–V95 and the rest)

**Features**

- **Passwords are set by their owner, through a link, or by root or an admin** (S2, V77 and V94,
  see also "Accounts" above). S2 had removed admin-set passwords; **"Set password" on another
  account's edit page is back** (stack/94, the maintainer, 2026-11-10: "while we test and learn new
  ways of login and reset password, let's keep good old user password edited by an admin/root"),
  for root and, only where a link to share for a reset is allowed, contest admins (their own
  active jurors and organizers). It does what using a shared link does: the person is signed out
  everywhere, API tokens are revoked, Google and Wikimedia identities unlinked, unused links
  revoked, and the email and wiki account unverified; logged as `password_set_by_admin` by id. A
  wiki account shared by several accounts signs in by email. Legacy SHA-1 passwords keep working for three months after the deploy (V81),
  then need a reset. Sessions from before the deploy are upgraded for 30 days (V80).
- **Organizers are read-only, apart from comments** (stack/95, the maintainer, 2026-11-10): only
  a juror of the round's contest votes, by any path (gallery, large view, keys, criteria), so a
  juror made an organizer votes no more and keeps their earlier votes; admins never vote, nor
  root unless a juror of that contest. A refused gallery vote says so ("You can no longer vote in
  this round"). The statistics page shows the jurors' pause buttons to the contest's admins
  only; others see each juror's voting as "Active" or "Paused".
- **"Leave out conflicts of interest" on an open round only sets the flag** (P1b); disqualify on
  the stopped round's conflicts page, which keeps the votes (#244).
- **The thumbnail fix:** thumbnails load at Wikimedia's standard widths only (#258, below).
- **Root's "Check every round's images"** repairs a round's file list if it ever drifts (V84).

- **Votes don't fail** when jurors vote while admins change rounds (#239, 2026-11-03).
- **Sign in with Google or Wikimedia**, to an existing account (#240, V87, 2026-11-03): built, but
  not offered to users yet; it needs the providers set up and tested first, so the user-facing
  "what's new" leaves it out.
- **Conflicts of interest:** on a stopped round, its conflicts page disqualifies a juror's
  conflicted files keeping their votes, and requalifies them (#244, 2026-11-03).
- **Disqualification history:** a round's conflicts page lists every disqualification and
  requalification of its files (when, which file, the reason code, the status before and after,
  by whom), for its admins and organizers. A requalified file goes back to the status it had: a
  file deleted on Commons while the round was open stays deleted, its votes aside, instead of
  coming back. A "Stop the round" button, with a confirm, on an open round's conflicts page
  (developed 2026-11-04, V88).
- **Criteria rounds' results:** per-criterion averages in the file list, the by-rate list, the
  CSV and the all-jurors large view, and stars per criterion (#247, 2026-11-03).
- **A round's file score, one option on the round form** (admins; developed 2026-11-05, V90):
  the average over the jurors who rated the file (the default), the average over all its
  assigned jurors with a missing rate as 0, or the total of the rates. Every view of the round's
  results uses it, shows what it divides by ("14.50 (29 / 2 of 3 jurors)", "29 (sum of 3
  jurors)") and ranks by it, and a next round's top files and "min average rate" use it too. It
  can be changed at any time; a next round's distribution keeps the rule it started with.
  Changes: the all-jurors by-rate gallery and large view no longer count a juror's missing rate
  as 0 by default (choose "assigned jurors" for that), and rank by the average rather than the
  sum. Rounds with the old hidden "optional rate" flag become total-of-the-rates rounds: they
  show the sum instead of the sum over the round's active jurors (same order), and a next
  round's "min average rate" from one now compares the sum (the form's hint says so).
- **A warning beside the file score on the round form** (admins; developed 2026-11-05): for a
  rated round with a vote of stars (not for one with worst marks only, where every file scores 0
  under every rule), it says that saving a different rule re-scores and re-orders the round's
  results on every page at once (nothing runs in the background), that a distribution already
  queued keeps its rule, and which later rounds took their files by this round's scores (those
  files stay; distributing new files to them from now on uses the new rule). In the page without JavaScript;
  with it, shown once the select differs from the saved rule.
- **The worst mark in rated rounds**, a round form option, off by default (admins; developed
  2026-11-05, V92): jurors may give a file the worst mark instead of stars, by a button (a cross
  icon) in the large view and on the gallery tiles, or Delete; clearing a vote stays apart (its
  own button, shown once the file has a vote, or Backspace; 0 still clears). A worst mark counts
  as 0 in every average and total and as a vote in a juror's progress and the round's "jurors
  who rated it"; the all-jurors by-rate gallery, the file list and the large view show the same
  cross icon and the number of worst marks beside the score (the words for screen readers), a
  juror's own views their mark; the CSV writes " — worst marks: n" after the score, as its legend
  says. A next round can leave out files with at least N worst marks, as it can a yes/no round's
  rejections. Not for criteria rounds or yes/no rounds (the form refuses it).
- **One product name and the contest's words** (developed 2026-11-05): "WLX Jury", untranslated,
  in the top bar, the page title and the sign-in heading; "Contest" for the former "Campaign";
  "Juror" for a person's role everywhere roles are named ("No role" for none); in Russian and
  Ukrainian "раунд" for a round throughout; messages that say whom to ask name the contest
  admin, who invites. The sign-in page is one centred column; its button reads "Sign in".
- **"Rejected by at least N" in two modes** (admins; developed 2026-11-05, V93): a next round
  leaves out the files rejected by at least N jurors, as before, or takes only them, to review the
  rejected files in a round of their own; from a yes/no round's rejections or a rated round's
  worst marks.
- **Several previous rounds, each on its own** (admins; developed 2026-11-10, triage R196-1): a
  next round from several previous rounds checks each file in each of them on its own, with that
  round's jurors, votes and file score; a file is taken if it passes in at least one (the user's
  rule), and a top-N ranks it by its best round. Before, its votes were added up across the
  rounds and scored by the first round's rule. The previous rounds must now share the scale and
  the number of criteria, not only yes/no or rated (pre-deploy check: the roadmap's A10).
- **"Leave out files that are in round(s)"** (admins; developed 2026-11-10, V95): a new round leaves
  out every file that is in the chosen other rounds of the contest, whatever its votes there, with
  every other filter, on its creation and on "Distribute new files"; e.g. the files of a previous
  round that didn't pass into round X (the admin guide's example).
- **Links to share for setting a password** (admins and root; developed 2026-11-05, V94): besides
  sending an invitation or root's password reset by email, "Create a link to share" on the users
  page (an invitation) or on an account's page (a new password) shows the same single-use link
  once, with a copy button, its expiry and a warning to send it only to the person, privately;
  a new link, by email or to share, makes the previous unused ones stop working. It also works
  for accounts without an email, which then sign in by their wiki account. The users page lists
  each account's latest invitation and an account's page its latest links as active (until
  when), used, expired or revoked, and how they were given. Root may create one for any account;
  a contest admin only for jurors and organizers whose every contest is one they administer (a
  new password only for active members; a pending invitee gets the invitation's link). Using a
  shared link marks the account's email and wiki account unverified, since nobody proved them:
  an admin of another contest who adds it then sends an invitation by email, which the
  address's owner accepts. Not offered for accounts without an email where Wikimedia sign-in is
  available (they sign in with it).
- **An international import's countries** (admins; developed 2026-11-10, triage R207-1): a
  winners page's files are limited to those in the contest's country categories when any of the
  page's files is in one (before: when the countries' root category had files of its own, so a
  root holding only the countries' categories imported the logos and icons too, and no file got
  its country). When none is, all the page's files are imported, and the import's status says
  so ("no file in a country category: none left out"). A national contest's page import is
  filtered as before: its root is the international one, which has files of its own on Commons.
  A category or template import of the international contest now gives its files their
  countries too. A file Commons sends without its image info no longer fails the import (and
  every retry): it is left out, and the other files get their countries.

**Development**

- MariaDB 10.11 support; tests run on 10.6 and 10.11 (#237, developed 2026-10-19 to
  2026-11-03).
- READ COMMITTED isolation, round-first locking and retry against deadlocks (#239).
- The deployment adoption scanner names credentials by their leaf key (#241). Database
  access practices and guard specs (#242).
- Quality: CI on every PR (#238, #243); about 2,150 tests; coverage baseline Scala 89.87 %
  of statements, JavaScript 96.76 % of lines, the vendored gallery excluded
  ([CI plan](docs/plans/2026-11-03-ci-and-scalawiki-packages.md#test-coverage-added-2026-11-03-pr-60)).
- Documentation consolidated into practices docs (testing, workflow, operations,
  integrations, UI, dependencies) with a history index and a docs index
  ([docs/README.md](docs/README.md); #245, PR 62).
- Calendar versions (`YY.MM`; the build is `26.11-SNAPSHOT`); the CodeRabbit request and
  review triage tools in `scripts/review/` (#250).
- An English juror guide, `docs/user/juror-guide.md`, written from the tool as it is; not
  published yet.
- **Documentation:** English organizer and admin guides and "what's new in 26.11"
  (`docs/user/`), anchored for later in-app help, with screenshots made by an opt-in browser spec
  (`browser-test/tests/docs-screenshots.spec.js`, `DOCS_SCREENSHOTS=1`), checked against the rejection
  filter and the shareable links as merged. The screenshots show public-domain and CC0 Commons
  photos (Wiki Loves Monuments, Ukraine and other countries; four lists by contest and country,
  fetched by `browser-test/docs-photos.py`, never committed; credits in
  `docs/user/images/README.md`) through the fixture's `-Dui.images=docs` mode, with four more shots
  (the set-password page, the gallery top bar, the score rule warning, the access-denied page).
- Debian/RPM packages and the Vagrant setup dropped; the ZIP is the release artifact. The dead
  Travis and AppVeyor configs and the Heroku files (`Procfile`, `system.properties`) removed.
- Java 17 baseline: compiled with `-release:17`, Java 11 support dropped (CI already ran 17).
- Browser tests: Playwright in one current build each of desktop Chromium, Firefox and WebKit,
  a CI job per engine on every PR, against the screenshot fixture with Commons stubbed; votes,
  Back after a vote, the colour modes, refusals and organizer pages, with zero CSP violations and
  console errors per page and an accessibility scan ([testing practices](docs/testing/practices.md) §10).
- A compatibility lint of our scripts and stylesheets against the supported browsers
  (`.browserslistrc`: the last two majors, Chrome 109, Firefox 115), a step of CI; older versions
  are linted, not run in a browser.
- Operations: `wlxjury-release prune`, run after every successful `reopen`, keeps the current
  release and four before it and the dumps of the five newest operations that took one (by
  count; an age rule is off by default), each with the release its restore switches back to, and stops rather than guess; deploys refuse an image cache that
  isn't the service user's or is group- or world-writable; an alert-only monit check of the app,
  quiet during maintenance (`conf/monit/`); no containers on production for now (developed
  2026-11-04; [operations practices](docs/operations/practices.md) §3.3a, §4.5).
- The unused Apache thumbnail config (`conf/apache/jury-images.conf`, its test image and Compose
  service) removed; the app's proxy (`ImageProxyController`) serves thumbnails.
- No Scaladoc in the release: the ZIP loses `share/doc/api` (730 files, 42 MB unpacked; the ZIP
  121.5 → 116.2 MB), and `sbt dist` no longer runs Scaladoc (208 → 72 s here).
- docker-compose works again and is tested: `sbt Docker/publishLocal` builds the image (the ZIP's
  files on a Java 17 JRE, non-root, three jar layers), MariaDB 10.6.22, secrets from `.env`,
  a CI smoke job (`compose.yml`); scalawiki's pin is one file, `project/scalawiki.sha`
  ([deployment](docs/deployment.md), "Run with docker-compose").
- **Thumbnails at Wikimedia's widths only:** the image proxy's redirects to `upload.wikimedia.org`
  (a file not stored or of unknown size, a failed fetch, an inconclusive Commons check) go to the
  next standard width up (`Global.wikimediaStep`), or to the file itself once that reaches the
  file's width, never the page's width, which Wikimedia has refused with 400 since
  2026-05-13 (T414805); the test Wikimedia refuses them too; the unused `thumb.php` url helper
  removed (developed
  2026-11-04; [integrations practices](docs/integrations/practices.md) §1.8).
- The vendored gallery (Unite Gallery 1.7.28) is inventoried and tested: every wlxjury patch is
  listed with its commits, and the formerly unmarked ones are marked. Jest runs the gallery in
  jsdom, with a test per patch that fails when the patch is reverted. The lightbox slide's url
  is now escaped too. The patched copy stays until our own tiles replace it, planned later
  with the other frontend changes (developed
  2026-11-04; [docs/vendor/unitegallery.md](docs/vendor/unitegallery.md)).
- Dependency updates, part 1 (developed 2026-11-04):
  - Unused and duplicate jars are off the runtime classpath: JMH moved to `Test`; scalawiki's chart jars, its play-json 2.9.4 and `commons-logging` are excluded. `lib/` went from 209 jars, 128.4 MB to 196 jars, 121.5 MB.
  - play-mailer 10.1.0 (`org.playframework`), Play 3.0.12 and Scala 2.13.17. Scala skips 2.13.18, which miscompiles a single `Int` literal passed to `Long*` in a method with a further parameter list ([dependencies practices](docs/dependencies/practices.md) §1.2b).
  - ScalikeJDBC 4.3.5, commons-io 2.22.0 and imageio-webp 3.15.3. The unused webjars-play, with its webjars-locator chain, is removed.
  - Test-only: scalatest 3.2.20, os-lib 0.11.8 and jna 5.19.1.
  - sbt-scoverage 2.4.4, sbt-coveralls 1.3.15, sbt-digest 2.1.0, sbt-jmh 0.4.8 and sbt-native-packager 1.13.0, with an identical ZIP layout.
- Dependency updates, part 2, step 1 (developed 2026-11-04): the tests run on HikariCP, the pool production uses, instead of ScalikeJDBC's commons-dbcp2 default, with no change in their speed. commons-dbcp2 and commons-pool2 are out of the build. `lib/` has 185 jars, 120.4 MB. joda-time stays, because scalikejdbc-orm's mappers load it.
- CI actions on Node.js 24, every one pinned by commit SHA: `checkout` 7.0.1, `setup-java` 6.1.0
  (its new JDK cache left off), `setup-node` 7.1.0, `cache` 6.1.0, `upload-artifact` 7.0.2 and
  `download-artifact` 8.0.2, each released 14 days or more before; the coverage job on Node 22
  as the others (developed 2026-11-05; [dependencies practices](docs/dependencies/practices.md)
  §2.2).
- Coverage on PRs: a comment per PR, updated on every Coverage run, with the Scala and JS totals
  and the change against the base branch (`coverage-comment.yml`, which reads the run's summary
  as validated data and never the PR's code), and Coveralls through its GitHub Action, off until
  the repository variable `COVERALLS_ENABLED` is set; the unused `sbt-coveralls` plugin removed
  (developed 2026-11-05; [testing practices](docs/testing/practices.md) §9.5).
- `round_image_event`, an append-only table of disqualifications and requalifications, written
  in the action's transaction after its `round_image` rows (last in the lock order, round
  locked first; `StructuralWritesSpec` scans it); the requalify reads the latest event's prior
  status (developed 2026-11-04; [round images plan](docs/plans/2026-11-02-round-images-two-step.md) §15).
- Per-file vote counts on `round_image` (assigned, voted, approvals, rejections, rate sum), kept
  in the background, not yet read (P2a): a juror's vote marks its file in `round_image_dirty`
  and a bulk write bumps its round's `counts_gen`, both in their own transactions, and a timer
  (`modules.RoundCountsTask`, `roundCounts.*`) recounts in windows of 2,000 files; the
  migration leaves every round dirty, so the counts fill in after the deploy (developed
  2026-11-05; [round images plan](docs/plans/2026-11-02-round-images-two-step.md) §16).
- Run hardening after a night run lost to the host's sleep (developed 2026-11-05): jobs whose
  runs keep ending without an outcome (a step that ends the JVM, R198-1) are failed after
  `jobs.maxAttempts` claims (5) instead of rerun every lease; a distribution's Retry after a
  crash no longer fails its count check when the round's other files changed meanwhile;
  `scripts/agent-check.py` gets a time limit that stops the whole process tree (`--timeout`,
  exit 124), an owned sbt lock (`--lock`, an `owner` file) and, on Windows, a keep-awake
  request; the write-path stress harness marks a variant the host slept through INVALID
  ([workflow practices](docs/workflow/practices.md) §4.2, §4.6; [testing practices](docs/testing/practices.md) §8.1a–b).
- P2b (developed 2026-11-05; [round images plan](docs/plans/2026-11-02-round-images-two-step.md)
  §17): all the jurors' lists (the by-rate pages, the file list's pages, counts, ranks,
  positions, the large view's neighbours, the region counts) can read `round_image`'s counts
  (`RoundImageQuery`), the files marked dirty overlaid from their votes in the same statement and
  a round with a recount pending read from its votes, behind `roundImage.read` (off; a pilot);
  the CSV and a next round's top-N read the votes. Parity specs over both sources and each score
  rule. The round's score rule (`AverageRule`) replaces the `optional_rate` flag, which the code
  no longer reads (the column stays until a later clean-up).
- The vote checks named (developed 2026-11-05; [round images plan](docs/plans/2026-11-02-round-images-two-step.md)
  §18): `Vote.isPositive` / `isRejected` / `isUnrated` / `isVoted` / `grade` in Scala, `VoteSql`'s
  fragments in SQL, instead of rate comparisons in about forty places; a rated round's sums are
  of grades (a ✗ as 0) in the votes' and the counts' queries alike (`rate_sum + rejections`).
- **Migrations:** V85 gallery index, V86 timestamp definitions, V87 for the sign-in (#240);
  V88 `round_image_event`; V89 `round_image` counts; V90 `round.average_rule`; V91 `vote.rate`
  and `vote_aside.rate` `NOT NULL DEFAULT 0` (an online rebuild of each table; the NULL handling
  in the code removed); V92 `round.worst_mark`; V93 `round.prev_rejected_mode`; V94 `account_token.shared` (`ALGORITHM = INSTANT`;
  rollback in `FlywayUpgradeSpec`, the live shared links revoked first); V95 `round.exclude_rounds`
  (`ALGORITHM = INSTANT`, then a `CHECK` that copies `round`; rollback in `FlywayUpgradeSpec`). No
  app path deletes a round: one deleted by hand in SQL must also have its id cleared from other
  rounds' `previous` and `exclude_rounds`, or their resubmit and "Distribute new files" are refused.

## Deployed

### 2026-11-02 14:01Z: hotfix `0.13-round-filters-rolefix-f3f2c73617de`

The production build `f9c31ce5` plus the contest-role allowlist, a security fix. It has no
migration; the date comes from the deploy record
([docs/history/security/](docs/history/security/2026-11-02-role-allowlist-hotfix.md)).

### 2026-10-31: category operations (`f9c31ce5`, what production ran until 26.11)

**Features**

- Round filters exclude a category, and combine two categories with AND, OR or MINUS. Existing
  rounds keep their filters (#210, developed 2026-10-31; V69–V70).

### 2026-10-30: conflicts of interest

**Features**

- Rounds can leave out files by the contest's organizers, jurors and staff, with a count on the
  round form (#209, developed 2026-10-29 to 2026-10-30; V67–V68).
- Deployed by this date, with no migration of their own:
  - region filter improvements: named special nominations, Wiki Loves Earth regions, cached
    counts (#205, 2026-10-25 to 2026-10-26);
  - clear access-denied pages; jurors see only their own votes; CSRF on votes (#206,
    2026-10-27; security);
  - the galleries keep the juror's place and a stable order, and each click is faster (#207,
    2026-10-27 to 2026-10-28);
  - importing international winners (#208, 2026-10-28 to 2026-10-29).

**Development**

- Static assets are served with content digests (#208).

### 2026-10-24 and 2026-10-25: contest files gallery

**Features**

- The contest's files as a gallery, with regions and a large view (#204, developed
  2026-10-24; V65 on 2026-10-24, V66 on 2026-10-25).
- Deployed by 2026-10-24: add files to a round from a pasted list, and WebP thumbnails (#203,
  2026-10-23).

### 2026-10-23: deleted and renamed Commons files

**Features**

- Files deleted on Commons are hidden, and renamed files show under their new name. The large
  view loads from Wikimedia at its own sizes (#202, developed 2026-10-22 to 2026-10-23; V63–V64).

### 2026-10-22: background jobs, multi-contest accounts (release 0.13)

Developed 2026-10-05 to 2026-10-22 (the multi-contest work from 2026-04-12), PRs #197–#201.

**Features**

- **Round creation and "distribute new files"** run as background jobs, with progress, Cancel
  and Retry. Rounds being created stay hidden from jurors.
- **One account per person**, a member of several contests with a role in each; a contest
  switcher; "Import files" and "Update monuments" run in the background.
- **Faster:** galleries page faster, and a double submit no longer creates two rounds; media-type
  filters work again.

**Development**

- A background job scheduler on a generic `job` table: short steps, a lease for restart
  recovery, per-kind limits, Cancel and Retry (V60).
- The tables are converted to InnoDB (V55.1); new indexes, chunked inserts, a round submit
  token (V56–V59).
- Deployment tooling: a single service with versioned releases (`wlxjury-release`), a restore
  path and a recovery runbook (#200).
- **Migrations:** V54–V62 (V55 the indexes that were never applied).

### 2026-09-07: query and security work from spring 2026

Developed 2026-03-10 to 2026-09-10, PRs #187–#196. The migrations ran on 2026-09-07.
Production later ran `8fdaa7d` (committed 2026-09-10), so a later deploy without migrations
followed; **its date is unknown**.

**Features**

- Faster galleries and region statistics (#192, #193).
- **Security fix** in round filters (#195).
- Round consistency checks and distribution verification (#196).

**Development**

- New indexes, and `monument_id` stored on votes (V46–V50); region filters fully
  parameterised (#195); cascaded thumbnail resizing (#187).
- **Migrations:** V46–V53 (V51 unique monument id, V52–V53 round checks). V45a–V45g never
  ran; their indexes came as V55.

### 2026-03-08: local image cache

**Features**

- Thumbnails are served from a local cache through the app's image proxy (#185, #186;
  developed 2026-03-06 to 2026-03-08; V44).
- Deployed by this date, with no migration of their own (commits between V43 and V44):
  - a security fix (2024-07-28);
  - faster region statistics, and monuments updated in place (2024-10 to 2024-11);
  - the special nomination filter on the round form (2025-12).

**Development**

- Play 2.9 (2024-07-08), then Play 3.0 (2025-03); the MariaDB JDBC driver (2025-03 to
  2025-04); database tests in Docker (2025-10). Deployed by this date.
- Integration and Docker end-to-end tests for the image cache (2026-03).

### 2024-07-08 and 2024-06-28: media type filter

**Features**

- Rounds can filter by media type (V42 on 2024-06-28, V43 on 2024-07-08; developed in
  June–July 2024).
- Deployed by 2024-06-28, with no migration of their own: videos play in the gallery and the
  large view (2023-12-21 to 2023-12-22); criteria names per contest type (2024-01 to 2024-02).
- Deployed by 2026-03-08, not with these migrations: "filter videos" (#177, merged
  2024-08-02).

### 2023-12-07: decimal minimum rate

**Features**

- A round's minimum rate from the previous round can be a decimal (V41; developed and deployed
  the same evening).
- Deployed by this date, with no migration of their own (commits before V41, 2023-03 to
  2023-12): a faster rounds list; "selected by at least" can be none; statistics show when no
  round is active; distributing new files fixed; the WLM 2023 criteria; a criteria vote no
  longer jumps to the next file.

## Earlier releases (from git history)

Each entry is dated by its development period; WLE runs in May–June
and WLM in September, and most work lands just before or during a contest's jury. Release
names come from the git tags `0.8`, `0.9` and `0.10` and from `version :=` in `build.sbt`
(`0.11-SNAPSHOT` from 2018-07-28, `0.13` from 2021-06-28; no `0.12` was found). PR numbers are
the original GitHub PRs (#78–#140). Migrations V1–V10 are the earlier Play evolutions, renamed when Flyway was
adopted on 2016-07-17, so their files' first commits don't date the tables.

### 2022: REST API started (developed 2022-11 to 2022-12)

**Development**

- Tapir REST endpoints for contests with an API spec (not served; see the REST API in
  Release 1).

### 2021: version 0.13, all-users list (developed 2021-06 to 2021-12)

**Features**

- An all-users list for root (`version := "0.13"` set with it, 2021-06-28).
- Links use HTTPS.
- Organizers see each file's author in the file list (2021-10 to 2021-11).

**Development**

- MySQL 8 support (2021-06). Vote timestamps dropped (V40).

### 2020: jurors started and stopped one by one (developed 2020-05 to 2020-12)

**Features**

- An organizer can start or stop each juror in a round (#133, merged 2020-06-25; V37 the
  round–user table, V38 migrating existing rounds).
- One more thumbnail size (#135, 2020-06-30).
- Importing files from a pasted list: names normalised, duplicates removed (the `2020_06`
  branch, #137 and #138, merged 2020-07-03 and 2020-07-05).
- Special nomination monuments updated; per-round thumbnail URLs; the author is saved
  (2020-10). Subregions, with Kyiv grouped, in the region menu (V39, 2020-11 to 2020-12).

**Development**

- The thumbnail server is configurable (#135).
- **Migrations:** V37–V38 round–user table, V39 subregions.

### 2019: start and stop rounds, special nominations (developed 2019-05 to 2019-12)

**Features**

- Each round can be started and stopped on its own, so several can be active (2019-06).
- Jurors can be put in an order that the distribution follows (V34, 2019-10).
- Round filter by special nomination, and contests linked to a Commons campaign (V35–V36,
  2019-10). Contests can be filtered by type and year (2019-08).
- Rounds can be edited when the file loading fails (#129). Global admins can view round
  statistics. The minimum-megapixels filter goes up to 4.

**Development**

- Java 11 (#131, 2019-08), systemd packages, the Netty server (2019-10). Tests on MySQL 5.6
  and an AppVeyor build (2019-07).

### 2018-08 to 2018-11: after 0.10 (developed)

**Features**

- Rounds include and exclude categories; "selected by at least" can be 0; users and jurors
  are numbered; Ukrainian regions sorted; monument index on files (V33).
- Fixes for WLE Ukraine monument updates, simultaneous rounds and region navigation.

### 2018-07: release 0.10 (tag `0.10`, 2018-07-17)

Developed 2018-03 to 2018-07; released around 2018-07-17 to 2018-07-28 (from the tag, the
`0_10` branch and `version := "0.10"` on 2018-07-28, which then moved to `0.11-SNAPSHOT`).

**Features**

- A round can take the top N files of the previous round (#114, V32).
- Pages translated (navigation, file list, users, rounds); French added (2018-07).
- Large view: fixed jumping to the first file and rating a file from the middle of a list.
- Security hardening of forms.
- Monument lists updated from the contest's list template (#113).

**Development**

- Play 2.6 (2018-06); CSRF filter and a content security policy (2018-03 to 2018-06).
- Database tests on embedded MySQL instead of H2 (2018-05); large view tests (2018-07); a
  Docker image (2018-07-28).
- **Migrations:** V28–V31 column renames, V32 top images filter.

### 2017: CSV export, sign-up, shared files (developed 2017-02 to 2017-12)

**Features**

- CSV export of a round's results (#85, 2017-02).
- Users can sign up (2017-04). Jurors invited by wiki mail, and wiki user names imported in
  bulk (2017-07).
- Contests can share files (#94, merged 2017-08-29; V24 file categories); the file's author
  is stored (V25); comments belong to a contest (V26); larger monument fields (V27).
- Sharper thumbnails on HiDPI and 4K screens (2017-12). The previous round's filter can take
  unrated files. Users no longer have a default contest (V23).

### 2016-10 to 2016-12: rating with criteria (developed, after 0.9)

**Features**

- Rated rounds with criteria: each criterion scored, the total kept (V19).
- Half-stars, configurable per round (V21); rating by keyboard digits.
- Monuments get regions (V20), files are counted and filtered by monument region, and rounds
  filter by monument (V22).
- Round editing: choose the jurors, see the jurors assigned and the round's statistics, and
  add new files to an existing round. Comments from previous rounds are shown.
- Select and reject on the gallery page (#79, merged 2016-10-05, the day after the tag).
  Large view: thumbs up and down for select and reject.

### 2016-10: release 0.9 (tag `0.9`, 2016-10-04)

Developed 2016-07 to 2016-10, after 0.8; released around 2016-10-04 (from the tag and
`version := "0.9"`).

**Features**

- Round filters: Commons category (V14), regions (V15), file size (V17–V18), megapixels and
  the previous round's average rate or "selected by at least N" (V13). Contest greeting (V12),
  file descriptions (V11).
- Import files from a Commons page, category or file list (2016-08).
- Faster galleries, files grouped by region (#78, merged 2016-09-30).
- Users have a wiki account and can sign in with it (V16).

**Development**

- Flyway migrations replace Play evolutions (2016-07-17).
- A rewritten image query that pages in the database; thumbnails from Commons' thumbnailer
  (#78).
- Debian and RPM packages with systemd, settings from environment variables, Vagrant
  (2016-07 to 2016-08).
- Travis CI and coverage, and the first gallery tests (2016-08).
- **Migrations:** V11–V18.

### 2016-06: release 0.8 (tag `0.8`, 2016-06-23)

Developed 2016-01 to 2016-06; released around 2016-06-23 (from the tag "native builds" and
`version := "0.8"`), in time for WLE 2016.

**Features**

- Contests managed in the app instead of in code: create a contest, import its files from
  Commons and see them next to the database's (2016-04 to 2016-05).
- Users imported in bulk from a list of names and emails, and emailed; unique emails; jurors
  see a contest's greeting.
- Rounds created and edited in a form, with jurors distributed at once and a progress bar;
  the current round is set by the organizer.
- Root can view any contest's organizer pages.

**Development**

- Native packages ("native builds", 2016-06); Slick removed in favour of ScalikeJDBC
  (2016-05); the scalawiki library for Commons (2016-04).

### 2015: parallel rounds, other countries (developed 2015-01 to 2015-12)

**Features**

- WLM 2015 (2015-10 to 2015-11): several rounds at once, as tabs; first rounds with two jurors
  per file; criteria being implemented; WLM 2015 Ukraine regions.
- Used for other countries' juries: scripts for Spain, Italy, Greece and Wiki Loves Africa
  (2015-10 to 2015-12).

**Development**

- Play 2.3, then 2.4, and a HikariCP pool (2015-01 to 2015-08).

### 2014: contests in the app, rated rounds, comments (developed 2014-05 to 2014-12)

**Features**

- WLE 2014 (2014-05 to 2014-07): contests, rounds and users kept in the app, an admin
  page for users and rounds, email to jurors, files distributed among jurors, rated rounds
  and a gallery by rate, round statistics, the monuments list.
- WLM 2014 (2014-10 to 2014-12): comments on files, juror statistics, optional rating, full
  resolution, TIFF support, image preloading, several gallery sizes, keyboard navigation
  and selection, a redesigned interface.

**Development**

- MySQL replaces PostgreSQL (2014-06); Bootstrap 3 (2014-12).

### 2013-10: first version, WLM 2013 Ukraine (developed 2013-10-14 to 2013-10-27)

**Features**

- Jurors sign in and select files in a gallery and a large view, in two rounds, with files
  from Commons and the Ukrainian regions (KOATUU). The file lists were prepared in advance.

**Development**

- A Play Framework (Scala) application on PostgreSQL.

## How the dates were found

Production's history (read-only, run by the user):

```sql
SELECT installed_rank, version, description, installed_on, success
FROM flyway_schema_history ORDER BY installed_rank;
```

| Versions | Applied | Entry |
|---|---|---|
| V41 | 2023-12-07 | decimal minimum rate |
| V42, V43 | 2024-06-28, 2024-07-08 | media type filter |
| V44 | 2026-03-08 | local image cache |
| V46–V53 | 2026-09-07 | spring 2026 query and security work |
| V54–V62 | 2026-10-22 | release 0.13 |
| V63–V64 | 2026-10-23 | deleted and renamed files |
| V65, V66 | 2026-10-24, 2026-10-25 | contest files gallery |
| V67–V68 | 2026-10-30 | conflicts of interest |
| V69–V70 | 2026-10-31 | category operations |

The history has no V45 row (V45a–V45g never ran) and no B44 row. When a release is
deployed, move its section from Unreleased to Deployed, date it from its first new
`flyway_schema_history` row (or from the deploy record if it has no migration), and add the
release name.
