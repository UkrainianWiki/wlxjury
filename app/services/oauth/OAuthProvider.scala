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
    name: Option[String] = None
) {

  /** The email, if the provider vouches for it. */
  def verifiedEmail: Option[String] = email.map(_.trim.toLowerCase).filter(e => emailVerified && e.nonEmpty)
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

/** The providers by id. */
@Singleton
class OAuthProviders(val all: Seq[OAuthProvider]) {

  @Inject() def this(google: GoogleProvider, wikimedia: WikimediaProvider) = this(Seq(google, wikimedia))

  def get(id: String): Option[OAuthProvider] = all.find(_.id == id)
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
