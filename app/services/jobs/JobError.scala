package services.jobs

import play.api.i18n.Messages
import play.api.libs.json.{Json, OFormat}

import scala.util.Try

/** Why a job failed, as `job.error` stores it and the status shows it: a message key, its
  * arguments, and for a crash the reference of the log line that holds the exception. Never
  * an exception's text (driver or Commons client messages: SQL with values, host names);
  * that goes only to the log.
  *
  * @param args      the message's arguments before the reference (numbers, ids)
  * @param reference the logged reference, the message's last argument
  */
final case class JobError(key: String, args: Seq[String] = Nil, reference: Option[String] = None) {

  def message(implicit messages: Messages): String = messages(key, (args ++ reference): _*)

  /** For logs and exceptions of our own: the key, its arguments and the reference. */
  def describe: String =
    (key +: args).mkString(" ") + reference.fold("")(r => s" ref=$r")

  def encode: String = Json.stringify(Json.toJson(this)(JobError.format))
}

object JobError {

  /** A step or its preparation crashed: the exception is logged with this reference. */
  val CrashedKey = "job.error.reference"

  /** A text stored before job errors were keys (an exception's message): not shown. */
  val UnknownKey = "job.error.unknown"

  /** The round has another number of images than the distribution added. */
  val CountMismatchKey = "job.error.count.mismatch"

  def crashed(reference: String): JobError = JobError(CrashedKey, reference = Some(reference))

  implicit val format: OFormat[JobError] = Json.using[Json.WithDefaultValues].format[JobError]

  /** A stored `job.error`; anything that isn't one of ours (an older row's exception text)
    * reads as [[UnknownKey]], so it is never shown.
    */
  def decode(stored: String): JobError =
    Try(Json.parse(stored).as[JobError]).toOption
      .filter(_.key.startsWith("job.error."))
      .getOrElse(JobError(UnknownKey))
}
