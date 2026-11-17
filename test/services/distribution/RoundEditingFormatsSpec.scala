package services.distribution

import org.specs2.mutable.Specification
import play.api.i18n.Messages.{parse, UrlMessageSource}
import play.api.i18n.{DefaultMessagesApi, Lang, MessagesImpl}
import play.api.libs.json._
import services.jobs.JobError

import java.nio.file.Paths

/** The formats round editing step 3 relies on, without a database: a distribution job's state as
  * stored before and after [[Passes]] (a job in flight across the deploy), the job errors' plural
  * arguments ([[JobError.message]]), and the new and changed plural messages in every language, for
  * one and for several (docs/plans/2026-11-16-round-editing.md §5, §7).
  */
class RoundEditingFormatsSpec extends Specification {

  // ── the distribution state's JSON ─────────────────────────────────────────

  "a distribution job's state" should {

    "read the old top-level rescanned into passes.rescanned" in {
      val old = Json.obj("phase" -> "assign", "cursor" -> 12, "rescanned" -> true)
      val st = old.as[DistributionState]
      (st.passes === Passes(rescanned = true)) and (st.rescanned must beTrue) and (st.cursor === 12L)
    }

    "read the old top-level rescanned: false as the default passes" in {
      Json.obj("phase" -> "assign", "rescanned" -> false).as[DistributionState].passes === Passes()
    }

    "give a state with neither field the default passes" in {
      val st = Json.obj("phase" -> "complete").as[DistributionState]
      (st.passes === Passes()) and (st.rescanned must beFalse)
    }

    "keep passes when both are there (passes wins), and write no top-level rescanned on a round trip" in {
      val both = Json.obj("phase" -> "topup", "rescanned" -> false,
        "passes" -> Json.obj("rescanned" -> true, "topUpRescanned" -> true, "toppedUp" -> 3, "topUpRows" -> 4))
      val st = both.as[DistributionState]
      val written = Json.toJsObject(st)
      (st.passes === Passes(rescanned = true, topUpRescanned = true, toppedUp = 3L, topUpRows = 4L)) and
        (written.keys must not(contain("rescanned"))) and
        ((written \ "passes" \ "rescanned").as[Boolean] must beTrue) and
        (written.as[DistributionState] === st) and
        (Json.toJsObject(DistributionState(phase = "assign")).keys must not(contain("rescanned")))
    }
  }

  // ── messages ──────────────────────────────────────────────────────────────

  private val langs = Seq("en", "fr", "uk", "ru")

  private lazy val api: DefaultMessagesApi = new DefaultMessagesApi(langs.map { l =>
    l -> (parse(UrlMessageSource(Paths.get(s"conf/messages.$l").toUri.toURL), s"messages.$l") match {
      case Right(m)    => m
      case Left(error) => throw error
    })
  }.toMap)

  private def messages(lang: String) = MessagesImpl(Lang(lang), api)

  "a job error's message" should {

    "pass the arguments its pattern formats as plurals as numbers, and keep the others text" in {
      implicit val en: MessagesImpl = messages("en")
      val one = JobError(RoundRebalanceHandler.FewReceiversKey, Seq("12345", "1")).message
      val two = JobError(RoundRebalanceHandler.FewReceiversKey, Seq("3", "2")).message
      val invariants = JobError(RoundRebalanceHandler.InvariantsKey, Seq("3", "1", "123456")).message
      (one must contain("Round 12345 gives each file to 1 juror:")) and (one must contain("at least 1 juror to")) and
        (two must contain("to 2 jurors:")) and
        (invariants must contain("1 file is out of step")) and (invariants must contain("file 123456 first")) and
        (JobError.numericArgs(RoundRebalanceHandler.FewReceiversKey) === Set(1)) and
        (JobError.numericArgs(RoundRebalanceHandler.VerifyKey) must beEmpty)
    }

    "format in every language, a missing key as before" in {
      foreach(langs) { l =>
        implicit val m: MessagesImpl = messages(l)
        (JobError(RoundRebalanceHandler.FewReceiversKey, Seq("3", "1")).message must not(beEmpty)) and
          (JobError("job.error.no.such.key", Seq("1")).message === "job.error.no.such.key")
      }
    }
  }

  "the new and changed plural messages" should {

    /** Each key with its arguments, for one and for several. */
    val cases: Seq[(String, Seq[Any], Seq[Any])] = Seq(
      ("round.juror.reassign.result", Seq(1L, 1, "A +1"), Seq(340L, 4, "A +85")),
      ("round.waiting", Seq(1L, 1), Seq(12L, 3)),
      ("round.waiting.topup", Seq(1L), Seq(12L)),
      ("coi.count", Seq(1), Seq(7)),
      ("coi.count.round", Seq(1, 1), Seq(3, 7)),
      ("round.juror.reassign.done.all", Seq("A", 1, 1L), Seq("A", 1, 22577L)),
      ("round.juror.reassign.cancelled.partway", Seq(1L, "17"), Seq(1500L, "17")),
      ("round.juror.reassign.cancelled.partway", Seq(0L, "17"), Seq(2L, "17")),
      ("round.juror.reassign.cancelled.removed", Seq(1L, "17"), Seq(22577L, "17")),
      ("round.juror.add.split.waiting", Seq("Y", 1L), Seq("Y", 12L)),
      ("job.error.rebalance.invariants.count", Seq("3", 1L), Seq("3", 4L)),
      ("authors.normalize.uploader", Seq(1L), Seq(3L)),
      ("authors.normalize.restored", Seq(1L), Seq(3L)),
      ("authors.normalize.not.restored", Seq(1L), Seq(3L)),
      ("authors.normalize.unchanged", Seq(1L), Seq(3L)),
      ("round.juror.add.split", Seq("Y", 1L, 1L, 0L), Seq("Y", 81L, 12L, 69L)),
      ("round.juror.add.waiting.left", Seq(1L), Seq(5L))
    )

    "format for one and for several in every language, with the count in it" in {
      foreach(langs) { l =>
        val m = messages(l)
        foreach(cases) { case (key, one, many) =>
          val a = m(key, one: _*)
          val b = m(key, many: _*)
          (a aka s"$l $key (one)" must not(beEmpty)) and (a must not(contain("{"))) and (b must not(contain("{"))) and
            (a aka s"$l $key" must not(be_==(b)))
        }
      }
    }

    "keep their apostrophes for several (a quote in a choice segment holding an argument is lost)" in {
      val en = messages("en")
      val fr = messages("fr")
      (en("coi.count", 3) must contain("contest's files are")) and (en("coi.count.round", 3, 7) must contain("round's files are")) and
        (en("round.juror.reassign.cancelled.partway", 3L, "1") must contain("juror's unrated files had")) and
        (en("round.juror.reassign.cancelled.removed", 3L, "1") must contain("juror's unrated files were")) and
        (fr("coi.count", 3) must contain("conflits d'intérêts")) and (fr("coi.count.round", 3, 7) must contain("conflits d'intérêts")) and
        (fr("authors.normalize.uploader", 3L) must contain("téléverseur d'origine")) and
        (fr("authors.normalize.restored", 3L) must contain("points d'interrogation")) and
        (fr("authors.normalize.not.restored", 3L) must contain("points d'interrogation")) and
        (fr("authors.normalize.unchanged", 3L) must contain("qu'un import les enregistre")) and
        (fr("authors.normalize.unchanged", 1L) must contain("qu'un import l'enregistre"))
    }

    "read right in French for 0, 1 and several (the changed keys)" in {
      val fr = messages("fr")
      (fr("round.juror.add.split.waiting", "Y", 1L) must startWith("Y reçoit le fichier qui attend un juré et qu'il peut prendre")) and
        (fr("round.juror.add.split.waiting", "Y", 3L) must startWith("Y reçoit les 3 fichiers qui attendent un juré et qu'il peut")) and
        (fr("round.juror.reassign.cancelled.partway", 0L, "1") must startWith("Réattribution annulée après qu'aucun des fichiers")) and
        (fr("round.juror.reassign.cancelled.partway", 1L, "1") must startWith("Réattribution annulée après que 1 fichier non noté")) and
        (fr("round.juror.reassign.cancelled.partway", 3L, "1") must startWith("Réattribution annulée après que 3 fichiers non notés")) and
        (fr("round.juror.reassign.cancelled.removed", 0L, "1") must startWith("Réattribution annulée après qu'aucun des fichiers")) and
        (fr("round.juror.reassign.cancelled.removed", 1L, "1") must contain("que 1 fichier non noté du juré lui a été retiré")) and
        (fr("round.juror.reassign.cancelled.removed", 3L, "1") must contain("que 3 fichiers non notés du juré lui ont été retirés")) and
        (fr("job.error.rebalance.invariants.count", "3", 1L) must contain("1 fichier de plus n'est plus")) and
        (fr("job.error.rebalance.invariants.count", "3", 4L) must contain("4 fichiers de plus ne sont plus")) and
        (fr("job.error.rebalance.invariants", "3", 1L, "9") must contain("1 fichier n'est plus")) and
        (fr("job.error.rebalance.invariants", "3", 4L, "9") must contain("4 fichiers ne sont plus")) and
        (fr("round.juror.preview.stale", "Voir ce que cela fait") === "Les cases ont changé : cliquez d'abord sur « Voir ce que cela fait ».") and
        (fr("authors.normalize.restored", 1L) === "dont 1 rétabli depuis Commons (compte enregistré en points d'interrogation)")
    }

    "say 1 file, not 1 files, in English" in {
      val en = messages("en")
      (en("round.waiting", 1L, 2) must startWith("1 file is waiting")) and
        (en("round.waiting", 3L, 2) must startWith("3 files are waiting")) and
        (en("round.juror.reassign.result", 1L, 1, "A +1") === "1 unrated file → 1 juror: A +1.") and
        (en("coi.count", 1) must startWith("1 of the contest's files is a conflict")) and
        (en("coi.count", 3) must startWith("3 of the contest's files are conflicts")) and
        (en("round.juror.reassign.cancelled.partway", 2L, "1") must contain("after 2 of the juror's unrated files had")) and
        (en("round.waiting.topup", 1L) === "Also give out the 1 file waiting for a juror")
    }
  }

  // ── a rebalance's state written by an earlier build ────────────────────────

  "a rebalance job's state" should {

    "read with an earlier build's baseline (a list of strings) as no baseline, the rest kept" in {
      val old = Json.obj("violationsBefore" -> Json.arr("1:5", "3:5"), "waitingPages" -> Json.arr(7, 8))
      val add = Json.obj("phase" -> "move", "filesGiven" -> 5, "frozen" -> Json.arr(3), "checks" -> old)
      val reassign = Json.obj("phase" -> "aside", "stopped" -> true, "frozen" -> Json.arr(3), "checks" -> old)
      (old.as[VerifyChecks] === VerifyChecks(None, Seq(7L, 8L))) and
        (add.validate[RebalanceState].asOpt.map(s => (s.phase, s.filesGiven, s.frozen)) must beSome(("move", 5L, Seq(3L)))) and
        (reassign.validate[ReassignState].asOpt.map(s => (s.phase, s.stopped, s.frozen)) must beSome(("aside", true, Seq(3L)))) and
        (Json.obj("waitingPages" -> "x").as[VerifyChecks] === VerifyChecks()) and
        (Json.toJsObject(VerifyChecks(Some(Map("1" -> ViolationBaseline(1L, Seq(5L)))), Seq(7L))).as[VerifyChecks] ===
          VerifyChecks(Some(Map("1" -> ViolationBaseline(1L, Seq(5L)))), Seq(7L)))
    }
  }

  // ── the verify step's bounded baseline ────────────────────────────────────

  "an invariant's baseline" should {

    "name every new page while it lists them all, and only an excess once truncated" in {
      val complete = ViolationBaseline(2L, Seq(5L, 9L))
      val truncated = ViolationBaseline(60L, (1L to 50L))
      (complete.fresh(Seq(5L, 9L)) === (0L -> Nil)) and (complete.fresh(Seq(5L)) === (0L -> Nil)) and
        (complete.fresh(Seq(3L, 5L, 9L)) === (1L -> Seq(3L))) and
        (truncated.fresh((1L to 60L)) === (0L -> Nil)) and
        // truncated: how many more, no page named (which unlisted one is new is unknown)
        (truncated.fresh((1L to 62L)) === (2L -> Nil)) and
        (ViolationBaseline(0L, Nil).fresh(Seq(7L)) === (1L -> Seq(7L)))
    }
  }

  // ── the add's split ───────────────────────────────────────────────────────

  "an add's share with files waiting for a juror" should {

    "take the waiting files first within the share, the rest from the donors, levelled from the top" in {
      val donors = Seq(1L -> 7L, 2L -> 10L, 3L -> 10L, 4L -> 10L)
      val share = RoundRebalanceHandler.quotas(0, donors).values.sum
      val fewer = RoundRebalanceHandler.addSplit(0, donors, 3)
      val equal = RoundRebalanceHandler.addSplit(0, donors, share)
      val more = RoundRebalanceHandler.addSplit(0, donors, share + 5)
      val none = RoundRebalanceHandler.addSplit(0, donors, 0)
      (share === 7L) and
        (fewer.waiting === 3L) and (fewer.fromDonors === Map(2L -> 1L, 3L -> 1L, 4L -> 2L)) and (fewer.total === share) and
        (equal.waiting === share) and (equal.fromDonors must beEmpty) and (equal.waitingLeft === 0L) and
        (more.waiting === share) and (more.fromDonors must beEmpty) and (more.waitingLeft === 5L) and
        (none === RoundRebalanceHandler.AddSplit(0L, RoundRebalanceHandler.quotas(0, donors), 0L, 7L))
    }

    "with a share of 0, take every waiting file the juror may take, nothing from the donors (decision of 2026-11-17)" in {
      val donors = Seq(1L -> 40L, 2L -> 30L)
      val waiting = RoundRebalanceHandler.addSplit(50, donors, 12)
      val none = RoundRebalanceHandler.addSplit(50, donors, 0)
      (waiting === RoundRebalanceHandler.AddSplit(12L, Map.empty, 12L, 0L)) and (waiting.total === 12L) and
        (waiting.waitingLeft === 0L) and (none === RoundRebalanceHandler.AddSplit(0L, Map.empty, 0L, 0L)) and
        (none.total === 0L)
    }

    "take from the most loaded first, summing exactly, none negative" in {
      val donors = Seq(1L -> 40L, 2L -> 30L, 3L -> 5L)
      val all = (0L to 75L).map(t => t -> RoundRebalanceHandler.fromTop(t, donors))
      (all.forall { case (t, q) => q.values.sum == t && q.values.forall(_ > 0) && q.forall { case (d, n) => n <= donors.toMap.apply(d) } }
        must beTrue) and
        (RoundRebalanceHandler.fromTop(10, donors) === Map(1L -> 10L)) and
        (RoundRebalanceHandler.fromTop(20, donors) === Map(1L -> 15L, 2L -> 5L)) and
        (RoundRebalanceHandler.fromTop(100, donors).values.sum === 75L) and
        (RoundRebalanceHandler.fromTop(5, Nil) must beEmpty)
    }
  }
}
