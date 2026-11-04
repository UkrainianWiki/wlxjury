package db.scalikejdbc

import scalikejdbc._

import java.time.ZonedDateTime

/** A round's file disqualifications and requalifications (`round_image_event`, V88), append
  * only (docs/plans/2026-11-02-round-images-two-step.md, §14 "Design choices" and §15): the
  * action, its code (a `dq_code` or a requalify reason, never free text), the row's status
  * before and after, who and when.
  *
  * Written by the two writers that move a row into and out of `dq`, in their transaction, after
  * the rows ([[RoundImageJdbc.disqualify]], [[RoundImageJdbc.requalifyRows]]): last in the lock
  * order, `round` → … → `round_image` → `round_image_event`, the round locked for update first
  * (checked, [[RoundWrites]]). A requalify reads the latest event's prior status
  * ([[latestDisqualify]]) to restore a file deleted on Commons while its round was open as it
  * was (`deleted`, its votes aside).
  */
object RoundImageEventJdbc {

  object Action {
    val Disqualify = "disqualify"
    val Requalify = "requalify"
    val All: Seq[String] = Seq(Disqualify, Requalify)
  }

  /** `code`'s length (V88). */
  val CodeLength = 16

  /** An event, as stored. `newStatus` None: the row went (a requalified file with no votes). */
  final case class Event(
      id: Long,
      roundId: Long,
      pageId: Long,
      action: String,
      code: String,
      priorStatus: String,
      newStatus: Option[String],
      by: Option[Long],
      at: ZonedDateTime
  )

  /** A file's change, to record: its status before and after. */
  final case class Change(pageId: Long, priorStatus: String, newStatus: Option[String])

  /** Records the round's `changes` as `action` with `code`, by `by`, now: after the rows were
    * written, in the same transaction (a failure rolls both back). The caller has locked the
    * round for update first (checked).
    */
  private[scalikejdbc] def record(roundId: Long, action: String, code: String, by: Option[Long], changes: Seq[Change])(
      implicit session: DBSession
  ): Unit = {
    require(Action.All.contains(action), s"unknown round_image_event action $action")
    require(code.nonEmpty && code.length <= CodeLength, s"bad round_image_event code $code")
    if (changes.nonEmpty) {
      RoundWrites.requireRounds(Seq(roundId))
      changes.sortBy(_.pageId).grouped(RoundImageJdbc.Chunk).foreach { chunk =>
        val values = chunk.map { c =>
          sqls"($roundId, ${c.pageId}, $action, $code, ${c.priorStatus}, ${c.newStatus}, $by, NOW(3))"
        }
        sql"""INSERT INTO round_image_event
                (round_id, page_id, action, code, prior_status, new_status, created_by, created_at)
              VALUES ${sqls.csv(values: _*)}""".update(): Unit
      }
    }
  }

  /** Of the round's files `pageIds`, the latest event of each whose latest is a disqualify:
    * page id -> the status it had before. A file disqualified before V88 has none. Read plainly:
    * the round's events are written under its lock, which the caller holds.
    */
  def latestDisqualify(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Map[Long, String] =
    if (pageIds.isEmpty) Map.empty
    else
      pageIds.distinct.sorted.grouped(RoundImageJdbc.Chunk).flatMap { chunk =>
        sql"""SELECT e.page_id, e.action, e.prior_status FROM round_image_event e
              JOIN (SELECT page_id, MAX(id) AS id FROM round_image_event
                    WHERE round_id = $roundId AND page_id IN ($chunk) GROUP BY page_id) m ON m.id = e.id"""
          .map(rs => (rs.long(1), rs.string(2), rs.string(3)))
          .list()
          .collect { case (pageId, Action.Disqualify, prior) => pageId -> prior }
      }.toMap

  /** The round's events, newest first, at most `limit`. */
  def byRound(roundId: Long, limit: Int = 500)(implicit session: DBSession = AutoSession): Seq[Event] =
    sql"""SELECT id, round_id, page_id, action, code, prior_status, new_status, created_by, created_at
          FROM round_image_event WHERE round_id = $roundId ORDER BY id DESC LIMIT $limit"""
      .map(event).list()

  /** A file's events in the round, oldest first. */
  def ofFile(roundId: Long, pageId: Long)(implicit session: DBSession = AutoSession): Seq[Event] =
    sql"""SELECT id, round_id, page_id, action, code, prior_status, new_status, created_by, created_at
          FROM round_image_event WHERE round_id = $roundId AND page_id = $pageId ORDER BY id"""
      .map(event).list()

  private def event(rs: WrappedResultSet): Event =
    Event(rs.long(1), rs.long(2), rs.long(3), rs.string(4), rs.string(5), rs.string(6), rs.stringOpt(7),
      rs.longOpt(8), rs.zonedDateTime(9))
}
