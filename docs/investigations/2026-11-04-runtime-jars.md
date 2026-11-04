# wlxjury runtime jars and the Docker base image (2026-11-04)

Investigation results, 2026-11-04. The steps are queued (user, 2026-11-04): §6 steps 1–3 open
the dependency-updates batch, step 4 (the OpenAPI document at build time) comes after all other
tasks; see the roadmap.

**Tree.** `sbt stage` of the docker-compose batch (`fc0b16e3`, PR #256), which has the same
`libraryDependencies` and base image as the stack tip of the day.

**How the figures were made:**
- `sbt stage`, then `Runtime/dependencyTree`, `Runtime/dependencyList` and `evicted`, in one sbt run under the shared lock. The lock was released afterwards and `target/universal/stage` removed.
- A Python constant-pool scan of every class in `lib/`, with breadth-first reachability from wlxjury's classes in the start script's classpath order.
- `javap` API diffs of the two play-json jars.
- The production `lib/` listing (release 0.13).
- Docker Hub and gcr.io manifests for the base images.

Scripts and raw outputs are in the scratchpad: `deps-attr.txt`, `reach.txt`, `pjrefs.json`, `pkgmod.txt`, `prod-lib.txt`, and `deps/appendix.md`, which is the full per-jar table.

**Two corrections to the brief:**
- On this tree **the Tapir API is served**: `-> /api api.ApiRouter` in `conf/routes`, using tapir-play-server, with Swagger UI at `/api/docs`. The tree's CLAUDE.md says so too. The note in the main checkout's CLAUDE.md, "not served", is stale for this branch. So the Tapir, circe and cats stack is needed at run time as things stand.
- **Lombok and the Bootstrap 3 webjar are not on this tree.** Both are in production's 0.13 `lib/` only, and this branch already removed what brought them (see "User decisions").

---

## User decisions: steps

### 1. Lombok: already gone on this branch

- **What pulled it in (0.13):** `org.pac4j:pac4j-parent:6.0.0`'s pom lists `org.projectlombok:lombok` (1.18.30, 2.0 MB) as an ordinary compile-scope dependency of every pac4j module. play-pac4j 12 brought pac4j 6, which brought Lombok. pac4j-parent 5.x doesn't do this.
- **Fix:** none is needed. Commit `eb7f706b` ("S5/S10 … pac4j removed") dropped pac4j, and the staged `lib/` has no Lombok. The saving ships with this branch's release.
- **scalawiki:** clean.
  - Its published `ivy.xml` files (core, wlx and bots, `0.7.0-SNAPSHOT`) declare no Lombok.
  - `git grep -i lombok` at the pinned sha `25951dbe` matches only a photo title in a test JSON file ("Pantai Telawas Lombok.jpg").
  - Its only Java source is `scalawiki-bots/src/main/java/net/sf/uadetector/internal/util/AlphanumComparator.java`, with no annotations.
- **Guard (optional):** a build check that fails if `org.projectlombok` appears in `Runtime / dependencyClasspath`.

### 2. jfreechart, scala-swing and commons-math3: out of scalawiki-wlx (scalawiki PR plan) and excluded meanwhile

**Where each one comes from:**

| jar | size | brought by | used by |
|---|---:|---|---|
| `de.sciss:scala-chart_2.13:0.8.0` | 348 KB | scalawiki-wlx (`ScalaChartV`) | nothing reachable from wlxjury (0 of 214 classes) |
| `org.scala-lang.modules:scala-swing_2.13:3.0.0` | 721 KB | scala-chart | nothing reachable (0 of 436) |
| `org.jfree:jfreechart:1.0.19` | 1.53 MB | scala-chart | `wlx.stat.reports.Charts`, `AuthorsStat`, `MonumentsPicturedByRegion` |
| `org.jfree:jcommon:1.0.23` | 322 KB | jfreechart | the same |
| `org.apache.commons:commons-math3:3.6.1` | 2.16 MB | **not scalawiki: JMH** (see below) | nothing reachable (0 of 1301) |

**commons-math3** comes from `jmh-core`. The root project enables `JmhPlugin` (`build.sbt` around line 84), and sbt-jmh adds `jmh-core`, `jmh-generator-bytecode` and `jmh-generator-reflection` to **Compile** scope. So JMH ships in the release: 6 jars, 2.92 MB (jmh-core, its three generators, commons-math3 and jopt-simple). Production's 0.13 has them too.

**How the jfree classes are reachable:** only through `ContestStat.withAccounts` (a lambda calling `AuthorsStat$.accountOf`) and `AuthorsStat` itself. wlxjury reaches `ContestStat` from `SpecialNominationFilter`:

```
org.jfree.chart.JFreeChart <- wlx.stat.reports.AuthorsStat <- AuthorsStat$ <- wlx.stat.ContestStat <- org.intracer.wmua.cmd.SpecialNominationFilter
```

- `withAccounts` is called only from scalawiki's `Statistics` and `reports.Output`, the stat bot. wlxjury never calls it.
- The JVM resolves the `getstatic AuthorsStat$` lazily, and verifying it needs no class load because the types are the same.
- So wlxjury never loads a jfree class in practice. wlxjury's own code doesn't import jfree, swing, math3 or scalax.chart at all.

**The progress bar is different:** `me.tongfei:progressbar` and jline (0.47 MB) **are** on wlxjury's path: `MonumentQuery.parseWithProgress` → `Progress.bar` → `ProgressBar`. Don't exclude them. `Progress` falls back to log lines when there is no console, but the classes are still linked.

**scalawiki PR (plan):**
1. Add a module `scalawiki-stat`, or `scalawiki-charts`, depending on `scalawiki-wlx`. Move `org.scalawiki.wlx.stat.reports.{Charts, AuthorsStat, MonumentsPicturedByRegion, Output, ReportRunner, ReporterRegistry, …}` and `Statistics` into it. Move the `scala-chart` dependency there; `jfreechart` and `scala-swing` follow transitively.
2. Keep in `scalawiki-wlx`:
   - `ContestStat`, minus `withAccounts`. Move that to an extension or helper in the new module, or move `AuthorsStat.accountOf`, which is pure string logic, into `wlx.stat` so that `ContestStat` no longer references `reports`.
   - `SpecialNomination`;
   - `MonumentQuery` and `MonumentDB`;
   - `CountryParser`;
   - `CampaignList`;
   - the dto classes.
3. `scalawiki-bots` then depends on the new module. Its `AlphanumComparator.java` stays where it is.
4. Optional, in the same PR or the next: make `Progress` take its bar implementation from a pluggable or optional dependency, so that `progressbar` and jline can become `% Optional`. That saves another 0.47 MB in wlxjury.
5. Verify: scalawiki's own tests, then `publishLocal` and wlxjury's `Test/compile`. The `SpecialNomination` specs in scalawiki-wlx cover the moved boundary.

**wlxjury, until that lands** (safe now):

```scala
"org.scalawiki" %% "scalawiki-wlx" % ScalawikiVersion excludeAll (
  ExclusionRule("de.sciss"),                                   // scala-chart (+ scala-swing, jfreechart, jcommon)
  ExclusionRule("org.scala-lang.modules", "scala-swing_2.13"),
  ExclusionRule("org.jfree")
),
```

- **JMH**, in the root project: either move `JmhPlugin` to a small `bench` sub-project that `dependsOn(root % "compile->compile;test->test")`, or keep it and re-scope its dependencies:
  `libraryDependencies := libraryDependencies.value.map(m => if (m.organization == "org.openjdk.jmh") m % Test else m)`.
  This works because `Jmh / dependencyClasspath := (Test / dependencyClasspath).value` is already set.
- **Saving:** 1.10 MB (scala-chart and scala-swing) + 1.85 MB (jfree) + 2.92 MB (JMH) = **5.87 MB**.
- **Risk:** a `NoClassDefFoundError` if a future wlxjury change calls the scalawiki stat reports. The scalawiki split removes that risk.
- **Verify:**
  - `Runtime/dependencyList` shows no `jfree`, `sciss`, `scala-swing` or `jmh`.
  - `Test/compile`, plus `Jmh/compile` for the JMH re-scope.
  - Run the specs that touch the round filters (`DistributeImages` and `ImageWithRatingSeqFilter` users), and one manual round with a special nomination. There is no spec for `SpecialNominationFilter` today. Adding one that builds the filter for a nomination without cities, which needs no network, would cover the `ContestStat` path.

### 3. The Bootstrap 3 webjar: already gone on this branch

- **What declared it (0.13):** `com.adrianhurt:play-bootstrap_2.13:1.6.1-P28-B3` and `play-bootstrap-core`. Their `-B3` flavour depends on `org.webjars:bootstrap:3.3.7-1` (750 KB) and `jquery 3.2.1` (167 KB). Both were in production's `lib/`.
- **This branch:** commit `5fc320ba` ("Theme phase 2: Bootstrap 5 helpers, Bootstrap 5.3, Tom Select, templates") replaced play-bootstrap with the app's own `views.bs` helpers. The staged `lib/` has only `org.webjars:bootstrap:5.3.8` and `jquery 3.7.1`.
- **References:** `rg "bootstrap/3|3\.3\.7"` over `app/views`, `public/` and `conf/routes` finds no Bootstrap 3 path. `layout.scala.html` loads `bootstrap/5.3.8/css/bootstrap.min.css` and `bootstrap.bundle.min.js` through `routes.WebJars.at`. The only "play-bootstrap" mentions left are comments in `app/views/bs/*.scala` explaining where the helpers' behaviour came from.
- **Steps:** none for the jar. Optionally, a guard in `LayoutSpec`, or a build check, that no `webjars/bootstrap/3` URL is rendered.
- **Verify** (already part of that change): `views.LayoutSpec`, plus the Playwright browser tests in `browser-test/` (layout, forms, Tom Select).

### 4. The two play-json copies

**Which artifact brings each:**
- `org.playframework:play-json_2.13:3.0.4` (and `play-functional 3.0.4`) comes from Play 3.0.7, `tapir-json-play` and `play-filters-helpers`.
- `com.typesafe.play:play-json_2.13:2.9.4` (and `com.typesafe.play:play-functional 2.9.4`) comes **only from `scalawiki-core`**: its `ivy.xml` declares `play-json_2.13 rev 2.9.4`, and scalawiki-wlx inherits it.
- **Not from play-mailer.** `com.typesafe.play:play-mailer 9.0.0` depends only on `commons-email 1.5`, `config` and `slf4j`, plus `guice` for `-guice`. It brings no Play or play-json artifact.
- Because the group IDs differ, Coursier treats them as two unrelated modules, and no eviction warning is raised.

**Same classes?** Yes, the same package and class names:

| | 2.9.4 | 3.0.4 |
|---|---:|---:|
| `play-json` classes | 306 | 317 |
| shared with the other version | 306 | 306 |
| only in this version | 0 | 11 |
| `play-functional` classes (all shared) | 61 | 61 |

- The 11 classes only in 3.0.4 are the Scala 2 macro helpers (`JsMacros`, `JsMacrosWithOptions`, `JsValueMacros`), `ImmutableLinkedHashMap*`, `JsObjectBuilder`, `EnumerationWrites` and `LowPriorityKeyReads`.
- `javap` shows 111 public members that exist only in 3.0.4's versions of the shared classes:
  - `Reads`/`OWrites.tuple2..4`;
  - `KeyReads.{int,long,boolean,…}KeyReads`, `KeyReads.map`, `readableKeyReads`;
  - `KeyWrites.anyValKeyWrites`;
  - `Json.newBuilder`, `Json.unwrap`;
  - `JsObject.createFieldsMap`;
  - `OFormat#bimap`, among others.
- 44 members exist only in 2.9.4.

**Which wins:** **2.9.4.**
- The start script's `app_classpath` lists `com.typesafe.play.play-json_2.13-2.9.4.jar` at position 68 and `org.playframework.play-json_2.13-3.0.4.jar` at position 105. Native-packager orders the classpath by dependency order, and the scalawiki jars come early (25–26).
- So **every shared `play.api.libs.json.*` class loads from 2.9.4** for all code in the app: Play, Tapir and wlxjury, not only scalawiki. The 11 classes unique to 3.0.4 load from 3.0.4. The same goes for `play-functional`.
- The compile classpath has the same order, so wlxjury itself is effectively compiled against a 2.9.4 and 3.0.4 mix.

**Has it broken, or could it?**
- I extracted every `play/api/libs/(json|functional)` member referenced from the bytecode of all other runtime jars: wlxjury, Play, play-filters-helpers, tapir-json-play, scalawiki-core and scalawiki-wlx. That is 225 distinct members, constructors included.
- **All 225 resolve in 2.9.4**, so nothing throws `NoSuchMethodError` today.
- **All 225 also resolve in 3.0.4 alone**, so removing 2.9.4 is binary-safe for every caller, scalawiki included.
- The checker was sanity-checked: `Json$.newBuilder` is correctly reported absent in 2.9.4 and present in 3.0.4.
- **Latent risks:**
  1. Any new wlxjury or Tapir code using a 3.0-only API (`Json.newBuilder`, the tuple `Reads`, the numeric `KeyReads`) compiles but fails at run time with `NoSuchMethodError`. Under a different jar order it could also compile against 2.9.4 and miss them.
  2. Behaviour is 2.9.4's (number parsing limits, `JsObject` construction) rather than what Play 3.0.7 was tested with.
  3. 2.9.4 gets no more fixes.

**Fix.** It is in scalawiki, not play-mailer:
- **In wlxjury now** (safe; 0.96 MB):
  ```scala
  "org.scalawiki" %% "scalawiki-core" % ScalawikiVersion excludeAll (
    ExclusionRule("com.typesafe.play", "play-json_2.13"),
    ExclusionRule("com.typesafe.play", "play-functional_2.13")
  ),
  ```
  Put the same exclusions on `scalawiki-wlx`, which brings core transitively. 3.0.4 is then the only copy. The bytecode check above says scalawiki's call sites all exist in 3.0.4.
- **In scalawiki:** switch `project/Dependencies.scala` to `"org.playframework" %% "play-json" % "3.0.4"`, the same package, so no source change is expected. Republish. wlxjury then drops the exclusion.
- **Risk:** low. Semantic differences between 2.9 and 3.0 are small: 3.0.x is 2.10.x under the new group ID.
- **Verify:**
  - `Runtime/dependencyList` shows one play-json.
  - scalawiki-dependent specs: the import and Commons specs (`services.imports.*`, `ContestService`, `CountryParser` users), which parse Commons JSON through scalawiki's `org.scalawiki.json.Parser`.
  - `test/api/ApiSpec` (Tapir JSON).
  - The CSRF and error JSON specs (`CsrfErrorHandler`, `Secured.onUnAuthorizedJson`).
- **play-mailer** is a separate, smaller clean-up. `com.typesafe.play:play-mailer 9.0.0` is the Play 2.9-era artifact with `javax.mail 1.5.6` (2016) and `activation 1.1`. `org.playframework:play-mailer 10.x` is the Play 3 line. Moving to it doesn't affect play-json. It does remove the `javax.activation` duplicate (below). Check its Jakarta Mail API change, and `MailService` and its spec.

---

## 1. The current list

**Totals:**
- `lib/` has **209 jars, 128.4 MB**: wlxjury's own 22.96 MB (assets jar 19.0 MB, code 3.9 MB), scalawiki's own 3.74 MB, and **third-party 101.7 MB** (the ~102 MB Docker layer 1).
- Production 0.13 has 210 jars and 109 MB. This branch adds a larger assets jar (Bootstrap 5, icons, Tom Select and the `digest` copies), Nimbus and bcrypt. It loses pac4j, Lombok, play-bootstrap, Bootstrap 3 and `pekko-http-play-json`.

**By origin:**

| group | MB | jars | status |
|---|---:|---:|---|
| Play and its stack (Play 3.0.7, play-json 3, Netty, Guice, Guava, Jackson, Twirl, Logback, scala-library 5.8, scala-reflect 3.7) | 27.94 | 66 | needed |
| Pekko + Pekko HTTP (stream 5.1, http-core 4.6, actor 4.0, http 2.0, protobuf 1.7, parboiled, lz4, ssl-config) | 20.85 | 13 | needed: Play's runtime, and scalawiki's HTTP client (`MwBot`) |
| Tapir API, docs/Swagger part (cats-core 6.1, cats-kernel 3.6, shapeless 3.2, swagger-ui 3.1, circe, snakeyaml, apispec, enumeratum, quicklens) | 19.59 | 28 | needed while `/api/docs` is generated at run time (see 3c) |
| Tapir API core (tapir-core, server, play-server, json-play, sttp-model/shared, magnolia) | 2.99 | 9 | needed (the API is served) |
| webjars (Bootstrap 5.3.8 2.5, bootstrap-icons 1.7, Tom Select 1.1, jQuery, requirejs, the locators, classgraph, mustache, commons-text) | 6.77 | 11 | needed (Bootstrap, icons, Tom Select, jQuery); the locator chain is webjars-play's |
| DB (MariaDB, Flyway 13.12 core and mysql, ScalikeJDBC 4.3.2, HikariCP, dbcp2, pool2, joda) | 5.83 | 20 | needed; dbcp2 and pool2 come from scalikejdbc-core but the pool is Hikari (`PlayDBApiAdapterModule`). **Since removed** (2026-11-04, dependency updates part 2, step 1): dbcp2, pool2 and dbcp2's jakarta.transaction-api are excluded; joda stays (dependencies practices §3.4) |
| scalawiki: other (jsoup, scallop, ficus, chronoscala, retry, odelay, scala-csv 2.0, commons-codec/io/lang3, pekko-http-caching, caffeine) | 3.76 | 12 | needed: scallop is reachable (`ContestController` → `RateConfig` → `StatParams`) |
| scalawiki own jars (core 1.2, wlx 2.4, http-extensions) | 3.74 | 3 | needed |
| scalawiki: charts (scala-chart, jfreechart, jcommon, scala-swing) | 2.99 | 4 | **unused** (decision 2) |
| JMH (jmh-core, generators, commons-math3, jopt-simple) | 2.92 | 6 | **build tool in Compile scope** (decision 2) |
| scalawiki: wikitext parser (sweble swc-engine and parser, ptk, rats-runtime, jaxb-impl 2.2.5, commons-collections 3.2.2) | 2.87 | 7 | needed (`TemplateParser` from `services.imports.ImageText`) |
| auth (Nimbus OAuth/OIDC and JOSE, json-smart, asm, bcrypt) | 2.16 | 10 | needed |
| scalawiki: play-json 2.9.4 + play-functional 2.9.4 | 0.96 | 2 | **duplicate** (decision 4) |
| mail (play-mailer 9 and -guice, commons-email, javax.mail 1.5.6, activation 1.1) | 0.80 | 5 | needed; old artifact |
| image handling (TwelveMonkeys imageio-webp, core, metadata, common-*) | 0.59 | 6 | needed (WebP thumbnails) |
| scalawiki: progress bar (progressbar, jline-terminal, jline-native) | 0.49 | 3 | linked from `MonumentQuery`; keep |
| JAXB API (direct `jaxb-api 2.3.1`, `javax.activation-api 1.2.0`) | 0.18 | 2 | the direct declaration is redundant: no `javax.xml.bind` in `app/`, and scalawiki-core brings it anyway |

The full per-jar table (size, parent, and which top-level dependencies bring it) is in the appendix at the end.

## 2. Classification

**Needed at run time:** Play, Netty and Guice; Pekko and Pekko HTTP; the Tapir API with Swagger; DB; auth; mail; the webjars; TwelveMonkeys; scalawiki core and wlx with sweble, jsoup, scallop, ficus, retry, chronoscala and the progress bar.

**Present but unused at run time:** checked by grepping `app/` and by constant-pool reachability from wlxjury's classes (an over-approximation).

| jar | size | reachable |
|---|---:|---|
| scala-chart | 348 KB | 0 classes |
| scala-swing | 721 KB | 0 classes |
| jfreechart + jcommon | 1.85 MB | only via the stat-bot code that wlxjury never runs (decision 2) |
| commons-collections 3.2.2 | 574 KB | 0 classes; sweble declares it, but nothing reachable uses it |
| commons-logging 1.2 | 60 KB | 0 classes; also shadowed by `jcl-over-slf4j` (5 identical class names, jcl-over-slf4j earlier on the classpath) |

**Compile- or build-time only, wrongly in runtime scope:**
- JMH: 2.92 MB.
- Lombok was the 0.13 case (pac4j 6); it's gone.
- `scala-reflect` (3.7 MB) is needed only by macros, but Play and play-json declare it at run time. Leave it: removing it is fragile.

**Duplicates and conflicts:**
- **play-json and play-functional 2.9.4 against 3.0.4:** 367 identical class names; 2.9.4 wins (decision 4).
- **`javax.activation:activation:1.1` against `javax.activation-api:1.2.0`:** 27 identical classes; 1.2.0 wins (classpath position 97 against 194). But `activation-1.1` alone has `com.sun.activation.registries.*`, which `MailcapCommandMap` needs, and that is reachable from `db.scalikejdbc.User$` → `InternetAddress`. So **don't** exclude `activation-1.1`; fix it by moving to play-mailer 10 or Jakarta Mail.
- **`commons-logging` against `jcl-over-slf4j`:** harmless (the right one wins). Exclude `commons-logging` from `scalikejdbc-core`/`commons-dbcp2`.
- **Eviction warnings:**
  - `scala-csv 2.0.0` is selected over wlxjury's own `1.4.1`, because scalawiki-wlx wants 2.0.0. Bump the direct dependency to 2.0.0 to match what runs.
  - netty 4.1.118 → 4.1.119, guava 31 → 32, scala-parser-combinators 1.1.2 → 2.4.0 (for Play), and slf4j to 2.0.17: all benign, the usual Play 3 set.
  - `jackson-core 2.15.2` with `jackson-databind 2.14.3` comes from Play's set; it's allowed (a newer core) but untidy.

**Stale:**
- Bootstrap 3 and play-bootstrap: already gone (decision 3).
- `jaxb-impl 2.2.5` (2012), reached by one class (`NamespacePrefixMapper` from sweble's `WikiConfigImpl`), and `commons-collections 3.2.2`: both are sweble's. That's a scalawiki matter.
- `javax.mail 1.5.6` and `activation 1.1`: replace with play-mailer 10.

## 3. Candidates to remove

**a. Safe now** (exclusions and scopes; no code change):

| # | what | size | how | risk | verify |
|---|---|---:|---|---|---|
| 1 | JMH + commons-math3 + jopt-simple | 2.92 MB | `jmh` dependencies `% Test`, or a `bench` sub-project | none at run time; JMH benches must still compile | `Jmh/compile`, `Runtime/dependencyList` |
| 2 | scala-chart + scala-swing | 1.10 MB | `excludeAll` on scalawiki-wlx | none: 0 classes reachable | `Test/compile`, the round-filter specs |
| 3 | jfreechart + jcommon | 1.85 MB | the same | low: reachable only through `ContestStat.withAccounts` and stat reports that wlxjury doesn't call | as above, plus a special-nomination round or a new `SpecialNominationFilter` spec |
| 4 | play-json + play-functional 2.9.4 | 0.96 MB | `excludeAll` on scalawiki-core and -wlx | low: all 225 referenced members exist in 3.0.4 | import and Commons specs, `ApiSpec`, CSRF and error JSON specs |
| 5 | commons-logging | 0.06 MB | `exclude("commons-logging", "commons-logging")` on scalikejdbc | none (shadowed) | `Test/compile` and startup |
| 6 | commons-collections 3.2.2 | 0.56 MB | exclude from scalawiki-core | low: 0 classes reachable, but sweble could use it reflectively | the `TemplateParser`/`ImageText` specs (imports) |

**Total if 1–5 go: 6.89 MB.** With 6 as well: **7.45 MB**, about 7% of the third-party 102 MB.

**b. Housekeeping** (no size change):
- Bump the direct `scala-csv` to 2.0.0.
- Drop the direct `javax.xml.bind:jaxb-api` line; scalawiki still brings it.
- Move to `org.playframework:play-mailer 10.x`. This removes the activation duplicate and the 2016 `javax.mail`, but needs a mail-code check.

**c. The one large item: Swagger UI and OpenAPI generated at run time, about 16.5 MB.**
- `tapir-swagger-ui-bundle` turns the endpoints into YAML at startup through apispec-circe, circe-yaml and snakeyaml. That pulls in circe, cats (9.6 MB), shapeless (3.2 MB) and the rest.
- The alternative is to generate `openapi.yaml` at **build** time with `tapir-openapi-docs` and `sttp-apispec`'s circe-yaml in a `Compile/resourceGenerators` task or a small sbt sub-project. Serve it as a static asset, with the existing `swagger-ui` webjar (3.05 MB, kept) through `webjars-play`, or `tapir-swagger-ui` with a pre-rendered document.
- This drops cats, shapeless, circe, snakeyaml, apispec-circe, enumeratum and quicklens from run time: about 16.5 MB, the biggest single saving available.
- **Risk:** medium. The docs must stay in sync with the endpoints (the build-time generation ensures it), and `/api/docs` must keep the same URL and CSP.
- **Verify:** `ApiSpec`, a test that the served YAML matches the generated one, and a browser check of `/api/docs`.
- This is a feature change, not a dependency tidy-up. Schedule it separately.

**Not removable:**
- Pekko HTTP (7.5 MB): scalawiki's HTTP client.
- scala-reflect.
- Netty native epoll for 4 architectures (about 160 KB).
- The webjars-locator chain (about 1 MB; webjars-play needs it).

## 5. The scalawiki side

**What wlxjury uses** (imports and reachability):
- core: `MwBot`, the `dto` and `cmd` query DSL, `json.Parser`, `wikitext.TemplateParser` (so sweble and jsoup);
- wlx: `dto.{Contest, Monument, SpecialNomination}`, `query.MonumentQuery`, `MonumentDB`, `CountryParser`, `CampaignList`, `stat.ContestStat`, and `stat.rating.RateConfig` (which brings scallop through `StatParams`).

**Needed:** pekko-http and its caching, play-json (as 3.x), ficus, config, chronoscala, retry, jsoup, sweble with ptk, rats and jaxb, commons-codec/io/lang3, scala-csv, and progressbar with jline (as long as `Progress` is linked from `MonumentQuery`).

**Not needed by wlxjury:** scala-chart, jfreechart, jcommon and scala-swing. Perhaps also scallop, if `RateConfig` stops extending a `ScallopConf`-based `StatParams` (396 KB).

**Suggestions for scalawiki** (not changes):
1. Move the reports, charts and `Statistics` bot out of `scalawiki-wlx` into a `scalawiki-stat` module (decision 2). This is the main one.
2. Switch to `org.playframework:play-json 3.0.x`.
3. Make the progress bar optional behind the `Progress` interface.
4. Separate `StatParams` (the CLI, with scallop) from `RateConfig` (the data), so that library users don't link scallop.
5. Consider marking `logback-classic` as `% Optional`. A library shouldn't force a logging backend. wlxjury gets Logback from Play anyway, so this saves nothing here, but it's correct practice.
6. Sweble's old `jaxb-impl 2.2.5` and `commons-collections 3.2.2`: check whether exclusions work in scalawiki's own tests and publish them there.

## 6. Recommendation and ordered steps (fits the dependency-updates batch)

1. **build.sbt, one commit:**
   - JMH to `Test`, or a `bench` sub-project;
   - the scalawiki exclusions (charts, jfree, play-json 2.9.4 and play-functional);
   - `commons-logging` excluded;
   - `scala-csv` bumped to 2.0.0;
   - the redundant `jaxb-api` line dropped.

   Then: `Runtime/dependencyList` diff, `Test/compile`, and the affected specs (imports and Commons, `ApiSpec`, CSRF and error JSON, round filters and distribution). Add a small `SpecialNominationFilter` spec. Then `stage` and the docker-compose smoke test. **Saving: 6.9 MB.**
2. **Mail:** move to `org.playframework:play-mailer 10.x`. Run the mail and invite specs.
3. **scalawiki PR:**
   - the stat and charts module split;
   - play-json 3;
   - optional `Progress`;
   - `StatParams` and `RateConfig` separated.

   Republish, update `project/scalawiki.sha`, and drop the wlxjury exclusions that are no longer needed.
4. **Later, as a feature:** generate the OpenAPI document at build time and drop circe, cats and shapeless from run time (about 16.5 MB).
5. Optional guards: a build check that fails on `org.projectlombok`, `org.openjdk.jmh`, `org.jfree` or a second `play-json` in the runtime classpath.

After steps 1–4, the third-party layer goes from about 102 MB to about 79 MB.

---

## 7. The base image

**Current:** `dockerBaseImage := "eclipse-temurin:17.0.20.1_1-jre-jammy@sha256:b3054a20e1a851574cdb1692dd0db5310c647e62214a2a7abecbec1b9e3ae8f1"`.
- That is the build of 2026-10-17, per the tree's own comment. The committed `fc0b16e3` had `@sha256:c35794d1…`, which is the tag's current build, pushed 2026-10-27 and too young for the two-week rule. The uncommitted edit fixed that.
- Docker Hub, amd64, compressed: **93.4 MB** for this image, made of:

| layer | compressed | estimated uncompressed |
|---|---:|---:|
| Ubuntu 22.04 rootfs | 29.8 MB | ~78 MB |
| `apt-get install curl wget gnupg fontconfig ca-certificates p11-kit tzdata locales`, plus `locale-gen en_US.UTF-8` | 16.1 MB | ~45 MB |
| Temurin 17.0.20.1 JRE in `/opt/java/openjdk` | 47.5 MB | ~140 MB |
| `ENV LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8` and the cacerts entrypoint | ~0 | ~0 |

- That totals about 266 MB uncompressed, which matches the 395 − 129 MB measured.
- Uncompressed figures are estimates. Docker Hub publishes only compressed sizes, and nothing was pulled.

**What the app needs from the base:**
- **Headless AWT/ImageIO.** Resizing stays (see below).
- **A UTF-8 locale.** `sun.jnu.encoding` must be UTF-8 for the image cache's Cyrillic file names. `-Dfile.encoding=UTF-8` does not set it.
- **`bash`.** The native-packager start script `bin/wlxjury` begins with `#!/usr/bin/env bash`.
- **`curl`**, for the compose health check (`docker-compose.yml`: `["CMD", "curl", "-fsS", … "/health/ready"]`).
- **Not needed:**
  - A timezone database: the JVM carries its own `tzdb.dat`.
  - JNA: it is `% Test` only.
  - Netty native epoll: Play's default `play.server.netty.transport = "jdk"`, and the app sets nothing else.

**Image resizing, the facts** (`app/services/LocalImageCacheService.scala`):
- **Decode:** `ImageIO.read` handles JPEG, PNG and GIF with the JDK's readers, and WebP with TwelveMonkeys `imageio-webp`, which is pure Java. PDF, TIFF and SVG are not decoded; they are shown as thumbnails only.
- **Scale:** `new BufferedImage(w, h, TYPE_INT_RGB)` and `createGraphics`, then bicubic and quality hints and `drawImage`.
- **Encode:** `ImageIO.write(img, "JPEG", …)`.
- **No text** is drawn: no `Font` and no `drawString`. So no fonts, fontconfig or freetype are needed.
- **Native parts needed:** `libawt` with `libawt_headless` (the Java2D loops) and `libjavajpeg` (the JDK's JPEG codec, with its own bundled libjpeg). All are inside any JRE that has `java.desktop`.
- **This doesn't bind the choice:** every option below supports headless decode, scale and encode.
- **Recommendation:** set `-Djava.awt.headless=true` explicitly in `Universal / javaOptions`. The JDK infers it when `DISPLAY` is unset, but being explicit is safer.
- Whether resizing is needed at all is the standard-sizes investigation's subject. The user has decided that resizing stays on our own server: one size is fetched from Commons and the others are derived.

**Alternatives:** sizes are amd64 compressed from Docker Hub and gcr.io; uncompressed figures are estimates.

| base | compressed | ≈ uncompressed | image scaling | other breakage | updates | effort |
|---|---:|---:|---|---|---|---|
| `eclipse-temurin:17-jre-jammy` (current) | 93.4 MB | ~266 MB | works (has fontconfig too) | none | Temurin tags rebuilt for OS updates; JDK versions as tags | none |
| `eclipse-temurin:17-jre-noble` (Ubuntu 24.04) | 94.4 MB | ~268 MB | works | none; matches the planned 24.04 server move | as above | change the tag and digest |
| `eclipse-temurin:17-jre-alpine` (Alpine 3.24, musl) | 68.1 MB | ~170–190 MB | works: Temurin builds its musl AWT natives, and the image has `fontconfig ttf-dejavu musl-locales` | **no bash** (add `apk add bash`, or use native-packager's `AshScriptPlugin`); **no curl** (busybox `wget -q --spider` for the health check, or `apk add curl`); musl differs from production's glibc | Temurin alpine tags; Alpine security updates by rebuild | small to medium, with a smoke test |
| `gcr.io/distroless/java17-debian12` | 82.5 MB | ~225 MB | works: Debian's `openjdk-17-jre-headless` | **no shell at all**: the bash start script can't run (needs an exec-form `java -cp /opt/docker/lib/* … play.core.server.ProdServerStart` with `application.ini`'s options); **no curl or wget** (health check needs a Java probe or the `:debug` variant); JDK is Debian's 17.0.x, not Temurin; set `LANG=C.UTF-8` and check `sun.jnu.encoding` | rebuilt continuously from Debian; only `latest`, `nonroot` and `debug` tags, **no JDK version tags**, so a digest pin is the only way to pin | medium |
| custom `jlink` runtime on `debian:12-slim` (~28 MB compressed) or `distroless/java-base-debian12` (15.1 MB compressed) | ~45–60 MB | ~100–150 MB | works if `java.desktop` is included | must list the modules (below) **plus the service modules jdeps can't see**: `jdk.crypto.ec` (TLS to Commons and Google), `jdk.localedata` (uk and ru formats), `jdk.charsets`, `jdk.zipfs`, `jdk.management(.agent)`; a missing one fails only at run time; distroless again means no shell | you rebuild the runtime for each Temurin JDK update (multi-stage `FROM eclipse-temurin:17-jdk@digest` → `jlink`); native-packager's `JlinkPlugin` runs on the **build host**, which here is Windows, so jlink must run in the Docker build stage or on Linux CI | medium to high |

**Modules for jlink**, from a constant-pool scan of all runtime jars except JMH and charts. `jdeps --print-module-deps` failed on named-module jars and was too slow on the flattened classpath, so I stopped it.
- From the scan: `java.base, java.compiler, java.datatransfer, java.desktop, java.logging, java.management, java.naming, java.prefs, java.rmi, java.scripting, java.security.jgss, java.security.sasl, java.sql, java.sql.rowset, java.transaction.xa, java.xml, jdk.jfr, jdk.management, jdk.naming.dns, jdk.net, jdk.sctp, jdk.unsupported`.
- Add the service modules above.
- That is most of a JRE anyway. The saving over Temurin's JRE comes mainly from `--strip-debug --no-man-pages --no-header-files` and leaving out the dev and debug modules.

**Two-week rule and digest pinning:**
- Keep pinning by digest for every option. For distroless that is the only pin.
- The rule applies to the **digest's** age: Temurin re-pushes the same tag, as `17.0.20.1_1-jre-jammy` was on 2026-10-27.
- Temurin 17.0.21 (the October CPU, released 2026-10-22) becomes eligible on **2026-11-05**. Then bump to a `17.0.21_9-jre-*` digest at least 14 days old (one recorded by `docker-library/repo-info`). Until then, the image runs 17.0.20.1, the same as production.

**Shared layers:** base layers download once per host and are reused by every rebuild and every app version, and Docker never rebuilds them. A smaller base helps the first pull, CI cache space and registry storage. It doesn't help redeploys, which move only layers 2 and 3 (and layer 1 when third-party jars change).

**Recommendation:**
- **Stay on Temurin JRE with Ubuntu.** It matches production's JRE and glibc, has bash, curl and the UTF-8 locale, and needs no start-script or health-check changes.
- Move to `-jre-noble` with the server's 24.04 move, and to 17.0.21 once its digest is 14 days old.
- Alpine saves about 80–95 MB once, at the cost of a musl and glibc difference from production plus the bash and health-check changes. It isn't worth it for a self-hosting image whose base is cached.
- Distroless saves about 40 MB and costs the start script and health check, so it isn't worth it.
- A jlink runtime (saving about 120–160 MB) is the only large saving. Consider it later if image size becomes a real constraint, for example many fresh hosts or a registry quota. Pair it with a smoke test that does a TLS call, a WebP and JPEG resize, a Ukrainian-locale page, and a non-ASCII cache file name.

---

## Appendix: every jar in `lib/` (this tree's `sbt stage`)

Sizes are in KB. The parent is the artifact that declares the jar. The last column lists the top-level dependencies of wlxjury that bring it; "+N" means N more.

| KB | jar | group | pulled in (parent) | top-level dependencies that bring it |
|---:|---|---|---|---|
| 18588 | `org.intracer.wlxjury-26.11-SNAPSHOT-assets` | wlxjury itself | wlxjury |  |
| 5913 | `org.typelevel.cats-core_2.13-2.9.0` | Tapir API: docs/Swagger | via circe-core | tapir-swagger-ui-bundle |
| 5785 | `org.scala-lang.scala-library-2.13.15` | Play and its stack | scala-library (everything) | all |
| 5062 | `org.apache.pekko.pekko-stream_2.13-1.0.3` | Pekko + Pekko HTTP | direct | tapir-play-server, pekko-stream, play-filters-helpers +7 |
| 4486 | `org.apache.pekko.pekko-http-core_2.13-1.0.1` | Pekko + Pekko HTTP | via pekko-http-caching, pekko-http | pekko-http, scalawiki-core, scalawiki-wlx |
| 3926 | `org.apache.pekko.pekko-actor_2.13-1.0.3` | Pekko + Pekko HTTP | via pekko-actor-typed, pekko-serialization-jackson, pekko-slf4j, | tapir-play-server, pekko-stream, play-filters-helpers +7 |
| 3834 | `org.intracer.wlxjury-26.11-SNAPSHOT-sans-externalized` | wlxjury itself | wlxjury |  |
| 3714 | `org.scala-lang.scala-reflect-2.13.15` | Play and its stack | via enumeratum-macros, play-json, play-json, scalikejdbc-interpo | tapir-json-play, tapir-play-server, tapir-swagger-ui-bundle +10 |
| 3494 | `org.typelevel.cats-kernel_2.13-2.9.0` | Tapir API: docs/Swagger | via cats-core | tapir-swagger-ui-bundle |
| 3167 | `com.chuusai.shapeless_2.13-2.3.10` | Tapir API: docs/Swagger | via circe-generic | tapir-swagger-ui-bundle |
| 3049 | `org.webjars.swagger-ui-5.18.3` | Tapir API: docs/Swagger | via tapir-swagger-ui | tapir-swagger-ui-bundle |
| 2972 | `com.google.guava.guava-32.1.3-jre` | Play and its stack | via guice, play | tapir-play-server, play-mailer-guice, play-filters-helpers +5 |
| 2859 | `org.playframework.play_2.13-3.0.7` | Play and its stack | via tapir-play-server, play-filters-helpers, play-guice, play-jd | tapir-play-server, play-filters-helpers, play-guice +4 |
| 2506 | `org.webjars.bootstrap-5.3.8` | webjars | direct | bootstrap |
| 2359 | `org.scalawiki.scalawiki-wlx_2.13-0.7.0-SNAPSHOT` | scalawiki own jars | direct | scalawiki-wlx |
| 2161 | `org.apache.commons.commons-math3-3.6.1` | JMH | via jmh-core | jmh-core, jmh-generator-bytecode, jmh-generator-reflection |
| 1913 | `org.apache.pekko.pekko-http_2.13-1.0.1` | Pekko + Pekko HTTP | direct | pekko-http, scalawiki-core, scalawiki-wlx |
| 1762 | `org.scalikejdbc.scalikejdbc-core_2.13-4.3.2` | DB | via scalikejdbc-config, scalikejdbc-interpolation-macro, scalike | scalikejdbc-config, scalikejdbc-orm, scalikejdbc |
| 1725 | `org.webjars.npm.bootstrap-icons-1.13.1` | webjars | direct | bootstrap-icons |
| 1707 | `org.apache.pekko.pekko-protobuf-v3_2.13-1.0.3` | Pekko + Pekko HTTP | via pekko-stream | tapir-play-server, pekko-stream, play-filters-helpers +7 |
| 1586 | `com.softwaremill.sttp.tapir.tapir-core_2.13-1.11.15` | Tapir API: core + Play server + play-json | direct | tapir-core, tapir-json-play, tapir-play-server +1 |
| 1579 | `com.fasterxml.jackson.core.jackson-databind-2.14.3` | Play and its stack | via jackson-dataformat-cbor, jackson-datatype-jdk8, jackson-data | tapir-json-play, tapir-play-server, play-filters-helpers +8 |
| 1528 | `org.jfree.jfreechart-1.0.19` | scalawiki: charts | via scala-chart | scalawiki-wlx |
| 1243 | `org.apache.pekko.pekko-actor-typed_2.13-1.0.3` | Pekko + Pekko HTTP | via play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 1211 | `org.scalawiki.scalawiki-core_2.13-0.7.0-SNAPSHOT` | scalawiki own jars | direct | scalawiki-core, scalawiki-wlx |
| 1132 | `io.circe.circe-core_2.13-0.14.6` | Tapir API: docs/Swagger | via jsonschema-circe, circe-generic, circe-jawn, circe-parser, c | tapir-swagger-ui-bundle |
| 1079 | `com.sun.xml.bind.jaxb-impl-2.2.5` | scalawiki: wikitext parser | via swc-engine | scalawiki-core, scalawiki-wlx |
| 1063 | `org.webjars.npm.tom-select-2.6.2` | webjars | direct | tom-select |
| 933 | `org.parboiled.parboiled_2.13-2.5.0` | Pekko + Pekko HTTP | via pekko-http-core | pekko-http, scalawiki-core, scalawiki-wlx |
| 899 | `com.nimbusds.oauth2-oidc-sdk-11.38.2` | auth | direct | oauth2-oidc-sdk |
| 890 | `com.github.ben-manes.caffeine.caffeine-2.9.3` | scalawiki: other | via pekko-http-caching | scalawiki-core, scalawiki-wlx |
| 798 | `org.flywaydb.flyway-core-13.12.0` | DB | direct | flyway-core, flyway-mysql |
| 794 | `com.nimbusds.nimbus-jose-jwt-10.9.1` | auth | via oauth2-oidc-sdk | oauth2-oidc-sdk |
| 786 | `com.google.inject.guice-6.0.0` | Play and its stack | via guice-assistedinject, play-mailer-guice, play-guice | play-mailer-guice, play-guice |
| 783 | `org.playframework.play-json_2.13-3.0.4` | Play and its stack | via tapir-json-play, play | tapir-json-play, tapir-play-server, play-filters-helpers +5 |
| 757 | `com.typesafe.play.play-json_2.13-2.9.4` | scalawiki: play-json 2.9.4 + play-functional 2.9.4 | via scalawiki-core | scalawiki-core, scalawiki-wlx |
| 728 | `org.mariadb.jdbc.mariadb-java-client-3.5.3` | DB | direct | mariadb-java-client |
| 721 | `org.scala-lang.modules.scala-swing_2.13-3.0.0` | scalawiki: charts | via scala-chart | scalawiki-wlx |
| 702 | `io.netty.netty-common-4.1.119.Final` | Play and its stack | via netty-buffer, netty-codec, netty-codec-http, netty-handler,  | play-netty-server |
| 666 | `org.lz4.lz4-java-1.8.0` | Pekko + Pekko HTTP | via pekko-serialization-jackson | tapir-play-server, play-filters-helpers, play-guice +4 |
| 660 | `io.netty.netty-codec-http-4.1.119.Final` | Play and its stack | via netty-reactive-streams-http, play-netty-server | play-netty-server |
| 624 | `joda-time.joda-time-2.13.0` | DB | via scalikejdbc-joda-time | scalikejdbc-orm |
| 617 | `org.apache.commons.commons-lang3-3.13.0` | scalawiki: other | via utils, commons-text, swc-engine, swc-parser-lazy | scalawiki-core, scalawiki-wlx, webjars-play |
| 607 | `ch.qos.logback.logback-core-1.5.17` | Play and its stack | via logback-classic | play-logback, scalawiki-core, scalawiki-wlx |
| 593 | `com.sun.mail.javax.mail-1.5.6` | mail | via commons-email | play-mailer-guice, play-mailer |
| 574 | `commons-collections.commons-collections-3.2.2` | scalawiki: wikitext parser | via swc-engine | scalawiki-core, scalawiki-wlx |
| 566 | `io.netty.netty-handler-4.1.119.Final` | Play and its stack | via netty-codec-http, netty-reactive-streams, play-netty-server | play-netty-server |
| 552 | `io.github.classgraph.classgraph-4.8.168` | webjars | via webjars-locator-core | webjars-play |
| 540 | `org.openjdk.jmh.jmh-core-1.37` | JMH | direct | jmh-core, jmh-generator-bytecode, jmh-generator-reflection |
| 536 | `com.fasterxml.jackson.core.jackson-core-2.15.2` | Play and its stack | via jackson-databind, jackson-dataformat-cbor, jackson-datatype- | tapir-json-play, tapir-play-server, play-filters-helpers +8 |
| 533 | `org.scala-lang.modules.scala-xml_2.13-2.2.0` | Play and its stack | via twirl-api | tapir-play-server, twirl-api, play-filters-helpers +6 |
| 509 | `io.netty.netty-transport-4.1.119.Final` | Play and its stack | via netty-codec, netty-codec-http, netty-handler, netty-transpor | play-netty-server |
| 500 | `com.fasterxml.jackson.module.jackson-module-scala_2.13-2.14.3` | Play and its stack | via pekko-serialization-jackson, play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 500 | `org.sweble.wikitext.swc-parser-lazy-3.1.9` | scalawiki: wikitext parser | via swc-engine | scalawiki-core, scalawiki-wlx |
| 498 | `org.scalikejdbc.scalikejdbc-orm_2.13-4.3.2` | DB | direct | scalikejdbc-orm |
| 496 | `commons-io.commons-io-2.16.1` | scalawiki: other | via swc-engine, swc-parser-lazy | scalawiki-core, scalawiki-wlx |
| 474 | `com.softwaremill.sttp.model.core_2.13-1.7.11` | Tapir API: core + Play server + play-json | via ws, tapir-core | tapir-core, tapir-json-play, tapir-play-server +1 |
| 455 | `com.softwaremill.sttp.tapir.tapir-server_2.13-1.11.15` | Tapir API: core + Play server + play-json | via tapir-play-server | tapir-play-server |
| 448 | `org.jsoup.jsoup-1.18.3` | scalawiki: other | via scalawiki-core | scalawiki-core, scalawiki-wlx |
| 396 | `org.rogach.scallop_2.13-5.2.0` | scalawiki: other | via scalawiki-core | scalawiki-core, scalawiki-wlx |
| 391 | `org.playframework.play-filters-helpers_2.13-3.0.7` | Play and its stack | direct | play-filters-helpers |
| 379 | `org.sweble.wikitext.swc-engine-3.1.9` | scalawiki: wikitext parser | via scalawiki-core | scalawiki-core, scalawiki-wlx |
| 363 | `commons-codec.commons-codec-1.17.1` | scalawiki: other | via utils, scalawiki-core | scalawiki-core, scalawiki-wlx |
| 348 | `de.sciss.scala-chart_2.13-0.8.0` | scalawiki: charts | via scalawiki-wlx | scalawiki-wlx |
| 346 | `io.netty.netty-codec-4.1.119.Final` | Play and its stack | via netty-codec-http, netty-handler | play-netty-server |
| 331 | `io.netty.netty-buffer-4.1.119.Final` | Play and its stack | via netty-codec, netty-codec-http, netty-handler, netty-transpor | play-netty-server |
| 326 | `org.yaml.snakeyaml-2.2` | Tapir API: docs/Swagger | via circe-yaml | tapir-swagger-ui-bundle |
| 322 | `org.jfree.jcommon-1.0.23` | scalawiki: charts | via jfreechart | scalawiki-wlx |
| 312 | `com.softwaremill.sttp.apispec.openapi-model_2.13-0.11.7` | Tapir API: docs/Swagger | via openapi-circe, tapir-openapi-docs | tapir-swagger-ui-bundle |
| 300 | `org.webjars.jquery-3.7.1` | webjars | direct | jquery |
| 299 | `ch.qos.logback.logback-classic-1.5.17` | Play and its stack | via play-logback, scalawiki-core | play-logback, scalawiki-core, scalawiki-wlx |
| 289 | `com.typesafe.config-1.4.3` | Play and its stack | via ficus, play-mailer, ssl-config-core, pekko-actor, play-confi | tapir-play-server, play-mailer-guice, play-mailer +11 |
| 276 | `com.softwaremill.sttp.apispec.openapi-circe_2.13-0.11.7` | Tapir API: docs/Swagger | via openapi-circe-yaml | tapir-swagger-ui-bundle |
| 257 | `com.softwaremill.sttp.apispec.apispec-model_2.13-0.11.7` | Tapir API: docs/Swagger | via asyncapi-model, jsonschema-circe, openapi-model | tapir-swagger-ui-bundle |
| 253 | `org.jline.jline-terminal-3.24.1` | scalawiki: progress bar | via progressbar | scalawiki-wlx |
| 245 | `org.playframework.play-server_2.13-3.0.7` | Play and its stack | direct | tapir-play-server, play-netty-server, play-server |
| 240 | `org.apache.commons.commons-text-1.11.0` | webjars | via webjars-locator | webjars-play |
| 227 | `com.typesafe.ssl-config-core_2.13-0.6.1` | Pekko + Pekko HTTP | via pekko-stream, play | tapir-play-server, pekko-stream, play-filters-helpers +7 |
| 219 | `org.checkerframework.checker-qual-3.37.0` | Play and its stack | via caffeine, guava | tapir-play-server, play-mailer-guice, play-filters-helpers +7 |
| 211 | `org.apache.commons.commons-dbcp2-2.11.0` | DB | via scalikejdbc-core | scalikejdbc-config, scalikejdbc-orm, scalikejdbc |
| 211 | `com.softwaremill.magnolia1_2.magnolia_2.13-1.1.10` | Tapir API: core + Play server + play-json | via tapir-core | tapir-core, tapir-json-play, tapir-play-server +1 |
| 198 | `org.scalikejdbc.scalikejdbc-interpolation_2.13-4.3.2` | DB | via scalikejdbc-orm, scalikejdbc | scalikejdbc-orm, scalikejdbc |
| 196 | `tyrex.tyrex-1.0.1` | DB | via play-jdbc | scalikejdbc-play-dbapi-adapter |
| 184 | `org.scala-lang.modules.scala-parser-combinators_2.13-2.4.0` | Play and its stack | via play, scalikejdbc-core | tapir-play-server, play-filters-helpers, play-guice +7 |
| 183 | `org.jline.jline-native-3.24.1` | scalawiki: progress bar | via jline-terminal | scalawiki-wlx |
| 180 | `com.typesafe.play.play-functional_2.13-2.9.4` | scalawiki: play-json 2.9.4 + play-functional 2.9.4 | via play-json | scalawiki-core, scalawiki-wlx |
| 179 | `org.playframework.play-functional_2.13-3.0.4` | Play and its stack | via play-json | tapir-json-play, tapir-play-server, play-filters-helpers +5 |
| 170 | `io.circe.circe-generic_2.13-0.14.6` | Tapir API: docs/Swagger | via jsonschema-circe | tapir-swagger-ui-bundle |
| 159 | `com.softwaremill.sttp.apispec.asyncapi-model_2.13-0.11.7` | Tapir API: docs/Swagger | via tapir-apispec-docs | tapir-swagger-ui-bundle |
| 158 | `com.zaxxer.HikariCP-5.0.1` | DB | via play-jdbc | scalikejdbc-play-dbapi-adapter |
| 155 | `com.iheart.ficus_2.13-1.5.2` | scalawiki: other | via scalawiki-core | scalawiki-core, scalawiki-wlx |
| 146 | `org.apache.commons.commons-pool2-2.12.0` | DB | via commons-dbcp2 | scalikejdbc-config, scalikejdbc-orm, scalikejdbc |
| 146 | `org.joda.joda-convert-2.2.3` | DB | via scalikejdbc-joda-time | scalikejdbc-orm |
| 144 | `io.netty.netty-transport-classes-epoll-4.1.119.Final` | Play and its stack | via netty-transport-native-epoll | play-netty-server |
| 133 | `com.softwaremill.sttp.tapir.tapir-apispec-docs_2.13-1.11.15` | Tapir API: docs/Swagger | via tapir-openapi-docs | tapir-swagger-ui-bundle |
| 132 | `org.apache.pekko.pekko-serialization-jackson_2.13-1.0.3` | Pekko + Pekko HTTP | via play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 125 | `javax.xml.bind.jaxb-api-2.3.1` | JAXB API | direct | jaxb-api, scalawiki-core, scalawiki-wlx |
| 123 | `de.fau.cs.osr.ptk.ptk-common-3.0.8` | scalawiki: wikitext parser | via scalawiki-core, swc-engine, swc-parser-lazy | scalawiki-core, scalawiki-wlx |
| 123 | `org.ow2.asm.asm-9.7.1` | auth | via accessors-smart, jmh-generator-asm | oauth2-oidc-sdk, jmh-generator-bytecode |
| 122 | `com.twelvemonkeys.imageio.imageio-core-3.15.0` | image handling | via imageio-metadata, imageio-webp | imageio-webp |
| 120 | `com.twelvemonkeys.common.common-lang-3.15.0` | image handling | via common-image, common-io, imageio-core, imageio-metadata, ima | imageio-webp |
| 119 | `net.minidev.json-smart-2.6.0` | auth | via oauth2-oidc-sdk | oauth2-oidc-sdk |
| 119 | `com.fasterxml.jackson.datatype.jackson-datatype-jsr310-2.14.3` | Play and its stack | via play-json, pekko-serialization-jackson, play-json, play | tapir-json-play, tapir-play-server, play-filters-helpers +7 |
| 111 | `com.github.spullara.mustache.java.compiler-0.9.11` | webjars | via webjars-locator | webjars-play |
| 105 | `jp.ne.opt.chronoscala_2.13-1.0.0` | scalawiki: other | via scalawiki-core | scalawiki-core, scalawiki-wlx |
| 104 | `org.playframework.play-netty-server_2.13-3.0.7` | Play and its stack | direct | play-netty-server |
| 100 | `com.softwaremill.quicklens.quicklens_2.13-1.9.12` | Tapir API: docs/Swagger | via tapir-openapi-docs | tapir-swagger-ui-bundle |
| 98 | `org.playframework.play-guice_2.13-3.0.7` | Play and its stack | direct | play-guice |
| 90 | `com.twelvemonkeys.common.common-io-3.15.0` | image handling | via common-image, imageio-core, imageio-metadata, imageio-webp | imageio-webp |
| 87 | `com.softwaremill.sttp.tapir.tapir-openapi-docs_2.13-1.11.15` | Tapir API: docs/Swagger | via tapir-swagger-ui-bundle | tapir-swagger-ui-bundle |
| 86 | `org.typelevel.jawn-parser_2.13-1.4.0` | Tapir API: docs/Swagger | via circe-jawn | tapir-swagger-ui-bundle |
| 85 | `org.playframework.play-streams_2.13-3.0.7` | Play and its stack | via play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 85 | `de.fau.cs.osr.utils.utils-3.0.8` | scalawiki: wikitext parser | via ptk-common, swc-engine, swc-parser-lazy | scalawiki-core, scalawiki-wlx |
| 82 | `com.twelvemonkeys.imageio.imageio-metadata-3.15.0` | image handling | via imageio-webp | imageio-webp |
| 79 | `com.twelvemonkeys.imageio.imageio-webp-3.15.0` | image handling | direct | imageio-webp |
| 79 | `org.scalawiki.http-extensions_2.13-0.7.0-SNAPSHOT` | scalawiki own jars | via scalawiki-core | scalawiki-core, scalawiki-wlx |
| 79 | `at.favre.lib.bytes-1.5.0` | auth | via bcrypt | bcrypt |
| 78 | `io.jsonwebtoken.jjwt-impl-0.11.5` | Play and its stack | via play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 78 | `com.twelvemonkeys.common.common-image-3.15.0` | image handling | via imageio-core, imageio-metadata, imageio-webp | imageio-webp |
| 77 | `com.softwaremill.sttp.tapir.tapir-files_2.13-1.11.15` | Tapir API: docs/Swagger | via tapir-swagger-ui | tapir-swagger-ui-bundle |
| 76 | `com.softwaremill.sttp.tapir.tapir-play-server_2.13-1.11.15` | Tapir API: core + Play server + play-json | direct | tapir-play-server |
| 76 | `net.sf.jopt-simple.jopt-simple-5.0.4` | JMH | via jmh-core | jmh-core, jmh-generator-bytecode, jmh-generator-reflection |
| 75 | `io.jsonwebtoken.jjwt-api-0.11.5` | Play and its stack | via jjwt-impl, jjwt-jackson, play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 74 | `com.fasterxml.jackson.core.jackson-annotations-2.14.3` | Play and its stack | via jackson-databind, jackson-datatype-jsr310, jackson-module-sc | tapir-json-play, tapir-play-server, play-filters-helpers +8 |
| 70 | `com.softwaremill.sttp.apispec.jsonschema-circe_2.13-0.11.7` | Tapir API: docs/Swagger | via openapi-circe | tapir-swagger-ui-bundle |
| 70 | `io.circe.circe-yaml_2.13-0.15.1` | Tapir API: docs/Swagger | via openapi-circe-yaml | tapir-swagger-ui-bundle |
| 69 | `com.beachape.enumeratum_2.13-1.7.5` | Tapir API: docs/Swagger | via tapir-enumeratum | tapir-swagger-ui-bundle |
| 68 | `org.slf4j.slf4j-api-2.0.17` | Play and its stack | via logback-classic, jdbcdslog, play-mailer, HikariCP, pekko-act | tapir-play-server, play-mailer-guice, play-mailer +13 |
| 67 | `org.playframework.twirl.twirl-api_2.13-2.0.8` | Play and its stack | direct | tapir-play-server, twirl-api, play-filters-helpers +6 |
| 66 | `com.typesafe.play.play-mailer_2.13-9.0.0` | mail | direct | play-mailer-guice, play-mailer |
| 64 | `com.softwaremill.retry.retry_2.13-0.3.6` | scalawiki: other | via scalawiki-core | scalawiki-core, scalawiki-wlx |
| 63 | `com.fasterxml.jackson.dataformat.jackson-dataformat-cbor-2.14.3` | Play and its stack | via pekko-serialization-jackson, play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 61 | `javax.activation.activation-1.1` | mail | via javax.mail | play-mailer-guice, play-mailer |
| 60 | `xtc.rats-runtime-1.15.0` | scalawiki: wikitext parser | via ptk-common, swc-engine, swc-parser-lazy | scalawiki-core, scalawiki-wlx |
| 60 | `commons-logging.commons-logging-1.2` | DB | via commons-dbcp2 | scalikejdbc-config, scalikejdbc-orm, scalikejdbc |
| 60 | `org.apache.pekko.pekko-http-caching_2.13-1.0.1` | scalawiki: other | via scalawiki-core | scalawiki-core, scalawiki-wlx |
| 59 | `com.beachape.enumeratum-macros_2.13-1.7.5` | Tapir API: docs/Swagger | via enumeratum | tapir-swagger-ui-bundle |
| 55 | `javax.activation.javax.activation-api-1.2.0` | JAXB API | via jaxb-api | jaxb-api, scalawiki-core, scalawiki-wlx |
| 55 | `org.playframework.play-jdbc_2.13-3.0.3` | Play and its stack | via scalikejdbc-play-dbapi-adapter | scalikejdbc-play-dbapi-adapter |
| 53 | `org.apache.commons.commons-email-1.5` | mail | via play-mailer | play-mailer-guice, play-mailer |
| 51 | `com.softwaremill.sttp.shared.core_2.13-1.4.2` | Tapir API: core + Play server + play-json | via pekko, ws, tapir-core | tapir-core, tapir-json-play, tapir-play-server +1 |
| 50 | `org.playframework.play-configuration_2.13-3.0.7` | Play and its stack | via play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 50 | `com.google.inject.extensions.guice-assistedinject-6.0.0` | Play and its stack | via play-guice | play-guice |
| 47 | `com.softwaremill.sttp.shared.ws_2.13-1.4.2` | Tapir API: core + Play server + play-json | via tapir-core | tapir-core, tapir-json-play, tapir-play-server +1 |
| 46 | `com.github.tototoshi.scala-csv_2.13-2.0.0` | scalawiki: other | direct | scala-csv, scalawiki-wlx |
| 44 | `org.webjars.webjars-play_2.13-3.0.2` | webjars | direct | webjars-play |
| 43 | `io.netty.netty-transport-native-unix-common-4.1.119.Final` | Play and its stack | via netty-handler, netty-transport-classes-epoll, netty-transpor | play-netty-server |
| 43 | `uk.org.lidalia.sysout-over-slf4j-1.0.2` | Play and its stack | direct | sysout-over-slf4j |
| 41 | `io.netty.netty-transport-native-epoll-4.1.119.Final-linux-aarch_64` | Play and its stack | via play-netty-server | play-netty-server |
| 40 | `org.playframework.netty.netty-reactive-streams-http-3.0.4` | Play and its stack | via play-netty-server | play-netty-server |
| 39 | `io.netty.netty-transport-native-epoll-4.1.119.Final-linux-x86_64` | Play and its stack | via play-netty-server | play-netty-server |
| 39 | `at.favre.lib.bcrypt-0.10.2` | auth | direct | bcrypt |
| 38 | `org.scalikejdbc.scalikejdbc-syntax-support-macro_2.13-4.3.2` | DB | via scalikejdbc-orm | scalikejdbc-orm |
| 37 | `me.tongfei.progressbar-0.10.1` | scalawiki: progress bar | via scalawiki-wlx | scalawiki-wlx |
| 36 | `io.netty.netty-resolver-4.1.119.Final` | Play and its stack | via netty-handler, netty-transport | play-netty-server |
| 35 | `io.netty.netty-transport-native-epoll-4.1.119.Final-linux-riscv64` | Play and its stack | via play-netty-server | play-netty-server |
| 35 | `com.fasterxml.jackson.datatype.jackson-datatype-jdk8-2.14.3` | Play and its stack | via play-json, pekko-serialization-jackson, play-json, play | tapir-json-play, tapir-play-server, play-filters-helpers +7 |
| 33 | `com.thoughtworks.paranamer.paranamer-2.8` | Play and its stack | via jackson-module-scala | tapir-play-server, play-filters-helpers, play-guice +4 |
| 33 | `com.googlecode.usc.jdbcdslog-1.0.6.2` | Play and its stack | via play-jdbc | scalikejdbc-play-dbapi-adapter |
| 31 | `org.scalikejdbc.scalikejdbc-config_2.13-4.3.2` | DB | direct | scalikejdbc-config, scalikejdbc-orm |
| 30 | `org.flywaydb.flyway-mysql-13.12.0` | DB | direct | flyway-mysql |
| 30 | `org.openjdk.jmh.jmh-generator-asm-1.37` | JMH | via jmh-generator-bytecode | jmh-generator-bytecode |
| 30 | `io.circe.circe-jawn_2.13-0.14.6` | Tapir API: docs/Swagger | via circe-parser | tapir-swagger-ui-bundle |
| 29 | `org.webjars.requirejs-2.3.7` | webjars | via webjars-play | webjars-play |
| 29 | `net.minidev.accessors-smart-2.6.0` | auth | via json-smart | oauth2-oidc-sdk |
| 29 | `org.apache.pekko.pekko-parsing_2.13-1.0.1` | Pekko + Pekko HTTP | via pekko-http-core | pekko-http, scalawiki-core, scalawiki-wlx |
| 28 | `org.scalikejdbc.scalikejdbc-joda-time_2.13-4.3.2` | DB | via scalikejdbc-orm | scalikejdbc-orm |
| 27 | `org.playframework.netty.netty-reactive-streams-3.0.4` | Play and its stack | via netty-reactive-streams-http | play-netty-server |
| 25 | `com.softwaremill.odelay.odelay-core_2.13-0.4.0` | scalawiki: other | via retry | scalawiki-core, scalawiki-wlx |
| 23 | `org.openjdk.jmh.jmh-generator-reflection-1.37` | JMH | direct | jmh-generator-bytecode, jmh-generator-reflection |
| 22 | `org.apache.pekko.pekko-slf4j_2.13-1.0.3` | Pekko + Pekko HTTP | via pekko-actor-typed, play, scalawiki-core | tapir-play-server, play-filters-helpers, play-guice +6 |
| 20 | `io.circe.circe-yaml-common_2.13-0.15.1` | Tapir API: docs/Swagger | via circe-yaml | tapir-swagger-ui-bundle |
| 19 | `com.google.code.findbugs.jsr305-3.0.2` | Play and its stack | via guava | tapir-play-server, play-mailer-guice, play-filters-helpers +5 |
| 18 | `org.webjars.webjars-locator-core-0.58` | webjars | via webjars-locator | webjars-play |
| 18 | `org.openjdk.jmh.jmh-generator-bytecode-1.37` | JMH | direct | jmh-generator-bytecode |
| 17 | `org.slf4j.jcl-over-slf4j-2.0.17` | Play and its stack | via play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 16 | `com.google.errorprone.error_prone_annotations-2.21.1` | Play and its stack | via caffeine, guava, guice-assistedinject | tapir-play-server, play-mailer-guice, play-filters-helpers +7 |
| 15 | `org.playframework.play-jdbc-api_2.13-3.0.3` | Play and its stack | via play-jdbc | scalikejdbc-play-dbapi-adapter |
| 15 | `org.webjars.webjars-locator-0.52` | webjars | via webjars-play | webjars-play |
| 15 | `jakarta.transaction.jakarta.transaction-api-1.3.3` | DB | via commons-dbcp2 | scalikejdbc-config, scalikejdbc-orm, scalikejdbc |
| 14 | `com.softwaremill.sttp.tapir.tapir-swagger-ui_2.13-1.11.15` | Tapir API: docs/Swagger | via tapir-swagger-ui-bundle | tapir-swagger-ui-bundle |
| 12 | `com.softwaremill.sttp.tapir.tapir-enumeratum_2.13-1.11.15` | Tapir API: docs/Swagger | via tapir-openapi-docs | tapir-swagger-ui-bundle |
| 12 | `io.circe.circe-numbers_2.13-0.14.6` | Tapir API: docs/Swagger | via circe-core | tapir-swagger-ui-bundle |
| 11 | `org.reactivestreams.reactive-streams-1.0.4` | Pekko + Pekko HTTP | via pekko-stream, netty-reactive-streams, play-streams | tapir-play-server, pekko-stream, play-filters-helpers +7 |
| 10 | `com.nimbusds.lang-tag-1.7` | auth | via oauth2-oidc-sdk | oauth2-oidc-sdk |
| 10 | `jakarta.inject.jakarta.inject-api-2.0.1` | Play and its stack | via guice | play-mailer-guice, play-guice |
| 10 | `com.fasterxml.jackson.module.jackson-module-parameter-names-2.14.3` | Play and its stack | via pekko-serialization-jackson, play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 9 | `org.playframework.play-logback_2.13-3.0.7` | Play and its stack | direct | play-logback |
| 9 | `com.google.j2objc.j2objc-annotations-2.8` | Play and its stack | via guava | tapir-play-server, play-mailer-guice, play-filters-helpers +5 |
| 8 | `com.softwaremill.sttp.tapir.tapir-json-play_2.13-1.11.15` | Tapir API: core + Play server + play-json | direct | tapir-json-play |
| 8 | `com.nimbusds.content-type-2.3` | auth | via oauth2-oidc-sdk | oauth2-oidc-sdk |
| 8 | `com.softwaremill.sttp.tapir.tapir-swagger-ui-bundle_2.13-1.11.15` | Tapir API: docs/Swagger | direct | tapir-swagger-ui-bundle |
| 8 | `org.scalikejdbc.scalikejdbc_2.13-4.3.2` | DB | direct | scalikejdbc-orm, scalikejdbc |
| 8 | `io.jsonwebtoken.jjwt-jackson-0.11.5` | Play and its stack | via play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 7 | `org.scalikejdbc.scalikejdbc-interpolation-macro_2.13-4.3.2` | DB | via scalikejdbc-interpolation, scalikejdbc-orm | scalikejdbc-orm, scalikejdbc |
| 7 | `org.scalikejdbc.scalikejdbc-play-dbapi-adapter_2.13-3.0.1-scalikejdbc-4.3` | DB | direct | scalikejdbc-play-dbapi-adapter |
| 6 | `org.scala-lang.modules.scala-collection-compat_2.13-2.13.0` | Play and its stack | via retry, jsonschema-circe, tapir-play-server, scalikejdbc-core | tapir-play-server, tapir-swagger-ui-bundle, scalawiki-core +4 |
| 6 | `org.slf4j.jul-to-slf4j-2.0.17` | Play and its stack | via play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 5 | `com.softwaremill.sttp.apispec.openapi-circe-yaml_2.13-0.11.7` | Tapir API: docs/Swagger | via tapir-swagger-ui-bundle | tapir-swagger-ui-bundle |
| 5 | `org.playframework.play-exceptions-3.0.7` | Play and its stack | via play-build-link, play-configuration | tapir-play-server, play-filters-helpers, play-guice +4 |
| 4 | `com.github.stephenc.jcip.jcip-annotations-1.0-1` | auth | via oauth2-oidc-sdk | oauth2-oidc-sdk |
| 4 | `com.google.guava.failureaccess-1.0.1` | Play and its stack | via guava | tapir-play-server, play-mailer-guice, play-filters-helpers +5 |
| 4 | `aopalliance.aopalliance-1.0` | Play and its stack | via guice | play-mailer-guice, play-guice |
| 4 | `com.softwaremill.sttp.shared.pekko_2.13-1.4.2` | Tapir API: core + Play server + play-json | via tapir-play-server | tapir-play-server |
| 3 | `com.typesafe.play.play-mailer-guice_2.13-9.0.0` | mail | direct | play-mailer-guice |
| 3 | `org.playframework.play-build-link-3.0.7` | Play and its stack | via play | tapir-play-server, play-filters-helpers, play-guice +4 |
| 2 | `io.circe.circe-parser_2.13-0.14.6` | Tapir API: docs/Swagger | via jsonschema-circe | tapir-swagger-ui-bundle |
| 2 | `javax.inject.javax.inject-1` | Play and its stack | via guice, play-mailer, play-jdbc-api, play | tapir-play-server, play-mailer-guice, play-mailer +6 |
| 2 | `com.google.guava.listenablefuture-9999.0-empty-to-avoid-conflict-with-guava` | Play and its stack | via guava | tapir-play-server, play-mailer-guice, play-filters-helpers +5 |
