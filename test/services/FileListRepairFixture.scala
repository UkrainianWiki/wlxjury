package services

import db.scalikejdbc.{CategoryJdbc, ContestJuryJdbc, Round, User, VoteJdbc}
import org.intracer.wmua.ContestJury
import scalikejdbc._

/** A shared untitled list as the old imports left it (services.FileListRepair): one blank-titled
  * row of 20 files, the list of contests A, B, C and D; A's rounds show files 1-5 (round a1 all
  * five, a2 two of them) and A's round "whole" all 20 (filled from the whole row: mixed); B's round
  * files 6-10, C's 11-15; D has no rounds; E is already on its own row ("#contest E"), with one
  * newly imported file there, and its round shows files 16 and 17, left in the shared row; files
  * 18-20 no round shows. F's list is a titled row that G names as its source (shared too).
  * One juror's votes fill the rounds (vote and round_image rows).
  */
object FileListRepairFixture {

  final case class F(blank: Long, a: ContestJury, b: ContestJury, c: ContestJury, d: ContestJury, e: ContestJury,
      f: ContestJury, g: ContestJury, eOwn: Long, titled: Long, a1: Long, a2: Long, aWhole: Long, b1: Long, c1: Long,
      e1: Long, juror: User, files: Seq[Long], newFile: Long) {
    def file(n: Int): Long = files(n - 1)
    def range(from: Int, to: Int): Seq[Long] = (from to to).map(file)
  }

  /** The fixture over `pageIds` (at least 22 existing images: the row's 20, E's new file and F's),
    * its contests named after `prefix`.
    */
  def create(pageIds: Seq[Long], prefix: String = "Repair", year: Int = 2018)(implicit session: DBSession = AutoSession): F = {
    require(pageIds.size >= 22, "22 images needed")
    val files = pageIds.take(20)
    val newFile = pageIds(20)
    val fFile = pageIds(21)
    val blank = sql"SELECT id FROM category WHERE title = ''".map(_.long(1)).single.apply()
      .getOrElse(sql"INSERT INTO category (title) VALUES ('')".updateAndReturnGeneratedKey.apply())
    def contest(letter: String, country: String, category: Option[Long], images: Option[String] = None) =
      ContestJuryJdbc.create(None, s"$prefix $letter", year, country, images = images, categoryId = category)
    val a = contest("A", "Armenia", Some(blank))
    val b = contest("B", "Ghana", Some(blank))
    val c = contest("C", "Nepal", Some(blank))
    val d = contest("D", "Armenia", Some(blank))
    val e = contest("E", "Ukraine", None)
    val eOwn = CategoryJdbc.findOrInsert(ImageService.ownCategory(e.getId))
    ContestJuryJdbc.setCategory(e.getId, eOwn)
    val titled = CategoryJdbc.findOrInsert(s"Category:$prefix shared list $year")
    val f = contest("F", "Ghana", Some(titled))
    val g = contest("G", "Ghana", None, images = Some(s"Category:$prefix shared list $year"))
    val members = files.map(blank -> _) ++ Seq(eOwn -> newFile, titled -> fFile)
    sql"INSERT IGNORE INTO category_members (category_id, page_id) VALUES (?, ?)"
      .batch(members.map { case (cat, p) => Seq(cat, p) }: _*).apply()

    val juror = User.create(User(s"$prefix juror", s"${prefix.toLowerCase.replace(' ', '-')}-juror-${System.nanoTime()}@example.org",
      None, Set(User.JURY_ROLE), None, None))
    def round(contest: ContestJury, number: Int, name: String, pages: Seq[Long]): Long = {
      val id = Round.create(Round(None, number, Some(name), contest.getId, active = false)).getId
      pages.foreach(p => VoteJdbc.create(p, 0, juror.getId, id))
      id
    }
    val a1 = round(a, 1, "First", files.take(5))
    val a2 = round(a, 2, "Second", files.take(2))
    val aWhole = round(a, 3, "Whole list", files)
    val b1 = round(b, 1, "First", files.slice(5, 10))
    val c1 = round(c, 1, "First", files.slice(10, 15))
    val e1 = round(e, 1, "First", files.slice(15, 17))
    round(f, 1, "First", Seq(fFile))
    F(blank, a, b, c, d, e, f, g, eOwn, titled, a1, a2, aWhole, b1, c1, e1, juror, files, newFile)
  }
}
