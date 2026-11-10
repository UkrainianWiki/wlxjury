package org.intracer.wmua

import db.scalikejdbc.User
import org.specs2.mutable.Specification

/** Logins, emails and wiki accounts as pasted from spreadsheets and messengers. */
class LoginTextSpec extends Specification {

  private val Nbsp = "\u00a0"

  "LoginText.clean" should {
    "strip ASCII whitespace, tabs and Unicode spaces at both ends" in {
      LoginText.clean(" alice@example.com") === "alice@example.com"
      LoginText.clean("alice@example.com\t") === "alice@example.com"
      LoginText.clean(s"${Nbsp}alice@example.com$Nbsp") === "alice@example.com"
      LoginText.clean("\u2007\u202f\ufeff\u200b\r\n alice@example.com \u3000\u2009") === "alice@example.com"
      LoginText.clean("\u200e\u2060alice@example.com\u200f") === "alice@example.com"
      LoginText.clean("\u0085alice@example.com\u0085") === "alice@example.com"
    }

    "keep inner characters, and give blank for only spaces or null" in {
      LoginText.clean(s" Wiki${Nbsp}name\tx ") === s"Wiki${Nbsp}name\tx"
      LoginText.clean(s"$Nbsp \t") === ""
      LoginText.clean(null) === ""
    }

    "lowercase an email after cleaning" in {
      LoginText.email(s"${Nbsp}Alice@Example.COM\t") === "alice@example.com"
    }
  }

  "User.parseList" should {
    "take an email with a non-breaking space or tab around it as the email, not a wiki account" in {
      User.parseList(s"${Nbsp}imp@example.com$Nbsp\n\tother@example.com ,${Nbsp}User:Wiki_person$Nbsp") === Seq(
        User(id = None, contestId = None, fullname = "", email = "imp@example.com"),
        User(id = None, contestId = None, fullname = "", email = "other@example.com"),
        User(id = None, contestId = None, fullname = "", email = "", wikiAccount = Some("Wiki_person"))
      )
    }

    "skip tokens of only spaces" in {
      User.parseList(s"$Nbsp\n \t\n") === Seq.empty
    }
  }

  "User.normalizeWikiAccount" should {
    "drop a pasted non-breaking space around the name" in {
      User.normalizeWikiAccount(s"${Nbsp}wiki_person\t$Nbsp") === Right(Some("Wiki person"))
      User.normalizeWikiAccount(s"$Nbsp") === Right(None)
    }

    "drop pasted spaces after a User: prefix or an underscore (CoiMembers.normalize)" in {
      db.scalikejdbc.CoiMembers.normalize(s"${Nbsp}User:${Nbsp}foo bar") === "Foo bar"
      db.scalikejdbc.CoiMembers.normalize("_ foo") === "Foo"
      User.normalizeWikiAccount(s"User: ${Nbsp}foo_bar\t") === Right(Some("Foo bar"))
    }

    "strip control characters at the ends, as String.trim did" in {
      LoginText.clean("\u0001Foo\u001f") === "Foo"
      User.normalizeWikiAccount("Foo\u0001") === Right(Some("Foo"))
      // inside the name a control character is still refused
      User.normalizeWikiAccount("Fo\u0001o") === Left("wiki.account.invalid")
    }
  }
}
