package services.regions

import org.intracer.wmua.ContestJury
import org.scalawiki.wlx.dto.{Contest, ContestType, Country, SpecialNomination}

import scala.collection.concurrent.TrieMap

/** What a jury contest is in scalawiki's terms: its campaign (`wlm_ua.conf`, `wle_ua.conf`,
  * ...), and so its country, monument id template and special nominations.
  *
  * Found by the contest's `campaign` if it has one, else by its name and country (production
  * contests have no campaign): the name exactly a contest type's ("Wiki Loves Monuments"),
  * the country exactly a country's name or code ("Ukraine", "ua"), so that "Ukraine Int" or
  * "Ukraine, Animals" are not taken for Ukraine. A campaign without a config of its own
  * (`wlm-de`) still gives the type and the country.
  *
  * Memoised: loading a campaign's config is not free, and contests are few.
  */
object ContestKind {

  private final case class Key(campaign: Option[String], name: String, country: String)

  private val kinds = TrieMap.empty[Key, Option[Contest]]
  private val nominationsByKey = TrieMap.empty[Key, Seq[SpecialNomination]]

  private def key(c: ContestJury): Key =
    Key(c.campaign.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty), c.name.trim, c.country.trim)

  /** The contest's campaign, in the contest's year; None if neither its campaign nor its
    * name and country are known to scalawiki.
    */
  def of(c: ContestJury): Option[Contest] =
    kinds.getOrElseUpdate(key(c), resolve(key(c))).map(_.copy(year = c.year))

  private def resolve(k: Key): Option[Contest] =
    k.campaign match {
      case Some(campaign) => Contest.byCampaign(campaign)
      case None =>
        for {
          contestType <- ContestType.all.find(_.name.equalsIgnoreCase(k.name))
          country <- country(k.country)
          contest <- Contest.byCampaign(s"${contestType.code}-${country.code.toLowerCase}")
        } yield contest
    }

  /** The country of that name or code, exactly (case aside). */
  def country(nameOrCode: String): Option[Country] =
    Option(nameOrCode.trim).filter(_.nonEmpty).flatMap { s =>
      Country.countryMap.values.find(_.name.equalsIgnoreCase(s)).orElse(Country.byCode(s))
    }

  /** The template that holds a file's monument id, from the campaign config. */
  def configMonumentIdTemplate(c: ContestJury): Option[String] =
    of(c).flatMap(_.fileTemplate).map(_.trim).filter(_.nonEmpty)

  /** The contest's monument id template: the one an admin set, else the campaign config's
    * (`fileTemplate`). Read here, not from the column, wherever the template matters: a
    * contest whose column was never filled still gets its monument ids and regions.
    */
  def monumentIdTemplate(c: ContestJury): Option[String] =
    c.monumentIdTemplate.map(_.trim).filter(_.nonEmpty).orElse(configMonumentIdTemplate(c))

  /** The campaign's special nominations, of every year: none for a campaign without them
    * (`wle_ua.conf`) or without a config.
    */
  def nominations(c: ContestJury): Seq[SpecialNomination] =
    nominationsByKey.getOrElseUpdate(
      key(c),
      of(c).flatMap(_.config).filter(_.hasPath("nominations")).fold(Seq.empty[SpecialNomination])(SpecialNomination.fromConfig)
    )

  /** Forgets the memoised kinds: for tests. */
  def clear(): Unit = {
    kinds.clear()
    nominationsByKey.clear()
  }
}
