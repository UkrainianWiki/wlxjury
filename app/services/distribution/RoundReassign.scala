package services.distribution

import db.scalikejdbc._
import play.api.libs.json._
import scalikejdbc._

/** The resume cursor of a `round_rebalance` job that reassigns a juror's unrated files
  * (`params.from`, docs/plans/2026-11-16-round-editing.md §7).
  *
  * @param phase              "stop" (lock, check, pause the round, stop the juror, start the ticked
  *                           stopped receivers, fix the quotas), "move" (the juror's unrated vote rows,
  *                           window by window), "aside" (their unrated rows in `vote_aside`), "verify"
  * @param frozen             the round, while this job holds it paused ([[Round.freeze]])
  * @param stopped            a "stop" step has run: the juror stopped, the ticked receivers started
  * @param fromWasActive      the juror's round_user row before the job (its first "stop")
  * @param restarted          receivers stopped before the job that a "stop" started (Cancel stops them
  *                           again if nothing moved)
  * @param quotasSet          the quotas are fixed (kept on Retry; fixed again after a failed count check)
  * @param quota              per receiver, the unrated files it should get (water-filling from below,
  *                           [[RoundReassign.fillQuotas]])
  * @param given              per receiver, the juror's vote rows it got
  * @param asideGiven         per receiver, the juror's unrated rows aside it got
  * @param cursor             "move": the last vote id a committed window read (ascending)
  * @param waiting            the juror's rows deleted with no eligible receiver: their files wait for a
  *                           juror (N > 0)
  * @param removed            the juror's unrated rows deleted in a round where every file goes to every
  *                           juror (nothing is given there)
  * @param asideRemoved       the juror's unrated rows aside deleted with no eligible receiver
  * @param receiverRowsBefore each receiver's rows (vote and vote_aside) when "stop" counted
  * @param fromRatedBefore    the juror's rated rows (vote and vote_aside) when "stop" counted: they stay
  * @param total              the juror's unrated rows when "stop" counted (the progress)
  * @param repassed           "verify" sent the job back to "move" once (a file restored meanwhile)
  * @param verifyFailed       the count check failed: a Retry starts the accounting again
  * @param movedAny           a committed step changed a row; never reset (Cancel restores the jurors'
  *                           states only if it is false)
  * @param checks             "verify"'s invariant baseline and the files left waiting ([[VerifyChecks]])
  */
final case class ReassignState(
    phase: String = RoundRebalanceHandler.Phase.Stop,
    frozen: Seq[Long] = Nil,
    stopped: Boolean = false,
    fromWasActive: Option[Boolean] = None,
    restarted: Seq[Long] = Nil,
    quotasSet: Boolean = false,
    quota: Map[String, Long] = Map.empty,
    given: Map[String, Long] = Map.empty,
    asideGiven: Map[String, Long] = Map.empty,
    cursor: Long = 0L,
    waiting: Long = 0L,
    removed: Long = 0L,
    asideRemoved: Long = 0L,
    receiverRowsBefore: Map[String, Long] = Map.empty,
    fromRatedBefore: Option[Long] = None,
    total: Option[Long] = None,
    repassed: Boolean = false,
    verifyFailed: Boolean = false,
    movedAny: Boolean = false,
    checks: VerifyChecks = VerifyChecks()
) {
  def givenTo(r: Long): Long = given.getOrElse(r.toString, 0L)
  def asideGivenTo(r: Long): Long = asideGiven.getOrElse(r.toString, 0L)
  def moved: Long = given.values.sum
  def asideMoved: Long = asideGiven.values.sum

  /** Each receiver's quota left (it may go below zero: a file goes to an eligible receiver whatever
    * its quota, rather than wait).
    */
  def remaining(receivers: Seq[Long]): Map[Long, Long] =
    receivers.map(r => r -> (quota.getOrElse(r.toString, 0L) - givenTo(r))).toMap

  /** The juror's rows handled so far (the progress). */
  def done: Long = moved + waiting + removed + asideMoved + asideRemoved
}

object ReassignState {
  implicit val format: OFormat[ReassignState] = Json.using[Json.WithDefaultValues].format[ReassignState]
}

/** Reassigning a juror's unrated files (§7): the quotas, the choice of receiver per file and the
  * queries the page and the job share.
  */
object RoundReassign {

  /** How many unrated files each receiver should get of `total`: water-filling from below, so that
    * the receivers end with about the same number of unrated files (the plan's §13 default, "open
    * work"). With `receivers` (id, unrated), the k least loaded fill up to a level, k the most for
    * which the k-th least loaded is at most the level `(total + their sum) / k`; each gets the level's
    * floor less its own, the remainder one more each, the least loaded first. The quotas sum exactly
    * to `total`; equal work gives an equal split.
    */
  def fillQuotas(total: Long, receivers: Seq[(Long, Long)]): Map[Long, Long] =
    if (receivers.isEmpty || total <= 0) receivers.map(_._1 -> 0L).toMap
    else {
      val sorted = receivers.sortBy { case (id, n) => (n, id) }
      val k = (1 to sorted.size).filter { k =>
        sorted(k - 1)._2 * k <= total + sorted.take(k).map(_._2).sum
      }.last
      val low = sorted.take(k)
      val sum = total + low.map(_._2).sum
      val base = sum / k
      val extra = (sum % k).toInt
      val filled = low.zipWithIndex.map { case ((id, n), i) => id -> (base - n + (if (i < extra) 1 else 0)) }.toMap
      receivers.map { case (id, _) => id -> filled.getOrElse(id, 0L) }.toMap
    }

  /** The receiver a file goes to: of those with no row of it (`holders`), the one with the largest
    * quota left, the lowest id first; None if every receiver has it (the file waits).
    */
  def choose(holders: Set[Long], receivers: Seq[Long], remaining: Map[Long, Long]): Option[Long] =
    receivers.filterNot(holders).sortBy(r => (-remaining.getOrElse(r, 0L), r)).headOption

  /** The receiver of each page, in order, and the quotas left after. */
  def allocate(pages: Seq[Long], holders: Map[Long, Set[Long]], receivers: Seq[Long], remaining: Map[Long, Long])
      : (Seq[(Long, Option[Long])], Map[Long, Long]) =
    pages.foldLeft((Vector.empty[(Long, Option[Long])], remaining)) { case ((acc, left), page) =>
      val to = choose(holders.getOrElse(page, Set.empty), receivers, left)
      (acc :+ (page -> to), to.fold(left)(r => left.updated(r, left.getOrElse(r, 0L) - 1)))
    }

  /** A vote row `v` that is unrated: rate 0 and no criterion rated above 0 (a partly rated
    * criteria vote holds the juror's work, U5).
    */
  def unrated(v: SQLSyntax): SQLSyntax =
    sqls"$v.rate = 0 AND NOT EXISTS (SELECT 1 FROM criteria_rate cr WHERE cr.vote_id = $v.id AND cr.rate > 0)"

  /** A vote row `v` of the round that a reassign moves: unrated ([[unrated]]), its file `in` the round
    * and not deleted on Commons, as the add's movable rows ([[RoundRebalanceHandler.unratedCounts]]). A
    * row of a file deleted meanwhile, its votes not yet set aside, stays with the juror.
    */
  def movableVote(roundId: Long, v: SQLSyntax): SQLSyntax =
    sqls"""${unrated(v)}
           AND EXISTS (SELECT 1 FROM round_image ri WHERE ri.round_id = $roundId AND ri.page_id = $v.page_id
                       AND ri.status = ${RoundImageJdbc.Status.In})
           AND NOT EXISTS (SELECT 1 FROM images i WHERE i.page_id = $v.page_id AND i.deleted_at IS NOT NULL)"""

  /** The juror's movable unrated vote rows ([[movableVote]]) after `afterId`, by id: (id, page id). A
    * plain read.
    */
  def unratedRows(roundId: Long, jurorId: Long, afterId: Long, limit: Int)(implicit session: DBSession): Seq[(Long, Long)] =
    sql"""SELECT v.id, v.page_id FROM vote v WHERE v.round_id = $roundId AND v.jury_id = $jurorId AND v.id > $afterId
          AND ${movableVote(roundId, sqls"v")} ORDER BY v.id LIMIT $limit""".map(rs => rs.long(1) -> rs.long(2)).list()

  /** The juror's unrated rows of the round in `table`: in `vote`, those a reassign moves
    * ([[movableVote]]); in `vote_aside` (files deleted on Commons or disqualified), every unrated one.
    */
  def unratedCount(roundId: Long, jurorId: Long, table: SQLSyntax)(implicit session: DBSession): Long = {
    val cond = if (table.value == "vote") movableVote(roundId, sqls"v") else unrated(sqls"v")
    sql"SELECT COUNT(*) FROM $table v WHERE v.round_id = $roundId AND v.jury_id = $jurorId AND $cond"
      .map(_.long(1)).single().getOrElse(0L)
  }

  /** The juror's rows a reassign would hand on: [[unratedCount]] in vote plus vote_aside, the job's
    * `total`. At 0 there is nothing to reassign: the page says so, the POST refuses, no job.
    */
  def reassignableCount(roundId: Long, jurorId: Long)(implicit session: DBSession): Long =
    unratedCount(roundId, jurorId, sqls"vote") + unratedCount(roundId, jurorId, sqls"vote_aside")

  /** The round's jurors (round_user) with [[reassignableCount]] above 0 and enough other jurors to
    * take their files ([[enoughReceivers]]): the round's statistics show "Reassign files…" only for
    * them. Per juror an EXISTS on vote_round_jury_rate_idx that stops at the first row found, not a
    * count of every unrated row (most of a new round's); the receivers read once for all the jurors.
    * A legacy round (no round_user rows) has none: a reassign is refused there.
    */
  def reassignableJurors(round: Round)(implicit session: DBSession = AutoSession): Set[Long] = {
    val roundId = round.getId
    val withFiles = sql"""SELECT ru.user_id FROM round_user ru WHERE ru.round_id = $roundId AND (
            EXISTS (SELECT 1 FROM vote v WHERE v.round_id = $roundId AND v.jury_id = ru.user_id
                    AND ${movableVote(roundId, sqls"v")})
            OR EXISTS (SELECT 1 FROM vote_aside v WHERE v.round_id = $roundId AND v.jury_id = ru.user_id
                       AND ${unrated(sqls"v")}))""".map(_.long(1)).list().toSet
    if (withFiles.isEmpty) withFiles
    else {
      val receivers = possibleReceivers(roundId, round.contestId)
      withFiles.filter(j => enoughReceivers(j, receivers, round.distribution))
    }
  }

  /** The round's jurors (round_user) who could receive a reassigned juror's files, each with whether
    * they are active in the round: an active jury membership of the contest, the account not deleted
    * (as [[RoundRebalanceHandler.eligibleJuror]], the receivers' check).
    */
  def possibleReceivers(roundId: Long, contestId: Long)(implicit session: DBSession = AutoSession): Map[Long, Boolean] = {
    val jurors = sql"SELECT user_id, active FROM round_user WHERE round_id = $roundId"
      .map(rs => rs.long(1) -> rs.boolean(2)).list().toMap
    User.contestMembers(contestId, roles = Some(Seq(User.JURY_ROLE)), ids = Some(jurors.keys.toSeq))
      .filter(_.deletedAt.isEmpty).map(u => u.getId -> jurors(u.getId)).toMap
  }

  /** Whether `from`'s files could go somewhere, of `receivers` ([[possibleReceivers]]): in a round where
    * each file goes to N > 0 jurors, at least N others (stopped ones too: a ticked one is started again);
    * where every file goes to every juror (N = 0), nothing is given and nobody is started, so at least one
    * other active one (else the files are left with no juror voting). If not: no "Reassign files…", and
    * the page, the POST and the job's "stop" refuse ([[RoundRebalanceHandler.AloneKey]]).
    */
  def enoughReceivers(from: Long, receivers: Map[Long, Boolean], distribution: Int): Boolean = {
    val others = receivers - from
    if (distribution <= 0) others.values.exists(identity) else others.size >= distribution
  }

  /** The juror's rated rows of the round, in vote and vote_aside: they stay with the juror. */
  def ratedCount(roundId: Long, jurorId: Long)(implicit session: DBSession): Long =
    Seq(sqls"vote", sqls"vote_aside").map { table =>
      sql"""SELECT COUNT(*) FROM $table v WHERE v.round_id = $roundId AND v.jury_id = $jurorId
            AND NOT (${unrated(sqls"v")})""".map(_.long(1)).single().getOrElse(0L)
    }.sum

  /** Of `receivers`, those with a row of each page in the round, in vote or vote_aside. */
  def holders(roundId: Long, pages: Seq[Long], receivers: Seq[Long])(implicit session: DBSession): Map[Long, Set[Long]] =
    if (pages.isEmpty || receivers.isEmpty) Map.empty
    else
      pages.distinct.sorted.grouped(2000).flatMap { chunk =>
        sql"""SELECT page_id, jury_id FROM vote WHERE round_id = $roundId AND page_id IN ($chunk) AND jury_id IN ($receivers)
              UNION SELECT page_id, jury_id FROM vote_aside WHERE round_id = $roundId AND page_id IN ($chunk)
                AND jury_id IN ($receivers)""".map(rs => rs.long(1) -> rs.long(2)).list()
      }.toSeq.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap

  /** A juror's files of a round as the reassign page shows them.
    *
    * @param assigned     rows in `vote`
    * @param rated        of them, rated (✗ and partly rated criteria votes included): they stay
    * @param unrated      of them, unrated with their file `in` the round and not deleted on Commons: they
    *                     go to the receivers
    * @param aside        rows in `vote_aside` (files deleted on Commons or disqualified)
    * @param unratedAside of them, unrated: they go to the receivers too
    */
  final case class JurorFiles(assigned: Long, rated: Long, unrated: Long, aside: Long, unratedAside: Long)

  def jurorFiles(roundId: Long, jurorId: Long)(implicit session: DBSession): JurorFiles = {
    val assigned = sql"SELECT COUNT(*) FROM vote WHERE round_id = $roundId AND jury_id = $jurorId".map(_.long(1)).single().getOrElse(0L)
    val aside = sql"SELECT COUNT(*) FROM vote_aside WHERE round_id = $roundId AND jury_id = $jurorId".map(_.long(1)).single().getOrElse(0L)
    val rated = sql"SELECT COUNT(*) FROM vote v WHERE v.round_id = $roundId AND v.jury_id = $jurorId AND NOT (${unrated(sqls"v")})"
      .map(_.long(1)).single().getOrElse(0L)
    JurorFiles(assigned, rated, unratedCount(roundId, jurorId, sqls"vote"), aside, unratedCount(roundId, jurorId, sqls"vote_aside"))
  }

  /** The round's files waiting for a juror (§7): `in`, not deleted on Commons, with fewer vote rows
    * than the round's jurors per file. None for a round where every file goes to every juror, or one
    * being created or held in review (its files have no votes yet).
    */
  def waitingCount(round: Round)(implicit session: DBSession = AutoSession): Option[Long] =
    if (round.distribution <= 0 || round.distributing) None
    else Some(sql"""SELECT COUNT(*) FROM round_image ri WHERE ${waitingCond(round.getId, round.distribution)}"""
      .map(_.long(1)).single().getOrElse(0L))

  /** `ri`, a file of the round waiting for a juror: `in`, not deleted on Commons, fewer than `perFile`
    * vote rows.
    */
  def waitingCond(roundId: Long, perFile: Int): SQLSyntax =
    sqls"""ri.round_id = $roundId AND ri.status = ${RoundImageJdbc.Status.In}
           AND NOT EXISTS (SELECT 1 FROM images i WHERE i.page_id = ri.page_id AND i.deleted_at IS NOT NULL)
           AND (SELECT COUNT(*) FROM vote v WHERE v.round_id = $roundId AND v.page_id = ri.page_id) < $perFile"""

  /** The next window of waiting files after `cursor`, by page id, with their vote rows' count. Read
    * under the round's lock.
    */
  def waitingWindow(roundId: Long, perFile: Int, cursor: Long, limit: Int)(implicit session: DBSession): Seq[(Long, Int)] =
    sql"""SELECT ri.page_id, (SELECT COUNT(*) FROM vote v WHERE v.round_id = $roundId AND v.page_id = ri.page_id)
          FROM round_image ri WHERE ${waitingCond(roundId, perFile)} AND ri.page_id > $cursor
          ORDER BY ri.page_id LIMIT $limit""".map(rs => rs.long(1) -> rs.int(2)).list()

  /** `ri`, a waiting file ([[waitingCond]]) the juror could take: no row of it in vote or vote_aside. An
    * add gives these to the new juror first, within their share (round-editing plan §5).
    */
  def waitingForCond(roundId: Long, perFile: Int, jurorId: Long): SQLSyntax =
    sqls"""${waitingCond(roundId, perFile)}
           AND NOT EXISTS (SELECT 1 FROM vote y WHERE y.round_id = $roundId AND y.page_id = ri.page_id AND y.jury_id = $jurorId)
           AND NOT EXISTS (SELECT 1 FROM vote_aside a WHERE a.round_id = $roundId AND a.page_id = ri.page_id
                           AND a.jury_id = $jurorId)"""

  /** The waiting files the juror could take ([[waitingForCond]]); 0 for a round where every file goes to
    * every juror.
    */
  def waitingForCount(roundId: Long, perFile: Int, jurorId: Long)(implicit session: DBSession = AutoSession): Long =
    if (perFile <= 0) 0L
    else sql"SELECT COUNT(*) FROM round_image ri WHERE ${waitingForCond(roundId, perFile, jurorId)}".map(_.long(1)).single().getOrElse(0L)

  /** The next window of the waiting files the juror could take after `cursor`, by page id, with their
    * vote rows' count. Read under the round's lock.
    */
  def waitingForWindow(roundId: Long, perFile: Int, jurorId: Long, cursor: Long, limit: Int)(implicit
      session: DBSession
  ): Seq[(Long, Int)] =
    sql"""SELECT ri.page_id, (SELECT COUNT(*) FROM vote v WHERE v.round_id = $roundId AND v.page_id = ri.page_id)
          FROM round_image ri WHERE ${waitingForCond(roundId, perFile, jurorId)} AND ri.page_id > $cursor
          ORDER BY ri.page_id LIMIT $limit""".map(rs => rs.long(1) -> rs.int(2)).list()

  /** Of the waiting files, those some of `jurors` could take (no row of it in vote or vote_aside):
    * the top-up's count check.
    */
  def waitingTakeable(roundId: Long, perFile: Int, jurors: Seq[Long])(implicit session: DBSession): Long =
    if (jurors.isEmpty) 0L
    else
      sql"""SELECT COUNT(*) FROM round_image ri WHERE ${waitingCond(roundId, perFile)}
            AND EXISTS (SELECT 1 FROM users u WHERE u.id IN ($jurors)
              AND NOT EXISTS (SELECT 1 FROM vote y WHERE y.round_id = $roundId AND y.page_id = ri.page_id AND y.jury_id = u.id)
              AND NOT EXISTS (SELECT 1 FROM vote_aside a WHERE a.round_id = $roundId AND a.page_id = ri.page_id
                              AND a.jury_id = u.id))""".map(_.long(1)).single().getOrElse(0L)
}
