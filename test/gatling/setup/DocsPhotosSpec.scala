package gatling.setup

import org.specs2.mutable.Specification

class DocsPhotosSpec extends Specification {

  "DocsPhotos.fileName" should {
    "name the file of a thumbnail path, whatever its width" in {
      DocsPhotos.fileName("/wikipedia/commons/thumb/4/49/Name.jpg/640px-Name.jpg") === "Name.jpg"
      DocsPhotos.fileName("/wikipedia/commons/thumb/4/49/Name.jpg/1280px-Name.jpg") === "Name.jpg"
    }
    "name the file of an original's path" in {
      DocsPhotos.fileName("/wikipedia/commons/4/49/Name.jpg") === "Name.jpg"
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
  }

  "DocsPhotos.choose" should {
    "give a file the same photo at every width and in every run" in {
      val a = DocsPhotos.choose("/wikipedia/commons/thumb/4/49/Name.jpg/640px-Name.jpg", 110, 9)
      a === DocsPhotos.choose("/wikipedia/commons/thumb/4/49/Name.jpg/1280px-Name.jpg", 110, 9)
      a === DocsPhotos.choose("/wikipedia/commons/thumb/4/49/Name.jpg/640px-Name.jpg", 110, 9)
    }
    "stay inside the pools and show about a quarter bad photos" in {
      val picks = (1 to 2000).flatMap(i => DocsPhotos.choose(s"/wikipedia/commons/a/b/File $i.jpg", 110, 9))
      picks.forall { case (bad, i) => i >= 0 && i < (if (bad) 9 else 110) } must beTrue
      val share = picks.count(_._1).toDouble / picks.size
      share must beBetween(0.18, 0.32)
      picks.map(_._2).distinct.size must be_>=(100)
    }
    "use the good pool alone without bad photos, and nothing without photos" in {
      DocsPhotos.choose("/x/y.jpg", 5, 0).map(_._1) === Some(false)
      DocsPhotos.choose("/x/y.jpg", 0, 0) === None
    }
  }
}
