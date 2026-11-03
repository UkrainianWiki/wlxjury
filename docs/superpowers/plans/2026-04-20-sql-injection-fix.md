# SQL Injection Fix: ImageDbNew.scala `where()` Region Parameter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate SQL injection in `SelectionQuery.where()` by replacing raw string interpolation of the `regions: Set[String]` parameter with ScalikeJDBC's `sqls""` parameterised interpolator, and add tests that prove the vulnerability exists before the fix and is gone after it.

**Architecture:** Change `where()` to return `SQLSyntax` instead of `String`, change `query()` to return `SQLSyntax`, update all callers. Use `sqls.in(col, seq)` for IN clauses and `sqls"col like $param"` for LIKE clauses so region values become JDBC `?` bind parameters rather than inline SQL text.

**Tech Stack:** Scala 2.13, ScalikeJDBC 4.3, specs2 / MUnit, MariaDB 10.6 via Testcontainers

---

## Files

| File | Change |
|---|---|
| `app/db/scalikejdbc/rewrite/ImageDbNew.scala` | Fix `where()`, `query()`, `list()`, `count()`, `imageRank()`, `imageRankSql()`, remove `single()` |
| `test/db/scalikejdbc/SelectionQuerySpec.scala` | Add pre-fix injection proof test; update existing tests for new `SQLSyntax` return type; add post-fix parameterisation tests |
| `test/db/scalikejdbc/ImagesSqlSpec.scala` | Update `check` helper and all tests to compare `SQLSyntax.value` (template with `?`) and `.parameters` |
| `test/db/scalikejdbc/ImageDbNewDbSpec.scala` | Add DB-level injection test: unclosed-quote payload throws before fix, returns 0 rows after fix |

---

> **Status note:** Task 0 (branch coverage) is already complete — 16 tests were added and pass green.

---

### Task 0: ✅ Add branch-coverage tests for all untested `where()` / `query()` paths (DONE)

**Files modified:**
- `test/db/scalikejdbc/SelectionQuerySpec.scala` — added 4 `where()` branch tests
- `test/db/scalikejdbc/ImagesSqlSpec.scala` — added 8 `query()` structural tests
- `test/db/scalikejdbc/ImageDbNewDbSpec.scala` — added 2 DB-level region filter tests

**Branches now covered that were previously missing:**

| File | Test added |
|---|---|
| `SelectionQuerySpec` | single long region (>2 chars) → `adm0 IN` |
| `SelectionQuerySpec` | multiple short regions → `adm0 IN` |
| `SelectionQuerySpec` | multiple long regions → `adm0 IN` |
| `SelectionQuerySpec` | `subRegions=true` → `adm1 IN` |
| `ImagesSqlSpec` | `idOnly=true` column selection |
| `ImagesSqlSpec` | `ranked=true` ROW_NUMBER() |
| `ImagesSqlSpec` | optimized count path |
| `ImagesSqlSpec` | wrapped count path (regions present) |
| `ImagesSqlSpec` | LIMIT/OFFSET suffix |
| `ImagesSqlSpec` | `noLimit=true` suppresses LIMIT |
| `ImagesSqlSpec` | multi-region list triggers monument JOIN |
| `ImagesSqlSpec` | `byRegion + subRegions=true` uses `adm1` |
| `ImageDbNewDbSpec` | region LIKE filter returns correct rows |
| `ImageDbNewDbSpec` | region LIKE filter returns empty for no match |

All 52 tests (16 new + 36 existing) pass.

---

### Task 1: Add pre-fix vulnerability proof tests (compile against current String API)

**Files:**
- Modify: `test/db/scalikejdbc/SelectionQuerySpec.scala`

- [ ] **Step 1: Open SelectionQuerySpec and read current regionId value**

`regionId = "80"` is 2 chars → hits the `LIKE` branch.  
`regionId = "13-001"` is 6 chars → hits the `IN` branch.  
Both are used in tests already.

- [ ] **Step 2: Add injection proof tests to `SelectionQuerySpec` (after existing `"where"` examples)**

In `test/db/scalikejdbc/SelectionQuerySpec.scala`, insert inside the `"where" should` block before the closing `}`:

```scala
    // VULNERABILITY PROOF — these two tests PASS before the fix because the
    // payload is embedded verbatim in the SQL string.
    // They are DELETED in Task 5 once where() returns SQLSyntax.

    "embed region verbatim in LIKE clause (sqli proof)" in {
      val payload = "uk' OR '1'='1"
      SelectionQuery(regions = Set(payload)).where() must contain(payload)
    }

    "embed region verbatim in IN clause (sqli proof)" in {
      val payload = "uk' UNION SELECT 1,2,3,4--"
      SelectionQuery(regions = Set(payload, "xx")).where() must contain("UNION SELECT")
    }
```

- [ ] **Step 3: Run the tests to confirm they pass (vulnerability confirmed)**

```bash
sbt "testOnly db.scalikejdbc.SelectionQuerySpec"
```

Expected: all pass, including the two new injection proof tests.

- [ ] **Step 4: Commit**

```bash
git add test/db/scalikejdbc/SelectionQuerySpec.scala
git commit -m "test: add SQL injection proof tests for SelectionQuery.where() (pre-fix)"
```

---

### Task 2: Add DB-level pre-fix injection test

**Files:**
- Modify: `test/db/scalikejdbc/ImageDbNewDbSpec.scala`

- [ ] **Step 1: Add import for exception matcher**

The file already has `import org.specs2.mutable.Specification` and `import org.specs2.specification.BeforeAll`. No new imports needed — `throwAn` is available from specs2 `Matchers`.

- [ ] **Step 2: Add a new `"SQL injection"` block inside `ImageDbNewDbSpec`**

Append inside the class, after the `"SelectionQuery.count" should` block:

```scala
  "SQL injection via regions parameter" should {

    // BEFORE FIX: an unclosed-quote payload causes a MariaDB syntax error.
    // AFTER  FIX: the same payload is a bind parameter; query returns Seq.empty.
    "throw a SQL syntax exception when region contains an unescaped quote (pre-fix proof)" in new AutoRollbackDb {
      SelectionQuery(
        userId  = Some(userId),
        roundId = Some(roundId),
        regions = Set("uk'")       // breaks: WHERE i.monument_id like 'uk'%'
      ).list() must throwAn[Exception]
    }
  }
```

- [ ] **Step 3: Run the new test to confirm it passes (exception thrown = vulnerability exists)**

```bash
sbt "testOnly db.scalikejdbc.ImageDbNewDbSpec"
```

Expected: the new test passes (an exception IS thrown before the fix).

- [ ] **Step 4: Commit**

```bash
git add test/db/scalikejdbc/ImageDbNewDbSpec.scala
git commit -m "test: add DB-level SQL injection proof test for SelectionQuery (pre-fix)"
```

---

### Task 3: Implement the fix — change `where()` to return `SQLSyntax`

**Files:**
- Modify: `app/db/scalikejdbc/rewrite/ImageDbNew.scala`

- [ ] **Step 1: Change the `where()` method signature and body**

Replace the entire `where` method (lines 170–200) with:

```scala
    def where(count: Boolean = false): SQLSyntax = {
      val col = SQLSyntax.createUnsafely(s"m.$regionColumn")

      val conditions: Seq[SQLSyntax] = Seq(
        userId.map(id => sqls"s.jury_id = $id"),
        roundId.map(id => sqls"s.round_id = $id"),
        rate.map(r => sqls"s.rate = $r"),
        rated.map { r =>
          val ratedCond: SQLSyntax = if (r) sqls"s.rate > 0" else sqls"s.rate = 0"
          withPageId.fold(ratedCond) { pageId =>
            sqls"($ratedCond or s.page_id = $pageId)"
          }
        },
        regions.headOption.map { _ =>
          if (regions.headOption.exists(_.length > 2)) {
            sqls.in(col, regions.toSeq)
          } else if (regions.size > 1) {
            sqls.in(SQLSyntax.createUnsafely("m.adm0"), regions.toSeq)
          } else {
            val likeParam = regions.head + "%"
            sqls"i.monument_id like $likeParam"
          }
        }
      ).flatten

      conditions.headOption.fold(sqls"") { _ =>
        sqls" where ${SQLSyntax.join(conditions, sqls"and")}"
      }
    }
```

- [ ] **Step 2: Verify file compiles**

```bash
sbt test:compile
```

Expected: compile errors in `query()` because it still concatenates `where(count)` as if it were a `String`.  
This is expected — proceed to the next task.

---

### Task 4: Change `query()` to return `SQLSyntax` and update all callers

**Files:**
- Modify: `app/db/scalikejdbc/rewrite/ImageDbNew.scala`

- [ ] **Step 1: Change `query()` return type and body**

Replace the entire `query` method (lines 45–96) with:

```scala
    def query(
        count: Boolean = false,
        idOnly: Boolean = false,
        noLimit: Boolean = false,
        byRegion: Boolean = false,
        ranked: Boolean = false
    ): SQLSyntax = {

      val columnsStr: String =
        "select  " +
          (if (count || idOnly) {
             "i.page_id as pi_on_i" +
               (if (ranked)
                  s", ROW_NUMBER() over (${orderBy()}) ranked"
                else "") +
               (if (grouped)
                  ", sum(s.rate) as rate, count(s.rate) as rate_count"
                else "")
           } else {
             if (byRegion)
               s"m.$regionColumn, count(DISTINCT i.page_id)"
             else if (!grouped)
               sqls"""${i.result.*}, ${s.result.*} """.value
             else
               sqls"""sum(s.rate) as rate, count(s.rate) as rate_count, ${i.result.*} """.value
           })

      val groupByStr = if (byRegion) {
        s" group by m.$regionColumn"
      } else if (grouped) {
        " group by s.page_id"
      } else ""

      val structureStr =
        columnsStr +
          join(monuments = regions.size > 1 || byRegion) +
          groupByStr +
          (if (!(count || byRegion)) orderBy() else "")

      val mainSql =
        sqls"${SQLSyntax.createUnsafely(structureStr)} ${where(count)}"

      if (count && regions.isEmpty && !byRegion) {
        val countExpr = SQLSyntax.createUnsafely("COUNT(DISTINCT s.page_id)")
        sqls"select $countExpr from selection s ${where()}"
      } else if (count) {
        sqls"select count(t.pi_on_i) from ($mainSql) t"
      } else if (noLimit || byRegion) {
        mainSql
      } else {
        sqls"$mainSql ${SQLSyntax.createUnsafely(limitSql())}"
      }
    }
```

- [ ] **Step 2: Update `list()`**

Replace:
```scala
    def list()(implicit session: DBSession = autoSession): Seq[ImageWithRating] = {
      postProcessor(SQL(query()).map(reader).list())
    }
```
With:
```scala
    def list()(implicit session: DBSession = autoSession): Seq[ImageWithRating] = {
      postProcessor(sql"${query()}".map(reader).list())
    }
```

- [ ] **Step 3: Update `count()`**

Replace:
```scala
    def count()(implicit session: DBSession = autoSession): Int = {
      single(query(count = true))
    }
```
With:
```scala
    def count()(implicit session: DBSession = autoSession): Int = {
      sql"${query(count = true)}".map(_.int(1)).single().getOrElse(0)
    }
```

- [ ] **Step 4: Update `imageRankSql()` to accept and return `SQLSyntax`**

Replace:
```scala
    def imageRankSql(pageId: Long, sql: String): String = {
      val result = if (driver == "mysql") {
        s"""SELECT ranked, pi_on_i
            FROM ($sql) t
            WHERE pi_on_i = $pageId;"""
      } else {
        s"""SELECT rank FROM
            (SELECT rownum as rank, t.pi_on_i as page_id
            FROM  ($sql) t) t2
        WHERE page_id = $pageId;"""
      }
      result
    }
```
With:
```scala
    def imageRankSql(pageId: Long, innerSql: SQLSyntax): SQLSyntax = {
      if (driver == "mysql") {
        sqls"SELECT ranked, pi_on_i FROM ($innerSql) t WHERE pi_on_i = $pageId"
      } else {
        sqls"SELECT rank FROM (SELECT rownum as rank, t.pi_on_i as page_id FROM ($innerSql) t) t2 WHERE page_id = $pageId"
      }
    }
```

- [ ] **Step 5: Update `imageRank()` and remove `single()`**

Replace:
```scala
    def imageRank(pageId: Long)(implicit session: DBSession = autoSession): Int = {
      single(
        imageRankSql(
          pageId,
          query(ranked = true, idOnly = true, noLimit = true)
        )
      )
    }

    def byRegionStat() ...

    def single(sql: String)(implicit session: DBSession = autoSession): Int = {
      SQL(sql).map(_.int(1)).single().getOrElse(0)
    }
```

Replace `imageRank()` with:
```scala
    def imageRank(pageId: Long)(implicit session: DBSession = autoSession): Int = {
      val inner = query(ranked = true, idOnly = true, noLimit = true)
      sql"${imageRankSql(pageId, inner)}".map(_.int(1)).single().getOrElse(0)
    }
```

And delete the `single(sql: String)` method entirely.

- [ ] **Step 6: Compile**

```bash
sbt test:compile
```

Expected: compile errors only in `SelectionQuerySpec` and `ImagesSqlSpec` (they still test against `String`). Fix those in Tasks 5–6.

---

### Task 5: Update `SelectionQuerySpec` for `SQLSyntax` return type

**Files:**
- Modify: `test/db/scalikejdbc/SelectionQuerySpec.scala`

- [ ] **Step 1: Replace the entire `"where" should` block**

The existing tests check `where()` against String output. Replace the whole `"where" should` block with:

```scala
  "where" should {
    "no conditions" in {
      SelectionQuery().where() === sqls""
    }

    "userId" in {
      val w = SelectionQuery(userId = Some(userId)).where()
      w.value === " where s.jury_id = ?"
      w.parameters === Seq(userId)
    }

    "roundId" in {
      val w = SelectionQuery(roundId = Some(roundId)).where()
      w.value === " where s.round_id = ?"
      w.parameters === Seq(roundId)
    }

    "rate" in {
      val rate = 1
      val w = SelectionQuery(rate = Some(rate)).where()
      w.value === " where s.rate = ?"
      w.parameters === Seq(rate)
    }

    "rated" in {
      val w = SelectionQuery(rated = Some(true)).where()
      w.value === " where s.rate > 0"
      w.parameters === Seq.empty
    }

    "not rated" in {
      val w = SelectionQuery(rated = Some(false)).where()
      w.value === " where s.rate = 0"
      w.parameters === Seq.empty
    }

    "region — single short code uses LIKE with bind parameter (injection blocked)" in {
      val injection = "uk' OR '1'='1"
      val w = SelectionQuery(regions = Set(injection)).where()
      w.value must contain("monument_id like ?")
      w.value must not contain "OR '1'='1"
      w.parameters must contain(injection + "%")
    }

    "region — multi-code uses IN with bind parameters (injection blocked)" in {
      val injection = "uk' UNION SELECT 1,2,3--"
      val w = SelectionQuery(regions = Set(injection, "de")).where()
      w.value must contain("in (?, ?)")
      w.value must not contain "UNION SELECT"
      w.parameters must contain(injection)
    }

    "withPageId alone" in {
      SelectionQuery(withPageId = Some(pageId)).where() === sqls""
    }

    "userId and roundId" in {
      val w = SelectionQuery(userId = Some(userId), roundId = Some(roundId)).where()
      w.value === " where s.jury_id = ? and s.round_id = ?"
      w.parameters === Seq(userId, roundId)
    }

    "userId and roundId and rated" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId), rated = Some(true)
      ).where()
      w.value === " where s.jury_id = ? and s.round_id = ? and s.rate > 0"
      w.parameters === Seq(userId, roundId)
    }

    "userId and roundId and not rated" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId), rated = Some(false)
      ).where()
      w.value === " where s.jury_id = ? and s.round_id = ? and s.rate = 0"
      w.parameters === Seq(userId, roundId)
    }

    "userId and roundId and rated and withPageId" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId),
        rated = Some(true), withPageId = Some(pageId)
      ).where()
      w.value === " where s.jury_id = ? and s.round_id = ? and (s.rate > 0 or s.page_id = ?)"
      w.parameters === Seq(userId, roundId, pageId)
    }

    "userId and roundId and not rated and withPageId" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId),
        rated = Some(false), withPageId = Some(pageId)
      ).where()
      w.value === " where s.jury_id = ? and s.round_id = ? and (s.rate = 0 or s.page_id = ?)"
      w.parameters === Seq(userId, roundId, pageId)
    }

    "userId and roundId and rated and region" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId),
        rated = Some(true), regions = Set(regionId)
      ).where()
      w.value must contain("s.jury_id = ?")
      w.value must contain("s.round_id = ?")
      w.value must contain("s.rate > 0")
      w.value must contain("monument_id like ?")
      w.parameters must contain(regionId + "%")
    }

    "userId and roundId and not rated and region" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId),
        rated = Some(false), regions = Set(regionId)
      ).where()
      w.value must contain("s.rate = 0")
      w.value must contain("monument_id like ?")
      w.parameters must contain(regionId + "%")
    }
  }
```

Note: You must add `import scalikejdbc._` (for `sqls`) to the top of the file if not already present.

- [ ] **Step 2: Add `sqls` import if missing**

Check the imports at the top of `SelectionQuerySpec.scala`. Add if not present:
```scala
import scalikejdbc._
```

- [ ] **Step 3: Compile**

```bash
sbt test:compile
```

Expected: only `ImagesSqlSpec` has remaining errors.

---

### Task 6: Update `ImagesSqlSpec` for `SQLSyntax` return type

**Files:**
- Modify: `test/db/scalikejdbc/ImagesSqlSpec.scala`

- [ ] **Step 1: Update the `check` helper and add a `checkParams` helper**

Replace the `check` helper:
```scala
  private def check(
      q: SelectionQuery,
      expected: String,
      f: SelectionQuery => String = _.query()
  ): Unit = {
    assertEquals(foldSpace(f(q)), foldSpace(expected))
  }
```
With:
```scala
  import scalikejdbc._

  private def check(
      q: SelectionQuery,
      expectedTemplate: String,
      expectedParams: Seq[Any] = Seq.empty,
      f: SelectionQuery => SQLSyntax = _.query()
  ): Unit = {
    val syntax = f(q)
    assertEquals(foldSpace(syntax.value), foldSpace(expectedTemplate))
    assertEquals(syntax.parameters.toSeq, expectedParams)
  }
```

- [ ] **Step 2: Update all existing `check` calls**

The existing tests check against exact SQL strings. After the fix, numeric parameters become `?`. Update each call:

```scala
  dbTest("list - all") { implicit session =>
    check(
      SelectionQuery(),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id"""
    )
  }

  dbTest("list - by user") { implicit session =>
    check(
      SelectionQuery(userId = Some(123)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.jury_id = ?""",
      expectedParams = Seq(123L)
    )
  }

  dbTest("list - by round") { implicit session =>
    check(
      SelectionQuery(roundId = Some(234)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.round_id = ?""",
      expectedParams = Seq(234L)
    )
  }

  dbTest("list - by rate") { implicit session =>
    check(
      SelectionQuery(rate = Some(1)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.rate = ?""",
      expectedParams = Seq(1)
    )
  }

  dbTest("list - by user, round, rate") { implicit session =>
    check(
      SelectionQuery(userId = Some(2), roundId = Some(3), rate = Some(1)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.jury_id = ? and s.round_id = ? and s.rate = ?""",
      expectedParams = Seq(2L, 3L, 1)
    )
  }

  dbTest("grouped - group by page list by round") { implicit session =>
    check(
      SelectionQuery(roundId = Some(3), grouped = true),
      s"""select sum(s.rate) as rate, count(s.rate) as rate_count, $imageFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.round_id = ?
        group by s.page_id""",
      expectedParams = Seq(3L)
    )
  }

  dbTest("by region - list uses bind parameter not raw string") { implicit session =>
    check(
      SelectionQuery(regions = Set("12")),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where i.monument_id like ?""",
      expectedParams = Seq("12%")
    )
  }

  dbTest("by region - stat") { implicit session =>
    def regionStatQuery(q: SelectionQuery): SQLSyntax = q.query(byRegion = true)

    check(
      SelectionQuery(),
      s"""select m.adm0, count(DISTINCT i.page_id) from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          join monument m
          on i.monument_id = m.id
          group by m.adm0""",
      f = regionStatQuery
    )
  }
```

- [ ] **Step 3: Compile and run**

```bash
sbt test:compile && sbt "testOnly db.scalikejdbc.ImagesSqlSpec"
```

Expected: all pass.

---

### Task 7: Update the DB injection test to assert the fix works

**Files:**
- Modify: `test/db/scalikejdbc/ImageDbNewDbSpec.scala`

- [ ] **Step 1: Replace the pre-fix proof test with the post-fix safety test**

Locate the `"SQL injection via regions parameter"` block added in Task 2 and replace:

```scala
  "SQL injection via regions parameter" should {

    "treat injection payload as literal data, return empty result (not an exception)" in new AutoRollbackDb {
      // Before fix: WHERE i.monument_id like 'uk'%' → SQL syntax error (exception)
      // After fix:  WHERE i.monument_id like ?      bind: ["uk'%"] → 0 results, no error
      SelectionQuery(
        userId  = Some(userId),
        roundId = Some(roundId),
        regions = Set("uk'")
      ).list() === Nil
    }

    "multi-region injection payload is treated as data, not executed SQL" in new AutoRollbackDb {
      val payload = "uk' UNION SELECT 1,2,3,4,5,6,7,8,9,10,11,12,13,14--"
      SelectionQuery(
        userId  = Some(userId),
        roundId = Some(roundId),
        regions = Set(payload, "de")
      ).list() === Nil
    }
  }
```

- [ ] **Step 2: Run the full test suite**

```bash
sbt test
```

Expected: all tests pass.

- [ ] **Step 3: Commit all changes**

```bash
git add app/db/scalikejdbc/rewrite/ImageDbNew.scala \
        test/db/scalikejdbc/SelectionQuerySpec.scala \
        test/db/scalikejdbc/ImagesSqlSpec.scala \
        test/db/scalikejdbc/ImageDbNewDbSpec.scala
git commit -m "fix: parameterise regions in SelectionQuery.where() to block SQL injection"
```

---

## Notes

- `rankedList(where: String)` and `rangeRankedList(where: String)` in `ImageDbNew.scala` embed a `where: String` argument directly in SQL. These methods have no callers anywhere in the codebase and are dead code. They are out of scope for this fix but should be deleted in a follow-up.
- `byRegionStat()` uses `userId` (Long) and `roundId` (Long) via string interpolation — no injection risk since they are numeric. No change required.
- The `regionColumn` value (`"adm0"` or `"adm1"`) is derived from a `Boolean` field, not from user input. Using `SQLSyntax.createUnsafely` for it is correct.
