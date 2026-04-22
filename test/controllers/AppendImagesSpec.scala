package controllers

import db.scalikejdbc.TestDb
import org.intracer.wmua.{Image, JuryTestHelpers}
import org.scalawiki.MwBot
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.{Namespace, Page, Revision}
import org.scalawiki.query.SinglePageQuery
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.{ImageService, MonumentService}

import scala.concurrent.{ExecutionContext, Future}

class AppendImagesSpec extends Specification with Mockito with JuryTestHelpers with TestDb {

  sequential
  stopOnFail

  implicit val ec: ExecutionContext = ExecutionContext.global

  "appendImages" should {
    val category = "Category:Category Name"
    val contestId = 10
    val idTemplate = "MonumentId"

    def createContest(
        monumentIdTemplate: Option[String] = None,
        id: Option[Long] = Some(contestId),
        images: Option[String] = Some(category)
    ) = {
      contestDao.create(
        id = id,
        name = "WLE",
        year = 2015,
        country = "Ukraine",
        images = images,
        categoryId = None,
        currentRound = None,
        monumentIdTemplate = monumentIdTemplate
      )
    }

    "get images empty" in {
      withDb {
        val images = Nil
        val ic = mockService(images, category, contestId)
        val contest = createContest()

        ic.appendImages(category, "", contest)

        imageDao.findByContest(contest) === images
      }
    }

    "get one image with text" in {
      withDb {
        val images = Seq(image(id = 11).copy(description = Some("descr"), monumentId = None))
        val contest = createContest(Some("NaturalMonument"))

        val ic = mockService(images, category, contestId)
        ic.appendImages(category, "", contest)

        val contestWithCategory = contestDao.findById(contest.getId).get
        eventually {
          imageDao.findByContest(contestWithCategory) === images
        }
      }
    }

    "get one image with descr and monumentId" in {
      withDb {
        val imageId = 11
        val descr = s"descr. {{$idTemplate|12-345-$imageId}}"
        val images = Seq(image(id = 11).copy(description = Some(descr)))

        val contest = createContest(Some(idTemplate))

        val ic = mockService(images, category, contestId)
        ic.appendImages(category, "", contest)

        val contestWithCategory = contestDao.findById(contest.getId).get
        eventually {
          imageDao.findByContest(contestWithCategory) === images
        }
      }
    }

    "get several images image with descr and monumentId" in {
      withDb {
        val images =
          (11 to 15).map(id => image(id).copy(description = Some(s"{{$idTemplate|12-345-$id}}")))
        val contest = createContest(Some(idTemplate))

        val ic = mockService(images, category, contestId)
        ic.appendImages(category, "", contest)

        val contestWithCategory = contestDao.findById(contest.getId).get
        eventually {
          imageDao.findByContest(contestWithCategory) === images
        }
      }
    }

    "get images from list" in {
      withDb {
        val images = (11 to 15).map(id => image(id).copy(monumentId = None))

        val contest = createContest()
        val ic = mockService(images, category, contestId)
        val imageList = images.map(_.title).mkString(System.lineSeparator)
        ic.appendImages("", imageList, contest)

        val contestWithCategory = contestDao.findById(contest.getId).get
        eventually {
          imageDao.findByContest(contestWithCategory) === images
        }
      }
    }

    "update images" in {
      withDb {
        val images1 = (11 to 15).map(id =>
          image(id).copy(
            description = Some(s"{{$idTemplate|12-345-$id}}"),
            monumentId = Some(s"12-345-$id")
          )
        )
        val images2 = (11 to 15).map(id =>
          image(id).copy(
            description = Some(s"{{$idTemplate|22-345-$id}}"),
            monumentId = Some(s"22-345-$id")
          )
        )

        val contest = createContest(Some(idTemplate))

        val ic = mockService(images1, category, contestId)
        ic.appendImages(category, "", contest)

        val contestWithCategory = contestDao.findById(contest.getId).get
        eventually {
          imageDao.findByContest(contestWithCategory) === images1
        }

        val ic2 = mockService(images2, category, contestId)
        ic2.appendImages(category, "", contestWithCategory)
        eventually {
          imageDao.findByContest(contestWithCategory) === images2
        }
      }
    }

    "shared images different categories" in {
      withDb {
        val images =
          (11 to 15).map(id => image(id).copy(description = Some(s"{{$idTemplate|12-345-$id}}")))

        val contest1 = createContest(
          id = Some(contestId + 1),
          images = Some(category + 1),
          monumentIdTemplate = Some(idTemplate)
        )
        val contest2 = createContest(
          id = Some(contestId + 2),
          images = Some(category + 2),
          monumentIdTemplate = Some(idTemplate)
        )

        val ic = mockService(images, category + 1, contestId + 1)
        ic.appendImages(category + 1, "", contest1)

        val contest1WithCategory = contestDao.findById(contest1.getId).get
        eventually {
          imageDao.findByContest(contest1WithCategory) === images
        }

        val ic2 = mockService(images, category + 2, contestId + 2)
        ic2.appendImages(category + 2, "", contest2)

        val contest2WithCategory = contestDao.findById(contest2.getId).get
        eventually {
          imageDao.findByContest(contest2WithCategory) === images
        }
      }
    }

    "get international images" in {
      withDb {
        val page = "Commons:Wiki Loves Earth 2019/Winners"
        val is = new ImageService(Global.commons, mock[MonumentService])
        val contest = createContest()
        is.appendImages(page, "", contest)
        val contestWithCategory = contestDao.findById(contest.getId).get

        eventually {
          imageDao.findByContest(contestWithCategory).size must be_>=(350)
        }
      }
    }
  }

  private def image(id: Long) =
    Image(
      pageId = id,
      title = s"File:Image$id.jpg",
      url = Some(s"url$id"),
      pageUrl = None,
      width = 640,
      height = 480,
      monumentId = Some(s"12-345-$id"),
      size = Some(1234)
    )

  private def imageInfo(id: Long) =
    new Page(
      id = Some(id),
      ns = Some(Namespace.FILE),
      title = s"File:Image$id.jpg",
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

  private def revision(id: Long, text: String) =
    new Page(
      id = Some(id),
      ns = Some(Namespace.FILE),
      title = s"File:Image$id.jpg",
      revisions = Seq(
        new Revision(Some(id + 100), Some(id), content = Some(text))
      )
    )

  private def mockService(images: Seq[Image], category: String, contestId: Long): ImageService = {
    val commons = mockQuery(images, category, contestId)
    new ImageService(commons, mock[MonumentService])
  }

  private def mockQuery(images: Seq[Image], category: String, contestId: Long): MwBot = {
    val imageInfos = images.map(i => imageInfo(i.pageId))
    val revisions = images.map(i =>
      revision(i.pageId, s"{{Information|description=${i.description.getOrElse("")}}}")
    )

    val query = mock[SinglePageQuery]
    query.withContext(Map("contestId" -> contestId.toString, "max" -> "0")) returns query
    queryImageInfo(query, imageInfos)
    queryRevisions(query, revisions)

    val commons = mockBot()
    commons.page(category) returns query
    commons.run(any[Action](), any[Map[String, String]](), any[Option[Long]]()) returns Future.successful(imageInfos: Iterable[Page])
    commons
  }

  private def queryImageInfo(
      query: SinglePageQuery,
      imageInfos: Seq[Page]
  ) = {
    query.imageInfoByGenerator(
      generator = "categorymembers",
      generatorPrefix = "cm",
      namespaces = Set(Namespace.FILE),
      props = Set("timestamp", "user", "size", "url", "mime"),
      titlePrefix = None
    ) returns Future.successful(imageInfos)
  }

  private def queryRevisions(
      query: SinglePageQuery,
      revisions: Seq[Page]
  ) = {
    query.revisionsByGenerator(
      generator = "categorymembers",
      generatorPrefix = "cm",
      namespaces = Set(Namespace.FILE),
      props = Set("content", "timestamp", "user", "comment"),
      limit = "50",
      titlePrefix = None
    ) returns Future.successful(revisions)
  }

}
