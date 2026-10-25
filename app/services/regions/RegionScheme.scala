package services.regions

import org.intracer.wmua.{ContestJury, Region}
import org.scalawiki.wlx.dto.{AdmDivision, Country}
import play.api.i18n.Messages

import scala.collection.concurrent.TrieMap

/** How a contest names the regions of its files' monument ids (their prefixes,
  * [[db.scalikejdbc.rewrite.ImageDbNew.regionOf]]): by its country's regions, KOATUU's for
  * Ukraine, WLM's and WLE's alike.
  *
  * @param country the contest's country ([[ContestKind]]); without one a region is named by its id
  */
final case class RegionScheme(country: Option[Country]) {

  private def ukrainian: Boolean = country.exists(_.code.equalsIgnoreCase(Country.Ukraine.code))

  /** The country's region of that id: a top-level one by its code (`07`), a sub-region by
    * its monument id prefix (`07-101`).
    */
  private def geographic(id: String): Option[AdmDivision] =
    country.flatMap { c =>
      if (id.contains("-")) c.byMonumentId(id)
      else c.regionById.get(id)
    }

  /** The region's name: for Ukraine the messages' (`07=Volyn O.`) if there is one, else the
    * country's region's; "Unknown" for an id that is no region of the country; the id
    * itself for a contest of no known country, or of one whose regions scalawiki doesn't have.
    */
  def name(id: String)(implicit messages: Messages): String =
    if (ukrainian && messages.isDefinedAt(id)) messages(id)
    else if (country.forall(_.regions.isEmpty)) id
    else geographic(id).map(_.name).getOrElse("Unknown")

  /** The regions, by id, with their names and file counts if known, in id order; with
    * `subRegions` (adm1 ids), Kyiv's sub-regions as one region, in name order.
    */
  def regions(byRegion: Map[String, Option[Int]], subRegions: Boolean = false)(implicit
      messages: Messages
  ): Seq[Region] = {
    val regions = byRegion.keys
      .filterNot(_ == null)
      .map(id => Region(id, name(id), byRegion(id)))
      .toSeq
      .sortBy(_.id)

    if (subRegions) {
      val kyivPictures = regions.filter(_.id.startsWith("80-")).map(_.count.getOrElse(0)).sum
      val withoutKyivRegions = regions.filterNot(_.id.startsWith("80-"))
      (withoutKyivRegions :+ Region("80", name("80"), Some(kyivPictures))).sortBy(_.name)
    } else regions
  }
}

object RegionScheme {

  /** Ukraine's regions, whatever the contest. */
  val Ukraine: RegionScheme = RegionScheme(Some(Country.Ukraine))

  private val schemes = TrieMap.empty[(Option[String], String, String), RegionScheme]

  /** The contest's scheme: its campaign's country ([[ContestKind]]), else the country it
    * names ("Ukraine", "ua"), whatever the contest's name.
    */
  def of(contest: ContestJury): RegionScheme =
    schemes.getOrElseUpdate(
      (contest.campaign, contest.name, contest.country),
      RegionScheme(ContestKind.of(contest).map(_.country).filter(_.code.nonEmpty).orElse(ContestKind.country(contest.country)))
    )

  /** Forgets the memoised schemes: for tests. */
  def clear(): Unit = schemes.clear()
}
