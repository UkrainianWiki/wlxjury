package security

import org.specs2.mutable.Specification

/** G8 (N3): every CSV export goes through `controllers.Csv` (whose `text` neutralises a cell
  * that a spreadsheet would read as a formula) and is served by `GalleryController.csvStream`:
  * no `CSVWriter` or `text/csv` anywhere else in `app/`. A new export adds its file here only
  * after it writes its user text through `Csv.text`.
  */
class ExportsSpec extends Specification {

  private val Allowed: Map[String, String] = Map(
    "app/controllers/Csv.scala" -> "the CSV writer; user text through Csv.text",
    "app/controllers/GalleryController.scala" -> "csvStream serves Csv.rateRows' rows as text/csv"
  )

  "CSV writing" should {
    "happen only in Csv and GalleryController.csvStream" in {
      val hits = SourceScan.hits(SourceScan.files("app", ".scala", ".html"), """CSVWriter|text/csv""".r)
      (hits.keySet -- Allowed.keySet) must beEmpty
    }
  }
}
