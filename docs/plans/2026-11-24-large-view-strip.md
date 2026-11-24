# The large view's thumbnail strip: optional, or something else (plan, 2026-11-24)

**Status:** proposal, not built. The maintainer's idea (2026-11-24): the "next files" strip is of
doubtful use as a fixed default; make it optional, or show something else (the same monument's
photos, the juror's recent rates). Kept separate: the invisible **prefetch of the next file's main
image**, which only speeds voting and is worth having whatever the strip shows (section 6).

Read with [own gallery tiles](2026-11-19-own-gallery-tiles.md) (decided) and, on the open branches,
the modern web guidance plan (finding F1) and the UI approach investigation (htmx).

## 1. Today

- **What:** `views.html.large.thumbsBar` (the round's large view) and the same block inline in
  `large/contestFile.scala.html` (contest files; `RoundReviewController` renders it too). A
  `sideSection("large-next", "large.next.files", open = false)`: a collapsed "Next files" section
  in the side column, under the file-info section. Per file one `gallerybox` with a link to that
  file's large view, a comment chip (`CommentJdbc.countsByRoom`, one grouped query) and a video
  mark. No rate, no title text (title in `alt`/`title`).
- **Two templates, two data sets:** only `large.scala.html` (the round's large view) has a
  `RoundList`, a viewed juror and votes. `contestFile.scala.html` (contest files and
  `RoundReviewController`) takes `StoredFile` and `ContestFilesView`: no juror, no votes, no
  monument list. So "Same monument" and "My recent votes" exist only in the round's large view; in
  contest files those two options are not offered (the setting reads as Next files there; Off still
  means Off).
- **How many:** `Neighbourhood.Following = 5` files after the current one in the juror's own
  list order (`Neighbourhood.following`). Not the files before it. None at the list's end.
- **Sizes:** `Global.thumbSizeX x thumbSizeY` = 150 x 120 with a `srcset` (`Global.srcSet`); a
  deleted file uses `cachedSrcSet`. `data-failed-mark="thumb"` hides a failed one (large-view.js).
- **Lazy?** No (finding F1 of the modern web guidance plan: the strip's `<img>` has no
  `loading="lazy"`). Worse, the section is collapsed by default, but a collapsed section is
  `display: none` and an `<img>` in a hidden box is still fetched (with `loading="lazy"`, Chrome
  and Firefox are expected not to load images in a `display: none` box; that is reasoning about
  the engines, **not tested here**): **every large view loads five
  thumbnails the juror usually never opens**. The open/closed state is kept per viewer by
  large-sections.js, so a juror who opened it gets it open on every file.
- **Query cost:** no query of its own. `RoundList.around` already reads a window of
  `Neighbourhood.Window` = 5 files (2 before, the file, 2 after) in one keyset statement and
  widens the forward read to `max(window - side, 1 + Following)` = 6 rows so the strip has its 5
  (`ahead` holds the rows beyond the window, only for the strip). So the strip costs about **3 more
  rows in a statement that already runs, plus one `countsByRoom` statement**, plus 5 image
  requests (the real cost, in the browser and on the image cache side). A large view step is tens
  of ms (V99 note: 243 -> 80 ms in the worst case on production's copy; V100 note, separately:
  197 -> 43 ms), so 3 rows are noise next to the page's other statements. A real-app timing is being measured separately; this plan only uses it
  to choose the default (section 4).
- **Dependencies:** none for keyboard or navigation. Keys and buttons use `data-key-*` on
  `navigationButtons` and the 5-file `files` window (`Neighbourhood.next`, `index`), not the strip.
  The strip is plain links, so it works without script. Only `large-sections.js` (state) and the
  thumbnail failure mark touch it. The vote's redirect to the next file ignores it.
- **Authorization today:** it shows only the viewer's list (`RoundList`, built after
  `GalleryService.mayViewRound` and `viewAs`): a juror's own list; an organizer viewing one juror
  sees that juror's list order; with `asUserId == 0` (all jurors) the aggregate list's next files.
  It shows no rates, so no vote privacy question exists today. The options "Same monument" and
  "My recent votes" below do show rates and must be checked.

## 2. Options (per juror)

| Option | Shows | Value |
|---|---|---|
| **Off** | the section header only (it holds the menu, with "Show next files"); no thumbnails, no images | the fastest page; most jurors never open it |
| **Next files** (today) | the 5 following files of the list | a look ahead; mostly redundant with the keys and the prefetch |
| **Same monument** | the other files of this file's monument in the round, each with the juror's own rate | WLM: compare shots of one place, spot near-duplicates, rate consistently |
| **My recent votes** | the juror's last N votes in this round, newest first, each with its rate | revisit and change a vote without leaving the page |
| *Same author* (idea only) | the other files of the file's author | not planned: the author is shown only to those who may see organizer info (large.scala.html), so organizers only |

### Same monument

- **Filter and order:** the strip does **not** inherit the page's filter. It uses
  `withFilter(VoteFilter.All)` on the monument list (an "unrated only" or "rate 3" tab would
  otherwise give an empty or odd strip), in the list's own order (file order by default).
- **Which files:** this round's files with `monument_id` = the file's monument, under the
  viewer's list rules: `list.withMonument(Some(id))` already exists (`RoundList.monumentLine`
  builds the list filtered by the monument for the info card's link). The strip is that list's
  first 5 without the current file, in the list's order. Reusing `RoundList` keeps every
  rule: round membership, region, the `distributing` flag, `mayViewRound`, and the viewed juror's
  votes only.
- **Link target:** a strip link opens the file in the juror's **current list** (`list.largeUrl`),
  not the monument-filtered list: simpler, and Back and the next/previous keys stay consistent
  with the list the juror was in.
- **The juror's rate on each:** the list's rows are `ImageWithRating` with the viewed juror's vote
  already (as the gallery tile has); shown as the tiles' rate chip (stars, or a yes/no mark;
  none when unrated).
- **No monument id** (empty or blank, `RoundList.validMonument` fails; every WLE file; any
  contest without monuments) **or no sibling** in the round: an **empty section with a note**
  (decided, Q6), no fallback to Next files, so Same monument needs no fallback rows.
- **Cost:** `monumentLine` already counts the monument's files for the info card (one count, a
  second only when that is 1). The strip adds one `LIMIT 6` read of the same list. Index: the
  juror gallery index with `monument_id` (`vote_jury_round_ratedesc_mon_page_idx` on 10.8+,
  `vote_jury_round_rate_mon_page_idx` on 10.6: `jury_id, round_id, rate, monument_id, page_id,
  voted_at`, V48/V85/V99); `vote_round_monument_id_idx` covers the round plus monument side
  (V82 rename, kept by V100) and `images` has V33's monument index. Needs an `EXPLAIN` on a
  production-sized copy before building (open question 5); expected a few ms, none when off.
- **Authorization:** only through `RoundList` for the viewed juror (`viewAs`): a juror gets their
  own rates; an organizer viewing juror X gets X's (only a juror of the round,
  `User.isRoundJuror`, else 404 as now); `asUserId == 0` shows the files without a per-juror
  rate (the aggregate chip the all-jurors gallery already shows, organizers only). No query takes
  a juror id from the request except through `viewAs`.

### My recent votes

- **Which:** this juror's last 5 votes in this round, by `voted_at DESC` (V97), without the
  current file, each with its rate. This is the "Newest votes first" order
  (`ListOrder` `latest-vote`: `UnratedLast`, then `VoteTime` descending). Alone, `LIMIT 6` would
  list unrated files when the juror has fewer than 5 votes, so the strip combines it with
  `VoteFilter.Rated` (on a binary round, any vote) and ignores the page's own filter and order.
  The query and index are those V99 built for that order (V99 note, gallery page 1: 82 -> 26 ms
  on 3.35M votes; usually single-digit ms), here a `LIMIT 6` first page.
- **Click:** to that file's large view in the same list context (`list.largeUrl`) so the juror can
  change the vote with the stars. Where the list's filter is "unrated only" the link goes to the
  file in the unfiltered list (`withFilter(All)`), as `voteMoved`'s link does.
- **Round open or closed:** a closed round is read-only for jurors (`navigationButtons`
  `readOnly`): the strip still lists the votes as a record and the links open the read-only file.
  It is shown only where the page itself is (`mayViewRound`).
- **Who gets it (decided, Q8):** only a **juror of the round, in their own view**. Admins and
  organizers (also in the all-jurors view, `asUserId == 0`, and when viewing as another juror) do
  not get the option at all: it is absent from their menu, not shown empty with a note. A juror
  with no vote yet sees an empty section with a note.
- **Privacy:** the query is `jury_id` = the signed-in juror, never a request parameter, so a juror
  never sees other jurors' votes.

## 3. Per option summary

| | Next files | Same monument | My recent votes | Off |
|---|---|---|---|---|
| Extra SQL | +3 rows (in the window query) + comment counts | its `LIMIT 6` monument-list read, no fallback rows | its `LIMIT 6` vote-time read, no fallback rows | none |
| Index | list order index | gallery index with `monument_id` | gallery index with `voted_at` (V99) | none |
| Caching | none needed | none | none | none |
| Images | 5 | up to 5 | up to 5 | 0 |
| Keyboard | none | a key to jump to the next file of the monument (later, not now) | none | none |
| No script | plain links | plain links | plain links | n/a |
| Rate shown | no (today) | the viewed juror's | the viewed juror's | n/a |

- **Mobile:** the side column stacks below the file on a phone, and up to 5 thumbnails cost data.
  On a narrow screen the section stays collapsed and the images get `loading="lazy"`, so a closed
  section loads nothing. Whether to hide the strip on phones outright is open question 7.
- **Gallery tiles plan:** G-1a moves the strip to `stripSrcSet` (250/330 px sources) under the
  standard scheme: the displayed 150 x 120 box stays and only the srcset moves after G-1a. Until
  then it is today's `srcSet`. No new displayed size; the window-size cookie does not affect the strip (its
  count is fixed). The new options may reuse the tile snippet with the rate chip once the own
  tiles exist; until then they use `thumbsBar`'s markup plus the existing rate chips.
- **htmx (if adopted):** the strip is a self-contained section, so a fragment (`hx-get` on first
  open) fits: a closed strip costs nothing. It needs no `hx-on`, `js:` values or trigger
  filters, so it is compatible with the CSP rule of the UI approach investigation. Not needed for a first version: `loading="lazy"` gets
  most of that without script.

## 4. Where the choice lives, and the default

- **Per account** (recommended): one column, like #129's coarse screen profile (values `off`,
  `next`, `monument`, `recent`; a migration at the next free number). It follows the juror across
  contests and devices. The setting is the **viewer's own**; the data is the viewed juror's (an
  organizer viewing as juror X uses the organizer's setting with X's data). Any signed-in role may
  POST it, for their own account only. Changed from a small menu in the strip's section header (a POST with the
  CSRF token, like contest switching), where the juror sees it.
- **Not per round:** it would differ in every round for no reason a juror can see. **Not only a
  session toggle:** it is forgotten on sign-out. The per-browser open/closed state
  (large-sections.js) stays and is orthogonal: the option decides what, the section state
  whether it is open.
- **No contest-level default** (decided, Q4): per account only.
- **Default (recommended): Off**, the section header still present with a one-click "Show next
  files". Reasons: the strip's value is doubtful by the maintainer's own account, it is the only
  part of the large view that loads five images a juror may never see, and the next-image prefetch
  (section 6) covers its speed benefit. **This depends on the separate real-app timing:** if the
  strip's cost is a few ms and its images cost nothing noticeable, Next files stays the default
  for continuity and the work is only the switch; if it costs visibly, Off.

## 5. Steps, effort, tests

| # | Step | Effort |
|---|---|---|
| S1 | `loading="lazy"`, `decoding="async"` and `width`/`height` on the strip's images in both templates. This is the strip's part of F1 of the modern web guidance plan (#134, which cites `contestFile.scala.html:91`); **whichever PR lands first does it** and the other drops it (proposal: #134's F1 PR) | 1 h |
| S2 | Account column, `User` field, the header menu and its POST (CSRF, denial paths) | 0.5 day |
| S3 | One strip function for an option, used by both templates; Next files = today's `following`. `RoundList.around`'s 6-row widening, `hood.following` and `commentCounts` are read only for Next (Monument and Recent have no fallback, so Off, Monument and Recent skip them) | 0.5-1 day |
| S4 | Same monument via `withMonument`, rate chips, the empty-with-note section | 0.5 day |
| S5 | My recent votes via the `latest-vote` order, closed-round and `asUserId == 0` rules | 0.5 day |
| S6 | Message keys, one snippet for both templates, a browser test of the section | 0.5 day |

About 3-4 days. S1 can ship alone, first.

**Tests**
- Per option for a juror: Next equals today's `Neighbourhood.following`; monument: only this
  round's files of the monument, the current file excluded, the juror's rates; recent: newest
  first, capped, current excluded.
- **Negative authorization:** two jurors vote on the same monument's files in one round: juror A's
  "recent" and "same monument" never show juror B's rates or files B alone voted on; an organizer
  viewing A sees A's, not B's; a viewed user who is not a juror of the round gives 404;
  `asUserId == 0` shows no per-juror rate (recent: the option is absent for organizers and in the all-jurors view); a juror passing another
  `asUserId` gets what `GalleryService.viewAs` decides today; a file of another round is never
  listed; a round still `distributing` lists nothing for jurors.
- No monument id, WLE, and a monument with one file give the empty section with its note; "recent" is not offered to admins and organizers (in any view), and a juror's own view lists only their votes.
- The setting's POST refuses a bad value and a missing CSRF token, tested with the filter on
  (`CsrfTestApp.withCsrfApp`); it changes only the caller's own account.
- An organizer viewing a user who is not a round juror gets 404 in every option.
- A template spec for the lazy attributes; `LargeViewFixturesSpec` and `LargeViewSideSpec`
  updated, since the side column changes.
- An `EXPLAIN` of the two new statements on a production-sized copy, recorded in the PR.

**Message keys (en, ru, uk, fr)**, listed in the PR for the maintainer's review of ru/uk and a
French speaker's of fr: `large.strip.title`, `large.strip.option.off`, `large.strip.option.next`,
`large.strip.option.monument`, `large.strip.option.recent`, `large.strip.monument.none` (no
monument id or no sibling), `large.strip.recent.none`, `large.strip.empty`,
`large.strip.change`; `large.next.files` stays as the Next label.

## 6. The prefetch of the next file's main image (separate, keep)

Nothing prefetches the next file's large image today (the only `new Image()` is in the Unite
Gallery plugin). The main image is served from Wikimedia directly with a `srcset`/`sizes` and a
proxy fallback (`large.scala.html`, kept by the tiles plan section 3.5), so the prefetch must ask
for the same candidate the page will pick: `<link rel="preload" as="image" imagesrcset=... imagesizes=...>`
(or an `Image()` with the same `srcset` and `sizes`), built from `Global.largeImage(next)` for
`Neighbourhood.next`. A plain `href` prefetch could fetch a different size and be wasted. It is
independent of the strip. Rules: only the file the vote or down key goes to, none when it is a
video, none where the viewer cannot vote. About 0.5 day with a view spec for the tag. The UI
approach investigation's **mock prototype** (not the real page) ran the vote and the move in about
95 ms with the next image prefetched; the real gain is to be taken from the real-app timing
measurement (`docs/performance/2026-11-24-large-view-timing.md`, in progress; link here once it
exists). Its own PR.

## 7. Decisions of the maintainer (2026-11-24) and open questions

Decided:
- **Q2:** all four options (Off, Next, Same monument, My recent votes).
- **Q3:** 5 thumbnails for all options.
- **Q4:** per account only (no contest-level default; S7 dropped).
- **Q5:** `EXPLAIN` on the production copy first.
- **Q6:** Same monument with no monument id or no sibling: an empty section with a note, no
  fallback to Next.
- **Q7:** phones: keep the strip, closed and lazy.
- **Q8 (changed):** My recent votes is offered only to jurors of the round in their own view;
  admins and organizers never get the option (not empty with a note).
- **Q9:** closed rounds: the recent list is read-only.
- **Conflict of interest (CY-2, #140):** a juror of a round sees only their own votes there,
  whatever their role; the strip must respect it: Same monument shows only the viewer's own
  ratings in rounds they judge (the viewer is then the viewed juror, as `viewAs` returns).

Provisional, to be re-confirmed after the clean timing (#142, being re-measured after a confound
was found: the preload gain is about 0 when measured cleanly; strip off or lazy still helps on
slow links; production already caches CSS and JS for a year):
- **Q1:** default Off.
- **Q10:** the cheap speed-ups first (lazy attributes S1, the prefetch as its own PR); the
  prefetch's worth in particular depends on the clean measurement.
