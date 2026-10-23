package services.imports

import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets
import scala.util.Try

/** The file titles of a pasted list, as people paste them: one per line, a title with or
  * without its namespace (`File:`, an alias such as `Image:`, or a localised one such as
  * `Файл:`), percent-encoded or not, with spaces or underscores; a link to the file's
  * description page (on Commons or another wiki: `/wiki/…`, `index.php?title=…`,
  * `#/media/…`, `Special:FilePath/…`) or to the file itself (`upload.wikimedia.org`, full
  * size or a thumbnail); a wiki link or a gallery line (`[[File:A.jpg|thumb]]`,
  * `File:A.jpg|caption`); a spreadsheet row (its first cell).
  */
object FileTitles {

  /** The list's titles as Commons names them (`File:A b.jpg`), without duplicates. */
  def apply(list: String): Seq[String] =
    list.split("(\r\n|\n|\r)").iterator.flatMap(title).toSeq.distinct

  /** The line's file title, if it has one. */
  def title(line: String): Option[String] = {
    val text = withoutWikiLink(line.takeWhile(_ != '\t').trim)
    val name = if (isUrl(text)) Try(fromUrl(text)).toOption.flatten else Some(decode(text))
    name
      .map(normalize)
      .map(withoutNamespace)
      .filter(_.nonEmpty)
      .map(n => "File:" + n.head.toUpper + n.tail)
  }

  /** `[[File:A.jpg|thumb|…]]` and `File:A.jpg|caption`: the link's target. */
  private def withoutWikiLink(text: String): String = {
    val inner = text.stripPrefix("[[").takeWhile(_ != ']')
    if (isUrl(inner)) inner else inner.takeWhile(_ != '|')
  }

  private def isUrl(text: String): Boolean = {
    val lower = text.toLowerCase
    lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")
  }

  /** The file a URL is of: its description page's title, or the name of the file it shows. */
  private def fromUrl(url: String): Option[String] = {
    val uri = new URI(encodeUnsafe(if (url.startsWith("//")) "https:" + url else url))
    val segments = Option(uri.getRawPath).getOrElse("").split('/').filter(_.nonEmpty).toSeq.map(decode)
    val fragment = Option(uri.getRawFragment).map(decode)
    val query = Option(uri.getRawQuery).toSeq
      .flatMap(_.split('&'))
      .flatMap(_.split("=", 2) match {
        case Array(k, v) => Some(decode(k) -> decode(v))
        case _           => None
      })
      .toMap
    val host = Option(uri.getHost).getOrElse("").toLowerCase

    // an article's media viewer: https://uk.wikipedia.org/wiki/Article#/media/Файл:A.jpg
    fragment.filter(_.startsWith("/media/")).map(_.stripPrefix("/media/")).orElse {
      if (host == "upload.wikimedia.org") {
        // /wikipedia/commons/a/ab/A.jpg, /wikipedia/commons/thumb/a/ab/A.jpg/800px-A.jpg
        val thumb = segments.indexOf("thumb")
        if (thumb >= 0) segments.lift(thumb + 3) else segments.lastOption
      } else {
        // /wiki/File:A.jpg, /w/index.php?title=File:A.jpg; a title may have slashes
        val wikiPath = segments.dropWhile(_ != "wiki").drop(1)
        query.get("title").orElse(Option.when(wikiPath.nonEmpty)(wikiPath.mkString("/"))).map(withoutSpecialPage)
      }
    }
  }

  /** `Special:FilePath/A.jpg`, `Special:Redirect/file/A.jpg`: the file's name. */
  private def withoutSpecialPage(title: String): String = {
    val lower = title.toLowerCase
    SpecialPagePrefixes.find(lower.startsWith).fold(title)(prefix => title.drop(prefix.length))
  }

  private val SpecialPagePrefixes =
    Seq("special:filepath/", "special:redirect/file/", "спеціальна:filepath/", "служебная:filepath/")

  /** Percent-decodes, keeping a `+` (file names have them) and text that isn't encoded. */
  private def decode(text: String): String =
    Try(URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8)).getOrElse(text)

  private val Unsafe = "\"<>\\^`{|}"

  /** Encodes what [[URI]] refuses in a pasted URL that isn't encoded: spaces, non-ASCII
    * letters, a `%` not followed by two hex digits.
    */
  private def encodeUnsafe(url: String): String = {
    val sb = new StringBuilder
    url.replaceAll("%(?![0-9A-Fa-f]{2})", "%25").codePoints().forEach { cp =>
      if (cp > 127 || cp <= 32 || Unsafe.indexOf(cp) >= 0)
        new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).foreach(b => sb.append(f"%%${b & 0xff}%02X"))
      else sb.appendAll(Character.toChars(cp))
    }
    sb.toString
  }

  /** As MediaWiki: underscores are spaces, runs of spaces one. */
  private def normalize(title: String): String =
    title.replace('_', ' ').replaceAll("[\\s ]+", " ").trim

  /** The file's name: without a file namespace prefix, in any language. Another prefix
    * stays: a colon in the file's name, or not a file (reported as not found).
    */
  private def withoutNamespace(title: String): String =
    title.indexOf(':') match {
      case -1 => title
      case i if FileNamespaces.contains(title.take(i).trim.toLowerCase) => title.drop(i + 1).trim
      case _ => title
    }

  /** The file namespace's names and aliases in the languages of the wikis (lower case). */
  val FileNamespaces: Set[String] = Set(
    "file", "image", "media",
    // Cyrillic: uk, ru, be, bg, sr, mk, kk, …
    "файл", "зображення", "изображение", "медіа", "медиа", "выява", "слика", "податотека", "сурет",
    // Latin
    "datei", "bild", "fichier", "plik", "grafika", "bestand", "afbeelding", "archivo", "imagen", "ficheiro",
    "arquivo", "imagem", "immagine", "soubor", "súbor", "fil", "billede", "tiedosto", "kuva", "kép", "fájl",
    "fișier", "imagine", "dosya", "resim", "datoteka", "slika", "vaizdas", "failas", "attēls", "fails", "pilt",
    "mynd", "fitxer", "imatge", "fitxategi", "irudia", "delwedd", "íomhá", "comhad", "berkas", "gambar", "tập tin",
    "hình", "faili", "skeda", "skedar",
    // other scripts
    "αρχείο", "εικόνα", "ファイル", "画像", "파일", "그림", "文件", "檔案", "图像", "圖像", "ملف", "صورة", "קובץ", "תמונה",
    "پرونده", "تصویر", "ไฟล์", "चित्र", "फ़ाइल", "ফাইল", "ფაილი", "նիշք", "պատկեր"
  )
}
