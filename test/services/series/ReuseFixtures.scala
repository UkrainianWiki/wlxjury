package services.series

import db.scalikejdbc.{Round, User}
import org.intracer.wmua.ContestJury
import scalikejdbc._
import services.diagnostics.HealthFixtures._

/** Committed rows shaped like contest 466 (contest series plan §4.7) for the reuse report's and the
  * split's specs: run after `SharedTestDb.truncateAll()`.
  */
object ReuseFixtures {

  def wle(year: Int) = s"Category:Images from Wiki Loves Earth $year in "
  def account(name: String): User = User.create(User(name, s"${name.replace(' ', '.')}${System.nanoTime()}@example.org"))

  /** Contest 466 in small (§4.7): WLE 2020 Nepal holding its own 2020 rounds, WLE Nepal 2021 (R1),
    * Wiki Loves Fashion 2022 in the Philippines (R2), WLE 2023 Philippines rounds with no files
    * (R4, empty), WLE 2026 Bhutan, active (R4), a round filtered by "2023 in Philippines" whose files
    * are Nepal's 2023 (signals disagree), and WLE 2024 Nepal with a fifth of Rwanda's files (R3).
    */
  final case class Nepal466(contest: ContestJury, own: Seq[Round], r2021: Seq[Round], fashion: Round, philippines: Round,
      bhutan: Round, disagreeing: Round, mixed: Round, juror: User, deleted: User, admin: User)

  def nepal466(): Nepal466 = {
    val all = (1L to 20L) ++ (101L to 120L) ++ (201L to 210L) ++ (301L to 310L) ++ (401L to 420L) ++ (501L to 525L)
    val c = contestWithFiles("Wiki Loves Earth", 2020, "Nepal", Some(wle(2020) + "Nepal"), all)
    inCountries(wle(2020), "Nepal" -> (1L to 20L))
    inCountries(wle(2021), "Nepal" -> (101L to 120L))
    inCountries("Category:Images from Wiki Loves Fashion 2022 in ", "the Philippines" -> (201L to 210L))
    inCountries(wle(2026), "Bhutan" -> (301L to 310L))
    inCountries(wle(2023), "Nepal" -> (401L to 420L))
    inCountries(wle(2024), "Nepal" -> (501L to 520L), "Rwanda" -> (521L to 525L))
    val juror = account("Nepal Juror")
    val deleted = account("Gone Juror")
    val admin = account("Nepal Admin")
    db.scalikejdbc.UserContestJdbc.createMembership(juror.getId, c.getId, User.JURY_ROLE)
    db.scalikejdbc.UserContestJdbc.createMembership(admin.getId, c.getId, User.ADMIN_ROLE)
    DB.autoCommit(implicit s => sql"UPDATE users SET deleted_at = NOW() WHERE id = ${deleted.getId}".update.apply())
    val p = Round.Status.Paused
    val o1 = round(c, 1, "Round 1 WLE 2020", status = p, files = 1L to 20L, jurors = Seq(juror))
    val o2 = round(c, 2, "Final 2020", status = p, previous = Seq(o1.getId), files = 1L to 5L, jurors = Seq(juror))
    val y1 = round(c, 3, "First Round 2021", status = p, category = Some(wle(2021) + "Nepal"), files = 101L to 120L,
      jurors = Seq(juror))
    val y2 = round(c, 4, "Second round 2021", status = p, previous = Seq(y1.getId), files = 101L to 104L, jurors = Seq(juror))
    val fashion = round(c, 5, "Wiki Loves Fashion 2022 (1st round)", status = p, files = 201L to 210L)
    val philippines = round(c, 6, "WLE Philippines First round", status = p, category = Some(wle(2023) + "the Philippines"))
    val disagreeing = round(c, 7, "WLE NP Test", status = p, category = Some(wle(2023) + "Philippines"), files = 401L to 420L)
    val mixed = round(c, 8, "WLE 2024 Filter", status = p, category = Some(wle(2024) + "Nepal"), files = 501L to 525L)
    val bhutan = round(c, 9, "WLE Bhutan 2026", category = Some(wle(2026) + "Bhutan"), files = 301L to 310L,
      jurors = Seq(deleted))
    DB.autoCommit(implicit s => sql"UPDATE round SET status_changed_by = ${admin.getId} WHERE id = ${bhutan.getId}".update.apply())
    Nepal466(c, Seq(o1, o2), Seq(y1, y2), fashion, philippines, bhutan, disagreeing, mixed, juror, deleted, admin)
  }

}
