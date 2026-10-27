package controllers

/** A page of a list of `count` items, `pageSize` to a page. Page 0 is a list shown whole,
  * with no page links.
  */
final case class Pager(page: Int, count: Int, pageSize: Int = Pager.pageSize) {

  def pages: Int = (count + pageSize - 1) / pageSize

  /** The page's first item's index. */
  def offset: Int = math.max(0, (page - 1) * pageSize)

  def display: Boolean = page != 0 && pages > 1

  def hasNext: Boolean = page < pages

  def hasPrev: Boolean = page > 1

  def pageNumbers: Seq[Int] = {
    val tenPowers = Seq(1000, 100, 10, 1).filter(_ <= pages)

    val numbers = Seq(1) ++ tenPowers.flatMap { power =>
      (1 to 9).map(_ * power + (page / (power * 10)) * power * 10)
    }

    numbers.filter(_ <= pages).distinct.sorted
  }
}

object Pager {

  val pageSize = 15

  /** The page of the item at `position` (0-based). */
  def pageOf(position: Int, pageSize: Int = pageSize): Int = position / pageSize + 1
}
