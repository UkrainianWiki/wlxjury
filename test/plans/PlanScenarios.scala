package plans

import controllers.{GalleryView, ListOrder, Pager, RoundList, VoteFilter}
import plans.PlanSeed.{CornerFiles, Corner, Images}
import plans.StatementCapture.within
import db.scalikejdbc.{CategoryLinkJdbc, ContestJuryJdbc, MonumentJdbc, Round, RoundStatLinks, RoundUser, User, VoteJdbc}
import org.intracer.wmua.{CommentJdbc, Image}
import services.{GalleryService, TestRoundServices}

/** What QueryPlanSpec runs, as the corner's juror, each in its own capture context. The SQL is
  * the code's; the list of calls follows the juror's pages (GalleryController.galleryOf, the
  * large view's showRound and vote): kept by hand, so a new page or job adds a scenario here.
  */
object PlanScenarios {

  private val gallery = new GalleryService

  /** The juror's gallery in every order and tab (first and last page), a region, a file's
    * page, the large view, a vote from the large view, and the juror's counts.
    */
  def juror(corner: Corner): Seq[String] = {
    val round = corner.round
    val juror = corner.juror
    val jurorId = corner.jurorId
    val orders = Seq(ListOrder.FileOrder, ListOrder.ByRate, ListOrder.LatestVote, ListOrder.OldestVote)
    def list(order: ListOrder, filter: VoteFilter = VoteFilter.All, region: Option[String] = None) =
      RoundList(round, jurorId, region, filter, order, GalleryView.Gallery)

    def page(l: RoundList): Unit = {
      gallery.viewerRounds(juror, round)
      gallery.mayViewRound(juror, round)
      val counts = l.jurorCounts()
      val total = counts.map(_.total(l.filter, l.region)).getOrElse(l.count())
      val first = Pager(1, total, l.pageSize)
      // the Comments menu's number (with the filter on, the list's own total); the Votes choices
      // count what they open, from the counts above (decision 2 = A: no count of all the juror's
      // files)
      if (!l.comments) l.commentedCount()
      val files = l.files(first)
      // the page's comment badges
      CommentJdbc.countsByRoom(corner.contestId, files.map(_.pageId))
      if (first.pages > 1) l.files(Pager(first.pages, total, l.pageSize))
    }

    val galleries = for {
      order <- orders
      filter <- VoteFilter.ofRound(round)
    } yield {
      val name = s"gallery ${order.param} ${filter.param}"
      within(name)(page(list(order, filter)))
      name
    }

    // the toggle "Only with comments" (stack/128): the juror's files with a comment in the contest
    val withComments = orders.map { order =>
      val name = s"gallery ${order.param} with comments"
      within(name)(page(list(order).withComments(true)))
      name
    }

    val more = Seq(
      "gallery region" -> (() => page(list(ListOrder.FileOrder, region = Some("07")))),
      "gallery at a file" -> (() => { list(ListOrder.ByRate).position(corner.pageId); () }),
      "large view" -> (() => {
        val hood = list(ListOrder.FileOrder).around(corner.pageId)
        list(ListOrder.LatestVote).around(corner.pageId)
        // the strip's comment badges
        hood.foreach(h => CommentJdbc.countsByRoom(corner.contestId, h.following.map(_.pageId)))
        VoteJdbc.findBy(corner.pageId, jurorId, corner.roundId)
        CommentJdbc.findBySubjectAndContest(corner.pageId, corner.contestId)
        MonumentJdbc.find(f"${corner.pageId % 27 + 1}%02d-101-${corner.pageId}%05d")
        ()
      }),
      "vote" -> (() => {
        // from the large view by rate: the file's place, the vote, where it moved
        val byRate = list(ListOrder.ByRate)
        val unrated = corner.pageId + 1
        val hood = byRate.around(unrated)
        RoundUser.isStopped(corner.roundId, jurorId)
        VoteJdbc.rate(unrated, jurorId, corner.roundId, 3)
        hood.foreach(h => byRate.voteMoved(unrated, h.position, 3))
        // and from the unrated tab
        val next = unrated + 1
        val tab = list(ListOrder.FileOrder, VoteFilter.Unrated)
        val hood2 = tab.around(next)
        VoteJdbc.rate(next, jurorId, corner.roundId, 2)
        hood2.foreach(h => tab.voteMoved(next, h.position, 2))
        ()
      }),
      "counts" -> (() => {
        gallery.rateDistribution(juror, round)
        User.findRoundJurorsToView(corner.roundId)
        ()
      })
    ).map { case (name, run) => within(name)(run()); name }

    // the monument filter (the round filters proposal v4, PR-2): a gallery of one monument, the
    // large view's line into it, a large view and a vote inside it; a file the scenarios above
    // don't vote on. The seed gives every file its own monument id.
    def monumentOf(pageId: Long) = f"${pageId % 27 + 1}%02d-101-$pageId%05d"
    val inMonument = corner.pageId + 3
    val monument = Seq(
      "gallery monument" -> (() => page(list(ListOrder.FileOrder).withMonument(Some(monumentOf(inMonument))))),
      "large view in monument filter" -> (() => {
        val all = list(ListOrder.FileOrder)
        all.around(inMonument).foreach(h => all.monumentLine(h.file))
        all.withMonument(Some(monumentOf(inMonument))).around(inMonument)
        ()
      }),
      "vote in monument filter" -> (() => {
        val tab = list(ListOrder.FileOrder, VoteFilter.Unrated).withMonument(Some(monumentOf(inMonument)))
        val hood = tab.around(inMonument)
        VoteJdbc.rate(inMonument, jurorId, corner.roundId, 4)
        hood.foreach(h => tab.voteMoved(inMonument, h.position, 4))
        ()
      })
    ).map { case (name, run) => within(name)(run()); name }

    // the author filter (the round filters proposal v4, PR-4; staff only, a juror's `author=` is
    // dropped): the corner juror's list as staff view it, of the seed's heavy author (3,000 of its
    // 20,000 files), a large view and a vote inside it (as a root juror's own list would vote), and
    // all the jurors' list of the author; images' author index (V104) reads the author's files
    // an unrated file of the author's in the corner round that no scenario above votes on
    val inAuthor: Long = (Images - CornerFiles + 1 until Images).map(_.toLong)
      .find(p => p % 40 < 6 && p % 10 != 0 && p > corner.pageId + 3).getOrElse(corner.pageId + 4)
    val author = Seq(
      "gallery author" -> (() => page(list(ListOrder.FileOrder).withAuthor(Some(PlanSeed.HeavyAuthor)))),
      "gallery author by rate" -> (() => page(list(ListOrder.ByRate).withAuthor(Some(PlanSeed.HeavyAuthor)))),
      "large view in author filter" -> (() => {
        val inList = list(ListOrder.FileOrder).withAuthor(Some(PlanSeed.HeavyAuthor))
        inList.around(inAuthor)
        list(ListOrder.LatestVote).withAuthor(Some(PlanSeed.HeavyAuthor)).around(inAuthor)
        ()
      }),
      "vote in author filter" -> (() => {
        val tab = list(ListOrder.FileOrder, VoteFilter.Unrated).withAuthor(Some(PlanSeed.HeavyAuthor))
        val hood = tab.around(inAuthor)
        VoteJdbc.rate(inAuthor, jurorId, corner.roundId, 4)
        hood.foreach(h => tab.voteMoved(inAuthor, h.position, 4))
        ()
      }),
      "gallery author all jurors" -> (() => {
        val all = RoundList(round, 0, order = ListOrder.FileOrder).withAuthor(Some(PlanSeed.HeavyAuthor))
        val total = all.count()
        all.files(Pager(1, total, all.pageSize))
        all.regionCounts()
        ()
      })
    ).map { case (name, run) => within(name)(run()); name }

    // the juror's comments report of their own list (the round filters proposal v4 §2.4): its
    // files with a comment in the contest by the latest comment, its total and tabs from the
    // juror's counts with comments, the page's comments and the report's comment total, and a
    // large view in it
    val report = Seq(
      "juror comments report" -> (() => {
        gallery.viewerRounds(juror, round)
        gallery.mayViewRound(juror, round)
        val l = list(ListOrder.FileOrder).withComments(true).recentComments
        val counts = l.jurorCounts()
        val total = counts.map(_.total(l.filter, l.region)).getOrElse(l.count())
        val files = l.files(Pager(1, total, l.pageSize))
        l.commentsOf(files)
        l.commentCount()
        l.around(files.headOption.fold(corner.pageId)(_.pageId))
        ()
      })
    ).map { case (name, run) => within(name)(run()); name }

    galleries ++ withComments ++ more ++ monument ++ author ++ report
  }

  val RoundStatScenario = "roundstat"

  /** An organizer's round statistics page (RoundController.roundStat and roundStatCounts) on
    * the corner round: the comments link's counts (inline), and the authors and conflicts
    * links' grouped pass and the history count (the counts endpoint, which reads the history
    * only without conflicts: here always).
    */
  def organizer(corner: Corner): Seq[String] = {
    within(RoundStatScenario) {
      CommentJdbc.roundCounts(corner.contestId, corner.roundId)
      RoundStatLinks.counts(corner.round)
      RoundStatLinks.historyCount(corner.roundId)
      ()
    }
    Seq(RoundStatScenario)
  }

  val DistributionScenario = "distribution"

  /** Last (the plan's §5): a small round of the corner's contest filled by
    * RoundDistributionHandler (a fake Commons with no files), then "Distribute new files". For
    * the statement inventory and R1 only: images and round_image aren't injected, and it writes
    * vote rows after the injection.
    */
  def distribution(corner: Corner): Unit = within(DistributionScenario) {
    val contest = ContestJuryJdbc.findById(corner.contestId).get
    val files = (PlanSeed.Images - 299 to PlanSeed.Images).map(id => Image(id.toLong, s"File:Query plan $id.jpg", None, None, 4000, 3000, None))
    CategoryLinkJdbc.addToCategory(contest.categoryId.get, files)
    val others = (1 to 2).map(i =>
      User.create(User(s"Distribution juror $i", s"distribution-juror-$i@plans.test", None, Set("jury"), contestId = contest.id)))
    val roundService = TestRoundServices.roundService()
    val round = roundService.createNewRound(
      Round(None, PlanSeed.PastRounds + 2, Some("Distributed round"), corner.contestId, distribution = 2, active = true),
      (corner.juror +: others).map(_.getId))
    roundService.distributeNewImages(round.getId)
    ()
  }
}
