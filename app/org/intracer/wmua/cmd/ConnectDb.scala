package org.intracer.wmua.cmd

import play.api.{Configuration, Play}
import scalikejdbc.{ConnectionPool, GlobalSettings, LoggingSQLAndTimeSettings}

case class ConnectDb(host: String = "jury.wikilovesearth.org.ua",
                     configuration: Configuration)
    extends (() => Unit) {

  def apply() = {
    Class.forName("com.mysql.jdbc.Driver")

    val url = s"jdbc:mysql://$host/wlxjury"

    val user = configuration.get[String]("db.default.user")
    val password = configuration.get[String]("db.default.user")

    ConnectionPool.singleton(url, user, password)

    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(
      enabled = true,
      singleLineMode = false,
      printUnprocessedStackTrace = false,
      stackTraceDepth = 15,
      logLevel = "info",
      warningEnabled = false,
      warningThresholdMillis = 3000L,
      warningLogLevel = "warn"
    )

  }

}
