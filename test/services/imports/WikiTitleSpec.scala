package services.imports

import org.specs2.mutable.Specification

/** A title as an admin pastes it from a wiki page, read as the plain title (#111). */
class WikiTitleSpec extends Specification {

  "WikiTitle.normalize" should {

    "take the link's brackets off" in {
      WikiTitle.normalize("[[Category:Foo]]") === "Category:Foo"
      WikiTitle.normalize("[[Category:Images from Wiki Loves Earth 2020 in Brazil - Best 115]]") ===
        "Category:Images from Wiki Loves Earth 2020 in Brazil - Best 115"
    }

    "take a leading colon off" in {
      WikiTitle.normalize("[[:Category:Foo]]") === "Category:Foo"
      WikiTitle.normalize(":Category:Foo") === "Category:Foo"
    }

    "take the sort key or label off" in {
      WikiTitle.normalize("[[Category:Foo|sort key]]") === "Category:Foo"
      WikiTitle.normalize("[[:Category:Foo|Foo]]") === "Category:Foo"
    }

    "read spaces and underscores as single spaces" in {
      WikiTitle.normalize("  [[ Category:Foo_bar  baz ]]  ") === "Category:Foo bar baz"
      WikiTitle.normalize("Category:Foo_bar") === "Category:Foo bar"
      WikiTitle.normalize("Category: Foo") === "Category:Foo"
      WikiTitle.normalize("[[Category:Foo\u00A0bar]]") === "Category:Foo bar"
    }

    "take off the brackets of a half-pasted link" in {
      WikiTitle.normalize("[[Category:Foo") === "Category:Foo"
      WikiTitle.normalize("Category:Foo]]") === "Category:Foo"
    }

    "keep Unicode" in {
      WikiTitle.normalize("[[Category:Зображення з Вікі любить Землю 2020 в Україні]]") ===
        "Category:Зображення з Вікі любить Землю 2020 в Україні"
      WikiTitle.normalize("[[Catégorie:Œuvres d’art]]") === "Catégorie:Œuvres d’art"
    }

    "leave a plain title unchanged" in {
      WikiTitle.normalize("Category:Foo") === "Category:Foo"
      WikiTitle.normalize("Template:Some template") === "Template:Some template"
      WikiTitle.normalize("Commons:Wiki Loves Earth 2020/Winners") === "Commons:Wiki Loves Earth 2020/Winners"
      WikiTitle.normalize("Wiki Loves Monuments 2026 in Nepal") === "Wiki Loves Monuments 2026 in Nepal"
    }

    "turn empty and blank into empty" in {
      WikiTitle.normalize("") === ""
      WikiTitle.normalize("   \t ") === ""
      WikiTitle.normalize("[[]]") === ""
      WikiTitle.normalize("[[ | x]]") === ""
    }
  }

  "WikiTitle.isLegal" should {

    "accept plain, Unicode and empty titles" in {
      WikiTitle.isLegal("Category:Foo - Best 115") must beTrue
      WikiTitle.isLegal("Category:Київ") must beTrue
      WikiTitle.isLegal("") must beTrue
    }

    "refuse what MediaWiki refuses in a title" in {
      Seq("Category:A#B", "Category:A<B", "Category:A>B", "Category:A[B", "Category:A]B", "Category:A|B",
        "Category:A{B", "Category:A}B", "Category:A\u0000B", "Category:" + "x" * 300)
        .map(t => WikiTitle.isLegal(t) must beFalse).reduce(_ and _)
    }

    "accept every title that normalize gives for a link" in {
      WikiTitle.isLegal(WikiTitle.normalize("[[Category:Foo|sort key]]")) must beTrue
    }

    "refuse a link with brackets inside" in {
      WikiTitle.isLegal(WikiTitle.normalize("[[[Category:Foo]]]")) must beFalse
    }
  }

  "ImportSource" should {
    "read a pasted link as its category" in {
      ImportSource("[[Category:Foo]]") === ImportSource.Category("Category:Foo")
      ImportSource("[[:Template:Bar|x]]") === ImportSource.Template("Template:Bar")
    }
  }
}
