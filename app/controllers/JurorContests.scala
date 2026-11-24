package controllers

import db.scalikejdbc.{ContestJuryJdbc, Round, User, UserContest}
import org.intracer.wmua.ContestJury
import play.api.i18n.Messages
import play.api.mvc.{RequestHeader, Result, Results}
import scalikejdbc.sqls

/** A juror's contests: a person may keep a juror membership of a past year's contest, where no
  * round is open to them any more. Such a contest is left out of the contest switcher, and the
  * start page goes on from an older contest to a newer one of theirs where a round is open, so
  * that nobody waits on a "no round yet" page of last year's contest.
  */
object JurorContests {

  /** A membership whose roles are juror roles only (an organizer or admin keeps every contest). */
  def jurorOnly(membership: UserContest): Boolean =
    membership.roles.nonEmpty && membership.roles.subsetOf(User.JURY_ROLES)

  /** The contest switcher's entries (logged.scala.html), with their contests; none for one entry:
    * the user's active memberships, a juror-only one only if a round is open to them in it, the
    * current contest always. At most two queries, none for a user with one membership.
    */
  def switcherEntries(user: User): Seq[(UserContest, ContestJury)] = {
    val memberships = user.userContests.filter(_.opens)
    // a series admin's menu also lists their series ([[seriesEntries]]): one real contest is then
    // an entry too, to switch back to
    val least = if (user.seriesGranted.nonEmpty) 1 else 2
    if (memberships.size < least) Nil
    else {
      val current = (m: UserContest) => user.contestId.contains(m.contestId)
      val toCheck = memberships.filter(m => jurorOnly(m) && !current(m)).map(_.contestId)
      val open = Round.contestsWithOpenRound(user.getId, toCheck)
      val listed = memberships.filter(m => current(m) || !jurorOnly(m) || open(m.contestId))
      if (listed.size < least) Nil
      else {
        val contests = ContestJuryJdbc.findAllBy(sqls.in(ContestJuryJdbc.defaultAlias.id, listed.map(_.contestId)))
        listed.flatMap(m => contests.find(_.id.contains(m.contestId)).map(m -> _))
      }
    }
  }

  /** The contest menu's series entries (contest series plan §3.3, §5.1): one per live series the user
    * is a series admin of, "Series: <name> (Series admin)", linking to its series page; its
    * contests are never listed flat. A session's only (Secured.withSeries); one query.
    */
  def seriesEntries(user: User): Seq[db.scalikejdbc.Series] =
    if (user.seriesGranted.isEmpty) Nil
    else db.scalikejdbc.SeriesJdbc.findByIds(user.seriesGranted).filter(_.live)
      .sortBy(_.name.toLowerCase(java.util.Locale.ROOT))

  /** The contest the session is in through a series grant only (no real membership opens it), with
    * the derived role there: the menu's button names it.
    */
  def seriesCurrent(user: User): Option[(ContestJury, String)] =
    user.contestId.filterNot(c => user.userContests.exists(m => m.contestId == c && m.opens))
      .flatMap(c => user.seriesContests.find(_.contestId == c))
      .flatMap(sc => ContestJuryJdbc.findById(sc.contestId).map(_ -> sc.role))

  /** A heading of the contest menu and its entries: a series' (`Series: <name>`), "Earlier years"
    * or "Other contests"; no heading for a menu with no live series.
    */
  final case class MenuGroup(heading: Option[MenuHeading], entries: Seq[(UserContest, ContestJury)])

  sealed trait MenuHeading
  final case class SeriesHeading(name: String) extends MenuHeading
  case object EarlierYears extends MenuHeading
  case object OtherContests extends MenuHeading

  /** The contest menu's entries ([[switcherEntries]]) grouped by series (contest series plan
    * §5.1): under each live series' heading (by name) its contests of this year and last year, the
    * newest first; then the older contests of live series under "Earlier years"; then the contests
    * in no series or a retired one under "Other contests". Without a live series among them, one
    * group without a heading (as before series). One query for the series' names.
    */
  def switcherGroups(user: User, thisYear: Int = java.time.Year.now().getValue): Seq[MenuGroup] =
    groups(switcherEntries(user), ids => db.scalikejdbc.SeriesJdbc.findByIds(ids), thisYear)

  def groups(entries: Seq[(UserContest, ContestJury)], seriesOf: Seq[Long] => Seq[db.scalikejdbc.Series],
      thisYear: Int): Seq[MenuGroup] =
    if (entries.isEmpty) Nil
    else {
      val live = seriesOf(entries.flatMap(_._2.seriesId).distinct).filter(_.live).map(s => s.id -> s).toMap
      val newestFirst = Ordering.by[(UserContest, ContestJury), (Int, Long)](e => (-e._2.year, -e._2.getId))
      val (inSeries, other) = entries.partition(e => e._2.seriesId.exists(live.contains))
      if (inSeries.isEmpty) Seq(MenuGroup(None, entries))
      else {
        val (recent, earlier) = inSeries.partition(_._2.year >= thisYear - 1)
        val bySeries = recent.groupBy(_._2.seriesId.get).toSeq
          .map { case (id, es) => MenuGroup(Some(SeriesHeading(live(id).name)), es.sorted(newestFirst)) }
          .sortBy(_.heading.collect { case SeriesHeading(n) => n.toLowerCase(java.util.Locale.ROOT) })
        bySeries ++
          Option.when(earlier.nonEmpty)(MenuGroup(Some(EarlierYears), earlier.sorted(newestFirst))) ++
          Option.when(other.nonEmpty)(MenuGroup(Some(OtherContests), other.sorted(newestFirst)))
      }
    }

  /** The juror's first open round: in the current contest, else in their latest NEWER contest
    * (a higher contest id than the current one's; the highest of them) where they are a juror
    * and a round is open to them. Never an older one: a contest's final round stays active, so
    * last year's is usually still open, and a juror waiting for this year's first round must not
    * land in it without choosing it (the no-round page offers it as a switch button). Only for
    * a user who is a juror in the current contest, or has no current membership: an organizer
    * or admin stays in their contest.
    */
  def firstOpenRound(user: User): Option[Round] =
    Round.activeRounds(user).headOption.orElse {
      val current = user.contestRoles
      if (current.nonEmpty && !current.subsetOf(User.JURY_ROLES)) None
      else {
        val newer = user.userContests
          .filter(m => m.opens && m.roles.exists(User.JURY_ROLES) && user.contestId.forall(m.contestId > _))
          .map(_.contestId)
        Round.contestsWithOpenRound(user.getId, newer).maxOption
          .flatMap(id => Round.activeRounds(Secured.inContest(user, id)).headOption)
      }
    }

  /** "No round yet" for the user in the current contest: no link back to the start page, which
    * would show it again, but a Sign out button and the user's other contests to switch to (the
    * switcher's entries). With the user's own rounds of the contest that are closed to them now
    * and why (stopped, being prepared, their voting stopped: [[services.GalleryService.closedRounds]],
    * one query), headed "Your rounds are closed now" instead of "No round configured yet".
    */
  def noRoundPage(user: User)(implicit messages: Messages, request: RequestHeader): Result =
    Results.Ok(views.html.noRoundYet(user,
      switcherEntries(user).filterNot(e => user.contestId.contains(e._1.contestId)),
      services.GalleryService.closedRounds(user).map { case (round, state) => round -> state.reason }))
}
