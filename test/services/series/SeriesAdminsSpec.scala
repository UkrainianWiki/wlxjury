package services.series

import db.scalikejdbc.{AccountChangeJdbc, CoiMembers, ContestJuryJdbc, SeriesAdminJdbc, SharedTestDb, TestDb, User,
  UserContestJdbc}
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

/** Series admins (contest series plan §3.3, §6; CY-2): who may be granted and by whom (CA19, CA22),
  * root-only re-grant and revoke, the chained revoke along `granted_by` on a three-level chain,
  * the start year (CA20: its default, the granter's bounds, root's later change), the contests a
  * grant reaches for the account rules (CA17), protection, COI, and what a join or a retirement
  * does to grants. Committed rows: the service runs its own transactions.
  */
class SeriesAdminsSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val thisYear = java.time.Year.now().getValue

  private def root: User =
    User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))

  private def member(name: String, contest: ContestJury, role: String = "organizer", wiki: Option[String] = None): User =
    User.create(User(name, s"$name${System.nanoTime()}@example.org", None, Set(role), Some("hash"), contest.id,
      wikiAccount = wiki))

  private def join(user: User, contest: ContestJury, role: String): Unit =
    UserContestJdbc.createMembership(user.getId, contest.getId, role)

  /** A series of WLM Nepal with contests of the years given, assigned by root. */
  private def seriesWith(actor: User, years: Int*): (Long, Seq[ContestJury]) = {
    val s = ContestSeries.createSeriesTx(s"Wiki Loves Monuments in Nepal ${System.nanoTime()}", Some("wlm"), Some("Nepal"),
      None, actor.getId).toOption.get
    val contests = years.map { y =>
      val c = ContestJuryJdbc.create(None, "Wiki Loves Monuments", y, "Nepal")
      ContestSeries.setSeries(c.getId, Some(s), actor.getId, None) must beRight
      c
    }
    s -> contests
  }

  private def grantId(user: User, seriesId: Long): Long = SeriesAdminJdbc.liveGrant(user.getId, seriesId).get.id

  "a grant" should {

    "be root's or a series admin's of the series, to an active member, never to a non-member, deleted, root, self or existing grantee" in {
      val r = root
      val (s, Seq(c25, c26)) = seriesWith(r, thisYear - 1, thisYear)
      val (other, Seq(o26)) = seriesWith(r, thisYear)
      val a = member("a", c26)
      val b = member("b", c25, "jury")
      val outsider = member("outsider", o26)
      val inactive = member("inactive", c26, "no")
      val deleted = member("deleted", c26)
      DB.autoCommit(implicit s2 => sql"UPDATE users SET deleted_at = NOW() WHERE id = ${deleted.getId}".update())
      val otherRoot = root
      join(otherRoot, c26, "admin")

      (SeriesAdmins.grant(r, s, a.getId, None, None) must beRight) and
        // a series admin adds within their series (CA19), any depth (CA22)
        (SeriesAdmins.grant(a, s, b.getId, None, None) must beRight) and
        (SeriesAdmins.grant(r, s, outsider.getId, None, None) === Left("series.admin.refused.not.member")) and
        (SeriesAdmins.grant(r, s, inactive.getId, None, None) === Left("series.admin.refused.not.member")) and
        (SeriesAdmins.grant(r, s, deleted.getId, None, None) === Left("series.admin.refused.account")) and
        (SeriesAdmins.grant(r, s, otherRoot.getId, None, None) === Left("series.admin.refused.root")) and
        (SeriesAdmins.grant(a, s, a.getId, None, None) === Left("series.admin.refused.self")) and
        (SeriesAdmins.grant(r, s, a.getId, None, None) === Left("series.admin.refused.existing")) and
        // a series admin of another series, or a plain member, may not
        (SeriesAdmins.grant(a, other, outsider.getId, None, None) === Left("series.admin.refused.not.admin")) and
        (SeriesAdmins.grant(inactive, s, member("c", c26).getId, None, None) === Left("series.admin.refused.not.admin")) and
        (SeriesAdmins.grant(r, 999999L, a.getId, None, None) === Left("series.not.found")) and {
          // each grant logged on the grantee, with the series in its comment
          val rows = AccountChangeJdbc.findByUser(b.getId, None, 10)
          (rows.map(_.field) === Seq(AccountChangeJdbc.SeriesAdminGranted)) and
            (rows.flatMap(_.comment) === Seq(AccountChangeJdbc.seriesComment(s))) and (rows.map(_.actorId) === Seq(a.getId))
        }
    }

    "be refused in a retired series, and its grants give no access or protection" in {
      val r = root
      val (s, Seq(c26)) = seriesWith(r, thisYear)
      val a = member("a", c26)
      val b = member("b", c26)
      SeriesAdmins.grant(r, s, a.getId, None, None) must beRight
      ContestSeries.retireSeries(s, r.getId, None) must beRight
      (SeriesAdmins.grant(r, s, b.getId, None, None) === Left("series.admin.refused.retired")) and
        (SeriesAdminJdbc.liveByUser(a.getId) must beEmpty) and
        (SeriesAdminJdbc.seriesContests(a.getId) must beEmpty) and
        (User.isProtectedTarget(a) must beFalse) and {
          // un-retired, its old grants don't come back by themselves: root grants again (§3.1)
          ContestSeries.unretireSeries(s, r.getId, None) must beRight
          (SeriesAdminJdbc.liveByUser(a.getId) must beEmpty) and
            (SeriesAdmins.grant(r, s, a.getId, None, None) must beRight)
        }
    }

    "be granted again after root revoked it by root only" in {
      val r = root
      val (s, Seq(c26)) = seriesWith(r, thisYear)
      val a = member("a", c26)
      val b = member("b", c26)
      SeriesAdmins.grant(r, s, a.getId, None, None) must beRight
      SeriesAdmins.grant(a, s, b.getId, None, None) must beRight
      SeriesAdmins.revoke(r, grantId(b, s), chain = false) must beRight
      (SeriesAdmins.grant(a, s, b.getId, None, None) === Left("series.admin.refused.root.revoked")) and
        (SeriesAdmins.grant(r, s, b.getId, None, None) must beRight)
    }

    "be revoked by root only, and a series admin changes no start year" in {
      val r = root
      val (s, Seq(c26)) = seriesWith(r, thisYear)
      val a = member("a", c26)
      val b = member("b", c26)
      SeriesAdmins.grant(r, s, a.getId, None, None) must beRight
      SeriesAdmins.grant(a, s, b.getId, None, None) must beRight
      (SeriesAdmins.revoke(a, grantId(b, s), chain = false) === Left("series.admin.refused.root.only")) and
        (SeriesAdmins.setFromYear(a, grantId(b, s), thisYear) === Left("series.admin.refused.root.only")) and
        (SeriesAdminJdbc.liveGrant(b.getId, s) must beSome)
    }
  }

  "the chained revoke" should {

    def chain(): (User, Long, User, User, User) = {
      val r = root
      val (s, Seq(c26)) = seriesWith(r, thisYear)
      val (a, b, c) = (member("a", c26), member("b", c26), member("c", c26))
      SeriesAdmins.grant(r, s, a.getId, None, None) must beRight
      SeriesAdmins.grant(a, s, b.getId, None, None) must beRight
      SeriesAdmins.grant(b, s, c.getId, None, None) must beRight
      (r, s, a, b, c)
    }

    "add at any depth, notify root of each addition by a series admin, and remove the whole branch from the top" in {
      val (r, s, a, b, c) = chain()
      val notices = SeriesAdminJdbc.recentBySeriesAdmins(30, 50).map(g => g.grantedBy -> g.userId)
      val revoked = SeriesAdmins.revoke(r, grantId(a, s), chain = true)
      (notices must containTheSameElementsAs(Seq(a.getId -> b.getId, b.getId -> c.getId))) and
        (revoked.map(_.map(_.userId).toSet) === Right(Set(a.getId, b.getId, c.getId))) and
        (SeriesAdminJdbc.liveBySeries(s) must beEmpty) and
        (SeriesAdminJdbc.findBySeries(s).forall(_.revokedByRoot) must beTrue)
    }

    "keep the top when the middle is revoked, with the middle's branch" in {
      val (r, s, a, b, c) = chain()
      SeriesAdmins.revoke(r, grantId(b, s), chain = true).map(_.map(_.userId).toSet) === Right(Set(b.getId, c.getId))
      SeriesAdminJdbc.liveBySeries(s).map(_.userId) === Seq(a.getId)
    }

    "keep the rest when revoking one without the chain, and still reach a branch whose middle went first" in {
      val (r, s, a, b, c) = chain()
      SeriesAdmins.revoke(r, grantId(b, s), chain = false).map(_.map(_.userId)) must beRight(Seq(b.getId))
      SeriesAdminJdbc.liveBySeries(s).map(_.userId).toSet === Set(a.getId, c.getId)
      SeriesAdmins.revoke(r, grantId(a, s), chain = true).map(_.map(_.userId).toSet) === Right(Set(a.getId, c.getId))
      SeriesAdminJdbc.liveBySeries(s) must beEmpty
    }
  }

  "the start year" should {

    "default to the earliest real Admin year in the series, capped at last year; else last year" in {
      val r = root
      val (s, Seq(c20, c22, c24, cNow)) = seriesWith(r, 2020, 2022, 2024, thisYear)
      val a = member("a", cNow)
      join(a, c22, "admin")
      join(a, c24, "admin")
      join(a, c20, "organizer") // Organizer rows don't count
      val recent = member("recent", cNow, "admin")
      val none = member("none", cNow)
      val retiredAdmin = member("retired", cNow)
      join(retiredAdmin, c20, "admin")
      ContestSeries.retireContest(c20.getId, r.getId, None) must beRight
      (SeriesAdminJdbc.defaultFromYear(a.getId, s, thisYear) === 2022) and
        (SeriesAdminJdbc.defaultFromYear(recent.getId, s, thisYear) === thisYear - 1) and
        (SeriesAdminJdbc.defaultFromYear(none.getId, s, thisYear) === thisYear - 1) and
        (SeriesAdminJdbc.defaultFromYear(retiredAdmin.getId, s, thisYear) === thisYear - 1)
    }

    "keep a series admin's grantee within their own start year and next year, the default raised; root any year" in {
      val r = root
      val (s, Seq(c20, cNow)) = seriesWith(r, 2020, thisYear)
      val a = member("a", cNow)
      val b = member("b", cNow)
      join(b, c20, "admin")
      SeriesAdmins.grant(r, s, a.getId, Some(thisYear), None) must beRight
      (SeriesAdmins.defaultYear(a, s, b.getId, thisYear) === thisYear) and
        (SeriesAdmins.grant(a, s, b.getId, Some(thisYear - 1), None) === Left("series.admin.refused.year")) and
        (SeriesAdmins.grant(a, s, b.getId, Some(thisYear + 2), None) === Left("series.admin.refused.year")) and
        (SeriesAdmins.grant(a, s, b.getId, None, None).map(_.fromYear) must beRight(thisYear)) and {
          SeriesAdmins.revoke(r, grantId(b, s), chain = false) must beRight
          SeriesAdmins.grant(r, s, b.getId, Some(2000), None).map(_.fromYear) must beRight(2000)
        }
    }

    "be changed by root only, logged with old and new values, and change the reach at once" in {
      val r = root
      val (s, Seq(c20, cNow)) = seriesWith(r, 2020, thisYear)
      val a = member("a", cNow)
      SeriesAdmins.grant(r, s, a.getId, None, None) must beRight
      val before = SeriesAdminJdbc.seriesAccountContests(a.getId)
      SeriesAdmins.setFromYear(r, grantId(a, s), 2020) must beRight
      val row = AccountChangeJdbc.findByUser(a.getId, None, 10).find(_.field == AccountChangeJdbc.SeriesAdminFromYear)
      (before === Set(cNow.getId)) and
        (SeriesAdminJdbc.seriesAccountContests(a.getId) === Set(c20.getId, cNow.getId)) and
        (row.map(r => (r.oldValue, r.newValue)) must beSome((Some((thisYear - 1).toString), Some("2020"))))
    }
  }

  "the contests a grant reaches (seriesAccountContests)" should {

    "be the series' live contests from the start year on, before the grant too, and none below it, retired, revoked or as a juror" in {
      val r = root
      val (s, Seq(c19, cLast, cNow)) = seriesWith(r, 2019, thisYear - 1, thisYear)
      val (_, Seq(elsewhere)) = seriesWith(r, thisYear)
      val a = member("a", cNow)
      SeriesAdmins.grant(r, s, a.getId, None, None) must beRight
      join(a, elsewhere, "organizer")
      val reach1 = SeriesAdminJdbc.seriesAccountContests(a.getId)
      val roles1 = SeriesAdminJdbc.seriesContests(a.getId).map(sc => sc.contestId -> sc.role).toMap
      // a real Juror row: the series doesn't apply there (CA7)
      join(a, cLast, "jury")
      val reach2 = SeriesAdminJdbc.seriesAccountContests(a.getId)
      ContestSeries.retireContest(cNow.getId, r.getId, None) must beRight
      val reach3 = SeriesAdminJdbc.seriesAccountContests(a.getId)
      ContestSeries.unretireContest(cNow.getId, r.getId, None) must beRight
      SeriesAdmins.revoke(r, grantId(a, s), chain = false) must beRight
      (reach1 === Set(cLast.getId, cNow.getId)) and
        (roles1 === Map(c19.getId -> "organizer", cLast.getId -> "admin", cNow.getId -> "admin")) and
        (reach2 === Set(cNow.getId)) and (reach3 must beEmpty) and
        (SeriesAdminJdbc.seriesAccountContests(a.getId) must beEmpty)
    }
  }

  "protection and COI" should {

    "protect a live grantee, root's only, and count it for COI in the series' contests, not of a retired series" in {
      val r = root
      val (s, Seq(c26)) = seriesWith(r, thisYear)
      val (_, Seq(other)) = seriesWith(r, thisYear)
      val a = member("a", other, wiki = Some("Series Admin A"))
      join(a, c26, "organizer")
      SeriesAdmins.grant(r, s, a.getId, None, None) must beRight
      UserContestJdbc.delete(a.getId, c26.getId) // no real row in the contest any more
      val coi = CoiMembers.forContest(c26.getId)
      val protectedNow = User.isProtectedTarget(a)
      val seriesProtected = User.seriesProtected(a.getId, c26.getId) -> User.seriesProtected(a.getId, other.getId)
      ContestSeries.retireSeries(s, r.getId, None) must beRight
      (coi.get("Series Admin A").map(_.role) must beSome(CoiMembers.SeriesAdminRole)) and
        (protectedNow must beTrue) and (seriesProtected === (true -> false)) and
        (CoiMembers.forContest(c26.getId).get("Series Admin A") must beNone) and
        (User.isProtectedTarget(a) must beFalse)
    }
  }

  "a join and a retirement" should {

    "move the joined account's grant with its start year, revoke a clashing one, and revoke a retired account's" in {
      val r = root
      val (s, Seq(c20, cNow)) = seriesWith(r, 2020, thisYear)
      val (s2, Seq(d26)) = seriesWith(r, thisYear)
      val kept = member("kept", cNow)
      val joined = member("joined", cNow)
      join(joined, d26, "organizer")
      join(kept, d26, "organizer")
      val c = member("c", cNow)
      SeriesAdmins.grant(r, s, joined.getId, Some(2020), None) must beRight
      SeriesAdmins.grant(r, s2, joined.getId, None, None) must beRight
      SeriesAdmins.grant(r, s2, kept.getId, None, None) must beRight
      SeriesAdmins.grant(joined, s, c.getId, Some(2020), None) must beRight
      val (moved, clashed) = DB.localTx(implicit tx => SeriesAdmins.onJoin(r, kept.getId, joined.getId))
      val keptGrants = SeriesAdminJdbc.liveByUser(kept.getId).map(g => g.seriesId -> g.adminFromYear).toMap
      // the grants the joined account added follow it: the chained revoke from the kept one reaches them
      val chained = SeriesAdmins.revoke(r, grantId(kept, s), chain = true).map(_.map(_.userId).toSet)
      val retiredCount = DB.localTx(implicit tx => SeriesAdmins.onRetire(r, kept.getId))
      ((moved, clashed) === (1, 1)) and
        (keptGrants === Map(s -> 2020, s2 -> (thisYear - 1))) and
        (SeriesAdminJdbc.liveByUser(joined.getId) must beEmpty) and
        (chained === Right(Set(kept.getId, c.getId))) and
        (retiredCount === 1) and (SeriesAdminJdbc.liveByUser(kept.getId) must beEmpty) and
        (c20.getId must be_>(0L))
    }
  }
}
