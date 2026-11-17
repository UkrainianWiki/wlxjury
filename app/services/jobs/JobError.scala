package services.jobs

import play.api.i18n.{Messages, MessagesImpl}
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

  /** The message, its arguments as stored; one its pattern formats as a number (`{i,choice,…}`, a
    * plural, or `{i,number…}`) is passed as one, so that "1 juror" and "2 jurors" can differ. Every
    * other stays text (ids and round numbers aren't grouped as "12,345").
    */
  def message(implicit messages: Messages): String = {
    val numeric = JobError.numericArgs(key)
    val values: Seq[Any] = args.zipWithIndex.map { case (a, i) =>
      if (numeric(i)) a.toLongOption.getOrElse(a) else a
    }
    messages(key, (values ++ reference): _*)
  }

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

  /** Its runs kept ending without an outcome (`jobs.maxAttempts` claims): given up. */
  val AttemptsKey = "job.error.attempts"

  def crashed(reference: String): JobError = JobError(CrashedKey, reference = Some(reference))

  def attemptsExhausted(attempts: Int, reference: String): JobError =
    JobError(AttemptsKey, Seq(attempts.toString), Some(reference))

  implicit val format: OFormat[JobError] = Json.using[Json.WithDefaultValues].format[JobError]

  private val NumericArg = """\{\s*(\d+)\s*,\s*(?:choice|number)""".r

  /** The arguments `key`'s pattern in the messages' language formats as numbers (its raw pattern, as
    * Play looks it up: the language's code, the language, then the defaults); none when the pattern
    * can't be read.
    */
  def numericArgs(key: String)(implicit messages: Messages): Set[Int] = messages match {
    case impl: MessagesImpl =>
      val all = impl.messagesApi.messages
      val lang = impl.lang
      Seq(lang.code, lang.language, "default", "default.play").iterator
        .flatMap(code => all.get(code).flatMap(_.get(key))).nextOption()
        .fold(Set.empty[Int])(pattern => NumericArg.findAllMatchIn(pattern).map(_.group(1).toInt).toSet)
    case _ => Set.empty
  }

  /** Keys of a handler's refusals by its own checks, stored as they are and shown as the form
    * shows them: the round distribution's rounds to leave out (V95).
    */
  val RefusalKeys: Set[String] = services.distribution.RoundDistributionHandler.ExcludeRoundsKeys

  /** A stored `job.error`; anything that isn't one of ours (an older row's exception text)
    * reads as [[UnknownKey]], so it is never shown.
    */
  def decode(stored: String): JobError =
    Try(Json.parse(stored).as[JobError]).toOption
      .filter(e => e.key.startsWith("job.error.") || RefusalKeys.contains(e.key))
      .getOrElse(JobError(UnknownKey))
}
