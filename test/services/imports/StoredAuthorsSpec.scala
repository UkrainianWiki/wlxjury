package services.imports

import org.specs2.mutable.Specification

/** Stored authors with markup, as older imports left them (every shape on the production
  * copy of 2026-11-13: `[[User:X|Y]]` and four credit templates), and how they are read and
  * shown.
  */
class StoredAuthorsSpec extends Specification {

  "hasMarkup" should {

    "tell wiki markup from an account name" in {
      Seq("[[User:A|A]]", "{{MO}}", "Name ]]", "{{lang|en|X}}", "[https://example.com X]", "[//example.com X]")
        .map(StoredAuthors.hasMarkup) must contain(beTrue).forall
      Seq("Liliia2022", "Петро Грушко", "A [b] c", "Name {x}", "https://example.com")
        .map(StoredAuthors.hasMarkup) must contain(beFalse).forall
    }
  }

  "isMangled" should {

    "tell an account an encoding loss turned into question marks" in {
      Seq("???????? ????????", "?????", "? ?", "Ab??cd").map(StoredAuthors.isMangled) must contain(beTrue).forall
      Seq("Who?", "Liliia2022", "Петро Грушко", "").map(StoredAuthors.isMangled) must contain(beFalse).forall
    }
  }

  "author" should {

    "read a stored value as an import reads the page's author" in {
      StoredAuthors.author("[[User:Liliia2022|Liliia2022]]") === ImageText.Author.Named("Liliia2022")
      StoredAuthors.author("[[User:Петро_Грушко|Петро]]") === ImageText.Author.Named("Петро_Грушко")
      StoredAuthors.author("{{SergiyBobokPhotos}}") === ImageText.Author.Named("{{SergiyBobokPhotos}}")
      StoredAuthors.author("{{unknown|author}}") === ImageText.Author.Unknown
      StoredAuthors.author("Я") === ImageText.Author.Uploader
      StoredAuthors.author("  ") === ImageText.Author.Unknown
    }
  }

  "shown" should {

    "show a link's account: its target, not its label, normalized as stored" in {
      StoredAuthors.shown(Some("[[User:Liliia2022|Liliia2022]]")) === Some("Liliia2022")
      StoredAuthors.shown(Some("[[User:Olya Tsimbal|Ольга Цимбал]]")) === Some("Olya Tsimbal")
      StoredAuthors.shown(Some("[[User:Петро_Грушко|Петро Грушко]]")) === Some("Петро Грушко")
      StoredAuthors.shown(Some("[[User:lower_case|x]]")) === Some("Lower case")
      StoredAuthors.shown(Some("[[:uk:Користувач:Ольга|Ольга]]")) === Some("Ольга")
    }

    "keep the case of accounts that the column's collation would equate" in {
      StoredAuthors.shown(Some("[[User:AbC|x]]")) === Some("AbC")
      StoredAuthors.shown(Some("[[User:ABC|x]]")) === Some("ABC")
    }

    "show a credit template not resolved yet by its name, without braces" in {
      StoredAuthors.shown(Some("{{SergiyBobokPhotos}}")) === Some("SergiyBobokPhotos")
      StoredAuthors.shown(Some("{{MO}}")) === Some("MO")
      StoredAuthors.shown(Some("{{Yuriy Kvach}}")) === Some("Yuriy Kvach")
      StoredAuthors.shown(Some("{{KharkivianPhotos}}")) === Some("KharkivianPhotos")
    }

    "show other markup as plain text, and nobody for an unknown author" in {
      StoredAuthors.shown(Some("{{own}} by [[w:Museum]]")) === Some("own by Museum")
      StoredAuthors.shown(Some("[https://example.com Site]")) === Some("Site")
      StoredAuthors.shown(Some("{{unknown|author}}")) must beNone
    }

    "show a plain value as it is, and nothing for none or an empty one" in {
      StoredAuthors.shown(Some("Liliia2022")) === Some("Liliia2022")
      StoredAuthors.shown(Some("lower_case as stored")) === Some("lower_case as stored")
      StoredAuthors.shown(Some("")) must beNone
      StoredAuthors.shown(None) must beNone
    }
  }
}
