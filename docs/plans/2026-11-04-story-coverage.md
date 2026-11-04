# Story coverage and the Allure overview (plan)

**State:** steps 1–4 built on `ukwikidev/story-coverage` (2026-11-04, [results](#8-results));
step 5 (the first gaps) not started. User decision, 2026-11-04: user-story coverage for the
tests, with an Allure overview. **Sources:** `browser-test/` at `76d88f1a` (Playwright Test 1.64.1, 53
tests per engine), [testing](../testing/practices.md) §9–§11,
[juror guide](../user/juror-guide.md), [dependencies](../dependencies/practices.md) §1, §2, §6,
[workflow](../workflow/practices.md) §8, `conf/routes`, `build.sbt` (specs2: 209 spec files;
munit 0.7.29: `ContestSpec`, `ImagesSqlSpec`), `.github/workflows/ci.yml` and `browser.yml`;
the user's feature review `docs/comparison/01-wlxjury.md` (2026-11-02, uncommitted; its
`WJ-F*` ids are cited below) and `03-comparison.md`, each claim checked against `76d88f1a`; the
npm registry and GitHub releases for versions (read 2026-11-04).

## Goal

One list of what each role does in the tool, a story ID per item, and a matrix that says which
tests show each story. *Why:* coverage (§9) says which lines ran, not which flows a juror or an
organizer can rely on; the matrix shows the gaps by flow, and the guide's sections already name
most juror flows.

## 1. The story catalogue

**1.1 Format and place: one Markdown file, `docs/testing/stories.md`.** One table per role,
columns `ID | Story | Guide section | Notes`. The script (§3) parses the table rows. *Why not YAML
plus generated Markdown:* two files that must agree need a guard of their own; a table is
readable on GitHub, reviewed with the code, and easy to parse. Coverage is **not** written into
the catalogue: it is generated (§3), so it can't go stale.

**1.2 IDs.** `<role>-<two digits>`: `J-` juror, `O-` organizer, `A-` admin, `R-` root, `S-`
system or security. An ID is never reused or renumbered; a story that no longer applies stays
with *(retired)* in Notes, and tests may not cite it. Gaps in the numbering are fine.

**1.3 A story is a sentence about a user-visible outcome** ("A juror rates a file by a digit
key"), not about code. One guide section may hold several stories; a story links the section
that describes it (`../user/juror-guide.md#voting-in-the-large-view`), or none.

**1.4 The first list** (68 stories: from the guide, `conf/routes`, the controllers and the
review, §1.5). *Browser* is today's state, read from the specs:
**yes** (a test shows the outcome), **part** (some of it), **tour** (only opened by
`pages.spec.js`: status 200, one `h1`, axe), **no**. *Scala* names the specs to tag (§2.3);
**?** means a spec to find in step 3.

| ID | Story | Guide section | Browser | Scala |
|---|---|---|---|---|
| J-01 | A juror accepts an invitation and sets a password | accepting-your-invitation | no | `AccountInvitationSpec` |
| J-02 | A juror signs in with login and password | signing-in-with-your-password | yes (sign-in, auth.setup) | `LoginControllerSpec` |
| J-03 | A wrong password keeps the login page, with its error | signing-in-with-your-password | yes (sign-in) | `LoginControllerSpec` |
| J-04 | A juror signs in with Wikimedia or Google | signing-in-with-wikimedia-or-google | no | `OAuthControllerSpec` |
| J-05 | A juror whose wiki name has several accounts chooses one | when-one-wiki-name-has-several-jury-accounts | no | `OAuthChooserSpec` |
| J-06 | A juror signs out from the user menu | signing-out | yes (sign-in) | `SignOutAndSwitchSpec` |
| J-07 | A signed-out juror opening a page signs in and comes back to it | when-you-are-signed-out | part (the `returnTo` redirect only) | `SecuredSpec` |
| J-08 | After signing in, the first open round's gallery opens, or "No round configured yet" | what-you-see-after-signing-in | no | `LoginControllerSpec` |
| J-09 | A juror in several open rounds switches round from the round menu | what-you-see-after-signing-in | no | `RoundListSpec` |
| J-10 | A round being prepared, stopped or paused for the juror is not shown | rounds-you-dont-see | no | `RoundListDbSpec` |
| J-11 | A juror in several contests switches contest from the contest menu, or by a link | taking-part-in-several-contests | no | `MultiContestUserSpec` |
| J-12 | The gallery's tabs (all, unrated, selected, rejected) show their files and counts | tabs-all-unrated-selected-rejected | tour | `JurorCountsSpec` |
| J-13 | A juror sorts the gallery | sorting-your-files | no | `GalleryOrderSpec` |
| J-14 | A juror filters the gallery by region | regions | no | `GalleryRegionsSpec`, `RegionMenuSpec` |
| J-15 | A juror pages through the gallery | the-pager | no | `PagerSpec` |
| J-16 | A juror selects or rejects a file in a yes/no round's gallery | voting-in-a-yesno-rounds-gallery | yes (voting) | `OwnVotesSpec` |
| J-17 | A juror rates a file in a rated round's gallery | rating-in-a-rated-rounds-gallery | yes (voting) | `GalleryTileStarsViewSpec` |
| J-18 | A file deleted, missing or renamed on Commons is shown as such, or under its new name | deleted-or-missing-files | no | `ImageProxyFileCheckSpec` |
| J-19 | A juror opens a file in the large view from the gallery and Esc returns to its page | opening-a-file-in-the-large-view | no (opened by URL) | `ListPositionSpec` |
| J-20 | A juror votes in the large view by its buttons or stars | voting-in-the-large-view | yes (voting) | `LargeViewControllerSpec` |
| J-21 | A juror votes in the large view by key (Space, Delete, digits) | keyboard-shortcuts-in-the-large-view | yes (voting) | `LargeViewControllerSpec` |
| J-22 | A juror moves between files by key (arrows, Page Up/Down, Home, End) | keyboard-shortcuts-in-the-large-view | no | `LargeViewUrlsSpec` |
| J-23 | A juror rates a file by criteria | rating-by-criteria | no | `RoundCriteriaSpec` |
| J-24 | Back after a vote shows the stored vote | going-back-after-a-vote | yes (voting) | – |
| J-25 | A juror comments on a file; others in the contest see it | comments-on-a-file | no | `CommentAuthorizationSpec` |
| J-26 | A comments-only round takes comments, no votes | comments-only-rounds | no | – |
| J-27 | A juror sees only their own votes; another juror's are refused | your-own-votes-only | yes (access) | `OwnVotesSpec` |
| J-28 | In a round that allows it, a juror gets the organizers' view | your-own-votes-only | no | `OwnVotesSpec` |
| J-29 | A disqualified file leaves the juror's list; requalified, it returns with the vote | disqualified-files | no | `CoiAuthorizationSpec` |
| J-30 | A juror picks light, dark or system theme, shown from the first frame | light-dark-or-system-theme | yes (theme) | `ThemeSwitchSpec`, `ThemeViewSpec` |
| J-31 | A juror changes the interface language | interface-language | no | – |
| J-32 | A juror opens their profile and links a Wikimedia account | your-profile | tour | `OwnAccountSpec` |
| J-33 | The large view's side sections stay as the juror left them | – | yes (sections; first frame `fixme`, U18) | `LargeViewSideSpec` |
| J-34 | An expired page or token says to reload | this-page-has-expired-or-you-were-signed-out | part (the refusal, not the page) | `LoginCsrfSpec` |
| J-35 | "Access denied" shows a reference and a way to sign in as another account | access-denied-and-its-reference | yes (access) | `SecuredSpec` |
| J-36 | A juror changes an earlier vote from the Selected, Rejected or Rated tab | tabs-all-unrated-selected-rejected | part (changes a vote, from the first tab) | `OwnVotesSpec` |
| J-37 | The large view shows the file's resolution, monument and Commons link; a video plays | opening-a-file-in-the-large-view | tour | `LargeImageSpec` |
| O-01 | An organizer opens a round's statistics, a row per juror | – | yes (organizer) | `RoundControllerSpec` |
| O-02 | An organizer lists a round's results of all jurors (rank, average) as gallery, by rate or file list | – | yes (organizer; by rate only) | `GalleryByRateViewSpec`, `GroupedListParitySpec` |
| O-03 | An organizer views one juror's votes from the juror menu | – | no | `OwnVotesSpec`, `AllJurorsListSpec` |
| O-04 | An organizer reads a criteria round's results | – | no | `CriteriaResultsPageSpec` |
| O-05 | An organizer exports a round's results as CSV, or the thumbnail URLs | – | no | `CsvSpec`, `ThumbnailUrlsExportSpec` |
| A-01 | An admin creates a round; it stays hidden until its files are distributed | – | part (the form's fields only) | `RoundControllerSpec`, `RoundEditPageSpec` |
| A-02 | An admin edits a round; Cancel saves nothing | – | part (cancel only) | `RoundEditPageSpec` |
| A-03 | An admin starts, stops and sets the current round | – | no | `StartStopViewSpec` |
| A-04 | An admin adds jurors to a round and pauses one | – | no | `RoundControllerSpec` |
| A-05 | An admin distributes a contest's new files to a round | – | no | `AppendImagesSpec` |
| A-06 | An admin follows a job's progress, cancels or retries it | – | no | `JobControllerSpec` |
| A-07 | An admin adds, mass-imports or invites users, reinvites, cancels an invitation, writes to them on Commons | – | tour (users) | `AccountInvitationSpec`, `UserControllerSpec` |
| A-08 | An admin changes a user's role or wiki account, resets a password, ends sessions | – | no | `RoleAssignmentSpec`, `SessionsSpec` |
| A-09 | An admin imports files from a Commons category, template or page, or a pasted list, and caches them | – | tour (contest images) | `ImportFilesPageSpec`, `ImagesFromListSpec` |
| A-10 | An admin reviews conflicts of interest, disqualifies and requalifies in a stopped round | – | no | `RoundConflictsAuthorizationSpec` |
| A-11 | An admin checks a round's files for deleted or missing ones | – | no | `RoundImageCheckAuthorizationSpec` |
| A-12 | An admin browses the contest's files, its "Deleted on Commons" tab and a file's page | – | tour | `ContestFilesPageSpec` |
| A-13 | An admin updates the files' monument ids and the monument lists | – | no | ? |
| A-14 | An admin adds a pasted list of files to an existing round, whatever its filters | – | no | `AppendImagesSpec` |
| A-15 | A double submit of the new round form makes one round | – | no | ? |
| A-16 | An admin makes the next round from earlier results: selected by at least, minimum average, top N, several rounds merged | – | no | `TopBinaryOrderChoiceSpec`, `MinJurorsChoiceSpec`, `RejectedByChoiceSpec` |
| A-17 | An admin edits the contest's greeting, previewed per member; a new account gets it by mail | – | no | ? |
| R-01 | Root opens the campaigns page, creates a contest or imports contests from Commons | – | part (opens it) | `ContestTemplateSpec` |
| R-02 | First-run setup creates the root account | – | no | `SetupSpec` |
| R-03 | Root lists all users across contests and makes an account root | – | no | `RoleAssignmentSpec` |
| S-01 | A juror is refused organizer and admin pages; an organizer admin pages (403 with reference) | access-denied-and-its-reference | yes (access) | `RouteAuthSpec`, `MutationAuthorizationSpec` |
| S-02 | A state-changing POST without the CSRF token is refused | – | yes (sign-in; the vote and theme POSTs send it) | `LoginCsrfSpec` |
| S-03 | Every page runs under the production CSP, without violations or other hosts | – | yes (guards, every test) | `ContentSecurityPolicySpec` |
| S-04 | Every page has one `h1` and no untriaged serious accessibility finding | – | yes (pages, Chromium) | `HeadingsViewSpec` |
| S-05 | A missing resource is the 404 page; a server error the error page with a reference | other-errors | no | `MissingResourceSpec`, `ErrorHandlerSpec` |
| S-06 | A vote in a round not open to jurors (stopped, frozen, distributing) is refused and the stored vote stays | rounds-you-dont-see | no | ? |

Not in the first list: the API tokens pages, readiness, the monuments pages. They are added
when a guide or a test needs them.

**1.5 What came from the review** (`01-wlxjury.md`, used for features and flows, not for its
F1–F14 grouping, which follows the Montage comparison). *New stories:* J-36 (WJ-F7.10), J-37
(F7.4), A-13 (F4.4), A-14 (F6.4), A-15 (F6.3), A-16 (F8.2), A-17 (F10.2), R-03 (F11.3), S-06
(F6.5, F7.8). *Widened by it:* J-18 (F4.6, F4.7), O-02 (F9.2), O-05 (F9.3, F9.4), A-07 (F11.2,
F10.4), A-09 (F4.1, F4.2), A-12 (F11.6), R-01 (F3.3). Montage-only features in
`03-comparison.md` are gaps, not stories: a story is added when a feature is built.

**1.6 Review claims not found in the code, or outdated** (checked against `76d88f1a`):

- *Self sign-up* (F1.2, `/signup`): no route; first-run `/setup` (R-02) and invitations (J-01)
  replaced it.
- *Sign-out by GET* (F1.5): now a confirmation page and a POST with its token, plus "sign out
  everywhere" (`POST /logout/everywhere`).
- *"No OAuth"* (F1.7), *"no disqualification"* (F5.7), *criteria as legacy only* (F5.2): out of
  date; OAuth and the account chooser, P1b disqualification and C2/C3 criteria are in the code
  (J-04, J-05, J-29, A-10, J-23, O-04).
- *Commons category autocomplete*, `autocomplete.js` (F4.10): not in the code; the import page
  and the round form have a category suggestion instead (`CategorySuggestViewSpec`), in A-09 and
  A-01.
- *Merge rounds, remove an image from a round* (F6.6, dead code then): gone.
- *The `selection_deleted` table* (F4.6): not found by that name; deleted files are
  `images.deleted_at` with the vote handling in `ImageJdbc`/`VoteJdbc`. J-18's test follows the
  code, not the review.
- *The round-1327 star exception* (F5.2): not found.

## 2. Tagging tests

**2.1 Playwright: the native `tag`, `@J-21`.** It needs no dependency, works with
`--grep @J-21`, shows in the HTML report, and `allure-playwright` turns each tag into an Allure
`tag` label (it strips the `@`). A `describe` tag covers its tests. A test may cite several IDs.

```js
test.describe('a juror votes', { tag: '@J-20' }, () => {
  test('in the binary round\'s large view by key, then Back shows the vote',
    { tag: ['@J-21', '@J-24'] }, async ({ routelessAs, fixture }) => { /* … */ });
});
```

The Allure alternative sets the labels at run time, in each test:

```js
import * as allure from 'allure-js-commons';
await allure.feature('Juror');
await allure.story('J-21 A juror votes in the large view by key');
```

**Recommended: the tag only, and an auto fixture in `lib/fixtures.js` derives the Allure labels
from it,** so a test names its story once. The story's text comes from `stories.md`
(`lib/stories.js`, a few lines that parse the table), so the report shows the catalogue's
wording, and a tag that is not in the catalogue fails the test there too.

```js
storyLabels: [async ({}, use, testInfo) => {
  for (const id of storyIds(testInfo.tags)) {          // '@J-21' -> 'J-21', known ids only
    await allure.epic(ROLE[id[0]]);                    // 'Juror'
    await allure.feature(STORY[id].section ?? ROLE[id[0]]);
    await allure.story(`${id} ${STORY[id].text}`);
  }
  await use();
}, { auto: true }],
```

**2.2 Scala: a convention, no adapter.** No maintained Allure adapter exists for specs2 or munit
(`allure-specs2` stopped at Scala 2.10; allure-java publishes only `allure-scalatest`, and this
build's specs use specs2 and munit). The ID goes into the example's or block's name, in
brackets; the script reads it from the source:

```scala
// specs2: a block covers its examples
"a juror's own votes [J-27, J-16]" should {
  "be the only votes in their gallery" in { /* … */ }
}
// munit
test("[J-02] a juror signs in with login and password") { /* … */ }
```

*Why the name:* it reaches sbt's JUnit XML (`target/test-reports/`) unchanged, so a later Allure
step can read it (§4.6), and a reader of a failure sees the story.

**2.3 Which Scala specs get tags: the story-level ones only.** A spec is story-level when it
drives a request through the router or a Play app for a flow in the catalogue: the specs in
`test/controllers/` and the request-level ones in `test/views/` and `test/security/` named in
§1.4 (about 40 files, mostly one tag per `should` block, so roughly 60–100 edits). Not tagged:
`test/db/`, `test/services/`, `test/org/`, `test/stress/`, `test/gatling/`, the source-scan guards
(`RawSqlSpec`, `RawHtmlSinksSpec`, `LogHygieneSpec` …), view-helper and parity specs, and Jest.
*Why:* about 2,000 examples exist; a unit test shows a rule, not a story, and tagging it would
make the matrix noisy and the tags churn with every refactor.

## 3. The coverage matrix script

**3.1 `scripts/story-coverage.py`,** Python 3 standard library only (as `coverage-summary.py`).
It reads `docs/testing/stories.md`, the tags in `browser-test/tests/*.js` (`'@X-NN'` strings),
the bracketed IDs in `test/**/*.scala`, and the headings of `docs/user/juror-guide.md`; it writes
the matrix (story → browser tests, Scala specs, guide section) to stdout, to
`target/story-coverage.md`, and to `$GITHUB_STEP_SUMMARY` when set.

**3.2 It fails** (exit 1) on: an ID in a test that the catalogue lacks or retired; a malformed
ID; a duplicate ID in the catalogue; a guide anchor that has no heading. *Why:* a typo would
otherwise count as coverage of nothing.

**3.3 It reports, without failing:** the stories with no test (by role), those covered only by
Scala or only by the browser, a test inside `test.fixme`/`test.skip` (counted as not covering),
and guide sections with no story (drift, §7.1).

**3.4 Not vacuous** (testing §4.2): it fails when it finds no tag in the browser tests or none in
the Scala specs, and `--self-test` runs it on a small fixture under `scripts/testdata/` with one
known, one unknown and one retired ID.

**3.5 Where it runs:** a step in `ci.yml` before the compile (seconds, no build), so a bad ID
fails the PR early; locally `python scripts/story-coverage.py`. It is static: what ran and
passed is the Allure report's job (§4).

## 4. Allure

**4.1 The two reports** (read 2026-11-04):

| | Allure Report 2 | Allure Report 3 |
|---|---|---|
| Form | Java CLI, a zip from GitHub or Maven Central | npm `allure` (TypeScript) |
| Latest | 2.46.1 (2026-09-03) | 3.22.1 (2026-10-23); 3.21.0 (2026-10-16) |
| Licence | Apache-2.0 | Apache-2.0 |
| Stable | yes, maintained | 3.0.0 on 2025-12-22 after 28 betas; frequent releases (10 since 2026-09-07) |
| History | copy the last report's `history/` into the results | `historyPath`: a JSONL file, `historyLimit`, `appendHistory` |
| One file | no (needs a server or `allure open`) | `plugins.awesome.options.singleFile: true` |

`allure-playwright` 3.14.0 (2026-10-19, Apache-2.0, peer `@playwright/test >=1.62.0`; it brings
`allure-js-commons`, Apache-2.0) writes `allure-results/`, records the project as a "Project"
parameter, and maps tags to labels.

**4.2 Recommended: Allure 3, `allure` 3.21.0 and `allure-playwright` 3.14.0,** exact versions in
`browser-test/package.json` and its lockfile. *Why:* `npm ci` pins it with the rest of the test
tools, no downloaded Java tool to checksum (dependencies §4.1), history in a file, one-file
reports. Two-week rule (§1.1): on 2026-11-04 the newest eligible `allure` is 3.21.0; 3.22.1 is
eligible from 2026-11-06 and may be taken if the PR merges after that. `allurerc.mjs`, not
`.ts` (TypeScript config needs Node 22.18; CI runs Node 20). The classic (Allure 2-style) plugin
is experimental: use the default Awesome report.

**4.3 Its surface:** `allure` 3.21.0 depends on about 30 `@allurereport/*` packages (Jira,
Slack, Xray plugins among them, unused). A test tool only, never served or packaged; named in the
PR's Security section; one update PR per bump (§5.1), given the release rate. **No CDN at
runtime:** the one-file report must open with the network off; step 4 checks that it loads no
font or script from another host, and the PR records it.

**4.4 Built in CI, in `browser.yml`.** Each engine's job uploads `allure-results/` always
(`allure-results-<engine>`); a `report` job (`needs: browser`, `if: always()`) downloads them
with `merge-multiple: true` into one directory, runs `npx allure generate`, and uploads
`allure-report` (14 days, as the other artifacts). *Merging:* result files have unique names,
and the "Project" parameter keeps an engine's run of a test apart from another's, so the three
jobs make one report, a test per engine. Each new action by SHA (dependencies §2.2).

**4.5 Published as an artifact only; GitHub Pages is a later decision.** Pages would make the
test names, steps, a11y findings and failure screenshots public. The repo and the fixture's data
are public (Commons images are placeholders), so nothing secret leaks, but the server log and
traces must never go there, and Pages needs `pages: write` and a deploy from `master` only (PRs
from forks can't deploy). The artifact is enough to read a run; Pages buys a stable link and
long history.

**4.6 History without a server: yes.** `historyPath: 'history/allure-history.jsonl'`, kept
between runs with `actions/cache` (save under a run-id key, restore by prefix), appended only on
pushes to `master` and `ukwikidev/followups`; PR runs read it with `appendHistory: false`, so a
PR's trend is against its base. A cache evicted after seven days unused loses the trend, not the
results.

**4.7 Scala in the same report: deferred.** Allure 3 reads JUnit XML (its `junitxml` reader), so
`ci.yml`'s `target/test-reports/` could join, with the `[J-27]` in the names but no story
labels; it runs in another workflow, so merging needs a `workflow_run` job. The matrix (§3)
already joins the two by ID.

**4.8 Size.** Results are a few KB per test (about 160 per run: 53 tests × 3 engines); the
attachments are the a11y JSON (17 pages, Chromium) and, on failure only, screenshots and
traces (a few MB each). A green run's report is expected at a few MB; step 4 records the measured
size, and traces stay in `browser-failures-*` if they make the report too large.

## 5. Docs and guard changes (in the PRs that build them, workflow §8.1)

- **Testing §9:** new 9.4 "Story coverage": the catalogue, the tag convention (§2), which specs
  get tags (§2.3), the script and what fails versus what is reported.
- **Testing §10:** 10.1's table notes each spec's story IDs; 10.6 "Adding a test" requires a
  story tag on a test of a catalogue flow; 10.7 adds the Allure report job; new 10.9 "The
  Allure report" (open the one-file report, history, what is in it).
- **Testing §11:** rows "9.4 story IDs known, guide anchors exist | `story-coverage.py` in
  `ci.yml` | enforced"; "9.4 uncovered stories | the script's summary | a report"; "10.9 Allure
  report per run | `browser.yml` report job | enforced as a report". Review checklist: "a new
  user-visible flow has a story ID and a tagged test, or is listed as uncovered".
- **Dependencies §2.1 and §6.1:** `allure` 3.21.0 and `allure-playwright` 3.14.0 with their
  release dates and Apache-2.0; the report opens offline (no CDN, §4.3).
- **docs README §1 and §3:** an index row for `testing/stories.md`; a §3 row "adds or changes a
  user-visible flow | the story catalogue: a story ID, its guide section, a tagged test or its
  uncovered entry".

## 6. Steps

1. **Catalogue and script** (about half a day): `stories.md` from §1.4, `story-coverage.py`
   with `--self-test`, the `ci.yml` step, testing §9.4 and §11, the README rows.
2. **Browser tags** (an hour): tags on the 53 tests from §1.4; no new tests. The matrix then
   matches §1.4's *Browser* column; any difference is a reading error in this plan.
3. **Scala tags** (about a day): the specs of §2.3, one tag per block where possible; names
   change once, so the CI history of those examples restarts.
4. **Allure for the browser tests** (about a day): the two packages, `allurerc.mjs`, the
   reporter beside `list`, `html`, `json`; `lib/stories.js` and the `storyLabels` fixture; the
   `report` job, history cache; record size and the offline check; testing §10, dependencies.
5. **First gaps** (separate batches, by priority): the juror stories with no browser test that
   only a browser shows (J-19, J-22, J-12–J-15, J-25), then A-01 and A-03 end to end.

**Deferred:** Scala results in the Allure report (§4.7); GitHub Pages (§4.5, the user's
decision); the organizer and admin guides after P3, which then become the `O-`/`A-` stories'
guide sections and add stories; Jest in the matrix; a minimum story coverage (a report until the
catalogue has settled); mobile stories with mobile support (testing §10.8).

## 7. Risks

**7.1 Catalogue drift.** A flow changes and the catalogue doesn't. *Mitigation:* the README §3
row and the checklist line; the script reports guide sections with no story and fails on a
dead anchor; the juror guide's own update rule (README §3) then reaches the catalogue.

**7.2 Tagging churn across about 2,000 Scala examples.** *Mitigation:* tags on story-level specs
only (§2.3), at block level; a refactor of a unit spec never touches a tag. A renamed example
keeps its bracket; the script lists stories that lose their last test.

**7.3 False coverage.** A tag says a test touches a story, not that it asserts the outcome.
*Mitigation:* reviewers check a new tag against the story's sentence; *part* stays visible in
Notes until a test shows the whole story.

**7.4 Allure 3's pace and surface.** Frequent releases and many transitive packages.
*Mitigation:* exact pins, the two-week rule, one update PR at a time; the report is advisory, so
a broken Allure release can be held back without blocking the tests (the report job's failure
does not fail the engines' jobs).

**7.5 Public reports.** Only if Pages is chosen (§4.5): no server log, trace or credential in
the published files.

## 8. Results

Built 2026-11-04 on `ukwikidev/story-coverage` (based on `stack/70-browser-tests`, `474e6cac`).

**The matrix** (`python scripts/story-coverage.py`): 68 stories, **46 covered, 15 partly
covered** (*Part:* in Notes), **7 uncovered**: J-09, J-10, J-22, J-29, J-31, J-37, A-16. By layer:
20 stories have browser and Scala tests, 4 browser only (J-33, R-01, S-03, S-04), 37 Scala only. The
guide sections no story links: the three "Kinds of rounds" ones and "Conflicts of interest".

**The two unverified points:**

- *`allure.story()` from the auto fixture:* works. A probe with a `{ auto: true }` fixture calling
  `allure.epic/feature/story` before `use()` gave the result JSON those labels beside the `tag`
  labels; in the Chromium run every tagged test has them (e.g. `Juror` /
  `Light, dark or system theme` / `J-30 …`) and the "Project" parameter `chromium`. An unknown tag
  (`@J-98`) failed the probe's test from the fixture. One side effect: the labels travel as
  attachments ("Allure Metadata"), so Playwright's HTML report lists them per test.
- *Offline:* **not as shipped.** Allure 3.21.0's Awesome report hard-codes Google Analytics
  (`analyticsEnable: true` in `@allurereport/plugin-awesome`'s `generators.js`; also in the
  classic, allure2 and dashboard plugins): opened with every non-file request refused, the
  report asked `www.googletagmanager.com` twice. `browser-test/allure-offline.mjs` removes that
  block after `generate` and fails if markup or CSS still loads from another host; after it, no
  request left the file, no console error, and the report drew its tree. Recheck on each
  `allure` bump (a later release may add a switch).

**Sizes:** a Chromium run (53 passed, 2 `fixme` skipped): 72 result files, 0.7 MB; the one-file
report 3.9 MB; a history entry 70 KB (`historyLimit: 30`, about 2 MB). Three engines' report:
not measured locally (expected ~5 MB: the a11y attachments are Chromium's only).

**Checks:** the script's `--self-test` and the tree pass; `npx playwright test --list`: 165
tests (55 per engine); `Test/compile` and `testOnly controllers.SignOutAndSwitchSpec
controllers.OAuthChooserSpec controllers.SetupSpec`: 19 passed, the bracketed IDs in the JUnit
XML names; Chromium with the Allure reporter: 53 passed, 2 skipped (the `fixme`s).

**Where the plan and the code differ (the code followed):**

- *§1.4's Scala column vs §2.3:* the column names view-render, parity, form-mapping and unit
  specs that §2.3 excludes; only the request-level ones were tagged (39 files).
  `RoundControllerSpec` is a form-mapping unit test (not O-01, A-01, A-04: those are
  `ContestFilesPageSpec`/`RoundEditPageSpec`, `RoundEditPageSpec`/`RoundCriteriaSpec`, and
  `MutationAuthorizationSpec`'s `setRoundUser` block). `AppendImagesSpec` is the import service
  (A-09, A-13), not A-05/A-14; A-14 is `RoundEditPageSpec`'s "add the pasted files" example and
  A-05 its new-files count. `ContentSecurityPolicySpec` is a guard of the policy (S-03 stays with
  the browser guards). Left untagged, so their stories show as Scala-uncovered or *Part:*:
  `RoundListSpec`, `RoundListDbSpec`, `JurorCountsSpec`, `ListPositionSpec`, `PagerSpec`,
  `GalleryTileStarsViewSpec`, `GalleryByRateViewSpec`, `LargeViewUrlsSpec`, `LargeViewSideSpec`,
  `ThemeViewSpec`, `StartStopViewSpec`, `HeadingsViewSpec`, `GroupedListParitySpec`, `CsvSpec`,
  the three choice specs, `ImagesFromListSpec`, `UserControllerSpec`.
- *The "?" stories:* A-13 `ContestTemplateSpec`, `MonumentControllerSpec`; A-15
  `RoundEditPageSpec` ("create one round when saved twice"); A-17 `AdminPagesCurrentContestSpec`
  (saving the greeting); S-06 the stopped-round and stopped-juror examples of
  `MutationAuthorizationSpec` and `LargeImageSpec`.
- *The tour:* `pages.spec.js` is tagged S-04 only; a page it merely opens is noted in the story's
  Notes ("browser: the page tour opens it only"), not tagged, so a 200 doesn't count as a story.
- *The fixme test* (`sections.spec.js`, U18) is tagged J-33 and listed as not counting.
- *Playwright:* `test.describe(details, fn)` without a title takes the object as the title, so
  the anonymous describe in `sections.spec.js` got its tags on its tests instead.
- *Testing doc numbering:* §5 named 10.6 "Adding a test" and 10.7 the CI section; in the code
  they are 10.7 and 10.8 (10.9 is "Times"), so the Allure section is **10.10**.
- *The report job* runs `if: !cancelled()`, not `always()`, so a cancelled PR run builds no
  report; the history is under `target/browser-test/allure-history/`, not `history/`.
- *Dependencies:* the transitive packages (~160) were locked with `npm install --before
  2026-10-21`, so none is newer than the two-week rule; their licenses are permissive
  (BlueOak-1.0.0, PSF-2.0, `dompurify` MPL-2.0 or Apache-2.0 among MIT, Apache, ISC, BSD).

**Deferred:** as §6, plus a three-engine local report size, and step 5's tests.
