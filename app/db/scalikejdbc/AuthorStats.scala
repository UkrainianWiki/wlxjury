package db.scalikejdbc

import org.intracer.wmua.AverageRule
import scalikejdbc._

/** The authors of a contest's files, with how many files each has: all the contest's (its
  * category's, those deleted on Commons too, as the contest files page lists them), or a
  * round's (its files `in` it, round_image), then with the round's votes counted per author.
  * For the authors list ([[controllers.AuthorsController]]), which only the contest's admins,
  * organizers and root see ([[User.canViewAuthors]]).
  *
  * Authors are grouped by their stored value byte for byte (`GROUP BY BINARY`), not in the
  * column's `utf8mb4_general_ci` collation: "Ivan", "ivan" and "Iván" are three authors, as the
  * contest files page's author filter tells them apart ([[ImageJdbc.categoryAuthorFiles]]).
  * Files without a stored author are one row of their own, `author` None.
  *
  * A round's votes are counted over all its jurors. A yes/no round's file has one outcome of
  * three (the maintainer's decision, 2026-11-13), so an author's three add up to their files:
  * approved (at least one yes), rejected by everyone who voted (votes cast, no yes), or not
  * voted yet (no vote cast: only votes yet to come, or no vote row at all, a file in the round
  * not assigned to anyone). Beside them the author's yes and no votes, for their approval rate
  * (yes / (yes + no) of the votes cast on their files, whatever the jurors per file). A rated
  * round's file is rated if at least one juror rated it (a ✗ is a rating, U5: a criteria vote
  * only once every criterion is), and its score is its grades' sum (a ✗ as 0) over the jurors the round's
  * [[AverageRule]] divides by: its rated votes, or every vote row (`Assigned`); a `Sum` round's
  * file over its rated votes, as its stars are drawn (`ImageWithRating.starRate`).
  *
  * Production copy (2026-11-13, warm): WLM UA 2026's 32,531 files, 231 authors, 0.12-0.15 s;
  * its round of 18,442 files 0.19 s; WLM UA 2015's round of 39,923 files 0.29 s. `images.author`
  * has no index: none is needed, the files are reached by the category's or the round's index
  * and each image by its primary key.
  */
object AuthorStats {

  /** An author's files (`author` None: the files without one), and in a round, of them: a
    * yes/no round's approved, rejected by all who voted and not voted yet (exclusive: they add
    * up to `files`), with the yes and no votes cast on them; a rated round's rated, with a ✗
    * (`worst`), and their average score in stored units, per file (None if none is rated).
    */
  final case class Row(
      author: Option[String],
      files: Int,
      approved: Int = 0,
      rejectedByAll: Int = 0,
      notVoted: Int = 0,
      yes: Int = 0,
      no: Int = 0,
      rated: Int = 0,
      worst: Int = 0,
      average: Option[Double] = None
  ) {

    /** The share of yes among the yes and no votes cast on the author's files, in percent; None
      * without any.
      */
    def approvalRate: Option[Double] = Option.when(yes + no > 0)(yes * 100.0 / (yes + no))
  }

  /** The category's files by author. */
  def ofCategory(categoryId: Long)(implicit session: DBSession = AutoSession): Seq[Row] =
    sql"""SELECT MIN(i.author), COUNT(*) FROM category_members m STRAIGHT_JOIN images i ON i.page_id = m.page_id
          WHERE m.category_id = $categoryId GROUP BY BINARY i.author"""
      .map(rs => Row(rs.stringOpt(1), rs.int(2)))
      .list()

  /** The round's files by author, with its votes: each file's votes aggregated once (the
    * round's entries in vote_round_page_rate_idx, without reading the rows), joined to the
    * round's files `in` it.
    */
  def ofRound(round: Round)(implicit session: DBSession = AutoSession): Seq[Row] = {
    val roundId = round.getId
    val denominator = round.averageRule match {
      case AverageRule.Assigned => sqls"t.n"
      case _                    => sqls"t.voted"
    }
    sql"""SELECT MIN(i.author), COUNT(*), COALESCE(SUM(t.a > 0), 0), COALESCE(SUM(t.voted > 0 AND t.a = 0), 0),
                 COUNT(*) - COALESCE(SUM(t.voted > 0), 0), COALESCE(SUM(t.a), 0), COALESCE(SUM(t.r), 0),
                 COALESCE(SUM(t.voted > 0), 0), COALESCE(SUM(t.r > 0), 0),
                 AVG(CASE WHEN t.voted > 0 THEN t.sm / $denominator END)
          FROM round_image ri STRAIGHT_JOIN images i ON i.page_id = ri.page_id
          LEFT JOIN (SELECT s.page_id, SUM(${VoteSql.positive()}) a,
                            SUM(${VoteSql.rejected()}) r, SUM(${VoteSql.voted()}) voted, SUM(${VoteSql.grade()}) sm,
                            COUNT(*) n
                     FROM vote s WHERE s.round_id = $roundId GROUP BY s.page_id) t ON t.page_id = ri.page_id
          WHERE ri.round_id = $roundId AND ri.status = ${RoundImageJdbc.Status.In}
          GROUP BY BINARY i.author"""
      .map(rs =>
        Row(rs.stringOpt(1), rs.int(2), rs.int(3), rs.int(4), rs.int(5), rs.int(6), rs.int(7), rs.int(8), rs.int(9),
          rs.bigDecimalOpt(10).map(_.doubleValue))
      )
      .list()
  }
}
