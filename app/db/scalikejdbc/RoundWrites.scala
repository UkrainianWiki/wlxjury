package db.scalikejdbc

import scalikejdbc._

import java.sql.Connection
import scala.collection.mutable

/** The one rule for structural writes to a round's votes and membership
  * (docs/plans/2026-11-03-write-path-simplification.md, W2): every write of `vote`,
  * `vote_aside`, `criteria_rate`, `round_image` or `round_criterion` rows that isn't a juror's
  * own vote locks the rounds it touches **for update, first** in its transaction, in id order.
  * The round row is then a per-round mutex between structural writers, so the order of their
  * locks below it no longer decides anything between two of them. Juror votes
  * ([[VoteJdbc.rate]], [[org.intracer.wmua.CriteriaRate.updateRate]]) stay row-level: they never
  * lock the round.
  *
  * Writers that start from files (a Commons import's changed or restored files, the file check,
  * the list phase's stored files, a monument id change) lock the rounds the files are in
  * ([[lockForFiles]]: read from `round_image` without a lock, then locked) before the files.
  *
  * The rule is checked at run time: the locks taken here (and by the other round locks, which
  * record theirs: [[Round.lock]], [[Round.isOpen]], [[RoundCriterion.updateIn]],
  * [[RoundConflicts]], the distribution's) are recorded per transaction, and the DAO's
  * structural writers `require` them ([[requireRounds]], [[requireFiles]]). A writer without
  * its round lock fails with an [[IllegalStateException]] before it writes.
  */
object RoundWrites {

  /** What a transaction has locked by this rule. Kept per thread and borrowed connection, by
    * identity (a transaction runs on one thread, on one borrow; the pools hand out a new
    * wrapper per borrow, so a lock of an earlier transaction never counts for a later one).
    */
  private final class Held {
    val rounds: mutable.Set[Long] = mutable.Set.empty
    val files: mutable.Set[Long] = mutable.Set.empty
  }

  private val held = new ThreadLocal[List[(java.lang.ref.WeakReference[Connection], Held)]] {
    override def initialValue(): List[(java.lang.ref.WeakReference[Connection], Held)] = Nil
  }

  private def open(c: Connection): Boolean =
    c != null && scala.util.Try(!c.isClosed).getOrElse(false)

  private def connectionOf(session: DBSession): Option[Connection] =
    session match {
      case _: AutoSession | _: NamedAutoSession | ReadOnlyAutoSession | _: ReadOnlyNamedAutoSession => None
      case s => Option(s.connection)
    }

  private def heldOf(session: DBSession): Option[Held] =
    connectionOf(session).map { c =>
      val live = held.get.filter { case (ref, _) => open(ref.get) }
      live.collectFirst { case (ref, h) if ref.get eq c => h }.getOrElse {
        val h = new Held
        held.set((new java.lang.ref.WeakReference(c), h) :: live)
        h
      }
    }

  /** Records rounds the caller has locked for update in its transaction (the round locks
    * outside this object call it).
    */
  def recordRounds(roundIds: Iterable[Long])(implicit session: DBSession): Unit =
    heldOf(session).foreach(_.rounds ++= roundIds)

  /** Locks the rounds for update, in id order, and records them. */
  def lock(roundIds: Seq[Long])(implicit session: DBSession): Unit =
    if (roundIds.nonEmpty) {
      val ids = roundIds.distinct.sorted
      sql"SELECT id FROM round WHERE id IN ($ids) ORDER BY id FOR UPDATE".map(_.long(1)).list()
      recordRounds(ids)
    }

  /** Locks those of the rounds this transaction hasn't locked yet: for a writer whose rounds
    * come from a locked read made after its caller's unlocked lookup of them (W2's finding 3:
    * a round that gained the file's rows between the two). Out of the id order, so a deadlock
    * it causes is possible, rolled back and retried (a transient error), unlike a refusal.
    */
  def lockMissing(roundIds: Seq[Long])(implicit session: DBSession): Unit =
    heldOf(session) match {
      case Some(h) => lock(roundIds.filterNot(h.rounds.contains))
      case None    => ()
    }

  /** Locks `roundIds` and the rounds the files have rows in, for update, in id order: before a
    * write that starts from files. Records the files as covered ([[requireFiles]]).
    */
  def lockForFiles(pageIds: Seq[Long], roundIds: Seq[Long] = Nil)(implicit session: DBSession): Unit =
    if (pageIds.nonEmpty || roundIds.nonEmpty) {
      val files = pageIds.distinct
      val ofFiles =
        if (files.isEmpty) Nil
        else sql"SELECT DISTINCT round_id FROM round_image WHERE page_id IN ($files)".map(_.long(1)).list()
      lock(ofFiles ++ roundIds)
      heldOf(session).foreach(_.files ++= files)
    }

  /** Locks the files' image rows for update in page id order (after their rounds), before a
    * loop of per-file updates.
    */
  def lockFiles(pageIds: Seq[Long])(implicit session: DBSession): Unit =
    if (pageIds.nonEmpty) ImageJdbc.lockFiles(pageIds.distinct.sorted)

  /** A transaction that locks the rounds first, then runs `f`. */
  def inRounds[A](roundIds: Seq[Long])(f: DBSession => A): A =
    DB.localTx { implicit session => lock(roundIds); f(session) }

  private def refuse(what: String): Nothing =
    throw new IllegalStateException(s"A structural write $what without its round lock (RoundWrites): " +
      "lock the rounds first, in the writer's transaction")

  /** Refuses a write of these rounds' rows unless this transaction locked them first. */
  def requireRounds(roundIds: Iterable[Long])(implicit session: DBSession): Unit =
    if (roundIds.nonEmpty) heldOf(session) match {
      case None => refuse(s"of rounds ${roundIds.toSeq.distinct.sorted.mkString(", ")} outside a transaction")
      case Some(h) =>
        val missing = roundIds.filterNot(h.rounds.contains).toSeq.distinct.sorted
        if (missing.nonEmpty) refuse(s"of rounds ${missing.mkString(", ")}")
    }

  /** Refuses a write of these files' rows in every round unless this transaction locked their
    * rounds first ([[lockForFiles]]).
    */
  def requireFiles(pageIds: Iterable[Long])(implicit session: DBSession): Unit =
    if (pageIds.nonEmpty) heldOf(session) match {
      case None => refuse(s"of files ${pageIds.toSeq.distinct.sorted.mkString(", ")} outside a transaction")
      case Some(h) =>
        val missing = pageIds.filterNot(h.files.contains).toSeq.distinct.sorted
        if (missing.nonEmpty) refuse(s"of files ${missing.take(10).mkString(", ")} in all their rounds")
    }
}
