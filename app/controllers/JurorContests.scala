package controllers

import db.scalikejdbc.{ContestJuryJdbc, Round, User, UserContest}
import org.intracer.wmua.ContestJury
import play.api.i18n.Messages
import play.api.mvc.{RequestHeader, Result, Results}
import scalikejdbc.sqls

/** A juror's contests: a person may keep a juror membership of a past year's contest, where no
  * round is open to them any more. Such a contest is left out of the contest switcher, and the
  * start page goes on to a contest of theirs where a round is open, so that nobody waits on a
  * "no round yet" page of last year's contest.
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
    val memberships = user.userContests.filter(_.active)
    if (memberships.size <= 1) Nil
    else {
      val current = (m: UserContest) => user.contestId.contains(m.contestId)
      val toCheck = memberships.filter(m => jurorOnly(m) && !current(m)).map(_.contestId)
      val open = Round.contestsWithOpenRound(user.getId, toCheck)
      val listed = memberships.filter(m => current(m) || !jurorOnly(m) || open(m.contestId))
      if (listed.size <= 1) Nil
      else {
        val contests = ContestJuryJdbc.findAllBy(sqls.in(ContestJuryJdbc.defaultAlias.id, listed.map(_.contestId)))
        listed.flatMap(m => contests.find(_.id.contains(m.contestId)).map(m -> _))
      }
    }
  }

  /** The juror's first open round: in the current contest, else in their latest other contest
    * (the highest contest id) where they are a juror and a round is open to them.
    */
  def firstOpenRound(user: User): Option[Round] =
    Round.activeRounds(user).headOption.orElse {
      val others = user.userContests
        .filter(m => m.active && m.roles.exists(User.JURY_ROLES) && !user.contestId.contains(m.contestId))
        .map(_.contestId)
      Round.contestsWithOpenRound(user.getId, others).maxOption
        .flatMap(id => Round.activeRounds(Secured.inContest(user, id)).headOption)
    }

  /** "No round yet" for the user in the current contest: no link back to the start page, which
    * would show it again, but a Sign out button and the user's other contests to switch to (the
    * switcher's entries).
    */
  def noRoundPage(user: User)(implicit messages: Messages, request: RequestHeader): Result =
    Results.Ok(views.html.noRoundYet(user, switcherEntries(user).filterNot(e => user.contestId.contains(e._1.contestId))))
}
