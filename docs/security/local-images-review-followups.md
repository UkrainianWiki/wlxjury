# Review follow-ups: `ukwikidev/security-local_images`

Findings from the code review of `ukwikidev/security-local_images` against `master` (2026-10-06).
The signup session bug (the session got `username -> password`) is fixed on the branch in `2cbe3b9`, with a test in `LoginControllerSpec`.

#1, #2, #5 and P1 are fixed on `ukwikidev/performance-memory` (`64210da`, `62b644e`, `39d5aef`), so the merge brings them in.

The rest are deferred **until `ukwikidev/performance-memory` is merged**, so they don't conflict with it.
`performance-memory` branched from this branch at `a65e165`. The two branches touch different files (the signup fix vs. rounds and region stats), so the merge is clean. Its status for each
item below was checked at `b46fafd`. Line numbers will still move, so re-check each item against the
merged code before fixing it.

| # | Severity | Area | File | After `performance-memory` |
|---|----------|------|------|----------------------------|
| 1 | High | Authorization | `RoundController.saveRound`, `RoundService.createNewRound` | **Fixed** (`39d5aef`) |
| 2 | High | Round distribution | `RoundService.distributeNewImages` | **Fixed** (`62b644e`) |
| 3 | High | Round distribution | `DistributeImages.scala` | Open. The insert is now chunked, same transaction |
| 4 | High | Performance / DoS | `ImageProxyController.scala` | Open, untouched |
| 5 | Medium | Region stats | `ImageDbNew.byRegionStat` | **Fixed** (`64210da`) |
| 6 | Medium | Data consistency | `ImageService`, `ImageJdbc` | **Fixed** (`9db7c54`, V57) |
| 7 | Medium | Local image cache | `LocalImageCacheService.startDownload` | Open, untouched |
| 8 | Low | Export | `Global.legacyThumbUrl` | Open, untouched |
| 9 | Low | Scripts | `ConnectDb.scala` | Open, untouched |
| P1 | High | Round creation | `RoundService.createNewRound` (duplicate guard) | **Fixed** (`62b644e`) |
| P2 | Low | Region gallery | `ImageDbNew.where`, `Selection.apply(Page, ...)` | New in `performance-memory` |
| P3 | Low | Deployment | `RoundService` locks, `RoundImageCounts` | New in `performance-memory` |

---

## 1. Rounds of another contest can be created, edited, frozen and copied — fixed in `39d5aef`

`saveRound` requires admin rights in the submitted contest, also on the form-error path, and an edited round must belong to that contest. `RoundService.previousRounds` looks previous rounds up in the round's own contest for creation, "Distribute new files" and `newFilesCount`. Tests are in `MutationAuthorizationSpec` and `RoundCreationResubmitSpec`.

`setRound` (start/stop a round) and `setRoundUser` (activate/deactivate a juror in a round) had the same gap: they checked only for the admin role. They now require admin rights in the round's contest (`withAdminRound`), and a malformed form returns 400 instead of throwing.

- **Where:** `RoundController.saveRound`; `RoundService.createNewRound` (around `RoundService.scala:49`
  here, `create` on `performance-memory`).
- **Problem:** `saveRound` checks only `rolePermission(ADMIN_ROLES)`, never that the user may administer
  the submitted `contest` or round `id`. So an admin of contest A can:
  - create a round in contest B (`contest=B`);
  - rename, reactivate and "distribute new files" to any round (`id=<round of B>` →
    `Round.updateRound`, `distributeNewImages`);
  - name another contest's rounds as previous rounds. They are looked up with the contest-agnostic
    `dao.findById`, then deactivated and used as the image source. `EditRound`'s `verifying` check uses
    `findByIds(contestId, ids)`, which silently drops foreign ids, so validation passes.
- **Scenario:** An admin of contest A submits `saveRound` with `contest=A` and
  `previousRound=<active round id of contest B>`. `Round.setActive(B's round, false)` stops contest B's
  live judging, and B's images are copied into A's new round.
- **Fix:** In `saveRound`, require `contestPermission(ADMIN_ROLES, Some(contestId))`. For an edit, also
  require that the stored round's `contestId` matches; `withAdminRound` on `performance-memory` does
  this. Look the previous rounds up scoped to the contest and reject the request if any id isn't found.
  `newFilesCount` (`performance-memory`) also loads previous rounds with `Round.findById`; scope that
  too. Add tests to `MutationAuthorizationSpec` for a foreign contest, a foreign round id and a foreign
  previous round.

## 2. "Distribute new files" can never recover a failed distribution — fixed in `62b644e`

`User.findRoundJurors` falls back to `round_user` when a round has no selection rows. The edit page and `distributeNewImages` use it.

- **Where:** `RoundService.distributeNewImages`, around `RoundService.scala:87`.
- **Problem:** Jurors are picked with `User.findByRoundSelection`, which only finds users who already
  have `selection` rows. If the first distribution failed, its `localTx` rolled back every selection, so
  the round has none.
- **Scenario:** `createNewRound` inserts the round and its `round_user` rows, then `distributeImages`
  throws and its selections are rolled back. `RoundDistributionFailed` tells the admin to retry with
  "Distribute new files". That gets an empty juror list and hits `require(jurors.nonEmpty)`
  ("cannot distribute N images to an empty jury"). `editRound` also shows no jurors for the round.
- **Also:** `editRound` fills the jurors from the same query, so the form has no jurors. `numJurors == 0`
  then makes the "Distribute new files" checkbox read-only, so the retry can't even be submitted from
  the UI.
- **Fix:** Load the round's jurors from `round_user` (`RoundUser`) when the round has no selection rows.
  On `performance-memory`, `findByRoundSelection` deliberately avoids `round_user` because it doesn't
  always match the selection rows of legacy rounds, so use it only as a fallback. Add a test: create a
  round, force the first distribution to fail, then retry. Fix this together with P1.

## 3. Rounds with criteria fail to distribute; criteria rates are outside the transaction

- **Where:** `DistributeImages.scala:51` (`addCriteriaRates`).
- **Problem:**
  - `addCriteriaRates` is called inside `DB.localTx` but doesn't take the session.
    `CriteriaRate.batchInsert` opens its own `DB localTx`, so the docstring's claim that criteria rates
    are written in the same transaction is false.
  - It calls `s.getId` on selections from `newSelection`, which have `id = None`, so it throws
    `NoSuchElementException`.
- **Scenario:** Create a round with `hasCriteria = true`. The exception rolls back every selection, and
  the round fails with `RoundDistributionFailed`. Rounds with criteria can't be created.
- **Fix:** Insert the selections in a way that returns their generated ids (or re-read them inside the
  transaction), then thread the implicit `DBSession` through `addCriteriaRates` and
  `CriteriaRate.batchInsert`. Add a test that distributes to a round with criteria.

## 4. Image proxy usually misses the local cache (CPU and Wikimedia traffic amplification)

- **Where:** `ImageProxyController.scala:65`, `LocalImageCacheService.targetHeights`, `Global.resizeTo`.
- **Problem:** The cache stores widths derived from fixed target **heights**. Most pages build thumbnail
  URLs with the width-and-height `Global.resizeTo(info, X, Y)`, so for landscape images the requested
  width almost never equals a cached width.
- **Scenario:** For a 4000×3000 image the cache stores 333px (250 high), but `galleryByRate`,
  `thumbsBar` and `large` ask for `min(300, 333) = 300`px. `large`'s `srcSet` also asks for 1920/2560-wide
  variants. `fileIfCached(image, 300)` is always `None`, so every page view runs `fetchAndCacheAll`:
  a full download from upload.wikimedia.org, an ImageIO decode and an 8-size scaling cascade. The route
  is unauthenticated and accepts any `px` up to 3840, which amplifies CPU load and Wikimedia traffic
  (and 429s) without limit.
- **Fix:** Make URL generation and the cache agree on one set of widths (snap requested `px` to the
  nearest cached width that is ≥ the request, and have `resizeTo`/`srcSet` emit only those). Reject or
  redirect `px` values outside that set, and consider rate-limiting cache misses per image. Add a test
  that a landscape thumbnail URL from `resizeTo` is served from the cache.

## 5. Regions disappear from region stats — fixed in `64210da`

The top-level region is now read from the `selection.monument_id` prefix, the same way `MonumentJdbc` fills `adm0`, with no join. Sub-regions (`adm1`) still need the monument table.


- **Where:** `ImageDbNew.byRegionStat`, around `ImageDbNew.scala:117`.
- **Problem:** The query now inner-joins `monument` on `selection.monument_id` instead of using
  `substring(i.monument_id, 1, 2)`. A region vanishes when there is no `monument` row for the id or when
  `selection.monument_id` is NULL.
- **Scenario:** A contest whose `monument` table was never filled (`MonumentService.updateLists` runs
  only for Ukraine with a `monumentIdTemplate`, and drops ids not matching `\d{2}-\d{3}-\d{4}`), or
  selections created with `Selection.apply(Page, ...)`, which leaves `monumentId` unset. The region stat
  is then empty and the gallery's region filter tabs vanish, although the `i.monument_id LIKE` filter
  would still work.
- **Fix:** Use a `LEFT JOIN`, or fall back to the `images.monument_id` prefix, so the stat matches what
  the region filter selects. Add a test with no `monument` rows.

## 6. `selection.monument_id` goes stale — fixed on `performance-memory`

`9db7c54` makes `ImageJdbc.update` and `ImageJdbc.updateMonumentId` update the selection rows in the
same transaction, and V57 resyncs the rows that had already drifted. Those are the only writers of
`images.monument_id`. Nothing left to do after the merge. The original finding follows for reference.

- **Where:** `ImageService.scala:67`; `ImageJdbc.updateMonumentId`, `ImageJdbc.update`.
- **Problem:** `selection.monument_id` is a new denormalized copy of `images.monument_id`, but the code
  that changes an image's monument id only updates `images`.
- **Scenario:** Images are distributed, then the admin runs "update monuments"
  (`updateImageMonuments` → `updateMonumentId`). Selection rows keep the old or NULL `monument_id`.
  `byRegionStat` lists wrong regions, and `order by s.monument_id` sorts wrongly, while the region filter
  (`i.monument_id LIKE`) uses the new value. The region tabs and their contents disagree.
- **Fix:** Update `selection.monument_id` in the same statement or transaction as
  `images.monument_id` (`UPDATE selection s JOIN images i ... SET s.monument_id = i.monument_id`), or
  drop the denormalized column and index the read path instead. Fix #5 together with this.

## 7. Local download can stay "running" forever

- **Where:** `LocalImageCacheService.startDownload`, around `LocalImageCacheService.scala:171`.
- **Problem:** Progress is set to `running = true` before any work starts, and there is no failure
  path that clears it.
- **Scenario:** `ImageJdbc.findByContestId` throws after `compute()` set `running = true`, or
  `Files.walk` fails (for example on a permissions error), so `registryReady` is a failed `Future` and the
  final `.map` in `runDownloadImpl` never runs. `progress(contestId)` reports `running = true` until the
  server restarts, `cache-status.js` polls every 3s indefinitely, and every later
  "Download images locally" click returns early on `cur.running`.
- **Fix:** Wrap the synchronous setup in `Try`, and attach `recover`/`onComplete` to the whole download
  future to set `running = false` and record the error so the UI can show it. Add a test where the
  image query throws.

## 8. `thumb_urls` export returns relative paths

- **Where:** `Global.legacyThumbUrl`, around `Global.scala:80`; used by
  `GalleryController.resizedImagesUrls`.
- **Problem:** Removing `thumbsHost` makes `legacyThumbUrl` return host-less paths
  (`/wikipedia/commons/thumb/...`).
- **Scenario:** An admin downloads `/thumb_urls/:contestId` to pre-warm or fetch thumbnails with wget
  or a script. Every line is a relative path, so the tool fails on all of them.
- **Fix:** Make the export build absolute URLs (`https://upload.wikimedia.org/...`, or the app's own
  absolute proxy URL via `routes...absoluteURL()`), whichever the export is meant to provide.

## 9. `ConnectDb` always throws

- **Where:** `ConnectDb.scala:16`.
- **Problem:** The config key is misspelled `"db.default.pasword"`, so
  `configuration.get[String]` throws `ConfigException.Missing` (only `db.default.password` is defined in
  `conf/application.conf`). Before this branch it read the user as the password; now it fails outright.
- **Fix:** Change the key to `"db.default.password"`.

---

# Findings in `ukwikidev/performance-memory`

From reviewing the 24 commits `performance-memory` adds on top of this branch (`a65e165..b46fafd`).
Only P1 needs fixing before the next round creation in production. P2 and P3 are minor.

## P1. A round whose distribution failed can't be retried or re-created — fixed in `62b644e`

The duplicate check ignores rounds without images. One consequence: a round that was created successfully but had no qualifying images doesn't block an identical resubmit either.

- **Where:** `RoundService.createNewRound` (the duplicate guard from `3e32a1f`), together with #2.
- **Problem:** `createNewRound` refuses a submission with the same settings as a round created in the
  last 30 minutes (`DuplicateRound`). When a distribution fails, `create` throws
  `RoundDistributionFailed` and leaves the round row in place with no selection rows. That empty round
  counts as the duplicate.
- **Scenario:** The organizer creates a round and the distribution fails (a deadlock, a lock-wait
  timeout, the #3 criteria bug). They are sent to the round's edit page, where "Distribute new files"
  is read-only because the round has no jurors (#2). Resubmitting the round form is refused as a
  duplicate for 30 minutes. The only ways out are to rename the round or delete the empty one first.
- **Fix:** Fix #2. Also skip rounds with no images in the duplicate check, for example
  `RoundImageCounts.get(...)` == 0 or `SelectionJdbc.imageCountByRound == 0`. Add a test: a failed
  creation followed by the same submission must create the round.

## P2. Selections without `monument_id` drop out of single-region galleries

- **Where:** `ImageDbNew.where`. The single-region filter changed from `i.monument_id like` to
  `s.monument_id like` (`617dc75` / `833b01d`).
- **Problem:** `Selection.apply(Page, ...)` doesn't set `monumentId`. `GlobalRefactor` builds selections
  from Commons pages, so those rows have a NULL `monument_id`. They no longer appear in their region's
  gallery, and V57 only fixes rows that existed when it ran.
- **Fix:** Set `monumentId` in that path, or remove `Selection.apply(Page, ...)` if `GlobalRefactor` is no
  longer used. Distribution (`DistributeImages.newSelection`) uses `Selection.apply(Image, ...)`, which
  is correct.

## P3. In-JVM locks and the count cache assume a single app instance

- **Where:** `RoundService.lock`, `RoundImageCounts`.
- **Problem:** The per-contest and per-round locks and the cache invalidation live in one JVM. The
  blue/green deployment (`docs/plans/2026-04-14-blue-green-*`) can run two instances against one DB.
  During a switch-over, a resubmit that reaches the other instance isn't blocked, and that instance's
  round counts can be up to 10 minutes stale (the TTL).
- **Fix:** Nothing, if only one instance takes traffic at a time. Otherwise, use a DB-level guard
  (`GET_LOCK('round-create-<contestId>')`, or a unique key) and a short TTL. At minimum, note it in the
  deployment docs.

## Checked and fine

- `ImageJdbc.update` / `updateMonumentId` in `DB.localTx`: `updateById(...).withAttributes` picks up
  the transaction's implicit session.
- `RoundImageCounts`: the version check correctly discards a load that overlaps an invalidation. Every
  app path that adds, moves or deletes selection rows invalidates the cache after its commit
  (distribution, `removeImage`, `removeUnrated`, `setRound`, `mergeRounds`, `Round.delete`,
  `GlobalRefactor`).
- `selectedImageCount` equals the old `roundRateStat` value for rate 1, which is all the stat table reads.
- `deferredJoinPage`: the inner query joins `images`, so `where()` and `orderBy()` resolve. It is used
  only without the monument join, so `m.*` columns never appear in it.
- `editRound`, `newFilesCount` and `roundStatTable` check the round's contest (`withAdminRound`).
- Hikari settings: `db.default.hikaricp.*` are the keys Play reads. The old BoneCP keys were indeed
  ignored.
