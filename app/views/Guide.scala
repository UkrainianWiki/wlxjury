package views

import play.api.libs.json.Json

import scala.util.Try

/** Links to the user guides. They aren't served by the tool: the only public copy is GitHub's view
  * of the repository, so a link points at the guide as it was at this build's version tag (its
  * text matches the running tool), from the build's identity (`wlxjury-build.json`, build.sbt's
  * `version`). Without it (never in a build): the default branch.
  */
object Guide {

  val Repository = "https://github.com/UkrainianWiki/wlxjury"

  /** The branch a link falls back to without a build identity. */
  val Fallback = "master"

  /** A build identity's version (`wlxjury-build.json`'s `version`), if it has one. */
  def versionOf(manifest: String): Option[String] =
    Try((Json.parse(manifest) \ "version").as[String]).toOption.map(_.trim).filter(_.nonEmpty)

  /** The build's version, the release tag (`26.11.17.1`). */
  lazy val version: String = Try {
    val stream = getClass.getClassLoader.getResourceAsStream("wlxjury-build.json")
    try new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally stream.close()
  }.toOption.flatMap(versionOf).getOrElse(Fallback)

  /** The admin guide at `anchor` (an `<a id>` in docs/user/admin-guide.md), as of `version`. */
  def admin(anchor: String, version: String = version): String =
    s"$Repository/blob/$version/docs/user/admin-guide.md#$anchor"
}
