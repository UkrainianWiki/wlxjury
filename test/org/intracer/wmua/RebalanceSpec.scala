package org.intracer.wmua

import org.intracer.wmua.cmd.DistributeImages
import org.specs2.mutable.Specification
import db.scalikejdbc.{ImageJdbc, Round, User}
import org.intracer.wmua.cmd.DistributeImages.{NoRebalance, Rebalance}

class RebalanceSpec extends Specification {

  val round = new Round(Some(1), number = 1, contestId = 1)
  val selection = Vote(1, 1, 1)
  val juror = User("", "", Some(1))
  val image = Image(1, "")
  lazy val di  = new DistributeImages(ImageJdbc)

  "Rebalance" should {

    "do nothing" in {
      di.rebalanceImages(round, Nil, Nil, Nil) === NoRebalance
      di.rebalanceImages(round, Seq(juror), Seq(image), Seq(selection)) === NoRebalance
    }

    "init one juror" in {
      di.rebalanceImages(round, Seq(juror), Seq(image), Nil) === Rebalance(Seq(selection), Nil)
    }
  }

}
