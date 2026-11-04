# CI for wlxjury and scalawiki on GitHub Packages

Status: the wlxjury part is implemented in PR `[55] CI` (branch `stack/55-ci`, on top of `stack/54-mariadb-10-11`). The scalawiki part is still a draft: `docs/plans/ci-drafts/scalawiki/`, to become a separate PR in that repository.

## Goal

1. Every wlxjury PR and every push to `master` and `ukwikidev/followups` runs the full sbt suite and the Jest tests on GitHub Actions. The deployment tool's tests run when what they depend on changes.
2. scalawiki gets a published, immutable version that wlxjury depends on, instead of a `0.7.0-SNAPSHOT` that exists only in each developer's `~/.ivy2/local`.

## Approved decisions (2026-11-03)

- **Where CI comes in:** a new PR on top of the stacked series (`[55]`, base `stack/54-mariadb-10-11`). The historical stack PRs #184–#237 get no CI, because their bases don't contain the workflow files. Later work runs it.
- **scalawiki:** built from source in CI for now, pinned to `25951dbe`, until a package is published.
- **MariaDB 10.11:** only on pushes to master and in the weekly run (`ci.yml`'s matrix and schedule). PRs test 10.6.22.
- **No scalawiki snapshots** are published; only tags.
- **Dead resolvers:** Bintray and JCenter are removed from wlxjury's `build.sbt` in this PR. The scalawiki side is in its later PR.
- **`appveyor.yml`** is deleted once Actions is green, in a follow-up PR. **Done** (2026-11-04,
  the Java 17 batch, with the README's AppVeyor and codecov badges).

## Facts this plan rests on

| | wlxjury | scalawiki |
|---|---|---|
| Repository | `UkrainianWiki/wlxjury`, **public**, default branch `master` | `UkrainianWiki/scalawiki`, **public**, default branch `master` |
| Branch used | `ukwikidev/followups` (integration) | `origin/page-categories` = `master` + 2 commits (`25951dbe`, 2026-10-28) |
| sbt / Scala / JDK | sbt 1.9.6, Scala 2.13.15, JDK 17 | sbt 1.10.6, Scala 2.13.15 only (`crossScalaVersions := Seq(Scala213V)`, so `+test` builds only 2.13), JDK ≥ 11 asserted, `-target:jvm-1.8` |
| Existing CI | `appveyor.yml` (sbt 1.3.13, VS 2017: dead); one Copilot review run on Actions; no workflow | `.github/workflows/ci.yml` (JDK 11, `sbt +test`, green in ~3 min on Scala Steward PRs); Scala Steward workflow (manual); a dead `appveyor.yml` |
| Actions | enabled, all actions allowed; default `GITHUB_TOKEN` permission **write** | enabled, all actions allowed |
| Org plan | Free: public repositories get unlimited standard-runner minutes; 20 concurrent jobs per org | |

### scalawiki build audit (`origin/page-categories`)

- **Modules:** `http-extensions`, `scalawiki-core` (depends on `http-extensions`), `scalawiki-dumps`, `scalawiki-wlx`, `scalawiki-bots` (Twirl and POI; an application), and the root aggregate `scalawiki`.
- **Coordinates:** `organization := "org.scalawiki"`, `version := "0.7.0-SNAPSHOT"` hard-coded in `commonSettings`. Releases `0.6.0`–`0.6.4` (last 2021-06-28) are plain tags without a `v` prefix, published to Bintray (`dl.bintray.com/intracer/maven`, dead since 2021). Nothing has been published since. The `0.6.4` ref name is ambiguous in the local clone: a branch or remote branch with the same name exists. Tag pushes must say `refs/tags/…` if that matters.
- **Publish settings:** none, apart from the `sbt-bintray` 0.6.1 plugin, which replaces `publishTo` with Bintray. This **blocks publishing** and must go.
- **Resolvers:** `Dependencies.resolvers` lists Typesafe, two dead `dl.bintray.com` repositories and JCenter. No dependency needs them now: scalawiki's own CI resolves on a clean runner, and every non-Central artifact in the local Coursier cache is an old one, such as scalawiki 0.6.3 or sbt 1.x plugins. They only slow resolution down. If they stayed, sbt would also write them into the published POMs as `<repositories>`. The draft sets `pomIncludeRepository := false`, so the POMs are clean either way. Dropping the dead resolvers is a separate cleanup.
- **Dependencies wlxjury pulls in through `scalawiki-core` and `scalawiki-wlx`:**
  - Pekko 1.0.3 and Pekko HTTP 1.0.1, including caching (`Library.Pekko`). wlxjury pins the same versions (`PekkoVersion`, `PekkoHttpVersion` in `build.sbt`, with the comment to update scalawiki first). They are in sync now.
  - play-json 2.9.4 (`com.typesafe.play`), typesafe config 1.4.3, ficus 1.5.2, chronoscala 1.0.0, logback 1.5.15, swc-engine 3.1.9, ptk-common 3.0.8, jsoup 1.18.3, retry 0.3.6 and scallop 5.2.0.
  - From `scalawiki-wlx`: scala-chart 0.8.0, scala-csv 2.0.0 and progressbar 0.10.1.
  - Its `dependencyOverrides` are not transitive, so they don't reach wlxjury.
  - All of these are on Maven Central. Nothing blocks consumers.
- **Build as-is:** see "Build check" at the end.

## Decisions

### Group id: keep `org.scalawiki`

Artifacts: `org.scalawiki:scalawiki-core_2.13`, `scalawiki-wlx_2.13`, `http-extensions_2.13` and `scalawiki-dumps_2.13`. wlxjury's dependency lines stay the same, and the previous releases used the same group.

- GitHub Packages doesn't check group ownership.
- *Future option:* publishing to Maven Central under `org.scalawiki` needs proof of ownership of `scalawiki.org` through a DNS TXT record. Without that, the group would have to be renamed then.

### Versioning

- **Releases come only from tags:**
  - Use plain tags like `0.7.0-M1`, `0.7.0` or `0.7.1`, with no `v` prefix, to match the existing tags.
  - sbt-dynver 5.1.0 reads the version from the tag: `dynverVTagPrefix := false`, `dynverSeparator := "-"`.
- **Any other commit, or a dirty tree, builds as a `-SNAPSHOT`.** With `dynverSonatypeSnapshots := true`, a build looks like `0.7.0-M1-3-1a2b3c4d-SNAPSHOT`. An untagged build can't pass for a release.
- **Snapshots are not published.**
  - GitHub Packages accepts Maven snapshots, but wlxjury would then depend on something mutable, which is today's problem.
  - Snapshots stay local, through `publishLocal`.
- **Versions are immutable:**
  - The workflow publishes each tag once, and a fix gets a new tag.
  - Never move or delete a published tag, and never delete a package version.
  - GitHub Packages rejects re-uploading an existing release version. I haven't tested this on this registry; it is what Maven repositories normally do.
- `versionScheme := Some("early-semver")` tells eviction checks that 0.x minor versions are binary-incompatible.
- **First release: `0.7.0-M1`** on the commit wlxjury builds against now, `25951dbe` (see the open questions). **Superseded (2026-11-04):** scalawiki moves to a Java 17 baseline (branch `java17`, off `master`), and with sbt-dynver the tagged commit must contain `publishing.diff` and `publish.yml`, which `25951dbe` doesn't. The order is: `java17` into `master` and `page-categories`, then the publishing branch (A3), then tag `0.7.0-M1` on top. So the first published release is Java 17 (class file 61) from the start; wlxjury already runs on 17. The `java17` branch also removes `sbt-bintray` and the dead resolvers, so `publishing.diff`'s removal of them is redone against it.

### Publishing

- **Plain `publishTo` and credentials, not `sbt-github-packages`.** That's six lines with no plugin to keep updated, and the resolver order on the consumer side stays explicit.
- **The publish workflow:** on a tag push it checks that HEAD is exactly at the tag and clean (`dynverAssertTagVersion`), runs `sbt test`, then `sbt publish` with `GITHUB_TOKEN` and `packages: write`.
- **What is published:** the root aggregate and `scalawiki-bots` set `publish / skip := true`. `http-extensions` has to be published, because `scalawiki-core`'s POM depends on it.
- **Package visibility:** Maven packages on GitHub are **repository-scoped**. Each one is linked to `UkrainianWiki/scalawiki` and inherits its permissions and visibility, which is public. Sources: [About permissions for GitHub Packages](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages) ("Apache Maven … only support repository-scoped permissions").

### Consumption in wlxjury

Draft: `docs/plans/ci-drafts/wlxjury/consume-scalawiki-package.diff`.

- **Resolver order:**
  1. `~/.ivy2/local`, sbt's built-in local repository. This is where scalawiki's `publishLocal` goes, and what wlxjury resolves from today: `~/.m2` has no scalawiki at all.
  2. `~/.m2` (`Resolver.mavenLocal`, kept).
  3. Maven Central.
  4. GitHub Packages last, through `externalResolvers +=`. Every other library is then found on Central without a round trip to a registry that answers 401 without a token, and a locally built scalawiki always wins.
- **The dead `bintrayRepo("intracer")`, `scalaz-bintray` and JCenter resolvers are already removed** in PR [55], and the diff is relative to that.
- **Version:** `ScalawikiVersion = sys.props.getOrElse("scalawiki.version", "0.7.0-M1")`. Joint development of both repositories uses `-Dscalawiki.version=0.7.0-SNAPSHOT`.
- **Credentials:**
  - Realm `GitHub Package Registry`, host `maven.pkg.github.com`. The token comes from `GITHUB_PACKAGES_TOKEN`, falling back to `GITHUB_TOKEN`; the user name comes from `GITHUB_ACTOR` (any non-empty name works with a PAT).
  - In CI, the workflow's `GITHUB_TOKEN` with `packages: read`.
  - Locally, a **classic** PAT with only `read:packages`, since fine-grained PATs don't work with the Maven registry. Put it in `~/.sbt/1.0/github.sbt` or in `GITHUB_PACKAGES_TOKEN`. Don't put it in a global `GITHUB_TOKEN`: `gh` gives that variable priority over its own login.

**Developer setup note** (for DEV.md once the package exists):

```scala
// ~/.sbt/1.0/github.sbt: global settings for every sbt build on this machine
credentials += Credentials("GitHub Package Registry", "maven.pkg.github.com",
  "<your GitHub login>", "<classic PAT with read:packages only>")
```

```bash
# Working on scalawiki and wlxjury together
cd scalawiki && sbt 'set ThisBuild / version := "0.7.0-SNAPSHOT"' publishLocal
cd wlxjury   && sbt -Dscalawiki.version=0.7.0-SNAPSHOT test
```

On Windows with the direct launcher, put `-Dscalawiki.version=…` before `-jar`, as CLAUDE.md says for `-Dintegration`.

### Can the `GITHUB_TOKEN` of wlxjury (and of fork PRs) read scalawiki's package?

- **Fork PRs:** GitHub turns every write permission of a fork PR's token into read, and `packages: read` stays read ([workflow syntax, `permissions`](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)). A fork PR therefore has whatever read access same-repository PRs have. Secrets are never passed to fork PRs, so a PAT secret is no fallback for them.
- **Cross-repository read:**
  - GitHub's docs say that a workflow's `GITHUB_TOKEN` can install packages associated with the workflow's repository. They also say that the Maven registry needs a token even for public packages, and that packages in other private repositories need a PAT. ([Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry))
  - The **public** cross-repository case is not documented. An older community answer says the token is limited to its own repository ([discussion 25513](https://github.com/orgs/community/discussions/25513), 2019, likely about a private package).
  - **Confidence that wlxjury's `GITHUB_TOKEN` can read the public `org.scalawiki` packages: medium-low.** It must be tested (rollout step 6) before anything depends on it.
- **"Manage Actions access":**
  - That panel (Package settings → Manage Actions access → Add repository → Role: Read) exists only for registries with granular permissions: Container, npm, NuGet and RubyGems.
  - A repository-scoped Maven package doesn't have it. Its access is the linked repository's.
  - So there is probably **no package-access step to do**. The user action is to check: open the org's Packages tab → `scalawiki-core_2.13` → Package settings, and see whether a Manage Actions access section is offered. If it is, add `UkrainianWiki/wlxjury` with role **Read** for each of the four packages.
- **If `GITHUB_TOKEN` can't read them:** a PAT secret serves only same-repository runs. Keep the from-source build (below) as CI's way of getting scalawiki, either alone or as the fallback for runs without the secret. Don't widen anything else.

### wlxjury CI (`.github/workflows/ci.yml`)

- **Triggers:**
  - `pull_request` with no branch filter, so stacked PRs against any base run.
  - `push` to `master` and `ukwikidev/followups`.
  - Weekly `schedule` (Mondays 03:41 UTC, on master).
  - `workflow_dispatch`.
  - Default PR event types: a stack retargeted after a merge reruns on the rebase push, not on the base change (see the open questions).
- **MariaDB matrix:** `mariadb:10.6.22` always. `mariadb:10.11.14` is added only for pushes to master and the weekly run. The image goes to `SharedTestDb.Image` through `-Dwlxjury.test.mariadb-image`. Job names are `sbt test (mariadb:<version>)`.
- **Jobs on `ubuntu-24.04`, pinned rather than `ubuntu-latest`:**
  - `build.sbt` notes that Testcontainers' Docker API compatibility depends on the Docker Engine version, so a runner image move should be deliberate.
  - Public-repository runners have 4 vCPUs and 16 GB.
- **Setup:**
  - Temurin 17 with `cache: sbt`, which covers `~/.ivy2/cache`, `~/.sbt` and Coursier, keyed on the build files.
  - `sbt/setup-sbt`, pinned by commit (`ecad189d…`, v1.5.12).
  - Node 20 with an npm cache keyed on `javascript-test/package-lock.json`. `jsTest` runs `npm ci` itself.
- **scalawiki:** a composite action, `.github/actions/scalawiki`. See "Interim path".
- **Testcontainers:**
  - Docker is native on Linux runners, and Ryuk works there.
  - `withTmpFs(/var/lib/mysql, size=1g)` becomes a Docker `--tmpfs` mount on the runner's own kernel. It works there, as it does locally in Docker Desktop's VM, and costs at most about 350 MB of the runner's 16 GB.
  - `docker pull` of the matrix image is its own step, so its time doesn't count as test time.
- **Commands:** `python3 scripts/agent-check.py -- sbt -J-Xmx2g -J-Dfile.encoding=UTF-8 "-Dwlxjury.test.mariadb-image=$MARIADB_IMAGE" 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8")' Test/compile test`. This uses CLAUDE.md's JVM options and order in one sbt session, except for sbt's 2 GB heap (see Risks). The first failing command stops it, so a compile error ends the run without testing.
  - The script prints only a summary. On failure the next step prints the last 400 lines of the log.
  - `target/test-reports/` (JUnit XML), `target/checks/` and any `hs_err_pid*.log` in the project root (where the forked test JVM runs; no `**` glob, so `node_modules` isn't walked) are uploaded with `if: always()` and kept 14 days.
- **Permissions and concurrency:**
  - Permissions are `contents: read`; add `packages: read` with the package switch.
  - **Concurrency:**
    - PRs: one group per PR, and a new push cancels the PR's older run.
    - Pushes, the weekly schedule and manual runs: a group per event, ref and commit (`github.sha`). GitHub keeps only one pending run per group, so a shared group would drop runs of merged commits.
- **Timeout:** 45 minutes.

**Deployment tests (`.github/workflows/deployment.yml`):**

- **Job `scripts`:** the Python unit tests (`test_deployment`) in `python:3.8.20-slim` and shellcheck in `koalaman/shellcheck:v0.11.0` (exact versions), each with `--network none` as in `docs/deployment.md`. About 1 minute.
- **Job `release`:**
  - `sbt dist`, then `docker build` of `Dockerfile.test` (Ubuntu 22.04, MariaDB 10.6, OpenJDK 17), then `test_database_restore` and `test_release_script` against the ZIP.
  - Estimate: 4–6 min for `dist` cold, 2–3 min for the image, and 3–6 min for the tests (not measured), so **10–15 minutes**.
  - `test_database_restore` skips its packaged-release test if the ZIP is missing, so the job builds it first.
- **Triggers:** every PR, and pushes to master and followups. (The first version filtered PRs by path. The release test starts the packaged app, though, so any startup code matters, and an always-run check can be made required without staying pending. The job takes about 4.5 minutes.)
- **The packaged-release test** finds the ZIP by `wlxjury-*.zip`, the newest one. With `CI` set (passed into the container) it fails, instead of skipping, when the ZIP is missing.
- The sbt specs in that runbook list (`deployment.*`, `ReadinessControllerSpec`, …) already run in ci.yml's full suite.

**Live Commons specs (`.github/workflows/integration.yml`):**

- Run weekly (Mondays 04:17 UTC) and by hand, never on PRs: `-Dintegration=true` with `testOnly` of `AppendImagesSpec`, `ImagesFromListSpec` and `LocalImageCacheServiceIntegrationSpec`.
- GitHub runs schedules from the default branch's copy of the file. It only starts after this reaches `master`, and stops after 60 days without repository activity.

### Runtime estimate

| | Estimate (ubuntu-24.04, 4 vCPU) |
|---|---|
| Setup, cache restore, image pull | 1–2 min |
| scalawiki from source (cache miss / hit) | 2–3 min / 10 s |
| `Test/compile`, cold incremental state (sbt `target/` isn't cached) | 6–9 min |
| Full suite and Jest (7:44 locally on 8 cores, compile included) | 10–15 min |
| **CI run** | **about 20–28 min, so 25 min for planning** |
| Deployment `release` job, when triggered | 10–15 min |

*These are the planning estimates. The measured figures (a CI run takes 5–7 min; the deployment job, now on every PR, about 4.5 min) are under "Validation of PR [55]".*

**Assumptions:** each of the 54 stacked PRs gets about 3 runs (open, review fixes, rebase after the PR below merges), and about a fifth trigger the deployment job.

- **Runner time:** about 54 × 3 × 25 ≈ **4,050 min**, plus about 30 × 12 ≈ 360 min, plus pushes to followups and master (about 40 × 25 = 1,000 min). That is **about 5,400 runner-minutes (90 h) in the month of the series**, and about 1,000–1,500 min/month after it.
- **Cost:** both repositories are public, so standard-runner minutes are **free**.
- **The real limit is the Free plan's 20 concurrent jobs:**
  - Opening all 54 PRs at once queues 54 runs, about 3 waves of 25 min, so the last PR waits about 75 min for a result.
  - Open the stack in batches, or accept the queue.
  - Cancelling superseded runs prevents most of the rebase churn.
- **Later saving:** cache sbt's `target/` directories (keyed on the commit, restored from the base branch) to save most of the compile time. Not in the first version: stale incremental state is a known source of false results.

## Interim path, until the first scalawiki package exists

**Recommendation: build scalawiki from source in CI now, then switch to the published package.**

- **What the composite action does:** it fetches the pinned commit `SCALAWIKI_SHA` (public, no token) into `$RUNNER_TEMP` and runs `http-extensions/publishLocal scalawiki-core/publishLocal scalawiki-wlx/publishLocal` without Scaladoc. It caches `~/.ivy2/local/org.scalawiki` keyed on the SHA, the JDK and a version (`<sha>-jdk17-v1`), so only the first run per SHA pays the 2–3 minutes.
  - At `25951dbe` the build still says `0.7.0-SNAPSHOT`, which is exactly what wlxjury's `build.sbt` asks for today. So the workflows need **no change to `build.sbt`**, and CI can start before any scalawiki change.
- **Why not a hand-published first package:**
  - It needs a classic PAT with `write:packages` on someone's machine, and the publishing settings merged into scalawiki first.
  - It also creates the immutable first version before the tag workflow has proved itself.
  - CI's start would depend on all of that.
- **Why this order:**
  - CI can start this week.
  - The package's cross-repository readability (above) is tested separately afterwards.
  - If that test fails, from-source is already the fallback that works for fork PRs too.
- **Cost:** the SHA has to be bumped by hand in three workflows (`ci.yml`, `deployment.yml`, `integration.yml`). This is temporary: the package removes it.

## Files

In PR [55], under their real paths:

- `build.sbt`: the dead Bintray and JCenter resolvers removed
- `.github/workflows/ci.yml`
- `.github/workflows/deployment.yml`
- `.github/workflows/integration.yml`
- `.github/actions/scalawiki/action.yml`

Drafts:

- `docs/plans/ci-drafts/scalawiki/publishing.diff`: `build.sbt` and `project/plugins.sbt` for scalawiki's `page-categories` (`git apply` there).
- `docs/plans/ci-drafts/scalawiki/.github/workflows/publish.yml`: goes to scalawiki's `.github/workflows/publish.yml`.
- `docs/plans/ci-drafts/scalawiki/anonymous-tests.diff`: scalawiki's tests made anonymous, and the live `WlmUaListsSpec` behind `-Dscalawiki.live=true` (see "Anonymous Wikimedia access").
- `docs/plans/ci-drafts/wlxjury/consume-scalawiki-package.diff`: wlxjury's `build.sbt` once `0.7.0-M1` exists, relative to [55].

## Anonymous Wikimedia access (added 2026-11-03)

**Incident.** The owner of IlyaBot got many "multiple failed attempts to log in to Wikimedia Commons" notices after the bot password changed.

- This machine has `COMMONS_USER` / `COMMONS_PASSWORD` in its environment.
- `test/resources/application.conf` read them (`${?COMMONS_USER}`), so every test JVM's app logged in as IlyaBot, with the stale password.
- scalawiki logs a new `MwBot` in from `SCALAWIKI_LOGIN` / `SCALAWIKI_PASSWORD` too, through the default `LoginInfo.fromEnv()` of `MwBot.fromHost` / `fromSite`. Those two aren't set here.

**Rule, for both repositories:** tests and CI use Wikimedia anonymously. A test that reads live wikis at volume goes behind a flag that is off by default.

**wlxjury (PR [55]):**

- **Test config:** `commons.user = ""`, `commons.password = ""`, with no environment substitution, and a comment saying why. `CommonsLogin.credentials` drops empty values.
- **Test-mode rule:** `CommonsLogin.credentials(config, mode)` gives no account in Mode.Test unless `commons.allowTestLogin` is true, and logs a warning instead. No spec or CI job sets it, and `build.sbt` doesn't forward it from sbt's command line. The only opt-in is a deliberate manual one, all three in the forked JVM: `set Test / javaOptions ++= Seq("-Dcommons.allowTestLogin=true", "-Dcommons.user=…", "-Dcommons.password=…")`.
- **Bot creation, and scalawiki's default bots:**
  - scalawiki's `MwBot.fromHost` / `fromSite` default to `LoginInfo.fromEnv()`, and `MwBot.cache` keeps one bot per domain.
  - wlxjury creates its bots through `services.imports.AnonymousBots` (`loginInfo = None`): `AppModule.bot`, `Global.commons`, `MonumentListSource`, `SpecialNominationFilter`'s monument query, and the test helper `CommonsImageFetcher`.
  - `AppModule` seeds the anonymous commons and uk.wikipedia bots at startup, so scalawiki's own default calls get them from the cache:
    - `SpecialNomination.getNominationMonuments`, whose `MonumentQuery.create` takes `MwBot.fromHost(MwBot.ukWiki)`;
    - `CampaignList` (`WithBot`, commons).
  - `SpecialNominationFilter` and `ContestController.fetchContests` also seed before those calls, for JVMs without the app.
  - **Covered:** every wlxjury path to commons and uk.wikipedia, and every bot wlxjury creates.
  - **Not covered:** a scalawiki default call for another domain, in a JVM where neither the app nor `AnonymousBots` created that domain's bot first. wlxjury makes none.
  - `AnonymousBotsSpec` checks that the test app seeds the bots, that the default `fromHost` then returns them, and that `app/` creates no bot or `MonumentQuery` with scalawiki's default login.
- **`TestConfigAnonymousSpec` checks four things:**
  - the test config resolves to an empty account even with `COMMONS_USER` etc. supplied;
  - it has no `${?COMMONS_*}` / `${?SCALAWIKI_*}` substitution;
  - the test JVM's loaded config gives `CommonsLogin` no account;
  - the workflows pass no `COMMONS_*` / `SCALAWIKI_*` names and no `secrets.*` but `GITHUB_TOKEN`.
- **Other paths:**
  - The Gatling fixture uses the same test config in Mode.Test.
  - The bench server maps the Wikimedia hosts to 127.0.0.1 and passes no Commons environment.
  - The deployment test image runs with `--network none` and no `-e`. `test_database_restore` now also strips `COMMONS_*` / `SCALAWIKI_*` from the packaged release's environment.
- **Workflows:** each says, in a comment, never to pass these credentials.
- **Manual runs:** sbt and Docker runs on a machine with bot credentials use `env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD` (CLAUDE.md, Testing).

**wlxjury specs that read live Wikimedia, all off by default and anonymous:**

| Spec | Flag | Live requests per run |
|---|---|---|
| `AppendImagesSpec` "get international images" | `-Dintegration=true` | one winners page's files (350+), in batches of 50: about 10–20 API requests |
| `ImagesFromListSpec` | `-Dintegration=true` | 262 listed titles: about 6–10 API requests |
| `LocalImageCacheServiceIntegrationSpec` | `-Dintegration=true` | a category listing and 5 image downloads |
| `LocalImageCacheServiceDockerSpec` | `-Ddocker.tests=true` | a category listing and 50 image downloads (the heaviest) |

Only the `-Dintegration=true` group runs in CI, weekly (`integration.yml`). Every other spec uses fakes, stubs or mock servers.

**scalawiki (draft: `docs/plans/ci-drafts/scalawiki/anonymous-tests.diff`, for a PR in that repository).** Findings on `origin/page-categories`:

| Where | What | Proposal |
|---|---|---|
| `LoginInfo.fromEnv` (default `loginInfo` of `MwBot.fromHost` / `fromSite`, so also `CachedBot.sessionHttp` and scalawiki's library code) | logs a new bot in as `SCALAWIKI_LOGIN` from the environment | returns None when `-Dscalawiki.anonymous=true`. The build sets that for the forked test JVM unless sbt runs with `-Dscalawiki.allowTestLogin=true`. Empty values count as unset |
| `scalawiki-wlx` `WlmUaListsSpec` | runs in the default `sbt test`, also in scalawiki's CI. Reads every WLM-UA monument list from live `uk.wikipedia.org` (hundreds of requests without a local `http-cache/`) through `CachedBot`, which logs in from the environment | `skipAllUnless(-Dscalawiki.live=true)` and lazy vals, so a skipped spec makes no request. The build forwards `-Dscalawiki.live` |
| `BaseMockServerSpec.getBot` | `fromHost` on the mock server with the default `loginInfo`: would send environment credentials to it | `loginInfo = None` |
| `scalawiki-core/src/it/` (`BaseIntegrationSpec`, `IntegrationSpec`, `EditEncodingIntegrationTest`, `EditTokensIntegrationTest`) | log in with `LoginInfo.fromEnv()` and edit live wikis | not compiled or run: the build has no IntegrationTest configuration. Leave them out of the build; if they come back, put them behind `scalawiki.allowTestLogin` |
| `LoginSpec`, `CachedBotSpec` and the other wlx specs | stub HTTP clients and mock servers; `CachedBotSpec` already passes `loginInfo = None` | none |
| `.github/workflows/ci.yml`, the drafted `publish.yml` | pass no wiki credentials | a comment saying so |

The two scalawiki diffs (`publishing.diff`, `anonymous-tests.diff`) both change `build.sbt`. Make them separate PRs and rebase the second on the first. The anonymous-tests one should go first.

## Test coverage (added 2026-11-03, PR [60])

**What runs where:**

- **`.github/workflows/coverage.yml`, job `Coverage (Scala and JS)`:** its own workflow, so `ci.yml`'s test job keeps its speed. Same triggers as `ci.yml` without the weekly schedule: every PR (any base), pushes to `master` and `ukwikidev/followups`, and `workflow_dispatch`. Same setup (Temurin 17, sbt cache, Node 20, the scalawiki action) and MariaDB 10.6.22 only.
  - Command: `env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD python3 scripts/agent-check.py -- sbt … coverage Test/compile test coverageReport`. No `-Dintegration`, no credentials (the anonymous rule above).
  - `TestConfigAnonymousSpec` flags any `COMMONS_*` / `SCALAWIKI_LOGIN` etc. in a workflow line, which failed CI's first run on this `env -u` line. It now ignores a name only as `env -u NAME` (a whole argument, so `-u COMMONS_USER=x` and a following `NAME=x` are still flagged), with examples both ways.
  - `coverageAggregate` is not needed: the build is one project.
  - **Non-blocking:** no coverage minimum, and the check is not to be required (U8 names only `CI / sbt test (mariadb:10.6.22)`). A real test failure still turns the job red, as it would in `ci.yml`.
- **Jest:** `build.sbt`'s `jsTest` passes `--coverage` when `coverageEnabled` is on (that is, after `coverage` in the same sbt session), and `--no-coverage` otherwise. So a plain `sbt test`, and `ci.yml`, are unchanged, and the coverage job runs Jest once, with coverage.
  - Config in `javascript-test/package.json` (`"jest"`): `rootDir` is the repository root and `roots` are `javascript-test/` and `public/javascripts/`, because Jest measures nothing outside its `rootDir` (the scripts under test are in `public/javascripts/`).
  - `coverageProvider: "v8"`: the default Babel provider reported 0 of 0 statements for these files, with the same config. V8 counts statements as lines.
  - `collectCoverageFrom`: our own `public/javascripts/*.js`, untested ones included, without the vendored `jquery.hotkeys.js`, `jquery.raty.js`, `unitegallery.js` and `ug-theme-tiles.js`.
  - Reporters: `lcov` (`lcov.info` and an HTML report), `text-summary` (in the log) and `json-summary`, into `target/jest-coverage/`.
- **Scala exclusions (`build.sbt`):** generated code only. `coverageExcludedPackages`: `router.*`, `controllers.javascript.*`, `controllers.Reverse*`, `*BuildInfo*` (there is no BuildInfo today; harmless). `coverageExcludedFiles`: `.*/target/.*`, every managed source (Twirl and routes are compiled from `target/scala-2.13/twirl` and `routes`). The templates go by file only, not by a `views.html.*` package rule: the hand-written `app/views/bs/FieldView.scala` and `Layout.scala` are in package `views.html.bs` and are measured.
  - **Not excluded: `org.intracer.wmua.cmd`.** Despite the name it holds no command-line tools: `DistributeImages` and `ImageWithRatingSeqFilter` are the distribution and filtering logic that `RoundDistributionHandler` uses, with their own specs. There is no `main` or `App` under `app/`.
- **Reports:** artifact `coverage-reports-attempt-<n>`, kept 14 days: `target/scala-2.13/scoverage-report/scoverage.xml`, `target/scala-2.13/coverage-report/` (Cobertura `cobertura.xml`), `target/jest-coverage/` (`lcov.info`, `lcov-report/index.html`, `coverage-summary.json`), plus the JUnit XML and the check log.
  - The Scala HTML report (`target/scala-2.13/scoverage-report/`, `index.html`; about 22 MB) is a second artifact, `coverage-html-attempt-<n>`, on pushes and manual runs only. For a PR, read the job summary and the XML, or run `sbt coverage test coverageReport` locally.
  - `scripts/coverage-summary.py` writes the job summary (`$GITHUB_STEP_SUMMARY`): Scala statement and branch %, and JS lines, statements, functions and branches %. It runs `if: always()` and lists a missing report as missing.
- **Packaging stays uninstrumented:**
  - `deployment.yml`'s `sbt dist` runs on its own runner, in a session that never ran `coverage`, so nothing instrumented can reach it.
  - Locally, `coverage` stays on for the rest of that sbt session. `build.sbt` now fails `Universal / mappings` (which `dist`, `stage`, the Debian and RPM packages all use) while `coverageEnabled` is on: `sbt coverage dist` stops with "Coverage instrumentation is on…". A new session or `coverageOff` recompiles without instrumentation (scalac options change).
- **Plugin versions:**
  - `sbt-scoverage` **2.1.0 → 2.3.1** (2025-02-20). Scoverage's compiler plugin is published per full Scala version: for Scala 2.13.15 only `scalac-scoverage-plugin` 2.2.1 and 2.3.0 exist. 2.1.0 wants 2.1.1, which has no 2.13.15 build, so `sbt coverage` could not have resolved. 2.4.x (from 2025-10) needs the 2.4/2.5 compiler plugin, which starts at Scala 2.13.16. 2.3.1 is built against sbt 1.10.7 and works on this build's sbt 1.9.6 (`project/build.properties`; not 1.10.x).
  - A later bump of Scala to 2.13.16+ allows sbt-scoverage 2.4.x.
  - `sbt-coveralls` 1.3.11: **left as is, unused.** Nothing calls `coveralls`. It is the ready option if Coveralls is chosen.
- **External services:** none yet, and no tokens. Coveralls (`sbt-coveralls`, or the Coveralls GitHub Action with `lcov.info` and Cobertura) or Codecov (its action reads `cobertura.xml` and `lcov.info`) would add PR comments and history. A later decision for the user; both need a repository token or OIDC setup.

**Measured locally** (8 cores, Windows, Docker Desktop; one subset of 15 specs, 164 examples: the three lock-sensitive specs `CriteriaRateSpec`, `RoundImageLockSpec`, `WritePathLockSpec`, the reflection-based `CategoryOperationInitializationSpec`, `ContestFilesPageSpec`, `RoundSpec`, `services.distribution.*` and `org.intracer.wmua.cmd.*`):

| | Without coverage | With coverage |
|---|---|---|
| `Test/compile` | 171 s (cold test compile) | 124 s (main recompiled with instrumentation) |
| Specs, summed per-spec times | 142.5 s | 167.7 s (**+18%**) |
| `coverageReport` | | 18 s |
| Result | 164 passed | 164 passed |

- The lock and timing specs passed under instrumentation with their own timeouts: `CriteriaRateSpec` 18.7 → 21.5 s, `RoundImageLockSpec` 0.6 → 0.6 s, `WritePathLockSpec` 1.9 → 2.9 s. None needs more time or an exclusion. `CategoryOperationInitializationSpec` (`Class.forName` on the category-operation objects) passes too: scoverage keeps class and member names.
- The slowest distribution specs grow most (`RoundDistributionJobSpec` 36 → 44 s, `RoundRedistributionFiltersSpec` 32 → 41 s).
- **Anomaly, not caused by coverage:** in the uninstrumented run, one statement of `CoiRoundSpec` (`UPDATE round SET active_before_freeze = …`, the freeze release) waited 1565 s (26 min) before completing; the spec then passed. Instrumented, the same spec took 6 s. Unexplained, and not a row-lock wait: InnoDB's 50 s default would have failed it. Likely a metadata lock or a paused VM; not reproduced. Since PR [60] the test container runs with `--innodb-lock-wait-timeout=10` and `--lock-wait-timeout=60` (`SharedTestDb.forTests`), so a leaked row or metadata lock now fails the spec with an error that names it, instead of hanging. Excluded from the sums above. Worth a look if it recurs in CI.

**Baseline:** from the first CI run of PR [60], under "Validation of PR [60]" below.

**Thresholds:** none (`coverageFailOnMinimum := false`, no Jest `coverageThreshold`). The first numbers come from one run, and a minimum just below them would fail on unrelated changes (new untested code, a moved spec) before anyone has looked at the reports. Revisit after a few weeks of numbers: a minimum a couple of points below the stable baseline, per language, and only then a required check.

## Rollout

### User actions

- **U1. Repository settings → Actions → General → Workflow permissions.** In both repositories, switch to "Read repository contents and packages permissions", and leave "Allow GitHub Actions to create and approve pull requests" off.
  - The workflows declare their own `permissions`.
  - wlxjury's default is currently **write**, which any workflow without a `permissions` block would get.
- **U2. Same page → Fork pull request workflows.** Keep "Require approval for first-time contributors", the default for public repositories.
- **U3. Review and merge PR [55]** after the stack below it.
- **U4. scalawiki:** review and merge the publishing change (A3) into `page-categories`, and later into `master`.
- **U5. Tag the first release** in scalawiki: `git tag -a 0.7.0-M1 -m "0.7.0-M1" <sha>` and `git push origin refs/tags/0.7.0-M1`. The tag workflow publishes it.
- **U6. Check package access:** org → Packages → `scalawiki-core_2.13` → Package settings. Confirm the package is linked to `UkrainianWiki/scalawiki` and public. If a Manage Actions access section is offered, add `UkrainianWiki/wlxjury` with role Read, for each of the four packages.
- **U7. Locally:** a classic PAT with only `read:packages`, in `~/.sbt/1.0/github.sbt` (setup note above). This is needed only once wlxjury defaults to the published version.
- **U8. Optional:** branch protection on `master` requiring the `CI / sbt test (mariadb:10.6.22)` check, once it has been green for a week.

### Agent actions

- **A1.** Done: PR [55] on top of the stack (approved decision).
- **A2.** Watch the first runs. Fix runner-only failures: Docker API version, tmpfs, encoding, timing-sensitive specs. Re-measure the times in this plan.
- **A3.** In scalawiki, a branch from `page-categories` with `publishing.diff` and `publish.yml`. Remove `appveyor.yml` there; optionally remove the dead Bintray and JCenter resolvers from `Dependencies.scala`.
  - Verify locally with `sbt dynverAssertTagVersion` (it should fail on an untagged commit), `show scalawiki-core/version` and `publishLocal`, run in a scratch clone so `~/.ivy2/local` keeps the developers' snapshot.
- **A4.** After U5: test the cross-repository read with a throwaway `workflow_dispatch` workflow in wlxjury. With `permissions: packages: read` it should run `curl -fsSI -u "x:$GITHUB_TOKEN" https://maven.pkg.github.com/UkrainianWiki/scalawiki/org/scalawiki/scalawiki-core_2.13/0.7.0-M1/scalawiki-core_2.13-0.7.0-M1.pom`. Record the result here.
- **A5.** If A4 passes:
  - Apply `consume-scalawiki-package.diff`, add `packages: read` to the three workflows, and remove the scalawiki action and `SCALAWIKI_SHA`.
  - Update DEV.md (setup note) and CLAUDE.md (the scalawiki line under "Wikimedia Commons Integration").
- **A6.** If A4 fails: keep from-source in CI, replacing the pinned SHA with the tag `0.7.0-M1`. Developers still use the package with their PAT.

### Order

1. U1, U2.
2. A1 and A2: CI with from-source scalawiki, PR [55].
3. U3. Then a follow-up PR that deletes `appveyor.yml` (done in the Java 17 batch, 2026-11-04).
4. A3, U4, U5: publishing.
5. U6, A4.
6. A5 or A6.
7. U8.

## Risks

- **Runner-only failures:** Docker Engine version against Testcontainers 1.21.4, tmpfs size, timing assumptions in specs on 4 vCPUs, file ordering on ext4 against NTFS. Mitigation: a pinned runner image, uploaded logs, and fixing them in A2 before branch protection.
- **sbt heap:** CLAUDE.md's `-Xmx512m` for sbt works for incremental local compiles, but a cold `Test/compile` (242 test sources) ran out of heap at 512m locally. CI, which never has incremental state, gives sbt `-J-Xmx2g`; the runner has 16 GB. The forked test JVM keeps `-Xmx768m`.
- **The scalawiki commit drifts from what developers have in `~/.ivy2/local`:** CI uses the pinned SHA while a developer may have a newer local snapshot. The package ends this.
- **Fork PRs:** they can't use secrets. Packages are reachable only if A4 passes; from-source always works.
- **Cross-repository package read not supported** (medium-low confidence that it is): the fallback is A6.
- **20-job concurrency** when the stack opens: a queue, not a failure (see the runtime estimate).
- **Resolver order and dependency confusion:**
  - Central comes before GitHub Packages. Someone could publish `org.scalawiki` on Central only by proving control of `scalawiki.org`.
  - The exact pinned version limits the exposure further.
- **Immutable versions:** a bad `0.7.0-M1` can't be replaced. Publish an `-M2`.
- **Live Commons specs:** they fail on Commons outages or content changes. They are weekly and not required, and should not be made required.
- **Scheduled workflows** stop after 60 days without activity, without notice beyond an email.

## Open questions

1. **Which commit gets the first tag?** *Superseded 2026-11-04:* on top of `java17` and the publishing branch (see "First release" above). The earlier recommendation: `0.7.0-M1` on `25951dbe` (page-categories head, the code wlxjury runs against now). Then merge page-categories into scalawiki `master` and cut later releases from `master`. A tag doesn't need its commit on `master`. Until then, CI depends on `25951dbe` staying reachable on GitHub (fetched by SHA). Tag it (`0.7.0-M1`, or a plain `wlxjury-ci-pin` tag), so a force-push or branch deletion in scalawiki can't break wlxjury's CI.
2. ~~Should the CI file go at the bottom of the stack?~~ **Decided:** on top, as PR [55]. #184–#237 get no CI.
3. **Rerun PRs when their base changes (`pull_request` type `edited`)?** *Recommendation:* no. The rebase push after a lower PR merges reruns it anyway, and `edited` also fires on title and description edits.
4. ~~MariaDB 10.11 in CI?~~ **Decided:** only on pushes to master and weekly, until the move to 24.04 makes it the default.
5. ~~Publish snapshots?~~ **Decided:** no, only tags.
6. **JDK for scalawiki's publish: 11, like its CI, or 17, like wlxjury?** *Recommendation:* 11, matching the tested CI. The bytecode targets 8 either way. Move both together later.
7. ~~Remove the dead resolvers?~~ **Decided:** yes. wlxjury's are removed in [55]; scalawiki's go in its own PR (A3).
8. ~~Keep `appveyor.yml`?~~ **Decided:** delete it once Actions is green, in a follow-up PR (scalawiki's in A3). wlxjury's was deleted in the Java 17 batch (2026-11-04).

## Validation of PR [55]

- **scalawiki build, before implementation:** the local compile wasn't run (the shared sbt lock was taken). scalawiki's own CI passed `sbt -v +test` on JDK 11 on the pinned commit `25951dbe` (run 40713977251, 2m15s), and `~/.ivy2/local` holds a 0.7.0-SNAPSHOT built with JDK 17.
- **actionlint 1.7.7** (Docker image `rhysd/actionlint:1.7.7`, with its bundled shellcheck): 0 errors in `ci.yml`, `deployment.yml` and `integration.yml`, and in the scalawiki `publish.yml` draft.
- **Compile with the resolver change:** `Test/compile` passed locally (142 s; 209 main and 242 test sources, cold test compile) with sbt at `-Xmx2g`. The same compile at CLAUDE.md's `-Xmx512m` resolved all dependencies and compiled the main sources without the removed resolvers, then ran out of heap on the test sources. That is why CI gives sbt 2 GB.
- **CI run 1** (`fff0e473`, 2026-11-03 10:13 UTC):
  - **CI** [run 41617381909](https://github.com/UkrainianWiki/wlxjury/actions/runs/41617381909), `sbt test (mariadb:10.6.22)`: **success in 7m19s.**
    - Cold caches: no sbt or Coursier cache yet, so scalawiki was built from source.
    - `Test/compile` took 92 s, the suite 205 s.
    - Scala: 1771 total, 0 failed, 0 errors, 10 skipped, 1 pending. Jest: 20 suites, 253 tests passed.
    - Artifact `test-reports-0-attempt-1` (2.1 MB: 384 JUnit XML files and the check log).
    - Much faster than the 20–28 min estimate: tmpfs and the 4-vCPU runner suit the suite.
  - **Deployment tests** [run 41617381787](https://github.com/UkrainianWiki/wlxjury/actions/runs/41617381787):
    - `Restore and release-script tests on the packaged release`: **success in 4m26s** (`sbt dist`, image build, and both test modules).
    - `Python unit tests and shellcheck`: **failed in 11 s**. `test_adoption_accepts_the_shipped_application_conf` took the shipped `wlxjury.legacyPasswords.{retire,firstCheck,checkEvery}` (S6) for literal credentials, because the setting path contains "password".
    - This failure was already on the stack, not caused by CI.
    - Fixed in [55] at first with an exact-path allowlist (`NONSECRET_SETTINGS`) in `wlxjury-deploy.py`. That allowlist is superseded: followups' deploy adoption-scanner fix (`75618f04`, stack PR 58) names a credential by its leaf key only (`credential_name()`), so the schedule settings pass without any allowlist. On followups (`ukwikidev/followups-ci`) and from stack PR 58 on, `NONSECRET_SETTINGS` is gone, and the test is `test_adoption_refuses_a_credential_leaf_under_the_legacy_password_schedule`: `retire` passes, a `token` leaf under `wlxjury.legacyPasswords` is still refused. Locally, all 61 Python tests pass on python:3.8-slim.
- **CI run 2** (`c3f8cdc1`: anonymous tests and the adoption fix, 2026-11-03 10:55 UTC):
  - **CI** [run 41623216087](https://github.com/UkrainianWiki/wlxjury/actions/runs/41623216087): **success in 5m08s**, with the sbt cache and the scalawiki cache restored.
    - `Test/compile` took 65 s, the suite 180 s.
    - Scala: 1776 total (`TestConfigAnonymousSpec` and the new `CommonsLoginSpec` example included), 0 failed, 10 skipped, 1 pending. Jest: 253 passed.
    - Artifact `test-reports-0-attempt-1` (2.1 MB).
  - **Deployment tests** [run 41623216062](https://github.com/UkrainianWiki/wlxjury/actions/runs/41623216062): both jobs **succeeded**. Python and shellcheck took 12 s; restore and release script took 4m22s. Artifact `deployment-logs-…` (5 KB).
- **CI run 3** (`4bbca2ec`: the review fixes, 2026-11-03 12:09 UTC):
  - **CI** [run 41633371583](https://github.com/UkrainianWiki/wlxjury/actions/runs/41633371583): **success in 6m17s**.
    - Scala: 1780 total (`AnonymousBotsSpec` included), 0 failed, 10 skipped, 1 pending. Jest: 253 passed.
    - `Test/compile` took 80 s, the suite 193 s.
  - **Deployment tests** [run 41633371700](https://github.com/UkrainianWiki/wlxjury/actions/runs/41633371700), now on every PR: **success**.
    - Python: 61 tests on `python:3.8.20-slim` and shellcheck v0.11.0, 11 s.
    - Release job: 4m57s. The packaged-release test found the ZIP by glob and passed, and all 24 restore and release-script tests passed.
- **Measured runtime:**
  - A CI run takes about 5–7 min, against the 20–28 min estimated above, and the deployment job about 4.5 min.
  - With about 3 runs per PR, a PR costs about 20 runner-minutes, and a month after the series about 300–500 min.
  - The queue at the 20-job limit is correspondingly shorter.
- **Locally:**
  - `TestConfigAnonymousSpec` and `CommonsLoginSpec` passed (28 of 28) with the credential variables unset.
  - `TestConfigAnonymousSpec` passed (4 of 4) with dummy `COMMONS_USER` / `COMMONS_PASSWORD` set in the JVM's environment.
  - Earlier, all of `services.imports.*`, `AppendImagesSpec` and `ImagesFromListSpec` passed.
  - Every run used `env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD`, or the dummy values.

## Validation of PR [60]

- **actionlint 1.7.7** (`rhysd/actionlint:1.7.7`): 0 errors in all four workflows.
- **Run 1** (`fcced9ac`): Coverage failed in the scalawiki build (Maven Central answered 404 for sbt 1.10.6's launcher POM once, a transient error; CI's scalawiki step passed at the same time). CI failed in `TestConfigAnonymousSpec`, which flagged the job's `env -u COMMONS_USER …` line; fixed in `ce9e6cef` (above). The log step no longer fails when sbt never ran (`3c598fdf`). Deployment tests passed.
- **Run 2** (`3c598fdf`, 2026-11-03 13:51 UTC), all green:
  - **Coverage** [run 41648056201](https://github.com/UkrainianWiki/wlxjury/actions/runs/41648056201): **6m48s** job, scalawiki from the cache. `coverage Test/compile` 96 s, `test` 258 s, `coverageReport` 3 s. Scala: 1902 total, 0 failed, 10 skipped, 1 pending; Jest: 20 suites, 253 passed. Artifact `coverage-reports-attempt-1`, about 40 MB unpacked (25 MB Scala reports, 15 MB JUnit XML, 0.4 MB Jest).
  - **CI** [run 41648056177](https://github.com/UkrainianWiki/wlxjury/actions/runs/41648056177), same commit: 5m23s job, `Test/compile` 63 s, `test` 207 s. So instrumentation costs about **+25% on the suite** (258 s against 207 s), +33 s of compile, and **about 1.5 min on the job**. That is far under the 15 minutes that would have moved coverage off PRs.
  - **Deployment tests** [run 41648056167](https://github.com/UkrainianWiki/wlxjury/actions/runs/41648056167): both jobs passed; `sbt dist` is unaffected by the packaging guard when coverage is off.
- **Run 3** (`4a861818`, docs only): Coverage [run 41649422116](https://github.com/UkrainianWiki/wlxjury/actions/runs/41649422116), CI and Deployment tests all green.
- **Baseline (run 41649422116, `4a861818`):**

  | | Statements | Branches | Lines | Functions |
  |---|---|---|---|---|
  | Scala (scoverage) | **89.87%** (16,926 / 18,834) | **85.84%** | | |
  | JS (Jest, V8) | **96.76%** (1,465 / 1,514) | **91.39%** (446 / 488) | **96.76%** (1,465 / 1,514) | **93.81%** (91 / 97) |

  - Scala by package (statements): `controllers` 90.5% (6,427), `db.scalikejdbc` 93.4%, `services` 88.5%, `services.distribution` 93.1%, `services.imports` 88.9%, `services.jobs` 85.9%, `services.oauth` 91.0%, `org.intracer.wmua.cmd` 77.8%, `modules` 62.9% (Guice wiring and startup), `api` 89.0%.
  - No generated file is in the report: no `target/` source, no `router` package, no template (the `views` package is the hand-written helpers in `app/views/`).
  - V8 counts JS statements as lines, so the two JS figures are equal by construction.
  - Run to run, Scala moves a little: run 2 had 89.89% / 85.94% (16,930 statements), 4 more in `services` and `services.jobs`, whose timing-dependent paths (scheduler, retries) don't run the same every time. Read differences of a tenth of a point as noise.
