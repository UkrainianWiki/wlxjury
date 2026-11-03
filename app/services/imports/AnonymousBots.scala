package services.imports

import org.scalawiki.MwBot

/** Bots that never log in from the environment.
  *
  * scalawiki's `MwBot.fromHost` / `fromSite` default to `loginInfo = LoginInfo.fromEnv()`: a
  * new bot logs in as `SCALAWIKI_LOGIN` / `SCALAWIKI_PASSWORD` when those are set (and throws
  * if that login fails). `MwBot.cache` then keeps that one bot per domain for every later call,
  * whatever its `loginInfo`. wlxjury reaches such default calls inside scalawiki too:
  * `SpecialNomination.getNominationMonuments` (`MonumentQuery.create` with its implicit
  * `MwBot.fromHost(MwBot.ukWiki)`) and `CampaignList` (`WithBot`, commons).
  *
  * So the bots of the hosts wlxjury reaches are created here, anonymous, before any default
  * call: [[seed]] at startup ([[modules.AppModule]]) and before the scalawiki calls that
  * create bots themselves. A later default call for those domains gets the anonymous bot from
  * the cache. The app's only account is `commons.user`, logged in by [[CommonsLogin]] on the
  * commons bot. A host outside [[Hosts]] (a contest's `listsHost`) is created through [[bot]]
  * where wlxjury uses it ([[MwMonumentListSource]]).
  */
object AnonymousBots {

  /** The wikis wlxjury reaches through scalawiki's default bots. */
  val Hosts: Seq[String] = Seq(MwBot.commons, MwBot.ukWiki)

  /** The bot for `host`: created without a login if it isn't in `MwBot.cache` yet. */
  def bot(host: String): MwBot = MwBot.fromHost(host, loginInfo = None)

  /** Creates the anonymous bots of [[Hosts]] and of `extra`, if not created yet. */
  def seed(extra: String*): Unit = (Hosts ++ extra).distinct.foreach(bot)
}
