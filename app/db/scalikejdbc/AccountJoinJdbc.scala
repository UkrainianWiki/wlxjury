package db.scalikejdbc

import scalikejdbc._
import services.jobs.JobStatus

/** The account join's database work (the account join plan's J-2,
  * docs/plans/2026-11-03-account-join.md; [[services.AccountJoin]] and
  * [[services.AccountJoinHandler]] use it): which accounts a join holds, what an account has in
  * which round, the collisions of decision J1, and the per-round move of one account's votes,
  * votes set aside and assignments to another ([[moveRound]], under the round-first rule of
  * [[RoundWrites]]).
  *
  * '''Unrated''' (the plan's "Since 2026-11-03", Votes): a `vote` or `vote_aside` row with `rate`
  * 0, no `voted_at`, and no criterion rated (a `criteria_rate` row with a rate other than 0: a
  * criteria vote rated partly keeps its total 0 and its time NULL, and is a vote all the same).
  * Of two rows of the two accounts for one file and round in one table, an unrated row is an
  * assignment, not a vote: it '''merges''' into the other row (the rated one stays, moved to the
  * kept account if it was the joined account's; of two unrated rows the kept account's stays), and
  * the unrated row is deleted with its criteria rows (none rated). Only two '''rated''' rows are a
  * '''collision''' (J1, the maintainer: refused if both accounts voted on the same file), and so is
  * a pair across the two tables (a row in `vote` and the other account's in `vote_aside` for one
  * file and round: a restore would meet a unique key), whatever their rates.
  */
object AccountJoinJdbc {

  /** The job kind ([[services.AccountJoinHandler]]). */
  val Kind = "account_join"

  /** The job statuses in which a join holds its two accounts: queued, running, and failed (it
    * waits for its Retry, or before its first round step for a Cancel). Done and cancelled don't.
    */
  val HoldingStatuses: Seq[String] = (JobStatus.active + JobStatus.Failed).toSeq.sorted

  /** The JSON paths of the joined account in a join's params and of the juror a rebalance adds
    * (bound as parameters).
    */
  private val JoinedPath = "$.joinedId"
  private val AddPath = "$.add"

  /** A join holding an account: the job, the kept and the joined (retiring) account. */
  final case class Holding(jobId: Long, keptId: Long, joinedId: Long) {
    def accounts: Set[Long] = Set(keptId, joinedId)
  }

  /** The joins that hold any of `ids` (as kept or joined), by a plain read: at READ COMMITTED the
    * latest committed. The callers that must not race a join's start read it after a lock the
    * start takes too (the accounts' users rows, [[User.lockLive]] in a `round_user` insert).
    */
  def holding(ids: Seq[Long])(implicit session: DBSession = AutoSession): Seq[Holding] =
    if (ids.isEmpty) Nil
    else {
      val wanted = ids.toSet
      sql"""SELECT id, subject_id, CAST(JSON_VALUE(params, ${JoinedPath}) AS UNSIGNED) FROM job
            WHERE status IN ($HoldingStatuses) AND kind = $Kind"""
        .map(rs => Holding(rs.long(1), rs.long(2), rs.long(3))).list.apply()
        .filter(h => h.accounts.exists(wanted))
    }

  /** Of `ids`, the accounts some join holds (kept or joined). */
  def joining(ids: Seq[Long])(implicit session: DBSession = AutoSession): Set[Long] =
    holding(ids).flatMap(_.accounts).toSet.intersect(ids.toSet)

  /** Of `ids`, the accounts some join retires (its joined account). */
  def retiring(ids: Seq[Long])(implicit session: DBSession = AutoSession): Set[Long] =
    holding(ids).map(_.joinedId).toSet.intersect(ids.toSet)

  /** The queued or running `round_rebalance` jobs that add one of `ids` (`params.add`). */
  def rebalancesAdding(ids: Seq[Long])(implicit session: DBSession = AutoSession): Seq[Long] =
    if (ids.isEmpty) Nil
    else {
      val active = JobStatus.active.toSeq.sorted
      sql"""SELECT id, CAST(JSON_VALUE(params, ${AddPath}) AS UNSIGNED) FROM job
            WHERE status IN ($active) AND kind = ${services.distribution.RoundRebalanceHandler.Kind}"""
        .map(rs => rs.long(1) -> rs.long(2)).list.apply().collect { case (job, add) if ids.contains(add) => job }
    }

  /** The rounds where the account has a `vote`, `vote_aside` or `round_user` row, ascending
    * (a `vote` or `vote_aside` round id may name a round that no longer exists: no foreign key).
    */
  def roundsOf(accountId: Long)(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"""SELECT round_id FROM vote WHERE jury_id = $accountId
          UNION SELECT round_id FROM vote_aside WHERE jury_id = $accountId
          UNION SELECT round_id FROM round_user WHERE user_id = $accountId
          ORDER BY 1""".map(_.long(1)).list.apply()

  /** The contests of the rounds, distinct, ascending. */
  def contestsOfRounds(roundIds: Seq[Long])(implicit session: DBSession = AutoSession): Seq[Long] =
    if (roundIds.isEmpty) Nil
    else sql"SELECT DISTINCT contest_id FROM round WHERE id IN ($roundIds) ORDER BY contest_id".map(_.long(1)).list.apply()

  /** Of `roundIds`, those that exist. */
  def existing(roundIds: Seq[Long])(implicit session: DBSession = AutoSession): Set[Long] =
    if (roundIds.isEmpty) Set.empty
    else sql"SELECT id FROM round WHERE id IN ($roundIds)".map(_.long(1)).list.apply().toSet

  /** Per round, the account's rows: votes (`vote`), rated ones among them, rows set aside, and
    * its `round_user` row's `active` (None: no row).
    */
  final case class InRound(votes: Long = 0, rated: Long = 0, aside: Long = 0, assigned: Option[Boolean] = None)

  def inRounds(accountId: Long, roundIds: Seq[Long])(implicit session: DBSession = AutoSession): Map[Long, InRound] =
    if (roundIds.isEmpty) Map.empty
    else {
      val votes = sql"""SELECT round_id, COUNT(*), SUM(rate <> 0 OR voted_at IS NOT NULL) FROM vote
                        WHERE jury_id = $accountId AND round_id IN ($roundIds) GROUP BY round_id"""
        .map(rs => rs.long(1) -> (rs.long(2), rs.long(3))).list.apply().toMap
      val aside = sql"""SELECT round_id, COUNT(*) FROM vote_aside WHERE jury_id = $accountId AND round_id IN ($roundIds)
                        GROUP BY round_id""".map(rs => rs.long(1) -> rs.long(2)).list.apply().toMap
      val assigned = sql"SELECT round_id, active FROM round_user WHERE user_id = $accountId AND round_id IN ($roundIds)"
        .map(rs => rs.long(1) -> rs.booleanOpt(2).getOrElse(false)).list.apply().toMap
      roundIds.map { r =>
        val (v, rated) = votes.getOrElse(r, (0L, 0L))
        r -> InRound(v, rated, aside.getOrElse(r, 0L), assigned.get(r))
      }.toMap
    }

  /** A pair of rows of the two accounts for one file and round: in which tables (`vote` or
    * `vote_aside`), their rates, whether each is unrated, and whether it merges (one table, at
    * least one of the two unrated) or collides (J1: both rated, or across the tables).
    */
  final case class Pair(roundId: Long, pageId: Long, keptRate: Int, joinedRate: Int, keptAside: Boolean,
      joinedAside: Boolean, keptUnrated: Boolean, joinedUnrated: Boolean) {
    def merges: Boolean = keptAside == joinedAside && (keptUnrated || joinedUnrated)
    def collides: Boolean = !merges
    /** In a merge, which row goes: the joined account's, unless only the kept account's is unrated. */
    def dropsKept: Boolean = merges && keptUnrated && !joinedUnrated
  }

  private def unrated(alias: SQLSyntax): SQLSyntax =
    sqls"""($alias.rate = 0 AND $alias.voted_at IS NULL
           AND NOT EXISTS (SELECT 1 FROM criteria_rate c WHERE c.vote_id = $alias.id AND c.rate <> 0))"""

  private val Vote = sqls"vote"
  private val Aside = sqls"vote_aside"

  /** Every pair of rows of `kept` and `joined` for one file and round (in `roundIds`, or every
    * round if empty), by plain reads: the preview's. The joined account's rows lead (its index),
    * the kept account's row is found by the unique key.
    */
  def pairs(kept: Long, joined: Long, roundIds: Seq[Long] = Nil)(implicit session: DBSession = AutoSession): Seq[Pair] = {
    val inRounds = if (roundIds.isEmpty) sqls"TRUE" else sqls"j.round_id IN ($roundIds)"
    def of(jt: SQLSyntax, kt: SQLSyntax): Seq[Pair] = {
      sql"""SELECT j.round_id, j.page_id, k.rate, j.rate, ${unrated(sqls"k")}, ${unrated(sqls"j")}
            FROM $jt j STRAIGHT_JOIN $kt k ON k.page_id = j.page_id AND k.jury_id = $kept AND k.round_id = j.round_id
            WHERE j.jury_id = $joined AND $inRounds"""
        .map(rs => Pair(rs.long(1), rs.long(2), rs.int(3), rs.int(4), keptAside = kt == Aside, joinedAside = jt == Aside,
          keptUnrated = rs.boolean(5), joinedUnrated = rs.boolean(6))).list.apply()
    }
    (of(Vote, Vote) ++ of(Aside, Aside) ++ of(Vote, Aside) ++ of(Aside, Vote)).sortBy(p => (p.roundId, p.pageId))
  }

  /** What [[moveRound]] did in a round: rows moved to the kept account (`vote`, `vote_aside`),
    * the joined account's unrated rows deleted where the kept account had one (merged), and its
    * `round_user` row moved or merged (0 or 1).
    */
  final case class Moved(votes: Int = 0, aside: Int = 0, mergedVotes: Int = 0, mergedAside: Int = 0, roundUser: Int = 0) {
    def +(o: Moved): Moved = Moved(votes + o.votes, aside + o.aside, mergedVotes + o.mergedVotes,
      mergedAside + o.mergedAside, roundUser + o.roundUser)
    def isEmpty: Boolean = this == Moved()
  }

  private final case class Row(id: Long, pageId: Long, rate: Int, votedAtNull: Boolean)

  private def row(rs: WrappedResultSet): Row = Row(rs.long(1), rs.long(2), rs.int(3), rs.boolean(4))

  /** Of the vote ids, those with a criterion rated (a rate other than 0): a plain read. */
  private def withCriteria(ids: Seq[Long])(implicit session: DBSession): Set[Long] =
    ids.distinct.grouped(1000).flatMap { chunk =>
      sql"SELECT DISTINCT vote_id FROM criteria_rate WHERE vote_id IN ($chunk) AND rate <> 0".map(_.long(1)).list.apply()
    }.toSet

  /** Moves the joined account's rows of the round to the kept account, in the caller's
    * transaction, which holds the round for update (the round-first rule: checked here, unless
    * the round no longer exists and there is none to lock: legacy rows of a deleted round) and,
    * before it, both accounts' users rows (the join plan's lock order: `job` → `users` → `round`
    * → `vote` → `round_user` → `criteria_rate` → `vote_aside` → `round_image`).
    *
    * Locks, in that order: the joined account's `vote` rows of the round and the kept account's
    * rows of the same files; both accounts' `round_user` rows; the joined account's `vote_aside`
    * rows and the kept account's of the same files. The criteria rates and the pairs across the two
    * tables are plain reads (a juror's vote can't create a row; the joined account can't vote while
    * the join runs, and a vote of the kept account waits for its locked row). Then, if any pair
    * collides (J1), Left with them and nothing written. Otherwise: the `round_user` row moved, or
    * merged into the kept account's (active if either was); the unrated row of each merged pair
    * deleted (the joined account's, or the kept one's where only that is unrated), with its
    * criteria rows (none rated); the joined account's other rows given to the kept account
    * (`jury_id`; ids, rates, times and criteria rates unchanged); and, where a merge removed a
    * `vote` row (a file's assigned count changed), the round's counts marked for the recompute
    * ([[RoundCounts.markRounds]]). No `round_image` row is added or removed, no vote dropped.
    */
  def moveRound(roundId: Long, kept: Long, joined: Long)(implicit session: DBSession): Either[Seq[Pair], Moved] = {
    val roundExists = sql"SELECT 1 FROM round WHERE id = $roundId".map(_ => ()).single.apply().isDefined
    if (roundExists) RoundWrites.requireRounds(Seq(roundId))
    def lockOwn(table: SQLSyntax): Seq[Row] =
      sql"""SELECT id, page_id, rate, voted_at IS NULL FROM $table WHERE jury_id = $joined AND round_id = $roundId
            ORDER BY id FOR UPDATE""".map(row).list.apply()
    def lockKept(table: SQLSyntax): Seq[Row] =
      sql"""SELECT k.id, k.page_id, k.rate, k.voted_at IS NULL
            FROM $table j STRAIGHT_JOIN $table k ON k.page_id = j.page_id AND k.jury_id = $kept AND k.round_id = j.round_id
            WHERE j.jury_id = $joined AND j.round_id = $roundId ORDER BY k.id FOR UPDATE""".map(row).list.apply()
    def keptIn(table: SQLSyntax, pages: Seq[Long]): Seq[Row] =
      pages.distinct.grouped(1000).flatMap { chunk =>
        sql"""SELECT id, page_id, rate, voted_at IS NULL FROM $table
              WHERE jury_id = $kept AND round_id = $roundId AND page_id IN ($chunk)""".map(row).list.apply()
      }.toSeq

    val joinedVotes = lockOwn(Vote)
    val keptVotes = lockKept(Vote)
    val users = sql"""SELECT user_id, active FROM round_user WHERE round_id = $roundId AND user_id IN ($kept, $joined)
                      ORDER BY user_id FOR UPDATE""".map(rs => rs.long(1) -> rs.booleanOpt(2).getOrElse(false))
      .list.apply().toMap
    val joinedAside = lockOwn(Aside)
    val keptAside = lockKept(Aside)
    val crossKeptAside = if (joinedVotes.isEmpty) Nil else keptIn(Aside, joinedVotes.map(_.pageId))
    val crossKeptVotes = if (joinedAside.isEmpty) Nil else keptIn(Vote, joinedAside.map(_.pageId))
    val rated = withCriteria(joinedVotes.map(_.id) ++ keptVotes.map(_.id) ++ joinedAside.map(_.id) ++ keptAside.map(_.id))
    def isUnrated(r: Row) = r.rate == 0 && r.votedAtNull && !rated.contains(r.id)

    def matched(own: Seq[Row], other: Seq[Row], keptTableAside: Boolean, joinedTableAside: Boolean) = {
      val byPage = other.map(r => r.pageId -> r).toMap
      own.flatMap(j => byPage.get(j.pageId).map { k =>
        (j, k, Pair(roundId, j.pageId, k.rate, j.rate, keptTableAside, joinedTableAside, isUnrated(k), isUnrated(j)))
      })
    }
    val votePairs = matched(joinedVotes, keptVotes, keptTableAside = false, joinedTableAside = false)
    val asidePairs = matched(joinedAside, keptAside, keptTableAside = true, joinedTableAside = true)
    val crosses = matched(joinedVotes, crossKeptAside, keptTableAside = true, joinedTableAside = false) ++
      matched(joinedAside, crossKeptVotes, keptTableAside = false, joinedTableAside = true)
    val collisions = (votePairs ++ asidePairs ++ crosses).map(_._3).filter(_.collides)
    if (collisions.nonEmpty) Left(collisions.sortBy(_.pageId))
    else {
      // round_user: moved, or merged (one row per round, active if either was)
      val roundUser = users.get(joined).fold(0) { joinedActive =>
        users.get(kept) match {
          case None =>
            sql"UPDATE round_user SET user_id = $kept WHERE round_id = $roundId AND user_id = $joined".update.apply()
          case Some(keptActive) =>
            if (joinedActive && !keptActive)
              sql"UPDATE round_user SET active = TRUE WHERE round_id = $roundId AND user_id = $kept".update.apply()
            sql"DELETE FROM round_user WHERE round_id = $roundId AND user_id = $joined".update.apply()
        }
        1
      }
      // the unrated row of each merged pair goes: the joined account's, or the kept account's where
      // only that one is unrated (the joined account's rated vote then moves in its place)
      def dropped(pairs: Seq[(Row, Row, Pair)]) =
        pairs.collect { case (j, k, p) if p.merges => if (p.dropsKept) k.id else j.id }
      val mergedVotes = dropped(votePairs)
      val mergedAside = dropped(asidePairs)
      // criteria_rate: the dropped rows' (none rated), before their votes
      (mergedVotes ++ mergedAside).grouped(1000).foreach { chunk =>
        sql"DELETE FROM criteria_rate WHERE vote_id IN ($chunk)".update.apply()
      }
      mergedVotes.grouped(1000).foreach(chunk => sql"DELETE FROM vote WHERE id IN ($chunk)".update.apply())
      val votes = sql"UPDATE vote SET jury_id = $kept WHERE jury_id = $joined AND round_id = $roundId".update.apply()
      mergedAside.grouped(1000).foreach(chunk => sql"DELETE FROM vote_aside WHERE id IN ($chunk)".update.apply())
      val aside = sql"UPDATE vote_aside SET jury_id = $kept WHERE jury_id = $joined AND round_id = $roundId".update.apply()
      // a merge removed a vote row: the files' assigned counts changed (round_image, P2a)
      if (mergedVotes.nonEmpty && roundExists) RoundCounts.markRounds(Seq(roundId))
      Right(Moved(votes, aside, mergedVotes.size, mergedAside.size, roundUser))
    }
  }

  /** The rounds where the joined account still has a `vote`, `vote_aside` or `round_user` row
    * (the last step's sweep).
    */
  def remainingRounds(joined: Long)(implicit session: DBSession): Seq[Long] = roundsOf(joined)

  /** The contests of each account's rows that [[User.contestsWorkedInBy]] doesn't read: rounds of
    * its votes set aside, and its comments' contests (the join's performer rule covers everything
    * the join moves).
    */
  def contestsTouchedBy(ids: Seq[Long])(implicit session: DBSession = AutoSession): Map[Long, Seq[Long]] =
    if (ids.isEmpty) Map.empty
    else {
      val distinct = ids.distinct
      sql"""SELECT s.jury_id, r.contest_id FROM (SELECT DISTINCT jury_id, round_id FROM vote_aside WHERE jury_id IN ($distinct)) s
            JOIN round r ON r.id = s.round_id
            UNION
            SELECT user_id, contest_id FROM comment WHERE user_id IN ($distinct) AND contest_id IS NOT NULL"""
        .map(rs => rs.long(1) -> rs.long(2)).list.apply().groupMap(_._1)(_._2)
    }

  /** Locks both accounts' `user_contest` and `account_token` rows (the lock order: right after
    * their users rows, before any round).
    */
  def lockAccountRows(ids: Seq[Long])(implicit session: DBSession): Unit =
    if (ids.nonEmpty) {
      val sorted = ids.distinct.sorted
      sql"SELECT user_id FROM user_contest WHERE user_id IN ($sorted) ORDER BY user_id, contest_id FOR UPDATE"
        .map(_.long(1)).list.apply()
      sql"SELECT id FROM account_token WHERE user_id IN ($sorted) ORDER BY id FOR UPDATE".map(_.long(1)).list.apply()
      ()
    }

  /** Whether the round is busy for a join step, read under its lock: a job holds its key (a
    * distribution, a juror being added) or it is frozen (`freeze_count > 0`).
    */
  def roundBusy(roundId: Long)(implicit session: DBSession): Boolean =
    services.jobs.JobJdbc.activeByDedupeKey(services.distribution.RoundDistributionHandler.dedupeKey(roundId)).isDefined ||
      sql"SELECT freeze_count FROM round WHERE id = $roundId".map(_.intOpt(1).getOrElse(0)).single.apply().exists(_ > 0)

  /** Of `roundIds`, those being distributed: `distributing = 1` (being created, held in review
    * (P3), or a creation that failed) or a job holding the round's key. Plain reads.
    */
  def distributing(roundIds: Seq[Long])(implicit session: DBSession = AutoSession): Seq[Long] =
    if (roundIds.isEmpty) Nil
    else {
      val flagged = sql"SELECT id FROM round WHERE id IN ($roundIds) AND distributing = TRUE".map(_.long(1)).list.apply()
      val keys = roundIds.map(services.distribution.RoundDistributionHandler.dedupeKey)
      val active = JobStatus.active.toSeq.sorted
      val held = sql"SELECT subject_id FROM job WHERE active_dedupe_key IN ($keys) AND status IN ($active)"
        .map(_.long(1)).list.apply()
      (flagged ++ held).distinct.sorted
    }

  /** Merges the joined account's memberships into the kept account's, in the caller's
    * transaction (both users rows locked): one row per contest; where both have one the
    * higher-access role wins ([[roleRank]]: `admin` > `organizer` > `jury` > the roles without
    * access > `no`), kept on a tie. The joined account's rows stay: its retirement deletes them
    * ([[services.AccountRetirement.retireIn]], which reads them for its rules). Returns each
    * contest's (kept role before, joined role, role after), only where the kept row changed.
    * `only`: these contests alone (an abandoned join: the contests of the rounds it moved).
    */
  def mergeMemberships(kept: Long, joined: Long, only: Option[Set[Long]] = None)(implicit
      session: DBSession): Seq[(Long, Option[String], String, String)] = {
    val keptRows = sql"SELECT contest_id, role FROM user_contest WHERE user_id = $kept FOR UPDATE"
      .map(rs => rs.long(1) -> rs.string(2)).list.apply().toMap
    val joinedRows = sql"SELECT contest_id, role FROM user_contest WHERE user_id = $joined ORDER BY contest_id FOR UPDATE"
      .map(rs => rs.long(1) -> rs.string(2)).list.apply().filter { case (c, _) => only.forall(_.contains(c)) }
    joinedRows.flatMap { case (contestId, joinedRole) =>
      keptRows.get(contestId) match {
        case None =>
          sql"INSERT INTO user_contest (user_id, contest_id, role) VALUES ($kept, $contestId, $joinedRole)".update.apply()
          Some((contestId, None, joinedRole, joinedRole))
        case Some(keptRole) if roleRank(joinedRole) > roleRank(keptRole) =>
          sql"UPDATE user_contest SET role = $joinedRole WHERE user_id = $kept AND contest_id = $contestId".update.apply()
          Some((contestId, Some(keptRole), joinedRole, joinedRole))
        case _ => None
      }
    }
  }

  /** A role's access, for a membership merge: `admin` 4, `organizer` 3, `jury` 2, the roles
    * without access (board, auditor, staff) 1, `no` and anything else 0.
    */
  def roleRank(role: String): Int = role match {
    case User.ADMIN_ROLE                         => 4
    case "organizer"                             => 3
    case User.JURY_ROLE                          => 2
    case r if User.NON_ACCESS_ROLES.contains(r)  => 1
    case _                                       => 0
  }

  /** The role a merge gives a contest where the kept account has `keptRole` and the joined one
    * `joinedRole` (either may be absent).
    */
  def mergedRole(keptRole: Option[String], joinedRole: Option[String]): Option[String] =
    (keptRole, joinedRole) match {
      case (Some(k), Some(j)) => Some(if (roleRank(j) > roleRank(k)) j else k)
      case (k, j)             => k.orElse(j)
    }

  /** Gives the joined account's comments to the kept account (`comment.user_id`); how many. */
  def moveComments(kept: Long, joined: Long)(implicit session: DBSession): Int =
    sql"UPDATE comment SET user_id = $kept WHERE user_id = $joined".update.apply()

  /** Ends both accounts' sessions (`auth_version` + 1, [[User.BumpAuthVersion]]). */
  def endSessions(ids: Seq[Long])(implicit session: DBSession): Int =
    if (ids.isEmpty) 0
    else sql"UPDATE users SET auth_version = ${User.BumpAuthVersion} WHERE id IN ($ids)".update.apply()

  /** Revokes every unused link (`account_token`: invitations, password and email links, shared
    * ones) of the accounts; how many.
    */
  def revokeLinks(ids: Seq[Long])(implicit session: DBSession): Int =
    if (ids.isEmpty) 0
    else sql"""UPDATE account_token SET revoked_at = NOW(3)
               WHERE user_id IN ($ids) AND used_at IS NULL AND revoked_at IS NULL""".update.apply()

  /** The account's live (unused, unrevoked, unexpired) links. */
  def liveLinks(accountId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"""SELECT COUNT(*) FROM account_token WHERE user_id = $accountId AND used_at IS NULL AND revoked_at IS NULL
          AND expires_at > NOW(3)""".map(_.long(1)).single.apply().getOrElse(0L)

  /** The account's usable API tokens. */
  def liveApiTokens(accountId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"""SELECT COUNT(*) FROM api_token WHERE user_id = $accountId AND revoked_at IS NULL
          AND (expires_at IS NULL OR expires_at > NOW(3))""".map(_.long(1)).single.apply().getOrElse(0L)

  def comments(accountId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"SELECT COUNT(*) FROM comment WHERE user_id = $accountId".map(_.long(1)).single.apply().getOrElse(0L)

  def orphans(accountId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"SELECT COUNT(*) FROM vote_orphan WHERE jury_id = $accountId".map(_.long(1)).single.apply().getOrElse(0L)

  /** Whether the account's email is verified by its owner: proven (`email_verified_at`) and not set
    * by an admin (V96: an address an admin set counts as unverified until its owner proves it).
    */
  def ownerVerifiedEmail(accountId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT email IS NOT NULL AND email_verified_at IS NOT NULL AND email_set_by_admin_at IS NULL
          FROM users WHERE id = $accountId""".map(_.boolean(1)).single.apply().contains(true)

  /** Sets the kept account's wiki account and its verification (the join's CoI rule). */
  def setWikiAccount(accountId: Long, wikiAccount: Option[String], verified: Boolean)(implicit session: DBSession): Unit =
    sql"""UPDATE users SET wiki_account = $wikiAccount,
            wiki_account_verified_at = IF($verified, COALESCE(wiki_account_verified_at, NOW(3)), NULL)
          WHERE id = $accountId""".update.apply(): Unit

  /** Records a join done (`account_join_request`, state `done`): the pair, the performer (who
    * asked and decided), the contest it was done from; its id.
    */
  def recordDone(a: Long, b: Long, actorId: Long, contestId: Option[Long])(implicit session: DBSession): Long = {
    val (first, second) = AccountJoinRequestJdbc.pair(a, b)
    sql"""INSERT INTO account_join_request
            (first_account_id, second_account_id, state, requested_by, decided_by, contest_id, decided_at)
          VALUES ($first, $second, ${AccountJoinRequestJdbc.Done}, $actorId, $actorId, $contestId, NOW(3))"""
      .updateAndReturnGeneratedKey.apply()
  }
}
