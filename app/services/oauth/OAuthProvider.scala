package services.oauth

import com.nimbusds.oauth2.sdk.http.HTTPRequest
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.openid.connect.sdk.Nonce

import java.net.URI
import javax.inject.{Inject, Singleton}

/** Who the provider says signed in. `subject` is stable; the rest is as of this sign-in. */
case class ExternalProfile(
    provider: String,
    subject: String,
    email: Option[String] = None,
    emailVerified: Boolean = false,
    username: Option[String] = None,
    name: Option[String] = None,
    /** Google's `hd` claim: present for a Google Workspace account. */
    hostedDomain: Option[String] = None
) {

  /** The email, if the provider vouches for it and is authoritative for it ([[emailAuthoritative]]). */
  def verifiedEmail: Option[String] =
    email.map(_.trim.toLowerCase).filter(e => emailVerified && e.nonEmpty && emailAuthoritative)

  /** Whether the provider's `email_verified` proves the address (M4, the user's decision,
    * 2026-11-03). Google: only for a Gmail address (`@gmail.com`, `@googlemail.com`), or for a
    * Workspace account (an `hd` claim), whose addresses are on domains its organisation verified;
    * for any other address Google's flag only says it was verified once, which says nothing of
    * who holds it now. Wikimedia: its confirmed email.
    */
  def emailAuthoritative: Boolean =
    provider != OAuthProviders.Google || hostedDomain.exists(_.trim.nonEmpty) ||
      email.map(_.trim.toLowerCase).exists(e => e.endsWith("@gmail.com") || e.endsWith("@googlemail.com"))

  /** A verified Google email that is not proof of the address ([[emailAuthoritative]]). */
  def emailNotAuthoritative: Boolean =
    emailVerified && email.exists(_.trim.nonEmpty) && !emailAuthoritative
}

/** One sign-in's secrets, kept in the session between leaving for the provider and the
  * callback: the state against login CSRF, the PKCE verifier and the OpenID Connect nonce.
  */
case class OAuthFlow(state: State, verifier: CodeVerifier, nonce: Nonce)

object OAuthFlow {
  def random(): OAuthFlow = OAuthFlow(new State(), new CodeVerifier(), new Nonce())

  def apply(state: String, verifier: String, nonce: String): OAuthFlow =
    OAuthFlow(new State(state), new CodeVerifier(verifier), new Nonce(nonce))
}

/** A failed exchange with the provider: its error response, a bad token or a network error. */
class OAuthException(message: String, cause: Throwable = null) extends RuntimeException(message, cause)

/** An OAuth 2.0 provider we sign users in with (authorization code flow with PKCE). */
trait OAuthProvider {

  def id: String

  /** The provider's authorization page for this sign-in. */
  def authorizationUri(client: OAuthClient, redirectUri: URI, flow: OAuthFlow): URI

  /** Trades the callback's code for the user's profile. Blocks on HTTP calls to the
    * provider; throws [[OAuthException]].
    */
  def exchange(client: OAuthClient, redirectUri: URI, code: String, flow: OAuthFlow): ExternalProfile
}

/** The providers by id, and the turns for exchanges with them ([[OAuthSettings.maxConcurrent]]). */
@Singleton
class OAuthProviders(val all: Seq[OAuthProvider], maxConcurrent: Int) {

  @Inject() def this(google: GoogleProvider, wikimedia: WikimediaProvider, settings: OAuthSettings) =
    this(Seq(google, wikimedia), settings.maxConcurrent)

  def this(all: Seq[OAuthProvider]) = this(all, 8)

  def get(id: String): Option[OAuthProvider] = all.find(_.id == id)

  private val turns = new java.util.concurrent.Semaphore(maxConcurrent)

  /** `f` in one of the turns; None at once if all are taken. */
  def withTurn[A](f: => A): Option[A] =
    if (!turns.tryAcquire()) None
    else
      try Some(f)
      finally turns.release()
}

object OAuthProviders {
  val Google    = "google"
  val Wikimedia = "wikimedia"

  /** In the order the login page shows them. */
  val All: Seq[String] = Seq(Wikimedia, Google)
}

/** Timeouts for the providers' HTTP calls. */
private[oauth] object OAuthHttp {
  def configured(request: HTTPRequest, settings: OAuthSettings): HTTPRequest = {
    request.setConnectTimeout(settings.connectTimeoutMillis)
    request.setReadTimeout(settings.readTimeoutMillis)
    request
  }
}
