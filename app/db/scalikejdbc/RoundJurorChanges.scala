package db.scalikejdbc

import play.api.libs.json.Json
import scalikejdbc._

/** A round stopped, started again, or closed by creating the next round, by an account that judges
  * it (a juror of it as [[User.isRoundJuror]] decides: votes in it, or round_user, active or not),
  * whatever its role (the maintainer's decision of 2026-11-24, with the conflict-of-interest rule):
  * a `contest_change` entry ([[ContestChangeJdbc.RoundJurorKinds]]), written in the transaction of the
  * change, shown on the round's statistics page, and a WARN line on `security.authz` after the
  * commit (ids only). A change by an account that doesn't judge the round, or by a job alone, writes
  * neither.
  */
object RoundJurorChanges {

  /** One entry written: for the log line after the commit. */
  final case class Entry(kind: String, roundId: Long, contestId: Long, actorId: Long, byRoundId: Option[Long])

  private val authzLogger = play.api.Logger("security.authz")

  /** Of `roundIds`, whose open state `actorId` has just changed (`kind`), the rounds the actor
    * judges: an entry for each, in the caller's transaction. `byRoundId`: a close's new round.
    */
  def record(kind: String, roundIds: Seq[Long], actorId: Option[Long], byRoundId: Option[Long] = None)(implicit
      session: DBSession): Seq[Entry] = {
    require(ContestChangeJdbc.RoundJurorKinds.contains(kind), "not a round juror kind")
    actorId.filter(_ => roundIds.nonEmpty).toSeq.flatMap { actor =>
      val judged = User.roundsJuredIn(actor, roundIds)
      if (judged.isEmpty) Nil
      else {
        // a close's new round, by its id and its number (what the page shows)
        val byRound = byRoundId.fold(Json.obj()) { b =>
          Json.obj("byRound" -> b) ++ sql"SELECT number FROM round WHERE id = $b".map(_.long(1)).single()
            .fold(Json.obj())(n => Json.obj("byRoundNumber" -> n))
        }
        sql"SELECT id, contest_id, number FROM round WHERE id IN (${judged.toSeq.sorted}) ORDER BY id"
          .map(rs => (rs.long(1), rs.long(2), rs.long(3))).list().map { case (id, contestId, number) =>
            ContestChangeJdbc.create(Some(contestId), None, actor, kind,
              detail = Json.obj("round" -> id, "number" -> number) ++ byRound)
            Entry(kind, id, contestId, actor, byRoundId)
          }
      }
    }
  }

  /** The entry's `security.authz` line: key-value, ids only. */
  def logLine(e: Entry): String =
    s"juror_round_change kind=${e.kind} accountId=${e.actorId} contestId=${e.contestId} roundId=${e.roundId}" +
      e.byRoundId.fold("")(id => s" byRoundId=$id")

  /** Logs the entries at WARN; after the commit that wrote them. */
  def log(entries: Seq[Entry]): Unit = entries.foreach(e => authzLogger.warn(logLine(e)))
}
