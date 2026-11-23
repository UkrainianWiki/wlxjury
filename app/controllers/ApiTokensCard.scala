package controllers

import db.scalikejdbc.{ApiToken, ApiTokenScope}
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation.Constraints
import services.ApiTokenService

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

/** The own profile's API tokens card (apiTokens.scala.html): the user's tokens, the create
  * form with its one-time `nonce`, right after creating one the new token, shown in that
  * response only, and a `notice` (a message key) when a create was refused.
  */
final case class ApiTokensCard(
    tokens: Seq[ApiToken],
    form: Form[(String, Option[Int], ApiTokenScope)],
    newToken: Option[String],
    nonce: String,
    notice: Option[String] = None
)

object ApiTokensCard {

  /** The create form's one-time nonce: its field and its session key. */
  val NonceKey = "api_token_nonce"

  def newNonce(): String = java.util.UUID.randomUUID().toString

  /** An expiry's form value: its days, or "never". */
  def expiryValue(days: Option[Int]): String = days.fold("never")(_.toString)

  /** The create form: a name, one of [[ApiTokenService.ExpiryDays]], 90 days at first, and the
    * scope ([[ApiTokenScope]]), full at first: a read-only, server-only token is opt-in (support
    * diagnostics plan §7). A form without the field (an older page) makes a full token, as before;
    * an unknown value is refused.
    */
  val form: Form[(String, Option[Int], ApiTokenScope)] = Form(
    tuple(
      "name" -> text
        .transform[String](_.trim, identity)
        .verifying(Constraints.nonEmpty, Constraints.maxLength(ApiTokenService.NameMaxLength)),
      "expiry" -> text
        .verifying("error.api.token.expiry", value => ApiTokenService.ExpiryDays.map(expiryValue).contains(value))
        .transform[Option[Int]](_.toIntOption, expiryValue),
      "scope" -> default(text, ApiTokenScope.Full.value)
        .verifying("error.api.token.scope", value => ApiTokenScope.find(value).isDefined)
        // verified above; were it ever not, the narrower scope
        .transform[ApiTokenScope](value => ApiTokenScope.find(value).getOrElse(ApiTokenScope.LocalRead), _.value)
    )
  ).fill(("", ApiTokenService.DefaultExpiryDays, ApiTokenScope.Full))

  /** A token's scope in the token lists: its short name; a stored value this binary doesn't know
    * (the API refuses such a token) as unknown.
    */
  def scopeLabel(token: ApiToken)(implicit messages: play.api.i18n.Messages): String =
    token.scope.fold(messages("api.token.scope.short.unknown"))(s => messages(s"api.token.scope.short.${s.value}"))

  private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

  def date(instant: Instant): String = dateFormat.format(instant)
}
