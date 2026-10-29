package db.scalikejdbc

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
    def describe: String = s"$account — $role"
  }

  /** A user name as MediaWiki normalizes it: without a `User:` prefix, `_` as a space, runs
    * of spaces as one, the first letter in title case (`Character.toTitleCase`: Georgian
    * Mkhedruli stays as it is, as MediaWiki keeps it; `ǆ` becomes `ǅ`).
    */
  def normalize(name: String): String = {
    val plain = name.replace('_', ' ').trim.replaceFirst("(?iu)^user\\s*:\\s*", "").replaceAll("\\s+", " ").trim
    if (plain.isEmpty) plain
    else {
      val first = plain.codePointAt(0)
      new String(Character.toChars(Character.toTitleCase(first))) + plain.substring(Character.charCount(first))
    }
  }

  /** The name a stored author compares by: the import stores the user's name, normalized; a
    * row imported before it the author's wikitext, read as scalawiki reads it
    * (`Image.parseUser` leaves a resolved name as it is). None for no author.
    */
  def authorKey(author: String): Option[String] =
    Some(normalize(org.scalawiki.dto.Image.parseUser(author))).filter(_.nonEmpty)

  /** The contest's conflict-of-interest people by normalized wiki account: one query of its
    * members in [[User.COI_ROLES]] and the root users, not deleted, with a wiki account. A
    * root user who is also a member is reported with their contest role.
    */
  def forContest(contestId: Long)(implicit session: DBSession = AutoSession): Map[String, Member] = {
    val roles = User.COI_ROLES.toSeq.sorted
    val rows =
      sql"""SELECT u.id, u.wiki_account, uc.role, 0 AS root FROM user_contest uc JOIN users u ON u.id = uc.user_id
            WHERE uc.contest_id = $contestId AND uc.role IN ($roles)
              AND u.deleted_at IS NULL AND u.wiki_account IS NOT NULL AND u.wiki_account <> ''
            UNION ALL
            SELECT u.id, u.wiki_account, ${User.ROOT_ROLE}, 1 AS root FROM users u
            WHERE u.is_root AND u.deleted_at IS NULL AND u.wiki_account IS NOT NULL AND u.wiki_account <> ''"""
        .map(rs => (Member(rs.long(1), rs.string(2).trim, rs.string(3)), rs.int(4) == 1))
        .list()
    rows
      .filter { case (m, _) => normalize(m.account).nonEmpty }
      // the contest role first, then by user id: one person per account
      .sortBy { case (m, root) => (root, m.userId) }
      .foldLeft(Map.empty[String, Member]) { case (acc, (m, _)) =>
        val key = normalize(m.account)
        if (acc.contains(key)) acc else acc + (key -> m)
      }
  }

  /** The person an author is, if a conflict of interest. */
  def matchOf(members: Map[String, Member])(author: Option[String]): Option[Member] =
    if (members.isEmpty) None else author.flatMap(authorKey).flatMap(members.get)

  /** The files of the contest's category that are conflicts of interest, by page id, with
    * the person each is by: one query of the category's files' authors, matched here (rows
    * imported before the author was resolved are read through [[authorKey]]).
    */
  def files(categoryId: Long, members: Map[String, Member])(implicit
      session: DBSession = AutoSession
  ): Map[Long, Member] =
    if (members.isEmpty) Map.empty
    else
      sql"""SELECT i.page_id, i.author FROM category_members cm JOIN images i ON i.page_id = cm.page_id
            WHERE cm.category_id = $categoryId AND i.author IS NOT NULL"""
        .map(rs => rs.long(1) -> rs.string(2))
        .list()
        .flatMap { case (pageId, author) => matchOf(members)(Some(author)).map(pageId -> _) }
        .toMap

  /** The files of the contest's category without a stored author: imported before the author
    * was, or by a contest that fetched no page text; an import of the contest stores it.
    */
  def filesWithoutAuthor(categoryId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"""SELECT COUNT(*) FROM category_members cm JOIN images i ON i.page_id = cm.page_id
          WHERE cm.category_id = $categoryId AND i.author IS NULL AND i.deleted_at IS NULL"""
      .map(_.long(1)).single().getOrElse(0L)
}
