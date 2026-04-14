package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import munit.FunSuite

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
      expected: String,
      f: SelectionQuery => String = _.query()
  ): Unit = {
    assertEquals(foldSpace(f(q)), foldSpace(expected))
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
          s.jury_id = 123"""
    )
  }

  dbTest("list - by round") { implicit session =>
    check(
      SelectionQuery(roundId = Some(234)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.round_id = 234"""
    )
  }

  dbTest("list - by rate") { implicit session =>
    check(
      SelectionQuery(rate = Some(1)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.rate = 1"""
    )
  }

  dbTest("list - by user, round, rate") { implicit session =>
    check(
      SelectionQuery(userId = Some(2), roundId = Some(3), rate = Some(1)),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.jury_id = 2 and s.round_id = 3 and s.rate = 1"""
    )
  }

  dbTest("grouped - group by page list by round") { implicit session =>
    check(
      SelectionQuery(roundId = Some(3), grouped = true),
      s"""select sum(s.rate) as rate, count(s.rate) as rate_count, $imageFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where
          s.round_id = 3
        group by s.page_id"""
    )
  }

  dbTest("by region - list") { implicit session =>
    check(
      SelectionQuery(regions = Set("12")),
      s"""select $allFields from selection s
          STRAIGHT_JOIN images i
          on i.page_id = s.page_id
          where i.monument_id like '12%'"""
    )
  }

  dbTest("by region - stat") { implicit session =>
    def regionStatQuery(q: SelectionQuery): String = q.query(byRegion = true)

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
}
