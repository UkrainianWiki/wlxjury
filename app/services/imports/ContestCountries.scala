package services.imports

import org.intracer.wmua.ContestJury
import services.regions.ContestKind

/** A WLx contest's country categories on Commons, which its upload campaigns put every file
  * in: `Category:Images from Wiki Loves Earth 2026 in Armenia`, else, as some countries name
  * theirs, `Category:Wiki Loves Earth 2026 in Tanzania`. A file's country is the contest's
  * country category it is in, whatever the page or list it was imported from (a file sent in
  * privately is in none of a winners page's sections). An import keeps it as the file's
  * link to that category (`category_members`), as Commons has it.
  *
  * A file may be in several: a country's and others named after it (`… in Serbia - detail`,
  * `… in Bangladesh under assessment`), whose names overlap, of which the country's is the
  * shortest; or a country's and its parts' (`… in the United Kingdom`, `… in England`),
  * which the import tells apart by Commons' parent categories ([[choose]]).
  */
object ContestCountries {

  /** Whether the contest is of no one country (the international one): its files are of
    * many, as [[services.regions.RegionScheme.of]] finds a contest's country.
    */
  def ofManyCountries(contest: ContestJury): Boolean =
    ContestKind.of(contest).map(_.country).filter(_.code.nonEmpty).orElse(ContestKind.country(contest.country)).isEmpty

  /** The contest's category of all its countries' files. */
  def root(contest: ContestJury): String = s"Category:Images from ${contest.name.trim} ${contest.year}"

  /** What the titles of the contest's country categories start with, the upload campaigns'
    * first.
    */
  def prefixes(contest: ContestJury): Seq[String] =
    Seq(root(contest) + " in ", s"Category:${contest.name.trim} ${contest.year} in ")

  /** The country of the contest's country category, if it is one. */
  def country(contest: ContestJury, category: String): Option[String] =
    prefixes(contest).find(p => category.startsWith(p)).map(p => category.drop(p.length).trim).filter(_.nonEmpty)

  /** Whether `name` is `other` with more after it: `Serbia - detail` of `Serbia`, not
    * `Nigeria` of `Niger` nor `Guinea-Bissau` of `Guinea`.
    */
  def overlaps(name: String, other: String): Boolean = name.startsWith(other + " ")

  /** The countries of these categories, overlapping names collapsed to the shortest, each
    * with its category (the upload campaigns' prefix first): `Serbia`, not `Serbia - detail`.
    */
  def candidates(contest: ContestJury, categories: Seq[String]): Seq[(String, String)] = {
    val byName = categories.flatMap(c => country(contest, c).map(_ -> c)).groupMap(_._1)(_._2)
      .map { case (name, cs) => name -> cs.minBy(c => prefixes(contest).indexWhere(p => c.startsWith(p))) }
    byName.toSeq.filterNot { case (name, _) => byName.keys.exists(other => overlaps(name, other)) }.sortBy(_._1)
  }

  /** The country category of a file of these candidates ([[candidates]]): the only one, or of
    * several that don't overlap (`the United Kingdom`, `England`), the one that is the
    * others' parent on Commons (`parents`: a category's parent categories); else the
    * shortest.
    */
  def choose(candidates: Seq[(String, String)], parents: Map[String, Seq[String]]): Option[String] =
    candidates match {
      case Seq()          => None
      case Seq((_, only)) => Some(only)
      case several =>
        val categories = several.map(_._2)
        categories
          .find(c => categories.filter(_ != c).forall(o => parents.getOrElse(o, Nil).contains(c)))
          .orElse(Some(several.minBy { case (name, c) => (name.length, c) }._2))
    }

  /** The countries of a contest's files from their links to its country categories
    * (`(page id, category id, title)`, as stored): of each file's [[candidates]], the
    * category with the most of the contest's files, then the shortest name. The one an
    * import chose has them all: a part's (`England`), or another contest's category of the
    * country, has no more.
    * @return the files' countries, by page id
    */
  def fileCountries(contest: ContestJury, links: Seq[(Long, Long, String)]): Map[Long, String] = {
    val files = links.groupBy(_._2).view.mapValues(_.map(_._1).distinct.size).toMap
    links.groupBy(_._1).flatMap { case (pageId, own) =>
      val names = own.flatMap { case (_, id, title) => country(contest, title).map(n => (n, id)) }
      val kept = names.filterNot { case (n, _) => names.exists { case (o, _) => overlaps(n, o) } }
      kept.sortBy { case (n, id) => (-files(id), n.length, id) }.headOption.map(pageId -> _._1)
    }
  }
}
