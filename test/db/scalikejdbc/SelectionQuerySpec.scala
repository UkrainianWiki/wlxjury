package db.scalikejdbc

import db.scalikejdbc.SelectionQuerySpec.{pageId, regionId, roundId, userId}
import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import org.specs2.mutable.Specification

class SelectionQuerySpec extends Specification {

  "where" should {
    "no conditions" in {
      SelectionQuery().where() === ""
    }

    "userId" in {
      SelectionQuery(userId = Some(userId))
        .where() === s" where s.jury_id = $userId"
    }

    "roundId" in {
      SelectionQuery(roundId = Some(roundId))
        .where() === s" where s.round_id = $roundId"
    }

    "rate" in {
      val rate = 1
      SelectionQuery(rate = Some(rate))
        .where() === s" where s.rate = $rate"
    }

    "rated" in {
      SelectionQuery(rated = Some(true))
        .where() === s" where s.rate > 0"
    }

    "not rated" in {
      SelectionQuery(rated = Some(false))
        .where() === s" where s.rate = 0"
    }

    "region" in {
      SelectionQuery(regions = Set(regionId))
        .where() === s" where i.monument_id like '$regionId%'"
    }

    "withPageId" in {
      SelectionQuery(withPageId = Some(pageId)).where() === ""
    }

    "userId and roundId" in {
      SelectionQuery(
        userId = Some(userId),
        roundId = Some(roundId)
      ).where() === s" where" +
        s" s.jury_id = $userId and" +
        s" s.round_id = $roundId"
    }

    "userId and roundId and rated" in {
      SelectionQuery(
        userId = Some(userId),
        roundId = Some(roundId),
        rated = Some(true)
      ).where() === s" where" +
        s" s.jury_id = $userId and" +
        s" s.round_id = $roundId and" +
        s" s.rate > 0"
    }

    "userId and roundId and not rated" in {
      SelectionQuery(
        userId = Some(userId),
        roundId = Some(roundId),
        rated = Some(false)
      ).where() === s" where" +
        s" s.jury_id = $userId and" +
        s" s.round_id = $roundId and" +
        s" s.rate = 0"
    }

    "userId and roundId and rated and withPageId" in {
      SelectionQuery(
        userId = Some(userId),
        roundId = Some(roundId),
        rated = Some(true),
        withPageId = Some(pageId)
      ).where() === s" where " +
        s"s.jury_id = $userId and" +
        s" s.round_id = $roundId and " +
        s"(s.rate > 0 or" +
        s" s.page_id = $pageId)"
    }

    "userId and roundId and not rated and withPageId" in {
      SelectionQuery(
        userId = Some(userId),
        roundId = Some(roundId),
        rated = Some(false),
        withPageId = Some(pageId)
      ).where() === s" where " +
        s"s.jury_id = $userId and" +
        s" s.round_id = $roundId and " +
        s"(s.rate = 0 or" +
        s" s.page_id = $pageId)"
    }

    "userId and roundId and rated and region" in {
      SelectionQuery(
        userId = Some(userId),
        roundId = Some(roundId),
        rated = Some(true),
        regions = Set(regionId)
      ).where() === s" where" +
        s" s.jury_id = $userId and" +
        s" s.round_id = $roundId and" +
        s" s.rate > 0 and" +
        s" i.monument_id like '$regionId%'"
    }

    "userId and roundId and not rated and region" in {
      SelectionQuery(
        userId = Some(userId),
        roundId = Some(roundId),
        rated = Some(false),
        regions = Set(regionId)
      ).where() === s" where" +
        s" s.jury_id = $userId and" +
        s" s.round_id = $roundId and" +
        s" s.rate = 0 and" +
        s" i.monument_id like '$regionId%'"
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
