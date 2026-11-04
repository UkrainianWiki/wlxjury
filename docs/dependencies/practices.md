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
newer, if the PR says why and links the advisory; pin its exact version and read its changelog. *Why:*
a compromised or broken release is usually found and pulled within days; waiting costs little.

**1.2 Compatible with the build, checked at the source.** Check the version against the build's
Scala, sbt and JDK before choosing it, and read its sources in `target/dep-sources/`
(`sbt extractDepSources`) before the web. *Example:* sbt-scoverage 2.3.1, not 2.4.x: scoverage's
compiler plugin is published per full Scala version, and 2.4.x starts at Scala 2.13.16 (this build
is 2.13.15); 2.1.0 couldn't resolve at all (CI plan, "Test coverage").

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
tooling only, not shipped).

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
