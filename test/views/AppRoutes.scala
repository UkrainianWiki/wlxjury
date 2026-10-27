package views

/** The app's routes as conf/routes defines them, matched without a Play app: the generated
  * router's `documentation` (method, path pattern, controller method), read from a
  * `router.Routes` built with no controllers (only its documentation is used, which needs
  * none).
  */
object AppRoutes {

  final case class RouteDoc(method: String, pattern: String, controller: String) {
    private val regex = {
      val param = """\$\w+<([^>]+)>""".r
      val sb = new StringBuilder("^")
      var last = 0
      param.findAllMatchIn(pattern).foreach { m =>
        sb.append(java.util.regex.Pattern.quote(pattern.substring(last, m.start)))
        sb.append("(").append(m.group(1)).append(")")
        last = m.end
      }
      sb.append(java.util.regex.Pattern.quote(pattern.substring(last))).append("$")
      sb.toString.r
    }

    def matches(method: String, path: String): Boolean =
      this.method == method && regex.pattern.matcher(path).matches()

    /** `controllers.LargeViewController.rateByPageId`, without the parameters. */
    def action: String = controller.takeWhile(_ != '(').replace("@", "")
  }

  lazy val all: Seq[RouteDoc] = {
    val ctor = classOf[router.Routes].getConstructors.maxBy(_.getParameterCount)
    val types = ctor.getParameterTypes
    require(types.last == classOf[String], s"router.Routes' last constructor parameter is not its prefix: ${types.toSeq}")
    val args = types.indices.map(i => if (i == types.length - 1) "/" else null).toArray[AnyRef]
    val routes = ctor.newInstance(args: _*).asInstanceOf[router.Routes]
    routes.documentation.map { case (m, p, c) => RouteDoc(m, p, c) }
  }

  /** The actions a request for `url` (a path, maybe with a query and fragment) routes to:
    * the first route that matches, as Play picks it, if any.
    */
  def actionFor(method: String, url: String): Option[String] = {
    val path = url.takeWhile(c => c != '?' && c != '#')
    all.find(_.matches(method, path)).map(_.action)
  }
}
