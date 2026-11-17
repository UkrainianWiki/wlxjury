package controllers

import db.scalikejdbc.{ContestJuryJdbc, User}
import modules.BlockingExecutionContext
import org.intracer.wmua.ContestJury
import play.api.i18n.I18nSupport
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, Result}
import services.jobs.{JobJdbc, JobScheduler}
import services.{AccountJoin, AccountJoinHandler}

import javax.inject.Inject

/** Joining accounts (the account join plan's J-2): the preview (`GET /admin/accounts/join`, the
  * accounts as `account`, repeated: a pair or a duplicates group, and the kept account as `kept`
  * once the performer chose it, J2: none preselected), the start (a form POST with the CSRF
  * token, one joined account into the kept one; a group is N−1 such joins, one at a time), and the
  * join's page (`GET /admin/accounts/join/:jobId`: its progress, result, Cancel and Retry).
  *
  * Who: root, or (from a contest, `contestId`) an admin of it who is an admin of every contest each
  * account is in or worked in ([[AccountJoin.mayPerform]]); everyone else gets the 403 page (logged
  * on `security.authz`), an account that doesn't exist or was retired the 404. Without `contestId`:
  * root only. Entry points: the duplicates report's "Join…" and the Edit user panel's "Join the
  * two accounts". Nothing is mailed unless the performer ticks "Tell both owners by email".
  */
class AccountJoinController @Inject() (cc: ControllerComponents, jobs: JobScheduler, blocking: BlockingExecutionContext)
    extends Secured(cc)
    with I18nSupport {

  /** The preview: the accounts (2 to [[AccountJoin.MaxAccounts]]), and with `kept` the preview of
    * joining each other one into it.
    */
  def preview(contestId: Option[Long]): EssentialAction =
    // the builder first in the body (RouteAuthSpec): root without a contest, else its admins and root
    withAuthOn(blocking)(contestId.fold[Secured.DescribedPermission](rolePermission(Set(User.ROOT_ROLE)))(c =>
      contestPermission(User.ADMIN_ROLES, Some(c)))) { user => implicit request =>
      val ids = request.queryString.getOrElse("account", Nil).map(_.trim.toLongOption)
      val kept = request.getQueryString("kept").filter(_.trim.nonEmpty).map(_.trim.toLongOption)
      withAccounts(user, contestId, ids, kept) { (contest, accounts, keptId) =>
        page(user, contest, accounts, keptId, Map.empty, None)
      }
    }

  /** Starts joining `joined` into `kept` (form fields; `account` repeated: the page's accounts, to
    * show it again), if the preview is still `signature`; `notify`: "Tell both owners by email".
    * Then the join's page; refused or changed, the preview again with why.
    */
  def start(contestId: Option[Long]): EssentialAction =
    withAuthOn(blocking)(contestId.fold[Secured.DescribedPermission](rolePermission(Set(User.ROOT_ROLE)))(c =>
      contestPermission(User.ADMIN_ROLES, Some(c)))) { user => implicit request =>
      val form = request.body.asFormUrlEncoded.getOrElse(Map.empty)
      def one(name: String) = form.get(name).flatMap(_.headOption).map(_.trim)
      val keptId = one("kept").flatMap(_.toLongOption)
      val joinedId = one("joined").flatMap(_.toLongOption)
      val ids = (form.getOrElse("account", Nil).map(_.trim.toLongOption) ++ Seq(keptId, joinedId)).distinct
      (keptId, joinedId) match {
        case (Some(k), Some(j)) if k != j =>
          withAccounts(user, contestId, ids, Some(Some(k))) { (contest, accounts, _) =>
            AccountJoin.start(user, contestId, k, j, one("signature").getOrElse(""), one("notify").contains("true"), jobs) match {
              case AccountJoin.Started(job) => Redirect(routes.AccountJoinController.job(job.id))
              case AccountJoin.Refused(p, changed) =>
                page(user, contest, accounts, Some(k), Map(j -> p), Some(if (changed) "account.join.changed" else "account.join.refused"))
              case AccountJoin.Gone => notFound(user)
              case AccountJoin.NotAllowed => denied(user, request, contestId, Seq(k, j))
            }
          }
        case _ => BadRequest(views.html.errorPage(Some(user), "error.title", "error.bad.request"))
      }
    }

  /** The join's page: who joins whom, its progress and outcome, Cancel (before its first round) and
    * Retry; for who may manage it (the job's contest's admins under the performer's rule, root).
    */
  def job(jobId: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      JobJdbc.findById(jobId).filter(_.kind == db.scalikejdbc.AccountJoinJdbc.Kind) match {
        case Some(job) if mayManage(user, job) =>
          val p = AccountJoinHandler.paramsOf(job)
          val accounts = User.findByIds(Seq(p.keptId, p.joinedId)).flatMap(u => u.id.map(_ -> u)).toMap
          Ok(views.html.accountJoinJob(user, job, accounts.get(p.keptId), accounts.get(p.joinedId),
            AccountJoinHandler.stateOf(job), p.contestId.flatMap(ContestJuryJdbc.findById)))
            .withHeaders(CACHE_CONTROL -> "no-store")
        case Some(job) =>
          Secured.logDenial(user, request, Secured.Denial(job.contestId, Some(s"job:${job.id}"), User.ADMIN_ROLES))
          notFound(user)
        case None => notFound(user)
      }
    }

  /** Root abandons a failed join after its first round step ([[AccountJoin.abandon]]): the job
    * cancelled, both accounts released, what moved left with the kept account. Back to its page.
    */
  def abandon(jobId: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      AccountJoin.abandon(user, jobId) match {
        case Right(job) => Redirect(routes.AccountJoinController.job(job.id))
        case Left(AccountJoin.AbandonRefused("error.not.found")) => notFound(user)
        case Left(refused) =>
          Redirect(routes.AccountJoinController.job(jobId)).flashing("error" -> messagesApi.preferred(request)(refused.key))
      }
    }

  private def mayManage(user: User, job: services.jobs.Job): Boolean = {
    val inJobContest = job.contestId.fold(user)(Secured.inContest(user, _))
    contestPermission(User.ADMIN_ROLES, job.contestId)(inJobContest) &&
      jobs.handler(job.kind).forall(_.mayManage(job, user))
  }

  private def notFound(user: User)(implicit request: Request[AnyContent]): Result =
    NotFound(views.html.errorPage(Some(user), "error.title", "error.not.found"))

  private def denied(user: User, request: Request[AnyContent], contestId: Option[Long], ids: Seq[Long]): Result =
    onUnAuthorized(user, request, Secured.Denial(contestId, Some(s"accounts:${ids.distinct.sorted.mkString(",")}"),
      Set(User.ADMIN_ROLE), Some("not_an_admin_of_every_contest")))

  /** Runs `f` with the contest, the accounts (2 to MaxAccounts, ascending, none retired) and the
    * kept one (one of them), if the ids parse, exist and the user may join them.
    */
  private def withAccounts(user: User, contestId: Option[Long], ids: Seq[Option[Long]], kept: Option[Option[Long]])(
      f: (Option[ContestJury], Seq[User], Option[Long]) => Result)(implicit request: Request[AnyContent]): Result = {
    val distinct = ids.flatten.distinct.sorted
    if (ids.exists(_.isEmpty) || kept.exists(_.isEmpty) || distinct.size < 2 || distinct.size > AccountJoin.MaxAccounts ||
        kept.flatten.exists(k => !distinct.contains(k)))
      BadRequest(views.html.errorPage(Some(user), "error.title", "error.bad.request"))
    else {
      val contest = contestId.flatMap(ContestJuryJdbc.findById)
      val accounts = User.findByIds(distinct).filter(_.deletedAt.isEmpty).sortBy(_.getId)
      if (contestId.nonEmpty && contest.isEmpty)
        NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
      else if (accounts.size != distinct.size) notFound(user)
      else if (!AccountJoin.mayPerform(user, accounts)) denied(user, request, contestId, distinct)
      else f(contest, accounts, kept.flatten)
    }
  }

  /** The page: the accounts with the kept-account choice, and with `kept` each other one's preview
    * (`shown`: previews computed already, by the start).
    */
  private def page(user: User, contest: Option[ContestJury], accounts: Seq[User], kept: Option[Long],
      shown: Map[Long, AccountJoin.Preview], message: Option[String])(implicit request: Request[AnyContent]): Result = {
    val contestId = contest.flatMap(_.id)
    val verified = accounts.map(_.getId).filter(db.scalikejdbc.AccountJoinJdbc.ownerVerifiedEmail(_)).toSet
    val keepable = if (verified.isEmpty) accounts.map(_.getId).toSet else verified
    val previews = kept.filter(keepable).flatMap(k => accounts.find(_.id.contains(k))).toSeq.flatMap { k =>
      accounts.filterNot(_.id == k.id).map(j => shown.getOrElse(j.getId, AccountJoin.preview(user, k, j, contestId)))
    }
    val result = Ok(views.html.accountJoin(user, contest, accounts, keepable, verified, kept.filter(keepable), previews, message))
      .withHeaders(CACHE_CONTROL -> "no-store")
    if (message.nonEmpty) result.copy(header = result.header.copy(status = CONFLICT)) else result
  }
}

object AccountJoinController {

  /** The preview's address for the accounts `ids`, from `contestId` (None: root's, outside a contest). */
  def previewUrl(contestId: Option[Long], ids: Seq[Long]): String = {
    val base = routes.AccountJoinController.preview(contestId).url
    val query = ids.distinct.map(id => s"account=$id").mkString("&")
    base + (if (base.contains('?')) "&" else "?") + query
  }
}
