package controllers

import db.scalikejdbc.User
import play.api.Logger
import play.api.data.{Form, FormError}
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{RequestHeader, Result, Results}
import play.twirl.api.Html

/** A refused page form, logged once (problem-monitoring plan, slice M-1): one WARN line
  * `form_refused` with the form's stable id, its contest, its round ("new" for none), the account,
  * the status, the names of the fields with an error and the message keys. Never a value the user
  * entered: an error's arguments are dropped (`files.list.unreadable` takes the unreadable lines,
  * a refusal's text may quote the input), and the names and keys are kept to identifier
  * characters. The result carries [[Recorded]] with the line's reference, so that a later
  * fallback (M-2's filter) doesn't record it twice.
  */
object FormRefusal {

  /** The logger of the support events (M-1: the line only; M-2 records a row with it). */
  val logger: Logger = Logger("support.event")

  /** The reference of the `form_refused` line a result was logged with. */
  val Recorded: TypedKey[String] = TypedKey[String]("formRefusal")

  /** The page of a refused form, answered 400 and logged with the form's errors. */
  def badRequest(form: String, user: User, contestId: Option[Long], roundId: Option[Long], refusedForm: Form[_])(
      page: Html)(implicit request: RequestHeader): Result =
    refused(form, user, contestId, roundId, refusedForm.errors.map(messageKey), refusedForm.errors.map(_.key))(
      Results.BadRequest(page))

  /** `result` (a refusal not told by a Play form's errors) logged with the message keys and the
    * fields' names given.
    */
  def refused(form: String, user: User, contestId: Option[Long], roundId: Option[Long], keys: Seq[String],
      fields: Seq[String] = Nil)(result: Result)(implicit request: RequestHeader): Result = {
    val reference = controllers.Secured.newReference()
    logger.warn(line(reference, form, user, contestId, roundId, result.header.status, keys, fields))
    result.addAttr(Recorded, reference)
  }

  /** The line: key-value fields, the lists comma separated (`-` for none). */
  def line(reference: String, form: String, user: User, contestId: Option[Long], roundId: Option[Long],
      status: Int, keys: Seq[String], fields: Seq[String]): String = {
    def list(values: Seq[String]) = values.map(identifier).filter(_.nonEmpty).distinct match {
      case Nil  => "-"
      case some => some.mkString(",")
    }
    s"form_refused ref=$reference form=${identifier(form)} contestId=${contestId.getOrElse("-")} " +
      s"roundId=${roundId.fold("new")(_.toString)} accountId=${user.id.getOrElse("-")} status=$status " +
      s"fields=${list(fields)} keys=${list(keys)}"
  }

  /** A new-round form posted again after it saved its round (`resubmit_dropped`): the posted
    * settings not applied, and whether they were those saved (`outcome=same`, a double click) or
    * not (`changed`: edits dropped); `distribution` what its distribution did (`resumed`, `running`,
    * `given_up`, `refused`, `none`). Ids only, never the round's name.
    */
  def resubmitLine(reference: String, form: String, user: User, contestId: Long, roundId: Long, outcome: String,
      distribution: String): String =
    s"resubmit_dropped ref=$reference form=${identifier(form)} contestId=$contestId roundId=$roundId " +
      s"accountId=${user.id.getOrElse("-")} outcome=${identifier(outcome)} distribution=${identifier(distribution)}"

  /** An error's first message key; its arguments never. */
  def messageKey(error: FormError): String = error.messages.headOption.getOrElse(error.message)

  /** A field name or a message key as logged: letters, digits and `._-[]` only, at most 100. */
  def identifier(value: String): String =
    value.take(100).map(c => if (c.isLetterOrDigit && c < 128 || "._-[]".indexOf(c.toInt) >= 0) c else '?')
}
