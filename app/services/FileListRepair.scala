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
    */
  final case class Candidate(contestId: Long, row: Row, others: Seq[Long], size: Int, ownRow: Boolean = false, left: Int = 0)

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
    * @param mixedWith the other contests sharing the row whose rounds show its files too
    */
  final case class RoundInfo(round: Round, files: Int, inSource: Int, mixedWith: Seq[Long]) {
    /** Pre-unticked: its files are also in the rounds of at least two other contests that share
      * the row, as a round filled from the whole shared row is.
      */
    def mixed: Boolean = mixedWith.size >= MixedContests
  }

  /** The other contests a round's files must also be in for it to be pre-unticked. */
  val MixedContests = 2

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
    Overview(candidates, onOwn, orphans.map(r => Orphan(r, size.getOrElse(r.id, 0))), contests, recent)
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
    // every round of the group whose files of the source row are in other group contests' rounds:
    // (round, its contest, the other contest)
    val shares =
      sql"""SELECT a.round_id, ra.contest_id, rb.contest_id
            FROM round ra
            STRAIGHT_JOIN round_image a ON a.round_id = ra.id
            STRAIGHT_JOIN category_members m ON m.category_id = ${source.id} AND m.page_id = a.page_id
            STRAIGHT_JOIN round_image b ON b.page_id = a.page_id
            STRAIGHT_JOIN round rb ON rb.id = b.round_id
            WHERE ra.contest_id IN ($group) AND rb.contest_id IN ($group) AND rb.contest_id <> ra.contest_id
            GROUP BY a.round_id, ra.contest_id, rb.contest_id"""
        .map(rs => (rs.long(1), rs.long(2), rs.long(3))).list.apply()
    val mixedWith = shares.groupMap(_._1)(_._3).view.mapValues(_.distinct.sorted).toMap
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
    val mixedRounds = shares.map(_._1).distinct.filter(id => mixedWith.get(id).exists(_.size >= MixedContests))
    val countries = countriesOf(contest, group, source.id, own.map(_.id), mixedRounds)
    Plan(contest, current, source, own, others, infos, counted, now, stays, sourceFiles.filterNot(staysSet), ownFiles,
      countries, affected)
  }

  /** The files' countries, by page id: a file's country category as the files page reads it
    * ([[ContestCountries.fileCountries]] with the prefixes of every contest sharing the row, this
    * contest's first); else the country of the contest sharing the row whose rounds (but mixed
    * ones) show it, if they are of one country; a file of the own row, this contest's country.
    */
  private def countriesOf(contest: ContestJury, group: Seq[Long], sourceId: Long, ownId: Option[Long],
      mixedRounds: Seq[Long])(implicit session: DBSession): Map[Long, String] = {
    val contests = ContestJuryJdbc.findAll().filter(c => group.contains(c.getId))
      .sortBy(c => if (c.getId == contest.getId) 0 else 1)
    val prefixes = contests.flatMap(ContestCountries.prefixes).distinct
    val links = (Seq(sourceId) ++ ownId).flatMap(ImageJdbc.categoryCountryLinks(_, prefixes))
    val byCategory = contests.reverse.foldLeft(Map.empty[Long, String])((m, c) => m ++ ContestCountries.fileCountries(c, links))
    val countryOf = contests.map(c => c.getId -> c.country.trim).toMap
    val notMixed = if (mixedRounds.isEmpty) sqls"" else sqls"AND ri.round_id NOT IN ($mixedRounds)"
    val byRounds = sql"""SELECT DISTINCT ri.page_id, r.contest_id FROM category_members m
          STRAIGHT_JOIN round_image ri ON ri.page_id = m.page_id
          STRAIGHT_JOIN round r ON r.id = ri.round_id
          WHERE m.category_id = $sourceId AND r.contest_id IN ($group) $notMixed"""
      .map(rs => rs.long(1) -> rs.long(2)).list.apply()
      .groupMap(_._1)(p => countryOf.getOrElse(p._2, "")).collect {
        case (pageId, cs) if cs.distinct.size == 1 && cs.head.nonEmpty => pageId -> cs.head
      }
    val ownCountry = Option(contest.country.trim).filter(_.nonEmpty)
    val ofOwn = (for (id <- ownId.toSeq; c <- ownCountry.toSeq; p <- members(id)) yield p -> c).toMap
    ofOwn ++ byRounds ++ byCategory
  }

  // ── writing ────────────────────────────────────────────────────────────────

  private def lockContest(contestId: Long)(implicit session: DBSession): Boolean =
    sql"SELECT id FROM contest_jury WHERE id = $contestId FOR UPDATE".map(_.long(1)).single.apply().isDefined

  /** Repairs the contest's list as previewed (`hash`, [[Plan.hash]]) with the rounds `counted`, in
    * one transaction: its own row (added if there is none) gets the files that stay, becomes its
    * list, and the repair is logged with the files it added. Left: the message key of the refusal
    * (the list changed since the preview, it needs no repair, no such contest). Right: the repair.
    */
  /** Rolls a confirmation back: the files differ from the preview's. */
  private object Stale extends RuntimeException("list changed", null, false, false)

  def confirm(contestId: Long, counted: Seq[Long], hash: String, actorId: Long): Either[String, ListRepair] = {
    val result = try DB.localTx { implicit session =>
      if (!lockContest(contestId)) Left("error.contest.not.found")
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
    } catch { case Stale => Left("list.repair.changed") }
    result.map { case (repair, p) =>
      RegionCountCache.invalidateCategory(Seq(repair.newCategoryId, p.current.id, p.source.id).distinct: _*)
      log.info(s"files_repaired contestId=$contestId repairId=${repair.id} oldRow=${p.current.id} sourceRow=${p.source.id} " +
        s"newRow=${repair.newCategoryId} rounds=${p.counted.mkString(",")} stays=${p.stays.size} leaves=${p.leaves.size} " +
        s"added=${repair.filesAdded} by=$actorId")
      repair
    }
  }

  /** Undoes the repair: the contest's list is its old row again, and its own row loses the files
    * the repair added (only those). Refused (the message key) if it was undone already, or the
    * contest's list changed since (another list, a later repair, a later import's files).
    */
  def undo(repairId: Long, actorId: Long): Either[String, ListRepair] = {
    val result = DB.localTx { implicit session =>
      ListRepairJdbc.lock(repairId).toRight("list.repair.not.found").flatMap { r =>
        if (r.undone) Left("list.repair.undo.already")
        else if (!lockContest(r.contestId)) Left("error.contest.not.found")
        else {
          val contest = ContestJuryJdbc.findById(r.contestId)
          val later = ListRepairJdbc.findByContest(r.contestId).exists(o => o.id > r.id && !o.undone)
          if (!contest.flatMap(_.categoryId).contains(r.newCategoryId) || later ||
              listHash(members(r.newCategoryId)) != r.listHash) Left("list.repair.undo.changed")
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
    }
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
    val result = DB.localTx { implicit session =>
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
