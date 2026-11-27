package controllers

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import play.api.mvc.RequestHeader

/** The browser's tab owns the draft. This key scopes it to the signed-in actor and this form. */
object RoundFormDraft {
  val Saved = "roundFormDraftSaved"

  def initialTopImages(request: RequestHeader): Option[Int] =
    request.getQueryString("topImages").flatMap(_.toIntOption).filter(_ > 0)

  def key(actor: Long, contest: Long, round: Option[Long], token: Option[String]): Option[String] =
    round.map(id => s"round:$id").orElse(token.map(t => s"new:$t")).map { form =>
      UUID.nameUUIDFromBytes(s"$actor:$contest:$form".getBytes(UTF_8)).toString
    }

  def saved(actor: Long, contest: Long, round: Option[Long], token: Option[String]): Seq[(String, String)] =
    key(actor, contest, round, token).map(Saved -> _).toSeq
}
