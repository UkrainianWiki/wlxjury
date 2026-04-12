# Selection `monument_id` Denormalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the 38 k-row nested-loop join in the juror gallery list query (~1.8 s cold) by denormalizing `monument_id` into `selection` and flipping the join order, achieving ~4× cold speedup.

**Architecture:** Add `monument_id VARCHAR(190)` to `selection` with a backfill `UPDATE … JOIN images`, add covering index `(jury_id, round_id, rate, monument_id, page_id)`, flip `ImageDbNew`'s `imagesJoinSelection` to `STRAIGHT_JOIN selection→images`, propagate `monument_id` through the `Selection` model and all write paths, update `GalleryService` ORDER BY key from `i.monument_id` to `s.monument_id`.

**Tech Stack:** MariaDB 10.6, Flyway, ScalikeJDBC 4.3 CRUDMapper, Play 3.0, specs2, Testcontainers (MariaDB container shared per JVM via `SharedTestDb`).

---

## File Map

| Action | File |
|--------|------|
| Create | `conf/db/migration/default/V48__denormalize_selection_monument_id.sql` |
| Modify | `app/org/intracer/wmua/Selection.scala` |
| Modify | `app/db/scalikejdbc/SelectionJdbc.scala` |
| Modify | `app/db/scalikejdbc/rewrite/ImageDbNew.scala` |
| Modify | `app/services/GalleryService.scala` |
| Create | `test/org/intracer/wmua/SelectionFactorySpec.scala` |
| Modify | `test/db/scalikejdbc/SelectionQuerySpec.scala` |
| Modify | `test/db/scalikejdbc/SelectionSpec.scala` |
| Create | `test/db/scalikejdbc/ImageDbNewDbSpec.scala` |

---

## Task 1: V48 Flyway Migration

**Files:**
- Create: `conf/db/migration/default/V48__denormalize_selection_monument_id.sql`

- [ ] **Step 1: Create the migration file**

```sql
-- Add monument_id column (nullable; backfilled in step 2)
ALTER TABLE selection ADD COLUMN monument_id VARCHAR(190) DEFAULT NULL;

-- Backfill all existing selection rows from their images
UPDATE selection s
  JOIN images i ON i.page_id = s.page_id
  SET s.monument_id = i.monument_id;

-- Covering index: enables STRAIGHT_JOIN selection→images with index-only sort
-- on (jury_id, round_id) predicate + (rate DESC, monument_id ASC, page_id ASC) order
CREATE INDEX idx_selection_jury_round_rate_mon_page
  ON selection(jury_id, round_id, rate, monument_id, page_id);

ANALYZE TABLE selection;
```

- [ ] **Step 2: Verify migration applies cleanly**

Run: `sbt test:compile`

Expected: compiles successfully (Flyway runs migrations via `SharedTestDb` during test startup; a compile confirms the file is picked up). If Flyway reports a checksum error on any existing migration, you have accidentally edited an existing V-file — check `git diff conf/`.

- [ ] **Step 3: Commit the migration**

```bash
git add conf/db/migration/default/V48__denormalize_selection_monument_id.sql
git commit -m "migrate: V48 — add monument_id to selection, backfill, covering index"
```

---

## Task 2: Unit Tests for SQL Join and ORDER BY Generation

These tests cover `SelectionQuery.join()` and `SelectionQuery.query()` SQL string output. No DB access — pure string assertions.

**Files:**
- Modify: `test/db/scalikejdbc/SelectionQuerySpec.scala`

- [ ] **Step 1: Add `join` and `query` test blocks to `SelectionQuerySpec`**

Append inside the `class SelectionQuerySpec extends Specification` body, after the existing `"where" should { … }` block:

```scala
  "join" should {

    "use selection as the outer (driving) table" in {
      val j = SelectionQuery(userId = Some(1L), roundId = Some(2L)).join(monuments = false)
      j.indexOf("selection s") must beLessThan(j.indexOf("images i"))
    }

    "emit STRAIGHT_JOIN" in {
      val j = SelectionQuery(userId = Some(1L), roundId = Some(2L)).join(monuments = false)
      j must contain("STRAIGHT_JOIN")
    }

    "not include monument join when monuments = false" in {
      val j = SelectionQuery(userId = Some(1L), roundId = Some(2L)).join(monuments = false)
      j must not(contain("monument"))
    }

    "append monument join when monuments = true" in {
      val j = SelectionQuery(userId = Some(1L), roundId = Some(2L)).join(monuments = true)
      j must contain("join monument m on i.monument_id = m.id")
    }
  }

  "query" should {

    "include s.monument_id asc in ORDER BY when specified" in {
      val q = SelectionQuery(
        userId  = Some(1L),
        roundId = Some(2L),
        order   = Map("s.monument_id" -> 1)
      ).query()
      q must contain("s.monument_id asc")
    }

    "include s.rate desc in ORDER BY when specified" in {
      val q = SelectionQuery(
        userId  = Some(1L),
        roundId = Some(2L),
        order   = Map("s.rate" -> -1)
      ).query()
      q must contain("s.rate desc")
    }

    "produce no ORDER BY clause when order map is empty" in {
      val q = SelectionQuery(userId = Some(1L), roundId = Some(2L)).query()
      q must not(contain("order by"))
    }
  }
```

- [ ] **Step 2: Run these tests — verify they fail red**

Run: `sbt "testOnly db.scalikejdbc.SelectionQuerySpec"`

Expected failures:
- `"use selection as the outer"` — FAIL (current join starts with `from images i`)
- `"emit STRAIGHT_JOIN"` — FAIL (no STRAIGHT_JOIN yet)
- `"not include monument join when monuments = false"` — PASS (already correct)
- `"append monument join when monuments = true"` — PASS (already correct)
- `"include s.monument_id asc"` — PASS (orderBy is a string passthrough)
- `"include s.rate desc"` — PASS
- `"produce no ORDER BY clause"` — PASS

---

## Task 3: `Selection` Domain Model — Add `monumentId` Field

**Files:**
- Create: `test/org/intracer/wmua/SelectionFactorySpec.scala`
- Modify: `app/org/intracer/wmua/Selection.scala`

- [ ] **Step 1: Write the factory unit test (will not compile until Selection has `monumentId`)**

Create `test/org/intracer/wmua/SelectionFactorySpec.scala`:

```scala
package org.intracer.wmua

import db.scalikejdbc.{Round, User}
import org.specs2.mutable.Specification

class SelectionFactorySpec extends Specification {

  private val juror = User(fullname = "juror", email = "j@test.com", id = Some(5L))
  private val round = Round(id = Some(10L), number = 1L, contestId = 1L)

  "Selection.apply(Image, User, Round, rate)" should {

    "copy monumentId from Image" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg", monumentId = Some("13-001"))
      val sel = Selection(img, juror, round, rate = 1)
      sel.monumentId === Some("13-001")
    }

    "copy None monumentId when Image has no monument" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg", monumentId = None)
      val sel = Selection(img, juror, round, rate = 0)
      sel.monumentId === None
    }

    "set pageId from Image" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg", monumentId = Some("13-001"))
      val sel = Selection(img, juror, round, rate = 0)
      sel.pageId === 42L
    }

    "set juryId from User.getId" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg")
      val sel = Selection(img, juror, round, rate = 0)
      sel.juryId === 5L
    }

    "set roundId from Round.getId" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg")
      val sel = Selection(img, juror, round, rate = 0)
      sel.roundId === 10L
    }
  }

  "Selection.apply(Image, User, Round) without rate" should {

    "default rate to 0 and copy monumentId" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg", monumentId = Some("07-101"))
      val sel = Selection(img, juror, round)
      sel.rate      === 0
      sel.monumentId === Some("07-101")
    }
  }
}
```

- [ ] **Step 2: Confirm the test does not compile yet**

Run: `sbt test:compile`

Expected: compilation error referencing `sel.monumentId` — value not found.

- [ ] **Step 3: Add `monumentId` field to `Selection` case class**

In `app/org/intracer/wmua/Selection.scala`, add the field as the last entry (defaulted, so all existing call sites compile unchanged):

```scala
case class Selection(pageId: Long, juryId: Long, roundId: Long,
                     var rate: Int = 0, id: Option[Long] = None,
                     createdAt: Option[ZonedDateTime] = None,
                     deletedAt: Option[ZonedDateTime] = None,
                     criteriaId: Option[Int] = None,
                     monumentId: Option[String] = None) extends HasId {

  def destroy() = SelectionJdbc.destroy(pageId, juryId, roundId)

}
```

- [ ] **Step 4: Update the `Selection` companion factory methods to propagate `monumentId`**

In `app/org/intracer/wmua/Selection.scala`, update the two `apply(Image, …)` overloads in `object Selection`:

```scala
object Selection {

  def apply(img: Image, juror: User, round: Round, rate: Int): Selection =
    Selection(img.pageId, juror.getId, round.getId, rate, monumentId = img.monumentId)

  def apply(img: Image, juror: User, round: Round): Selection =
    apply(img, juror, round, 0)

  // The Page-based overloads have no monumentId to copy — leave unchanged:
  def apply(imagePage: Page, juror: User, round: Round, rate: Int): Selection =
    Selection(imagePage.id.get, juror.getId, round.getId, rate)

  def apply(imagePage: Page, juror: User, round: Round): Selection =
    apply(imagePage, juror, round, 0)
}
```

- [ ] **Step 5: Run the factory tests — verify green**

Run: `sbt "testOnly org.intracer.wmua.SelectionFactorySpec"`

Expected: all 6 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/org/intracer/wmua/Selection.scala \
        test/org/intracer/wmua/SelectionFactorySpec.scala
git commit -m "feat: add monumentId field to Selection; factory methods copy it from Image"
```

---

## Task 4: `SelectionJdbc` Read + Write Paths

**Files:**
- Modify: `test/db/scalikejdbc/SelectionSpec.scala`
- Modify: `app/db/scalikejdbc/SelectionJdbc.scala`

- [ ] **Step 1: Add failing DB tests to `SelectionSpec`**

Append inside the `"fresh database" should { … }` block in `test/db/scalikejdbc/SelectionSpec.scala`, after the last existing test (`"rate selections"`):

```scala
    "persist and read back monumentId via create" in new AutoRollbackDb {
      val created = selectionDao.create(
        pageId = -10L, rate = 1, juryId = 20L, roundId = 0L,
        monumentId = Some("13-220")
      )
      val id = created.getId
      created.monumentId === Some("13-220")
      selectionDao.findById(id).map(_.monumentId) === Some(Some("13-220"))
    }

    "persist None monumentId via create" in new AutoRollbackDb {
      val created = selectionDao.create(
        pageId = -11L, rate = 0, juryId = 20L, roundId = 0L
      )
      val id = created.getId
      created.monumentId === None
      selectionDao.findById(id).map(_.monumentId) === Some(None)
    }

    "batchInsert preserves monumentId per row" in new AutoRollbackDb {
      val selections = Seq(
        Selection(pageId = 1L, juryId = 1L, roundId = 1L, rate = 1, monumentId = Some("13-001")),
        Selection(pageId = 2L, juryId = 1L, roundId = 1L, rate = 0, monumentId = Some("07-101")),
        Selection(pageId = 3L, juryId = 1L, roundId = 1L, rate = 1, monumentId = None)
      )
      selectionDao.batchInsert(selections)
      val all = selectionDao.findAll().sortBy(_.pageId)
      all.map(_.monumentId) === Seq(Some("13-001"), Some("07-101"), None)
    }
```

- [ ] **Step 2: Run `SelectionSpec` — verify new tests fail**

Run: `sbt "testOnly db.scalikejdbc.SelectionSpec"`

Expected: the three new tests FAIL (the `create()` method has no `monumentId` parameter yet, so the test doesn't compile; fix: `monumentId` was added to `Selection` in Task 3 but `create()` signature is unchanged, so the compile error is `too many arguments`).

- [ ] **Step 3: Update `SelectionJdbc.extract()` to read `monument_id`**

In `app/db/scalikejdbc/SelectionJdbc.scala`, update both `extract` and `apply(ResultName)`:

```scala
  override def extract(rs: WrappedResultSet, c: ResultName[Selection]): Selection = Selection(
    pageId     = rs.long(c.pageId),
    juryId     = rs.long(c.juryId),
    roundId    = rs.long(c.roundId),
    rate       = rs.int(c.rate),
    id         = rs.longOpt(c.id),
    createdAt  = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
    deletedAt  = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime),
    monumentId = rs.stringOpt(c.monumentId)
  )

  def apply(c: ResultName[Selection])(rs: WrappedResultSet): Selection =
    Selection(
      pageId     = rs.long(c.pageId),
      juryId     = rs.long(c.juryId),
      roundId    = rs.long(c.roundId),
      rate       = rs.int(c.rate),
      id         = rs.longOpt(c.id),
      createdAt  = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
      deletedAt  = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime),
      monumentId = rs.stringOpt(c.monumentId)
    )
```

- [ ] **Step 4: Update `SelectionJdbc.create()` to accept and write `monumentId`**

```scala
  def create(
      pageId: Long,
      rate: Int,
      juryId: Long,
      roundId: Long,
      createdAt: Option[ZonedDateTime] = None,
      monumentId: Option[String] = None
  )(implicit session: DBSession = AutoSession): Selection = {
    val id = withSQL {
      insert
        .into(SelectionJdbc)
        .namedValues(
          column.pageId      -> pageId,
          column.rate        -> rate,
          column.juryId      -> juryId,
          column.roundId     -> roundId,
          column.monumentId  -> monumentId
        )
    }.updateAndReturnGeneratedKey()

    Selection(
      pageId     = pageId,
      juryId     = juryId,
      roundId    = roundId,
      rate       = rate,
      id         = Some(id),
      createdAt  = createdAt,
      monumentId = monumentId
    )
  }
```

- [ ] **Step 5: Update `SelectionJdbc.batchInsert()` to write `monument_id`**

The order of values in each row's `Seq` must match the order of `sqls.?` placeholders in `namedValues`.

```scala
  def batchInsert(selections: Iterable[Selection])(implicit session: DBSession = AutoSession): Unit = {
    val column = SelectionJdbc.column
    val batchParams: Seq[Seq[Any]] =
      selections.map(i => Seq(i.pageId, i.rate, i.juryId, i.roundId, i.monumentId.orNull)).toSeq
    withSQL {
      insert
        .into(SelectionJdbc)
        .namedValues(
          column.pageId     -> sqls.?,
          column.rate       -> sqls.?,
          column.juryId     -> sqls.?,
          column.roundId    -> sqls.?,
          column.monumentId -> sqls.?
        )
    }.batch(batchParams: _*).apply()
  }
```

- [ ] **Step 6: Run `SelectionSpec` — verify all tests pass**

Run: `sbt "testOnly db.scalikejdbc.SelectionSpec"`

Expected: all tests PASS (including the 3 new ones).

- [ ] **Step 7: Commit**

```bash
git add app/db/scalikejdbc/SelectionJdbc.scala \
        test/db/scalikejdbc/SelectionSpec.scala
git commit -m "feat: SelectionJdbc reads/writes monument_id; batchInsert propagates it"
```

---

## Task 5: `ImageDbNew` DB Integration Tests

These tests verify ordering correctness and count accuracy for `SelectionQuery.list()` and `.count()` after the join flip. They will initially fail because the join is still images-first.

**Files:**
- Create: `test/db/scalikejdbc/ImageDbNewDbSpec.scala`

- [ ] **Step 1: Create `ImageDbNewDbSpec.scala`**

```scala
package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import org.intracer.wmua.{Image, Selection}
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc.DBSession

class ImageDbNewDbSpec extends Specification with BeforeAll with TestDb {

  override def beforeAll(): Unit = SharedTestDb.init()

  // Fixed IDs chosen to avoid collision with other specs sharing the same container
  private val userId  = 9001L
  private val roundId = 9002L

  private def img(pageId: Long, monumentId: String): Image =
    Image(pageId, s"File:Image$pageId.jpg", None, None, 640, 480, Some(monumentId))

  private def sel(pageId: Long, rate: Int, monumentId: String): Selection =
    Selection(pageId = pageId, juryId = userId, roundId = roundId, rate = rate,
              monumentId = Some(monumentId))

  private val galleryOrder = Map("s.rate" -> -1, "s.monument_id" -> 1, "s.page_id" -> 1)

  "SelectionQuery.list" should {

    // Images:      A(pageId=1, monument=13-001, rate=1)
    //              B(pageId=2, monument=13-002, rate=1)
    //              C(pageId=3, monument=01-001, rate=0)
    // ORDER BY rate DESC, monument_id ASC, page_id ASC:
    //   rate=1, mon=13-001 → pageId=1  (A)
    //   rate=1, mon=13-002 → pageId=2  (B)
    //   rate=0, mon=01-001 → pageId=3  (C)
    "return images in rate DESC, monument_id ASC, page_id ASC order" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(1L, "13-001"), img(2L, "13-002"), img(3L, "01-001")))
      selectionDao.batchInsert(Seq(sel(1L, 1, "13-001"), sel(2L, 1, "13-002"), sel(3L, 0, "01-001")))

      val result = SelectionQuery(userId = Some(userId), roundId = Some(roundId),
                                  order = galleryOrder).list()

      result.map(_.image.pageId) === Seq(1L, 2L, 3L)
    }

    // Two images with rate=1; C (mon=01-001) < A (mon=13-001) alphabetically
    // so C must appear first when sorted monument_id ASC
    "sort by monument_id ASC when rates are equal" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(10L, "13-001"), img(11L, "01-001")))
      selectionDao.batchInsert(Seq(sel(10L, 1, "13-001"), sel(11L, 1, "01-001")))

      val result = SelectionQuery(userId = Some(userId), roundId = Some(roundId),
                                  order = galleryOrder).list()

      result.map(_.image.pageId) === Seq(11L, 10L)
    }

    // Two images same rate and monument_id → tie-break by page_id ASC
    "sort by page_id ASC as tie-breaker within same rate and monument" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(20L, "13-001"), img(21L, "13-001")))
      selectionDao.batchInsert(Seq(sel(20L, 1, "13-001"), sel(21L, 1, "13-001")))

      val result = SelectionQuery(userId = Some(userId), roundId = Some(roundId),
                                  order = galleryOrder).list()

      result.map(_.image.pageId) === Seq(20L, 21L)
    }

    "respect LIMIT and OFFSET" in new AutoRollbackDb {
      val images = (30L to 34L).map(id => img(id, s"13-0${id}"))
      imageDao.batchInsert(images)
      selectionDao.batchInsert(images.map(i => sel(i.pageId, 1, i.monumentId.get)))

      val result = SelectionQuery(
        userId  = Some(userId),
        roundId = Some(roundId),
        order   = Map("s.page_id" -> 1),
        limit   = Some(Limit(pageSize = Some(3), offset = Some(0)))
      ).list()

      result.map(_.image.pageId) === Seq(30L, 31L, 32L)
    }

    "return empty list when no selections exist for the round" in new AutoRollbackDb {
      val result = SelectionQuery(userId = Some(userId), roundId = Some(99999L)).list()
      result === Nil
    }
  }

  "SelectionQuery.count" should {

    "return the number of matching selections" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(40L, "13-001"), img(41L, "13-002"), img(42L, "07-001")))
      selectionDao.batchInsert(Seq(sel(40L, 1, "13-001"), sel(41L, 0, "13-002"), sel(42L, 1, "07-001")))

      SelectionQuery(userId = Some(userId), roundId = Some(roundId)).count() === 3
    }

    "agree with list().size" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(50L, "13-001"), img(51L, "07-001")))
      selectionDao.batchInsert(Seq(sel(50L, 1, "13-001"), sel(51L, 1, "07-001")))

      val q = SelectionQuery(userId = Some(userId), roundId = Some(roundId))
      q.count() === q.list().size
    }

    "return 0 when no selections exist" in new AutoRollbackDb {
      SelectionQuery(userId = Some(userId), roundId = Some(99998L)).count() === 0
    }
  }
}
```

- [ ] **Step 2: Run `ImageDbNewDbSpec` — verify the ordering tests fail red**

Run: `sbt "testOnly db.scalikejdbc.ImageDbNewDbSpec"`

Expected:
- `"return images in rate DESC …"` — likely PASS even before join flip (data is small, optimizer may get lucky), OR FAIL if MariaDB picks images as outer
- `"sort by monument_id ASC when rates are equal"` — FAIL (ordering relies on `s.monument_id`, which is not yet used in the join/ORDER BY correctly)

Note the actual failure mode so you can confirm the right tests go red-then-green.

---

## Task 6: Flip the Join in `ImageDbNew` and Update `GalleryService`

**Files:**
- Modify: `app/db/scalikejdbc/rewrite/ImageDbNew.scala`
- Modify: `app/services/GalleryService.scala`

- [ ] **Step 1: Flip `imagesJoinSelection` in `ImageDbNew`**

In `app/db/scalikejdbc/rewrite/ImageDbNew.scala`, change the `imagesJoinSelection` `val` inside `SelectionQuery`:

```scala
    private val imagesJoinSelection =
      """ from selection s
        |STRAIGHT_JOIN images i
        |on i.page_id = s.page_id""".stripMargin
```

The `join(monuments)` method that appends `\n join monument m on i.monument_id = m.id` remains unchanged — `images i` is still in the FROM clause, just now as the inner table.

- [ ] **Step 2: Update the order key in `GalleryService`**

In `app/services/GalleryService.scala`, change the `order` map in the `selectionQuery` helper (around line 90):

```scala
      order = Map("s.rate" -> -1, "s.monument_id" -> 1, "s.page_id" -> 1),
```

(Change `"rate"` → `"s.rate"` and `"i.monument_id"` → `"s.monument_id"`; `"s.page_id"` was already correct.)

- [ ] **Step 3: Run `SelectionQuerySpec` — verify all tests pass now**

Run: `sbt "testOnly db.scalikejdbc.SelectionQuerySpec"`

Expected: all tests PASS (join is now selection-first with STRAIGHT_JOIN).

- [ ] **Step 4: Run `ImageDbNewDbSpec` — verify all tests pass**

Run: `sbt "testOnly db.scalikejdbc.ImageDbNewDbSpec"`

Expected: all tests PASS.

- [ ] **Step 5: Run `RegionStatSpec` to confirm byRegion queries still work**

Run: `sbt "testOnly db.scalikejdbc.RegionStatSpec"`

Expected: all tests PASS (the `byRegionStat` query has its own SQL string and is unaffected by the join flip; the `byRegion` path in `query()` still builds `from selection s STRAIGHT_JOIN images i … join monument m on i.monument_id = m.id`).

- [ ] **Step 6: Commit**

```bash
git add app/db/scalikejdbc/rewrite/ImageDbNew.scala \
        app/services/GalleryService.scala \
        test/db/scalikejdbc/ImageDbNewDbSpec.scala
git commit -m "perf: STRAIGHT_JOIN selection→images + s.monument_id ORDER BY in SelectionQuery"
```

---

## Task 7: Full Test Suite + Final Commit

- [ ] **Step 1: Compile the full test suite**

Run: `sbt test:compile`

Expected: no errors.

- [ ] **Step 2: Run the full test suite**

Run: `sbt test`

Expected: all tests PASS. If any spec fails, read the failure message and fix before proceeding.

- [ ] **Step 3: Run the affected specs explicitly as a sanity check**

```bash
sbt "testOnly db.scalikejdbc.SelectionSpec \
             db.scalikejdbc.SelectionQuerySpec \
             db.scalikejdbc.ImageDbNewDbSpec \
             db.scalikejdbc.RegionStatSpec \
             org.intracer.wmua.SelectionFactorySpec"
```

Expected: all PASS.

- [ ] **Step 4: Final commit if any files were left unstaged**

```bash
git status
# Stage any remaining changes, then:
git commit -m "chore: ensure all monument_id denormalization changes are committed"
```
