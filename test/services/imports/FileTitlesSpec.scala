package services.imports

import org.specs2.mutable.Specification

/** The titles of a pasted list of files, in the forms people paste them. */
class FileTitlesSpec extends Specification {

  "FileTitles.title" should {

    "add the namespace, and capitalise the name" in {
      FileTitles.title("A b.jpg") === Some("File:A b.jpg")
      FileTitles.title("a b.jpg") === Some("File:A b.jpg")
      FileTitles.title("File:A b.jpg") === Some("File:A b.jpg")
      FileTitles.title("file:a b.jpg") === Some("File:A b.jpg")
    }

    "replace an alias or a localised namespace" in {
      FileTitles.title("Image:A.jpg") === Some("File:A.jpg")
      FileTitles.title("Файл:Київ.jpg") === Some("File:Київ.jpg")
      FileTitles.title("Зображення:Київ.jpg") === Some("File:Київ.jpg")
      FileTitles.title("Datei:A.jpg") === Some("File:A.jpg")
      FileTitles.title("Media:A.jpg") === Some("File:A.jpg")
    }

    "keep a colon of the file's name" in {
      FileTitles.title("Kyiv: Golden Gate.jpg") === Some("File:Kyiv: Golden Gate.jpg")
      FileTitles.title("File:Kyiv: Golden Gate.jpg") === Some("File:Kyiv: Golden Gate.jpg")
    }

    "replace underscores, collapse spaces, trim" in {
      FileTitles.title("  File:A__b_c.jpg \t") === Some("File:A b c.jpg")
      FileTitles.title("File:A  b.jpg") === Some("File:A b.jpg")
    }

    "percent-decode, keeping a plus and an unencoded percent" in {
      FileTitles.title("File:C%C3%A9.jpg") === Some("File:Cé.jpg")
      FileTitles.title("%D0%A4%D0%B0%D0%B9%D0%BB:%D0%9A.jpg") === Some("File:К.jpg")
      FileTitles.title("File:A+B.jpg") === Some("File:A+B.jpg")
      FileTitles.title("File:100% pure.jpg") === Some("File:100% pure.jpg")
    }

    "read a description page's URL" in {
      FileTitles.title("https://commons.wikimedia.org/wiki/File:A_b.jpg") === Some("File:A b.jpg")
      FileTitles.title("https://commons.wikimedia.org/wiki/File:C%C3%A9.jpg?uselang=uk") === Some("File:Cé.jpg")
      FileTitles.title("https://commons.m.wikimedia.org/wiki/File:A.jpg#Summary") === Some("File:A.jpg")
      FileTitles.title("//commons.wikimedia.org/wiki/File:A.jpg") === Some("File:A.jpg")
      FileTitles.title("https://commons.wikimedia.org/w/index.php?title=File:A_b.jpg&oldid=1") === Some("File:A b.jpg")
      FileTitles.title("https://uk.wikipedia.org/wiki/Файл:Київ 1.jpg") === Some("File:Київ 1.jpg")
      FileTitles.title("https://uk.wikipedia.org/wiki/%D0%A4%D0%B0%D0%B9%D0%BB:A.jpg") === Some("File:A.jpg")
      FileTitles.title("https://uk.wikipedia.org/wiki/Київ#/media/Файл:Київ_2.jpg") === Some("File:Київ 2.jpg")
      FileTitles.title("https://commons.wikimedia.org/wiki/Special:FilePath/A_b.jpg") === Some("File:A b.jpg")
    }

    "read a file's URL, full size or a thumbnail" in {
      FileTitles.title("https://upload.wikimedia.org/wikipedia/commons/a/ab/A_b.jpg") === Some("File:A b.jpg")
      FileTitles.title("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/C%C3%A9.jpg/800px-C%C3%A9.jpg") ===
        Some("File:Cé.jpg")
    }

    "read a wiki link, a gallery line and a spreadsheet row" in {
      FileTitles.title("[[File:A b.jpg|thumb|caption]]") === Some("File:A b.jpg")
      FileTitles.title("[[Файл:A.jpg]]") === Some("File:A.jpg")
      FileTitles.title("File:A.jpg|caption") === Some("File:A.jpg")
      FileTitles.title("File:A.jpg\tAuthor\t5") === Some("File:A.jpg")
    }

    "skip an empty line" in {
      FileTitles.title("   ") must beNone
    }
  }

  "FileTitles" should {
    "read a list, without duplicates" in {
      FileTitles(
        "File:A_b.jpg\r\n\r\n  https://commons.wikimedia.org/wiki/File:C%C3%A9.jpg \nA b.jpg\nФайл:A_b.jpg\n") ===
        Seq("File:A b.jpg", "File:Cé.jpg")
    }
  }
}
