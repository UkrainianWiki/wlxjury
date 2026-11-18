package services.diagnostics

import org.intracer.wmua.ContestJury
import services.imports.ContestCountries
import services.regions.ContestKind

import java.util.Locale

/** What a contest or an import source names: the contest type ("Monuments" of "Wiki Loves
  * Monuments", or WLM), the year after it, and the country after that year's " in " (support
  * diagnostics plan, H1 and DG10). A source names one only in a "Wiki Loves <type> <year>" or
  * "WL<x> <year>" phrase, as Commons' contest categories and pages do ("Category:Images from Wiki
  * Loves Monuments 2026 in Nepal", "Commons:Wiki Loves Earth 2020/Winners"); a year elsewhere in a
  * title ("Churches built in 1900") is not read, so a source of no contest is never a mismatch.
  *
  * @param kind    the type's word as written ("Monuments", "Earth"; an abbreviation's word)
  * @param country the country as written, underscores as spaces ("Nepal", "the United Kingdom")
  */
final case class ContestIdentity(kind: Option[String], year: Option[Int], country: Option[String]) {
  def isEmpty: Boolean = kind.isEmpty && year.isEmpty && country.isEmpty

  /** As the title names it, in its own casing: "Wiki Loves Monuments 2026 in Nepal". */
  def describe: String =
    (kind.map(k => s"Wiki Loves $k").toSeq ++ year.map(_.toString) ++ country.map(c => s"in $c")).mkString(" ")
}

object ContestIdentity {

  /** Commons' abbreviations of the contest types that have their own word. */
  private val Abbreviations = Map("m" -> "Monuments", "e" -> "Earth", "f" -> "Folklore", "s" -> "Science")

  /** "Wiki Loves <type>" (or "WL<x>"), then the year, then " in <country>" up to a separator. */
  private val Phrase =
    """(?iu)(?:(?<![\p{L}\p{N}])wiki[\s_]*loves[\s_]+(\p{L}+)|(?<![\p{L}\p{N}])WL([A-Z])(?![\p{L}\p{N}]))(?:[\s_-]+((?:19|20)\d{2})(?!\d)(?:[\s_]+in[\s_]+([^/|#()\[\]{}:,;]+))?)?""".r

  /** What the text names, from its first contest phrase; empty for none. */
  def parse(text: String): ContestIdentity =
    Phrase.findFirstMatchIn(text).fold(ContestIdentity(None, None, None)) { m =>
      val kind = Option(m.group(1)).orElse(Option(m.group(2)).flatMap(a => Abbreviations.get(a.toLowerCase(Locale.ROOT))))
      val country = Option(m.group(4)).map(_.replace('_', ' ').trim.replaceAll("\\s+", " ")).filter(_.nonEmpty)
      ContestIdentity(kind, Option(m.group(3)).map(_.toInt), country)
    }

  /** The contest as the form's warning names it: its name and year, and its country unless it is
    * of many.
    */
  def describe(contest: ContestJury): String =
    (Seq(contest.name.trim, contest.year.toString) ++
      Option.when(!ContestCountries.ofManyCountries(contest))(contest.country.trim).filter(_.nonEmpty).map("in " + _))
      .mkString(" ")

  /** The contest's own: the type of its name, its year, and its country as stored unless it is of
    * many countries (the international one, [[ContestCountries.ofManyCountries]]), whose files are
    * of any.
    */
  def of(contest: ContestJury): ContestIdentity =
    ContestIdentity(
      parse(contest.name).kind,
      Some(contest.year),
      Option.when(!ContestCountries.ofManyCountries(contest))(contest.country.trim).filter(_.nonEmpty)
    )

  /** The ISO code of a national contest's country: its campaign's, else its country field read as
    * an English name or a code ([[ContestKind.country]]: "UA", "Ukraine"); None for a contest of many
    * countries, or a country that doesn't resolve ("Україна", "USA"): its country is then not compared.
    */
  def countryCode(contest: ContestJury): Option[String] =
    Option.when(!ContestCountries.ofManyCountries(contest))(
      ContestKind.of(contest).map(_.country).filter(_.code.nonEmpty).orElse(ContestKind.country(contest.country))
        .map(_.code.toUpperCase(Locale.ROOT))
    ).flatten

  /** The ISO code of a source's country ("the United Kingdom", "Ukraine - Kyiv": a part's country),
    * None if it names none or one that doesn't resolve.
    */
  def countryCode(named: ContestIdentity): Option[String] =
    named.country.flatMap { c =>
      val whole = c.replaceFirst("(?i)^the ", "")
      val country = whole.split(" - ", 2).head.trim
      ContestKind.country(whole).orElse(ContestKind.country(country)).map(_.code.toUpperCase(Locale.ROOT))
    }

  /** How a source's identity differs from the contest's: the parts both name and that differ. */
  sealed abstract class Difference(val key: String)
  case object OtherKind extends Difference("health.identity.other.kind")
  case object OtherYear extends Difference("health.identity.other.year")
  case object OtherCountry extends Difference("health.identity.other.country")

  /** The source's identity and how it differs from the contest's (none: it matches, or names no
    * contest to compare).
    */
  final case class SourceCheck(source: String, identity: ContestIdentity, differences: Seq[Difference]) {
    def mismatch: Boolean = differences.nonEmpty
  }

  /** H1's logic for one source, shared by the health page and the import form (DG10): the type
    * case-insensitively, the year, and the country by ISO code, each only where both name one.
    */
  def check(contest: ContestJury, source: String): SourceCheck = {
    val own = of(contest)
    val named = parse(source)
    def lower(s: Option[String]) = s.map(_.toLowerCase(Locale.ROOT))
    def differs[T](a: Option[T], b: Option[T]) = a.isDefined && b.isDefined && a != b
    val differences = Seq(
      Option.when(differs(lower(own.kind), lower(named.kind)))(OtherKind),
      Option.when(differs(own.year, named.year))(OtherYear),
      Option.when(differs(countryCode(contest), countryCode(named)))(OtherCountry)
    ).flatten
    SourceCheck(source, named, differences)
  }
}
