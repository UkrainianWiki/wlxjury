package services

import db.scalikejdbc.{CategoryJdbc, ContestChangeJdbc, ContestJuryJdbc, ImageJdbc, ListRepair,
  ListRepairJdbc, RegionCountCache, Round}
import org.intracer.wmua.ContestJury
import play.api.Logger
import play.api.libs.json.Json
import scalikejdbc._
import services.imports.ContestCountries

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Root's repair of a contest's file list (the "Repair file list" pages, V114).
  *
  * A contest's file list is its `contest_jury.category_id` row, its files in `category_members`.
  * Since 2017 an import found or added that row by title: every unnamed import shared the row of
  * the empty title (one row of several countries' files, the list of unrelated contests), and a
  * source set by title gave a contest another contest's row. The guards of the shared-category
  * fix stop new damage (CategoryJdbc.findOrInsert refuses a blank title; an import puts the files
  * into the contest's own "#contest N" row); this repairs the old lists, one contest at a time.
  *
  * The new list is the files the contest's own rounds show (the rounds root counts), of its old
  * list row, plus the files already in its own row. A contest already on its own row (an import
  * after the guards) takes them from the shared row its rounds' files were left in. Rounds,
  * `round_image` and votes never change. Each repair is logged (`contest_change` and
  * `list_repair`, with the files it added), and its Undo takes out only those files, refused if the
  * list changed since.
  *
  * A row is shared as [[CategoryLinkJdbc.mayCopyFrom]] has it: another contest has it as its
  * category, as its source (`images`, the very title, underscores as spaces) or by V24's name for
  * it ("Images from <name> <year> in <country>", the title with or without `Category:`, any
  * case); a blank-titled row ([[CategoryJdbc.isBlank]]), or one renamed [[LegacyTitle]], is
  * never a contest's own.
  */
object FileListRepair {

  private val log = Logger("round.admin")

  /** The title of the blank row once no contest has it: kept for audit. */
  val LegacyTitle = "#unnamed legacy import"

  /** A list row: its id and title. */
  final case class Row(id: Long, title: Option[String]) {
    def blank: Boolean = CategoryJdbc.isBlank(title.orNull)
    /** Blank, or the blank row renamed for audit ([[LegacyTitle]]). */
    def legacy: Boolean = blank || title.exists(_.startsWith(LegacyTitle))
  }

  /** A contest whose list needs a repair.
    *
    * @param row      its list row; for a contest already on its own row ([[ownRow]]), the shared
    *                 row its rounds' files were left in
    * @param others   the other contests that have the row (as their list, source or V24 name)
    * @param size     the row's files
    * @param ownRow   the contest is already on its own row
    * @param left     for one on its own row: its rounds' files in the row but not in its list
    * @param likelyOwner of a titled shared row, the contest it most likely belongs to: its repair
    *                 drops the row's files its rounds never showed
    */
  final case class Candidate(contestId: Long, row: Row, others: Seq[Long], size: Int, ownRow: Boolean = false, left: Int = 0,
      likelyOwner: Boolean = false)

  /** A blank or shared row no contest has as its list any more; `mayRename`: a blank one. */
  final case class Orphan(row: Row, size: Int) {
    def mayRename: Boolean = row.blank
  }

  final case class Overview(shared: Seq[Candidate], onOwnRow: Seq[Candidate], orphans: Seq[Orphan],
      contests: Map[Long, ContestJury], recent: Seq[ListRepair])

  /** A round of the contest on the repair page.
    *
    * @param files     its files (`round_image`, any status)
    * @param inSource  of them, those in the row the files come from
    * @param mixedWith the other contests sharing the row of whose files (those of the row their rounds
    *                  show) this round shows at least half ([[MixedShare]])
    */
  final case class RoundInfo(round: Round, files: Int, inSource: Int, mixedWith: Seq[Long]) {
    /** Pre-unticked: it shows most of the files of at least two other contests sharing the row,
      * as a round filled from the whole shared row does; a contest's own round shows few of
      * another's (directional: a whole-list round elsewhere never unticks it).
      */
    def mixed: Boolean = mixedWith.size >= MixedContests
  }

  /** The other contests most of whose files a round must show to be pre-unticked. */
  val MixedContests = 2

  /** "Most": the share of another contest's files a round must show to count it ([[RoundInfo.mixedWith]]). */
  val MixedShare = 0.5

  /** What a repair of the contest would do, as the page shows it and the confirmation checks.
    *
    * @param current  the contest's list row now
    * @param source   the row the files come from: the old list, or (a contest on its own row) the
    *                 shared row its rounds' files were left in
    * @param own      its own row, if it has one
    * @param others   the other contests that have the source row
    * @param counted  the rounds whose files are counted
    * @param now      the files of its list now
    * @param stays    the source's files that the counted rounds show
    * @param leaves   the source's other files
    * @param ownFiles the files already in its own row (none if that is the source)
    * @param countries the files' countries, by page id
    * @param affected the list needs a repair: its row is blank or shared, or it is on its own row
    *                 and its rounds' files were left in a shared one
    */
  final case class Plan(contest: ContestJury, current: Row, source: Row, own: Option[Row], others: Seq[Long],
      rounds: Seq[RoundInfo], counted: Seq[Long], now: Seq[Long], stays: Seq[Long], leaves: Seq[Long],
      ownFiles: Seq[Long], countries: Map[Long, String], affected: Boolean) {

    def onOwnRow: Boolean = own.exists(_.id == current.id)

    /** The files the repair would add to the own row. */
    def added: Seq[Long] = { val there = ownFiles.toSet; stays.filterNot(there) }

    /** The size of the list after the repair. */
    def newSize: Int = ownFiles.size + added.size

    /** The plan's hash: what the confirmation must match (the contest, its list, the source row,
      * the counted rounds and the files that stay).
      */
    lazy val hash: String = sha256(s"${contest.getId}|${current.id}|${source.id}|${counted.sorted.mkString(",")}|" +
      stays.mkString(","))

    /** The files of each country, the most first; "" for files of no country. */
    def byCountry(pageIds: Seq[Long]): Seq[(String, Int)] =
      pageIds.groupMapReduce(countries.getOrElse(_, ""))(_ => 1)(_ + _).toSeq.sortBy { case (c, n) => (c.isEmpty, -n, c) }
  }

  def sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)).map("%02x".format(_)).mkString

  /** The hash of a list's sorted files: what Undo compares. */
  def listHash(pageIds: Seq[Long]): String = sha256(pageIds.sorted.mkString(","))

  // ── reading ────────────────────────────────────────────────────────────────

  private def members(categoryId: Long)(implicit session: DBSession): Seq[Long] =
    sql"SELECT page_id FROM category_members WHERE category_id = $categoryId ORDER BY page_id".map(_.long(1)).list.apply()

  private def row(id: Long)(implicit session: DBSession): Option[Row] =
    sql"SELECT id, title FROM category WHERE id = $id".map(rs => Row(rs.long(1), rs.stringOpt(2))).single.apply()

  /** The contest's own row, if there is one (by its title, as CategoryJdbc.findOrInsert finds it). */
  private def ownRowOf(contestId: Long)(implicit session: DBSession): Option[Row] = {
    val title = ImageService.ownCategory(contestId)
    sql"""SELECT id, title FROM category WHERE title = $title OR REPLACE(title, '_', ' ') = ${title.replace('_', ' ')}
          ORDER BY title = $title DESC, id LIMIT 1""".map(rs => Row(rs.long(1), rs.stringOpt(2))).single.apply()
  }

  /** Each contest with a list row: the row, and the other contests that have it (as their list,
    * their source or by V24's name: [[CategoryLinkJdbc.mayCopyFrom]]'s notion, for every contest
    * at once), of `contestId` only if given.
    */
  private def listRows(contestId: Option[Long])(implicit session: DBSession): Seq[(Long, Row, Seq[Long])] = {
    val only = contestId.fold(sqls"")(id => sqls"AND cj.id = $id")
    sql"""SELECT cj.id, c.id, c.title, GROUP_CONCAT(DISTINCT o.id ORDER BY o.id)
          FROM contest_jury cj
          JOIN category c ON c.id = cj.category_id
          LEFT JOIN contest_jury o ON o.id <> cj.id
            AND (o.category_id = c.id
              OR REPLACE(TRIM(o.images), '_', ' ') = REPLACE(TRIM(c.title), '_', ' ')
              OR CONCAT('Images from ', o.name, ' ', o.year, ' in ', o.country) =
                REPLACE(IF(LOWER(LEFT(TRIM(c.title), 9)) = 'category:', SUBSTRING(TRIM(c.title), 10), TRIM(c.title)),
                  '_', ' '))
          WHERE TRUE $only
          GROUP BY cj.id, c.id, c.title
          ORDER BY cj.id"""
      .map(rs => (rs.long(1), Row(rs.long(2), rs.stringOpt(3)),
        rs.stringOpt(4).toSeq.flatMap(_.split(',')).filter(_.nonEmpty).map(_.toLong)))
      .list.apply()
  }

  private def sizes(ids: Seq[Long])(implicit session: DBSession): Map[Long, Int] =
    if (ids.isEmpty) Map.empty
    else
      sql"SELECT category_id, COUNT(*) FROM category_members WHERE category_id IN (${ids.distinct}) GROUP BY category_id"
        .map(rs => rs.long(1) -> rs.int(2)).list.apply().toMap

  /** The blank or legacy rows and the rows repairs took files from that no contest has as its list. */
  private def orphanRows()(implicit session: DBSession): Seq[Row] =
    sql"""SELECT c.id, c.title FROM category c
          WHERE (c.title IS NULL OR c.title REGEXP '^[[:space:]_]*$$' OR c.title LIKE ${LegacyTitle + "%"}
                 OR c.id IN (SELECT source_category_id FROM list_repair)
                 OR c.id IN (SELECT old_category_id FROM list_repair WHERE old_category_id IS NOT NULL))
            AND (c.title IS NULL OR c.title NOT LIKE '#contest %')
            AND NOT EXISTS (SELECT 1 FROM contest_jury cj WHERE cj.category_id = c.id)
          ORDER BY c.id""".map(rs => Row(rs.long(1), rs.stringOpt(2))).list.apply()

  /** Whether a contest's list row needs a repair: blank or legacy, or another contest has it. */
  private def needsRepair(contestId: Long, row: Row, others: Seq[Long]): Boolean =
    !row.title.contains(ImageService.ownCategory(contestId)) && (row.legacy || others.nonEmpty)

  /** The contests on their own row whose rounds show files of the `rows` that their list lacks:
    * (contest, row, files), but for the pairs a repair not undone took the files from.
    */
  private def leftInRows(rows: Seq[Long], contestId: Option[Long])(implicit session: DBSession): Seq[(Long, Long, Int)] =
    if (rows.isEmpty) Nil
    else {
      val only = contestId.fold(sqls"")(id => sqls"AND cj.id = $id")
      val found = sql"""SELECT cj.id, m.category_id, COUNT(DISTINCT m.page_id)
            FROM category_members m
            STRAIGHT_JOIN round_image ri ON ri.page_id = m.page_id
            STRAIGHT_JOIN round r ON r.id = ri.round_id
            STRAIGHT_JOIN contest_jury cj ON cj.id = r.contest_id
            STRAIGHT_JOIN category own ON own.id = cj.category_id AND own.title = CONCAT('#contest ', cj.id)
            WHERE m.category_id IN ($rows) $only
              AND NOT EXISTS (SELECT 1 FROM category_members w WHERE w.category_id = cj.category_id AND w.page_id = m.page_id)
            GROUP BY cj.id, m.category_id"""
        .map(rs => (rs.long(1), rs.long(2), rs.int(3))).list.apply()
      val repaired = ListRepairJdbc.live().map(r => (r.contestId, r.sourceCategoryId)).toSet
      found.filterNot { case (c, rowId, _) => repaired((c, rowId)) }
    }

  /** The contests whose lists need a repair, the rows no contest has any more, and the latest repairs. */
  def overview()(implicit session: DBSession = AutoSession): Overview = {
    val lists = listRows(None)
    val shared = lists.filter { case (c, row, others) => needsRepair(c, row, others) }
    val orphans = orphanRows()
    val affectedRows = (shared.map(_._2) ++ orphans).map(_.id).distinct
    val left = leftInRows(affectedRows, None)
    val othersOf = lists.groupMap(_._2.id)(_._1)
    val rowById = (shared.map(_._2) ++ orphans).map(r => r.id -> r).toMap
    val size = sizes(affectedRows)
    val onOwn = left.groupBy(_._1).toSeq.map { case (contestId, rows) =>
      val (_, rowId, n) = rows.maxBy { case (_, id, files) => (files, -id) }
      Candidate(contestId, rowById(rowId), othersOf.getOrElse(rowId, Nil).filterNot(_ == contestId), size.getOrElse(rowId, 0),
        ownRow = true, left = n)
    }.sortBy(_.contestId)
    val candidates = shared.map { case (c, row, others) => Candidate(c, row, others, size.getOrElse(row.id, 0)) }
    val recent = ListRepairJdbc.recent(20)
    val contestIds = ((candidates ++ onOwn).flatMap(c => c.contestId +: c.others) ++ recent.map(_.contestId)).toSet
    val contests = ContestJuryJdbc.findAll().filter(c => contestIds(c.getId)).map(c => c.getId -> c).toMap
    val marked = candidates.map(c => c.copy(likelyOwner = likelyOwner(c, othersOf.getOrElse(c.row.id, Nil), contests)))
    Overview(marked, onOwn, orphans.map(r => Orphan(r, size.getOrElse(r.id, 0))), contests, recent)
  }

  /** Whether the candidate is the likely owner of its titled shared row (never of an untitled
    * one): a contest whose source or V24 name is the row's title; with none, the oldest (year, then
    * id) of those that have it as their list.
    */
  private def likelyOwner(c: Candidate, pointing: Seq[Long], contests: Map[Long, ContestJury]): Boolean =
    !c.row.legacy && c.others.nonEmpty && {
      val title = c.row.title.getOrElse("").trim.replace('_', ' ')
      val bare = (if (title.toLowerCase.startsWith("category:")) title.drop(9) else title).trim
      def names(o: ContestJury): Boolean =
        o.images.exists(_.trim.replace('_', ' ').equalsIgnoreCase(title)) ||
          s"Images from ${o.name} ${o.year} in ${o.country}".equalsIgnoreCase(bare)
      val group = (c.contestId +: c.others).distinct.flatMap(contests.get)
      group.filter(names) match {
        case Seq()  => (c.contestId +: pointing).distinct.flatMap(contests.get).minByOption(o => (o.year, o.getId)).exists(_.getId == c.contestId)
        case naming => naming.exists(_.getId == c.contestId)
      }
    }

  /** What a repair of the contest would do, with the rounds `counted` (None: every round but the
    * mixed ones, [[RoundInfo.mixed]]; other contests' rounds ignored). Left: the message key of why
    * there is nothing to repair (no such contest, no list, on its own row with nothing left).
    */
  def plan(contestId: Long, counted: Option[Seq[Long]])(implicit session: DBSession = AutoSession): Either[String, Plan] =
    ContestJuryJdbc.findById(contestId).toRight("error.contest.not.found").flatMap { contest =>
      contest.categoryId.flatMap(row).toRight("list.repair.no.list").flatMap { current =>
        val own = ownRowOf(contestId)
        val sourceAndOthers: Either[String, (Row, Seq[Long], Boolean)] =
          if (own.exists(_.id == current.id)) {
            // on its own row: the shared row its rounds' files were left in, the most of them
            val lists = listRows(None)
            val rows = (lists.filter { case (c, r, o) => needsRepair(c, r, o) }.map(_._2) ++ orphanRows()).map(r => r.id -> r).toMap
            leftInRows(rows.keys.toSeq, Some(contestId)).sortBy { case (_, id, n) => (-n, id) }.headOption
              .map { case (_, id, _) => (rows(id), lists.collect { case (c, r, _) if r.id == id => c }, true) }
              .toRight("list.repair.nothing")
          } else {
            val others = listRows(Some(contestId)).headOption.fold(Seq.empty[Long])(_._3)
            Right((current, others, needsRepair(contestId, current, others)))
          }
        sourceAndOthers.map { case (source, others, affected) =>
          build(contest, current, source, own.filterNot(_.id == source.id), others, affected, counted)
        }
      }
    }

  private def build(contest: ContestJury, current: Row, source: Row, own: Option[Row], others: Seq[Long],
      affected: Boolean, chosen: Option[Seq[Long]])(implicit session: DBSession): Plan = {
    val contestId = contest.getId
    // the contests sharing the source row: those that have it, and those whose repair took files from it
    val repairedFrom = sql"SELECT DISTINCT contest_id FROM list_repair WHERE source_category_id = ${source.id} AND undone_at IS NULL"
      .map(_.long(1)).list.apply()
    val pointing = sql"SELECT id FROM contest_jury WHERE category_id = ${source.id}".map(_.long(1)).list.apply()
    val group = (Seq(contestId) ++ others ++ pointing ++ repairedFrom).distinct
    val rounds = Round.where("contestId" -> contestId).orderBy(Round.r.id).apply()
    val roundIds = rounds.flatMap(_.id)
    val roundCounts =
      if (roundIds.isEmpty) Map.empty[Long, (Int, Int)]
      else
        sql"""SELECT ri.round_id, COUNT(*), SUM(m.page_id IS NOT NULL) FROM round_image ri
              LEFT JOIN category_members m ON m.category_id = ${source.id} AND m.page_id = ri.page_id
              WHERE ri.round_id IN ($roundIds) GROUP BY ri.round_id"""
          .map(rs => rs.long(1) -> (rs.int(2), rs.int(3))).list.apply().toMap
    // the source row's files each round of the group shows: (round, its contest, file)
    val shown =
      sql"""SELECT ri.round_id, r.contest_id, ri.page_id
            FROM category_members m
            STRAIGHT_JOIN round_image ri ON ri.page_id = m.page_id
            STRAIGHT_JOIN round r ON r.id = ri.round_id
            WHERE m.category_id = ${source.id} AND r.contest_id IN ($group)"""
        .map(rs => (rs.long(1), rs.long(2), rs.long(3))).list.apply()
    val mixedWith = mixedRounds(shown)
    val infos = rounds.map { r =>
      val (files, inSource) = roundCounts.getOrElse(r.getId, (0, 0))
      RoundInfo(r, files, inSource, mixedWith.getOrElse(r.getId, Nil))
    }
    val counted: Seq[Long] = chosen.fold[Seq[Long]](infos.filterNot(_.mixed).map(_.round.getId))(_.filter(roundIds.toSet).distinct).sorted
    val sourceFiles = members(source.id)
    val stays =
      if (counted.isEmpty) Nil
      else
        sql"""SELECT m.page_id FROM category_members m WHERE m.category_id = ${source.id}
              AND EXISTS (SELECT 1 FROM round_image ri WHERE ri.page_id = m.page_id AND ri.round_id IN ($counted))
              ORDER BY m.page_id""".map(_.long(1)).list.apply()
    val staysSet = stays.toSet
    val ownFiles = own.fold(Seq.empty[Long])(o => members(o.id))
    val now = if (current.id == source.id) sourceFiles else members(current.id)
    val mixed = mixedWith.collect { case (id, others) if others.size >= MixedContests => id }.toSet
    val countries = countriesOf(contest, group, source.id, own.map(_.id),
      shown.collect { case (round, c, page) if !mixed(round) => page -> c })
    Plan(contest, current, source, own, others, infos, counted, now, stays, sourceFiles.filterNot(staysSet), ownFiles,
      countries, affected)
  }

  /** Of each round (of `shown`: round, its contest, a file of the row), the other contests of whose
    * files (those any of their rounds show) it shows at least [[MixedShare]].
    */
  def mixedRounds(shown: Seq[(Long, Long, Long)]): Map[Long, Seq[Long]] = {
    val byContest = shown.groupMap(_._2)(_._3).view.mapValues(_.toSet).toMap
    shown.groupBy(_._1).map { case (round, rows) =>
      val contest = rows.head._2
      val files = rows.map(_._3).toSet
      round -> byContest.collect {
        case (other, theirs) if other != contest && theirs.nonEmpty && theirs.count(files) >= MixedShare * theirs.size => other
      }.toSeq.sorted
    }
  }

  /** The files' countries, by page id: a file's country category as the files page reads it
    * ([[ContestCountries.fileCountries]] with the prefixes of every contest sharing the row, this
    * contest's first); else the country of the contest sharing the row whose rounds (but mixed
    * ones) show it, if they are of one country; a file of the own row, this contest's country.
    */
  private def countriesOf(contest: ContestJury, group: Seq[Long], sourceId: Long, ownId: Option[Long],
      shownBy: Seq[(Long, Long)])(implicit session: DBSession): Map[Long, String] = {
    val contests = ContestJuryJdbc.findAll().filter(c => group.contains(c.getId))
      .sortBy(c => if (c.getId == contest.getId) 0 else 1)
    val prefixes = contests.flatMap(ContestCountries.prefixes).distinct
    val links = (Seq(sourceId) ++ ownId).flatMap(ImageJdbc.categoryCountryLinks(_, prefixes))
    val byCategory = contests.reverse.foldLeft(Map.empty[Long, String])((m, c) => m ++ ContestCountries.fileCountries(c, links))
    val countryOf = contests.map(c => c.getId -> c.country.trim).toMap
    val byRounds = shownBy.distinct.groupMap(_._1)(p => countryOf.getOrElse(p._2, "")).collect {
        case (pageId, cs) if cs.distinct.size == 1 && cs.head.nonEmpty => pageId -> cs.head
      }
    val ownCountry = Option(contest.country.trim).filter(_.nonEmpty)
    val ofOwn = (for (id <- ownId.toSeq; c <- ownCountry.toSeq; p <- members(id)) yield p -> c).toMap
    ofOwn ++ byRounds ++ byCategory
  }

  // ── writing ────────────────────────────────────────────────────────────────

  private def lockContest(contestId: Long)(implicit session: DBSession): Boolean =
    sql"SELECT id FROM contest_jury WHERE id = $contestId FOR UPDATE".map(_.long(1)).single.apply().isDefined

  /** Rolls a confirmation back: the files differ from the preview's. */
  private object Stale extends RuntimeException("list changed", null, false, false)

  /** The job kinds that read or write the contest's list while they run: an import (its files go
    * into the list) and a round's distribution (which reads the list's category once per run).
    */
  val BusyKinds: Seq[String] = Seq(services.imports.ImageImportHandler.Kind, services.distribution.RoundDistributionHandler.Kind)

  /** Whether the contest has an import or a distribution queued or running ([[BusyKinds]]). */
  private def busy(contestId: Long)(implicit session: DBSession): Boolean =
    sql"""SELECT COUNT(*) FROM job WHERE contest_id = $contestId AND kind IN ($BusyKinds)
          AND status IN (${services.jobs.JobStatus.active.toSeq})""".map(_.long(1)).single.apply().exists(_ > 0)

  /** A deadlock or a lock wait timeout (MariaDB 1213, 1205), anywhere in the causes: another
    * transaction had the rows; nothing was changed.
    */
  private def lockConflict(t: Throwable): Boolean =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).exists {
      case e: java.sql.SQLException =>
        e.getErrorCode == 1213 || e.getErrorCode == 1205 || e.isInstanceOf[java.sql.SQLTransactionRollbackException]
      case _ => false
    }

  /** A duplicate key (MariaDB 1062), anywhere in the causes. */
  private def duplicate(t: Throwable): Boolean =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).exists {
      case e: java.sql.SQLException => e.getErrorCode == 1062 || e.isInstanceOf[java.sql.SQLIntegrityConstraintViolationException]
      case _ => false
    }

  /** Repairs the contest's list as previewed (`hash`, [[Plan.hash]]) with the rounds `counted`, in
    * one transaction: its own row (added if there is none) gets the files that stay, becomes its
    * list, and the repair is logged with the files it added. Left: the message key of the refusal
    * (the list changed since the preview or another transaction had it, an import or a
    * distribution is queued or running, it needs no repair, no such contest). Right: the repair.
    */
  def confirm(contestId: Long, counted: Seq[Long], hash: String, actorId: Long): Either[String, ListRepair] = {
    val result = try DB.localTx { implicit session =>
      if (!lockContest(contestId)) Left("error.contest.not.found")
      else if (busy(contestId)) Left("list.repair.busy")
      else plan(contestId, Some(counted)).flatMap { p =>
        if (!p.affected) Left("list.repair.not.affected")
        else if (p.hash != hash) Left("list.repair.changed")
        else {
          val ownId = CategoryJdbc.findOrInsert(ImageService.ownCategory(contestId))
          val there = members(ownId)
          val added = { val thereSet = there.toSet; p.stays.filterNot(thereSet) }
          // the files go in by INSERT ... SELECT (a JDBC batch is a round trip per row: 4.4 s for
          // 38,000 files), checked against the preview's: a round's files changed meanwhile, nothing
          val id = ListRepairJdbc.create(contestId, Some(p.current.id), p.source.id, ownId, p.counted, actorId)
          val recorded = ListRepairJdbc.recordAdded(id, p.source.id, ownId, p.counted)
          if (recorded != added.size) throw Stale
          ListRepairJdbc.linkAdded(id, ownId)
          ContestJuryJdbc.setCategory(contestId, ownId)
          ListRepairJdbc.setOutcome(id, added.size, listHash(members(ownId)))
          ContestChangeJdbc.create(Some(contestId), p.contest.seriesId, actorId, ContestChangeJdbc.FilesRepaired,
            detail = Json.obj("repair" -> id, "oldRow" -> p.current.id, "sourceRow" -> p.source.id, "newRow" -> ownId,
              "rounds" -> p.counted, "stays" -> p.stays.size, "leaves" -> p.leaves.size, "added" -> added.size,
              "own" -> there.size))
          Right((ListRepairJdbc.findById(id).get, p))
        }
      }
    } catch {
      case Stale                           => Left("list.repair.changed")
      case e: Throwable if lockConflict(e) => Left("list.repair.changed")
    }
    result.map { case (repair, p) =>
      RegionCountCache.invalidateCategory(Seq(repair.newCategoryId, p.current.id, p.source.id).distinct: _*)
      log.info(s"files_repaired contestId=$contestId repairId=${repair.id} oldRow=${p.current.id} sourceRow=${p.source.id} " +
        s"newRow=${repair.newCategoryId} rounds=${p.counted.mkString(",")} stays=${p.stays.size} leaves=${p.leaves.size} " +
        s"added=${repair.filesAdded} by=$actorId")
      repair
    }
  }

  /** Undoes the repair: the contest's list is its old row again, and its own row loses the files
    * the repair added (only those). Refused (the message key) if it was undone already; while an
    * import or a distribution is queued or running; if the contest's list changed since (another
    * list, a later repair, a later import's files) or an import ran since (even one that added
    * nothing); or if the old row was renamed for audit ([[LegacyTitle]]: the contest would be back
    * on every unnamed import's files).
    */
  def undo(repairId: Long, actorId: Long): Either[String, ListRepair] = {
    val result = try DB.localTx { implicit session =>
      ListRepairJdbc.lock(repairId).toRight("list.repair.not.found").flatMap { r =>
        if (r.undone) Left("list.repair.undo.already")
        else if (!lockContest(r.contestId)) Left("error.contest.not.found")
        else if (busy(r.contestId)) Left("list.repair.busy")
        else {
          val contest = ContestJuryJdbc.findById(r.contestId)
          val later = ListRepairJdbc.findByContest(r.contestId).exists(o => o.id > r.id && !o.undone)
          val importedSince = sql"""SELECT COUNT(*) FROM job WHERE contest_id = ${r.contestId}
                AND kind = ${services.imports.ImageImportHandler.Kind}
                AND created_at >= (SELECT created_at FROM list_repair WHERE id = ${r.id})"""
            .map(_.long(1)).single.apply().exists(_ > 0)
          val oldRenamed = r.oldCategoryId.filter(_ != r.newCategoryId).flatMap(row)
            .exists(_.title.exists(_.startsWith(LegacyTitle)))
          if (!contest.flatMap(_.categoryId).contains(r.newCategoryId) || later ||
              listHash(members(r.newCategoryId)) != r.listHash) Left("list.repair.undo.changed")
          else if (importedSince) Left("list.repair.undo.imported")
          else if (oldRenamed) Left("list.repair.undo.renamed")
          else {
            val removed = sql"""DELETE FROM category_members WHERE category_id = ${r.newCategoryId}
                  AND page_id IN (SELECT page_id FROM list_repair_file WHERE repair_id = ${r.id})""".update.apply()
            r.oldCategoryId.filter(_ != r.newCategoryId).foreach(ContestJuryJdbc.setCategory(r.contestId, _))
            ListRepairJdbc.markUndone(r.id, actorId)
            ContestChangeJdbc.create(Some(r.contestId), contest.flatMap(_.seriesId), actorId, ContestChangeJdbc.FilesRepairUndone,
              detail = Json.obj("repair" -> r.id, "oldRow" -> r.oldCategoryId, "newRow" -> r.newCategoryId, "removed" -> removed))
            Right(r)
          }
        }
      }
    } catch { case e: Throwable if lockConflict(e) => Left("list.repair.changed") }
    result.map { r =>
      RegionCountCache.invalidateCategory((Seq(r.newCategoryId, r.sourceCategoryId) ++ r.oldCategoryId).distinct: _*)
      log.info(s"files_repair_undone contestId=${r.contestId} repairId=${r.id} oldRow=${r.oldCategoryId.getOrElse("none")} " +
        s"newRow=${r.newCategoryId} by=$actorId")
      r
    }
  }

  /** Renames a blank row no contest has as its list to [[LegacyTitle]] (with its id if that title
    * is taken), kept for audit. Left: the message key of the refusal (no such row, not blank, a
    * contest's list). Right: the new title.
    */
  def rename(rowId: Long, actorId: Long): Either[String, String] = {
    val result = try DB.localTx { implicit session =>
      sql"SELECT id, title FROM category WHERE id = $rowId FOR UPDATE".map(rs => Row(rs.long(1), rs.stringOpt(2))).single.apply()
        .toRight("list.repair.row.not.found").flatMap { r =>
          val inUse = sql"SELECT COUNT(*) FROM contest_jury WHERE category_id = $rowId".map(_.long(1)).single.apply().exists(_ > 0)
          if (!r.blank) Left("list.repair.rename.not.blank")
          else if (inUse) Left("list.repair.rename.in.use")
          else {
            val taken = sql"SELECT COUNT(*) FROM category WHERE title = $LegacyTitle".map(_.long(1)).single.apply().exists(_ > 0)
            val title = if (taken) s"$LegacyTitle #$rowId" else LegacyTitle
            sql"UPDATE category SET title = $title WHERE id = $rowId".update.apply()
            Right(title)
          }
        }
    } catch {
      // another rename took the title meanwhile, or held the rows: nothing changed
      case e: Throwable if duplicate(e) || lockConflict(e) => Left("list.repair.changed")
    }
    result.foreach(title => log.info(s"list_renamed categoryId=$rowId title=${title.replace(' ', '_')} by=$actorId"))
    result
  }

  // ── the files gallery's repair filter ──────────────────────────────────────

  /** The files gallery's `repair` parameter: which side and the counted rounds ("stays:12,15"). */
  final case class GalleryFilter(side: String, counted: Seq[Long]) {
    def param: String = s"$side:${counted.mkString(",")}"
  }

  val Sides: Seq[String] = Seq("stays", "leaves")

  object GalleryFilter {
    def parse(value: String): Option[GalleryFilter] = value.split(":", 2) match {
      case Array(side, rounds) if Sides.contains(side) =>
        val ids = rounds.split(',').map(_.trim).filter(_.nonEmpty)
        Option.when(ids.forall(_.forall(_.isDigit)) && ids.forall(_.length <= 18))(GalleryFilter(side, ids.map(_.toLong).toSeq))
      case _ => None
    }
  }

  /** The gallery's files of the filter's side of the contest's repair plan: the source row and the
    * files, with the plan.
    */
  def galleryFiles(contestId: Long, filter: GalleryFilter)(implicit session: DBSession = AutoSession): Either[String, (Plan, Seq[Long])] =
    plan(contestId, Some(filter.counted)).map(p => p -> (if (filter.side == "stays") p.stays else p.leaves))
}
