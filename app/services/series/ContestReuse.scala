package services.series

import db.scalikejdbc.Round
import org.intracer.wmua.ContestJury
import play.api.Configuration
import scalikejdbc._
import services.diagnostics.ContestIdentity
import services.imports.{ContestCountries, ImageImportHandler}

/** The inline reuse check (contest series plan §3.2; D-1a's H1 logic): signs that a contest was
  * reused for another year's or country's files, the workaround the series replace. It keeps a
  * contest out of the assignment proposals (§3.5); CY-3's next-year path is refused by it too.
  * The contest's own signals and every round's (the maintainer's refinement of 2026-11-20: one
  * country's contest may host rounds of several other countries, R4 in §4):
  *
  *  - **another source:** an import source since V60 (a file import job's `source`) or the
  *    contest's own source (`contest.images`) that names another type, country or year
  *    ([[ContestIdentity.check]]: for an international contest, any country of its type and year
  *    is its own, so its country is never compared);
  *  - **a round of another source:** a round's category filter (`round.category`) naming another
  *    identity;
  *  - **a round of other files:** of a round drawn from the contest's files (one with no previous
  *    round in the contest: later rounds take their files from it), the files in a category of
  *    another identity: another contest's category (that contest's identity), or a category whose
  *    title names one ("Images from Wiki Loves Monuments 2025 in Armenia"), at least
  *    `foreignMin` files and `foreignShare` of the round's;
  *  - **rounds of several countries:** a national contest (or one whose country doesn't resolve)
  *    whose rounds name two or more countries by the signals above;
  *  - **rounds far apart:** its round ids span more than `roundIdSpan` (ids give creation order;
  *    production makes about 200 rounds a year). Shown beside the other signs only, never a flag
  *    by itself: on the production copy (2026-11-20) 17 of 239 contests with rounds span more
  *    than 150 ids, 13 of them with another sign; the other 4 are national contests with a late
  *    round (WLE Ukraine 2014 and 2016, Australia 2021, Bangladesh 2025).
  *
  * Reads: the contests' import jobs (the job contest index), their rounds (the round contest
  * index), and the files of the rounds drawn from the contest's files with their categories
  * (round_image's key, category_members' page index): a fixed number of queries for any number
  * of contests.
  */
object ContestReuse {

  sealed trait Sign

  /** A source naming another contest: the source as typed, and what it names. */
  final case class OtherSource(source: String, named: ContestIdentity) extends Sign

  /** A round whose category filter names another contest. */
  final case class RoundSource(roundId: Long, source: String, named: ContestIdentity) extends Sign

  /** A round with `files` of its `of` files in categories of another contest, `named`. */
  final case class RoundFiles(roundId: Long, named: ContestIdentity, files: Long, of: Long) extends Sign

  /** A national contest whose rounds name several countries (as their sources name them). */
  final case class RoundCountries(countries: Seq[String]) extends Sign

  /** The contest's first and last round ids, more than the threshold apart. */
  final case class RoundSpan(first: Long, last: Long) extends Sign

  /** The latest finished reuse scan (the dry-run report, CY-0, [[ReuseScanHandler]]) proposes moving its
    * clusters, leaving some as history, or root's choice (`verdict`, [[ReuseReport.Verdict]]): §3.5's
    * "or, later, by the CY-0 report". Added by the proposals, never by [[checkAll]].
    */
  final case class Report(jobId: Long, verdict: String) extends Sign

  /** A sign's kind as the proposals' left-out list names it (`series.reuse.kind.*`). */
  def kindKey(sign: Sign): String = "series.reuse.kind." + (sign match {
    case _: OtherSource    => "source"
    case _: RoundSource    => "round.source"
    case _: RoundFiles     => "round.files"
    case _: RoundCountries => "round.countries"
    case _: RoundSpan      => "span"
    case _: Report         => "report"
  })

  val Kinds: Seq[String] = Seq("source", "round.source", "round.files", "round.countries", "span", "report")

  /** The thresholds (`series.reuse.*`). */
  final case class Settings(roundIdSpan: Int = 150, foreignShare: Double = 0.02, foreignMin: Int = 5)

  object Settings {
    def apply(config: Configuration): Settings = {
      val d = Settings()
      Settings(
        config.getOptional[Int]("series.reuse.roundIdSpan").getOrElse(d.roundIdSpan),
        config.getOptional[Double]("series.reuse.foreignShare").getOrElse(d.foreignShare),
        config.getOptional[Int]("series.reuse.foreignMin").getOrElse(d.foreignMin)
      )
    }
  }

  /** The signs for one contest: none if it shows no reuse. */
  def check(contest: ContestJury, settings: Settings, known: Option[Seq[ContestJury]] = None)(implicit
      session: DBSession): Seq[Sign] =
    checkAll(Seq(contest), settings, known = known).getOrElse(contest.getId, Nil)

  /** The signs of each contest that shows any (contests that show none are left out). Without
    * `files`, the rounds' files are not read (the contests page's badge over many contests).
    * `known`: every contest, if the caller has them already (the reuse report's catalog), else read.
    */
  def checkAll(contests: Seq[ContestJury], settings: Settings = Settings(), files: Boolean = true,
      known: Option[Seq[ContestJury]] = None)(implicit session: DBSession = AutoSession): Map[Long, Seq[Sign]] = {
    val ids = contests.flatMap(_.id).distinct
    if (ids.isEmpty) Map.empty
    else {
      val sources = importSources(ids)
      val rounds = roundsOf(ids)
      val roots = rounds.filter(r => r.previous.forall(p => !rounds.exists(o => o.id == p && o.contestId == r.contestId)))
      val read = if (files) roots.map(_.id) else Nil
      val byRound = roundFiles(read)
      val sizes = roundSizes(read)
      val identities = categoryIdentities(byRound.values.flatMap(_.keys).toSeq.distinct, known)
      contests.flatMap { contest =>
        val id = contest.getId
        val own = rounds.filter(_.contestId == id)
        val typed = (contest.images.toSeq ++ sources.getOrElse(id, Nil)).map(_.trim).filter(_.nonEmpty).distinct
        val other = typed.map(ContestIdentity.check(contest, _)).filter(_.mismatch).map(c => OtherSource(c.source, c.identity))
        val roundSources = own.flatMap(r => r.category.map(ContestIdentity.check(contest, _)).filter(_.mismatch)
          .map(c => RoundSource(r.id, c.source, c.identity)))
        // per round drawn from the contest's files: files by the identity their categories name
        val named = roots.filter(_.contestId == id).map { r =>
          val byIdentity = byRound.getOrElse(r.id, Map.empty).toSeq
            .flatMap { case (category, n) => identities.get(category).map(_ -> n) }
            .groupMapReduce(_._1)(_._2)(math.max)
          r -> byIdentity
        }
        val roundFilesSigns = named.flatMap { case (r, byIdentity) =>
          val total = sizes.getOrElse(r.id, 0L)
          byIdentity.toSeq.filter { case (identity, n) =>
            ContestIdentity.check(contest, identity.describe).mismatch &&
              n >= settings.foreignMin && n >= total * settings.foreignShare
          }.sortBy(-_._2).map { case (identity, n) => RoundFiles(r.id, identity, n, total) }
        }
        // the countries the rounds name: their sources', and each round's files' most named
        val countries = {
          val fromSources = own.flatMap(_.category).map(ContestIdentity.parse).flatMap(ContestIdentity.countryCode)
          val fromFiles = named.flatMap { case (r, byIdentity) =>
            val total = sizes.getOrElse(r.id, 0L)
            byIdentity.filter { case (_, n) => n >= settings.foreignMin && n >= total * settings.foreignShare }
              .maxByOption(_._2).flatMap(e => ContestIdentity.countryCode(e._1))
          }
          (fromSources ++ fromFiles).distinct.sorted
        }
        val several = Option.when(!ContestCountries.ofManyCountries(contest) && countries.size >= 2)(RoundCountries(countries))
        val span = own.map(_.id).minOption.zip(own.map(_.id).maxOption)
          .collect { case (first, last) if last - first > settings.roundIdSpan => RoundSpan(first, last) }
        val signs = other ++ roundSources ++ roundFilesSigns ++ several
        // the span supports another sign; alone it is a late round, not reuse
        Option.when(signs.nonEmpty)(id -> (signs ++ span))
      }.toMap
    }
  }

  /** The sources of the contests' file imports (jobs since V60), by contest. */
  private[series] def importSources(contestIds: Seq[Long])(implicit session: DBSession): Map[Long, Seq[String]] =
    sql"""SELECT contest_id, JSON_VALUE(params, '$$.source') FROM job
          WHERE contest_id IN ($contestIds) AND kind = ${ImageImportHandler.Kind}
            AND JSON_VALUE(params, '$$.mode') = ${ImageImportHandler.Mode.Import}"""
      .map(rs => rs.long(1) -> rs.stringOpt(2)).list.apply()
      .collect { case (c, Some(s)) => c -> s }
      .groupMap(_._1)(_._2)

  private final case class RoundRow(id: Long, contestId: Long, previous: Seq[Long], category: Option[String])

  /** The contests' rounds but cancelled creations: id, contest, previous rounds, category filter. */
  private def roundsOf(contestIds: Seq[Long])(implicit session: DBSession): Seq[RoundRow] =
    sql"""SELECT id, contest_id, previous, category FROM round WHERE contest_id IN ($contestIds)
            AND (status IS NULL OR BINARY status <> 'cancelled')"""
      .map(rs => RoundRow(rs.long(1), rs.long(2), Round.parseIds(rs.stringOpt(3)),
        rs.stringOpt(4).map(_.trim).filter(_.nonEmpty)))
      .list.apply()

  /** The read of [[roundFiles]]: in a fixed order, each step by a key (round and contest_jury by
    * primary key, round_image by its primary key's round_id, category_members by its page index),
    * so it reads each file of the rounds once per category it is in (ContestReuseSpec EXPLAINs it).
    */
  private[series] def roundFilesQuery(roundIds: Seq[Long]): SQLSyntax =
    sqls"""SELECT ri.round_id, cm.category_id, COUNT(*) FROM round r
              STRAIGHT_JOIN contest_jury cj ON cj.id = r.contest_id
              STRAIGHT_JOIN round_image ri ON ri.round_id = r.id
              STRAIGHT_JOIN category_members cm ON cm.page_id = ri.page_id
            WHERE r.id IN ($roundIds) AND NOT (cm.category_id <=> cj.category_id)
            GROUP BY ri.round_id, cm.category_id"""

  /** Of each round, its files by each category they are in other than its contest's own. */
  private def roundFiles(roundIds: Seq[Long])(implicit session: DBSession): Map[Long, Map[Long, Long]] =
    if (roundIds.isEmpty) Map.empty
    else
      sql"${roundFilesQuery(roundIds)}"
        .map(rs => (rs.long(1), rs.long(2), rs.long(3))).list.apply()
        .groupBy(_._1).view.mapValues(_.map(t => t._2 -> t._3).toMap).toMap

  private def roundSizes(roundIds: Seq[Long])(implicit session: DBSession): Map[Long, Long] =
    if (roundIds.isEmpty) Map.empty
    else
      sql"SELECT round_id, COUNT(*) FROM round_image WHERE round_id IN ($roundIds) GROUP BY round_id"
        .map(rs => rs.long(1) -> rs.long(2)).list.apply().toMap

  /** The identity each category names: another contest's whose category it is, else its title's
    * ("Category:Images from Wiki Loves Earth 2020 in Armenia"); categories naming none are left out.
    * The contests of `known` if given, else read.
    */
  private[series] def categoryIdentities(categoryIds: Seq[Long], known: Option[Seq[ContestJury]] = None)(implicit
      session: DBSession): Map[Long, ContestIdentity] =
    if (categoryIds.isEmpty) Map.empty
    else {
      val owners = known.fold[Seq[ContestJury]](db.scalikejdbc.ContestJuryJdbc
        .findAllBy(sqls.in(db.scalikejdbc.ContestJuryJdbc.defaultAlias.categoryId, categoryIds)))(
        _.filter(_.categoryId.exists(categoryIds.toSet)))
      val byContest = owners
        .flatMap(c => c.categoryId.map(_ -> ContestIdentity.parse(ContestIdentity.describe(c)))).toMap
      val byTitle = sql"SELECT id, title FROM category WHERE id IN ($categoryIds)"
        .map(rs => rs.long(1) -> ContestIdentity.parse(rs.string(2))).list.apply()
        .filter { case (_, identity) => identity.year.isDefined && identity.kind.isDefined }.toMap
      (byTitle ++ byContest).filter { case (_, identity) => !identity.isEmpty }
    }
}
