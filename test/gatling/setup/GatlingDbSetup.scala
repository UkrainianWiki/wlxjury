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

  def load(port: Int, cfg: GatlingConfig.type): GatlingFixtureData = {
    val numUsers = cfg.users
    val fraction = cfg.jurorFraction
    val maxRate  = cfg.maxRate
    val rng      = new Random(42)

    // 1 ── Load monuments from CSV
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
    monuments.grouped(1000).foreach(batch => MonumentJdbc.batchInsert(batch.toSeq))

    // 2 ── Load images from CSV
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
    images.grouped(1000).foreach(batch => ImageJdbc.batchInsert(batch.toSeq))
    val imagePageIds = images.map(_.pageId)

    // 3 ── Contest
    val contest   = ContestJuryJdbc.create(id = None, name = "Gatling Perf Test Contest", year = 2024, country = "ua")
    val contestId = contest.id.get

    // 4 ── Jurors
    val jurors = (1 to numUsers).map { i =>
      val email    = s"juror$i@gatling.test"
      val password = s"pass$i"
      val user     = User.create(fullname = s"Juror $i", email = email,
                                 password = User.sha1(password), roles = Set("jury"),
                                 contestId = Some(contestId))
      (user.id.get, email, password)
    }

    // 5 ── Organizer
    val orgEmail    = "organizer@gatling.test"
    val orgPassword = "orgpass"
    val orgUser     = User.create(fullname = "Organizer", email = orgEmail,
                                  password = User.sha1(orgPassword), roles = Set("organizer"),
                                  contestId = Some(contestId))
    val organizer   = (orgUser.id.get, orgEmail, orgPassword)

    // 6 ── Rounds
    val binaryRound = Round.create(Round(id = None, number = 1, name = Some("Binary Round"),
                                         contestId = contestId, rates = Round.binaryRound, active = true))
    val ratingRound = Round.create(Round(id = None, number = 2, name = Some("Rating Round"),
                                         contestId = contestId,
                                         rates = Round.rateRounds.find(_.id == maxRate).getOrElse(Round.rateRounds.last),
                                         active = true))

    // 7 ── Add jurors to both rounds
    // Round.addUsers uses the *receiver* round's id — call on each round separately
    val jurorUsers = jurors.map { case (id, _, _) =>
      RoundUser(roundId = binaryRound.id.get, userId = id, role = "jury", active = true)
    }
    binaryRound.addUsers(jurorUsers)
    ratingRound.addUsers(jurorUsers)

    // 8 ── Generate selections
    val jurorIds  = jurors.map(_._1)
    val numJurors = jurorIds.length

    val binarySelections = for {
      pageId <- imagePageIds
      chosen  = rng.shuffle(jurorIds.toVector).take(math.round(numJurors * fraction).toInt)
      juryId <- chosen
    } yield Selection(pageId = pageId, juryId = juryId, roundId = binaryRound.id.get,
                      rate = if (rng.nextBoolean()) 1 else -1)

    val ratingSelections = for {
      pageId <- imagePageIds
      chosen  = rng.shuffle(jurorIds.toVector).take(math.round(numJurors * fraction).toInt)
      juryId <- chosen
    } yield Selection(pageId = pageId, juryId = juryId, roundId = ratingRound.id.get,
                      rate = rng.nextInt(maxRate) + 1)

    val allSelections = binarySelections ++ ratingSelections
    allSelections.grouped(5000).foreach(batch => SelectionJdbc.batchInsert(batch.toSeq))

    // Interleave binary and rating so votingPairs covers both rounds
    val interleavedSelections = binarySelections.zip(ratingSelections).flatMap { case (b, r) => Seq(b, r) }
    val votingPairs = interleavedSelections.map(s => (s.juryId, s.pageId, s.roundId, s.rate)).take(50000)

    // 9 ── Regions from monument adm0 (computed by MonumentJdbc.batchInsert)
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
