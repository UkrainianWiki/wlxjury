# Design: saveResized Cascade Optimization & JMH Benchmarks

**Date:** 2026-03-10
**Branch:** local_images

## Problem

`LocalImageCacheService#saveResized` generates 8 thumbnail sizes from a single downloaded source image (up to 2200×1650 px). Every size is scaled directly from that large source via `Graphics2D.drawImage` with bicubic interpolation — even when the target is only 120 px wide (a ~18× downscale ratio). Reading the full source image for each small target wastes memory bandwidth and CPU.

Additionally, `fetchAndCacheAll` duplicates work: it scales `sourceImg → requestedPx` immediately for the response, while `saveResized` scales the same size again in the background.

## Goal

1. Benchmark the current implementation with JMH.
2. Optimize by cascading: scale each smaller size from the previous larger output rather than always from the source.
3. Eliminate duplicated work in `fetchAndCacheAll` by sharing cascade results with `saveResized`.
4. Re-run benchmarks to prove improvement.

## Build Setup

Add to `project/plugins.sbt`:
```scala
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")
```

Enable in `build.sbt` on the root project:
```scala
.enablePlugins(..., JmhPlugin)
```

Run benchmarks with:
```
sbt "jmh:run -i 5 -wi 3 -f 1 .*SaveResizedBenchmark"
sbt "jmh:run -i 5 -wi 3 -f 1 .*FetchAndCacheAllBenchmark"
```

## New Method: `cascadeScale`

Extract pure cascade computation into a package-private method (no IO, no registry, no futures):

```scala
private[services] def cascadeScale(
  sourceImg: BufferedImage,
  targetWidths: Seq[Int]  // pre-filtered, sorted descending
): Seq[(Int, BufferedImage)]
```

Returns `(width, image)` pairs. Each image is scaled from the previous one in the sequence.

This cleanly separates CPU computation from disk IO, enabling stable benchmarking.

## Optimization: Cascaded Scaling

**Current** — every size scaled from `sourceImg`:
```
sourceImg (2200px) → 160px
sourceImg (2200px) → 240px
sourceImg (2200px) → 320px
...
sourceImg (2200px) → 1466px
```

**New** — each size scaled from the previous larger output:
```
sourceImg (2200px) → 1466px → 666px → 500px → 333px → 320px → 240px → 160px
```

Each step has a lower downscale ratio and operates on a smaller image, reducing memory reads for small targets. Quality is equivalent or better (bicubic over smaller ratios is more accurate).

## Updated `saveResized`

**New signature:**
```scala
private[services] def saveResized(
  image: Image,
  sourceImg: BufferedImage,
  sourcePx: Int,
  requestedPx: Option[Int] = None
): Option[BufferedImage]
```

**Logic:**
1. Calls `cascadeScale` to produce all target images
2. For each result, fires a fire-and-forget `Future(ImageIO.write(...))` — does not block on IO
3. Updates registry for each saved file
4. Returns `Some(img)` if `requestedPx` matches a generated size, otherwise `None`

Existing callers pass no `requestedPx` — the default `None` preserves backward compatibility. Return type change from `Unit` to `Option[BufferedImage]` requires minor updates at call sites.

## Updated `fetchAndCacheAll`

**New flow:**
1. Download source image (unchanged)
2. Call `saveResized(..., requestedPx = Some(requestedPx))` synchronously
3. Get the returned `Option[BufferedImage]`, encode to JPEG, return bytes

No separate `scale(sourceImg, requestedPx)` call. The cascade already produced the requested size during `saveResized`. Disk IO is non-blocking (fire-and-forget futures).

## Benchmark Classes

Both classes live in `test/services/` (same package as the service, enabling access to `private[services]` methods). They are compiled and run via `jmh:compile` / `jmh:run`, not part of `sbt test`.

### `SaveResizedBenchmark`

- `@State(Scope.Thread)`: creates a `BufferedImage(2200, 1650)` and precomputes `targetWidths` for a 4000×3000 image
- `fromLargest`: current approach — calls `scale(sourceImg, px)` for each target width independently
- `cascaded`: new approach — calls `cascadeScale(sourceImg, targetWidths)`
- No file IO. Measures pure cascade CPU time for all 8 sizes.

### `FetchAndCacheAllBenchmark`

- `@Param(Array("120", "180", "240", "250", "375", "500", "1100", "1650"))` — all 8 target heights
- For each height, computes `requestedPx = ImageUtil.resizeTo(4000, 3000, targetHeight)`
- `@State(Scope.Thread)`: creates `sourceImg: BufferedImage(2200, 1650)`
- `fromLargest`: calls `scale(sourceImg, requestedPx)` then JPEG encode
- `cascaded`: calls `cascadeScale(sourceImg, targetWidthsUpTo(requestedPx))`, picks result, JPEG encode
- No file IO. Measures time to produce response bytes for each requested size.

## Testing

**Existing tests** — all remain valid. `saveResized(..., None)` (default) behaviour is unchanged except return type. Minor updates needed at call sites.

**New unit tests in `LocalImageCacheServiceSpec`:**
- `saveResized(..., Some(px))` returns `Some(img)` with correct width for a known target
- `saveResized(..., Some(px))` returns `None` when `px > sourcePx`
- `saveResized(..., None)` returns `None`
- Cascade produces correct dimensions at each step (no accidental upscaling)

**Benchmarks** — not part of `sbt test`. Run explicitly via `jmh:run`.

## Success Criteria

- JMH output shows cascaded `SaveResizedBenchmark` has lower ops/ms than `fromLargest` for the full 8-size run
- JMH output shows cascaded `FetchAndCacheAllBenchmark` has lower latency for small sizes (120–500px) where the cascade ratio advantage is largest
- All existing tests pass
- No regression in image quality (correct dimensions verified by existing `saveResized` tests)
