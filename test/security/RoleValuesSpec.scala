package security

import db.scalikejdbc.User
import org.specs2.mutable.Specification

/** G1 (N1): a stored role is exactly one value of `User.ContestRoles`; nothing splits a role
  * string; root comes only from `users.is_root`. The DAO writers' refusals and the reader are
  * covered by `UserContestSpec`/`UserContestRolesSpec`, the form and controller by
  * `RoleAssignmentSpec`, the database by V76's constraint (`UserContestRoleMigrationSpec`).
  */
class RoleValuesSpec extends Specification {

  /** Files whose form fields named `role`/`roles` have no allowlist `verifying`, with why that
    * is fine. Empty: every one is checked.
    */
  private val UncheckedRoleFields: Map[String, String] = Map.empty

  /** A value whose name contains `role` (`role`, `userRole`, `contestRoles`, a field or a call of
    * one) that is split, or a `role`/`roles` column read and split.
    */
  private val SplitRole = """(?i)role\w*(?:\.\w+|\(\))*\.split\(|string\("roles?"\)\.split\(""".r

  /** A Play form mapping's field named `role` or `roles`. A heuristic: the field name exactly,
    * with its `verifying` on the same line.
    */
  private val RoleField = """"roles?"\s*->\s*(?:optional|default|text|nonEmptyText|seq|list|set)\b[^\n]*""".r

  "role values in app/" should {
    "never be split (a role is one value, not a list)" in {
      SourceScan.hits(SourceScan.files("app", ".scala", ".html"), SplitRole) must beEmpty
    }

    "be found when split (the scan is not vacuous)" in {
      val split = Seq("""uc.role.split(",")""", """roles.split(",").map(_.trim)""", """uc.contestRole.split(",")""",
        """userRole.split(",")""", """contestRoles.split(",")""", """membershipRole.split(",")""",
        """rs.string("role").split(",")""")
      (split.filter(SplitRole.findFirstIn(_).isEmpty) must beEmpty) and
        (SplitRole.findFirstIn("""Set(role)""") must beNone)
    }

    "come from an allowlist in every form field named role or roles" in {
      val unchecked = for {
        source <- SourceScan.files("app/controllers", ".scala")
        field <- RoleField.findAllMatchIn(source.text).toList
        if !field.matched.contains("verifying") && !UncheckedRoleFields.contains(source.path)
      } yield s"${source.path}:${source.lineOf(field.start)}: ${field.matched.trim}"
      unchecked must beEmpty
    }
  }

  "the contest role allowlist" should {
    "hold exactly the form's roles, never root or a marker" in {
      (User.ContestRoles === Set("no", "jury", "organizer", "admin", "board", "auditor", "staff")) and
        (User.ContestRoles must not(contain(User.ROOT_ROLE))) and
        (User.ContestRoles.exists(_.startsWith("USER_ID_")) must beFalse)
    }

    "match exactly: no lists, spaces, case variants, root or markers" in {
      val refused = Seq("root", "Root", " root", "root ", "jury,root", "admin,root", "Jury", "jury ", "USER_ID_1",
        "jury1", "jury-", "prejury", "")
      (refused.filter(User.isContestRole) must beEmpty) and (User.FORM_ROLES.forall(User.isContestRole) must beTrue)
    }

    "let nobody assign root as a role, root included (root is the account's Root field)" in {
      val admin = User("Admin", "admin@example.org", Some(1L), Set(User.ADMIN_ROLE), contestId = Some(1L))
      val root = User("Root", "root@example.org", Some(2L), Set(User.ROOT_ROLE), isRoot = true)
      (User.mayAssignRoles(admin, Set(User.ROOT_ROLE)) must beFalse) and
        (User.mayAssignRoles(admin, Set("jury,root")) must beFalse) and
        (User.mayAssignRoles(admin, Set(User.ADMIN_ROLE)) must beTrue) and
        (User.mayAssignRoles(root, Set(User.ROOT_ROLE)) must beFalse) and
        (User.mayAssignRoles(root, Set(User.ADMIN_ROLE)) must beTrue) and
        (User.mayAssignRoles(root, Set("USER_ID_1")) must beFalse)
    }
  }
}
