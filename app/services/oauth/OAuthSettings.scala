package services.oauth

import play.api.Configuration

import java.net.URI
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._

/** A provider's OAuth client: what its console issued for one of our hosts. */
case class OAuthClient(clientId: String, clientSecret: String) {
  override def toString: String = s"OAuthClient($clientId, ***)"
}

/** The `oauth` block of application.conf (docs/oauth.md).
  *
  * The request's Host header is the client's to choose (AllowedHostsFilter is off), so a
  * provider is offered, and a redirect URI built, only for a host the config names.
  */
@Singleton
class OAuthSettings @Inject() (configuration: Configuration) {

  private val oauth = configuration.getOptional[Configuration]("oauth").getOrElse(Configuration.empty)

  /** "https" in production, where Apache ends TLS and Play sees plain HTTP. */
  val scheme: String = oauth.getOptional[String]("scheme").getOrElse("https")

  val connectTimeoutMillis: Int = duration("connectTimeout", 5.seconds).toMillis.toInt
  val readTimeoutMillis: Int    = duration("readTimeout", 10.seconds).toMillis.toInt

  /** The longest a sign-in may take from leaving for the provider to coming back. */
  val flowTimeout: FiniteDuration = duration("flowTimeout", 10.minutes)

  val googleIssuer: String =
    oauth.getOptional[String]("google.issuer").getOrElse("https://accounts.google.com")

  val wikimediaBaseUrl: String =
    oauth.getOptional[String]("wikimedia.baseUrl").getOrElse("https://meta.wikimedia.org/w/rest.php").stripSuffix("/")

  private val googleClient: Option[OAuthClient] =
    credentials(oauth.getOptional[String]("google.clientId"), oauth.getOptional[String]("google.clientSecret"))

  private val googleHosts: Set[String] =
    oauth.getOptional[Seq[String]]("google.hosts").getOrElse(Nil).map(normalizeHost).toSet

  private val wikimediaClients: Map[String, OAuthClient] =
    oauth.getOptional[Seq[Configuration]]("wikimedia.clients").getOrElse(Nil).flatMap { c =>
      def opt(path: String) = c.getOptional[String](path)
      for {
        host   <- opt("host").map(normalizeHost)
        client <- credentials(opt("clientId"), opt("clientSecret"))
      } yield host -> client
    }.toMap

  /** The client to use for the provider on the host, if it is offered there. */
  def client(provider: String, host: String): Option[OAuthClient] = provider match {
    case OAuthProviders.Google    => googleClient.filter(_ => googleHosts.contains(normalizeHost(host)))
    case OAuthProviders.Wikimedia => wikimediaClients.get(normalizeHost(host))
    case _                        => None
  }

  /** The providers offered on the host, in the order the login page shows them. */
  def providersFor(host: String): Seq[String] =
    OAuthProviders.All.filter(client(_, host).isDefined)

  /** Where the provider sends the user back to; registered with the provider for each host. */
  def redirectUri(provider: String, host: String): URI =
    URI.create(s"$scheme://${normalizeHost(host)}/oauth/$provider/callback")

  private def duration(path: String, default: FiniteDuration): FiniteDuration =
    oauth.getOptional[FiniteDuration](path).getOrElse(default)

  private def normalizeHost(host: String): String = host.trim.toLowerCase

  private def credentials(id: Option[String], secret: Option[String]): Option[OAuthClient] =
    for {
      i <- id.map(_.trim).filter(_.nonEmpty)
      s <- secret.map(_.trim).filter(_.nonEmpty)
    } yield OAuthClient(i, s)
}
