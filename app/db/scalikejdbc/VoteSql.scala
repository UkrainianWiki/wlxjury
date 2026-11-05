package db.scalikejdbc

import scalikejdbc._

/** What a vote's rate means, in SQL: the fragments the queries use instead of comparing a rate
  * column themselves, the counterparts of [[org.intracer.wmua.Vote]]'s checks. Each takes the
  * rate column (`s.rate` by default, the alias most queries give `vote`); its text is fixed, so
  * a fragment may go into a query's text (`.value`) where a builder works on strings
  * ([[org.intracer.wmua.BinaryOrder]], [[org.intracer.wmua.AverageRule]]), and the same text
  * names the same column in every list's order and rank vocabulary.
  *
  * The values: 0 unrated; a yes/no round's 1 and -1; a rated round's stars 1…N.
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

  /** A no. */
  def rejected(rate: SQLSyntax = s): SQLSyntax = sqls"$rate < 0"

  /** Not rated yet, or cleared. */
  def unrated(rate: SQLSyntax = s): SQLSyntax = sqls"$rate = 0"

  /** A yes/no round's yes, as an equality (the round's `(round_id, rate)` index reads it). */
  def selected(rate: SQLSyntax = s): SQLSyntax = sqls"$rate = 1"

  /** Any vote: yes or no, or stars. */
  def voted(rate: SQLSyntax = s): SQLSyntax = sqls"$rate <> 0"

  /** A rated round's grade: the stars, anything below as 0. */
  def grade(rate: SQLSyntax = s): SQLSyntax = sqls"GREATEST($rate, 0)"
}
