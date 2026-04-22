package controllers

import db.scalikejdbc.{Round, User}
import org.intracer.wmua._
import org.specs2.mutable.Specification

class CsvSpec extends Specification {

  val binaryRound = Round(None, 1, Some("br"), 0, rates = Round.binaryRound)
  def ratedRound(maxRate: Int) = Round(None, 1, Some("rr"), 0, rates = Round.ratesById(maxRate))

  "export rates" should {
    "export nothing" in {
      Csv.exportRates(Seq.empty, Seq.empty, binaryRound) === Seq(Seq("Rank", "File", "Overall"))
    }

    "export unrated file, no jurors" in {
      val file = ImageWithRating(Image(0, "File:1.jpg"), Seq.empty)
      Csv.exportRates(Seq(file), Seq.empty, binaryRound) === Seq(
        Seq("Rank", "File", "Overall"),
        Seq("1", "File:1.jpg", "0")
      )
    }

    "export two unrated files, no jurors" in {
      val files = (1 to 2).map(i => ImageWithRating(Image(0, s"File:$i.jpg"), Seq.empty))
      Csv.exportRates(files, Seq.empty, binaryRound) === Seq(
        Seq("Rank", "File", "Overall"),
        Seq("1-2", "File:1.jpg", "0"),
        Seq("1-2", "File:2.jpg", "0")
      )
    }

    "export 1 file, 1 juror, binary round" in {
      val files = Seq(ImageWithRating(Image(0, "File:a.jpg"), Seq(Selection(0, 9, 0, rate = 1))))
      Csv.exportRates(files, Seq(User("Juror1", "", Some(9))), binaryRound) === Seq(
        Seq("Rank", "File", "Overall", "Juror1"),
        Seq("1", "File:a.jpg", "1", "1")
      )
    }

    "export 1 file, 1 juror, rated round" in {
      val files = Seq(ImageWithRating(Image(0, "File:a.jpg"), Seq(Selection(0, 9, 0, rate = 5))))
      Csv.exportRates(files, Seq(User("Juror1", "", Some(9))), ratedRound(10)) should_===  Seq(
        Seq("Rank", "File", "Overall", "Juror1"),
        Seq("1", "File:a.jpg", "5", "5")
      )
    }
  }

  "add bom" should {
    val BOM = "\ufeff"
    "header" in {
      Csv.addBom(Seq(Seq("a", "b"))) === Seq(Seq(BOM + "a", "b"))
    }

    "header + data" in {
      Csv.addBom(Seq(Seq("a", "b"), Seq("1", "2"))) === Seq(Seq(BOM + "a", "b"), Seq("1", "2"))
    }
  }
}
