package services

import play.api.Configuration

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import javax.inject.Inject

/** Pages on Commons that the tool links to, from the settings. */
class CommonsLinks @Inject() (configuration: Configuration) {

  /** The page whose text pre-fills the "please enable wiki email" message that an organizer
    * leaves on a juror's talk page (`wlxjury.commons.enableEmailPreload`, env
    * `WLXJURY_COMMONS_ENABLE_EMAIL_PRELOAD`); by default a subpage of the tool's Commons page.
    */
  val enableEmailPreload: String =
    configuration.getOptional[String]("wlxjury.commons.enableEmailPreload").map(_.trim).filter(_.nonEmpty)
      .getOrElse(CommonsLinks.DefaultEnableEmailPreload)

  /** A new section on the juror's talk page with that text; `encodedUserName` is already
    * URL-encoded ([[views.WikiLinks.encodedUserName]]).
    */
  def enableEmailUrl(encodedUserName: String): String =
    s"https://commons.wikimedia.org/w/index.php?title=User_talk:$encodedUserName&action=edit&section=new" +
      s"&preload=${CommonsLinks.encodedTitle(enableEmailPreload)}&preloadtitle=Please%20enable%20wiki%20email"
}

object CommonsLinks {
  val DefaultEnableEmailPreload = "Commons:WLX Jury Tool/enableEmailText"

  /** The defaults, for code paths without a Play application (specs, the users page's default). */
  val default = new CommonsLinks(Configuration.empty)

  /** A page title as a query value: spaces as `_`, everything that means something in a URL
    * encoded (`:` and `/` included, which MediaWiki decodes).
    */
  def encodedTitle(title: String): String = URLEncoder.encode(title.trim.replace(' ', '_'), UTF_8)
}
