package gatling.setup

import com.github.tototoshi.csv.CSVReader
import db.scalikejdbc._
import org.intracer.wmua.{Image, Selection}
import org.scalawiki.wlx.dto.Monument
import scalikejdbc._

import java.io.File
import scala.util.Random

case class GatlingFixtureData(
    port: Int,
    contestId: Long,
    roundBinaryId: Long,
    roundRatingId: Long,
    jurors: Seq[(Long, String, String)],     // (id, email, plainPassword)
    organizer: (Long, String, String),        // (id, email, plainPassword)
    imagePageIds: Seq[Long],
    regions: Seq[String],
    votingPairs: Seq[(Long, Long, Long, Int)] // (juryId, pageId, roundId, rate)
)

object GatlingDbSetup {

  /** Reconstruct GatlingFixtureData from a restored DB without re-running the CSV load. */
  def loadFromDb(port: Int): GatlingFixtureData = {
    import scalikejdbc.AutoSession
    implicit val session: DBSession = AutoSession

    // Initialise the hasManyThrough association (required before any Round.findAllBy call)
    Round.usersRef

    val contest = ContestJuryJdbc.findAll()
      .find(_.name == "Gatling Perf Test Contest")
      .getOrElse(throw new RuntimeException("Gatling contest not found — is the dump stale?"))
    val contestId = contest.id.get

    val rounds = Round.findAllBy(sqls.eq(Round.defaultAlias.contestId, contestId))
    val binaryRound = rounds.find(_.name.exists(_.contains("Binary")))
      .getOrElse(throw new RuntimeException("Binary round not found"))
    val ratingRound = rounds.find(_.name.exists(_.contains("Rating")))
      .getOrElse(throw new RuntimeException("Rating round not found"))

    val allUsers = User.findAllBy(sqls.eq(User.defaultAlias.contestId, contestId))

    val jurors = allUsers
      .filter(_.roles.contains("jury"))
      .sortBy(_.email)
      .map { u =>
        val email = u.email
        val idx   = email.replaceAll("juror(\\d+)@.*", "$1")
        (u.id.get, email, s"pass$idx")
      }

    val orgUser = allUsers.find(_.roles.contains("admin"))
      .getOrElse(throw new RuntimeException("Organizer not found"))
    val organizer = (orgUser.id.get, orgUser.email, "orgpass")

    val imagePageIds = sql"SELECT page_id FROM images"
      .map(_.long("page_id")).list()

    val regions = sql"SELECT DISTINCT adm0 FROM monument WHERE adm0 IS NOT NULL"
      .map(_.string("adm0")).list()

    // Split 50k evenly across both rounds so both are represented in votingPairs
    val votingPairs =
      (sql"""SELECT jury_id, page_id, round_id, rate FROM selection
             WHERE round_id = ${binaryRound.id.get} LIMIT 25000"""
        .map(rs => (rs.long("jury_id"), rs.long("page_id"), rs.long("round_id"), rs.int("rate")))
        .list() ++
       sql"""SELECT jury_id, page_id, round_id, rate FROM selection
             WHERE round_id = ${ratingRound.id.get} LIMIT 25000"""
        .map(rs => (rs.long("jury_id"), rs.long("page_id"), rs.long("round_id"), rs.int("rate")))
        .list())

    GatlingFixtureData(port = port, contestId = contestId,
      roundBinaryId = binaryRound.id.get, roundRatingId = ratingRound.id.get,
      jurors = jurors, organizer = organizer,
      imagePageIds = imagePageIds, regions = regions, votingPairs = votingPairs)
  }

  def load(port: Int, cfg: GatlingConfig.type): GatlingFixtureData = {
    val numUsers = cfg.users
    val fraction = cfg.jurorFraction
    val maxRate  = cfg.maxRate
    val rng      = new Random(42)

    // ── Parse CSVs (outside transaction — pure CPU) ──────────────────────────
    val monumentRows = parseCsv("data/wlm-ua-monuments.csv")
    val monuments = monumentRows.map { row =>
      new Monument(
        id   = row("id"),
        name = row("name"),
        lat  = row.get("lat"),
        lon  = row.get("lon"),
        typ  = row.get("type"),
        year = row.get("year_of_construction"),
        city = row.get("municipality"),
        page = row("id")
      )
    }

    val imageRows = parseCsv("data/wlm-UA-images-2025.csv")
    val images = imageRows.flatMap { row =>
      row.get("page_id").filter(_.nonEmpty).flatMap { pid =>
        scala.util.Try(pid.toLong).toOption.map { pageId =>
          Image(
            pageId     = pageId,
            title      = row.getOrElse("title", ""),
            url        = row.get("url").filter(_.nonEmpty),
            pageUrl    = row.get("page_url").filter(_.nonEmpty),
            width      = row.get("width").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(0),
            height     = row.get("height").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(0),
            monumentId = row.get("monument_id").filter(_.nonEmpty),
            author     = row.get("author").filter(_.nonEmpty),
            size       = row.get("size_bytes").flatMap(s => scala.util.Try(s.toInt).toOption),
            mime       = row.get("mime").filter(_.nonEmpty)
          )
        }
      }
    }
    val imagePageIds = images.map(_.pageId)
    val imageMonumentMap: Map[Long, Option[String]] = images.map(img => img.pageId -> img.monumentId).toMap

    // ── Entity creation (auto-commits individually; small row counts) ─────────
    val contest   = ContestJuryJdbc.create(id = None, name = "Gatling Perf Test Contest", year = 2024, country = "ua",
                                           monumentIdTemplate = Some("{{UkrainianMonument}}"))
    val contestId = contest.id.get

    val jurors = (1 to numUsers).map { i =>
      val email    = s"juror$i@gatling.test"
      val password = s"pass$i"
      val user     = User.create(fullname = s"Juror $i", email = email,
                                 password = User.sha1(password), roles = Set("jury"),
                                 contestId = Some(contestId))
      (user.id.get, email, password)
    }

    val orgEmail    = "organizer@gatling.test"
    val orgPassword = "orgpass"
    val orgUser     = User.create(fullname = "Organizer", email = orgEmail,
                                  password = User.sha1(orgPassword), roles = Set("admin"),
                                  contestId = Some(contestId))
    val organizer   = (orgUser.id.get, orgEmail, orgPassword)

    val binaryRound = Round.create(Round(id = None, number = 1, name = Some("Binary Round"),
                                         contestId = contestId, rates = Round.binaryRound, active = true))
    val ratingRound = Round.create(Round(id = None, number = 2, name = Some("Rating Round"),
                                         contestId = contestId,
                                         rates = Round.rateRounds.find(_.id == maxRate).getOrElse(Round.rateRounds.last),
                                         active = true))

    val jurorUsers = jurors.map { case (id, _, _) =>
      RoundUser(roundId = binaryRound.id.get, userId = id, role = "jury", active = true)
    }
    binaryRound.addUsers(jurorUsers)
    ratingRound.addUsers(jurorUsers)

    // ── Bulk inserts — wrapped in a single transaction with constraint checks disabled ──
    // Only batchInsert calls participate; entity creation above uses autoSession.
    val jurorIds  = jurors.map(_._1)
    val numJurors = jurorIds.length

    val binarySelections = for {
      pageId <- imagePageIds
      chosen  = rng.shuffle(jurorIds.toVector).take(math.round(numJurors * fraction).toInt)
      juryId <- chosen
    } yield Selection(pageId = pageId, juryId = juryId, roundId = binaryRound.id.get,
                      rate = if (rng.nextBoolean()) 1 else -1,
                      monumentId = imageMonumentMap.getOrElse(pageId, None))

    val ratingSelections = for {
      pageId <- imagePageIds
      chosen  = rng.shuffle(jurorIds.toVector).take(math.round(numJurors * fraction).toInt)
      juryId <- chosen
    } yield Selection(pageId = pageId, juryId = juryId, roundId = ratingRound.id.get,
                      rate = rng.nextInt(maxRate) + 1,
                      monumentId = imageMonumentMap.getOrElse(pageId, None))

    DB.localTx { implicit session =>
      SQL("SET foreign_key_checks = 0").execute.apply()
      SQL("SET unique_checks = 0").execute.apply()

      monuments.grouped(1000).foreach(batch => MonumentJdbc.batchInsertFresh(batch.toSeq))
      images.grouped(1000).foreach(batch => ImageJdbc.batchInsert(batch.toSeq))
      (binarySelections ++ ratingSelections).grouped(5000).foreach(batch => SelectionJdbc.batchInsert(batch.toSeq))

      SQL("SET foreign_key_checks = 1").execute.apply()
      SQL("SET unique_checks = 1").execute.apply()
    }

    // Refresh optimizer statistics after bulk load so the first query uses correct plans.
    DB autoCommit { implicit session =>
      SQL("ANALYZE TABLE images, selection, monument, users, rounds, round_user").execute.apply()
    }

    val interleavedSelections = binarySelections.zip(ratingSelections).flatMap { case (b, r) => Seq(b, r) }
    val votingPairs = interleavedSelections.map(s => (s.juryId, s.pageId, s.roundId, s.rate)).take(50000)

    // ── Regions query — runs after transaction commits ───────────────────────
    val regions = DB readOnly { implicit session =>
      sql"SELECT DISTINCT adm0 FROM monument WHERE adm0 IS NOT NULL"
        .map(_.string("adm0")).list()
    }

    GatlingFixtureData(port = port, contestId = contestId,
      roundBinaryId = binaryRound.id.get, roundRatingId = ratingRound.id.get,
      jurors = jurors, organizer = organizer,
      imagePageIds = imagePageIds, regions = regions, votingPairs = votingPairs)
  }

  private def parseCsv(path: String): List[Map[String, String]] = {
    val reader = CSVReader.open(new File(path))
    try reader.allWithHeaders()
    finally reader.close()
  }
}
