# Local Image Cache — Integration Tests & Time Tracking Design

Date: 2026-03-06

## Context

`LocalImageCacheService` downloads contest images from Wikimedia Commons and resizes them locally to
avoid 429 Too Many Requests errors. Unit tests already cover all internal methods in isolation.
This document covers:

1. Integration tests against real Wikimedia Commons
2. Time tracking added to `CacheProgress` and the status endpoint

---

## 1. Integration Tests

### File

`test/services/LocalImageCacheServiceIntegrationSpec.scala` — package `services` (same package as
the service, giving access to `private[services]` methods).

### Gating

The entire spec is skipped unless the JVM system property `integration` is set to `true`:

```bash
sbt -Dintegration=true "testOnly services.LocalImageCacheServiceIntegrationSpec"
```

Implemented with specs2 `skipAllUnless(sys.props.get("integration").contains("true"))`.

### Image source

Query `Category:Images from Wiki Loves Earth 2021 in Armenia` via
`MwBot.fromHost("commons.wikimedia.org")` (anonymous, read-only). Fetch up to 100 images with full
image info (URL, width, height, mime). Take the first 50 that pass:
- `img.isImage`
- `img.url.isDefined`
- `img.width > 0 && img.height > 0`

### Pipeline

Run download + resize concurrently using a small Pekko Streams pipeline inside the test (parallelism
4, rate 5/s) calling the already-accessible `private[services]` methods:

1. Compute `sourcePx = min(ImageUtil.resizeTo(w, h, 1650), w)`
2. Build source URL: `image.url` if `sourcePx >= image.width`, else `svc.wikiThumbUrl(image, sourcePx)`
3. `svc.downloadWithRetry(url, attempt = 1)`
4. `ImageIO.read(new ByteArrayInputStream(bytes))`
5. `svc.saveResized(image, sourceImg, sourcePx)`

### Assertions

- All 50 images must succeed (100% success threshold — this feature exists to eliminate missing images)
- For each image: `svc.allSizesCached(image)` must be `true`
- Log total elapsed pipeline time at the end

### Timeout

1 minute for the full pipeline.

---

## 2. `CacheProgress` Time Tracking

### Updated case class

```scala
case class CacheProgress(
  done: Int,
  total: Int,
  errors: Int,
  running: Boolean,
  startedAtMs: Long,   // epoch ms when download started (0 if never started)
  elapsedMs: Long,     // wall-clock ms since start
  ratePerSec: Double,  // images per second (done / elapsed); 0.0 if elapsed == 0
  etaSeconds: Long     // (total - done) / ratePerSec; 0 if done or rate unknown
)
```

JSON serialization via the existing `Json.writes[CacheProgress]` — no manual changes needed.

### Service changes (`startDownload`)

- Record `val startMs = System.currentTimeMillis()` before the stream starts.
- On every progress update and at completion, compute:
  - `elapsedMs = System.currentTimeMillis() - startMs`
  - `ratePerSec = if (elapsedMs > 0) done * 1000.0 / elapsedMs else 0.0`
  - `etaSeconds = if (ratePerSec > 0) ((total - done) / ratePerSec).toLong else 0L`
- The default `CacheProgress(0, 0, 0, running = false, ...)` fills time fields with 0.

### Frontend (`contest_images.scala.html`)

Display format in `#cache-status`:

> 12,450 / 35,000 downloaded (35%) – 4.2 img/s – ETA 5m 23s – running…

ETA formatted as `Xm Ys` when ≥ 60s, plain `Xs` otherwise.

---

## Implementation Order

1. Update `CacheProgress` + `startDownload` (time tracking)
2. Update frontend JS to show rate + ETA
3. Write integration test spec
