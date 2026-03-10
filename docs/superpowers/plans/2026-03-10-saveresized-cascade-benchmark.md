# saveResized Cascade Optimization & JMH Benchmarks — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Benchmark `LocalImageCacheService#saveResized` and `fetchAndCacheAll` with JMH, optimize both by cascading downscale steps (each size from the previous larger output), and re-run benchmarks to prove improvement.

**Architecture:** Extract a pure `cascadeScale` method (no IO) for benchmarking and reuse; update `saveResized` to use it and return the requested image as a byproduct; update `fetchAndCacheAll` to get its response image from the cascade instead of a separate `scale` call. File saves remain fire-and-forget `Future`s inside `saveResized`.

**Tech Stack:** Scala 2.13, Play Framework 3.0, sbt-jmh 0.4.7, JMH 1.x, Specs2, `java.awt.image.BufferedImage`, `javax.imageio.ImageIO`

---

## Chunk 1: Build setup and baseline JMH benchmarks

### Task 1: Add sbt-jmh plugin and enable JmhPlugin

**Files:**
- Modify: `project/plugins.sbt`
- Modify: `build.sbt`

- [ ] **Step 1: Add sbt-jmh to plugins**

  Append to `project/plugins.sbt`:
  ```scala
  addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")
  ```

- [ ] **Step 2: Enable JmhPlugin and wire test sources**

  In `build.sbt`, the `lazy val root` block currently starts with:
  ```scala
  lazy val root = identity(
    (project in file("."))
      .enablePlugins(
        PlayScala,
        PlayNettyServer,
        DebianPlugin,
        SystemdPlugin,
        JavaServerAppPackaging
      )
  )
    .disablePlugins(PlayPekkoHttpServer)
  ```

  Change it to add `JmhPlugin` to the existing `.enablePlugins(...)` call and add the three JMH source-wiring settings. Note: `.disablePlugins(PlayPekkoHttpServer)` is chained **outside** `identity(...)` in the actual file — keep it there. `.settings(...)` goes **inside** `identity(...)`:
  ```scala
  lazy val root = identity(
    (project in file("."))
      .enablePlugins(
        PlayScala,
        PlayNettyServer,
        DebianPlugin,
        SystemdPlugin,
        JavaServerAppPackaging,
        JmhPlugin
      )
      .settings(
        Jmh / sourceDirectory     := (Test / sourceDirectory).value,
        Jmh / classDirectory      := (Test / classDirectory).value,
        Jmh / dependencyClasspath := (Test / dependencyClasspath).value
      )
  )
    .disablePlugins(PlayPekkoHttpServer)
  ```

  These settings make `jmh:compile` pick up benchmark sources from `test/` so that benchmark classes (in package `services`) can access `private[services]` methods.

- [ ] **Step 3: Verify compilation**

  Run:
  ```
  sbt jmh:compile
  ```
  Expected: compiles with no errors (no benchmark classes yet, that's fine).

- [ ] **Step 4: Commit**

  ```bash
  git add project/plugins.sbt build.sbt
  git commit -m "build: add sbt-jmh plugin for LocalImageCacheService benchmarks"
  ```

---

### Task 2: Write `SaveResizedBenchmark` (baseline — from largest)

**Files:**
- Create: `test/services/SaveResizedBenchmark.scala`

This benchmark measures the current strategy: each of the 8 target widths scaled independently from the large source image. At this point `cascadeScale` does not exist yet, so the `cascaded` method is present as a non-annotated stub (no `@Benchmark`) to satisfy the shape expected in Chunk 3. Only `fromLargest` is active for the baseline run.

- [ ] **Step 1: Create the benchmark file**

  ```scala
  package services

  import com.typesafe.config.ConfigFactory
  import org.apache.pekko.actor.ActorSystem
  import org.intracer.wmua.ImageUtil
  import org.openjdk.jmh.annotations._
  import play.api.Configuration

  import java.awt.image.BufferedImage
  import java.nio.file.Files
  import java.util.concurrent.TimeUnit
  import scala.concurrent.ExecutionContext.Implicits.global

  @State(Scope.Thread)
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Warmup(iterations = 3, time = 1)
  @Measurement(iterations = 5, time = 1)
  @Fork(1)
  class SaveResizedBenchmark {

    var system: ActorSystem = _
    var svc: LocalImageCacheService = _
    var sourceImg: BufferedImage = _
    var targetWidths: Seq[Int] = _

    val imageW = 4000
    val imageH = 3000
    // Heights mirroring LocalImageCacheService#targetHeights
    val targetHeights: Seq[Int] = Seq(120, 180, 240, 250, 375, 500, 1100, 1650)

    @Setup(Level.Trial)
    def setup(): Unit = {
      system = ActorSystem("SaveResizedBench")
      val dir = Files.createTempDirectory("bench-saveresized").toFile
      val config = Configuration(ConfigFactory.parseString(
        s"""wlxjury.thumbs.local-path = "${dir.getAbsolutePath}"
           |wlxjury.thumbs.parallelism = 1
           |wlxjury.thumbs.rate-per-second = 100
           |wlxjury.thumbs.max-attempts = 1
           |""".stripMargin
      ))
      // LocalImageCacheService takes an implicit ExecutionContext as its second parameter list.
      // `scala.concurrent.ExecutionContext.Implicits.global` (imported above) satisfies it.
      svc = new LocalImageCacheService(config, system)
      // Simulated downloaded source: 2200×1650 (typical largest Wikimedia thumbnail)
      sourceImg = new BufferedImage(2200, 1650, BufferedImage.TYPE_INT_RGB)
      targetWidths = targetHeights
        .map(h => ImageUtil.resizeTo(imageW, imageH, h))
        .filter(px => px > 0 && px < imageW)
        .distinct
        .sorted(Ordering[Int].reverse)   // largest first (needed for cascade later)
    }

    @TearDown(Level.Trial)
    def teardown(): Unit = {
      import scala.concurrent.Await
      import scala.concurrent.duration._
      Await.result(system.terminate(), 30.seconds)
    }

    /** Current approach: each target scaled independently from sourceImg. */
    @Benchmark
    def fromLargest(): Unit = {
      targetWidths.foreach { px =>
        svc.scale(sourceImg, px)
      }
    }

    /** Placeholder — NOT annotated with @Benchmark.
      * Wired to cascadeScale in Chunk 3 Task 6. */
    def cascaded(): Unit = fromLargest()
  }
  ```

- [ ] **Step 2: Compile and confirm JMH sees the benchmark**

  ```
  sbt jmh:compile
  ```
  Expected: compiles successfully.

- [ ] **Step 3: Run baseline `fromLargest` only**

  ```
  sbt "jmh:run -i 5 -wi 3 -f 1 .*SaveResizedBenchmark.fromLargest"
  ```
  Expected: JMH prints a result table with one row for `fromLargest`. The metric is `ms/op` (milliseconds per operation, since `@BenchmarkMode(Mode.AverageTime)`). Record the number — this is the baseline.

- [ ] **Step 4: Commit**

  ```bash
  git add test/services/SaveResizedBenchmark.scala
  git commit -m "bench: add SaveResizedBenchmark baseline (fromLargest)"
  ```

---

### Task 3: Write `FetchAndCacheAllBenchmark` (baseline — from largest)

**Files:**
- Create: `test/services/FetchAndCacheAllBenchmark.scala`

Benchmarks time-to-first-bytes for `fetchAndCacheAll` at all 8 target heights. No network, no disk — pure `scale` + JPEG encode. The `cascaded` method is present but not annotated with `@Benchmark` until Chunk 3 wires it to `cascadeScale`.

- [ ] **Step 1: Create the benchmark file**

  ```scala
  package services

  import com.typesafe.config.ConfigFactory
  import org.apache.pekko.actor.ActorSystem
  import org.intracer.wmua.ImageUtil
  import org.openjdk.jmh.annotations._
  import play.api.Configuration

  import java.awt.image.BufferedImage
  import java.io.ByteArrayOutputStream
  import java.nio.file.Files
  import java.util.concurrent.TimeUnit
  import javax.imageio.ImageIO
  import scala.concurrent.ExecutionContext.Implicits.global

  @State(Scope.Thread)
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Warmup(iterations = 3, time = 1)
  @Measurement(iterations = 5, time = 1)
  @Fork(1)
  class FetchAndCacheAllBenchmark {

    /** Each param value is one of the target heights from LocalImageCacheService#targetHeights. */
    @Param(Array("120", "180", "240", "250", "375", "500", "1100", "1650"))
    var targetHeightStr: String = _

    var system: ActorSystem = _
    var svc: LocalImageCacheService = _
    var sourceImg: BufferedImage = _
    var requestedPx: Int = _
    var cascadeWidths: Seq[Int] = _  // target widths from largest down to requestedPx (for cascade later)

    val imageW = 4000
    val imageH = 3000

    @Setup(Level.Trial)
    def setup(): Unit = {
      system = ActorSystem("FetchAndCacheAllBench")
      val dir = Files.createTempDirectory("bench-fetch").toFile
      val config = Configuration(ConfigFactory.parseString(
        s"""wlxjury.thumbs.local-path = "${dir.getAbsolutePath}"
           |wlxjury.thumbs.parallelism = 1
           |wlxjury.thumbs.rate-per-second = 100
           |wlxjury.thumbs.max-attempts = 1
           |""".stripMargin
      ))
      // LocalImageCacheService takes an implicit ExecutionContext as its second parameter list.
      // `scala.concurrent.ExecutionContext.Implicits.global` (imported above) satisfies it.
      svc = new LocalImageCacheService(config, system)
      sourceImg = new BufferedImage(2200, 1650, BufferedImage.TYPE_INT_RGB)
      requestedPx = ImageUtil.resizeTo(imageW, imageH, targetHeightStr.toInt)
      // cascadeWidths: all target widths from the largest down to and including requestedPx,
      // sorted descending. This mirrors the cascade path in production saveResized when
      // the cascade reaches the requested size. takeWhile(_ >= requestedPx) keeps widths
      // ≥ requestedPx (i.e. from the largest target down to requestedPx inclusive), so
      // cascadeScale(sourceImg, cascadeWidths) starts from sourceImg and cascades through
      // the same intermediate steps as it would in production.
      cascadeWidths = Seq(120, 180, 240, 250, 375, 500, 1100, 1650)
        .map(h => ImageUtil.resizeTo(imageW, imageH, h))
        .filter(px => px > 0 && px < imageW)
        .distinct
        .sorted(Ordering[Int].reverse)    // largest first
        .takeWhile(_ >= requestedPx)      // from largest down to requestedPx inclusive
    }

    @TearDown(Level.Trial)
    def teardown(): Unit = {
      import scala.concurrent.Await
      import scala.concurrent.duration._
      Await.result(system.terminate(), 30.seconds)
    }

    private def encodeJpeg(img: BufferedImage): Array[Byte] = {
      val baos = new ByteArrayOutputStream()
      ImageIO.write(img, "JPEG", baos)
      baos.toByteArray
    }

    /** Current approach: scale sourceImg directly to requestedPx. */
    @Benchmark
    def fromLargest(): Array[Byte] =
      encodeJpeg(svc.scale(sourceImg, requestedPx))

    /** Placeholder — NOT annotated with @Benchmark.
      * Wired to cascadeScale in Chunk 3 Task 6. */
    def cascaded(): Array[Byte] = fromLargest()
  }
  ```

- [ ] **Step 2: Compile**

  ```
  sbt jmh:compile
  ```
  Expected: compiles successfully.

- [ ] **Step 3: Run baseline for all heights**

  ```
  sbt "jmh:run -i 5 -wi 3 -f 1 .*FetchAndCacheAllBenchmark.fromLargest"
  ```
  Expected: JMH prints 8 result rows — one per `targetHeightStr` param value. Metric is `ms/op`. Record the numbers — this is the baseline. Expect large heights (1100, 1650) to take longer (more pixels to scale) than small ones (120, 180).

- [ ] **Step 4: Commit**

  ```bash
  git add test/services/FetchAndCacheAllBenchmark.scala
  git commit -m "bench: add FetchAndCacheAllBenchmark baseline (fromLargest)"
  ```

---

## Chunk 2: Cascade optimization

### Task 4: Add `cascadeScale` method and update `saveResized`

**Files:**
- Modify: `app/services/LocalImageCacheService.scala`

The `cascadeScale` method is pure CPU — no IO, no registry, no futures. It drives the cascade logic and is the method benchmarks call directly.

- [ ] **Step 1: Write a failing test for `cascadeScale`**

  In `test/services/LocalImageCacheServiceSpec.scala`, add a new `"cascadeScale"` block after the existing `"scale"` block:

  ```scala
  "cascadeScale" should {

    val svc = mkService(mkTempDir())

    "produce outputs with the requested widths in order" in {
      val src = new BufferedImage(2200, 1650, BufferedImage.TYPE_INT_RGB)
      val widths = Seq(1466, 666, 333)  // already sorted descending
      val results = svc.cascadeScale(src, widths)
      results.map(_._1) === widths
    }

    "produce correct pixel widths" in {
      val src = new BufferedImage(2200, 1650, BufferedImage.TYPE_INT_RGB)
      val widths = Seq(1466, 666, 333)
      val results = svc.cascadeScale(src, widths)
      results.forall { case (w, img) => img.getWidth == w } must beTrue
    }

    "never produce an image wider than the previous step" in {
      val src = new BufferedImage(2200, 1650, BufferedImage.TYPE_INT_RGB)
      val widths = Seq(1466, 666, 333)
      val results = svc.cascadeScale(src, widths)
      val allWidths = src.getWidth +: results.map(_._1)
      allWidths.zip(allWidths.tail).forall { case (a, b) => b <= a } must beTrue
    }

    "return empty for empty input" in {
      val src = new BufferedImage(100, 75, BufferedImage.TYPE_INT_RGB)
      svc.cascadeScale(src, Seq.empty) must beEmpty
    }
  }
  ```

- [ ] **Step 2: Run the failing test**

  ```
  sbt "testOnly services.LocalImageCacheServiceSpec"
  ```
  Expected: FAIL — `cascadeScale` is not a member of `LocalImageCacheService`.

- [ ] **Step 3: Implement `cascadeScale` in `LocalImageCacheService`**

  Add this method after the `scale` method (line 342):

  ```scala
  /** Scales sourceImg down through targetWidths in order (largest first).
    * Each step uses the previous output as source, reducing the downscale ratio
    * and the number of pixels read per step. Pure CPU — no IO.
    *
    * @param sourceImg   the starting (largest) image
    * @param targetWidths widths to generate, sorted descending (largest first)
    * @return (width, image) pairs in the same order as targetWidths
    */
  private[services] def cascadeScale(
    sourceImg: BufferedImage,
    targetWidths: Seq[Int]
  ): Seq[(Int, BufferedImage)] = {
    var prev = sourceImg
    targetWidths.map { px =>
      val scaled = scale(prev, px)
      prev = scaled
      (px, scaled)
    }
  }
  ```

- [ ] **Step 4: Run the test again**

  ```
  sbt "testOnly services.LocalImageCacheServiceSpec"
  ```
  Expected: all tests pass including the new `cascadeScale` block.

- [ ] **Step 5: Update `saveResized` to use `cascadeScale` and return `Option[BufferedImage]`**

  Replace the current `saveResized` method (lines 328–340):

  ```scala
  private[services] def saveResized(image: Image, sourceImg: BufferedImage, sourcePx: Int): Unit =
    targetHeights.foreach { h =>
      val px = ImageUtil.resizeTo(image.width, image.height, h)
      if (px > 0 && px < image.width && px <= sourcePx) {
        val file = localFile(image, px)
        if (!registry.containsKey(file.getAbsolutePath)) {
          file.getParentFile.mkdirs()
          val resized = scale(sourceImg, px)
          ImageIO.write(resized, "JPEG", file)
          registry.put(file.getAbsolutePath, ())
        }
      }
    }
  ```

  With:

  ```scala
  /** Generates all target sizes via cascaded downscaling and saves them asynchronously.
    *
    * @param requestedPx if Some(px), returns the BufferedImage for that width if generated
    * @return Some(img) for the requested width if it was generated; None otherwise
    */
  private[services] def saveResized(
    image: Image,
    sourceImg: BufferedImage,
    sourcePx: Int,
    requestedPx: Option[Int] = None
  ): Option[BufferedImage] = {
    val toGenerate = targetHeights
      .map(h => ImageUtil.resizeTo(image.width, image.height, h))
      .filter(px => px > 0 && px < image.width && px <= sourcePx)
      .distinct
      .sorted(Ordering[Int].reverse)  // largest first for cascade

    val results = cascadeScale(sourceImg, toGenerate)

    results.foreach { case (px, img) =>
      val file = localFile(image, px)
      if (!registry.containsKey(file.getAbsolutePath)) {
        registry.put(file.getAbsolutePath, ())   // claim before async write
        file.getParentFile.mkdirs()
        Future {
          ImageIO.write(img, "JPEG", file)
        }.recover { case ex =>
          logger.warn(s"Failed to write ${file.getName}: ${ex.getMessage}")
          registry.remove(file.getAbsolutePath)  // release claim on failure
        }
      }
    }

    requestedPx.flatMap(rpx => results.find(_._1 == rpx).map(_._2))
  }
  ```

- [ ] **Step 6: Fix the `downloadAndResize` call site**

  In `LocalImageCacheService.scala`, find `downloadAndResize` (around line 259). It contains:
  ```scala
  if (sourceImg != null) saveResized(image, sourceImg, sourcePx)
  ```
  After the return type changes to `Option[BufferedImage]`, this expression infers to `Any` (the LUB of `Option[BufferedImage]` and `Unit`), causing a compile error since `downloadAndResize` returns `Future[Unit]`. Fix by discarding the result explicitly:
  ```scala
  if (sourceImg != null) { saveResized(image, sourceImg, sourcePx); () }
  ```

- [ ] **Step 7: Update all `saveResized` tests that read files from disk**

  The new implementation writes files asynchronously. All existing `saveResized` tests that check `file.exists()` or `ImageIO.read(file)` immediately after `saveResized` will be flaky without waiting for the async write to land. In `test/services/LocalImageCacheServiceSpec.scala`, add `Thread.sleep(500)` after every `svc.saveResized(...)` call in the `"saveResized"` block that is followed by a file read or existence check.

  Affected tests (find by their description strings):

  **`"create files for all sizes where px < image.width"`** — add sleep after `saveResized`:
  ```scala
  svc.saveResized(img, sourceImg, sourcePx = 2200)
  Thread.sleep(500)
  val missing = allWidths.filterNot(px => svc.localFile(img, px).exists())
  ```

  **`"write valid JPEGs with correct pixel dimensions"`** — add sleep after `saveResized`:
  ```scala
  svc.saveResized(img, sourceImg, sourcePx = 2200)
  Thread.sleep(500)
  val px250 = ...
  ```

  **`"not create files for sizes where px >= image.width"`** — add sleep after `saveResized`:
  ```scala
  svc.saveResized(img, new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB), sourcePx = 400)
  Thread.sleep(500)
  svc.localFile(img, 400).exists() must beFalse
  ```

  **`"not create files for sizes larger than sourcePx"`** — add sleep after `saveResized`:
  ```scala
  svc.saveResized(img, new BufferedImage(500, 375, BufferedImage.TYPE_INT_RGB), sourcePx = 500)
  Thread.sleep(500)
  svc.localFile(img, 666).exists() must beFalse
  ```

  **`"registry" / "be populated by initRegistry from existing files on disk"`** — `svc1.saveResized` is followed immediately by `svc2.initRegistry()` which scans disk. Add sleep so the async write lands first:
  ```scala
  svc1.saveResized(img, src, 800)
  Thread.sleep(500)   // let async write land before initRegistry scans disk

  val svc2 = mkService(tmpDir)
  Await.result(svc2.initRegistry(), 10.seconds)
  ```

  **`"fetchAndCacheAll" / "return JPEG bytes for fileIfCached when file is in registry"`** — `svc.saveResized` pre-caches files; the test then checks `file.get.exists()` on disk. Registry claim is synchronous (no sleep needed for `fileIfCached`), but the `file.get.exists()` disk check needs the write to complete:
  ```scala
  svc.saveResized(img, src, 800)
  Thread.sleep(500)   // let async write land before checking file.exists()

  val px   = neededWidths.head
  val file = svc.fileIfCached(img, px)
  file must beSome
  file.get.exists() must beTrue
  ```

  **`"not overwrite an existing file on a second call"`** — add sleep after first `saveResized` so `lastModified` is stable:
  ```scala
  svc.saveResized(img, sourceImg, sourcePx = 2200)
  Thread.sleep(500)   // let async write complete before reading lastModified

  val file       = svc.localFile(img, 333)
  val modifiedAt = file.lastModified()
  Thread.sleep(50)
  svc.saveResized(img, sourceImg, sourcePx = 2200)
  Thread.sleep(200)   // second call skips write (registry claimed); confirm no race

  file.lastModified() === modifiedAt
  ```

- [ ] **Step 8: Add unit tests for `saveResized` return value**

  In `test/services/LocalImageCacheServiceSpec.scala`, inside the existing `"saveResized"` block, add three tests after the existing ones:

  ```scala
  "return Some(img) for the requested px when it is generated" in {
    val dir = mkTempDir()
    val svc = mkService(dir)
    val img = largeImg
    val result = svc.saveResized(img, sourceImg, sourcePx = 2200, requestedPx = Some(333))
    // result is synchronous (cascade CPU); no sleep needed
    result must beSome
    result.get.getWidth === 333
  }

  "return None when requestedPx exceeds sourcePx" in {
    val dir = mkTempDir()
    val svc = mkService(dir)
    val img = largeImg
    val result = svc.saveResized(img, sourceImg, sourcePx = 500, requestedPx = Some(1466))
    result must beNone
  }

  "return None when requestedPx is absent (default)" in {
    val dir = mkTempDir()
    val svc = mkService(dir)
    val img = largeImg
    svc.saveResized(img, sourceImg, sourcePx = 2200) must beNone
  }
  ```

- [ ] **Step 9: Run all tests**

  ```
  sbt "testOnly services.LocalImageCacheServiceSpec"
  ```
  Expected: all tests pass including the three new `saveResized` return-value tests and the updated "not overwrite" test.

- [ ] **Step 10: Commit**

  ```bash
  git add app/services/LocalImageCacheService.scala test/services/LocalImageCacheServiceSpec.scala
  git commit -m "feat: add cascadeScale and update saveResized to cascade from previous output"
  ```

---

### Task 5: Update `fetchAndCacheAll` to reuse cascade result

**Files:**
- Modify: `app/services/LocalImageCacheService.scala`

- [ ] **Step 1: Update `fetchAndCacheAll` to remove the duplicate `scale` call**

  The existing tests `"return JPEG bytes for the requested px"`, `"add all target sizes to the registry after fetching"`, and `"skip saving sizes already in registry"` already cover the observable behaviour. No new tests are needed before this change — existing tests will catch regressions.

  In `LocalImageCacheService.scala`, find the `fetchAndCacheAll` method. Replace the `else` branch:

  **Current:**
  ```scala
  else {
    // Save all target sizes in background (saveResized skips those already in registry)
    Future(saveResized(image, sourceImg, sourcePx))
      .recover { case ex => logger.warn(s"Background save failed for ${image.title}: ${ex.getMessage}") }
    // Resize the requested size and return immediately
    val requestedImg = scale(sourceImg, requestedPx)
    val baos = new java.io.ByteArrayOutputStream()
    ImageIO.write(requestedImg, "JPEG", baos)
    Future.successful(baos.toByteArray)
  }
  ```

  **New:**
  ```scala
  else {
    // Run cascade synchronously: saves fire async inside saveResized, cascade CPU returns
    // the requested image immediately. A try/catch preserves the current behavior where
    // scaling errors (e.g. OOM) are logged and fall back to a direct scale rather than
    // propagating a failed Future to the caller.
    val imgOpt = try {
      saveResized(image, sourceImg, sourcePx, Some(requestedPx))
    } catch {
      case ex: Exception =>
        logger.warn(s"Cascade failed for ${image.title}: ${ex.getMessage}")
        None
    }
    val requestedImg = imgOpt.getOrElse(scale(sourceImg, requestedPx))
    val baos = new java.io.ByteArrayOutputStream()
    ImageIO.write(requestedImg, "JPEG", baos)
    Future.successful(baos.toByteArray)
  }
  ```

  The `try/catch` preserves the current suppression-and-log behavior for synchronous cascade failures. The fallback `scale(sourceImg, requestedPx)` also covers the edge case where `requestedPx` exceeds `sourcePx` and is absent from the cascade results.

- [ ] **Step 2: Run all tests**

  ```
  sbt "testOnly services.LocalImageCacheServiceSpec"
  ```
  Expected: all tests pass.

- [ ] **Step 3: Run the full test suite**

  ```
  sbt test
  ```
  Expected: all tests pass (including DB, integration tests if Docker is available).

- [ ] **Step 4: Commit**

  ```bash
  git add app/services/LocalImageCacheService.scala
  git commit -m "feat: fetchAndCacheAll reuses cascade result from saveResized, eliminating duplicate scale"
  ```

---

## Chunk 3: Wire benchmarks to cascade and verify improvement

### Task 6: Update benchmarks to call `cascadeScale`

**Files:**
- Modify: `test/services/SaveResizedBenchmark.scala`
- Modify: `test/services/FetchAndCacheAllBenchmark.scala`

- [ ] **Step 1: Update `SaveResizedBenchmark` — replace placeholder `cascaded` and fix `fromLargest`**

  Both methods should return a value so the JVM JIT cannot eliminate the computation as dead code. Replace both methods:

  ```scala
  /** Current approach: each target scaled independently from sourceImg. */
  @Benchmark
  def fromLargest(): Seq[BufferedImage] =
    targetWidths.map(px => svc.scale(sourceImg, px))

  /** Cascade approach: each target scaled from the previous larger output. */
  @Benchmark
  def cascaded(): Seq[(Int, BufferedImage)] =
    svc.cascadeScale(sourceImg, targetWidths)
  ```

  `targetWidths` is sorted descending in `setup()`. JMH consumes the returned `Seq` reference, preventing dead-code elimination.

- [ ] **Step 2: Update `FetchAndCacheAllBenchmark.cascaded`**

  Replace the placeholder `cascaded` method:

  ```scala
  /** Cascade approach: cascade from largest to requestedPx, then JPEG encode. */
  @Benchmark
  def cascaded(): Array[Byte] = {
    val results = svc.cascadeScale(sourceImg, cascadeWidths)
    val img = results.find(_._1 == requestedPx).map(_._2).getOrElse(sourceImg)
    encodeJpeg(img)
  }
  ```

  `cascadeWidths` in `setup()` is sorted descending and contains all target widths from the largest down to and including `requestedPx` (via `takeWhile(_ >= requestedPx)`). So `cascadeScale(sourceImg, cascadeWidths)` replicates the production cascade path: it starts from `sourceImg` (2200px) and steps down through the same intermediate sizes until it reaches `requestedPx`.

- [ ] **Step 3: Compile benchmarks**

  ```
  sbt jmh:compile
  ```
  Expected: compiles successfully.

- [ ] **Step 4: Commit**

  ```bash
  git add test/services/SaveResizedBenchmark.scala test/services/FetchAndCacheAllBenchmark.scala
  git commit -m "bench: wire cascaded methods to cascadeScale implementation"
  ```

---

### Task 7: Run benchmarks and record results

- [ ] **Step 1: Run `SaveResizedBenchmark` — both methods, capture output**

  ```bash
  mkdir -p docs/superpowers/benchmark-results
  sbt "jmh:run -i 5 -wi 3 -f 1 .*SaveResizedBenchmark" | tee docs/superpowers/benchmark-results/2026-03-10-cascade.txt
  ```
  Expected: two rows — `fromLargest` and `cascaded`. Metric is `ms/op`. `cascaded` should be faster because small sizes are scaled from smaller intermediate images rather than the full 2200×1650 source.

- [ ] **Step 2: Run `FetchAndCacheAllBenchmark` — both methods, all heights, append output**

  ```bash
  sbt "jmh:run -i 5 -wi 3 -f 1 .*FetchAndCacheAllBenchmark" | tee -a docs/superpowers/benchmark-results/2026-03-10-cascade.txt
  ```
  Expected: 16 rows (8 heights × 2 methods). For small heights (120, 180, 240, 250), `cascaded` should show meaningfully lower latency. For large heights (1100, 1650), `cascaded` and `fromLargest` will be similar — at height 1650, `cascadeWidths` contains a single element so the cascade is identical to a direct scale.

- [ ] **Step 3: Verify correctness of cascade output**

  Run the full test suite one final time to confirm no regressions:
  ```
  sbt test
  ```
  Expected: all tests pass.

- [ ] **Step 4: Commit results**

  ```bash
  git add docs/superpowers/benchmark-results/2026-03-10-cascade.txt
  git commit -m "bench: record JMH results for saveResized cascade optimization"
  ```
