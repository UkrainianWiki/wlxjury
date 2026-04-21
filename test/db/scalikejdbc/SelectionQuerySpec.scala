package db.scalikejdbc

import db.scalikejdbc.SelectionQuerySpec.{pageId, regionId, roundId, userId}
import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import org.specs2.mutable.Specification
import scalikejdbc._

class SelectionQuerySpec extends Specification {

  "where" should {
    "no conditions" in {
      SelectionQuery().where() === sqls""
    }

    "userId" in {
      val w = SelectionQuery(userId = Some(userId)).where()
      (w.value === " where s.jury_id = ?") and (w.parameters.toSeq === Seq(userId))
    }

    "roundId" in {
      val w = SelectionQuery(roundId = Some(roundId)).where()
      (w.value === " where s.round_id = ?") and (w.parameters.toSeq === Seq(roundId))
    }

    "rate" in {
      val rate = 1
      val w = SelectionQuery(rate = Some(rate)).where()
      (w.value === " where s.rate = ?") and (w.parameters.toSeq === Seq(rate))
    }

    "rated" in {
      val w = SelectionQuery(rated = Some(true)).where()
      (w.value === " where s.rate > 0") and (w.parameters.toSeq === Seq.empty)
    }

    "not rated" in {
      val w = SelectionQuery(rated = Some(false)).where()
      (w.value === " where s.rate = 0") and (w.parameters.toSeq === Seq.empty)
    }

    "region — single short code uses LIKE with bind parameter (injection blocked)" in {
      // "u'" is length 2 (≤ 2) → goes through the LIKE branch, not IN
      val injection = "u'"
      val w = SelectionQuery(regions = Set(injection)).where()
      w.value must contain("monument_id like ?")
      w.parameters.toSeq must_=== Seq(injection + "%")
    }

    "region — multi-code uses IN with bind parameters (injection blocked)" in {
      val injection = "uk' UNION SELECT 1,2,3--"
      val w = SelectionQuery(regions = Set(injection, "de")).where()
      w.value must contain("in (?, ?)")
      w.value must not contain "UNION SELECT"
      w.parameters.size must_=== 2
      w.parameters must contain(injection)
      w.parameters must contain("de")
    }

    "withPageId alone" in {
      SelectionQuery(withPageId = Some(pageId)).where() === sqls""
    }

    "userId and roundId" in {
      val w = SelectionQuery(userId = Some(userId), roundId = Some(roundId)).where()
      (w.value === " where s.jury_id = ? and s.round_id = ?") and (w.parameters.toSeq === Seq(userId, roundId))
    }

    "userId and roundId and rated" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId), rated = Some(true)
      ).where()
      (w.value === " where s.jury_id = ? and s.round_id = ? and s.rate > 0") and (w.parameters.toSeq === Seq(userId, roundId))
    }

    "userId and roundId and not rated" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId), rated = Some(false)
      ).where()
      (w.value === " where s.jury_id = ? and s.round_id = ? and s.rate = 0") and (w.parameters.toSeq === Seq(userId, roundId))
    }

    "userId and roundId and rated and withPageId" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId),
        rated = Some(true), withPageId = Some(pageId)
      ).where()
      (w.value === " where s.jury_id = ? and s.round_id = ? and (s.rate > 0 or s.page_id = ?)") and (w.parameters.toSeq === Seq(userId, roundId, pageId))
    }

    "userId and roundId and not rated and withPageId" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId),
        rated = Some(false), withPageId = Some(pageId)
      ).where()
      (w.value === " where s.jury_id = ? and s.round_id = ? and (s.rate = 0 or s.page_id = ?)") and (w.parameters.toSeq === Seq(userId, roundId, pageId))
    }

    "userId and roundId and rated and region" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId),
        rated = Some(true), regions = Set(regionId)
      ).where()
      w.value must contain("s.jury_id = ?")
      w.value must contain("s.round_id = ?")
      w.value must contain("s.rate > 0")
      w.value must contain("monument_id like ?")
      w.parameters must contain(regionId + "%")
    }

    "userId and roundId and not rated and region" in {
      val w = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId),
        rated = Some(false), regions = Set(regionId)
      ).where()
      w.value must contain("s.rate = 0")
      w.value must contain("monument_id like ?")
      w.parameters must contain(regionId + "%")
    }
  }

  "join" should {

    "use selection as the outer (driving) table" in {
      val j = SelectionQuery(userId = Some(userId), roundId = Some(roundId)).join(monuments = false)
      j.indexOf("selection s") must beLessThan(j.indexOf("images i"))
    }

    "emit STRAIGHT_JOIN" in {
      val j = SelectionQuery(userId = Some(userId), roundId = Some(roundId)).join(monuments = false)
      j must contain("STRAIGHT_JOIN")
    }

    "not include monument join when monuments = false" in {
      val j = SelectionQuery(userId = Some(userId), roundId = Some(roundId)).join(monuments = false)
      j must not(contain("monument"))
    }

    "append monument join when monuments = true" in {
      val j = SelectionQuery(userId = Some(userId), roundId = Some(roundId)).join(monuments = true)
      j must contain("join monument m on i.monument_id = m.id")
    }
  }

  "orderBy" should {

    "include monument_id asc when specified" in {
      SelectionQuery(userId = Some(userId), roundId = Some(roundId))
        .orderBy(Map("s.monument_id" -> 1)) must contain("s.monument_id asc")
    }

    "include rate desc when specified" in {
      SelectionQuery(userId = Some(userId), roundId = Some(roundId))
        .orderBy(Map("rate" -> -1)) must contain("rate desc")
    }

    "produce empty string when order map is empty" in {
      SelectionQuery(userId = Some(userId), roundId = Some(roundId))
        .orderBy(Map.empty) === ""
    }
  }

}

object SelectionQuerySpec {
  private val userId = 1L
  private val roundId = 2L
  private val regionId = "80"
  private val pageId = 3L
}
