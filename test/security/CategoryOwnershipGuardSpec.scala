package security

import org.specs2.mutable.Specification

/** A tripwire, not a proof: a source scan of `app/` (no database) for the writes that once made
  * contests share one list of files.
  *
  * A contest's files are the members of its category (`contest_jury.category_id`). From 2017 an
  * import set the contest's source by title and found or created the category of that title
  * (`category.title` is unique): every import with no source name shared the row of the empty
  * title, one row of mixed files that ten contests pointed at, and a source set by title gave a
  * contest another year's category. Since then a contest has a category of its own, `#contest N`,
  * given by its import. This spec fails when a new write path appears, so that review looks at it:
  *
  *  - `CategoryJdbc.findOrInsert` is called only with a contest's own title
  *    (`ImageService.ownCategory`); `addCountries`' country categories are their own method;
  *  - `contest_jury.category_id` is written only by `ContestJuryJdbc` (its `create`, called with
  *    none, and `setCategory`), and `setCategory` is called only by `ImageService` (the import) and
  *    `FileListRepair` (root's repair, to the contest's own row, and its Undo, back to the old one);
  *  - rows of `category` are inserted only by `CategoryJdbc`.
  *
  * Every reference to `findOrInsert` and `setCategory` is a call (the 2017 code passed
  * `CategoryJdbc.findOrInsert` as a method value, `images.map(CategoryJdbc.findOrInsert)`). Known
  * limit: a text scan can't tell whose own title it is, so
  * `findOrInsert(ImageService.ownCategory(other.getId))` would still pass.
  */
class CategoryOwnershipGuardSpec extends Specification {

  private lazy val sources = SourceScan.files("app", ".scala")

  private def counts(pattern: scala.util.matching.Regex): Map[String, Int] =
    SourceScan.hits(sources, pattern).map { case (file, lines) => file.replaceFirst("^.*?(app/)", "$1") -> lines.size }

  /** References to `name` other than its definition that are not calls: a method value
    * (`images.map(CategoryJdbc.findOrInsert)`), an eta-expansion; by file and line.
    */
  private def uncalled(name: String): Map[String, Seq[Int]] =
    SourceScan.hits(sources, s"""(?<!def )\\b$name\\b(?!\\s*\\()""".r)

  /** The text of each call of `name` (not its definition): the file and the call's arguments. */
  private def calls(name: String): Seq[(String, String)] =
    sources.flatMap { s =>
      s"""(?<!def )\\b$name\\s*\\(""".r.findAllMatchIn(s.text)
        .map(m => s.path.replaceFirst("^.*?(app/)", "$1") -> SourceScan.callText(s.text, m.end).trim).toList
    }

  "a contest's category" should {

    "be found or added only by a contest's own title" in {
      uncalled("findOrInsert") must beEmpty
      val found = calls("findOrInsert")
      // the import, and root's list repair (services.FileListRepair), which moves a contest to its own
      found.map(_._1).distinct.sorted === Seq("app/services/FileListRepair.scala", "app/services/ImageService.scala")
      forall(found) { case (_, args) => args must startWith("ImageService.ownCategory(") }
    }

    "be written to contest_jury only by ContestJuryJdbc, and set only by the import" in {
      counts(""""categoryId"\s*->""".r) === Map("app/db/scalikejdbc/ContestJuryJdbc.scala" -> 1)
      // ContestJuryJdbc.create's column; CategoryJdbc's is category_members' own
      counts("""column\.categoryId\s*->""".r) ===
        Map("app/db/scalikejdbc/ContestJuryJdbc.scala" -> 1, "app/db/scalikejdbc/CategoryJdbc.scala" -> 1)
      counts("""(?is)UPDATE\s+contest_jury\b[^;"]{0,400}?\bcategory_id\s*=""".r) must beEmpty
      counts("""(?is)INSERT\s+(IGNORE\s+)?INTO\s+contest_jury\b[^;"]{0,400}?\bcategory_id\b""".r) must beEmpty
      uncalled("setCategory") must beEmpty
      // the import; root's list repair (its own row) and its Undo (the list it had before, logged)
      calls("setCategory").map(_._1).distinct.sorted === Seq("app/services/FileListRepair.scala", "app/services/ImageService.scala")
      // exactly two there: Confirm's (the own row) and Undo's (the old row)
      calls("setCategory").count(_._1 == "app/services/FileListRepair.scala") === 2
    }

    "be added to contests with none: a new contest's category comes from its first import" in {
      val created = calls("ContestJuryJdbc\\.create")
      created.map(_._1) === Seq("app/services/ContestService.scala")
      forall(created) { case (_, args) => args must not(beMatching("""(?s).*\bcategoryId\b.*""")) }
    }
  }

  "category rows" should {

    "be inserted only by CategoryJdbc" in {
      counts("""(?i)INSERT\s+(IGNORE\s+)?INTO\s+category\b""".r).keySet === Set("app/db/scalikejdbc/CategoryJdbc.scala")
      counts("""CategoryJdbc\s*\.\s*create""".r) must beEmpty
    }
  }
}
