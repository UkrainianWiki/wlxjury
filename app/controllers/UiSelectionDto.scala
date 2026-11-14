package controllers

import play.api.i18n.Messages
import play.mvc.Call
import play.twirl.api.Html

class UiSelectionDto[T](val itemUrlFunction: T => Call,
                        val selectedItemId: T,
                        val items: Seq[(T, SelectionItem)],
                        val defaultPair: Option[(T, SelectionItem)] = None,
                        val unlisted: SelectionItem = UiSelectionDto.Unlisted) {

  val optValues: Seq[(T, SelectionItem)] = defaultPair.fold(
    Seq.empty[(T, SelectionItem)])(Seq(_)) ++
    items.map {
      case (k, v) => (k, v)
    }

  def map: Map[T, SelectionItem] = optValues.toMap

  /** The selected item; `unlisted` if it is not in the menu (a neutral one by default), never an
    * error. */
  def current: SelectionItem = map.getOrElse(selectedItemId, unlisted)

  /** The menu; `compactIcon`: in the organisers' crowded results bar, its toggle that icon below
    * 1700 px, the current item's text read by screen readers and shown as a tooltip, so the bar
    * stays on one line (dropDownSelection.scala.html, main.css). `id`: the menu's element id;
    * `label`: what the menu is, read before the current item by screen readers only. */
  def toDropDown(iconOnly: Boolean = false, compactIcon: Option[String] = None, id: Option[String] = None,
      label: Option[String] = None)(implicit messages: Messages): Html =
    views.html
      .dropDownSelection(this.asInstanceOf[UiSelectionDto[Any]], iconOnly, compactIcon, id, label)
}

object UiSelectionDto {

  /** The current item of a menu that does not list the selected one. */
  val Unlisted: SelectionItem = SelectionItem("—", localized = false)
}

object Test {

  def test(dto: UiSelectionDto[Any]): Call = {
    dto.itemUrlFunction(Some(dto.map.keys.head))
  }

  private val dto: UiSelectionDto[Int] = new UiSelectionDto[Int](
    Int => play.api.mvc.Call("", ""),
    1,
    Seq.empty,
    Some(0 -> new SelectionItem("")))

  test(dto.asInstanceOf[UiSelectionDto[Any]])
}
