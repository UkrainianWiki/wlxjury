package services.series

import db.scalikejdbc.Series
import org.intracer.wmua.ContestJury
import org.scalawiki.wlx.dto.ContestType
import services.diagnostics.ContestIdentity
import services.regions.ContestKind

import java.util.Locale

/** What a contest is for its series (contest series plan §3.1, §3.5): its type and its place (a
  * country by code, or International taken literally, Q5). Membership never depends on this: a
  * contest is in a series by `series_id` only. It feeds the assignment proposals, and the check
  * that a contest assigned to a series doesn't clearly belong to another.
  */
object SeriesIdentity {

  sealed trait Place { def key: String }
  case object International extends Place { val key = "international" }
  /** A country scalawiki knows, by its ISO code in lower case. */
  final case class Code(code: String) extends Place { def key: String = code }
  /** Any other country text: empty, a misspelling, "Ukraine, Animals". Never proposed. */
  final case class Unusual(text: String) extends Place { def key: String = "?" + text }

  /** A contest type: scalawiki's code and name ("wlm", "Wiki Loves Monuments"), or for a type
    * scalawiki doesn't know the word after "Wiki Loves" in lower case and the name it makes
    * ("folklore", "Wiki Loves Folklore"; CA24, decided 2026-11-20; scalawiki may learn them later).
    */
  final case class SeriesType(code: String, name: String)

  val InternationalText = "International"

  /** The longest type code `series.contest_type` takes (V109). */
  val TypeCodeMaxLength = 32

  /** A scalawiki type's own. */
  def of(contestType: ContestType): SeriesType = SeriesType(contestType.code, contestType.name)

  /** The type of a code as stored: scalawiki's, else "Wiki Loves <Code>". */
  def typeOfCode(code: String): SeriesType =
    ContestType.byCode(code.toLowerCase(Locale.ROOT)).map(of)
      .getOrElse(SeriesType(code.toLowerCase(Locale.ROOT), s"Wiki Loves ${code.take(1).toUpperCase(Locale.ROOT)}${code.drop(1)}"))

  /** A type code root typed, if it is one: letters only, at most [[TypeCodeMaxLength]]. */
  def validCode(code: String): Option[String] =
    Option(code.trim.toLowerCase(Locale.ROOT)).filter(c => c.nonEmpty && c.length <= TypeCodeMaxLength && c.forall(_.isLetter))

  /** Commons' one-letter abbreviations of the types (as [[ContestIdentity]] reads "WL<x>"). */
  private val Abbreviations = Map("m" -> "Monuments", "e" -> "Earth", "f" -> "Folklore", "s" -> "Science")

  /** The code of a type root typed on the create form, read as [[typeOf]] reads a contest's name,
    * so that a series and its contests get the same code: a scalawiki code as it is (`wlm`); else
    * without a leading "Wiki Loves", an abbreviation ("WLF", "f") as its word, and the word then
    * read as "Wiki Loves <word>" ("Monuments" and "Wiki Loves Monuments" give `wlm`, "Wiki Loves
    * Folklore" `folklore`). None if it names no type.
    */
  def typedCode(text: String): Option[String] = {
    val t = text.replaceAll("[\\s\\p{Z}]+", " ").trim
    if (t.isEmpty) None
    else ContestType.byCode(t.toLowerCase(Locale.ROOT)).map(_.code).orElse {
      val word = t.replaceFirst("(?i)^wiki ?loves ", "").trim
      val abbreviation = "(?i)^(?:wl)?([a-z])$".r
      val expanded = word match {
        case abbreviation(letter) => Abbreviations.getOrElse(letter.toLowerCase(Locale.ROOT), word)
        case other                => other
      }
      typeOf(ContestJury(None, s"Wiki Loves $expanded", 0, "", None)).map(_.code)
    }
  }

  /** The contest's type: its campaign's; else its name's, scalawiki's type of that name (case
    * aside), else the word after "Wiki Loves" (or a "WL<x>" abbreviation) in its name (CA24).
    */
  def typeOf(c: ContestJury): Option[SeriesType] =
    ContestKind.of(c).map(k => of(k.contestType))
      .orElse(ContestType.all.find(_.name.equalsIgnoreCase(c.name.trim)).map(of))
      .orElse(ContestIdentity.parse(c.name).kind.map(_.trim).filter(_.nonEmpty).flatMap { word =>
        val name = s"Wiki Loves ${word.take(1).toUpperCase(Locale.ROOT)}${word.drop(1)}"
        ContestType.all.find(_.name.equalsIgnoreCase(name)).map(of)
          .orElse(validCode(word).map(SeriesType(_, name)))
      })

  /** The contest's place: its campaign's country; else "International" as written (case aside);
    * else its country text read as a name or code scalawiki knows; else [[Unusual]].
    */
  def placeOf(c: ContestJury): Place =
    ContestKind.of(c).map(_.country).filter(_.code.nonEmpty) match {
      case Some(country) => Code(country.code.toLowerCase(Locale.ROOT))
      case None          => placeOfText(c.country)
    }

  /** A country text's place, as [[placeOf]] reads a contest's without a campaign. */
  def placeOfText(text: String): Place =
    if (text.trim.equalsIgnoreCase(InternationalText)) International
    else ContestKind.country(text).filter(_.code.nonEmpty).fold[Place](Unusual(text.trim))(c => Code(c.code.toLowerCase(Locale.ROOT)))

  /** The country's name as series name it: scalawiki's English name, or "International". */
  def countryName(place: Place): String = place match {
    case International => InternationalText
    case Code(code)    => ContestKind.country(code).map(_.name).getOrElse(code.toUpperCase(Locale.ROOT))
    case Unusual(text) => text
  }

  /** A series' suggested name: "Wiki Loves Monuments in Nepal", "Wiki Loves Earth International". */
  def suggestedName(contestType: SeriesType, place: Place): String = place match {
    case International => s"${contestType.name} $InternationalText"
    case other         => s"${contestType.name} in ${countryName(other)}"
  }

  /** Whether the contest clearly belongs to another series than this one: a positive mismatch
    * only. Its type is read and differs from the series' type, or its country resolves (a known
    * country or International) and differs from the series' resolved country. A contest whose
    * type or country isn't recognised ("USA", "Україна", "Photo contest") is no mismatch: root may
    * assign it ([[recognised]] says when that was the case, for the log).
    */
  def mismatch(series: Series, contest: ContestJury): Boolean = {
    val typeDiffers = series.contestType.exists(t => typeOf(contest).exists(!_.code.equalsIgnoreCase(t)))
    val placeDiffers = series.country.map(_.trim).filter(_.nonEmpty).map(placeOfText).exists {
      case _: Unusual => false
      case seriesPlace =>
        placeOf(contest) match {
          case _: Unusual => false
          case place      => place != seriesPlace
        }
    }
    typeDiffers || placeDiffers
  }

  /** Whether the contest's type and country, where the series names them, are recognised: false
    * when root assigns a contest the tool can't read (logged so).
    */
  def recognised(series: Series, contest: ContestJury): Boolean =
    series.contestType.forall(_ => typeOf(contest).isDefined) &&
      series.country.map(_.trim).filter(_.nonEmpty).forall(_ => !placeOf(contest).isInstanceOf[Unusual])

  /** Whether a series is of this type and place (an existing series offered first, §3.5). */
  def seriesOf(series: Series, contestType: SeriesType, place: Place): Boolean =
    series.contestType.exists(_.equalsIgnoreCase(contestType.code)) &&
      series.country.map(placeOfText).contains(place)
}
