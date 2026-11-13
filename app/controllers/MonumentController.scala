package controllers

import db.scalikejdbc.{ContestJuryJdbc, ImageJdbc, MonumentJdbc, Round, User}
import modules.BlockingExecutionContext
import org.intracer.wmua.ContestJury
import org.scalawiki.wlx.dto.Monument
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction}
import services.GalleryService

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/** A monument's page (the maintainer's decisions D5-D9 of 2026-11-13), which a file's monument
  * card in the large views links: the monument as its Wikipedia list has it (name, place, id,
  * year, type), its list row, Commons category and Wikidata item, and, in the contest and round
  * the file was seen in, its files there as counts with links ([[MonumentPage.InContest]]).
  *
  * For every signed-in user: the monument lists are public. The contest and the round are
  * request parameters, never trusted ([[MonumentController.context]]); without a valid context
  * the page has the public data only.
  */
class MonumentController @Inject() (
    cc: ControllerComponents,
    blocking: BlockingExecutionContext,
    galleryService: GalleryService
) extends Secured(cc)
    with I18nSupport {

  /** Find a monument by its id: the monument's page, or the form again saying there is none. */
  def list(id: Option[String]): EssentialAction = withAuth() { user => implicit request =>
    id.map(_.trim).filter(_.nonEmpty) match {
      case Some(i) if MonumentJdbc.find(i).isDefined => Redirect(routes.MonumentController.byId(i))
      case Some(i)                                    => NotFound(views.html.monumentSearch(user, Some(i)))
      case None                                       => Ok(views.html.monumentSearch(user, None))
    }
  }

  /** The monument's page; with `contest` and `round`, its files there, where the user may see
    * them. An unknown monument is a 404.
    */
  def byId(id: String, contest: Option[Long], round: Option[Long]): EssentialAction =
    withAuthOn(blocking)() { user => implicit request =>
      MonumentJdbc.find(id).fold(NotFound(views.html.errorPage(Some(user), "error.title", "error.monument.not.found"))) {
        monument => Ok(views.html.monument(user, MonumentPage(monument, MonumentController.context(user, monument.id,
          contest, round, galleryService))))
      }
    }
}

object MonumentController {

  /** The monument's files in the contest and the round, where `user` may see them:
    *
    *   - the contest (`contestId`, else the round's): only if the user is an active member of it
    *     who may open its files page ([[User.mayOpenContestFiles]]: its admins, organizers and
    *     jurors; root), checked as the user in that contest ([[Secured.inContest]]). Its files of
    *     the monument as the files page lists them for the user: all of them for those who see
    *     the organizers' page ([[User.canViewContestFiles]]), only those not deleted on Commons
    *     for a juror;
    *   - the round: only one of that contest the user may view ([[GalleryService.mayViewRound]]:
    *     a juror's own active rounds). Its files of the monument: those any juror has for those
    *     who see the round's organizer views ([[User.canViewOrgInfo]]), else only the user's own
    *     ([[ImageJdbc.roundMonumentFileCount]]): never how many files others have.
    *
    * None without a contest the user may see this way: the page shows no files then.
    */
  def context(user: User, monumentId: String, contestId: Option[Long], roundId: Option[Long],
      galleryService: GalleryService): Option[MonumentPage.InContest] = {
    val round = roundId.flatMap(Round.findById).filter(r => contestId.forall(_ == r.contestId))
    for {
      cid <- contestId.orElse(round.map(_.contestId))
      member = Secured.inContest(user, cid)
      if member.mayOpenContestFiles(cid)
      contest <- ContestJuryJdbc.findById(cid)
    } yield {
      val full = member.canViewContestFiles(cid)
      val files = contest.categoryId.map(ImageJdbc.categoryMonumentFiles(_, monumentId, liveOnly = !full).size)
      val inRound = round.filter(galleryService.mayViewRound(member, _)).map { r =>
        val own = !member.canViewOrgInfo(r)
        MonumentPage.InRound(r, ImageJdbc.roundMonumentFileCount(r.getId, monumentId, Option.when(own)(member.getId)),
          own)
      }
      MonumentPage.InContest(contest, files, inRound)
    }
  }
}

/** What a monument's page shows ([[MonumentController.byId]]): the monument, and its files in
  * the contest and round it was opened from, if the user may see them.
  */
final case class MonumentPage(monument: Monument, context: Option[MonumentPage.InContest]) {

  /** Its list page's title, if it has one (every stored monument on production's copy). */
  def listPage: Option[String] = Option(monument.page).map(_.trim).filter(_.nonEmpty)

  /** Its row in its Wikipedia list ([[MonumentPage.listUrl]]). */
  def listUrl: Option[String] = listPage.map(_ => MonumentPage.listUrl(monument))

  /** The row's anchor in the list's URL, shown with the page's title. */
  def listAnchored: Boolean = MonumentPage.hasRowAnchor(monument.id)

  /** Its Commons category (the list's `галерея`), if the list has one. */
  def commonsCategoryUrl: Option[String] =
    commonsCategory.map(c => MonumentPage.wikiUrl("commons.wikimedia.org", "Category:" + c))

  def commonsCategory: Option[String] = monument.gallery.map(_.trim).filter(_.nonEmpty)

  /** Its Wikidata item, if the list has one (V102). */
  def wikidata: Option[String] = MonumentJdbc.wikidataOf(monument)

  def wikidataUrl: Option[String] = wikidata.map(q => "https://www.wikidata.org/wiki/" + q)
}

object MonumentPage {

  /** The monument's files in a contest: their number as the contest files page lists them for
    * the user (None: the contest has no category), and in a round.
    */
  final case class InContest(contest: ContestJury, files: Option[Int], round: Option[InRound]) {

    /** The contest files page narrowed to the monument's files. */
    def filesUrl(monumentId: String): String =
      routes.GalleryController.contestFiles(contest.getId, monument = Some(monumentId)).url
  }

  /** The monument's files in a round: those any juror has, or only the user's own (`own`). */
  final case class InRound(round: Round, files: Int, own: Boolean)

  /** The host of the monument lists: uk.wikipedia, the only lists imported
    * ([[services.imports.MonumentListImportHandler]], Ukrainian contests only), as the monument
    * card links them.
    */
  val ListsHost = "uk.wikipedia.org"

  /** A page of `host`: its title with `_` for spaces and other characters percent-encoded but
    * for `/` and `:`, which MediaWiki's titles keep (subpages and namespaces).
    */
  def wikiUrl(host: String, title: String): String =
    s"https://$host/wiki/" + URLEncoder.encode(title.trim.replace(' ', '_'), StandardCharsets.UTF_8)
      .replace("%2F", "/").replace("%3A", ":")

  /** The monument's list page, at its row where the row has an anchor: uk.wikipedia's regional
    * list templates give each row its id as the anchor (ВЛП-рядок `id="{{{ID}}}"`, ВЛЗ-рядок
    * `{{anchor|{{{ID}}}}}`, both checked 2026-11-13); the special nominations' WLM-рядок
    * doesn't, and its monuments with ids of their own (00-, 88- to 99-: wlm_ua.conf's
    * `idPrefix`es) link the list page alone. A regional id's row on a nomination's list (a few
    * dozen on production's copy) has no anchor there: the browser stays at the page's top.
    */
  def listUrl(m: Monument): String = {
    val page = wikiUrl(ListsHost, m.page)
    if (hasRowAnchor(m.id)) page + "#" + URLEncoder.encode(m.id, StandardCharsets.UTF_8) else page
  }

  /** Whether the monument's list row has its id as an anchor: a regional id (its first part one
    * of Ukraine's region codes, 01 to 85).
    */
  def hasRowAnchor(id: String): Boolean =
    id.split("-").headOption.filter(_.matches("[0-9]{2}")).map(_.toInt).exists(r => r >= 1 && r <= 85)
}
