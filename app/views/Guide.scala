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

  /** A release's version, a tag: numbers and dots only (`26.11.17.1`); a snapshot has no tag. */
  private val Release = """\d+(\.\d+)+""".r

  /** A build identity's version (`wlxjury-build.json`'s `version`), if it is a release's: none for
    * a snapshot (`26.11.18-SNAPSHOT`, a build of master between releases), whose tag doesn't exist,
    * so its links go to the default branch.
    */
  def versionOf(manifest: String): Option[String] =
    Try((Json.parse(manifest) \ "version").as[String]).toOption.map(_.trim).filter(Release.matches)

  /** The build's version, the release tag (`26.11.17.1`), or [[Fallback]] for a snapshot. */
  lazy val version: String = Try {
    val stream = getClass.getClassLoader.getResourceAsStream("wlxjury-build.json")
    try new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally stream.close()
  }.toOption.flatMap(versionOf).getOrElse(Fallback)

  /** The user guide's page `page` (a file of docs/user) at `anchor` (an `<a id>` in it; empty: the
    * page's top), as of `version`. The help links' URLs ([[HelpLinks]]).
    */
  def url(page: String, anchor: String, version: String = version): String =
    s"$Repository/blob/$version/docs/user/$page" + (if (anchor.isEmpty) "" else s"#$anchor")

  /** The admin guide's contents page at `anchor` (an `<a id>` in docs/user/admin-guide.md), as of `version`. */
  def admin(anchor: String, version: String = version): String = url("admin-guide.md", anchor, version)
}
