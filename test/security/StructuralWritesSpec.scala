package security

import org.specs2.mutable.Specification

/** The tripwire beside the round-first rule's run-time guard
  * (docs/plans/2026-11-03-write-path-simplification.md, W2): raw SQL that writes `vote`,
  * `vote_aside`, `criteria_rate`, `round_image`, `round_image_event` (V88), `round_image_dirty`
  * (V89), `round_criterion` or a monument id copy is written only in these methods. Each either `require`s its round (or file) locks at run time
  * ([[db.scalikejdbc.RoundWrites]]) or is listed with the lock it takes itself. A new raw write
  * elsewhere fails here until it is routed through a guarded writer or listed with its reason.
  * Writes through CRUDMapper or QueryDSL (`updateById`, `insert.into`) are guarded at run time
  * where they touch the tables (`ImageJdbc.updateIn`'s monument id), not scanned.
  */
class StructuralWritesSpec extends Specification {

  /** file#method (an object's member) -> its lock rule. */
  private val Allowed: Map[String, String] = Map(
    // guarded at run time (RoundWrites.requireRounds / requireFiles)
    "app/db/scalikejdbc/VoteJdbc.scala#deleteVotes" -> "requireRounds after locking the votes",
    "app/db/scalikejdbc/ImageJdbc.scala#moveVotesAside" -> "requireRounds",
    "app/db/scalikejdbc/ImageJdbc.scala#moveVotesBack" -> "requireRounds",
    "app/db/scalikejdbc/ImageJdbc.scala#syncSelectionMonumentId" -> "its callers updateIn / updateMonumentIdIn requireFiles",
    "app/db/scalikejdbc/RoundImageJdbc.scala#addVoted" -> "requireRounds",
    "app/db/scalikejdbc/RoundImageJdbc.scala#disqualify" -> "requireRounds (RoundConflicts.disqualify locks the round first)",
    "app/db/scalikejdbc/RoundImageJdbc.scala#syncChunk" -> "its caller sync requireRounds",
    "app/db/scalikejdbc/RoundImageJdbc.scala#syncMonumentId" -> "requireFiles",
    "app/db/scalikejdbc/RoundCounts.scala#writeCounts" ->
      "requireRounds (the recompute's window: the round FOR UPDATE first, then its rows, then their dirty entries)",
    "app/db/scalikejdbc/RoundImageEventJdbc.scala#record" ->
      "requireRounds (its callers RoundImageJdbc.disqualify / requalifyRows, after the rows: last)",
    // their own round lock, recorded
    "app/db/scalikejdbc/CriterionJdbc.scala#updateIn" -> "the round FOR UPDATE first, then its votes (RoundCriterion.update)",
    "app/db/scalikejdbc/CriterionJdbc.scala#insert" -> "a new round's criteria, in its creating transaction (the inserted round row)",
    "app/db/scalikejdbc/RoundCounts.scala#clear" ->
      "the recompute's window: its own round FOR UPDATE (lockRound, recorded), the entries it locked FOR UPDATE",
    "app/db/scalikejdbc/RoundCounts.scala#fileWindow" ->
      "entries of a round that no longer exists (none to lock); else the round FOR UPDATE first (lockRound)",
    "app/services/distribution/RoundImageCheckHandler.scala#monumentStep" ->
      "the repair job's monument fix: the round FOR UPDATE (lockRound), then images in share mode, then round_image by key",
    "app/services/distribution/RoundImageCheckHandler.scala#orphansStep" ->
      "criteria rates of no vote: their votes looked up FOR UPDATE first; no round has them",
    // a juror's own vote: row-level by design (never the round)
    "app/db/scalikejdbc/VoteJdbc.scala#rateIn" -> "the juror's vote row by primary key, then round_user and round_criterion shared",
    "app/org/intracer/wmua/CriteriaRate.scala#updateRateIn" -> "the juror's vote row FOR UPDATE, then round_criterion shared, then criteria_rate",
    "app/db/scalikejdbc/RoundCounts.scala#markFile" ->
      "a juror's vote marks its own file, last in its transaction (its callers rateIn / updateRateIn): no round lock by design"
  )

  private val statement =
    ("""(?i)(INSERT( IGNORE)? INTO|REPLACE INTO|UPDATE|DELETE( \w+)? FROM)\s+""" +
      """(vote|vote_aside|criteria_rate|round_image|round_image_event|round_image_dirty|round_criterion)\b""" +
      """|UPDATE \$table|DELETE \w+ FROM \$table|UPDATE images SET monument_id""").r

  /** An object's or class's member def: two spaces in, so a local def inside it doesn't count. */
  private val member = """(?m)^  (?:(?:private|protected)(?:\[\w+\])? |override |final )*def\s+(\w+)""".r

  private def writers: Set[String] =
    SourceScan.files("app", ".scala").flatMap { source =>
      statement.findAllMatchIn(source.text).map { m =>
        val enclosing = member.findAllMatchIn(source.text.substring(0, m.start)).toSeq.lastOption.map(_.group(1)).getOrElse("?")
        s"${source.path}#$enclosing"
      }
    }.toSet

  "the round's structural writes in raw SQL" should {
    "be only in the guarded writers and the listed ones" in {
      (writers -- Allowed.keySet) must beEmpty
    }
    "list no writer that no longer writes" in {
      (Allowed.keySet -- writers) must beEmpty
    }
  }
}
