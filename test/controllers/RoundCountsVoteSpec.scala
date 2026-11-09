package controllers

import db.scalikejdbc._
import db.scalikejdbc.RoundCounts.Counts
import org.intracer.wmua._
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.counts.RoundCountsRecompute

import scala.concurrent.Future

/** Votes through the app's routes keep round_image's counts right (P2a, V89): the gallery's
  * vote (`selectWS`), the large view's (`rateByPageId`) in a binary, a rated, a half-star and
  * a criteria round mark their files, and after a recompute the counts equal the votes'
  * (invariant 5, [[RoundImageInvariants]]).
  */
class RoundCountsVoteSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val recompute = new RoundCountsRecompute(RoundCountsRecompute.Settings(background = false))

  private case class Fixture(contest: ContestJury, round: Round, jurors: Seq[User], pages: Seq[Long])

  private var lastUser = 0

  /** A round of `rates` with two jurors and three files, each juror assigned every file. */
  private def fixture(rates: Rates, halfStar: Boolean = false, criteria: Seq[Long] = Nil,
      worstMark: Boolean = false): Fixture = {
    val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
    val round = roundDao.create(Round(None, 1, Some("Round"), contestId = contest.getId, rates = rates, active = true,
      halfStar = halfStar, criterionIds = criteria, worstMark = worstMark))
    val jurors = (1 to 2).map { _ =>
      lastUser += 1
      userDao.create(User(s"Juror $lastUser", s"juror$lastUser@example.com", None, Set("jury"), contestId = contest.id))
    }
    round.addUsers(jurors.map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    val images = (1L to 3L).map(id => Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id")))
    imageDao.batchInsert(images)
    selectionDao.batchInsert(for (j <- jurors; image <- images) yield Vote(image, j, round, rate = 0))
    recompute.untilClean()
    Fixture(contest, round, jurors, images.map(_.pageId))
  }

  private def post(app: Application, f: Fixture, juror: User, path: String): Future[Result] =
    route(app, FakeRequest(POST, path)
      .withSession(Secured.UserName -> juror.email, Secured.CurrentContestId -> f.contest.getId.toString)
      .withHeaders("X-Requested-With" -> "XMLHttpRequest")
      .withCSRFToken).get

  /** The gallery's vote (tile-votes.js), and the large view's vote form. */
  private def gallery(app: Application, f: Fixture, juror: User, pageId: Long, rate: Int) =
    post(app, f, juror, s"/rate/round/${f.round.getId}/pageid/$pageId/select/$rate")

  private def large(app: Application, f: Fixture, juror: User, pageId: Long, rate: Int, criterion: Option[Long] = None) =
    post(app, f, juror, s"/large/round/${f.round.getId}/pageid/$pageId/select/$rate" +
      criterion.fold("?rate=0")(c => s"?criteria=$c"))

  private def entries(roundId: Long): Seq[Long] =
    DB.readOnly(implicit s => sql"SELECT page_id FROM round_image_dirty WHERE round_id = $roundId ORDER BY page_id"
      .map(_.long(1)).list())

  private def counts(f: Fixture): Seq[Counts] = f.pages.map(p => RoundCounts.of(f.round.getId, p).get)

  /** After a recompute: the round clean, no violation (invariant 5 included). */
  private def recounted(f: Fixture) = {
    recompute.untilClean()
    (RoundCounts.isClean(f.round.getId) must beTrue) and (RoundImageInvariants.violations(f.round.getId) must beEmpty)
  }

  "a binary round's votes, from the gallery and the large view" should {
    "mark the files they change, and be counted by the recompute" in {
      testDbApp { app =>
        val f = fixture(Round.binaryRound)
        val Seq(j1, j2) = f.jurors
        val statuses = Seq(
          gallery(app, f, j1, 1L, 1), gallery(app, f, j1, 2L, -1), large(app, f, j2, 1L, 1), large(app, f, j2, 3L, -1)
        ).map(status(_))
        val marked = entries(f.round.getId)
        (statuses.forall(s => s == OK || s == SEE_OTHER) must beTrue) and (marked === Seq(1L, 2L, 3L)) and
          // shadow until P2b: the counts wait for the recompute
          (counts(f) === Seq.fill(3)(Counts(2, 0, 0, 0, 0))) and recounted(f) and
          (counts(f) === Seq(Counts(2, 2, 2, 0, 2), Counts(2, 1, 0, 1, -1), Counts(2, 1, 0, 1, -1)))
      }
    }

    "recount a juror changing their mind, and leave a file unmarked when a vote changes nothing" in {
      testDbApp { app =>
        val f = fixture(Round.binaryRound)
        val Seq(j1, _) = f.jurors
        status(gallery(app, f, j1, 1L, 1))
        recounted(f)
        status(gallery(app, f, j1, 1L, 1)) // the same again
        val unchanged = entries(f.round.getId)
        status(gallery(app, f, j1, 1L, -1))
        (unchanged must beEmpty) and (entries(f.round.getId) === Seq(1L)) and recounted(f) and
          (counts(f).head === Counts(2, 1, 0, 1, -1))
      }
    }
  }

  "a rated round's votes" should {
    "be counted in stars" in {
      testDbApp { app =>
        val f = fixture(Round.ratesById(5))
        val Seq(j1, j2) = f.jurors
        Seq(large(app, f, j1, 1L, 5), large(app, f, j2, 1L, 3), gallery(app, f, j1, 2L, 1)).map(status(_))
        recounted(f) and (counts(f) === Seq(Counts(2, 2, 2, 0, 8), Counts(2, 1, 1, 0, 1), Counts(2, 0, 0, 0, 0)))
      }
    }

    "in half stars, be counted in stored units" in {
      testDbApp { app =>
        val f = fixture(Round.ratesById(10), halfStar = true)
        val Seq(j1, j2) = f.jurors
        Seq(large(app, f, j1, 1L, 7), large(app, f, j2, 1L, 10), large(app, f, j2, 2L, 1)).map(status(_))
        recounted(f) and (counts(f).take(2) === Seq(Counts(2, 2, 2, 0, 17), Counts(2, 1, 1, 0, 1)))
      }
    }
  }

  "a criteria round's votes" should {
    "count a vote once every criterion is rated, and its sum" in {
      testDbApp { app =>
        val f = fixture(Round.ratesById(10), criteria = Seq(1L, 2L))
        val Seq(j1, j2) = f.jurors
        status(large(app, f, j1, 1L, 6, criterion = Some(1L)))
        val partly = (entries(f.round.getId), { recompute.untilClean(); counts(f).head })
        Seq(large(app, f, j1, 1L, 8, criterion = Some(2L)), large(app, f, j2, 1L, 3, criterion = Some(1L)),
          large(app, f, j2, 1L, 4, criterion = Some(2L))).map(status(_))
        (partly === ((Nil, Counts(2, 0, 0, 0, 0)))) and recounted(f) and (counts(f).head === Counts(2, 2, 2, 0, 21))
      }
    }
  }

  private def rate(f: Fixture, juror: User, pageId: Long): Int =
    VoteJdbc.findBy(pageId, juror.getId, f.round.getId).get.rate

  "a rated round's worst mark (rate -1) [J-38]" should {
    "be taken from the gallery and the large view as -1, mark its file, count as voted and as a rejection" in {
      testDbApp { app =>
        val f = fixture(Round.ratesById(5), worstMark = true)
        val Seq(j1, j2) = f.jurors
        val statuses = Seq(gallery(app, f, j1, 1L, -1), large(app, f, j2, 1L, 4), large(app, f, j1, 2L, -1)).map(status(_))
        val marked = entries(f.round.getId)
        (statuses === Seq(OK, SEE_OTHER, SEE_OTHER)) and (marked === Seq(1L, 2L)) and
          (Seq(rate(f, j1, 1L), rate(f, j2, 1L), rate(f, j1, 2L)) === Seq(-1, 4, -1)) and recounted(f) and
          // (assigned, voted, approvals, rejections, rate_sum): the grades' sum is rate_sum + rejections
          (counts(f) === Seq(Counts(2, 2, 1, 1, 3), Counts(2, 1, 0, 1, -1), Counts(2, 0, 0, 0, 0)))
      }
    }

    "be cleared apart (back to 0, unrated), the clear marking the file too" in {
      testDbApp { app =>
        val f = fixture(Round.ratesById(5), worstMark = true)
        val Seq(j1, _) = f.jurors
        status(gallery(app, f, j1, 1L, -1))
        recounted(f)
        status(large(app, f, j1, 1L, 0))
        val marked = entries(f.round.getId)
        (rate(f, j1, 1L) === 0) and (marked === Seq(1L)) and recounted(f) and (counts(f).head === Counts(2, 0, 0, 0, 0))
      }
    }

    "be refused with 400 in a rated round without the option, the vote left as it was" in {
      testDbApp { app =>
        val f = fixture(Round.ratesById(5))
        val Seq(j1, _) = f.jurors
        status(gallery(app, f, j1, 1L, 3))
        val tile = gallery(app, f, j1, 1L, -1)
        val largeView = large(app, f, j1, 1L, -1)
        (status(tile) === BAD_REQUEST) and (contentAsJson(tile) \ "error").as[String] === "invalid_rate" and
          (status(largeView) === BAD_REQUEST) and (rate(f, j1, 1L) === 3)
      }
    }

    "be refused in a criteria round, whatever its stored option (its votes are per criterion)" in {
      testDbApp { app =>
        val f = fixture(Round.ratesById(10), criteria = Seq(1L, 2L), worstMark = true)
        val Seq(j1, _) = f.jurors
        (status(large(app, f, j1, 1L, -1, criterion = Some(1L))) === BAD_REQUEST) and
          (status(gallery(app, f, j1, 1L, -1)) === BAD_REQUEST) and (rate(f, j1, 1L) === 0)
      }
    }

    "leave a yes/no round's -1, its no, as it was" in {
      testDbApp { app =>
        val f = fixture(Round.binaryRound)
        val Seq(j1, _) = f.jurors
        (status(gallery(app, f, j1, 1L, -1)) === OK) and (rate(f, j1, 1L) === -1)
      }
    }
  }
}
