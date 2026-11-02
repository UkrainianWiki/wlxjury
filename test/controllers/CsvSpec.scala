package controllers

import db.scalikejdbc.{Round, User}
import org.intracer.wmua._
import org.specs2.mutable.Specification

class CsvSpec extends Specification {

  val binaryRound = Round(None, 1, Some("br"), 0, rates = Round.binaryRound)
  def ratedRound(maxRate: Int) = Round(None, 1, Some("rr"), 0, rates = Round.ratesById(maxRate))

  "export rates" should {
    "export nothing" in {
      Csv.exportRates(Seq.empty, Seq.empty, binaryRound) === Seq(Seq("Rank", "File", "Overall"), Csv.BinaryLegend)
      Csv.exportRates(Seq.empty, Seq.empty, ratedRound(5)) === Seq(Seq("Rank", "File", "Overall"))
    }

    "export unrated file, no jurors" in {
      val file = ImageWithRating(Image(0, "File:1.jpg"), Seq.empty)
      Csv.exportRates(Seq(file), Seq.empty, binaryRound) === Seq(
        Seq("Rank", "File", "Overall"),
        Seq("1", "File:1.jpg", "0"),
        Csv.BinaryLegend
      )
    }

    "export two unrated files, no jurors" in {
      val files = (1 to 2).map(i => ImageWithRating(Image(0, s"File:$i.jpg"), Seq.empty))
      Csv.exportRates(files, Seq.empty, binaryRound) === Seq(
        Seq("Rank", "File", "Overall"),
        Seq("1-2", "File:1.jpg", "0"),
        Seq("1-2", "File:2.jpg", "0"),
        Csv.BinaryLegend
      )
    }

    "export 1 file, 1 juror, binary round" in {
      val files = Seq(ImageWithRating(Image(0, "File:a.jpg"), Seq(Selection(0, 9, 0, rate = 1))))
      Csv.exportRates(files, Seq(User("Juror1", "", Some(9))), binaryRound) === Seq(
        Seq("Rank", "File", "Overall", "Juror1"),
        Seq("1", "File:a.jpg", "1", "1"),
        Csv.BinaryLegend
      )
    }

    "keep a binary round's votes as stored, 1, -1 and 0, empty where not assigned, with a legend row" in {
      val jurors = (1 to 4).map(i => User(s"Juror$i", "", Some(i.toLong)))
      val file = ImageWithRating(Image(0, "File:a.jpg"),
        Seq(Selection(0, 1, 0, rate = 1), Selection(0, 2, 0, rate = -1), Selection(0, 3, 0, rate = 0)))
      val rows = Csv.exportRates(Seq(file), jurors, binaryRound)
      (rows(1) === Seq("1", "File:a.jpg", "1", "1", "-1", "0", "")) and
        (rows.last === Seq("", "Overall: approvals. Jurors: 1 selected, -1 rejected, 0 not voted yet, empty not assigned"))
    }

    "export 1 file, 1 juror, rated round" in {
      val files = Seq(ImageWithRating(Image(0, "File:a.jpg"), Seq(Selection(0, 9, 0, rate = 5))))
      Csv.exportRates(files, Seq(User("Juror1", "", Some(9))), ratedRound(10)) should_===  Seq(
        Seq("Rank", "File", "Overall", "Juror1"),
        Seq("1", "File:a.jpg", "5", "5")
      )
    }
  }

  "formula injection" should {
    val starts = Seq("=", "+", "-", "@", "\t", "\r")

    "prefix a juror's name starting with a formula character in the header" in {
      val jurors = starts.zipWithIndex.map { case (c, i) => User(s"${c}HYPERLINK(1)", "", Some(i.toLong + 1)) }
      Csv.exportRates(Seq.empty, jurors, ratedRound(5)).head ===
        Seq("Rank", "File", "Overall") ++ starts.map(c => s"'${c}HYPERLINK(1)")
    }

    "prefix a file title starting with a formula character in its row" in {
      val files = starts.map(c => ImageWithRating(Image(0, s"${c}cmd|' /C calc'!A0"), Seq.empty))
      Csv.exportRates(files, Seq.empty, ratedRound(5)).tail.map(_(1)) ===
        starts.map(c => s"'${c}cmd|' /C calc'!A0")
    }

    "leave other text and the app's numbers, a binary round's -1 included, as they are" in {
      val jurors = Seq(User("Juror =1", "", Some(1)), User("Juror2", "", Some(2)))
      val file = ImageWithRating(Image(0, "File:a=b.jpg"), Seq(Selection(0, 1, 0, rate = -1)))
      Csv.exportRates(Seq(file), jurors, binaryRound).take(2) === Seq(
        Seq("Rank", "File", "Overall", "Juror =1", "Juror2"),
        Seq("1", "File:a=b.jpg", "0", "-1", "")
      )
    }

    "neutralise only a leading formula character" in {
      (Csv.text("") === "") and (Csv.text("a-b") === "a-b") and (Csv.text("-5") === "'-5")
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
