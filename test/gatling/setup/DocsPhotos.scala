package gatling.setup

import java.io.File
import java.nio.file.Files

/** The photos behind -Dui.images=docs (the user guides' screenshots, docs/user/images/README.md):
  * the files that browser-test/docs-photos.py cached under target/docs-photos/, which the
  * fixture's image stub serves instead of its grey placeholder. */
object DocsPhotos {

  /** One in `BadEvery` files shows a degraded photo (blur, tilt, exposure, low resolution), so
    * that the yes/no examples have clearly bad ones. */
  val BadEvery = 4

  /** The file a request path (`/wikipedia/commons/thumb/4/49/Name.jpg/1280px-Name.jpg` or
    * `/wikipedia/commons/4/49/Name.jpg`) is for, whatever the thumbnail's width. */
  def fileName(path: String): String = {
    val parts = path.split('/').filter(_.nonEmpty)
    if (parts.contains("thumb") && parts.length >= 2) parts(parts.length - 2) else parts.lastOption.getOrElse("")
  }

  /** A stable hash (not String.hashCode's platform-independent but unmixed value, so the choice
    * spreads over the pool): the same file name always gets the same photo, in every run. */
  private def stableHash(s: String): Int = {
    var h = 0x811c9dc5
    for (b <- s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) h = (h ^ (b & 0xff)) * 0x01000193
    h & Int.MaxValue
  }

  /** The index into `good` or `bad` of the photo for `path`; `None` if both are empty. */
  def choose(path: String, goodCount: Int, badCount: Int): Option[(Boolean, Int)] = {
    val h = stableHash(fileName(path))
    if (goodCount == 0 && badCount == 0) None
    else if (badCount > 0 && (goodCount == 0 || h % BadEvery == 0)) Some((true, (h / BadEvery) % badCount))
    else Some((false, (h / BadEvery) % goodCount))
  }

  private def jpgs(dir: File): Seq[File] =
    Option(dir.listFiles()).toSeq.flatten.filter(_.getName.endsWith(".jpg")).sortBy(_.getName)

  val DefaultLists = Seq("wlm-ua", "wlm-intl")

  /** The lists of -Dui.photos (`wlm-ua`, `wlm-intl`, `wle-ua`, `wle-intl`, the browser-test/docs-photos-*.json
    * files); the default if it is not set. The photos of the lists, in this order, are the pool,
    * so the mapping is the same for the same choice. */
  def lists(prop: Option[String]): Seq[String] = {
    val names = prop.toSeq.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty).distinct
    if (names.isEmpty) DefaultLists else names
  }

  /** Reads the cached photos of `lists`; fails with the command to run if there are none. */
  def load(root: File, lists: Seq[String]): Path => Array[Byte] = {
    val good = lists.flatMap(l => jpgs(new File(root, s"$l/good")))
    val bad  = lists.flatMap(l => jpgs(new File(root, s"$l/bad")))
    require(good.nonEmpty, s"no photos in $root for ${lists.mkString(",")}: run `python browser-test/docs-photos.py` (DOCS_PHOTOS=${lists.mkString(",")}) first")
    path => {
      val (isBad, i) = choose(path, good.size, bad.size).get
      Files.readAllBytes((if (isBad) bad(i) else good(i)).toPath)
    }
  }

  type Path = String
}
