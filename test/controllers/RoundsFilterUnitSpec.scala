package controllers

import db.scalikejdbc.Round
import org.specs2.mutable.Specification

/** [[RoundsFilter]] without a database: parsing (unknown values the defaults), the params of the
  * choices that aren't the default, adding them to a URL, the three orders, and the types (a
  * criteria round is rated, a comments-only round neither).
  */
class RoundsFilterUnitSpec extends Specification {

  private def round(id: Long, active: Boolean, rates: db.scalikejdbc.Rates = Round.binaryRound, criteria: Boolean = false) =
    Round(Some(id), id, Some(s"R$id"), contestId = 1, rates = rates, active = active, hasCriteria = criteria)

  private val r1 = round(1, active = false)
  private val r2 = round(2, active = true, Round.rateRounds.head)
  private val r3 = round(3, active = false, Round.rateRounds.last, criteria = true)
  private val r4 = round(4, active = true, Round.commentsOnlyRound)

  "RoundsFilter.of" should {
    "read known values and default the rest" in {
      RoundsFilter.of(Map("status" -> "active", "type" -> "rated", "order" -> "newest").get) ===
        RoundsFilter(RoundsFilter.Status.Active, RoundsFilter.Kind.Rated, RoundsFilter.Order.Newest)
      RoundsFilter.of(Map("status" -> "ACTIVE", "type" -> "", "order" -> "x").get) === RoundsFilter.Default
      RoundsFilter.of(Map.empty[String, String].get) === RoundsFilter.Default
    }
  }

  "params and addTo" should {
    "leave out the defaults and append to a query" in {
      RoundsFilter.Default.params === Nil
      RoundsFilter.Default.addTo("/roundstat/5") === "/roundstat/5"
      val f = RoundsFilter.Default.withOrder(RoundsFilter.Order.ActiveFirst).withStatus(RoundsFilter.Status.Inactive)
      f.params === Seq("status" -> "inactive", "order" -> "active")
      f.addTo("/roundstat/5") === "/roundstat/5?status=inactive&order=active"
      f.addTo("/admin/rounds?contestId=2") === "/admin/rounds?contestId=2&status=inactive&order=active"
      RoundsFilter.of(Map("status" -> "inactive", "order" -> "active").get) === f
    }
  }

  "the filter" should {
    "keep rounds by status and type" in {
      val all = Seq(r1, r2, r3, r4)
      RoundsFilter.Default(all) === all
      RoundsFilter.Default.withStatus(RoundsFilter.Status.Active)(all) === Seq(r2, r4)
      RoundsFilter.Default.withStatus(RoundsFilter.Status.Inactive)(all) === Seq(r1, r3)
      RoundsFilter.Default.withKind(RoundsFilter.Kind.Binary)(all) === Seq(r1)
      // a criteria round is rated; a comments-only round neither yes/no nor rated
      RoundsFilter.Default.withKind(RoundsFilter.Kind.Rated)(all) === Seq(r2, r3)
      RoundsFilter.Kind.of(r4) must beNone
      RoundsFilter.Kind.of(r3) === Some(RoundsFilter.Kind.Rated)
    }

    "order by creation, oldest or newest first, or the active first" in {
      val shuffled = Seq(r3, r1, r4, r2)
      RoundsFilter.Default(shuffled) === Seq(r1, r2, r3, r4)
      RoundsFilter.Default.withOrder(RoundsFilter.Order.Newest)(shuffled) === Seq(r4, r3, r2, r1)
      RoundsFilter.Default.withOrder(RoundsFilter.Order.ActiveFirst)(shuffled) === Seq(r2, r4, r1, r3)
    }
  }

  "statRound" should {
    import RoundsFilter._
    val all = Seq(r3, r1, r4, r2)
    val r5 = round(5, active = false)
    "keep the current round when the filter keeps it, whatever the order" in {
      forall(Order.values) { o =>
        RoundsFilter(Status.Active, Kind.All, o).statRound(r4, all) === r4
      }
      RoundsFilter.Default.withOrder(Order.Newest).statRound(r1, all) === r1
    }
    "else open the first round the filter keeps, in its order" in {
      // r4 (comments only) is never rated: rated keeps r2 (active) and r3 (stopped)
      RoundsFilter(kind = Kind.Rated).statRound(r4, all) === r2
      RoundsFilter(kind = Kind.Rated, order = Order.Newest).statRound(r4, all) === r3
      RoundsFilter(kind = Kind.Rated, order = Order.ActiveFirst).statRound(r4, all) === r2
      // yes/no: r1 and r5, both stopped
      RoundsFilter(kind = Kind.Binary, order = Order.ActiveFirst).statRound(r2, all :+ r5) === r1
      RoundsFilter(kind = Kind.Binary, order = Order.Newest).statRound(r2, all :+ r5) === r5
      // exactly one match
      RoundsFilter(Status.Inactive, Kind.Binary).statRound(r2, all) === r1
    }
    "tell Active first from Oldest first: a stopped older round and an active newer one" in {
      // rated keeps r3 (stopped, criteria) and r6 (active, newer)
      val r6 = round(6, active = true, Round.rateRounds.head)
      val rounds = Seq(r1, r3, r4, r6)
      RoundsFilter(kind = Kind.Rated).statRound(r1, rounds) === r3
      RoundsFilter(kind = Kind.Rated, order = Order.ActiveFirst).statRound(r1, rounds) === r6
      RoundsFilter(kind = Kind.Rated, order = Order.Newest).statRound(r1, rounds) === r6
      // with a newer stopped one too, Active first still opens the active one, Newest the newest
      val r7 = round(7, active = false, Round.rateRounds.head)
      RoundsFilter(kind = Kind.Rated, order = Order.ActiveFirst).statRound(r1, rounds :+ r7) === r6
      RoundsFilter(kind = Kind.Rated, order = Order.Newest).statRound(r1, rounds :+ r7) === r7
    }
    "stay on the current round when none matches, and pick only among the rounds given" in {
      RoundsFilter(Status.Active, Kind.Binary).statRound(r3, all) === r3
      RoundsFilter(kind = Kind.Rated).statRound(r1, Seq(r1, r4)) === r1
    }
  }
}
