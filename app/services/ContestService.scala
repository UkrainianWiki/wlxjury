package services

import db.scalikejdbc.ContestJuryJdbc
import org.intracer.wmua.ContestJury
import org.scalawiki.MwBot
import org.scalawiki.dto.Namespace
import org.scalawiki.wlx.CountryParser
import org.scalawiki.wlx.dto.Contest
import spray.util.pimpFuture

import javax.inject.Inject

class ContestService @Inject() (val commons: MwBot) {

  def importContests(formContest: String, actorId: Option[Long] = None): Iterable[ContestJury] = {
    val imported =
      if (formContest.startsWith("Commons:")) {
        importListPage(formContest)
      } else {
        importCategory(formContest)
      }

    val existing = ContestJuryJdbc
      .findAll()
      .map(c => s"${c.name}/${c.year}/${c.country}")
      .toSet
    val newContests = imported
      .filterNot(c => existing.contains(s"${c.contestType.name}/${c.year}/${c.country.name}"))

    newContests.map { contest =>
      val contestJury = ContestJury(
        id = None,
        name = contest.contestType.name,
        year = contest.year,
        country = contest.country.name,
        images = Some(
          s"Category:Images from ${contest.contestType.name} ${contest.year} in ${contest.country.name}"
        ),
        monumentIdTemplate = contest.uploadConfigs.headOption.map(_.fileTemplate),
        campaign = Some(contest.campaign)
      )
      createContest(contestJury, actorId, "import")
    }
  }

  def importListPage(pageName: String): Seq[Contest] = {
    val wiki = commons.pageText(pageName).await
    CountryParser.parse(wiki)
  }

  def importCategory(categoryName: String): Iterable[Contest] = {
    val pages =
      commons.page(categoryName).categoryMembers(Set(Namespace.CATEGORY)).await

    pages.flatMap(p => CountryParser.fromCategoryName(p.title)) ++
      CountryParser
        .fromCategoryName(categoryName)
        .filter(_.country.name.nonEmpty)
  }

  /** Adds the contest; with its creator (root; `how`: the add form, an import, the API), records
    * its creation (V109: `created_at`, `created_by` and a `contest_change` row), in one transaction.
    */
  def createContest(contest: ContestJury, actorId: Option[Long] = None, how: String = "api"): ContestJury =
    scalikejdbc.DB.localTx { implicit session =>
      val created = ContestJuryJdbc.create(
        contest.id,
        contest.name,
        contest.year,
        contest.country,
        contest.images,
        contest.categoryId,
        contest.monumentIdTemplate,
        contest.campaign
      )
      actorId.foreach(services.series.ContestSeries.recordCreated(created, _, how))
      created
    }

  /** Updates the contest's identity and settings (the API's, root's): refused (`Left`, a message
    * key) if the contest is in a series its new type or country no longer fits, or the new year is
    * taken there (contest series plan §3.1); an identity change is logged (`identity`) with the
    * actor.
    */
  def updateContest(contest: ContestJury, actorId: Option[Long] = None): Either[String, Unit] =
    contest.id.fold[Either[String, Unit]](Left("error.contest.not.found")) { id =>
      services.series.ContestSeries.editIdentity(id, contest, actorId) { implicit session =>
        ContestJuryJdbc.updateById(id)
          .withAttributes(
            "name" -> contest.name,
            "year" -> contest.year,
            "country" -> contest.country,
            "images" -> contest.images,
            "monumentIdTemplate" -> contest.monumentIdTemplate,
            "campaign" -> contest.campaign
          )
        ()
      }.left.map(k => if (k == "contest.not.found") "error.contest.not.found" else k)
    }

  def findContests(): List[ContestJury] = {
    ContestJuryJdbc.findAll() // .map(_.copy(messages = applicationMessages))
  }

  def getContest(id: Long): Option[ContestJury] = {
    ContestJuryJdbc.findById(id)
  }

}
