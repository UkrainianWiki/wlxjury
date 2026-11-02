package controllers

import db.scalikejdbc._
import db.scalikejdbc.rewrite.ImageDbNew.Limit
import org.intracer.wmua._
import org.intracer.wmua.ImageWithRating.Ranks
import org.scalawiki.wlx.dto.Monument
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

import scala.collection.mutable.ListBuffer

/** The lists of all the jurors' votes on a round (the by-rate gallery, the file list, the large
  * view's files around one) against a model of them computed from the votes in memory: each
  * page's files, aggregates and ranks, the totals and rank counts, positions and neighbourhoods,
  * over ties that span pages, every vote filter (empty lists too), regions (one, several through
  * the monument table, a Віа Регіа sub-region), binary orders, a criteria round, and the page
  * past the end. Whatever reads the lists (the grouped query, B12's deferred page, the
  * `round_image` counts of docs/plans/2026-11-02-round-images-two-step.md P2b) must give these.
  */
class GroupedListParitySpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  import GroupedListModel._

  private val PageSize = 3

  /** 40 files in regions 07 (sub-regions 07-101, 07-102) and 14, every 10th without a
    * monument id; 4 jurors, each file voted on by 2-4 of them with `rate`'s rates.
    */
  private def setUp(rates: Rates, rate: scala.util.Random => Int, hasCriteria: Boolean = false,
      specialNomination: Option[String] = None): (Round, Seq[File]) = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val round = roundDao.create(Round(None, 1, contestId = contest.getId, rates = rates, active = true,
      hasCriteria = hasCriteria, specialNomination = specialNomination))
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
      image -> voters.map(j => Selection(image, j, round, rate(random)))
    }
    selectionDao.batchInsert(votes.flatMap(_._2))
    (round, votes.map { case (image, s) => File(image.pageId, image.monumentId, s.map(_.rate)) })
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
          check(s"page $p votes", page.map(_.selection.map(_.rate).sorted), slice.map(_.rates.sorted))
        else {
          check(s"page $p sums", page.map(_.selection.map(_.rate).sum), slice.map(_.sum))
          check(s"page $p vote counts", page.map(_.countFromDb), slice.map(_.rates.size))
          if (list.round.isBinary)
            check(s"page $p tallies", page.map(f => (f.approvals, f.rejections)), slice.map(f => (f.approvals, f.rejections)))
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

  private def inRegion(region: Option[String])(f: File): Boolean = region.forall(r => f.region.contains(r))

  "all the jurors' lists of a rated round" should {
    "be the model's, in each filter, region and order" in {
      val (round, files) = setUp(Round.ratesById(5), _.nextInt(4))
      val all = lists(round, VoteFilter.ofRound(round) :+ VoteFilter.Rate(2), Seq(ListOrder.ByRate, ListOrder.FileOrder),
        Seq(None, Some("07"), Some("14"), Some("99")))
      val problems = all.flatMap(l => compare(s"${l.view.name} ${l.filter.param} ${l.order.param} ${l.regionParam}", l,
        files, inRegion(l.region)))
      (problems must_== Nil) and
        // empty lists among them: region 99
        (all.count(_.count() == 0) must be_>(0))
    }
  }

  "all the jurors' lists of a yes/no round" should {
    "be the model's, in each filter, region and binary order" in {
      val (round, files) = setUp(Round.binaryRound, r => r.nextInt(3) - 1)
      val all = lists(round, VoteFilter.ofRound(round), BinaryOrder.all.map(ListOrder.Binary) :+ ListOrder.FileOrder,
        Seq(None, Some("07"), Some("99")))
      val fileLists = for (order <- BinaryOrder.all; filter <- VoteFilter.ofRound(round))
        yield RoundList(round, 0, None, filter, ListOrder.Binary(order), GalleryView.FileList)
      val problems = (all ++ fileLists).flatMap(l => compare(s"${l.view.name} ${l.filter.param} ${l.order.param} ${l.regionParam}",
        l, files, inRegion(l.region)))
      problems must_== Nil
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
        list.around(id).map(h => (h.files.map(_.pageId), h.index)) must_==
          Some((expected.slice(from, from + Neighbourhood.Window).map(_.pageId), i - from))
      }
    }
  }

  "all the jurors' lists of a criteria round" should {
    "be the model's" in {
      val (round, files) = setUp(Round.ratesById(5), _.nextInt(16), hasCriteria = true)
      val all = lists(round, Seq(VoteFilter.All, VoteFilter.Rated), Seq(ListOrder.ByRate), Seq(None, Some("14")))
      all.flatMap(l => compare(s"${l.view.name} ${l.filter.param} ${l.regionParam}", l, files, inRegion(l.region))) must_== Nil
    }
  }

  "all the jurors' lists in several regions or a sub-region, through the monument table" should {
    "be the model's in two regions" in {
      val (round, files) = setUp(Round.ratesById(5), _.nextInt(4))
      val problems = Seq(GalleryView.ByRate, GalleryView.FileList).flatMap { view =>
        val list = RoundList(round, 0, view = view)
        val q = list.query.copy(regions = Set("07", "14"))
        // files without a monument id have no region
        val expected = GroupedListModel.list(list, files, _.region.nonEmpty)
        val ranks = GroupedListModel.ranks(list, expected)
        val counts = Ranks(q.keyCounts(list.rankKey.columns))
        val pages = (0 to expected.size / PageSize).map(p =>
          q.copy(limit = Some(Limit(Some(PageSize), Some(p * PageSize)))).list())
        Seq(
          Option.when(q.count() != expected.size)(s"$view count ${q.count()}"),
          Option.when(pages.flatten.map(_.pageId) != expected.map(_.pageId))(s"$view pages ${pages.flatten.map(_.pageId)}"),
          Option.when(pages.flatten.map(f => counts(list.rankKey.values(f))) != ranks)(s"$view ranks")
        ).flatten
      }
      problems must_== Nil
    }

    "be the model's in a Віа Регіа sub-region" in {
      val (round, files) = setUp(Round.ratesById(5), _.nextInt(4), specialNomination = Some("Віа Регіа"))
      val all = lists(round, Seq(VoteFilter.All, VoteFilter.Rated), Seq(ListOrder.ByRate), Seq(Some("07-101"), Some("07-102")))
      (all.map(_.query.subRegions).distinct must_== Seq(true)) and
        (all.flatMap(l => compare(s"${l.view.name} ${l.filter.param} ${l.regionParam}", l, files,
          f => l.region.exists(r => f.monumentId.exists(_.take(6) == r)))) must_== Nil)
    }
  }
}

/** All the jurors' lists of a round computed in memory from its votes: which files, in what
  * order, with what ranks.
  */
object GroupedListModel {

  /** A file of the round: its monument id and its votes' rates. */
  final case class File(pageId: Long, monumentId: Option[String], rates: Seq[Int]) {
    def sum: Int = rates.sum
    def approvals: Int = rates.count(_ > 0)
    def rejections: Int = rates.count(_ < 0)

    /** Its top-level region, as `ImageDbNew.regionOf` and the monument table's adm0 have it. */
    def region: Option[String] = monumentId.map(_.split("-", 2).head.take(3)).filter(_.nonEmpty)
  }

  def matches(filter: VoteFilter, file: File): Boolean = filter match {
    case VoteFilter.All     => true
    case VoteFilter.Rated   => file.rates.exists(_ > 0)
    case VoteFilter.Unrated => file.rates.contains(0)
    case f                  => f.rate.exists(file.rates.contains)
  }

  /** NULL first ascending, as MariaDB sorts it. */
  private val monumentOrder: Ordering[Option[String]] = Ordering.Option(Ordering.String)

  /** The list's order: a file list by its rank key (ties by monument and page id in a rated
    * round, by page id in a yes/no one), by rate by the rate sum or a binary order, or in file
    * order.
    */
  def ordering(list: RoundList): Ordering[File] = {
    val byFile: Ordering[File] = Ordering.by((f: File) => (f.monumentId, f.pageId))(Ordering.Tuple2(monumentOrder, Ordering.Long))
    val bySum: Ordering[File] = Ordering.by((f: File) => -f.sum).orElse(byFile)
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
      if (list.round.isBinary) list.binaryOrder.values(f.approvals, f.rejections) else Seq(f.sum))
    keys.map(Ranks.of(keys))
  }
}
