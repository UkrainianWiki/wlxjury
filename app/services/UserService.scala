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

  def createUser(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury])(implicit messages: Messages): User = {
    val password = formUser.password.getOrElse(User.randomString(12))
    val hash = User.hash(formUser, password)

    val toCreate = formUser.copy(
      password  = Some(hash),
      contestId = contestOpt.flatMap(_.id).orElse(creator.contestId))

    val createdUser = User.create(toCreate)

    contestOpt.foreach { contest =>
      contest.id.foreach { cid =>
        val role = toCreate.roles
          .find(r => r != db.scalikejdbc.User.ROOT_ROLE && !r.startsWith("USER_ID_"))
          .getOrElse("jury")
        db.scalikejdbc.UserContestJdbc.createMembership(createdUser.getId, cid, role)
      }
      if (contest.greeting.use) {
        sendMail(creator, createdUser, contest, password)
      }
    }
    createdUser
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
