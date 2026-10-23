# Deleted and renamed Commons files in the jury galleries (October 2026)

**Goal:**
1. The jury doesn't show files that were deleted on Commons.
2. It shows renamed files under their new name, with a working thumbnail.

Both break the juror gallery today: one thumbnail that fails to load leaves **no** images shown.

Replaces, for now, `2026-10-22-shared-image-store.md` (kept as background only).

## Why the gallery shows nothing

- The thumbnail URL is built from the stored `images.url` (`Global.legacyThumbUrl`). It is served by `ImageProxyController` from the local cache, else fetched from Commons, else redirected to `upload.wikimedia.org`. A deleted or renamed file isn't in the cache, so the browser ends at a 404.
- unitegallery's justified tiles (`gallery.scala.html` → `ug-theme-tiles`) wait for every thumbnail, then lay them out by aspect ratio. For an image that failed, `onThumbImageLoaded` (`unitegallery.js:2989`) stores `thumbRatioByWidth = 0 / 0 = NaN`. `getJustifiedData` (`:9174`) sums every tile's width into `totalWidth`, so one NaN makes every row and position NaN.
- Nothing in the jury notices deletions: the import job never removes or marks files, and the app never deletes images. Renames are picked up only when an admin runs "Import files" again, and only for files still in the source category. Nobody does that during judging.

## 1. The gallery survives a broken thumbnail (client): done 2026-10-22, not committed

Checked in headless Chrome with the gallery's options and one 404 thumbnail among 9. With the old `unitegallery.js`, every tile stays at 250×200 with an empty `left` (NaN), so all of them stack at the start of the row. With the patch, the layout is justified and the broken tile is a grey placeholder at the stored (or 4:3) ratio. Unrelated and pre-existing: the bundled `ismouseover` plugin calls `.load(e)` on an empty iframe set, which jQuery 3 doesn't support. It throws `a.indexOf is not a function` once at page load, with or without this patch.

- `gallery.scala.html`: add `data-width` / `data-height` to each `<img>`, the thumbnail's size from `images.width/height` (scaled to `gallerySizeY`). unitegallery already reads them when the gallery starts (`unitegallery.js:19878`).
- `unitegallery.js` `onThumbImageLoaded`: when the loaded size is 0 (a load error), keep the ratio from `data-width/height` (4:3 if there is none) instead of NaN. Add a class `wlx-thumb-failed` to the tile (not `ug-thumb-error`, the library's own inner element), and give it a CSS placeholder ("File not available on Commons"). The library already has local patches (`ebfcfef`, `bb0a59a`), so one more is in line with the repo.
- This alone fixes "no images shown", including thumbnails that fail for a moment.

## 2. Detect renames and deletions: a `refresh` mode of `commons_image_import`

- It uses the same job kind and the same per-contest dedupe key, since it writes the contest's files: it never overlaps an import of that contest.
- **Step** (`ImageImportHandler`, cursor = last page id):
  - Read from the DB the next ≤ `maxIdsPerRequest` (500 with `apihighlimits`) page ids of the contest's files: `category_members` of the contest's category ⋈ `images`, `page_id > :cursor AND deleted_at IS NULL`, ordered by `page_id`.
  - Fetch on the fetch lane: `CommonsSource.files(ids, info = true, text = false)`, by page id. A moved file comes back under its **new title with the same page id**; a deleted one comes back with `missing`.
  - Write in one transaction:
    - A changed title, url, page_url, width, height, size, mime or media_type: update **only those columns**, where they differ. That needs a new DAO method: `ImageJdbc.update` would also overwrite the monument id, description and author with the empty values of an info-only fetch.
    - `missing`: mark deleted (section 3).
    - An id the response doesn't mention at all is left alone; only `missing` counts as deleted.
- **Cost:** a 38k-file contest is ~77 requests with `apihighlimits`, a minute or two. No wikitext is fetched.
- After a rename, the proxy and the local cache work unchanged: the page now has the new URL, `findByFilename` finds the new title, and the thumbnail is fetched again under that name. The old cached file is orphaned, which is harmless.

## 3. Hide deleted files: move their votes aside

- **Migration V63:** `images.deleted_at TIMESTAMP(3) NULL`, and `CREATE TABLE selection_deleted LIKE selection` (as V58 did with `selection_orphans`).
- **When a file is marked deleted**, in the same step: `INSERT IGNORE INTO selection_deleted SELECT * FROM selection WHERE page_id IN (…)`, then `DELETE FROM selection WHERE page_id IN (…)`.
- **Why the votes are moved instead of the galleries filtering by `i.deleted_at IS NULL`:** `ImageDbNew`'s tuned paths read `selection` alone: counts on selection, the deferred join that pages selection ids, region stats from `selection.monument_id`. A filter on `images` would put the join back into each of them. With the rows moved, every gallery, count, rank and result query stays as it is.
- **Never `DELETE FROM images`:** `FK_selection_page_id` (V58) and `FK_category_member_page_id` cascade, so the votes would be lost with no archive.
- **New rounds:** `RoundDistributionHandler` reads candidates from `category_members ⋈ images` (around lines 402, 438, 457, 501). Add `i.deleted_at IS NULL` there; that is the job path, not a request path. Distributions from a previous round read `selection`, which is already clean.
- **Only open rounds** (`Round.openCond`: active, being created, or frozen while active) lose a deleted file's votes. Finished rounds and closed contests keep their results; a round not started yet keeps them until it opens: `Round.setActiveGuarded` moves aside the votes of deleted files in a round it opens. `DeletedFiles.markDeleted` locks the open rounds its files have votes in (share mode, in id order) before it marks them, and a round that opens is read `FOR UPDATE` before its votes are checked, so one of the two always sees the other.
- **A file shared by two contests** is deleted on Commons for both: its rows are moved in the open rounds of both.
- **Undeletion:** an import that sees a deleted file again clears `deleted_at` and moves its rows back from `selection_deleted`, in every round (`DeletedFiles.restore`, first in the import's write step). If the file was distributed again to the same juror and round meanwhile, the juror's vote wins: the moved row replaces an unrated new one and gives way to a rated one. Nothing of the restored rounds stays in `selection_deleted`.
- **Rounds being distributed:** a `round_distribution` job's count check counts the round's distinct images across `selection` and `selection_deleted`, so moving votes aside or back never changes it, and `DeletedFiles` moves them in a round being filled at once. The candidate queries skip a file with votes of the round in either table, so the job never adds an image the count already has. A step may read a file as a candidate just before it is marked deleted and add it after its votes were moved: the job's end (verify step, failure or cancel) moves aside the votes of deleted files in the round if it is open.
- **Deleted again:** the rows go to `selection_deleted` with `REPLACE`, so the current votes replace any moved aside before. The rows are locked `FOR UPDATE` before they are copied: a shared lock first would deadlock, on its upgrade, with a juror voting on one of them.
- **Changes while rows are aside:** removing an image from a round or a round's unrated votes, merging rounds, moving an image to another round (`SelectionJdbc.setRound`) and a new monument id (`selection.monument_id`) apply to `selection_deleted` too; a restore drops the rows of a round deleted meanwhile. Merging and `setRound` drop a source vote where the juror has the image in the target round already, in either table.
- **Votes moved into an open round** (merging a finished round into it, `setRound` from one) may be a deleted file's, which the finished round kept: after the move commits, `Round.settleDeletedFiles` moves them aside if the target is open. `markDeleted` reads the rounds from its snapshot, so it misses a move that commits meanwhile; `DeletedFiles.settle`, after it commits, moves aside the votes of the files still deleted in the rounds open then. Whichever commits last sees the other.
- **Locks:** rounds first, in id order (`markDeleted`, a distribution's verify step and end), then the files, all of them in page id order (`ImageJdbc.lockFiles`: the file check's deleted and changed files together, the import's restored and updated ones), as a distribution's chunk locks them for `selection`'s foreign key.
- **Finding the deleted files of a round** (when it opens, a distribution ends, votes are merged in) starts from the few files marked deleted (V64's index on `images.deleted_at`), not from the round's votes.

## 3a. Detecting a deleted or renamed file when a browser asks for its thumbnail

**Implemented on `ukwikidev/missing-commons-files`** (with section 3's migration, V63, and the distribution filter). The code differs from the sketch below as follows:
- `services.imports.CommonsFileCheck` batches the checks on its own thread (200 ms window, one request at a time) and keeps each answer for 1 h (5 min after a failed request).
- The API answers a deleted file `{"pageid": N, "missing": ""}` without a title, which scalawiki's parser drops (checked against the live API), as it drops any page it can't parse. So the ids absent from the answer are asked again without props (`CommonsSource.missingFiles`, reading the JSON itself); only those answered `missing` are deleted, the rest are Unknown.
- A file uploaded again (same title and url, other size) is `Changed`: stored, and its thumbnail fetched for the new size, by the proxy and the download job alike.
- `image_renames.title` is case-sensitive (`utf8mb4_bin`), as Commons titles are after the first letter. The proxy finds a file by its name (case-sensitively too), and only if none by a former one (`ImageJdbc.findForThumbnail`).
- `ImageJdbc.findByFilename` skips deleted files. The proxy still finds one of that name last, so the finished rounds that keep it show its cached thumbnails.
- A file uploaded again under its name (`Changed`) has its cached sizes deleted (`LocalImageCacheService.evict`): under the same url, the old version's would be served and scaled from.
- **The large view** loads its image from `upload.wikimedia.org`, not through the proxy: at Wikimedia's sizes only (`Global.wikimediaSteps`, the only ones it serves), from the one the 1280×1100 box needs to twice that, picked by the browser from `srcset` with `w` widths and `sizes` (`Global.largeImage`). Nothing is fetched or scaled here. If Wikimedia doesn't have it, `onerror` loads it once from the proxy at the same size, which checks Commons: a renamed file's is a redirect to its new name on Wikimedia, a deleted file's a 404, and the page shows a grey box. A proxy request at one of Wikimedia's sizes, or for the file itself, is passed on as Wikimedia sent it, not decoded.
- A refused thumbnail user is looked up again after 5 s, not the minute an allowed one is kept: a juror just added to a contest sees the thumbnails at once.
- The controller and the download job wait up to 5 s for the check; it goes on after, and a later ask gets its answer. Only a 404 from Wikimedia (`ThumbnailNotFound`) triggers it, and only for files with a Commons url.
- Former titles are stored (`image_renames`, V63), by the check and by the import, so a thumbnail under a former name keeps working after a restart and on the other instance.
- The thumbnail proxy serves signed-in users only (a session user who, as `Secured.withAuth` would, has a role in one of their contests, read once a minute per user; 401 otherwise), with `Cache-Control: private`. So only a juror or organizer loading a gallery can trigger a check.
- A cached answer is given again only for the file as stored when it was asked: a rename or new version now stored is then Unchanged, other attributes are asked again, and so is a deletion an import has undone.
- If a renamed or re-uploaded file's thumbnail can't be fetched either, the proxy redirects to it under its new name, no wider than the file now is.
- It is not a job: it needs an answer within the thumbnail request. Section 2's refresh job is still the way to find every deleted file.

**Feasible, and cheap, but it only sees thumbnails that aren't cached.**

- **The app sees the failures.** Thumbnail URLs are relative (`/wikipedia/commons/thumb/…`, `Global.legacyThumbUrl`), and the production vhost proxies `/` to Play (`wlxjury-infra/…/wlxjury-http.conf.j2`). So every thumbnail request reaches `ImageProxyController.serve`. It looks the file up by title (`ImageJdbc.findByFilename`). For a file not in the local cache it calls `LocalImageCacheService.fetchAndCacheAll`, which fails for a deleted or renamed file: `downloadWithRetry` gets a 404 and returns `None`. The controller then redirects to `upload.wikimedia.org`, which also returns 404. That failure branch is the hook.
  - The separate images vhost (`wlxjury-images.conf.j2`, "set `wlxjury.thumbs.host`") would bypass the app. No code reads `wlxjury.thumbs.host` today; if it is ever used, the client fallback below applies.
- **What the hook would do:** ask Commons about the file by **page id** (`CommonsSource.files(Seq(pageId), info = true, text = false)`, with its retries and bot login).
  - **Renamed:** same page id, new title and url. Update the info columns (section 2's DAO method), run `fetchAndCacheAll` again with the updated image, and answer **200 with the new thumbnail**. The gallery being loaded already shows the renamed file; the caption and link update on the next page load.
  - **Deleted:** `missing`. Mark it deleted (section 3) and answer 404; with part 1 the gallery copes.
  - **Exists, same title** (Commons was briefly unreachable, 429, a missing thumbnail size): nothing to change, and answer as today.
  - Wait for the check with a short timeout (~3 s), else fall back to today's redirect. The check runs anyway and the next page load is right.
- **Guarding it:**
  - A gallery page asks for each thumbnail up to 3 times (`src` + 2 `srcset` sizes), and a page has many files. So: single-flight per page id; batch the ids of ~200 ms into one `pageids=` request (≤ 50, or 500 with `apihighlimits`); a cool-down per page id (e.g. 1 h) so a file that keeps failing for another reason is checked once.
  - Only files in the DB whose fetch failed trigger a check, so random URLs cost nothing.
  - Only `missing` by page id means deleted.
- **What it can't see:**
  - Thumbnails already cached on disk (`Ok.sendFile`, also filled ahead of judging by `startDownload` / `startDownloadForRound`) or in the browser's cache (30 days). A file cached before it was deleted keeps showing.
  - So it finds the files that **break** the gallery, i.e. the uncached ones, as they are viewed, but not every deleted file. Section 2's refresh job is still needed to hide all deleted files. The cache download run is another natural hook: a 404 there could queue the same check.
- **Client-side alternative**, only needed if thumbnails ever bypass the app: in part 1's error branch, `navigator.sendBeacon('/images/:pageId/check')` with the tile's `data-pageId`, feeding the same batched check.

## 4. When it runs

- **A button** on the contest files page, "Check files on Commons (renamed / deleted)", that enqueues a refresh. Its status shows through the existing `snippets/jobStatus`, as the imports do.
- **Periodically:** a small ticker in `JobScheduler` that, every `jobs.commonsRefresh.interval` (default 6 h, 0 = off), enqueues a refresh for each contest with an active round. The active dedupe key makes this a no-op while an import or refresh of that contest runs, also across app instances.

## 5. Tests

- `FakeCommonsSource` with a renamed file (same id, new title and url) and a deleted file (`missing`). A refresh:
  - updates the renamed file and leaves its monument id, description and author untouched;
  - sets `deleted_at` on the deleted one and moves its selection rows to `selection_deleted`.
- The juror gallery's list, count and rank no longer include the deleted file. A new round's distribution skips it.
- A second refresh changes nothing. A failure on page *k*, then Retry, gives the same result.
- JS: a gallery page with one bogus thumbnail URL still lays out the other tiles (manual check, or a small Gatling/HTML check).

## Decided: finished rounds keep their votes

Votes for deleted files are moved aside only in open rounds (section 3): a file deleted on Commons later (for example, for copyright) must not change a past contest's results, juror stats or winner lists.
