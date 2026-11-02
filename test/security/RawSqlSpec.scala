package security

import org.specs2.mutable.Specification

/** G4 (Vuln 1): request values are bound. `SQLSyntax.createUnsafely` and `SQL(s"…")` put text
  * into a statement unbound, so they are used only in the files listed here, with constants,
  * enumerated orders or numbers (a reviewer checks a new use in a listed file too).
  * G4b (Vuln 3, P8): passwords are hashed only by `PasswordHasher`; the only other digest is
  * the SHA-256 of an API token.
  */
class RawSqlSpec extends Specification {

  private val Allowed: Map[String, String] = Map(
    "app/db/scalikejdbc/ImageJdbc.scala" -> "regionOf over a fixed column name",
    "app/db/scalikejdbc/rewrite/ImageDbNew.scala" ->
      "the gallery query builder: structure, group, order and limit from enumerated options and numbers; values bound",
    "app/org/intracer/wmua/cmd/DistributeImages.scala" -> "the criteria ids, constants",
    "app/services/distribution/RoundDistributionHandler.scala" -> "an order from an enumerated set"
  )

  private val Digests: Map[String, String] = Map(
    "app/services/PasswordHasher.scala" -> "bcrypt, and checking a legacy SHA-1 hash",
    "app/services/ApiTokenService.scala" -> "SHA-256 of a random 256-bit token (stored hash only)",
    "app/controllers/LoginController.scala" ->
      "the setup token (P20): SHA-256 of both sides, compared in constant time; nothing stored"
  )

  "unbound SQL text" should {
    "be built only in the reviewed files" in {
      (SourceScan.hits(SourceScan.files("app", ".scala"), """createUnsafely\(|SQL\(s"""".r).keySet -- Allowed.keySet) must
        beEmpty
    }
  }

  "hashing" should {
    "use MessageDigest only in PasswordHasher and for API tokens, and SHA-1 only in PasswordHasher" in {
      val sources = SourceScan.files("app", ".scala")
      ((SourceScan.hits(sources, """MessageDigest""".r).keySet -- Digests.keySet) must beEmpty) and
        ((SourceScan.hits(sources, """(?i)"sha-?1"|sha1Hex|DigestUtils""".r).keySet - "app/services/PasswordHasher.scala") must
          beEmpty)
    }
  }
}
