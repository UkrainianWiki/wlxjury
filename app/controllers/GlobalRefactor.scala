package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.scalawiki.MwBot
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.cmd.query.list.ListArgs
import org.scalawiki.dto.cmd.query.{Generator, Query}
import org.scalawiki.dto.{Namespace, Page}
import org.scalawiki.query.DslQuery

import scala.concurrent.Future

class GlobalRefactor(val commons: MwBot) {

  import scala.concurrent.ExecutionContext.Implicits.global

  def initUrls(): Unit = {
    KOATUU.load()
  }

  def getCategories(parent: String): Future[Seq[Page]] = {

    val generatorArg = ListArgs
      .toDsl("categorymembers",
             Some(parent),
             None,
             Set(Namespace.CATEGORY),
             Some("max"))
      .get
    val action = Action(
      Query(
        Generator(generatorArg)
      ))
    new DslQuery(action, commons).run().map(_.allPages.toSeq)
  }

  def addContestCategories(contestName: String, year: Int): Future[Unit] = {
    val parent = s"Category:Images from $contestName $year"
    getCategories(parent).map { categories =>
      val contests = categoriesToContests(contestName, year, parent, categories)
      //        val ids = ContestJuryJdbc.batchInsert(contests)
      //        val rounds =  ids.map (id => Round(None, 1, Some("Round 1"), id))
      //
      //        rounds.foreach {
      //          round =>
      //            val roundId = RoundJdbc.create(round).id
      //            ContestJuryJdbc.setCurrentRound(round.contest, roundId.get)
      //        }

      contests.foreach { contest =>
        val dbContest = ContestJuryJdbc
          .where("country" -> contest.country,
                 "year" -> year,
                 "name" -> contestName)
          .apply()
          .head
        //GlobalRefactor.appendImages(contest.files.get, dbContest)
        addAdmin(dbContest)
      }
    }
  }

  def rateByCategory(category: String,
                     juryId: Long,
                     roundId: Long,
                     rate: Int): Future[Unit] = {

    val future = commons
      .page(category)
      .imageInfoByGenerator("categorymembers",
                            "cm",
                            props = Set("timestamp", "user", "size", "url"),
                            titlePrefix = None)

    future.map { filesInCategory =>
      filesInCategory.foreach { page =>
        SelectionJdbc.rate(page.id.get, juryId, roundId, rate)
      }
    }
  }

  def distributeByCategory(parent: String,
                           contest: ContestJury): Future[Seq[Future[Unit]]] = {

    //    val round = Round(None, 1, Some("Round 1"), contest.getId, distribution = 0, active = true)
    //
    //    val roundId = RoundJdbc.create(round).id
    //    ContestJuryJdbc.setCurrentRound(round.contest, roundId.get)

    val round = Round.findById(89).get

    getCategories(parent).map { categories =>
      val jurors = categories.map(
        "WLMUA2015_" + _.title.split("-")(1).trim.replaceAll(" ", "_"))
      val logins = jurors ++ Seq(contest.name + "OrgCom")
      val passwords = logins.map(_ => User.randomString(8))
      val users = logins.zip(passwords).map {
        case (login, password) =>
          User.create(
            login,
            login,
            User.sha1(contest.country + "/" + password),
            if (jurors.contains(login))
              Set("jury")
            else Set("organizer"),
            contest.id,
            Some("uk")
          )
      }

      logins.zip(passwords).foreach {
        case (login, password) =>
          println(s"$login / $password")
      }

      val jurorsDb = users.init

      categories.zip(jurorsDb).map {
        case (category, juror) =>
          val future = commons
            .page(category.title)
            .imageInfoByGenerator("categorymembers",
                                  "cm",
                                  props =
                                    Set("timestamp", "user", "size", "url"),
                                  titlePrefix = None)

          future.map { filesInCategory =>
            val selection =
              filesInCategory.map(img => Selection(img, juror, round))
            SelectionJdbc.batchInsert(selection)
            round.id.foreach(RoundImageCounts.invalidate(_))
          }
      }
    }
  }

  def addAdmin(contest: ContestJury): Unit = {
    val shortContest = contest.name.split(" ").map(_.head).mkString("")
    val shortCountry = contest.country
      .replaceFirst("the ", "")
      .replaceFirst(" & Nagorno-Karabakh", "")
      .split(" ")
      .mkString("")

    val name = shortContest + contest.year + shortCountry + "Admin"

    val password = User.randomString(8)
    val hash = User.sha1(contest.country + "/" + password)
    val user =
      User(name, name, None, Set("admin"), Some(hash), contest.id, Some("en"))

    println(s"admin user: $name / $password")
    User.create(user)
  }

  def categoriesToContests(contest: String,
                           year: Int,
                           parent: String,
                           categories: Seq[Page]): Seq[ContestJury] = {

    val imageCategories = categories.filter(_.title.startsWith(parent + " in "))
    val contests = imageCategories.map { imageCategory =>
      val title = imageCategory.title
      val country = title.replaceFirst(parent + " in ", "")
      ContestJury(None, contest, year, country, Some(title), None, None)
    }
    contests
  }
}
