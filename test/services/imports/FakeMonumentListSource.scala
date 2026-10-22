package services.imports

import org.scalawiki.wlx.dto.{Contest, Monument}
import play.api.libs.json.{JsObject, Json}

import java.util.concurrent.CopyOnWriteArrayList

/** Monument lists for specs: the list pages embedding each template, and the monuments of
  * each page. Records the pages it parsed, with the list template asked for.
  */
class FakeMonumentListSource(
    embedding: Map[String, Seq[String]] = Map.empty,
    monumentsOn: Map[String, Seq[Monument]] = Map.empty
) extends MonumentListSource {

  val parsed = new CopyOnWriteArrayList[(String, Option[String])]()

  /** Fails parsing this page, once. */
  @volatile var failOnPage: Option[String] = None

  def listPages(contest: Contest, template: String, cursor: JsObject, limit: Int): (Seq[String], Option[JsObject]) = {
    val pages = embedding.getOrElse(template, Nil).sorted
    val from = (cursor \ "offset").asOpt[Int].getOrElse(0)
    val next = from + limit
    (pages.slice(from, next), Option.when(next < pages.size)(Json.obj("offset" -> next)))
  }

  def monuments(contest: Contest, titles: Seq[String], listTemplate: Option[String]): Seq[Monument] =
    titles.flatMap { title =>
      if (failOnPage.contains(title)) {
        failOnPage = None
        throw new RuntimeException(s"Wikipedia is down ($title)")
      }
      parsed.add(title -> listTemplate)
      monumentsOn.getOrElse(title, Nil)
    }
}
