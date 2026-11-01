package modules

import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment, Mode}
import play.filters.hosts.{AllowedHostsConfig, AllowedHostsFilter}

/** Play's allowed-hosts module (`play.filters.hosts.AllowedHostsModule`, which
  * `application.conf` disables in its favour), with the hosts also taken from
  * `wlxjury.allowedHosts`: one string of comma-separated host names, because the deployed names
  * come from an environment variable (`WLXJURY_ALLOWED_HOSTS` in `conf/wlxjury/production.conf`)
  * and HOCON can't split a variable into a list. The filter stays
  * `play.filters.hosts.AllowedHostsFilter`.
  *
  * Production (Mode.Prod) refuses to start without a host in `wlxjury.allowedHosts`: a runtime
  * config from before this setting would otherwise allow only the loopback names, and every
  * request through Apache would get a 400 once traffic resumed. A failed start keeps the deploy
  * tools' readiness check failing, with maintenance still on.
  */
class AllowedHostsModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[AllowedHostsConfig].toInstance(AllowedHosts.config(configuration, environment.mode)),
      bind[AllowedHostsFilter].toSelf
    )
}

object AllowedHosts {
  val Key = "wlxjury.allowedHosts"

  /** A host name, or a suffix of names (`.example.org`), with an optional port, as Play's
    * `HostMatcher` takes them. Not ".", which matches every host, nor an IPv6 literal. */
  private val Pattern =
    """(?i)\.?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:[0-9]{1,5})?""".r

  /** The names in `value`, separated by commas and/or whitespace. */
  def parse(value: String): Seq[String] = {
    val hosts = value.split("[,\\s]+").toSeq.map(_.trim).filter(_.nonEmpty)
    hosts.filterNot(Pattern.matches) match {
      case Seq() => hosts
      case invalid =>
        throw new IllegalArgumentException(
          s"$Key (WLXJURY_ALLOWED_HOSTS): not a host name: ${invalid.mkString(", ")}")
    }
  }

  /** Play's configured check (`play.filters.hosts.*`), allowing also the hosts of [[Key]]. */
  def config(configuration: Configuration, mode: Mode): AllowedHostsConfig = {
    val play  = AllowedHostsConfig.fromConfiguration(configuration)
    val extra = configuration.getOptional[String](Key).fold(Seq.empty[String])(parse)
    if (mode == Mode.Prod && extra.isEmpty)
      throw new IllegalStateException(
        s"$Key is empty: set WLXJURY_ALLOWED_HOSTS in runtime.env to the host names Apache serves " +
          "the jury tool at, comma-separated (conf/wlxjury/production.conf, docs/deployment.md)")
    play.copy(allowed = (play.allowed ++ extra).distinct)
  }
}
