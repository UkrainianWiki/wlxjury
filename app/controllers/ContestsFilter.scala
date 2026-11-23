package controllers

import org.intracer.wmua.ContestJury
import services.regions.ContestKind

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** The Type, Country, Year and Order menus of the contests page (the maintainer's request of
  * 2026-11-19), in the design of the Rounds page's ([[RoundsFilter]]): which of the contests are
  * listed, and in what order. Kept in the URL (`contestType`, `country`, `year`, `order`), only
  * where not the default, so a page with no params lists every type and country in the latest
  * year of the contests, oldest first; the old links (`?contestType=…&year=2025`) open the same
  * contests as before. An unknown `year` or `order` is the default, never an error.
  *
  * It only narrows and orders a list it is given: who may see the page and its contests is
  * decided before (root, [[ContestController.list]]), never here.
  */
final case class ContestsFilter(
    kind: Option[String] = None,
    country: ContestsFilter.Country = ContestsFilter.Country.All,
    year: ContestsFilter.Year = ContestsFilter.Year.Latest,
    order: ContestsFilter.Order = ContestsFilter.Order.Oldest,
    series: ContestsFilter.SeriesChoice = ContestsFilter.SeriesChoice.All,
    status: ContestsFilter.Status = ContestsFilter.Status.Live
) {

  import ContestsFilter._

  def withKind(k: Option[String]): ContestsFilter = copy(kind = k)
  def withCountry(c: Country): ContestsFilter = copy(country = c)
  def withYear(y: Year): ContestsFilter = copy(year = y)
  def withOrder(o: Order): ContestsFilter = copy(order = o)
  def withSeries(s: SeriesChoice): ContestsFilter = copy(series = s)
  def withStatus(s: Status): ContestsFilter = copy(status = s)

  /** The query params of the choices that aren't the default, in a fixed order. */
  def params: Seq[(String, String)] =
    Seq(
      kind.map(TypeParam -> _),
      country.param.map(CountryParam -> _),
      year.param.map(YearParam -> _),
      Option.when(order != Order.Oldest)(OrderParam -> order.param),
      series.param.map(SeriesParam -> _),
      status.param.map(StatusParam -> _)
    ).flatten

  /** `url` with this filter's params added (after its own, if it has a query); as it is for the
    * default filter.
    */
  def addTo(url: String): String = ContestsFilter.addParams(url, params)
}

object ContestsFilter {

  /** `contestType`, as the old type-and-year links named it: a contest's name ("Wiki Loves Earth"). */
  val TypeParam = "contestType"
  val CountryParam = "country"
  val YearParam = "year"
  val OrderParam = "order"
  val SeriesParam = "series"
  val StatusParam = "status"

  /** The Series menu (contest series plan §5.3): every contest, those in no series, or one
    * series' (by id). An unknown value is every contest.
    */
  sealed trait SeriesChoice { def param: Option[String] }

  object SeriesChoice {
    case object All extends SeriesChoice { val param: Option[String] = None }
    case object NoSeries extends SeriesChoice { val param: Option[String] = Some("none") }
    final case class In(id: Long) extends SeriesChoice { def param: Option[String] = Some(id.toString) }

    def parse(s: String): SeriesChoice = s.trim.toLowerCase(Locale.ROOT) match {
      case "none" => NoSeries
      case v      => v.toLongOption.fold[SeriesChoice](All)(In)
    }

    def matches(choice: SeriesChoice, c: ContestJury): Boolean = choice match {
      case All      => true
      case NoSeries => c.seriesId.isEmpty
      case In(id)   => c.seriesId.contains(id)
    }
  }

  /** The Status menu (§3.1, §5.3): live contests by default; the retired ones, or all, by choice. */
  sealed abstract class Status(val value: String, val messageKey: String) {
    def param: Option[String] = Option.when(this != Status.Live)(value)
  }

  object Status {
    case object Live extends Status("live", "contests.status.live")
    case object Retired extends Status("retired", "contests.status.retired")
    case object Any extends Status("all", "contests.status.all")
    val values: Seq[Status] = Seq(Live, Retired, Any)
    def parse(s: String): Status = values.find(_.value == s.trim.toLowerCase(Locale.ROOT)).getOrElse(Live)

    def matches(choice: Status, c: ContestJury): Boolean = choice match {
      case Live    => !c.retired
      case Retired => c.retired
      case Any     => true
    }
  }

  val Default: ContestsFilter = ContestsFilter()

  /** `url` with the params added (after its own, if it has a query), URL-encoded; as it is for none. */
  def addParams(url: String, params: Seq[(String, String)]): String =
    if (params.isEmpty) url
    else {
      val query = params.map { case (k, v) => s"$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }.mkString("&")
      url + (if (url.contains('?')) "&" else "?") + query
    }

  private val InternationalText = "international"

  /** A contest's place in the Country menu ([[placeOf]]): any; International; a country scalawiki
    * knows, by its code (`ua`: a contest of "UA" and one of "Ukraine" are of one); or any other
    * country text, as it is (case aside).
    */
  sealed trait Country { def param: Option[String] }

  object Country {
    case object All extends Country { val param: Option[String] = None }
    case object International extends Country { val param: Option[String] = Some(InternationalText) }

    /** A country scalawiki knows, by its code in lower case. */
    final case class Code(code: String) extends Country { def param: Option[String] = Some(code) }

    /** A country text scalawiki doesn't know ("Ukraine, Animals", a misspelling). */
    final case class Other(text: String) extends Country { def param: Option[String] = Some(text) }

    /** A param or a country text: `international`; a known country's code or name; else the text. */
    def parse(s: String): Country =
      Option(normalize(s)).filter(_.nonEmpty) match {
        case None                                             => All
        case Some(v) if v.equalsIgnoreCase(InternationalText) => International
        case Some(v) => knownCode(v).fold[Country](Other(v))(Code)
      }

    /** Runs of white space (a no-break space too) as one space, none at the ends. */
    def normalize(s: String): String = s.replaceAll("[\\s\\p{Z}]+", " ").trim

    /** The lower-case code of a country scalawiki knows by this name or code, or one whose name in
      * one of the app's languages this is ("Україна", "Украина").
      */
    private def knownCode(v: String): Option[String] =
      ContestKind.country(v).map(_.code).filter(_.nonEmpty).map(_.toLowerCase(Locale.ROOT))
        .orElse(localizedNames.get(v.toLowerCase(Locale.ROOT)))

    /** The app's languages (`play.i18n.langs`), for the countries' names in them. */
    private val Languages = Seq("en", "uk", "ru", "fr").map(Locale.forLanguageTag)

    /** Java's names of the ISO countries in the app's languages, lower case, to their lower-case
      * codes; built once.
      */
    private lazy val localizedNames: Map[String, String] =
      (for {
        code <- Locale.getISOCountries.toSeq
        language <- Languages
        name = new Locale("", code).getDisplayCountry(language).trim
        if name.nonEmpty && name != code
      } yield name.toLowerCase(Locale.ROOT) -> code.toLowerCase(Locale.ROOT)).toMap

    /** Whether a contest of `place` ([[placeOf]]) is of this choice. */
    def matches(choice: Country, place: Country): Boolean = (choice, place) match {
      case (All, _)             => true
      case (Other(a), Other(b)) => a.equalsIgnoreCase(b)
      case (a, b)               => a == b
    }
  }

  /** Where a contest is, for the Country menu: with a campaign scalawiki knows (its `campaign`, or
    * its name and country), the campaign's country, or International if the campaign is of no one
    * country; otherwise its country text: International if empty or "International", a country
    * scalawiki knows by that name or code, else the text as it is (its own entry, never
    * International).
    */
  // Deliberately not the galleries' ContestCountries.ofManyCountries: on root's data page a
  // country text the tool doesn't know gets an entry of its own (the galleries take it for
  // international), and a known campaign's country wins over the contest's country text.
  def placeOf(c: ContestJury): Country =
    ContestKind.of(c).map(_.country) match {
      case Some(country) if country.code.nonEmpty => Country.Code(country.code.toLowerCase(Locale.ROOT))
      case Some(_)                                => Country.International
      case None                                   => Country.parse(c.country) match {
          case Country.All => Country.International
          case place       => place
        }
    }

  /** A known country's name in `locale` (Java's, by its ISO code), else scalawiki's. */
  def countryName(code: String, locale: Locale): String =
    ContestKind.country(code).fold(code.toUpperCase(Locale.ROOT)) { country =>
      val iso = new Locale("", country.code.toUpperCase(Locale.ROOT))
      if (iso.getDisplayCountry(Locale.ENGLISH) == country.name) iso.getDisplayCountry(locale) else country.name
    }

  /** The year: by default the latest year of the contests (not the calendar's), all years
    * (`year=all`), or one.
    */
  sealed trait Year { def param: Option[String] }

  object Year {
    case object Latest extends Year { val param: Option[String] = None }
    case object All extends Year { val param: Option[String] = Some("all") }
    final case class In(year: Int) extends Year { def param: Option[String] = Some(year.toString) }

    def parse(s: String): Year = s.trim.toLowerCase(Locale.ROOT) match {
      case "all" => All
      case v     => v.toIntOption.fold[Year](Latest)(In)
    }
  }

  /** By year, the oldest or the newest first; in a year, in the order the contests were added
    * (their id; before these menus the page listed all contests in that order). Or by country
    * (`order=country`, A to Z only): by the country's name as the Country menu shows it, in the
    * page language's alphabetical order, International last; in a country by year, then id.
    */
  sealed abstract class Order(val param: String, val messageKey: String)

  object Order {
    case object Oldest extends Order("oldest", "rounds.order.oldest")
    case object Newest extends Order("newest", "rounds.order.newest")
    case object ByCountry extends Order("country", "contests.order.country")
    val values: Seq[Order] = Seq(Oldest, Newest, ByCountry)
    def parse(s: String): Option[Order] = values.find(_.param == s.trim.toLowerCase(Locale.ROOT))
  }

  /** From the route's params; any missing or unknown is the default. */
  def of(contestType: Option[String], year: Option[String], country: Option[String], order: Option[String],
      series: Option[String] = None, status: Option[String] = None): ContestsFilter =
    ContestsFilter(
      contestType.map(_.trim).filter(_.nonEmpty),
      country.fold[Country](Country.All)(Country.parse),
      year.fold[Year](Year.Latest)(Year.parse),
      order.flatMap(Order.parse).getOrElse(Order.Oldest),
      series.fold[SeriesChoice](SeriesChoice.All)(SeriesChoice.parse),
      status.fold[Status](Status.Live)(Status.parse)
    )

  /** The prefix of the add form's hidden fields that carry the list's type, country and order. */
  val FormPrefix = "list."

  /** The list's type, country and order carried by a posted form's fields ([[FormPrefix]]), parsed
    * as the params are; the latest year.
    */
  def fromForm(field: String => Option[String]): ContestsFilter =
    of(field(FormPrefix + TypeParam), None, field(FormPrefix + CountryParam), field(FormPrefix + OrderParam))

  /** The list after a contest was added with `carried` shown: the new contest's year; the type and
    * country kept only if the new contest is of them, so that it is listed.
    */
  def afterAdd(carried: ContestsFilter, added: ContestJury): ContestsFilter =
    carried.copy(
      kind = carried.kind.filter(_ == added.name.trim),
      country = if (Country.matches(carried.country, placeOf(added))) carried.country else Country.All,
      year = Year.In(added.year)
    )

  /** The list after an import: the imported contests' year if they share one, else all years. */
  def afterImport(imported: Iterable[ContestJury]): ContestsFilter =
    imported.map(_.year).toSet.toSeq match {
      case Seq(year) => Default.withYear(Year.In(year))
      case _         => Default.withYear(Year.All)
    }
}

/** The contests page's list and menus (navbar.scala.html, contests.scala.html): the contests the
  * filter keeps in its order, the menus' choices with their counts (the contests each choice
  * keeps within the other menus' choices, as [[RoundsFilterBar]]'s), and the page each choice
  * opens (`url`: the page with that filter). Each contest's place is resolved once; known
  * countries are named in `locale`.
  */
final case class ContestsFilterBar(
    filter: ContestsFilter,
    url: ContestsFilter => String,
    contests: Seq[ContestJury],
    locale: Locale = Locale.ENGLISH,
    /** The series, for the Series menu (contest series plan §5.3). */
    series: Seq[db.scalikejdbc.Series] = Nil
) extends ContestMenuCounts {

  import ContestsFilter._

  /** The contests of the Series and Status choices: the other menus choose among these only. */
  private val rows: Seq[(ContestJury, Country)] =
    contests.filter(c => SeriesChoice.matches(filter.series, c) && Status.matches(filter.status, c)).map(c => c -> placeOf(c))

  /** How many contests a Series choice keeps, within the Status choice (the other menus aside). */
  def countSeries(s: SeriesChoice): Int = contests.count(c => SeriesChoice.matches(s, c) && Status.matches(filter.status, c))

  /** How many contests a Status choice keeps, within the Series choice (the other menus aside). */
  def countStatus(s: Status): Int = contests.count(c => SeriesChoice.matches(filter.series, c) && Status.matches(s, c))

  /** The page language's alphabetical order (Ukrainian І, Ї, Є in their places). */
  private val collator = java.text.Collator.getInstance(locale)

  /** The latest year of the contests: the Year menu's default. */
  val latestYear: Option[Int] = contests.map(_.year).maxOption

  /** The year shown, None for all of them. */
  val year: Option[Int] = filter.year match {
    case Year.Latest => latestYear
    case Year.All    => None
    case Year.In(y)  => Some(y)
  }

  private def kindOk(k: Option[String], c: ContestJury) = k.forall(_ == c.name.trim)
  private def yearOk(y: Option[Int], c: ContestJury) = y.forall(_ == c.year)
  private def countryOk(choice: Country, row: (ContestJury, Country)) = Country.matches(choice, row._2)

  /** The contests the filter keeps, in its order (lazy: by country needs [[countries]]). */
  lazy val shown: Seq[ContestJury] = {
    val kept = rows.filter(r => kindOk(filter.kind, r._1) && countryOk(filter.country, r) && yearOk(year, r._1))
    val byYear = Ordering.by[(ContestJury, Country), (Int, Long)] { case (c, _) => (c.year, c.id.getOrElse(Long.MaxValue)) }
    filter.order match {
      case Order.Oldest => kept.sorted(byYear).map(_._1)
      case Order.Newest => kept.sorted(byYear).reverse.map(_._1)
      case Order.ByCountry =>
        // the Country menu's name of each contest's entry (one country's spellings as one); International last
        val named = kept.map { case r @ (_, place) =>
          val entry = if (place == Country.International) None else Some(countries.find(Country.matches(_, place)).getOrElse(place))
          (r, entry.map(name))
        }
        val byName: Ordering[Option[String]] = (a, b) => (a, b) match {
          case (Some(x), Some(y)) => collator.compare(x, y)
          case (Some(_), None)    => -1
          case (None, Some(_))    => 1
          case (None, None)       => 0
        }
        named.sorted(Ordering.Tuple2(byName, byYear).on[((ContestJury, Country), Option[String])](n => (n._2, n._1)))
          .map(_._1._1)
    }
  }

  /** The contests' types (names), alphabetically. */
  val kinds: Seq[String] = contests.map(_.name.trim).filter(_.nonEmpty).distinct.sorted

  /** A known country's name in the page's language; another text as it is. */
  def name(choice: Country): String = choice match {
    case Country.Code(code)  => countryName(code, locale)
    case Country.Other(text) => text
    case _                   => ""
  }

  /** The contests' countries but International, one entry each (a known country by its code,
    * another text case aside, as first spelt), by name.
    */
  val countries: Seq[Country] = {
    val places = rows.map(_._2).filter {
      case _: Country.Code | _: Country.Other => true
      case _                                  => false
    }
    places.foldLeft(Vector.empty[Country])((seen, p) => if (seen.exists(Country.matches(_, p))) seen else seen :+ p)
      .sortWith((a, b) => collator.compare(name(a), name(b)) < 0)
  }

  /** Whether any contest is international. */
  val hasInternational: Boolean = rows.exists(_._2 == Country.International)

  /** The contests' years, the newest first. */
  val years: Seq[Int] = contests.map(_.year).distinct.sorted(Ordering[Int].reverse)

  /** How many contests a Type choice keeps, within the current country and year. */
  def countKind(k: Option[String]): Int = rows.count(r => kindOk(k, r._1) && countryOk(filter.country, r) && yearOk(year, r._1))

  /** How many contests a Country choice keeps, within the current type and year. */
  def countCountry(c: Country): Int = rows.count(r => kindOk(filter.kind, r._1) && countryOk(c, r) && yearOk(year, r._1))

  /** How many contests a Year choice keeps, within the current type and country. */
  def countYear(y: Option[Int]): Int = rows.count(r => kindOk(filter.kind, r._1) && countryOk(filter.country, r) && yearOk(y, r._1))

  /** The current country as the menu lists it: another text as the contests spell it. */
  val country: Country = filter.country match {
    case other: Country.Other => countries.find(Country.matches(other, _)).getOrElse(other)
    case choice               => choice
  }

  /** The Year menu's choice for a year (None: all years). */
  def yearChoice(y: Option[Int]): Year = y.fold[Year](Year.All)(Year.In)
}

/** The counts of the Type, Country and Year menus' items (contestFilterMenus.scala.html): the
  * contests each choice keeps on the contests page ([[ContestsFilterBar]]), the accounts on root's
  * all-users page ([[UsersFilterBar]]); each within the other menus' choices.
  */
trait ContestMenuCounts {
  def countKind(k: Option[String]): Int
  def countCountry(c: ContestsFilter.Country): Int
  def countYear(y: Option[Int]): Int
}
