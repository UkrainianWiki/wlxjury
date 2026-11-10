package controllers

import db.scalikejdbc.{ContestJuryJdbc, CriterionJdbc, MediaType, Round, RoundCriterion, RoundLimits, User}
import org.scalawiki.wlx.dto.SpecialNomination
import org.intracer.wmua.{AverageRule, BinaryOrder, CategoryOperation, CategoryRule, Criterion, RejectedMode}
import play.api.data.{Form, Mapping}
import play.api.data.Forms._
import play.api.data.validation._
import services.distribution.RoundDistributionHandler

import scala.util.Try

/** The round form.
  *
  * @param fileList  "Add files": a pasted list of files to add to the round
  * @param newImages "Distribute new files"
  * @param criteriaShown the form had the criteria selects (`round.criterionIds`): a client
  *                      without them (a script, an older page) leaves an existing round's
  *                      criteria as they are
  * @param averageRuleShown the form had the score select (`round.averageRule`): a client without
  *                      it leaves an existing round's rule as it is
  * @param worstMarkShown the form had the worst mark checkbox (`round.worstMark`): a client
  *                      without it leaves an existing round's option as it is
  */
case class EditRound(
    round: Round,
    jurors: Seq[Long],
    fileList: Option[String] = None,
    newImages: Boolean = false,
    criteriaShown: Boolean = true,
    averageRuleShown: Boolean = true,
    worstMarkShown: Boolean = true,
    // the rejected-by choices as posted (V93), for the form's checks: the mode select's value
    // (None: a client without it) and X (None: empty)
    rejectedModeChoice: Option[String] = None,
    rejectedByChoice: Option[Int] = None
)

object EditRound {

  def nonEmptySeq[T]: Constraint[Seq[T]] =
    Constraint[Seq[T]]("constraint.required") { o =>
      if (o.nonEmpty) Valid else Invalid(ValidationError("error.required"))
    }

  private val jurorsMappingKV = "jurors" -> seq(text).verifying(nonEmptySeq)
  val jurorsMapping: Mapping[Seq[String]] = single(jurorsMappingKV)

  /** Extra fields live outside the 22-field mapping: category operation, conflicts of interest,
    * "exclude files rejected by at least N jurors" ("" and "0" are no filter, None) and the
    * order of a top-N from a yes/no previous round, kept only with a top-N (an unknown
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

  /** A criteria round's criteria (the form's criteria[0..9] selects, by position; the empty
    * ones left out): none, or 2 to 10 of the catalogue, each once, in a rated round. A value
    * that is no id is an unknown criterion. An existing round's criteria posted as stored are
    * not checked: they are not changed, and a legacy criteria round's (V83: one criterion, more
    * than 10, or a rating that isn't rated) stay as they are while the rest of its form is saved.
    */
  /** "Rejected by at least N" (V93): a mode needs its N, and N a mode (not "no filter"). A client
    * without the mode select (an older page) excludes with its N. That a new round's filter has a
    * chosen previous round whose votes may reject is `RoundController`'s check ([[RejectedByNoSource]]:
    * it reads the previous rounds).
    */
  private val validRejectedBy: Constraint[EditRound] = Constraint("constraint.rejected.by") { edit =>
    val mode = edit.rejectedModeChoice
    val by = edit.rejectedByChoice
    if (mode.exists(m => m != NoRejectedFilter) && by.isEmpty) Invalid(ValidationError(RejectedByCountMissing))
    else if (mode.contains(NoRejectedFilter) && by.isDefined) Invalid(ValidationError(RejectedByWithoutMode))
    else Valid
  }

  /** Whether a new round's "rejected by at least N" has a chosen previous round whose votes may
    * reject ([[Round.rejects]]: a yes/no round, a rated round without criteria). Without one it would
    * leave nothing out ("exclude") or keep nothing ("only"): refused. An edit's previous rounds and
    * filters aren't changed (nor its previous rounds posted): not checked.
    */
  def rejectedByHasSource(round: Round, previous: Seq[Round]): Boolean =
    round.id.isDefined || round.rejectedByFilter.isEmpty || previous.exists(_.rejects)

  val RejectedByCountMissing = "error.rejected.by.count.missing"
  val RejectedByWithoutMode = "error.rejected.by.without.mode"
  val RejectedByNoSource = "error.rejected.by.no.source"

  private val validCriteria: Constraint[EditRound] = Constraint("constraint.criteria") { edit =>
    val round = edit.round
    val unchanged = round.id.exists(id => RoundCriterion.idsOf(id) == round.criterionIds)
    if (unchanged) Valid
    else
      Criterion.validationError(round.criterionIds, round.isRated, CriterionJdbc.existing(_))
        .fold[ValidationResult](Valid)(key => Invalid(ValidationError(key, Criterion.MinPerRound, Criterion.MaxPerRound)))
  }

  /** The worst mark (rate -1) only in a rated round without criteria: a yes/no round's -1 is its "no",
    * and a criteria round's votes are per criterion.
    */
  private val validWorstMark: Constraint[EditRound] = Constraint("constraint.worst.mark") { edit =>
    val round = edit.round
    if (!round.worstMark || (round.isRated && round.criterionIds.isEmpty)) Valid
    else Invalid(ValidationError(WorstMarkRatedOnly))
  }

  val WorstMarkRatedOnly = "error.worst.mark.rated.only"

  /** "Leave out files that are in round(s)" (V95): other rounds of the round's contest, found there,
    * none of its previous rounds and none being distributed now
    * ([[RoundDistributionHandler.excludedRoundsError]], which the service and the job check again).
    * Its contest's rounds are read only when some are chosen. An edit doesn't change them (the
    * select is disabled, posting nothing).
    */
  private val validExcludedRounds: Constraint[EditRound] = Constraint("constraint.exclude.rounds") { edit =>
    val round = edit.round
    RoundDistributionHandler
      .excludedRoundsError(round, Round.findByIdsInAnyContest, RoundDistributionHandler.distributingNow)
      .fold[ValidationResult](Valid)(refusal => Invalid(ValidationError(refusal.key, refusal.args: _*)))
  }

  /** The rejected-by mode select's "no filter" (a value of its own: Play reads "" as absent, which
    * is an older client's, excluding).
    */
  val NoRejectedFilter = "none"

  /** The criteria selects' values: the chosen ones, by position (-1 for a value that is no id). */
  def criterionIdsOf(values: Seq[String]): Seq[Long] =
    values.map(_.trim).filter(_.nonEmpty).map(_.toLongOption.filter(_ > 0).getOrElse(-1L))

  /** The rounds-to-leave-out select's values, each once, in their order (-1 for a value that is no
    * id, which the form refuses).
    */
  def excludeRoundIdsOf(values: Seq[String]): Seq[Long] = criterionIdsOf(values).distinct

  val editRoundForm: Form[EditRound] = Form(
    mapping(
      "" -> roundMapping,
      "excludeCoi" -> boolean,
      "categoryClause" -> categoryOperation,
      "topBinaryOrder" -> optional(text),
      "rejectedBy" -> optional(text),
      "criteria" -> seq(text),
      "criteriaShown" -> boolean,
      "halfStar" -> boolean,
      // how a rated round scores its files (V90): one of AverageRule's values; a client without it
      // (an older page, a script) gets the default for a new round, an edit keeps the stored one
      // (RoundController.saveRound)
      "averageRule" -> optional(text).verifying("error.invalid", _.forall(AverageRule.parse(_).isDefined)),
      // a rated round's worst mark (V92), with the hidden field saying the form had the checkbox
      // (an unchecked box sends nothing): a client without both keeps an existing round's option
      "worstMark" -> boolean,
      "worstMarkShown" -> boolean,
      // "rejected by at least N" (V93): "none" no filter, "exclude" those files, "only" them; a
      // client without it (an older page, a script) excludes, the meaning before V93
      "rejectedMode" -> optional(text).verifying("error.invalid",
        _.forall(m => m == EditRound.NoRejectedFilter || RejectedMode.parse(m).isDefined)),
      // "Leave out files that are in round(s)" (V95): round ids, checked by validExcludedRounds
      "excludeRounds" -> seq(text)
    )((edit, excludeCoi, selected, topOrder, rejectedBy, criteria, criteriaShown, halfStar, averageRule, worstMark,
        worstMarkShown, rejectedMode, excludeRounds) => {
      val configured = selected match {
        case None => edit.round // Older clients omitted the clause; retain their inference.
        case Some(value) =>
          val operation = CategoryOperation.fromForm(value).get
          if (operation == CategoryOperation.Unrestricted)
            edit.round.copy(category = None, excludeCategory = None, categoryClause = None)
          else edit.round.copy(categoryClause = operation.storedValue,
            excludeCategory = edit.round.excludeCategory.filter(_ => operation.requiresSecond))
      }
      val rejectedByChoice = rejectedBy.flatMap(s => Try(s.toInt).toOption).filter(_ > 0)
      edit.copy(criteriaShown = criteriaShown, rejectedModeChoice = rejectedMode, rejectedByChoice = rejectedByChoice,
        round = configured.copy(excludeCoi = excludeCoi,
        topBinaryOrder = edit.round.topImages.flatMap(_ => BinaryOrder.ofForm(topOrder)),
        prevRejectedBy = rejectedByChoice.filterNot(_ => rejectedMode.contains(NoRejectedFilter)),
        prevRejectedMode = rejectedMode.flatMap(RejectedMode.parse).getOrElse(RejectedMode.Default),
        criterionIds = criterionIdsOf(criteria), halfStar = halfStar,
        averageRule = averageRule.flatMap(AverageRule.parse).getOrElse(AverageRule.Default),
        worstMark = worstMark && worstMarkShown, excludeRounds = excludeRoundIdsOf(excludeRounds)),
        averageRuleShown = averageRule.isDefined, worstMarkShown = worstMarkShown)
    })(edit => Some((edit, edit.round.excludeCoi, Some(edit.round.categoryRule.operation.formValue),
      edit.round.topImages.filter(_ > 0).map(_ => edit.round.topOrderParam), edit.round.rejectedByFilter.map(_.toString),
      edit.round.criterionIds.map(_.toString), edit.criteriaShown, edit.round.halfStar,
      Some(edit.round.averageRule.param), edit.round.worstMark, edit.worstMarkShown,
      Some(edit.round.rejectedByFilter.fold(NoRejectedFilter)(_ => edit.round.prevRejectedMode.param)),
      edit.round.excludeRounds.map(_.toString))))
      .verifying(validCategories)
      .verifying(validCriteria)
      .verifying(validRejectedBy)
      .verifying(validWorstMark)
      .verifying(validExcludedRounds)
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
      // the hidden field is always "jury" (editRound.scala.html); a contest role, never root or a list (G1)
      "roles" -> text.verifying("error.invalid", User.isContestRole(_)),
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
      "topImages" -> optional(number(min = 1)),
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
      // "0", the old form's no filter (old bookmarks or forms), is no filter: None
      prevSelectedBy = prevSelectedBy.flatMap(s => Try(s.toInt).toOption).filter(_ > 0),
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
        round.selectedByFilter.map(_.toString),
        round.prevMinAvgRate.map(_.toString),
        categories.first,
        categories.second,
        round.regionIds,
        round.minImageSize.fold("No")(_.toString),
        editRound.jurors.map(_.toString),
        editRound.newImages,
        round.monuments,
        // a stored 0 is no top-N: filled as such, the field (at least 1) doesn't block a save
        round.topImages.filter(_ > 0),
        round.specialNomination,
        round.mediaType.getOrElse(MediaType.All)
      )
    )
  }
}
