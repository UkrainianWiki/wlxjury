package security

import org.specs2.mutable.Specification
import views.AppRoutes

/** G2 (P0 3, WJ-F2.4): every routed action is built with a `Secured` builder (`withAuth`,
  * `withAuthForm`, `withAuthOn`, `withAuthJson`, `withAuthJsonOn`), directly or through a helper of the same
  * file, or is listed here: public, or checking the session itself. The API (`-> /api`) is
  * not in the router's documentation; `api.ApiSpec` covers its authentication. G2b
  * (`SignedOutSpec`) sends each non-public route without a session.
  */
class RouteAuthSpec extends Specification {
  import RouteAuthSpec._

  /** A Secured builder, or an unchecked way to build an action (`Action`, `EssentialAction {…}`,
    * `actionBuilder`).
    */
  private val Builder =
    """\b(?:withAuthJsonOn|withAuthJson|withAuthOn|withAuthForm|withAuth)\(|\bAction\b|\bEssentialAction\s*[{(]|\bactionBuilder\b""".r
  /** An unqualified call: `routes.ImageController.images(` or `xs.map(` is no helper of the file. */
  private val Call = """(?<![.\w])(\w+)\(""".r
  /** A member's start on a new line: its annotations (`@volatile`, `@scala.annotation.nowarn`,
    * `@throws[E]`, `@nowarn("…")`, arguments with one level of nested parentheses; strings are
    * blanked before the scan, [[blank]]), its modifiers (`case` of a case class or object among
    * them), then `def`, `val`, `var`, `lazy val`, `object`, `class`, `trait` or `type`. A `case` of
    * a match is inside its braces, never at the body's depth 0.
    */
  private val NextMember =
    ("""\n\s*(?:@[\w.]+(?:\[[^\]]*\])?(?:\((?:[^()]|\([^()]*\))*\))?\s+)*""" +
      """(?:(?:private|protected|override|final|implicit|sealed|abstract|case)(?:\[\w+\])?\s+)*""" +
      """(?:def|val|var|lazy\s+val|object|class|trait|type)\s""").r

  /** `text` with every comment and every string and character literal's contents as spaces (new
    * lines kept, so positions and lines stay), the quotes kept: a builder's name or a bracket in a
    * comment or a literal is not code. An interpolated string's `${…}` is blanked with it.
    */
  private def blank(text: String): String = {
    val out = new StringBuilder(text)
    def spaces(from: Int, until: Int): Unit =
      (from until math.min(until, text.length)).foreach(k => if (text.charAt(k) != '\n') out.setCharAt(k, ' '))
    def endOf(i: Int, close: String): Int = {
      val k = text.indexOf(close, i)
      if (k < 0) text.length else k + close.length
    }
    var i = 0
    while (i < text.length) {
      val c = text.charAt(i)
      if (text.startsWith("\"\"\"", i)) {
        val e = endOf(i + 3, "\"\"\"")
        spaces(i + 3, e - 3)
        i = e
      } else if (text.startsWith("//", i)) {
        val e = endOf(i, "\n") - 1
        spaces(i, e)
        i = e
      } else if (text.startsWith("/*", i)) {
        val e = endOf(i + 2, "*/")
        spaces(i, e)
        i = e
      } else if (c == '"') {
        var k = i + 1
        while (k < text.length && text.charAt(k) != '"' && text.charAt(k) != '\n')
          k += (if (text.charAt(k) == '\\') 2 else 1)
        spaces(i + 1, k)
        i = k + 1
      } else if (c == '\'' && text.startsWith("\\u", i + 1) && text.startsWith("'", i + 7)) {
        spaces(i + 1, i + 7)
        i += 8
      } else if (c == '\'' && text.startsWith("\\", i + 1) && text.startsWith("'", i + 3)) {
        spaces(i + 1, i + 3)
        i += 4
      } else if (c == '\'' && text.startsWith("'", i + 2)) {
        spaces(i + 1, i + 2)
        i += 3
      } else i += 1
    }
    out.toString
  }

  /** The body of `def name` in `text`, if any: after its signature's `=`, to the next member at
    * the body's own nesting level (an inner `val`/`def` is part of it), or to the bracket that
    * closes the scope the `def` is in ([[bodyEnd]]).
    */
  private def body(text: String, name: String): Option[String] =
    s"""\\bdef $name\\b""".r.findFirstMatchIn(text).map { m =>
      var depth = 0
      var i = m.end
      var start = -1
      while (start < 0 && i < text.length) {
        text.charAt(i) match {
          case '(' | '[' | '{' => depth += 1
          case ')' | ']' | '}' => depth -= 1
          case '=' if depth == 0 && !text.startsWith("=>", i) && !text.startsWith("==", i) => start = i + 1
          case _ =>
        }
        i += 1
      }
      val from = if (start < 0) text.length else start
      text.substring(from, bodyEnd(text, from))
    }

  /** Where a body starting at `from` ends: at a member ([[NextMember]]) at depth 0, or at a
    * closing bracket of the enclosing scope (depth below 0). Comments and string and character
    * literals are skipped when counting brackets.
    */
  private def bodyEnd(text: String, from: Int): Int = {
    def skipTo(i: Int, close: String): Int = {
      val k = text.indexOf(close, i)
      if (k < 0) text.length else k + close.length
    }
    var depth = 0
    var i = from
    var end = -1
    while (end < 0 && i < text.length) {
      val c = text.charAt(i)
      if (text.startsWith("\"\"\"", i)) i = skipTo(i + 3, "\"\"\"")
      else if (text.startsWith("//", i)) i = skipTo(i, "\n") - 1
      else if (text.startsWith("/*", i)) i = skipTo(i + 2, "*/")
      else if (c == '"') {
        var k = i + 1
        while (k < text.length && text.charAt(k) != '"' && text.charAt(k) != '\n')
          k += (if (text.charAt(k) == '\\') 2 else 1)
        i = k + 1
      } else if (c == '\'' && text.startsWith("'", i + 2)) i += 3
      else if (c == '\'' && text.startsWith("\\", i + 1) && text.startsWith("'", i + 3)) i += 4
      else {
        c match {
          case '(' | '[' | '{' => depth += 1
          case ')' | ']' | '}' =>
            depth -= 1
            if (depth < 0) end = i
          case '\n' if depth == 0 && NextMember.pattern.matcher(text).region(i, text.length).lookingAt() => end = i
          case _ =>
        }
        i += 1
      }
    }
    if (end < 0) text.length else end
  }

  /** How `name`'s action is built: "builder", "Action" (unchecked), or "none"; through
    * helpers of the same file it calls before any builder. Read in the source with its comments
    * and literals blanked ([[blank]]).
    */
  private def builtWith(text: String, name: String): String = scan(blank(text), name, 0)

  private def scan(text: String, name: String, depth: Int): String =
    body(text, name).fold("none") { b =>
      Builder.findFirstMatchIn(b) match {
        case Some(m) if m.matched.startsWith("with") => "builder"
        case Some(m) =>
          // a helper called before the bare Action may hold the builder
          helper(text, name, b.substring(0, m.start)).filter(_ => depth < 3).fold("Action")(h => scan(text, h, depth + 1))
        case None => helper(text, name, b).filter(_ => depth < 3).fold("none")(h => scan(text, h, depth + 1))
      }
    }

  private def helper(text: String, self: String, b: String): Option[String] =
    Call.findAllMatchIn(b).map(_.group(1)).find(n => n != self && s"""\\bdef $n\\b""".r.findFirstIn(text).nonEmpty)

  private lazy val sources: Map[String, String] = SourceScan.files("app", ".scala").map(s => s.path -> s.text).toMap

  private def sourceOf(action: String): Option[String] = {
    val cls = action.substring(0, action.lastIndexOf('.'))
    sources.get(s"app/${cls.replace('.', '/')}.scala")
  }

  "every routed action" should {
    "use a Secured builder, or be listed as public or checking the session itself" in {
      val unchecked = for {
        route <- AppRoutes.all
        action = route.action
        if !Public.contains(action) && !ChecksSessionItself.contains(action)
        how = sourceOf(action).fold("no source")(builtWith(_, action.substring(action.lastIndexOf('.') + 1)))
        if how != "builder"
      } yield s"${route.method} ${route.pattern} -> $action: $how"
      unchecked.distinct must beEmpty
    }

    "be classified by the scan as built (it is not vacuous)" in {
      val bare = "def a(): EssentialAction = Action { request => Ok }\n"
      val wrapped = "def b(): EssentialAction = EssentialAction { r => c()(r) }\n" +
        "  def c(): EssentialAction = withAuth() { u => r => Ok }\n"
      val delegated = "def d(page: Int = 1): EssentialAction =\n    listGeneric(Some(page), page)\n" +
        "  def listGeneric(v: Option[Int], p: Int): EssentialAction = noStore(withAuthOn(blocking)() { u => r => Ok })\n"
      val form = "def f(id: Long): EssentialAction =\n    withAuthForm(p(id)) { u => implicit r =>\n" +
        "      val script = isScript(r)\n      if (script) NoContent else Ok\n    }\n"
      val innerVal = "def g(): EssentialAction = {\n    val p = perm(\"{\") // a (\n    withAuth(p) { u => r => Ok }\n  }\n"
      // neither the next member's builder nor a reverse route to a builder-made action counts
      val next = "def h(): EssentialAction = {\n    val x = 1\n    Action { r => Ok }\n  }\n" +
        "  def i(): EssentialAction = withAuth() { u => r => Ok }\n"
      val reverse = "def j(): EssentialAction =\n    noBuilder { r => Redirect(routes.C.k().url) }\n" +
        "  def k(): EssentialAction = withAuth() { u => r => Ok }\n"
      (builtWith(bare, "a") === "Action") and (builtWith(wrapped, "b") !== "builder") and
        (builtWith(delegated, "d") === "builder") and (builtWith(bare, "missing") === "none") and
        (builtWith(form, "f") === "builder") and (builtWith(innerVal, "g") === "builder") and
        (builtWith(next, "h") === "Action") and (builtWith(reverse, "j") === "none")
    }

    "read no builder or helper call in a comment or a literal" in {
      val commented = "def a(): EssentialAction = {\n    // withAuth() { u => r => Ok }\n    /* withAuthJson( */\n" +
        "    Action { r => Ok }\n  }\n"
      val quoted = "def b(): EssentialAction = {\n    val s = \"withAuth(\"\n    val t = \"\"\"withAuthOn(blocking)\"\"\"\n" +
        "    Action { r => Ok }\n  }\n"
      // a helper named only in a comment or a string is not called
      val helperInComment = "def c(): EssentialAction = {\n    // see d()\n    log(\"d()\")\n    Action { r => Ok }\n  }\n" +
        "  def d(): EssentialAction = withAuth() { u => r => Ok }\n"
      (builtWith(commented, "a") === "Action") and (builtWith(quoted, "b") === "Action") and
        (builtWith(helperInComment, "c") === "Action")
    }

    "not end a body at a closing bracket inside a literal or a comment" in {
      // a `}` or `)` in a string, a character, a triple-quoted string or a comment would close the
      // def's scope early, leaving its builder out
      val closing = "def a(): EssentialAction = {\n    val s = \"}\"\n    val c = ')'\n    val e = '\\\\'\n" +
        "    val t = \"\"\") }\"\"\"\n    val u = '\\u007d'\n    // }\n    /* ) */\n    withAuth() { u => r => Ok }\n  }\n" +
        "  def b(): EssentialAction = Action { r => Ok }\n"
      (builtWith(closing, "a") === "builder") and (body(closing, "a").exists(_.contains("withAuth()")) must beTrue) and
        (blank(closing).length === closing.length) and (blank(closing) must not(contain("\"}\""))) and
        (builtWith(closing, "b") === "Action")
    }

    "end a body at the next member, case, sealed, abstract, type and annotated members too" in {
      def after(member: String) = s"def a(): EssentialAction =\n    noBuilder { r => Ok }\n  $member\n"
      foreach(Seq(
        "case class C(p: P = withAuth() { u => r => Ok })",
        "case object O { val e = withAuth() { u => r => Ok } }",
        "final case class F(e: EssentialAction = withAuth() { u => r => Ok })",
        "sealed trait S { def e = withAuth() { u => r => Ok } }",
        "abstract class A { def e = withAuth() { u => r => Ok } }",
        "type T = Wrapped { def e = withAuth() { u => r => Ok } }",
        "private type U = Int; val e = withAuth() { u => r => Ok }",
        "lazy val e = withAuth() { u => r => Ok }",
        "@volatile var e = withAuth() { u => r => Ok }",
        "@nowarn(\"cat=deprecation\") def e = withAuth() { u => r => Ok }",
        "@deprecated(\"x\", \"1\") @inline private def e = withAuth() { u => r => Ok }",
        "@scala.annotation.nowarn def e = withAuth() { u => r => Ok }",
        "@throws[IllegalStateException] def e = withAuth() { u => r => Ok }",
        "@deprecated(\"use f()\", \"1\") def e = withAuth() { u => r => Ok }",
        "@SuppressWarnings(Array(\"x\")) val e = withAuth() { u => r => Ok }"
      )) { member =>
        builtWith(after(member), "a") aka member must_=== "none"
      }
    }

    "recognise annotated members on the source as written too (strings not blanked)" in {
      // NextMember itself: nested parentheses and a parenthesis inside a string
      foreach(Seq("@scala.annotation.nowarn def e", "@throws[E] def e", "@deprecated(\"use f()\", \"1\") def e",
        "@SuppressWarnings(Array(\"x\")) val e")) { member =>
        NextMember.pattern.matcher(s"\n  $member = 1").lookingAt() aka member must beTrue
      }
    }

    "have no stale entry in the lists" in {
      val routed = AppRoutes.all.map(_.action).toSet
      ((Public.keySet ++ ChecksSessionItself.keySet) -- routed) must beEmpty
    }
  }
}

object RouteAuthSpec {

  /** Actions anyone may call, with why that is fine. */
  val Public: Map[String, String] = Map(
    "controllers.ReadinessController.ready" -> "answers loopback clients only (the deploy tool), others 404; no data",
    "controllers.Assets.versioned" -> "static files",
    "controllers.WebJars.at" -> "static files of the listed webjars, others 404",
    "controllers.LoginController.login" -> "the sign-in page",
    "controllers.LoginController.auth" -> "sign-in (bcrypt turns bounded)",
    "controllers.LoginController.logoutPage" -> "a page with a sign-out button; changes nothing",
    "controllers.LoginController.logout" -> "sign-out, a POST with the CSRF token",
    "controllers.LoginController.invitation" -> "an invitation link: the token is checked (AccountTokenService.check)",
    "controllers.LoginController.acceptInvitation" -> "accepting an invitation: token checked, POST with CSRF",
    "controllers.LoginController.emailConfirmPage" -> "an email-change link: the token is checked",
    "controllers.LoginController.emailConfirm" -> "confirming an email change: token checked, POST with CSRF",
    "controllers.LoginController.setupView" -> "404 unless users is empty and wlxjury.setup.token is set",
    "controllers.LoginController.setup" -> "404 unless users is empty; the setup token is checked",
    "controllers.TrailingSlashController.untrail" ->
      "a redirect to the same local path without its trailing slashes (or a 404); the target checks access",
    "controllers.UserController.switchContestLink" -> "an old link: redirects to / and changes nothing (switching is a POST)",
    "controllers.OAuthController.login" ->
      "leaves for the provider (404 if not offered on the host); only the session's flow keys change",
    "controllers.OAuthController.callback" ->
      "the provider's redirect: signs in or links only with the state of this browser's flow (IdentityLinker's S2 rules)",
    "controllers.OAuthChooserController.page" -> "the account chooser: only the session's own choice, by its flow token",
    "controllers.OAuthChooserController.choose" ->
      "a choice: POST with CSRF; the session's choice and option tokens; a placeholder, or a password proof",
    "controllers.OAuthChooserController.confirmPage" -> "a mailed link's page: a button, changes nothing",
    "controllers.OAuthChooserController.confirm" ->
      "the mailed proof: POST with CSRF; a live single-use link of an account of this session's choice"
  )

  /** Actions that check the session themselves (no builder), each refusing a signed-out
    * request with 401 (G2b checks it).
    */
  val ChecksSessionItself: Map[String, String] = Map(
    "controllers.ImageProxyController.serve" ->
      "thumbnails: 401 without a session; the session's account must resolve (mayView, cached per account and version)",
    "controllers.RoundController.newFilesCount" ->
      "script JSON: 401 without a session (userFromRequest); an admin of the round's contest (adminOfRound) else a JSON denial",
    "controllers.JobController.status" ->
      "script JSON: 401 without a session (userFromRequest); withJob checks the job's contest"
  )
}
