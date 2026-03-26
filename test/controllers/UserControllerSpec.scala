package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.intracer.wmua.ContestJury
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import services.{SMTPOrWikiMail, UserService}

class UserControllerSpec extends Specification with PlayTestDb with Mockito {

  val sender = User("Admin User", "email@server.com", None, contestId = None)

  "fill template" should {

    "fill contest info" in {
      testDbApp { implicit app =>
        val contest = ContestJury(name = "Wiki Loves Earth", year = 2016, country = "Ukraine", images = None, id = None)
        val template = "Organizing committee of {{ContestType}} {{ContestYear}} {{ContestCountry}} is glad to welcome you as a jury member\n" +
          "Please visit {{JuryToolLink}}\n" +
          "Regards, {{AdminName}}"
        val admin = new UserService(mock[SMTPOrWikiMail], app.configuration)
        val filled = admin.fillGreeting(template, contest, sender, sender)
        filled === "Organizing committee of Wiki Loves Earth 2016 Ukraine is glad to welcome you as a jury member\n" +
          "Please visit https://jury.wle.org.ua/\n" +
          "Regards, Admin User"
      }
    }
  }
}
