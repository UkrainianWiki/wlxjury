package views

import org.specs2.mutable.Specification

/** A page script's texts (JsTexts): JSON-escaped, never HTML-escaped, and unable to end the
  * script element. */
class JsTextsSpec extends Specification {

  private val bs = 92.toChar.toString // a backslash
  private val q  = "\""

  "JsTexts" should {
    "write the texts as a JSON object, ' and & as they are" in {
      JsTexts("a" -> "l'été & co", "b" -> "x").body === s"{${q}a$q:${q}l'été & co$q,${q}b$q:${q}x$q}"
    }

    "escape quotes, backslashes and <, so no text ends the string or the script" in {
      val body = JsTexts("a" -> (q + bs + "</script>")).body
      body === s"{${q}a$q:$q$bs$q$bs${bs}${bs}u003c/script>$q}"
      body must not contain "<"
    }
  }
}
