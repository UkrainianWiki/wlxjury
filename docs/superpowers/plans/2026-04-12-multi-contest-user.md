# Multi-Contest User Membership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow a user to belong to multiple contests with a different role in each, using a `user_contest` join table and session-scoped contest selection.

**Architecture:** A new `user_contest(user_id, contest_id, role)` table replaces the `contest_id` and `roles` columns on `users`. An `is_root` boolean on `users` replaces the root role. `Secured.userFromRequest` reads `current_contest_id` from the session and enriches the `User` object with `contestId` and `roles` from `user_contest`. Auth logic (`isInContest`, `contestPermission`) is unchanged because `User` retains those fields at runtime.

**Tech Stack:** ScalikeJDBC 4.3 (`CRUDMapper`, `JoinTable`, raw `withSQL`), Flyway migrations, Play Framework 3.0, Twirl templates, specs2 + Testcontainers (MariaDB 10.6).

---

## File Map

| File | Change |
|---|---|
| `conf/db/migration/default/V50__Add_is_root_and_user_contest.sql` | New: add `is_root` to `users`, create `user_contest` |
| `conf/db/migration/default/V51__Migrate_roles_to_user_contest.sql` | New: data migration, drop `contest_id`/`roles` from `users` |
| `app/db/scalikejdbc/UserContestJdbc.scala` | New: `UserContest` case class + DAO |
| `app/db/scalikejdbc/User.scala` | Modify: `extract`, `create`, `updateUser`, `findByContest`, `loadJurors`, new `findByContestAndRoles` |
| `app/db/scalikejdbc/Round.scala` | Modify: `availableJurors` uses `User.findByContestAndRoles` |
| `app/controllers/Secured.scala` | Modify: `userFromRequest` reads session, loads `UserContest` |
| `app/controllers/LoginController.scala` | Modify: `auth()` sets `current_contest_id` in session |
| `app/controllers/UserController.scala` | Modify: `saveUser` calls `UserContestJdbc.updateRole`; new `switchContest` action |
| `app/services/UserService.scala` | Modify: `createUser` inserts into `user_contest` after `User.create` |
| `app/views/logged.scala.html` | Modify: contest switcher dropdown |
| `conf/routes` | Modify: add `POST /switch-contest` route |
| `test/db/scalikejdbc/SharedTestDb.scala` | Modify: add `user_contest` to `dataTables` |
| `test/db/scalikejdbc/SharedPlayApp.scala` | Modify: add `user_contest` to `dataTables` |
| `test/db/scalikejdbc/TestDb.scala` | Modify: `createUsers` also inserts `user_contest` row |
| `test/db/scalikejdbc/UserDbSpec.scala` | Modify: remove `contestId` from insert assertions |
| `test/db/scalikejdbc/UserContestSpec.scala` | New: DAO tests |

---

## Task 1: DB Migration V50 — Add `is_root` and `user_contest` table

**Files:**
- Create: `conf/db/migration/default/V50__Add_is_root_and_user_contest.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Add root flag to users (replaces the 'root' value in the roles column)
ALTER TABLE users ADD COLUMN is_root BOOLEAN NOT NULL DEFAULT FALSE;

-- Join table: a user can belong to multiple contests with different roles
CREATE TABLE user_contest (
  user_id    BIGINT UNSIGNED NOT NULL,
  contest_id BIGINT UNSIGNED NOT NULL,
  role       VARCHAR(255)    NOT NULL,
  PRIMARY KEY (user_id, contest_id),
  CONSTRAINT fk_uc_user    FOREIGN KEY (user_id)    REFERENCES users(id)    ON DELETE CASCADE,
  CONSTRAINT fk_uc_contest FOREIGN KEY (contest_id) REFERENCES contest(id)  ON DELETE CASCADE
);
```

- [ ] **Step 2: Verify migration compiles (Flyway will run it on next app start)**

```bash
sbt "testOnly db.scalikejdbc.UserDbSpec"
```

Expected: existing test still passes (columns not yet dropped)

- [ ] **Step 3: Commit**

```bash
git add conf/db/migration/default/V50__Add_is_root_and_user_contest.sql
git commit -m "db: add is_root column and user_contest join table (V50)"
```

---

## Task 2: DB Migration V51 — Data migration, drop old columns

**Files:**
- Create: `conf/db/migration/default/V51__Migrate_roles_to_user_contest.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Mark existing root users
UPDATE users SET is_root = TRUE WHERE roles = 'root';

-- Migrate all non-root contest memberships to the join table
INSERT INTO user_contest (user_id, contest_id, role)
SELECT id, contest_id, roles
FROM users
WHERE contest_id IS NOT NULL
  AND roles IS NOT NULL
  AND roles != ''
  AND roles != 'root';

-- Remove the columns now stored in user_contest / is_root
ALTER TABLE users DROP COLUMN contest_id;
ALTER TABLE users DROP COLUMN roles;
```

- [ ] **Step 2: Commit**

```bash
git add conf/db/migration/default/V51__Migrate_roles_to_user_contest.sql
git commit -m "db: migrate roles/contest_id to user_contest, drop old columns (V51)"
```

---

## Task 3: `UserContestJdbc` DAO + test

**Files:**
- Create: `app/db/scalikejdbc/UserContestJdbc.scala`
- Create: `test/db/scalikejdbc/UserContestSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
// test/db/scalikejdbc/UserContestSpec.scala
package db.scalikejdbc

import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

class UserContestSpec extends Specification with BeforeAll with TestDb {

  override def beforeAll(): Unit = {
    SharedTestDb.init()
    SharedTestDb.truncateAll()
  }

  "UserContestJdbc" should {
    "create and find membership" in new AutoRollbackDb {
      implicit val contest: ContestJury =
        contestDao.create(Some(1L), "WLE", 2024, "Ukraine")
      val user = userDao.create(
        User("Alice", "alice@example.com", None, Set.empty, Some("hash"), None)
      )
      UserContestJdbc.createMembership(user.getId, 1L, "jury")

      val memberships = UserContestJdbc.findByUser(user.getId)
      memberships.map(_.role) === Seq("jury")
      memberships.map(_.contestId) === Seq(1L)
    }

    "find by contest" in new AutoRollbackDb {
      implicit val contest: ContestJury =
        contestDao.create(Some(2L), "WLM", 2024, "Ukraine")
      val user1 = userDao.create(User("Bob", "bob@example.com", None, Set.empty, Some("hash"), None))
      val user2 = userDao.create(User("Carol", "carol@example.com", None, Set.empty, Some("hash"), None))
      UserContestJdbc.createMembership(user1.getId, 2L, "jury")
      UserContestJdbc.createMembership(user2.getId, 2L, "organizer")

      val memberships = UserContestJdbc.findByContest(2L)
      memberships.map(_.userId).sorted === Seq(user1.getId, user2.getId).sorted
    }

    "update role" in new AutoRollbackDb {
      implicit val contest: ContestJury =
        contestDao.create(Some(3L), "WLE", 2023, "Poland")
      val user = userDao.create(User("Dave", "dave@example.com", None, Set.empty, Some("hash"), None))
      UserContestJdbc.createMembership(user.getId, 3L, "jury")
      UserContestJdbc.updateRole(user.getId, 3L, "organizer")

      UserContestJdbc.findByUser(user.getId).map(_.role) === Seq("organizer")
    }

    "delete membership" in new AutoRollbackDb {
      implicit val contest: ContestJury =
        contestDao.create(Some(4L), "WLM", 2023, "Poland")
      val user = userDao.create(User("Eve", "eve@example.com", None, Set.empty, Some("hash"), None))
      UserContestJdbc.createMembership(user.getId, 4L, "jury")
      UserContestJdbc.delete(user.getId, 4L)

      UserContestJdbc.findByUser(user.getId) === Nil
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails (UserContestJdbc does not exist)**

```bash
sbt "testOnly db.scalikejdbc.UserContestSpec"
```

Expected: compile error — `UserContestJdbc` not found

- [ ] **Step 3: Implement `UserContestJdbc`**

```scala
// app/db/scalikejdbc/UserContestJdbc.scala
package db.scalikejdbc

import scalikejdbc._
import scalikejdbc.orm.CRUDMapper

case class UserContest(userId: Long, contestId: Long, role: String)

object UserContestJdbc extends CRUDMapper[UserContest] {
  override val tableName = "user_contest"
  override lazy val defaultAlias = createAlias("uc")
  val uc = UserContestJdbc.syntax("uc")

  override def extract(rs: WrappedResultSet, n: ResultName[UserContest]): UserContest =
    UserContest(
      userId    = rs.long(n.userId),
      contestId = rs.long(n.contestId),
      role      = rs.string(n.role)
    )

  def findByUser(userId: Long)(implicit session: DBSession = AutoSession): Seq[UserContest] =
    where("userId" -> userId).apply()

  def findByContest(contestId: Long)(implicit session: DBSession = AutoSession): Seq[UserContest] =
    where("contestId" -> contestId).apply()

  def createMembership(userId: Long, contestId: Long, role: String)
                      (implicit session: DBSession = AutoSession): Unit =
    withSQL {
      insert.into(UserContestJdbc).namedValues(
        column.userId    -> userId,
        column.contestId -> contestId,
        column.role      -> role
      )
    }.update()

  def updateRole(userId: Long, contestId: Long, role: String)
                (implicit session: DBSession = AutoSession): Unit =
    updateBy(sqls.eq(uc.userId, userId).and.eq(uc.contestId, contestId))
      .withAttributes("role" -> role)

  def delete(userId: Long, contestId: Long)
            (implicit session: DBSession = AutoSession): Unit =
    deleteBy(sqls.eq(uc.userId, userId).and.eq(uc.contestId, contestId))
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
sbt "testOnly db.scalikejdbc.UserContestSpec"
```

Expected: all 4 examples pass

- [ ] **Step 5: Commit**

```bash
git add app/db/scalikejdbc/UserContestJdbc.scala test/db/scalikejdbc/UserContestSpec.scala
git commit -m "feat: add UserContestJdbc DAO and tests"
```

---

## Task 4: Update `User` model — extract, create, updateUser

The `contest_id` and `roles` columns are now gone from the `users` table. `User.extract` must stop reading them and instead populate `roles` with only `USER_ID_xxx` (plus `ROOT_ROLE` if `is_root`). `User.create` must stop writing them. `User.updateUser` must stop updating `roles` on `users`.

**Files:**
- Modify: `app/db/scalikejdbc/User.scala`
- Modify: `test/db/scalikejdbc/UserDbSpec.scala`

- [ ] **Step 1: Write the failing test (after migration columns are gone, existing test will fail)**

Replace the content of `test/db/scalikejdbc/UserDbSpec.scala`:

```scala
package db.scalikejdbc

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

class UserDbSpec extends Specification with BeforeAll {

  override def beforeAll(): Unit = {
    SharedTestDb.init()
    SharedTestDb.truncateAll()
  }

  "fresh database" should {
    "be empty" in new AutoRollbackDb {
      userDao.findAll() must beEmpty
    }

    "create and reload user without contest" in new AutoRollbackDb {
      val user = User(
        fullname  = "fullname",
        email     = "email",
        roles     = Set.empty,
        password  = Some("password hash"),
        lang      = Some("en"),
        createdAt = Some(now)
      )
      val created = userDao.create(user)
      val id = created.id.get

      val found = userDao.findById(id)
      found.map(_.fullname)  === Some("fullname")
      found.map(_.email)     === Some("email")
      found.map(_.isRoot)    === Some(false)
      // roles from DB only contains USER_ID_xxx (contest role comes from userFromRequest)
      found.map(_.roles)     === Some(Set("USER_ID_" + id))
      found.map(_.contestId) === Some(None)
    }

    "mark root user" in new AutoRollbackDb {
      val root = userDao.create(
        User("root", "root@example.com", isRoot = true, password = Some("hash"))
      )
      val found = userDao.findById(root.getId)
      found.map(_.isRoot) === Some(true)
      found.map(_.roles)  === Some(Set(User.ROOT_ROLE, "USER_ID_" + root.getId))
    }
  }
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
sbt "testOnly db.scalikejdbc.UserDbSpec"
```

Expected: compile errors or assertion failures (V51 migration drops `contest_id`/`roles`, `isRoot` field does not exist on `User` yet)

- [ ] **Step 3: Update `User` case class and `extract`/`create`/`updateUser` in `app/db/scalikejdbc/User.scala`**

**3a. Add `isRoot` and `userContests` fields to the case class.** Change the `case class User(...)` definition from:

```scala
case class User(
    fullname: String,
    email: String,
    id: Option[Long] = None,
    roles: Set[String] = Set.empty,
    password: Option[String] = None,
    contestId: Option[Long] = None,
    lang: Option[String] = None,
    createdAt: Option[ZonedDateTime] = None,
    deletedAt: Option[ZonedDateTime] = None,
    wikiAccount: Option[String] = None,
    hasWikiEmail: Boolean = false,
    accountValid: Boolean = true,
    sort: Option[Int] = None,
    active: Option[Boolean] = Some(true)
) extends HasId
```

to:

```scala
case class User(
    fullname: String,
    email: String,
    id: Option[Long] = None,
    roles: Set[String] = Set.empty,
    password: Option[String] = None,
    contestId: Option[Long] = None,
    lang: Option[String] = None,
    createdAt: Option[ZonedDateTime] = None,
    deletedAt: Option[ZonedDateTime] = None,
    wikiAccount: Option[String] = None,
    hasWikiEmail: Boolean = false,
    accountValid: Boolean = true,
    sort: Option[Int] = None,
    active: Option[Boolean] = Some(true),
    isRoot: Boolean = false,
    userContests: Seq[UserContest] = Nil
) extends HasId
```

Note: `contestId` and `roles` are kept as runtime-only fields (not backed by DB columns any more).

**3b. Update `User.extract`** — replace the existing `extract` method:

```scala
override def extract(rs: WrappedResultSet, c: scalikejdbc.ResultName[User]): User = {
  val id = rs.int(c.id)
  val root = rs.booleanOpt(c.isRoot).getOrElse(false)
  User(
    id        = Some(id),
    fullname  = rs.string(c.fullname),
    email     = rs.string(c.email),
    roles     = Set("USER_ID_" + id) ++ (if (root) Set(User.ROOT_ROLE) else Set.empty),
    isRoot    = root,
    contestId = None,
    password  = Some(rs.string(c.password)),
    lang      = rs.stringOpt(c.lang),
    wikiAccount = rs.stringOpt(c.wikiAccount),
    sort      = rs.intOpt(c.sort),
    createdAt = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
    deletedAt = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime)
  )
}
```

**3c. Update the secondary `apply` method** — replace:

```scala
def apply(c: ResultName[User])(rs: WrappedResultSet): User = new User(
  id = Some(rs.int(c.id)),
  fullname = rs.string(c.fullname),
  email = rs.string(c.email),
  roles = rs.string(c.roles).split(",").map(_.trim).toSet ++ Set("USER_ID_" + rs.int(c.id)),
  contestId = rs.longOpt(c.contestId),
  password = Some(rs.string(c.password)),
  lang = rs.stringOpt(c.lang),
  wikiAccount = rs.stringOpt(c.wikiAccount),
  sort = rs.intOpt(c.sort),
  createdAt = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
  deletedAt = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime)
)
```

with:

```scala
def apply(c: ResultName[User])(rs: WrappedResultSet): User = {
  val id   = rs.int(c.id)
  val root = rs.booleanOpt(c.isRoot).getOrElse(false)
  new User(
    id          = Some(id),
    fullname    = rs.string(c.fullname),
    email       = rs.string(c.email),
    roles       = Set("USER_ID_" + id) ++ (if (root) Set(User.ROOT_ROLE) else Set.empty),
    isRoot      = root,
    contestId   = None,
    password    = Some(rs.string(c.password)),
    lang        = rs.stringOpt(c.lang),
    wikiAccount = rs.stringOpt(c.wikiAccount),
    sort        = rs.intOpt(c.sort),
    createdAt   = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
    deletedAt   = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime)
  )
}
```

**3d. Update `User.create(user: User)`** — remove `contestId` and `roles` from `namedValues`, add `isRoot`:

```scala
def create(user: User)(implicit session: DBSession = AutoSession): User = {
  val id = withSQL {
    insert
      .into(User)
      .namedValues(
        column.fullname     -> user.fullname,
        column.email        -> user.email.trim.toLowerCase,
        column.wikiAccount  -> user.wikiAccount,
        column.password     -> user.password,
        column.isRoot       -> user.isRoot,
        column.lang         -> user.lang,
        column.sort         -> user.sort,
        column.createdAt    -> user.createdAt
      )
  }.updateAndReturnGeneratedKey()

  user.copy(id = Some(id), roles = user.roles ++ Set("USER_ID_" + id))
}
```

**3e. Update the string-parameter `create` overload** (the one that calls `withSQL` directly) — remove `column.roles` and `column.contestId`, add `column.isRoot -> false`:

```scala
def create(
    fullname: String,
    email: String,
    password: String,
    roles: Set[String],
    contestId: Option[Long],
    lang: Option[String],
    createdAt: Option[ZonedDateTime]
): User = {
  val id = withSQL {
    insert
      .into(User)
      .namedValues(
        column.fullname  -> fullname,
        column.email     -> email.trim.toLowerCase,
        column.password  -> password,
        column.isRoot    -> false,
        column.lang      -> lang,
        column.createdAt -> createdAt
      )
  }.updateAndReturnGeneratedKey()

  User(
    id        = Some(id),
    fullname  = fullname,
    email     = email,
    password  = Some(password),
    roles     = roles ++ Set("USER_ID_" + id),
    contestId = contestId,
    createdAt = createdAt
  )
}
```

**3f. Update `User.updateUser`** — remove the `roles` attribute update from `users` table. The role is now updated in `user_contest` by the controller. Remove `"roles" -> roles.head` from the `withAttributes` call:

```scala
def updateUser(
    id: Long,
    fullname: String,
    wikiAccount: Option[String],
    email: String,
    roles: Set[String],
    lang: Option[String],
    sort: Option[Int]
): Unit =
  updateById(id)
    .withAttributes(
      "fullname"    -> fullname,
      "email"       -> email,
      "wikiAccount" -> wikiAccount,
      "lang"        -> lang,
      "sort"        -> sort
    )
```

- [ ] **Step 4: Run the test**

```bash
sbt "testOnly db.scalikejdbc.UserDbSpec"
```

Expected: all examples pass

- [ ] **Step 5: Commit**

```bash
git add app/db/scalikejdbc/User.scala test/db/scalikejdbc/UserDbSpec.scala
git commit -m "feat: update User model — remove DB-backed contest_id/roles, add isRoot"
```

---

## Task 5: Update `User` queries + `Round.availableJurors`

The methods `findByContest`, `loadJurors`, and `Round.availableJurors` all filter on the now-deleted `contest_id`/`roles` columns. Replace them with `user_contest` JOINs.

**Files:**
- Modify: `app/db/scalikejdbc/User.scala`
- Modify: `app/db/scalikejdbc/Round.scala`

- [ ] **Step 1: Replace `User.findByContest` in `app/db/scalikejdbc/User.scala`**

Replace:
```scala
def findByContest(contest: Long): Seq[User] =
  where("contestId" -> contest).orderBy(u.id).apply()
```

with:

```scala
def findByContest(contestId: Long)(implicit session: DBSession = autoSession): Seq[User] = {
  val uc = UserContestJdbc.syntax("uc")
  withSQL {
    select(u.result.*)
      .from(User as u)
      .join(UserContestJdbc as uc).on(u.id, uc.userId)
      .where.eq(uc.contestId, contestId)
      .orderBy(u.id)
  }.map(User(u)).list()
}
```

- [ ] **Step 2: Replace `User.loadJurors(contestId)` in `app/db/scalikejdbc/User.scala`**

Replace:
```scala
def loadJurors(contestId: Long): Seq[User] = {
  findAllBy(sqls.in(User.u.roles, Seq("jury")).and.eq(User.u.contestId, contestId))
}
```

with:

```scala
def loadJurors(contestId: Long)(implicit session: DBSession = autoSession): Seq[User] = {
  val uc = UserContestJdbc.syntax("uc")
  withSQL {
    select(u.result.*)
      .from(User as u)
      .join(UserContestJdbc as uc).on(u.id, uc.userId)
      .where.eq(uc.contestId, contestId)
      .and.eq(uc.role, "jury")
      .orderBy(u.id)
  }.map(User(u)).list()
}
```

- [ ] **Step 3: Replace `User.loadJurors(contestId, jurorIds)` in `app/db/scalikejdbc/User.scala`**

Replace:
```scala
def loadJurors(contestId: Long, jurorIds: Seq[Long]): Seq[User] = {
  findAllBy(
    sqls
      .in(User.u.id, jurorIds)
      .and
      .in(User.u.roles, Seq("jury"))
      .and
      .eq(User.u.contestId, contestId)
  )
}
```

with:

```scala
def loadJurors(contestId: Long, jurorIds: Seq[Long])(implicit session: DBSession = autoSession): Seq[User] = {
  val uc = UserContestJdbc.syntax("uc")
  withSQL {
    select(u.result.*)
      .from(User as u)
      .join(UserContestJdbc as uc).on(u.id, uc.userId)
      .where.in(u.id, jurorIds)
      .and.eq(uc.contestId, contestId)
      .and.eq(uc.role, "jury")
      .orderBy(u.id)
  }.map(User(u)).list()
}
```

- [ ] **Step 4: Add `User.findByContestAndRoles` in `app/db/scalikejdbc/User.scala`**

Add after the `loadJurors` methods:

```scala
def findByContestAndRoles(contestId: Long, roles: Seq[String])
                         (implicit session: DBSession = autoSession): Seq[User] = {
  val uc = UserContestJdbc.syntax("uc")
  withSQL {
    select(u.result.*)
      .from(User as u)
      .join(UserContestJdbc as uc).on(u.id, uc.userId)
      .where.eq(uc.contestId, contestId)
      .and.in(uc.role, roles)
      .orderBy(u.id)
  }.map(User(u)).list()
}
```

- [ ] **Step 5: Update `Round.availableJurors` in `app/db/scalikejdbc/Round.scala`**

Replace:
```scala
def availableJurors(implicit session: DBSession = AutoSession): Seq[User] =
  User.findAllBy(
    sqls.in(User.u.roles, roles.toSeq).and.eq(User.u.contestId, contestId)
  )
```

with:

```scala
def availableJurors(implicit session: DBSession = AutoSession): Seq[User] =
  User.findByContestAndRoles(contestId, roles.toSeq)
```

- [ ] **Step 6: Compile to verify no errors**

```bash
sbt test:compile
```

Expected: clean compile

- [ ] **Step 7: Commit**

```bash
git add app/db/scalikejdbc/User.scala app/db/scalikejdbc/Round.scala
git commit -m "feat: rewrite findByContest/loadJurors/availableJurors to join user_contest"
```

---

## Task 6: Update test helpers and `SharedTestDb`/`SharedPlayApp`

`truncateAll` must clear `user_contest`. `TestDb.createUsers` must create `user_contest` rows so that `findByContest` and `loadJurors` work in tests.

**Files:**
- Modify: `test/db/scalikejdbc/SharedTestDb.scala`
- Modify: `test/db/scalikejdbc/SharedPlayApp.scala`
- Modify: `test/db/scalikejdbc/TestDb.scala`

- [ ] **Step 1: Add `user_contest` to `dataTables` in `SharedTestDb.scala`**

Replace:
```scala
private val dataTables = Seq(
  "criteria_rate", "selection", "category_members", "comment",
  "criteria", "round_user", "images", "rounds", "users",
  "category", "monument", "contest_jury"
)
```

with:

```scala
private val dataTables = Seq(
  "criteria_rate", "selection", "category_members", "comment",
  "criteria", "round_user", "user_contest", "images", "rounds", "users",
  "category", "monument", "contest_jury"
)
```

- [ ] **Step 2: Add `user_contest` to `dataTables` in `SharedPlayApp.scala`**

Replace:
```scala
private val dataTables = Seq(
  "criteria_rate", "selection", "category_members", "comment",
  "criteria", "round_user", "images", "rounds", "users",
  "category", "monument", "contest_jury"
)
```

with:

```scala
private val dataTables = Seq(
  "criteria_rate", "selection", "category_members", "comment",
  "criteria", "round_user", "user_contest", "images", "rounds", "users",
  "category", "monument", "contest_jury"
)
```

- [ ] **Step 3: Update `TestDb.createUsers` to also insert `user_contest` rows**

In `test/db/scalikejdbc/TestDb.scala`, replace:

```scala
def createUsers(role: String, userIndexes: Int*)(implicit
    contest: ContestJury,
    session: DBSession = AutoSession
): Seq[User] =
  userIndexes.map(i => contestUser(i, role)).map(userDao.create)
```

with:

```scala
def createUsers(role: String, userIndexes: Int*)(implicit
    contest: ContestJury,
    session: DBSession = AutoSession
): Seq[User] =
  userIndexes.map(i => contestUser(i, role)).map { u =>
    val created = userDao.create(u)
    UserContestJdbc.createMembership(created.getId, contest.id.get, role)
    created
  }
```

Also add `UserContestJdbc` to the `TestDb` trait accessors:

```scala
val userContestDao: UserContestJdbc.type = UserContestJdbc
```

- [ ] **Step 4: Run all DB tests**

```bash
sbt "testOnly db.scalikejdbc.*"
```

Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add test/db/scalikejdbc/SharedTestDb.scala test/db/scalikejdbc/SharedPlayApp.scala test/db/scalikejdbc/TestDb.scala
git commit -m "test: add user_contest to truncateAll and createUsers helper"
```

---

## Task 7: Update `Secured.userFromRequest` and `LoginController.auth`

`userFromRequest` must read `current_contest_id` from the session and populate `user.contestId`, `user.roles`, and `user.userContests`. `LoginController.auth` must set `current_contest_id` on login.

**Files:**
- Modify: `app/controllers/Secured.scala`
- Modify: `app/controllers/LoginController.scala`

- [ ] **Step 1: Update `Secured.scala`**

Replace the entire file:

```scala
package controllers

import controllers.Secured.{CurrentContestId, UserName}
import db.scalikejdbc.{Round, User, UserContestJdbc}
import play.api.mvc._

abstract class Secured(cc: ControllerComponents) extends AbstractController(cc) {

  type Permission = User => Boolean

  def userFromRequest(request: RequestHeader): Option[User] = {
    request.session
      .get(UserName)
      .map(_.trim.toLowerCase)
      .flatMap(User.byUserName)
      .map { user =>
        if (user.isRoot) {
          // Root users are contest-independent
          user.copy(userContests = Nil)
        } else {
          val memberships = UserContestJdbc.findByUser(user.getId)
          val currentContestId: Option[Long] =
            request.session.get(CurrentContestId).flatMap(s => scala.util.Try(s.toLong).toOption)
              .filter(cid => memberships.exists(_.contestId == cid))
              .orElse(memberships.headOption.map(_.contestId))

          currentContestId match {
            case Some(cid) =>
              val contestRole = memberships.find(_.contestId == cid).map(_.role).getOrElse("jury")
              user.copy(
                contestId    = Some(cid),
                roles        = user.roles ++ Set(contestRole),
                userContests = memberships
              )
            case None =>
              user.copy(userContests = memberships)
          }
        }
      }
  }

  def onUnAuthenticated(request: RequestHeader): Result =
    Results.Redirect(routes.LoginController.login())

  def onUnAuthorized(user: User): Result =
    Results.Redirect(routes.LoginController.error("You don't have permission to access this page"))

  def withAuth(
      permission: Permission = rolePermission(User.ADMIN_ROLES ++ Set("jury", "organizer"))
  )(f: => User => Request[AnyContent] => Result): EssentialAction = {
    Security.Authenticated(userFromRequest, onUnAuthenticated) { user =>
      Action { request =>
        if (permission(user))
          f(user)(request)
        else
          onUnAuthorized(user)
      }
    }
  }

  def rolePermission(roles: Set[String])(user: User): Boolean = user.hasAnyRole(roles)

  def isRoot(user: User): Boolean = rolePermission(Set(User.ROOT_ROLE))(user)

  def contestPermission(roles: Set[String], contestId: Option[Long])(user: User): Boolean = {
    isRoot(user) ||
    (user.hasAnyRole(roles) && user.isInContest(contestId))
  }

  def roundPermission(roles: Set[String], roundId: Long)(user: User): Boolean =
    Round.findById(roundId).exists { round =>
      contestPermission(roles, Some(round.contestId))(user)
    }
}

object Secured {
  val UserName        = "username"
  val CurrentContestId = "current_contest_id"
}
```

- [ ] **Step 2: Update `LoginController.auth()` to set `current_contest_id` in session**

In `app/controllers/LoginController.scala`, update the successful login branch in `auth()`. Replace:

```scala
User
  .login(login, password)
  .map { user =>
    val result = indexRedirect(user).withSession(Secured.UserName -> login)
    user.lang.fold(result)(l => result.withLang(Lang(l)))
  }
```

with:

```scala
User
  .login(login, password)
  .map { user =>
    val memberships = db.scalikejdbc.UserContestJdbc.findByUser(user.getId)
    val firstContestId = memberships.headOption.map(_.contestId.toString)
    val sessionData = Seq(Some(Secured.UserName -> login), firstContestId.map(Secured.CurrentContestId -> _)).flatten
    val result = indexRedirect(user).withSession(sessionData: _*)
    user.lang.fold(result)(l => result.withLang(Lang(l)))
  }
```

- [ ] **Step 3: Compile**

```bash
sbt test:compile
```

Expected: clean

- [ ] **Step 4: Run controller tests**

```bash
sbt "testOnly controllers.*"
```

Expected: all pass (or pre-existing failures only)

- [ ] **Step 5: Commit**

```bash
git add app/controllers/Secured.scala app/controllers/LoginController.scala
git commit -m "feat: load user's contest membership from session in userFromRequest"
```

---

## Task 8: Update `UserService.createUser` and `UserController.saveUser`

`createUser` must insert into `user_contest` after creating the user. `saveUser` must update `user_contest.role` instead of `users.roles`.

**Files:**
- Modify: `app/services/UserService.scala`
- Modify: `app/controllers/UserController.scala`

- [ ] **Step 1: Update `UserService.createUser`**

In `app/services/UserService.scala`, in `createUser`, after `val createdUser = User.create(toCreate)` add the `user_contest` insert. Replace:

```scala
val createdUser = User.create(toCreate)

contestOpt.foreach { contest =>
```

with:

```scala
val createdUser = User.create(toCreate)

contestOpt.foreach { contest =>
  contest.id.foreach { cid =>
    db.scalikejdbc.UserContestJdbc.createMembership(
      createdUser.getId,
      cid,
      toCreate.roles.find(r => r != User.ROOT_ROLE && !r.startsWith("USER_ID_")).getOrElse("jury")
    )
  }
}

contestOpt.foreach { contest =>
```

Wait — the current code already has `contestOpt.foreach` once. Replace the full `createUser` method body to avoid duplication:

```scala
def createUser(
    creator: User,
    formUser: User,
    contestOpt: Option[ContestJury])(implicit messages: Messages): User = {
  val password = formUser.password.getOrElse(User.randomString(12))
  val hash = User.hash(formUser, password)

  val toCreate = formUser.copy(
    password  = Some(hash),
    contestId = contestOpt.flatMap(_.id).orElse(creator.contestId))

  val createdUser = User.create(toCreate)

  contestOpt.foreach { contest =>
    contest.id.foreach { cid =>
      val role = toCreate.roles
        .find(r => r != User.ROOT_ROLE && !r.startsWith("USER_ID_"))
        .getOrElse("jury")
      db.scalikejdbc.UserContestJdbc.createMembership(createdUser.getId, cid, role)
    }
    if (contest.greeting.use) {
      sendMail(creator, createdUser, contest, password)
    }
  }
  createdUser
}
```

- [ ] **Step 2: Update `UserController.saveUser` to update `user_contest.role`**

In `app/controllers/UserController.scala`, after the `User.updateUser(...)` call in `saveUser`, add the role update. Replace:

```scala
User.updateUser(userId,
                formUser.fullname,
                formUser.wikiAccount,
                formUser.email,
                newRoles,
                formUser.lang,
                formUser.sort)
```

with:

```scala
User.updateUser(userId,
                formUser.fullname,
                formUser.wikiAccount,
                formUser.email,
                newRoles,
                formUser.lang,
                formUser.sort)

for {
  cid  <- formUser.contestId.orElse(user.contestId)
  role <- newRoles.find(r => r != User.ROOT_ROLE && !r.startsWith("USER_ID_"))
} UserContestJdbc.updateRole(userId, cid, role)
```

Add `import db.scalikejdbc.UserContestJdbc` at the top of `UserController.scala` if not already present.

- [ ] **Step 3: Compile**

```bash
sbt test:compile
```

Expected: clean

- [ ] **Step 4: Run all tests**

```bash
sbt test
```

Expected: all pass (or only pre-existing failures)

- [ ] **Step 5: Commit**

```bash
git add app/services/UserService.scala app/controllers/UserController.scala
git commit -m "feat: insert user_contest on create; update role on saveUser"
```

---

## Task 9: Contest switcher — route, action, view

Add a dropdown in the navbar that shows the user's contests and lets them switch. A new `POST /switch-contest` action updates `current_contest_id` in the session.

**Files:**
- Modify: `conf/routes`
- Modify: `app/controllers/UserController.scala`
- Modify: `app/views/logged.scala.html`

- [ ] **Step 1: Add route in `conf/routes`**

After the `POST /users/resetpasswd/:id` line, add:

```
POST        /switch-contest                                                          @controllers.UserController.switchContest(contestId: Long)
```

- [ ] **Step 2: Add `switchContest` action in `UserController.scala`**

Add to `UserController`:

```scala
def switchContest(contestId: Long): EssentialAction = withAuth() { user => request =>
  val memberships = UserContestJdbc.findByUser(user.getId)
  if (memberships.exists(_.contestId == contestId)) {
    val newSession = request.session + (Secured.CurrentContestId -> contestId.toString)
    Redirect(routes.LoginController.index).withSession(newSession)
  } else {
    onUnAuthorized(user)
  }
}
```

- [ ] **Step 3: Update `logged.scala.html` to show contest switcher**

Replace the content of `app/views/logged.scala.html`:

```html
@import db.scalikejdbc.{User, UserContest}
@import db.scalikejdbc.ContestJuryJdbc
@(user: db.scalikejdbc.User)(implicit messages: Messages)

@if(user != null) {
    <ul class="nav navbar-nav navbar-right" >
        @if(user.userContests.size > 1) {
        <li class="dropdown">
            <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button" aria-expanded="false">
                <span class="glyphicon glyphicon-random"></span>
                @ContestJuryJdbc.findById(user.contestId.getOrElse(0L)).map(_.name).getOrElse("Switch contest")
                <span class="caret"></span>
            </a>
            <ul class="dropdown-menu">
                @for(uc <- user.userContests) {
                    @defining(ContestJuryJdbc.findById(uc.contestId)) { contestOpt =>
                        @for(contest <- contestOpt) {
                            <li class="@if(user.contestId.contains(uc.contestId)){active}">
                                <form method="POST" action="@routes.UserController.switchContest(uc.contestId)" style="display:inline">
                                    <button type="submit" class="btn btn-link" style="padding:3px 20px">
                                        @contest.name (@uc.role)
                                    </button>
                                </form>
                            </li>
                        }
                    }
                }
            </ul>
        </li>
        }
        <li class="dropdown">
            <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button" aria-expanded="false">
                <span class="glyphicon glyphicon-user"></span> <span class="caret"></span>
            </a>
        <ul class="dropdown-menu">
            <li>
                <a href="@routes.UserController.editUser(user.getId)">
                    <span class="glyphicon glyphicon-user"></span> @Messages("profile")
                </a>
            </li>
            <li>
                <a href="@routes.LoginController.logout()">
                    <span class="glyphicon glyphicon-off"></span> @Messages("logout")
                </a>
            </li>
        </ul>
        </li>
    </ul>
}
```

Note: The contest switcher is only shown when the user belongs to more than one contest. `ContestJuryJdbc.findById` is called per row — acceptable for the small number of contests a user belongs to.

- [ ] **Step 4: Compile**

```bash
sbt test:compile
```

Expected: clean

- [ ] **Step 5: Run full test suite**

```bash
sbt test
```

Expected: all pass

- [ ] **Step 6: Commit**

```bash
git add conf/routes app/controllers/UserController.scala app/views/logged.scala.html
git commit -m "feat: add contest switcher UI and switchContest action"
```

---

## Self-Review Checklist

- [x] **Spec coverage — data model (V50+V51):** Tasks 1–2 ✓
- [x] **Spec coverage — UserContestJdbc DAO:** Task 3 ✓
- [x] **Spec coverage — User model (extract, create, updateUser):** Task 4 ✓
- [x] **Spec coverage — findByContest, loadJurors, availableJurors:** Task 5 ✓
- [x] **Spec coverage — session-based userFromRequest:** Task 7 ✓
- [x] **Spec coverage — UserService.createUser:** Task 8 ✓
- [x] **Spec coverage — contest switcher UI:** Task 9 ✓
- [x] **Root users stay contest-independent:** `Secured.userFromRequest` short-circuits on `user.isRoot` ✓
- [x] **truncateAll includes user_contest:** Task 6 ✓
- [x] **TestDb.createUsers creates user_contest rows:** Task 6 ✓
- [x] **No TBDs or incomplete steps:** checked ✓
- [x] **Types consistent across tasks:** `UserContest` defined in Task 3, used in Tasks 4–9 ✓
