# Help links in the tool, and the user guides split into topic pages

**Status:** decided. All twelve questions of the proposal were decided by the maintainer on
2026-11-26 **[user]** (§1). Nothing is built yet. Written 2026-11-26 against master `96312a39`
(release 26.11.17) and the open hotfix PR #216 (26.11.17.1), which adds the first guide link.

Marks: **[user]** decided by the maintainer; anything else is this plan's default, open to the
maintainer.

**The goal.** The tool gets small "more about this" links next to the hard parts of its forms and
pages, leading to the right section of the English user guide, as it was at the running version.
First the guides become link targets that last: short topic pages with fixed ids instead of three
long role guides.

## 1. Decisions [user] 2026-11-26

1. **Where the links point:** GitHub's view of the repository, pinned to the release tag of the
   running build: `https://github.com/UkrainianWiki/wlxjury/blob/<version>/docs/user/<page>.md#<id>`.
   Not master (ahead of production), not served in the tool.
2. **New tab:** a help link opens in a new tab, so a half-filled form is never lost; it shows an
   icon and an accessible label that says so.
3. **Language:** the link text is translated (en, ru, uk, fr); a visually hidden label says the
   guide is in English.
4. **Fixed ids in the juror guide** (`j-…`) before any juror link. A spec checks that every id the
   tool links to exists.
5. **Jurors:** one **Help** link in the gallery's top bar, to the jurors' contents page. No links
   per field or section for jurors.
6. **Missing sections are written first** (criteria catalogue, image cache, image check, the round
   settings change log), then linked. No link to "the nearest section".
7. **Field links only for the hard fields** (Track label, the category boxes, Top N / Choose N, the
   score rule, the distribution settings); section links elsewhere.
8. **docs/user is split into about 25 topic pages**, with a contents page per role.
9. **Shared topics are written once**, with labelled role notes, not one page per role.
10. **The three guide files stay, as contents pages.** `admin-guide.md`, `organizer-guide.md` and
    `juror-guide.md` become the per-role contents (a list of topics). Each keeps every old anchor
    id on its entry, and the entry links to the topic page and id, so an old link still lands on
    the right entry. Nothing is deleted.
11. **Order:** the split first, then the links, straight to the topic pages.
12. **Images stay flat** in `docs/user/images`, with unchanged names: the screenshot spec and the
    photo scripts don't change.

Already in flight: the hotfix PR #216 adds **More about tracks** after the Track label's help on
the round form, through `views.Guide` (`app/views/Guide.scala`), which reads the build's version
from `wlxjury-build.json` and links `docs/user/admin-guide.md#a-tracks`. The helper of §4 extends
`views.Guide`; #216's link is moved onto it in slice 3, and stays valid until then (the contents
page keeps `#a-tracks`).

## 2. The topic pages

### 2.1 Layout

Topic pages sit **flat in `docs/user/`**, `docs/user/<topic>.md`, next to the three contents pages
and `whats-new-26.11.md`; not in a `topics/` folder. Why:

- the images stay at `images/x.png` relative to every page, so no image link changes (decision 12);
- links between pages are plain file names, as today;
- a help URL is `docs/user/<topic>.md#<id>`, short, and the folder has about 30 files, which GitHub
  lists in one screen.

Juror topics start with `juror-`, so the jurors' pages group together in the listing. A new
`docs/user/README.md` says who reads what and links the three contents pages and what's new;
GitHub shows it under the folder's file list.

Each topic page: a `#` title, sections as `##`, sub-sections as `###`. Every `##` and `###` heading
has an explicit `<a id="…"></a>` on the line above it. Section headings keep their words, so the
GitHub slugs that the story catalogue links still exist on the topic page.

### 2.2 Ids

- Existing ids stay as they are, on the section they named: `a-…` (admin), `o-…` (organizer),
  `w-…` (what's new), `j-recent-comments`. No topic prefix.
- The juror sections and the organizer headings without an id get one: `j-…` (juror) and `o-…`.
- An id is unique across `docs/user` topic pages (the check of §5 enforces it), so a section can
  move between pages later without breaking a help link: the HelpLinks table changes, the id
  doesn't.

### 2.3 Topics, and where each old section goes

Roles: A admin, O organizer, J juror, all = everyone. Sizes are lines after the split.

| Page | Role | From (old guide, section id or heading) | Lines |
|---|---|---|---|
| `README.md` | all | new: who reads what, the contents pages, what's new | 20 |
| `signing-in.md` | all, notes for A | juror: Signing in to WLX Jury (accepting your invitation, password, Wikimedia and Google *(comment, not offered yet)*, signing out); admin: `a-sign-in`, `a-password` (an **Admins** note), `a-passwords`, `a-wikimedia-google` *(comment)*, `a-sessions`, `a-api-tokens` | 200 |
| `settings.md` | all | juror: Your settings (theme, language, profile), Supported browsers | 40 |
| `troubleshooting.md` | all | juror: Common problems | 50 |
| `accounts.md` | A, O | admin: `a-accounts`, `a-one-account`, `a-roles`, `a-current-contest`; organizer: `o-who-can-do-what` | 75 |
| `people.md` | A | admin: `a-users`, `a-users-page`, `a-add-person`, `a-wiki-confirm`, `a-find-account`, `a-mass-import`, `a-add-from-contest` | 215 |
| `editing-people.md` | A | admin: `a-edit-person`, `a-wiki-account-taken`, `a-account-changes`, `a-duplicates`, `a-join` | 180 |
| `invitations.md` | A | admin: `a-invitations`, `a-invitation-email`, `a-pending`, `a-share-link`, `a-greeting` | 95 |
| `contest.md` | A, note for O | admin: `a-contest`, `a-contest-settings`, `a-series`, `a-series-admins`, `a-more-than-one-admin`; organizer: the Series paragraph of `o-who-can-do-what` | 80 |
| `contest-files.md` | O, A | organizer: `o-contest-files`; admin: `a-import-files` | 75 |
| `monuments-authors.md` | O, A | organizer: `o-monuments`, `o-authors`, `o-authors-list` | 110 |
| `rounds.md` | A, note for O | admin: `a-running`, `a-contest-and-rounds`, `a-round-kinds` (+ organizer `o-round-kinds` as a note), `a-stages`, `a-rounds-list`, `a-start-stop`, `a-what-jurors-see` | 140 |
| `round-create.md` | A | admin: `a-create-round`, `a-distribution`, `a-edit-round` | 85 |
| `round-review.md` | A, note for O | admin: `a-review` | 50 |
| `round-files.md` | A | admin: `a-choose-files`, `a-leave-out-rounds`, `a-rejection-filter`, `a-add-files`, `a-distribute-new-files` | 125 |
| `tracks.md` | A | admin: `a-tracks` | 80 |
| `scoring.md` | A, O | admin: `a-score-rule`, `a-worst-mark`; organizer: `o-score` | 55 |
| `round-jurors.md` | A | admin: `a-round-jurors`, `a-pause-juror`, `a-add-juror`, `a-reassign-juror` | 145 |
| `statistics.md` | O, A | organizer: `o-start`, Following the voting, `o-statistics` | 100 |
| `results.md` | O, A | organizer: Results, `o-results` (with the vote marks and vote-time orders), `o-by-rate`, `o-file-list`, `o-large-view`, `o-csv` | 105 |
| `comments.md` | O, A | organizer: `o-comments-filter`, `o-comments-report`, `o-comments-by-person` | 80 |
| `conflicts.md` | O, A | organizer: Conflicts of interest, `o-conflicts`, `o-conflicts-page`; admin: `a-leave-out-conflicts`, `a-disqualify` | 70 |
| `root.md` | root | admin: `a-root`, `a-normalize-authors` | 125 |
| `juror-rounds.md` | J | juror: Finding your rounds, Kinds of rounds | 90 |
| `juror-gallery.md` | J | juror: The gallery (top bar, votes menu, files with comments, `j-recent-comments`, sorting, regions, one monument, pager, voting and rating, deleted files) | 175 |
| `juror-large-view.md` | J | juror: The large view (opening, the monument's page, voting, criteria, keyboard, going back, comments) | 140 |
| `juror-visibility.md` | J | juror: What you can and can't see | 35 |

26 topic pages, 35 to 215 lines. `whats-new-26.11.md` stays as it is, with its links retargeted to
the topic pages.

**Shared topics** (decision 9): `signing-in`, `accounts`, `contest-files`, `scoring`,
`conflicts`, `statistics`, `results`, `comments`. The text is written once; where a role does
more or sees less, a labelled note says so: a paragraph that starts **Admins:**, **Organizers:**
or **Jurors:**, or for a whole section a first line `> **For admins.** …`. Where two guides said
the same thing twice (the kinds of rounds, the score), the fuller text stays and the other becomes
a one-line role note. The juror pages stay written to the juror ("you"); the organizer and admin
pages link them for what jurors see.

### 2.4 The contents pages (decision 10)

`admin-guide.md`, `organizer-guide.md` and `juror-guide.md` keep their title and a short intro
(who the guide is for, the labels in bold, the terms), then list the topics in the order the old
guide had them, which is the order that role needs. Per topic page: its link and one line on what
it covers; under it, one entry per old section:

```markdown
<a id="a-users"></a>
## People

[People](people.md): the users page, adding people one by one, from a list or from another contest.

- <a id="a-users-page"></a><a id="the-users-page"></a>[The users page](people.md#a-users-page)
- <a id="a-add-person"></a><a id="adding-a-person"></a>[Adding a person](people.md#a-add-person)
```

- **Every old anchor stays on its entry:** each explicit id (`a-…`, `o-…`,
  `signing-in-to-the-jury-tool`, `j-recent-comments`, …) and each GitHub slug of an old heading
  (`#reviewing-a-rounds-images`, `#the-votes-menu`), which links in the story catalogue, plans,
  mails and forum posts use. A slug that one of the contents page's own headings already makes is
  not repeated.
- The entry links to the topic page at the section's explicit id, never its slug.
- This is the old-anchor → new-page table: an anchor can't redirect on GitHub, so the reader lands
  on the entry and follows its link. The pages are kept for good (nothing is deleted), not as
  one-release stubs as the proposal had them.
- A release tag before the split keeps pointing at the old long guides, so links into an older
  version keep working too.

### 2.5 What else the split touches

- `docs/testing/stories.md`: its guide links move to the topic pages, at the heading slugs
  (`scripts/story-coverage.py` checks them against headings). `story-coverage.py` counts `##`
  headings as sections too in its "unlinked sections" report, since topic pages use `##`.
- `docs/README.md` §3 and §5: one guide per role becomes topic pages plus contents pages; the
  stable-anchor rule covers `j-…`; the user-docs row of §3 names the juror pages.
- Comments in `browser-test/tests/review.spec.js` and `round-category.spec.js` that cite a guide
  section name the topic page.
- `README.md` keeps its links to the three guides (they are the contents pages now).
- Not changed: images, `images/README.md`, `docs-screenshots.spec.js`, `docs-photos*.py`,
  `DocsPhotos.scala`; plans and history (their links land on the contents pages' entries).
- Open PRs that edit `docs/user` (#216 tracks, #174, #124) conflict with the split. Whichever
  merges second carries the other's text: if the split merges second, master is merged into the
  split branch and the changed text is moved into its topic page by hand (no rebase of a pushed
  branch). The split waits for #216, the hotfix, to merge first.

## 3. Missing sections (slice 2, decision 6)

Written before any link to them, in the topic page that owns the field:

| Section | Page, id | What it explains |
|---|---|---|
| Criteria catalogue | `scoring.md#a-criteria` | the shared catalogue (every contest's round forms offer its criteria; who adds them), choosing 1–10 criteria on the round form, a file's rate as their sum, when criteria can't change; today only a paragraph under kinds of rounds |
| Local image cache | `contest-files.md#a-image-cache` | **Download images locally** on the contest files page and the round form: what the cache is for, that the operators set it up, its job and its "nothing to fetch" line |
| Round image check | `round-create.md#a-image-check` | **Check round images** on a round, and root's **Check every round's images**: what it compares, what it repairs, what it only reports, why a round is skipped |
| Round settings change log | `statistics.md#o-round-changes` | **Changes to this round's settings** on the round's statistics: which fields are logged (track label, score rule, worst mark, top files, …), who sees it, and "Changed by a juror of this round" |
| Top files (found while planning) | `round-files.md#a-top-files` | **Number of top files from the previous rounds** and **Top files of a yes/no round by**: no section on master; Top N / Choose N is a hard field (decision 7), so it is written here, or with the cut picker if that lands first |

About half a day, one PR, docs only; each section gets the usual docs review.

## 4. The helper

### 4.1 Pieces

- **`views.HelpLinks`** (Scala, next to `views.Guide`): one table, topic key → page, id and the
  message key of the link text:

  ```scala
  final case class HelpTopic(page: String, anchor: String) { def messageKey = ... }
  object HelpLinks {
    val topics: Map[String, HelpTopic] = Map(
      "tracks"       -> HelpTopic("tracks.md", "a-tracks"),
      "categories"   -> HelpTopic("round-files.md", "a-choose-files"),
      "juror.help"   -> HelpTopic("juror-guide.md", ""),   // the jurors' contents page
      ...)
    def url(key: String): String = Guide.url(topics(key))
  }
  ```

  URLs live only here. A view passes only the key; an unknown key fails the spec, never a page.
- **`views.Guide`** (from #216) gains `url(page, anchor)`; `admin(anchor)` becomes a call of it, or
  goes once #216's link uses the helper.
- **Version source:** `views.Guide.version`, from `wlxjury-build.json` as #216 reads it. A version
  that isn't a release tag (`26.11.18-SNAPSHOT` in development, or no build file) falls back to
  `master`: a tag is used only if it matches `^\d+\.\d+\.\d+(\.\d+)?$`. Every release and
  hotfix is tagged with its bare version (`26.11.17`; workflow practices §7.4), so a production
  link resolves. No new config key.
- **Twirl snippet `views/snippets/helpLink.scala.html`**, `@helpLink("tracks")`:

  ```html
  <a class="help-link" href="…" target="_blank" rel="noopener noreferrer">
    <i class="bi bi-question-circle" aria-hidden="true"></i>
    More about tracks<span class="visually-hidden"> (guide in English, opens in a new tab)</span>
  </a>
  ```

  It reuses `views.ExternalLinks.marked` for the new-tab mark the tool shows after links that
  leave it (as #216 does), so the hidden text and the mark aren't duplicated; the exact markup
  follows UI practices.
- **Message keys:** `help.more.<topic>` per link text ("More about tracks", "More about the file
  score", …), `help.guide` ("Help", the jurors' top bar), `help.englishNewTab` (the hidden label),
  in `messages.en`, `.fr`, `.ru`, `.uk`. #216's `round.track.more` becomes `help.more.tracks`.
  The PR body lists the keys for the translation review (ru/uk by the maintainer, fr by a French
  speaker).

### 4.2 The check (`test/views/HelpLinksSpec.scala`)

A plain unit spec, no database and no Play app, in the normal suite:

- every topic's page exists under `docs/user/`, and its anchor (when not empty) is an explicit
  `<a id="…">` in that page, not a heading slug;
- every topic's message key, `help.guide` and `help.englishNewTab` exist in all four
  `conf/messages.*` files, and nowhere is the text empty;
- every `@helpLink("…")` in `app/views/**/*.scala.html` names a key of the table (a regex over
  the templates);
- `Guide.url` for a snapshot version links `master`, for `26.11.17.1` that tag.

So a renamed or moved section fails the build in the same PR that moves it.

`scripts/docs-links.py` (slice 1, §5) checks the guide's own links; the spec checks the tool's
links into it.

## 5. The guide's link check (slice 1)

`scripts/docs-links.py`, standard library only, like `story-coverage.py`, with `--self-test`; a CI
step next to **Story coverage** in `ci.yml`. It reads every `*.md` under `docs/user/` and fails on:

- a relative link or `<img src>` whose file doesn't exist;
- a `#anchor` (in-page or to another page in `docs/user`) that is neither an explicit `<a id>` nor
  a heading slug of the target page;
- an explicit id used twice in one page, or twice across the topic pages;
- a `##` or `###` heading of a topic page without an explicit id on the line above.

It also checks the links into `docs/user` from `README.md`, `docs/README.md` and
`docs/testing/stories.md`. With `--legacy-rev REV` it checks that every anchor of the old guides at
`REV` (explicit ids and heading slugs) is still on the same contents page; the split PR runs it
once against `96312a39` and reports the result (CI checks out one commit, so it isn't a CI step).

## 6. Where the links go

After the split and the missing sections. **F** field link (after the field's help text), **S**
section link (beside the page's or section's heading). Per decision 7 only the five hard fields
get F.

### Admins

| Place | Kind | Target |
|---|---|---|
| Round form: Track label | F | `tracks.md#a-tracks` (moves #216's link onto the helper) |
| Round form: category boxes (first, second, More category options) | F | `round-files.md#a-choose-files` |
| Round form: Top N / Choose N (top files) | F | `round-files.md#a-top-files` |
| Round form: File score | F | `scoring.md#a-score-rule` |
| Jurors form: File distribution | F | `round-jurors.md#a-round-jurors` |
| Round form: heading | S | `round-create.md#a-create-round` |
| Round form: Media filtering, previous rounds | S | `round-files.md#a-choose-files` |
| Round form: rejection filter, leave out rounds | S | `round-files.md#a-rejection-filter`, `#a-leave-out-rounds` |
| Round form: Worst mark | S | `scoring.md#a-worst-mark` |
| Round form: Criteria | S | `scoring.md#a-criteria` |
| Round form: Add files, Distribute N new files | S | `round-files.md#a-add-files`, `#a-distribute-new-files` |
| Round form: Leave out conflicts of interest | S | `conflicts.md#a-leave-out-conflicts` |
| Round form / contest files: Download images locally | S | `contest-files.md#a-image-cache` |
| Round: Check round images | S | `round-create.md#a-image-check` |
| Review page | S | `round-review.md#a-review` |
| Rounds list | S | `rounds.md#a-rounds-list` |
| Add juror, Reassign files, pause | S | `round-jurors.md#a-add-juror`, `#a-reassign-juror`, `#a-pause-juror` |
| Users page; add, import, from another contest | S | `people.md#a-users-page`, `#a-mass-import`, `#a-add-from-contest` |
| Edit user; possible duplicates; join | S | `editing-people.md#a-edit-person`, `#a-duplicates`, `#a-join` |
| Account changes | S | `editing-people.md#a-account-changes` |
| Invitations; link to share; greeting | S | `invitations.md#a-pending`, `#a-share-link`, `#a-greeting` |
| Import contest files | S | `contest-files.md#a-import-files` |
| Series page | S | `contest.md#a-series-admins` |

### Organizers (and admins)

| Place | Kind | Target |
|---|---|---|
| Round statistics | S | `statistics.md#o-statistics` |
| Changes to this round's settings | S | `statistics.md#o-round-changes` |
| Results bar (gallery of all jurors, by rate, file list) | S | `results.md#o-results` |
| Comments report | S | `comments.md#o-comments-report` |
| Conflicts page | S | `conflicts.md#o-conflicts-page` |
| Contest files | S | `contest-files.md#o-contest-files` |
| Monument's page, authors list | S | `monuments-authors.md#o-monuments`, `#o-authors-list` |

### Jurors

| Place | Kind | Target |
|---|---|---|
| Gallery top bar, **Help** | S | `juror-guide.md` (the jurors' contents page) |

### Root

| Place | Kind | Target |
|---|---|---|
| Contests, Series | S | `root.md#a-root`, `contest.md#a-contest-settings` |
| Normalize stored authors | S | `root.md#a-normalize-authors` |

No link on the Access denied and error pages: they already say what to do, and a juror who needs
more has **Help** in the gallery.

## 7. Slices and order (decision 11)

1. **The split** (branch `docs/guide-split`): the topic pages and `README.md`, the three contents
   pages, juror `j-…` ids, links retargeted (whats-new, story catalogue, docs README),
   `scripts/docs-links.py` and its CI step, the `story-coverage.py` section level. Docs and a
   stdlib script; no Scala. About 1.5 days, then the docs review (the maintainer reads the merged
   shared topics). After #216 merges.
2. **The missing sections** (§3), docs only: about half a day.
3. **The helper** (§4): `HelpLinks`, `Guide.url` and the version rule, `helpLink`, the message keys
   in four languages, `HelpLinksSpec`; #216's tracks link moved onto it; the five admin field links
   (§6). About 1 day. Specs: `HelpLinksSpec`, the round form's view specs (`RoundTrackSpec`), and
   the large-view specs if a shared snippet changes.
4. **Section links for admins and organizers** (§6): about 1 day, in one or two PRs (admins, then
   organizers and root). Each placement is a key and one `@helpLink`; the spec covers them.
5. **The jurors' Help link** in the gallery's top bar: small; the gallery's top-bar specs and a
   screenshot of the bar (`juror-gallery-top-bar.png` is regenerated with the docs screenshots).

Every slice gets the usual review and fix cycle; slice 3 onwards lists its message keys in the PR
body for the translation review.

## 8. Not in this plan

- Serving the guides in the tool, or on GitHub Pages: not needed while the release-tag link works
  (decision 1).
- Translated guides: the guides stay English; publishing to a wiki for translation stays as
  `docs/README.md` §5 describes, undecided.
- Help links in e-mails or the API.
