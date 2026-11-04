# Dependencies and supply chain

What the jury tool depends on and how a dependency is added, pinned, updated and checked: Scala
libraries and sbt plugins, webjars and vendored JavaScript, npm packages for the tests, GitHub
Actions, container images and downloaded tools. Each rule has a short why and an example. The
security rule (no unused dependency, a new one reviewed) is
[secure-coding.md](../security/secure-coding.md) §19; scalawiki's pin and package are
[integrations](../integrations/practices.md) §2. **Sources:** secure-coding §19 and guard G11;
the [CI plan](../plans/2026-11-03-ci-and-scalawiki-packages.md) (pinned runner, actions and
images, the sbt-scoverage choice); `build.sbt`, `project/plugins.sbt`,
`javascript-test/package.json`; the PR series' base notes (`origin/stack/series-doc`).

## Contents

1. [Choosing a version](#1-choosing-a-version)
2. [Pinning](#2-pinning)
3. [Pekko and scalawiki](#3-pekko-and-scalawiki)
4. [Downloaded tools and checksums](#4-downloaded-tools-and-checksums)
5. [Updates](#5-updates)
6. [Licenses](#6-licenses)
7. [The vendored gallery](#7-the-vendored-gallery)
8. [Guards](#8-guards)
9. [Where the build doesn't follow these yet (for triage)](#9-where-the-build-doesnt-follow-these-yet-for-triage)

## 1. Choosing a version

**1.1 At least two weeks old.** A new dependency or a new version is one released at least 14 days
before it is merged: a library, a plugin, a webjar, an npm package, an action or an image. Check the
release date on the registry (Maven Central, npm, the action's tag, the image's tag), not the
date it was announced. One exception (the user, 2026-11-03): a security fix that can't wait may be
newer, if the PR says why and links the advisory; pin its exact version and read its changelog.
It covers base images too (the user, 2026-11-04): a container's `FROM` may move to a digest
younger than two weeks only when that digest carries nothing but security fixes of the OS or the
JRE. The PR links each advisory it fixes (the Ubuntu USN, the JDK's CVE or the image's release
note), pins the new digest, and says why the fix can't wait; a base update with anything else in
it waits the two weeks. *Why:* a compromised or broken release is usually found and pulled within
days; waiting costs little. For a base image's JRE, though, it would cost more than on a host
whose package updates patch its JRE.

**1.2 Compatible with the build, checked at the source.** Check the version against the build's
Scala, sbt and JDK before choosing it, and read its sources in `target/dep-sources/`
(`sbt extractDepSources`) before the web. *Example:* scoverage's compiler plugin is published
per full Scala version, so sbt-scoverage moves with `scalaVersion`: 2.3.1 while the build was on
2.13.15 (2.4.x starts at 2.13.16; 2.1.0 couldn't resolve at all, CI plan, "Test coverage"), 2.4.4
with 2.13.17 (dependency-updates batch, 2026-11-04).

**1.2b A version that miscompiles is skipped, with the reason next to the pin.** Scala is
**2.13.17, not 2.13.18** (2026-11-04): 2.13.18 miscompiles a single `Int` literal passed to a
`Long*` parameter of a method with a further (implicit, default) parameter list. The varargs
`List` then holds a boxed `Integer`, and unboxing it throws `ClassCastException` at run time;
2.13.15, 2.13.16 and 2.13.17 widen it. Found by `DistributeImagesBatchSpec` through
`TestDb.createContests(10)`; nothing in `app/` has such a signature (`rg "\w+\*\)\s*\("`). The
repro, compiled with each version's `scala.tools.nsc.Main` and run:

```scala
trait S; object AutoS extends S
object R {
  def create(ids: Long*)(implicit s: S = AutoS): Seq[Long] = ids.map(_ + 1)
  def main(a: Array[String]): Unit = println(create(10)) // 2.13.18: ClassCastException
}
```

`create(10, 20)`, `create(10L)` and a method without the second list are fine. No scala/bug issue
matched (searched read-only on 2026-11-04: varargs, `Long`, widening, `unboxToLong`, the
2.13.18 regressions); reporting it upstream is open. Move to a 2.13.x that fixes it once that
release is two weeks old. Play 3.0.12 and webjars-play 3.0.10 declare scala-library 2.13.18;
its class list differs from 2.13.17's by one compile-time annotation
(`scala.annotation.unchecked.uncheckedOverride`).

**1.2a Java 17 is the baseline** (the user, 2026-11-04). The build compiles with `-release:17`
(scalac) and `--release 17` (javac); CI and the deployment test images run JDK 17, and production
runs OpenJDK 17 ([deployment.md](../deployment.md), "Java 17 is required"). An update that needs
Java 17 is fine. One that needs a newer JDK (class files above version 61, or JDK 21 APIs) waits
until the baseline moves. Java 11 is not supported: don't hold a version back, or add a pin, for
it. `javap -v` on a class from the jar shows its level (`major version: 61` is Java 17). *Why:*
many updates now need 17 (Flyway 10 and later already did: `FlywayVersion` 13.12.0 is version 61),
and production has run 17 since its Ubuntu 22.04 install.

**1.3 Used, maintained, reviewed.** A new dependency is used by the code that adds it, has recent
releases and a responsive upstream, and is named in the PR's "Security" section
(`dependencies`); an unused one is removed (S10 removed `play-pac4j`, comparing `sbt dist`'s
classpath before and after). Prefer a library already on the classpath, or a few lines of own
code, to a new one.

## 2. Pinning

**2.1 Exact versions everywhere.** sbt dependencies and plugins at exact versions (no `latest`,
no ranges, no `-SNAPSHOT` but scalawiki's pinned build); webjars at exact versions (`jquery`
3.7.1, `bootstrap` 5.3.8, `tom-select` 2.6.2); Jest's tools through `javascript-test/package-lock.json`
and `npm ci`; the browser tests' `@playwright/test` 1.64.1 (released 2026-10-14) and
`@axe-core/playwright` 4.14.0 (2026-10-15, with `axe-core` 4.14.0) at exact versions in
`browser-test/package.json` and its lockfile, the browsers being the builds that Playwright
version names (Chromium 156, Firefox 157, WebKit 27.2; CI caches them by the lockfile's hash);
the browser tests' report tools `allure` 3.21.0 (Allure Report 3, released 2026-10-16) and
`allure-playwright` 3.14.0 (2026-10-19) and `allure-js-commons` 3.14.0 (the fixture's import, a
direct dependency) at exact versions in the same files, their ~160 transitive packages locked
with `npm install --before` 14 days back (the newest `allure`, 3.22.x, was too new on 2026-11-04;
one update PR per bump, given its release rate). Test tools only, never served or packaged. The
report uses Allure 3's **allure2 plugin, not its default Awesome one** (the user, 2026-11-04):
Awesome hard-codes Google Analytics with no opt-out (upstream allure3#476 and PR #784 open),
allure2 honours `ALLURE_NO_ANALYTICS`; `browser-test/allure-offline.mjs` then fails the report on
any analytics loader or other host and adds a CSP (no CDN: checked with the network blocked,
testing §10.10). **Recheck on every Allure update;**
the compatibility lint's `eslint` 10.13.0, `eslint-plugin-compat` 7.0.2, `globals` 17.13.0,
`stylelint` 17.16.0, `stylelint-no-unsupported-browser-features` 8.1.2, `browserslist` 4.29.4 and
`caniuse-lite` 1.0.30001817 at exact versions in `javascript-test/package.json` (all released 14
days or more before 2026-11-04; `caniuse-lite` is the data that `.browserslistrc`'s "last 2" is
read from, so updating it moves the range); Python tools at exact versions where installed
(`playwright==1.48.0`). *Why:* the same commit must build the same thing next month.

**2.2 Actions by commit SHA.** A workflow `uses:` a third-party action by its full commit SHA, with
the tag as a comment: `sbt/setup-sbt@ecad189dd245444b9ae0a0cd5922fd938b88906f # v1.5.12`.
*Why:* a tag can be moved to other code; a SHA can't. First-party `actions/*` follow the same
rule (§9 T1).

**2.3 Images by exact tag.** `mariadb:10.6.22`, `mariadb:10.11.14`, `python:3.8.20-slim`,
`koalaman/shellcheck:v0.11.0` (and `rhysd/actionlint:1.7.7` when run by hand); Testcontainers' image through
`SharedTestDb.Image`; runners `ubuntu-24.04`, not `ubuntu-latest` (Testcontainers' Docker API
compatibility depends on the engine version, so a runner move is deliberate). Never `latest` or
`stable`. *Why:* a moving tag changes the test server under an unchanged commit.

**2.4 Bases pinned too.** The bench server and `Dockerfile.test` name their Ubuntu release and
production's MariaDB build (performance §2.6).

## 3. Pekko and scalawiki

**3.1 Pekko follows scalawiki and Play.** Pekko HTTP comes with scalawiki, whose versions are in its
`project/Dependencies.scala` (`Library.Pekko`: `PekkoV`, `PekkoHttpV`), and Play itself is built on
Pekko (actor, stream). A Pekko version must suit both: bump scalawiki first, check that the Play
line in use accepts that Pekko, then `PekkoVersion` (1.0.3) and `PekkoHttpVersion` (1.0.1) in
`build.sbt`, then rerun the image cache specs (`LocalImageCacheService*Spec`, which use Pekko HTTP's
client). Pekko 2.0 waits for a Play release that supports it (none known yet; user, 2026-11-04).
*Why:* two Pekko versions on one classpath fail at run time, not at compile time (secure-coding §19;
the `build.sbt` comment).

**3.3 The next major lines** (user, 2026-11-04): Play 3.1 is not released yet; Pekko 2.0 comes with
a Play release that supports it (3.1); Scala 3 is not planned, and may come later together with
scalawiki's own improvements; sbt 2 is worth investigating for development speed (roadmap, "sbt 2
investigation").

**3.2 scalawiki is pinned by commit** until a tagged package exists (integrations §2.1, §2.4).

**3.4 What wlxjury excludes from scalawiki, until scalawiki changes.** `build.sbt` excludes
`ScalawikiPlayJson2` from scalawiki-core and -wlx: these are its `com.typesafe.play` play-json
and play-functional 2.9.4, the same classes as Play's `org.playframework` 3.0.x and loaded first.
It also excludes `ScalawikiCharts` from -wlx: scala-chart, scala-swing, jfreechart and jcommon,
which only its stat-bot reports use. `SpecialNominationFilterSpec` covers the `ContestStat` path
next to them. Both exclusions go once the scalawiki PR (play-json 3, a `scalawiki-stat` module)
is published; that PR's plan is in the roadmap's dependency-updates entry. The same commit
excludes `commons-logging` (jcl-over-slf4j provides it) and jaxb-api's `javax.activation-api`
(jakarta.activation 1.2.1, from play-mailer 10, carries it). *Why:* two copies of the same class
on the classpath load whichever comes first, unannounced, because the group IDs differ.

## 4. Downloaded tools and checksums

**4.1 Verify what is downloaded.** A tool fetched by a script or workflow outside a package manager
(a binary, an archive, an installer) is checked against a SHA-256 recorded next to the URL, and the
step fails on a mismatch. A git fetch by full commit SHA (the scalawiki action) counts as verified.
*Why:* a download URL serves whatever is there today.

**4.2 Prefer a pinned image or action** to a download in a `run:` step.

## 5. Updates

**5.1 One update, one PR.** An update PR changes one library or one coherent group (Play and its
plugins; Pekko with scalawiki), on top of the stack, with: the release notes read (breaking
changes, migrations), the two-week rule (§1.1), `Test/compile` and the affected specs, and the
classpath diff for a framework update (`sbt dist`'s `lib/`). A major update (Play, Scala,
Bootstrap) gets its own plan.

**5.2 The old scala-steward PRs.** `app/scala-steward-intracer` opened update PRs against the old
`master` (6 open, e.g. #169 jQuery 3.7.1, #159 Bootstrap 3.4.1, #168 sbt-scoverage 2.0.9). Most are
superseded by the stack (Bootstrap 5.3.8, jQuery 3.7.1, sbt-scoverage 2.3.1): close each with a
comment naming the stack PR that superseded it; redo any still useful as an update PR (§5.1). Turning
an update bot on again is a later decision; until then G11 is the report.

**5.3 A report at the release gate.** Before a release, a dependency update and vulnerability
report (sbt and npm) is read and its findings triaged (secure-coding §19, G11: a report, not a
failure; no plugin yet).

## 6. Licenses

**6.1 Compatible with Apache 2.0.** The project is Apache-2.0 (`LICENSE`). A new dependency's
license is permissive and compatible (Apache-2.0, MIT, BSD); a copyleft
library that is served to browsers or linked into the app needs the user's decision first. Name
the license in the PR. *Example:* `axe-core` and `@axe-core/playwright` are MPL-2.0 (a
file-level copyleft): test tools only, injected into the test browsers by the browser tests,
never served by the app or packaged with it, so no decision was needed; Playwright is Apache-2.0.
The compatibility lint's tools are MIT; `caniuse-lite`'s data is CC-BY-4.0 (attribution; test
tooling only, not shipped). `allure`, `allure-playwright` and `allure-js-commons` are Apache-2.0;
their transitive packages are MIT, Apache-2.0, ISC, BSD, BlueOak-1.0.0 (`glob`, `minimatch` …),
PSF-2.0 (`argparse`) and `dompurify`'s MPL-2.0 or Apache-2.0: all permissive or with a permissive
choice, and test tooling only.

**6.2 Vendored files keep their notice.** A copied file keeps its upstream copyright and license
header (`jquery.raty.js`: MIT; `jquery.hotkeys.js`: dual MIT or GPL v2); a file without
one (`unitegallery.js`, upstream MIT) gets its license recorded in the gallery inventory (§7).

## 7. The vendored gallery

`unitegallery.js` (Unite Gallery 1.7.28, 2016), `ug-theme-tiles.js`, `jquery.raty.js` 2.7.1 and
`jquery.hotkeys.js` are copied into `public/javascripts/`, and the gallery is patched for wlxjury
(`wlxjury:` comments; [UI](../ui/practices.md) §7). Upstream looks unmaintained. **Keep the patched
fork or replace the gallery: a decision for later,** after the queued patch inventory and its
tests (roadmap, "the patched gallery"). Until then: no new patch without a `wlxjury:` comment and a
Jest test, and prefer replacing to patching more of it (secure-coding §8).

## 8. Guards

| Rule | Enforced by | State |
|---|---|---|
| 1.1 at least two weeks old | review | planned: a check of new versions' release dates in update PRs |
| 1.3 no unused dependency | review; `sbt dist` classpath diff | review only |
| 2.1 npm lockfile | `npm ci` in `jsTest` and in `browser.yml` (fails on a lockfile mismatch) | enforced |
| 2.2 actions by SHA | review | planned: a workflow scan for `uses:` without a 40-hex SHA |
| 2.3 images by exact tag | review | planned: the same scan for `latest`/`stable` tags in workflows and scripts |
| 3.1 Pekko in step | `build.sbt` comment; image cache specs | review only |
| 4.1 checksums | review | planned (no downloaded tool today) |
| 5.3 update and vulnerability report | G11 | planned (no plugin yet) |
| 6.1 licenses | review | planned: a license report with the G11 report |

## 9. Where the build doesn't follow these yet (for triage)

- **T1 (2.2):** every workflow but `browser.yml` uses `actions/checkout@v4`,
  `actions/setup-java@v4`, `actions/setup-node@v4`, `actions/upload-artifact@v4` and (in
  `.github/actions/scalawiki`) `actions/cache@v4` by tag; only `sbt/setup-sbt` is pinned by SHA
  there. `browser.yml` pins every action by SHA (done 2026-11-04).
- **T2 (2.3):** `docs/deployment.md` "Local verification" runs `python:3.8-slim` and
  `koalaman/shellcheck:stable`, while CI runs `python:3.8.20-slim` and `v0.11.0`.
- **T3 (2.1):** `scripts/ui-shots.py` installs `"pillow<11"`, a range.
- **T4 (6.2):** `unitegallery.js` and `ug-theme-tiles.js` carry no license header.
- **T5 (1.3):** Node 20 reached its end of life in April 2026, and `eslint-plugin-compat` 7.0.2
  declares Node >= 22. `ci.yml` (whose job runs the lint) and `browser.yml` run Node 22.23.4
  (2026-10-20, `setup-node` pinned by SHA); `coverage.yml` still runs Node 20 (Jest only), to
  move with the next update.
