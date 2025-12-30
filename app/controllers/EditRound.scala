package controllers

import db.scalikejdbc.{MediaType, Round, RoundLimits}
import org.scalawiki.wlx.dto.SpecialNomination
import play.api.data.{Form, Mapping}
import play.api.data.Forms._
import play.api.data.validation._

import scala.util.Try

case class EditRound(
    round: Round,
    jurors: Seq[Long],
    returnTo: Option[String],
    newImages: Boolean = false
)

object EditRound {

  def nonEmptySeq[T]: Constraint[Seq[T]] =
    Constraint[Seq[T]]("constraint.required") { o =>
      if (o.nonEmpty) Valid else Invalid(ValidationError("error.required"))
    }

  private val jurorsMappingKV = "jurors" -> seq(text).verifying(nonEmptySeq)
  val jurorsMapping: Mapping[Seq[String]] = single(jurorsMappingKV)
  val editRoundForm: Form[EditRound] = Form(
    mapping(
      "id" -> optional(longNumber),
      "number" -> longNumber,
      "name" -> optional(text),
      "contest" -> longNumber,
      "roles" -> text,
      "distribution" -> number,
      "rates" -> number,
      "returnTo" -> optional(text),
      "minMpx" -> text,
      "previousRound" -> optional(longNumber),
      "minJurors" -> optional(text),
      "minAvgRate" -> optional(text),
      "source" -> optional(text),
      "excludeCategory" -> optional(text),
      "regions" -> seq(text),
      "minSize" -> text,
      jurorsMappingKV,
      "newImages" -> boolean,
      "monumentIds" -> optional(text),
      "topImages" -> optional(number),
      "specialNomination" -> optional(text),
      "mediaType" -> text
    )(applyEdit)(unapplyEdit)
  )

  def applyEdit(
      id: Option[Long],
      num: Long,
      name: Option[String],
      contest: Long,
      roles: String,
      distribution: Int,
      rates: Int,
      returnTo: Option[String],
      minMpx: String,
      previousRound: Option[Long],
      prevSelectedBy: Option[String],
      prevMinAvgRate: Option[String],
      category: Option[String],
      excludeCategory: Option[String],
      regions: Seq[String],
      minImageSize: String,
      jurors: Seq[String],
      newImages: Boolean,
      monumentIds: Option[String],
      topImages: Option[Int],
      specialNomination: Option[String],
      mediaType: String
  ): EditRound = {
    val round = new Round(
      id,
      num,
      name,
      contest,
      Set(roles),
      distribution,
      Round.ratesById(rates),
      limits = RoundLimits(),
      minMpx = Try(minMpx.toInt).toOption,
      previous = previousRound,
      prevSelectedBy = prevSelectedBy.flatMap(s => Try(s.toInt).toOption),
      prevMinAvgRate = prevMinAvgRate.flatMap(s => Try(BigDecimal(s)).toOption),
      category = category,
      excludeCategory = excludeCategory,
      regions = if (regions.nonEmpty) Some(regions.mkString(",")) else None,
      minImageSize = Try(minImageSize.toInt).toOption,
      monuments = monumentIds,
      topImages = topImages,
      specialNomination = specialNomination.filter(SpecialNomination.nominations.map(_.name).contains),
      mediaType = Option(mediaType).filterNot(_ == MediaType.All)
    ).withFixedCategories
    EditRound(round, jurors.flatMap(s => Try(s.toLong).toOption), returnTo, newImages)
  }

  def unapplyEdit(editRound: EditRound): Option[
    (
        Option[Long],
        Long,
        Option[String],
        Long,
        String,
        Int,
        Int,
        Option[String],
        String,
        Option[Long],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Seq[String],
        String,
        Seq[String],
        Boolean,
        Option[String],
        Option[Int],
        Option[String],
        String
    )
  ] = {
    val round = editRound.round.withFixedCategories
    Some(
      (
        round.id,
        round.number,
        round.name,
        round.contestId,
        round.roles.head,
        round.distribution,
        round.rates.id,
        editRound.returnTo,
        round.minMpx.fold("No")(_.toString),
        round.previous,
        round.prevSelectedBy.map(_.toString),
        round.prevMinAvgRate.map(_.toString),
        round.category,
        round.excludeCategory,
        round.regionIds,
        round.minImageSize.fold("No")(_.toString),
        editRound.jurors.map(_.toString),
        editRound.newImages,
        round.monuments,
        round.topImages,
        round.specialNomination,
        round.mediaType.getOrElse(MediaType.All)
      )
    )
  }
}
