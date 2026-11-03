# AutoRollback DB Test Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-test container+app startup in DB integration tests with a shared container (started once per JVM) and ScalikeJDBC `AutoRollback` for per-test transaction isolation, dramatically reducing test suite runtime.

**Architecture:** A global singleton `SharedTestDb` object lazily starts one MariaDB Testcontainer, configures ScalikeJDBC's `ConnectionPool`, and runs Flyway migrations — once per JVM. Specs2 tests use `new AutoRollbackDb { }` per-test anonymous instances (the correct specs2 pattern for `scalikejdbc.specs2.mutable.AutoRollback`) with a spec-level `BeforeAll` hook calling `SharedTestDb.init()`. For munit, a `FunFixture[(DB, DBSession)]` wraps each test in a transaction and rolls it back in teardown. DAO custom write methods (currently using `DB.localTx` or no implicit session parameter) are updated to accept `implicit session: DBSession = AutoSession`, making them participate in the AutoRollback transaction.

**Tech Stack:** ScalikeJDBC 4.3, `scalikejdbc-test` 4.3, specs2, munit, Flyway 9, Testcontainers (MariaDB 10.6)

---

## Critical Background: How `implicit def session` Works in These DAOs

Every DAO object (`ContestJuryJdbc`, `SelectionJdbc`, `User`, `Round`, `ImageJdbc`, etc.) declares:
```scala
implicit def session: DBSession = autoSession
```
This object-scope implicit is used by **all methods in the object that do not have their own `implicit session` parameter**.

When a test in `new AutoRollbackDb { }` calls `selectionDao.create(...)`:
- The call site has `AutoRollbackLike`'s `implicit val session: DBSession` in scope
- BUT `create` has no `implicit session` parameter
- Inside `create`'s body, `withSQL { ... }.updateAndReturnGeneratedKey()` finds `autoSession` from the object scope
- The test's AutoRollback session is **never passed in** → the write auto-commits, bypassing rollback

**Methods that DO work with AutoRollback (inherited CRUDMapper methods):** `findAll()`, `findById()`, `where(...).apply()`, etc. — these have `(implicit session: DBSession = autoSession)` parameters, so the test scope's session IS forwarded.

**Fix:** Add `(implicit session: DBSession = AutoSession)` to each custom write method called in tests. The method-level parameter **shadows** the object-level `implicit def session` within the method body. Production callers are unaffected — they get `AutoSession` by default.

---

## Files Affected

| File | Action |
|---|---|
| `build.sbt` | Add `scalikejdbc-test` dependency |
| `test/db/scalikejdbc/SharedTestDb.scala` | **New** — singleton container + ConnectionPool + Flyway |
| `test/db/scalikejdbc/AutoRollbackDb.scala` | **New** — anonymous-instance trait for specs2 |
| `test/db/scalikejdbc/AutoRollbackMunitDb.scala` | **New** — munit rollback fixture trait |
| `test/db/scalikejdbc/PlayTestDb.scala` | **New** — per-suite Play app for async-actor tests |
| `test/db/scalikejdbc/TestDb.scala` | Remove `testDbApp`/`withDb`; add `implicit session` to helpers |
| `app/db/scalikejdbc/ContestJuryJdbc.scala` | Add `implicit session` to `create`, `setImagesSource`, `batchInsert` |
| `app/db/scalikejdbc/SelectionJdbc.scala` | Add `implicit session` to `create`, `batchInsert`, `rate` |
| `app/db/scalikejdbc/User.scala` | Add `implicit session` to both `create` overloads |
| `app/db/scalikejdbc/Round.scala` | Add `implicit session` to `create` |
| `app/db/scalikejdbc/ImageJdbc.scala` | Add `implicit session` to `batchInsert` |
| `app/db/scalikejdbc/CategoryJdbc.scala` | Add `implicit session` to `findOrInsert` |
| `app/db/scalikejdbc/CategoryJdbc.scala` (`CategoryLinkJdbc`) | Add `implicit session` to `addToCategory`, `batchInsert` |
| `test/db/scalikejdbc/RoundSpec.scala` | Migrate |
| `test/db/scalikejdbc/SelectionSpec.scala` | Migrate |
| `test/db/scalikejdbc/UserDbSpec.scala` | Migrate |
| `test/db/scalikejdbc/ImageSpec.scala` | Migrate |
| `test/db/scalikejdbc/ImageJdbcSpec.scala` | Migrate |
| `test/db/scalikejdbc/JurorImagesSpec.scala` | Migrate |
| `test/db/scalikejdbc/ImagesSqlSpec.scala` | Remove `TestDb` (no DB needed) |
| `test/db/scalikejdbc/ContestSpec.scala` | Migrate (munit) |
| `test/db/scalikejdbc/RoundUsersSpec.scala` | Per-suite `PlayTestDb` (async actor, no rollback) |

---

## Task 1: Add `scalikejdbc-test` dependency

**Files:**
- Modify: `build.sbt`

- [ ] **Step 1: Add the dependency**

In `build.sbt`, after the existing scalikejdbc lines (~line 90):
```scala
"org.scalikejdbc" %% "scalikejdbc-test" % ScalikejdbcVersion % Test,
```
`ScalikejdbcVersion` is already defined as `"4.3.2"`.

- [ ] **Step 2: Compile**
```bash
sbt test:compile
```
Expected: SUCCESS.

- [ ] **Step 3: Commit**
```bash
git add build.sbt
git commit -m "build: add scalikejdbc-test for AutoRollback support"
```

---

## Task 2: Create `SharedTestDb` — global JVM singleton

**Files:**
- Create: `test/db/scalikejdbc/SharedTestDb.scala`

Starts container ONCE per JVM, configures ScalikeJDBC's default `ConnectionPool`, runs Flyway migrations, registers JVM shutdown hook.

- [ ] **Step 1: Write the file**

```scala
package db.scalikejdbc

import com.dimafeng.testcontainers.MariaDBContainer
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName
import scalikejdbc.ConnectionPool

object SharedTestDb {

  /** Lazily starts the container and wires the default ScalikeJDBC pool.
   *  Calling `init()` multiple times is safe — initialization happens once. */
  lazy val initialized: Boolean = {
    val container = MariaDBContainer(
      dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
      dbName         = "wlxjury",
      dbUsername     = "WLXJURY_DB_USER",
      dbPassword     = "WLXJURY_DB_PASSWORD"
    )
    container.start()

    Class.forName(container.driverClassName)
    ConnectionPool.singleton(container.jdbcUrl, container.username, container.password)

    Flyway
      .configure()
      .dataSource(container.jdbcUrl, container.username, container.password)
      .locations("classpath:db/migration/default")
      .load()
      .migrate()

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      ConnectionPool.closeAll()
      container.stop()
    }))

    true
  }

  /** Call from spec `beforeAll()` to trigger lazy initialisation. */
  def init(): Unit = { val _ = initialized }
}
```

- [ ] **Step 2: Compile**
```bash
sbt test:compile
```

- [ ] **Step 3: Commit**
```bash
git add test/db/scalikejdbc/SharedTestDb.scala
git commit -m "test: add SharedTestDb singleton for one-time container init"
```

---

## Task 3: Create `AutoRollbackDb` trait for specs2

**Files:**
- Create: `test/db/scalikejdbc/AutoRollbackDb.scala`

Used as `new AutoRollbackDb { ... }` **inside each test block** (not mixed into the spec class). Each anonymous instance constructs with a fresh `_db.begin()` and rolls back after the test via specs2's `after` mechanism.

- [ ] **Step 1: Write the file**

```scala
package db.scalikejdbc

import scalikejdbc.specs2.mutable.{AutoRollback => SJAutoRollback}

/** Use as a per-test anonymous instance inside specs2 test blocks:
 *
 *  {{{
 *    class MySpec extends Specification with BeforeAll {
 *      override def beforeAll(): Unit = SharedTestDb.init()
 *
 *      "insert something" in new AutoRollbackDb {
 *        // implicit val session: DBSession is provided by AutoRollbackLike
 *        myDao.create(...)
 *        myDao.findAll() === Seq(...)
 *      }
 *    }
 *  }}}
 *
 *  Each `new AutoRollbackDb` opens a transaction and rolls it back after the test.
 *  The spec class must call SharedTestDb.init() in beforeAll to ensure the
 *  ScalikeJDBC ConnectionPool is ready.
 */
trait AutoRollbackDb extends SJAutoRollback with TestDb
```

- [ ] **Step 2: Compile**
```bash
sbt test:compile
```

- [ ] **Step 3: Commit**
```bash
git add test/db/scalikejdbc/AutoRollbackDb.scala
git commit -m "test: add AutoRollbackDb anonymous-instance trait for specs2"
```

---

## Task 4: Create `AutoRollbackMunitDb` trait for munit

**Files:**
- Create: `test/db/scalikejdbc/AutoRollbackMunitDb.scala`

Uses `FunFixture[(DB, DBSession)]` — retaining the `DB` handle through teardown for correct rollback. `session.conn` is `private[scalikejdbc]` so the `DB` object must be kept separately.

- [ ] **Step 1: Write the file**

```scala
package db.scalikejdbc

import munit.{FunFixtures, FunSuite}
import scalikejdbc.{ConnectionPool, DB, DBSession}

/** Mix into munit FunSuite DB tests:
 *
 *  {{{
 *    class MySpec extends FunSuite with AutoRollbackMunitDb {
 *      dbTest("insert something") { implicit session =>
 *        myDao.create(...)
 *        assertEquals(myDao.findAll(), List(...))
 *      }
 *    }
 *  }}}
 */
trait AutoRollbackMunitDb extends FunFixtures with TestDb { self: FunSuite =>

  /** Opens a transaction; teardown always rolls it back.
   *  Carries both (DB, DBSession) because `DB.rollbackIfActive()` requires
   *  the original DB handle — it cannot be reconstructed from the session alone
   *  (`session.conn` is package-private in ScalikeJDBC). */
  private val dbFixture: FunFixture[(DB, DBSession)] = FunFixture(
    setup = _ => {
      SharedTestDb.init()
      val db = DB(ConnectionPool.borrow())
      db.begin()
      (db, db.withinTxSession())
    },
    teardown = { case (db, _) =>
      db.rollbackIfActive()
      db.close()
    }
  )

  /** Declare a test that receives an implicit DBSession. */
  def dbTest(name: String)(body: DBSession => Any): Unit =
    dbFixture.test(name) { case (_, session) =>
      implicit val s: DBSession = session
      body(session)
    }
}
```

- [ ] **Step 2: Compile**
```bash
sbt test:compile
```

- [ ] **Step 3: Commit**
```bash
git add test/db/scalikejdbc/AutoRollbackMunitDb.scala
git commit -m "test: add AutoRollbackMunitDb FunFixture trait for munit"
```

---

## Task 5: Add `implicit session` to DAO write methods

These are ALL the custom write methods called from the test specs being migrated. For each: add `(implicit session: DBSession = AutoSession)` to the signature and, where the method body used `DB.localTx { implicit session => ... }`, remove that wrapper (the implicit parameter now provides the session directly).

**Why this is safe for production callers:** Adding `implicit session: DBSession = AutoSession` gives the method the same default behaviour as before. Callers that don't pass a session explicitly get `AutoSession` (auto-commit) exactly as today.

---

### Task 5a: `ContestJuryJdbc` — `create`, `setImagesSource`, `batchInsert`

**Files:**
- Modify: `app/db/scalikejdbc/ContestJuryJdbc.scala`

- [ ] **Step 1: Read the file** (already known from earlier analysis)

- [ ] **Step 2: Update `create`**

Before:
```scala
def create(id: Option[Long], name: String, ...): ContestJury = {
  val dbId = withSQL { insert.into(ContestJuryJdbc).namedValues(...) }.updateAndReturnGeneratedKey()
  ...
}
```

After — add `(implicit session: DBSession = AutoSession)`:
```scala
def create(
    id: Option[Long], name: String, year: Int, country: String,
    images: Option[String] = None, categoryId: Option[Long] = None,
    currentRound: Option[Long] = None, monumentIdTemplate: Option[String] = None,
    campaign: Option[String] = None
)(implicit session: DBSession = AutoSession): ContestJury = {
  val dbId = withSQL { insert.into(ContestJuryJdbc).namedValues(...) }.updateAndReturnGeneratedKey()
  ...
}
```

- [ ] **Step 3: Update `setImagesSource`**

Before:
```scala
def setImagesSource(id: Long, images: Option[String]): Int = {
  val categoryId = images.map(CategoryJdbc.findOrInsert)
  updateById(id).withAttributes("images" -> images, "categoryId" -> categoryId)
}
```

After:
```scala
def setImagesSource(id: Long, images: Option[String])(implicit session: DBSession = AutoSession): Int = {
  val categoryId = images.map(CategoryJdbc.findOrInsert)
  updateById(id).withAttributes("images" -> images, "categoryId" -> categoryId)
}
```

- [ ] **Step 4: Update `batchInsert`** — remove `DB.localTx` wrapper

Before:
```scala
def batchInsert(contests: Seq[ContestJury]): Seq[Int] = {
  DB localTx { implicit session =>
    val batchParams = ...
    withSQL { insert.into(ContestJuryJdbc).namedValues(...) }.batch(batchParams: _*).apply()
  }
}
```

After:
```scala
def batchInsert(contests: Seq[ContestJury])(implicit session: DBSession = AutoSession): Seq[Int] = {
  val batchParams: Seq[Seq[Any]] = contests.map(c => Seq(c.id, c.name, c.year, c.country,
    c.images, c.currentRound, c.monumentIdTemplate, c.campaign))
  withSQL {
    insert.into(ContestJuryJdbc).namedValues(
      column.id -> sqls.?, column.name -> sqls.?, column.year -> sqls.?,
      column.country -> sqls.?, column.images -> sqls.?, column.currentRound -> sqls.?,
      column.monumentIdTemplate -> sqls.?, column.campaign -> sqls.?
    )
  }.batch(batchParams: _*).apply()
}
```

- [ ] **Step 5: Compile**
```bash
sbt compile
```
Expected: SUCCESS (check for any callers that may break due to ambiguous implicit).

- [ ] **Step 6: Commit**
```bash
git add app/db/scalikejdbc/ContestJuryJdbc.scala
git commit -m "refactor: make ContestJuryJdbc write methods session-aware"
```

---

### Task 5b: `CategoryJdbc` — `findOrInsert` and `CategoryLinkJdbc.addToCategory`, `batchInsert`

**Files:**
- Modify: `app/db/scalikejdbc/CategoryJdbc.scala`

- [ ] **Step 1: Update `CategoryJdbc.findOrInsert`**

Before:
```scala
def findOrInsert(title: String): Long = {
  where("title" -> title).apply().headOption.map(_.id).getOrElse {
    createWithAttributes("title" -> title)
  }
}
```

After:
```scala
def findOrInsert(title: String)(implicit session: DBSession = AutoSession): Long = {
  where("title" -> title).apply().headOption.map(_.id).getOrElse {
    createWithAttributes("title" -> title)
  }
}
```

- [ ] **Step 2: Update `CategoryLinkJdbc.addToCategory` and `batchInsert`**

Before:
```scala
def addToCategory(categoryId: Long, images: Seq[Image]): Unit = {
  val categoryLinks = images.map { image => CategoryLink(categoryId, image.pageId) }
  batchInsert(categoryLinks)
}

def batchInsert(links: Seq[CategoryLink]): Unit = {
  DB localTx { implicit session =>
    val batchParams = links.map(link => Seq(link.categoryId, link.pageId))
    withSQL { insert.into(CategoryLinkJdbc).namedValues(...) }.batch(batchParams: _*).apply()
  }
}
```

After:
```scala
def addToCategory(categoryId: Long, images: Seq[Image])(implicit session: DBSession = AutoSession): Unit = {
  val categoryLinks = images.map { image => CategoryLink(categoryId, image.pageId) }
  batchInsert(categoryLinks)
}

def batchInsert(links: Seq[CategoryLink])(implicit session: DBSession = AutoSession): Unit = {
  val column = CategoryLinkJdbc.column
  val batchParams: Seq[Seq[Any]] = links.map(link => Seq(link.categoryId, link.pageId))
  withSQL {
    insert.into(CategoryLinkJdbc).namedValues(
      column.categoryId -> sqls.?,
      column.pageId     -> sqls.?
    )
  }.batch(batchParams: _*).apply()
}
```

- [ ] **Step 3: Compile**
```bash
sbt compile
```

- [ ] **Step 4: Commit**
```bash
git add app/db/scalikejdbc/CategoryJdbc.scala
git commit -m "refactor: make CategoryJdbc/CategoryLinkJdbc write methods session-aware"
```

---

### Task 5c: `SelectionJdbc` — `create`, `batchInsert`, `rate`

**Files:**
- Modify: `app/db/scalikejdbc/SelectionJdbc.scala`

- [ ] **Step 1: Update `create`**

Add `(implicit session: DBSession = AutoSession)` to the method signature.

- [ ] **Step 2: Update `batchInsert`**

Remove `DB localTx { implicit session => ... }` wrapper; add `(implicit session: DBSession = AutoSession)` to signature.

- [ ] **Step 3: Update `rate`**

Before:
```scala
def rate(pageId: Long, juryId: Long, roundId: Long, rate: Int = 1): Unit =
  withSQL { update(SelectionJdbc).set(column.rate -> rate).where.eq(...) }.update()
```

After:
```scala
def rate(pageId: Long, juryId: Long, roundId: Long, rate: Int = 1)
    (implicit session: DBSession = AutoSession): Unit =
  withSQL { update(SelectionJdbc).set(column.rate -> rate).where.eq(...) }.update()
```

- [ ] **Step 4: Compile** `sbt compile`

- [ ] **Step 5: Commit**
```bash
git add app/db/scalikejdbc/SelectionJdbc.scala
git commit -m "refactor: make SelectionJdbc write methods session-aware"
```

---

### Task 5d: `User` — both `create` overloads

**Files:**
- Modify: `app/db/scalikejdbc/User.scala`

- [ ] **Step 1: Read the file** — note both `create` overloads at ~lines 280 and 314

- [ ] **Step 2: Add `(implicit session: DBSession = AutoSession)` to both `create` methods**

Both follow the same pattern: `withSQL { insert.into(User).namedValues(...) }.updateAndReturnGeneratedKey()`.

- [ ] **Step 3: Compile** `sbt compile`

- [ ] **Step 4: Commit**
```bash
git add app/db/scalikejdbc/User.scala
git commit -m "refactor: make User.create session-aware"
```

---

### Task 5e: `Round` — `create`

**Files:**
- Modify: `app/db/scalikejdbc/Round.scala`

- [ ] **Step 1: Read the file**, locate `def create(round: Round): Round` (~line 225)

- [ ] **Step 2: Add `(implicit session: DBSession = AutoSession)` to `create`**

- [ ] **Step 3: Compile** `sbt compile`

- [ ] **Step 4: Commit**
```bash
git add app/db/scalikejdbc/Round.scala
git commit -m "refactor: make Round.create session-aware"
```

---

### Task 5f: `ImageJdbc` — `batchInsert`

**Files:**
- Modify: `app/db/scalikejdbc/ImageJdbc.scala`

- [ ] **Step 1: Read the file**, locate `def batchInsert(images: Seq[Image]): Unit` (~line 72)

- [ ] **Step 2: Remove `DB.localTx` wrapper; add `(implicit session: DBSession = AutoSession)`**

Note: `ImageJdbc` extends `ImageRepo`. Check if `ImageRepo` defines `batchInsert` — if it does, update the interface signature too.

```bash
grep -n "batchInsert" app/db/ImageRepo.scala
```

If found in `ImageRepo`, add `(implicit session: DBSession = AutoSession)` there as well.

- [ ] **Step 3: Compile** `sbt compile`

- [ ] **Step 4: Commit**
```bash
git add app/db/scalikejdbc/ImageJdbc.scala app/db/ImageRepo.scala
git commit -m "refactor: make ImageJdbc.batchInsert session-aware"
```

---

### Task 5g: Verify all production tests still pass

```bash
sbt test
```

Any failures here indicate a caller that received a broken signature. Fix before continuing.

---

## Task 6: Update `TestDb` — remove container helpers, add `implicit session`

**Files:**
- Modify: `test/db/scalikejdbc/TestDb.scala`

`createContests` calls `ContestJuryJdbc.create` and `ContestJuryJdbc.setImagesSource`. `createUsers` calls `User.create`. Both need to accept and forward `implicit session` so helper calls inside `new AutoRollbackDb { }` participate in the transaction.

- [ ] **Step 1: Rewrite `TestDb`**

```scala
package db.scalikejdbc

import org.intracer.wmua.ContestJury
import scalikejdbc.{AutoSession, DBSession}

import java.time.ZonedDateTime

/** Shared DAO accessors and test-data helpers.
 *  DB lifecycle is handled by AutoRollbackDb / AutoRollbackMunitDb
 *  (which call SharedTestDb.init() before tests run).
 *
 *  Helpers accept implicit DBSession (default: AutoSession) so that
 *  when called inside a transaction context (AutoRollback test) all
 *  inserts participate in the transaction and are rolled back.
 */
trait TestDb {

  val contestDao: ContestJuryJdbc.type = ContestJuryJdbc
  val imageDao: ImageJdbc.type         = ImageJdbc
  val roundDao: Round.type             = Round
  val selectionDao: SelectionJdbc.type = SelectionJdbc
  val userDao: User.type               = User

  def now: ZonedDateTime = TestDb.now

  def createContests(contestIds: Long*)(implicit session: DBSession = AutoSession): Seq[ContestJury] =
    contestIds.map { id =>
      val contest = ContestJuryJdbc.create(Some(id), s"contest$id", 2000 + id.toInt, s"country$id")
      ContestJuryJdbc.setImagesSource(id, Some(s"Images from ${contest.name}"))
      contest
    }

  def contestUser(i: Long, role: String = "jury")(implicit contest: ContestJury): User =
    User(
      s"fullname$i", s"email$i", None, Set(role),
      Some("password hash"), contest.id, Some("en"), Some(TestDb.now)
    )

  def createUsers(userIndexes: Seq[Int])(implicit
      contest: ContestJury,
      session: DBSession = AutoSession,
      d: DummyImplicit
  ): Seq[User] = createUsers(userIndexes: _*)

  def createUsers(userIndexes: Int*)(implicit
      contest: ContestJury,
      session: DBSession = AutoSession
  ): Seq[User] = createUsers("jury", userIndexes: _*)

  def createUsers(role: String, userIndexes: Seq[Int])(implicit
      contest: ContestJury,
      session: DBSession = AutoSession,
      d: DummyImplicit
  ): Seq[User] = createUsers(role, userIndexes: _*)

  def createUsers(role: String, userIndexes: Int*)(implicit
      contest: ContestJury,
      session: DBSession = AutoSession
  ): Seq[User] =
    userIndexes.map(i => contestUser(i, role)).map(userDao.create)
}

object TestDb {
  def now: ZonedDateTime = ZonedDateTime.now.withNano(0)
}
```

> **Implicit resolution:** Inside `new AutoRollbackDb { }`, `AutoRollbackLike` provides `implicit val session: DBSession`. When `createContests(10)` is called, Scala finds that implicit and passes it automatically, because the `session` parameter has type `DBSession` and the method's default `AutoSession` is only used if nothing better is in scope.

- [ ] **Step 2: Apply Task 8a simultaneously**

> ⚠️ **Ordering constraint:** `ImagesSqlSpec` calls `withDb` from `TestDb`. Since this step removes `withDb`, `ImagesSqlSpec` will fail to compile unless fixed in the same commit. Go to Task 8a now, make the `ImagesSqlSpec` change, then include it in the commit below.

- [ ] **Step 3: Compile**
```bash
sbt test:compile
```
Expected: compile errors only in specs that still call `withDb` (Tasks 7a–7f). No errors in `ImagesSqlSpec` or non-test code.

- [ ] **Step 4: Commit (include `ImagesSqlSpec` from Task 8a)**
```bash
git add test/db/scalikejdbc/TestDb.scala test/db/scalikejdbc/ImagesSqlSpec.scala
git commit -m "test: strip container lifecycle from TestDb; remove spurious DB dep from ImagesSqlSpec"
```

---

## Task 7: Migrate specs2 DB tests

Pattern for every migration:
1. `extends Specification with TestDb` → `extends Specification with BeforeAll`
2. Add `override def beforeAll(): Unit = SharedTestDb.init()`
3. Remove `sequential` / `stopOnFail` (each test is now fully isolated via rollback)
4. Replace `"test" in { withDb { body } }` → `"test" in new AutoRollbackDb { body }`
5. Remove container/Play imports (`play.api.test.Helpers`, `GuiceApplicationBuilder`, etc.)

---

### Task 7a: Migrate `RoundSpec`

**Files:**
- Modify: `test/db/scalikejdbc/RoundSpec.scala`

- [ ] **Step 1: Apply migration**

```scala
package db.scalikejdbc

import db.scalikejdbc.RoundSpec.round
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

class RoundSpec extends Specification with BeforeAll {

  override def beforeAll(): Unit = SharedTestDb.init()

  "rounds" should {
    "be empty" in new AutoRollbackDb {
      roundDao.findAll().size === 0
    }

    "insert round" in new AutoRollbackDb {
      val created = roundDao.create(round)
      val id = created.id
      created === round.copy(id = id)
      roundDao.findById(id.get) === Some(created)
      roundDao.findAll() === Seq(created)
    }

    "available jurors" in new AutoRollbackDb {
      implicit val contest: ContestJury = createContests(10).head
      val created = roundDao.create(round)
      val jurors = createUsers(1 to 3).map(u => u.copy(roles = u.roles + s"USER_ID_${u.getId}"))
      createUsers("prejury", 11 to 13)
      createUsers("organizer", 20)
      createUsers(31 to 33)(contest.copy(id = Some(20)), implicitly)
      created.availableJurors === jurors
    }

    "set new current round" in new AutoRollbackDb {
      val contest = contestDao.create(None, "WLE", 2015, "Ukraine", None, None, None)
      val contestId = contest.getId
      val createdAt = now
      val created = roundDao.create(round.copy(createdAt = createdAt, contestId = contestId))
      roundDao.findById(created.getId).map(_.active) === Some(true)
      roundDao.findById(created.getId).map(_.copy(createdAt = createdAt)) ===
        Some(created.copy(createdAt = createdAt))
      roundDao.activeRounds(contestId).map(_.copy(createdAt = createdAt)) ===
        Seq(created.copy(createdAt = createdAt))
      contestDao.findById(contestId).get.currentRound === None
    }
  }
}

object RoundSpec {
  private val round = Round(
    id = None, number = 1, name = Some("Round 1"), contestId = 10,
    roles = Set("jury"), distribution = 3, rates = Round.ratesById(10),
    active = true, createdAt = TestDb.now
  )
}
```

- [ ] **Step 2: Run**
```bash
sbt "testOnly db.scalikejdbc.RoundSpec"
```
Expected: all 4 tests PASS.

- [ ] **Step 3: Commit**
```bash
git add test/db/scalikejdbc/RoundSpec.scala
git commit -m "test: migrate RoundSpec to AutoRollbackDb"
```

---

### Task 7b: Migrate `SelectionSpec`

**Files:**
- Modify: `test/db/scalikejdbc/SelectionSpec.scala`

- [ ] **Step 1: Apply migration**

Replace `extends Specification with TestDb` → `extends Specification with BeforeAll`.
Add `override def beforeAll(): Unit = SharedTestDb.init()`.
Replace `"test" in { withDb { body } }` → `"test" in new AutoRollbackDb { body }`.

The private helper `findAll()` is called inside test bodies where `session` is in scope from `AutoRollbackLike`. Since `selectionDao.findAll()` is an inherited CRUDMapper method accepting `(implicit session: DBSession)`, it will use the transaction session automatically — no signature change needed for the helper itself.

- [ ] **Step 2: Run**
```bash
sbt "testOnly db.scalikejdbc.SelectionSpec"
```

- [ ] **Step 3: Commit**
```bash
git add test/db/scalikejdbc/SelectionSpec.scala
git commit -m "test: migrate SelectionSpec to AutoRollbackDb"
```

---

### Task 7c: Migrate `UserDbSpec`

**Files:**
- Modify: `test/db/scalikejdbc/UserDbSpec.scala`

- [ ] **Step 1: Apply migration** (same pattern)
- [ ] **Step 2: Run** `sbt "testOnly db.scalikejdbc.UserDbSpec"`
- [ ] **Step 3: Commit**
```bash
git add test/db/scalikejdbc/UserDbSpec.scala
git commit -m "test: migrate UserDbSpec to AutoRollbackDb"
```

---

### Task 7d: Migrate `ImageSpec`

**Files:**
- Modify: `test/db/scalikejdbc/ImageSpec.scala`

- [ ] **Step 1: Apply migration**

The private helper `addToContest` calls `CategoryLinkJdbc.addToCategory`, which now accepts `implicit session`. Update the helper to accept and forward the session:

```scala
private def addToContest(contestId: Long, images: Seq[Image])
    (implicit session: scalikejdbc.DBSession = scalikejdbc.AutoSession): Unit =
  CategoryLinkJdbc.addToCategory(
    ContestJuryJdbc.findById(contestId).flatMap(_.categoryId).get,
    images
  )
```

- [ ] **Step 2: Run** `sbt "testOnly db.scalikejdbc.ImageSpec"`
- [ ] **Step 3: Commit**
```bash
git add test/db/scalikejdbc/ImageSpec.scala
git commit -m "test: migrate ImageSpec to AutoRollbackDb"
```

---

### Task 7e: Migrate `ImageJdbcSpec`

**Files:**
- Modify: `test/db/scalikejdbc/ImageJdbcSpec.scala`

- [ ] **Step 1: Apply migration** (same pattern)
- [ ] **Step 2: Run** `sbt "testOnly db.scalikejdbc.ImageJdbcSpec"`
- [ ] **Step 3: Commit**
```bash
git add test/db/scalikejdbc/ImageJdbcSpec.scala
git commit -m "test: migrate ImageJdbcSpec to AutoRollbackDb"
```

---

### Task 7f: Migrate `JurorImagesSpec`

**Files:**
- Modify: `test/db/scalikejdbc/JurorImagesSpec.scala`

- [ ] **Step 1: Read the full file** — it has class-level `implicit var contest`, `var round`, `var user`. After migration each test is isolated so these must become local vals inside each `new AutoRollbackDb { }` block.

- [ ] **Step 2: Apply migration**

Move all test setup into each test block. Remove class-level mutable vars.

- [ ] **Step 3: Run** `sbt "testOnly db.scalikejdbc.JurorImagesSpec"`
- [ ] **Step 4: Commit**
```bash
git add test/db/scalikejdbc/JurorImagesSpec.scala
git commit -m "test: migrate JurorImagesSpec to AutoRollbackDb"
```

---

## Task 8: Migrate munit DB tests

### Task 8a: Fix `ImagesSqlSpec` — remove spurious DB dependency

`ImagesSqlSpec` only tests SQL query-string building (`SelectionQuery.query()`). It never executes SQL. The `TestDb` mixin and `withDb` wrapper are unnecessary.

**Files:**
- Modify: `test/db/scalikejdbc/ImagesSqlSpec.scala`

- [ ] **Step 1: Read the full file** to confirm no DB call exists
- [ ] **Step 2: Change the class declaration**

```scala
class ImagesSqlSpec extends FunSuite {
```

Remove `with TestDb` and the `withDb { ... }` wrapper (keep test body).

> ⚠️ **This change is committed together with Task 6** (rewriting `TestDb`). Do not commit it separately — see Task 6 Step 4.

- [ ] **Step 3: Run** `sbt "testOnly db.scalikejdbc.ImagesSqlSpec"` (after Task 6 commit)

---

### Task 8b: Migrate `ContestSpec`

**Files:**
- Modify: `test/db/scalikejdbc/ContestSpec.scala`

- [ ] **Step 1: Apply migration**

Replace `extends FunSuite with TestDb` → `extends FunSuite with AutoRollbackMunitDb`.
Replace each `test("name") { withDb { body } }` → `dbTest("name") { implicit session => body }`.

```scala
package db.scalikejdbc

import org.intracer.wmua.ContestJury
import munit.FunSuite

class ContestSpec extends FunSuite with AutoRollbackMunitDb {

  dbTest("fresh database should be empty") { implicit session =>
    assertEquals(contestDao.findAll(), Nil)
  }

  dbTest("create contest") { implicit session =>
    val images = Some("Category:Images from Wiki Loves Earth 2015 in Ukraine")
    val contest = contestDao.create(
      id = None, name = "WLE", year = 2015, country = "Ukraine",
      images = images, categoryId = None, currentRound = None
    )
    assertEquals(contest.name, "WLE")
    assertEquals(contest.year, 2015)
    assertEquals(contest.country, "Ukraine")
    assertEquals(contest.images, images)
    assertEquals(contestDao.findById(contest.getId), Some(contest))
    assertEquals(contestDao.findAll(), List(contest))
  }

  dbTest("create contests") { implicit session =>
    def images(contest: String, year: Int, country: String) =
      Some(s"Category:Images from $contest $year in $country")
    val contests = List(
      ContestJury(id = None, name = "WLE", year = 2015, country = "Ukraine",
        images = images("WLE", 2015, "Ukraine")),
      ContestJury(id = None, name = "WLM", year = 2015, country = "Ukraine",
        images = images("WLM", 2015, "Ukraine")),
      ContestJury(id = None, name = "WLM", year = 2015, country = "Poland",
        images = images("WLM", 2015, "Poland"))
    )
    contestDao.batchInsert(contests)
    assertEquals(contestDao.findAll().map(_.copy(id = None)), contests)
  }
}
```

- [ ] **Step 2: Run** `sbt "testOnly db.scalikejdbc.ContestSpec"`
- [ ] **Step 3: Commit**
```bash
git add test/db/scalikejdbc/ContestSpec.scala
git commit -m "test: migrate ContestSpec to AutoRollbackMunitDb"
```

---

## Task 9: Handle `RoundUsersSpec` — per-suite Play app

`RoundUsersSpec` uses `round1.addUsers(...)` which dispatches via `Round.usersRef` (an Akka actor). Actors use auto-committed sessions and cannot participate in AutoRollback. This spec needs a full Play application (for the actor system) and uses `PlayTestDb` — one container per spec class.

**Files:**
- Create: `test/db/scalikejdbc/PlayTestDb.scala`
- Modify: `test/db/scalikejdbc/RoundUsersSpec.scala`

- [ ] **Step 1: Create `PlayTestDb`**

```scala
package db.scalikejdbc

import com.dimafeng.testcontainers.MariaDBContainer
import org.testcontainers.utility.DockerImageName
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.Await
import scala.concurrent.duration._

/** Provides a Play application backed by its own MariaDB container.
 *  Container and app start in beforeAll, stop in afterAll.
 *
 *  Use only when tests require Akka actors or Play services.
 *  GuiceApplicationBuilder.build() returns an already-started application;
 *  flyway-play runs migrations automatically on startup via the Play module.
 *  app.stop() returns Future[Unit] — Await is used to ensure clean shutdown.
 */
trait PlayTestDb extends TestDb {

  private var _container: MariaDBContainer = _
  protected var app: Application           = _

  def startPlayApp(additionalConfig: Map[String, String] = Map.empty): Unit = {
    _container = MariaDBContainer(
      dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
      dbName         = "wlxjury",
      dbUsername     = "WLXJURY_DB_USER",
      dbPassword     = "WLXJURY_DB_PASSWORD"
    )
    _container.start()
    app = new GuiceApplicationBuilder()
      .configure(Map(
        "db.default.driver"   -> _container.driverClassName,
        "db.default.username" -> _container.username,
        "db.default.password" -> _container.password,
        "db.default.url"      -> _container.jdbcUrl
      ) ++ additionalConfig)
      .build()
    roundDao.usersRef // initialise actor ref required by addUsers
  }

  def stopPlayApp(): Unit = {
    if (app != null) Await.result(app.stop(), 30.seconds)
    if (_container != null) _container.stop()
  }
}
```

- [ ] **Step 2: Update `RoundUsersSpec`**

Replace `extends Specification with TestDb` → `extends Specification with PlayTestDb with BeforeAll with AfterAll`.
Add `beforeAll`/`afterAll` hooks.
Remove `withDb { ... }` wrappers — the app is already live for the whole spec:

```scala
class RoundUsersSpec extends Specification with PlayTestDb
    with org.specs2.specification.BeforeAll
    with org.specs2.specification.AfterAll {

  sequential
  stopOnFail

  override def beforeAll(): Unit = startPlayApp()
  override def afterAll(): Unit  = stopPlayApp()

  "round users" should {
    "assign round to jurors" in {
      // same body as before, withDb removed
      implicit val contest = contestDao.create(None, "WLE", 2015, "Ukraine")
      // ... rest of test unchanged
    }
  }
}
```

> **Isolation:** Tests share DB state across the spec. This is acceptable — `sequential + stopOnFail` already assumes ordering, and a fresh container is used per run.

- [ ] **Step 3: Run**
```bash
sbt "testOnly db.scalikejdbc.RoundUsersSpec"
```

- [ ] **Step 4: Commit**
```bash
git add test/db/scalikejdbc/PlayTestDb.scala test/db/scalikejdbc/RoundUsersSpec.scala
git commit -m "test: migrate RoundUsersSpec to per-suite PlayTestDb"
```

---

## Task 10: Run all DB tests and verify

- [ ] **Step 1: Run the full DB test package**
```bash
sbt "testOnly db.scalikejdbc.*"
```
Expected: all tests PASS. Container starts at most twice (once via `SharedTestDb`, once in `RoundUsersSpec`).

- [ ] **Step 2: Run full test suite**
```bash
sbt test
```
Expected: no regressions.

- [ ] **Step 3: Final commit if any cleanup**
```bash
git commit -m "test: all DB specs migrated to AutoRollback / shared container"
```

---

## Troubleshooting Guide

### Test data leaks between tests (isolation failure)
A write method is still using `AutoSession` inside the AutoRollback transaction. Symptoms: a test that asserts `findAll() === Nil` passes the first run but fails subsequent tests in the same suite.

Find offending methods:
```bash
grep -rn "DB localTx\|DB\.autoCommit\|autoSession\|AutoSession" app/db/scalikejdbc/
```
Apply the Task 5 pattern: add `(implicit session: DBSession = AutoSession)` to the method, remove the `DB.localTx` wrapper.

### "No implicit session" compile error
A helper or DAO method called inside `new AutoRollbackDb { }` doesn't have an `implicit session` parameter. Add it using the Task 5 pattern.

### `ConnectionPool is not initialized`
`SharedTestDb.init()` was not called before the first test. Ensure `BeforeAll.beforeAll()` overrides `SharedTestDb.init()` in the spec class.

### Flyway migration fails in `SharedTestDb`
Migrations are loaded from `classpath:db/migration/default`. Verify the `conf/` directory is on the test classpath:
```bash
sbt "show test:fullClasspath"
```
Look for the `conf` directory in the output.

### `PlayTestDb` — Flyway not running
`GuiceApplicationBuilder.build()` triggers `flyway-play` as a Play module. Ensure `db.default.migration.auto` is not set to `false` in `test/resources/application.conf`.
