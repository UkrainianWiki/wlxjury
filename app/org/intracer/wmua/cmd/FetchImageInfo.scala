package org.intracer.wmua.cmd

import java.net.URLDecoder

import db.scalikejdbc.ImageJdbc
import org.intracer.wmua.{ContestJury, Image}
import org.scalawiki.MwBot
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.cmd.query.prop.iiprop.{IiProp, IiPropArgs}
import org.scalawiki.dto.cmd.query.prop.{CategoryInfo, ImageInfo, Prop}
import org.scalawiki.dto.cmd.query.{Query, TitlesParam}
import org.scalawiki.dto.{Namespace, Page}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class FetchImageInfo(
    source: String,
    titles: Seq[String] = Seq.empty,
    contest: ContestJury,
    commons: MwBot,
    max: Long = 0L
) {

  private val imageInfoProps = Set("timestamp", "user", "size", "url", "mime", "mediatype")

  private var missingImages: Set[String] = Set.empty

  def apply(): Future[Seq[Image]] = {
    val imageInfoQuery =
      if (titles.isEmpty)
        generatorQuery
      else
        titlesQuery

    for (pages <- imageInfoQuery) yield pages.flatMap(page => ImageJdbc.fromPage(page, contest))
  }

  def generatorQuery: Future[Seq[Page]] = {
    val (generator, prefix) = if (source.toLowerCase.startsWith("category:")) {
      ("categorymembers", "cm")
    } else if (source.toLowerCase.startsWith("template:")) {
      ("embeddedin", "ei")
    } else {
      ("images", "im")
    }

    imageInfoByGenerator(source, generator, prefix)
  }

  def titlesQuery: Future[Seq[Page]] = {
    def queryUpTo50(upTo50: Seq[String]) = {
      val trimmed = upTo50.map(_.trim).filterNot(_.isEmpty)
      val urlDecoded = trimmed
        .map(t => URLDecoder.decode(t, "UTF8"))
        .map(_.replace("https://commons.wikimedia.org/wiki/", ""))

      val iiProps = IiProp(IiPropArgs.byNames(imageInfoProps.toSeq): _*)
      val query = Action(Query(TitlesParam(urlDecoded), Prop(ImageInfo(iiProps))))
      commons.run(query)
    }

    val uniqueNormalizedTitles = titles.map(_.replace("_", " ")).distinct

    Future.sequence(uniqueNormalizedTitles.sliding(50, 50).map(queryUpTo50)).map { batches =>
      val pages = batches.flatten.toSeq
      val fetchedTitles = pages.map(_.title)
      if (fetchedTitles.size != titles.size) {
        missingImages = uniqueNormalizedTitles.toSet -- fetchedTitles.toSet
      }
      pages
    }
  }

  def numberOfImages: Future[Long] = {
    val query = Action(Query(TitlesParam(Seq(source)), Prop(CategoryInfo)))
    commons.run(query).map {
      _.headOption.flatMap(_.categoryInfo.map(_.files)).getOrElse(0L)
    }
  }

  def imageInfoByGenerator(
      source: String,
      generator: String,
      generatorPrefix: String
  ): Future[Seq[Page]] = {

    val context = Map("contestId" -> contest.id.getOrElse(0).toString, "max" -> max.toString)

    commons
      .page(source)
      .withContext(context)
      .imageInfoByGenerator(
        generator,
        generatorPrefix,
        namespaces = Set(Namespace.FILE),
        props = imageInfoProps,
        titlePrefix = None
      )
      .map(_.toSeq)
  }
}
