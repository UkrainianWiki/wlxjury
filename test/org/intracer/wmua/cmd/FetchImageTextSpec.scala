package org.intracer.wmua.cmd

import org.intracer.wmua.{ContestJury, Image, JuryTestHelpers}
import org.scalawiki.dto.{Namespace, Page, Revision}
import org.scalawiki.query.SinglePageQuery
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.test.PlaySpecification

import scala.concurrent.Future

class FetchImageTextSpec extends PlaySpecification with Mockito with JuryTestHelpers {

  private def contestImage(id: Long) =
    Image(
      pageId = id,
      title = s"File:Image$id.jpg",
      url = None,
      pageUrl = None,
      width = 0,
      height = 0,
      monumentId = Some(s"12-345-$id")
    )

  def revision(id: Long, text: String) =
    new Page(
      id = Some(id),
      ns = Some(Namespace.FILE),
      title = s"File:Image$id.jpg",
      revisions = Seq(
        new Revision(Some(id + 100), Some(id), content = Some(text))
      )
    )

  "appendImages" should {
    "get images empty" in {
      val category = "Category:Category Name"
      val contestId = 13
      val images = Seq.empty[Image]
      val revisions = Seq.empty[Page]

      val query = mock[SinglePageQuery]
      query.withContext(Map("contestId" -> contestId.toString, "max" -> "0")) returns query
      query.revisionsByGenerator(
        generator = "categorymembers",
        generatorPrefix = "cm",
        namespaces = Set(Namespace.FILE),
        props = Set("content", "timestamp", "user", "comment"),
        limit = "50",
        titlePrefix = None
      ) returns Future.successful(revisions)

      val commons = mockBot()
      commons.page(category) returns query

      val contest =
        ContestJury(Some(contestId), "WLE", 2015, "Ukraine", Some(category))

      await(FetchImageText(category, contest, None, commons).apply()) === images
    }

    "get images one image with text" in {
      val category = "Category:Category Name"
      val contestId = 13
      val imageId = 11
      val images = Seq(
        contestImage(imageId)
          .copy(description = Some("descr"), monumentId = None, author = Some("Author"))
      )
      val revisions = Seq(revision(imageId, "{{Information|description=descr|author=Author}}"))

      val query = mock[SinglePageQuery]
      query.withContext(Map("contestId" -> contestId.toString, "max" -> "0")) returns query
      query.revisionsByGenerator(
        "categorymembers",
        "cm",
        Set(Namespace.FILE),
        Set("content", "timestamp", "user", "comment"),
        limit = "50",
        titlePrefix = None
      ) returns Future.successful(revisions)

      val commons = mockBot()
      commons.page(category) returns query

      val contest =
        ContestJury(Some(contestId), "WLE", 2015, "Ukraine", Some(category), Some(0), None)

      await(FetchImageText(category, contest, None, commons).apply()) should_=== images
    }

    "get images one image with descr and monumentId" in {
      val category = "Category:Category Name"
      val idTemplate = "monumentId"
      val contestId = 13
      val imageId = 11
      val descr = s"descr. {{$idTemplate|12-345-$imageId}}"
      val images =
        Seq(contestImage(imageId).copy(description = Some(descr), author = Some("Author")))
      val revisions = Seq(revision(imageId, s"{{Information|description=$descr|author=Author}}"))

      val query = mock[SinglePageQuery]
      query.withContext(Map("contestId" -> contestId.toString, "max" -> "0")) returns query
      query.revisionsByGenerator(
        generator = "categorymembers",
        generatorPrefix = "cm",
        namespaces = Set(Namespace.FILE),
        props = Set("content", "timestamp", "user", "comment"),
        limit = "50",
        titlePrefix = None
      ) returns Future.successful(revisions)

      val commons = mockBot()
      commons.page(category) returns query

      val contest =
        ContestJury(
          id = Some(contestId),
          name = "WLE",
          year = 2015,
          country = "Ukraine",
          images = Some(category),
          categoryId = Some(0),
          currentRound = None
        )

      await(
        FetchImageText(category, contest, Some(idTemplate), commons)
          .apply()
      ) should_=== images
    }

    "one image from page" in {
      val page = "Commons:Wiki Loves Earth 2019/Winners"
      val idTemplate = "monumentId"
      val contestId = 13
      val imageId = 11
      val descr = s"descr. {{$idTemplate|12-345-$imageId}}"
      val images = Seq(contestImage(imageId).copy(description = Some(descr), author = None))
      val revisions =
        Seq(revision(imageId, s"{{Information|description=$descr}}"))

      val query = mock[SinglePageQuery]
      query.withContext(Map("contestId" -> contestId.toString, "max" -> "0")) returns query
      query.revisionsByGenerator(
        generator = "images",
        generatorPrefix = "im",
        namespaces = Set(Namespace.FILE),
        props = Set("content", "timestamp", "user", "comment"),
        limit = "50",
        titlePrefix = None
      ) returns Future.successful(revisions)

      val commons = mockBot()
      commons.page(page) returns query

      val contest =
        ContestJury(
          id = Some(contestId),
          name = "WLE",
          year = 2019,
          country = "International",
          images = Some(page),
          categoryId = Some(0),
          currentRound = None
        )

      await(
        FetchImageText(page, contest, Some(idTemplate), commons)
          .apply()
      ) should_=== images
    }
  }
}
