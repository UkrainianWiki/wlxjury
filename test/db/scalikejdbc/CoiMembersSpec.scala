package db.scalikejdbc

import org.intracer.wmua.Image
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

/** The people whose files are conflicts of interest in a contest ([[CoiMembers]]): its
  * members in [[User.COI_ROLES]] and the root users, by their normalized wiki accounts.
  */
class CoiMembersSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def user(name: String, account: Option[String], contestRole: Option[(Long, String)] = None,
      root: Boolean = false): User = {
    val u = User.create(User(name, s"$name@example.com", None,
      contestRole.map(_._2).toSet ++ (if (root) Set(User.ROOT_ROLE) else Set.empty),
      Some("hash"), contestRole.map(_._1), wikiAccount = account))
    u
  }

  "normalize" should {
    "normalize a user name as MediaWiki does" in {
      CoiMembers.normalize("User:Some_user") === "Some user"
      CoiMembers.normalize("user: some  user ") === "Some user"
      CoiMembers.normalize("ilya") === "Ilya"
      CoiMembers.normalize("ǆungla") === "ǅungla" // title case, not upper case
      CoiMembers.normalize("ირაკლი") === "ირაკლი" // Georgian Mkhedruli stays as it is
      CoiMembers.normalize("ёлка") === "Ёлка"
    }
  }

  "forContest" should {

    "give the contest's members in conflict-of-interest roles, by account, and every root user" in {
      createContests(10, 20)
      val roles = Seq("admin", "organizer", "jury", "board", "auditor", "staff")
      val members = roles.map(r => r -> user(r, Some(s"${r}_account"), Some(10L -> r))).toMap
      user("prejury", Some("prejury account"), Some(10L -> "prejury"))
      user("no", Some("no account"), Some(10L -> User.NO_ROLE))
      user("other", Some("Other contest"), Some(20L -> "jury"))
      user("noaccount", None, Some(10L -> "board"))
      val root = user("root", Some("root user"), root = true)

      val coi = CoiMembers.forContest(10)
      coi.keySet === roles.map(r => CoiMembers.normalize(s"${r}_account")).toSet + "Root user"
      coi("Board account") === CoiMembers.Member(members("board").getId, "board_account", "board")
      coi("Root user") === CoiMembers.Member(root.getId, "root user", "root")
    }

    "report a root user who is a member with their contest role, and leave out deleted users" in {
      createContests(10)
      val rootJuror = user("rootjuror", Some("Rooty"), Some(10L -> "jury"), root = true)
      val deletedRoot = user("deletedroot", Some("Gone"), root = true)
      val deletedJuror = user("deletedjuror", Some("Gone juror"), Some(10L -> "jury"))
      DB.autoCommit(implicit s =>
        sql"UPDATE users SET deleted_at = NOW() WHERE id IN (${Seq(deletedRoot.getId, deletedJuror.getId)})".update())

      CoiMembers.forContest(10) === Map("Rooty" -> CoiMembers.Member(rootJuror.getId, "Rooty", "jury"))
    }

    "count a membership's change at once" in {
      createContests(10)
      val board = user("board", Some("Board member"), Some(10L -> "board"))
      CoiMembers.forContest(10).keySet === Set("Board member")
      UserContestJdbc.updateRole(board.getId, 10, User.NO_ROLE)
      CoiMembers.forContest(10) must beEmpty
    }
  }

  "files" should {
    "match the category's files by their stored author, exactly" in {
      implicit val contest: org.intracer.wmua.ContestJury = createContests(10).head
      user("staff", Some("Staff_member"), Some(10L -> "staff"))
      val images = Seq(
        Image(1, "File:1.jpg", author = Some("Staff member")),
        Image(2, "File:2.jpg", author = Some("Staff Member")), // equal in the column's collation only
        Image(3, "File:3.jpg", author = Some("[[User:Staff member|Staff]]")), // not imported again
        Image(4, "File:4.jpg")
      )
      imageDao.batchInsert(images)
      val categoryId = contestDao.findById(10).get.categoryId.get
      CategoryLinkJdbc.addToCategory(categoryId, images)

      CoiMembers.files(categoryId, CoiMembers.forContest(10)).keySet === Set(1L)
      CoiMembers.filesWithoutAuthor(categoryId) === 1L
    }
  }

  "users without an email" should {
    "be stored, two of them, with a NULL email" in {
      createContests(10)
      val a = User.create(User("", "", None, Set("board"), Some("hash"), Some(10L), wikiAccount = Some("A")))
      val b = User.create(User("", "", None, Set("staff"), Some("hash"), Some(10L), wikiAccount = Some("B")))
      User.findById(a.getId).map(_.email) === Some("")
      User.findById(b.getId).map(_.email) === Some("")
      DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM users WHERE email IS NULL".map(_.int(1)).single()) === Some(2)
    }
  }

  "a membership with only a role without access" should {
    "not be active: never the contest a request runs in" in {
      User.NON_ACCESS_ROLES.forall(r => !UserContest(1, 10, r).active) must beTrue
      UserContest(1, 10, User.NO_ROLE).active must beFalse
      Seq("jury", "organizer", "admin").forall(r => UserContest(1, 10, r).active) must beTrue
    }
  }
}
