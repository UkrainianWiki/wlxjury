import sbt.Keys._

// Run Jest tests in javascript-test/ as part of `sbt test`.
lazy val jsTest = taskKey[Unit]("Run Jest tests in javascript-test/")

jsTest := {
  val jsDir = baseDirectory.value / "javascript-test"
  val result = scala.sys.process.Process(Seq("npm", "test", "--", "--no-coverage"), jsDir).!
  if (result != 0) throw new MessageOnlyException("Jest tests failed")
}

// Wire jsTest into the normal `sbt test` run.
Test / test := (Test / test).dependsOn(jsTest).value

lazy val root = identity(
  (project in file("."))
    .enablePlugins(
      PlayScala,
      PlayNettyServer,
      DebianPlugin,
      SystemdPlugin,
      JavaServerAppPackaging,
      JmhPlugin
    )
    .settings(
      Jmh / sourceDirectory     := (Test / sourceDirectory).value,
      Jmh / classDirectory      := (Test / classDirectory).value,
      Jmh / dependencyClasspath := (Test / dependencyClasspath).value
    )
)
  .disablePlugins(PlayPekkoHttpServer)

name := "wlxjury"

organization := "org.intracer"

version := "0.13"

scalaVersion := "2.13.15"

val ScalikejdbcVersion = "4.3.2"
val ScalikejdbcPlayVersion = "3.0.1-scalikejdbc-4.3"
val ScalawikiVersion = "0.7.0-SNAPSHOT"
val PlayMailerVersion = "9.0.0"
val MockServerVersion = "5.7.0"
val PlayPac4jVersion = "12.0.0-PLAY3.0"
val pac4jVersion = "6.0.4.1"
val playVersion = "3.0.7"
val PekkoVersion = "1.0.3"
val PekkoHttpVersion = "1.0.1"
val ScalaTestVersion = "3.2.9"
val TestcontainersScalaVersion = "0.41.0"
val TapirVersion = "1.11.15"
val MunitVersion = "0.7.29"

resolvers += Resolver.bintrayRepo("intracer", "maven")

resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

resolvers += Resolver.jcenterRepo

resolvers += Resolver.mavenLocal

libraryDependencySchemes += "org.scala-lang.modules" %% "scala-parser-combinators" % "always"

libraryDependencies ++= Seq(
  "org.webjars" %% "webjars-play" % "3.0.2",
  "com.adrianhurt" %% "play-bootstrap" % "1.6.1-P28-B3",
  "org.webjars" % "bootstrap" % "3.3.7-1" exclude ("org.webjars", "jquery"),
  "org.webjars" % "jquery" % "3.2.1",

  "org.scalikejdbc" %% "scalikejdbc" % ScalikejdbcVersion,
  "org.scalikejdbc" %% "scalikejdbc-config" % ScalikejdbcVersion,
  "org.scalikejdbc" %% "scalikejdbc-orm" % ScalikejdbcVersion,
  "org.scalikejdbc" %% "scalikejdbc-play-initializer" % ScalikejdbcPlayVersion,
  "org.scalikejdbc" %% "scalikejdbc-play-dbapi-adapter" % ScalikejdbcPlayVersion,
  "org.scalikejdbc" %% "scalikejdbc-play-fixture" % ScalikejdbcPlayVersion,
  "org.flywaydb" %% "flyway-play" % "8.0.1",
  "org.flywaydb" % "flyway-mysql" % "9.16.3",
  "org.mariadb.jdbc" % "mariadb-java-client" % "3.5.3",
  "org.scalawiki" %% "scalawiki-core" % ScalawikiVersion,
  "org.scalawiki" %% "scalawiki-wlx" % ScalawikiVersion,

  "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
  "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
  "org.pac4j" %% "play-pac4j" % PlayPac4jVersion,
  "com.typesafe.play" %% "play-mailer" % PlayMailerVersion,
  "com.typesafe.play" %% "play-mailer-guice" % PlayMailerVersion,
  "com.github.tototoshi" %% "scala-csv" % "1.4.1",
  "uk.org.lidalia" % "sysout-over-slf4j" % "1.0.2",
  "javax.xml.bind" % "jaxb-api" % "2.3.1",
  "com.softwaremill.sttp.tapir" %% "tapir-core" % TapirVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-pekko-http-server" % TapirVersion excludeAll(
    ExclusionRule("org.apache.pekko", "pekko-stream_2.13"),
    ExclusionRule("org.apache.pekko", "pekko-slf4j_2.13"),
    ExclusionRule("org.apache.pekko", "pekko-http_2.13"),
    ExclusionRule("org.apache.pekko", "pekko-http-caching_2.13")
  ),
  "com.softwaremill.sttp.tapir" %% "tapir-json-play" % TapirVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % TapirVersion,
  "com.github.pjfanning" %% "pekko-http-play-json" % "2.0.0",
  guice,
  filters,
  specs2 % Test,
  jdbc % Test,
  "ch.vorburger.mariaDB4j" % "mariaDB4j" % "3.1.0" % Test,
  "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-scalatest" % TestcontainersScalaVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-mariadb" % "0.41.0" % Test,
  "com.dimafeng" %% "testcontainers-scala-mysql" % "0.41.0" % Test,
  "io.chrisdavenport" %% "testcontainers-specs2" % "0.2.0-M5" % Test,
  "org.mock-server" % "mockserver-netty" % MockServerVersion % Test,
  "net.java.dev.jna" % "jna" % "4.5.0" % Test,
  "net.java.dev.jna" % "jna-platform" % "4.5.0" % Test,
  "com.lihaoyi" %% "os-lib" % "0.11.4" % Test,
  "org.apache.pekko" %% "pekko-http-testkit" % PekkoHttpVersion % Test,
  "org.apache.pekko" %% "pekko-stream-testkit" % PekkoVersion % Test,
  "org.scalameta" %% "munit" % MunitVersion % Test
)

dependencyOverrides ++= Seq(
  "commons-io" % "commons-io" % "2.16.1"
)

routesGenerator := InjectedRoutesGenerator

//doc in Compile <<= target.map(_ / "none")

Test / javaOptions += "-Dconfig.file=test/resources/application.conf"
Test / javaOptions += "-Djna.nosys=true"
Test / fork := true
// Forward -Dintegration, -Ddocker.tests, and -Dgenerate.baseline to the forked test JVM
Test / javaOptions ++= Seq("integration", "docker.tests", "generate.baseline").flatMap { key =>
  sys.props.get(key).map(v => s"-D$key=$v")
}

//rpmRelease := "1"

rpmRequirements ++= Seq("java-1.8.0-openjdk", "bash")

rpmVendor := "intracer"

rpmUrl := Some("https://github.com/intracer/wlxjury")

rpmLicense := Some("ASL 2.0")

packageSummary := "WLX Jury Tool is an image selection and rating tool for Wiki Loves Monuments and Wiki Loves Earth contests"

packageDescription :=
  """On gallery page jurors or organizing committee can browse the images, see the rating or selection status of each image, open large image view page.
    |Images can be filtered by their status - unrated, and selected or rejected in selection rounds or rated in rating rounds.
    |In rating rounds images are sorted by their rating.
    |Organizing committee can see the votes of each juror separately or the overall rating of all jurors together.
    |In large image view there is vertical ribbon of image thumbnails on the left and large currently viewed image on the right.
    |User can go backward or forward with navigation buttons or arrow keys on the keyboard, or can click the thumbnails in the ribbon
    |Juror can select, reject or rate the currently viewed image.
    |Juror can also comment the image and see other jurors comments.
    |Organizing committee can see the ratings and comments given by all jurors to the image.
    |From large image view one can return to gallery view, visit image page on commons by clicking the large image, or open full resolution version of the image. Caption of the full resolution image version link shows image resolution.
  """.stripMargin.replace('\n', ' ')

maintainer := "Ilya Korniiko <intracer@gmail.com>"

Debian / debianPackageRecommends ++= Seq("virtual-mysql-server")

addCommandAlias(
  "packageAll",
  "; clean" +
    "; packageDebSystemV" +
    "; clean " +
    "; packageDebUpstart" +
    "; clean " +
    "; packageDebSystemd" +
    "; clean " +
    "; packageRpmSystemV" +
    "; clean " +
    "; packageRpmUpstart" +
    "; clean " +
    "; packageRpmSystemd"
)

addCommandAlias(
  "packageDebSystemV",
  "; set serverLoading in Debian := Some(com.typesafe.sbt.packager.archetypes.systemloader.ServerLoader.SystemV)" +
    "; internalPackageDebianSystemV"
)

addCommandAlias(
  "packageDebUpstart",
  "; set serverLoading in Debian := Some(com.typesafe.sbt.packager.archetypes.systemloader.ServerLoader.Upstart)" +
    "; internalPackageDebianUpstart"
)

addCommandAlias(
  "packageDebSystemd",
  "; set serverLoading in Debian := Some(com.typesafe.sbt.packager.archetypes.systemloader.ServerLoader.Systemd)" +
    "; internalPackageDebianSystemd"
)

addCommandAlias(
  "packageRpmSystemV",
  "; set serverLoading in Rpm := Some(com.typesafe.sbt.packager.archetypes.systemloader.ServerLoader.SystemV)" +
    "; internalPackageRpmSystemV"
)

addCommandAlias(
  "packageRpmUpstart",
  "; set serverLoading in Rpm := Some(com.typesafe.sbt.packager.archetypes.systemloader.ServerLoader.Upstart)" +
    "; internalPackageRpmUpstart"
)

addCommandAlias(
  "packageRpmSystemd",
  "; set serverLoading in Rpm := Some(com.typesafe.sbt.packager.archetypes.systemloader.ServerLoader.Systemd)" +
    "; internalPackageRpmSystemd"
)

lazy val internalPackageDebianSystemV =
  taskKey[File]("creates debian package with systemv")
lazy val internalPackageDebianUpstart =
  taskKey[File]("creates debian package with upstart")
lazy val internalPackageDebianSystemd =
  taskKey[File]("creates debian package with systemd")

lazy val internalPackageRpmSystemV =
  taskKey[File]("creates rpm package with systemv")
lazy val internalPackageRpmUpstart =
  taskKey[File]("creates rpm package with upstart")
lazy val internalPackageRpmSystemd =
  taskKey[File]("creates rpm package with systemd")

internalPackageDebianSystemV := {
  val output = baseDirectory.value / "package" / s"wlxjury-systemv-${version.value}.deb"
  val debianFile = (Debian / packageBin).value
  IO.move(debianFile, output)
  output
}

internalPackageDebianUpstart := {
  val output = baseDirectory.value / "package" / s"wlxjury-upstart-${version.value}.deb"
  val debianFile = (Debian / packageBin).value
  IO.move(debianFile, output)
  output
}

internalPackageDebianSystemd := {
  val output = baseDirectory.value / "package" / s"wlxjury-systemd-${version.value}.deb"
  val debianFile = (Debian / packageBin).value
  IO.move(debianFile, output)
  output
}

internalPackageRpmSystemV := {
  val output = baseDirectory.value / "package" / s"wlxjury-systemv-${version.value}.rpm"
  val rpmFile = (Rpm / packageBin).value
  IO.move(rpmFile, output)
  output
}

internalPackageRpmUpstart := {
  val output = baseDirectory.value / "package" / s"wlxjury-upstart-${version.value}.rpm"
  val rpmFile = (Rpm / packageBin).value
  IO.move(rpmFile, output)
  output
}

internalPackageRpmSystemd := {
  val output = baseDirectory.value / "package" / s"wlxjury-systemd-${version.value}.rpm"
  val rpmFile = (Rpm / packageBin).value
  IO.move(rpmFile, output)
  output
}
