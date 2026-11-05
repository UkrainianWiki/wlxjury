package gatling.setup

import org.specs2.mutable.Specification

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class DocsPhotosSpec extends Specification {

  "DocsPhotos.fileName" should {
    "name the file of a thumbnail path, whatever its width" in {
      DocsPhotos.fileName("/wikipedia/commons/thumb/4/49/Name.jpg/640px-Name.jpg") === "Name.jpg"
      DocsPhotos.fileName("/wikipedia/commons/thumb/4/49/Name.jpg/1280px-Name.jpg") === "Name.jpg"
    }
    "name the file of an original's path" in {
      DocsPhotos.fileName("/wikipedia/commons/4/49/Name.jpg") === "Name.jpg"
    }
    "match a title's key, underscores as spaces" in {
      DocsPhotos.keyOfName(DocsPhotos.fileName("/wikipedia/commons/thumb/4/49/A_b_c.jpg/640px-A_b_c.jpg")) ===
        DocsPhotos.keyOfTitle("File:A b c.jpg")
    }
  }

  "DocsPhotos.lists" should {
    "default to both lists, Ukraine first" in {
      DocsPhotos.lists(None) === Seq("wlm-ua", "wlm-intl")
      DocsPhotos.lists(Some(" ")) === Seq("wlm-ua", "wlm-intl")
    }
    "take the lists named, in order, without repeats" in {
      DocsPhotos.lists(Some("wle-intl")) === Seq("wle-intl")
      DocsPhotos.lists(Some("wle-intl, wlm-ua,wle-intl")) === Seq("wle-intl", "wlm-ua")
    }
    "refuse a name that isn't a list's" in {
      DocsPhotos.lists(Some("../x")) must throwA[IllegalArgumentException]
      DocsPhotos.lists(Some("wlm-ua,WLM")) must throwA[IllegalArgumentException]
    }
  }

  "DocsPhotos.choose" should {
    "give consecutive files different photos" in {
      val picks = (0 until 200).map(o => DocsPhotos.choose(o, shown = true, 109, 9).get)
      picks.forall(!_._1) must beTrue
      picks.sliding(2).forall { case Seq(a, b) => a != b } must beTrue
      picks.take(109).distinct.size === 109
    }
    "give a degraded photo to one file in four, never to a shown one" in {
      val picks = (0 until 2000).map(o => DocsPhotos.choose(o, shown = false, 109, 9).get)
      picks.count(_._1) === 500
      picks.forall { case (bad, i) => i >= 0 && i < (if (bad) 9 else 109) } must beTrue
      (0 until 2000).flatMap(o => DocsPhotos.choose(o, shown = true, 109, 9)).exists(_._1) must beFalse
    }
    "use the good pool alone without bad photos, and nothing without good ones" in {
      DocsPhotos.choose(3, shown = false, 5, 0) === Some((false, 3))
      DocsPhotos.choose(3, shown = false, 0, 9) === None
    }
  }

  "DocsPhotos.load" should {
    def withPhotos[A](manifests: Map[String, String], files: Seq[String])(test: File => A): A = {
      val root = Files.createTempDirectory("docs-photos").toFile
      try {
        for ((list, json) <- manifests) {
          new File(root, s"$list/good").mkdirs()
          new File(root, s"$list/bad").mkdirs()
          Files.write(new File(root, s"$list/manifest.json").toPath, json.getBytes(UTF_8))
        }
        files.foreach(f => Files.write(new File(root, f).toPath, f.getBytes(UTF_8)))
        test(root)
      } finally {
        def delete(f: File): Unit = { Option(f.listFiles()).toSeq.flatten.foreach(delete); f.delete() }
        delete(root)
      }
    }
    val (g1, g2, b1) = ("good/" + "a" * 24 + ".jpg", "good/" + "b" * 24 + ".jpg", "bad/" + "c" * 24 + ".jpg")
    val manifest =
      s"""{"good": [{"title": "File:A.jpg", "file": "$g1"}, {"title": "File:B.jpg", "file": "$g2"}],
         | "bad": [{"base": "File:A.jpg", "file": "$b1"}]}""".stripMargin

    "serve the manifest's files in its order, nothing until the fixture is known" in
      withPhotos(Map("wlm-ua" -> manifest), Seq(s"wlm-ua/$g1", s"wlm-ua/$g2", s"wlm-ua/$b1")) { root =>
        val photos = DocsPhotos.load(root, Seq("wlm-ua"))
        photos.pool.good.map(_.getName) === Seq("a" * 24 + ".jpg", "b" * 24 + ".jpg")
        photos.photo("/wikipedia/commons/thumb/1/12/F0.jpg/640px-F0.jpg") must beNone
        photos.setFixture(DocsPhotos.Fixture((0 to 3).map(i => s"File:F$i.jpg"), Seq("File:F3.jpg")))
        def nameOf(i: Int) = photos.fileFor(s"/wikipedia/commons/thumb/1/12/F$i.jpg/640px-F$i.jpg").map(_.getName)
        (nameOf(0), nameOf(1), nameOf(3)) === ((Some("a" * 24 + ".jpg"), Some("b" * 24 + ".jpg"), Some("b" * 24 + ".jpg")))
        photos.setFixture(DocsPhotos.Fixture((0 to 3).map(i => s"File:F$i.jpg"), Nil))
        nameOf(3) === Some("c" * 24 + ".jpg")
      }
    "refuse a list without a manifest (not verified by docs-photos.py)" in
      withPhotos(Map.empty, Nil) { root =>
        new File(root, "wlm-ua/good").mkdirs()
        Files.write(new File(root, s"wlm-ua/$g1").toPath, Array[Byte](1))
        DocsPhotos.load(root, Seq("wlm-ua")) must throwA[IllegalArgumentException]
      }
    "refuse a manifest naming a missing file or a path outside its list" in {
      withPhotos(Map("wlm-ua" -> manifest), Seq(s"wlm-ua/$g1", s"wlm-ua/$b1")) { root =>
        DocsPhotos.load(root, Seq("wlm-ua")) must throwA[IllegalArgumentException]
      }
      val outside = """{"good": [{"title": "File:A.jpg", "file": "../x.jpg"}], "bad": []}"""
      withPhotos(Map("wlm-ua" -> outside), Nil) { root =>
        DocsPhotos.load(root, Seq("wlm-ua")) must throwA[IllegalArgumentException]
      }
    }
  }
}
