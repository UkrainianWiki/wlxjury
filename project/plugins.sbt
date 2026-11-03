// logLevel := Level.Warn

resolvers ++= Seq(
  Resolver.file("file", new File(Path.userHome.absolutePath + "/.ivy2/local/"))
)
resolvers += Classpaths.sbtPluginReleases

// Use the Play sbt plugin for Play projects
addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.7")

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.0")

// Fingerprints packaged assets (pipelineStages in build.sbt); 2.0.0 is the release built for sbt 1.9
addSbtPlugin("com.github.sbt" % "sbt-digest" % "2.0.0")

// Coverage (CI job "coverage", docs/plans/2026-11-03-ci-and-scalawiki-packages.md). 2.3.1 is the
// newest whose compiler plugin (scalac-scoverage-plugin 2.3.0) is published for Scala 2.13.15:
// 2.4.x needs 2.13.16+, and 2.1.0's plugin 2.1.1 has no 2.13.15 build at all.
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.3.1")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.3.11")

addDependencyTreePlugin

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")

addSbtPlugin("io.gatling" % "gatling-sbt" % "4.2.4")