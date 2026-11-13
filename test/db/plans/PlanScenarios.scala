package db.plans

import controllers.{GalleryView, ListOrder, Pager, RoundList, VoteFilter}
import db.plans.PlanSeed.Corner
import db.plans.StatementCapture.within
import db.scalikejdbc.{CategoryLinkJdbc, ContestJuryJdbc, MonumentJdbc, Round, RoundUser, User, VoteJdbc}
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
      l.files(first)
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

    val more = Seq(
      "gallery region" -> (() => page(list(ListOrder.FileOrder, region = Some("07")))),
      "gallery at a file" -> (() => { list(ListOrder.ByRate).position(corner.pageId); () }),
      "large view" -> (() => {
        list(ListOrder.FileOrder).around(corner.pageId)
        list(ListOrder.LatestVote).around(corner.pageId)
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

    galleries ++ more
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
