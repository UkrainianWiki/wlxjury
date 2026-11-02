package security

import db.scalikejdbc.User
import org.specs2.mutable.Specification

/** G1 (N1): a stored role is exactly one value of `User.ContestRoles`; nothing splits a role
  * string; root comes only from `users.is_root`. The DAO writers' refusals and the reader are
  * covered by `UserContestSpec`/`UserContestRolesSpec`, the form and controller by
  * `RoleAssignmentSpec`, the database by V76's constraint (`UserContestRoleMigrationSpec`).
  */
class RoleValuesSpec extends Specification {

  /** Form fields named `role`/`roles` without an allowlist `verifying`, with why that is fine. */
  private val UncheckedRoleFields: Map[String, String] = Map(
    "app/controllers/EditRound.scala" ->
      "a round's juror role label (rounds.roles): picks which members of the round's own contest are offered as jurors; grants nothing"
  )

  /** A `role…` value (or a field of one) that is split. */
  private val SplitRole = """(?i)\brole\w*(?:\.\w+|\(\))*\.split\(""".r

  /** A Play form mapping's field named `role` or `roles`. */
  private val RoleField = """"roles?"\s*->\s*(?:optional|default|text|nonEmptyText|seq|list|set)\b[^\n]*""".r

  "role values in app/" should {
    "never be split (a role is one value, not a list)" in {
      SourceScan.hits(SourceScan.files("app", ".scala", ".html"), SplitRole) must beEmpty
    }

    "be found when split (the scan is not vacuous)" in {
      (SplitRole.findFirstIn("""uc.role.split(",")""") must beSome) and
        (SplitRole.findFirstIn("""roles.split(",").map(_.trim)""") must beSome) and
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
      (User.ContestRoles === User.FORM_ROLES.toSet) and
        (User.ContestRoles must not(contain(User.ROOT_ROLE))) and
        (User.ContestRoles.exists(_.startsWith("USER_ID_")) must beFalse)
    }

    "match exactly: no lists, spaces, case variants, root or markers" in {
      val refused = Seq("root", "Root", " root", "root ", "jury,root", "admin,root", "Jury", "jury ", "USER_ID_1",
        "jury1", "jury-", "prejury", "")
      (refused.filter(User.isContestRole) must beEmpty) and (User.FORM_ROLES.forall(User.isContestRole) must beTrue)
    }

    "let only a root editor assign root" in {
      val admin = User("Admin", "admin@example.org", Some(1L), Set(User.ADMIN_ROLE), contestId = Some(1L))
      val root = User("Root", "root@example.org", Some(2L), Set(User.ROOT_ROLE), isRoot = true)
      (User.mayAssignRoles(admin, Set(User.ROOT_ROLE)) must beFalse) and
        (User.mayAssignRoles(admin, Set("jury,root")) must beFalse) and
        (User.mayAssignRoles(admin, Set(User.ADMIN_ROLE)) must beTrue) and
        (User.mayAssignRoles(root, Set(User.ROOT_ROLE)) must beTrue) and
        (User.mayAssignRoles(root, Set("USER_ID_1")) must beFalse)
    }
  }
}
