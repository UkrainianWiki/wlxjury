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

  /** A workflow or action line (its comment removed) that hands wiki credentials or secrets to
    * a job: COMMONS_* or SCALAWIKI_LOGIN etc. (SCALAWIKI_SHA, the commit CI builds, is not
    * one), secrets.X but GITHUB_TOKEN, secrets['X'], toJSON(secrets), `secrets: inherit`, or
    * the test-login switch.
    */
  private def passesCredentials(code: String): Boolean =
    code.matches(".*\\b(COMMONS_[A-Z]+|SCALAWIKI_(LOGIN|PASSWORD|USER|TOKEN))\\b.*") ||
      code.matches(".*\\bsecrets\\s*\\.(?!GITHUB_TOKEN\\b).*") ||
      code.matches(".*\\bsecrets\\s*\\[.*") ||
      code.matches("(?i).*\\btoJSON\\(\\s*secrets\\s*\\).*") ||
      code.matches(".*\\bsecrets\\s*:\\s*inherit\\b.*") ||
      code.contains("allowTestLogin")

  private def yamlFiles(dir: File): Seq[File] =
    Option(dir.listFiles()).toSeq.flatten.flatMap { f =>
      if (f.isDirectory) yamlFiles(f)
      else if (f.getName.endsWith(".yml") || f.getName.endsWith(".yaml")) Seq(f)
      else Nil
    }

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

  "the CI workflows and actions" should {

    "pass no Commons or scalawiki credentials and no secrets but GITHUB_TOKEN" in {
      val files = yamlFiles(new File(".github/workflows")) ++ yamlFiles(new File(".github/actions"))
      val lines = for {
        file <- files
        (line, number) <- scala.io.Source.fromFile(file, "UTF-8").getLines().toSeq.zipWithIndex
        code = line.replaceFirst("\\s#.*$", "").replaceFirst("^\\s*#.*$", "")
        if passesCredentials(code)
      } yield s"${file.getPath}:${number + 1}: $line"
      (files.exists(_.getPath.contains("workflows")) must beTrue) and
        (files.exists(_.getPath.contains("actions")) must beTrue) and
        (lines must beEmpty)
    }

    "be checked for the forms that pass secrets" in {
      Seq("password: ${{ secrets['COMMONS'] }}", "all: ${{ toJSON(secrets) }}", "    secrets: inherit",
        "token: ${{ secrets.BOT_TOKEN }}", "COMMONS_USER: x", "-Dcommons.allowTestLogin=true")
        .filterNot(passesCredentials) must beEmpty
    }

    "not flag the token they use, or the scalawiki commit" in {
      Seq("GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}", "SCALAWIKI_SHA: 25951dbe").filter(passesCredentials) must beEmpty
    }
  }
}
