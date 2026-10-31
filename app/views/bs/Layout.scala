package views.html.bs

/** How the bs form helpers lay out a field: the label above it (vertical, the default) or the
  * label and the field side by side in grid columns (horizontal). Taken implicitly, as
  * play-bootstrap's field constructor is, so a template sets it once:
  * {{{
  * @implicitLayout = @{ bs.Layout.horizontal("col-md-4", "col-md-8") }
  * }}}
  * Without one in scope, the companion's `default` (vertical) applies.
  */
sealed trait Layout {

  /** The columns, for a horizontal layout. */
  def horizontal: Option[Layout.Horizontal]
}

object Layout {

  case object Vertical extends Layout {
    val horizontal: Option[Horizontal] = None
  }

  /** @param labelCol the label column's classes, e.g. `col-md-4`
    * @param fieldCol the field column's classes, e.g. `col-md-8`
    */
  final case class Horizontal(labelCol: String, fieldCol: String) extends Layout {
    val horizontal: Option[Horizontal] = Some(this)

    /** The field column moved past the label column (`col-md-4` gives `offset-md-4`), for a
      * field with no visible label in that column.
      */
    def offsetFieldCol: String = {
      val offsets = labelCol.split("\\s+").toSeq.collect { case ColClass(bp, n) =>
        "offset-" + Option(bp).getOrElse("") + n
      }
      (fieldCol +: offsets).mkString(" ")
    }
  }

  private val ColClass = """col-((?:[a-z]+-)?)(\d+)""".r

  val vertical: Layout = Vertical

  def horizontal(labelCol: String, fieldCol: String): Layout = Horizontal(labelCol, fieldCol)

  /** Vertical, unless a template has its own implicit layout. */
  implicit val default: Layout = Vertical
}
