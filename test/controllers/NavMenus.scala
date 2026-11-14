package controllers

import org.jsoup.nodes.{Document, Element}

import scala.jdk.CollectionConverters._

/** The navbar's Votes and Comments menus as a page renders them (navbar.scala.html votesMenu,
  * commentsMenu): their items and buttons, for the specs that check what they show and open.
  */
object NavMenus {

  /** A menu item: its name and count ("Unrated (19)" is "Unrated", 19), its link (None for a
    * greyed-out item), whether it is the current one (active, aria-current="page") or disabled.
    */
  final case class Item(name: String, count: Option[Int], href: Option[String], active: Boolean, disabled: Boolean)

  private val Counted = """^(.*?) \((\d+)\)$""".r

  private def item(e: Element): Item = {
    val text = e.text.trim
    val (name, count) = text match {
      case Counted(n, c) => (n, Some(c.toInt))
      case other         => (other, None)
    }
    Item(name, count, Option(e.attr("href")).filter(_.nonEmpty), e.hasClass("active"), e.hasClass("disabled"))
  }

  def items(doc: Document, menuId: String): Seq[Item] =
    doc.select(s"#$menuId ul.dropdown-menu .dropdown-item").asScala.toSeq.map(item)

  /** The Votes menu's items, in order. */
  def votes(doc: Document): Seq[Item] = items(doc, "votes-menu")

  /** The Comments menu's items, in order. */
  def comments(doc: Document): Seq[Item] = items(doc, "comments-menu")

  /** A menu's button text, with what only screen readers read ("Votes: All (6) · 3 unrated"). */
  def button(doc: Document, menuId: String): Option[String] =
    Option(doc.selectFirst(s"#$menuId > button")).map(_.text.trim)

  /** The current item of a menu. */
  def current(doc: Document, menuId: String): Option[Item] = items(doc, menuId).find(_.active)

  /** The Votes menu's items as (name, count, link): what the juror's tabs were. */
  def voteCounts(doc: Document): Seq[(String, Int, String)] =
    votes(doc).collect { case Item(name, Some(n), Some(href), _, _) => (name, n, href) }
}
