package controllers

import org.intracer.wmua.{CategoryCombine, Criterion}
import play.api.data.{Form, FormError}
import play.api.i18n.Messages
import services.distribution.RoundDistributionHandler

/** The errors of a refused round form, where the form shows them (UI-path plan §0.5, the
  * 2026-11-25 refused saves): each in a summary at the top that links to its field, and next to
  * its field. A form-level error that belongs to a field (the category boxes, the criteria, the
  * rejected-by pair, the worst mark, the rounds to leave out, the previous rounds, an unreadable
  * "Add files" list) is moved to that field ([[placed]]), so its field shows it; a section that
  * holds an error is rendered open ([[inMediaFiltering]], [[inAddFiles]], [[inCriteria]]).
  */
object RoundFormErrors {

  /** One line of the summary: the field's label key (None: a form-level error, or a field the
    * form doesn't show), the anchor it links to, and the error's text.
    */
  final case class Item(labelKey: Option[String], anchor: Option[String], text: String)

  /** The fields of the "Media filtering" section (round/edit/imageFiltering.scala.html). */
  val MediaFiltering: Set[String] = Set("previousRound", "minJurors", "topBinaryOrder", "rejectionFilter",
    "minAvgRate", "topImages", "excludeRounds", "mediaType", "source", "excludeCategory", "categoryCombine",
    "categoryClause", "regions", "monumentIds", "minMpx", "minSize", "specialNomination")

  /** The rounds to leave out's errors (not "a round leaving out this one's files is being filled",
    * which is about another round).
    */
  private val ExcludeRoundsKeys: Set[String] = {
    import RoundDistributionHandler._
    Set(ExcludeRoundsSelf, ExcludeRoundsOther, ExcludeRoundsPrevious, ExcludeRoundsBusy, ExcludeRoundsMissing)
  }

  /** The field a form-level error belongs to, by its message key. */
  def fieldOfKey(key: String): Option[String] = key match {
    case "error.category.first.required"                  => Some("source")
    case "error.category.second.required"                 => Some("excludeCategory")
    case "error.previous.rounds.mixed.type"               => Some("previousRound")
    case EditRound.RejectedByCountMissing | EditRound.RejectedByWithoutMode | EditRound.RejectedByNoSource =>
      Some("rejectionFilter")
    case EditRound.WorstMarkRatedOnly                     => Some("worstMark")
    case "files.list.unreadable"                          => Some("fileList")
    case k if Criterion.ErrorKeys(k)                      => Some("criteria")
    case k if ExcludeRoundsKeys(k)                        => Some("excludeRounds")
    case _                                                => None
  }

  /** Place errors on the visible fields, including submissions of the old rejection fields. */
  def placed[T](form: Form[T]): Form[T] =
    form.copy(errors = form.errors.map(e => fieldOf(e).fold(e)(field => e.copy(key = field))))

  private def fieldOf(error: FormError): Option[String] = error.key match {
    case ""                            => fieldOfKey(FormRefusal.messageKey(error))
    case "rejectedMode" | "rejectedBy"  => Some("rejectionFilter")
    case key                           => Some(key)
  }

  /** The field's name without an index (`criteria[2]` is `criteria`). */
  private def base(key: String): String = key.takeWhile(c => c != '[' && c != '.')

  def inMediaFiltering(form: Form[_]): Boolean = form.errors.exists(e => fieldOf(e).exists(f => MediaFiltering(base(f))))

  def inAddFiles(form: Form[_]): Boolean = form.errors.exists(e => fieldOf(e).exists(f => base(f) == "fileList"))

  def inCriteria(form: Form[_]): Boolean = form.errors.exists(e => fieldOf(e).exists(f => base(f) == "criteria"))

  /** The label of a field as the form shows it; None for a field it doesn't show (hidden ones). */
  def labelKey(field: String, form: Form[_]): Option[String] = base(field) match {
    case "name"              => Some("round.name.optional")
    case "trackLabel"        => Some("round.track.label")
    case "previousRound"     => Some("previous.round")
    case "minJurors"         => Some("selected.by.at.least.x.jurors")
    case "topBinaryOrder"    => Some("top.images.binary.order")
    case "rejectionFilter"   => Some("rejected.by.mode")
    case "minAvgRate"        => Some("average.file.rating.is.at.least")
    case "topImages"         => Some("top.images")
    case "excludeRounds"     => Some("exclude.rounds")
    case "mediaType"         => Some("media.type")
    case "source"            => Some("category.only")
    case "excludeCategory"   =>
      Some(form("categoryCombine").value.flatMap(CategoryCombine.parse).getOrElse(CategoryCombine.Default).secondLabelKey)
    case "categoryCombine" | "categoryClause" => Some("category.combine")
    case "regions"           => Some("regions")
    case "monumentIds"       => Some("monument.ids.comma.separated")
    case "minMpx"            => Some("minimum.megapixels")
    case "minSize"           => Some("minimum.megabytes")
    case "specialNomination" => Some("special.nomination")
    case "excludeCoi"        => Some("coi.exclude")
    case "fileList"          => Some("add.files.list")
    case "distribution"      => Some("file.distribution")
    case "rates"             => Some("rating.type")
    case "averageRule"       => Some("round.average.rule")
    case "worstMark"         => Some("round.worst.mark")
    case "criteria"          => Some("criteria")
    case _                   => None
  }

  /** The element a field's summary line links to: its row (the bs helpers' `<id>_field`), the
    * criteria's section, the category choice's row.
    */
  def anchor(field: String): Option[String] = base(field) match {
    case "criteria"                           => Some("round-criteria")
    case "categoryCombine" | "categoryClause" => Some("category-more_field")
    case "worstMark"                          => Some("round-worst-mark")
    // the threshold has no field of its own: it is the cut control's (round/edit/topN)
    case "minAvgRate"                         => Some("topImages_field")
    case f if labelKeyKnown(f)                => Some(f + "_field")
    case _                                    => None
  }

  private def labelKeyKnown(field: String): Boolean = field != "id" && field != "contest" && field != "number" &&
    field != "roles" && field != "jurors" && field.nonEmpty

  /** Which of the form's fields the page shows ([[onPage]]): the previous rounds' fields only
    * when the contest has rounds, the regions' with regions, the nomination with nominations, the
    * criteria and the worst mark unless the round is an existing unrated one.
    */
  final case class Shown(rounds: Boolean, regions: Boolean, nominations: Boolean, unrated: Boolean)

  /** Whether the page has the field's element to link to (a crafted post may name another). */
  def onPage(field: String, form: Form[_], shown: Shown): Boolean = base(field) match {
    case "previousRound" | "minJurors" | "rejectionFilter" | "minAvgRate" | "excludeRounds" => shown.rounds
    // the top N's row is on the page with the previous rounds' fields (round/edit/topN)
    case "topImages" => shown.rounds
    case "topBinaryOrder" => shown.rounds && form("topImages").value.exists(_.nonEmpty)
    case "regions" | "monumentIds" => shown.regions
    case "specialNomination" => shown.nominations
    case "criteria" | "worstMark" => !shown.unrated
    case _ => true
  }

  /** Every error of the form, in the form's order, each once; linked to its field if the page shows it. */
  def items(form: Form[_], shown: Shown)(implicit messages: Messages): Seq[Item] =
    form.errors.map { e =>
      val field = fieldOf(e)
      val label = field.flatMap(labelKey(_, form))
      Item(label, label.flatMap(_ => field.filter(onPage(_, form, shown)).flatMap(anchor)), messages(e.messages, e.args: _*))
    }.distinct
}
