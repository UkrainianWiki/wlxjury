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
record. Work that is developed but not in a release yet is under **Unreleased**; a release that is
built but not deployed is under its own heading with "date TBD at deploy". The
[earlier releases](#earlier-releases-from-git-history) are dated by when the work was
developed (its first to last commit), or "released around" a git tag, with the release name
where a tag or `version :=` in `build.sbt` gives one. Earlier deploy dates may be added later.
Plans that aren't built
yet are in the [roadmap](docs/plans/2026-11-02-roadmap.md). PR numbers are the stacked series,
from #184 on (01–59 in `docs/plans/2026-11-03-pr-series.md` on `origin/stack/series-doc`, the
later ones in the roadmap and its deploy records).

**Versioning:** from the next release on, versions are calendar-based, `YY.MM` (e.g. `26.11`),
with `.N` for an extra deploy in the same month (`26.11.1`), and release headings will read
`## 26.11 (YYYY-MM-DD)`. A hotfix of a deployed `YY.MM.N` release gets a fourth number,
`YY.MM.N.H` (`26.11.13.1`). Past releases keep their dates, and their tag names where tags exist
(`0.8`, `0.9`, `0.10`); they are not renumbered.

## Unreleased (developed, not yet deployed)

No next release is set yet (`build.sbt` says `26.12-SNAPSHOT`). Everything up to #381 was
deployed in `26.11.13`, and three fixes in the hotfix `26.11.13.1`; what is developed after them
is listed below.

**Features**

- **All-users page menus** (the maintainer's decision of 2026-11-19, for 26.11.14; developed
  2026-11-19, `stack/198-users-contest-filter`, a follow-up of the contests page menus): root's
  all-users page (`/admin/allUsers`) gets the contests page's **Type**, **Country** and **Year**
  menus (the same parsing, country rule and menus: `contestFilterMenus.scala.html`) and a **Role**
  menu (All, Admin, Organizer, Juror) in the navbar; as on `/contests`, a bar with filters, so the
  page links (Contests, Users and the others) move into the Pages menu. They filter the accounts by their contest
  memberships: an account is listed if it has an active membership (not "No role", Board, Audit
  committee or Staff) in a contest of the chosen type, country and year with the chosen role in
  that same contest. By default every menu is on **All** (all years, not the latest) and the page
  lists every account, those in no contest too, in its usual order. Each item shows the accounts
  it lists within the other menus' choices, counted in memory from the memberships the page
  already loads (no extra query); the menus are plain links keeping the other choices. A choice
  that lists nobody says so, with a link to all accounts (new keys `users.filter.none`,
  `users.filter.reset`). The contest type-and-year grid is gone from the page, and its template
  (`contestsTable.scala.html`) with it; a contest's own users page has no menus. On both users
  pages the row's edit pencil is now a link named "Edit" for screen readers (it was an unnamed
  button inside a link, which the new browser test's axe scan flagged). No migration.
- **Warnings for a round nobody can vote in** (reported by an organizer on 26.11.12, the
  maintainer's decisions of 2026-11-19, DG10: warn, never block; developed 2026-11-19,
  `stack/202-round-warnings`): resuming a juror's voting in a stopped round still resumes it, with
  a warning that they can't vote until the round is started (none in a round only frozen while
  open). Pausing the last juror who can vote in an open round, or starting a round with none, is
  done with a warning that no juror can vote in it and what to do. A juror can vote if their
  `round_user` row is active (a NULL `active` counts as paused), their account is not deleted and
  they are still a juror of the contest (an active `jury` membership). An open round (active, or
  frozen while active; never one being created) with no such juror gets a lasting notice on its
  statistics page (for its admins and root with what to do, for its organizers only the fact;
  not repeated under the flash that says it), a warning icon after its Active "Yes" in the
  Rounds table (its words in the tooltip and for screen readers), and a **Warn** in the contest
  health report's H12 for each such round (`health.h12.jurorspaused`; a contest with no recent
  activity stays "not checked", as for the other checks), flagging the round there. It replaces
  H12's Info `health.h12.nojurors` (removed), which only said so when no open round had jurors.
  Adding a juror or reassigning gives no warning (the statistics page's notice covers what they
  leave). One read (`RoundUser.openWithoutActiveJuror`). Jurors' pages are unchanged. Fixed with
  it: a juror row with a NULL `round_user.active` no longer breaks the round statistics page (it
  is read as paused). New keys `round.warning.stopped.juror`, `round.warning.nojurors`,
  `round.warning.nojurors.act`, `round.warning.nojurors.short`. The health report's rounds
  "Jurors" column now counts only jurors who can vote, and H12 takes a round frozen while open as
  open (`Round.openToJurorsIds`). No migration.

**Fixes**

- **The first keys typed in a saved category or import source are kept** (a follow-up of the
  26.11.13.1 hotfix item "A saved category or import source can be edited again"; developed
  2026-11-19, `stack/201-chromium-ci`): on a phone or a busy page, the first keys typed after a
  tap on a field with a saved value were dropped (typing "Category:Images from Wiki Loves
  Monuments 2026 in Armenia" could send "Wiki Loves Monuments 2026 in Armenia"), since the value
  became editable text only once Tom Select's own focus handling ran, on a timer. It now does on
  the field's own focus; a field left before that timer keeps its value as before
  (`category-suggest.js`). No migration.
- **A started round's criteria are read only** (found on production round 1969, the
  maintainer's decisions of 2026-11-19; developed 2026-11-19, `stack/203-criteria-voted-form`):
  the edit form of a rated round showed the ten criteria selects even after its first vote. Once a
  round is started, running (active, or frozen while active) or with a vote, its form has no
  criteria selects: it lists the round's criteria in order, read only, or says that a round
  without criteria can no longer get any, under a line saying why. Its save refuses any change of
  the criteria, a replacement in its place too (which was taken after the first vote, moving the
  criterion's rates), with that line as the error; the vote check keeps its locks. A post without
  the selects keeps the stored criteria. A round stopped before its first vote keeps the full form.
  `RoundCriterion.started` / `hasVote` (plain reads for the form, criterion rates read only for a
  criteria round). `criteria.help` updated; new keys `criteria.locked`, `criteria.locked.none`;
  `error.criteria.voted` removed. No migration.

**Development**

- **Chromium browser tests green on CI** (`stack/201-chromium-ci`): `category-suggest.spec.js`'s
  phone tests (A-09) by the fix above; `rounds-filter.spec.js` (O-07) waits for a pager link's
  colour transition before comparing it with the secondary grey; `voting.spec.js`'s Back checks
  (U23) print Chromium's back/forward cache reasons when it doesn't restore the page, and, off CI
  only, take its pruning for the host's critical memory pressure (the full local run's flake) as
  "not checked", checking the reloaded page's stored vote instead (UI practices, U23).

## 26.11.13.1 (2026-11-19)

Built 2026-11-19, from `26.11.13`: a hotfix of three fixes. **No migration**: the schema stays
V107, so the release tool skips the database backup, and going back to `26.11.13` needs no
database restore.

**Fixes**

- **"Download images locally" says when everything is already cached** (the contest's images
  page and a round's form), or when there is nothing to download, instead of showing nothing;
  with the time it checked, so a later visit doesn't take an old result as current. A run that
  downloaded some files also says how many were cached before.
- **No "Reassign files…" in a round of one juror** (the maintainer's note on 26.11.13; developed
  2026-11-19, `stack/200-reassign-single-juror`, a follow-up of #368 and #377): the round's
  statistics showed the button for the one juror of a round, whose reassign page could only refuse
  ("tick at least 1 juror… or add a juror first") or, in a round where every file goes to every
  juror, would have stopped the juror and removed their unrated files with nobody else voting on
  them. The button now shows only if enough other jurors of the round could take the files, each
  with an active jury membership of the contest and an account not deleted
  (`RoundReassign.enoughReceivers`): at least N others where each file goes to N jurors (stopped
  ones included: a ticked one is started again), at least one other active one where every file goes
  to every juror (nobody is started there). The page and the POST refuse otherwise, as with nothing
  to reassign, with no receivers and no confirm, saying why and to add a juror first (new key
  `job.error.rebalance.reassign.alone`); the job checks again under the round's lock at each "stop"
  step, a Retry's too, so a juror stopped or removed after the reassign was queued fails it before
  anything changes (the error stores and logs the juror's account id, not their name). The page's
  unreachable "no other juror" line and its key `round.juror.reassign.no.receivers` are gone. Admin
  guide. No migration.
- **A saved category or import source can be edited again** (since 68c4716c of 2026-11-01): the
  import source and the round form's categories showed their value as an item that a click could
  neither select nor edit. Focus now makes it the text, wholly selected; leaving unchanged keeps
  it, a cleared one is emptied, and a quick Tab no longer focuses the field again. A long value
  now shows on one line with an ellipsis, whole as its tooltip and when edited
  (`category-suggest.js`, `main.css`; `hotfix/category-suggest-focus`, developed 2026-11-19).

## 26.11.13 (2026-11-19)

Deployed 2026-11-19 (built 2026-11-18, rebuilt 2026-11-19); from `26.11.12`, PRs #364-#381. **No
migration**: the schema stays V107, so the release tool skips the database backup. Going back to
`26.11.12` needs no database restore, but first let each reassign job, and each distribution or add
in its `topup` or `waiting` phase, finish or cancel it (`docs/deployment.md`). Changes to know
about:
- **Look:** the Wikimedia Codex look across the tool (the vote colours kept), and the "WLX Jury"
  wordmark as the heading of the sign-in and other signed-out pages.
- **Rounds:** the Rounds and Round Statistics pages filter rounds by status and type and sort
  them (the same menus on both, kept between them); each round's type is an icon before its name
  (✓ ✗ yes/no, a star rated; coloured when active, grey when not), on those pages and in the
  galleries' round menu; on Statistics a filter choice that leaves out the round shown opens the
  first round it lists; a juror's unrated files can be reassigned to other jurors of a running
  round.
- **Contests (root):** the contests page filters by type, country and year (the latest by
  default) and sorts oldest first, newest first or by country.
- **Large view:** the jurors' rank numbers are fully visible, the score is shown once, and links
  that leave the tool open in a new tab, with a mark of their site (Commons, Wikipedia, Wikidata).
- **People:** admins add people from another of their contests, and can add an account by a wiki
  account nobody verified once they confirm it is the person they mean; no mail is sent unless a
  box is ticked.
- **Support:** a contest health page for root and the contest's admins, a warning on the import
  form when the source doesn't match the contest, the import source checked on Commons before an
  import starts, and a page telling a juror why a round can't be opened.
- **Sign-in:** every sign-in redirects, so a reload or Sign out no longer meets the CSRF error page;
  a stale sign-in form while signed in opens "You are already signed in as"; a juror whose rounds
  are all closed is told which and why instead of "No round configured yet".
- **Licence:** GPL-3.0-or-later from this release on.
- **Logging (server):** the production logging config now ships in the release
  (`conf/logback-production.xml`). After the deploy, run the `wlxjury-infra` playbook, then
  restart the app (`docs/deployment.md`). Until then, the new INFO support lines aren't written.
  For this one release cycle, a rollback to `26.11.12` starts with the unit's fallback,
  `/etc/wlxjury-runtime/logback.xml`, so leave that file in place.

**Features**

- **Round type icons** (the maintainer's browser check of `26.11.13`, decisions of 2026-11-19;
  developed 2026-11-19, `stack/195-round-type-icons`, a follow-up of #370): the rounds' text
  badges ("Active", "Yes/No", "Rated"), which followed names of different lengths and so never
  lined up, become icons before the name in a slot of one width: ✓ ✗ for a yes/no round, a star
  for a rated one (criteria rounds too), an empty slot for a comments-only one; coloured (the vote
  green and red, the star gold) for an active round, grey for an inactive one, whose text stays
  grey. No "Active" badge: screen readers hear "Final, Yes/No, Active" after the name, and the
  icons' tooltip says "Yes/No, Active" / "Rated, Inactive" (new key `round.badge.inactive`). The
  same in the Rounds table, the statistics page's round pager (whose centred chips leave out a
  comments-only round's empty slot) and the galleries' round menu; the Type menu shows the icons
  as a legend. The galleries' round menu now scrolls inside itself, as the region menu does (the
  same 70vh rule), instead of running off the screen with many rounds, and never sideways: a name
  too long for a narrow window is cut with an ellipsis (wrapped on a phone). The browser tests'
  fixture gets a contest of 30 rounds of every kind, named by their types, for these checks.
  The Rounds table's Number, File distribution, Rating type, Active and Images columns are as
  wide as their contents, so the names take the width left (they wrapped over four or five
  lines beside a wide Number column); on a phone those columns wrap and a name keeps 14rem. On
  a phone the galleries' round menu is at least 20rem wide (or the window less 1rem), so a name
  wraps by lines, not word by word. In the Active column the start/stop button comes first and its
  "Yes"/"No" after it, so the buttons line up whatever the word's width ("Так"/"Ні").
  On the round statistics the jurors table's icon links (gallery, file list, by rate; the
  Total row's) are square and centred with the count beside them, and the cells centred in
  their rows: since the Codex restyle (#366, already in `26.11.13`) the 28 px small buttons left
  the icons at their top and the counts on their baseline, the buttons taller than wide.
- **Large view fixes** (the maintainer's browser check of `26.11.13`; developed 2026-11-19,
  `stack/195-round-type-icons`):
  - the rank numbers of the jurors' vote list ("1.", "2.") were half off the window's left edge
    (the lists' reset left no room for them): the list is indented for them, as much as the
    numbers need (1.75em; 2.5em for "100." from a hundred jurors on), so the stars keep their row.
  - the score was written twice for all the jurors' votes, "7.67" in bold, then "7.67 (23 / 3
    jurors)": the line under the number is now only "(23 / 3 jurors)" and the worst marks. A
    round scored by the sum keeps "15 (sum of 3 jurors)" (its bold number is the average), and a
    half-star round "7.50 (15 / 2 of 3 jurors)" (its bold number is in stars, 3.75); a rated
    round's file of one juror has no line. A criteria round's line drops its leading stars and
    keeps the sum, "(Σ 14.50: 29 / 2 jurors)", one juror's "(Σ 7)" too
    (`ImageWithRating.rateDetail`, `criteriaRateDetail`; the same messages).
  - the **full resolution** link opened in the same tab, while a click on the image opens Commons
    in a new one: it opens in a new tab too (`target="_blank" rel="noopener"`), in a round's and
    a contest file's large view; the image's link gets `rel="noopener"`, and so do the file
    list's links to Commons. Every other link that leaves the tool opens in a new tab too: the
    monument box's Wikipedia links (city, name, place: `WikiLinks`), the contest's Commons
    category on the images page, the users page's Commons user page, talk and email links, and the
    gallery caption's Commons link; the monument's own page (in the tool) stays in the tab
    (`ExternalLinksViewSpec` guards the large views and the templates' `https://` links).
  - a text link that leaves the tool has a small mark after its text, by its site
    (`snippets/externalMark`, the maintainer's decision of 2026-11-19): the Commons logo, the
    Wikipedia W (`bi-wikipedia`), the Wikidata logo (both logos at the text's size, in the link's
    colour, symbols defined once per page by the layout and drawn with `<use>`, from Codex's
    single-colour logo icons) or the outward arrow (`bi-box-arrow-up-right`) for any other site;
    after a no-break space, in one span with the link's last character that doesn't wrap, so the
    mark never starts a line alone; hidden from screen readers, which hear " (opens in a new tab)"
    (new key `link.new.tab`); the space, the mark and those words can't be selected, so a copied
    link's text is its text alone; a long title without spaces wraps inside the large view's side
    column. In the large views (the monument's Wikipedia links, the author's Commons page, the title
    and the full resolution), the monument page (the "↗" glyphs replaced; `author.commons` loses its
    "↗"; the author links' title is the new `author.commons.page`, without the new-tab words the
    link already says), the images page, the users page and the contest files page; not on image
    links, icon-only links or the file list's copyable list of titles. Exempt: the galleries'
    caption link to Commons and unitegallery's Deleted badge, both inside the vendored gallery.
    `ExternalLinksCheck` checks the links of the large views, the monument page, the contest files
    page (author filter), the users page and the images page's source choice.
  - a browser test of all three (`large-side.spec.js`).
- **The statistics page's filter switches round** (the maintainer's decision of 2026-11-19, "switch
  to first in order"; developed 2026-11-19, `stack/197-stat-filter-switch`, a follow-up of #370): on
  a round's statistics, a **Status**, **Type** or **Order** choice that leaves out the round you are
  looking at opens the first round it lists, in its order (the oldest, the newest or the first
  active), so a choice that lists one round opens it; a choice that keeps the round stays on it
  (changing only the order does, unless the round is already outside the filter). The links are
  worked out on the server (`RoundsFilter.statRound`), with no redirect and no script, among the
  rounds the viewer may see; the counts in the menus are as before. If no round matches, the page
  stays on the round and says "No rounds match these choices." with a link to all rounds.
  **Statistics** in the pages menu, from a filtered Rounds page, follows the same rule
  (`currentRoundStat`: the round it opens otherwise if the filter keeps it, else the first the
  filter lists, else the round itself, which says none match); its own open round counts only if the
  viewer may see its statistics (an admin of one contest who is a juror of another is no longer sent
  to that round's statistics, which refused them). A round opened with a filter that leaves it out
  (a saved or shared link) is still shown in the row, selected, with a dashed outline (solid when
  focused, so the focus shows in forced-colours mode) and the tooltip "Not in the chosen filter",
  read by screen readers after its name (new key `rounds.filter.outside`). The Rounds page and the
  galleries' round menu are unchanged.

- **The wordmark on the signed-out pages** (developed 2026-11-17, `stack/181-signin-wordmark`):
  the "WLX Jury" wordmark is the page's heading above the form on the sign-in page, and the one
  heading of the invitation, set-up, invalid-link and email-change pages; the signed-out error,
  sign-out and account-chooser pages show it as a banner. The signed-out navbar no longer carries
  the brand, and the signed-out pages' title is `application.title`, WLX and the translated
  suffix. Its accessible name is "WLX" and the translated suffix (e.g. "WLX Журі") whatever the
  layout.
- **The Wikimedia Codex look** (developed 2026-11-17, `stack/183-codex-theme`): the Codex design
  tokens 2.7.0, vendored unmodified (`docs/vendor/codex.md`), mapped onto Bootstrap in each
  colour mode (`codex-theme.css`): 2 px corners, 32 px controls (44 large, 28 small), bold
  buttons, the system font stack, Message-style alerts, pale badges, one focus ring, the navbar
  and menus on the page's background (dark page `#101418`), Tom Select as a Codex control. One
  selected highlight (the current page, a pressed toggle, a chosen item) and one hover grey; grey
  never means selected. Links have no underline until pointed at, except inside a sentence,
  underlined at rest (WCAG 1.4.1); an anchor without href (a count of 0, e.g. "Conflicts of
  interest of this round: none") is plain text, never underlined. The vote colours and the WLM
  danger red (`theme.css`) are kept; a signed-out page with nothing in its navbar has none; a
  dark-mode page prints dark text on white. Browser checks for the selected and hover colours,
  links and the controls' heights.
- **Reassign a juror's unrated files** (step 3 of the round-editing plan, developed 2026-11-17,
  `stack/185-reassign-unrated`): **Reassign files…** beside each juror's Start/Stop in a running
  round's statistics (admins and root) opens a page with the juror's files and the receivers (D1:
  the active jurors ticked, stopped ones unticked and marked; a ticked stopped juror is started
  again). A `round_rebalance` job (`params.from`) pauses the round, moves only unrated rows to the
  receivers by water-filling of open work (each file to the eligible receiver with the most quota
  left, never one who has it in `vote` or `vote_aside`), gives the juror's unrated rows set aside
  to receivers too, rescans in its count check, and stops the juror; votes stay. Files no receiver
  can take wait for a juror: counted under the statistics table, and given out by the round
  form's opt-in **Also give out the N files waiting for a juror** (a top-up pass of the distribute
  job, least loaded first; ticked alone it gives out only those files), and by **Add juror…**, which
  gives the new juror the waiting files first within their share ("12 waiting + 69 from others").
  The add and reassign pages' confirm is greyed out until the figures are shown for the ticks.
  No migration. Admin and organizer guides. Rolling back past it: let each reassign job, and each
  distribution or add in its new `topup` or `waiting` phase, finish or cancel it first
  (`docs/deployment.md`). The conflicts-of-interest counts say "1 of the contest's files is a
  conflict" for one file, and the French author-normalisation messages keep their apostrophes.
- **Reassign files… only for jurors with files to move** (found in the 26.11.13 browser check,
  developed 2026-11-18, `stack/194-reassign-button-unrated`): the round statistics show
  **Reassign files…** only for a juror with at least one file a reassign would move (an unrated
  file in the round and not deleted on Commons, or an unrated file set aside). For a juror with
  none, the reassign page says there is nothing to reassign (suggesting "Pause the juror's voting"
  if they are still voting), and its POST refuses with 409 and queues no job; before, it queued a
  job that only stopped the juror. No migration.
- **Adding by a wiki account nobody verified** (A-3 of the existing accounts plan, decision A2;
  developed 2026-11-17, `stack/186-unverified-wiki-add`): the users page's add row given a wiki
  account and no email, for a role with access, that names accounts whose wiki account nobody
  verified no longer just refuses ("add them by email"): it adds nothing yet and shows those
  accounts by name only, none preselected; the admin picks the one they mean, ticks **This is the
  person I mean** (both required on the server) and the account is added as the search adds it
  (it joins if its owner proved the email or it has no password, else an invitation; no mail
  unless ticked), with the owner's notice in the tool, the account change log's comment "wiki
  account unverified, confirmed by the admin" and a `security.account` line by ids only. The
  result names the account with its address masked outside the admin's contests. Accounts with
  no password and no email are confirmed the same way, never joined unconfirmed; the page says
  when only root can then give such a person a sign-in link. **Import users** only counts such rows
  ("Not added yet"), linking the users page. Secure coding §14's rule changes with it (its risk
  stated there). No migration. Admin guide.
- **Filter and sort rounds** (asked for by the maintainer on 2026-11-18, developed 2026-11-18,
  `stack/187-rounds-filter`): the Rounds and Round Statistics pages get the same top-bar menus,
  Status (All / Active / Not active), Type (All / Yes/No / Rated, criteria rounds under Rated) and
  Order (Oldest first, as before / Newest first / Active first), Status and Type with counts; the
  defaults list the same rounds in the same order as before. The choice is kept in the URL
  (`status`, `type`, `order`) and between the two pages (their links, Start/Stop, the round form);
  on Statistics the menus narrow and order the round menu. Each round's type is an icon before its
  name (**Round type icons** above; they replaced the text badges this PR first had), and inactive
  rounds are in secondary grey, also in the gallery's round menu (no filters there). The page links
  of these two pages fold into the Pages menu; the round name in the Rounds table links to its
  statistics. The filter only narrows what each viewer already sees. No migration. Admin and
  organizer guides.
- **A page saying why a round can't be opened, and the import source checked on Commons** (D-0b of
  the support diagnostics plan, developed 2026-11-18, `stack/191-diagnostics-quick-fixes`): a juror
  of a round who opens its gallery or large view (or its region menu, or comments on it) while it is
  stopped, frozen as the previous round of a distribution (given back, or closing for a round
  creation), still being distributed, or they are stopped on it gets a page saying which (200, not
  the 403 "access denied" page; DG7), with buttons for their open rounds; a script gets the vote's
  409 `round_closed` with the `reason`; anyone else refused there still gets the 403. On the files
  page, a source name without a namespace prefix is checked on Commons for **Import files** and
  **Update monuments** before anything starts (DG11): the page or `Category:<name>`, whichever
  exists; when both do, the choice with each one's file count, the category preselected; when
  neither does, refused with a message; when Commons can't be reached in
  `diagnostics.sourceCheckTimeout` (5 s), the page says so and nothing starts (try again later, or
  add `Category:`). A prefixed source and a pasted list are unchanged. The files page says when a
  finished import found no files to add, or left a page's files out as in no country category of the
  contest. The import action runs on the blocking pool. No migration. Admin and juror guides.
- **Contest health page and the import form's identity warning** (D-1a of the support diagnostics
  plan, DG1, DG2, DG10; developed 2026-11-18, `stack/192-contest-health`):
  `/admin/contests/:id/health`, for root and the contest's admins only, linked from the Rounds and
  contest files pages, checks the contest against its import sources (type, year, country), its
  files by country, failed or empty imports, sources read as a page, empty or tiny open rounds, an
  open round with no source round or filter in a contest that looks mixed, rounds stuck being
  created or frozen, and no round open to jurors; each check gives pass, info, warn or fail with a
  hint (thresholds in `diagnostics.health.*`; files in no country category are counted only for
  imports since 2026-11-11, when imports began to record countries). Creators outside the contest
  are named to root only, emails masked. While an admin types an import source, the files page warns
  when it doesn't match the contest (also after queueing, without JavaScript); it never refuses. No
  migration. Not in the user guides yet.
- **Add people from another of your contests** (asked for by the maintainer on 2026-11-18,
  developed 2026-11-18, `stack/193-add-people-from-contest`): a button on the users page opens a
  page where an admin picks one of their other contests (an active Admin membership; root: any),
  newest first, and sees its members (no root accounts for admins, no retired ones), with a role
  filter, none ticked, each with their role there to keep or change (an Admin row in bold).
  Members of this contest already, and people with a live invitation into it, can't be ticked;
  accounts known only by a wiki account nobody verified can (the admin picks the account itself,
  so A-3's confirmation doesn't apply; the maintainer, 2026-11-18): one with neither an email nor
  a password joins and the result says to share a link. **Add the ticked people** (a form
  POST with the CSRF token) checks every id again (a source that isn't the admin's, or an id that
  isn't its member, is a 404 and adds nobody) and adds each as the search's **Add to this
  contest** does (it joins, or an invitation; "Send an invitation email" unticked by default;
  ticked, it also mails the greeting to those who join at once), at most 200 at a time,
  recorded in the account change log with the comment `from contest #<id>`, which gives the owner
  the search's notice in the tool; Account changes, the edit page and the notice show it as the
  source contest's name and year (the change log's link opens its users page). The users page
  says how many joined, were invited, were made Admin and were skipped, with the reasons.
  `security.account` lines by ids and counts (`add_from_list`, `add_from_contest`). No
  migration. Admin guide.
- **Contests page menus** (the maintainer's request of 2026-11-19, developed 2026-11-19,
  `stack/196-contests-filter`): root's contests page has **Type**, **Country** and **Year** menus
  and an **Order** menu in the navbar, in the design of the Rounds page's, each item with its count
  within the other menus' choices and every link keeping the others. Country goes by the contest's
  campaign, else its country text: a known country is one entry however written ("UA", "Ukraine":
  `country=ua`), **International** has international campaigns and an empty or "International"
  country, any other text is an entry of its own; the menu scrolls inside its box. By default the
  page lists the latest year of the contests, not the calendar's; **All years** is `year=all`.
  Oldest first by default, by year and then as added (before, the list was in the order the
  contests were added, all years together); the old `?contestType=…&year=…` links still work.
  **By country** (`order=country`, A to Z only, new key `contests.order.country`) sorts by the
  name the Country menu shows, in the page language's alphabetical order, International last, each
  country's contests by year and then as added. The type-and-year grid above the list is gone from
  that page (kept on root's all-users page). After **Add** the page opens on the new contest's
  year, keeping the list's type, country and order where the new contest is of them; after
  **Import**, on the imported contests' year (all years if several, or if the import added none,
  all of them being there already). A country written in another language ("Україна") joins its
  entry. No migration. Admin guide.

**Fixes**

- **Sign-in always redirects; the start page explains closed rounds** (a report of 2026-11-19:
  an organiser who is also a juror signed in as a juror whose round was stopped, saw "No round
  configured yet", and reloading gave the CSRF error page, refs ba1126f9 and 92ca61d7; Sign out
  failed the same way; developed 2026-11-19, `stack/199-signin-redirect`):
  - a password sign-in without `returnTo` answered its POST with the start page itself (a juror
    without an open round got the "no round" page): a reload posted the sign-in form again, and
    the page's own forms (Sign out, the contest switch) failed CSRF, since the new session had no
    token until a page was opened. Every sign-in (password, Google, Wikimedia, invitation) now
    redirects (303) to the start page, or to its `returnTo` as before, so the page opens by a GET
    with its token;
  - a signed-in user's sign-in form posted again without a valid token (a page still open from
    before, the Back button, a stale sign-in tab on a shared computer while another account is
    signed in) signs nobody in and switches neither the account nor the contest; it goes (303)
    to a new page, `/auth/signed-in`, instead of the error page: **You are already signed in
    as** the account's name, that to sign in with another account one signs out first, and
    **Continue** (the start page) or **Logout** (new keys `signed.in.already.title`,
    `signed.in.already`, `signed.in.already.other`, `signed.in.already.continue`). As with any
    refused form, Play clears the session's CSRF token, so the account's other open tabs need a
    reload before their next vote or form. Logged as before (`csrf_failure`, with its
    reference). A signed-out one and other refused forms keep the error page. A signed-out visit
    to `/auth/signed-in` asks to sign in and then opens the start page, not that page;
  - the "no round" page of a juror whose own rounds of the contest are closed to them now says
    **Your rounds are closed now** (new key `no.round.yet.closed`) and lists each with why, the
    round state page's title and text (stopped, still being prepared, closed for now, closing, or
    their voting stopped; a `round_user` row with no `active` value counts as stopped, as the
    juror's open rounds already took it, though the vote path still takes such a juror's vote, a
    known gap); only rounds with their own `round_user` row, never other jurors' or another
    contest's; one query. With no such round it says "No round configured yet" as before. Juror
    guide.

**Development**

- **Support log lines** (D-0b of the support diagnostics plan, developed 2026-11-18,
  `stack/191-diagnostics-quick-fixes`): the import's summary as `import_done job= contestId=
  mode= source="…" …` (the source quoted), `monument_lists_done`, and the scheduler's
  `job_done` / `job_failed` / `job_crashed` lines with `contestId`; `round_access_refused reason=
  roundId= accountId= contestId=` on the new `round.access` logger (INFO) for each juror shown the
  state page; `server_error` and `csrf_failure` gain `accountId` and `contestId`; the Commons and
  image-cache WARN lines quote titles, URLs and exception text. The production logback writes
  `services.imports`, `services.jobs`, `security.account`, `security.roles`, `round.admin` and
  `round.access` at INFO (`round.counts` stays at WARN). Operations §1.7.
- **Licence: GPL-3.0-or-later** (developed 2026-11-17, `stack/182-gpl-relicense`): relicensed from
  Apache-2.0; releases before this stay available under Apache-2.0. `LICENSE` is the GPL-3.0
  text, `NOTICE` lists the bundled components and the option chosen for each dual-licensed one;
  both ship in the ZIP and Docker image. The unused `tyrex` jar (Exolab licence with a credit
  clause) is excluded.
- **User guides (docs only)** (developed 2026-11-17, `stack/184-codex-guide-shots`): the guides'
  screenshots regenerated in the Codex look.
- **The production logging config ships in the release** (developed 2026-11-18,
  `stack/189-logging-config-in-releases`, D-0a of the support diagnostics plan):
  `conf/wlxjury/logback.xml` moved to `conf/logback-production.xml`, which the ZIP carries
  (`sbt dist` refuses a ZIP without it); the `wlxjury-infra` unit starts the selected release
  with it, so a logger change ships with its code and a backout brings back the old release's
  file (a release that predates it falls back to `/etc/wlxjury-runtime/logback.xml`, kept for one
  release cycle). The deploy tools no longer snapshot or restore `logback.xml`; the loggers
  themselves are unchanged. The infra playbook now takes the deploy tools' source per run, from
  a worktree at the running release's commit (or a later pushed one), and refuses an older or
  unrelated commit before any change. In effect after the playbook run and the app's next
  restart.

## 26.11.12 (2026-11-17)

Deployed 2026-11-17, build `26.11.12-099e6d68e56f` (release commit `099e6d68` on
`release/26.11.12`), schema V107, from `26.11.11`, PRs #359-#363. **Two migrations**: V106
(`account_join_request`, the table of join requests and of pairs kept apart, and the account
change log's `account_joined` field) and V107 (the log's `account_retired` field). The schema
changed, so the release tool took a database backup before it started the new release. Going
back to `26.11.11` means the release tool's restore, or the undo statements at the end of V107
and then V106 run by hand before the old release starts. Deploy record: roadmap, "26.11.12
deploy record". Changes to know about:
- **Accounts:** root and the contests' admins get the possible duplicate accounts report (Keep
  separate), can **join two accounts** (a background job; Abandon) and **retire an empty
  duplicate** from the "wiki account taken" panel; no mail is sent unless a box is ticked.
- **Colours:** the yes/no vote colours and Bootstrap's danger red follow the WLM logo (dark green
  and red, softer in the dark theme); the signed-out brand is a "WLX Jury" wordmark.
- **Vote buttons:** the marks are centred, the buttons have one size, and a first tap on a
  touch screen votes nothing.

**Features**

- **Vote colours from the WLM logo** (developed 2026-11-17, `stack/180-wlm-vote-colours`): the
  yes/no colours of the vote buttons, chips, tally, large view and the navbar's selected and
  rejected tabs are the WLM logo's dark green (`#025935`) and red (`#8f0000`) in the light theme
  and softer tones of them in the dark theme (`#019f62`/`#d85546`; text `#44cc8e`/`#ff968a`, 4.5:1 on the active tab),
  less aggressive than the bright pair they replace; the dark tile buttons keep their 1 px halo
  over light photos. Bootstrap's danger red (alerts, validation errors, danger buttons and badges,
  the worst mark, the deleted-on-Commons frame, the gallery's error text) moves to the same red
  family (`#8f0000` light, `#f46e5d` text and `#d85546` fills dark, with Bootstrap's derived
  subtle and emphasis tones); success green is unchanged outside the vote marks. The signed-out
  navbar's brand is a wordmark: a serif W, the L as a green ✓ and the X as a red ✗ in the vote
  colours (inline SVG named "WLX" for screen readers), then "Jury" (`application.brand.suffix`:
  Jury, Жюри, Журі, Jury).
- **Possible duplicate accounts** (J-1 of the account join plan; developed 2026-11-16,
  `stack/173-duplicates-report`): **Possible duplicates** on the users page lists the groups of
  accounts that share a wiki account (in any letter case, with or without `User:`, underscores
  as spaces) or, in root's report only for now, a verified email (one account's proven address on
  another's Google or Wikimedia sign-in), nothing else (not a name, not a shared round); a
  contest's groups for its admins (a
  group with an account that is a member of it or worked in it; addresses masked outside their
  contests, only their contests shown, root accounts never), every group for root (from the
  Users page of all contests). **Keep separate** (a form POST with the CSRF token) records a pair
  as different people, or one person's accounts kept apart on purpose (a screening portion:
  **Keep separate from the others** on its row), and hides the pair for good; only root or an
  admin of every contest the group's accounts are in or worked in may, and a group that changed
  since the page was shown records nothing. Read-only otherwise: no account changes, nothing is
  mailed; the report and each Keep separate are logged by ids only (`security.account`). Joining
  is J-2's (below). Admin guide.
- **Retire an empty duplicate account** (A-4 of the existing accounts plan, decision A3; developed
  2026-11-16, `stack/178-retire-empty-account`): on Edit user's "Other accounts with the wiki
  account" panel, each **Add that account to this contest instead** has an unticked **Retire the
  account being edited**, for one person with two accounts. Offered only for an empty account (no
  votes, rounds, comments, linked sign-in or API token), to root or an admin of every contest it is
  in (and of this contest, where it is a member or invited); an admin account to root only; never one's own or a root account; not while a round of its
  contests is getting its files or a new juror (a round held in review doesn't stop it; the panel
  says why otherwise). Retiring, after the add,
  in one transaction: its memberships and unused links gone, its email address freed for the other
  account, its password cleared, its sessions ended, `users.deleted_at` set; it never signs in
  again and is listed nowhere (members, jurors, the search, the conflict-of-interest filter,
  root's all users). The panel shows what changes in the conflict-of-interest filter before the
  add. Round creation and adding a juror re-check the account under its users row lock, so a
  retired account is never assigned. **Account retired** in the change log on both accounts;
  logged by ids only (`security.account`); no mail. Admin guide.
- **Join two accounts** (J-2 of the account join plan, decisions J1–J3; developed 2026-11-17,
  `stack/179-account-join`): **Join…** on a group of the possible duplicates, or **Join the two
  accounts…** on Edit user's "Other accounts with the wiki account" panel, opens a preview, for
  root or an admin of every contest each account is in or worked in. The admin chooses the account
  to keep (nothing preselected; only an account whose owner proved its email, where some have
  one), then sees for each other account its contests and roles (the higher-access role kept), its
  rounds, votes and votes set aside, duplicate unrated assignments (merged), **collisions** (a file
  both accounts voted on in one round: any refuses the join, so no vote is ever dropped; a vote and
  the other account's unrated assignment merge, keeping the vote), the
  conflict-of-interest change and the Google or Wikimedia sign-ins it unlinks. **Join** (a POST
  with the CSRF token, the preview read again) runs a background job, round by round: memberships,
  round assignments, votes (with their criteria rates), votes set aside and comments move to the
  account kept, then the other account is retired as A-4 retires one. Both accounts' sessions end
  and their API tokens and unused links are revoked; meanwhile the retiring account is signed out
  and can't sign in, and neither account is added to a round or a contest, accepts a link, or has
  its role, password or API tokens changed. Cancel only before the first round moves, Retry after a
  failed step (finished for the admin who retries if the one who started it no longer may); root
  can abandon a failed join (both accounts released, what moved kept). No mail unless **Tell both owners by email** is ticked. **Account joined** in the
  change log on both accounts; logged by ids only (`security.account`). Admin guide.

**Fixes**

- **Vote buttons: marks centred, one size, a visible zoom** (the maintainer's report on 26.11.11;
  developed 2026-11-16, `stack/177-vote-marks-centred`): the ✓ and ✗ sat off centre in their
  buttons, the ✗ most. The glyph's place came from the line's baseline, set by the text font's
  ascent and descent (Arial on the tiles, the system font in the large view; 2.5-3 px off with
  another font), plus Bootstrap Icons' -.125em text alignment; each icon is now a box of a text
  line's height that centres its glyph (flex, `line-height: 1`), and the ✓, whose ink is half a
  unit right of its box's centre, is moved back by that everywhere it is shown. Within 1 px of the
  button's centre on both axes, in Chromium and Firefox, both modes. The large view's ✓ and ✗ have
  the navigation buttons' weight (Bootstrap's border width, no longer 3 px), and the gallery tiles'
  ✓ and ✗ are the large view's buttons (`btn btn-sm`, 32 × 31 px, no longer 38 px with a 3 px
  border), with a backing of the page's colour and a 1 px ring of the text colour over the photo. The
  tile's zoom (a video's play), a white sprite hard to see on a light photo, is the same button in
  the text colour with a magnifier (`bi-zoom-in`; `bi-play-fill`), still above the ✓ and ✗ (the
  maintainer's decision), centred over them; on a small tile the three stay 8 px apart. One design
  through `main.css` `wlx-vote-btn` and its tile variant `wlx-tile-btn`; the icon box an em wide,
  so sizes don't wait for the icon font. A first tap on a tile not pointed at (a touch screen's)
  that landed on its ✓ or ✗ voted as well as showing the buttons: it now only shows them
  (`unitegallery.js` select and reject clicks). Juror guide.

**Development**

- **`account_join_request` and `account_joined` (V106)** (the account join plan's J-1; developed
  2026-11-16): a table of join requests and pairs kept apart (an ordered pair, a state of
  `pending`, `done`, `refused`, `cancelled`, `kept_apart` with a unique key on the pairs kept
  apart, the proofs and the kept address for J-3's requests, who asked and decided, the contest),
  and `account_change`'s field CHECK gains `account_joined` (for J-2). Rerunnable (`IF NOT
  EXISTS`, the CHECK dropped and added), each statement under a 60 s `lock_wait_timeout`; the
  rollback is in the script's last comment (FlywayUpgradeSpec "V106's account join requests").
  The report on production's size (2,800 accounts, 280 in 86 groups, DuplicateAccountsSpec in the
  test container): 94 ms; the names one read of `users` (a range of its wiki account index, 280
  rows), the email path from `user_identity` with `users` by its keys.

- **`account_retired` (V107)** (the existing accounts plan's A-4; developed 2026-11-16):
  `account_change`'s field CHECK gains `account_retired`, keeping every field before it (V106's
  `account_joined` included). One statement, rerunnable (the CHECK dropped and added), under a 60 s
  `lock_wait_timeout`; the rollback is in the script's last comment (FlywayUpgradeSpec "V107's
  account_retired field"); retired accounts stay retired after it.

## 26.11.11 (2026-11-16)

Deployed 2026-11-16, build `26.11.11-e0eaece3372c` (release commit `e0eaece3` on
`release/26.11.11`), schema V105, from `26.11.10`, PRs #355-#358. **No migration**: the schema
stays V105, so going back to `26.11.10` is the app alone. Deploy record: roadmap, "26.11.11 deploy
record". Changes to know about:
- **Creating a round:** the new-round form has the filters only and ends with **Review images
  first** or **Choose jurors next**; the jurors and the file distribution are chosen on the
  round's jurors form, and nothing is distributed until it is submitted.
- **Removed:** the checkbox "Distribute as soon as the images are ready" (a job queued with it
  before still distributes after its fill).
- **Comments on a round in review** (its admins and organizers can now write them on the review's
  images) are hidden from jurors, also from those of other rounds with the same file, until the
  round is distributed.
- **Votes:** a juror's large view always shows both ✓ and ✗; a click on the pressed one undoes
  the vote.

**Features**

- **One vote-button design, and undoing a vote in the large view** (the maintainer's notes of
  2026-11-16; developed 2026-11-16, `stack/175-vote-buttons-byrate-rank`): a juror's large view of a
  yes/no file always has both ✓ and ✗, in the tiles' order (it offered only the votes the file
  didn't have, one or two buttons, which was confusing); the file's vote is pressed
  (`aria-pressed`), and a click on it undoes the vote (rate 0, the tiles' semantics; then on to the
  next file, as after any vote, the maintainer's decision), titled "Selected: Clear the vote". Space
  and Delete still only give their votes (the maintainer's decision: undoing is a click). The
  gallery tiles' ✓ and ✗ and the large view's are one design (`main.css` `wlx-vote-btn`, through
  Bootstrap's button variables): the tiles' 3 px border in the vote's colour, filled with it when
  pointed at (the large view's change, now on the tiles too), pressed filled with a ring of the
  page's colour inside the border (not by colour alone), one radius (6 px) and a two-tone focus ring
  (a ring of the page's colour, then one of its text colour: 3:1 or more, Bootstrap's tint was about
  1.5:1), in both colour modes; a focused button keeps its state. The large views' row (a round's
  and a contest file's) is no longer a Bootstrap button group: each button has its own complete
  border, apart by a small gap (the outline vote buttons showed three coloured sides and a grey one,
  and covered their neighbours' borders pointed at). Juror guide.
- **By rate: the chips with the ranking** (the maintainer's note of 2026-11-16, same branch): a
  file's chips (all the jurors' tally or one juror's vote with its time, then the comments link)
  are under the thumbnail on the line of the rank and a rated round's stars, wrapping as whole
  items and never wider than the thumbnail (`galleryByRate` `rankChips`, `main.css`
  `.wlx-byrate-line`), no longer on the thumbnail's lower left. The same chip snippets, the
  comments chip a link of its own to the large view at `#comments` with the list kept, and a
  juror's own new vote time there at once (`tile-votes.js listVoteTimeAfter`, into the rank
  line). Gallery tiles unchanged. Juror and organizer guides.
- **Round review: one place filter, a jurors form, comments** (the maintainer's decisions of
  2026-11-16; developed 2026-11-16, `stack/176-review-places-jurors-form`):
  - **One place filter of one level** (`services.places.PlaceScheme`): a contest with a
    monument id template (a national one, whatever its free-text country) by **region**
    (monument ids, Ukraine's regions for now), else an international contest (of many countries,
    with a category) by **country** (its country categories), with one menu template and script and one way of
    counting a list's files by country (the countries cached per contest, read only when needed). The contest files page shows one menu (no longer both);
    a round's **review page** has the contest's menu, its counts those of the status tab shown, to
    check each country's (region's) number of files before distributing; a round's gallery,
    by-rate gallery and file list have the **country menu for its admins and organizers** (jurors
    get none, nor its JSON, and a juror's `country=` is ignored; they keep the region menu where
    the contest has regions).
  - **The jurors form** (`/admin/rounds/:id/jurors`, the round's admins): the jurors picker and
    the file distribution, with the number of images that passed the filters and per place, and
    Distribute (its refusals as before: no jurors, more per file than jurors, the jury changed
    since shown, an unfinished re-apply, the previous rounds' votes changed). The **new-round
    form has filters only**, ending with **Review images first** (the rounds list, then the
    review page) and **Choose jurors next** (the jurors form, which waits for the selection);
    nothing is distributed until the jurors form is submitted. "Distribute as soon as the images
    are ready" (D4) is gone (a job queued with it before still distributes after its fill). The
    review page's and the rounds list's **Distribute to jurors** open the jurors form. Editing a
    distributed round keeps its jurors as before.
  - **Comments on a round in review**: its admins and organizers write them in the review's
    large view; they are the round's ordinary comments, which jurors see once it is
    distributed, not before: a juror of another round with the same file doesn't get them until
    then in any comment read (the large view, the comments report, the comment badges, "Only
    with comments" and its counts, the latest-comment order), one rule for all of them
    (`RoundList.hiddenCommentRounds`, the contest's rounds not yet distributed). Admin guide.
- **"Wiki account taken" on Edit user** (A-2 of the existing accounts plan; developed 2026-11-16,
  `stack/172-wiki-taken-panel`): a save refused because other accounts have the wiki account typed
  now shows those accounts (up to 10 of a legacy group, none preselected) as the search shows them:
  the email masked outside the admin's contests, the contests of theirs each is in, and its votes,
  comments and rounds counted in those contests only (root: all). **Add that account to this
  contest instead** is the search's add by id, with the form's role and both mail boxes unticked;
  **Keep them separate** puts the wiki account back in the form (nothing recorded). Root accounts
  are never shown to admins. Each panel is logged by ids only. Admin guide.

**Fixes**

- **No criteria on a yes/no round's edit page** (reported on production; developed 2026-11-16,
  `stack/174-binary-round-no-criteria`, #355): the edit page of an existing yes/no or comments-only
  round showed the **Criteria** section (`round-form.js` hides it only while a new round's rating
  is chosen; an existing round's rating is fixed). The page now leaves out the criteria and the
  worst mark, with their hidden markers (`criteriaShown`, `worstMarkShown`), by the round's stored
  rating, also when the form comes back with an error, so a save keeps the round's stored criteria
  (a legacy one's, V83) and worst mark. A rated round's page is unchanged. A post that still sends
  `criteriaShown` with the selects empty (no longer the page's) is refused for such a round, as an
  added criterion was, instead of removing its legacy criteria, on a save and a re-apply. Admin guide.

## 26.11.10 (2026-11-16)

Deployed 2026-11-16, build `26.11.10-20980dc6d822` (release commit `20980dc6` on
`release/26.11.10`), schema V105, from `26.11.9`, PRs #350-#354. **No migration**: the schema
stays V105, so going back to `26.11.9` is the app alone. Deploy record: roadmap, "26.11.10 deploy
record". Two changes to know about: the monument
ids filter now reads `a, b` as both monuments (before, only `a`), so such a round's next
distribution or "Distribute new files" can add `b`'s files; and adding an account found by the
users page's search sends **no email unless the admin ticks** "Send an invitation email" or
"Tell its owner by email" (both unticked): its owner sees a notice in the tool instead.

**Features**

- **Jurors chosen at Distribute; re-apply filters in review** (P3-5 of the round-editing plan;
  developed 2026-11-16, `stack/169`, after the 26.11.9 RC report that a round couldn't be created
  for review without jurors): a round created for review needs no jurors; its review page's
  **Distribute to jurors** has the juror picker and the file distribution. While the round waits
  for review with no votes, its form's filters are editable and **Re-apply filters** selects its
  images again (files that no longer pass removed, new matches added, a top-N ranked again; files
  from a list and disqualified ones kept), and the review page shows how many were added and
  removed. The review page warns when previous rounds' votes changed since the images were
  selected. **Changed meaning:** the monument ids filter now trims spaces after its commas: a
  round whose filter reads `a, b` matched only `a` before (` b` matched nothing) and now matches
  both; such a round's next distribution or "Distribute new files" can add `b`'s files.

- **By rate: the comment chip opens the comments, the chips as on the gallery's tiles** (the
  maintainer's report on 26.11.9; developed 2026-11-16, `stack/170`): a file's comment chip on
  the by-rate gallery was a plain badge inside the thumbnail's link, so a click opened the file's
  large view at its top. It is now the gallery tiles' link chip (`snippets.commentChip` with the
  file's large view in the list, its filter and order kept, and `#comments`), in one row of chips
  at the thumbnail's lower left (`galleryByRate.scala.html` `thumbChips`, next to the thumbnail's
  link, not in it): first a yes/no round's tally of all the jurors (binaryTally's chip) or one
  juror's ✓ or ✗ (`snippets.voteChip`), with the vote's time in the vote-time orders (a rated
  round's time alone; the juror's own vote moves it at once, `tile-votes.js listVoteTimeAfter`
  through the gallery's `voteChip` and chip templates), then the comments. The tally, the mark and
  the time line under the thumbnail are gone; the rank and a rated round's stars stay there.
  The large view's **Next files** strip had the same plain badge (a click opened that file at its
  top): its comment chip is now the same link, in the same row (`large/thumbsBar`).
  Screen readers read a vote chip's time as it is shown ("Selected, 16 Nov, 09:10"), not the
  stored UTC value: the chip's words have the time as a `<time>` that `vote-time.js` formats, and
  its title is the same with the year (`snippets.voteChip`; the tiles' chips build the same).
- **Votes button and the tiles' ✓ and ✗ even** (the maintainer's notes of 2026-11-16, `stack/170`):
  the Votes button's "· 30 unrated" is muted as its count in every state of the button and in both
  colour modes (it took the button's colour, so pointed at it turned brighter than the count;
  `main.css .wlx-nav-suffix`). A gallery tile's ✓ and ✗ buttons are level: `unitegallery.js`
  placed the ✓ 8 px above the ✗. In a juror's large view the ✓ and ✗ are outline buttons like
  the gallery, previous and next buttons in their group (the same border, size, radius and focus
  ring), green and red, filled when pointed at, as the worst-mark button
  (`large/navigationButtons` buttonSelect; they were filled, without the others' border).

- **The staff pages as links in the top bar, and one style for counts** (the maintainer's
  feedback on 26.11.9 and decision of 2026-11-16; developed 2026-11-16, `stack/171`): on a page
  whose top bar has no filters, order or pager and that isn't a gallery (the rounds, the users,
  the statistics, the forms),
  the pages of the Pages menu (**Contests**, **Contest files**, **Users**, **Rounds**,
  **Statistics**: the same items, order and permissions) are links in the bar, the page's own
  highlighted and `aria-current`, from 1280 px; narrower, and on the galleries' grouped bar, the
  Pages menu as before. Counts after a name are drawn by one snippet (`snippets/count`, CSS
  `wlx-count`, also from `roundstat-counts.js`): a space and the number grouped in the page's
  language (`views.Count`, as the menus' counts and `toLocaleString`), without brackets, muted,
  as the Votes button's count, on the bar's buttons, the contest files tabs (the conflicts of
  interest's count now on every tab, for those who may see authors, by a count-only query,
  `CoiMembers.filesCount`), a round's review tabs, the
  conflicts of interest's people, the statistics page's comments, conflicts and authors links and
  the large view's monument **Gallery N** and the contest files page's **Monument: … N** and
  **Author: …** chips. After a name that is data and may end in digits (a monument id, an
  author's or commenter's account: the chips and the conflicts of interest's people, read as
  ", 3 files", and the comments list's "Comments by" items, read as ", 3 comments") the count is a
  pill of its own. Messages no
  longer embed the count (`*.link.count` keys removed, the chips' `({1})` dropped, in all four
  languages).

- **Find an existing account** on the users page (A-1 of the existing accounts plan, A1 decided
  by the maintainer 2026-11-16; developed 2026-11-16, `stack/168-account-search`): root and the
  contest's admins find every account by any part (3 characters or more) of its name or wiki
  account, or by its whole email address (a part of one only within their contests), at most 20,
  each with a masked address outside their contests, its wiki account, the contests of theirs it
  is in, and **Add to this contest** (by id, the email path's rule: it joins if it proved its
  email, otherwise an invitation, mailed from the tool's address only if asked). Its owner sees a
  notice in the tool at their next sign-in (a bar, and the list on their profile, dismissible);
  **Tell its owner by email** mails it too, only if ticked: nothing is mailed without the admin's
  choice. The text goes in a POST with the CSRF token, never a URL; each search is logged by ids
  only. Admin guide.

**Development**

- **Plans (docs only):** adding existing accounts (search, the "wiki account already taken"
  panel, adding by an unverified wiki name) and the account join plan brought up to date with
  the maintainer's decisions of 2026-11-16 (`docs/plans/2026-11-16-existing-accounts.md`,
  `docs/plans/2026-11-03-account-join.md`, #350).

## 26.11.9 (2026-11-16)

Deployed 2026-11-16 07:37 server time (06:37 UTC), build `26.11.9-6424d1e2885d` (release commit
`6424d1e2` on `release/26.11.9`), schema V105, from `26.11.8`, PRs #341-#349. One migration: V105
(`round.status`, with a backfill); V103 is retired. **Rolling back** to `26.11.8` or earlier:
first finish or cancel the rounds held in review ("Images ready: N" on the rounds list) and
let every "Adding juror …" job finish or cancel it, so that no round is left paused. Deploy record:
roadmap, "26.11.9 deploy record".

**Features**

- **Review a round's images before distribution** (P3 of the two-step plan, P3-1 to P3-3 of the
  round-editing plan; developed 2026-11-16, `stack/164`–`166`, #347-#349): a new round's images
  are selected first and wait on the round's review page (`/admin/rounds/:id/review`), hidden from its jurors;
  the round form's main button is **Create and review images**, and **Distribute as soon as the
  images are ready** (off) skips the review. The page shows the round's files as the contest files
  page does (tiles, region menu, large view) by status (in the round, deleted on Commons,
  disqualified), how they were added and its conflicts of interest; **Distribute to jurors**
  (admins, root) gives them out and opens the round. Organizers see the page read only. The rounds
  list shows **Images ready: N — Review / Distribute to jurors**. Admin and organizer guides.
- **Add a juror to a running round** (step 1 of the round-editing plan, the maintainer's decisions
  D1-D3 of 2026-11-16; developed 2026-11-16, `stack/162-add-juror`,
  `stack/163-add-juror-fair-share`, #345, #346): **Add juror…** under a round's statistics (admins
  and root) picks one of the contest's jurors outside the round (or a stopped juror of it, see
  below), shows what they will get and, once confirmed, runs it as a background job
  (`round_rebalance`) in a round that is running; votes already given stay as they are. In a round
  held for review the juror is only added, and gets their share when the round is distributed. In a
  round where every juror gets every file, the new juror gets all the round's files they have no
  vote on (not those deleted on Commons), unrated. In a round of N jurors per file, the round's
  jurors are listed ticked as donors (stopped ones unticked and marked): the ticked ones give
  **unrated files only**, so that the new juror and the donors end with about as many unrated files
  each ("9,120 unrated files over 5 jurors. Y gets about 1,520: from A 410, B 390, …"); rated votes,
  a rated round's ✗ and a criteria round's partial votes never move. A stopped juror's row gets
  **Start and give a fair share…**, the same page with that juror chosen. While the job runs the
  round **pauses itself**: its jurors keep it in their menus and see a short "paused" page instead
  of the gallery and the large view, and a vote is refused with a message to reload shortly
  (`409 round_paused`); staff still see the files. Start/Stop of a juror and creating or distributing a
  round that reads this one are refused while it runs. Cancel keeps the files a juror was already
  given (and the juror stays started); a juror the job started who got nothing is stopped again.
  Adding the same juror again tops up only what they lack. A round left paused with no job is
  flagged on the rounds list, and root's **Release** opens it again. The rounds list's job column
  shows the add and how many files it gave. Admin guide: "Adding a juror to a running round";
  organizer guide.
  Rolling back to a release without this: let each "Adding juror …" job finish or cancel it first
  (`docs/deployment.md`, "Before rolling back past "add a juror"").
- **The funnel after each comment's author is back, as quick access** (the maintainer's request
  of 2026-11-15, after `26.11.8` was built; developed 2026-11-15, `stack/159`, #342): in
  the organisers' comments list each comment's author with an account is again followed by a
  funnel, **Show only Name's comments**, the same `commenter=` link as the author's item under
  the **Comments** menu's **Comments by**. On the filtered author's comments the funnel is filled
  and current (`aria-current="true"`, as the menu's item), **Show all authors' comments**, and
  links the list without the filter, as **Everyone** does; other authors' funnels in the filtered
  view switch to them. The **Comments by** section stays the menu's uniform filter; the chip
  **Comments by Name ×** stays gone (the menu and the button show the filter). The author icons'
  focus outline is now solid in the link colour (Bootstrap's focus ring colour, a 25% tint, was
  too faint for a 2 px outline). Jurors get no funnels, and `commenter=` stays ignored in their
  own list. The keys `comments.report.author.filter` and `comments.report.commenter.remove` are
  back (en, fr, ru, uk), with new words. Organizer guide.
- **A link to a file's comments stays at the comments while the file loads** (the maintainer's
  report of 2026-11-15; developed 2026-11-15, `stack/160-large-image-size`, #343): the tiles', the
  large view's and the comments list's 💬 chips open the large view at its comments
  (`#comments`); while the file hadn't loaded, its image had no size, so the page scrolled to the
  comments, then the file arrived and pushed them out of view. The large view's image (in a round
  and on the contest files page) now has its box at its loaded height before the file arrives,
  from the stored width and height (`Global.largeImageBox`: `width` and `height` attributes at the
  `sizes` width, the height following the width in main.css, so that `mw-100 mh-100` fit it as
  they fit the file; no inline style); once loaded they go (`large-view.js`) and the view's rules
  size the file exactly as before.
  A file of unknown size, not on Commons, of other proportions than the stored ones, or that fails
  to load, and the browser's own scroll cut short (Bootstrap makes it smooth; WebKit stopped it
  partway when the image loaded): the page scrolls back to the comments, instantly, when the image
  loads or fails and when the page has loaded (once the browser's own scroll has settled, then
  only if their heading isn't in view or the image is still loading), unless the user has
  scrolled (a wheel, touch, key, click or pointer) since. Not on a reload or Back
  (the view is no-store, so Back loads it again): the browser restores where the user was. A
  video's box was already the column's (100% × 100%); the strip's thumbnails have fixed boxes.

**Development**

- **`round.status` (V105) and rows kept without votes** (round-editing plan P3-1, P3-2; developed
  2026-11-16): a sync no longer deletes a `round_image` row whose pair has no votes (invariant 8
  ended; the deletion paths mark a vote-less file `deleted` and restore it); V105 adds
  `round.status` / `status_changed_at` / `_by` with a CHECK and a backfill (rerunnable after a
  rollback), written in step with `active` and `distributing`; the distribution job has modes
  (`fill`, `distribute`; a job without one runs one pass). Rollback: finish or cancel rounds in
  review first; forward again: rerun V105's UPDATE and the repair job.
- **Plans (docs only):** screen-size analytics, recorded for later, not scheduled
  (`docs/plans/2026-11-15-screen-size-analytics.md`, #341); round editing: review before
  distribution, add a juror, reassign unrated files, with the maintainer's decisions of
  2026-11-16 (`docs/plans/2026-11-16-round-editing.md`, #344).

## 26.11.8 (2026-11-16)

Deployed 2026-11-16 00:02 server time (2026-11-15 23:02 UTC), build `26.11.8-3bfd11bb686c`
(release commit `3bfd11bb` on `release/26.11.8`), schema V104, from `26.11.7`, PRs #322-#340.
Two migrations: V102 (`monument.wikidata`) and V104 (an online index on `images (author)`); V103
is intentionally absent (reserved, then retired, see V104 and 26.11.9). Everything developed up
to 2026-11-13 before the entries below was deployed in `26.11.7`. **Jurors do not open the
contest files page in this release**: an earlier build of 26.11.8 (from #323, held, never
deployed) opened it to them; #330 takes it back, and a juror reaches their own round's files of a
monument from the monument's page instead. Deploy record: roadmap, "26.11.8 deploy record".

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
  **Jurors** of the contest could open the contest files page (the maintainer's decision D9;
  reversed before the release, see "A round's lists by monument" below, so not in 26.11.8):
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
- **Criteria rounds: sums labelled** (CodeRabbit triage of 2026-11-14, #326): a criteria round's
  CSV names its third column **Overall (sum of the criteria)** (other rounds keep **Overall**),
  and a juror's vote in a criteria round shows the number the stars show per criterion with the
  vote's sum beside it ("7.00 (Σ 14)"), as the by-rate gallery has it. Organizer guide: the CSV.
  Juror guide: Logout on a shared computer, sign-ins that keep the session.
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
  button has a people icon and reads **All jurors** (uk «Усе журі», shorter for the bar than the
  first «Усі члени журі»: the maintainer, 2026-11-15; ru «Все члены жюри», fr «Tous les jurés»)
  or the juror's name, and the Votes button an icon before its choice (for jurors too; since
  `stack/154-vote-icons` the choice's own, below), both icons hidden from screen readers, so
  **All jurors** and **All (5712)** no longer look alike. To keep the bar on one line, the juror's name in it is cut a little shorter
  (at 7.5rem instead of 9rem; whole in its title). The file list of all jurors is headed **All
  jurors** instead of "Juror: All" (in French it read «Juré : Toutes»). Juror and organizer guides.
- **Ukrainian «усі»/«всі»** (the maintainer, 2026-11-14; developed 2026-11-14,
  `stack/153-statistics-in-pages-menu`): a label that starts with the word says **Усі** (the Votes
  menu's «Всі» is now **Усі**, as **Усі регіони**), and inside a sentence the word follows the у/в
  alternation (**всі** after a vowel, **усі** after a consonant or a pause): «Кожний файл усім
  членам журі». Russian and French had no such mix and are unchanged.
- **One set of icons for yes/no votes** (the maintainer's decision of 2026-11-15; developed
  2026-11-15, `stack/154-vote-icons`): ✓ means selected and ✗ rejected everywhere. The voting
  buttons of the large view and of the gallery tiles (the tiles' green and red thumb pictures),
  the read-only vote of one juror's view (large view and tiles) and the Votes menu's items no
  longer show thumbs up and down; the results already used ✓ and ✗. The Votes button shows the
  icon of its choice instead of a fixed check box, as its items do: **Unrated** an empty circle,
  **Selected** ✓ (green), **Rejected** ✗ (red), **Rated** a star, and **All** the round's marks
  together (circle, ✓ and ✗, or circle and star in a rated round), since
  `stack/156-all-votes-icon` squeezed into one icon's box (the maintainer's idea of 2026-11-15:
  the row of three looked wide): a small ✓ and ✗ above a small circle, tinted as before (an
  upright triangle, the circle on top, read as a person beside the juror menu's people icon), a
  rated round's circle and star on a diagonal; CSS only, the menu items' icon box narrowed to it. The icons stay hidden from
  screen readers; the buttons' names, titles, keys and behaviour are unchanged. Rated rounds keep
  their stars, and their worst mark (also ✗) is never on a page with a yes/no round's ✗.
  **A voted tile has no coloured frame any more** (too strong, the maintainer): in a single
  juror's gallery of a yes/no round, the juror's own and the organisers' read-only view of it, the
  vote is a ✓ or ✗ badge in the tile's lower-left corner, where the gallery of all the jurors shows
  its tally (✓ 12 ✗ 3), styled as the tally; the read-only badge was at the top right, and the
  comments' badge no longer moves down for it. **The large view of all the jurors' votes** on a
  yes/no round shows the file's tally (**✓ 12 ✗ 3**, as its gallery tile) where a single juror's
  view shows that juror's vote; it comes from the list query the view already runs (no new
  query), and only organisers, admins and root see that view. Rated rounds already show the
  overall rate there. Juror and organizer guides.
  **Two chips side by side in a tile's lower-left corner** (the maintainer's notes of
  2026-11-15, same branch): the vote and its time are one chip (**✓ 14:32**, the time muted; the
  mark alone for a vote without a time; a rated round's time alone, above the stars), where they
  were two (the time at the top left); the comments' chip (**💬 3**) follows it, or the tally in
  the gallery of all the jurors (**✓ 12 ✗ 3**), instead of the top right, as in the by-rate
  gallery and the large view's **Next files**. The comments' chip is a link: it opens the file's
  large view at its comments (the tile's own list, plus `#comments`), takes the focus, and is 24
  px high at least; the vote chip still takes no clicks. A narrow tile leaves the time out first;
  the chips never cover the ✓ and ✗ buttons. Screen readers read one name per chip ("Selected, 12
  Nov, 14:32", "3 comments: open the comments"; new key `comments.open`).
  The ✓ and ✗ glyphs (the buttons', the badges', the menus') are drawn at one optical size: the
  check 12% larger, the cross 8% smaller with thicker strokes, their boxes and tap targets as
  before. **The large view links its comments**: under the buttons, after the vote, its time or
  the tally, a file with comments has the same **💬 3**, a link to the comments section under the
  file (`#comments`), with the count that section shows; screen readers and its title say "3
  comments: open the comments" (as the tiles' chip, `comments.open`, since
  `stack/156-all-votes-icon`; first a key of its own). A plain link: no
  script, and the vote keys keep working while it has the focus. Juror and organizer guides.
- **Counts without brackets in the navbar** (the maintainer's decision of 2026-11-15; developed
  2026-11-15, `stack/154-vote-icons`): the Votes, Comments and region menus' items show their
  count at the item's right edge, muted, the digits lined up in a column; a closed
  button shows it after its name, muted: **All 539 · 482 unrated**, **Comments 4**. Screen
  readers still read it ("All 539"). The messages that carried "({0})" for the Comments menu's
  items (`comments.only`, `comments.menu.recent`) are gone: the template draws the count after
  the plain text, in all four languages. Juror and organizer guides.
- **The comments list links each file's comments, and filters by author from its Comments menu**
  (the maintainer's notes and decisions of 2026-11-15; developed 2026-11-15,
  `stack/156-all-votes-icon`): in **Recent comments list** each row has, after its vote marks, the
  comment chip of the tiles and the large view (**💬 3**, the row's own comments: no extra query),
  a link to the file's large view at its comments (`#comments`), for jurors and the organisers
  alike; screen readers read "3 comments: open the comments". In the organisers' list the
  **Comments** menu has a second section, **Comments by** (uk **Коментарі від**), which replaces
  the funnel after each comment's author and the chip **Comments by Name ×** above the rows (once
  filtered the funnels were gone and the way back wasn't seen): **Everyone** with all the
  comments, then every author of the list's comments in the scope (jurors, pre-screeners and
  organizers, also one without files of their own), each with the number the list filtered by
  them counts, the most first, the current one marked; filtered, the button names them
  (**Список коментарів · Олена**, cut short if long). One menu, not a second speech bubble beside
  it. The same `commenter=` links, no new routes; the counts from one grouped query over the
  report's own scope (`SelectionQuery.commentAuthors`). Jurors get no such section, and
  `commenter=` stays ignored in their own list. New keys `comments.by`, `comments.by.all` (en, fr,
  ru, uk); the chip's and the funnel's keys (`comments.report.commenter`, `.commenter.remove`,
  `comments.report.author.filter`) are gone. Juror and organizer guides.
- **The organisers' results bar fits with Linux's fonts too** (the maintainer's decisions of
  2026-11-15; developed 2026-11-15, `stack/156-all-votes-icon`): the bar of one juror's votes
  (gallery, by rate, file list, comments list) keeps its pager on its line from 1280 pixels in
  Ukrainian with the fonts CI's Ubuntu has (DejaVu Sans, wider than Windows'), which it didn't (15
  to 40 px short). Its full labels from 1800 pixels (the comments list, whose Comments button can
  name an author, from 2100), compact below, where it was 1700 for all: the order menu a sort
  icon, the Pages menu its icon, the Votes button without the unrated, the region without
  "Region:", the Comments menu its bubble and count or the author's name; in the comments list the
  Votes button its icon alone and the names cut shorter. Everywhere the links a little closer
  (0.375rem each side, was 0.5), the juror's name cut at 6.5rem (was 7.5) and the region at 8rem
  (was 9). Jurors' bars are unchanged. Organizer guide.

**Development**

- **Width tests with Linux's fonts** (developed 2026-11-15, `stack/156-all-votes-icon`):
  `browser-test/linux-fonts.mjs` runs Playwright in the official image of its version
  (`mcr.microsoft.com/playwright:v<version>-noble`) with DejaVu Sans, CI's `system-ui`, against
  the fixture server on the host, so the navbar's width tests can be run with CI's fonts from
  Windows (docs/testing/practices.md §10.6). `navbar.spec.js` covers every view of the organisers'
  results bar (gallery, by rate, file list, comments list), compact at 1280 px and full at the
  width it is full from, each with at least 10 px free after the pager, and logs by how much a
  wrapping bar is short. Measured free px on Linux (chromium, firefox / webkit): at 1263 gallery
  and by rate 42 / 32, file list 77 / 68, comments list 41-42 / 31; full at 1783 gallery 100 / 90,
  by rate 53 / 43, file list 158 / 149, at 2083 the comments list 44 / 34.

- **One definition per chip** (the maintainer's request of 2026-11-15; developed 2026-11-15,
  `stack/156-all-votes-icon`): the vote (`snippets.voteChip`: mark and optional time), all the
  jurors' tally (`binaryTally`, with `chip = true`) and the comments (`snippets.commentChip`,
  a link with an `href`) are each one Twirl snippet; the large view, the comments list and the
  by-rate and strip thumbnails render them (`snippets.commentBadge` is gone), and the gallery has
  them once in `<template>`s (`snippets.chipTemplates`) that `tile-votes.js` clones, setting only
  text and attributes, instead of building its own markup. One class set (`badge wlx-chip` and
  `wlx-chip-vote`, `-tally`, `-comments`; `wlx-chip-time`) and one label key (`comments.open`;
  `comments.goto` is gone, so the large view's chip now reads "3 comments: open the comments") in
  place of the tiles' `wlx-tile-chip`/`-vote-mark`/`-tally`/`-comments`/`-time`, the large
  view's `wlx-comments-link` and the thumbnails' `wlx-comment-badge`. Placement, the tiles'
  click handling, their fitting and the vote keys are unchanged. `views.ChipFixturesSpec`
  renders the templates and the server's chips into `javascript-test/fixtures/chips.html`
  (`FIXTURES_UPDATE=true` regenerates it), and `chips.test.js` checks that each chip a tile
  builds has the server's elements, classes, attributes and accessible name.

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
deployed, replace its "date TBD at deploy" with the deploy date, date it from its first new
`flyway_schema_history` row (or from the deploy record if it has no migration), and add the
release name.
