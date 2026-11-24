package services.series

import db.scalikejdbc.{ContestJuryJdbc, Round, SeriesJdbc}
import org.intracer.wmua.ContestJury
import play.api.Configuration
import play.api.libs.json.{Json, OFormat}
import scalikejdbc._
import services.diagnostics.ContestIdentity
import services.imports.ContestCountries

import java.util.Locale

/** The reuse dry-run report (contest series plan §4.1–§4.3, CY-0): of one contest, its rounds in
  * clusters (connected under `previous`; `exclude_rounds` doesn't join), each cluster's signals,
  * the identity they name, its files in the three buckets against that identity, and what a split
  * would do with it. It only reads (SELECTs in the caller's session); it proposes and never moves
  * anything (CY-4 does, after root's confirmation).
  *
  * **The cluster's identity comes from its own signals** (R4: one country's contest may host whole
  * clusters of other countries), never from the host's: the category filter of its rounds (S5r,
  * the first round's first) and the identity most of its files' categories name (S2). Missing parts
  * are the host's (its type and country) and the file era's year (S1). Where S5r and S2, or another
  * round's filter, or the file era's year, name a part differently, the cluster is **root's choice**,
  * never proposed automatically; so is a cluster that only the file era places in another year (S1
  * never links), and, in a contest of many countries, another year's or type's cluster whose
  * signals name one country (its national contest's, or an international one's country round?).
  *
  * **What it proposes per cluster** (the decisions of 2026-11-20): `stay` (the host's own identity,
  * or no sign of another), `move` (R1 the host's type and country, another year; R2 another type;
  * R4 another country of a national host) to the identity's series and year, `leave` (an empty
  * cluster of another identity, and R3: foreign files above the share of its S2-classed files) or
  * `choose`. A cluster with an active round is flagged: a move would need stopping it (CY-4).
  *
  * It also lists what CY-4's open decisions (CA9–CA13, CA23) need to be asked with numbers: each
  * cluster's jurors and their memberships (host role, deleted, protected, a conflicting role in an
  * existing target), the people who ran its rounds, the rows a move would touch (rounds, comments,
  * jobs; votes untouched), and the host's files only the moved clusters use.
  *
  * Reads (each by a key; [[ReuseReportSpec]] EXPLAINs the file reads): the contest's rounds; their
  * `round_image` counts; per cluster its files' categories (`round_image` by its key,
  * `category_members` by its page index) and its buckets; the median page id of its largest round;
  * the rounds' votes (`vote` by its round index), comments (the contest index), jobs (the contest
  * index), active jurors and the jurors' and runners' accounts and memberships.
  */
object ReuseReport {

  // ── settings ────────────────────────────────────────────────────────────────

  /** The reuse check's thresholds (`series.reuse.*`), and the share of a cluster's files that a
    * type and year, and then a country, must name for S2 to name them (`series.reuse.namedShare`).
    */
  final case class Settings(reuse: ContestReuse.Settings = ContestReuse.Settings(), namedShare: Double = 0.5)

  object Settings {
    def apply(config: Configuration): Settings =
      Settings(ContestReuse.Settings(config),
        config.getOptional[Double]("series.reuse.namedShare").getOrElse(Settings().namedShare))
  }

  // ── eras (S1, S3) ─────────────────────────────────────────────────────────

  /** One contest's place in time, for the eras: its year, the median page id of its largest round,
    * and the median id of its rounds.
    */
  final case class EraSample(contestId: Long, year: Int, page: Option[Long], round: Long)

  /** A year's centre: the median of its contests' median page ids and of their median round ids. */
  final case class YearCentre(year: Int, page: Option[Long], round: Long)

  /** Where each year's files and rounds lie (the plan's Q1, tuned on the production copy of
    * 2026-11-20: per-year minimum and maximum page ids are spoiled by a few files of other years
    * and test ids up to 9.2e18, while each contest's median page id falls in a narrow band per
    * year, so a year is the median of its contests' medians, and a value's year the nearest).
    * Contests whose round ids span more than the threshold are left out, as Q1 does.
    */
  final case class Eras(years: Seq[YearCentre]) {
    def isEmpty: Boolean = years.isEmpty

    def yearOfPage(page: Long): Option[Int] = nearest(years.flatMap(y => y.page.map(y.year -> _)), page)

    def yearOfRound(round: Long): Option[Int] = nearest(years.map(y => y.year -> y.round), round)

    /** The year of the nearest centre; None with fewer than two years (nothing to tell apart), or
      * if the value is farther from it than the widest gap between two years' centres (a test page
      * id far beyond any year's files).
      */
    private def nearest(centres: Seq[(Int, Long)], value: Long): Option[Int] = {
      val sorted = centres.map(_._2).sorted
      val slack = sorted.zip(sorted.drop(1)).map { case (a, b) => BigInt(b) - BigInt(a) }.maxOption.getOrElse(BigInt(0))
      centres.map { case (year, c) => year -> (BigInt(c) - BigInt(value)).abs }.minByOption(_._2)
        .filter { case (_, d) => centres.size >= 2 && d <= slack }.map(_._1)
    }
  }

  object Eras {
    val empty: Eras = Eras(Nil)

    private def median(values: Seq[Long]): Long = {
      val sorted = values.sorted
      sorted(sorted.size / 2)
    }

    def of(samples: Seq[EraSample]): Eras =
      Eras(samples.groupBy(_.year).toSeq.sortBy(_._1).map { case (year, ss) =>
        val pages = ss.flatMap(_.page)
        YearCentre(year, Option.when(pages.nonEmpty)(median(pages)), median(ss.map(_.round)))
      })
  }

  /** The contest's era sample: None without rounds, or if its round ids span more than the
    * threshold (Q3's contests, left out of Q1).
    */
  def eraSample(contest: ContestJury, settings: Settings)(implicit session: DBSession): Option[EraSample] = {
    val rounds = roundsOf(contest.getId)
    val ids = rounds.map(_.id)
    if (ids.isEmpty || ids.max - ids.min > settings.reuse.roundIdSpan) None
    else {
      val sizes = roundSizes(ids)
      val page = sizes.toSeq.sortBy { case (id, n) => (-n, id) }.headOption.filter(_._2 > 0).flatMap { case (id, n) => medianPage(id, n) }
      Some(EraSample(contest.getId, contest.year, page, ids.sorted.apply(ids.size / 2)))
    }
  }

  // ── the report ──────────────────────────────────────────────────────────────

  /** A cluster's kind, by its identity against the host's (§4.1). */
  object Kind {
    val Own = "own"
    /** R1: the host's type and country, another year. */
    val OtherYear = "r1"
    /** R2: another type. */
    val OtherType = "r2"
    /** R4: the host's type, another country (a national host). */
    val OtherCountry = "r4"
    /** No signal names an identity: taken as the host's. */
    val Unknown = "unknown"
    val All: Seq[String] = Seq(Own, OtherYear, OtherType, OtherCountry, Unknown)
  }

  object Action {
    val Stay = "stay"
    val Move = "move"
    val Leave = "leave"
    val Choose = "choose"
    val All: Seq[String] = Seq(Move, Choose, Leave, Stay)
  }

  /** A contest's verdict: its most demanding cluster's action, else `signs` if only the inline
    * check flags it, else `clean`.
    */
  object Verdict {
    val Choose = "choose"
    val Move = "move"
    val Leave = "leave"
    val Signs = "signs"
    val Clean = "clean"
    val All: Seq[String] = Seq(Choose, Move, Leave, Signs, Clean)

    /** The verdicts that keep a contest out of the series proposals (§3.5). */
    val KeptOut: Set[String] = Set(Choose, Move, Leave)
  }

  /** Why a cluster's signals disagree: the signal and the part it names otherwise (`host`: the
    * file era places a cluster no signal names in another year than the host's; `international`: a
    * signal names one country for another year's or type's cluster of a contest of many countries).
    */
  final case class Disagreement(signal: String, part: String)

  val DisagreementParts: Seq[String] = Seq("kind", "year", "country", "host", "international")

  /** The signals' codes (§4.2), as the report names them (`reuse.signal.*`). */
  val SignalCodes: Seq[String] = Seq("s1", "s2", "s3", "s4", "s5", "s5r", "s6")

  /** Why a move wouldn't give a juror the Juror role in the target ([[Juror.skip]]). */
  val SkipReasons: Seq[String] = Seq("deleted", "protected", "conflict", "already")

  /** A round of a cluster; `active`: open to jurors or being distributed, which a move would have
    * to stop first (§4.5, CY-4).
    */
  final case class RoundLine(id: Long, number: Long, name: Option[String], status: String, distributing: Boolean,
      category: Option[String], files: Long, jurors: Int, votes: Long, comments: Long, jobs: Long) {
    def active: Boolean = status == Round.Status.Active || status == Round.Status.Draft || distributing
  }

  /** The cluster's files against its identity (§4.1): matching, foreign, unclassified. */
  final case class Buckets(matching: Long, foreign: Long, unclassified: Long) {
    def total: Long = matching + foreign + unclassified
    def classified: Long = matching + foreign
  }

  /** The signals (§4.2). S5 is the contest's import sources that name the cluster's identity. */
  final case class Signals(
      s1Year: Option[Int],
      s2: Seq[(ContestIdentity, Long)],
      s2ManyCountries: Boolean,
      s3Year: Option[Int],
      s4Years: Option[(Int, Int)],
      s5: Seq[String],
      s5r: Seq[(Long, String, ContestIdentity)],
      s6: Seq[(Int, Int)]
  )

  /** A juror of the cluster: their membership in the host, and in the existing target if any. */
  final case class Juror(userId: Long, name: String, deleted: Boolean, root: Boolean, hostRole: Option[String],
      targetRole: Option[String], otherClusters: Seq[Int]) {

    /** Why a move wouldn't give them the Juror role in the target (CA11's recommendation): a
      * deleted account, a protected one (root), a role there Juror can't go with (O14), or Juror
      * there already. None: it would.
      */
    def skip: Option[String] =
      if (deleted) Some("deleted")
      else if (root) Some("protected")
      else targetRole match {
        case Some(role) if role == db.scalikejdbc.User.JURY_ROLE => Some("already")
        case Some(_)                                            => Some("conflict")
        case None                                               => None
      }
  }

  /** Someone who ran the cluster's rounds (changed their status, started their jobs; CA23). */
  final case class Runner(userId: Long, name: String, root: Boolean, hostRole: Option[String])

  /** The proposed target: the identity's series (existing or to create) and year, and the live
    * contests of that series and year, or outside any series of that type, country and year.
    */
  final case class Target(identity: ContestIdentity, seriesType: SeriesIdentity.SeriesType, place: SeriesIdentity.Place,
      seriesId: Option[Long], seriesName: String, year: Int, contests: Seq[Long])

  final case class Cluster(
      index: Int,
      rounds: Seq[RoundLine],
      identity: ContestIdentity,
      kind: String,
      action: String,
      mixed: Boolean,
      empty: Boolean,
      disagreements: Seq[Disagreement],
      signals: Signals,
      buckets: Buckets,
      target: Option[Target],
      jurors: Seq[Juror],
      runners: Seq[Runner]
  ) {
    def roundIds: Seq[Long] = rounds.map(_.id)
    def activeRounds: Seq[Long] = rounds.filter(_.active).map(_.id)
    def votes: Long = rounds.map(_.votes).sum
    def comments: Long = rounds.map(_.comments).sum
    def jobs: Long = rounds.map(_.jobs).sum
    /** A move's key: its target's series and year (clusters of one key are one move). */
    def moveKey: Option[(String, String, Int)] = target.map(t => (t.seriesType.code, t.place.key, t.year))
  }

  final case class Report(
      contest: ContestJury,
      identity: ContestIdentity,
      national: Boolean,
      signs: Seq[ContestReuse.Sign],
      clusters: Seq[Cluster],
      eras: Boolean,
      unusedInSource: Long
  ) {
    def moves: Seq[Cluster] = clusters.filter(_.action == Action.Move)
    def moveCount: Int = moves.flatMap(_.moveKey).distinct.size
    def r4Host: Boolean = national && clusters.exists(_.kind == Kind.OtherCountry)

    def verdict: String = {
      val actions = clusters.map(_.action).toSet
      if (actions(Action.Choose)) Verdict.Choose
      else if (actions(Action.Move)) Verdict.Move
      else if (actions(Action.Leave)) Verdict.Leave
      else if (signs.nonEmpty) Verdict.Signs
      else Verdict.Clean
    }

    /** The jurors a move would give the Juror role in a target (CA11), over the moving clusters. */
    def jurorsToAdd: Int = moves.flatMap(_.jurors).filter(_.skip.isEmpty).map(_.userId).distinct.size

    /** The host's jurors who judge only moved rounds: their host memberships listed for its admin. */
    def onlyMovedJurors: Int = {
      val staying = clusters.filterNot(_.action == Action.Move).flatMap(_.jurors).map(_.userId).toSet
      moves.flatMap(_.jurors).map(_.userId).distinct.count(id => !staying(id))
    }
  }

  /** Every contest and series, as the report compares a cluster's identity with them (the scan
    * reads them once per step; a harness over a copy before V109 passes its own).
    */
  final case class Catalog(contests: Seq[ContestJury], series: Seq[db.scalikejdbc.Series])

  object Catalog {
    def load()(implicit session: DBSession): Catalog = Catalog(ContestJuryJdbc.findAll(), SeriesJdbc.findAll())
  }

  /** The report of one contest, with the eras of the latest scan (empty: S1 and S3 not shown). */
  def report(contest: ContestJury, eras: Eras, settings: Settings, catalog: Option[Catalog] = None)(implicit
      session: DBSession): Report = {
    val known = catalog.getOrElse(Catalog.load())
    val contestId = contest.getId
    // a national contest: its country resolves, also as Commons writes it ("the Philippines"); an
    // international one: "International", or no country (older international contests). A
    // country that doesn't resolve ("Wales", "Country{test}") is neither: never compared
    val national = !ContestCountries.ofManyCountries(contest) ||
      countryCodeOf(contest.country).exists(_ != SeriesIdentity.InternationalText)
    val international = !national && (contest.country.trim.isEmpty || isInternational(Some(contest.country)))
    val host = ContestIdentity.of(contest) match {
      case h if !international && h.country.isEmpty => h.copy(country = Some(contest.country.trim).filter(_.nonEmpty))
      case h                                  => h
    }
    val signs = ContestReuse.check(contest, settings.reuse, Some(known.contests))
    val rounds = roundsOf(contestId)
    val ids = rounds.map(_.id)
    val sizes = roundSizes(ids)
    val votes = roundVotes(ids)
    val comments = roundComments(contestId)
    val jobs = roundJobs(contestId)
    val jurorRows = roundJurors(ids)
    val sources = (contest.images.toSeq ++ ContestReuse.importSources(Seq(contestId)).getOrElse(contestId, Nil))
      .map(_.trim).filter(_.nonEmpty).distinct
    val groups = clustersOf(rounds)
    val allContests = known.contests
    val allSeries = known.series.filter(_.live)

    // per cluster: its files by category (other than the host's own), and what each names
    val byCategory = groups.map(g => categoryCounts(g.map(_.id), contest.categoryId))
    val identities = ContestReuse.categoryIdentities(byCategory.flatMap(_.keys).distinct, Some(allContests))

    val jurorsOf: Seq[Set[Long]] = groups.map(g => g.flatMap(r => jurorRows.getOrElse(r.id, Nil)).toSet)

    val drafts = groups.zipWithIndex.map { case (group, i) =>
      val index = i + 1
      val lines = group.map { r =>
        RoundLine(r.id, r.number, r.name, r.lifecycle, r.distributing, r.category, sizes.getOrElse(r.id, 0L),
          jurorRows.getOrElse(r.id, Nil).size, votes.get(r.id).map(_.count).getOrElse(0L), comments.getOrElse(r.id, 0L),
          jobs.get(r.id).map(_._1).getOrElse(0L))
      }
      val files = lines.map(_.files).sum
      // S5r: the rounds' category filters naming a contest, the first round's first
      val s5r = group.flatMap(r => r.category.map(c => (r.id, c, ContestIdentity.parse(c))))
        .filter { case (_, _, named) => named.kind.isDefined && named.year.isDefined }
      // S2: the identities its files' categories name, most first
      val named = byCategory(i).toSeq.flatMap { case (category, n) => identities.get(category).map(_ -> n) }
        .groupMapReduce(_._1)(_._2)(math.max).toSeq.sortBy { case (id, n) => (-n, id.describe) }
      val total = if (lines.count(_.files > 0) > 1) distinctFiles(group.map(_.id)) else files
      val namedBy = byCategory(i).toSeq.flatMap { case (category, n) => identities.get(category).map(id => (category, id, n)) }
      val (s2Identity, many) = dominant(namedBy, total, settings, cats => distinctFiles(group.map(_.id), cats))
      // S1: the file era of its largest round's median file
      val s1 = if (eras.isEmpty) None
        else lines.filter(_.files > 0).sortBy(l => (-l.files, l.id)).headOption
          .flatMap(l => medianPage(l.id, l.files)).flatMap(eras.yearOfPage)
      val s3 = if (eras.isEmpty) None else eras.yearOfRound(group.map(_.id).min)
      val s4 = {
        val years = group.flatMap(r => votes.get(r.id).toSeq.flatMap(v => v.firstYear.toSeq ++ v.lastYear.toSeq))
        years.minOption.zip(years.maxOption)
      }
      val s6 = jurorsOf.zipWithIndex.collect {
        case (other, j) if j != i && (other & jurorsOf(i)).nonEmpty => (j + 1) -> (other & jurorsOf(i)).size
      }
      val (identity, strong, disagreements) = identityOf(host, international, s5r.map(_._3), s2Identity, many, s1)
      (index, group, lines, files, s5r, named, many, s1, s3, s4, s6, identity, strong, disagreements)
    }

    val clusters = drafts.map { case (index, group, lines, files, s5r, named, many, s1, s3, s4, s6, named0, strong,
          disagreements0) =>
      val kind = kindOf(host, national, named0, strong)
      // a contest not of one known country (international, or "Africa") holding another year's or
      // type's rounds of one country: that country's national contest's, or a country round of a
      // contest of many? Never sent to a national series automatically: proposed as of many
      // countries, and root's choice. A country written as the host's own ("Wales" in a Wales
      // contest whose country doesn't resolve) is the host's: not this case
      def hostsOwn(c: String) = !international && normalized(c) == normalized(contest.country)
      val (identity, disagreements) =
        if (!national && kind != Kind.Own && kind != Kind.Unknown &&
            named0.country.exists(c => !isInternational(Some(c)) && !hostsOwn(c)))
          (named0.copy(country = Some(SeriesIdentity.InternationalText)),
            disagreements0 :+ Disagreement(if (s5r.headOption.exists(_._3.country.isDefined)) "s5r" else "s2", "international"))
        else (named0, disagreements0)
      val (matchIds, classifiedIds) = classify(identities.filter { case (c, _) => byCategory(index - 1).contains(c) }, identity)
      val buckets = bucketsOf(group.map(_.id), matchIds, classifiedIds, files)
      val empty = buckets.total == 0
      val mixed = buckets.foreign >= settings.reuse.foreignMin && buckets.foreign > buckets.classified * settings.reuse.foreignShare
      val action =
        if (empty) { if (kind == Kind.Own || kind == Kind.Unknown) Action.Stay else Action.Leave }
        else if (disagreements.nonEmpty) Action.Choose
        else if (kind == Kind.Own || kind == Kind.Unknown) Action.Stay
        else if (mixed) Action.Leave
        else Action.Move
      val target = Option.when(kind != Kind.Own && kind != Kind.Unknown || action == Action.Choose)(
        targetOf(contest, identity, allContests, allSeries)).flatten
      val s5 = sources.filter(s => matches(ContestIdentity.parse(s), identity))
      val signals = Signals(s1, named.take(MaxNamed), many, s3, s4, s5, s5r, s6)
      Cluster(index, lines, identity, kind, action, mixed, empty, disagreements, signals, buckets, target, Nil, Nil)
    }

    // the people: jurors, and those who ran the rounds
    val targetIds = clusters.flatMap(_.target.toSeq.flatMap(_.contests.headOption)).distinct
    val runnerIds = rounds.flatMap(r => r.statusChangedBy.toSeq ++ jobs.get(r.id).toSeq.flatMap(_._2))
    val people = accounts((jurorRows.values.flatten ++ runnerIds).toSeq.distinct)
    val memberships = membershipsOf(people.keys.toSeq, contestId +: targetIds)
    val withPeople = clusters.map { c =>
      val target = c.target.flatMap(_.contests.headOption)
      val jurors = jurorsOf(c.index - 1).toSeq.flatMap(people.get).sortBy(a => (a.name.toLowerCase(Locale.ROOT), a.id)).map { a =>
        Juror(a.id, a.name, a.deleted, a.root, memberships.get((a.id, contestId)), target.flatMap(t => memberships.get((a.id, t))),
          jurorsOf.zipWithIndex.collect { case (other, j) if j != c.index - 1 && other(a.id) => j + 1 })
      }
      val runIds = c.roundIds.flatMap(id => rounds.find(_.id == id).toSeq.flatMap(_.statusChangedBy) ++
        jobs.get(id).toSeq.flatMap(_._2)).distinct
      val runners = runIds.flatMap(people.get).sortBy(a => (a.name.toLowerCase(Locale.ROOT), a.id))
        .map(a => Runner(a.id, a.name, a.root, memberships.get((a.id, contestId))))
      c.copy(jurors = jurors, runners = runners)
    }

    val moving = withPeople.filter(_.action == Action.Move)
    val unused = unusedInSource(moving.flatMap(_.roundIds), withPeople.filterNot(_.action == Action.Move).flatMap(_.roundIds))
    Report(contest, host, national, signs, withPeople, !eras.isEmpty, unused)
  }

  /** How many identities of a cluster's files the report lists. */
  val MaxNamed = 6

  /** S2's identity, from the categories naming an identity (`named`: category, identity, its
    * files) and the cluster's file count (`total`): the type and year whose categories hold the most
    * files (each file once over them all, `distinct`), if more than `namedShare` of the cluster's
    * files (and at least `foreignMin`); then the country (resolved, so "in Brazil - São Paulo" is
    * Brazil's) whose categories of that type and year hold more than `namedShare` of them (each file
    * once), else of many countries (true) if two or more countries are named, else none. A national
    * contest's files usually have no country category, so a few foreign files never name a cluster
    * (they are its buckets' foreign).
    */
  private def dominant(named: Seq[(Long, ContestIdentity, Long)], total: Long, settings: Settings,
      distinct: Seq[Long] => Long): (Option[ContestIdentity], Boolean) = {
    def key(id: ContestIdentity) = (id.kind.map(_.toLowerCase(Locale.ROOT)), id.year)
    def enough(n: Long) = n > total * settings.namedShare && n >= settings.reuse.foreignMin
    def files(es: Seq[(Long, ContestIdentity, Long)]) = if (es.size == 1) es.head._3 else distinct(es.map(_._1))
    val dated = named.filter { case (_, id, _) => id.kind.isDefined && id.year.isDefined }
    // only the types and years whose categories could hold enough (their counts summed) are counted
    dated.groupBy(e => key(e._2)).toSeq.filter { case (_, es) => enough(es.map(_._3).sum) }
      .map { case (k, es) => (k, es, files(es)) }
      .sortBy { case (k, _, n) => (-n, k.toString) }.headOption.filter(k => enough(k._3)) match {
      case None => (None, false)
      case Some((_, es, _)) =>
        val first = es.maxBy { case (_, id, n) => (n, id.describe) }._2
        val byCountry = es.flatMap { case e @ (_, id, _) => id.country.flatMap(countryCodeOf).map(_ -> e) }
          .groupMap(_._1)(_._2).toSeq
        val counted = byCountry.filter { case (_, cs) => enough(cs.map(_._3).sum) }.map { case (code, cs) =>
          // its name: the country's own category before its parts' ("in Brazil" before "in Brazil - São Paulo")
          (code, cs.minBy(c => (c._2.describe.length, -c._3))._2, files(cs))
        }
        counted.sortBy(c => (-c._3, c._1)).headOption match {
          case Some((_, id, n)) if enough(n) => (Some(first.copy(country = id.country.map(clean))), false)
          case _ => (Some(first.copy(country = None)), byCountry.size >= 2)
        }
    }
  }

  private def lower(s: Option[String]) = s.map(_.toLowerCase(Locale.ROOT))

  /** A country's text for comparing as written: in lower case, without a leading "the " or invisible characters. */
  private def normalized(country: String): String = clean(country).toLowerCase(Locale.ROOT).replaceFirst("^the ", "")

  /** A country as a category title writes it, without invisible format characters (a title's U+200E). */
  private def clean(country: String): String = country.replaceAll("\\p{Cf}", "").trim

  /** The country's ISO code ("the Philippines": PH; "Ukraine - Kyiv": UA), or International's; None
    * if it doesn't resolve ("Bulgaria last round", a typo): such a country is never compared.
    */
  private def countryCodeOf(country: String): Option[String] =
    if (isInternational(Some(country))) Some(SeriesIdentity.InternationalText)
    else ContestIdentity.countryCode(ContestIdentity(None, None, Some(clean(country))))

  /** Whether two countries are the same; None unless both resolve (as the inline check compares them). */
  private def sameCountry(a: Option[String], b: Option[String]): Option[Boolean] =
    for { x <- a; y <- b; cx <- countryCodeOf(x); cy <- countryCodeOf(y) } yield cx == cy

  /** The cluster's identity, whether its signals name one (S5r or S2), and where they disagree. */
  private def identityOf(host: ContestIdentity, international: Boolean, s5r: Seq[ContestIdentity], s2: Option[ContestIdentity],
      many: Boolean, s1: Option[Int]): (ContestIdentity, Boolean, Seq[Disagreement]) = {
    val first = s5r.headOption
    def differences(signal: String, a: ContestIdentity, b: ContestIdentity): Seq[Disagreement] = Seq(
      Option.when(lower(a.kind).zip(lower(b.kind)).exists { case (x, y) => x != y })(Disagreement(signal, "kind")),
      Option.when(a.year.zip(b.year).exists { case (x, y) => x != y })(Disagreement(signal, "year")),
      Option.when(sameCountry(a.country, b.country).contains(false))(Disagreement(signal, "country"))
    ).flatten
    val strong = first.isDefined || s2.isDefined
    val named = first.toSeq ++ s2.toSeq
    val merged = ContestIdentity(named.flatMap(_.kind).headOption, named.flatMap(_.year).headOption,
      // a country that resolves before one that doesn't ("…2016 in Bulgaria last round")
      named.flatMap(_.country).find(c => countryCodeOf(c).isDefined).orElse(named.flatMap(_.country).headOption))
    val signalDisagreements =
      first.zip(s2).toSeq.flatMap { case (a, b) => differences("s2", a, b) } ++
        first.toSeq.flatMap(a => s5r.drop(1).flatMap(b => differences("s5r", a, b))).distinct
    val year = merged.year.orElse(s1).getOrElse(host.year.getOrElse(0))
    // the file era: against a year the signals name, or the host's year when they name none
    val eraDisagreement = s1.filter(_ != year).map(_ => Disagreement("s1", "year")) ++
      Option.when(merged.year.isEmpty && s1.exists(y => host.year.exists(_ != y)))(Disagreement("s1", "host")).toSeq
    val country =
      if (merged.country.isDefined) merged.country
      else if (many || international) Some(SeriesIdentity.InternationalText)
      else host.country
    val identity = ContestIdentity(merged.kind.orElse(host.kind), Some(year), country)
    (identity, strong, (signalDisagreements ++ eraDisagreement).distinct)
  }

  private def isInternational(country: Option[String]): Boolean =
    country.exists(_.trim.equalsIgnoreCase(SeriesIdentity.InternationalText))

  /** The cluster's kind against the host's: R2 another type, else R4 another country (a national
    * host; for an international one any country of its type and year is its own), else R1 another
    * year; with no signal of its own, unknown.
    */
  private def kindOf(host: ContestIdentity, national: Boolean, identity: ContestIdentity, strong: Boolean): String = {
    val typeDiffers = lower(host.kind).zip(lower(identity.kind)).exists { case (a, b) => a != b }
    val yearDiffers = host.year.zip(identity.year).exists { case (a, b) => a != b }
    val countryDiffers = national && (isInternational(identity.country) || sameCountry(host.country, identity.country).contains(false))
    if (typeDiffers) Kind.OtherType
    else if (countryDiffers) Kind.OtherCountry
    else if (yearDiffers) Kind.OtherYear
    else if (strong) Kind.Own
    else Kind.Unknown
  }

  /** Whether a category's identity is the target's: its type and year, and its country (for an
    * international target, any country, or none).
    */
  private def matches(named: ContestIdentity, target: ContestIdentity): Boolean =
    lower(named.kind).isDefined && lower(named.kind) == lower(target.kind) && named.year.isDefined && named.year == target.year &&
      (isInternational(target.country) || sameCountry(named.country, target.country).contains(true))

  /** Whether a category's identity differs from the target's in a part both name. */
  private def foreign(named: ContestIdentity, target: ContestIdentity): Boolean =
    lower(named.kind).zip(lower(target.kind)).exists { case (a, b) => a != b } ||
      named.year.zip(target.year).exists { case (a, b) => a != b } ||
      (!isInternational(target.country) && sameCountry(named.country, target.country).contains(false))

  /** The categories of the target's identity, and those naming any identity it is or isn't. */
  private def classify(identities: Map[Long, ContestIdentity], target: ContestIdentity): (Seq[Long], Seq[Long]) = {
    val matching = identities.collect { case (c, id) if matches(id, target) => c }.toSeq.sorted
    val other = identities.collect { case (c, id) if !matches(id, target) && foreign(id, target) => c }.toSeq
    (matching, (matching ++ other).sorted)
  }

  /** The proposed target of an identity: its series (an existing live one of its type and place,
    * else the name it would get) and the live contests of that series and year, or of no series
    * with that type, place and year (the host aside).
    */
  private def targetOf(host: ContestJury, identity: ContestIdentity, contests: Seq[ContestJury],
      series: Seq[db.scalikejdbc.Series]): Option[Target] =
    for {
      kind <- identity.kind
      code <- SeriesIdentity.typedCode(kind)
      year <- identity.year
    } yield {
      val seriesType = SeriesIdentity.typeOfCode(code)
      val place: SeriesIdentity.Place =
        if (isInternational(identity.country)) SeriesIdentity.International
        else identity.country.map { c =>
          ContestIdentity.countryCode(ContestIdentity(None, None, Some(c)))
            .fold[SeriesIdentity.Place](SeriesIdentity.Unusual(c))(code => SeriesIdentity.Code(code.toLowerCase(Locale.ROOT)))
        }.getOrElse(SeriesIdentity.International)
      val existing = series.find(s => SeriesIdentity.seriesOf(s, seriesType, place))
      val candidates = contests.filter { c =>
        !c.retired && !c.id.contains(host.getId) && c.year == year && (existing match {
          case Some(s) if c.seriesId.contains(s.id) => true
          case _ => c.seriesId.isEmpty && SeriesIdentity.typeOf(c).exists(_.code == seriesType.code) && SeriesIdentity.placeOf(c) == place
        })
      }.sortBy(c => (!existing.exists(s => c.seriesId.contains(s.id)), c.getId)).flatMap(_.id)
      Target(identity, seriesType, place, existing.map(_.id), existing.fold(SeriesIdentity.suggestedName(seriesType, place))(_.name),
        year, candidates)
    }

  // ── reads ──────────────────────────────────────────────────────────────────

  private[series] final case class RoundRow(id: Long, number: Long, name: Option[String], previous: Seq[Long],
      category: Option[String], status: Option[String], active: Boolean, distributing: Boolean,
      statusChangedBy: Option[Long]) {
    def lifecycle: String = status.getOrElse(Round.Status.ofFlags(distributing = distributing, open = active))
  }

  /** The contest's rounds but cancelled creations, by id (as the inline check reads them). */
  private[series] def roundsOf(contestId: Long)(implicit session: DBSession): Seq[RoundRow] =
    sql"""SELECT id, number, name, previous, category, status, active, distributing, status_changed_by FROM round
          WHERE contest_id = $contestId AND (status IS NULL OR BINARY status <> 'cancelled') ORDER BY id"""
      // `active` is NULL in some old rounds (26 on the production copy, 2026-11-20)
      .map(rs => RoundRow(rs.long(1), rs.longOpt(2).getOrElse(0L), rs.stringOpt(3).map(_.trim).filter(_.nonEmpty),
        Round.parseIds(rs.stringOpt(4)), rs.stringOpt(5).map(_.trim).filter(_.nonEmpty), rs.stringOpt(6), rs.booleanOpt(7).getOrElse(false),
        rs.booleanOpt(8).getOrElse(false), rs.longOpt(9)))
      .list.apply()

  /** The rounds in clusters: connected under `previous` within the contest, each by id, the
    * clusters by their first round.
    */
  private[series] def clustersOf(rounds: Seq[RoundRow]): Seq[Seq[RoundRow]] = {
    val ids = rounds.map(_.id).toSet
    val parent = scala.collection.mutable.Map(rounds.map(r => r.id -> r.id): _*)
    def find(x: Long): Long = { val p = parent(x); if (p == x) x else { val root = find(p); parent(x) = root; root } }
    for (r <- rounds; p <- r.previous if ids(p)) {
      val (a, b) = (find(r.id), find(p))
      if (a != b) parent(math.max(a, b)) = math.min(a, b)
    }
    rounds.groupBy(r => find(r.id)).toSeq.sortBy(_._1).map(_._2.sortBy(_.id))
  }

  private def roundSizes(roundIds: Seq[Long])(implicit session: DBSession): Map[Long, Long] =
    if (roundIds.isEmpty) Map.empty
    else
      sql"SELECT round_id, COUNT(*) FROM round_image WHERE round_id IN ($roundIds) GROUP BY round_id"
        .map(rs => rs.long(1) -> rs.long(2)).list.apply().toMap

  /** The rounds' files, each once (by `round_image`'s key). */
  private def distinctFiles(roundIds: Seq[Long])(implicit session: DBSession): Long =
    sql"SELECT COUNT(DISTINCT page_id) FROM round_image WHERE round_id IN ($roundIds)".map(_.long(1)).single.apply().getOrElse(0L)

  /** The rounds' files in any of the categories, each once (the keys of [[categoryCountsQuery]]). */
  private def distinctFiles(roundIds: Seq[Long], categoryIds: Seq[Long])(implicit session: DBSession): Long =
    sql"""SELECT COUNT(DISTINCT ri.page_id) FROM round_image ri
             STRAIGHT_JOIN category_members cm ON cm.page_id = ri.page_id
           WHERE ri.round_id IN ($roundIds) AND cm.category_id IN ($categoryIds)"""
      .map(_.long(1)).single.apply().getOrElse(0L)

  /** The round's median page id: by its key, `files` its row count. */
  private def medianPage(roundId: Long, files: Long)(implicit session: DBSession): Option[Long] =
    sql"SELECT page_id FROM round_image WHERE round_id = $roundId ORDER BY page_id LIMIT 1 OFFSET ${files / 2}"
      .map(_.long(1)).single.apply()

  private final case class Votes(count: Long, firstYear: Option[Int], lastYear: Option[Int])

  /** Each round's votes, and the years of the first and last that has its time (since V97). */
  private def roundVotes(roundIds: Seq[Long])(implicit session: DBSession): Map[Long, Votes] =
    if (roundIds.isEmpty) Map.empty
    else
      sql"""SELECT round_id, COUNT(*), YEAR(MIN(voted_at)), YEAR(MAX(voted_at)) FROM vote
            WHERE round_id IN ($roundIds) GROUP BY round_id"""
        .map(rs => rs.long(1) -> Votes(rs.long(2), rs.intOpt(3), rs.intOpt(4))).list.apply().toMap

  /** Each round's comments (the contest index). */
  private def roundComments(contestId: Long)(implicit session: DBSession): Map[Long, Long] =
    sql"SELECT round_id, COUNT(*) FROM comment WHERE contest_id = $contestId AND round_id IS NOT NULL GROUP BY round_id"
      .map(rs => rs.long(1) -> rs.long(2)).list.apply().toMap

  /** Each round's jobs (the contest index), and who started them. */
  private def roundJobs(contestId: Long)(implicit session: DBSession): Map[Long, (Long, Seq[Long])] =
    sql"""SELECT subject_id, created_by FROM job WHERE contest_id = $contestId AND subject_type = 'round'
            AND subject_id IS NOT NULL"""
      .map(rs => rs.long(1) -> rs.longOpt(2)).list.apply()
      .groupBy(_._1).map { case (id, rows) => id -> (rows.size.toLong, rows.flatMap(_._2).distinct) }

  /** Each round's active jurors. */
  private def roundJurors(roundIds: Seq[Long])(implicit session: DBSession): Map[Long, Seq[Long]] =
    if (roundIds.isEmpty) Map.empty
    else
      sql"SELECT round_id, user_id FROM round_user WHERE round_id IN ($roundIds) AND active = TRUE"
        .map(rs => rs.long(1) -> rs.long(2)).list.apply()
        .groupBy(_._1).map { case (id, rows) => id -> rows.map(_._2).distinct }

  /** The files query of a cluster: its rounds' files by each category they are in other than the
    * host's own, each file once per category; `round_image` by its key, then `category_members` by
    * its page index (ReuseReportSpec EXPLAINs it).
    */
  private[series] def categoryCountsQuery(roundIds: Seq[Long], own: Option[Long]): SQLSyntax =
    sqls"""SELECT cm.category_id, COUNT(DISTINCT ri.page_id) FROM round_image ri
             STRAIGHT_JOIN category_members cm ON cm.page_id = ri.page_id
           WHERE ri.round_id IN ($roundIds) AND NOT (cm.category_id <=> $own)
           GROUP BY cm.category_id"""

  private def categoryCounts(roundIds: Seq[Long], own: Option[Long])(implicit session: DBSession): Map[Long, Long] =
    if (roundIds.isEmpty) Map.empty
    else sql"${categoryCountsQuery(roundIds, own)}".map(rs => rs.long(1) -> rs.long(2)).list.apply().toMap

  /** The buckets query: the cluster's files, those in a category of the target, and those in a
    * category naming any identity (the same keys as [[categoryCountsQuery]]).
    */
  private[series] def bucketsQuery(roundIds: Seq[Long], matching: Seq[Long], classified: Seq[Long]): SQLSyntax = {
    val m = if (matching.isEmpty) Seq(-1L) else matching
    val c = if (classified.isEmpty) Seq(-1L) else classified
    sqls"""SELECT COUNT(DISTINCT ri.page_id),
                  COUNT(DISTINCT CASE WHEN cm.category_id IN ($m) THEN ri.page_id END),
                  COUNT(DISTINCT CASE WHEN cm.category_id IS NOT NULL THEN ri.page_id END)
           FROM round_image ri
             LEFT JOIN category_members cm ON cm.page_id = ri.page_id AND cm.category_id IN ($c)
           WHERE ri.round_id IN ($roundIds)"""
  }

  private def bucketsOf(roundIds: Seq[Long], matching: Seq[Long], classified: Seq[Long], files: Long)(implicit
      session: DBSession): Buckets =
    if (roundIds.isEmpty || files == 0) Buckets(0, 0, 0)
    else
      sql"${bucketsQuery(roundIds, matching, classified)}".map { rs =>
        val (total, m, c) = (rs.long(1), rs.long(2), rs.long(3))
        Buckets(m, c - m, total - c)
      }.single.apply().getOrElse(Buckets(0, 0, 0))

  /** The files of the moving clusters' rounds in none of the staying ones' (CA10: what the source
    * would keep linked only for rounds that left it). Clusters for root's choice count as staying.
    */
  private def unusedInSource(moving: Seq[Long], staying: Seq[Long])(implicit session: DBSession): Long =
    if (moving.isEmpty) 0L
    else {
      val stay = if (staying.isEmpty) Seq(-1L) else staying
      sql"""SELECT COUNT(DISTINCT ri.page_id) FROM round_image ri WHERE ri.round_id IN ($moving)
              AND NOT EXISTS (SELECT 1 FROM round_image s WHERE s.page_id = ri.page_id AND s.round_id IN ($stay))"""
        .map(_.long(1)).single.apply().getOrElse(0L)
    }

  private final case class Account(id: Long, name: String, deleted: Boolean, root: Boolean)

  private def accounts(ids: Seq[Long])(implicit session: DBSession): Map[Long, Account] =
    if (ids.isEmpty) Map.empty
    else
      sql"SELECT id, fullname, deleted_at IS NOT NULL, is_root FROM users WHERE id IN ($ids)"
        .map(rs => Account(rs.long(1), rs.stringOpt(2).getOrElse(""), rs.boolean(3), rs.boolean(4))).list.apply()
        .map(a => a.id -> a).toMap

  /** The accounts' roles in the contests, by (account, contest). */
  private def membershipsOf(ids: Seq[Long], contestIds: Seq[Long])(implicit session: DBSession): Map[(Long, Long), String] =
    if (ids.isEmpty || contestIds.isEmpty) Map.empty
    else
      sql"SELECT user_id, contest_id, role FROM user_contest WHERE contest_id IN ($contestIds) AND user_id IN ($ids)"
        .map(rs => (rs.long(1), rs.long(2)) -> rs.string(3)).list.apply().toMap

  // ── the scan's summary ─────────────────────────────────────────────────────

  /** One contest's line of the scan (kept in the job's state): ids and counts only. */
  final case class Summary(
      contestId: Long,
      name: String,
      year: Int,
      country: String,
      retired: Boolean,
      seriesId: Option[Long],
      verdict: String,
      rounds: Int,
      clusters: Int,
      kinds: Map[String, Int],
      actions: Map[String, Int],
      mixed: Int,
      empty: Int,
      moves: Int,
      r4Host: Boolean,
      activeToMove: Seq[Long],
      signs: Seq[String],
      filesToLink: Long,
      jurorsToAdd: Int,
      onlyMovedJurors: Int,
      unusedInSource: Long,
      rowsToMove: Long
  )

  object Summary {
    implicit val format: OFormat[Summary] = Json.format[Summary]
  }

  def summary(r: Report): Summary = {
    val c = r.contest
    Summary(
      contestId = c.getId, name = c.name, year = c.year, country = c.country, retired = c.retired, seriesId = c.seriesId,
      verdict = r.verdict,
      rounds = r.clusters.map(_.rounds.size).sum,
      clusters = r.clusters.size,
      kinds = r.clusters.groupBy(_.kind).view.mapValues(_.size).toMap,
      actions = r.clusters.groupBy(_.action).view.mapValues(_.size).toMap,
      mixed = r.clusters.count(_.mixed),
      empty = r.clusters.count(_.empty),
      moves = r.moveCount,
      r4Host = r.r4Host,
      activeToMove = r.moves.flatMap(_.activeRounds),
      signs = r.signs.map(ContestReuse.kindKey).distinct.map(_.stripPrefix("series.reuse.kind.")),
      filesToLink = r.moves.map(_.buckets.matching).sum,
      jurorsToAdd = r.jurorsToAdd,
      onlyMovedJurors = r.onlyMovedJurors,
      unusedInSource = r.unusedInSource,
      rowsToMove = r.moves.map(m => m.rounds.size + m.comments + m.jobs).sum
    )
  }
}
