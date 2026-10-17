package services.oauth

import com.nimbusds.oauth2.sdk._
import com.nimbusds.oauth2.sdk.auth.{ClientSecretPost, Secret}
import com.nimbusds.oauth2.sdk.http.HTTPRequest
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import play.api.libs.json.{JsNumber, JsString, JsValue, Json}

import java.net.URI
import javax.inject.{Inject, Singleton}
import scala.util.control.NonFatal

/** Sign-in with a Wikimedia account: MediaWiki's OAuth 2.0 (Extension:OAuth), which has no
  * OpenID Connect, so the user comes from its profile endpoint. Email and real name are in
  * the profile only if the consumer has the "access to real name and email address" grant.
  */
@Singleton
class WikimediaProvider @Inject() (settings: OAuthSettings) extends OAuthProvider {

  val id: String = OAuthProviders.Wikimedia

  private def endpoint(path: String): URI = URI.create(s"${settings.wikimediaBaseUrl}/oauth2/$path")

  def authorizationUri(client: OAuthClient, redirectUri: URI, flow: OAuthFlow): URI =
    new AuthorizationRequest.Builder(ResponseType.CODE, new ClientID(client.clientId))
      .endpointURI(endpoint("authorize"))
      .redirectionURI(redirectUri)
      .state(flow.state)
      .codeChallenge(flow.verifier, CodeChallengeMethod.S256)
      .build()
      .toURI

  def exchange(client: OAuthClient, redirectUri: URI, code: String, flow: OAuthFlow): ExternalProfile = {
    val request = new TokenRequest.Builder(
      endpoint("access_token"),
      new ClientSecretPost(new ClientID(client.clientId), new Secret(client.clientSecret)),
      new AuthorizationCodeGrant(new AuthorizationCode(code), redirectUri, flow.verifier)
    ).build()

    val response =
      try TokenResponse.parse(OAuthHttp.configured(request.toHTTPRequest, settings).send())
      catch { case NonFatal(e) => throw new OAuthException(s"token request failed: ${e.getMessage}", e) }

    if (!response.indicatesSuccess())
      throw new OAuthException(s"token error: ${response.toErrorResponse.getErrorObject.getCode}")

    val token = Option(response.toSuccessResponse.getTokens.getBearerAccessToken)
      .getOrElse(throw new OAuthException("no bearer access token in the token response"))

    val profileRequest = new HTTPRequest(HTTPRequest.Method.GET, endpoint("resource/profile"))
    profileRequest.setAuthorization(token.toAuthorizationHeader)
    val profile =
      try OAuthHttp.configured(profileRequest, settings).send()
      catch { case NonFatal(e) => throw new OAuthException(s"profile request failed: ${e.getMessage}", e) }

    if (!profile.indicatesSuccess())
      throw new OAuthException(s"profile request failed: HTTP ${profile.getStatusCode}")

    val json =
      try Json.parse(profile.getBody)
      catch { case NonFatal(e) => throw new OAuthException(s"profile is not JSON: ${e.getMessage}", e) }

    parseProfile(json)
  }

  /** The `/oauth2/resource/profile` response. `sub` is the central (global) user id. */
  private[oauth] def parseProfile(json: JsValue): ExternalProfile = {
    val subject = (json \ "sub").toOption.collect {
      case JsString(s) if s.trim.nonEmpty => s.trim
      case JsNumber(n)                    => n.toBigInt.toString
    }.getOrElse(throw new OAuthException("profile without sub"))

    ExternalProfile(
      provider      = id,
      subject       = subject,
      email         = (json \ "email").asOpt[String].filter(_.trim.nonEmpty),
      emailVerified = (json \ "confirmed_email").asOpt[Boolean].getOrElse(false),
      username      = (json \ "username").asOpt[String].filter(_.trim.nonEmpty),
      name          = (json \ "realname").asOpt[String].filter(_.trim.nonEmpty)
    )
  }
}
