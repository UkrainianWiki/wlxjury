package gatling.setup

import scalikejdbc._

/** External-target mode (`-Dgatling.target=external`): the simulations run against an app
  * that is already running, e.g. the production-like benchmark server
  * (bench/scripts/bench.sh, docs/plans/2026-10-07-prod-like-benchmark.md), with no
  * TestServer and no testcontainer. The data is whatever that server's database holds,
  * typically an anonymised production copy; the fixture's ids are read from it over JDBC
  * (`gatling.external.db.*`) for the contest and rounds named in `gatling.external`.
  *
  * Accounts: the anonymised copy gives every user the password `<passwordPrefix><id>`
  * (bench/scripts/anonymise.sql). Jurors are those with selection rows in the two rounds;
  * organizers are the contest's own admin accounts.
  */
object GatlingExternalTarget {

  private def cfg = GatlingConfig.External

  def load(): GatlingFixtureData = {
    Class.forName("org.mariadb.jdbc.Driver")
    ConnectionPool.singleton(cfg.dbUrl, cfg.dbUser, cfg.dbPassword,
      ConnectionPoolSettings(initialSize = 2, maxSize = 8))
    implicit val session: DBSession = AutoSession

    val contestId = cfg.contestId
    val binaryId  = cfg.binaryRoundId
    val ratedId   = cfg.ratedRoundId
    val rounds    = Seq(binaryId, ratedId)

    val roundContests = sql"SELECT id, contest_id FROM rounds WHERE id IN ($rounds)"
      .map(rs => rs.long(1) -> rs.long(2)).list().toMap
    require(rounds.forall(roundContests.get(_).contains(contestId)),
      s"rounds $rounds must exist and belong to contest $contestId: $roundContests")

    def password(id: Long) = s"${cfg.passwordPrefix}$id"

    val jurors = sql"""SELECT DISTINCT u.id, u.email FROM selection s JOIN users u ON u.id = s.jury_id
                       WHERE s.round_id IN ($rounds) ORDER BY u.id"""
      .map(rs => (rs.long(1), rs.string(2), password(rs.long(1)))).list()

    val organizers = sql"""SELECT u.id, u.email FROM users u JOIN user_contest uc ON uc.user_id = u.id
                           WHERE uc.contest_id = $contestId
                           AND (uc.role LIKE '%admin%' OR uc.role LIKE '%organizer%') AND u.deleted_at IS NULL
                           ORDER BY u.id"""
      .map(rs => (rs.long(1), rs.string(2), password(rs.long(1)))).list()
    require(organizers.nonEmpty, s"contest $contestId has no organizer accounts")

    val imagePageIds = sql"""SELECT cm.page_id FROM category_members cm
                             JOIN contest_jury c ON c.category_id = cm.category_id WHERE c.id = $contestId"""
      .map(_.long(1)).list()

    // Region codes as the gallery's region filter takes them (adm0: the monument id's
    // first part), of the binary round's images.
    val regions = sql"""SELECT DISTINCT LEFT(SUBSTRING_INDEX(i.monument_id, '-', 1), 3) r
                        FROM selection s JOIN images i ON i.page_id = s.page_id
                        WHERE s.round_id = $binaryId AND i.monument_id IS NOT NULL AND i.monument_id <> ''
                        ORDER BY r"""
      .map(_.string(1)).list()

    val votingPairs = sql"""SELECT jury_id, page_id, round_id, COALESCE(rate, 0) FROM selection
                            WHERE round_id IN ($rounds) AND deleted_at IS NULL"""
      .map(rs => (rs.long(1), rs.long(2), rs.long(3), rs.int(4))).list()

    GatlingFixtureData(port = cfg.appPort, contestId = contestId,
      roundBinaryId = binaryId, roundRatingId = ratedId,
      jurors = jurors, organizer = organizers.head,
      imagePageIds = imagePageIds, regions = regions, votingPairs = votingPairs,
      extraOrganizers = organizers.tail)
  }
}
