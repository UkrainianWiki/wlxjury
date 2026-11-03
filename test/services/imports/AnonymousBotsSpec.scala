package services.imports

import db.scalikejdbc.SharedPlayApp
import org.scalawiki.MwBot
import org.specs2.mutable.Specification

import java.io.File

/** scalawiki's default bots (`MwBot.fromHost(host)`, as `MonumentQuery.create`,
  * `SpecialNomination.getNominationMonuments` and `CampaignList` take them) log in from the
  * environment when they are created, and `MwBot.cache` keeps one per domain. The app creates
  * the anonymous ones first ([[AnonymousBots]]), so those default calls get them.
  */
class AnonymousBotsSpec extends Specification {

  sequential

  "the test app" should {

    "create the anonymous bots that scalawiki's default calls then get" in {
      SharedPlayApp.init() // starts the app: AppModule seeds the cache
      val seeded = MwBot.cache.keys
      (AnonymousBots.Hosts.forall(seeded.contains) must beTrue) and
        // the default loginInfo (LoginInfo.fromEnv) is not used: the cached bot is returned
        (MwBot.fromHost(MwBot.ukWiki) must beTheSameAs(AnonymousBots.bot(MwBot.ukWiki))) and
        (MwBot.fromHost(MwBot.commons) must beTheSameAs(AnonymousBots.bot(MwBot.commons)))
    }
  }

  "the app's sources" should {

    "create bots and monument queries only without a login" in {
      def scalaFiles(dir: File): Seq[File] =
        Option(dir.listFiles()).toSeq.flatten.flatMap { f =>
          if (f.isDirectory) scalaFiles(f) else if (f.getName.endsWith(".scala")) Seq(f) else Nil
        }
      val offending = for {
        file <- scalaFiles(new File("app")) if file.getName != "AnonymousBots.scala"
        (line, number) <- scala.io.Source.fromFile(file, "UTF-8").getLines().toSeq.zipWithIndex
        code = line.replaceFirst("//.*$", "")
        if code.matches(".*MwBot\\.(fromHost|fromSite|create)\\(.*") ||
          // MonumentQuery.create(contest) without an explicit bot takes scalawiki's default one
          (code.contains("MonumentQuery.create(") && !code.matches(".*MonumentQuery\\.create\\([^)]*\\)\\(.*")) ||
          code.contains("ImageQuery.create(")
      } yield s"${file.getPath}:${number + 1}: ${line.trim}"
      offending must beEmpty
    }
  }
}
