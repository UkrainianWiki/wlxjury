# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

WLX Jury Tool is a Play Framework (Scala) web application for image selection and rating in Wiki Loves Monuments and Wiki Loves Earth photo contests. Jurors browse images, rate/select them across configurable rounds, and organizers manage contests and view aggregated results. Images are fetched from Wikimedia Commons.

## Looking Up Library Sources

**Before searching the web for any library API** (ScalikeJDBC, Testcontainers, Play, Pekko, etc.), check `target/dep-sources/` first — it contains extracted `.scala`/`.java` source files for all dependencies.

```bash
# One-time setup (re-run after adding new dependencies):
sbt extractDepSources

# Then search directly — much faster than web search:
# grep -r "class MariaDBContainer" target/dep-sources/
# grep -r "def withinTxSession" target/dep-sources/
```

Layout: `target/dep-sources/<org>/<artifact-name>-<version>/` — e.g.:
- `target/dep-sources/org.testcontainers/testcontainers-1.x.x/`
- `target/dep-sources/com.dimafeng/testcontainers-scala-core_2.13-0.41.0/`
- `target/dep-sources/org.scalikejdbc/scalikejdbc_2.13-4.3.2/`
- `target/dep-sources/com.typesafe.play/play_2.13-3.0.7/`

If `target/dep-sources/` is empty or missing, run `sbt extractDepSources` once.

## Commands

### Running the application
```bash
sbt run          # Start with auto-reloading on HTTP requests
sbt ~run         # Start with triggered background recompilation
```

Requires environment variables: `WLXJURY_DB_HOST`, `WLXJURY_DB`, `WLXJURY_DB_USER`, `WLXJURY_DB_PASSWORD`

### Testing
```bash
sbt test:compile                                      # Compile all tests
sbt test                                              # Run all tests
sbt "testOnly db.scalikejdbc.RoundSpec"               # Run a single test class
sbt "testOnly db.scalikejdbc.*"                       # Run all DB tests
```

Tests use Testcontainers (Docker) to spin up a MariaDB 10.6 instance automatically. No manual DB setup needed for tests. Test configuration is at `test/resources/application.conf`.

### Building packages
```bash
sbt packageDebSystemd    # Debian package with systemd
sbt packageRpmSystemd    # RPM package with systemd
sbt packageAll           # All package variants
```

## Architecture

### Tech Stack
- **Play Framework 3.0** (Scala 2.13, Netty server — Pekko HTTP server disabled)
- **ScalikeJDBC 4.3** with ORM (`CRUDMapper`) for database access
- **MariaDB** as the database with **Flyway** migrations (`conf/db/migration/default/`)
- **Tapir 1.11** + Pekko HTTP for the REST API layer
- **pac4j** via play-pac4j for authentication
- **scalawiki** (internal library) for Wikimedia Commons integration

### Layer Structure

```
app/
  org/intracer/wmua/   # Domain model (case classes): Image, Selection, ContestJury,
                       #   Round, User, Comment, CriteriaRate, ImageWithRating
  db/                  # Repository interfaces (ImageRepo, RoundRepo)
  db/scalikejdbc/      # Repository implementations using ScalikeJDBC CRUDMapper
  db/scalikejdbc/rewrite/ # ImageDbNew: newer query builder for image queries
  controllers/         # Play MVC controllers (extend Secured)
  services/            # Business logic (ContestService, RoundService, ImageService, etc.)
  api/                 # Tapir REST endpoints (contests CRUD + Swagger UI at /api/...)
  modules/             # Guice DI: AppModule binds RoundRepo→Round, ImageRepo→ImageJdbc
  views/               # Twirl templates
```

### Key Domain Concepts

- **ContestJury**: A photo contest (e.g., "Wiki Loves Monuments 2024 Ukraine")
- **Round**: A judging round within a contest. Can be binary (select/reject) or rated (1–N stars). Rounds can filter images by region, category, monument, size, media type, and special nomination.
- **Selection**: A juror's vote/rating on an image in a round (`page_id`, `jury_id`, `round_id`, `rate`)
- **User roles**: `root` (super-admin), `admin`, `organizer`, `jury`

### Authentication & Authorization

Session-based auth via Play session cookie (username stored as `"username"` key). `Secured` trait (`app/controllers/Secured.scala`) provides `withAuth(permission)` action builder. Role checks use `User.hasAnyRole` and contest membership via `User.isInContest`.

### Database Tests

DB integration tests in `test/db/scalikejdbc/` extend the `TestDb` trait, which spins up a temporary containerized MariaDB via Testcontainers. Tests call `withDb { ... }` or `testDbApp { app => ... }`. DAOs are available as `contestDao`, `imageDao`, `roundDao`, `selectionDao`, `userDao`.

### REST API

Tapir endpoints are defined in `app/api/Api.scala` and routed through Pekko HTTP (separate from Play routing). Swagger UI is auto-generated at `/api/docs`. Play routes are in `conf/routes`.

### Wikimedia Commons Integration

`scalawiki-core` and `scalawiki-wlx` (resolved from `Resolver.mavenLocal` or bintray) provide the Commons API client (`MwBot`). Credentials are optional (`commons.user` / `commons.password` env vars) but improve API rate limits.
