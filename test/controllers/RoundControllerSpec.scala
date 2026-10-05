package controllers

import db.scalikejdbc.{Round, RoundLimits}
import modules.BlockingExecutionContext
import org.intracer.wmua.cmd.DistributeImages
import org.specs2.mock.Mockito.mock
import play.api.test.{Helpers, PlaySpecification}
import services.RoundService

import java.time.ZonedDateTime

class RoundControllerSpec extends PlaySpecification {

  "RoundForm" should {
    "copy round to form" in {
      val round = new Round(
        id = Some(1),
        number = 2,
        name = Some("name"),
        contestId = 4,
        roles = Set("jury"),
        distribution = 2,
        rates = Round.ratesById(10),
        limits = RoundLimits(),
        createdAt = ZonedDateTime.now,
        deletedAt = None,
        active = true,
        optionalRate = true,
        juryOrgView = false,
        minMpx = Some(4),
        previous = Some("20"),
        prevSelectedBy = None,
        prevMinAvgRate = None,
        category = Some("Category:include"),
        excludeCategory = Some("Category:exclude"),
        regions = None,
        minImageSize = Some(1),
        hasCriteria = true,
        halfStar = false,
        monuments = None,
        topImages = None,
        specialNomination = Some("special nomination"),
        users = Nil
      )

      val controller = new RoundController(
        Helpers.stubControllerComponents(),
        mock[ContestController],
        mock[RoundService],
        mock[DistributeImages],
        mock[BlockingExecutionContext]
      )

      val editRound = EditRound(round, Nil, None, newImages = true)
      val filledForm = EditRound.editRoundForm.fill(editRound)
      val formRound = filledForm.value.get.round
      round === formRound
    }
  }

}
