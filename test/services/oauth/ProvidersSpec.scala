package services.oauth

import com.nimbusds.jose.crypto.{MACSigner, RSASSASigner}
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, PlainJWT, SignedJWT}
import com.nimbusds.oauth2.sdk.pkce.{CodeChallenge, CodeChallengeMethod}
import org.specs2.mutable.Specification
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.{AnyContent, Request, Results}
import play.api.routing.sird._
import play.core.server.Server

import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

/** The real Google and Wikimedia providers against a local fake provider: discovery, token,
  * JWKS and profile endpoints, ID tokens signed with a test key.
  */
class ProvidersSpec extends Specification {

  sequential

  private val key: RSAKey = new RSAKeyGenerator(2048).keyID("test-key").generate()
  private val client      = OAuthClient("client-1", "secret-1")
  private val redirectUri = URI.create("https://jury.example.org/oauth/google/callback")

  /** The token request the fake provider got last. */
  private val tokenForm = new AtomicReference[Map[String, Seq[String]]](Map.empty)
  private val tokenAuth = new AtomicReference[Option[String]](None)

  /** What the next ID token says, given the issuer and the nonce the test expects. */
  private val idTokenClaims = new AtomicReference[(String, String) => JWTClaimsSet](validClaims)

  private def validClaims(issuer: String, nonce: String): JWTClaimsSet =
    new JWTClaimsSet.Builder()
      .issuer(issuer)
      .audience(client.clientId)
      .subject("google-sub-1")
      .issueTime(new Date())
      .expirationTime(new Date(System.currentTimeMillis + 600000))
      .claim("nonce", nonce)
      .claim("email", "Alice@Example.com")
      .claim("email_verified", true)
      .claim("name", "Alice")
      .claim("hd", "example.com")
      .build()

  private val nonce = new AtomicReference[String]("")

  /** How the fake provider signs the next ID token: with its published key, or a test's way. */
  private val signer = new AtomicReference[JWTClaimsSet => String](signed)

  private def signedWith(rsa: RSAKey)(claims: JWTClaimsSet): String = {
    val jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID).build(), claims)
    jwt.sign(new RSASSASigner(rsa))
    jwt.serialize()
  }

  private def signed(claims: JWTClaimsSet): String = {
    val jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID).build(), claims)
    jwt.sign(new RSASSASigner(key))
    jwt.serialize()
  }

  private def form(request: Request[AnyContent]): Map[String, Seq[String]] =
    request.body.asFormUrlEncoded.getOrElse(Map.empty)

  private def withProvider[T](block: (String, OAuthSettings) => T): T =
    Server.withRouterFromComponents() { components =>
      import Results._
      import components.{defaultActionBuilder => Action}
      {
        case GET(p"/.well-known/openid-configuration") =>
          Action { request =>
            val base = s"http://${request.host}"
            Ok(
              Json.obj(
                "issuer"                                -> base,
                "authorization_endpoint"                -> s"$base/authorize",
                "token_endpoint"                        -> s"$base/token",
                "jwks_uri"                              -> s"$base/jwks",
                "response_types_supported"              -> Seq("code"),
                "subject_types_supported"               -> Seq("public"),
                "id_token_signing_alg_values_supported" -> Seq("RS256")
              )
            )
          }
        case GET(p"/jwks") =>
          Action(Ok(new JWKSet(key).toString(true)).as("application/json"))
        case POST(p"/token") =>
          Action { request =>
            tokenForm.set(form(request))
            tokenAuth.set(request.headers.get("Authorization"))
            if (!form(request).get("code").contains(Seq("good-code")))
              BadRequest(Json.obj("error" -> "invalid_grant"))
            else {
              val idToken = signer.get()(idTokenClaims.get()(s"http://${request.host}", nonce.get()))
              Ok(Json.obj("access_token" -> "g-token", "token_type" -> "Bearer", "expires_in" -> 3600, "id_token" -> idToken))
            }
          }
        case POST(p"/w/rest.php/oauth2/access_token") =>
          Action { request =>
            tokenForm.set(form(request))
            tokenAuth.set(request.headers.get("Authorization"))
            if (!form(request).get("code").contains(Seq("good-code")))
              BadRequest(Json.obj("error" -> "invalid_grant"))
            else Ok(Json.obj("access_token" -> "wm-token", "token_type" -> "Bearer", "expires_in" -> 3600))
          }
        case GET(p"/w/rest.php/oauth2/resource/profile") =>
          Action { request =>
            if (!request.headers.get("Authorization").contains("Bearer wm-token")) Unauthorized
            else
              Ok(
                Json.obj(
                  "sub"             -> "12345",
                  "username"        -> "Jane Doe",
                  "editcount"       -> 100,
                  "confirmed_email" -> true,
                  "email"           -> "jane@example.org",
                  "realname"        -> "Jane"
                )
              )
          }
        case POST(p"/slow/w/rest.php/oauth2/access_token") =>
          Action {
            Thread.sleep(2000)
            Ok
          }
      }
    } { port =>
      val base = s"http://localhost:$port"
      val settings = new OAuthSettings(
        Configuration.from(
          Map(
            "oauth.google.issuer"     -> base,
            "oauth.wikimedia.baseUrl" -> s"$base/w/rest.php",
            "oauth.readTimeout"       -> "1s"
          )
        )
      )
      block(base, settings)
    }

  private def query(uri: URI): Map[String, String] =
    uri.getRawQuery
      .split("&")
      .map(_.split("=", 2))
      .map(kv => kv(0) -> URLDecoder.decode(kv(1), UTF_8))
      .toMap

  "GoogleProvider" should {
    "send the user to the discovered authorization endpoint with state, nonce and PKCE" in withProvider {
      (base, settings) =>
        val flow   = OAuthFlow.random()
        val uri    = new GoogleProvider(settings).authorizationUri(client, redirectUri, flow)
        val params = query(uri)

        uri.toString must startWith(s"$base/authorize?")
        params("client_id") === client.clientId
        params("redirect_uri") === redirectUri.toString
        params("response_type") === "code"
        params("scope").split(" ").toSet === Set("openid", "email", "profile")
        params("state") === flow.state.getValue
        params("nonce") === flow.nonce.getValue
        params("code_challenge_method") === "S256"
        params("code_challenge") === CodeChallenge.compute(CodeChallengeMethod.S256, flow.verifier).getValue
        params("prompt") === "select_account"
    }

    "trade the code for the ID token's user, sending the PKCE verifier" in withProvider { (_, settings) =>
      val flow = OAuthFlow.random()
      nonce.set(flow.nonce.getValue)
      idTokenClaims.set(validClaims)

      val profile = new GoogleProvider(settings).exchange(client, redirectUri, "good-code", flow)

      profile === ExternalProfile("google", "google-sub-1", Some("Alice@Example.com"), emailVerified = true, None, Some("Alice"),
        Some("example.com"))
      profile.verifiedEmail === Some("alice@example.com")
      tokenForm.get().get("code_verifier") === Some(Seq(flow.verifier.getValue))
      tokenForm.get().get("redirect_uri") === Some(Seq(redirectUri.toString))
      tokenAuth.get() must beSome(startWith("Basic "))
    }

    "refuse an ID token for another sign-in, client or issuer" in withProvider { (_, settings) =>
      val provider = new GoogleProvider(settings)
      val flow     = OAuthFlow.random()

      nonce.set("another-nonce")
      idTokenClaims.set(validClaims)
      provider.exchange(client, redirectUri, "good-code", flow) must throwA[OAuthException]

      nonce.set(flow.nonce.getValue)
      idTokenClaims.set((iss, n) => new JWTClaimsSet.Builder(validClaims(iss, n)).audience("another-client").build())
      provider.exchange(client, redirectUri, "good-code", flow) must throwA[OAuthException]

      idTokenClaims.set((_, n) => validClaims("https://evil.example", n))
      provider.exchange(client, redirectUri, "good-code", flow) must throwA[OAuthException]
    }

    "refuse an ID token signed with another key, an unknown key id, alg none or HS256" in withProvider { (_, settings) =>
      val flow = OAuthFlow.random()
      nonce.set(flow.nonce.getValue)
      idTokenClaims.set(validClaims)
      def refused(sign: JWTClaimsSet => String) = {
        signer.set(sign)
        try new GoogleProvider(settings).exchange(client, redirectUri, "good-code", flow) must throwA[OAuthException]
        finally signer.set(signed)
      }
      refused(signedWith(new RSAKeyGenerator(2048).keyID("test-key").generate()))
      refused(signedWith(new RSAKeyGenerator(2048).keyID("unknown-kid").generate()))
      refused(claims => new PlainJWT(claims).serialize())
      refused { claims =>
        val jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims)
        // the client secret as the HMAC key, the classic confusion: padded to HS256's 256 bits
        jwt.sign(new MACSigner((client.clientSecret * 8).getBytes(UTF_8)))
        jwt.serialize()
      }
    }

    "refuse an expired ID token and one issued in the future" in withProvider { (_, settings) =>
      val flow = OAuthFlow.random()
      nonce.set(flow.nonce.getValue)
      val hour = 3600000L
      idTokenClaims.set((iss, n) => new JWTClaimsSet.Builder(validClaims(iss, n))
        .issueTime(new Date(System.currentTimeMillis - 2 * hour)).expirationTime(new Date(System.currentTimeMillis - hour)).build())
      new GoogleProvider(settings).exchange(client, redirectUri, "good-code", flow) must throwA[OAuthException]
      idTokenClaims.set((iss, n) => new JWTClaimsSet.Builder(validClaims(iss, n))
        .issueTime(new Date(System.currentTimeMillis + hour)).expirationTime(new Date(System.currentTimeMillis + 2 * hour)).build())
      try new GoogleProvider(settings).exchange(client, redirectUri, "good-code", flow) must throwA[OAuthException]
      finally idTokenClaims.set(validClaims)
    }

    "take an email as unverified when email_verified is false or \"false\"" in withProvider { (_, settings) =>
      val flow = OAuthFlow.random()
      nonce.set(flow.nonce.getValue)
      def profileWith(value: Any) = {
        idTokenClaims.set((iss, n) => new JWTClaimsSet.Builder(validClaims(iss, n)).claim("email_verified", value).build())
        try new GoogleProvider(settings).exchange(client, redirectUri, "good-code", flow)
        finally idTokenClaims.set(validClaims)
      }
      forall(Seq[Any](false, "false")) { v =>
        val profile = profileWith(v)
        (profile.emailVerified must beFalse) and (profile.verifiedEmail must beNone)
      }
    }

    "fail on the provider's error" in withProvider { (_, settings) =>
      new GoogleProvider(settings).exchange(client, redirectUri, "bad-code", OAuthFlow.random()) must
        throwA[OAuthException](message = "invalid_grant")
    }
  }

  "WikimediaProvider" should {
    "send the user to the authorization endpoint with state and PKCE" in withProvider { (base, settings) =>
      val flow   = OAuthFlow.random()
      val uri    = new WikimediaProvider(settings).authorizationUri(client, redirectUri, flow)
      val params = query(uri)

      uri.toString must startWith(s"$base/w/rest.php/oauth2/authorize?")
      params("client_id") === client.clientId
      params("redirect_uri") === redirectUri.toString
      params("state") === flow.state.getValue
      params("code_challenge_method") === "S256"
      params("code_challenge") === CodeChallenge.compute(CodeChallengeMethod.S256, flow.verifier).getValue
    }

    "trade the code for the profile's user" in withProvider { (_, settings) =>
      val flow    = OAuthFlow.random()
      val profile = new WikimediaProvider(settings).exchange(client, redirectUri, "good-code", flow)

      profile === ExternalProfile("wikimedia", "12345", Some("jane@example.org"), emailVerified = true, Some("Jane Doe"), Some("Jane"))
      tokenForm.get().get("client_id") === Some(Seq(client.clientId))
      tokenForm.get().get("client_secret") === Some(Seq(client.clientSecret))
      tokenForm.get().get("code_verifier") === Some(Seq(flow.verifier.getValue))
    }

    "fail on the provider's error" in withProvider { (_, settings) =>
      new WikimediaProvider(settings).exchange(client, redirectUri, "bad-code", OAuthFlow.random()) must
        throwA[OAuthException](message = "invalid_grant")
    }

    "give up on a provider that does not answer" in withProvider { (base, _) =>
      val slow = new OAuthSettings(
        Configuration.from(Map("oauth.wikimedia.baseUrl" -> s"$base/slow/w/rest.php", "oauth.readTimeout" -> "500ms"))
      )
      new WikimediaProvider(slow).exchange(client, redirectUri, "good-code", OAuthFlow.random()) must
        throwA[OAuthException]
    }

    "read a profile without email" in {
      val provider = new WikimediaProvider(new OAuthSettings(Configuration.empty))
      provider.parseProfile(Json.obj("sub" -> 7, "username" -> "Jane")) ===
        ExternalProfile("wikimedia", "7", None, emailVerified = false, Some("Jane"), None)
      provider.parseProfile(Json.obj("username" -> "Jane")) must throwA[OAuthException]
      // an email the wiki has not confirmed is not a verified one
      val unconfirmed = provider.parseProfile(Json.obj("sub" -> "8", "username" -> "Jane", "email" -> "j@example.org",
        "confirmed_email" -> false))
      (unconfirmed.emailVerified must beFalse) and (unconfirmed.verifiedEmail must beNone)
    }
  }
}
