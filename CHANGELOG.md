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

The next release is `26.11.8` (`build.sbt` says `26.12-SNAPSHOT`). Everything developed up to
2026-11-13 before the entries below was deployed in `26.11.7`.

**Features**

- **Monument page** (the maintainer's decisions D5-D9 of 2026-11-13; developed 2026-11-13,
  `stack/140-monuments`): the monument box of a file's large view (in a round and on the contest
  files page; jurors see it too) has one link, **Monument** and its id, to a page with the
  monument as its Wikipedia list has it (name, place, year, type), its list row on uk.wikipedia
  (`#<id>`: the regional lists' rows carry their id as an anchor; the special nominations' lists
  don't, and are linked without one), its Commons category and its Wikidata item where the list
  has them, and, in the contest and round the file was seen in, **Files: In the contest: N →**
  (the contest files page with only the monument's files) and **In round …: N**. The contest and
  round are checked on the server: only an active member of the contest (or root) gets counts,
  only for a round they may see; a juror's round count is their own files (**yours**), their
  contest count leaves out files deleted on Commons; no author or vote anywhere. Without them the
  page has the monument alone. The round count has no link yet (a round list filtered by monument
  is planned with the round list's author filter). The old `/monuments` table (the first 20
  monuments, linked from nowhere) is now a box that opens a monument's page by its id.
  The contest files page takes `monument=<id>` (exactly: `BINARY`, as `images.monument_id` is
  `utf8mb4_general_ci`) with a chip **Monument: … (N) ×**, combined with the deleted files, the
  region and country menus, the author filter and the large view.
  **Jurors** of the contest can now open the contest files page (the maintainer's decision D9):
  all its files except those deleted on Commons, without tabs, the authors list, the conflicts of
  interest or any author; `deleted`, `round`, `coi` and `author` are ignored for them, and a
  juror of another contest is refused as before. The top bar still links it for organizers and
  admins only. Juror, organizer and admin guides: "The monument's page", "Monuments".
- **Counts on the round statistics links** (the round filters and comments proposal, §3;
  developed 2026-11-14, `stack/146-roundstat-counts`): under a round's statistics,
  **Comments on the round's files (12) · all rounds (30)** says the comments report's numbers
  (this round's comments, and all the contest's comments on the round's files when there are
  more, linking to that scope); **Conflicts of interest of this round (3 files · 2
  disqualified)** and **Authors of this round (148 · 12 files without an author)** get their
  counts after the page has loaded, each the number its page shows. A count of 0 makes the link
  plain text (as the statistics table's icons); the conflicts link stays, as "none · history
  (N)" (500+ beyond the 500 events its page lists), while the round has a history, and the
  authors link, as "none stored · N files without an author", while the round has such files. The counts come from a JSON request
  (`GET /roundstat/:round/counts`, numbers only, on the blocking pool) for those who may see all
  three links, checked in the round's own contest; until it answers, without JavaScript or if it
  fails, the two links stay as before, enabled and without counts. Organizer guide: "Statistics".
- **A round's lists by monument; jurors no longer open the contest files page** (the maintainer's
  decisions of 2026-11-14, PR-2 of the round filters proposal v4; developed 2026-11-14,
  `stack/147-monument-round-filter`): a round's gallery, by-rate gallery, file list and comments
  report take `monument=<id>` (exactly, as on the contest files page), shown as a chip
  **Monument: … (N) ×** with a link to the monument's page. Every link of the list keeps it: the
  pages, tabs, orders, views, the round and juror menus, and the large view's next and previous
  files and votes, by the buttons, stars and keys. The tabs and the pager count only the
  monument's files; the region is dropped and its menu hidden (a monument is in one region). The
  large view shows a compact chip while the filter is on, and its monument box a line **This
  monument in the list (N)**, which opens the current list (the same juror, tab and order) with
  only the monument's files, or says it is the list's only file of the monument; files whose
  monument isn't in a monument list (Wiki Loves Earth, other countries) get a small box with the
  id and that line. The monument page's **In round …: N** links the round's gallery with the
  monument: a juror's own files for a juror, all the jurors' votes for organizers, admins and
  root. **Jurors no longer open the contest files page; a monument's page links their round's
  files instead** (reverses D9): the page, its large view and its region and country menus are
  the contest's admins', organizers' and root's again (a juror gets the 403 page or JSON 403), and
  a juror's monument page has no contest count. `byRegionStat`, `regionCounts` and
  `regionRateCounts` bind every value. Juror, organizer and admin guides.
- **Comments list for jurors; tabs count what they open** (the maintainer's decisions 2, 5 and 6
  of 2026-11-14, PR-3 of the round filters proposal v4; developed 2026-11-14,
  `stack/148-juror-comments-list`): while **Only with comments** is on, **Recent comments** beside
  it opens the comments report of the same files (the juror, tab, region and monument kept; all
  rounds' comments; latest comment first), for organizers and jurors alike, and the report's
  **Show in the gallery** goes back to the gallery with the toggle on, in the gallery's default
  order. A juror opens the report of their own list only: every comment their large view shows
  on their files in the round (all the contest's rounds), with a note saying so, their tabs and
  orders, and no scope buttons, round names, CSV, filter by person or gallery icons; its large
  view's previous, next and votes stay among those files. Another juror's report, all jurors'
  together, the CSV and the filter by person stay the organizers': a juror gets the 403 page, or
  JSON 403 for a script request, and `scope=` and `commenter=` are ignored for them. **The tabs
  count what they open** (changes #310): with the toggle on and in the report they count the
  files with comments, so **All (17)** opens 17 files; the extra count of all the juror's files
  is gone, and in a Via Regia round of sub-regions the tabs count from the same scan. Juror,
  organizer and admin guides.
- **A round's lists by author, for staff** (decision 4 = A of 2026-11-14, PR-4 of the round
  filters proposal v4; developed 2026-11-14, `stack/149-author-round-filter`): a round's gallery,
  by-rate gallery, file list, comments report and CSV take `author=<author as stored>` (exactly,
  as on the contest files page and the authors list: "Ivan", "ivan" and "Iván" are three
  authors), shown as a chip **Author: … (N) ×** with the author's **Commons ↗**, for the
  contest's admins, organizers and root only. Every link of the list keeps it, as the monument
  filter: pages, tabs, orders, views, the region, round and juror menus, the large view's next and
  previous files (a compact chip there) and its votes; it combines with the monument filter and
  **Only with comments**, and the tabs and pager count within it. In a round's pages an author's
  name now opens the round's list with their files: in the round's large view (the current list,
  with a small icon to the contest files page beside it), the round's file list (that file list)
  and a round's authors list (all the jurors' votes, with the icon); the contest's authors list,
  the contest files page's large view and its conflicts of interest still open the contest files
  page. For anyone else, jurors included, `author=` is ignored without an error, as on the contest
  files page: no author, chip or `author=` in any of their pages. Organizer guide: "Authors".
- **One line on the monument card** (the maintainer's decision of 2026-11-14; developed
  2026-11-14, `stack/151-monument-card-line`): the large view's monument box no longer repeats
  the monument ("This monument in the list (2) →", "Monument 46-233-0012 →" and the chip). Under
  the region, city, name and place it has one line, `46-233-0012 · Gallery (2) · Page →`:
  **Gallery (N)** opens the current list with only the monument's files (the former "This
  monument in the list (N)", titled "Show only this monument's photos in the gallery"), and is
  left out while the list already has only them (the chip is on) or when the file is the list's
  only one of the monument (the former "The only file of this monument in the list"); **Page →**
  opens the monument's page. A monument without a row in a monument list gets the id and
  **Gallery (N)**, or just the id. Juror and organizer guides.
- **Votes and Comments menus in a grouped top bar** (the navigation proposal v3, the maintainer's
  decisions D1-D9 of 2026-11-14; developed 2026-11-14, `stack/152-filter-bar`): the bar of every
  list page is in groups: the pages (staff), the filters (round, juror, votes, **Comments**,
  region, or the monument and author chips), after a thin line the order (and the staff's view
  menu), then the pager; each group wraps as a whole on a narrow window. A juror's vote tabs
  become one votes menu (**All**, **Unrated**, **Selected**, **Rejected**, or **Rated**), its
  button the current choice and count, on **All** also how many files in the list are unrated
  (**All (5712) · 120 unrated**), "Votes" read by screen readers. A choice keeps the region and
  counts in it (in a round of sub-regions one count per choice). The toggle **Only with
  comments**, **Recent comments** and the report's **Show in the gallery** become one
  **Comments** menu: **All files**, **Only with comments (N)** and **Recent comments list (N)**,
  a choice with no file greyed out; the report's order menu moves into the bar. Organizers,
  admins and root get the same two menus (counts only in one juror's view; below 1700 pixels the
  unrated suffix is left out; the bar stays on one line from 1280 pixels in Ukrainian, a long
  juror name cut short), and their page links (contests, all users, contest files, users,
  rounds, statistics) become one pages menu at the bar's start, showing the page you are on
  (below 1700 pixels its icon alone); their own files, opened from the juror menu, get the same
  bar.
  Their view menu no longer lists the report (the **Comments** menu opens it with all rounds'
  comments). Their second set of vote tabs for their own files is gone, without a replacement
  for now (staff don't vote: their files are under the juror menu); a "My votes" link comes back
  with multi-role (maintainer, 2026-11-14). The monument and author
  chips move into the filters, compact (the id or the name, **↗** to the page, **×**). The file
  list gets the menus too. Every navbar menu (the filters, order, pages, contest, theme and
  account menus, and the pager's page box) opens from a button (Space opens it), and the link
  menus mark the page's item for screen readers. Juror, organizer and admin guides.
- **Statistics on every page of the contest** (the maintainer, 2026-11-14; developed 2026-11-14,
  `stack/153-statistics-in-pages-menu`): the pages menu lists **Statistics** for the contest's
  organizers and admins (and root) on all its pages, the users, rounds and contest files pages
  too, not only on a page with rounds (a rule from when the links took room in the bar). It
  opens the round's statistics on a round's page, else the current round's; a contest without
  rounds has no such item. Jurors still have no pages menu, and the statistics pages check access
  as before. Organizer guide.
- **Juror menu and Votes button told apart** (the maintainer's decision C of 2026-11-14; developed
  2026-11-14, `stack/153-statistics-in-pages-menu`): in the organisers' bar the juror menu's
  button has a people icon and reads **All jurors** (uk «Усі журі», ru «Все жюри», fr «Tous les
  jurés») or the juror's name, and the Votes button a check-box icon before its choice (for jurors
  too), both icons hidden from screen readers, so **All jurors** and **All (5712)** no longer look
  alike. Juror and organizer guides.

**Development**

- **Release tool safety** (after 26.11.6's mistaken restore; `stack/139-release-tool-safety`,
  #322; installed on the server on 2026-11-14, before the 26.11.7 deploy): `deploy`, `restore` and
  `status` print a few lines and end with the one command to run next, alone on the last line;
  back-out commands only in `status --verbose`. A restore of an operation that reached `ready`
  asks to type RESTORE (or needs `--yes` without a terminal), also after a `reopen`, and a named
  restore is refused when a later operation deployed the same release again. `deploy` writes the
  ZIP's SHA-256 into each release folder and replaces a non-current folder that came from the
  same ZIP; the current release and any other folder are still refused. `prune`'s skip and
  "Keep" notes only with `--verbose`.
- **V104 `images_author_idx (author)`**, an **online index build** (`ALGORITHM = INPLACE,
  LOCK = NONE`: reads and writes go on; `ADD INDEX IF NOT EXISTS`, rerunnable; a 60 s
  `lock_wait_timeout`, also for the `ANALYZE TABLE images` after it; undo `DROP INDEX`; about 8 s
  on production's copy). Measured there with the index and with `IGNORE INDEX` (warm runs): all
  the jurors' list of a 29k round by author counts in 7 ms instead of 101-116 (523 cold) and its
  region counts in 12-14 instead of 80-87; the largest juror list (22.6k files) counts one author's
  files in 14-48 ms instead of 62-66; the contest files page's author filter takes 7 ms (its
  previous shape read the category's members, 97-134 ms); the conflicts of interest 29-30 instead
  of 95-100. A juror's page, and a typical juror's list, keep reading the juror's rows (the same
  plan and speed either way, 9-24 ms); a shape driven from the author index was no faster there.
  `varchar(255)` utf8mb4 is 1,020 bytes, within the DYNAMIC row format's 3,072. **V103 stays
  reserved** for round lineage's `round.status`, usable only while V104 isn't deployed: with
  production's settings a database that has V104 refuses a V103 added later, so whichever deploys
  second takes the next free number above the other (FlywayUpgradeSpec checks both). QueryPlanSpec's
  seed gains a heavy author (7,500 files, 3,000 in the corner juror's round; vote's rows and the
  statistics snapshot unchanged) and author scenarios.
- **V102 `monument.wikidata`** (VARCHAR(16), NULL; INSTANT, rerunnable): the list rows'
  `wikidata` parameter, which scalawiki leaves in `otherParams`, stored by the monument list import
  ("Update monuments") as `Q` and its number (anything else NULL) and replaced or cleared on each
  import. Existing monuments get theirs on the next list import. Round lineage's `round.status`
  moves to V103.

## 26.11.7 (2026-11-14)

Deployed 2026-11-14, build `26.11.7-efb3ff01c8c9` (release commit `efb3ff01` on `release/26.11.7`),
schema V101 (no migrations; the release tool skipped the backup), from `26.11.6`. PRs #318-#321.
Deploy record: roadmap, "26.11.7 deploy record".

**Features**

- **Authors for staff** (developed 2026-11-13, `stack/131-authors`): admins, organizers and root
  see each file's author, as their Commons account, with two links: the name opens the contest
  files page narrowed to that author's files, with a removable chip **Author: X (N) ×**, and a
  small **Commons ↗** opens their user page on Commons. They are in the round's and the contest
  files page's large views, the round file list's author column, and the contest files page's
  **Conflicts of interest** tab, which now lists the people whose files they are. The filter
  takes the author exactly as stored ("Ivan", "ivan" and "Iván" are three authors: a `BINARY`
  comparison, as `images.author` is `utf8mb4_general_ci`) and keeps to the pages, the region and
  country menus and the large view.
  A new **Authors** list (the button on the contest files page, **Authors of this round** under a
  round's statistics) has the contest's or a round's authors, told apart the same way, with
  their numbers of files, a line on how diverse they are ("Round 2: 87 authors, 482 files; most
  by one author: 41 (8.5%)") and, once the round has votes, over all its jurors: a yes/no round's
  files per author by one outcome each, adding up to their files (approved: at least one yes;
  rejected by all: votes cast, no yes; not voted yet: no vote cast), and the author's approval
  rate (yes among the yes and no votes cast on their files, "—" without any: sorted from the
  lowest, the authors rejected the most come first); a rated round's rated files, files with a ✗
  (where the round takes one or has one) and average score in stars by the round's score rule
  (per criterion in a criteria round); counts with their shares, every column sortable. Jurors see none of
  it: no names, links, filter or list (403), and no author in the large view's JSON. On the
  production copy the list of WLM UA 2026 (32,531 files, 231 authors) reads in about 0.15 s and
  its round of 18,442 files in 0.2 s, with no new index. Organizer guide: "Authors", "The
  authors list".
- **Comments by one person** (the maintainer's request; developed 2026-11-13,
  `stack/136-comments-by-person`): in the comments report, after each comment's author, a funnel
  icon shows the report with only the files that person commented on in the report's scope
  (`commenter=<user id>`), and a pictures icon opens their gallery of the round the comment was
  written in, only for a juror of that round (pre-screeners included). The icons' words are in
  their tooltip and for screen readers; the name stays text. A chip **Comments by Name ×** names
  the filter and removes it. Each file shows that person's comments, the file's other comments
  folded under them; **Latest comment first** is by their latest, and the header's counts ("3
  comments by Name"), pages, CSV (their comments only) and the large view's previous and next
  follow it, with the juror, vote filter, region, scope and order. Comments without an account
  stay without links. Admins, organizers and root only, as the report: a juror's request with `commenter=` is
  refused as the report is, and ignored in their own gallery and large view.

**Development**

- **Records and tests:** the 26.11.5 and 26.11.6 deploy records (#320); `.gitattributes` checks
  `conf/monit/**` out with LF, so `MonitCheckTest` passes on a Windows checkout; a test of the
  comments report's default order with a remembered yes/no order (#321).

## 26.11.6 (2026-11-13)

Deployed 2026-11-13, build `26.11.6-89b458d69a8f` (release commit `89b458d6` on `release/26.11.6`),
schema V101 (no migrations; the release tool skipped the backup), from `26.11.5`. PRs #314-#317,
and the CI lint fix pushed to #307's branch after `26.11.5`. Deploy record: roadmap, "26.11.6 deploy record".

**Features**

- **Access denied in another contest** (found on 26.11.5; developed 2026-11-13,
  `stack/135-denial-contest-reason`): the 403 page of a page that belongs to a contest the account
  has no active role in (never added, or deactivated) now says so first, lists the account's
  contests with its role in each and says to ask an admin of that contest. That contest is not
  named (a missing contest reads the same). Before, an admin of WLM 2025 Ukraine who opened a round
  of another contest saw the roles the page is for ("Admin, Juror, Organizer, Root") beside "Your
  role in WLM 2025 in Ukraine: Admin", which read as a contradiction. A page of another of the
  account's contests gives the role there, not in the session's current contest. Root, pages of
  no contest, the stopped round's page, the reference and the log line are unchanged.
- **Comments report opens by the latest comment** (developed 2026-11-13,
  `stack/134-view-menu-state`): with no order in its address, the report is ordered by **Latest
  comment first**: its page and CSV, one juror's and all the jurors', either scope, and a large view
  opened from it, whose previous and next follow the same list. Opening it from another view (the
  view menu, the round's statistics) switches to that order; an order chosen in the report's menu
  is kept, the CSV included, and leaving the report keeps it (by rate only from the latest
  comment, which the other views don't have).

**Fixes**

- **View menu stuck on «By rate»** (reported on 26.11.5; developed 2026-11-13,
  `stack/134-view-menu-state`): an organizer's **Gallery** of all the jurors' votes
  (`/gallery/round/R/user/0/...`) showed the by-rate view, so the view menu showed **By rate** as
  chosen and its **Gallery** item went back to the same page, in any order; broken since the
  gallery's list rework (cf56da73, 26.11). The gallery path is now always the gallery, also for
  all the jurors (their by-rate view stays at `/byrate/...`), and the order (`sort=`) stays apart
  from the view.
- **Access-denied page never a server error:** the page's roles are loaded with one query for the
  account's contests (not one per membership) and not at all for a stopped round's page; if they or
  the suggested destination can't be loaded, the page leaves them out, logged at WARN on
  `security.authz` with the denial's reference (`denial_roles_unavailable`,
  `denial_suggestion_unavailable`), instead of turning the 403 into a 500.
- **Conflicts of interest, a large selection:** on a round's conflicts page, a selection of more
  than 2,000 files (the page's limit) is refused whole with a message and nothing is written; it
  was cut at 2,000 while reporting success. A malformed file id is refused instead of dropped. The
  form's fields are bound as typed values (the checkboxes are `pageId[]`).

**Development**

- **CodeRabbit triage of #238–#248** (`stack/133-coderabbit-triage-1113`):
  - CI: every `actions/checkout` keeps no token (`persist-credentials: false`), so the build code a
    job runs can't read the job's token; the scalawiki package plan's token, from-source fallback
    and tag check.
  - The deployment's credential scanner (`wlxjury-deploy.py`, when it adopts a server's
    configuration): a quoted HOCON key is one segment, dots included, and its leaf's last dot-part
    is checked too; a whole-line comment is read like a setting, and in a comment only an
    expansion (`$(...)`, `${...}`, `$NAME`) is exempt; booleans and durations are no longer exempt
    in the line formats (`.cnf`, `.env`, `.properties`), where they can be the password.
  - Write paths: `RoundWrites` refuses a round lock in auto-commit mode (its `FOR UPDATE` ends with
    the statement), where the vote inserts take a transaction of their own, and a read-only
    session's vote insert fails, writing nothing; `VoteJdbc.rate` finds and rates a vote row that
    was replaced while the juror waited for its lock (a restore).
  - OAuth (not offered on production yet): one's own unlink and its sessions' end commit in one
    transaction; root's "reset password" unlinks the identities only once the link is mailed, and
    ends the sessions with them; `GoogleProvider` fetches the discovery document again after a
    failed fetch.
  - The migration lint (`MigrationSchemaSpec`) reads qualified tables and `ALTER TABLE IF EXISTS`
    and skips string values after `COMMENT` and `DEFAULT`; the vote-row lock scan
    (`VoteRowLockSpec`) exempts only the vote row's own id.
  - `scripts/browser-stats.py` counts the last 14 days (or the given number) by each request's
    time and lists every major version; its tests run on CI. Docs: exact image tags' residual
    risk, the local scalawiki pin, commit pathspecs, the theme fallback.
- **Release provenance plan** (`docs/plans/2026-11-13-release-provenance.md`): CI-built releases
  with a build provenance attestation that `wlxjury-release deploy VERSION` verifies with cosign,
  then reproducible ZIPs. The maintainer's decision D1: releases are tags on master, through a
  protected `release-train` branch until the stack merges. Not built yet: `26.11.6` followed the
  old flow (a `release/26.11.6` branch from the stack, deployed by its ZIP's hash) as an explicit
  temporary exception.
- **CI's compatibility lint**, red since #295 (2026-11-12): a false positive on the account menu's
  `width: max-content` (caniuse's partial support is about the `stretch` keyword); the rule is
  disabled on that line with the reason (71f2723c, on `stack/125-login-csrf-token` after
  `26.11.5`'s commit, merged up the stack).

## 26.11.5 (2026-11-13)

Deployed 2026-11-13, build `26.11.5-0a7c0cba764f` (release commit `0a7c0cba` on `release/26.11.5`),
schema V101 (migration V101: comment's `(contest_id, room)` index, 140 ms on production), from
`26.11.4`. PRs #307-#313; the first deploy with #308's release tool (installed on the server just
before it). Deploy record: roadmap, "26.11.5 deploy record".

**Features**

- **Files with comments** (asked for by a contest admin; developed 2026-11-13,
  `stack/128-comments-badge-filter`): a file with comments shows a speech bubble with their number
  on its gallery tile, its by-rate tile and in the large view's **Next files**, read by screen
  readers as words ("3 comments", in the language's plural form). A toggle **Only with comments
  (N)** beside the tabs (organizers: beside the vote filter) narrows the round's list to those
  files, combined with any tab, region, order and view, the CSV included; N is the current tab's
  number. The toggle is kept by every link of the list: its pages, the large view's previous and
  next, votes and comments (a comment goes back to the list it was made in, also an organizer's
  view of a juror's or of all the jurors' votes). Jurors get it on their own files, organizers and admins in every view
  they already have; it never shows a file the viewer couldn't see. Both count the comments a
  file's large view shows: any round's of the contest, any author's. The large view's comments
  now show their author, time (in the viewer's time zone and language; none where the stored time
  can't be read) and, for a comment from another round, that round's name (to admins, organizers
  and root only: jurors see the comment without it).
- **Comments report** (the admin's request, second part; developed 2026-11-13,
  `stack/130-comments-report`): a fifth view of a round's list, **Comments** (in the view menu, and
  linked from the round's statistics), for the contest's admins, organizers and root only (a juror
  gets the 403 page, or JSON from a script, also for their own files). It lists the files with
  comments, 20 to a page: a ~200 px thumbnail and the name linking to the large view (which then
  moves among the report's files), the file's result in the round (all the jurors' tally or one
  juror's vote, a rated round's stars), and each comment with its author, time and, from another
  round, that round's name; texts keep their line breaks and are never HTML. A switch **This
  round** (the default) / **All rounds of the contest** chooses the comments: a file is listed if it
  has one of them. Its order menu has the list's own orders and **Latest comment first** (by the
  newest comment of that choice; the default since `26.11.6`). **Comments CSV** has a row per
  comment (file, page id, Commons URL, author, time in UTC, round, text), every cell
  formula-safe. The juror, vote filter, region and pager work as in the other views.
- A comment on a stopped round's file is refused on the server too, for admins, organizers and
  root as for jurors: a 403 page saying the comment was not saved, with a link back to the file
  (or JSON) and its reference, logged on `security.authz` with the reason `round_stopped`. The
  form was already hidden there.

**Fixes**

- **Sign-in page for clients that don't ask for HTML** (developed 2026-11-13,
  `stack/125-login-csrf-token`): `GET /auth` with an `Accept` header without `text/html` (a
  scanner's, 5 times on 2026-11-13) was a 500, "No CSRF token was generated for this request", and
  signed-in pages with forms failed the same way for such a client. Browsers were not affected.
  Every request the CSRF filter doesn't check (GET, HEAD, OPTIONS) now gets a token, which reaches
  the session only when a page renders it (a JSON answer still sets no cookie), and every template
  renders the field with `csrfField`, which leaves it out rather than failing. `PageCsrfTokenSpec`
  covers the `Accept` headers, HEAD, the signed-in pages with forms, and guards the templates
  against the throwing helper.
- **Authors shown as wiki markup** (developed 2026-11-13): a file imported before authors were
  resolved kept its author as wikitext, so organizers saw e.g. «Автор: [[User:Liliia2022|Liliia2022]]»
  in the large views and the round file list (on the production copy: 160,492 of 1,029,265 files,
  among them every file of WLM UA 2025 and all 8 deleted files of WLM UA 2026). Fixed two ways:
  - **Normalize stored authors**, a root-only button on the rounds list: a background job
    (`author_normalize`) stores each such author as the account an import stores today, keyed by
    page id (never by value: `images.author` is `utf8mb4_general_ci`). A link's target account
    (`[[User:Petro_Hrushko|Petro]]` → `Petro Hrushko`); a credit template's linked user (the four
    on production all link to one: `{{MO}}` → `Максим Огородник`); for a template that links to
    nobody, the original uploader, none for a deleted file or a bot; none for an unknown author.
    Accounts an old import's encoding loss stored as question marks (`[[User:???????? ????????|…]]`:
    38 values, 3,706 files on the production copy, none deleted) are read again from the file's
    page text on Commons; one Commons names no author for is left as it is, never stored as
    question marks. Chunked, cancellable and resumable; a second run changes nothing. Admin guide:
    "Normalizing stored authors".
  - The large views, their JSON and the file list's author column show any value still stored
    with markup as its account (`StoredAuthors.shown`), as plain text. Authors stay visible to
    organizers, admins and root only.
  - **On production** root ran the job after the deploy, the authors backed up first: 160,453
    files changed, among them 3,667 question-mark accounts restored from Commons and 1 author taken
    from the first uploader, and 39 left as they were, the same as in the rehearsal on the
    production copy (3 min 39 s there).
- **Conflicts of interest after the job:** once the job has run, files by contest members that
  were stored with wikitext authors match as conflicts of interest (`CoiMembers`' `author IN (…)`
  prefilter now finds them): the round form's counts, the conflicts pages and the next
  distributions, "Distribute new files" included. Nothing caches these matches, so nothing needs
  a refresh; rounds already distributed keep their files until an admin disqualifies the new
  matches on the round's conflicts page.

**Development**

- V101: index `comment_contest_room_idx` on `comment (contest_id, room)`, which also takes over
  the foreign key from the single-column `contest_id` index (dropped where a dump kept it, so an
  upgraded schema equals a fresh one), online and rerunnable (140 ms on production, 218 ms on the
  copy), for the toggle's semi-join, the badges' one grouped query per page and the large view's
  comments. Round lineage's planned `round.status` moves from V101 to V102.
- **Author write guard:** `ImageText.resolvedAuthors` (behind `withAuthors`, used by the import,
  its refresh and a round's file list) never stores markup: an author that is still markup after
  parsing (`{{own}} by [[w:Museum]]`) falls back to the original uploader, as a credit template
  that links to nobody does. `FileAuthorSpec` and `RoundFileListSpec` check every write path;
  `AuthorNormalizeSpec` the job (chunking, idempotency, two values equal under the collation,
  question-mark accounts restored or left, Cancel and Retry). The job's first step counts the
  files with markup in one scan of `images` (about 10 s on production) on the fetch lane, and its
  few Commons requests may overlap an import's: the fetch lane has no limit shared across kinds.
- **Deployment tool** (`stack/124-release-tool-defaults`; installed on the server on 2026-11-13,
  before this deploy, which it ran: `sudo wlxjury-release deploy f42926d333d35ce4`, the dump
  taken by the tool): `wlxjury-release status` shows maintenance, the current release and schema,
  the open operation with its milestones, and the exact next commands (read-only, no lock; it says
  when the lock is held). `reopen` and `restore` without a name act on the current operation (the
  newest, this tool's, not complete, with the current release one of its two) and refuse a stale
  one, listing it with the reason. `deploy` takes the hash's first 16 or more hex characters,
  checked against the ZIP's whole SHA-256 (still recorded), finds the one `wlxjury-*.zip` in the
  folder whose hash matches when no ZIP is named, and hashes, lists and unpacks only a root-only
  copy of the ZIP (after checking there is room for it), deleted once unpacked. Every refusal stays.
- **Query plan checks** (plan `docs/plans/2026-11-13-query-plan-checks.md`, the maintainer's 11
  decisions; P1 built in `stack/127-query-plan-spec`): `QueryPlanSpec` (`testOnly
  plans.QueryPlanSpec`), in a forked JVM and MariaDB 10.6 container of its own, seeds a corner
  shaped like production's (about 1M votes) with a committed statistics snapshot, captures the
  statements of the juror scenarios and a round's distribution from the code, and checks their
  plans: no index-merge intersection on `vote` (R1), the named statements' keys (R3), and a canary
  that adds V100's dropped indexes back and must see the intersection. CI's 10.6 leg fails when the
  spec is missing or skipped; the 10.11 leg skips it (decision 9). The rehearsal's plan check on
  the production copy found 0 intersect plans.

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
