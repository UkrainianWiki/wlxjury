package controllers

import db.scalikejdbc.{PlayTestDb, Round, User}
import play.api.test.{FakeRequest, PlaySpecification}
import services.GalleryService
import services.imports.{ImageImportHandler, ImportParams, ImportState}
import services.jobs.JobScheduler

/** The log lines the support diagnostics plan's D-0b adds or changes (operations §1): key-value,
  * with the account and contest ids, untrusted text quoted ([[Secured.logValue]]), never an email.
  */
class SupportLogLinesSpec extends PlaySpecification with PlayTestDb {

  sequential

  "the import's summary (import_done, services.imports)" should {
    "be key-value with the contest, the counts and the source quoted" in {
      val params = ImportParams(466, ImageImportHandler.Mode.Import, "Images from \"WLM\" 2026\nin Nepal")
      val state = ImportState(seen = 12, inserted = 3, linked = 4, updated = 1, missing = 2, notInCountries = 5,
        byCountry = Some(true))
      ImageImportHandler.doneLine(57, params, state) ===
        "import_done job=57 contestId=466 mode=import source=\"Images from \\\"WLM\\\" 2026\\nin Nepal\" titles=0 " +
        "seen=12 inserted=3 linked=4 updated=1 monumentIdsChanged=0 missing=2 notInCountries=5 byCountry=true"
      ImageImportHandler.doneLine(58, params.copy(source = "Category:A", titleCount = 3), ImportState()) must
        endWith("source=\"Category:A\" titles=3 seen=0 inserted=0 linked=0 updated=0 monumentIdsChanged=0 " +
          "missing=0 notInCountries=0 byCountry=-")
    }
  }

  "the job lines (services.jobs)" should {
    "name the job, its kind and its contest, or - for a job of no contest" in {
      def job(contestId: Option[Long]) = services.jobs.Job(7, "commons_image_import", contestId, None, None, None,
        "done", play.api.libs.json.Json.obj(), play.api.libs.json.Json.obj(), None, None, None, None, 0, None, None,
        java.time.Instant.now(), None, None, None, None)
      JobScheduler.jobFields(job(Some(466))) === "job=7 kind=commons_image_import contestId=466"
      JobScheduler.jobFields(job(None)) === "job=7 kind=commons_image_import contestId=-"
    }
  }

  "a juror's refused round (round_access_refused, round.access)" should {
    "carry the reason, the round, the account and the contest, ids only" in {
      val round = Round(id = Some(1974), number = 1, contestId = 466)
      val juror = User("Juror", "juror@example.org", id = Some(812), roles = Set("jury"), contestId = Some(466))
      GalleryService.RoundState.All.map(s => GalleryService.accessRefusedLine(s, round, juror)) === Seq(
        "round_access_refused reason=round_stopped roundId=1974 accountId=812 contestId=466",
        "round_access_refused reason=round_preparing roundId=1974 accountId=812 contestId=466",
        "round_access_refused reason=round_closing roundId=1974 accountId=812 contestId=466",
        "round_access_refused reason=round_distributing roundId=1974 accountId=812 contestId=466",
        "round_access_refused reason=juror_stopped roundId=1974 accountId=812 contestId=466")
    }
  }

  "the account fields of server_error and csrf_failure" should {
    "take the signed session's ids, digits only, when the database can't tell the user" in {
      val request = FakeRequest(GET, "/x").withSession(Secured.AccountId -> "812", Secured.CurrentContestId -> "466")
      Secured.accountFields(request, None) === "accountId=812 contestId=466 accountFrom=session"
      Secured.accountFields(FakeRequest(GET, "/x").withSession(Secured.AccountId -> "8 1\n2"), None) ===
        "accountId=- contestId=- accountFrom=none"
      // a user the database told: their own contest, never the session's
      val user = User("A", "a@example.org", id = Some(5), roles = Set("jury"), contestId = None)
      Secured.accountFields(request, Some(user)) === "accountId=5 contestId=- accountFrom=user"
    }
  }
}
