# Changelog

What changed in each release of the WLX Jury Tool, one short line per change. Details are in
the pull requests and the git history. Entries are grouped as Features, Fixes, Security and
Operations; "Migrations" lines tell a deploy which database migrations it applies.

**Versioning:** from 26.11 on, versions are calendar-based, `YY.MM` (e.g. `26.11`), with `.N`
for an extra deploy in the same month (`26.11.1`), and release headings read
`## 26.11 (YYYY-MM-DD)`. A hotfix of a deployed `YY.MM.N` release gets a fourth number,
`YY.MM.N.H` (`26.11.13.1`). Earlier releases keep their dates and tag names (`0.8`, `0.9`, `0.10`).
Deploy dates come from production's migration history, as described at the end.

## Unreleased (on master, not yet deployed)

For the next release, 26.11.17.

**Features**

- Saving a round's settings logs who changed what, with the old and new values, on the round's statistics and in the contest's change log (migration V114).
- GraphQL: `round(id)` and `roundStat(roundId)` give one round and the round statistics page's numbers, with the page's own-votes rule.
- GraphQL: `roundImages` and `image` give a juror's files in a round with their votes, paged by cursor, under the galleries' rules.
- Rounds show their track (Main, Quality, a nomination, a region…) as a coloured chip on the Rounds page, the round statistics and the organisers' round menu, with a Track filter on Rounds and Statistics; admins can label a round's track on its form, and label changes are logged (migration V115).
- "Add juror…" and a stopped juror's "Start and give a fair share…" work on stopped rounds too: the unrated files move while the round stays stopped, and every juror has a fair share when it is started again. The round statistics offer them only when they can be done: not on a round still being set up (its jurors are chosen there), held by a job, or from before jurors were stored. The add page names the rounds created from this round, whose files change only when they are distributed again.
- Retiring a contest is refused while a job holds one of its rounds (a juror being added, or a distribution reading its votes): try again when the job has finished.
- A round's statistics, review page and Edit round page link the rounds it was created from ("Created from: Round 3 (…)") and the rounds created from it, for its admins and organizers.
- A round being set up says on its Edit page and its review page that jurors can't see it yet, with "Change the settings", "Check the files" and "Distribute to jurors" (disabled, saying why, while its files are being matched or when none matches) (#186).
- A round's rating type can be changed while it is being set up (in review, nothing distributed); it is locked once distributed (#184).
- A new round is set up in numbered steps with a step bar: 1 "Which files" (the form, one button "Save and find matching files"), 2 "Check the matching files" (the review page, "N of the contest's M files match", then "Distribute to jurors" as its main button), 3 "Jurors" (the jurors form), then open to jurors. The Rounds page reads "Setting up – step 2 of 3: check files" or "… step 3 of 3: choose jurors" with that step's button in place of Start; the round form, statistics and conflicts page show the same step with a link.

**Fixes**

- Rounds page: a round whose setup was cancelled or didn't finish, meant to be reviewed first and with no jurors yet, has "Find the matching files again": its files are found again and it waits for review, instead of having to be set up anew. Its refused Start says so (#194).
- A round stopped while a juror is being added to it no longer makes the add fail: the add completes and the round stays stopped.
- Rounds page: a round not yet distributed to its jurors has no Start button. It reads "Not started – waiting for distribution" with a "Distribute to jurors" link, or "Images are being distributed" while that runs. Starting it anyway no longer says its images are being distributed when they aren't: the message says what to do.
- Round form: a refused save comes back as the same form (re-apply mode, regions, nominations, the round's jurors kept), lists every error at the top with a link to its field, shows each at its field, and opens the section that holds it ("Media filtering" stayed closed before, hiding the error). Each refused save is logged as `form_refused` (field names and message keys, never the values).
- Round form: Back or a repeated Save after a new round was saved opens that round's Edit page ("Round N is saved. You can change its settings here.") instead of the stale form; nothing is created twice or refused, and a repeat is logged as `resubmit_dropped` (same or changed form).
- Saving a new round says so on the page it lands on ("Round N (description) is saved. Its matching files are being found. Check them, then choose jurors."); saving an existing round's settings says "Round N (description) is saved."
- Distribute is refused for a round whose settings match no file; the jurors form shows it disabled with the reason.
- Round form: a save refused because another round's job must finish first (a round to leave out still being distributed, a previous round with a juror being added) keeps the form as entered and shows that job's progress, linked to it; when it has finished the box says "Round N has finished. You can save now." and Save is enabled again.

## Deployed releases

### 26.11.16 (2026-11-25)

Built from master after `26.11.15`; no migration (the schema stays V113).

**Features**

- GraphQL: series admins see their series' contests and rounds; `me.email` shows your own email; `myStatus` shows a stopped juror (#169).

**Fixes**

- Round form: the category filter is two boxes, Only files in category and Leave out files in category (#170).
- By-rate pages no longer scroll sideways (#168).
- An import no longer gives a contest another contest's file list (#172).

**Security**

- Every route under `/api` outside the REST API needs a signed-in caller (#169).

### 26.11.15 (2026-11-24)

Built from master after `26.11.14`; it migrates the database, so the deploy takes a backup.

**Features**

- Read-only API tokens that work only from the server itself (#121).
- GraphQL API for the tool's new screens: the caller, their contests and rounds (#154).
- Health and recent-activity API endpoints for support, plus a diagnostics command-line tool (#121).
- Contest series: one contest per year tied together (#120).
- Root's Series page: create, rename and retire series.
- Assignment proposals group contests into series, confirmed in bulk with Undo.
- Contest "Series and status" page to assign, move, retire or restore a contest.
- Reused contests are flagged on the Contests page.
- Retired contests open nothing and disappear from menus and lists.
- Contest menu groups contests by series and year.
- Contests page gets Series and Status menus and Series and Created columns.
- Warning when adding a contest with a duplicate name, year and country.
- Add juror offers only people outside the round; restart a stopped juror from Round statistics (#122).
- Root's contest reuse report shows which contests hold rounds of other contests (#123).
- Large view loads the next file's photo ahead of time (#146).
- Category names pasted with [[ ]] are accepted (#139).
- Series admins (#140): root grants them, a series admin can add more, and an un-retired series asks root to confirm them again.
- Revoking a series admin also revokes the links they made.
- A juror of an open round sees only their own votes in it, whatever their contest role.

**Fixes**

- Thumbnails load as you scroll, and pages no longer jump while they load (#150).
- Background count refresh no longer waits behind long jobs.
- Contests page no longer shows an unused round number.
- API: `currentRound` removed from the contest JSON (it was always null).
- Old juror assignments with no status are treated as stopped, votes included (#119).
- Roles on conflict-of-interest pages are shown in the user's language.
- Top-bar menus stay inside a phone's screen.

**Security**

- A juror stopping, reopening or closing their own round is logged and shown on round statistics.
- API tokens of an account being retired by an account join are refused.

**Operations**

- Migrations: V108, V109, V110, V112, V113.
- Nightly backup no longer locks the database's tables (legacy tables converted to InnoDB).
- Rolling back past V110: revoke the read-only API tokens first.
- Script for maintaining the local image cache (#132).

### 26.11.14 (2026-11-24)

Built from `26.11.13.1`; no migration.

**Features**

- Sticky top bar on desktop (#128).
- Round form criteria start small, with Add and Remove buttons.
- A round may have a single criterion.
- Back from the large view keeps the top bar visible.
- "Download images locally" no longer reloads the page.
- Filter menus leave out empty items.
- All-users page gets Type, Country, Year and Role menus.
- Warnings when no juror can vote in an open round.

**Fixes**

- A started round's criteria can no longer be changed.
- Round statistics no longer break on a juror with no status.
- First keys typed in a saved category or import source are no longer lost.

### 26.11.13.1 (2026-11-19)

Hotfix; no migration.

**Fixes**

- "Download images locally" says when everything is already cached or there is nothing to download.
- No "Reassign files…" button in a round of one juror.
- A saved category or import source can be edited again.

### 26.11.13 (2026-11-19)

**Features**

- Wikimedia Codex look across the tool; vote colours kept.
- "WLX Jury" wordmark on the sign-in and other signed-out pages.
- Rounds and Round Statistics pages filter by status and type and sort rounds.
- Round type shown as an icon before the name, also in the galleries' round menu.
- Statistics page switches to the first matching round when the filter leaves the current one out.
- Reassign a juror's unrated files to other jurors of a running round.
- "Reassign files…" shown only for jurors who have files to move.
- Files waiting for a juror can be given out later, or when adding a juror.
- Add a juror by a wiki account nobody verified, after confirming it is the right person.
- Add people from another of your contests.
- Contests page filters by type, country and year and sorts by year or country.
- Contest health page for root and the contest's admins.
- Import form warns when the source doesn't match the contest.
- Import source is checked on Commons before an import starts.
- Page telling a juror why a round can't be opened.
- Large view: jurors' rank numbers fully visible, score shown once.
- Links that leave the tool open in a new tab, marked by site (Commons, Wikipedia, Wikidata).
- New support log lines for imports, jobs and refused round access.

**Fixes**

- Every sign-in redirects, so reload and Sign out no longer meet the CSRF error page.
- A stale sign-in form while signed in shows "You are already signed in".
- A juror whose rounds are all closed is told which and why.

**Operations**

- Licence changed to GPL-3.0-or-later.
- Production logging config ships in the release; run the infra playbook and restart after deploy.
- Rolling back: finish or cancel reassign jobs first.

### 26.11.12 (2026-11-17)

**Features**

- Report of possible duplicate accounts, with "Keep separate".
- Join two accounts (background job, can be abandoned).
- Retire an empty duplicate account from the "wiki account taken" panel.
- Vote colours follow the WLM logo (dark green and red, softer in dark theme).
- "WLX Jury" wordmark replaces the signed-out brand.

**Fixes**

- Vote marks centred in their buttons, buttons one size.
- A first tap on a touch screen no longer votes.

**Operations**

- Migrations: V106, V107.

### 26.11.11 (2026-11-16)

**Features**

- New-round form has filters only; jurors and distribution are chosen on a separate jurors form.
- Nothing is distributed until the jurors form is submitted.
- One place filter (region or country) on the review page and round lists.
- Comments on a round in review are hidden from jurors until it is distributed.
- Large view always shows both ✓ and ✗; clicking the pressed one undoes the vote.
- One vote-button design on tiles and large view.
- By-rate gallery: chips shown with the ranking.
- "Wiki account taken" on Edit user lists the matching accounts.

**Fixes**

- No criteria section on a yes/no round's edit page.

**Operations**

- Removed the "Distribute as soon as the images are ready" checkbox.

### 26.11.10 (2026-11-16)

**Features**

- Jurors are chosen when distributing a round created for review.
- "Re-apply filters" while a round waits for review.
- Find an existing account on the users page and add it to the contest.
- Adding an account sends no email unless the admin ticks a box.
- Comment chip in the by-rate gallery opens the comments.
- Staff pages shown as links in the top bar.
- Counts shown the same way everywhere.

**Fixes**

- Monument ids filter `a, b` now matches both monuments.
- Votes button and tile ✓/✗ buttons aligned.

### 26.11.9 (2026-11-16)

**Features**

- Review a new round's images before distributing them to jurors.
- Add a juror to a running round, with a fair share of unrated files.
- Funnel after each comment's author is back as a quick filter.
- Link to a file's comments stays at the comments while the photo loads.

**Operations**

- Migrations: V105.
- Rolling back: finish or cancel rounds held in review and "Adding juror" jobs first.

### 26.11.8 (2026-11-16)

**Features**

- Monument page with its Wikipedia list row, Commons category and Wikidata item.
- Round lists can be filtered by monument or, for staff, by author.
- Counts on the round statistics links.
- Comments list for jurors; tabs count the files they open.
- Votes and Comments menus in a grouped top bar.
- Statistics link on every page of the contest.
- Juror menu and Votes button told apart by icons.
- Ukrainian "усі"/"всі" used consistently.
- ✓ and ✗ icons for yes/no votes everywhere; voted tiles have no coloured frame.
- Vote and comment chips side by side on tiles; the large view links its comments.
- Comments list links each file's comments and filters by author.
- Criteria rounds' sums are labelled in the CSV and large view.
- Organisers' results bar fits with Linux fonts too.

**Fixes**

- Jurors no longer open the contest files page; their monument page links their round's files.

**Operations**

- Migrations: V102, V104.
- Release tool prints the next command and asks to confirm a restore.

### 26.11.7 (2026-11-14)

**Features**

- Authors shown to staff, with a link to their files and to their Commons page.
- Authors list for the contest and for a round, with file counts and vote results.
- Comments by one person: filter the comments report to one author.

### 26.11.6 (2026-11-13)

**Features**

- The access-denied page for another contest lists your contests and roles.
- Comments report opens by the latest comment first.

**Fixes**

- View menu no longer stuck on "By rate" in all jurors' gallery.
- Access-denied page can no longer turn into a server error.
- Conflicts of interest: a selection of more than 2,000 files is refused instead of cut.

**Operations**

- Deployment credential scanner and write-path checks tightened.

### 26.11.5 (2026-11-13)

**Features**

- Comment count on tiles and a toggle "Only with comments".
- Comments report with CSV export for admins, organizers and root.
- Comments on a stopped round's files are refused for staff too.

**Fixes**

- Sign-in page no longer fails for clients that don't ask for HTML.
- Authors stored as wiki markup are shown as plain account names.
- Root button to normalize stored authors; conflicts of interest then match them.

**Operations**

- Migrations: V101.
- Release tool shows status and the exact next commands, and takes a short hash.

### 26.11.4 (2026-11-13)

**Features**

- Large view of one juror's votes shows the juror's name and vote with its time.

**Fixes**

- Switching rounds in one juror's view no longer ends in an error page.
- Stored times (invitation and link expiry, job times and others) are no longer shown shifted.
- By-rate view shows a vote's time at once.

**Operations**

- Migrations: V99, V100.
- Release tool refuses to deploy while a background job is running.

### 26.11.3 (2026-11-12)

**Features**

- Image check result shown on the page instead of raw JSON.
- Sort a juror's gallery by vote time; times shown on tiles.
- Organizers see one juror's votes on the tiles, read-only.
- Organizers' results bar stays on one line.

**Fixes**

- Ukrainian «Ні» shown for an inactive round.
- Adding a new person no longer fails with a server error.
- Import Users lists the lines it could not add instead of skipping them silently.

**Operations**

- Migrations: V97, V98.
- Script to fill earlier votes' times from access logs.

### 26.11.2 (2026-11-12)

**Features**

- Admins can change a juror's email at once; the person keeps the password.
- Invitation email is a choice, unticked by default.
- Account changes log for admins and root.
- User menu starts with the signed-in account's name, wiki login, email and role.

**Fixes**

- Password change form asks for the current password first.
- Account mails say what they are about.
- Removed the root-only Sort field on the user edit page.
- Page addresses with a trailing slash redirect instead of showing "not found".
- Role select and add button level with the inputs on the users page.
- Saving one's profile returns to the page it was opened from.
- A juror's menu no longer lists last year's contest with no open round.
- By-rating order no longer offered on Unrated, Selected and Rejected tabs.

**Operations**

- Migrations: V96.

### 26.11.1 (2026-11-12)

**Fixes**

- Dark mode: secondary buttons and pager links have visible borders and text.
- Light mode: secondary buttons no longer look disabled.

### 26.11 (2026-11-11)

First calendar-version release; includes everything developed since the previous deploy (V71-V95).

**Features**

- Light, dark and auto colour modes on a refreshed layout, kept per user.
- Yes/no rounds: the all-jurors gallery shows each file's approvals; "selected by at least N" filter.
- REST API with Swagger UI for root, using API tokens.
- Accounts are created by invitation only; email change is confirmed from the new address.
- "Keep me signed in", "Sign out everywhere", and idle and absolute session lifetimes.
- Criteria catalogue: admins pick each round's criteria from a shared list.
- Criteria rounds show per-criterion averages and stars in lists, CSV and large view.
- Root's "Check every round's images" repairs a round's file list.
- Sign in with Google or Wikimedia for existing accounts (built, not yet offered).
- Passwords set by their owner through a link, or by root or an admin.
- Links to share for invitations and password resets, with a copy button.
- Organizers are read-only apart from comments; only jurors vote.
- Stopped jurors can no longer vote in that round.
- Conflicts of interest: disqualify and requalify files on a stopped round, keeping votes.
- Disqualification history on a round's conflicts page; "Stop the round" button there.
- "Leave out conflicts of interest" on an open round only sets the flag.
- Round form option for the file score rule: average, average over assigned jurors, or total.
- Warning beside the score rule that saving a different one re-scores the round.
- "Worst mark" option for rated rounds.
- "Rejected by at least N" can leave out or take only those files.
- A next round from several previous rounds checks each of them on its own.
- "Leave out files that are in round(s)" filter.
- International import limits files to the contest's country categories.
- One product name, "WLX Jury", and consistent words: Contest, Juror, round.
- Faster organizer views for ranked and grouped all-jurors pages.

**Fixes**

- Votes no longer fail while admins change rounds.
- Image proxy serves only Wikimedia's standard thumbnail widths.
- Misplaced or duplicate criteria votes are refused.
- A file Commons sends without image info no longer fails the import.

**Security**

- Hardening of roles, host names, cookies, sessions, password storage, output escaping, logs, exports and security headers.
- Old SHA-1 passwords work for three months, then need a reset.

**Operations**

- Migrations: V71-V95.
- MariaDB 10.11 supported; Java 17 required.
- Debian/RPM packages and Vagrant dropped; the ZIP is the release artifact.
- Release tool prunes old releases and dumps; monit check of the app.
- docker-compose works again.
- Smaller ZIP: no Scaladoc.

### 2026-11-02 14:01Z: hotfix `0.13-round-filters-rolefix-f3f2c73617de`

**Security**

- Contest roles restricted to an allowlist.

### 2026-10-31: category operations (`f9c31ce5`, what production ran until 26.11)

**Features**

- Round filters can exclude a category and combine two with AND, OR or MINUS.

**Operations**

- Migrations: V69, V70.

### 2026-10-30: conflicts of interest

**Features**

- Rounds can leave out the contest staff's own files.
- Better region filters: named special nominations, Wiki Loves Earth regions, cached counts.
- Clear access-denied pages; jurors see only their own votes.
- Galleries keep the juror's place and a stable order, and each click is faster.
- Import of international winners.

**Security**

- CSRF protection on votes.

**Operations**

- Migrations: V67, V68.
- Static assets served with content digests.

### 2026-10-24 and 2026-10-25: contest files gallery

**Features**

- The contest's files as a gallery, with regions and a large view.
- Add files to a round from a pasted list; WebP thumbnails.

**Operations**

- Migrations: V65, V66.

### 2026-10-23: deleted and renamed Commons files

**Features**

- Files deleted on Commons are hidden; renamed files show under their new name.
- The large view loads from Wikimedia at its own sizes.

**Operations**

- Migrations: V63, V64.

### 2026-10-22: background jobs, multi-contest accounts (release 0.13)

**Features**

- Round creation and "Distribute new files" run as background jobs with progress, Cancel and Retry.
- Rounds being created stay hidden from jurors.
- One account per person, a member of several contests with a role in each; contest switcher.
- "Import files" and "Update monuments" run in the background.
- Galleries page faster.

**Fixes**

- A double submit no longer creates two rounds.
- Media-type filters work again.

**Operations**

- Migrations: V54-V62.
- Single service with versioned releases, restore path and recovery runbook.
- Tables converted to InnoDB.

### 2026-09-07: query and security work from spring 2026

**Features**

- Faster galleries and region statistics.
- Round consistency checks and distribution verification.

**Security**

- Fix in round filters.

**Operations**

- Migrations: V46-V53.

### 2026-03-08: local image cache

**Features**

- Thumbnails served from a local cache through the app's image proxy.
- Faster region statistics; monuments updated in place.
- Special nomination filter on the round form.

**Security**

- Security fix (2024-07-28).

**Operations**

- Migrations: V44.
- Play 3.0 and the MariaDB JDBC driver.

### 2024-07-08 and 2024-06-28: media type filter

**Features**

- Rounds can filter by media type.
- Videos play in the gallery and the large view.
- Criteria names per contest type.

**Operations**

- Migrations: V42, V43.

### 2023-12-07: decimal minimum rate

**Features**

- A round's minimum rate from the previous round can be a decimal.
- Faster rounds list; "selected by at least" can be none.
- Statistics show when no round is active.
- WLM 2023 criteria.

**Fixes**

- Distributing new files fixed.
- A criteria vote no longer jumps to the next file.

**Operations**

- Migrations: V41.

## Earlier releases (from git history)

Dated by their development period; release names come from the git tags `0.8`, `0.9` and
`0.10` and `version :=` in `build.sbt`. Migrations V1-V10 are the earlier Play evolutions.

### 2022: REST API started (developed 2022-11 to 2022-12)

**Features**

- REST endpoints for contests (not served).

### 2021: version 0.13, all-users list (developed 2021-06 to 2021-12)

**Features**

- All-users list for root.
- Links use HTTPS.
- Organizers see each file's author in the file list.

**Operations**

- MySQL 8 supported.
- Migrations: V40.

### 2020: jurors started and stopped one by one (developed 2020-05 to 2020-12)

**Features**

- An organizer can start or stop each juror in a round.
- One more thumbnail size.
- Import files from a pasted list, names normalised, duplicates removed.
- Special nomination monuments updated; the author is saved.
- Subregions, with Kyiv grouped, in the region menu.

**Operations**

- Migrations: V37-V39.

### 2019: start and stop rounds, special nominations (developed 2019-05 to 2019-12)

**Features**

- Each round can be started and stopped on its own, so several can be active.
- Jurors can be put in an order that the distribution follows.
- Round filter by special nomination; contests linked to a Commons campaign.
- Contests can be filtered by type and year.
- Global admins can view round statistics.

**Fixes**

- Rounds can be edited when the file loading fails.

**Operations**

- Migrations: V34-V36.
- Java 11 and systemd packages.

### 2018-08 to 2018-11: after 0.10 (developed)

**Features**

- Rounds include and exclude categories; "selected by at least" can be 0.
- Users and jurors are numbered; Ukrainian regions sorted.

**Fixes**

- WLE Ukraine monument updates, simultaneous rounds and region navigation.

**Operations**

- Migrations: V33.

### 2018-07: release 0.10 (tag `0.10`, 2018-07-17)

**Features**

- A round can take the top N files of the previous round.
- Pages translated; French added.
- Monument lists updated from the contest's list template.

**Fixes**

- Large view: jumping to the first file and rating from the middle of a list.

**Security**

- Hardening of forms; CSRF filter and content security policy.

**Operations**

- Migrations: V28-V32.
- Docker image.

### 2017: CSV export, sign-up, shared files (developed 2017-02 to 2017-12)

**Features**

- CSV export of a round's results.
- Users can sign up; jurors invited by wiki mail; wiki user names imported in bulk.
- Contests can share files; the file's author is stored.
- Comments belong to a contest.
- Sharper thumbnails on HiDPI screens.
- The previous round's filter can take unrated files.

**Operations**

- Migrations: V23-V27.

### 2016-10 to 2016-12: rating with criteria (developed, after 0.9)

**Features**

- Rated rounds with criteria: each criterion scored, the total kept.
- Half-stars per round; rating by keyboard digits.
- Monuments get regions; rounds filter by monument.
- Round editing: choose jurors, see statistics, add new files to an existing round.
- Comments from previous rounds are shown.
- Select and reject on the gallery page; thumbs in the large view.

**Operations**

- Migrations: V19-V22.

### 2016-10: release 0.9 (tag `0.9`, 2016-10-04)

**Features**

- Round filters: Commons category, regions, file size, megapixels, previous round's rate.
- Contest greeting and file descriptions.
- Import files from a Commons page, category or file list.
- Faster galleries, files grouped by region.
- Accounts record a wiki user name, accepted at sign-in instead of the email (with the app's own password).

**Operations**

- Migrations: V11-V18; Flyway replaces Play evolutions.
- Debian and RPM packages with systemd.

### 2016-06: release 0.8 (tag `0.8`, 2016-06-23)

**Features**

- Contests managed in the app: create a contest and import its files from Commons.
- Users imported in bulk from names and emails, and emailed.
- Rounds created and edited in a form, jurors distributed at once.
- Root can view any contest's organizer pages.

### 2015: parallel rounds, other countries (developed 2015-01 to 2015-12)

**Features**

- Several rounds at once, as tabs; two jurors per file.
- WLM 2015 Ukraine regions.
- Used for other countries' juries (Spain, Italy, Greece, Wiki Loves Africa).

### 2014: contests in the app, rated rounds, comments (developed 2014-05 to 2014-12)

**Features**

- Contests, rounds and users kept in the app; admin pages for users and rounds.
- Email to jurors; files distributed among jurors.
- Rated rounds, gallery by rate, round statistics, monuments list.
- Comments on files, juror statistics, optional rating, full resolution, TIFF support.
- Image preloading, several gallery sizes, keyboard navigation, redesigned interface.

### 2013-10: first version, WLM 2013 Ukraine (developed 2013-10-14 to 2013-10-27)

**Features**

- Jurors sign in and select files in a gallery and a large view, in two rounds.

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
deployed, replace its "date TBD at deploy" with the deploy date, date it from its first new
`flyway_schema_history` row (or from the deploy record if it has no migration), and add the
release name.
