package services.oauth

import com.sun.net.httpserver.HttpServer
import org.specs2.mutable.Specification
import play.api.Configuration

import java.net.{InetSocketAddress, URI}
import java.util.concurrent.atomic.AtomicInteger

/** [[GoogleProvider]]'s discovery document: a failed fetch is not kept, so the next sign-in
  * fetches it again (CodeRabbit on #240: the failed future stayed cached for good).
  */
class GoogleProviderSpec extends Specification {

  sequential

  "the discovery document" should {
    "be fetched again by the next sign-in after a failed fetch" in {
      val requests = new AtomicInteger
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/", exchange => {
        requests.incrementAndGet()
        exchange.sendResponseHeaders(503, -1)
        exchange.close()
      })
      server.start()
      try {
        val issuer = s"http://127.0.0.1:${server.getAddress.getPort}"
        // no hosts: Google isn't offered, so nothing is fetched at start
        val provider = new GoogleProvider(new OAuthSettings(Configuration.from(Map(
          "oauth.google.issuer" -> issuer, "oauth.connectTimeout" -> "2s", "oauth.readTimeout" -> "2s"))))
        def signIn(): Unit =
          provider.authorizationUri(OAuthClient("id", "secret"), URI.create("https://jury.example/oauth/google/callback"),
            OAuthFlow.random()) must throwA[OAuthException]
        signIn()
        requests.get === 1
        // the failed fetch is forgotten by a callback once it fails: a later sign-in fetches again
        val deadline = System.nanoTime + 10L * 1000 * 1000 * 1000
        while (requests.get < 2 && System.nanoTime < deadline) { signIn(); if (requests.get < 2) Thread.sleep(50) }
        requests.get must be_>=(2)
      } finally server.stop(0)
    }
  }
}
