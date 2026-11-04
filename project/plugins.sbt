// logLevel := Level.Warn

resolvers ++= Seq(
  Resolver.file("file", new File(Path.userHome.absolutePath + "/.ivy2/local/"))
)
resolvers += Classpaths.sbtPluginReleases

// Use the Play sbt plugin for Play projects
addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.12")

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.0")

// Fingerprints packaged assets (pipelineStages in build.sbt)
addSbtPlugin("com.github.sbt" % "sbt-digest" % "2.1.0")

// Coverage (CI job "coverage", docs/plans/2026-11-03-ci-and-scalawiki-packages.md). scoverage's
// compiler plugin is published per full Scala version, so it moves with scalaVersion: 2.13.17
// needs sbt-scoverage 2.4.x (its plugin 2.5.x has a 2.13.17 build; 2.3.1's plugin has none).
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.3.15")

addDependencyTreePlugin

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")

addSbtPlugin("io.gatling" % "gatling-sbt" % "4.2.4")