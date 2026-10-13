package wlxjury.build

/** The release identity written by `deploymentManifest`.
  *
  * Pure, so the app's tests compile this same file (build.sbt adds it to Test
  * sources) and check the rules the build applies.
  */
object BuildIdentity {
  private val Valid = "[A-Za-z0-9][A-Za-z0-9._-]{0,95}"
  private val Migration = "V([0-9]+(?:[._][0-9]+)*)__.+\\.sql".r

  /** Inputs that end up in a release, for detecting tracked and untracked changes. */
  val SourcePaths: Seq[String] = Seq("app", "conf", "public", "project", "build.sbt")

  /** @param commit    short HEAD hash, or None without git
    * @param dirty     any tracked or untracked change under [[SourcePaths]]
    * @param requested `-Dwlxjury.buildId`, the name of a reviewed release build
    */
  def buildId(version: String, commit: Option[String], dirty: Boolean, requested: Option[String]): Either[String, String] =
    requested match {
      case Some(id) if dirty =>
        Left(s"Refusing release build ID $id: app, conf, public, project or build.sbt has uncommitted or untracked changes")
      case Some(id) =>
        commit match {
          case None => Left(s"Release build ID $id needs a git commit to identify the reviewed source")
          case Some(hash) => valid(if (id.contains(hash)) id else s"$id-$hash")
        }
      case None =>
        valid(s"$version-${commit.getOrElse("development")}${if (dirty) "-dirty" else ""}")
    }

  private def valid(id: String): Either[String, String] =
    if (id.matches(Valid)) Right(id) else Left(s"Invalid build ID $id")

  /** `git status --porcelain` lists changed and untracked files, unlike `git diff`. */
  def isDirty(porcelainStatus: String): Boolean = porcelainStatus.linesIterator.exists(_.trim.nonEmpty)

  /** The numerically latest versioned migration, as Flyway records it (`V55_1` is 55.1). */
  def latestMigration(fileNames: Seq[String]): String = {
    val versions = fileNames.collect { case Migration(v) => v.replace('_', '.') }
    require(versions.nonEmpty, "No versioned migrations found")
    versions.reduce((a, b) => if (precedes(a, b)) b else a)
  }

  // Compiled by sbt's Scala 2.12 and the app's 2.13, so no collection-ordering helpers.
  private def precedes(a: String, b: String): Boolean = {
    val (x, y) = (a.split('.').map(_.toInt).toList, b.split('.').map(_.toInt).toList)
    x.zipAll(y, -1, -1).find { case (p, q) => p != q }.exists { case (p, q) => p < q }
  }

  def manifest(id: String, version: String, schemaVersion: String): String =
    s"""{"build":"$id","version":"$version","schemaVersion":"$schemaVersion"}
"""
}
