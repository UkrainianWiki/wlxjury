# Juror galleries: keeping the place and a stable order

Two juror complaints (WLM 2026 UA):

1. «Повернення на першу сторінку» — going back from the large view to the gallery often lands
   on the first page, so a juror loses track of their progress.
2. «Несподіване сортування зображень» — after clicking the stars an image sometimes changes its
   place in the list.

## Findings

### 1. Losing the page

Going back (the gallery button, `Esc`) opens `/{module}/round/R/user/U/region/X/pageid/P`
(`navigationButtons.scala.html:36`, `keyboardNav.scala.html:32`). `GalleryService.filesByUserId`
finds the page from `SelectionQuery.imageRank(P)`. Four defects:

| # | Defect | Where | Effect |
|---|---|---|---|
| A | A rated round's gallery links its tiles to the large view **without the rate filter** and with `module = "byrate"` | `galleryByRate.scala.html:66`, `galleryByRateRegions.scala.html:67` | A juror on the **Unrated** tab (`/gallery/...?rate=0`) opens a file; the large view walks the *whole* list (sorted by rate) and `Esc` returns to `/byrate/.../pageid/P` with no filter: the "Rated" tab, some page of all files. Clicking **Unrated** again starts at page 1. Most likely the reported case: the complaint mentions stars, so a rated round. |
| B | `imageRank` returns 0 for a file not in the list; 0 becomes page 1 | `GalleryService.filesByUserId`, `ImageDbNew.imageRank` | In a filtered list (Unrated, Selected, ...) a file the juror has just voted on is no longer in it. A large view of it (browser Back after a vote, an old tab, a stale link) redirects to the *first file of the list* (`LargeViewController.showRound`, `index < 0`), and `Esc` from there is page 1. |
| C | Off by one: `imageRank` is 1-based (`ROW_NUMBER`), the page is `rank / pageSize` | `GalleryService.filesByUserId` | Going back from the last file of a page (15th, 30th, ...) opens the *next* page, where the file isn't. |
| D | `rated` (the organisers' Rated/Unrated filter) is not passed to the large view, its links or the pager either | routes, `pagerView`, large view templates | The same as A for organisers. |

### 2. Images moving

Every juror list is ordered `rate desc, monument_id, page_id` (`GalleryService.getQuery`).

- On the **Unrated** tab a rated file leaves the list (expected, but the large view then shows
  different neighbours, and Back to it jumps to the start — B).
- On the **Rated** tab (which is in fact *all* the juror's files: `/byrate/...` without a filter,
  labelled with the rated count) a new or changed rate moves the file to its rate group: the
  thumbs bar changes around the juror, `Up` no longer goes to the file just rated.
- The binary round's **All** view has the same order (selected, unrated, rejected).

There are no star widgets on gallery tiles in rated rounds; rating happens in the large view
only, so the movement is seen in the large view's thumbs bar and on returning to the gallery.

### Why the code makes these bugs easy

The bugs are not four unrelated slips; they come from how the list a juror is in is represented:

- **The list has no type.** "Which files, in which order" is six loose values — `asUserId`,
  `round`, `region`, `rate`, `rated`, `module` — passed separately through two controllers, the
  service, and ~12 templates (`main`, `navbar`, `pagerView`, `regionNav`, `gallery`,
  `galleryByRate[Regions]`, `large`, `main_large`, `navigationButtons`, `keyboardNav`,
  `thumbsBar`, `raty`). Each template rebuilds URLs by hand (`routes.…large(...)`, string
  paths with `@rateParam`), and each picks a subset of the values: A and D are two of those
  subsets. Adding `sort` the same way would be a seventh value in every signature.
- **Two filters for one idea.** `rate: Option[Int]` (binary rounds, and the juror's Unrated tab
  in rated rounds) and `rated: Option[Boolean]` (organisers in rated rounds) both mean "which
  votes", are combined in SQL, and are carried by different routes (`list` has `rate`, `byRate`
  has `rated`).
- **`module` is a string that means several things**: which template, which default list
  (`moduleByUserId`), where "back" goes, and the navbar's title. It is checked against
  `GalleryController.Modules` at runtime.
- **A mutable pager with a side channel.** `Pager` has `var page`, `var pages` and a private
  count; `GalleryService.filesByUserId` sets `pager.page` as a side effect of reading files
  when given `startPageId`. Position-to-page arithmetic is in three places (`filesByUserId`,
  `showRound`'s unused `page`, `contestFileLarge`), with different conventions — C.
- **The large view's window is computed twice** (`showRound`, `checkLargeIndex`: rank → offset −
  3 → 5 files → index) and then re-sliced (`show2`'s `extraLeft`/`extraRight`, `start`/`end`) for
  a window that is already exactly the five files shown. "File not found" is handled
  differently in each (first file of the list; gallery page 1) — B.
- **Order is a `Map[String, Int]`**, correct only because Scala keeps insertion order for maps
  of up to four entries; the ORDER BY and `imageRank` must agree, but nothing ties them.

The contest files page already has the better shape: `ContestFilesView` knows its list and
builds its own `pageUrl` / `largeUrl`, and `large/fileKeyboardNav.scala.html` takes
`(index, size, urlAt, backUrl)` instead of the list's parts. The plan brings the round
galleries to the same shape, then fixes the bugs inside it.

## Design

### `RoundList`: the list a viewer is in (`app/controllers/RoundList.scala`)

An immutable value, parsed once per request, that owns the list's identity, its query and
every URL into it:

```scala
final case class RoundList(
    round: Round,
    asUserId: Long,        // 0: all jurors (organisers)
    region: Option[String],
    filter: VoteFilter,
    order: ListOrder,
    view: GalleryView
) {
  def query: SelectionQuery              // the only place SelectionQuery is built for galleries
  def pageUrl(page: Int): String
  def atUrl(pageId: Long): String         // the gallery at a file: its page + "#pageId"
  def largeUrl(pageId: Long): String
  def voteUrl(pageId: Long, select: Int, criterion: Option[Int] = None): String
  def withFilter(f: VoteFilter): RoundList   // tabs, region nav, sort switch, juror/round menus
  def withOrder(o: ListOrder): RoundList
  def withRegion(r: Option[String]): RoundList
  def withViewer(asUserId: Long): RoundList
}
```

- `VoteFilter` (sealed): `All`, `Unrated`, `Rated`, `Rate(n)` (binary: selected/rejected).
  Replaces `rate` + `rated`. URLs keep reading `?rate=` and `?rated=` so bookmarks and the
  `rateDistribution` tabs still work, but write one `?filter=`.
- `ListOrder` (sealed): `FileOrder` (`monument_id, page_id`) and `ByRate`
  (`rate desc, monument_id, page_id`). Each owns its ORDER BY columns as a `Seq`, and the
  "ordered before" predicate `position` uses (below) — one definition, so the order and the
  position can't disagree. `SelectionQuery.order` becomes a `Seq[(String, Int)]`.
- `GalleryView` (sealed): `Gallery`, `ByRate`, `FileList`, `Csv`. Replaces the `module` string
  and `GalleryController.Modules`; `moduleByUserId` goes, the default is in `RoundList.parse`.
- `RoundList.parse(round, viewer, asUserId, params, cookie)`: the single place for defaults —
  a juror's own list: `All`, `FileOrder` unless the URL or the `gallerySort` cookie says
  otherwise; organisers' and results' lists: `ByRate`, cookie ignored. Unknown values fall back
  to the default.

Authorization stays where it is (`GalleryService.mayViewRound` / `viewAs`, `Secured`), done
before a `RoundList` is built; the list never decides who may see it.

### Paging and position (`ListPage`)

- `Pager` becomes immutable: `Pager(page: Int, count: Int, pageSize: Int = 15)` with `pages`,
  `offset`, `hasNext`, `hasPrev`, `pageNumbers` computed. `startPageId` and `Limit.startPageId`
  go; nothing mutates a pager. `contestFiles` uses the same type.
- `RoundList.position(pageId): Option[Int]` — 0-based: the file's index in the list; for a file
  of the viewer's round the filter leaves out, the number of the list's files ordered before
  it (where it would be); `None` for a file not in the viewer's round. Replaces `imageRank`'s
  callers (its 1-based "0 = missing" convention ends there). `Pager.pageOf(position)` is the
  one place that turns a position into a page.
- The `pageid/P` gallery routes (`listAtId`, `byRateAt`) become a redirect to
  `list.pageUrl(page) + "#P"`: the gallery handler only ever renders a page number, the URL then
  shows the real page (reloads and the pager agree), and the "set the page while reading files"
  path disappears with `filesByUserId`'s side effect.

### The large view's window (`Neighbourhood`)

- `RoundList.around(pageId): Option[Neighbourhood]` — `Neighbourhood(files, index)`, up to two
  files before and after, **always including `pageId`** if it is in the viewer's round, even
  when the filter now leaves it out (the existing `withPageId`, used only for criteria rounds,
  generalised). One query; `showRound` and `checkLargeIndex` both use it; `show2`'s
  `extraLeft`/`extraRight`/`start`/`end` and the template's `start`/`end` parameters go.
- `Neighbourhood.next: Option[ImageWithRating]` — the file after, else the one before: the
  vote redirect's target (today's `newIndex` arithmetic in `checkLargeIndex`).
- A large view of a file not in the round: the gallery at page 1 with nothing pretending to be
  "the file". No more silent jump to the list's first file.

### Templates take the list

- `large`, `main_large`, `navigationButtons`, `thumbsBar`, `raty` take `(list: RoundList,
  hood: Neighbourhood)` instead of `asUserId, files, index, start, end, page, rate, region,
  round, module`.
- The round large view's navigation keys reuse `fileKeyboardNav(index, size, urlAt, backUrl)`
  (the contest files' one) with `urlAt = i => list.largeUrl(hood.files(i).pageId)` and
  `backUrl = list.atUrl(pageId)`. `keyboardNav` keeps only the vote keys. (Today PageUp/PageDown
  index `files(index ± 5)` of a five-file window, so they only ever reach its ends; the shared
  one says so.)
- `galleryByRate`, `galleryByRateRegions`, `gallery` (`data-largeUrl`), `pagerView`,
  `regionNav`, and the gallery parts of `navbar` take `list` and call its URLs. `main` passes
  `Option[RoundList]` to `navbar` instead of `asUserId`, `rate`, `region`, `rated`.
- `rateParam` and the hand-written `/large/...`, `/gallery/...`, `/byrate/...` strings go from
  these templates. Paths only, never `absoluteURL` (`LargeViewControllerSpec` guards it).

### What it leaves alone

`GalleryService.isNotAuthorized` / `mayViewRound` overlap, the hard-coded criteria names in
`showRound`, and the comments module's own `rate`/`module` parameters are separate clean-ups;
noted, not part of this change.

## Plan

### Step 1 — `RoundList`, `VoteFilter`, `ListOrder`, `GalleryView`; immutable `Pager`

Introduce the types with `ByRate` as the only order (no behaviour change yet), move
`getQuery`'s construction into `RoundList.query`, switch both controllers and the templates to
it. Fixes A and D by construction: every link is the list's. `GalleryService.getSortedImages` /
`filesByUserId` / `getQuery` go (their spec, `GalleryServiceSpec`, moves to `RoundList`).

### Step 2 — `position`, `around`, the `pageid` redirect

Fixes B and C. Removes `imageRank`'s callers' arithmetic, `checkLargeIndex`'s copy of the
window, `show2`'s re-slicing.

### Step 3 — file order, the All tab, the remembered sort

- Add `FileOrder`; the defaults and the `gallerySort` cookie (long-lived, path `/`, not the Play
  session, so it survives signing out) live in `RoundList.parse`. The URL's `sort` always wins.
  The sort switch's links are `list.withOrder(...)` URLs; the response that renders a list with
  an explicit `sort` sets the cookie.
- Juror tabs for both kinds of round: **All (n)**, **Unrated (n)**, **Rated / Selected /
  Rejected (n)** — `list.withFilter(...)` links; the "Rated" tab that lists all files today is
  gone. A juror lands on All (round links, `listCurrent`, the rounds menu, after switching
  contest).

### Step 4 — say when a vote moved the file

The vote handler has the list (`RoundList` from the vote URL) and the file's position before the
vote; after it, if `list.position(pageId)` differs or is now outside the filter, it flashes the
page id and the new rate. The next page shows a non-modal notice («Оцінене зображення
перемістилося (★4) — переглянути») linking `list.largeUrl(pageId)` (or the All list's, when
filtered out). Never in file order without a filter. Non-modal so keyboard voting isn't
interrupted.

### Step 5 — performance check

With `jury_id, round_id` fixed, the index `(jury_id, round_id, rate, monument_id, page_id)` (V48)
gives the rate order and the filtered lists' order, but not `monument_id, page_id` over all rates:
the deferred-join page and `position` will filesort the juror's key tuples (a few thousand
rows). Run a paired pilot of `VotingSimulation` (both orders, SQL debug logging off); only if the
gallery/large view regresses noticeably, add `(jury_id, round_id, monument_id, page_id)` in a
migration.

## Tests

- `RoundList` (unit, no DB): `parse` defaults (juror → All + file order; organiser → by rate),
  cookie vs URL precedence, invalid values, old `?rate=`/`?rated=` URLs; every URL keeps
  filter, order, region, viewer and view (A, D) and is a path.
- `RoundList` (DB): `position` of the 1st, 15th, 16th file (C); of a file the filter leaves out
  (B); `None` outside the round; both orders; `around` at the list's ends and for a
  filtered-out file. `Pager.pageOf`.
- `LargeImageSpec`: from a rated round's Unrated tab the large view's links, `Esc` and vote
  redirect stay in that list (A); the `pageid` route redirects to the right page; a just-voted
  file's large view shows that file (B).
- `LargeViewControllerSpec` (jsdom): navigation keys from `fileKeyboardNav`, paths only.
- Gallery: a juror lands on All in file order; a vote in file order leaves the gallery page
  unchanged; the moved notice with `ByRate` and not with `FileOrder`; the sort cookie.
- Existing `OwnVotesSpec`, `MutationAuthorizationSpec` unchanged (no permission changes).

## Order of delivery

Steps 1–2 are refactoring plus the bug fixes, no visible UX change: ship first. Steps 3–4 change
what jurors see; Step 5 decides whether they need an index.

## Decisions (2026-10-27)

- Jurors' default order: file order.
- A juror lands on the All tab.
- The chosen sort is remembered across visits (a per-browser cookie).
- Restructure rather than add parameters: one list value owns the query and its URLs.

## Implementation notes

- Step 1: a binary round's tile also lost the list: `ug-theme-tiles.js` opened the large view
  with `?rate=` set to the clicked file's *own* vote, so from **All** a click on an unrated file
  put the large view, and `Esc`, in the Unrated list. The tile now opens `list.largeUrl` as is.
- Step 1: URLs write `filter=` and `sort=`; the way back keeps its old name, `module=`. Older
  `rate=`/`rated=` URLs still read. The list's query string is read from the request
  (`RoundList.of`), so the routes only have path parameters; `/comment` carries the list's query
  to go back to it.
- Step 1: removed the unrouted `LargeViewController.removeImage` and the unused
  `rateSelection`, `pagerView` and `rateParam` templates.
- Step 2: `around` keeps the old window: two files before the file and five in all where the
  list has them (at its start, the file and four after). By rate, a file a vote took out of
  the filter is placed by its *new* rate (a selected file first, a rejected one last), so
  going back from it is still not its old page; file order (Step 3) keeps it in place.
- Step 2: the vote redirect and the large view use the same `around`; the large view's moves
  are `fileKeyboardNav`'s, `keyboardNav` keeps only the vote keys.
- Step 3: file order is the default of a juror's own *gallery* view (`/gallery/...`, and a large
  view going back to it); a juror's `/byrate/...` list stays by rate, as its name says. The
  cookie is set when a juror's own gallery is shown with a `sort=` other than the remembered
  one: every list link carries `sort=`, so the order last used is the one remembered. Landing
  links (sign-in, the access-denied suggestion, the navbar on other pages) carry the
  remembered order. The juror's tabs and the sort menu are in the navbar; Unrated shows also
  with 0 files.
- Step 4: no note when a vote takes a file out of the **Unrated** list: that is what the tab is
  for, and a note on every vote there would be noise. The note shows when a vote moves the file
  by rate, or takes it out of another filtered list (Rated, Selected, Rejected). It is a
  dismissible Bootstrap alert on the next large view (or gallery page), linking the file in
  the same list (in all files, if it left the list).
