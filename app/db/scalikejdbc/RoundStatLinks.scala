package db.scalikejdbc

import scalikejdbc._
import services.imports.StoredAuthors

/** The counts of the round statistics page's links to the round's authors and its conflicts of
  * interest, which the page loads after it is shown (`GET /roundstat/:round/counts`,
  * roundstat-counts.js): each the number its page shows, from one grouped pass over the round's
  * files and their authors, then split in Scala by the pages' own rules.
  */
object RoundStatLinks {

  /** The counts: numbers only, never a name.
    *
    * @param authors       the authors list's number of authors ([[controllers.AuthorsPage.authors]]):
    *                      the round's `in` files' authors as shown ([[StoredAuthors.shown]]), distinct
    * @param withoutAuthor its files without one ([[controllers.AuthorsPage.withoutAuthor]]): no
    *                      stored author, a blank one, or markup that shows as nothing
    * @param conflicts     the conflicts page's open conflicts ([[CoiMembers.roundFiles]]): the `in`
    *                      and `deleted` files whose author is one of the contest's conflict-of-interest
    *                      people ([[CoiMembers.matchOf]])
    * @param disqualified  its disqualified files (the round's `dq` rows)
    * @param history       its history's events (`round_image_event`), read only when there is
    *                      neither a conflict nor a disqualified file: the link then stays only
    *                      while the round has a history
    */
  final case class Counts(authors: Int, withoutAuthor: Int, conflicts: Int, disqualified: Int, history: Option[Int])

  /** The round's counts: one grouped pass of the round's files by status and author (as the
    * authors list groups them, `BINARY`: "Ivan" and "ivan" are two), the contest's
    * conflict-of-interest people (one small query, only if the round has `in` or `deleted`
    * files), and the history count if there is neither a conflict nor a disqualified file. With
    * no such people nothing is matched, and no list of them reaches SQL.
    */
  def counts(round: Round)(implicit session: DBSession = AutoSession): Counts = {
    import RoundImageJdbc.Status
    val roundId = round.getId
    val rows =
      sql"""SELECT ri.status, MIN(i.author), COUNT(*)
            FROM round_image ri STRAIGHT_JOIN images i ON i.page_id = ri.page_id
            WHERE ri.round_id = $roundId
            GROUP BY ri.status, BINARY i.author"""
        .map(rs => (rs.string(1), rs.stringOpt(2), rs.int(3)))
        .list()
    val in = rows.collect { case (Status.In, author, n) => StoredAuthors.shown(author) -> n }
    val authors = in.flatMap(_._1).distinct.size
    val withoutAuthor = in.collect { case (None, n) => n }.sum
    val open = rows.filter { case (status, _, _) => status == Status.In || status == Status.Deleted }
    val conflicts =
      if (open.isEmpty) 0
      else {
        val matchOf = CoiMembers.matchOf(CoiMembers.forContest(round.contestId)) _
        open.collect { case (_, author, n) if matchOf(author).isDefined => n }.sum
      }
    val disqualified = rows.collect { case (Status.Dq, _, n) => n }.sum
    val history = Option.when(conflicts == 0 && disqualified == 0)(historyCount(roundId))
    Counts(authors, withoutAuthor, conflicts, disqualified, history)
  }

  /** The round's history events (all of them; the conflicts page shows the latest
    * [[RoundConflicts.HistoryLimit]]): a range of round_image_event_round_page_idx.
    */
  def historyCount(roundId: Long)(implicit session: DBSession = AutoSession): Int =
    sql"SELECT COUNT(*) FROM round_image_event WHERE round_id = $roundId".map(_.int(1)).single().getOrElse(0)

  /** Who may read the counts, in the round's contest: those who may see each of the three
    * links (its organizer info, its authors, its conflicts of interest).
    */
  def mayView(user: User, round: Round): Boolean =
    user.canViewOrgInfo(round) && user.canViewAuthors(round.contestId) && user.canViewRoundConflicts(round.contestId)
}
