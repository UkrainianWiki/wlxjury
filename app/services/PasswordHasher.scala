package services

import at.favre.lib.crypto.bcrypt.{BCrypt, LongPasswordStrategies}
import com.typesafe.config.{Config, ConfigFactory}
import play.api.Logger
import play.api.data.validation.{Constraint, Invalid, Valid, ValidationError, ValidationResult}

import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.security.{MessageDigest, SecureRandom}
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Semaphore, TimeUnit}
import scala.concurrent.duration._

/** Password hashes: bcrypt (`$2b$`, the cost from [[PasswordHasher.Settings]]), stored in
  * `users.password`.
  *
  *  - A password is trimmed when it is set and when it is checked ([[PasswordHasher.normalize]]).
  *  - A bcrypt hash (`$2a$`/`$2b$`) is checked with bcrypt, at the cost it was made with; one
  *    made at a lower cost than the configured one is rehashed after a successful check.
  *  - A legacy hash (40 lowercase hex characters: the unsalted SHA-1 of the password, which
  *    the app stored before) is checked once, and rehashed with bcrypt after a successful
  *    check ([[PasswordHasher.Accepted.rehash]]); it is never written for a new password.
  *    After the grace period (P8, V81) a legacy hash no longer signs in and is replaced by
  *    [[PasswordHasher.RetiredLegacy]] ([[db.scalikejdbc.LegacyPasswordRetirementJdbc]]).
  *  - Anything else never matches.
  *  - A new password is at most 72 UTF-8 bytes, bcrypt's limit ([[PasswordHasher.newPassword]]).
  *    A longer one (a legacy password only) is pre-hashed with SHA-512 before bcrypt, the
  *    same way when it is hashed and when it is checked, so such a user still signs in and
  *    is rehashed.
  *  - At most [[PasswordHasher.Settings.maxConcurrent]] bcrypt computations run at once; the
  *    others wait until a deadline, then fail with [[PasswordHasher.Busy]].
  *
  * Neither a password nor a hash is ever logged.
  */
class PasswordHasher(val settings: PasswordHasher.Settings) {
  import PasswordHasher._

  private val permits = new Semaphore(settings.maxConcurrent, true)
  private val inFlight = new AtomicInteger
  private val random = new SecureRandom()
  private val longPasswords = LongPasswordStrategies.hashSha512(BCrypt.Version.VERSION_2B)
  private val hasher = BCrypt.`with`(BCrypt.Version.VERSION_2B, random, longPasswords)
  // the version (2a or 2b) and the cost come from the stored hash
  private val verifier = BCrypt.verifyer(null, longPasswords)

  /** A hash of a password nobody has, checked when there is no hash to check against (an
    * unknown login, a missing or unusable stored value, a wrong legacy password), so that
    * a sign-in takes about as long whatever the account.
    */
  private lazy val dummyHash: Array[Byte] = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    hasher.hash(settings.cost, bytes)
  }

  /** When a sign-in starting now stops waiting for a turn to check its password. */
  def deadline(): Long = System.nanoTime() + settings.maxWait.toNanos

  /** The bcrypt hash of the (trimmed) password, to store. Waits for a turn at most
    * [[Settings.maxWait]], then fails with [[Busy]].
    */
  def hash(password: String, deadline: Long = this.deadline()): String =
    withBytes(password) { bytes =>
      // never stored: a blank password never matches ([[check]]); forms refuse it first
      require(bytes.nonEmpty, "a blank password")
      withPermit(deadline)(bcrypt(bytes))
    }

  /** Whether `password` matches the stored value; see the class comment. */
  def check(stored: Option[String], password: String, deadline: Long = this.deadline()): Verdict =
    withBytes(password) { bytes =>
      stored match {
        case _ if bytes.isEmpty =>
          withPermit(deadline)(dummy(bytes))
        case Some(hash @ BcryptHash(cost)) if cost.toInt >= BCrypt.MIN_COST && cost.toInt <= MaxStoredCost =>
          withPermit(deadline) {
            if (!verifier.verify(bytes, hash.getBytes(US_ASCII)).verified) Rejected
            else if (cost.toInt < settings.cost) Accepted(Some(bcrypt(bytes)))
            else Accepted(None)
          }
        case Some(hash @ LegacyHash()) =>
          val matches = MessageDigest.isEqual(sha1Hex(bytes).getBytes(US_ASCII), hash.getBytes(US_ASCII))
          withPermit(deadline) {
            if (matches) Accepted(Some(bcrypt(bytes))) else dummy(bytes)
          }
        case _ =>
          withPermit(deadline)(dummy(bytes))
      }
    }

  /** Whether one more sign-in or sign-up may start ([[Settings.maxQueued]] at most in
    * flight); each admitted one calls [[leave]] when done.
    */
  def admit(): Boolean =
    if (inFlight.incrementAndGet() <= settings.maxQueued) true
    else {
      inFlight.decrementAndGet()
      false
    }

  def leave(): Unit = inFlight.decrementAndGet()

  /** The number of bcrypt computations that may start now (for tests). */
  def availablePermits: Int = permits.availablePermits()

  private def bcrypt(bytes: Array[Byte]): String =
    new String(hasher.hash(settings.cost, bytes), US_ASCII)

  private def dummy(bytes: Array[Byte]): Verdict = {
    verifier.verify(bytes, dummyHash)
    Rejected
  }

  /** `f` in one of the [[Settings.maxConcurrent]] turns, waiting for one until `deadline`
    * (`System.nanoTime`), else [[Busy]].
    */
  def withPermit[A](deadline: Long)(f: => A): A = {
    if (!permits.tryAcquire(math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
      throw new Busy
    try f
    finally permits.release()
  }

  private def withBytes[A](password: String)(f: Array[Byte] => A): A = {
    val bytes = normalize(password).getBytes(UTF_8)
    try f(bytes)
    finally Arrays.fill(bytes, 0.toByte)
  }
}

object PasswordHasher {

  private val logger = Logger("security.password")

  /** bcrypt's limit: a longer password is pre-hashed ([[PasswordHasher]]), and refused as a
    * new one ([[newPassword]]).
    */
  val MaxBytes = 72

  val DefaultCost = 12

  /** The lowest cost the app hashes with unless `bcrypt.allowLowCost` is set (in the
    * tests' config only).
    */
  val MinCost = 10

  /** A stored hash with a higher cost never matches: it would take minutes to check. */
  val MaxStoredCost = 16

  private val BcryptHash = """\$2[ab]\$(\d\d)\$[./A-Za-z0-9]{53}""".r
  private val LegacyHash = "[0-9a-f]{40}".r

  /** Whether `stored` is a legacy hash (the unsalted SHA-1 of the password, as hex). */
  def isLegacy(stored: String): Boolean = LegacyHash.matches(stored)

  /** What replaces a legacy hash after its grace period (P8, V81): neither bcrypt nor SHA-1, so
    * it never matches ([[PasswordHasher.check]]); the owner sets a new password through a link.
    */
  val RetiredLegacy = "!retired-legacy-sha1"

  /** How a password is compared and stored: without surrounding spaces. */
  def normalize(password: String): String = password.trim

  /** The check of a new password (sign-up, a profile, an admin's edit form): not blank, and
    * at most [[MaxBytes]] UTF-8 bytes after trimming.
    */
  def newPasswordError(password: String): Option[String] = {
    val normalized = normalize(password)
    if (normalized.isEmpty) Some("password.empty")
    else if (normalized.getBytes(UTF_8).length > MaxBytes) Some("password.too.long")
    else None
  }

  /** [[newPasswordError]] as a form constraint. */
  val newPassword: Constraint[String] = Constraint[String]("constraint.password") { password =>
    newPasswordError(password).fold[ValidationResult](Valid)(key =>
      Invalid(ValidationError(key, MaxBytes)))
  }

  /** The result of [[PasswordHasher.check]]. */
  sealed trait Verdict
  case object Rejected extends Verdict

  /** The password matches; `rehash` is the new hash to store in place of the checked one
    * (a legacy hash, or a bcrypt hash of a lower cost than the configured one).
    */
  final case class Accepted(rehash: Option[String]) extends Verdict

  /** No turn to compute a hash before the deadline: too many sign-ins at once. */
  final class Busy extends RuntimeException("too many password checks at once", null, false, false)

  /** @param cost          the bcrypt cost of new hashes (2^cost rounds)
    * @param maxConcurrent bcrypt computations at once
    * @param maxWait       how long a sign-in waits for its turn
    * @param maxQueued     sign-ins and sign-ups in flight (queued or running) at most; more
    *                      get 503 at once
    */
  final case class Settings(cost: Int, maxConcurrent: Int, maxWait: FiniteDuration, maxQueued: Int = 50)

  object Settings {

    /** `bcrypt.{cost, maxConcurrent, maxWait, maxQueued}`; a cost below [[MinCost]] is raised to
      * it unless `bcrypt.allowLowCost` is true.
      */
    def from(config: Config): Settings = {
      def opt[A](key: String)(get: String => A): Option[A] = {
        val path = s"bcrypt.$key"
        if (config.hasPath(path)) Some(get(path)) else None
      }
      val requested = opt("cost")(config.getInt).getOrElse(DefaultCost)
      val allowLow = opt("allowLowCost")(config.getBoolean).getOrElse(false)
      val cost =
        if (requested < MinCost && !allowLow) {
          logger.warn(s"bcrypt.cost $requested is below $MinCost; using $MinCost")
          MinCost
        } else requested
      require(cost >= BCrypt.MIN_COST && cost <= MaxStoredCost,
        s"bcrypt.cost must be between ${BCrypt.MIN_COST} and $MaxStoredCost, was $cost")
      val maxConcurrent = opt("maxConcurrent")(config.getInt).getOrElse(2)
      require(maxConcurrent >= 1, s"bcrypt.maxConcurrent must be at least 1, was $maxConcurrent")
      val maxWait = opt("maxWait")(p => config.getDuration(p).toMillis.millis).getOrElse(10.seconds)
      val maxQueued = opt("maxQueued")(config.getInt).getOrElse(50)
      require(maxQueued >= 1, s"bcrypt.maxQueued must be at least 1, was $maxQueued")
      Settings(cost, maxConcurrent, maxWait, maxQueued)
    }
  }

  /** The app's hasher, configured from the app's config file (`-Dconfig.file`, as Play reads
    * it): the tests' and the Gatling fixture's config hash at cost 4.
    */
  lazy val default: PasswordHasher = new PasswordHasher(Settings.from(ConfigFactory.load()))

  private def sha1Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
}
