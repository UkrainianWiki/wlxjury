package db.scalikejdbc

import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.jobs.JobJdbc

import java.time.{Instant, LocalDateTime, ZoneId, ZoneOffset}

/** The database's times read as the instants stored whatever the JVM's and the database
  * session's time zones ([[DbTime]]): a JDBC timestamp read is the session's wall time taken in
  * the JVM's zone, wrong by their difference (VoteVotedAtSpec covers `vote.voted_at`).
  *
  * Each example writes in one session zone and reads in another, both unlike the JVM's, on one
  * pooled connection whose zone is set back afterwards. Service-level (finders on their own
  * sessions elsewhere): the tables are truncated before each example (CLAUDE.md).
  */
class DbTimeZoneSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** Two session zones, both unlike the JVM's offset now, and unlike each other. */
  private val (writeZone, readZone) = {
    val jvm = ZoneId.systemDefault().getRules.getOffset(Instant.now()).getId.replace("Z", "+00:00")
    val Seq(w, r) = Seq("+05:00", "-03:00", "+09:00", "-07:00").filterNot(_ == jvm).take(2)
    (w, r)
  }

  /** `body` on one connection in the session time zone `zone`, set back after it (pooled). */
  private def inZone[A](zone: String)(body: DBSession => A): A =
    DB.localTx { implicit s =>
      SQL(s"SET time_zone = '$zone'").execute()
      try body(s)
      finally sql"SET time_zone = @@global.time_zone".execute()
    }

  /** The wall time, written as a literal in [[writeZone]], and the instant it is there. */
  private val wall = "2026-11-12 19:05:00.123"
  private val later = "2026-11-13 19:05:00.456"
  private def at(wallTime: String): Instant =
    LocalDateTime.parse(wallTime.replace(' ', 'T')).atOffset(ZoneOffset.of(writeZone)).toInstant

  private def dbNow(implicit s: DBSession): Instant =
    sql"SELECT UNIX_TIMESTAMP(NOW(3))".map(rs => DbTime.fromEpochSeconds(rs.bigDecimal(1))).single().get

  private case class Fixture(contest: ContestJury, user: User, round: Round)

  private def fixture(): Fixture = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val user = userDao.create(User("Admin", "admin@example.org", None, Set("admin"), contestId = contest.id))
    val round = roundDao.create(Round(None, 1, contestId = contest.getId, active = true))
    imageDao.batchInsert(Seq(Image(1L, "File:Image1.jpg", None, None, 640, 480, Some("07-101-0001"))))
    Fixture(contest, user, round)
  }

  "a job's times" should {
    "read as the instants stored" in {
      val id = inZone(writeZone) { implicit s =>
        sql"""INSERT INTO job (kind, status, params, state, created_at, started_at, updated_at, finished_at, heartbeat_at)
              VALUES ('test', 'done', '{}', '{}', $wall, $wall, $later, $later, $later)""".updateAndReturnGeneratedKey()
      }
      val job = inZone(readZone)(implicit s => JobJdbc.findById(id).get)
      (job.createdAt === at(wall)) and (job.startedAt === Some(at(wall))) and (job.updatedAt === Some(at(later))) and
        (job.finishedAt === Some(at(later))) and (job.heartbeatAt === Some(at(later)))
    }
  }

  "an account link's times" should {
    "read as the instants stored" in {
      val f = fixture()
      val id = inZone(writeZone) { implicit s =>
        sql"""INSERT INTO account_token (user_id, purpose, token_hash, email, created_at, expires_at, used_at, revoked_at)
              VALUES (${f.user.getId}, 'invitation', ${"a" * 64}, 'admin@example.org', $wall, $later, $wall, $later)"""
          .updateAndReturnGeneratedKey()
      }
      val token = inZone(readZone)(implicit s => AccountTokenJdbc.find(id).get)
      (token.createdAt === at(wall)) and (token.expiresAt === at(later)) and (token.usedAt === Some(at(wall))) and
        (token.revokedAt === Some(at(later)))
    }

    "expire, as read and shown, when the database finds it expired: a 7-day shared invitation" in {
      // the docs-shots fixture showed a 7-day invitation's expiry 2 h early ("04:31 UTC" at 06:29
      // UTC): the database clock's NOW(3) + INTERVAL read as a JDBC timestamp in another zone
      val f = fixture()
      val week = 7L * 24 * 3600
      val (token, now, utcByDb) = inZone(readZone) { implicit s =>
        val id = AccountTokenJdbc.create(f.user.getId, AccountTokenJdbc.Invitation, "b" * 64, "admin@example.org",
          f.contest.id, Some("jury"), Some(f.user.getId), validForSeconds = week, shared = true)
        // the stored expiry in UTC to the minute, by the database alone
        val utc = sql"""SELECT DATE_FORMAT(CONVERT_TZ(expires_at, @@session.time_zone, '+00:00'), '%Y-%m-%d %H:%i')
                        FROM account_token WHERE id = $id""".map(_.string(1)).single().get
        (AccountTokenJdbc.find(id).get, dbNow, utc)
      }
      def within(t: Instant, from: Instant, to: Instant) = !t.isBefore(from) && !t.isAfter(to)
      (token.live must beTrue) and (token.shared must beTrue) and
        (within(token.expiresAt, now.plusSeconds(week - 60), now.plusSeconds(week)) must beTrue) and
        (within(token.createdAt, now.minusSeconds(60), now) must beTrue) and
        // as the share-link and invitation pages show it (shareLink, linkStatus, editUser)
        (views.LinkTimes.utc(token.expiresAt) === utcByDb + " UTC") and
        (token.status === AccountToken.Active(token.expiresAt)) and
        (views.LinkTimes.utc(token.status.at) === utcByDb + " UTC")
    }
  }

  "an API token's times" should {
    "read as the instants stored" in {
      val f = fixture()
      val id = inZone(writeZone) { implicit s =>
        sql"""INSERT INTO api_token (user_id, name, token_hash, prefix, created_at, expires_at, last_used_at, revoked_at)
              VALUES (${f.user.getId}, 'cli', ${"c" * 64}, 'wlx_c', $wall, $later, $wall, $later)""".updateAndReturnGeneratedKey()
      }
      val token = inZone(readZone)(implicit s => ApiTokenJdbc.find(id).get)
      (token.createdAt === at(wall)) and (token.expiresAt === Some(at(later))) and
        (token.lastUsedAt === Some(at(wall))) and (token.revokedAt === Some(at(later)))
    }
  }

  "an account change's time" should {
    "read as the instant stored" in {
      val f = fixture()
      inZone(writeZone) { implicit s =>
        sql"""INSERT INTO account_change (user_id, actor_id, field, created_at)
              VALUES (${f.user.getId}, ${f.user.getId}, 'name', $wall)""".update()
      }
      inZone(readZone)(implicit s => AccountChangeJdbc.findByUser(f.user.getId, None, 10)).map(_.createdAt) ===
        Seq(at(wall))
    }
  }

  "a round's disqualification and its event" should {
    "read as the instants stored" in {
      val f = fixture()
      val roundId = f.round.getId
      inZone(writeZone) { implicit s =>
        sql"""INSERT INTO round_image (round_id, page_id, status, dq_code, dq_at) VALUES ($roundId, 1, 'dq', 'coi', $wall)
              ON DUPLICATE KEY UPDATE status = 'dq', dq_code = 'coi', dq_at = $wall""".update()
        sql"""INSERT INTO round_image_event (round_id, page_id, action, code, prior_status, new_status, created_at)
              VALUES ($roundId, 1, 'disqualify', 'coi', 'in', 'dq', $later)""".update()
      }
      val (dq, events, ofFile) = inZone(readZone) { implicit s =>
        (RoundImageJdbc.disqualified(roundId), RoundImageEventJdbc.byRound(roundId), RoundImageEventJdbc.ofFile(roundId, 1L))
      }
      (dq.flatMap(_.at).map(_.toInstant) === Seq(at(wall))) and
        (events.map(_.at.toInstant) === Seq(at(later))) and (ofFile.map(_.at.toInstant) === Seq(at(later))) and
        // shown in the JVM's zone, as a JDBC read gave it
        (dq.flatMap(_.at).map(_.getZone) === Seq(ZoneId.systemDefault()))
    }
  }

  "a file deleted on Commons" should {
    "have the time of its deletion as stored" in {
      val f = fixture()
      val roundId = f.round.getId
      inZone(writeZone) { implicit s =>
        sql"UPDATE images SET deleted_at = $wall WHERE page_id = 1".update()
        sql"INSERT IGNORE INTO round_image (round_id, page_id) VALUES ($roundId, 1)".update()
      }
      inZone(readZone)(implicit s => ImageJdbc.roundDeletedFiles(roundId, 10, 0)).flatMap(_.deletedAt).map(_.toInstant) ===
        Seq(at(wall))
    }
  }
}
