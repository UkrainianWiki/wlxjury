package org.intracer.wmua.cmd

import db.ImageRepo
import db.scalikejdbc.{Round, User}
import org.intracer.wmua.Image
import org.intracer.wmua.cmd.DistributeImagesSpec.{di, files, image, juror, round, video}
import org.specs2.mock.Mockito
import org.specs2.mock.Mockito.{mock, theStubbed}
import org.specs2.mutable.Specification

class DistributeImagesSpec extends Specification with Mockito {

  "imagesByRound" should {
    "work with no images" in {
      di(Nil).imagesByRound(round) === Nil
    }

    "do not filter" in {
      di(files).imagesByRound(round) === files
    }

    "filter by image media type" in {
      di(files).imagesByRound(round.copy(mediaType = Some("image"))) === List(image)
    }

    "filter by video media type" in {
      di(files).imagesByRound(round.copy(mediaType = Some("video"))) === List(video)
    }

    "count a video with no media type yet by its extension" in {
      val legacyVideo = Image(2L, "File:2.webm", mime = Some("video/webm"))
      di(List(image, legacyVideo)).imagesByRound(round.copy(mediaType = Some("video"))) === List(legacyVideo)
    }
  }

  "distributeImages" should {
    "refuse to distribute with no jurors" in {
      di(files).distributeImages(round, files, Nil) must
        throwAn[IllegalArgumentException](message = "empty jury")
    }

    "refuse to distribute when a juror is listed twice (would duplicate rows)" in {
      di(files).distributeImages(round.copy(distribution = 2), List(image), List(juror, juror)) must
        throwAn[IllegalArgumentException](message = "duplicate")
    }
  }

}

object DistributeImagesSpec {
  private val roundId = 1L
  private val contestId = 2L
  private val round = new Round(Some(roundId), 1, contestId = contestId)
  private val image = Image(1L, "File:1.jpg", mime = Some("image/jpeg"))
  private val video = Image(1L, "File:1.ogv", mime = Some("application/ogg"), mediaType = Some("VIDEO"))
  private val files = List(image, video)
  private val juror = User("Juror", "juror@example.com", id = Some(10L), roles = Set("jury"))

  def di(images: List[Image]) = new DistributeImages(mockRepo(images))

  def mockRepo(images: List[Image]): ImageRepo = {
    val repo = mock[ImageRepo]
    repo.byRoundMerged(roundId).returns(Nil)
    repo.findByContestId(contestId).returns(images)
    repo
  }

}
