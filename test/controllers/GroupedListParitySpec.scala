package controllers

import db.scalikejdbc._
import db.scalikejdbc.rewrite.ImageDbNew.Limit
import db.scalikejdbc.rewrite.RoundImageQuery
import org.intracer.wmua._
import org.intracer.wmua.ImageWithRating.Ranks
import org.scalawiki.wlx.dto.Monument
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.counts.RoundCountsRecompute

import scala.collection.mutable.ListBuffer

/** The lists of all the jurors' votes on a round (the by-rate gallery, the file list, the large
  * view's files around one) against a model of them computed from the votes in memory: each
  * page's files, aggregates and ranks, the totals and rank counts, positions and neighbourhoods,
  * over ties that span pages, every vote filter (empty lists too), regions (one, several through
  * the monument table, a Віа Регіа sub-region), binary orders, a criteria round, and the page
  * past the end. Whatever reads the lists (the grouped query, B12's deferred page, the
  * `round_image` counts of docs/plans/2026-11-02-round-images-two-step.md P2b) must give these:
  * each list is compared read from the votes and from the counts (`roundImage.read`, after a
  * recount), for each rule a rated round scores its files by (V90); and from the counts with
  * files marked dirty by later votes (read from their votes in the same query) and with a
  * recount pending (read from the votes).
  */
class GroupedListParitySpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  import GroupedListModel._

  private val PageSize = 3

  private val recompute = new RoundCountsRecompute(RoundCountsRecompute.Settings(background = false))

  /** 40 files in regions 07 (sub-regions 07-101, 07-102) and 14, every 10th without a
    * monument id; 4 jurors, each file voted on by 2-4 of them with `rate`'s rates; and two
    * votes without a page id, which no list has.
    */
  private def setUp(rates: Rates, rate: scala.util.Random => Int, hasCriteria: Boolean = false,
      specialNomination: Option[String] = None, rule: AverageRule = AverageRule.Default,
      worstMark: Boolean = false): (Round, Seq[File]) = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val round = roundDao.create(Round(None, 1, contestId = contest.getId, worstMark = worstMark, rates = rates, active = true,
      criterionIds = if (hasCriteria) Seq(1L, 2L) else Nil, specialNomination = specialNomination, averageRule = rule))
    val jurors = (1 to 4).map(n =>
      userDao.create(User(s"Juror $n", s"juror$n@example.com", None, Set("jury"), contestId = contest.id)))
    val images = (1 to 40).map { n =>
      val monument = n match {
        case _ if n % 10 == 0 => None
        case _ if n % 3 == 0  => Some(f"14-101-$n%04d")
        case _ if n % 3 == 1  => Some(f"07-101-$n%04d")
        case _                => Some(f"07-102-$n%04d")
      }
      Image(n.toLong, s"File:Image$n.jpg", None, None, 640, 480, monument)
    }
    imageDao.batchInsert(images)
    monumentDao.batchInsert(images.flatMap(_.monumentId).map(id => new Monument(id = id, name = s"Monument $id", page = "Test")))
    scalikejdbc.DB.autoCommit { implicit s => scalikejdbc.SQL("UPDATE monument SET adm1 = SUBSTR(id, 1, 6)").update() }
    val random = new scala.util.Random(42)
    val votes = images.zipWithIndex.map { case (image, n) =>
      val voters = (0 until 2 + n % 3).map(k => jurors((n + k) % jurors.size))
      image -> voters.map(j => Vote(image, j, round, rate(random)))
    }
    selectionDao.batchInsert(votes.flatMap(_._2))
    // two votes without a page id (`vote.page_id` is nullable), in region 07-101 and
    // matching every filter but the unrated: no list has them
    scalikejdbc.DB.autoCommit { implicit s =>
      jurors.take(2).foreach { j =>
        scalikejdbc.SQL("INSERT INTO vote (page_id, rate, jury_id, round_id, monument_id) VALUES (NULL, 1, ?, ?, '07-101-9999')")
          .bind(j.getId, round.getId).update()
      }
    }
    // the counts as the background recompute leaves them (P2a)
    recompute.untilClean()
    (round, votes.map { case (image, s) => File(image.pageId, image.monumentId, s.map(_.rate), s.map(_.juryId)) })
  }

  /** The list's problems read from the votes and from the counts; and the counts' source must be
    * the one used for every filter the counts can tell (all but one rate of a rated round).
    */
  private def bothSources(label: String, list: RoundList, files: Seq[File], inRegion: File => Boolean): Seq[String] = {
    val counted = list.withCountsRead(true)
    val tellable = list.filter.rate.forall(r => r == 0 || list.round.isBinary)
    val problems = compare(label + " votes", list, files, inRegion) ++ compare(label + " counts", counted, files, inRegion)
    problems ++ Option.when(tellable && !counted.countsUsed)(label + ": counts not read")
  }

  /** Votes changed after the recount: every (size / n)th file's first juror's rate set by `rate`,
    * as a juror's vote does (each marks its file dirty); the files as they are now.
    */
  private def revote(round: Round, files: Seq[File], n: Int, rate: scala.util.Random => Int): Seq[File] = {
    val random = new scala.util.Random(7)
    files.zipWithIndex.map { case (f, i) =>
      if (i % (files.size / n) != 0) f
      else {
        val r = rate(random)
        VoteJdbc.rate(f.pageId, f.jurors.head, round.getId, r)
        f.copy(rates = r +: f.rates.tail)
      }
    }
  }

  /** Every page of the list, its total, rank counts, positions and the large view's files,
    * as the model has them: the mismatches.
    */
  private def compare(label: String, list: RoundList, files: Seq[File], inRegion: File => Boolean): Seq[String] = {
    val problems = ListBuffer.empty[String]
    def check(what: String, got: Any, expected: Any): Unit =
      if (got != expected) problems += s"$label $what: got $got, expected $expected"

    val expected = GroupedListModel.list(list, files, inRegion)
    val ranks = GroupedListModel.ranks(list, expected)
    check("count", list.count(), expected.size)
    val counts = Option.when(list.ranked)(list.rankCounts())
    counts.foreach(c => check("rank counts' sum", c.map(_._2).sum, expected.size))
    val pages = (expected.size + PageSize - 1) / PageSize
    (1 to pages + 1).foreach { p =>
      val page = list.files(Pager(p, expected.size, PageSize))
      val slice = expected.slice((p - 1) * PageSize, p * PageSize)
      check(s"page $p", page.map(_.pageId), slice.map(_.pageId))
      if (page.map(_.pageId) == slice.map(_.pageId)) {
        if (list.view == GalleryView.FileList)
          check(s"page $p votes", page.map(_.votes.map(_.rate).sorted), slice.map(_.rates.sorted))
        else {
          // a rated round's sum is of grades, a ✗ as 0
          check(s"page $p sums", page.map(_.votes.map(_.rate).sum),
            slice.map(f => if (list.round.isBinary) f.sum else f.grades))
          check(s"page $p vote counts", page.map(_.countFromDb), slice.map(_.rates.size))
          if (list.round.isBinary)
            check(s"page $p tallies", page.map(f => (f.approvals, f.rejections)), slice.map(f => (f.approvals, f.rejections)))
          else {
            check(s"page $p rated votes", page.map(_.ratedVotes), slice.map(_.voted.toLong))
            check(s"page $p worst marks", page.map(_.worstMarks), slice.map(_.rejections))
          }
        }
        counts.foreach(c => check(s"page $p ranks", list.ranks(page, c), ranks.slice((p - 1) * PageSize, p * PageSize)))
      }
    }
    if (list.view == GalleryView.ByRate) expected.indices.filter(_ % 5 == 2).foreach { i =>
      val id = expected(i).pageId
      check(s"position of $id", list.position(id), Some(i))
      val from = math.max(0, i - Neighbourhood.Side)
      check(s"files around $id", list.around(id).map(h => (h.files.map(_.pageId), h.index, h.position)),
        Some((expected.slice(from, from + Neighbourhood.Window).map(_.pageId), i - from, i)))
    }
    problems.toSeq
  }

  private def lists(round: Round, filters: Seq[VoteFilter], orders: Seq[ListOrder], regions: Seq[Option[String]]) =
    for {
      filter <- filters
      region <- regions
      (order, view) <- orders.map(_ -> GalleryView.ByRate) :+ (ListOrder.ByRate -> GalleryView.FileList)
    } yield RoundList(round, 0, region, filter, order, view)

  private def label(l: RoundList): String = Seq(l.view.name, l.filter.param, l.order.param, l.regionParam).mkString(" ")

  private def inRegion(region: Option[String])(f: File): Boolean = region.forall(r => f.region.contains(r))

  "all the jurors' lists of a rated round" should {
    "be the model's, in each filter, region and order, for each score rule, from the votes and the counts" in {
      forall(AverageRule.all) { rule =>
        SharedTestDb.truncateAll()
        val (round, files) = setUp(Round.ratesById(5), _.nextInt(4), rule = rule)
        val all = lists(round, VoteFilter.ofRound(round) :+ VoteFilter.Rate(2), Seq(ListOrder.ByRate, ListOrder.FileOrder),
          Seq(None, Some("07"), Some("14"), Some("99")))
        val problems = all.flatMap(l => bothSources(rule.param + " " + label(l), l, files, inRegion(l.region)))
        (problems must_== Nil) and
          // empty lists among them: region 99
          (all.count(_.count() == 0) must be_>(0))
      }
    }

    "be the model's from the counts with files marked dirty by later votes, and with a recount pending" in {
      val (round, before) = setUp(Round.ratesById(5), _.nextInt(4))
      val files = revote(round, before, 8, _.nextInt(4))
      val all = lists(round, VoteFilter.ofRound(round), Seq(ListOrder.ByRate), Seq(None, Some("07")))
      val dirty = RoundCounts.state(round.getId).map(_.dirtyFiles)
      val marked = all.flatMap(l => bothSources("dirty " + label(l), l, files, inRegion(l.region)))
      // a bulk write's mark: the round is read from its votes until recounted
      scalikejdbc.DB.autoCommit { implicit s =>
        scalikejdbc.SQL("UPDATE round SET counts_gen = counts_gen + 1 WHERE id = ?").bind(round.getId).update()
      }
      val pending = all.map(_.withCountsRead(true))
      val pendingProblems = pending.flatMap(l => compare("pending " + label(l), l, files, inRegion(l.region)))
      (dirty must beSome(be_>(0L))) and (marked must_== Nil) and
        (pending.map(_.countsUsed).distinct must_== Seq(false)) and (pendingProblems must_== Nil)
    }

    "be the model's with the worst mark (✗ as 0, and as a vote), for each rule, from the votes, the counts and dirty files" in {
      forall(AverageRule.all) { rule =>
        SharedTestDb.truncateAll()
        // rates -1 (✗) to 3
        val (round, before) = setUp(Round.ratesById(5), _.nextInt(5) - 1, rule = rule, worstMark = true)
        val all = lists(round, VoteFilter.ofRound(round) :+ VoteFilter.Rate(2), Seq(ListOrder.ByRate, ListOrder.FileOrder),
          Seq(None, Some("07"), Some("99")))
        val clean = all.flatMap(l => bothSources(rule.param + " ✗ " + label(l), l, before, inRegion(l.region)))
        // ✗ given and cleared after the recount: the files marked, read through the overlay
        val files = revote(round, before, 8, r => if (r.nextBoolean()) -1 else 0)
        val dirty = all.filter(_.region.isEmpty).flatMap(l =>
          bothSources(rule.param + " ✗ dirty " + label(l), l, files, inRegion(l.region)))
        (before.exists(_.rejections > 0) must beTrue) and (clean must_== Nil) and (dirty must_== Nil)
      }
    }

    "read the votes when a bulk write commits between the generation check and the counts statement" in {
      val (round, files) = setUp(Round.ratesById(5), _.nextInt(4))
      // a juror without a vote yet on F1, given one (rate 4) by a bulk insert, as a distribution
      // chunk does to a file already in the round: it bumps the generation and marks no file
      val newcomer = userDao.create(User("Juror 5", "juror5@example.com", None, Set("jury"), contestId = Some(round.contestId)))
      val target = files.head
      val after = files.map(f => if (f.pageId == target.pageId) f.copy(rates = f.rates :+ 4) else f)
      def bulkWrite(): Unit = selectionDao.batchInsert(Seq(Vote(target.pageId, newcomer.getId, round.getId, 4, monumentId = target.monumentId)))
      val lists = Seq(GalleryView.ByRate, GalleryView.FileList).map(v => RoundList(round, 0, view = v).withCountsRead(true))
      // the first list's first statement meets the write; the second list starts after it, dirty
      lists.head.countsSession.beforeRead = () => { lists.head.countsSession.beforeRead = () => (); bulkWrite() }
      val first = compare("bulk " + label(lists.head), lists.head, after, _ => true)
      val second = compare("bulk " + label(lists(1)), lists(1), after, _ => true)
      // once recounted, the counts are read again, and agree
      recompute.untilClean()
      val recounted = RoundList(round, 0, view = GalleryView.ByRate).withCountsRead(true)
      val third = compare("recounted " + label(recounted), recounted, after, _ => true)
      (first must_== Nil) and (lists.head.countsUsed must beFalse) and
        (second must_== Nil) and (lists(1).countsUsed must beFalse) and
        (third must_== Nil) and (recounted.countsUsed must beTrue)
    }
  }

  "all the jurors' lists of a yes/no round" should {
    "be the model's, in each filter, region and binary order" in {
      val (round, files) = setUp(Round.binaryRound, r => r.nextInt(3) - 1)
      val all = lists(round, VoteFilter.ofRound(round), BinaryOrder.all.map(ListOrder.Binary) :+ ListOrder.FileOrder,
        Seq(None, Some("07"), Some("99")))
      val fileLists = for (order <- BinaryOrder.all; filter <- VoteFilter.ofRound(round))
        yield RoundList(round, 0, None, filter, ListOrder.Binary(order), GalleryView.FileList)
      val problems = (all ++ fileLists).flatMap(l => bothSources(label(l), l, files, inRegion(l.region)))
      problems must_== Nil
    }

    "be the model's from the counts with files marked dirty, whatever the round's score rule" in {
      forall(AverageRule.all) { rule =>
        SharedTestDb.truncateAll()
        val (round, before) = setUp(Round.binaryRound, r => r.nextInt(3) - 1, rule = rule)
        val files = revote(round, before, 8, r => r.nextInt(3) - 1)
        val all = lists(round, VoteFilter.ofRound(round), BinaryOrder.all.map(ListOrder.Binary), Seq(None))
        all.flatMap(l => bothSources(rule.param + " " + label(l), l, files, inRegion(l.region))) must_== Nil
      }
    }

    "keep the large view's file the filter leaves out in its place among all the files" in {
      val (round, files) = setUp(Round.binaryRound, r => r.nextInt(3) - 1)
      val list = RoundList(round, 0, None, VoteFilter.Rate(-1), ListOrder.Binary(BinaryOrder.Default), GalleryView.ByRate)
      val out = files.filterNot(_.rates.contains(-1)).map(_.pageId)
      out must not(beEmpty)
      forall(out.take(3)) { id =>
        val expected = GroupedListModel.list(list, files, _ => true, withPageId = Some(id))
        val i = expected.indexWhere(_.pageId == id)
        val from = math.max(0, i - Neighbourhood.Side)
        val around = Some((expected.slice(from, from + Neighbourhood.Window).map(_.pageId), i - from))
        (list.around(id).map(h => (h.files.map(_.pageId), h.index)) must_== around) and
          (list.withCountsRead(true).around(id).map(h => (h.files.map(_.pageId), h.index)) must_== around)
      }
    }
  }

  "all the jurors' lists of a criteria round" should {
    "be the model's" in {
      forall(AverageRule.all) { rule =>
        SharedTestDb.truncateAll()
        val (round, files) = setUp(Round.ratesById(5), _.nextInt(16), hasCriteria = true, rule = rule)
        val all = lists(round, Seq(VoteFilter.All, VoteFilter.Rated), Seq(ListOrder.ByRate), Seq(None, Some("14")))
        all.flatMap(l => bothSources(rule.param + " " + label(l), l, files, inRegion(l.region))) must_== Nil
      }
    }
  }

  "all the jurors' lists in several regions or a sub-region, through the monument table" should {
    "be the model's in two regions" in {
      val (round, files) = setUp(Round.ratesById(5), _.nextInt(4))
      val problems = Seq(GalleryView.ByRate, GalleryView.FileList).flatMap { view =>
        val list = RoundList(round, 0, view = view)
        val q = list.query.copy(regions = Set("07", "14"))
        val counted = RoundImageQuery.of(q, binary = false, list.rankKey.columns.map(_._1)).get
        val counts2 = Ranks(counted.keyCounts(list.rankKey.columns))
        val pages2 = (0 to files.size / PageSize).map(p =>
          counted.copy(limit = Some(Limit(Some(PageSize), Some(p * PageSize)))).pageIds())
        // files without a monument id have no region
        val expected = GroupedListModel.list(list, files, _.region.nonEmpty)
        val ranks = GroupedListModel.ranks(list, expected)
        val counts = Ranks(q.keyCounts(list.rankKey.columns))
        val pages = (0 to expected.size / PageSize).map(p =>
          q.copy(limit = Some(Limit(Some(PageSize), Some(p * PageSize)))).list())
        Seq(
          Option.when(q.count() != expected.size)(s"$view count ${q.count()}"),
          Option.when(pages.flatten.map(_.pageId) != expected.map(_.pageId))(s"$view pages ${pages.flatten.map(_.pageId)}"),
          Option.when(pages.flatten.map(f => counts(list.rankKey.values(f))) != ranks)(s"$view ranks"),
          Option.when(counted.count() != expected.size)(s"$view counts' count ${counted.count()}"),
          Option.when(pages2.flatten != expected.map(_.pageId))(s"$view counts' pages ${pages2.flatten}"),
          Option.when(pages.flatten.map(f => counts2(list.rankKey.values(f))) != ranks)(s"$view counts' ranks")
        ).flatten
      }
      problems must_== Nil
    }

    "be the model's in a Віа Регіа sub-region" in {
      val (round, files) = setUp(Round.ratesById(5), _.nextInt(4), specialNomination = Some("Віа Регіа"))
      val all = lists(round, Seq(VoteFilter.All, VoteFilter.Rated), Seq(ListOrder.ByRate), Seq(Some("07-101"), Some("07-102")))
      (all.map(_.query.subRegions).distinct must_== Seq(true)) and
        (all.flatMap(l => bothSources(label(l), l, files,
          f => l.region.exists(r => f.monumentId.exists(_.take(6) == r)))) must_== Nil)
    }
  }
}

/** All the jurors' lists of a round computed in memory from its votes: which files, in what
  * order, with what ranks.
  */
object GroupedListModel {

  /** A file of the round: its monument id, its votes' rates and their jurors. */
  final case class File(pageId: Long, monumentId: Option[String], rates: Seq[Int], jurors: Seq[Long] = Nil) {
    def sum: Int = rates.sum
    def approvals: Int = rates.count(_ > 0)

    /** A rated round's sum: of grades, a ✗ (-1) as 0 (the user, 2026-11-05). */
    def grades: Int = rates.map(math.max(_, 0)).sum

    /** A rated round's jurors who rated it: any vote, a ✗ included. */
    def voted: Int = rates.count(_ != 0)

    /** Its rank key in a rated round: its average of grades under the rule (exact, as an
      * integer; the rated rule's over the jurors with any vote), then the grades' sum; the sum
      * alone in a sum round.
      */
    def ratedKey(rule: AverageRule): Seq[Long] = rule match {
      case AverageRule.Sum      => Seq(grades.toLong)
      case AverageRule.Rated    => Seq(AverageRule.key(grades, voted), grades.toLong)
      case AverageRule.Assigned => Seq(AverageRule.key(grades, rates.size), grades.toLong)
    }
    def rejections: Int = rates.count(_ < 0)

    /** Its top-level region, as `ImageDbNew.regionOf` and the monument table's adm0 have it. */
    def region: Option[String] = monumentId.map(_.split("-", 2).head.take(3)).filter(_.nonEmpty)
  }

  def matches(filter: VoteFilter, file: File): Boolean = filter match {
    case VoteFilter.All     => true
    case VoteFilter.Rated   => file.rates.exists(_ != 0) // a rated round's: a ✗ is a vote
    case VoteFilter.Unrated => file.rates.contains(0)
    case f                  => f.rate.exists(file.rates.contains)
  }

  /** NULL first ascending, as MariaDB sorts it. */
  private val monumentOrder: Ordering[Option[String]] = Ordering.Option(Ordering.String)

  /** The list's order: a file list by its rank key (ties by monument and page id in a rated
    * round, by page id in a yes/no one), by rate by the round's rule (the average, then the sum;
    * the sum in a sum round) or a binary order, or in file order.
    */
  def ordering(list: RoundList): Ordering[File] = {
    val byFile: Ordering[File] = Ordering.by((f: File) => (f.monumentId, f.pageId))(Ordering.Tuple2(monumentOrder, Ordering.Long))
    val bySum: Ordering[File] =
      Ordering.by((f: File) => f.ratedKey(list.round.averageRule).map(-_))(Ordering.Implicits.seqOrdering[Seq, Long])
        .orElse(byFile)
    def byBinary(order: BinaryOrder): Ordering[File] =
      Ordering.by((f: File) => (order.sortKey(f.approvals, f.rejections), f.pageId))(
        Ordering.Tuple2(Ordering.Implicits.seqOrdering[Seq, Int], Ordering.Long))
    (list.view, list.order) match {
      case (GalleryView.ByRate, ListOrder.FileOrder) => byFile
      case _ if list.round.isBinary                 => byBinary(list.binaryOrder)
      case _                                        => bySum
    }
  }

  /** The list's files in its order; and `withPageId` whatever its votes (a large view's file). */
  def list(list: RoundList, files: Seq[File], inRegion: File => Boolean, withPageId: Option[Long] = None): Seq[File] =
    files.filter(f => (matches(list.filter, f) || withPageId.contains(f.pageId)) && inRegion(f)).sorted(ordering(list))

  /** The ranks of the list's files, by its rank key. */
  def ranks(list: RoundList, ordered: Seq[File]): Seq[String] = {
    val keys = ordered.map(f =>
      if (list.round.isBinary) list.binaryOrder.values(f.approvals, f.rejections).map(_.toLong)
      else f.ratedKey(list.round.averageRule))
    keys.map(Ranks.of(keys))
  }
}
