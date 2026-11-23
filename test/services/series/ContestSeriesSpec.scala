package services.series

import db.scalikejdbc.{ContestChangeJdbc, ContestJuryJdbc, Round, SeriesJdbc, SharedTestDb, TestDb, User, UserContestJdbc}
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.diagnostics.HealthFixtures
import services.imports.ImportState

/** Root's work on contest series (contest series plan §3.1, §3.5; CY-1): the key of one live
  * contest per series and year, the derived previous year, assigning, moving and unassigning with
  * the identity check, retiring and un-retiring contests and series, the bulk assignment as one
  * batch and its undo, and the change log. Committed rows: the service runs its own transactions.
  */
class ContestSeriesSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def root: User =
    User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))

  private def contest(name: String, year: Int, country: String): ContestJury =
    ContestJuryJdbc.create(None, name, year, country)

  private def reload(c: ContestJury): ContestJury = ContestJuryJdbc.findById(c.getId).get

  private def series(name: String, kind: Option[String] = Some("wlm"), country: Option[String] = Some("Nepal"))(implicit
      actor: User): Long =
    ContestSeries.createSeriesTx(name, kind, country, None, actor.getId).toOption.get

  "a series" should {

    "keep one live contest per series and year, derive the previous year, and log each assignment" in {
      implicit val actor: User = root
      val s = series("Wiki Loves Monuments in Nepal")
      val c24 = contest("Wiki Loves Monuments", 2024, "Nepal")
      val c25 = contest("Wiki Loves Monuments", 2025, "Nepal")
      val c26 = contest("Wiki Loves Monuments", 2026, "Nepal")
      val dup = contest("Wiki Loves Monuments", 2026, "Nepal")
      Seq(c24, c25, c26).map(c => ContestSeries.setSeries(c.getId, Some(s), actor.getId, None)) must contain(be_==(Right(()))).forall
      ContestSeries.setSeries(dup.getId, Some(s), actor.getId, None) === Left("series.year.taken")
      ContestSeries.previous(reload(c26)).map(_.getId) must beSome(c25.getId)
      ContestSeries.previous(reload(c24)) must beNone
      // the database key holds by itself too
      DB.autoCommit(implicit s2 => scala.util.Try(sql"UPDATE contest_jury SET series_id = $s WHERE id = ${dup.getId}".update()))
        .isFailure must beTrue
      ContestChangeJdbc.findBySeries(s, 10).map(_.kind).count(_ == ContestChangeJdbc.SeriesAssigned) === 3
      ContestChangeJdbc.findByContest(c26.getId, 10).map(_.actorId) === Seq(actor.getId)
    }

    "skip a retired contest for the previous year and the key; refuse un-retiring into a taken year" in {
      implicit val actor: User = root
      val s = series("Wiki Loves Monuments in Nepal")
      val c24 = contest("Wiki Loves Monuments", 2024, "Nepal")
      val c25 = contest("Wiki Loves Monuments", 2025, "Nepal")
      val c26 = contest("Wiki Loves Monuments", 2026, "Nepal")
      Seq(c24, c25, c26).foreach(c => ContestSeries.setSeries(c.getId, Some(s), actor.getId, None))
      ContestSeries.retireContest(c25.getId, actor.getId, Some("reused")) === Right(())
      ContestSeries.previous(reload(c26)).map(_.getId) must beSome(c24.getId)
      // the retired 2025 doesn't hold the year: another 2025 contest may join
      val again = contest("Wiki Loves Monuments", 2025, "Nepal")
      ContestSeries.setSeries(again.getId, Some(s), actor.getId, None) === Right(())
      ContestSeries.unretireContest(c25.getId, actor.getId, None) === Left("series.year.taken")
      ContestSeries.retireContest(c25.getId, actor.getId, None) === Left("contest.already.retired")
      ContestChangeJdbc.findByContest(c25.getId, 10).find(_.kind == ContestChangeJdbc.Retired).flatMap(_.comment) must beSome("reused")
    }

    "refuse a contest of another type or country, a retired series, and an unchanged series; move and unassign" in {
      implicit val actor: User = root
      val nepal = series("Wiki Loves Monuments in Nepal")
      val other = series("WLM Nepal (second)", Some("wlm"), Some("Nepal"))
      val wle = contest("Wiki Loves Earth", 2025, "Nepal")
      val armenia = contest("Wiki Loves Monuments", 2025, "Armenia")
      val c = contest("Wiki Loves Monuments", 2025, "Nepal")
      ContestSeries.setSeries(wle.getId, Some(nepal), actor.getId, None) === Left("series.identity.mismatch")
      ContestSeries.setSeries(armenia.getId, Some(nepal), actor.getId, None) === Left("series.identity.mismatch")
      ContestSeries.setSeries(c.getId, Some(nepal), actor.getId, None) === Right(())
      ContestSeries.setSeries(c.getId, Some(nepal), actor.getId, None) === Left("series.unchanged")
      ContestSeries.setSeries(c.getId, Some(other), actor.getId, Some("moved")) === Right(())
      reload(c).seriesId must beSome(other)
      ContestSeries.retireSeries(nepal, actor.getId, None) === Right(())
      ContestSeries.setSeries(c.getId, Some(nepal), actor.getId, None) === Left("series.retired.closed")
      ContestSeries.setSeries(c.getId, None, actor.getId, None) === Right(())
      reload(c).seriesId must beNone
      ContestChangeJdbc.findByContest(c.getId, 10).map(_.kind) ===
        Seq(ContestChangeJdbc.SeriesUnassigned, ContestChangeJdbc.SeriesChanged, ContestChangeJdbc.SeriesAssigned)
      // a series of no type or country takes any contest root assigns to it
      val special = series("Ukraine, Animals", None, None)
      ContestSeries.setSeries(wle.getId, Some(special), actor.getId, None) === Right(())
    }

    "let root assign a contest whose type or country isn't recognised, logged so" in {
      implicit val actor: User = root
      val nepal = series("Wiki Loves Monuments in Nepal")
      val unread = Seq(contest("WLM", 2021, "Nepal"), contest("Wiki Loves Monuments", 2022, "Непал"),
        contest("Photo contest", 2023, "Nepal"))
      unread.map(c => ContestSeries.setSeries(c.getId, Some(nepal), actor.getId, None)) === Seq(Right(()), Right(()), Right(()))
      unread.map(c => ContestChangeJdbc.findByContest(c.getId, 1).head.detail.value.get("identity").map(_.as[String])) ===
        Seq(None, Some(ContestSeries.UnrecognisedIdentity), Some(ContestSeries.UnrecognisedIdentity))
    }

    "keep one live series per name in the database too" in {
      implicit val actor: User = root
      series("Wiki Loves Monuments in Nepal")
      DB.localTx(implicit s => scala.util.Try(SeriesJdbc.create("WIKI LOVES MONUMENTS IN NEPAL", None, None, None, actor.getId)))
        .isFailure must beTrue
    }

    "refuse a duplicate live name; retire, un-retire and rename a series, logged" in {
      implicit val actor: User = root
      val s = series("Wiki Loves Monuments in Nepal")
      ContestSeries.createSeriesTx("wiki loves monuments in nepal", Some("wlm"), None, None, actor.getId) === Left("series.name.taken")
      ContestSeries.createSeriesTx("   ", None, None, None, actor.getId) === Left("series.name.invalid")
      ContestSeries.rename(s, "WLM in Nepal", actor.getId) === Right(())
      ContestSeries.retireSeries(s, actor.getId, None) === Right(())
      ContestSeries.retireSeries(s, actor.getId, None) === Left("series.already.retired")
      SeriesJdbc.findById(s).exists(_.retired) must beTrue
      ContestSeries.unretireSeries(s, actor.getId, None) === Right(())
      ContestChangeJdbc.findBySeries(s, 10).filter(_.contestId.isEmpty).map(_.kind) === Seq(ContestChangeJdbc.Unretired,
        ContestChangeJdbc.Retired, ContestChangeJdbc.Identity, ContestChangeJdbc.Created)
    }

    "refuse retiring a contest while a round is open, paused (frozen while open) or being created" in {
      implicit val actor: User = root
      val c = HealthFixtures.contestWithFiles("Wiki Loves Monuments", 2025, "Nepal", None, Seq(1L))
      val open = HealthFixtures.round(c, 1, "Open")
      ContestSeries.retireContest(c.getId, actor.getId, None) === Left("contest.retire.live.round")
      DB.autoCommit(implicit s => sql"UPDATE round SET active = 0, freeze_count = 1, active_before_freeze = 1 WHERE id = ${open.getId}".update())
      ContestSeries.retireContest(c.getId, actor.getId, None) === Left("contest.retire.live.round")
      // a creation running, in review or failed: finish or cancel it
      DB.autoCommit(implicit s => sql"UPDATE round SET freeze_count = 0, distributing = 1 WHERE id = ${open.getId}".update())
      ContestSeries.retireContest(c.getId, actor.getId, None) === Left("contest.retire.draft.round")
      // a cancelled creation doesn't keep it
      DB.autoCommit(implicit s => sql"UPDATE round SET status = 'cancelled' WHERE id = ${open.getId}".update())
      ContestSeries.liveRoundRefusal(c.getId) must beNone
      DB.autoCommit(implicit s => sql"UPDATE round SET status = 'draft' WHERE id = ${open.getId}".update())
      // a stopped round (V105's paused, not open) doesn't keep it
      DB.autoCommit(implicit s => sql"UPDATE round SET distributing = 0, status = ${Round.Status.Paused} WHERE id = ${open.getId}".update())
      ContestSeries.retireContest(c.getId, actor.getId, None) === Right(())
      reload(c).retired must beTrue
    }
  }

  "a retired contest" should {
    "give its members no access but keep their memberships for the account rules" in {
      implicit val actor: User = root
      val live = contest("Wiki Loves Monuments", 2026, "Nepal")
      val old = contest("Wiki Loves Monuments", 2020, "Nepal")
      val admin = User.create(User("A", "a@example.org", None, Set("admin"), contestId = old.id))
      UserContestJdbc.createMembership(admin.getId, live.getId, "jury")
      ContestSeries.retireContest(old.getId, actor.getId, None) === Right(())
      val inOld = controllers.Secured.inCurrentContest(admin, Some(old.getId.toString))
      inOld.contestId must beSome(live.getId)
      controllers.Secured.inContest(inOld, old.getId).contestId must beSome(live.getId)
      inOld.userContests.find(_.contestId == old.getId).map(m => (m.active, m.opens)) must beSome((true, false))
      // only the retired contest: no contest at all
      UserContestJdbc.findByContest(live.getId).foreach(m => DB.autoCommit(implicit s =>
        sql"DELETE FROM user_contest WHERE user_id = ${m.userId} AND contest_id = ${m.contestId}".update()))
      controllers.Secured.inCurrentContest(admin.copy(contestId = None), Some(old.getId.toString)).contestId must beNone
    }

    "give an admin of it no reach as an actor, and be no source to add people from, for root too" in {
      implicit val actor: User = root
      val live = contest("Wiki Loves Monuments", 2026, "Nepal")
      val old = contest("Wiki Loves Monuments", 2020, "Nepal")
      val other = contest("Wiki Loves Earth", 2026, "Nepal")
      val admin = User.create(User("A", "a2@example.org", None, Set("admin"), contestId = live.id))
      UserContestJdbc.createMembership(admin.getId, old.getId, "admin")
      ContestSeries.retireContest(old.getId, actor.getId, None) === Right(())
      val session = controllers.Secured.inCurrentContest(admin.copy(contestId = None), Some(live.getId.toString))
      services.AccountSearch.administered(session) must beSome(Seq(live.getId))
      services.AddFromContest.sources(session, live.getId) must beEmpty
      services.AddFromContest.source(session, live.getId, old.getId) must beNone
      val asRoot = controllers.Secured.inCurrentContest(actor, None)
      services.AddFromContest.sources(asRoot, live.getId).map(_.getId) === Seq(other.getId)
      services.AddFromContest.source(asRoot, live.getId, old.getId) must beNone
    }
  }

  "the bulk assignment" should {
    "assign the choices as one batch, new series first, and undo it but for contests changed since" in {
      implicit val actor: User = root
      val existing = series("Wiki Loves Earth in Nepal", Some("wle"), Some("Nepal"))
      val e25 = contest("Wiki Loves Earth", 2025, "Nepal")
      val m24 = contest("Wiki Loves Monuments", 2024, "Armenia")
      val m25 = contest("Wiki Loves Monuments", 2025, "Armenia")
      val batch = ContestSeries.confirm(Seq(
        ContestSeries.Choice(Some(existing), "ignored", Some("wle"), Some("Nepal"), None, Seq(e25.getId)),
        ContestSeries.Choice(None, "Wiki Loves Monuments in Armenia", Some("wlm"), Some("Armenia"), Some("wlm-am"),
          Seq(m24.getId, m25.getId))
      ), actor.getId)
      batch must beRight
      val id = batch.toOption.get
      val armenia = SeriesJdbc.findAll().find(_.name == "Wiki Loves Monuments in Armenia").get
      armenia.campaign must beSome("wlm-am")
      reload(m25).seriesId must beSome(armenia.id)
      reload(e25).seriesId must beSome(existing)
      ContestChangeJdbc.findByBatch(id).count(_.kind == ContestChangeJdbc.SeriesAssigned) === 3
      ContestChangeJdbc.recentBatches(5).map(b => (b.id, b.contests, b.undoneBy)) === Seq((id, 3, None))

      // m24 moved since: the undo leaves it
      ContestSeries.setSeries(m24.getId, None, actor.getId, None)
      val undone = ContestSeries.undoBatch(id, actor.getId)
      undone.map { case (u, l) => (u.sorted, l) } === Right((Seq(e25.getId, m25.getId).sorted, Seq(m24.getId)))
      reload(e25).seriesId must beNone
      reload(m25).seriesId must beNone
      ContestChangeJdbc.recentBatches(5).head.undoneBy must beSome
      ContestSeries.undoBatch(id, actor.getId) === Left("series.batch.undone.already")
    }

    "refuse the whole batch if one contest can't be assigned" in {
      implicit val actor: User = root
      val s = series("Wiki Loves Monuments in Nepal")
      val held = contest("Wiki Loves Monuments", 2025, "Nepal")
      ContestSeries.setSeries(held.getId, Some(s), actor.getId, None)
      val a = contest("Wiki Loves Monuments", 2024, "Nepal")
      val b = contest("Wiki Loves Monuments", 2025, "Nepal")
      val before = SeriesJdbc.findAll().size
      ContestSeries.confirm(Seq(
        ContestSeries.Choice(None, "A new one", Some("wlm"), Some("Armenia"), None, Seq(contest("Wiki Loves Monuments", 2025, "Armenia").getId)),
        ContestSeries.Choice(Some(s), "", None, None, None, Seq(a.getId, b.getId))
      ), actor.getId) === Left(("series.year.taken", Some(b.getId)))
      reload(a).seriesId must beNone
      SeriesJdbc.findAll().size === before
    }
  }

  "an identity edit" should {
    "be refused when it no longer fits the series, and logged otherwise" in {
      implicit val actor: User = root
      val s = series("Wiki Loves Monuments in Nepal")
      val c = contest("Wiki Loves Monuments", 2025, "Nepal")
      ContestSeries.setSeries(c.getId, Some(s), actor.getId, None)
      val service = new services.ContestService(null)
      service.updateContest(reload(c).copy(country = "Armenia"), Some(actor.getId)) === Left("series.identity.mismatch")
      service.updateContest(reload(c).copy(year = 2024), Some(actor.getId)) === Right(())
      ContestChangeJdbc.findByContest(c.getId, 5).head.kind === ContestChangeJdbc.Identity
    }

    "record the creator and a created entry for a contest root adds" in {
      implicit val actor: User = root
      val added = new services.ContestService(null).createContest(ContestJury(None, "Wiki Loves Earth", 2026, "Nepal", None),
        Some(actor.getId), "form")
      ContestSeries.createdInfo(Seq(added.getId)).get(added.getId).map(i => (i._1, i._2.isDefined)) must beSome((Some(actor.getId), true))
      ContestChangeJdbc.findByContest(added.getId, 5).map(_.kind) === Seq(ContestChangeJdbc.Created)
      // an import job unrelated to this check
      HealthFixtures.importJob(added, "Category:Images from Wiki Loves Earth 2026 in Nepal", ImportState()) must be_>(0L)
    }
  }
}
