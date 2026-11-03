package services

import play.api.db.DBApi
import javax.inject.{Inject, Singleton}

/** Uses Play's actual application pool, not a separate deployment connection. */
@Singleton
class ReadinessCheck @Inject()(dbApi: DBApi) {

  /** The isolation level of a connection of the application pool, as the server reports it
    * (`@@tx_isolation`, on 11.1+ also `@@transaction_isolation`), e.g. `READ-COMMITTED`. The
    * write paths rely on READ COMMITTED (docs/plans/2026-11-03-write-path-simplification.md, W1),
    * set by `db.default.hikaricp.transactionIsolation`; a deployment config that overrides it
    * must not go unnoticed.
    */
  def isolation(): String = dbApi.database("default").withConnection { connection =>
    val statement = connection.createStatement()
    try {
      statement.setQueryTimeout(3)
      val rows = statement.executeQuery("SELECT @@tx_isolation")
      try { rows.next(); rows.getString(1) } finally rows.close()
    } finally statement.close()
  }

  def schemaVersion(): Option[String] = dbApi.database("default").withConnection { connection =>
    val statement = connection.createStatement()
    try {
      statement.setQueryTimeout(3)
      val rows = statement.executeQuery(
        "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL " +
          "AND NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success = 0) " +
          "ORDER BY installed_rank DESC LIMIT 1")
      try if (rows.next()) Option(rows.getString(1)) else None
      finally rows.close()
    } finally statement.close()
  }
}

object ReadinessCheck {

  /** The level the pool must run at (W1). */
  val RequiredIsolation = "READ-COMMITTED"
}
