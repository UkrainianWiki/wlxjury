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

  /** Reads the cached photos; fails with the command to run if there are none. */
  def load(root: File): Path => Array[Byte] = {
    val good = jpgs(new File(root, "good"))
    val bad  = jpgs(new File(root, "bad"))
    require(good.nonEmpty, s"no photos in $root/good: run `python browser-test/docs-photos.py` first")
    path => {
      val (isBad, i) = choose(path, good.size, bad.size).get
      Files.readAllBytes((if (isBad) bad(i) else good(i)).toPath)
    }
  }

  type Path = String
}
