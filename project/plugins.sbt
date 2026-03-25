// logLevel := Level.Warn

resolvers ++= Seq(
  Resolver.file("file", new File(Path.userHome.absolutePath + "/.ivy2/local/"))
)
resolvers += Classpaths.sbtPluginReleases

// Use the Play sbt plugin for Play projects
addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.7")

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.0")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.1.0")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.3.11")

addDependencyTreePlugin

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")

addSbtPlugin("io.gatling" % "gatling-sbt" % "4.2.4")