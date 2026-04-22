package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import munit.FunSuite

class ImagesSqlSpec extends FunSuite with TestDb {

  private val imageFields =
    "i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, " +
      "i.last_round as lr_on_i, i.width as w_on_i, i.height as h_on_i, i.monument_id as mi_on_i, i.description as d_on_i, " +
      "i.size as s_on_i, i.author as a_on_i, i.mime as m_on_i"

  private val selectionFields =
    "s.id as i_on_s, s.jury_id as ji_on_s, s.created_at as ca_on_s, s.deleted_at as da_on_s, " +
      "s.round_id as ri_on_s, s.page_id as pi_on_s, s.rate as r_on_s, s.criteria_id as ci_on_s"

  private val allFields = imageFields + ", " + selectionFields

  private def foldSpace(s: String) = s.replaceAll("\\s+", " ").trim

  // TODO start mysql once per spec
  private def check(
      q: SelectionQuery,
      expected: String,
      f: SelectionQuery => String = _.query()
  ): Unit = {
    withDb {
      assertEquals(foldSpace(f(q)), foldSpace(expected))
    }
  }

  test("list - all") {
    check(
      SelectionQuery(),
      s"""select $allFields from images i
          join selection s
          on i.page_id = s.page_id"""
    )
  }

  test("list - by user") {
    check(
      SelectionQuery(userId = Some(123)),
      s"""select $allFields from images i
          join selection s
          on i.page_id = s.page_id
          where
          s.jury_id = 123"""
    )
  }

  test("list - by round") {
    check(
      SelectionQuery(roundId = Some(234)),
      s"""select $allFields from images i
          join selection s
          on i.page_id = s.page_id
          where
          s.round_id = 234"""
    )
  }

  test("list - by rate") {
    check(
      SelectionQuery(rate = Some(1)),
      s"""select $allFields from images i
          join selection s
          on i.page_id = s.page_id
          where
          s.rate = 1"""
    )
  }

  test("list - by user, round, rate") {
    check(
      SelectionQuery(userId = Some(2), roundId = Some(3), rate = Some(1)),
      s"""select $allFields from images i
          join selection s
          on i.page_id = s.page_id
          where
          s.jury_id = 2 and s.round_id = 3 and s.rate = 1"""
    )
  }

  test("grouped - group by page list by round") {
    check(
      SelectionQuery(roundId = Some(3), grouped = true),
      s"""select sum(s.rate) as rate, count(s.rate) as rate_count, $imageFields from images i
          join selection s
          on i.page_id = s.page_id
          where
          s.round_id = 3
        group by s.page_id"""
    )
  }

  test("by region - list") {
    check(
      SelectionQuery(regions = Set("12")),
      s"""select $allFields from images i
          join selection s
          on i.page_id = s.page_id
          where i.monument_id like '12%'"""
    )
  }

  test("by region - stat") {
    def regionStatQuery(q: SelectionQuery): String = q.query(byRegion = true)

    check(
      SelectionQuery(),
      s"""select m.adm0, count(DISTINCT i.page_id) from images i
          join selection s
          on i.page_id = s.page_id
          join monument m
          on i.monument_id = m.id
          group by m.adm0""",
      f = regionStatQuery
    )
  }
}
