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

  def createNewUser(user: User, formUser: User)(
      implicit messages: Messages): User = {
    val contest: Option[ContestJury] =
      formUser.currentContest.flatMap(ContestJuryJdbc.findById)
    createUser(user, formUser, contest)
  }

  /** Adds the person to the contest (`contestOpt`, else the creator's): a new account, or,
    * if one with the email (without an email: the wiki account) exists, a membership of
    * that account, whose password stays. One account per person, in as many contests as
    * needed. A new member gets the contest's greeting, if it sends one; an existing member
    * keeps their role and gets nothing.
    */
  def createUser(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury])(implicit messages: Messages): User = {
    val contestId = contestOpt.flatMap(_.id).orElse(creator.contestId)
    val role = formUser.contestRole.getOrElse(User.JURY_ROLE)

    existingAccount(formUser) match {
      case Some(account) =>
        val added = contestId.exists(UserContestJdbc.createMembership(account.getId, _, role))
        val member = contestId.flatMap(User.findInContest(account.getId, _)).getOrElse(account)
        for (contest <- contestOpt if added && contest.greeting.use)
          sendMail(creator, member, contest, messages("password.existing"))
        member

      case None =>
        val password = formUser.password.getOrElse(User.randomString(12))
        val createdUser = User.create(
          formUser.copy(
            password = Some(User.hash(formUser, password)),
            contestId = contestId
          )
        )
        for (contest <- contestOpt if contest.greeting.use)
          sendMail(creator, createdUser, contest, password)
        createdUser
    }
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
