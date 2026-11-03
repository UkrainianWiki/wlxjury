package org.intracer.wmua

import db.scalikejdbc.{Round, User}
import org.specs2.mutable.Specification

class SelectionFactorySpec extends Specification {

  private val juror = User(fullname = "juror", email = "j@test.com", id = Some(5L))
  private val round = Round(id = Some(10L), number = 1L, contestId = 1L)

  "Selection.apply(Image, User, Round, rate)" should {

    "copy monumentId from Image" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg", monumentId = Some("13-001"))
      val sel = Selection(img, juror, round, rate = 1)
      sel.monumentId === Some("13-001")
    }

    "copy None monumentId when Image has no monument" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg", monumentId = None)
      val sel = Selection(img, juror, round, rate = 0)
      sel.monumentId === None
    }

    "set pageId from Image" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg", monumentId = Some("13-001"))
      val sel = Selection(img, juror, round, rate = 0)
      sel.pageId === 42L
    }

    "set juryId from User.getId" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg")
      val sel = Selection(img, juror, round, rate = 0)
      sel.juryId === 5L
    }

    "set roundId from Round.getId" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg")
      val sel = Selection(img, juror, round, rate = 0)
      sel.roundId === 10L
    }
  }

  "Selection.apply(Image, User, Round) without rate" should {

    "default rate to 0 and copy monumentId" in {
      val img = Image(pageId = 42L, title = "File:Test.jpg", monumentId = Some("07-101"))
      val sel = Selection(img, juror, round)
      sel.rate      === 0
      sel.monumentId === Some("07-101")
    }
  }
}
