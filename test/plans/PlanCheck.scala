package plans

import plans.StatementCapture.Shape
import play.api.libs.json._
import scalikejdbc._


/** EXPLAIN of captured statements, rebound to the corner, and the rules (plan §4.3).
  *
  * **Rebinding** (§4.1): a `?` or an inlined number compared (`=`, `IN`) with `jury_id`,
  * `round_id`, `page_id` or `contest_id` takes the corner's value, whatever ids the scenario
  * used; other values keep the sample's. A plan the server finds impossible for those values
  * (`Impossible WHERE`, `no matching row in const table`) is **unbound**, not a pass.
  */
object PlanCheck {

  /** The corner's ids; `pages(k)` the k-th of distinct files of the corner round, for an IN list. */
  final case class Ids(jurorId: Long, roundId: Long, pageId: Long, contestId: Long, pages: Int => Long) {
    def of(column: String): Option[Long] = column.toLowerCase match {
      case "jury_id"    => Some(jurorId)
      case "round_id"   => Some(roundId)
      case "page_id"    => Some(pageId)
      case "contest_id" => Some(contestId)
      case _            => None
    }
  }

  final case class Row(id: String, selectType: String, table: String, accessType: String, possibleKeys: String,
      key: String, ref: String, rows: String, extra: String) {
    def keys: Seq[String] = Option(key).toSeq.flatMap(_.split(",").map(_.trim)).filter(_.nonEmpty)
    def intersect: Boolean = accessType == "index_merge" && Option(extra).exists(_.contains("intersect("))
    def line: String = s"$id $selectType $table $accessType key=${Option(key).getOrElse("-")} ref=${Option(ref).getOrElse("-")} rows=$rows ${Option(extra).getOrElse("")}".trim
  }

  /** A shape's plan; `withSample`: EXPLAINed with its own sample's values, as the corner's left
    * it unbound.
    */
  final case class Plan(shape: Shape, sql: String, rows: Seq[Row], error: Option[String], withSample: Boolean = false) {
    def unbound: Boolean = rows.exists(r => Option(r.extra).exists(e =>
      e.contains("Impossible WHERE") || e.contains("no matching row in const table")))
    def summary: String = error.map("error: " + _).getOrElse(rows.map(_.line).mkString(" | "))
  }

  /** What a `?` is compared with: its column, and its place in an IN list (None for `=`). */
  final case class Placeholder(column: String, inList: Option[Int])

  private val comparedWith = """(?i)(?:\b(\w+)\s*(?:=|<=>)\s*|\b(\w+)\s+in\s*\(((?:\s*\?\s*,)*)\s*)$""".r

  /** For each `?` in order, the column it is compared with (`=`, or in an `IN` list), if any. */
  def placeholderColumns(sql: String): Seq[Option[Placeholder]] = {
    val out = Seq.newBuilder[Option[Placeholder]]
    var quote: Char = 0
    sql.indices.foreach { i =>
      val c = sql(i)
      if (quote != 0) { if (c == quote) quote = 0 }
      else if (c == '\'' || c == '"' || c == '`') quote = c
      else if (c == '?') {
        val before = sql.substring(math.max(0, i - 20000), i)
        out += comparedWith.findFirstMatchIn(before).map { m =>
          Option(m.group(1)).fold(Placeholder(m.group(2), Some(m.group(3).count(_ == '?'))))(Placeholder(_, None))
        }
      }
    }
    out.result()
  }

  private val literal = """(?i)\b((?:\w+\.)?(jury_id|round_id|page_id|contest_id))(\s*=\s*)(\d+)\b""".r

  /** The statement and its parameters bound to the corner (§4.1): a `?` or a number compared
    * by `=` with `jury_id`, `round_id`, `page_id` or `contest_id` takes the corner's value; in an
    * `IN` list, page ids take distinct files of the corner round (the list keeps its length) and
    * other ids keep the sample's; other values keep the sample's.
    */
  def rebind(sql: String, params: Seq[Any], ids: Ids): (String, Seq[Any]) = {
    val columns = placeholderColumns(sql)
    val bound = params.zipWithIndex.map { case (p, i) =>
      columns.lift(i).flatten.flatMap {
        case Placeholder(col, None) => ids.of(col)
        case Placeholder(col, Some(k)) if col.equalsIgnoreCase("page_id") => Some(ids.pages(k))
        case _ => None
      }.getOrElse(p)
    }
    val text = literal.replaceAllIn(sql, m =>
      scala.util.matching.Regex.quoteReplacement(ids.of(m.group(2)).fold(m.matched)(v => s"${m.group(1)}${m.group(3)}$v")))
    (text, bound)
  }

  /** Whether the statement reads or writes vote (or round_image), not vote_aside and the like. */
  def onVote(sql: String): Boolean = "(?i)\\b(vote|round_image)\\b(?!_)".r.findFirstIn(sql).isDefined

  def explainable(sql: String): Boolean = "(?i)^\\s*(select|update|delete|insert|replace)\\b".r.findFirstIn(sql).isDefined

  /** EXPLAINs the shape's sample rebound to the corner, in a session of its own, uncaptured; if
    * the corner's values leave the plan unbound (no such rows), EXPLAINs it again with the
    * sample's own values (`withSample`), whose rows exist. Still unbound, it is reported.
    */
  def explain(shape: Shape, ids: Ids): Plan = {
    val (sql, params) = rebind(shape.template, shape.sample.getOrElse(Nil), ids)
    val plan = explainSql(shape, sql, params)
    if (!plan.unbound) plan
    else explainSql(shape, shape.template, shape.sample.getOrElse(Nil)).copy(withSample = true)
  }

  private def explainSql(shape: Shape, sql: String, params: Seq[Any]): Plan =
    StatementCapture.pausedFor {
      try {
        val rows = DB.readOnly { implicit s =>
          SQL("EXPLAIN " + sql).bind(params.toSeq: _*).map { rs =>
            Row(rs.string("id"), rs.string("select_type"), rs.string("table"), rs.string("type"),
              rs.string("possible_keys"), rs.string("key"), rs.string("ref"), rs.string("rows"), rs.string("Extra"))
          }.list()
        }
        Plan(shape, sql, rows, None)
      } catch { case e: Exception => Plan(shape, sql, Nil, Some(e.getMessage.linesIterator.nextOption().getOrElse(e.toString))) }
    }

  /** The aliases of vote in a statement (and `vote` itself). */
  def voteAliases(sql: String): Set[String] =
    "(?i)\\b(?:from|join|update|into)\\s+vote\\b(?!_)(?:\\s+(?:as\\s+)?(\\w+))?".r.findAllMatchIn(sql)
      .flatMap(m => Option(m.group(1))).filterNot(a => Keywords(a.toLowerCase)).toSet + "vote"

  private val Keywords = Set("where", "set", "on", "join", "left", "inner", "group", "order", "limit", "using", "for",
    "union", "values", "select", "straight_join", "force", "ignore", "use", "partition", "natural", "cross", "having")

  /** The plan's rows on vote: by alias, or by a key of vote's. */
  def voteRows(plan: Plan, voteIndexes: Set[String]): Seq[Row] = {
    val aliases = voteAliases(plan.sql)
    plan.rows.filter(r => aliases(r.table) || r.keys.exists(k => k != "PRIMARY" && voteIndexes(k)))
  }

  /** R3's scope: vote read by constants alone, the only table of its select: a `ref`, `eq_ref` or
    * `const` access whose `ref` is all `const`, no IN list longer than eq_range_index_dive_limit.
    *
    * Not a range (nor an index merge): a range's rows are estimated by dives into the seeded
    * rows, unscaled, while a `ref`'s come from the injected statistics, scaled to production's
    * size; the optimizer's choice between the two is then not production's. Measured in P1: the
    * file order's region page and keyset steps chose a range on `vote_round_monument_id_idx`,
    * the corner round having one juror. Ranges are listed in the report, and checked on
    * production's copy (P2).
    */
  def constantBound(plan: Plan, row: Row): Boolean = {
    val alone = plan.rows.count(_.id == row.id) == 1
    val consts = Option(row.ref).exists(_.split(",").forall(_.trim == "const"))
    val access = Set("ref", "eq_ref", "const")(row.accessType)
    val longIn = """(?i)\bin\s*\((\s*\?\s*,){200,}""".r.findFirstIn(plan.shape.template).isDefined
    alone && consts && access && !longIn
  }

  // ---- rules ---------------------------------------------------------------------------------

  /** A named group (plan §4.3, R3 and the canary): the keys its constant-bound vote access may
    * use per server, and whether the canary must show the intersection on it.
    */
  final case class Named(group: String, expect: Map[String, Seq[String]], canary: Boolean, why: String)

  /** `r1Allow`: groups exempt from R1; `unboundAllow`: groups whose vote statements may stay
    * unbound (no rows for the corner's or the sample's values); each with a reason.
    */
  final case class Rules(named: Seq[Named], r1Allow: Map[String, String], unboundAllow: Map[String, String] = Map.empty)

  implicit val namedReads: Reads[Named] = Json.reads[Named]
  implicit val rulesReads: Reads[Rules] = Json.using[Json.WithDefaultValues].reads[Rules]

  /** test/resources/plans/rules.json, from the classpath. */
  val RulesResource = "/plans/rules.json"

  def rules(resource: String = RulesResource): Rules = Json.parse(resourceBytes(resource)).as[Rules]

  def resourceBytes(resource: String): Array[Byte] =
    scala.util.Using.resource(Option(getClass.getResourceAsStream(resource)).getOrElse(
      throw new IllegalStateException(s"$resource is not on the classpath")))(_.readAllBytes())
}
