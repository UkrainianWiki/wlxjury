package controllers

import db.scalikejdbc.{ContestJuryJdbc, MediaType, Round, RoundLimits}
import org.scalawiki.wlx.dto.SpecialNomination
import org.intracer.wmua.{BinaryOrder, CategoryOperation, CategoryRule}
import play.api.data.{Form, Mapping}
import play.api.data.Forms._
import play.api.data.validation._

import scala.util.Try

/** The round form.
  *
  * @param fileList  "Add files": a pasted list of files to add to the round
  * @param newImages "Distribute new files"
  */
case class EditRound(
    round: Round,
    jurors: Seq[Long],
    fileList: Option[String] = None,
    newImages: Boolean = false
)

object EditRound {

  def nonEmptySeq[T]: Constraint[Seq[T]] =
    Constraint[Seq[T]]("constraint.required") { o =>
      if (o.nonEmpty) Valid else Invalid(ValidationError("error.required"))
    }

  private val jurorsMappingKV = "jurors" -> seq(text).verifying(nonEmptySeq)
  val jurorsMapping: Mapping[Seq[String]] = single(jurorsMappingKV)

  /** Extra fields live outside the 22-field mapping: category operation, conflicts of interest
    * and the order of a top-N from a yes/no previous round, kept only with a top-N (an unknown
    * order is the default; the legacy rule's value, shown for a round from before V72, is none,
    * which a new round's submission from a yes/no round makes the default:
    * [[Round.withExplicitTopOrder]]).
    */
  private val categoryOperation = optional(text).verifying(
    "error.category.operation", _.forall(value => CategoryOperation.fromForm(value).isDefined))

  private val validCategories: Constraint[EditRound] = Constraint("constraint.category.rule") { edit =>
    val errors = edit.round.categoryRule.validationErrors
    if (errors.isEmpty) Valid else Invalid(errors.map(key => ValidationError(key)))
  }

  val editRoundForm: Form[EditRound] = Form(
    mapping(
      "" -> roundMapping,
      "excludeCoi" -> boolean,
      "categoryClause" -> categoryOperation,
      "topBinaryOrder" -> optional(text)
    )((edit, excludeCoi, selected, topOrder) => {
      val configured = selected match {
        case None => edit.round // Older clients omitted the clause; retain their inference.
        case Some(value) =>
          val operation = CategoryOperation.fromForm(value).get
          if (operation == CategoryOperation.Unrestricted)
            edit.round.copy(category = None, excludeCategory = None, categoryClause = None)
          else edit.round.copy(categoryClause = operation.storedValue,
            excludeCategory = edit.round.excludeCategory.filter(_ => operation.requiresSecond))
      }
      edit.copy(round = configured.copy(excludeCoi = excludeCoi,
        topBinaryOrder = edit.round.topImages.flatMap(_ => BinaryOrder.ofForm(topOrder))))
    })(edit => Some((edit, edit.round.excludeCoi, Some(edit.round.categoryRule.operation.formValue),
      edit.round.topImages.map(_ => edit.round.topOrderParam))))
      .verifying(validCategories)
  )

  /** Keep older submissions meaningful on an error page, where the form has no value. */
  def categoryDisplay(form: Form[EditRound]): Form[EditRound] = {
    val selected = form("categoryClause").value.filter(_.nonEmpty)
    selected.flatMap(CategoryOperation.fromForm) match {
      case Some(operation) => form.copy(data = form.data.updated("categoryClause", operation.formValue))
      case None if selected.isDefined => form // Preserve an invalid value and its error.
      case None =>
        val rule = CategoryRule.fromStored(form("source").value, form("excludeCategory").value, None)
        form.copy(data = (form.data -- Seq("source", "excludeCategory")) ++
          Map("categoryClause" -> rule.operation.formValue) ++
          rule.first.map("source" -> _) ++ rule.second.map("excludeCategory" -> _))
    }
  }

  private def roundMapping: Mapping[EditRound] =
    mapping(
      "id" -> optional(longNumber),
      "number" -> longNumber,
      "name" -> optional(text),
      "contest" -> longNumber,
      "roles" -> text,
      "distribution" -> number,
      "rates" -> number,
      "fileList" -> optional(text),
      "minMpx" -> text,
      "previousRound" -> seq(text),
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
      .verifying(
        "error.previous.rounds.mixed.type",
        editRound => {
          val ids = editRound.round.previousIds
          ids.sizeIs < 2 ||
            Round.sameRateType(Round.findByIds(editRound.round.contestId, ids))
        }
      )

  def applyEdit(
      id: Option[Long],
      num: Long,
      name: Option[String],
      contest: Long,
      roles: String,
      distribution: Int,
      rates: Int,
      fileList: Option[String],
      minMpx: String,
      previousRounds: Seq[String],
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
    val previousRoundIds = previousRounds.flatMap(s => Try(s.trim.toLong).toOption)
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
      previous = Option.when(previousRoundIds.nonEmpty)(previousRoundIds.mkString(",")),
      prevSelectedBy = prevSelectedBy.flatMap(s => Try(s.toInt).toOption),
      prevMinAvgRate = prevMinAvgRate.flatMap(s => Try(BigDecimal(s)).toOption),
      category = category,
      excludeCategory = excludeCategory,
      regions = if (regions.nonEmpty) Some(regions.mkString(",")) else None,
      minImageSize = Try(minImageSize.toInt).toOption,
      monuments = monumentIds,
      topImages = topImages,
      specialNomination = specialNomination.filter { name =>
        ContestJuryJdbc.findById(contest).exists { c =>
          c.name == "Wiki Loves Monuments" && c.country == "Ukraine" &&
            SpecialNomination.nominations.filter(_.years.contains(c.year)).exists(_.name == name)
        }
      },
      mediaType = Option(mediaType).filterNot(_ == MediaType.All)
    ).withFixedCategories
    EditRound(round, jurors.flatMap(s => Try(s.toLong).toOption), fileList.filter(_.trim.nonEmpty), newImages)
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
        Seq[String],
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
    val categories = round.categoryRule
    Some(
      (
        round.id,
        round.number,
        round.name,
        round.contestId,
        round.roles.head,
        round.distribution,
        round.rates.id,
        editRound.fileList,
        round.minMpx.fold("No")(_.toString),
        round.previousIds.map(_.toString),
        round.prevSelectedBy.map(_.toString),
        round.prevMinAvgRate.map(_.toString),
        categories.first,
        categories.second,
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
