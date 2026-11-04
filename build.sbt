import sbt.Keys.{libraryDependencies, *}

// Extract dependency source jars to target/dep-sources/ as plain .scala/.java files.
// Run `sbt extractDepSources` once (or after adding new dependencies).
// Claude Code can then Grep/Read directly inside target/dep-sources/ instead of
// searching the web for library API signatures.
lazy val extractDepSources = taskKey[File]("Extract dependency source jars to target/dep-sources")

extractDepSources := {
  val report = (Test / updateClassifiers).value
  val outDir = target.value / "dep-sources"
  val log    = streams.value.log
  IO.createDirectory(outDir)
  for {
    config      <- report.configurations
    module      <- config.modules
    (art, file) <- module.artifacts
    if art.classifier.contains("sources") && file.exists()
  } {
    val m    = module.module
    val dest = outDir / m.organization / s"${m.name}-${m.revision}"
    if (!dest.exists()) {
      IO.createDirectory(dest)
      IO.unzip(file, dest)
      log.info(s"Extracted ${m.name} ${m.revision}")
    }
  }
  outDir
}

// Run Jest tests in javascript-test/ as part of `sbt test`.
lazy val jsTest = taskKey[Unit]("Run Jest tests in javascript-test/")

jsTest := {
  val jsDir = baseDirectory.value / "javascript-test"
  // Windows can only start npm through its npm.cmd wrapper
  val npm = if (scala.util.Properties.isWin) "npm.cmd" else "npm"
  // node_modules is git-ignored: install Jest from package-lock.json on a fresh checkout, and
  // again when the lockfile changed since (npm records the install in node_modules/.package-lock.json)
  val installedLock = jsDir / "node_modules" / ".package-lock.json"
  if (!installedLock.exists() || installedLock.lastModified() < (jsDir / "package-lock.json").lastModified()) {
    val installed = scala.sys.process.Process(Seq(npm, "ci"), jsDir).!
    if (installed != 0) throw new MessageOnlyException("npm ci failed")
  }
  // `sbt coverage test` (the CI job "coverage") measures the JS too, into target/jest-coverage/
  // (javascript-test/package.json, "jest"). A plain `sbt test` runs without it.
  val coverageArgs = if (coverageEnabled.value) Seq("--coverage") else Seq("--no-coverage")
  val result = scala.sys.process.Process(Seq(npm, "test", "--") ++ coverageArgs, jsDir).!
  if (result != 0) throw new MessageOnlyException("Jest tests failed")
}

// Wire jsTest into the normal `sbt test` run.
Test / test := (Test / test).dependsOn(jsTest).value

// Scala coverage (sbt-scoverage), measured only by `sbt coverage test coverageReport` in the CI
// job "coverage" (plan: docs/plans/2026-11-03-ci-and-scalawiki-packages.md, "Test coverage").
// Not measured: generated code, i.e. the Twirl templates and the routes and reverse routes.
// The templates go by file only (below): views.html.bs also holds hand-written FieldView and
// Layout (app/views/bs/), which are measured.
coverageExcludedPackages := Seq(
  "router\\..*",
  "controllers\\.javascript\\..*",
  "controllers\\.Reverse.*",
  ".*BuildInfo.*"
).mkString(";")
// The same by file: every managed (generated) source under target/ (sbt-scoverage turns the `/`
// into Windows' separator there)
coverageExcludedFiles := ".*/target/.*"
// Report only: no build fails on coverage yet
coverageFailOnMinimum := false

lazy val root =
  (project in file("."))
    .enablePlugins(
      PlayScala,
      PlayNettyServer,
      // The release artifact is the Universal ZIP (`sbt dist`); there are no Debian or RPM
      // packages. The ZIP's bin/wlxjury launcher comes from JavaServerAppPackaging; production's
      // systemd unit supplies /etc/default/wlxjury (JAVA_OPTS) as an EnvironmentFile.
      JavaServerAppPackaging,
      // `sbt Docker/publishLocal`: the docker-compose image, from the same staged files as the
      // ZIP (settings below; docs/deployment.md, "Run with docker-compose").
      DockerPlugin,
      JmhPlugin,
      GatlingPlugin
    )
    .settings(
      Jmh / sourceDirectory     := (Test / sourceDirectory).value,
      Jmh / classDirectory      := (Test / classDirectory).value,
      Jmh / dependencyClasspath := (Test / dependencyClasspath).value,
      // sbt-jmh adds jmh-core and its generators (with commons-math3 and jopt-simple) to Compile,
      // which shipped them in the release (runtime jars investigation, 2026-11-04, decision 2).
      // The benchmarks are in test/ and Jmh uses Test's classpath (above): Test is enough.
      libraryDependencies := libraryDependencies.value.map { m =>
        if (m.organization == "org.openjdk.jmh" && m.configurations.isEmpty) m % Test else m
      },
      // Gatling sources live in test/gatling/ — separate from JMH sources in test/
//      Gatling / scalaSource     := (Test / sourceDirectory).value / "gatling",
//      // Exclude Gatling simulation files from Test scope (they need gatling classpath, not Test)
//      Test / unmanagedSources / excludeFilter :=
//        HiddenFileFilter || new SimpleFileFilter(f =>
//          f.getAbsolutePath.contains("/test/gatling/simulations/")),
      Gatling / javaOptions     += "-Dconfig.file=test/resources/application.conf",
      // Forward -Dgatling.* overrides to Gatling JVM
      Gatling / javaOptions     ++= sys.props.toSeq.collect {
        case (k, v) if k.startsWith("gatling.") || k == "wlxjury.test.mariadb-image" => s"-D$k=$v"
      },
      // gatling-sbt wires GatlingIt/enterprisePackage into the project's
      // packagedArtifacts (gated on `<config> / publishArtifact`), so `sbt dist`
      // pulls it in and fails with "Couldn't locate Gatling libraries in the
      // classpath" (GatlingIt has no gatling deps; they're `% Test`). We don't
      // publish Gatling bundles, so opt both configs out.
      Gatling / publishArtifact   := false,
      GatlingIt / publishArtifact := false
    )
    .disablePlugins(PlayPekkoHttpServer)

name := "wlxjury"

organization := "org.intracer"

// Calendar version YY.MM (.N for an extra deploy in the same month); the snapshot carries the
// next release. Release steps: docs/workflow/practices.md §7.4.
version := "26.11-SNAPSHOT"

// The same identity is embedded in the app and placed at the ZIP root. The
// deployment tool compares readiness with this manifest before opening traffic.
// Rules live in project/BuildIdentity.scala, which the tests also compile.
lazy val deploymentManifest = taskKey[File]("Write the release identity and expected schema")
deploymentManifest := {
  import wlxjury.build.BuildIdentity
  val base = baseDirectory.value
  def git(args: String*) = scala.util.Try(scala.sys.process.Process("git" +: args, base).!!.trim).toOption
  val commit = git("rev-parse", "--short=12", "HEAD")
  // Untracked sources are compiled too (e.g. new Twirl templates), so `git diff` is not enough.
  val dirty = git(Seq("status", "--porcelain", "--untracked-files=all", "--") ++ BuildIdentity.SourcePaths: _*)
    .forall(BuildIdentity.isDirty)
  val id = BuildIdentity.buildId(version.value, commit, dirty, sys.props.get("wlxjury.buildId"))
    .fold(message => sys.error(message), identity)
  val latest = BuildIdentity.latestMigration((base / "conf" / "db" / "migration" / "default").listFiles().map(_.getName).toSeq)
  val out = (Compile / resourceManaged).value / "wlxjury-build.json"
  val content = BuildIdentity.manifest(id, version.value, latest)
  if (!out.exists || IO.read(out) != content) IO.write(out, content)
  out
}
Compile / resourceGenerators += Def.task { Seq(deploymentManifest.value) }.taskValue
Universal / mappings += deploymentManifest.value -> "wlxjury-build.json"
// Server setup files are installed once from a reviewed checkout (docs/deployment.md);
// keep them out of releases, whose conf/ directory is also on the app's classpath.
Universal / mappings ~= (_.filterNot { case (_, path) =>
  // Mapping paths use the build machine's separator.
  Seq("conf/wlxjury/", "conf/apache/", "conf/systemd/").exists(path.replace('\\', '/').startsWith)
})
// Instrumented classes call the scoverage runtime and write measurement files: never package them.
// `sbt coverage ...` keeps coverage on for the rest of that sbt session; `coverageOff`, or a new
// session, recompiles without it.
Universal / mappings := {
  val packaged = (Universal / mappings).value
  if (coverageEnabled.value)
    throw new MessageOnlyException("Coverage instrumentation is on: run `coverageOff`, or start a new sbt session, before packaging")
  packaged
}
Test / unmanagedSources += baseDirectory.value / "project" / "BuildIdentity.scala"

// The docker-compose image (`sbt Docker/publishLocal`, docker-compose.yml): the ZIP's files under
// /opt/docker on a Java 17 JRE, run as a non-root user. The base is pinned by digest, and that
// digest is itself at least 14 days old (dependencies practices §1.1, §2.3): Temurin 17.0.20.1
// (production's JRE) on Ubuntu 22.04, the build of 2026-10-17 (the tag is rebuilt for OS
// updates; this digest is the one docker-library/repo-info recorded then). 17.0.21 (2026-10-22)
// was 13 days old on 2026-11-04.
Docker / packageName := "wlxjury"
dockerBaseImage := "eclipse-temurin:17.0.20.1_1-jre-jammy@sha256:b3054a20e1a851574cdb1692dd0db5310c647e62214a2a7abecbec1b9e3ae8f1"
// The version tag, and `local`, the tag docker-compose.yml runs (it needn't change per release).
dockerAliases := Seq(dockerAlias.value, dockerAlias.value.withTag(Some("local")))
dockerExposedPorts := Seq(9000)
Docker / daemonUser := "wlxjury"
Docker / daemonUserUid := Some("10001")
// its own group, not root's (the plugin's default gid 0)
Docker / daemonGroup := "wlxjury"
Docker / daemonGroupGid := Some("10001")
// Writable by that user (the files under /opt/docker are read-only to it): the file log
// (logback.xml) and the thumbnail cache (wlxjury.thumbs.local-path).
dockerExposedVolumes := Seq("/opt/docker/logs", "/var/www/jury-images")
// The image's root directory is read-only to the app's user, so Play writes no RUNNING_PID.
dockerCmd := Seq("-Dpidfile.path=/dev/null")
// Three jar layers, bottom to top, so that a change rebuilds and moves only what changed: the
// third-party jars (stable, the bulk), scalawiki's (a snapshot, changes more often), then
// wlxjury's own jars and assets with bin/ and conf/ (bin/wlxjury lists the classpath).
Docker / dockerGroupLayers := {
  val lib = (Docker / defaultLinuxInstallLocation).value + "/lib/"

  {
    case (_, path) if path.startsWith(lib + "org.scalawiki.") => 2
    case (_, path) if path.startsWith(lib + "org.intracer.")  => 3
    case (_, path) if path.startsWith(lib)                    => 1
    case _                                                    => 3
  }
}

// Packaged assets get <md5>-<name> copies and <name>.md5 files, which routes.Assets.versioned
// puts in asset URLs, so a deploy changes the URL of every changed file. Only the packaged
// pipeline (stage, dist) runs it: `sbt run` and tests serve plain names.
pipelineStages := Seq(digest)

// No Scaladoc in the release: Play's dist mappings would run `Compile / doc` and add it as
// share/doc/api (42 MB that nothing on the server reads), and every stage or dist would wait
// for it. `sbt doc` still builds it locally.
PlayKeys.includeDocumentationInBinary := false

// 2.13.17, not 2.13.18: 2.13.18 miscompiles a single Int literal passed to `Long*` in a method
// with a further (implicit, default) parameter list, e.g. TestDb.createContests(10): the List
// holds a boxed Integer and unboxing throws ClassCastException at run time (2.13.15-2.13.17
// widen it). Play 3.0.12 and webjars-play 3.0.10 declare scala-library 2.13.18; 2.13.18's
// library adds only one compile-time annotation class to 2.13.17's. Move to 2.13.19+ once a
// release fixes this and is two weeks old.
scalaVersion := "2.13.17"

// Keep source decoding and forked JVM defaults deterministic across machines.
ThisBuild / scalacOptions += "-encoding"
ThisBuild / scalacOptions += "UTF-8"
ThisBuild / javacOptions += "-encoding"
ThisBuild / javacOptions += "UTF-8"
// Java 17 is the baseline (the user, 2026-11-04; production runs OpenJDK 17): class files and the
// JDK API are 17's whatever JDK builds, so a newer local JDK can't slip in a newer API.
ThisBuild / scalacOptions += "-release:17"
ThisBuild / javacOptions ++= Seq("--release", "17")
ThisBuild / javaOptions += "-Dfile.encoding=UTF-8"
Universal / javaOptions += "-Dfile.encoding=UTF-8"

val ScalikejdbcVersion = "4.3.2"
// Flyway runs from db.Migrations (no flyway-play): docs/plans/2026-11-03-flyway.md
val FlywayVersion = "13.12.0"
val ScalikejdbcPlayVersion = "3.0.1-scalikejdbc-4.3"
val ScalawikiVersion = "0.7.0-SNAPSHOT"
// play-mailer 10 is the Play 3 line (org.playframework); 9.x was Play 2.9's (com.typesafe.play)
val PlayMailerVersion = "10.1.0"
val MockServerVersion = "5.7.0"
// the Play sbt plugin's version (project/plugins.sbt) sets Play's; keep this in step
val playVersion = "3.0.12"
// Login with Google and Wikimedia accounts (services.oauth): PKCE, state, nonce, the token exchange
// and, for Google, OpenID Connect discovery and ID token validation. No pac4j (S10 removed it).
val NimbusOAuthVersion = "11.38.2"
// Pekko and Pekko HTTP must match scalawiki's (project/Dependencies.scala, Library.Pekko: PekkoV,
// PekkoHttpV), which brings them too: update them there first, then here.
val PekkoVersion = "1.0.3"
val PekkoHttpVersion = "1.0.1"
val ScalaTestVersion = "3.2.9"
val TestcontainersScalaVersion = "0.41.0"
// testcontainers-scala 0.41 brings testcontainers-java 1.19, which Docker Engine 29+
// rejects (it no longer serves the old default API version); 1.21.4 speaks 1.44.
val TestcontainersJavaVersion = "1.21.4"
val TapirVersion = "1.11.15"
val MunitVersion = "0.7.29"
val GatlingVersion = "3.8.4"

val ScalawikiPlayJson2 = Seq(
  ExclusionRule("com.typesafe.play", "play-json_2.13"),
  ExclusionRule("com.typesafe.play", "play-functional_2.13")
)
val ScalawikiCharts = Seq(
  ExclusionRule("de.sciss"), // scala-chart
  ExclusionRule("org.scala-lang.modules", "scala-swing_2.13"),
  ExclusionRule("org.jfree") // jfreechart, jcommon
)

// Bintray (and with it JCenter) shut down in 2021; every dependency is on Maven Central.
// scalawiki comes from ~/.ivy2/local (sbt's default, where scalawiki's publishLocal goes)
// or ~/.m2: docs/plans/2026-11-03-ci-and-scalawiki-packages.md
resolvers += Resolver.mavenLocal

libraryDependencySchemes += "org.scala-lang.modules" %% "scala-parser-combinators" % "always"

libraryDependencies ++= Seq(
  "org.webjars" %% "webjars-play" % "3.0.2",
  "org.webjars" % "jquery" % "3.7.1",
  // Bootstrap 5's bundle JS includes Popper; Tom Select's complete build includes its two deps
  "org.webjars" % "bootstrap" % "5.3.8" exclude ("org.webjars", "popper.js"),
  "org.webjars.npm" % "bootstrap-icons" % "1.13.1",
  "org.webjars.npm" % "tom-select" % "2.6.2" excludeAll (ExclusionRule("org.webjars.npm")),
  "org.scalikejdbc" %% "scalikejdbc" % ScalikejdbcVersion,
  "org.scalikejdbc" %% "scalikejdbc-config" % ScalikejdbcVersion,
  "org.scalikejdbc" %% "scalikejdbc-orm" % ScalikejdbcVersion,
  "org.scalikejdbc" %% "scalikejdbc-play-dbapi-adapter" % ScalikejdbcPlayVersion,
  "org.scalikejdbc" %% "scalikejdbc-test" % ScalikejdbcVersion % Test,
  "org.flywaydb" % "flyway-core" % FlywayVersion,
  "org.flywaydb" % "flyway-mysql" % FlywayVersion,
  "org.mariadb.jdbc" % "mariadb-java-client" % "3.5.3",
  // scalawiki-core still declares play-json 2.9.4 under the old `com.typesafe.play` group, which
  // Coursier keeps beside Play's `org.playframework` 3.0.x: the same 306 classes twice, 2.9.4
  // first on the classpath. Every member wlxjury, Play, Tapir and scalawiki call exists in 3.0.x
  // (runtime jars investigation, decision 4). scalawiki-wlx brings scala-chart (with scala-swing,
  // jfreechart and jcommon) for its stat-bot reports, which wlxjury never runs (decision 2).
  // Both exclusions go once scalawiki moves to play-json 3 and splits its stat module out.
  "org.scalawiki" %% "scalawiki-core" % ScalawikiVersion excludeAll (ScalawikiPlayJson2: _*),
  "org.scalawiki" %% "scalawiki-wlx" % ScalawikiVersion excludeAll (ScalawikiPlayJson2 ++ ScalawikiCharts: _*),
  "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
  "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
  "com.nimbusds" % "oauth2-oidc-sdk" % NimbusOAuthVersion,
  "org.playframework" %% "play-mailer" % PlayMailerVersion,
  "org.playframework" %% "play-mailer-guice" % PlayMailerVersion,
  // 2.0.0, the version scalawiki-wlx brings and the one that runs
  "com.github.tototoshi" %% "scala-csv" % "2.0.0",
  // ImageIO needs a WebP reader to resize Commons images for the thumbnail cache.
  "com.twelvemonkeys.imageio" % "imageio-webp" % "3.15.0",
  "uk.org.lidalia" % "sysout-over-slf4j" % "1.0.2",
  // Password hashes (services.PasswordHasher): bcrypt $2b$, cost from password.bcrypt.cost
  "at.favre.lib" % "bcrypt" % "0.10.2",
  "com.softwaremill.sttp.tapir" %% "tapir-core" % TapirVersion,
  // the REST API (api.ApiRouter) on Play's own server, through its filters; Play's Pekko
  // HTTP server stays out (the app runs on Netty)
  "com.softwaremill.sttp.tapir" %% "tapir-play-server" % TapirVersion excludeAll (
    ExclusionRule("org.playframework", "play-pekko-http-server_2.13")
  ),
  "com.softwaremill.sttp.tapir" %% "tapir-json-play" % TapirVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % TapirVersion,
  guice,
  filters,
  specs2 % Test,
  jdbc % Test,
  "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-scalatest" % TestcontainersScalaVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-mariadb" % TestcontainersScalaVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-mysql" % TestcontainersScalaVersion % Test,
  "io.chrisdavenport" %% "testcontainers-specs2" % "0.2.0-M5" % Test,
  "org.mock-server" % "mockserver-netty" % MockServerVersion % Test,
  "net.java.dev.jna" % "jna" % "4.5.0" % Test,
  "net.java.dev.jna" % "jna-platform" % "4.5.0" % Test,
  "com.lihaoyi" %% "os-lib" % "0.11.4" % Test,
  "org.apache.pekko" %% "pekko-stream-testkit" % PekkoVersion % Test,
  "org.scalameta" %% "munit" % MunitVersion % Test,
  "io.gatling.highcharts" % "gatling-charts-highcharts" % GatlingVersion % Test,
  "io.gatling" % "gatling-test-framework" % GatlingVersion % Test
)

// commons-dbcp2 (from scalikejdbc-core) brings commons-logging, which Play's jcl-over-slf4j
// already provides (and shadows on the classpath)
excludeDependencies += ExclusionRule("commons-logging", "commons-logging")
// play-mailer 10's jakarta.mail brings com.sun.activation:jakarta.activation 1.2.1: the
// javax.activation API (the same 31 classes) and the implementation MailcapCommandMap needs.
// jaxb-api's javax.activation-api 1.2.0 (from scalawiki-core) would be a second copy of the API.
excludeDependencies += ExclusionRule("javax.activation", "javax.activation-api")

dependencyOverrides ++= Seq(
  "commons-io" % "commons-io" % "2.16.1"
) ++ Seq("testcontainers", "jdbc", "database-commons", "mariadb", "mysql").map { module =>
  "org.testcontainers" % module % TestcontainersJavaVersion
}

routesGenerator := InjectedRoutesGenerator

//doc in Compile <<= target.map(_ / "none")

Test / javaOptions += "-Dconfig.file=test/resources/application.conf"
Test / javaOptions += "-Djna.nosys=true"
Test / fork := true
// Forward -Dintegration, -Ddocker.tests, -Dgenerate.baseline, -Dimport.* (ImportScaleSpec) and
// -Dwlxjury.test.mariadb-image (SharedTestDb.Image) to the forked test JVM
Test / javaOptions ++= Seq("integration", "docker.tests", "generate.baseline", "import.scale", "import.latencyMs", "import.pageSize", "wlxjury.test.mariadb-image").flatMap { key =>
  sys.props.get(key).map(v => s"-D$key=$v")
}

// All Gatling simulations in a fixed order: rounds are created and their images
// distributed before voting starts, so RoundDistribution runs first. Each simulation
// forks its own JVM and restores the fixture DB, so the order doesn't change results.
addCommandAlias(
  "gatlingAll",
  ";Gatling/testOnly gatling.simulations.RoundDistributionSimulation" +
    ";Gatling/testOnly gatling.simulations.ConcurrentRoundCreationSimulation" +
    ";Gatling/testOnly gatling.simulations.VotingSimulation" +
    ";Gatling/testOnly gatling.simulations.JurorGallerySimulation" +
    ";Gatling/testOnly gatling.simulations.RegionFilterSimulation" +
    ";Gatling/testOnly gatling.simulations.RoundManagementSimulation" +
    ";Gatling/testOnly gatling.simulations.AggregatedRatingsSimulation"
)
