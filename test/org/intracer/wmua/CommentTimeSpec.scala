package org.intracer.wmua

import org.specs2.mutable.Specification

import java.time.Instant

/** [[Comment.parseTime]]: `comment.created_at` (varchar(40)) in the formats the app has written,
  * read leniently; anything else no time rather than a wrong one.
  */
class CommentTimeSpec extends Specification {

  private def at(text: String) = Comment.parseTime(text)

  "a comment's created_at" should {

    "be read as an OffsetDateTime, as CommentJdbc.now writes it" in {
      at("2026-11-12T14:05:00+02:00") === Some(Instant.parse("2026-11-12T12:05:00Z"))
      at(CommentJdbc.now()) must beSome
    }

    "be read as an older ZonedDateTime's text, with a zone id and fractions of a second" in {
      at("2016-10-05T12:34:56.464+03:00[Europe/Kiev]") === Some(Instant.parse("2016-10-05T09:34:56.464Z"))
      at("2016-10-05T12:34:56.464+02:00") === Some(Instant.parse("2016-10-05T10:34:56.464Z"))
      at("2018-11-01T09:00:01.123456+02:00[Europe/Kyiv]") === Some(Instant.parse("2018-11-01T07:00:01.123456Z"))
    }

    "take the offset over a zone id, and a zone id cut short by the column" in {
      at("2016-10-05T12:34:56.464+02:00[Europe/Ki") === Some(Instant.parse("2016-10-05T10:34:56.464Z"))
      at("2016-10-05T12:34:56.464+02:00[No/Such]") === Some(Instant.parse("2016-10-05T10:34:56.464Z"))
    }

    "read a zone id without an offset in that zone" in {
      at("2016-07-05T12:00:00[Europe/Kiev]") === Some(Instant.parse("2016-07-05T09:00:00Z"))
    }

    "accept a space for the T, a Z offset, no seconds and surrounding blanks" in {
      at("2016-10-05 12:34:56+02:00") === Some(Instant.parse("2016-10-05T10:34:56Z"))
      at("2016-10-05T12:34:56Z") === Some(Instant.parse("2016-10-05T12:34:56Z"))
      at("2016-10-05T12:34+02:00") === Some(Instant.parse("2016-10-05T10:34:00Z"))
      at("  2016-10-05T12:34:56Z ") === Some(Instant.parse("2016-10-05T12:34:56Z"))
    }

    "be no time when it can't be read" in {
      forall(Seq(null, "", "  ", "yesterday", "2016-10-05T12:34:56", "2016-10-05T12:34:56.464+0", "2016-13-45T99:00:00Z",
        "2016-10-05")) { text =>
        at(text) must beNone
      }
    }
  }
}
