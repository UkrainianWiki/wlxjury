package services.imports

import org.scalawiki.MwBot
import org.scalawiki.dto.Namespace
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.cmd.query.list.ListArgs
import org.scalawiki.dto.cmd.query.{Generator, Query}
import org.scalawiki.wlx.dto.{Contest, Monument}
import org.scalawiki.wlx.query.MonumentQuery
import play.api.libs.json.JsObject

import javax.inject.Singleton
import scala.collection.concurrent.TrieMap
import scala.concurrent.Await

/** The monument lists on Wikipedia, one bounded request at a time: what a step of a
  * [[MonumentListImportHandler]] job fetches. Blocking: called on the job scheduler's
  * fetch lane.
  */
trait MonumentListSource {

  /** One page of at most `limit` titles of the list pages that embed `template` (in the
    * main and project namespaces), from `cursor` (`{}`: the first), and the next cursor.
    */
  def listPages(contest: Contest, template: String, cursor: JsObject, limit: Int): (Seq[String], Option[JsObject])

  /** The monuments on the list pages, parsed with the contest's list template, or with
    * `listTemplate` (a special nomination's).
    */
  def monuments(contest: Contest, titles: Seq[String], listTemplate: Option[String]): Seq[Monument]
}

/** [[MonumentListSource]] over the API of the contest's lists wiki. */
@Singleton
class MwMonumentListSource extends MonumentListSource {

  private val bots = TrieMap.empty[String, MwBot]

  private def bot(contest: Contest): MwBot = {
    val host = contest.listsHost.getOrElse(MwBot.ukWiki)
    bots.getOrElseUpdate(host, MwBot.fromHost(host))
  }

  def listPages(contest: Contest, template: String, cursor: JsObject, limit: Int): (Seq[String], Option[JsObject]) = {
    val namespaces = Set(Namespace.PROJECT, Namespace.MAIN)
    val generator = ListArgs.toDsl("embeddedin", Some(template), None, namespaces, Some(limit.toString)).get
    val (pages, next) = new MwApi(bot(contest)).page(Action(Query(Generator(generator))), cursor)
    (pages.map(_.title).sorted, next)
  }

  def monuments(contest: Contest, titles: Seq[String], listTemplate: Option[String]): Seq[Monument] =
    if (titles.isEmpty) Nil
    else {
      val b = bot(contest)
      new MwApi(b).withRetries(s"monument lists ${titles.mkString(", ")}") {
        val query = MonumentQuery.create(contest)(b)
        Await.result(query.monumentsByPages(titles.toSet, listTemplate), MwApi.RequestTimeout).flatMap(_.monuments)
      }
    }
}
