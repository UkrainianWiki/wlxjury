package controllers

case class Pager(var page: Int = 1,
                 offset: Option[Int] = None,
                 limit: Option[Int] = None,
                 startPageId: Option[Long] = None,
                 var pages: Option[Int] = None) {

  private var _count: Option[Int] = None

  def display: Boolean = page != 0 && pages.exists(_ > 1)

  def hasNext: Boolean = pages.exists(page < _)

  def hasPrev: Boolean = page > 1

  def pageSize: Int = limit.getOrElse(Pager.pageSize)

  def setCount(v: Int): Unit = {
    _count = Some(v)
    pages = Some(v / pageSize + (if (v % pageSize > 0) 1 else 0))
  }

  def pageNumbers: Seq[Int] = {
    (for (p <- pages) yield {

      val tenPowers = Seq(1000, 100, 10, 1).filter(_ <= p)

      val numbers = Seq(1) ++ tenPowers.flatMap { power =>
        (1 to 9).map(_ * power + (page / (power * 10)) * power * 10)
      }

      numbers.filter(_ <= p).distinct.sorted
    }).getOrElse(Seq.empty)
  }
}

object Pager {

  def pageOffset(page: Int) =
    new Pager(page = page, offset = Some(Math.max(0, (page - 1) * pageSize)))

  def startPageId(id: Long) = new Pager(startPageId = Some(id))

  def pageSize = 15

}
