package org.intracer.wmua.cmd

import org.intracer.wmua.{ContestJury, Image, JuryTestHelpers}
import org.scalawiki.dto.{Namespace, Page}
import org.scalawiki.query.SinglePageQuery
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.test.PlaySpecification

import scala.concurrent.Future

class FetchImageInfoSpec extends PlaySpecification with Mockito with JuryTestHelpers {

  private def contestImage(id: Long) =
    Image(
      pageId = id,
      title = s"File:Image$id.jpg",
      url = Some(s"url$id"),
      pageUrl = Some(s"pageUrl$id"),
      width = 640,
      height = 480,
      monumentId = None,
      size = Some(1234)
    )

  def imageInfo(id: Long) =
    new Page(
      Some(id),
      Some(Namespace.FILE),
      s"File:Image$id.jpg",
      images = Seq(
        new org.scalawiki.dto.Image(
          title = s"File:Image$id.jpg",
          url = Some(s"url$id"),
          pageUrl = Some(s"pageUrl$id"),
          size = Some(1234),
          width = Some(640),
          height = Some(480)
        )
      )
    )

  "appendImages" should {
    "get images empty" in {
      // implicit ee: ExecutionEnv =>
      val category = "Category:Category Name"
      val contestId = 13
      val imageId = 11
      val images = Seq.empty[Image]
      val imageInfos = Seq.empty[Page]

      val query = mock[SinglePageQuery]

      query.withContext(Map("contestId" -> contestId.toString, "max" -> "0")) returns query
      query.imageInfoByGenerator(
        generator = "categorymembers",
        generatorPrefix = "cm",
        namespaces = Set(Namespace.FILE),
        props = Set("timestamp", "user", "size", "url", "mime"),
        titlePrefix = None
      ) returns Future.successful(imageInfos)

      val commons = mockBot()
      commons.page(category) returns query

      val contest = ContestJury(
        id = Some(contestId),
        name = "WLE",
        year = 2015,
        country = "Ukraine",
        images = Some(category),
        categoryId = None,
        currentRound = None
      )

      await(
        FetchImageInfo(source = category, titles = Seq.empty, contest = contest, commons = commons)
          .apply()
      ) must be_==(images)
    }

    "get images one image" in {
      val category = "Category:Category Name"
      val contestId = 13
      val imageId = 11
      val images = Seq(contestImage(imageId))
      val imageInfos = Seq(imageInfo(imageId))

      val query = mock[SinglePageQuery]
      query.withContext(Map("contestId" -> contestId.toString, "max" -> "0")) returns query
      query.imageInfoByGenerator(
        generator = "categorymembers",
        generatorPrefix = "cm",
        namespaces = Set(Namespace.FILE),
        props = Set("timestamp", "user", "size", "url", "mime"),
        titlePrefix = None
      ) returns Future.successful(imageInfos)

      val commons = mockBot()
      commons.page(category) returns query

      val contest =
        ContestJury(
          id = Some(contestId),
          name = "WLE",
          year = 2015,
          country = "Ukraine",
          images = Some(category)
        )

      await(
        FetchImageInfo(source = category, titles = Seq.empty, contest = contest, commons = commons)
          .apply()
      ) must be_==(images)
    }
  }

}
