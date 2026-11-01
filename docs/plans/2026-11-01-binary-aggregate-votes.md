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
   fewest rejections.
2. Show rejections: "✓ 3 ✗ 2" in the gallery and file list, with a screen-reader text
   ("3 approvals, 2 rejections").
3. Filters pick which files are listed; the counts and the order use all of a file's votes
   (`HAVING` on the per-file aggregate).
4. "Selected by at least N jurors": the filter can be skipped, with explicit wording in the
   form (e.g. "any number of approvals (no filter)") stored as NULL; 0 no longer means "has
   an unrated vote". Migrate stored 0 values to NULL only after checking existing rounds use
   it as "no filter" (a Flyway migration; Flyway stays 9.16.3).
5. File list and CSV for all jurors: paged (the CSV streamed), as proposed.

## Order of work

1. Page the all-jurors file list and stream the CSV (issue 4). `RoundFileListSpec`, `CsvSpec`,
   `FileListViewSpec`.
2. One binary order: SQL order and `ROW_NUMBER`, `groupedWithDetails`, `rankImages`.
   `GalleryOrderSpec`, `RoundListDbSpec`, `RoundListSpec`, `RoundFileListSpec`, `CsvSpec`,
   `LargeViewControllerSpec`.
3. Top-N and "selected by" for a binary previous round. `RoundDistributionJobSpec`,
   `RoundRedistributionFiltersSpec`, `DistributeImagesSpec`, `DistributeImagesBatchSpec`,
   `ImageWithRatingSeqFilterSpec`.
4. File-level vote filters. `ImageDbNewDbSpec`, `GalleryRegionsSpec`, `RoundListDbSpec`.
5. Show rejections and unrated votes: large view, stat footer, file list columns.
   `LargeViewSideSpec`, `FileListViewSpec`, `GalleryByRateViewSpec`.
6. Rank line, tile view, `rateSum` split. `GalleryByRateViewSpec`, `GalleryTileStarsViewSpec`.
