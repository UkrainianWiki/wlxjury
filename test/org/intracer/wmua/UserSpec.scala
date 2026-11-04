package org.intracer.wmua

import db.scalikejdbc.User
import org.specs2.mutable.Specification

class UserSpec extends Specification {

  "User" should
  {
    "be in contest" in {
      User("", "", contestId = Some(1)).isInContest(Some(1)) === true
      User("", "", contestId = Some(1)).isInContest(Some(2)) === false

      User("", "", contestId = None).isInContest(None) === false
      User("", "", contestId = Some(1)).isInContest(None) === false
      User("", "", contestId = None).isInContest(Some(1)) === false
    }
  }

  "parseList" should {

    "parse empty" in {
      User.parseList("") === Seq.empty
    }

    "parse one email" in {
      val list: Seq[User] = User.parseList("123@abc.com")
      val users: Seq[User] = Seq(User(email = "123@abc.com", id = None, contestId = None, fullname = ""))
      list === users
    }

    "parse one email with name" in {
      val list: Seq[User] = User.parseList("Name Surname <123@abc.com>")
      val users: Seq[User] = Seq(User(email = "123@abc.com", id = None, contestId = None, fullname = "Name Surname"))
      list === users
    }


    "parse emails" in {
      val emails = Seq("123@abc.com", "234@bcd.com", "345@cde.com")

      User.parseList(emails.mkString("\n")) === emails.map { email =>
        User(id = None, contestId = None, fullname = "", email = email)
      }
    }

    "parse wiki accounts" in {
      val accounts = Seq("Ilya", "Antanana", "Ahonc", "Base")

      User.parseList(accounts.mkString("\n")) === accounts.map { account =>
        User(id = None, contestId = None, fullname = "", email = "", wikiAccount = Some(account))
      }
    }

    "parse prefixed accounts" in {
      val withoutUser = Seq("Ilya", "Antanana", "Ahonc", "Base")
      val withUser = withoutUser.map("User:" + _)

      User.parseList(withUser.mkString("\n")) === withoutUser.map { account =>
        User(id = None, contestId = None, fullname = "", email = "", wikiAccount = Some(account))
      }
    }

    "parse names and emails commas" in {
      val strings = Seq(
        "Name1 Surname1 <email1@server.com>",
        "Name2 Surname2 <email2@server.com>"
      )

      User.parseList(strings.mkString(",")) === Seq(
        User(id = None, contestId = None, fullname = "Name1 Surname1", email = "email1@server.com"),
        User(id = None, contestId = None, fullname = "Name2 Surname2", email = "email2@server.com")
      )
    }

    "parse names and emails newlines" in {
      val strings = Seq(
        "Name1 Surname1 <email1@server.com>",
        "Name2 Surname2 <email2@server.com>"
      )

      User.parseList(strings.mkString("\n")) === Seq(
        User(id = None, contestId = None, fullname = "Name1 Surname1", email = "email1@server.com"),
        User(id = None, contestId = None, fullname = "Name2 Surname2", email = "email2@server.com")
      )
    }

    // jakarta.mail 1.6.7 (play-mailer 10) parses non-ASCII where javax.mail 1.5.6 threw; Play's
    // emailAddress check still refuses the address, so both stay wiki account names as before
    "keep non-ASCII names and addresses as wiki accounts" in {
      val strings = Seq("Ім'я Прізвище", "юзер@приклад.укр", "Ім'я Прізвище <юзер@приклад.укр>")

      User.parseList(strings.mkString("\n")) === strings.map { account =>
        User(id = None, contestId = None, fullname = "", email = "", wikiAccount = Some(account))
      }
    }

    "parse names and emails newlines and commas" in {
      val strings = Seq(
        "Name1 Surname1 <email1@server.com>",
        "Name2 Surname2 <email2@server.com>"
      )

      User.parseList(strings.mkString(",\n")) === Seq(
        User(id = None, contestId = None, fullname = "Name1 Surname1", email = "email1@server.com"),
        User(id = None, contestId = None, fullname = "Name2 Surname2", email = "email2@server.com")
      )
    }
  }


}
