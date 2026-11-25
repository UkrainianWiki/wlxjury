package db.scalikejdbc

import play.api.libs.json._
import scalikejdbc._

/** A round's settings saved (the maintainer's decision of 2026-11-25): a `contest_change` entry
  * ([[ContestChangeJdbc.RoundSettingKinds]], V114) with who (an account id, never an email) and, of
  * the settings ([[Fields]]) that changed, the old and new values as persisted: read before and
  * after the save in its transaction, under the round's lock, not taken from the form. A save that
  * changes nothing writes none; a save without an account (a job, a script) neither. Shown on the
  * round's statistics page and in the contest's change log.
  *
  * detail: `{"round": id, "number": n, "path": [[Path]], "changed": {field: {"from": old, "to": new}}}`;
  * a creation's `from` is null, and lists only the settings not left at their defaults. Values are
  * compared whole and stored short: ids, names, category titles, a text cut at [[MaxValueLength]]
  * code points; never lists of files. A cut text has its whole length and a short hash of it beside
  * it (`fromLength`, `fromHash`, `toLength`, `toHash`), so two values that differ only past the cut
  * still read as different.
  */
object RoundChanges {

  /** The request path that saved the change (the detail's `path`). */
  object Path {
    /** The round form's new round. */
    val Create = "create"
    /** The round form's edit of a round. */
    val Edit = "edit"
    /** The round form's "Re-apply filters" on a round held in review. */
    val Reapply = "reapply"
    /** The rounds list's (or the round's conflicts page's) start. */
    val Start = "start"
    /** The rounds list's (or the round's conflicts page's) stop. */
    val Stop = "stop"
    /** The round's jurors form, before its distribute: the jurors per file. */
    val Jurors = "jurors"
    /** "Distribute new files" on a cancelled creation: reinstated (`draft`). */
    val Reinstate = "reinstate"
    /** The job page's Cancel of a creation's job: the creation `cancelled`. */
    val Cancel = "cancel"

    val All: Seq[String] = Seq(Create, Edit, Reapply, Start, Stop, Jurors, Reinstate, Cancel)
  }

  /** The settings compared, in the order shown. */
  val Fields: Seq[String] = Seq(
    "name", "rates", "halfStar", "criteria", "averageRule", "worstMark",
    "previous", "prevSelectedBy", "prevRejectedBy", "prevRejectedMode", "prevMinAvgRate", "topImages", "topBinaryOrder",
    "category", "excludeCategory", "categoryClause", "regions", "monuments", "minImageSize", "minMpx", "mediaType",
    "specialNomination", "excludeRounds", "excludeCoi", "distribution", "active", "status")

  /** Left out of a creation's entry: every new round starts in the same state. */
  private val Lifecycle = Set("active", "status")

  /** A text value's longest stored length, in code points; longer ones are cut, ending in "…". */
  val MaxValueLength = 200

  /** The settings' values, by [[Fields]]' names. */
  type Snapshot = Map[String, JsValue]

  /** `text` cut to `max` code points (never inside a surrogate pair), ending in "…", if longer. */
  def cut(text: String, max: Int): String =
    if (text.codePointCount(0, text.length) <= max) text
    else text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…"

  /** The text's CRC-32 as 8 hex digits: tells two cut values apart (a checksum, not a secret, so
    * no MessageDigest: `security.RawSqlSpec` keeps those to passwords and tokens).
    */
  def hash(text: String): String = {
    val crc = new java.util.zip.CRC32()
    crc.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    f"${crc.getValue}%08x"
  }

  /** The round's settings as compared, whole: None and blank texts, and empty lists, are null. */
  def values(r: Round, criteria: Seq[String]): Snapshot = {
    def text(v: Option[String]): JsValue = v.map(_.trim).filter(_.nonEmpty).fold[JsValue](JsNull)(JsString(_))
    def number(v: Option[Int]): JsValue = v.fold[JsValue](JsNull)(JsNumber(_))
    def ids(v: Seq[Long]): JsValue = if (v.isEmpty) JsNull else JsArray(v.map(JsNumber(_)).toIndexedSeq)
    Map(
      "name" -> text(r.name),
      "rates" -> JsString(r.rates.name),
      "halfStar" -> JsBoolean(r.halfStar),
      "criteria" -> (if (criteria.isEmpty) JsNull else JsArray(criteria.map(JsString(_)).toIndexedSeq)),
      "averageRule" -> JsString(r.averageRule.param),
      "worstMark" -> JsBoolean(r.worstMark),
      "previous" -> ids(r.previousIds),
      "prevSelectedBy" -> number(r.prevSelectedBy),
      "prevRejectedBy" -> number(r.prevRejectedBy),
      "prevRejectedMode" -> JsString(r.prevRejectedMode.param),
      "prevMinAvgRate" -> r.prevMinAvgRate.fold[JsValue](JsNull)(JsNumber(_)),
      "topImages" -> number(r.topImages),
      "topBinaryOrder" -> r.topBinaryOrder.fold[JsValue](JsNull)(o => JsString(o.param)),
      "category" -> text(r.category),
      "excludeCategory" -> text(r.excludeCategory),
      "categoryClause" -> text(r.categoryClause),
      "regions" -> text(r.regions),
      "monuments" -> text(r.monuments),
      "minImageSize" -> number(r.minImageSize),
      "minMpx" -> number(r.minMpx),
      "mediaType" -> text(r.mediaType),
      "specialNomination" -> text(r.specialNomination),
      "excludeRounds" -> ids(r.excludeRounds),
      "excludeCoi" -> JsBoolean(r.excludeCoi),
      "distribution" -> JsNumber(r.distribution),
      "active" -> JsBoolean(r.active),
      "status" -> text(r.status)
    )
  }

  /** A new round's settings, for a creation's entry: those that differ are listed (the rating always). */
  private lazy val Defaults: Snapshot = values(Round(None, 0, contestId = 0), Nil)

  /** The round's settings as persisted now, read in the caller's session (its transaction). */
  def snapshot(roundId: Long)(implicit session: DBSession): Option[(Round, Snapshot)] =
    Round.findById(roundId).map(r => r -> values(r, RoundCriterion.ofRound(roundId).map(_.fallbackName)))

  /** What changed from `before` to `after`, in [[Fields]]' order: (field, from, to). Without a
    * `before` (a creation), the settings not at their defaults, from null, the lifecycle left out.
    */
  def diff(before: Option[Snapshot], after: Snapshot): Seq[(String, JsValue, JsValue)] =
    before match {
      case Some(b) =>
        Fields.flatMap { f =>
          val (from, to) = (b.getOrElse(f, JsNull), after.getOrElse(f, JsNull))
          Option.when(from != to)((f, from, to))
        }
      case None =>
        Fields.filterNot(Lifecycle).flatMap { f =>
          val to = after.getOrElse(f, JsNull)
          Option.when(f == "rates" || to != Defaults.getOrElse(f, JsNull))((f, JsNull, to))
        }
    }

  /** A value as stored: a text cut ([[cut]]), with its whole length and hash under `side`Length and
    * `side`Hash if it was; a list's texts cut, each.
    */
  private def stored(side: String, v: JsValue): JsObject = v match {
    case JsString(t) if cut(t, MaxValueLength) != t =>
      Json.obj(side -> cut(t, MaxValueLength), s"${side}Length" -> t.codePointCount(0, t.length), s"${side}Hash" -> hash(t))
    case JsArray(items) => Json.obj(side -> JsArray(items.map {
      case JsString(t) => JsString(cut(t, MaxValueLength))
      case other       => other
    }))
    case other => Json.obj(side -> other)
  }

  /** The entry's detail: the values compared whole, stored short ([[stored]]). */
  def detail(round: Round, path: String, changes: Seq[(String, JsValue, JsValue)]): JsObject =
    Json.obj("round" -> round.getId, "number" -> round.number, "path" -> path,
      "changed" -> JsObject(changes.map { case (f, from, to) => f -> (stored("from", from) ++ stored("to", to)) }))

  /** Writes the entry, if anything changed and an account did it: its id. */
  def record(kind: String, before: Option[Snapshot], round: Round, after: Snapshot, actorId: Option[Long], path: String)(
      implicit session: DBSession): Option[Long] = {
    require(ContestChangeJdbc.RoundSettingKinds.contains(kind), "not a round settings kind")
    require(Path.All.contains(path), "unknown round change path")
    val changes = diff(before, after)
    actorId.filter(_ => changes.nonEmpty).map { actor =>
      ContestChangeJdbc.create(Some(round.contestId), None, actor, kind, detail = detail(round, path, changes))
    }
  }

  /** `writes`, with the round's settings read before and after them in the caller's transaction,
    * and an entry of what they changed ([[record]]). The caller holds the round's lock. An
    * exception from `writes` rolls the entry back with them.
    */
  def logged[A](roundId: Long, actorId: Option[Long], kind: String, path: String)(writes: => A)(implicit
      session: DBSession): A = {
    val before = actorId.flatMap(_ => snapshot(roundId))
    val result = writes
    for (b <- before; (round, after) <- snapshot(roundId))
      record(kind, Some(b._2), round, after, actorId, path)
    result
  }

  /** A new round's entry ([[ContestChangeJdbc.RoundCreated]]): its settings, read in the creating
    * transaction.
    */
  def recordCreated(roundId: Long, actorId: Option[Long])(implicit session: DBSession): Option[Long] =
    actorId.flatMap { _ =>
      snapshot(roundId).flatMap { case (round, after) =>
        record(ContestChangeJdbc.RoundCreated, None, round, after, actorId, Path.Create)
      }
    }
}
