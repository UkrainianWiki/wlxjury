package services.imports

import org.intracer.wmua.ContestJury
import services.regions.ContestKind

/** A WLx contest's country categories on Commons, which its upload campaigns put every file
  * in: `Category:Images from Wiki Loves Earth 2026 in Armenia`, under the contest's
  * `Category:Images from Wiki Loves Earth 2026`. A file's country is the contest's country
  * category it is in, whatever the page or list it was imported from (a file sent in
  * privately is in none of a winners page's sections). An import keeps it as the file's
  * link to that category (`category_members`), as Commons has it.
  */
object ContestCountries {

  /** Whether the contest is of no one country (the international one): its files are of
    * many, as [[services.regions.RegionScheme.of]] finds a contest's country.
    */
  def ofManyCountries(contest: ContestJury): Boolean =
    ContestKind.of(contest).map(_.country).filter(_.code.nonEmpty).orElse(ContestKind.country(contest.country)).isEmpty

  /** The contest's category of all its countries' files. */
  def root(contest: ContestJury): String = s"Category:Images from ${contest.name.trim} ${contest.year}"

  /** What the titles of the contest's country categories start with. */
  def prefix(contest: ContestJury): String = root(contest) + " in "

  /** The contest's country category of a file in these categories: the shortest, as a
    * country's category may have others named after it (`… in Bangladesh under assessment`).
    */
  def category(contest: ContestJury, categories: Seq[String]): Option[String] = {
    val p = prefix(contest)
    categories.filter(c => c.startsWith(p) && c.length > p.length).minByOption(_.length)
  }

  /** The country of the contest's country category. */
  def country(contest: ContestJury, category: String): String = category.stripPrefix(prefix(contest)).trim
}
