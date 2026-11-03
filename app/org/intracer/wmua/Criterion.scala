package org.intracer.wmua

import play.api.i18n.Messages

/** A criterion of the global catalogue (table `criterion`, V83), which criteria rounds choose
  * theirs from ([[db.scalikejdbc.RoundCriterion]]).
  *
  * @param messageKey   its name, a message key: shipped criteria have messages in every
  *                     language ([[Criterion.ShippedKeys]]); one an admin adds has a key of
  *                     its own ([[Criterion.customKey]]) that no messages file has until it is
  *                     translated
  * @param fallbackName the name shown when the key has no message in the viewer's language
  */
case class Criterion(id: Long, messageKey: String, fallbackName: String) {

  /** The name in the viewer's language, else the fallback text. */
  def name(implicit messages: Messages): String =
    if (messages.isDefinedAt(messageKey)) messages(messageKey) else fallbackName
}

object Criterion {

  /** A criteria round has 2 to 10 criteria, each once. */
  val MinPerRound = 2
  val MaxPerRound = 10

  /** The catalogue's size limit: it is global, and every round form lists it. */
  val MaxEntries = 500

  val MaxNameLength = 255

  /** The criteria V83 ships, by id: their keys have messages in every language. */
  val Shipped: Seq[(Long, String)] = Seq(
    1L -> "criterion.science",
    2L -> "criterion.caption",
    3L -> "criterion.usefulness",
    4L -> "criterion.aesthetics",
    5L -> "criterion.uniqueness",
    6L -> "criterion.technical.quality",
    7L -> "criterion.wikipedia.usefulness",
    8L -> "criterion.photo.originality",
    9L -> "criterion.extra.points"
  )

  val ShippedKeys: Seq[String] = Shipped.map(_._2)

  /** The key of a criterion an admin adds, by its id. */
  def customKey(id: Long): String = s"criterion.custom.$id"

  /** Why a round's criteria are refused, as a message key, or none: 2 to 10, each once, all
    * in the catalogue (`known`), in a rated round. No criteria: a round without them.
    */
  def validationError(ids: Seq[Long], rated: Boolean, known: Seq[Long] => Set[Long]): Option[String] =
    if (ids.isEmpty) None
    else if (!rated) Some(RatedOnly)
    else if (ids.size < MinPerRound || ids.size > MaxPerRound) Some("error.criteria.count")
    else if (ids.distinct.size != ids.size) Some("error.criteria.distinct")
    else if (known(ids) != ids.toSet) Some("error.criteria.unknown")
    else None

  val RatedOnly = "error.criteria.rated.only"

  /** The round form's errors of its criteria (the section opens to show them). */
  val ErrorKeys: Set[String] = Set(RatedOnly, "error.criteria.count", "error.criteria.distinct",
    "error.criteria.unknown", "error.criteria.voted")
}
