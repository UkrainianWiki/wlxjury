package controllers

/** A page of a list of `count` items, `pageSize` to a page. Page 0 is a list shown whole,
  * with no page links. Its links are the previous and the next page, with the items shown
  * ("46–60 of 5,712"): the count comes from what the page reads anyway (a juror's
  * [[JurorCounts]]), page numbers told a juror little.
  */
final case class Pager(page: Int, count: Int, pageSize: Int = Pager.pageSize) {

  def pages: Int = (count + pageSize - 1) / pageSize

  /** The page's first item's index. */
  def offset: Int = math.max(0, (page - 1) * pageSize)

  def display: Boolean = page != 0 && pages > 1

  def hasNext: Boolean = page < pages

  def hasPrev: Boolean = page > 1

  /** The page's first item, from 1 (0 if the list is empty). */
  def first: Int = if (count == 0) 0 else math.min(offset + 1, count)

  /** The page's last item, from 1. */
  def last: Int = math.min(offset + pageSize, count)
}

object Pager {

  val pageSize = 15

  /** The page of the item at `position` (0-based). */
  def pageOf(position: Int, pageSize: Int = pageSize): Int = position / pageSize + 1
}
