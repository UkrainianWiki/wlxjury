package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.intracer.wmua.Image
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

/** The `/thumb_urls` export: one absolute URL a line, for wget or a script. */
class ThumbnailUrlsExportSpec extends PlaySpecification with PlayTestDb {

  sequential

  "the thumbnail urls export [A-20]" should {
    "list absolute URLs on the app's host (wlxjury.host)" in {
      testDbApp { implicit app =>
        val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
        val image = Image(1L, "File:Corallus.jpg",
          Some("https://upload.wikimedia.org/wikipedia/commons/9/9e/Corallus.jpg"), None, 4000, 3000, None)
        imageDao.batchInsert(Seq(image))
        contestDao.setImagesSource(contest.getId, Some("Category:WLE images"))
        db.scalikejdbc.CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, Seq(image))

        val result = route(app, FakeRequest(GET, s"/thumb_urls/${contest.getId}")
          .withSession(Secured.UserName -> admin.email).withCSRFToken).get
        status(result) must_== OK
        implicit val mat: org.apache.pekko.stream.Materializer = app.materializer
        val lines = contentAsString(result).linesIterator.toSeq
        lines must not(beEmpty)
        lines must contain(startWith("https://jury.wle.org.ua/wikipedia/commons/thumb/9/9e/Corallus.jpg/")).forall
      }
    }
  }
}
