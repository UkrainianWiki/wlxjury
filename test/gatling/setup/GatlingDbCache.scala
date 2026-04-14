package gatling.setup

import com.dimafeng.testcontainers.MariaDBContainer
import org.testcontainers.utility.{MountableFile, ThrowingFunction}

import java.io.{File, FileOutputStream, InputStream}
import java.nio.file.Files
import java.security.MessageDigest

/** Caches the fully-loaded Gatling fixture DB as a gzipped mysqldump.
 *
 *  Cache file: data/cache/gatling-{key}.sql.gz
 *  Cache key : SHA-256 of migration SQL content + CSV file sizes + user count (first 16 hex chars).
 *
 *  Usage in GatlingTestFixture.init():
 *    val key = GatlingDbCache.cacheKey(GatlingConfig)
 *    if (GatlingDbCache.exists(key)) GatlingDbCache.restore(container, key)
 *    else { GatlingDbSetup.load(...); GatlingDbCache.save(container, key) }
 */
object GatlingDbCache {

  private val cacheDir = new File("data/cache")

  // ── Cache key ──────────────────────────────────────────────────────────────

  def cacheKey(cfg: GatlingConfig.type): String = {
    val md = MessageDigest.getInstance("SHA-256")

    // Hash the full content of every migration SQL file (sorted by name).
    val migDir = new File("conf/db/migration/default")
    Option(migDir.listFiles()).getOrElse(Array.empty)
      .filter(_.getName.endsWith(".sql"))
      .sortBy(_.getName)
      .foreach(f => md.update(Files.readAllBytes(f.toPath)))

    // Hash CSV file sizes (large files; size change is a reliable proxy for content change).
    Seq("data/wlm-ua-monuments.csv", "data/wlm-UA-images-2025.csv").foreach { path =>
      val size = new File(path).length()
      md.update(java.nio.ByteBuffer.allocate(8).putLong(size).array())
    }

    // Hash fixture parameters (user count, fraction, max rate).
    md.update(s"${cfg.users}:${cfg.jurorFraction}:${cfg.maxRate}".getBytes("UTF-8"))

    md.digest().map("%02x".format(_)).mkString.take(16)
  }

  // ── Public API ─────────────────────────────────────────────────────────────

  def dumpFile(key: String): File = new File(cacheDir, s"gatling-$key.sql.gz")

  def exists(key: String): Boolean = dumpFile(key).exists()

  /** Restore data from cache. Call AFTER Flyway migrations (schema already exists). */
  def restore(container: MariaDBContainer, key: String): Unit = {
    val dump = dumpFile(key)
    println(s"[GatlingDbCache] Restoring from $dump …")
    container.container.copyFileToContainer(
      MountableFile.forHostPath(dump.getAbsolutePath),
      "/tmp/gatling-fixture.sql.gz"
    )
    val result = container.container.execInContainer("sh", "-c",
      s"zcat /tmp/gatling-fixture.sql.gz | mysql -u${container.username} " +
      s"-p${container.password} ${container.databaseName}"
    )
    if (result.getExitCode != 0)
      throw new RuntimeException(s"[GatlingDbCache] restore failed: ${result.getStderr}")

    // Refresh optimizer statistics after restore so queries use correct execution plans.
    val analyzeResult = container.container.execInContainer("sh", "-c",
      s"mysql -u${container.username} -p${container.password} ${container.databaseName}" +
      s" -e 'ANALYZE TABLE images, selection, monument, users, rounds, round_user;'"
    )
    if (analyzeResult.getExitCode != 0)
      println(s"[GatlingDbCache] ANALYZE TABLE warning: ${analyzeResult.getStderr}")

    println("[GatlingDbCache] Restore complete.")
  }

  /** Dump DB data (no schema) to cache file and delete stale caches. */
  def save(container: MariaDBContainer, key: String): Unit = {
    cacheDir.mkdirs()
    val dump = dumpFile(key)
    println("[GatlingDbCache] Saving dump …")
    val result = container.container.execInContainer("sh", "-c",
      s"MYSQL_PWD=${container.password} mysqldump" +
      s" -u${container.username}" +
      s" --no-create-info --single-transaction --skip-triggers --no-tablespaces --skip-lock-tables" +
      s" --ignore-table=${container.databaseName}.flyway_schema_history" +
      s" ${container.databaseName}" +
      s" | gzip > /tmp/gatling-fixture.sql.gz"
    )
    if (result.getExitCode != 0)
      throw new RuntimeException(s"[GatlingDbCache] dump failed: ${result.getStderr}")

    // Copy the gzipped dump from the container to the host.
    container.container.copyFileFromContainer(
      "/tmp/gatling-fixture.sql.gz",
      new ThrowingFunction[InputStream, java.lang.Void] {
        override def apply(stream: InputStream): java.lang.Void = {
          val out = new FileOutputStream(dump)
          try stream.transferTo(out) finally out.close()
          null
        }
      }
    )

    println(s"[GatlingDbCache] Saved to $dump (${dump.length() / 1024 / 1024} MB).")

    // Remove any stale dump files for old cache keys.
    Option(cacheDir.listFiles()).getOrElse(Array.empty)
      .filter(f => f.getName.startsWith("gatling-") && f.getName.endsWith(".sql.gz") && f != dump)
      .foreach { f => f.delete(); println(s"[GatlingDbCache] Deleted stale cache: ${f.getName}") }
  }
}
