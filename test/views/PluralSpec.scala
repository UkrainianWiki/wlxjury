package views

import org.specs2.mutable.Specification
import play.api.i18n.{Lang, Messages, MessagesImpl}
import play.api.test.Helpers

/** [[Plural]]'s forms of the app's languages, and [[CommentCount]]'s words for a badge. */
class PluralSpec extends Specification {

  "the plural form" should {

    "follow Ukrainian and Russian: one for 1, 21, 101 (not 11), few for 2-4, 22-24 (not 12-14), many else" in {
      forall(Seq("uk", "ru")) { lang =>
        Seq(1, 21, 101, 1001).map(Plural.form(_, lang)).distinct === Seq("one")
        Seq(2, 3, 4, 22, 34, 102).map(Plural.form(_, lang)).distinct === Seq("few")
        Seq(0, 5, 11, 12, 13, 14, 19, 20, 25, 111, 112).map(Plural.form(_, lang)).distinct === Seq("many")
      }
    }

    "follow French (one for 0 and 1) and English (one for 1)" in {
      Seq(0, 1).map(Plural.form(_, "fr")).distinct === Seq("one")
      Seq(2, 21).map(Plural.form(_, "fr")).distinct === Seq("other")
      Plural.form(1, "en") === "one"
      Seq(0, 2, 21).map(Plural.form(_, "en")).distinct === Seq("other")
    }
  }

  "a badge's words" should {

    val api = Helpers.stubMessagesApi(Map(
      "en" -> Map("comments.count.one" -> "{0} comment", "comments.count.other" -> "{0} comments"),
      "uk" -> Map("comments.count.one" -> "{0} коментар", "comments.count.few" -> "{0} коментарі",
        "comments.count.many" -> "{0} коментарів")))

    "be in the page's language and plural form" in {
      implicit val uk: Messages = MessagesImpl(Lang("uk"), api)
      Seq(1, 3, 5, 11, 21).map(CommentCount.label) === Seq("1 коментар", "3 коментарі", "5 коментарів", "11 коментарів",
        "21 коментар")
      val en: Messages = MessagesImpl(Lang("en"), api)
      (CommentCount.label(1)(en), CommentCount.label(2)(en)) === (("1 comment", "2 comments"))
    }
  }
}
