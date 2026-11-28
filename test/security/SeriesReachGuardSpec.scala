package security

import org.specs2.mutable.Specification

/** The guard specs of series admins (contest series plan §3.3, §8; CY-2), over all of `app/`:
  *
  *  - `withSeries` (a session's series reach) is called only from `Secured.sessionUser` and
  *    `LoginResults`: never for an API token (`ApiTokenService`) or root's edit form
  *    (`UserController.editableUser`), which keep `Secured.inCurrentContest`, raw;
  *  - the session's series reach (`seriesContests`, `derivedRoles`, `seriesGranted`, and what reads
  *    them) is read only by `Secured`, `JurorContests`, the series controller and GraphQL's context
  *    (the last two through Secured's helpers: `seriesReachOf`, `seriesContestIds`), `User` defining it;
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
    "app/controllers/RoundController.scala" -> (12, "rounds: list, create, edit, start, stop, statistics, round set, cut preview; " +
      "Find the matching files again (saveRound's reinstating path, as the round form)"),
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
    "app/controllers/AccountJoinController.scala" -> (1, "a coarse gate: mayManage decides, by a real row or account reach"),
    "app/graphql/GraphQLContext.scala" -> (1, "GraphQL's contests and rounds, as the round pages (a session's only: " +
      "a token has no series reach)")
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
        "app/services/AccountJoin.scala",
        // a series admin grants only members of the contests their grant reaches (review of #140, L5)
        "app/services/series/SeriesAdmins.scala")) must beEmpty
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
      * NULL` alone: the two conditions' definitions, the key's row (`unrevoked`), revoke,
      * markToConfirm, confirm, setFromYear, a join's `unrevokedOf` and `moveTo`, and the links a revoke
      * revokes (`revokeLinksIn`, on account_token).
      */
    val UnrevokedSites = 10

    /** SeriesAdminJdbc's readers of live grants (access: through `liveJoin`, whose condition is
      * `liveGrantCondition`) and of held grants (protection: `heldGrantCondition`).
      */
    val LiveReaders = Seq("liveBySeries", "liveInSeries", "liveByUser", "seriesContests", "liveForContest")
    val HeldReaders = Seq("hasHeldGrant", "withHeldGrant", "holdsGrantOnContest")

    /** The SQL literals (triple-quoted) of `text` that name the `series_admin` table, any case. */
    def queries(text: String): Seq[String] =
      "(?s)\"\"\"(.*?)\"\"\"".r.findAllMatchIn(text).map(_.group(1))
        .filter(q => "(?i)\\b(?:from|join|update)\\s+series_admin\\b".r.findFirstIn(q).nonEmpty).toSeq

    /** Each `def` of SeriesAdminJdbc with its body (up to the next `def`). */
    def methods(text: String): Map[String, String] =
      "(?s)\\bdef (\\w+)(.*?)(?=\\n\\s*(?:/\\*\\*|def |private |val |\\}\\s*$))".r.findAllMatchIn(text)
        .map(m => m.group(1) -> m.group(2)).toMap

    "count exactly as a revoked one for access: every query reading grants elsewhere takes a shared condition" in {
      val sql = counts("""(?i)(?:from|join)\s+series_admin\b""".r).keySet
      val others = SourceScan.files("app", ".scala").filter(f => sql(f.path.replaceFirst("^.*?(app/)", "$1")) &&
        !f.path.endsWith("SeriesAdminJdbc.scala"))
      (sql === Set("app/db/scalikejdbc/SeriesAdminJdbc.scala", "app/db/scalikejdbc/CoiMembers.scala",
        "app/db/scalikejdbc/AccountJoinRequestJdbc.scala")) and
        // per query: COI reads access (live grants), join proposals read protection (held grants)
        forall(others.flatMap(f => queries(f.text).map(f.path -> _))) { case (file, q) =>
          (file -> ("(liveGrantCondition|heldGrantCondition)\\(".r.findFirstIn(q).nonEmpty)) === (file -> true)
        } and
        (counts("""(?i)sa\.revoked_at\s+IS\s+NULL""".r).keySet -- Set("app/db/scalikejdbc/SeriesAdminJdbc.scala") must beEmpty) and
        (counts("""(?i)revoked_at\s+IS\s+NULL""".r).get("app/db/scalikejdbc/SeriesAdminJdbc.scala") must beSome(UnrevokedSites))
    }

    "read live grants in SeriesAdminJdbc through liveJoin, and held grants through heldGrantCondition" in {
      val text = SourceScan.files("app/db/scalikejdbc", ".scala").find(_.path.endsWith("SeriesAdminJdbc.scala")).map(_.text)
        .getOrElse("")
      val defs = methods(text)
      ("(?s)private val liveJoin\\s*=\\s*sqls[^\\n]*\\n?[^\\n]*liveGrantCondition".r.findFirstIn(text) must beSome) and
        forall(LiveReaders)(m => (m -> defs.get(m).exists(_.contains("$liveJoin"))) === (m -> true)) and
        forall(HeldReaders)(m => (m -> defs.get(m).exists(_.contains("heldGrantCondition("))) === (m -> true))
    }
  }

  "target root checks" should {
    "be User.isProtectedTarget: the isRoot and is_root sites left are the listed ones" in {
      counts("""\.isRoot\b|\bis_root\b""".r) === RootSites.view.mapValues(_._1).toMap
    }
  }

  /** The places that show other jurors' votes of a round, or tell whether they may, each behind
    * `GalleryService.othersVotesVisible` or its batch form `othersVotesVisibleIn` (the conflict-of-interest rule, the maintainer's decision of
    * 2026-11-24: a juror of a round sees only their own votes in it while it is open, whatever their role).
    */
  private val OthersVotesSites: Map[String, (Int, String)] = Map(
    "app/services/GalleryService.scala" -> (4, "the definitions (othersVotesVisible delegating to its batch form " +
      "othersVotesVisibleIn: one rule); viewAs: every gallery view (gallery, by rate, file list, " +
      "CSV, comments report and its CSV, the JSON endpoints) and the large view as another juror or all (0)"),
    "app/graphql/GraphQLSchema.scala" -> (1, "GraphQL's Round.othersVotesVisible flag, for a contest's rounds " +
      "(roundImages and image apply the rule through GalleryService.viewAs)"),
    "app/graphql/RoundQueries.scala" -> (1, "GraphQL's roundStat: other jurors' selections, rejections and the " +
      "totals by vote, hidden as in roundStatTable"),
    "app/controllers/LargeViewController.scala" -> (1, "the large view's every-juror votes panel (juryStat)"),
    "app/controllers/MonumentController.scala" -> (1, "a monument's files in a round: own votes only"),
    "app/controllers/AuthorsController.scala" -> (2, "a round's authors with every juror's votes, and the accessible rounds in its Track menu"),
    "app/views/roundStatTable.scala.html" -> (1, "the statistics: other jurors' selections, rejections, lists, totals"),
    "app/views/navbar.scala.html" -> (1, "the organisers' bar: the juror menu and all the jurors' views"),
    "app/views/roundStat.scala.html" -> (1, "the statistics' comments report link: the viewer's own list in an open round they judge"),
    "app/controllers/RoundController.scala" -> (2, "the statistics' entries of the round stopped, started again or closed by " +
      "a juror of it: none for a juror of the open round; the round form's cut control's link to the previous round's " +
      "rating view (all the jurors' votes): none for a juror of that open round (withCutLinks)")
  )

  /** The queries that read every juror's votes of a round together outside [[controllers.RoundList]]
    * (whose all-jurors lists come from `GalleryService.viewAs`, behind the rule), and the only files
    * that may name each: its definition, and callers that show the result behind `othersVotesVisible`
    * or not at all (F2 of the conflict-of-interest review). A new caller is checked against the rule
    * and listed here.
    */
  private val AggregateVoteReaders: Seq[(String, Set[String], String)] = Seq(
    ("""\b(getRoundStat|RoundService\.roundStat)\(""",
      Set("app/services/RoundService.scala", "app/controllers/RoundController.scala", "app/graphql/RoundQueries.scala"),
      "each juror's tallies and the totals by vote: the round's stat table only (the edit page's panel and the " +
        "statistics), which hides other jurors' columns and the totals from a juror of the open round (roundStatTable); " +
        "GraphQL's roundStat (RoundQueries), which hides the same behind GalleryService.othersVotesVisible"),
    // a member import of the reader, after which a plain `roundStat(` call names it (CodeRabbit on #182): only
    // RoundService's class, which imports its companion's members
    ("""\bimport\s+(services\.)?RoundService\.([_*]|roundStat\b|\{[^}]*\broundStat\b[^}]*\})""",
      Set("app/services/RoundService.scala"),
      "a member import of RoundService.roundStat (or all of RoundService's members), which a plain roundStat( call " +
        "then names: RoundService's class only, for getRoundStat"),
    ("""\broundUserStat\(""", Set("app/db/RoundRepo.scala", "app/db/scalikejdbc/Round.scala", "app/services/RoundService.scala"),
      "every juror's count per rate: getRoundStat only"),
    ("""\bimageCountsByRate\(""", Set("app/db/RoundRepo.scala", "app/db/scalikejdbc/Round.scala", "app/services/RoundService.scala"),
      "a binary round's files with a vote of each rate: getRoundStat only"),
    ("""\bselectedImageCount\(""", Set("app/db/RoundRepo.scala", "app/db/scalikejdbc/Round.scala"),
      "a binary round's selected files: no caller"),
    ("""AuthorStats\.ofRound\(""", Set("app/controllers/AuthorsController.scala"),
      "a round's authors with every juror's votes: refused to a juror of the open round"),
    ("""\bbyRoundAndImageWithJury\(""", Set("app/db/scalikejdbc/VoteJdbc.scala", "app/controllers/LargeViewController.scala"),
      "a file's every juror's votes: the large view's panel, behind the rule"),
    ("""\bCriteriaRate\.averages\(""", Set("app/controllers/Csv.scala", "app/controllers/LargeViewController.scala",
      "app/org/intracer/wmua/CriteriaResults.scala"),
      "a file's criteria averages of every juror (no juror given): the CSV (all-jurors list, behind viewAs), the large view " +
        "(behind viewAs) and CriteriaResults.of (a gallery's tabs, behind viewAs); a new page needs the rule, not canViewOrgInfo alone"),
    ("""\bImageJdbc\.rateDistribution\(""", Set("app/controllers/GalleryController.scala", "app/services/GalleryService.scala"),
      "a round's files per rate of a juror, every juror's with asUserId 0: the gallery's tabs (asUserId from viewAs) and " +
        "GalleryService.rateDistribution; a new page needs the rule, not canViewOrgInfo alone"),
    ("""RoundImageQuery\.of\(""", Set("app/controllers/RoundList.scala"),
      "a list's files with round_image's sums: RoundList only")
  )

  "the conflict-of-interest rule" should {
    "guard every place that shows other jurors' votes" in {
      counts("""othersVotesVisible(In)?\(""".r) === OthersVotesSites.view.mapValues(_._1).toMap
    }

    "keep the queries of every juror's votes together to their listed callers, each still found there" in {
      forall(AggregateVoteReaders) { case (pattern, allowed, _) =>
        (pattern -> counts(pattern.r).keySet) === (pattern -> allowed)
      }
    }

    "catch a member import of the aggregate reader, not an import of RoundService itself" in {
      val importPattern = AggregateVoteReaders.map(_._1).find(_.contains("import")).get.r
      val caught = Seq("import services.RoundService.roundStat", "import services.RoundService.{getX, roundStat}",
        "import services.RoundService.{roundStat => stat}", "import services.RoundService._", "import RoundService._",
        "import services.RoundService.*")
      val passed = Seq("import services.RoundService", "import services.RoundService.roundStatX",
        "import services.RoundServiceHelpers._")
      (caught.filterNot(importPattern.findFirstIn(_).isDefined) must beEmpty) and
        (passed.filter(importPattern.findFirstIn(_).isDefined) must beEmpty)
    }
  }
}
