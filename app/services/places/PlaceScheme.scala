package services.places

import db.scalikejdbc.ImageJdbc
import org.intracer.wmua.ContestJury
import play.api.i18n.Messages
import services.imports.ContestCountries
import services.regions.{ContestKind, RegionScheme}

import java.text.Collator
import java.util.Locale

/** The one level of places a contest's files are filtered and counted by (the maintainer's
  * decision of 2026-11-16: one shared place filter, no drill-down): a national contest's (one with
  * a monument id template) by **regions**, the prefixes of their monument ids ([[RegionScheme]]:
  * Ukraine's KOATUU regions for now); an international contest's (no template, of many countries,
  * [[ContestCountries.ofManyCountries]], with a category) by **countries**, its files' country
  * categories. A contest of neither has none.
  *
  * Every page with a place menu (the contest files page, a round's review page and its jurors
  * form, a round's gallery, by-rate gallery and file list) takes its places from here: which
  * kind ([[PlaceScheme.of]]), their names and order ([[PlaceScheme.places]]), and for countries
  * the one way a list's files are counted by country ([[PlaceScheme.Countries.counts]]); regions
  * are counted by each list's own region query (`regionOf` of the monument id), as before.
  */
sealed trait PlaceScheme {

  def contest: ContestJury

  /** The places of these ids with their files' counts, if counted, named and in the menu's
    * order: regions as [[RegionScheme.regions]] orders them (geographic, nominations, others),
    * countries by name.
    */
  def places(counts: Map[String, Option[Int]], subRegions: Boolean = false)(implicit messages: Messages): Seq[Place]
}

/** A place of a contest ([[PlaceScheme]]): its id (a region's id, a country's name), its name
  * as shown, its files' count if counted, and its kind (`Geographic`, `Nomination`, `Other`,
  * `Country`), which the menu groups by (region-menu.js).
  */
final case class Place(id: String, name: String, count: Option[Int], kind: String)

object PlaceScheme {

  /** The kind of a country, as the menu's JSON names it. */
  val CountryKind = "Country"

  /** A national contest's regions: its files' monument id prefixes ([[RegionScheme.of]]). */
  final case class Regions(contest: ContestJury) extends PlaceScheme {

    def scheme: RegionScheme = RegionScheme.of(contest)

    def places(counts: Map[String, Option[Int]], subRegions: Boolean)(implicit messages: Messages): Seq[Place] =
      scheme.regions(counts, subRegions).map(r => Place(r.id, r.name, r.count, r.kind.toString))
  }

  /** An international contest's countries: each file's country, by page id
    * ([[ContestCountries.fileCountries]], from its links to the contest's country categories).
    */
  final case class Countries(contest: ContestJury, byFile: Map[Long, String]) extends PlaceScheme {

    /** Whether any of the contest's files is of the country. */
    def has(country: String): Boolean = byFile.valuesIterator.contains(country)

    /** The contest's files of the country, by page id, in page id order. */
    def filesOf(country: String): Seq[Long] = byFile.collect { case (pageId, `country`) => pageId }.toSeq.sorted

    /** The one way a list's files are counted by country, on every page: of these files (a
      * list's page ids, each once), the number of each country's; a file of no country is not
      * counted.
      */
    def counts(pageIds: Iterable[Long]): Map[String, Int] =
      pageIds.iterator.flatMap(byFile.get).foldLeft(Map.empty[String, Int])((m, c) => m.updated(c, m.getOrElse(c, 0) + 1))

    /** The countries of these ids by name (the root locale's collation, as the contest files
      * page has always sorted them).
      */
    def places(counts: Map[String, Option[Int]], subRegions: Boolean)(implicit messages: Messages): Seq[Place] = {
      val collator = Collator.getInstance(Locale.ROOT)
      counts.toSeq.sortWith((a, b) => collator.compare(a._1, b._1) < 0)
        .map { case (name, n) => Place(name, name, n, CountryKind) }
    }
  }

  /** Whether the contest's files are by regions: a contest of monuments (a monument id template,
    * its own or its campaign's), whose ids' prefixes are its regions. Every such contest is
    * national, also one whose free-text country names no one country ("Ukraine, Animals",
    * "Andorra & Catalan areas"), and no international contest has a template (production's
    * contests, 2026-11-16): the template decides first (no database read).
    */
  def byRegions(contest: ContestJury): Boolean = ContestKind.monumentIdTemplate(contest).isDefined

  /** Whether the contest's files are by countries: no monument id template, of many countries
    * ([[ContestCountries.ofManyCountries]]), with a category whose files' country categories give
    * them (no database read).
    */
  def byCountries(contest: ContestJury): Boolean =
    !byRegions(contest) && contest.categoryId.isDefined && ContestCountries.ofManyCountries(contest)

  /** The contest's place scheme, its countries read (one query of its files' links to its
    * country categories); None for a contest of neither.
    */
  def of(contest: ContestJury): Option[PlaceScheme] =
    if (byCountries(contest)) countries(contest)
    else Option.when(byRegions(contest))(Regions(contest))

  /** The countries of an international contest's files ([[byCountries]]), cached per category
    * (db.scalikejdbc.CountryFilesCache, invalidated by imports); None for any other contest.
    */
  def countries(contest: ContestJury): Option[Countries] =
    for {
      categoryId <- contest.categoryId.filter(_ => byCountries(contest))
    } yield {
      val prefixes = ContestCountries.prefixes(contest)
      Countries(contest, db.scalikejdbc.CountryFilesCache.countries(categoryId, prefixes)(
        ContestCountries.fileCountries(contest, ImageJdbc.categoryCountryLinks(categoryId, prefixes))))
    }
}
