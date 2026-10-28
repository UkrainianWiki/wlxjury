package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

/** A juror's large view reads a file's place from its row and a count of the rows ordered
  * before it, and its neighbours from that place ([[RoundList.position]], [[RoundList.around]],
  * [[RoundList.voteMoved]]): the same as the file's rank among all the list's rows and the list
  * at an offset from it gave, for every file, filter, order and region, in a binary and a rated
  * round, with files without a monument id and with equal monument ids.
  */
class ListPositionSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val filters: Seq[VoteFilter] =
    Seq(VoteFilter.All, VoteFilter.Unrated, VoteFilter.Rated) ++ (-1 to 5).filter(_ != 0).map(VoteFilter.Rate)

  /** 14 files in regions 07, 14 (two of them on one monument), none; the juror votes on all,
    * another juror on some; file 99 is no file of the juror's.
    */
  private def setUp(rates: Rates): (Round, User, Seq[Long]) = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val round = roundDao.create(Round(None, 1, contestId = contest.getId, rates = rates, active = true))
    val Seq(juror, other) = (1 to 2).map(n => userDao.create(User(s"juror$n", s"juror$n@example.org", None,
      Set("jury"), contestId = contest.id)))
    val monuments = Map(1 -> Some("07-101-0003"), 2 -> Some("07-101-0001"), 3 -> Some("07-101-0001"),
      4 -> Some("14-101-0004"), 5 -> None, 6 -> Some("14-101-0002"), 7 -> Some("07-102-0007"), 8 -> None,
      9 -> Some(""), 10 -> Some("14-101-0002"), 11 -> Some("07-101-0001"), 12 -> Some("07-100-0012"),
      13 -> Some("14-103-0013"), 14 -> None, 99 -> Some("07-101-0099"))
    imageDao.batchInsert(monuments.toSeq.sortBy(_._1).map { case (id, m) =>
      Image(id.toLong, s"File:Image$id.jpg", None, None, 640, 480, m)
    })
    val possible = if (rates == Round.binaryRound) Seq(-1, 0, 1) else 0 to rates.maxRate
    def vote(user: User, page: Int, i: Int) =
      Selection(page.toLong, user.getId, round.getId, possible(i % possible.size), monumentId = monuments(page))
    selectionDao.batchInsert((1 to 14).map(p => vote(juror, p, p * 5 + 1)) ++ (1 to 6).map(p => vote(other, p, p)) :+
      vote(other, 99, 1))
    (round, juror, (1L to 14L) :+ 99L)
  }

  private def lists(round: Round, juror: User): Seq[RoundList] =
    for (filter <- filters; order <- ListOrder.all; region <- Seq(None, Some("07"), Some("14")))
      yield RoundList(round, juror.getId, region, filter, order)

  private def samePlaces(round: Round, juror: User, files: Seq[Long]) = {
    val all = for (list <- lists(round, juror); pageId <- files) yield {
      def window(h: Option[Neighbourhood]) = h.map(n => (n.files.map(_.pageId), n.index, n.position))
      (list.filter, list.order, list.region, pageId,
        list.position(pageId), window(list.around(pageId)), list.rankPosition(pageId), window(list.aroundAtOffset(pageId)))
    }
    (all.map(a => (a._1, a._2, a._3, a._4, a._5, a._6)) === all.map(a => (a._1, a._2, a._3, a._4, a._7, a._8))) and
      (all.count(_._5.isDefined) must be_>(100))
  }

  /** The old [[RoundList.voteMoved]]: the file's rank among the list's files after the vote. */
  private def movedByRank(list: RoundList, pageId: Long, before: Int, rate: Int): Option[MovedVote] =
    if (list.order == ListOrder.FileOrder && list.filter == VoteFilter.All) None
    else {
      val rank = list.query.imageRank(pageId)
      if (rank == 0) Option.when(list.filter != VoteFilter.Unrated)(MovedVote(pageId, out = true, rate))
      else Option.when(rank - 1 != before)(MovedVote(pageId, out = false, rate))
    }

  private def sameMoves(round: Round, juror: User, files: Seq[Long]) = {
    val rates = files.flatMap(p => SelectionJdbc.findBy(p, juror.getId, round.getId).map(s => p -> s.rate)).toMap
    val all = for (list <- lists(round, juror); pageId <- files.filter(rates.contains); before <- Seq(0, 3, 7)) yield
      (list.filter, list.order, list.region, pageId, before,
        list.voteMoved(pageId, before, rates(pageId)), movedByRank(list, pageId, before, rates(pageId)))
    all.map(a => a.productIterator.toSeq.take(6)) === all.map(a => a.productIterator.toSeq.take(5) :+ a._7)
  }

  "a file's place in a juror's list" should {

    "be its rank among the list's files, and its neighbours those at an offset, in a binary round" in {
      val (round, juror, files) = setUp(Round.binaryRound)
      samePlaces(round, juror, files)
    }

    "be its rank among the list's files, and its neighbours those at an offset, in a rated round" in {
      val (round, juror, files) = setUp(Round.rateRounds.find(_.maxRate == 5).get)
      samePlaces(round, juror, files)
    }

    "tell a vote's move as its rank after the vote did, in a rated round" in {
      val (round, juror, files) = setUp(Round.rateRounds.find(_.maxRate == 5).get)
      sameMoves(round, juror, files)
    }

    "tell a vote's move as its rank after the vote did, in a binary round" in {
      val (round, juror, files) = setUp(Round.binaryRound)
      sameMoves(round, juror, files)
    }

    "be read the old way for all the jurors' votes together" in {
      val (round, _, files) = setUp(Round.binaryRound)
      val list = RoundList(round, 0)
      list.query.keyed must beFalse
      files.map(list.position) === files.map(list.rankPosition)
    }
  }
}
