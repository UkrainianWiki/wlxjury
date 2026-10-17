package services

import controllers.Greeting
import db.scalikejdbc.{ContestJuryJdbc, User, UserContestJdbc}
import org.intracer.wmua.ContestJury
import play.api.Configuration
import play.api.i18n.Messages

import javax.inject.Inject
import scala.collection.immutable.ListMap

class UserService @Inject()(val sendMail: SMTPOrWikiMail,
                            configuration: Configuration) {

  /** Adds the person in the edit form to its contest; see [[addToContest]]. */
  def createNewUser(user: User, formUser: User)(
      implicit messages: Messages): UserService.Added = {
    val contest: Option[ContestJury] =
      formUser.currentContest.flatMap(ContestJuryJdbc.findById)
    addToContest(user, formUser, contest)
  }

  def createUser(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury])(implicit messages: Messages): User =
    addToContest(creator, formUser, contestOpt).user

  /** Adds the person to the contest (`contestOpt`, else the creator's): a new account, or,
    * if one with the email (without an email: the wiki account) exists, a membership of
    * that account, whose password stays. One account per person, in as many contests as
    * needed. A new member gets the contest's greeting, if it sends one; an existing member
    * keeps their role and gets nothing.
    *
    * For an organizer adding people only: it hands out existing accounts. Sign-up creates
    * its account with [[createAccount]].
    */
  def addToContest(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury])(implicit messages: Messages): UserService.Added = {
    import UserService._
    val contestId = contestOpt.flatMap(_.id).orElse(creator.contestId)
    val role = formUser.contestRole.getOrElse(User.JURY_ROLE)

    existingAccount(formUser) match {
      case Some(account) =>
        val added = contestId.exists(UserContestJdbc.createMembership(account.getId, _, role))
        val member = contestId.flatMap(User.findInContest(account.getId, _)).getOrElse(account)
        for (contest <- contestOpt if added && contest.greeting.use)
          sendMail(creator, member, contest, messages("password.existing"))
        if (added) JoinedContest(member) else AlreadyMember(member)

      case None =>
        // root only from root ([[createAccount]]); anyone else's new user gets a contest role
        val roles = if (creator.hasRole(User.ROOT_ROLE)) formUser.roles else Set(role)
        NewAccount(createAccount(creator, formUser.copy(contestId = contestId, roles = roles), contestOpt))
    }
  }

  /** Creates the account, with a membership in [[User.contestId]] if set, and sends the
    * contest's greeting if it uses one. Fails on an email that already has an account.
    * Only a root creator makes a root user.
    */
  def createAccount(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury])(implicit messages: Messages): User = {
    val allowed =
      if (creator.hasRole(User.ROOT_ROLE)) formUser
      else formUser.copy(roles = formUser.roles - User.ROOT_ROLE, isRoot = false)
    val password = allowed.password.getOrElse(User.randomString(12))
    val createdUser = User.create(allowed.copy(password = Some(User.hash(allowed, password))))
    for (contest <- contestOpt if contest.greeting.use)
      sendMail(creator, createdUser, contest, password)
    createdUser
  }

  /** The account a new user's email (or, without an email, wiki account) already has. */
  def existingAccount(formUser: User): Option[User] = {
    val email = formUser.email.trim.toLowerCase
    if (email.nonEmpty) User.findByEmail(email).headOption
    else formUser.wikiAccount.map(_.trim).filter(_.nonEmpty).flatMap(User.findByAccount(_).headOption)
  }

  def sendMail(creator: User,
               recipient: User,
               contest: ContestJury,
               password: String)(implicit messages: Messages): Unit = {
    val greeting = getGreeting(contest)
    val subject = messages("welcome.subject", contest.name)
    val message = fillGreeting(greeting.text.get,
                               contest,
                               creator,
                               recipient.copy(password = Some(password)))
    sendMail.sendMail(creator, recipient, subject, message)
  }

  def getGreeting(contest: ContestJury): Greeting = {
    val defaultGreeting = configuration.get[String]("wlxjury.greeting")

    contest.greeting.text
      .fold(contest.greeting.copy(text = Some(defaultGreeting)))(_ =>
        contest.greeting)
  }

  def fillGreeting(template: String,
                   contest: ContestJury,
                   sender: User,
                   user: User): String = {
    variables(contest, sender, user).foldLeft(template) {
      case (s, (k, v)) =>
        s.replace(k, v)
    }
  }

  def variables(contest: ContestJury,
                sender: User,
                recipient: User): Map[String, String] = {
    val host = configuration.get[String]("wlxjury.host")

    ListMap(
      "{{ContestType}}" -> contest.name,
      "{{ContestYear}}" -> contest.year.toString,
      "{{ContestCountry}}" -> contest.country,
      "{{ContestCountry}}" -> contest.country,
      "{{JuryToolLink}}" -> host,
      "{{AdminName}}" -> sender.fullname,
      "{{RecipientName}}" -> recipient.fullname,
      "{{Login}}" -> recipient.email,
      "{{Password}}" -> recipient.password.getOrElse("")
    )
  }

}

object UserService {

  /** What [[UserService.addToContest]] did: the person as a member of the contest. */
  sealed trait Added { def user: User }
  case class NewAccount(user: User) extends Added
  case class JoinedContest(user: User) extends Added
  case class AlreadyMember(user: User) extends Added
}
