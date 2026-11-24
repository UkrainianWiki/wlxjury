package db.scalikejdbc

import org.intracer.wmua.LoginText
import scalikejdbc._

/** The people whose files are conflicts of interest in a contest: its members in
  * [[User.COI_ROLES]] (admins, organizers, jurors, the board, auditors and staff) and every
  * root user, by their wiki account. A file is one when its author (`images.author`, as the
  * import stores it) is one of their accounts. Nothing is precomputed: a membership change
  * applies at once.
  */
object CoiMembers {

  /** A person whose files are conflicts of interest: their account as stored, their user
    * id, and their role in the contest, or `root` for a root user who is not a member of it.
    */
  final case class Member(userId: Long, account: String, role: String) {

    /** The account and the role code, for the stored disqualification detail and the job's
      * state (not translated: they outlive the request's language).
      */
    def describe: String = s"$account — $role"

    /** The account and the role's name in the user's language (`role.*`), for the pages. */
    def label(implicit messages: play.api.i18n.Messages): String = s"$account — $roleName"

    def roleName(implicit messages: play.api.i18n.Messages): String = messages("role." + role)
  }

  /** A stored conflict-of-interest detail ([[Member.describe]]: "account — role code") with the
    * role named in the user's language, as [[Member.label]]; any other text as it is (a role that
    * is not a conflict-of-interest one, or a detail written otherwise).
    */
  def detailLabel(detail: String)(implicit messages: play.api.i18n.Messages): String = {
    val at = detail.lastIndexOf(" — ")
    val role = if (at < 0) "" else detail.substring(at + 3)
    if (User.COI_ROLES.contains(role) || role == User.ROOT_ROLE || role == SeriesAdminRole)
      Member(0L, detail.substring(0, at), role).label
    else detail
  }

  /** A user name as MediaWiki normalizes it: without a `User:` prefix, `_` as a space, runs
    * of spaces as one, no spaces at the ends (pasted ones too, [[LoginText.clean]]), the first
    * letter in title case (`Character.toTitleCase`: Georgian Mkhedruli stays as it is, as
    * MediaWiki keeps it; `ǆ` becomes `ǅ`).
    */
  def normalize(name: String): String = {
    val plain = LoginText.clean(
      LoginText.clean(name.replace('_', ' ')).replaceFirst("(?iu)^user\\s*:\\s*", "").replaceAll("\\s+", " "))
    if (plain.isEmpty) plain
    else {
      val first = plain.codePointAt(0)
      new String(Character.toChars(Character.toTitleCase(first))) + plain.substring(Character.charCount(first))
    }
  }

  /** The role code of a series admin's files (contest series plan §3.3, "COI"): `series_admin`. */
  val SeriesAdminRole = "series_admin"

  /** The contest's conflict-of-interest people by normalized wiki account: one query of its
    * members in [[User.COI_ROLES]], the live series admins of its live series (contest series plan
    * §3.3: a series admin with no real row counts; none of a retired series or a revoked grant)
    * and the root users, not deleted, with a wiki account. A series admin or root user who is also
    * a member is reported with their contest role, a root user who is a series admin as such.
    */
  def forContest(contestId: Long)(implicit session: DBSession = AutoSession): Map[String, Member] = {
    val roles = User.COI_ROLES.toSeq.sorted
    val rows =
      sql"""SELECT u.id, u.wiki_account, uc.role, 0 AS coi_rank FROM user_contest uc JOIN users u ON u.id = uc.user_id
            WHERE uc.contest_id = $contestId AND uc.role IN ($roles)
              AND u.deleted_at IS NULL AND u.wiki_account IS NOT NULL AND u.wiki_account <> ''
            UNION ALL
            SELECT u.id, u.wiki_account, $SeriesAdminRole, 1 AS coi_rank FROM contest_jury c
              JOIN series s ON s.id = c.series_id AND s.retired_at IS NULL
              JOIN series_admin sa ON sa.series_id = s.id AND sa.revoked_at IS NULL
              JOIN users u ON u.id = sa.user_id
            WHERE c.id = $contestId AND u.deleted_at IS NULL AND u.wiki_account IS NOT NULL AND u.wiki_account <> ''
            UNION ALL
            SELECT u.id, u.wiki_account, ${User.ROOT_ROLE}, 2 AS coi_rank FROM users u
            WHERE u.is_root AND u.deleted_at IS NULL AND u.wiki_account IS NOT NULL AND u.wiki_account <> ''"""
        .map(rs => (Member(rs.long(1), rs.string(2).trim, rs.string(3)), rs.int(4)))
        .list()
    rows
      .filter { case (m, _) => normalize(m.account).nonEmpty }
      // the contest role first, then a series admin, then root, then by user id: one person per account
      .sortBy { case (m, rank) => (rank, m.userId) }
      .foldLeft(Map.empty[String, Member]) { case (acc, (m, _)) =>
        val key = normalize(m.account)
        if (acc.contains(key)) acc else acc + (key -> m)
      }
  }

  /** The person a stored author is, if a conflict of interest: the import stores the author
    * normalized ([[normalize]]), as the keys are, so they compare as they are. A file imported
    * before the author was resolved holds its wikitext and doesn't match until imported again.
    */
  def matchOf(members: Map[String, Member])(author: Option[String]): Option[Member] =
    if (members.isEmpty) None else author.flatMap(members.get)

  /** The files of the contest's category that are conflicts of interest, by page id, with
    * the person each is by: the database finds the files by those authors (`IN`, in the
    * column's case-insensitive collation), and [[matchOf]] keeps the exact matches.
    */
  def files(categoryId: Long, members: Map[String, Member])(implicit
      session: DBSession = AutoSession
  ): Map[Long, Member] =
    if (members.isEmpty) Map.empty
    else
      sql"""SELECT i.page_id, i.author FROM category_members cm JOIN images i ON i.page_id = cm.page_id
            WHERE cm.category_id = $categoryId AND i.author IN (${members.keys.toSeq})"""
        .map(rs => rs.long(1) -> rs.string(2))
        .list()
        .flatMap { case (pageId, author) => matchOf(members)(Some(author)).map(pageId -> _) }
        .toMap

  /** How many of the category's files [[files]] finds, counted in the database: the authors'
    * index finds the candidates (`IN`, case-insensitive), `BINARY` keeps the exact matches, as
    * [[matchOf]] does; for the conflicts of interest's tab on the contest files page's other tabs.
    */
  def filesCount(categoryId: Long, members: Map[String, Member])(implicit
      session: DBSession = AutoSession
  ): Int =
    if (members.isEmpty) 0
    else {
      val accounts = members.keys.toSeq
      sql"""SELECT COUNT(DISTINCT cm.page_id) FROM category_members cm JOIN images i ON i.page_id = cm.page_id
            WHERE cm.category_id = $categoryId AND i.author IN ($accounts) AND BINARY i.author IN ($accounts)"""
        .map(_.int(1)).single().getOrElse(0)
    }

  /** The round's files that are conflicts of interest and not disqualified (P1b), those with
    * votes moved aside as deleted on Commons too: every file with a row in the round
    * (round_image, V84) of one of `statuses` (`in` and `deleted` by default): page id, title
    * and the person each is by, as [[files]] finds them.
    */
  def roundFiles(
      roundId: Long,
      members: Map[String, Member],
      statuses: Seq[String] = Seq(RoundImageJdbc.Status.In, RoundImageJdbc.Status.Deleted)
  )(implicit session: DBSession = AutoSession): Seq[(Long, String, Member)] =
    if (members.isEmpty || statuses.isEmpty) Nil
    else
      sql"""SELECT i.page_id, i.title, i.author
            FROM round_image ri STRAIGHT_JOIN images i ON i.page_id = ri.page_id
            WHERE ri.round_id = $roundId AND ri.status IN ($statuses) AND i.author IN (${members.keys.toSeq})
            ORDER BY ri.page_id"""
        .map(rs => (rs.long(1), rs.string(2), rs.string(3)))
        .list()
        .flatMap { case (pageId, title, author) => matchOf(members)(Some(author)).map((pageId, title, _)) }

  /** The files of the contest's category without a stored author: imported before the author
    * was, or by a contest that fetched no page text, which an import of the contest stores;
    * or whose page names nobody (`{{unknown|author}}`, only a bot uploader), which stay so.
    */
  def filesWithoutAuthor(categoryId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"""SELECT COUNT(*) FROM category_members cm JOIN images i ON i.page_id = cm.page_id
          WHERE cm.category_id = $categoryId AND i.author IS NULL AND i.deleted_at IS NULL"""
      .map(_.long(1)).single().getOrElse(0L)
}
