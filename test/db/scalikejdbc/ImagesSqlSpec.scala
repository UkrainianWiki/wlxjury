package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import munit.FunSuite
import scalikejdbc._

class ImagesSqlSpec extends FunSuite with AutoRollbackMunitDb {

  private val imageFields =
    "i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, " +
      "i.last_round as lr_on_i, i.width as w_on_i, i.height as h_on_i, i.monument_id as mi_on_i, i.description as d_on_i, " +
      "i.size as s_on_i, i.author as a_on_i, i.mime as m_on_i, i.media_type as mt_on_i, i.deleted_at as da_on_i"

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

  dbTest("grouped - group by page list by round, without a yes/no round's tally") { implicit session =>
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

  dbTest("grouped with tally - a yes/no round's approvals and rejections apart") { implicit session =>
    check(
      SelectionQuery(roundId = Some(3), grouped = true, tally = true),
      s"""select sum(s.rate) as rate, count(s.rate) as rate_count, sum(s.rate > 0) as approvals, sum(s.rate < 0) as rejections, $imageFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.round_id = ?
        group by s.page_id""",
      expectedParams = Seq(3L)
    )
  }

  dbTest("tally - only a grouped list's rows: a count, a juror's rows unchanged") { implicit session =>
    val grouped = SelectionQuery(roundId = Some(3), grouped = true)
    assertEquals(grouped.copy(tally = true).query(count = true).value, grouped.query(count = true).value)
    assertEquals(grouped.copy(tally = true).query(idOnly = true).value, grouped.query(idOnly = true).value)
    val juror = SelectionQuery(userId = Some(2), roundId = Some(3))
    assertEquals(juror.copy(tally = true).query().value, juror.query().value)
  }

  dbTest("by region - list uses bind parameter not raw string") { implicit session =>
    check(
      SelectionQuery(regions = Set("12")),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where s.monument_id like ? and NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, '-', 1), 3), '') = ?""",
      expectedParams = Seq("12%", "12")
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

  dbTest("idOnly=true selects only the page id, from selection alone") { implicit session =>
    check(
      SelectionQuery(roundId = Some(3L)),
      s"""select  s.page_id as pi_on_i from selection s
          where s.round_id = ? and s.page_id is not null""",
      expectedParams = Seq(3L),
      f = _.query(idOnly = true)
    )
  }

  dbTest("ranked=true emits ROW_NUMBER() over orderBy in column list") { implicit session =>
    check(
      SelectionQuery(userId = Some(1L), roundId = Some(2L), order = Seq("rate" -> -1)),
      s"""select  s.page_id as pi_on_i, ROW_NUMBER() over ( order by rate desc) ranked from selection s
          where s.jury_id = ? and s.round_id = ? and s.page_id is not null
          order by rate desc""",
      expectedParams = Seq(1L, 2L),
      f = _.query(ranked = true, idOnly = true, noLimit = true)
    )
  }

  dbTest("count - optimised path when regions empty and not byRegion") { implicit session =>
    check(
      SelectionQuery(roundId = Some(5L)),
      "select COUNT(DISTINCT s.page_id) from selection s where s.round_id = ? and s.page_id is not null",
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
          where s.round_id = ? and s.monument_id like ? and NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, '-', 1), 3), '') = ? ) t""",
      expectedParams = Seq(5L, "12%", "12"),
      f = _.query(count = true)
    )
  }

  dbTest("count - a juror's region on selection alone, without DISTINCT") { implicit session =>
    check(
      SelectionQuery(userId = Some(4L), roundId = Some(5L), regions = Set("12")),
      "select COUNT(*) from selection s where s.jury_id = ? and s.round_id = ? and s.monument_id like ? and NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, '-', 1), 3), '') = ? and s.page_id is not null",
      expectedParams = Seq(4L, 5L, "12%", "12"),
      f = _.query(count = true)
    )
  }

  dbTest("limit pages the selection ids, then joins the page's rows (deferred join)") { implicit session =>
    check(
      SelectionQuery(
        roundId = Some(3L),
        limit = Some(Limit(pageSize = Some(5), offset = Some(10)))
      ),
      s"""select  $allFields from (select s.id from selection s
          where s.round_id = ? and s.page_id is not null LIMIT 5 OFFSET 10) k
          STRAIGHT_JOIN selection s on s.id = k.id
          STRAIGHT_JOIN images i on i.page_id = s.page_id""",
      expectedParams = Seq(3L)
    )
  }

  dbTest("a grouped page groups and pages selection alone, then joins the page's images") { implicit session =>
    check(
      SelectionQuery(
        roundId = Some(3L),
        grouped = true,
        limit = Some(Limit(pageSize = Some(5), offset = Some(10)))
      ),
      s"""select k.rate, k.rate_count, $imageFields from (select s.page_id as pid, sum(s.rate) as rate,
          count(s.rate) as rate_count from selection s where s.round_id = ? and s.page_id is not null
          group by s.page_id LIMIT 5 OFFSET 10) k
          STRAIGHT_JOIN images i on i.page_id = k.pid""",
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
    assert(withLimit.contains("LIMIT 5 OFFSET 10"), s"expected LIMIT, got: $withLimit")
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
