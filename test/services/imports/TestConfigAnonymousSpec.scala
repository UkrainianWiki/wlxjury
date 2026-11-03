package services.imports

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import play.api.{Configuration, Environment, Mode}

import java.io.File
import scala.jdk.CollectionConverters._

/** Tests and CI use Wikimedia anonymously: the test config takes no Commons account from the
  * environment, a Mode.Test app doesn't log in, and no workflow passes Commons or scalawiki
  * credentials. A machine with the bot's credentials in its environment once turned every
  * test run into a login, and a changed bot password into failed logins on the bot's account.
  */
class TestConfigAnonymousSpec extends Specification {

  private val testConfig = new File("test/resources/application.conf")

  /** The test config resolved as if the environment held the bot's account: `${?NAME}`
    * substitutions find these values at the root, as they would find environment variables.
    */
  private def resolvedWithAccountInEnv =
    ConfigFactory.parseMap(Map(
      "COMMONS_USER" -> "Someone@bot", "COMMONS_PASSWORD" -> "from-the-environment",
      "SCALAWIKI_LOGIN" -> "Someone@bot", "SCALAWIKI_PASSWORD" -> "from-the-environment").asJava)
      .withFallback(ConfigFactory.parseFile(testConfig))
      .resolve()

  "the test config" should {

    "have no Commons account, whatever the environment holds" in {
      val config = resolvedWithAccountInEnv
      (config.getString("commons.user") === "") and (config.getString("commons.password") === "")
    }

    "not read Commons or scalawiki credentials from the environment" in {
      val settings = scala.io.Source.fromFile(testConfig, "UTF-8").getLines().toSeq
        .filterNot(_.trim.startsWith("#"))
      settings.filter(_.matches(".*\\$\\{\\??(COMMONS|SCALAWIKI)_[A-Z]+\\}.*")) must beEmpty
    }

    "be what this test JVM's apps load, and give them no account" in {
      // the forked test JVM runs with -Dconfig.file=test/resources/application.conf (build.sbt)
      val loaded = Configuration.load(Environment.simple(mode = Mode.Test))
      (loaded.get[String]("commons.user") === "") and
        (CommonsLogin.credentials(loaded, Mode.Test) must beNone)
    }
  }

  "the CI workflows" should {

    "pass no Commons or scalawiki credentials and no secrets but GITHUB_TOKEN" in {
      val workflows = Option(new File(".github/workflows").listFiles()).toSeq.flatten
        .filter(f => f.getName.endsWith(".yml") || f.getName.endsWith(".yaml"))
      val lines = for {
        file <- workflows
        (line, number) <- scala.io.Source.fromFile(file, "UTF-8").getLines().toSeq.zipWithIndex
        code = line.replaceFirst("\\s#.*$", "").replaceFirst("^\\s*#.*$", "")
        // credential names; SCALAWIKI_SHA (the commit CI builds) is not one
        if code.matches(".*\\b(COMMONS_[A-Z]+|SCALAWIKI_(LOGIN|PASSWORD|USER|TOKEN))\\b.*") ||
          code.matches(".*\\bsecrets\\.(?!GITHUB_TOKEN\\b).*") || code.contains("allowTestLogin")
      } yield s"${file.getName}:${number + 1}: $line"
      (workflows must not(beEmpty)) and (lines must beEmpty)
    }
  }
}
