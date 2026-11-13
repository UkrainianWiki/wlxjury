package security

import org.specs2.mutable.Specification
import views.AppRoutes

/** G2 (P0 3, WJ-F2.4): every routed action is built with a `Secured` builder (`withAuth`,
  * `withAuthOn`, `withAuthJson`, `withAuthJsonOn`), directly or through a helper of the same
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
    """\b(?:withAuthJsonOn|withAuthJson|withAuthOn|withAuth)\(|\bAction\b|\bEssentialAction\s*[{(]|\bactionBuilder\b""".r
  private val Call = """\b(\w+)\(""".r
  private val NextMember =
    """\n\s*(?:(?:private|protected|override|final|implicit)(?:\[\w+\])?\s+)*(?:def|val|var|lazy\s+val|object|class|trait)\s""".r

  /** The body of `def name` in `text` (after its signature's `=`, to the next member), if any.
    * It stops at the first inner `val`/`def` too, so a builder after one is missed: the check
    * then reports the action (it errs toward reporting).
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
      val end = NextMember.findFirstMatchIn(text.substring(from)).fold(text.length)(from + _.start)
      text.substring(from, end)
    }

  /** How `name`'s action is built: "builder", "Action" (unchecked), or "none"; through
    * helpers of the same file it calls before any builder.
    */
  private def builtWith(text: String, name: String, depth: Int = 0): String =
    body(text, name).fold("none") { b =>
      Builder.findFirstMatchIn(b) match {
        case Some(m) if m.matched.startsWith("with") => "builder"
        case Some(m) =>
          // a helper called before the bare Action may hold the builder
          helper(text, name, b.substring(0, m.start)).filter(_ => depth < 3).fold("Action")(h => builtWith(text, h, depth + 1))
        case None => helper(text, name, b).filter(_ => depth < 3).fold("none")(h => builtWith(text, h, depth + 1))
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
      (builtWith(bare, "a") === "Action") and (builtWith(wrapped, "b") !== "builder") and
        (builtWith(delegated, "d") === "builder") and (builtWith(bare, "missing") === "none")
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
