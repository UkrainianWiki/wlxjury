package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import org.intracer.wmua.{Image, Vote}
import org.scalawiki.wlx.dto.Monument
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.distribution.DistributionSource
import services.distribution.RoundDistributionHandler.PrevVotes
import services.imports.DeletedFiles

import scala.util.Random

/** The "which images" reads P1 moved from grouping the votes to round_image (V84), against the
  * queries they replaced, kept here as the reference, on the same data: random votes in four
  * rounds written through the app's writers (distribution inserts, files deleted on Commons in
  * open rounds and restored, a file moved between rounds, a file and the unrated votes removed),
  * plus votes without a page id and of a round that doesn't exist, which no read lists.
  */
class RoundImageReadParitySpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def q[A](statement: SQLSyntax)(f: WrappedResultSet => A): List[A] = DB.readOnly(implicit s => sql"$statement".map(f).list())

  // ── the replaced queries ────────────────────────────────────────────────

  private object Old {
    def imageCountByRounds(roundIds: Seq[Long]): Map[Long, Int] =
      q(sqls"SELECT s.round_id, COUNT(DISTINCT s.page_id) FROM vote s WHERE s.round_id IN ($roundIds) GROUP BY s.round_id")(rs =>
        rs.long(1) -> rs.int(2)).toMap

    def byRound(roundId: Long): Set[Long] =
      q(sqls"SELECT DISTINCT i.page_id FROM images i JOIN vote s ON i.page_id = s.page_id WHERE s.round_id = $roundId")(_.long(1)).toSet

    def regions(roundId: Long, sub: Boolean): Set[String] =
      if (sub) q(sqls"""SELECT DISTINCT m.adm1 FROM vote s JOIN monument m ON m.id = s.monument_id
                        WHERE m.adm1 IS NOT NULL AND s.round_id = $roundId""")(_.string(1)).toSet
      else q(sqls"""SELECT DISTINCT r FROM (SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, '-', 1), 3), '') r
                    FROM vote s WHERE s.round_id = $roundId) x WHERE r IS NOT NULL""")(_.string(1)).toSet

    def coiFiles(roundId: Long, authors: Seq[String]): Seq[Long] =
      q(sqls"""SELECT i.page_id FROM (
                 SELECT page_id FROM vote WHERE round_id = $roundId
                 UNION SELECT page_id FROM vote_aside WHERE round_id = $roundId) p
               JOIN images i ON i.page_id = p.page_id
               WHERE i.author IN ($authors) ORDER BY i.page_id""")(_.long(1))

    private def hasVotes(table: SQLSyntax, roundId: Long) =
      sqls"EXISTS (SELECT 1 FROM $table v WHERE v.round_id = $roundId AND v.page_id = i.page_id)"

    def deletedFileCounts(roundId: Long): (Int, Int) =
      q(sqls"""SELECT COUNT(*), COALESCE(SUM(${hasVotes(sqls"vote", roundId)}), 0) FROM images i
               WHERE i.deleted_at IS NOT NULL
                 AND (${hasVotes(sqls"vote", roundId)} OR ${hasVotes(sqls"vote_aside", roundId)})""")(rs =>
        rs.int(1) -> rs.int(2)).head

    def roundsOf(pageIds: Seq[Long]): Set[Long] =
      q(sqls"SELECT DISTINCT round_id FROM vote WHERE page_id IN ($pageIds)")(_.long(1)).toSet

    def inRound(roundId: Long, pageId: Long): Boolean =
      q(sqls"SELECT EXISTS(SELECT 1 FROM vote WHERE round_id = $roundId AND page_id = $pageId)")(_.boolean(1)).head

    def previousPages(prevIds: Seq[Long]): Seq[Long] =
      // the windows' `page_id > cursor` left out a vote without a page id
      q(sqls"SELECT DISTINCT s.page_id FROM vote s WHERE s.round_id IN ($prevIds) AND s.page_id > 0 ORDER BY s.page_id")(_.long(1))

    /** The distribution's count check: the round's files with votes, and those only aside. */
    def imageCount(roundId: Long): Long =
      q(sqls"SELECT COUNT(DISTINCT page_id) FROM vote WHERE round_id = $roundId")(_.long(1)).head +
        q(sqls"""SELECT COUNT(DISTINCT d.page_id) FROM vote_aside d WHERE d.round_id = $roundId
                 AND NOT EXISTS (SELECT 1 FROM vote s WHERE s.round_id = $roundId AND s.page_id = d.page_id)""")(_.long(1)).head
  }

  // ── the data ───────────────────────────────────────────────────────────

  private val authors = Seq("Alice", "Bob")

  /** 60 files in regions 07 (07-101, 07-102) and 14, every 9th without a monument id, every
    * 5th by Alice or Bob; rounds 1-4 (4 closed), 5 jurors, each round a random subset of the
    * files to 1-3 jurors with random rates; then the app's bulk writers.
    */
  private def setUp(seed: Int): Seq[Long] = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val rounds = (1 to 4).map(n => roundDao.create(Round(None, n, contestId = contest.getId, active = n != 4)).getId)
    val jurors = (1 to 5).map(n => userDao.create(User(s"Juror $n", s"juror$n@example.com", None, Set("jury"), contestId = contest.id)))
    val images = (1 to 60).map { n =>
      val monument = n match {
        case _ if n % 9 == 0 => None
        case _ if n % 3 == 0 => Some(f"14-101-$n%04d")
        case _ if n % 3 == 1 => Some(f"07-101-$n%04d")
        case _               => Some(f"07-102-$n%04d")
      }
      Image(n.toLong, s"File:Image$n.jpg", None, None, 640, 480, monument,
        author = if (n % 5 == 0) Some(authors(n % 2)) else None)
    }
    imageDao.batchInsert(images)
    monumentDao.batchInsert(images.flatMap(_.monumentId).map(id => new Monument(id = id, name = s"M $id", page = "T")))
    DB.autoCommit(implicit s => sql"UPDATE monument SET adm1 = SUBSTR(id, 1, 6)".update())
    val random = new Random(seed)
    rounds.foreach { roundId =>
      val round = roundDao.findById(roundId).get
      val votes = images.filter(_ => random.nextInt(4) > 0).flatMap { image =>
        random.shuffle(jurors).take(1 + random.nextInt(3)).map(j => Vote(image, j, round, random.nextInt(3) - 1))
      }
      selectionDao.batchInsert(votes)
    }
    // votes no read lists: without a page id, and of a round that doesn't exist
    DB.autoCommit { implicit s =>
      sql"INSERT INTO vote (page_id, rate, jury_id, round_id, monument_id) VALUES (NULL, 1, 1, ${rounds.head}, '07-101-9999')".update()
      sql"INSERT INTO vote (page_id, rate, jury_id, round_id, monument_id) VALUES (1, 1, 1, 999, '07-101-0001')".update()
    }
    // files deleted on Commons: aside in the open rounds; some restored
    val deleted = (1 to 60).filter(_ => random.nextInt(6) == 0).map(_.toLong)
    DB.localTx(implicit s => DeletedFiles.markDeleted(deleted))
    DB.localTx(implicit s => DeletedFiles.restore(deleted.filter(_ % 2 == 0)))
    // a file moved, one removed, the unrated votes of round 3
    TestVoteWrites.setRound(7L, rounds(0), rounds(1))
    TestVoteWrites.removeImage(11L, rounds(1))
    TestVoteWrites.removeUnrated(rounds(2))
    rounds
  }

  "the reads backed by round_image" should {

    "give what the vote-based queries gave, over random data" in {
      forall(Seq(1, 2, 3)) { seed =>
        SharedTestDb.truncateAll()
        val rounds = setUp(seed)
        val members = authors.zipWithIndex.map { case (a, i) => a -> CoiMembers.Member(i.toLong + 1, a, "board") }.toMap
        val pages = (1L to 60L)
        (forall(rounds)(r => RoundImageInvariants.violations(r) must beEmpty)) and
          (ImageJdbc.imageCountByRounds(rounds) === Old.imageCountByRounds(rounds)) and
          forall(rounds) { r =>
            (ImageJdbc.byRound(r).map(_.pageId).toSet === Old.byRound(r)) and
              (SelectionQuery(roundId = Some(r)).byRegionStat().keySet === Old.regions(r, sub = false)) and
              (SelectionQuery(roundId = Some(r), subRegions = true).byRegionStat().keySet === Old.regions(r, sub = true)) and
              (CoiMembers.roundFiles(r, members).map(_._1) === Old.coiFiles(r, authors)) and
              (ImageJdbc.roundDeletedFileCounts(r) === Old.deletedFileCounts(r)) and
              (pages.filter(p => VoteJdbc.inRound(r, p, None)) === pages.filter(p => Old.inRound(r, p))) and
              (RoundImageJdbc.countAll(r) === Old.imageCount(r))
          } and
          forall(Seq(rounds.take(1), rounds.take(2), rounds.drop(2))) { prev =>
            val all = Old.previousPages(prev)
            val votes = PrevVotes()
            val windows = DB.readOnly { implicit s =>
              Iterator.iterate(DistributionSource.previousWindow(prev, votes, 0L, 7))(w =>
                DistributionSource.previousWindow(prev, votes, w.last, 7)).takeWhile(_.size > 0).toSeq
            }
            (DB.readOnly(implicit s => DistributionSource.previousCount(prev, votes)) === all.size.toLong) and
              (windows.map(_.size).sum === all.size) and
              (windows.map(_.last) === all.grouped(7).map(_.last).toSeq)
          } and
          {
            val open = DB.localTx(implicit s => ImageJdbc.lockOpenRoundsOf(pages))
            val openRounds = rounds.filter(r => roundDao.findById(r).exists(_.active)).toSet
            open === (Old.roundsOf(pages) intersect openRounds)
          }
      }
    }
  }
}
