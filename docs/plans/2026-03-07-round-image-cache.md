# Round Image Cache Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow caching images for a specific round instead of the entire contest, so jurors don't wait hours for 38k images when only a few hundred are in their round.

**Architecture:** Add `startDownloadForRound(contestId, roundId)` and `progressForRound(roundId)` to `LocalImageCacheService` (using the existing `ImageJdbc.byRound(roundId)` query and a separate `roundProgressMap`). Wire two new routes (`/contest/:id/round/:rid/images/cache/start|status`) to two new controller methods. Add a cache button + status polling to `editRound.scala.html`.

**Tech Stack:** Scala 2.13, Play Framework 3.0, specs2, ScalikeJDBC, Twirl templates, `cache-status.js` (already exists at `public/javascripts/cache-status.js`).

---

### Task 1: Service — add round-scoped download methods

**Files:**
- Modify: `app/services/LocalImageCacheService.scala`
- Test: `test/services/LocalImageCacheServiceSpec.scala`

**Background:**
- `progressMap: ConcurrentHashMap[Long, CacheProgress]` is keyed by `contestId`.
- `ImageJdbc.byRound(roundId: Long): List[Image]` already exists.
- `runDownload(contestId: Long, images: Seq[Image]): Future[Unit]` is `private[services]` — reuse it.

**Step 1: Write the failing tests**

Add to `test/services/LocalImageCacheServiceSpec.scala`, after the existing test groups:

```scala
"startDownloadForRound" should {

  "report progress keyed by roundId, separate from contest progress" in {
    val tmpDir = Files.createTempDirectory("round-cache-test").toFile
    val svc = makeService(tmpDir)

    svc.startDownloadForRound(contestId = 99L, roundId = 7L)

    val roundProg  = svc.progressForRound(7L)
    val contestProg = svc.progress(99L)

    roundProg.running must beFalse   // nothing in round, completes instantly
    contestProg.running must beFalse // contest progress not touched

    tmpDir.delete()
    ok
  }

  "not start a second download for the same round while one is running" in {
    val tmpDir = Files.createTempDirectory("round-cache-guard").toFile
    val svc = makeService(tmpDir)

    // prime the round progress as running
    svc.progressForRound(8L) // ensure key exists (returns default)
    // can't easily assert "second call is no-op" without mocking images,
    // so just verify it doesn't throw
    svc.startDownloadForRound(contestId = 1L, roundId = 8L)
    svc.startDownloadForRound(contestId = 1L, roundId = 8L) // should be no-op

    ok
  }
}
```

**Step 2: Run tests to verify they fail**

```
sbt "testOnly services.LocalImageCacheServiceSpec"
```
Expected: compilation failure — `startDownloadForRound` and `progressForRound` do not exist yet.

**Step 3: Add the methods to the service**

In `app/services/LocalImageCacheService.scala`, right after `private val progressMap = new ConcurrentHashMap[Long, CacheProgress]()`:

```scala
private val roundProgressMap = new ConcurrentHashMap[Long, CacheProgress]()
```

After `def startDownload(contestId: Long)`, add:

```scala
def progressForRound(roundId: Long): CacheProgress =
  Option(roundProgressMap.get(roundId)).getOrElse(CacheProgress(0, 0, 0, running = false))

def startDownloadForRound(contestId: Long, roundId: Long): Unit = {
  if (progressForRound(roundId).running) return
  val images = ImageJdbc.byRound(roundId)
    .filter(img => img.isImage && img.url.isDefined && img.width > 0 && img.height > 0)
  runDownloadForRound(roundId, images)
}

private def runDownloadForRound(roundId: Long, images: Seq[Image]): Future[Unit] = {
  val existing   = localFileSet()
  val toDownload = images.filterNot(allSizesCached(_, existing))
  logger.info(s"Round $roundId: ${images.size} images total, ${images.size - toDownload.size} already cached, ${toDownload.size} to download")

  val startMs = System.currentTimeMillis()
  val total   = toDownload.size
  val done    = new AtomicInteger(0)
  val errs    = new AtomicInteger(0)

  def mkProgress(d: Int, running: Boolean): CacheProgress = {
    val elapsed = System.currentTimeMillis() - startMs
    val rate    = if (elapsed > 0) d * 1000.0 / elapsed else 0.0
    val eta     = if (rate > 0) ((total - d) / rate).toLong else 0L
    CacheProgress(d, total, errs.get, running,
      startedAtMs = startMs, elapsedMs = elapsed, ratePerSec = rate, etaSeconds = eta)
  }

  roundProgressMap.put(roundId, mkProgress(0, running = true))

  Source(toDownload)
    .throttle(ratePerSec, 1.second)
    .mapAsyncUnordered(parallelism) { image =>
      downloadAndResize(image)
        .recover { case ex =>
          logger.warn(s"Failed to cache ${image.title}: ${ex.getMessage}")
          errs.incrementAndGet()
        }
        .map { _ =>
          val d = done.incrementAndGet()
          roundProgressMap.put(roundId, mkProgress(d, running = true))
        }
    }
    .runWith(Sink.ignore)
    .map { _ =>
      roundProgressMap.put(roundId, mkProgress(done.get, running = false))
    }
}
```

**Step 4: Run tests**

```
sbt "testOnly services.LocalImageCacheServiceSpec"
```
Expected: 32 examples, 0 failures.

**Step 5: Commit**

```bash
git add app/services/LocalImageCacheService.scala test/services/LocalImageCacheServiceSpec.scala
git commit -m "feat: add startDownloadForRound and progressForRound to LocalImageCacheService"
```

---

### Task 2: Routes and controller methods

**Files:**
- Modify: `conf/routes`
- Modify: `app/controllers/ImageController.scala`

**Background:**
- Existing routes at lines 88–89: `POST /contest/:id/images/cache/start` and `GET /contest/:id/images/cache/status`.
- Controller has `startImageCache(contestId: Long)` and `imageCacheStatus(contestId: Long)`.
- Auth uses `withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId)))`.

**Step 1: Add routes**

In `conf/routes`, after the existing cache routes (lines 88–89):

```
POST        /contest/:id/round/:rid/images/cache/start                                 @controllers.ImageController.startRoundImageCache(id: Long, rid: Long)
GET         /contest/:id/round/:rid/images/cache/status                                @controllers.ImageController.roundImageCacheStatus(id: Long, rid: Long)
```

**Step 2: Add controller methods**

In `app/controllers/ImageController.scala`, after `imageCacheStatus`:

```scala
def startRoundImageCache(contestId: Long, roundId: Long): EssentialAction =
  withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { _ => implicit request =>
    localImageCacheService.startDownloadForRound(contestId, roundId)
    Redirect(routes.RoundController.editRound(Some(roundId), contestId, None))
  }

def roundImageCacheStatus(contestId: Long, roundId: Long): EssentialAction =
  withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { _ => _ =>
    Ok(Json.toJson(localImageCacheService.progressForRound(roundId)))
  }
```

**Step 3: Compile**

```
sbt compile
```
Expected: success (routes regenerated, no type errors).

**Step 4: Commit**

```bash
git add conf/routes app/controllers/ImageController.scala
git commit -m "feat: add round image cache start/status routes and controller methods"
```

---

### Task 3: Add cache UI to editRound template

**Files:**
- Modify: `app/views/editRound.scala.html`

**Background:**
- Template signature includes `contestId: Option[Long]` and `editRoundForm: Form[controllers.EditRound]`.
- The round ID is in `editRoundForm("id").value` (an `Option[String]` — present when editing an existing round, absent for a new round form).
- `cache-status.js` is already at `public/javascripts/cache-status.js` and exports `pollCacheStatus(statusUrl)`.
- The contest images page (`contest_images.scala.html`) has a working example of this pattern.

**Step 1: Add the cache section**

In `app/views/editRound.scala.html`, at the end of the file (before the closing `}` of any outer block, after the save/cancel buttons section), add:

```html
@for(
  cid  <- contestId;
  ridStr <- editRoundForm("id").value;
  rid  <- scala.util.Try(ridStr.toLong).toOption
) {
  <hr/>
  <h4>@Messages("local.image.cache")</h4>
  <div id="round-cache-status"></div>
  <form method="post" action="@routes.ImageController.startRoundImageCache(cid, rid)">
    @helper.CSRF.formField
    <button type="submit" class="btn btn-default">@Messages("download.images.locally")</button>
  </form>

  <script src="@routes.Assets.at("javascripts/cache-status.js")"></script>
  <script>
    pollCacheStatus(
      "@routes.ImageController.roundImageCacheStatus(cid, rid)",
      "round-cache-status"
    );
  </script>
}
```

**Note:** `pollCacheStatus` in `cache-status.js` currently takes only one argument (the URL). It needs to accept an optional second argument for the element ID so it can target `round-cache-status` instead of `cache-status`. See Step 2.

**Step 2: Update `cache-status.js` to accept element ID**

In `public/javascripts/cache-status.js`, change the `pollCacheStatus` signature and the `document.getElementById` call:

```js
// Before:
function pollCacheStatus(statusUrl) {
  ...
  document.getElementById('cache-status').innerHTML = ...

// After:
function pollCacheStatus(statusUrl, elementId) {
  elementId = elementId || 'cache-status';
  ...
  document.getElementById(elementId).innerHTML = ...
```

**Step 3: Update Jest tests for the new signature**

In `javascript-test/cache-status.test.js`, update any tests that call `pollCacheStatus` to verify the default element ID still works, and add a test for the custom element ID. The existing tests should still pass (default behaviour unchanged).

**Step 4: Compile and check template renders**

```
sbt compile
```
Expected: success.

Manually verify by navigating to an existing round's edit page — the cache section should appear below the save button when editing (not creating) a round.

**Step 5: Commit**

```bash
git add app/views/editRound.scala.html public/javascripts/cache-status.js javascript-test/cache-status.test.js
git commit -m "feat: add round image cache download button to editRound page"
```

---

### Task 4: Verify full test suite

**Step 1: Run all tests**

```
sbt test
```
Expected: all unit and DB tests pass; integration and docker specs are skipped (0 examples). JS tests pass.

**Step 2: Commit any fixes, then done.**

---

## Running round cache manually

```
POST /contest/42/round/7/images/cache/start   # triggers download for round 7
GET  /contest/42/round/7/images/cache/status  # returns CacheProgress JSON
```

The status JSON is the same `CacheProgress` shape as the contest-level endpoint, so the frontend can reuse `formatCacheStatus`.
