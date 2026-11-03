package services

import play.api.db.DBApi
import javax.inject.{Inject, Singleton}

/** Uses Play's actual application pool, not a separate deployment connection. */
@Singleton
class ReadinessCheck @Inject()(dbApi: DBApi) {
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
