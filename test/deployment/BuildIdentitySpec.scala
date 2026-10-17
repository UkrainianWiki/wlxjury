package deployment

import org.specs2.mutable.Specification
import play.api.libs.json.Json
import wlxjury.build.BuildIdentity

import java.io.File

class BuildIdentitySpec extends Specification {
  "a development build" should {
    "name its version and commit, marked when the sources differ from it" in {
      BuildIdentity.buildId("0.13", Some("dbedca565807"), dirty = false, None) must beRight("0.13-dbedca565807")
      BuildIdentity.buildId("0.13", Some("dbedca565807"), dirty = true, None) must beRight("0.13-dbedca565807-dirty")
      BuildIdentity.buildId("0.13", None, dirty = true, None) must beRight("0.13-development-dirty")
    }
  }

  "a requested release build ID" should {
    "be refused for sources with uncommitted or untracked changes" in {
      BuildIdentity.buildId("0.13", Some("dbedca565807"), dirty = true, Some("0.13-reviewed")) must beLeft
    }
    "always carry the commit it was built from" in {
      BuildIdentity.buildId("0.13", Some("dbedca565807"), dirty = false, Some("0.13-reviewed")) must
        beRight("0.13-reviewed-dbedca565807")
      BuildIdentity.buildId("0.13", Some("dbedca565807"), dirty = false, Some("0.13-dbedca565807")) must
        beRight("0.13-dbedca565807")
      BuildIdentity.buildId("0.13", None, dirty = false, Some("0.13-reviewed")) must beLeft
    }
    "be a safe directory name" in {
      BuildIdentity.buildId("0.13", Some("dbedca565807"), dirty = false, Some("../etc")) must beLeft
      BuildIdentity.buildId("0.13", Some("dbedca565807"), dirty = false, Some("a b")) must beLeft
    }
  }

  "the source status" should {
    "count untracked files as changes" in {
      // `git status --porcelain` output; `git diff` reported nothing for these.
      BuildIdentity.isDirty("?? app/views/error401.scala.html\n") must beTrue
      BuildIdentity.isDirty(" M build.sbt\n") must beTrue
      BuildIdentity.isDirty("") must beFalse
      BuildIdentity.isDirty("\n") must beFalse
    }
  }

  "the expected schema" should {
    "be the numerically latest versioned migration, as Flyway records it" in {
      BuildIdentity.latestMigration(Seq("V9__a.sql", "V10__b.sql", "B44__Baseline.sql", "R__view.sql")) mustEqual "10"
      BuildIdentity.latestMigration(Seq("V55__a.sql", "V55_1__b.sql", "V54__c.sql")) mustEqual "55.1"
      BuildIdentity.latestMigration(Seq("V55_1__b.sql", "V60__c.sql")) mustEqual "60"
    }
    "match the repository's migrations in the packaged manifest" in {
      val names = new File("conf/db/migration/default").listFiles().map(_.getName).toSeq
      val stream = getClass.getClassLoader.getResourceAsStream("wlxjury-build.json")
      val manifest = try Json.parse(stream) finally stream.close()
      (manifest \ "schemaVersion").as[String] mustEqual BuildIdentity.latestMigration(names)
    }
  }
}
