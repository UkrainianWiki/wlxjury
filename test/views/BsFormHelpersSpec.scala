package views

import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import org.specs2.mutable.Specification
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation.Constraints
import play.api.i18n.{Lang, Messages, MessagesImpl}
import play.api.mvc.Call
import play.api.test.Helpers
import play.twirl.api.Html
import views.html.bs
import views.html.bs.Layout

import scala.jdk.CollectionConverters._

/** The Bootstrap 5 form helpers in views/bs, rendered with a plain Messages: labels tied to
  * their inputs, errors and help, attributes, layouts, select and checkbox state. No database
  * or Play app.
  */
class BsFormHelpersSpec extends Specification {

  implicit val messages: Messages = MessagesImpl(
    Lang("en"),
    Helpers.stubMessagesApi(
      Map("en" -> Map("error.required" -> "This field is required", "name.label" -> "Your name"))
    )
  )

  private val form = Form(
    tuple(
      "name" -> nonEmptyText,
      "secret" -> text,
      "notes" -> text,
      "choice" -> text,
      "many" -> seq(text),
      "flag" -> boolean
    )
  )

  private val filled = form.bind(
    Map(
      "name" -> "Anna",
      "secret" -> "s3cret",
      "notes" -> "<b>notes</b>",
      "choice" -> "b",
      "many[0]" -> "c",
      "many[1]" -> "a",
      "flag" -> "true"
    )
  )

  private val failed = form.bind(Map("name" -> ""))

  private val constrained = Form(
    tuple(
      "name" -> nonEmptyText(maxLength = 20),
      "age" -> number(min = 1, max = 9),
      "code" -> text.verifying(Constraints.pattern("[a-z]+".r))
    )
  )

  private val options = Seq("a" -> "A", "b" -> "B", "c" -> "C")

  private val horizontal: Layout = Layout.horizontal("col-md-4", "col-md-8")

  private def doc(html: Html): Document = Jsoup.parseBodyFragment(html.body)

  private def one(d: Document, css: String): Element = {
    val found = d.select(css).asScala.toSeq
    found must haveSize(1)
    found.head
  }

  private def classes(e: Element): Set[String] = e.classNames().asScala.toSet

  "text" should {
    "tie the label to the input in a vertical wrapper, without errors" in {
      val d = doc(bs.text(filled("name"), Symbol("_label") -> "name.label"))
      val wrapper = one(d, "div#name_field")
      val label = one(d, "label")
      val input = one(d, "input")
      classes(wrapper) === Set("mb-3")
      classes(label) === Set("form-label")
      label.text === "Your name"
      label.attr("for") === input.id
      input.id === "name"
      input.attr("name") === "name"
      input.attr("type") === "text"
      input.attr("value") === "Anna"
      classes(input) === Set("form-control")
      input.hasAttr("aria-describedby") must beFalse
      d.select(".invalid-feedback").size === 0
    }

    "show the localised error, is-invalid and link help and error" in {
      val d = doc(bs.text(failed("name"), Symbol("_label") -> "Name", Symbol("_help") -> "Your full name"))
      val input = one(d, "input")
      classes(input) must contain("is-invalid")
      val error = one(d, ".invalid-feedback")
      error.text === "This field is required"
      val help = one(d, ".form-text")
      help.text === "Your full name"
      input.attr("aria-describedby").split(" ").toSeq === Seq(help.id, error.id)
      d.select("#name_help").size === 1
    }

    "render other arguments as escaped attributes and add the caller's class" in {
      val html = bs.text(
        filled("name"),
        Symbol("_label") -> "Name",
        Symbol("placeholder") -> "\"><script>x</script>",
        Symbol("data-round") -> 5,
        Symbol("readonly") -> true,
        Symbol("disabled") -> false,
        Symbol("class") -> "form-control form-control-lg"
      )
      html.body must not(contain("<script>"))
      val input = one(doc(html), "input")
      input.attr("placeholder") === "\"><script>x</script>"
      input.attr("data-round") === "5"
      input.hasAttr("readonly") must beTrue
      input.hasAttr("disabled") must beFalse
      input.hasAttr("_label") must beFalse
      input.attr("class") === "form-control form-control-lg"
    }

    "translate a placeholder that is a message key" in {
      one(doc(bs.text(filled("name"), Symbol("placeholder") -> "name.label")), "input")
        .attr("placeholder") === "Your name"
    }

    "add the field's constraints as attributes, which arguments override" in {
      val d = doc(bs.text(constrained("name")))
      val input = one(d, "input")
      input.hasAttr("required") must beTrue
      input.attr("maxlength") === "20"
      val age = one(doc(bs.text(constrained("age"))), "input")
      age.attr("min") === "1"
      age.attr("max") === "9"
      one(doc(bs.text(constrained("code"))), "input").attr("pattern") === "[a-z]+"

      val overridden = one(
        doc(bs.text(constrained("name"), Symbol("required") -> false, Symbol("maxlength") -> 5)),
        "input"
      )
      overridden.hasAttr("required") must beFalse
      overridden.attr("maxlength") === "5"
      one(doc(bs.password(constrained("name"))), "input").hasAttr("required") must beTrue
    }

    "mark an invalid input with aria-invalid only when it has errors" in {
      one(doc(bs.text(failed("name"))), "input").attr("aria-invalid") === "true"
      one(doc(bs.text(filled("name"))), "input").hasAttr("aria-invalid") must beFalse
      one(doc(bs.password(failed("name"))), "input").attr("aria-invalid") === "true"
      one(doc(bs.textarea(failed("name"))), "textarea").attr("aria-invalid") === "true"
    }

    "add the caller's aria-describedby to the help's, in one attribute" in {
      val html = bs.text(filled("name"), Symbol("_help") -> "Help", Symbol("aria-describedby") -> "extra")
      "aria-describedby".r.findAllIn(html.body).size === 1
      one(doc(html), "input").attr("aria-describedby") === "extra name_help"
    }

    "escape the label and help text" in {
      val html = bs.text(filled("name"), Symbol("_label") -> "<i>L</i>", Symbol("_help") -> "<i>H</i>")
      html.body must not(contain("<i>"))
      one(doc(html), "label").text === "<i>L</i>"
    }

    "take the input id from `id`" in {
      val d = doc(bs.text(filled("name"), Symbol("_label") -> "Name", Symbol("id") -> "custom"))
      one(d, "input").id === "custom"
      one(d, "label").attr("for") === "custom"
      one(d, "div.mb-3").id === "custom_field"
    }

    "hide the label from sight with `_hiddenLabel`" in {
      val d = doc(bs.text(filled("name"), Symbol("_hiddenLabel") -> "Login", Symbol("placeholder") -> "Login"))
      val label = one(d, "label")
      classes(label) === Set("form-label", "visually-hidden")
      label.text === "Login"
      label.attr("for") === one(d, "input").id
    }

    "put label and field in columns in a horizontal layout" in {
      implicit val layout: Layout = horizontal
      val d = doc(bs.text(failed("name"), Symbol("_label") -> "Name"))
      classes(one(d, "div#name_field")) === Set("row", "mb-3")
      classes(one(d, "label")) === Set("col-md-4", "col-form-label")
      val column = one(d, "div.col-md-8")
      column.select("input#name").size === 1
      column.select(".invalid-feedback").size === 1
    }

    "take a template's own implicitLayout over the default" in {
      val d = doc(views.html.bstest.horizontalField(filled("name")))
      classes(one(d, "div#name_field")) === Set("row", "mb-3")
      classes(one(d, "label")) === Set("col-sm-3", "col-form-label")
      one(d, "div.col-sm-9").select("input#name").size === 1
    }

    "move the field past the label column when the label is hidden" in {
      implicit val layout: Layout = horizontal
      val d = doc(bs.text(filled("name"), Symbol("_hiddenLabel") -> "Name"))
      classes(one(d, "input").parent) === Set("col-md-8", "offset-md-4")
    }
  }

  "password" should {
    "never render the value" in {
      val d = doc(bs.password(filled("secret"), Symbol("_label") -> "Password"))
      val input = one(d, "input")
      input.attr("type") === "password"
      input.hasAttr("value") must beFalse
      one(d, "label").attr("for") === input.id
    }
  }

  "textarea" should {
    "render rows and the escaped value" in {
      val html = bs.textarea(filled("notes"), Symbol("_label") -> "Notes", Symbol("rows") -> 8)
      html.body must not(contain("<b>"))
      val d = doc(html)
      val area = one(d, "textarea")
      area.attr("rows") === "8"
      area.text === "<b>notes</b>"
      classes(area) === Set("form-control")
      one(d, "label").attr("for") === area.id
    }
  }

  "select" should {
    "select the field's value, with a blank default option" in {
      val d = doc(bs.select(filled("choice"), options, Symbol("_label") -> "Choice", Symbol("_default") -> "None"))
      val select = one(d, "select")
      select.attr("name") === "choice"
      classes(select) === Set("form-select")
      one(d, "label").attr("for") === select.id
      d.select("option").asScala.map(_.attr("value")).toSeq === Seq("", "a", "b", "c")
      d.select("option[selected]").asScala.map(_.attr("value")).toSeq === Seq("b")
    }

    "take `value` when the field has none and replace a form-control class" in {
      val d = doc(bs.select(form("choice"), options, Symbol("value") -> "c", Symbol("class") -> "form-control"))
      d.select("option[selected]").asScala.map(_.attr("value")).toSeq === Seq("c")
      classes(one(d, "select")) === Set("form-select")
      one(d, "select").hasAttr("value") must beFalse
    }

    "select every indexed value of a multiple select, submitted as name[]" in {
      val d = doc(bs.select(filled("many"), options, Symbol("_label") -> "Many", Symbol("multiple") -> true))
      val select = one(d, "select")
      select.attr("name") === "many[]"
      select.hasAttr("multiple") must beTrue
      d.select("option[selected]").asScala.map(_.attr("value")).toSet === Set("a", "c")
      d.select("input[type=hidden]").size === 0
    }

    "be disabled with hidden inputs carrying the value when readonly" in {
      val single = doc(bs.select(filled("choice"), options, Symbol("readonly") -> true))
      one(single, "select").hasAttr("disabled") must beTrue
      one(single, "select").hasAttr("readonly") must beFalse
      val hidden = one(single, "input[type=hidden]")
      hidden.attr("name") === "choice"
      hidden.attr("value") === "b"

      val multiple =
        doc(bs.select(filled("many"), options, Symbol("multiple") -> true, Symbol("readonly") -> true))
      multiple.select("input[type=hidden]").asScala.map(i => i.attr("name") -> i.attr("value")).toSeq ===
        Seq("many[]" -> "c", "many[]" -> "a")
    }

    "not submit a disabled select" in {
      val d = doc(bs.select(filled("many"), options, Symbol("multiple") -> true, Symbol("disabled") -> true))
      one(d, "select").hasAttr("disabled") must beTrue
      d.select("input[type=hidden]").size === 0
    }

    "render disabled once and submit nothing when readonly and disabled" in {
      val html = bs.select(filled("choice"), options, Symbol("readonly") -> true, Symbol("disabled") -> true)
      // the raw tag: Jsoup's parser would drop a repeated attribute
      val tag = "<select[^>]*>".r.findFirstIn(html.body).getOrElse("")
      """\bdisabled\b""".r.findAllIn(tag).size === 1
      doc(html).select("input[type=hidden]").size === 0
    }

    "submit the option shown when readonly with no value" in {
      def hidden(html: Html) = doc(html).select("input[type=hidden]").asScala.map(_.attr("value")).toSeq
      hidden(bs.select(form("choice"), options, Symbol("readonly") -> true)) === Seq("a")
      hidden(bs.select(form("choice"), options, Symbol("readonly") -> true, Symbol("_disabled") -> Seq("a"))) ===
        Seq("b")
      hidden(bs.select(form("choice"), options, Symbol("readonly") -> true, Symbol("_default") -> "None")) ===
        Seq("")
      hidden(bs.select(form("choice"), options, Symbol("readonly") -> true, Symbol("value") -> "x")) === Seq("a")
      hidden(bs.select(form("many"), options, Symbol("multiple") -> true, Symbol("readonly") -> true)) === Nil
    }

    "show errors" in {
      val d = doc(bs.select(failed("name"), options, Symbol("_label") -> "Name"))
      classes(one(d, "select")) must contain("is-invalid")
      one(d, "select").attr("aria-invalid") === "true"
      one(d, ".invalid-feedback").text === "This field is required"
    }
  }

  "checkbox" should {
    "be checked from the field, the input before its label" in {
      val d = doc(bs.checkbox(filled("flag"), Symbol("_label") -> "Flag", Symbol("data-coi") -> "<3"))
      val check = one(d, "div.form-check")
      val input = one(d, "input[type=checkbox]")
      val label = one(d, "label")
      check.children.asScala.map(_.tagName).toSeq.take(2) === Seq("input", "label")
      classes(input) === Set("form-check-input")
      classes(label) === Set("form-check-label")
      label.attr("for") === input.id
      label.text === "Flag"
      input.attr("value") === "true"
      input.hasAttr("checked") must beTrue
      input.attr("data-coi") === "<3"
    }

    "be unchecked for another value or no value" in {
      val off = form.bind(Map("flag" -> "false"))("flag")
      doc(bs.checkbox(off, Symbol("_label") -> "Flag")).select("input[checked]").size === 0
      doc(bs.checkbox(form("flag"), Symbol("_label") -> "Flag")).select("input[checked]").size === 0
      doc(bs.checkbox(form("flag"), Symbol("_label") -> "Flag", Symbol("_default") -> true))
        .select("input[checked]")
        .size === 1
    }

    "submit a hidden value when readonly and checked" in {
      val d = doc(bs.checkbox(filled("flag"), Symbol("_label") -> "Flag", Symbol("readonly") -> true))
      one(d, "input[type=checkbox]").hasAttr("disabled") must beTrue
      val hidden = one(d, "input[type=hidden]")
      hidden.attr("name") === "flag"
      hidden.attr("value") === "true"
      val off = form.bind(Map("flag" -> "false"))("flag")
      doc(bs.checkbox(off, Symbol("readonly") -> true)).select("input[type=hidden]").size === 0
    }

    "render disabled once and submit nothing when readonly and disabled" in {
      val html = bs.checkbox(filled("flag"), Symbol("readonly") -> true, Symbol("disabled") -> true)
      val tag = "<input type=\"checkbox\"[^>]*>".r.findFirstIn(html.body).getOrElse("")
      """\bdisabled\b""".r.findAllIn(tag).size === 1
      doc(html).select("input[type=hidden]").size === 0
    }

    "show help and errors" in {
      val d = doc(bs.checkbox(failed("name"), Symbol("_label") -> "Flag", Symbol("_help") -> "Why"))
      val input = one(d, "input")
      classes(input) must contain("is-invalid")
      input.attr("aria-invalid") === "true"
      val error = one(d, "div.form-check .invalid-feedback")
      error.text === "This field is required"
      input.attr("aria-describedby").split(" ").toSet === Set(one(d, ".form-text").id, error.id)
    }

    "line up with the fields in a horizontal layout, or label the column with `_text`" in {
      implicit val layout: Layout = horizontal
      val plain = doc(bs.checkbox(filled("flag"), Symbol("_label") -> "Flag"))
      classes(one(plain, "div.form-check").parent) === Set("col-md-8", "offset-md-4")

      val withText = doc(bs.checkbox(filled("flag"), Symbol("_label") -> "Column", Symbol("_text") -> "Flag"))
      one(withText, "div.col-md-4.col-form-label").text === "Column"
      one(withText, "label.form-check-label").text === "Flag"
      classes(one(withText, "div.form-check").parent) === Set("col-md-8")
    }
  }

  "form" should {
    "render action, method and escaped attributes around the body" in {
      val html = bs.form(Call("POST", "/rounds/save?x=1&y=2"), Symbol("id") -> "f\"1", Symbol("novalidate") -> true) {
        Html("<input name=\"inside\">")
      }
      val f = one(doc(html), "form")
      f.attr("action") === "/rounds/save?x=1&y=2"
      f.attr("method") === "POST"
      f.id === "f\"1"
      f.hasAttr("novalidate") must beTrue
      f.select("input[name=inside]").size === 1
    }
  }
}
