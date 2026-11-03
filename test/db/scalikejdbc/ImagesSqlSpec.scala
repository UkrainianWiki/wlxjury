package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import munit.FunSuite
import scalikejdbc._

class ImagesSqlSpec extends FunSuite with AutoRollbackMunitDb {

  private val imageFields =
    "i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, " +
      "i.last_round as lr_on_i, i.width as w_on_i, i.height as h_on_i, i.monument_id as mi_on_i, i.description as d_on_i, " +
      "i.size as s_on_i, i.author as a_on_i, i.mime as m_on_i"

  private val selectionFields =
    "s.id as i_on_s, s.jury_id as ji_on_s, s.created_at as ca_on_s, s.deleted_at as da_on_s, " +
      "s.round_id as ri_on_s, s.page_id as pi_on_s, s.rate as r_on_s, s.criteria_id as ci_on_s, s.monument_id as mi_on_s"

  private val allFields = imageFields + ", " + selectionFields

  private def foldSpace(s: String) = s.replaceAll("\\s+", " ").trim

  private def check(
      q: SelectionQuery,
      expectedTemplate: String,
      expectedParams: Seq[Any] = Seq.empty,
      f: SelectionQuery => SQLSyntax = _.query()
  ): Unit = {
    val syntax = f(q)
    assertEquals(foldSpace(syntax.value), foldSpace(expectedTemplate))
    assertEquals(syntax.parameters.toSeq, expectedParams)
  }

  dbTest("list - all") { implicit session =>
    check(
      SelectionQuery(),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id"""
    )
  }

  dbTest("list - by user") { implicit session =>
    check(
      SelectionQuery(userId = Some(123)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.jury_id = ?""",
      expectedParams = Seq(123L)
    )
  }

  dbTest("list - by round") { implicit session =>
    check(
      SelectionQuery(roundId = Some(234)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.round_id = ?""",
      expectedParams = Seq(234L)
    )
  }

  dbTest("list - by rate") { implicit session =>
    check(
      SelectionQuery(rate = Some(1)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.rate = ?""",
      expectedParams = Seq(1)
    )
  }

  dbTest("list - by user, round, rate") { implicit session =>
    check(
      SelectionQuery(userId = Some(2), roundId = Some(3), rate = Some(1)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.jury_id = ? and s.round_id = ? and s.rate = ?""",
      expectedParams = Seq[Any](2L, 3L, 1)
    )
  }

  dbTest("grouped - group by page list by round") { implicit session =>
    check(
      SelectionQuery(roundId = Some(3), grouped = true),
      s"""select sum(s.rate) as rate, count(s.rate) as rate_count, $imageFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.round_id = ?
        group by s.page_id""",
      expectedParams = Seq(3L)
    )
  }

  dbTest("by region - list uses bind parameter not raw string") { implicit session =>
    check(
      SelectionQuery(regions = Set("12")),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where i.monument_id like ?""",
      expectedParams = Seq("12%")
    )
  }

  dbTest("by region - stat") { implicit session =>
    def regionStatQuery(q: SelectionQuery): SQLSyntax = q.query(byRegion = true)

    check(
      SelectionQuery(),
      s"""select m.adm0, count(DISTINCT i.page_id) from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          join monument m
          on i.monument_id = m.id
          group by m.adm0""",
      f = regionStatQuery
    )
  }

  // ---- uncovered branches ----

  dbTest("idOnly=true selects only i.page_id") { implicit session =>
    check(
      SelectionQuery(roundId = Some(3L)),
      s"""select  i.page_id as pi_on_i from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where s.round_id = ?""",
      expectedParams = Seq(3L),
      f = _.query(idOnly = true)
    )
  }

  dbTest("ranked=true emits ROW_NUMBER() over orderBy in column list") { implicit session =>
    check(
      SelectionQuery(userId = Some(1L), roundId = Some(2L), order = Map("rate" -> -1)),
      s"""select  i.page_id as pi_on_i, ROW_NUMBER() over ( order by rate desc) ranked from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where s.jury_id = ? and s.round_id = ?
          order by rate desc""",
      expectedParams = Seq(1L, 2L),
      f = _.query(ranked = true, idOnly = true, noLimit = true)
    )
  }

  dbTest("count - optimised path when regions empty and not byRegion") { implicit session =>
    check(
      SelectionQuery(roundId = Some(5L)),
      "select COUNT(DISTINCT s.page_id) from selection s where s.round_id = ?",
      expectedParams = Seq(5L),
      f = _.query(count = true)
    )
  }

  dbTest("count - wrapped path when regions non-empty") { implicit session =>
    check(
      SelectionQuery(roundId = Some(5L), regions = Set("12")),
      s"""select count(t.pi_on_i) from (select  i.page_id as pi_on_i from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where s.round_id = ? and i.monument_id like ? ) t""",
      expectedParams = Seq(5L, "12%"),
      f = _.query(count = true)
    )
  }

  dbTest("limit appends LIMIT N OFFSET M to query") { implicit session =>
    check(
      SelectionQuery(
        roundId = Some(3L),
        limit = Some(Limit(pageSize = Some(5), offset = Some(10)))
      ),
      s"""select  $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where s.round_id = ? LIMIT 5 OFFSET 10""",
      expectedParams = Seq(3L)
    )
  }

  dbTest("noLimit=true suppresses LIMIT clause") { implicit session =>
    val q = SelectionQuery(
      roundId = Some(3L),
      limit = Some(Limit(pageSize = Some(5), offset = Some(10)))
    )
    val withLimit    = foldSpace(q.query().value)
    val withoutLimit = foldSpace(q.query(noLimit = true).value)
    assert(withLimit.endsWith("LIMIT 5 OFFSET 10"), s"expected LIMIT suffix, got: $withLimit")
    assert(!withoutLimit.contains("LIMIT"),          s"expected no LIMIT, got: $withoutLimit")
  }

  dbTest("multi-region list includes monument JOIN and adm0 IN clause") { implicit session =>
    check(
      SelectionQuery(regions = scala.collection.immutable.SortedSet("07", "08")),
      s"""select  $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          join monument m on i.monument_id = m.id
          where m.adm0 in (?, ?)""",
      expectedParams = Seq("07", "08")
    )
  }

  dbTest("byRegion=true with subRegions=true uses adm1 column") { implicit session =>
    def regionStatQuery(q: SelectionQuery): SQLSyntax = q.query(byRegion = true)

    check(
      SelectionQuery(subRegions = true),
      s"""select m.adm1, count(DISTINCT i.page_id) from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          join monument m
          on i.monument_id = m.id
          group by m.adm1""",
      f = regionStatQuery
    )
  }
}
