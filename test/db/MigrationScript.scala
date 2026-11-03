package db

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.sql.Connection
import scala.util.Using

/** Runs one migration's statements on a connection, for a spec that applies it to a scratch
  * database. A simplified splitter, not Flyway's parser: whole-line `--` comments are dropped
  * and statements split at a `;` that ends a line (no DELIMITER, no `;` at a line's end inside a
  * string), which is enough for the scripts it runs (V85, V86). Returns the warnings the
  * statements left (Flyway logs them as "DB: ...").
  */
object MigrationScript {

  def run(c: Connection, file: String): Seq[String] = {
    val sql = new String(Files.readAllBytes(Paths.get("conf/db/migration/default", file)), StandardCharsets.UTF_8)
    val statements = sql.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(""";\s*(\n|$)""").map(_.trim).filter(_.nonEmpty)
    statements.toSeq.flatMap { statement =>
      Using.resource(c.createStatement()) { st =>
        st.execute(statement)
        Iterator.iterate(st.getWarnings)(_.getNextWarning).takeWhile(_ != null).map(_.getMessage).toList
      }
    }
  }
}
