package services.oauth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.oauth2.sdk._
import com.nimbusds.oauth2.sdk.auth.{ClientSecretBasic, Secret}
import com.nimbusds.oauth2.sdk.id.{ClientID, Issuer}
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.openid.connect.sdk._
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator

import java.net.URI
import javax.inject.{Inject, Singleton}
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

/** Sign-in with Google: OpenID Connect, its ID token checked against Google's keys.
  *
  * The endpoints come from the issuer's discovery document, fetched on the first sign-in
  * (a failed fetch is retried on the next) rather than at startup.
  */
@Singleton
class GoogleProvider @Inject() (settings: OAuthSettings) extends OAuthProvider {

  val id: String = OAuthProviders.Google

  @volatile private var resolved: Option[OIDCProviderMetadata] = None

  /** One per client id: each holds its cached copy of the provider's signing keys. */
  private val validators = TrieMap.empty[String, Seq[IDTokenValidator]]

  private def metadata: OIDCProviderMetadata =
    resolved.getOrElse(synchronized {
      resolved.getOrElse {
        val m =
          try
            OIDCProviderMetadata.resolve(
              new Issuer(settings.googleIssuer),
              settings.connectTimeoutMillis,
              settings.readTimeoutMillis
            )
          catch { case NonFatal(e) => throw new OAuthException(s"discovery failed: ${e.getMessage}", e) }
        resolved = Some(m)
        m
      }
    })

  def authorizationUri(client: OAuthClient, redirectUri: URI, flow: OAuthFlow): URI =
    new AuthenticationRequest.Builder(
      ResponseType.CODE,
      new Scope(OIDCScopeValue.OPENID, OIDCScopeValue.EMAIL, OIDCScopeValue.PROFILE),
      new ClientID(client.clientId),
      redirectUri
    ).endpointURI(metadata.getAuthorizationEndpointURI)
      .state(flow.state)
      .nonce(flow.nonce)
      .codeChallenge(flow.verifier, CodeChallengeMethod.S256)
      .prompt(Prompt.Type.SELECT_ACCOUNT)
      .build()
      .toURI

  def exchange(client: OAuthClient, redirectUri: URI, code: String, flow: OAuthFlow): ExternalProfile = {
    val request = new TokenRequest.Builder(
      metadata.getTokenEndpointURI,
      new ClientSecretBasic(new ClientID(client.clientId), new Secret(client.clientSecret)),
      new AuthorizationCodeGrant(new AuthorizationCode(code), redirectUri, flow.verifier)
    ).build()

    val response =
      try OIDCTokenResponseParser.parse(OAuthHttp.configured(request.toHTTPRequest, settings).send())
      catch { case NonFatal(e) => throw new OAuthException(s"token request failed: ${e.getMessage}", e) }

    if (!response.indicatesSuccess())
      throw new OAuthException(s"token error: ${response.toErrorResponse.getErrorObject.getCode}")

    val idToken = response.toSuccessResponse match {
      case oidc: OIDCTokenResponse => oidc.getOIDCTokens.getIDToken
      case _                       => throw new OAuthException("no ID token in the token response")
    }

    val claims = validate(client, idToken, flow)
    ExternalProfile(
      provider      = id,
      subject       = claims.getSubject.getValue,
      email         = Option(claims.getStringClaim("email")),
      emailVerified = isTrue(claims.getClaim("email_verified")),
      name          = Option(claims.getStringClaim("name"))
    )
  }

  /** The ID token's claims, if Google signed it for this client and this sign-in. Google
    * issues `https://accounts.google.com` or, in older tokens, `accounts.google.com`.
    */
  private def validate(client: OAuthClient, idToken: com.nimbusds.jwt.JWT, flow: OAuthFlow): IDTokenClaimsSet = {
    val candidates = validators.getOrElseUpdate(client.clientId, newValidators(client))
    val results = candidates.iterator.map { v =>
      try Right(v.validate(idToken, flow.nonce))
      catch { case NonFatal(e) => Left(e) }
    }.toSeq
    results.collectFirst { case Right(claims) => claims }.getOrElse {
      val error = results.collectFirst { case Left(e) => e.getMessage }.getOrElse("")
      throw new OAuthException(s"invalid ID token: $error")
    }
  }

  private def newValidators(client: OAuthClient): Seq[IDTokenValidator] = {
    val m         = metadata
    val retriever = new DefaultResourceRetriever(settings.connectTimeoutMillis, settings.readTimeoutMillis)
    val issuers   = Seq(m.getIssuer) ++ legacyIssuer(m.getIssuer)
    issuers.map { issuer =>
      new IDTokenValidator(issuer, new ClientID(client.clientId), JWSAlgorithm.RS256, m.getJWKSetURI.toURL, retriever)
    }
  }

  private def legacyIssuer(issuer: Issuer): Option[Issuer] =
    Some(issuer.getValue).filter(_ == "https://accounts.google.com").map(_ => new Issuer("accounts.google.com"))

  private def isTrue(claim: Any): Boolean = claim match {
    case b: java.lang.Boolean => b.booleanValue
    case s: String            => s.equalsIgnoreCase("true")
    case _                    => false
  }
}
