package db.scalikejdbc

import scalikejdbc._

/** What a vote's rate means, in SQL: the fragments the queries use instead of comparing a rate
  * column themselves, the counterparts of [[org.intracer.wmua.Vote]]'s checks. Each takes the
  * rate column (`s.rate` by default, the alias most queries give `vote`); its text is fixed, so
  * a fragment may go into a query's text (`.value`) where a builder works on strings
  * ([[org.intracer.wmua.BinaryOrder]], [[org.intracer.wmua.AverageRule]]), and the same text
  * names the same column in every list's order and rank vocabulary.
  *
  * The values (`vote.rate`, NOT NULL DEFAULT 0 since V91): 0 unrated; a yes/no round's 1 and -1;
  * a rated round's stars 1…N, and -1 for its ✗ (the worst mark, `round.worst_mark`, V92), which
  * counts as 0 in every average and total ([[grade]]) and as a vote ([[voted]]).
  */
object VoteSql {

  /** The rate of `vote` (or `vote_aside`) aliased `s`. */
  val s: SQLSyntax = sqls"s.rate"

  /** The rate of `vote` (or `vote_aside`) aliased `v`. */
  val v: SQLSyntax = sqls"v.rate"

  /** The rate of `vote` aliased `r`. */
  val r: SQLSyntax = sqls"r.rate"

  /** The rate column of a statement on one table, unaliased. */
  val bare: SQLSyntax = sqls"rate"

  /** A yes, or stars (in a criteria round, every criterion rated). */
  def positive(rate: SQLSyntax = s): SQLSyntax = sqls"$rate > 0"

  /** A no, or a ✗. */
  def rejected(rate: SQLSyntax = s): SQLSyntax = sqls"$rate < 0"

  /** Not rated yet, or cleared. */
  def unrated(rate: SQLSyntax = s): SQLSyntax = sqls"$rate = 0"

  /** A yes/no round's yes, as an equality (the round's `(round_id, rate)` index reads it). */
  def selected(rate: SQLSyntax = s): SQLSyntax = sqls"$rate = 1"

  /** Any vote: yes or no, stars or ✗. */
  def voted(rate: SQLSyntax = s): SQLSyntax = sqls"$rate <> 0"

  /** A rated round's grade: the stars, a ✗ as 0. */
  def grade(rate: SQLSyntax = s): SQLSyntax = sqls"GREATEST($rate, 0)"

  /** The sum of a file's vote rows `s`' rates, grouped by file: a yes/no round's approvals less
    * its rejections. As text: a list's order and rank column, the same in every vocabulary.
    */
  val RateSum: String = "sum(s.rate)"

  /** The sum of a file's vote rows `s`' grades, grouped by file: a rated round's score sum, a ✗ as
    * 0 (the user's decision, 2026-11-05). As text, as [[RateSum]].
    */
  val GradeSum: String = s"sum(${grade().value})"

  /** The new value of a vote's `voted_at` (V97) as its rate goes from `before` to `after`, for an
    * UPDATE of the row: unchanged if the rate is the same; the database's NOW(3) for another
    * rate; NULL for a vote cleared to unrated, which has no vote time (an unrated vote is last
    * in either order by time, whatever the column holds). The database clock, as V96's account
    * changes have it, not the app's: one clock for every app process, no time zone conversion of
    * a bound value, and the time is taken by the statement that changes the rate.
    */
  def votedAt(before: Int, after: Int): SQLSyntax =
    if (after == before) sqls"voted_at"
    else if (after == 0) sqls"NULL"
    else sqls"NOW(3)"

  /** A file's rated votes in a rated round, grouped by file: any vote, a ✗ included. */
  val VotedCount: String = s"SUM(${voted().value})"
}
