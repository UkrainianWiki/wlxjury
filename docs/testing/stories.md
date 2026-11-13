# User stories

What each role does in the jury tool, one story per row, each with an ID that tests cite
([testing](practices.md) §9.4). Which tests show a story is **not** written here: it is generated
by `python scripts/story-coverage.py` (the CI job's summary, and `target/story-coverage.md`), so it
can't go stale. **Sources:** the [juror guide](../user/juror-guide.md), `conf/routes`, the
controllers, and the feature review of 2026-11-02 (plan
[`2026-11-04-story-coverage.md`](../plans/2026-11-04-story-coverage.md) §1.4–§1.6).

**IDs:** `<role>-<two digits>`: `J-` juror, `O-` organizer, `A-` admin, `R-` root, `S-` system or
security. An ID is never reused or renumbered: a story that no longer applies stays, with
*(retired)* in Notes, and no test may cite it. Gaps in the numbering are fine.

**A story** is a sentence about an outcome a user sees, not about code. It links the guide section
that describes it, or none (–). *Part:* in Notes says what the tests don't show yet; it stays until
a test shows the whole story, and makes the story "partly covered" in the matrix.

**Tags:** a browser test cites a story by its Playwright tag (`{ tag: '@J-21' }`), a Scala spec in
an example's or a block's name (`"a juror's own votes [J-27]" should`); see testing §9.4.

**Guides:** the organizer and admin stories above (O-*, A-*, R-*) still show "–" as their guide
section; their guides are now [organizer](../user/organizer-guide.md) (following the voting and
the results) and [admin](../user/admin-guide.md) (people, the contest and running the judging:
rounds, files, conflicts of interest), to be linked by anchor (`#o-…`, `#a-…`) when the catalogue
is next updated. The screenshot spec (`docs-screenshots.spec.js`) opens pages for images and tests
no story, so it carries no story tag.

**Not in this list yet:** the API tokens pages, readiness, the monuments pages. They are added when
a guide or a test needs them.

## Juror

| ID | Story | Guide section | Notes |
|---|---|---|---|
| J-01 | A juror accepts an invitation and sets a password | [Accepting your invitation](../user/juror-guide.md#accepting-your-invitation) | |
| J-02 | A juror signs in with login and password | [Signing in with your password](../user/juror-guide.md#signing-in-with-your-password) | |
| J-03 | A wrong password keeps the login page, with its error | [Signing in with your password](../user/juror-guide.md#signing-in-with-your-password) | |
| J-04 | A juror signs in with Wikimedia or Google | [Signing in with Wikimedia or Google](../user/juror-guide.md#signing-in-with-wikimedia-or-google) | |
| J-05 | A juror whose wiki name has several accounts chooses one | [When one wiki name has several jury accounts](../user/juror-guide.md#when-one-wiki-name-has-several-jury-accounts) | |
| J-06 | A juror signs out from the user menu | [Signing out](../user/juror-guide.md#signing-out) | also "sign out everywhere" |
| J-07 | A signed-out juror opening a page signs in and comes back to it | [When you are signed out](../user/juror-guide.md#when-you-are-signed-out) | browser: the `returnTo` redirect only |
| J-08 | After signing in, the first open round's gallery opens (in a newer contest of the juror's if the current one has none, never an older one), or "No round configured yet" with Sign out, no link back to the start page | [What you see after signing in](../user/juror-guide.md#what-you-see-after-signing-in) | *Part:* only the page without an active round |
| J-09 | A juror in several open rounds switches round from the round menu | [What you see after signing in](../user/juror-guide.md#what-you-see-after-signing-in) | |
| J-10 | A round being prepared, stopped or paused for the juror is not shown | [Rounds you don't see](../user/juror-guide.md#rounds-you-dont-see) | |
| J-11 | A juror in several contests switches contest from the contest menu (juror-only contests without an open round left out), or by a link | [Taking part in several contests](../user/juror-guide.md#taking-part-in-several-contests) | |
| J-12 | The gallery's tabs (all, unrated, selected, rejected) show their files and counts | [Tabs: all, unrated, selected, rejected](../user/juror-guide.md#tabs-all-unrated-selected-rejected) | browser: the page tour opens the galleries only |
| J-13 | A juror sorts the gallery | [Sorting your files](../user/juror-guide.md#sorting-your-files) | |
| J-14 | A juror filters the gallery by region | [Regions](../user/juror-guide.md#regions) | |
| J-15 | A juror pages through the gallery | [The pager](../user/juror-guide.md#the-pager) | |
| J-16 | A juror selects or rejects a file in a yes/no round's gallery | [Voting in a yes/no round's gallery](../user/juror-guide.md#voting-in-a-yesno-rounds-gallery) | |
| J-17 | A juror rates a file in a rated round's gallery | [Rating in a rated round's gallery](../user/juror-guide.md#rating-in-a-rated-rounds-gallery) | |
| J-18 | A file deleted, missing or renamed on Commons is shown as such, or under its new name | [Deleted or missing files](../user/juror-guide.md#deleted-or-missing-files) | deleted files are `images.deleted_at`, not a `selection_deleted` table (plan §1.6) |
| J-19 | A juror opens a file in the large view from the gallery and Esc returns to its page | [Opening a file in the large view](../user/juror-guide.md#opening-a-file-in-the-large-view) | *Part:* no test opens it from a tile or presses Esc; the browser tests open it by URL |
| J-20 | A juror votes in the large view by its buttons or stars | [Voting in the large view](../user/juror-guide.md#voting-in-the-large-view) | |
| J-21 | A juror votes in the large view by key (Space, Delete, digits) | [Keyboard shortcuts in the large view](../user/juror-guide.md#keyboard-shortcuts-in-the-large-view) | |
| J-22 | A juror moves between files by key (arrows, Page Up/Down, Home, End) | [Keyboard shortcuts in the large view](../user/juror-guide.md#keyboard-shortcuts-in-the-large-view) | |
| J-23 | A juror rates a file by criteria | [Rating by criteria](../user/juror-guide.md#rating-by-criteria) | |
| J-24 | Back after a vote shows the stored vote | [Going back after a vote](../user/juror-guide.md#going-back-after-a-vote) | Chromium: U23 asserted instead (testing §10.3); Safari manual |
| J-25 | A juror comments on a file; others in the contest see it | [Comments on a file](../user/juror-guide.md#comments-on-a-file) | |
| J-26 | A comments-only round takes comments, no votes | [Comments-only rounds](../user/juror-guide.md#comments-only-rounds) | *Part:* the refused votes only |
| J-27 | A juror sees only their own votes; another juror's are refused | [Your own votes only](../user/juror-guide.md#your-own-votes-only) | |
| J-28 | In a round that allows it, a juror gets the organizers' view | [Your own votes only](../user/juror-guide.md#your-own-votes-only) | *(retired)* 26.11: the legacy `jury_org_view` flag is ignored; jurors get only their own votes (J-27) |
| J-29 | A disqualified file leaves the juror's list; requalified, it returns with the vote | [Disqualified files](../user/juror-guide.md#disqualified-files) | |
| J-30 | A juror picks light, dark or system theme, shown from the first frame | [Light, dark or system theme](../user/juror-guide.md#light-dark-or-system-theme) | |
| J-31 | A juror changes the interface language | [Interface language](../user/juror-guide.md#interface-language) | |
| J-32 | A juror opens their profile and links a Wikimedia account | [Your profile](../user/juror-guide.md#your-profile) | browser: the page tour opens the profile only |
| J-33 | The large view's side sections stay as the juror left them | – | *Part:* the first frame is `fixme` (U18, UI practices §9) |
| J-34 | An expired page or token says to reload | ["This page has expired or you were signed out"](../user/juror-guide.md#this-page-has-expired-or-you-were-signed-out) | |
| J-35 | "Access denied" shows a reference and a way to sign in as another account | ["Access denied" and its reference](../user/juror-guide.md#access-denied-and-its-reference) | |
| J-36 | A juror changes an earlier vote from the Selected, Rejected or Rated tab | [Tabs: all, unrated, selected, rejected](../user/juror-guide.md#tabs-all-unrated-selected-rejected) | *Part:* no test changes a vote from the Selected, Rejected or Rated tab; the browser changes one from the first tab, the Scala specs one voted out of a filtered list |
| J-37 | The large view shows the file's resolution, monument and Commons link; a video plays | [Opening a file in the large view](../user/juror-guide.md#opening-a-file-in-the-large-view) | browser: the page tour opens it only |
| J-38 | In a rated round with the worst mark, a juror gives a file ✗ and clears a vote apart, by the buttons, the tile and the keys (Delete, Backspace) | [Rated rounds (stars)](../user/juror-guide.md#rated-rounds-stars) | *Part:* no browser test yet; Jest (`worst-mark.test.js`) and the Scala specs |

## Organizer

| ID | Story | Guide section | Notes |
|---|---|---|---|
| O-01 | An organizer opens a round's statistics, a row per juror | – | |
| O-02 | An organizer lists a round's results of all jurors (rank, average) as gallery, by rate or file list | – | |
| O-03 | An organizer views one juror's votes from the juror menu | – | |
| O-04 | An organizer reads a criteria round's results | – | |
| O-05 | An organizer exports a round's results as CSV | – | |
| O-06 | An organizer signs in with login and password | – | |

## Admin

| ID | Story | Guide section | Notes |
|---|---|---|---|
| A-01 | An admin creates a round; it stays hidden until its files are distributed | – | *Part:* no request-level test of the hidden round (that is `RoundDistributionJobSpec`, a service spec) |
| A-02 | An admin edits a round; Cancel saves nothing | – | |
| A-03 | An admin starts, stops and sets the current round | – | *Part:* setting the current round only; start and stop in `StartStopViewSpec`, a view spec |
| A-04 | An admin adds jurors to a round and pauses one | – | *Part:* pausing only |
| A-05 | An admin distributes a contest's new files to a round | – | *Part:* the new files' count only; the distribution in service specs |
| A-06 | An admin follows a job's progress, cancels or retries it | – | *Part:* a failed job's status and the job endpoints' access |
| A-07 | An admin adds, mass-imports or invites users, reinvites, cancels an invitation, writes to them on Commons | – | browser: the page tour opens the users page only |
| A-08 | An admin changes a user's role or wiki account, or sets their password | – | mailing a reset link and ending sessions are root's (R-05) |
| A-09 | An admin imports files from a Commons category, template or page, or a pasted list, and caches them | – | *Part:* the import job's start; the sources in `AppendImagesSpec`, a service spec |
| A-10 | An admin reviews conflicts of interest, disqualifies and requalifies in a stopped round | – | |
| A-11 | An admin checks a round's files for deleted or missing ones | – | |
| A-12 | An admin browses the contest's files, its "Deleted on Commons" tab and a file's page | – | |
| A-13 | An admin updates the files' monument ids and the monument lists | – | *Part:* the template and the offer only; the update in `AppendImagesSpec` |
| A-14 | An admin adds a pasted list of files to an existing round, whatever its filters | – | |
| A-15 | A double submit of the new round form makes one round | – | |
| A-16 | An admin makes the next round from earlier results: selected by at least, minimum average, top N, several rounds merged | – | the form's choices in view specs (`TopBinaryOrderChoiceSpec` …), the selection in service specs |
| A-17 | An admin edits the contest's greeting, previewed per member; a new account gets it by mail | – | *Part:* saving it only |
| A-18 | An admin signs in with login and password | – | |
| A-19 | An admin creates a link to share for an invitation or a password reset, copies it once, and sees whether it was used, expired or revoked; only for accounts that are theirs alone | – | |
| A-20 | An admin exports the thumbnail URLs of the contest's or a round's files | – | |

## Root

| ID | Story | Guide section | Notes |
|---|---|---|---|
| R-01 | Root opens the contests page, creates a contest or imports contests from Commons | – | *Part:* opening the page only |
| R-02 | First-run setup creates the root account | – | |
| R-03 | Root lists all users across contests and makes an account root | – | *Part:* making an account root only |
| R-04 | Root signs in with login and password | – | |
| R-05 | Root mails a reset-password link to an account, or ends all its sessions | – | |

## System and security

| ID | Story | Guide section | Notes |
|---|---|---|---|
| S-01 | A juror is refused organizer and admin pages, an organizer admin pages, a member with no jury role (board, auditor, staff) every page (403 with reference) | ["Access denied" and its reference](../user/juror-guide.md#access-denied-and-its-reference) | |
| S-02 | A state-changing POST without the CSRF token is refused | – | |
| S-03 | Every page runs under the production CSP, without violations or other hosts | – | every browser test checks it (`lib/fixtures.js`); the policy itself in `ContentSecurityPolicySpec`, a guard |
| S-04 | Every page has one `h1` and no untriaged serious accessibility finding | – | the axe scan in Chromium only |
| S-05 | A missing resource is the 404 page; a server error the error page with a reference | [Other errors](../user/juror-guide.md#other-errors) | |
| S-06 | A vote in a round not open to jurors (stopped, frozen, distributing) is refused and the stored vote stays | [Rounds you don't see](../user/juror-guide.md#rounds-you-dont-see) | *Part:* a stopped round and a stopped juror only |
| S-07 | A vote from someone not a juror of the round, or of another contest, is refused and changes nothing | – | |
| S-08 | A vote outside the round's rates (2 in a yes/no round, 6 in a 1–5 round) is refused | – | |
| S-09 | Another account's password is set by a single-use link (mailed or shared, A-19), or typed in by root or an admin only where a shared reset link is allowed, with its effects (sessions ended, email unverified; `AdminSetPasswordSpec`); an admin changes its email only while it is an invitee (who is invited again), root through a link mailed to the new address; an admin may set its wiki account, valid and no other account's, as unverified | – | |
| S-10 | A juror or an organizer is refused an admin's change (a POST), and nothing is written | – | |
