# Flyway Schema Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `SchemaBaselineSpec` test that applies all 44 Flyway migrations and dumps the resulting DDL to `conf/db/migration/default/B44__Baseline.sql`.

**Architecture:** The test spins up its own `MariaDBContainer`, builds a Play app inline (triggering Flyway), then calls `mysqldump` via `container.container.execInContainer`. The output is written to the baseline SQL file. The test is tagged `baseline` and excluded from the default `sbt test` run.

**Tech Stack:** Scala 2.13, specs2 mutable.Specification, `com.dimafeng:testcontainers-scala-mariadb:0.41.0`, `mariadb:10.6.22` Docker image, Play Framework 3.0 / GuiceApplicationBuilder, sbt testOptions.

---

## Files

- **Modify:** `build.sbt` — add `Test / testOptions` to exclude `baseline`-tagged specs2 examples from default test run
- **Create:** `test/db/scalikejdbc/SchemaBaselineSpec.scala` — the generator test
- **Create (generated):** `conf/db/migration/default/B44__Baseline.sql` — produced by running the test

---

## Task 1: Exclude `baseline` tag from default sbt test run

**Files:**
- Modify: `build.sbt`

- [ ] **Step 1: Add testOptions line to build.sbt**

Open `build.sbt`. After the existing `Test / fork := true` line (around line 131), add:

```scala
Test / testOptions += Tests.Argument(TestFrameworks.Specs2, "exclude", "baseline")
```

The full block should look like:

```scala
Test / javaOptions += "-Dconfig.file=test/resources/application.conf"
Test / javaOptions += "-Djna.nosys=true"
Test / fork := true
Test / testOptions += Tests.Argument(TestFrameworks.Specs2, "exclude", "baseline")
```

- [ ] **Step 2: Verify it compiles**

```bash
sbt test:compile
```

Expected: `[success]` — no compilation errors.

- [ ] **Step 3: Commit**

```bash
git add build.sbt
git commit -m "test: exclude baseline-tagged specs from default sbt test run"
```

---

## Task 2: Write SchemaBaselineSpec

**Files:**
- Create: `test/db/scalikejdbc/SchemaBaselineSpec.scala`

This test does NOT extend `TestDb`. It constructs the container and app inline, mirroring the private `TestDb.testDbApp` pattern. It does NOT call `roundDao.usersRef` (that init is only needed when ORM round relations are used).

Key points:
- `MariaDBContainer` from `com.dimafeng.testcontainers` wraps a Java container. `execInContainer` lives on the underlying Java object: `container.container.execInContainer(...)`.
- Credentials are the literal strings `"WLXJURY_DB_USER"` and `"WLXJURY_DB_PASSWORD"` — the same values hardcoded in `TestDb` and `application.conf`, not shell env vars.
- `-p` flag must be concatenated with the password (no space): `"-pWLXJURY_DB_PASSWORD"`. If passed as two separate args mysqldump prompts interactively.
- `--no-tablespaces` is required — the container user lacks the `PROCESS` privilege and mysqldump fails without it.
- `--ignore-table=wlxjury.flyway_schema_history` excludes Flyway's tracking table. Schema name `"wlxjury"` is hardcoded (matches `TestDb.Schema` which is `private`).
- No `--databases` flag — omitting it suppresses `CREATE DATABASE`/`USE` statements; Flyway provides schema context.
- Output is written to `conf/db/migration/default/B44__Baseline.sql` relative to the project root. sbt runs tests from the project root, so this resolves correctly.

- [ ] **Step 1: Create the file**

Create `test/db/scalikejdbc/SchemaBaselineSpec.scala` with this content:

```scala
package db.scalikejdbc

import com.dimafeng.testcontainers.MariaDBContainer
import org.specs2.mutable.Specification
import org.testcontainers.utility.DockerImageName
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.running

import java.io.File
import java.nio.file.Files

class SchemaBaselineSpec extends Specification {

  "schema baseline generator" should {
    "generate B44 baseline migration" in {
      val container = MariaDBContainer(
        dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
        dbName = "wlxjury",
        dbUsername = "WLXJURY_DB_USER",
        dbPassword = "WLXJURY_DB_PASSWORD"
      )

      container.start()
      try {
        val app = new GuiceApplicationBuilder()
          .configure(Map(
            "db.default.driver"   -> container.driverClassName,
            "db.default.username" -> container.username,
            "db.default.password" -> container.password,
            "db.default.url"      -> container.jdbcUrl
          ))
          .build()

        running(app) {
          val result = container.container.execInContainer(
            "mysqldump",
            "--no-data",
            "--skip-lock-tables",
            "--skip-add-drop-table",
            "--no-tablespaces",
            "--ignore-table=wlxjury.flyway_schema_history",
            "-u", "WLXJURY_DB_USER",
            "-pWLXJURY_DB_PASSWORD",
            "wlxjury"
          )

          val sql = result.getStdout
          val outputFile = new File("conf/db/migration/default/B44__Baseline.sql")
          Files.writeString(outputFile.toPath, sql)

          sql must not(beEmpty)
        }
      } finally {
        container.stop()
      }
    } tag "baseline"
  }
}
```

- [ ] **Step 2: Compile**

```bash
sbt test:compile
```

Expected: `[success]` — no errors. If you see a missing import or method-not-found, check:
- `container.container` for the Java unwrap (not `container.dockerImageName`)
- `result.getStdout` is the correct method on `org.testcontainers.containers.Container.ExecResult`

- [ ] **Step 3: Commit the spec file**

```bash
git add test/db/scalikejdbc/SchemaBaselineSpec.scala
git commit -m "test: add SchemaBaselineSpec to generate Flyway B44 baseline"
```

---

## Task 3: Run the generator and commit the baseline SQL

- [ ] **Step 1: Run the generator**

```bash
sbt "testOnly db.scalikejdbc.SchemaBaselineSpec -- include baseline"
```

Expected:
- Docker pulls `mariadb:10.6.22` if not already cached (first run only — subsequent runs are instant)
- Flyway applies all 44 migrations
- `mysqldump` runs inside the container
- Test passes with `[info] Total for specification SchemaBaselineSpec: ... 1 example, 0 failures`
- File `conf/db/migration/default/B44__Baseline.sql` is created

If the test fails:
- Check `result.getStderr` — add a temporary `println(result.getStderr)` inside the test to see mysqldump error output
- Common failure: wrong container hostname/port in the dump command — but since mysqldump runs *inside* the container, it connects to `localhost` by default, which is correct

- [ ] **Step 2: Inspect the generated file**

```bash
head -40 conf/db/migration/default/B44__Baseline.sql
```

Expected: SQL comments header from mysqldump followed by `CREATE TABLE` statements. You should see tables like `contest`, `images`, `rounds`, `selection`, `users`, `comment`, `criteria`, `criteria_rate`, `image_categories`, `monument`, `round_user`.

Verify `flyway_schema_history` is NOT present:
```bash
grep -i flyway conf/db/migration/default/B44__Baseline.sql
```
Expected: no output (the table was excluded).

- [ ] **Step 3: Commit the baseline SQL**

```bash
git add conf/db/migration/default/B44__Baseline.sql
git commit -m "feat: add B44 Flyway baseline migration consolidating V1-V44"
```

---

## How to regenerate in the future

When new migrations V45+ accumulate and another baseline is needed, create a new `SchemaBaselineSpec` variant that writes `B{N}__Baseline.sql` and re-run. The old baseline file and V-prefix migrations between the two baselines can then be deleted.

To regenerate the current B44 baseline (e.g., after confirming no schema drift):

```bash
sbt "testOnly db.scalikejdbc.SchemaBaselineSpec -- include baseline"
git add conf/db/migration/default/B44__Baseline.sql
git commit -m "chore: regenerate B44 baseline"
```
