package controllers

import db.scalikejdbc.{AuthorStats, ContestJuryJdbc, Round, RoundCriterion, User}
import services.imports.StoredAuthors
import modules.BlockingExecutionContext
import org.intracer.wmua.ContestJury
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction}

import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/** The authors list: the distinct authors of a contest's files with their numbers of files,
  * each linking to the contest files page filtered to their files and to their Commons user
  * page; or of a round's files, to see how diverse its authors are, with the round's votes per
  * author once it has some ([[AuthorStats]]). Sortable by each column.
  *
  * For the contest's admins and organizers, and root ([[User.canViewAuthors]]): jurors get the
  * 403 page, as on the contest files page, since the files' authors are hidden from them. A
  * round of another contest, or none, is a 404.
  */
class AuthorsController @Inject() (
    cc: ControllerComponents,
    blocking: BlockingExecutionContext
) extends Secured(cc)
    with I18nSupport {

  def list(contestId: Long, round: Option[Long], sort: Option[String]): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) {
      user => implicit request =>
        val contest = ContestJuryJdbc.findById(contestId)
        val rounds = contest.fold(Seq.empty[Round])(_ => Round.findByContest(contestId))
        val chosen = round.map(id => rounds.find(_.id.contains(id)))
        (contest, chosen) match {
          case (None, _) =>
            NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
          case (_, Some(None)) =>
            NotFound(views.html.errorPage(Some(user), "error.title", "error.round.not.found"))
          case (Some(c), _) if !user.canViewAuthors(c.getId) =>
            onUnAuthorized(user, request, Secured.Denial.contest(c.id, User.ADMIN_ROLES ++ User.ORG_COM_ROLES))
          case (Some(c), r) =>
            val page = AuthorsPage.of(c, r.flatten, rounds, AuthorsPage.Sort.parse(sort))
            Ok(views.html.authors(user, page))
        }
    }
}

/** What the authors list shows: the contest's (or `round`'s) authors, sorted, and the files
  * without one; the round's votes per author if it has any.
  *
  * @param criteriaCount a criteria round's number of criteria, which its averages are per
  *                      criterion of ([[Round.perCriterion]]); 1 for another round
  */
final case class AuthorsPage(
    contest: ContestJury,
    round: Option[Round],
    rounds: Seq[Round],
    rows: Seq[AuthorStats.Row],
    withoutAuthor: Int,
    sort: AuthorsPage.Sort,
    criteriaCount: Int = 1
) {
  import AuthorsPage._

  /** All the files listed: those of the authors and those without one. */
  val files: Int = rows.map(_.files).sum + withoutAuthor

  /** The number of authors: their names as shown, so a value still stored as wiki markup
    * (`[[User:X|Y]]`, before the author normalization job) and the account it names are one.
    */
  def authors: Int = rows.flatMap(_.author).flatMap(a => StoredAuthors.shown(Some(a))).distinct.size

  /** The most files of one author, and the share of all the files they are. */
  def largest: Option[(Int, Double)] = rows.map(_.files).maxOption.map(n => n -> share(n, files))

  /** A yes/no round with votes: its columns approved, rejected by all, not voted and the
    * approval rate.
    */
  def binaryVotes: Boolean = round.exists(_.isBinary) && voted

  /** A rated round with votes: its columns rated, average, and ✗ where it [[showsWorst]]. */
  def ratedVotes: Boolean = round.exists(_.isRated) && voted

  /** A rated round that takes a ✗ ([[Round.takesWorstMark]]), or has one (given before the
    * option was turned off): its ✗ column.
    */
  def showsWorst: Boolean = ratedVotes && (round.exists(_.takesWorstMark) || rows.exists(_.worst > 0))

  /** Any of the round's files has a vote (not one yet to come). */
  private def voted: Boolean = rows.exists(_.rated > 0)

  /** The average in stars of a row: per criterion in a criteria round, in stars of a half-star one. */
  def stars(row: AuthorStats.Row): Option[Double] =
    for (avg <- row.average; r <- round) yield r.copy(criteriaCount = criteriaCount).perCriterion(avg) / r.starUnits

  /** The list with another order: its URL. */
  def sortUrl(column: Column): String =
    routes.AuthorsController.list(contest.getId, round.flatMap(_.id), Some(sort.toggled(column).param)).url

  /** The list of another round (None: the contest's), in the same order. */
  def roundUrl(other: Option[Round]): String =
    routes.AuthorsController.list(contest.getId, other.flatMap(_.id), Some(sort.param)).url
}

object AuthorsPage {

  def of(contest: ContestJury, round: Option[Round], rounds: Seq[Round], sort: Sort): AuthorsPage = {
    val all = round.fold(contest.categoryId.fold(Seq.empty[AuthorStats.Row])(AuthorStats.ofCategory(_)))(
      AuthorStats.ofRound(_))
    // a value with markup that shows as nothing (`{{Unknown|author}}`) is no author either
    val (named, none) = all.partition(r => StoredAuthors.shown(r.author).nonEmpty)
    val criteria = round.filter(_.hasCriteria).fold(1)(r => RoundCriterion.countOf(r.getId).max(1))
    val page = AuthorsPage(contest, round, rounds, Nil, none.map(_.files).sum, sort, criteria)
    page.copy(rows = sort.sorted(named, page))
  }

  /** `n` of `of` in percent; 0 of none. */
  def share(n: Int, of: Int): Double = if (of == 0) 0.0 else n * 100.0 / of

  /** A percentage with one decimal in the language's number format: `8.5%`, `8,5%`. */
  def percent(value: Double, locale: Locale): String = String.format(locale, "%.1f%%", Double.box(value))

  /** A column of the list, by which it can be sorted, and its parameter. */
  sealed abstract class Column(val param: String)

  object Column {
    case object Name          extends Column("name")
    case object Files         extends Column("files")
    case object Approved      extends Column("approved")
    case object RejectedByAll extends Column("rejected")
    case object NotVoted      extends Column("notvoted")
    case object Approval      extends Column("approval")
    case object Rated         extends Column("rated")
    case object Worst         extends Column("worst")
    case object Average       extends Column("average")

    val all: Seq[Column] = Seq(Name, Files, Approved, RejectedByAll, NotVoted, Approval, Rated, Worst, Average)
  }

  /** The list's order: a column, ascending or descending (`-` before the parameter). The vote
    * columns sort by the author's share of files (12 of 14 rejected before 30 of 100), then
    * their number; the approval rate and the average by their values, authors without one last
    * either way (ascending, the approval rate shows the authors rejected the most first); ties
    * by name.
    */
  final case class Sort(column: Column, descending: Boolean) {

    def param: String = (if (descending) "-" else "") + column.param

    /** Clicked on `other`: this column the other way; another column, a name A-Z, the approval
      * rate from the lowest (the authors rejected the most first), another number from the
      * highest.
      */
    def toggled(other: Column): Sort =
      if (other == column) copy(descending = !descending)
      else Sort(other, other != Column.Name && other != Column.Approval)

    def sorted(rows: Seq[AuthorStats.Row], page: AuthorsPage): Seq[AuthorStats.Row] = {
      val collator = Collator.getInstance(Locale.ROOT)
      val byName: Ordering[AuthorStats.Row] = new Ordering[AuthorStats.Row] {
        def compare(a: AuthorStats.Row, b: AuthorStats.Row): Int =
          collator.compare(a.author.getOrElse(""), b.author.getOrElse(""))
      }
      def byValue(value: AuthorStats.Row => Option[Double]): Ordering[AuthorStats.Row] =
        Ordering.by((r: AuthorStats.Row) => value(r).getOrElse(Double.NegativeInfinity))(Ordering.Double.TotalOrdering)
      def ofShare(count: AuthorStats.Row => Int): Ordering[AuthorStats.Row] =
        Ordering.by((r: AuthorStats.Row) => (share(count(r), r.files), count(r)))(
          Ordering.Tuple2(Ordering.Double.TotalOrdering, Ordering.Int))
      val key: Ordering[AuthorStats.Row] = column match {
        case Column.Name     => byName
        case Column.Files    => Ordering.by(_.files)
        case Column.Approved      => ofShare(_.approved)
        case Column.RejectedByAll => ofShare(_.rejectedByAll)
        case Column.NotVoted      => ofShare(_.notVoted)
        case Column.Rated         => ofShare(_.rated)
        case Column.Worst         => ofShare(_.worst)
        case Column.Approval      => byValue(_.approvalRate)
        case Column.Average       => byValue(page.stars)
      }
      val ordered = if (descending) key.reverse else key
      // ties by name, A-Z whichever way the column goes; without a rate or an average last either way
      val missing: AuthorStats.Row => Boolean = r =>
        column == Column.Average && r.average.isEmpty || column == Column.Approval && r.approvalRate.isEmpty
      rows.sorted(Ordering.by(missing).orElse(ordered).orElse(byName))
    }
  }

  object Sort {
    val Default: Sort = Sort(Column.Files, descending = true)

    /** `files`, `-files`, `name`, ...; the default for none or an unknown one. */
    def parse(param: Option[String]): Sort =
      param.map(_.trim).filter(_.nonEmpty).flatMap { p =>
        val descending = p.startsWith("-")
        Column.all.find(_.param == p.stripPrefix("-")).map(Sort(_, descending))
      }.getOrElse(Default)
  }
}
