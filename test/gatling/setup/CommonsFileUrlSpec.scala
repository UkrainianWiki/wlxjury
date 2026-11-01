package gatling.setup

import org.specs2.mutable.Specification

/** The expected urls are the Commons API's (imageinfo `url`) for these files. */
class CommonsFileUrlSpec extends Specification {

  "CommonsFileUrl" should {
    "percent-encode a Cyrillic name" in {
      CommonsFileUrl("File:Свято-Миколаївська церква, Нижня Будаківка 03.jpg") ===
        "https://upload.wikimedia.org/wikipedia/commons/4/49/" +
        "%D0%A1%D0%B2%D1%8F%D1%82%D0%BE-%D0%9C%D0%B8%D0%BA%D0%BE%D0%BB%D0%B0%D1%97%D0%B2%D1%81%D1%8C%D0%BA%D0%B0" +
        "_%D1%86%D0%B5%D1%80%D0%BA%D0%B2%D0%B0%2C_%D0%9D%D0%B8%D0%B6%D0%BD%D1%8F" +
        "_%D0%91%D1%83%D0%B4%D0%B0%D0%BA%D1%96%D0%B2%D0%BA%D0%B0_03.jpg"
    }

    "encode spaces as underscores and punctuation as rawurlencode does" in {
      CommonsFileUrl("File:+DSCF1804.jpg") ===
        "https://upload.wikimedia.org/wikipedia/commons/d/d2/%2BDSCF1804.jpg"
      CommonsFileUrl("File:Дзвіниця?.jpg") ===
        "https://upload.wikimedia.org/wikipedia/commons/b/b9/%D0%94%D0%B7%D0%B2%D1%96%D0%BD%D0%B8%D1%86%D1%8F%3F.jpg"
      CommonsFileUrl("File:DSC 0736+ Пам`ятник Юрію Дрогобичу.jpg") ===
        "https://upload.wikimedia.org/wikipedia/commons/3/3a/DSC_0736%2B_" +
        "%D0%9F%D0%B0%D0%BC%60%D1%8F%D1%82%D0%BD%D0%B8%D0%BA_%D0%AE%D1%80%D1%96%D1%8E" +
        "_%D0%94%D1%80%D0%BE%D0%B3%D0%BE%D0%B1%D0%B8%D1%87%D1%83.jpg"
      CommonsFileUrl("File:(Жаботин) Успенська церква.jpg") ===
        "https://upload.wikimedia.org/wikipedia/commons/f/f8/%28%D0%96%D0%B0%D0%B1%D0%BE%D1%82%D0%B8%D0%BD%29" +
        "_%D0%A3%D1%81%D0%BF%D0%B5%D0%BD%D1%81%D1%8C%D0%BA%D0%B0_%D1%86%D0%B5%D1%80%D0%BA%D0%B2%D0%B0.jpg"
    }

    "keep the characters rawurlencode keeps" in {
      CommonsFileUrl("File:A-b_c.d~e*f.jpg").split('/').last === "A-b_c.d~e%2Af.jpg"
    }
  }
}
