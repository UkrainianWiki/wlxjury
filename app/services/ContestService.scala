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

  def importContests(formContest: String): Iterable[ContestJury] = {
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
      createContest(contestJury)
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

  def createContest(contest: ContestJury): ContestJury = {
    ContestJuryJdbc.create(
      contest.id,
      contest.name,
      contest.year,
      contest.country,
      contest.images,
      contest.categoryId,
      contest.currentRound,
      contest.monumentIdTemplate
    )
  }

  def findContests(): List[ContestJury] = {
    ContestJuryJdbc.findAll() // .map(_.copy(messages = applicationMessages))
  }

  def getContest(id: Long): Option[ContestJury] = {
    ContestJuryJdbc.findById(id)
  }

}
