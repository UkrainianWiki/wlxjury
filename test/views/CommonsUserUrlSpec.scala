package views

import org.specs2.mutable.Specification
import services.imports.StoredAuthors

/** An author's Commons user page ([[WikiLinks.commonsUserUrl]]): `User:` and the name as
  * MediaWiki normalizes it, URL-encoded so that no character of the name changes the URL.
  */
class CommonsUserUrlSpec extends Specification {

  private val prefix = "https://commons.wikimedia.org/wiki/User:"

  "commonsUserUrl" should {
    "write spaces as underscores" in {
      WikiLinks.commonsUserUrl("Petro Hrushko") === prefix + "Petro_Hrushko"
    }

    "keep an underscore, and runs of spaces and underscores as one" in {
      (WikiLinks.commonsUserUrl("Some_user") === prefix + "Some_user") and
        (WikiLinks.commonsUserUrl("  Some  _ user ") === prefix + "Some_user")
    }

    "encode an apostrophe and the characters that mean something in a URL" in {
      (WikiLinks.commonsUserUrl("O'Brien") === prefix + "O%27Brien") and
        (WikiLinks.commonsUserUrl("A&B?c#d/e") === prefix + "A%26B%3Fc%23d%2Fe")
    }

    "encode Cyrillic as UTF-8" in {
      WikiLinks.commonsUserUrl("Петро Грушко") ===
        prefix + "%D0%9F%D0%B5%D1%82%D1%80%D0%BE_%D0%93%D1%80%D1%83%D1%88%D0%BA%D0%BE"
    }

    "link an account only: a stored link's account, never a credit template" in {
      (StoredAuthors.account("Petro Hrushko") === Some("Petro Hrushko")) and
        (StoredAuthors.account("[[User:Петро_Грушко|Петро]]") === Some("Петро Грушко")) and
        (StoredAuthors.account("{{SergiyBobokPhotos}}") === None) and
        (StoredAuthors.account("{{unknown|author}}") === None) and
        (StoredAuthors.account("  ") === None)
    }

    "capitalize the first letter, as MediaWiki does, without a User: prefix of the name" in {
      (WikiLinks.commonsUserUrl("ivan") === prefix + "Ivan") and
        (WikiLinks.commonsUserUrl("User:ivan") === prefix + "Ivan") and
        (WikiLinks.commonsUserUrl("ірина") === prefix + "%D0%86%D1%80%D0%B8%D0%BD%D0%B0")
    }
  }
}
