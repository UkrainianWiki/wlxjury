# Binary rounds: all the jurors' votes together

A yes/no (binary) round stores `selection.rate` -1 (rejected), 0 (unrated), 1 (selected). Since
79f5c51e the by-rate gallery for all the jurors (`asUserId == 0`) shows the file's approvals
("✓ n", `binaryVote.scala.html`), counted by `sum(s.rate > 0) as approvals`
(`ImageDbNew.scala:86`). This plan lists what is still wrong or unclear in the aggregate views.
It is from reading the code and the fixture DB (round 1: 20 jurors, 38,366 files, each file with
10 votes: 192,097 selected, 191,561 rejected, 2 unrated).

## Summary

1. **The same round has two orders, and neither matches the number shown everywhere.** The
   by-rate gallery, `/query` and the large view's neighbours sort by the rate sum
   (approvals − rejections). The file list and CSV sort and rank by approvals. The gallery shows
   approvals.
2. **A top-N next round from a binary round uses the rate sum, unless "selected by at least N
   jurors" is also set.** The same N can pick different files depending on an unrelated field.
   "Selected by at least 0 jurors" picks only files that some juror left unrated.
3. **Filters count votes only among the filtered rows.** `filter=rejected` and `filter=unrated`
   show "✓ 0" on every file. `filter=selected` changes the order.
4. **The all-jurors file list loads and renders the whole round in one request.** On the fixture
   that is 383,660 vote rows, 38,366 table rows × 20 juror columns. On 2026-11-01 at 09:09 it ran
   the 1 GB fixture server out of heap and killed it.
5. **Rejections and unrated votes can't be seen in the aggregate views.** The large view lists
   only the jurors who approved. The statistics footer's "unrated" counts files nobody approved.
   The juror columns show bare 1/-1/0.
6. Smaller: the by-rate gallery's rank is always empty. The all-jurors tile gallery shows no
   votes. `rateSum` means two things.

## 1. Order and rank: rate sum vs approvals

- **By-rate gallery, `/query`, large view.** They use `ListOrder.ByRate`, `rate desc, s.monument_id, s.page_id`
  (`RoundList.scala:81`). In the grouped query `rate` is `sum(s.rate)` (`ImageDbNew.scala:86`).
  The large view's position (`RoundList.rankPosition`, `:179-183`) uses `ROW_NUMBER()` over the
  same order (`ImageDbNew.scala:78`, which has no `approvals` column).
- **File list and CSV.** `groupWithDetails` loads every vote row. It sorts files by
  `-selection.filter(_ > 0).sum`, which is approvals (`ImageDbNew.scala:383`). It ranks them with
  `rankImages` → `rateSum` = `Σ max(rate, 0)` (`ImageWithRating.scala:56-57, 103-108`), also
  approvals. Ties come out in `groupBy` map order (`:373-383`). The order of tied files is not
  fixed and can differ between two loads of the same list.
- **Example.** Voting is unfinished. File A: 3 ✓, 2 ✗, 5 unrated (sum 1). File B: 2 ✓, 0 ✗,
  8 unrated (sum 2). The gallery puts B ("✓ 2") above A ("✓ 3"). The file list ranks A 1, B 2.
  The fixture can't show this. Every file there has all 10 votes, so sum = 2·approvals − 10, and
  both orders agree (apart from ties). It happens with unfinished voting or with files that have
  different numbers of jurors.
- **Rank ranges.** `ImageWithRating.rank` gives tied files a range ("4-9"). By approvals alone,
  the fixture's 9,496 files with 5 ✓ share one range "14517-24012". Using rejections as a
  tie-break gives smaller ranges only where files have different numbers of votes.
- **Fix.** Use one rule for a binary round's all-jurors lists: `approvals desc, rejections asc`
  (sum desc gives the same order), then `s.monument_id, s.page_id`. Add
  `sum(s.rate > 0) as approvals` to the count/ranked columns (`ImageDbNew.scala:78`). Give
  `RoundList.query` a grouped binary order: either a `ListOrder` per round or the columns chosen
  in `RoundList.query` when `grouped && round.isBinary`. In `groupedWithDetails`, sort by
  (−approvals, rejections, page id). Make `rankImages` take the binary round's key
  (approvals, rejections) instead of `rateSum`.
- **Tests.** `GalleryOrderSpec` / `RoundListDbSpec`: A above B in the gallery, `/query` and
  `rankPosition`. `RoundFileListSpec` / `CsvSpec`: same order, fixed order for ties.
  `ImageWithRatingSpec`-style unit test for the ranks.

## 2. Next-round selection from a binary round

- `rated = prevSelectedBy.map(_ > 0)` (`RoundDistributionHandler.scala:178`) limits the vote rows
  read (`ratedCond`, `:611`). `DistributionSource.rank` (`:713-750`) orders by
  `SUM(GREATEST(rs,0)) / assignedJurors`, where `rs` = `SUM(s.rate)` over those rows (`:730-734`).
  - Top-N **without** "selected by at least": all rows → rank by `max(approvals − rejections, 0)`.
    Every file with as many rejections as approvals or more scores 0. The fixture's 23,850 files with ✓ ≤ 5 all
    tie and are ordered by page id.
  - Top-N **with** "selected by at least 1": only `rate > 0` rows → rank by approvals.
  - Example, N = 1, files A and B above. Without the field it picks B. With it set to 1 it picks
    A, the file the file list ranks first.
- "Selected by at least N" (N ≥ 1) is correct. The rows are `rate > 0`, so `rateSum` = approvals
  and `SelectedAtLeast` (`ImageWithRatingSeqFilter.scala:104`) compares the number the gallery
  shows.
- The form offers "0" (`round/edit/imageFiltering.scala.html:34-39`). `Some(0)` gives
  `rated = Some(false)`, which reads only `s.rate = 0` rows. The next round gets only files with
  at least one unrated vote (2 files on the fixture), not "all files". Same in
  `DistributeImages.scala:167` (`byRoundMerged(rated = selectedAtLeast.map(_ > 0))`).
- The in-memory `SelectTopByRating` (`ImageWithRatingSeqFilter.scala:96`) and `totalRate` for a
  binary round (`ImageWithRating.scala:48-54`: `max(sum, 0) / assigned jurors`) have the same
  rate-sum rule.
- **Fix.** For a binary previous round, rank by `SUM(s.rate > 0) DESC, SUM(s.rate < 0) ASC,
  page_id`, computed in the inner query whatever `rated` is. Do the same in `SelectTopByRating`
  (approvals, then rejections). Treat "0" as no filter, or remove "0" from the options.
- **Tests.** `RoundDistributionJobSpec` / `RoundRedistributionFiltersSpec`: top-1 picks A with
  and without the "selected by" field. `DistributeImagesSpec`: the same on the in-memory path.
  "0" keeps all the files.

## 3. Filters and the aggregate

- The vote filter is applied to the rows (`ImageDbNew.scala:307-318`), so the grouped `sum` and
  `approvals` count only the matching rows.
  - `filter=rejected`: files with ≥ 1 rejection, each shown "✓ 0", fewest rejections first.
  - `filter=unrated`: "✓ 0", in monument/page order.
  - `filter=selected`: the real approvals, ordered by approvals, unlike All (issue 1).
  - In the file list, `filter=rejected` leaves every juror column blank except the -1s. Blank
    there also means "not assigned".
- The region menu's counts (`regionCounts`, `MAX(hit)` per file) already count files that have
  at least one matching vote, the file-level meaning.
- **Fix.** For a grouped query, filter files rather than rows: aggregate over all the file's rows
  and filter with `HAVING sum(s.rate = -1) > 0` (or `= 1`, `= 0`). Keep `withPageId`. Check the
  plan of the 38k-file round, since `HAVING` gives up the index range on `rate`.
- **Tests.** `ImageDbNewDbSpec`: A with `filter=rejected` shows 3 approvals and is listed.
  `GalleryRegionsSpec`: counts unchanged.

## 4. The all-jurors file list renders the whole round

- **Request.** `GET /filelist/round/1/user/0/page/1` (module filelist, round 1, user 0 = all
  jurors, page 1, region all, no filter, format wiki). The total link on the statistics page
  (`roundStatTable.scala.html`, list icon) opens it.
- `RoundList.files` ignores the pager for a file list or CSV (`RoundList.scala:164`:
  `if (withDetails) query.list()`). `fileList.scala.html` renders every file with a cell per
  juror (`GalleryController.scala:194-198`). The fixture: 383,660 rows read, 38,366 rows × 24 cells
  built in one Twirl buffer. The server died with `OutOfMemoryError` in `BufferedContent.body`
  at `GalleryController.scala:198`. CSV (`Csv.exportRates`, `GalleryController.csvStream:297`)
  also builds every row before streaming.
- **Fix.** Page the file list like the gallery (it already takes `page`). Load the page's files
  grouped, then their votes for those page ids only. Stream the CSV in chunks of files.
- **Tests.** `RoundFileListSpec`: page 2 lists files 1001-2000 by the issue-1 order. `CsvSpec`:
  the streamed CSV equals the old output for a small round.

## 5. Rejections and unrated votes not visible

- **Large view, all jurors.** `SelectionJdbc.byRoundAndImageWithJury` (`SelectionJdbc.scala:112-127`)
  keeps only `rate > 0`. `juryStat` lists only the jurors who approved. The cross that
  79f5c51e added to `showRate` never shows, and rejections and unrated votes are not shown. No
  stars or number are shown for binary (`navigationButtons.scala.html:66`); `score`
  (`large.scala.html:18`) is unused.
- **Statistics footer** (`roundStatTable.scala.html:71-124`). "Selected" = files with ≥ 1
  approval (`Round.selectedImageCount`). "Unrated" = total − that, which is files nobody approved:
  the fixture's 40 files rejected by all 10 jurors are counted as "unrated". "Rejected" is empty.
  The per-juror rows are right (`roundUserStat`).
- **File list / CSV juror columns** (`fileList.scala.html:106`, `Csv.scala:36`) show `1`, `-1`,
  `0`, or blank when the juror wasn't assigned. "Overall selected" is approvals, with no
  rejections count.
- **Fix.** Show the jurors who rejected the file too (✗) and the unrated ones (or "n unrated"), in
  that order, under the approvers. In the footer, count files with ≥ 1 approval, files with no
  vote yet, and files rejected by all (or relabel the column "not selected"). In the file list,
  add "✗ n" and show the juror columns as ✓/✗/· (CSV: `1`/`-1`/`0` with a legend row, or
  `yes`/`no`/empty). Optionally "✓ 3 ✗ 2" in the gallery (decision 2).
- **Tests.** `LargeViewSideSpec`: a rejecting juror is listed with a cross. `FileListViewSpec`:
  the column glyphs. A stat-table view test for the footer.

## 6. Smaller issues

- `rank` / `rank2` are never set (`ImageWithRating.scala:13-14`; only `rankStr` reads them), so
  `galleryByRate`'s `<b>@file.rankStr</b>` is always empty. The fixture server's page showed
  `<b></b>` on all 15 files. Either fill it from the page offset with the issue-1 ranks, or drop it.
- The all-jurors tile gallery (`GalleryController.scala:185`, `module=gallery`) shows nothing
  about votes. `data-rate` holds the rate sum and is unused (`jury` is false). Draw "✓ n" on the
  tile or don't offer the tile view for user 0.
- `rateSum` is `max(sum, 0)` for a grouped row but approvals for a detailed one. After issue 1,
  give binary code `approvals` / `rejections` and keep `rateSum` for rated rounds.
- The navbar tabs for all jurors are the viewer's own counts (labelled as such). `voteMoved`
  names selected/rejected/unrated. Both fine.

## Decisions (the user's, 2026-11-01)

1. Ordering: selectable, not one fixed rule. The orders on offer for a yes/no round:
   approvals then fewest rejections; approvals − rejections; approvals only (ties always in a
   stable id order). The galleries, file list and CSV take the order from a sort control
   (remembered like the gallery's sort today); the round form's top-N from a previous yes/no
   round takes it from a field of its own, stored with the round, so a distribution is
   reproducible and shows which rule picked the files. Default for both: approvals then
   fewest rejections. A round from before V72 (no order stored) keeps the old rate-sum rule,
   shown on its form as "Legacy rule"; a new round always stores its order (a72ff4ea).
2. Show rejections: "✓ 3 ✗ 2" in the gallery and file list, with a screen-reader text
   ("3 approvals, 2 rejections").
3. Filters pick which files are listed; the counts and the order use all of a file's votes
   (`HAVING` on the per-file aggregate).
4. "Selected by at least N jurors": the filter can be skipped, with explicit wording in the
   form (e.g. "any number of approvals (no filter)") stored as NULL; 0 no longer means "has
   an unrated vote". Migrate stored 0 values to NULL only after checking existing rounds use
   it as "no filter" (a Flyway migration; Flyway stays 9.16.3).
   Form part done (1a2564a9): no plain 0 among the choices, the empty one "Any number of
   approvals (no filter)" (`selected.by.any.number`, stored as NULL), a stored 0 shown only for
   a round that has it, as "0 (legacy: files with an unrated vote)", selected, so a resubmit
   keeps it; a stored 0 behaves as before. The 0 → NULL migration still waits for the
   production check.
5. File list and CSV for all jurors: paged (the CSV streamed), as proposed.

## Order of work

1. Page the all-jurors file list and stream the CSV (issue 4). `RoundFileListSpec`, `CsvSpec`,
   `FileListViewSpec`. **Done (5fc1a909).**
2. One binary order: SQL order and `ROW_NUMBER`, `groupedWithDetails`, `rankImages`.
   `GalleryOrderSpec`, `RoundListDbSpec`, `RoundListSpec`, `RoundFileListSpec`, `CsvSpec`,
   `LargeViewControllerSpec`. **Done (6f435660).**
3. Top-N and "selected by" for a binary previous round. `RoundDistributionJobSpec`,
   `RoundRedistributionFiltersSpec`, `DistributeImagesSpec`, `DistributeImagesBatchSpec`,
   `ImageWithRatingSeqFilterSpec`. **Done (3001d72d).**
0. Old top-N rounds keep their rule (`rounds.top_binary_order` NULL). **Done (a72ff4ea).**
4. File-level vote filters. `ImageDbNewDbSpec`, `GalleryRegionsSpec`, `RoundListDbSpec`.
   **Done (19e6078c).**
5. Show rejections and unrated votes: large view, stat footer, file list columns.
   `LargeViewSideSpec`, `FileListViewSpec`, `GalleryByRateViewSpec`. **Done (e3d8c7d1).**
6. Rank line, tile view, `rateSum` split. `GalleryByRateViewSpec`, `GalleryTileStarsViewSpec`.
   **Done (95783736).**

## Done: steps 1-3 (2026-11-01)

- **Step 1.** File list: 1000 files to a page (`RoundList.FileListPageSize`), the navbar's pager.
  A page is the grouped query's page ids (`SelectionQuery.list` with `groupWithDetails`), then
  their images and votes by page id (`withVotes`, votes in `s.id` order); `groupedWithDetails`
  is gone. Ranks among the whole list from its files per rank key (`keyCounts`, one grouped
  count; `ImageWithRating.Ranks`). CSV (`Csv.rateRows`): the list's page ids read at once (38k
  longs), then votes 1000 files at a time, `mapAsync(1)` on the blocking pool; header and
  columns unchanged. File list and CSV are by rate whatever the list's order (`sort=file`
  ignored there, as before). Tests in `controllers.AllJurorsListSpec` (paging, streamed CSV
  equals `exportRates` of the whole list): `RoundFileListSpec` is "Add files", not this list.
- **Step 2.** `org.intracer.wmua.BinaryOrder`: `approvals` (default), `difference`,
  `approvals-only`; ties by page id everywhere (not monument then page: the distribution
  already tied by page id). Columns are aggregate expressions (`sum(s.rate > 0)`, ...), not
  aliases, so `ORDER BY` and `ROW_NUMBER` agree. `ListOrder.Binary(order)`, URL `sort=`; only
  for `user/0` of a yes/no round (`RoundList.aggregateBinary`), else by rate. Remembered in
  cookie `binarySort`; `sort=rate` on such a list (the statistics page's links) opens the
  remembered order. Navbar menu in the organisers' block only. Grouped rows read
  `rejections`; `rankImages` / `RankKey` take the order. Vote filters still count rows (step 4).
- **Step 3.** V72 `rounds.top_binary_order VARCHAR(16) NULL` (NULL: the default). Stored only
  with a top-N; the form's select sits in `#selectFilters` (yes/no previous rounds) with the
  top-N, so an existing round's page shows its rule; the job's `settings` record it.
  `DistributionSource.rank` for a yes/no previous round (`binaryPage`): all the previous
  rounds' rows together (approvals and rejections summed across rounds, not per round),
  ordered by the round's order, then page id; `rated` kept as `HAVING` (an approval; a stored
  0: an unrated vote), so the candidates are as before and only the order changed. A
  candidate's rate is its approvals, so "selected by at least" compares them. In memory:
  `DistributeImages.binaryCandidates`, `SelectTopByRating` by the order. `funGenerators` now
  puts the top-N last: it ran in hash-map order, so the in-memory top-N could rank before
  "selected by" filtered. Existing rounds have NULL: a retry or "Distribute new files" of an
  old top-N from a yes/no round uses the new default, not the old rate-sum rule (no stored
  value names it). Stored 0 not migrated (decision 4). Tests: `RoundRedistributionFiltersSpec`
  (top-1 per order, with and without "selected by", streamed equals in-memory),
  `ImageWithRatingSeqFilterSpec`, `TopBinaryOrderChoiceSpec`, `db.TopBinaryOrderMigrationSpec`.
  Superseded by task 0 below: NULL is now the legacy rule.

## Done: task 0 and steps 4-6 (2026-11-01)

- **Task 0, old top-N rounds (a72ff4ea).** Before 3001d72d a top-N from a yes/no round was
  ranked by `DistributionSource.ratedPage`: per previous round `SUM(s.rate)` with a clamp at 0
  (`GREATEST(rs, 0)`), summed over the rounds, over the assigned jurors, then that sum, the raw
  sum and page id; with "selected by at least N ≥ 1" only `rate > 0` rows, so the approvals.
  None of the three orders is that (the clamp, and the field changing it), so it is a legacy
  value: `rounds.top_binary_order` NULL = the legacy rule (`BinaryOrder.LegacyParam`
  "legacy", `Round.topOrderParam`), no migration, V72 unchanged. The streamed distribution
  (`binaryOrder` None → `ratedPage`) and the in-memory one (`SelectTopByRating` without an
  order → `totalRate`) are the code of before. A new round's submission always stores an
  order (`Round.withExplicitTopOrder` in `RoundService.submit`, the default unless chosen;
  a missing or "legacy" form value is the default for it); the form preselects the default
  for a new round (`RoundController.editRound`) and offers "Legacy rule: ..." only for a
  round that has it, selected. An existing round's form never changes the stored order
  (`Round.updateRound` saves the name only). The job settings record "legacy". Tests:
  `RoundRedistributionFiltersSpec` (an old round filled by "Distribute new files": top-1 by
  the rate sum, by the approvals with "selected by", ties by page id; streamed equals in
  memory), `RoundEditPageSpec` (new form preselects and stores the default, also without the
  field; an old round shows the legacy rule), `TopBinaryOrderChoiceSpec`.
- **Step 4, file-level filters (19e6078c).** `SelectionQuery.filesByVotes` (set by
  `RoundList.query` for `user/0` only): the vote filter goes to `HAVING sum(s.rate = r) > 0`
  (or `> 0` / `= 0` for rated / unrated), with `withPageId` as `OR s.page_id = ...` there;
  `where()` leaves it out, so the grouped columns, order, `ROW_NUMBER`, `keyCounts` (ranks)
  and `withVotes` (file list, CSV: all the file's votes) use all of a file's votes. The count
  keeps the row filter in `COUNT(DISTINCT s.page_id)` (the same number). A juror's lists and
  `byRoundMerged` (the distribution's reads, `filesByVotes` false) still filter rows, so
  "selected by at least" and the legacy top-N read what they did. Region counts were already
  per file (`MAX(hit)`). A file list's `count()` now counts files with a region too (it was
  the vote rows). Query cost: the filtered list scans the round's rows as "all" does (the
  `rate` index range is given up); not measured on the 38k fixture.
- **Step 5, rejections shown (e3d8c7d1).** `binaryTally` ("✓ 3 ✗ 2", screen-reader and title
  text `binary.tally` "3 approvals, 2 rejections", a choice format in en/fr, "Схвалень: 3,
  відхилень: 2" style in uk/ru) in the by-rate gallery and the file list (column
  `overall.votes`); `binaryVote` lost its approvals mode and has `unrated` (a dot). File list
  juror columns: ✓/✗/· (blank: not assigned) under a legend (`filelist.legend.not.assigned`).
  CSV: 1/-1/0 kept, columns unchanged, one legend row last (English, as the header).
  `byRoundAndImageWithJury`: every vote, approvals (by rate) then rejections then unrated, by
  name; the vote list stays org-only (`canViewOrgInfo`), tested in `OwnVotesSpec`. Footer:
  `Round.imageCountsByRate` (one grouped scan): files with ≥ 1 approval, ≥ 1 unrated vote,
  ≥ 1 rejection, each with its by-rate and file list links (the filtered lists' totals, so
  they overlap and need not add up to the total; noted under "Total",
  `round.stat.total.note`), not "files with no vote yet" / "rejected by all".
- **Step 6 (95783736).** By-rate gallery ranks: `RoundList.ranks` (one `keyCounts` query, as
  the file list) only in the by-rate view, not a rated round's plain gallery; `rank`, `rank2`
  and `rankStr` removed. Tile view of `user/0`: `data-approvals/-rejections/-tally` on the
  items, `addTally` draws a badge at the tile's foot. `rateSum`: binary code already reads
  `approvals`/`rejections` (rank keys, `rateString`, the tallies); `rateSum` and `totalRate`
  are documented as the rated rounds' and the legacy top-N's; `SelectedAtLeast` keeps
  `rateSum` (the rows it sees are `rate > 0` or carry the approvals, and a rated round's
  stored "selected by" keeps its meaning).
- **Open item verified.** A rated round's all-jurors large view: `ROW_NUMBER() OVER (ORDER BY
  rate ...)` orders by the `sum(s.rate)` alias, not a row's `s.rate`; the position, previous
  and next match the by-rate gallery (`RoundListDbSpec`, files whose first vote row orders
  otherwise). No change.

## Review (a9f562a4..bee7b6bb)

An independent review of `a9f562a4..443ac143` found only low issues:

1. **V72's comment** called NULL the default; it is the legacy rule of a round from before
   V72 (a72ff4ea). Reworded (2dab5252). V72 is only on this branch and no spec pins its
   checksum; a dev DB that already applied it needs `flyway repair` or a rebuild.
2. **CSV ranks vs votes**: the rank counts and page ids were two reads, the votes later, so a
   vote between them could rank rows out of order. One read now gives each file's page id
   and rank-key values (`SelectionQuery.pageKeys`), and the ranks come from it; a row's votes
   are those when its chunk is read, with no transaction held over the stream (ae47ad52).
3. **A CSV counted the list** for a pager it doesn't use: the counts, pager, region menu and
   tabs are lazy and a CSV reads none of them (f30c2827).
4. **Measure the all-jurors scans**: not done. The fixture server runs 9dcbd9e8 (before the
   aggregate steps), and restarting it on this branch was refused by the session's
   permission check, so there is no measurement yet (see "Left").
5. **The tiles' footer note** read as "files with every kind of vote": now "Each column:
   files with at least one such vote (a file can be in several)", in en/fr/uk/ru (5bf1d4f7).
6. **An order where it plays no part**: a new top-N stored 'approvals' and its job recorded
   an order over a rated previous round too. Both now only with a yes/no previous round
   (`withExplicitTopOrder(prevBinary)`, the job's `topBinaryOrder`), else NULL and nothing
   recorded; the round form shows the order, and the legacy rule, only until previous rounds
   are chosen or when one is yes/no, so an old top-N over a rated round shows none
   (dbe3a4b8).
7. **Ranks out of order**: `user/0` by rate with `sort=file` showed binary-order ranks on a
   list in file order; no ranks in file order now (bee7b6bb).

## Left

- Migrate stored "selected by at least 0" to NULL (decision 4) once production rounds are
  checked to use it as "no filter".
- Measure the all-jurors scans on the 38k-file fixture (review item 4), on a server running
  this branch: `/byrate/round/1/user/0/page/1` unfiltered and with `filter=selected`,
  `rejected`, `unrated`; `/filelist/round/1/user/0/page/1`; `/gallery/round/1/user/0/page/1`
  (1 warm-up, 5 timed each, median and min/max). `EXPLAIN` a filtered page more than about
  2x the unfiltered one, or any page over about 2 s.

## Follow-up: more ranking methods (STV, normalised ratings)

The user asked whether the order can grow to other methods, for rated rounds too, e.g. the
single transferable vote. `BinaryOrder` is a closed list of per-file scores, each an SQL
`ORDER BY` over a file's grouped votes plus the same values in Scala for the in-memory
distribution; the galleries' paging, the large view's `ROW_NUMBER` position, the file list,
CSV and top-N rely on that. Two kinds of method:

1. **A score per file** fits as it is: another case with its SQL and a message key. Yes/no:
   approval share (approvals / assigned jurors), a smoothed share. Rated: mean, sum, share of
   jurors rating at least k. **Per-juror normalisation** (each juror's ratings as z-scores or
   percentiles before averaging, against strict and lenient jurors) is still one score per
   file, written with window functions over each juror's votes. Rated rounds need the type
   generalised to a round order with the options each kind allows (`top_binary_order` renamed
   or a new column, by a new migration), and their by-rate order (today a fixed `rate` sort)
   taking it.
2. **Methods over ballots**, not scores: STV, Schulze/Condorcet, Borda, Kemeny. A file's result
   depends on the other files (STV transfers surpluses and eliminated candidates' votes), so
   it can't be an `ORDER BY`. STV elects N (the seats); the order of election and elimination
   ranks only partly. Ballots would come from each juror's ratings sorted, with frequent ties
   on 1-10 stars (a rule: page id, random, or a variant with equal preferences); a yes/no
   round gives approvals only, which suits approval voting more than STV. Each juror sees
   only the files distributed to them, while these methods assume ballots over all candidates
   (or need a rule for the missing ones).

Design for the second kind: the round's order names a method with its parameters (`stv`,
seats N); a background job (`job` table, `JobScheduler`) reads all the votes, runs the method
in Scala and stores each file's rank, e.g. `round_result(round_id, page_id, rank, method,
params, computed_at)`; the galleries, file list, CSV and top-N order by that rank, keeping
paging and the large view's position cheap. The result is marked stale when votes change and
recomputed on request or when the round stops; never per page view (the fixture round has
~383k votes). The stored string, form field and sort menu stay: a method either gives SQL
columns, as now, or orders by the stored rank. First candidates: per-juror normalisation, then
STV. Decide first: tie rule for ballots, partial ballots, and what an all-jurors gallery shows
for files STV didn't elect.
