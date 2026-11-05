package db.stress

import munit.FunSuite

/** The stress harness's INVALID marking (`WallTimeCheck`), without running the harness. */
class WallTimeCheckSpec extends FunSuite {

  private val leap = "HikariPool-3 - Thread starvation or clock leap detected (housekeeper delta=1h2m3s)."
  private val retrograde =
    "HikariPool-3 - Retrograde clock change detected (housekeeper delta=29s), soft-evicting connections from pool."

  test("a run that took about its seconds is valid") {
    assertEquals(WallTimeCheck.invalidReason(120, 131.4, Nil), None)
    assertEquals(WallTimeCheck.invalidReason(120, 420.0, Nil), None)
  }

  test("a run longer than seconds + 300 is INVALID: the host slept") {
    val reason = WallTimeCheck.invalidReason(120, 3725.0, Nil)
    assert(reason.exists(_.startsWith("host slept or clock jumped")), reason)
    assert(reason.exists(_.contains("elapsed 3725 s for a 120 s run")), reason)
  }

  test("a Hikari clock warning makes a run of normal length INVALID") {
    val reason = WallTimeCheck.invalidReason(120, 125.0, Seq(leap))
    assert(reason.exists(_.startsWith("host slept, clock jumped or threads starved")), reason)
    assert(reason.exists(_.contains("clock leap detected")), reason)
    assert(!reason.exists(_.contains("elapsed")), reason)
  }

  test("both reasons are given together") {
    val reason = WallTimeCheck.invalidReason(60, 900.0, Seq(leap, retrograde))
    assert(reason.exists(r => r.contains("elapsed 900 s") && r.contains("2 clock warning(s)")), reason)
  }

  test("Hikari's two clock warnings are recognised, other pool messages are not") {
    assert(WallTimeCheck.isClockWarning(leap))
    assert(WallTimeCheck.isClockWarning(retrograde))
    assert(!WallTimeCheck.isClockWarning("HikariPool-3 - Connection is not available, request timed out after 30000ms."))
  }
}
