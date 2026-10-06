package services

import db.scalikejdbc.{CategoryJdbc, CategoryLinkJdbc, ContestJuryJdbc, ImageJdbc}
import org.intracer.wmua.cmd.FetchImageText.defaultParam
import org.intracer.wmua.cmd.{FetchImageInfo, FetchImageText, ImageEnricher}
import org.intracer.wmua.{ContestJury, Image}
import org.scalawiki.MwBot
import org.scalawiki.wlx.dto.{Contest, Country}
import play.api.Logging

import javax.inject.Inject
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class ImageService @Inject() (
    val commons: MwBot,
    monumentService: MonumentService
)(implicit ec: ExecutionContext)
    extends Logging {

  def updateImageMonuments(source: String, contest: ContestJury): Unit = {
    if (contest.country == Country.Ukraine.name && contest.monumentIdTemplate.isDefined) {
      val monumentContest = Seq("earth", "monuments").filter(
        contest.name.toLowerCase.contains
      ) match {
        case Seq("earth")     => Some(Contest.WLEUkraine(contest.year))
        case Seq("monuments") => Some(Contest.WLMUkraine(contest.year))
        case _                => None
      }

      monumentContest.foreach(monumentService.updateLists)
    }

    def generatorParams: (String, String) = {
      if (source.toLowerCase.startsWith("category:")) {
        ("categorymembers", "cm")
      } else if (source.toLowerCase.startsWith("template:")) {
        ("embeddedin", "ei")
      } else {
        ("images", "im")
      }
    }

    val monumentIdTemplate = contest.monumentIdTemplate.get

    val (generator, prefix) = generatorParams

    commons
      .page(source)
      .revisionsByGenerator(
        generator,
        prefix,
        Set.empty,
        Set("content", "timestamp", "user", "comment"),
        limit = "50",
        titlePrefix = None
      ) map { pages =>
      val idRegex = """(\d\d)-(\d\d\d)-(\d\d\d\d)"""

      pages.foreach { page =>
        for (
          monumentId <- page.text
            .flatMap(text => defaultParam(text, monumentIdTemplate))
            .flatMap(id => if (id.matches(idRegex)) Some(id) else None);
          pageId <- page.id
        ) {
          ImageJdbc.updateMonumentId(pageId, monumentId)
        }
      }
    } recover { case e: Exception => println(e) }
  }

  def appendImages(
      source: String,
      imageList: String,
      contest: ContestJury,
      idsFilter: Set[String] = Set.empty,
      max: Long = 0
  ): Unit = {
    ContestJuryJdbc.setImagesSource(contest.getId, Some(source))
    val existingImages = ImageJdbc.findByContest(contest)
    initImagesFromSource(
      contest,
      source,
      imageList,
      existingImages,
      idsFilter,
      max
    )
  }

  def initImagesFromSource(
      contest: ContestJury,
      source: String,
      titles: String,
      existing: Seq[Image],
      idsFilter: Set[String] = Set.empty,
      max: Long
  ): Unit = {
    val existingByPageId = existing.groupBy(_.pageId)
    val withImageDescriptions = contest.monumentIdTemplate.isDefined
    val titlesSeq: Seq[String] = if (titles.trim.isEmpty) {
      Nil
    } else {
      titles.split("(\r\n|\n|\r)")
    }

    val imageInfos =
      FetchImageInfo(source, titlesSeq, contest, commons, max).apply()

    val getImages = if (withImageDescriptions) {
      fetchImageDescriptions(contest, source, max, imageInfos)
    } else {
      imageInfos
    }

    val result = getImages.map { images =>
      val newImages =
        images.filter(image => !existingByPageId.contains(image.pageId))

      val existingIds =
        ImageJdbc.existingIds(newImages.map(_.pageId).toSet).toSet

      val notInOtherContests =
        newImages.filterNot(image => existingIds.contains(image.pageId))

      val categoryId = CategoryJdbc.findOrInsert(source)
      saveNewImages(notInOtherContests)
      CategoryLinkJdbc.addToCategory(categoryId, newImages)

      ImageService.changedImages(images, existing).foreach(ImageJdbc.update)
    }

    Await.result(result, 500.minutes)
  }

  def fetchImageDescriptions(
      contest: ContestJury,
      source: String,
      max: Long,
      imageInfos: Future[Seq[Image]]
  ): Future[Seq[Image]] = {
    val revInfo =
      FetchImageText(source, contest, contest.monumentIdTemplate, commons, max)
        .apply()
    ImageEnricher.zipWithRevData(imageInfos, revInfo)
  }

  def saveNewImages(imagesWithIds: Seq[Image]): Unit = {
    logger.info("saving images: " + imagesWithIds.size)

    ImageJdbc.batchInsert(imagesWithIds)
    logger.info("saved images")
  }

}

object ImageService {

  /** The fetched images that are stored already but changed: those [[ImageJdbc.update]]
    * has something to write for. Compared on the fields it writes, except pageUrl,
    * which reading an image back doesn't return.
    */
  def changedImages(fetched: Seq[Image], existing: Seq[Image]): Seq[Image] = {
    def stored(i: Image) =
      (i.title, i.url, i.width, i.height, i.monumentId, i.description, i.size, i.mime, i.mediaType)
    val existingById = existing.map(i => i.pageId -> stored(i)).toMap
    fetched.filter(i => existingById.get(i.pageId).exists(_ != stored(i)))
  }
}
