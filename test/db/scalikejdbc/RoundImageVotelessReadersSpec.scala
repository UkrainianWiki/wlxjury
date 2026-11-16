package db.scalikejdbc

import controllers.{GalleryView, RoundList}
import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import db.scalikejdbc.rewrite.RoundImageQuery
import org.intracer.wmua.{ContestJury, Image, Vote}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.counts.RoundCountsRecompute
import services.distribution.DistributionSource
import services.distribution.RoundDistributionHandler.PrevVotes

/** The readers of `round_image` as a round's file list, with a file `in` the round and no votes
  * (P3-1, invariant 8 ended: a file filled and not distributed yet), each as the round-editing
  * plan's §6.1 table says: the votes' and the counts' lists leave it out; the counts of the
  * round's files, its files and the per-author and conflict-of-interest reads include it; a next
  * round takes it unfiltered only; a re-fill never adds it again; the recompute gives it zero
  * counts. Service-level reads: truncation, not AutoRollback.
  */
class RoundImageVotelessReadersSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def exec(statement: SQLSyntax): Unit = DB.autoCommit(implicit s => sql"$statement".update(): Unit)

  /** Files 1-3 by "Author1".."Author3"; files 1 and 2 voted by two jurors (1 rated yes), file 3
    * `in` with no votes, as a fill leaves it.
    */
  private def setUp(): Round = {
    implicit val contest: ContestJury = createContests(10).head
    val round = roundDao.create(Round(None, 1, None, contest.getId, active = true))
    val r = round.getId
    val jurors = createUsers("jury", 1, 2).map(_.getId)
    imageDao.batchInsert((1L to 3L).map(i =>
      Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"07-101-000$i"), author = Some(s"Author$i"))))
    VoteJdbc.batchInsert(for (p <- Seq(1L, 2L); j <- jurors) yield Vote(p, j, r, if (p == 1L) 1 else 0))
    exec(sqls"""INSERT INTO round_image (round_id, page_id, status, added_how, monument_id)
                SELECT $r, page_id, 'in', 'fill', monument_id FROM images WHERE page_id = 3""")
    new RoundCountsRecompute(RoundCountsRecompute.Settings(background = false)).untilClean()
    round
  }

  "a file in a round with no votes" should {

    "be left out of the counts path's lists, the vote source and the CSV's votes" in {
      val round = setUp()
      val r = round.getId
      val list = RoundList(round, 0, view = GalleryView.FileList)
      val counted = RoundImageQuery.of(list.query, binary = true, list.rankKey.columns.map(_._1)).get
      (counted.pageIds() === Seq(1L, 2L).filter(counted.pageIds().contains)) and
        (counted.pageIds() must not(contain(3L))) and (counted.count() === 2) and
        (SelectionQuery(roundId = Some(r)).list().map(_.pageId).distinct must not(contain(3L))) and
        (VoteJdbc.byRound(r).map(_.pageId).distinct.sorted === Seq(1L, 2L))
    }

    "be counted and listed among the round's files, per author and for conflicts of interest" in {
      val round = setUp()
      val r = round.getId
      val authors = AuthorStats.ofRound(round).map(row => row.author -> row.files).toMap
      (RoundImageJdbc.countIn(r) === 3L) and (RoundImageJdbc.countsIn(Seq(r)) === Map(r -> 3)) and
        (ImageJdbc.imageCountByRounds(Seq(r)) === Map(r -> 3)) and
        (ImageJdbc.byRound(r).map(_.pageId) === List(1L, 2L, 3L)) and
        (authors.get(Some("Author3")) === Some(1)) and
        (CoiMembers.roundFiles(r, Map("Author3" -> CoiMembers.Member(1L, "Author3", "jury"))).map(_._1) === Seq(3L))
    }

    "not pass to a next round, unfiltered or by a rate filter, and never be filled again" in {
      val round = setUp()
      val r = round.getId
      val filled = DB.readOnly { implicit s =>
        (DistributionSource.previousCount(Seq(r), PrevVotes(), Nil),
          DistributionSource.previousCount(Seq(r), PrevVotes(rated = true), Nil))
      }
      imageDao.batchInsert(Seq(Image(4L, "File:Image4.jpg", None, None, 640, 480, Some("07-101-0004"))))
      exec(sqls"INSERT INTO category (id, title) VALUES (77, 'Images of the contest')")
      exec(sqls"INSERT INTO category_members (category_id, page_id) VALUES (77, 1), (77, 2), (77, 3), (77, 4)")
      // given to no juror there: not one of its files for a next round (the candidates read votes)
      (filled === ((2L, 1L))) and
        (DB.readOnly(implicit s => DistributionSource.previousWindow(Seq(r), PrevVotes(), 0L, 10, Nil)).size === 2) and
        // a fill of the round adds only file 4; a round leaving this one out leaves out file 3 too
        (DB.readOnly(implicit s => DistributionSource.categoryCandidates(77L, 0L, 10L, r, Nil)).map(_.pageId) ===
          Seq(4L)) and
        (DB.readOnly(implicit s => DistributionSource.categoryCount(77L, Seq(r))) === 1L)
    }

    "get zero counts from the recompute, keeping invariant 5" in {
      val round = setUp()
      val r = round.getId
      (RoundCounts.of(r, 3L) === Some(RoundCounts.Counts.Zero)) and (RoundCounts.isClean(r) must beTrue) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }
  }
}
