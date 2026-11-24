package security

import org.specs2.mutable.Specification

/** The guard specs of series admins (contest series plan §3.3, §8; CY-2), over all of `app/`:
  *
  *  - `withSeries` (a session's series reach) is called only from `Secured.sessionUser` and
  *    `LoginResults`: never for an API token (`ApiTokenService`) or root's edit form
  *    (`UserController.editableUser`), which keep `Secured.inCurrentContest`, raw;
  *  - the session's series reach (`seriesContests`, `derivedRoles`, `seriesGranted`, and what reads
  *    them) is read only by `Secured`, `JurorContests` and the series controller (through Secured's
  *    helpers), `User` defining it;
  *  - `seriesAccountContests`, the account rules' only input from the series (CA17), is read only by
  *    those rules;
  *  - every action that opts in to derived roles (`derived = true`) or to account reach
  *    (`accountReach = true`, `accountAdminPermission`) is listed here: a new one needs this list and
  *    its review updated;
  *  - a bare `isRoot` on a target account, or SQL filtering target accounts on `is_root`, is not
  *    added: a target is checked with `User.isProtectedTarget` (root or a series admin); the sites
  *    left are listed with why.
  */
class SeriesReachGuardSpec extends Specification {

  private lazy val sources = SourceScan.files("app", ".scala", ".html")

  private def counts(pattern: scala.util.matching.Regex): Map[String, Int] =
    SourceScan.hits(sources, pattern).map { case (file, lines) => file.replaceFirst("^.*?(app/)", "$1") -> lines.size }

  /** Each action that opts in to derived roles, by file: the count of `derived = true`. */
  private val DerivedOptIns: Map[String, (Int, String)] = Map(
    "app/controllers/RoundController.scala" -> (10, "rounds: list, create, edit, start, stop, statistics, round set"),
    "app/controllers/RoundJurorsController.scala" -> (4, "a round's jurors"),
    "app/controllers/RoundReviewController.scala" -> (5, "round review"),
    "app/controllers/RoundConflictsController.scala" -> (3, "conflicts of interest"),
    "app/controllers/RoundImageCheckController.scala" -> (1, "a round's file check"),
    "app/controllers/ImageController.scala" -> (7, "imports and the image cache"),
    "app/controllers/CriteriaController.scala" -> (2, "criteria"),
    "app/controllers/GalleryController.scala" -> (9, "galleries and contest files (votes stay VotePermission)"),
    "app/controllers/LargeViewController.scala" -> (2, "the large view (votes stay VotePermission)"),
    "app/controllers/MonumentController.scala" -> (2, "monuments (galleries)"),
    "app/controllers/ImageDiscussionController.scala" -> (1, "comments (an Organizer comments)"),
    "app/controllers/AuthorsController.scala" -> (1, "authors (statistics)"),
    "app/controllers/ContestHealthController.scala" -> (1, "the contest's health (statistics)"),
    "app/controllers/UserController.scala" -> (3, "the greeting (settings, twice); saveUser, whose body runs the account rules"),
    "app/controllers/JobController.scala" -> (2, "a coarse gate: adminOf decides, by a real row or account reach"),
    "app/controllers/AccountJoinController.scala" -> (1, "a coarse gate: mayManage decides, by a real row or account reach")
  )

  /** Each action that takes account reach (CA17: a real Admin row, or a contest the grant reaches). */
  private val AccountReach: Map[String, (Int, String)] = Map(
    "app/controllers/Secured.scala" -> (2, "the permission's definition"),
    "app/controllers/UserController.scala" -> (11, "users page, search, adds, import, invitations, share and password links, edit"),
    "app/controllers/AccountChangeController.scala" -> (1, "the contest's change log"),
    "app/controllers/AccountJoinController.scala" -> (3, "account joins: preview, start, mayManage"),
    "app/controllers/DuplicateAccountsController.scala" -> (2, "duplicates: report, keep separate"),
    "app/controllers/AddFromContestController.scala" -> (2, "add people from another contest"),
    "app/controllers/JobController.scala" -> (1, "account-kind jobs (adminOf)")
  )

  /** The files where `.isRoot` or `is_root` may stay: actor checks, the root flag itself, display. */
  private val RootSites: Map[String, (Int, String)] = Map(
    "app/db/scalikejdbc/User.scala" -> (10, "the flag, its writes, isProtectedTarget, searchAccounts' rootShown (root only, CA21)"),
    "app/db/scalikejdbc/AccountJoinRequestJdbc.scala" -> (3, "join proposals: root accounts and live grants are root's"),
    "app/db/scalikejdbc/CoiMembers.scala" -> (1, "COI: root users count in every contest"),
    "app/db/scalikejdbc/SeriesAdminJdbc.scala" -> (1, "the granter (an actor): root's notices of series admins' grants"),
    "app/services/series/SeriesAdmins.scala" -> (1, "the actor"),
    "app/services/series/ReuseReport.scala" -> (1, "display of a runner"),
    "app/controllers/OAuthController.scala" -> (2, "log lines"),
    "app/controllers/OAuthChooserController.scala" -> (1, "a log line"),
    "app/controllers/RoundController.scala" -> (2, "the actor"),
    "app/controllers/UserController.scala" -> (7, "the Root field (root's own form); a root account is never invited; " +
      "the share-link pre-filter (ShareLinkService decides with isProtectedTarget)"),
    "app/services/UserService.scala" -> (7, "actors; a root account is never invited; placeholders; the add's own row lock"),
    "app/services/AccountJoin.scala" -> (2, "the actor"),
    "app/services/AccountRetirement.scala" -> (1, "the helper, used on the target with isProtectedTarget next to it"),
    "app/services/AccountSearch.scala" -> (2, "the actor; root accounts hidden from non-root (CA21 shows series admins)"),
    "app/services/AccountTokenService.scala" -> (1, "the inviter (an actor)"),
    "app/services/ShareLinkService.scala" -> (1, "the helper; a root account never gets an invitation, even from root"),
    "app/services/WikiTakenPanel.scala" -> (2, "the actor"),
    "app/services/diagnostics/ContestHealth.scala" -> (1, "the viewer"),
    "app/views/users.scala.html" -> (4, "display and hints; the POSTs check isProtectedTarget"),
    "app/views/rounds.scala.html" -> (4, "the viewer"),
    "app/views/editUser.scala.html" -> (1, "the Root field's state (root's form)"),
    "app/views/addFromContest.scala.html" -> (1, "the viewer"),
    "app/views/duplicateAccounts.scala.html" -> (1, "the viewer"),
    "app/views/accountJoinJob.scala.html" -> (1, "the viewer"),
    "app/views/logged.scala.html" -> (1, "the signed-in user"),
    "app/views/series/seriesHome.scala.html" -> (1, "the viewer")
  )

  "the session's series reach" should {

    "be added by withSeries only from Secured's session and LoginResults, never for API tokens or root's edit form" in {
      // calls only: a definition (`def withSeries(`, ContestsFilter's own) is none
      val callers = counts("""(?<![\w.])(?<!def )withSeries\(|Secured\.withSeries\(""".r).keySet
      (callers === Set("app/controllers/Secured.scala", "app/controllers/LoginResults.scala")) and
        (callers must not(contain("app/services/ApiTokenService.scala"))) and
        (callers must not(contain("app/controllers/UserController.scala")))
    }

    "be read only by Secured and JurorContests (User defines it)" in {
      (counts("""\.(seriesContests|derivedRoles|seriesGranted|inSeriesContest|derivedOptIn)\b""".r).keySet --
        Set("app/controllers/Secured.scala", "app/controllers/JurorContests.scala")) must beEmpty
    }

    "be found when read (the scan is not vacuous)" in {
      val pattern = """\.(seriesContests|derivedRoles|seriesGranted|inSeriesContest|derivedOptIn)\b""".r
      Seq("user.seriesContests.map(_.contestId)", "u.derivedRoles.get(1)", "user.seriesGranted.nonEmpty")
        .forall(pattern.findFirstIn(_).nonEmpty) must beTrue
    }
  }

  "seriesAccountContests" should {
    "be read only by the account rules" in {
      (counts("""\bseriesAccountContests\b""".r).keySet -- Set(
        "app/db/scalikejdbc/SeriesAdminJdbc.scala", "app/controllers/Secured.scala", "app/controllers/UserController.scala",
        "app/services/AccountSearch.scala", "app/services/ShareLinkService.scala", "app/services/AccountTokenService.scala",
        "app/services/AccountJoin.scala")) must beEmpty
    }
  }

  "the opt-ins" should {
    "be exactly the listed derived = true actions" in {
      counts("""derived = true""".r) === DerivedOptIns.view.mapValues(_._1).toMap
    }
    "be exactly the listed account-reach actions" in {
      counts("""accountReach = true|accountAdminPermission\(""".r) === AccountReach.view.mapValues(_._1).toMap
    }
  }

  "a grant waiting for root's confirmation" should {

    /** The writes and lookups of unrevoked grants in SeriesAdminJdbc, which may read `revoked_at IS
      * NULL` alone: the live condition's definition, the key's row (`unrevoked`), revoke, markToConfirm,
      * confirm, setFromYear, a join's `unrevokedOf` and `moveTo`. Every read of live grants goes
      * through `liveGrantCondition` (in `liveJoin`).
      */
    val UnrevokedSites = 8

    "count exactly as a revoked one: every read of live grants takes liveGrantCondition" in {
      val sql = counts("""(?:FROM|JOIN)\s+series_admin\b""".r).keySet
      val condition = counts("""liveGrantCondition\(""".r)
      (sql === Set("app/db/scalikejdbc/SeriesAdminJdbc.scala", "app/db/scalikejdbc/CoiMembers.scala",
        "app/db/scalikejdbc/AccountJoinRequestJdbc.scala")) and
        // the other readers use the shared condition, never a bare revoked_at test
        (condition.keySet === sql) and
        (counts("""sa\.revoked_at IS NULL""".r).keySet -- Set("app/db/scalikejdbc/SeriesAdminJdbc.scala") must beEmpty) and
        (counts("""revoked_at IS NULL""".r).get("app/db/scalikejdbc/SeriesAdminJdbc.scala") must beSome(UnrevokedSites)) and
        (SourceScan.files("app/db/scalikejdbc", ".scala").find(_.path.endsWith("SeriesAdminJdbc.scala")).exists(s =>
          """private val liveJoin\s*=\s*sqls\"\"\"[^"]*liveGrantCondition""".r.findFirstIn(s.text).nonEmpty) must beTrue)
    }
  }

  "target root checks" should {
    "be User.isProtectedTarget: the isRoot and is_root sites left are the listed ones" in {
      counts("""\.isRoot\b|\bis_root\b""".r) === RootSites.view.mapValues(_._1).toMap
    }
  }
}
