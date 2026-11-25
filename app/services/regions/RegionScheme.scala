package services.regions

import org.intracer.wmua.{ContestJury, Region}
import org.scalawiki.wlx.dto.{AdmDivision, Country, SpecialNomination}
import play.api.i18n.Messages

import java.text.Collator
import java.util.Locale
import scala.collection.concurrent.TrieMap

/** How a contest names the regions of its files' monument ids (their prefixes,
  * [[db.scalikejdbc.rewrite.ImageDbNew.regionOf]]): by its country's regions, KOATUU's for
  * Ukraine, WLM's and WLE's alike; by its special nominations for the prefixes their lists
  * give monuments of their own (`99-141-2306`: Jewish heritage); "Other (xx)" for any other.
  *
  * @param country          the contest's country ([[ContestKind]]); without one a region is named by its id
  * @param byPrefix         the nominations of the contest's year, by id prefix
  * @param fallbackByPrefix the campaign's nominations of any year, by id prefix: a prefix
  *                         belongs to its lists, which files of other years cite too
  */
final case class RegionScheme(
    country: Option[Country],
    byPrefix: Map[String, SpecialNomination] = Map.empty,
    fallbackByPrefix: Map[String, SpecialNomination] = Map.empty
) {

  private def ukrainian: Boolean = country.exists(_.code.equalsIgnoreCase(Country.Ukraine.code))

  /** The country's region of that id: a top-level one by its code (`07`), a sub-region by
    * its monument id prefix (`07-101`).
    */
  private def geographic(id: String): Option[AdmDivision] =
    country.flatMap { c =>
      if (id.contains("-")) c.byMonumentId(id)
      else c.regionById.get(id)
    }

  /** The region of that id: for Ukraine named by the messages (`07=Volyn O.`) if they have
    * it, else by the country's region; else a nomination's, by its name (translated if the
    * messages have it); else "Other (xx)". For a contest of no known country, or of one whose
    * regions scalawiki doesn't have, the id itself.
    */
  def region(id: String, count: Option[Int])(implicit messages: Messages): Region =
    if (ukrainian && messages.isDefinedAt(id)) Region(id, messages(id), count)
    else if (country.forall(_.regions.isEmpty)) Region(id, id, count, Region.Other)
    else
      geographic(id) match {
        case Some(adm) => Region(id, adm.name, count)
        case None =>
          byPrefix.get(id).orElse(fallbackByPrefix.get(id)) match {
            case Some(n) =>
              Region(id, if (messages.isDefinedAt(n.name)) messages(n.name) else n.name, count, Region.Nomination)
            case None => Region(id, messages("region.other", id), count, Region.Other)
          }
      }

  /** The region a file's monument card names for its monument id ([[views.html.large.monumentInfo]]):
    * for an id a special nomination gives its own monuments (`92-232-4801`), the nomination's
    * name ([[region]]) and, where the id's second part starts with one of the country's region
    * codes (its `23`), that region as [[region]] names it: "Болгарська спадщина, Запорізька".
    * For any other id its prefix as the messages have it (the card's text before
    * nominations were named), so other regions and other countries read as they did.
    */
  def monumentRegion(monumentId: String)(implicit messages: Messages): String = {
    val parts  = monumentId.trim.split("-")
    val prefix = parts.head
    val named  = region(prefix, None)
    if (named.kind != Region.Nomination) messages(prefix)
    else {
      val geographicRegion = parts.lift(1).filter(_.matches("[0-9]{3}")).map(_.take(2))
        .map(region(_, None)).filter(_.kind == Region.Geographic).map(_.name)
      (named.name +: geographicRegion.toSeq).mkString(", ")
    }
  }

  /** The regions, by id, with their names and file counts if known: the country's regions
    * in id order (with `subRegions`, adm1 ids, Kyiv's sub-regions as one region, in name
    * order), then the nominations in Ukrainian alphabetical order, then the others by id.
    */
  def regions(byRegion: Map[String, Option[Int]], subRegions: Boolean = false)(implicit
      messages: Messages
  ): Seq[Region] = {
    val all = byRegion.keys.filterNot(_ == null).map(id => region(id, byRegion(id))).toSeq.sortBy(_.id)
    val (geographic, rest) = all.partition(_.kind == Region.Geographic)
    val (nominations, other) = rest.partition(_.kind == Region.Nomination)

    val regions =
      if (subRegions && ukrainian) {
        val kyiv = geographic.filter(r => r.id == "80" || r.id.startsWith("80-"))
        val kyivCount = Option.when(kyiv.forall(_.count.isDefined))(kyiv.flatMap(_.count).sum)
        val withoutKyivRegions = geographic.filterNot(kyiv.contains)
        (withoutKyivRegions ++ Option.when(kyiv.nonEmpty)(region("80", kyivCount))).sortBy(_.name)
      } else geographic

    val collator = Collator.getInstance(Locale.forLanguageTag("uk"))
    regions ++ nominations.sortWith((a, b) => collator.compare(a.name, b.name) < 0) ++ other
  }
}

object RegionScheme {

  /** Ukraine's regions, whatever the contest, without nominations. */
  val Ukraine: RegionScheme = RegionScheme(Some(Country.Ukraine))

  private val schemes = TrieMap.empty[(Option[String], String, String, Int), RegionScheme]

  /** The contest's scheme: its campaign's country ([[ContestKind]]), else the country it
    * names ("Ukraine", "ua"), whatever the contest's name; its campaign's nominations.
    */
  def of(contest: ContestJury): RegionScheme =
    schemes.getOrElseUpdate(
      (contest.campaign, contest.name, contest.country, contest.year), {
        val nominations = ContestKind.nominations(contest)
        RegionScheme(
          ContestKind.of(contest).map(_.country).filter(_.code.nonEmpty).orElse(ContestKind.country(contest.country)),
          SpecialNomination.byIdPrefix(nominations, contest.year),
          SpecialNomination.byIdPrefix(nominations)
        )
      }
    )

  /** Forgets the memoised schemes: for tests. */
  def clear(): Unit = schemes.clear()
}
