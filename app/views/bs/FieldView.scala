package views.html.bs

import play.api.data.Field
import play.api.i18n.MessagesProvider
import play.api.templates.PlayMagic.translate
import play.twirl.api.{Html, HtmlFormat}

/** What the bs helpers render for one field, from its arguments, named as play-bootstrap's:
  *  - `_label`, `_hiddenLabel` (a label only screen readers read), `_help`, `_text` (a
  *    checkbox's text when `_label` goes in the horizontal label column), `_id` (the
  *    wrapper's id, default `<id>_field`), `_default` (a select's blank option), `_disabled`
  *    (a select's disabled option values), `_showErrors -> false`;
  *  - `id` (the input's id, default the field's), `value` (the value when the field has none;
  *    for a checkbox, the value it submits, default `true`), `checked` (a checkbox's state
  *    instead of the field's), `readonly` (on a select or checkbox, which ignore it, renders
  *    it disabled with hidden inputs submitting its value, as play-bootstrap did: a single
  *    select's selected value, or the option the browser shows when none is selected, the
  *    first one; a multiple select's selected values, none if none is. With `disabled` too,
  *    nothing is submitted);
  *  - `class` is added to the helper's own class (`form-control`, `form-select`,
  *    `form-check-input`), not replacing it; a repeated base class is dropped;
  *  - `aria-describedby` ids are added to those of the help and errors;
  *  - any other argument without a leading underscore is an attribute of the input: `true` is
  *    a bare attribute, `false` or `None` leaves it out, other values are escaped.
  * The field's constraints add `required`, `min`, `max`, `minlength`, `maxlength` and `pattern`
  * as play-bootstrap did, unless the arguments set them. Labels, help, `placeholder`, `_text`
  * and `_default` are translated if they are message keys, as play-bootstrap did; option texts
  * are shown as given.
  */
final class FieldView(field: Field, args: Seq[(Symbol, Any)], baseClass: String)(implicit
    messages: MessagesProvider
) {

  private val argMap: Map[Symbol, Any] = args.filter(_._2 != None).toMap

  /** The arguments after the constraints' attributes, which they override. */
  private val inputArgs: Seq[(Symbol, Any)] =
    FieldView.constraintArgs(field).filterNot { case (key, _) => argMap.contains(key) } ++
      args.map {
        case (key, v) if key.name == "placeholder" => (key, translate(v))
        case other                                 => other
      }

  private def arg(name: String): Option[Any] = argMap.get(Symbol(name))

  private def isTrue(name: String): Boolean = arg(name).contains(true)

  val id: String = arg("id").map(_.toString).getOrElse(field.id)

  val wrapperId: String = arg("_id").map(_.toString).getOrElse(id + "_field")

  val name: String = field.name

  val label: Option[Any] = arg("_label").orElse(arg("_hiddenLabel")).map(translate)

  val labelHidden: Boolean = arg("_hiddenLabel").isDefined || isTrue("_hideLabel")

  /** The label is shown, not only read by screen readers. */
  def labelVisible: Boolean = label.isDefined && !labelHidden

  val text: Option[Any] = arg("_text").map(translate)

  /** An empty help (`_help -> ""`) renders nothing. */
  val help: Option[Any] = arg("_help").map(translate).filter(_.toString.trim.nonEmpty)

  val helpId: String = id + "_help"

  /** The field's errors, localised, each with its id for `aria-describedby`. */
  val errors: Seq[(String, String)] =
    if (arg("_showErrors").contains(false)) Nil
    else
      field.errors.zipWithIndex.map { case (e, i) =>
        (s"${id}_error_$i", messages.messages(e.messages, e.args.map(translate): _*))
      }

  def hasErrors: Boolean = errors.nonEmpty

  def describedBy: Option[String] = {
    val own = arg("aria-describedby").toSeq.flatMap(_.toString.split("\\s+")).filter(_.nonEmpty)
    Some((own ++ help.map(_ => helpId) ++ errors.map(_._1)).distinct.mkString(" ")).filter(_.nonEmpty)
  }

  /** `aria-describedby` and, with errors, `aria-invalid`. */
  def aria: Html = {
    val describe = describedBy.map(d => "aria-describedby=\"" + HtmlFormat.escape(d).body + "\"")
    Html((describe.toSeq ++ Option.when(hasErrors)("aria-invalid=\"true\"")).mkString(" "))
  }

  val readonly: Boolean = isTrue("readonly")

  val disabled: Boolean = isTrue("disabled")

  /** A select or checkbox renders `disabled` for either. */
  def shownDisabled: Boolean = readonly || disabled

  /** A readonly select or checkbox submits its value in hidden inputs, unless disabled. */
  def submitsHidden: Boolean = readonly && !disabled

  val multiple: Boolean = isTrue("multiple")

  val value: Option[String] = field.value.orElse(arg("value").map(_.toString))

  val inputClass: String = {
    val extra = arg("class").toSeq
      .flatMap(_.toString.split("\\s+"))
      .filter(c => c.nonEmpty && !FieldView.BaseClasses(c))
    ((baseClass +: extra) ++ Option.when(hasErrors)("is-invalid")).distinct.mkString(" ")
  }

  // select

  /** `name[]` for a multiple select, which Play's form binding turns into `name[0]`, ... */
  def selectName: String = if (multiple) name + "[]" else name

  /** The selected values, in the order submitted, as Play's `helper.select` reads them. */
  val selectedValues: Seq[String] =
    if (multiple && field.indexes.nonEmpty) field.indexes.flatMap(i => field(s"[$i]").value).distinct
    else value.toSeq

  val defaultOption: Option[Any] = arg("_default").map(translate)

  /** What a readonly select's hidden inputs submit: see the class comment. */
  def hiddenValues(options: Seq[(String, String)]): Seq[String] =
    if (!submitsHidden) Nil
    else if (multiple) selectedValues
    else {
      val shown = defaultOption.map(_ => "").toSeq ++ options.map(_._1).filterNot(disabledOptions)
      selectedValues.find(shown.contains).orElse(shown.headOption).toSeq
    }

  val disabledOptions: Set[String] = arg("_disabled") match {
    case Some(values: Iterable[_]) => values.map(_.toString).toSet
    case _                         => Set.empty
  }

  // checkbox

  val boxValue: String = arg("value").map(_.toString).getOrElse("true")

  val checked: Boolean = arg("checked") match {
    case Some(c) => c == true
    case None    => field.value.map(_ == boxValue).orElse(arg("_default").map(_ == true)).getOrElse(false)
  }

  /** The input's attributes from the arguments, except those the helper renders itself. */
  def attrs(handled: String*): Html = FieldView.attributes(inputArgs, FieldView.Handled ++ handled)
}

object FieldView {

  private val BaseClasses = Set("form-control", "form-select", "form-check-input")

  /** Rendered by every helper itself. */
  private val Handled = Set("id", "name", "class", "value", "type", "aria-describedby", "aria-invalid")

  /** Input attributes for the field's constraints, as play-bootstrap's `constraintsArgs`. */
  def constraintArgs(field: Field)(implicit messages: MessagesProvider): Seq[(Symbol, Any)] =
    field.constraints.flatMap {
      case ("constraint.required", _)           => Some(Symbol("required") -> true)
      case ("constraint.min", Seq(p, _*))       => Some(Symbol("min") -> messages.messages(p.toString))
      case ("constraint.max", Seq(p, _*))       => Some(Symbol("max") -> messages.messages(p.toString))
      case ("constraint.minLength", Seq(p, _*)) => Some(Symbol("minlength") -> messages.messages(p.toString))
      case ("constraint.maxLength", Seq(p, _*)) => Some(Symbol("maxlength") -> messages.messages(p.toString))
      case ("constraint.pattern", Seq(p, _*)) =>
        p match {
          case regex: String       => Some(Symbol("pattern") -> messages.messages(regex))
          case regex: Function0[_] => Some(Symbol("pattern") -> regex().toString)
          case _                   => None
        }
      case _ => None
    }

  private val AttrName = "[a-zA-Z][a-zA-Z0-9_:.-]*".r

  /** Arguments without a leading underscore as HTML attributes, in their order (a repeated one
    * takes its last value): `true` is a bare attribute, `false` or `None` leaves it out, other
    * values are escaped. Names that aren't attribute names are left out.
    */
  def attributes(args: Seq[(Symbol, Any)], skip: Set[String] = Set.empty): Html = {
    val values = args.toMap
    val rendered = args
      .map(_._1)
      .distinct
      .map(key => key -> values(key))
      .filter { case (key, v) =>
        val n = key.name
        !n.startsWith("_") && !skip(n) && v != false && v != None && AttrName.matches(n)
      }
      .map {
        case (key, true) => key.name
        case (key, v)    => key.name + "=\"" + HtmlFormat.escape(v.toString).body + "\""
      }
    Html(rendered.mkString(" "))
  }
}
