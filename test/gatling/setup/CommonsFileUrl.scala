package gatling.setup

import scalikejdbc._

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** The url of a Commons file's original, from its title, as the Commons API gives it
  * (imageinfo `url`, which imports store, less the `?utm_...` parameters):
  * `https://upload.wikimedia.org/wikipedia/commons/a/ab/Name.jpg`, where `Name.jpg` is the
  * title without `File:`, spaces as underscores, and `ab` the first two hex digits of the
  * name's MD5. MediaWiki percent-encodes the name as PHP's `rawurlencode`: everything but
  * `A-Za-z0-9-_.~` (`%D0%A1` for `С`, `%3F` for `?`, `%2B` for `+`), which
  * [[controllers.Global]]'s thumbnail urls and [[controllers.ImageProxyController]] expect.
  */
object CommonsFileUrl {

  def apply(title: String): String = {
    val name = title.stripPrefix("File:").replace(' ', '_')
    val md5 = MessageDigest.getInstance("MD5").digest(name.getBytes(UTF_8)).map("%02x".format(_)).mkString
    // URLEncoder leaves '*' and encodes '~', and writes a space (none here) as '+'
    val encoded = URLEncoder.encode(name, UTF_8).replace("*", "%2A").replace("%7E", "~").replace("+", "%20")
    s"https://upload.wikimedia.org/wikipedia/commons/${md5.take(1)}/${md5.take(2)}/$encoded"
  }

  /** Sets the url of every image without one from its title; the number of images set.
    * The Gatling fixture's CSV has titles only, so its images show no thumbnails without it.
    */
  def fillMissing(batchSize: Int = 1000)(implicit session: DBSession = AutoSession): Int = {
    val missing = sql"SELECT page_id, title FROM images WHERE url IS NULL"
      .map(rs => (rs.long(1), rs.string(2))).list()
    missing.grouped(batchSize).foreach { batch =>
      sql"UPDATE images SET url = ? WHERE page_id = ? AND url IS NULL"
        .batch(batch.map { case (pageId, title) => Seq(apply(title), pageId) }: _*)
        .apply()
    }
    missing.size
  }
}
