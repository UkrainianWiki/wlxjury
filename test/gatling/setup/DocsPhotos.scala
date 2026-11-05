package gatling.setup

import play.api.libs.json.{JsObject, Json}

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** The photos behind -Dui.images=docs (the user guides' screenshots, docs/user/images/README.md):
  * the files that browser-test/docs-photos.py cached and verified under target/docs-photos/,
  * which the fixture's image stub serves instead of its grey placeholder. */
object DocsPhotos {

  /** One in `BadEvery` files shows a degraded photo (blur, tilt, exposure, low resolution), so
    * that the yes/no examples have clearly bad ones; never a file the shots show at the top. */
  val BadEvery = 4

  /** How many files of each list the shots open ([[UiShotsServer]]) count as shown at its top:
    * a gallery's first page (15) and the first rows of a file list, with a margin. */
  val ShownPerList = 30

  /** The file a request path (`/wikipedia/commons/thumb/4/49/Name.jpg/1280px-Name.jpg` or
    * `/wikipedia/commons/4/49/Name.jpg`) is for, whatever the thumbnail's width. */
  def fileName(path: String): String = {
    val parts = path.split('/').filter(_.nonEmpty)
    if (parts.contains("thumb") && parts.length >= 2) parts(parts.length - 2) else parts.lastOption.getOrElse("")
  }

  /** A file's key: its name as in its title (spaces, not a URL's underscores), without `File:`. */
  def keyOfName(name: String): String = name.replace('_', ' ')
  def keyOfTitle(title: String): String = title.stripPrefix("File:")

  /** A stable hash (not String.hashCode's platform-independent but unmixed value, so the choice
    * spreads over the pool), for a file the fixture doesn't have. */
  private def stableHash(s: String): Int = {
    var h = 0x811c9dc5
    for (b <- s.getBytes(UTF_8)) h = (h ^ (b & 0xff)) * 0x01000193
    h & Int.MaxValue
  }

  /** The photo of the file with fixture ordinal `ordinal`: `(bad, index)` into the pool's bad or
    * good photos. Consecutive ordinals get different good photos (the index is the ordinal
    * modulo the pool); every `BadEvery`th ordinal gets a degraded one unless the file is `shown`.
    * `None` without good photos. */
  def choose(ordinal: Int, shown: Boolean, goodCount: Int, badCount: Int): Option[(Boolean, Int)] =
    if (goodCount == 0) None
    else if (!shown && badCount > 0 && ordinal % BadEvery == BadEvery - 1) Some((true, (ordinal / BadEvery) % badCount))
    else Some((false, ordinal % goodCount))

  val DefaultLists = Seq("wlm-ua", "wlm-intl")

  /** A list's name as docs-photos.py takes it: lowercase words joined by hyphens. */
  private val ListName = "^[a-z]+(-[a-z]+)*$".r

  /** The lists of -Dui.photos (`wlm-ua`, `wlm-intl`, `wle-ua`, `wle-intl`, the browser-test/docs-photos-*.json
    * files); the default if it is not set. The photos of the lists, in this order, are the pool,
    * so the mapping is the same for the same choice. */
  def lists(prop: Option[String]): Seq[String] = {
    val names = prop.toSeq.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty).distinct
    names.foreach(n => require(ListName.matches(n), s"ui.photos: '$n' is not a list name"))
    if (names.isEmpty) DefaultLists else names
  }

  /** The photos of the lists, in their order. */
  final case class Pool(good: IndexedSeq[File], bad: IndexedSeq[File])

  /** A manifest's file: `good/` or `bad/` and docs-photos.py's hashed name, nothing else. */
  private val ManifestFile = "^(good|bad)/[0-9a-f]{24}\\.jpg$".r

  /** Reads the photos that docs-photos.py's last successful run of each list verified (its
    * `manifest.json`); fails with the command to run if a list has none. */
  def pool(root: File, lists: Seq[String]): Pool = {
    val command = s"run `python browser-test/docs-photos.py` (DOCS_PHOTOS=${lists.mkString(",")}) first"
    def filesOf(list: String): (Seq[File], Seq[File]) = {
      val dir      = new File(root, list)
      val manifest = new File(dir, "manifest.json")
      require(manifest.isFile, s"no verified photos for $list in $dir: $command")
      val json = Json.parse(Files.readAllBytes(manifest.toPath))
      def files(kind: String): Seq[File] = (json \ kind).as[Seq[JsObject]].map { entry =>
        val name = (entry \ "file").as[String]
        require(ManifestFile.matches(name) && name.startsWith(kind + "/"), s"$manifest: unexpected file '$name'")
        val file = new File(dir, name)
        require(file.isFile, s"$manifest lists $name, which is missing: $command")
        file
      }
      (files("good"), files("bad"))
    }
    val perList = lists.map(filesOf)
    val good    = perList.flatMap(_._1).toIndexedSeq
    val bad     = perList.flatMap(_._2).toIndexedSeq
    require(good.nonEmpty, s"no photos in $root for ${lists.mkString(",")}: $command")
    Pool(good, bad)
  }

  /** The fixture's files: each one's ordinal (by page id) and those the guides' shots show at the
    * top of a page ([[UiShotsServer]]), by [[keyOfTitle]]. */
  final case class Fixture(ordinals: Map[String, Int], shown: Set[String])

  object Fixture {
    def apply(titlesByPageId: Seq[String], shownTitles: Iterable[String]): Fixture =
      Fixture(titlesByPageId.map(keyOfTitle).zipWithIndex.toMap, shownTitles.map(keyOfTitle).toSet)
  }

  /** The stub's photos: none until the fixture's files are known ([[setFixture]]), so that no
    * thumbnail is cached before the shown files are protected from a degraded photo. */
  final class Photos(val pool: Pool) {
    @volatile private var fixture: Option[Fixture] = None

    def setFixture(f: Fixture): Unit = fixture = Some(f)

    /** The photo file for `path`; `None` before [[setFixture]]. A file the fixture doesn't have
      * gets a good photo by a hash of its name. */
    def fileFor(path: String): Option[File] = fixture.flatMap { f =>
      val key = keyOfName(fileName(path))
      val (ordinal, shown) = f.ordinals.get(key).fold((stableHash(key), true))(o => (o, f.shown.contains(key)))
      choose(ordinal, shown, pool.good.size, pool.bad.size).map { case (isBad, i) => if (isBad) pool.bad(i) else pool.good(i) }
    }

    def photo(path: String): Option[Array[Byte]] = fileFor(path).map(file => Files.readAllBytes(file.toPath))
  }

  def load(root: File, lists: Seq[String]): Photos = new Photos(pool(root, lists))
}
