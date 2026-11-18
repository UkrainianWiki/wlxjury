package gatling.setup

import db.scalikejdbc.{ContestJuryJdbc, CountryFilesCache, ImageJdbc, User}
import scalikejdbc._
import services.diagnostics.ContestHealth
import services.imports.ContestCountries

import java.io.{File, PrintWriter}
import java.time.LocalDateTime

/** Times the contest health report's H2 read (support diagnostics plan, D-1a): the contest's files'
  * links to its country categories ([[ImageJdbc.categoryCountryLinks]], the `category_members` self
  * join) and their countries, on the Gatling fixture DB (38,367 files in the contest's category),
  * then the whole report. The fixture's contest is of one country (WLM 2025 in Ukraine), whose
  * imports store no country links: in this run's container only, it is made international (its
  * country emptied) and 95% of its files are put in ten country categories of its prefix, so the
  * join reads what an international contest's has. Each timed run starts with the country cache
  * cleared (cold, as the first view after an import). SQL logging off.
  *
  * Not a spec: timings in CI are noise. Run alone (nothing else heavy running):
  * {{{
  * sbt "Test/runMain gatling.setup.ContestHealthBenchmark"
  * sbt -Dbench.fanout=10 "Test/runMain gatling.setup.ContestHealthBenchmark"   # 10 more categories per file
  * }}}
  * Writes target/gatling/contest-health-<timestamp>.txt.
  */
object ContestHealthBenchmark {

  private val warmup = sys.props.getOrElse("bench.warmup", "3").toInt
  private val runs = sys.props.getOrElse("bench.runs", "20").toInt

  def main(args: Array[String]): Unit = {
    val status = try { body(); 0 } catch { case e: Throwable => e.printStackTrace(); 1 }
    sys.exit(status)
  }

  private def ms[T](f: => T): (T, Double) = {
    val t0 = System.nanoTime()
    val r = f
    r -> (System.nanoTime() - t0) / 1e6
  }

  private def pct(xs: Seq[Double], p: Double): Double = {
    val s = xs.sorted
    s(math.min(s.size - 1, math.ceil(p * s.size).toInt - 1).max(0))
  }

  private def body(): Unit = {
    require(!GatlingConfig.external, "ContestHealthBenchmark changes its data: use the disposable fixture")
    val f = GatlingTestFixture
    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = false)
    implicit val session: DBSession = AutoSession

    // made international, in this run's container only
    sql"UPDATE contest_jury SET country = '', campaign = NULL WHERE id = ${f.contestId}".update()
    val contest = ContestJuryJdbc.findById(f.contestId).get
    require(ContestCountries.ofManyCountries(contest), s"${contest.name} ${contest.year} is still of one country")
    val categoryId = contest.categoryId.get
    val prefix = ContestCountries.prefixes(contest).head
    val countries = (1 to 10).map(i => s"${prefix}Country $i")
    countries.foreach(t => sql"INSERT IGNORE INTO category (title) VALUES ($t)".update())
    val ids = countries.map(t => sql"SELECT id FROM category WHERE title = $t".map(_.long(1)).single().get)
    ids.zipWithIndex.foreach { case (id, i) =>
      sql"""INSERT IGNORE INTO category_members (category_id, page_id)
            SELECT $id, page_id FROM category_members WHERE category_id = $categoryId
            AND MOD(page_id, 20) <> 0 AND MOD(page_id, 10) = $i""".update()
    }
    // bench.fanout: each file in that many more categories (production's files are in several:
    // other contests', their own country's), which the join reads past
    val fanout = sys.props.getOrElse("bench.fanout", "0").toInt
    (1 to fanout).foreach { k =>
      val title = s"Category:Bench extra $k"
      sql"INSERT IGNORE INTO category (title) VALUES ($title)".update()
      val extra = sql"SELECT id FROM category WHERE title = $title".map(_.long(1)).single().get
      sql"""INSERT IGNORE INTO category_members (category_id, page_id)
            SELECT $extra, page_id FROM category_members WHERE category_id = $categoryId""".update()
    }
    val files = sql"SELECT COUNT(*) FROM category_members WHERE category_id = $categoryId".map(_.long(1)).single().get
    val linked = sql"SELECT COUNT(*) FROM category_members WHERE category_id IN ($ids)".map(_.long(1)).single().get
    val root = User.findByEmail("root@ui-shots.test").headOption.getOrElse(
      User.create(User("Bench root", "bench-root@example.org", None, Set(User.ROOT_ROLE), isRoot = true)))
    val prefixes = ContestCountries.prefixes(contest)

    def timed(name: String)(f: => Any): (String, Seq[Double]) = {
      (1 to warmup).foreach { _ => CountryFilesCache.clear(); f }
      name -> (1 to runs).map { _ => CountryFilesCache.clear(); ms(f)._2 }
    }
    val results = Seq(
      timed("categoryCountryLinks (the join)")(ImageJdbc.categoryCountryLinks(categoryId, prefixes).size),
      timed("H2: links + fileCountries")(ContestHealth.countries(contest, files, files)),
      timed("the whole report (cold country cache)")(ContestHealth.report(contest, root)),
      { // the cache warm: what a second view within 10 minutes costs
        ContestHealth.report(contest, root)
        "the whole report (warm country cache)" -> (1 to runs).map(_ => ms(ContestHealth.report(contest, root))._2)
      }
    )
    val h2 = ContestHealth.countries(contest, files, files).get
    val version = sql"SELECT VERSION()".map(_.string(1)).single().get

    val out = new StringBuilder
    def say(line: String): Unit = { println(s"[health] $line"); out ++= line + "\n" }
    say(s"ContestHealthBenchmark ${LocalDateTime.now}: MariaDB $version; $files files in category $categoryId, " +
      s"$linked country links in ${ids.size} country categories, $fanout more categories per file; H2: ${h2.byCountry.map(_._2).sum} in a country, " +
      s"${h2.none} in none; warmup $warmup, runs $runs, SQL logging off")
    say(f"${"p50"}%8s ${"p95"}%8s ${"max"}%8s  ms, query")
    results.foreach { case (name, xs) => say(f"${pct(xs, 0.5)}%8.1f ${pct(xs, 0.95)}%8.1f ${xs.max}%8.1f  $name") }
    val dir = new File("target/gatling")
    dir.mkdirs()
    val file = new File(dir, s"contest-health-${LocalDateTime.now.toString.replace(':', '-')}.txt")
    val w = new PrintWriter(file, "UTF-8")
    try w.print(out) finally w.close()
    println(s"[health] wrote ${file.getPath}")
    GatlingFixtureLifecycle.stop()
  }
}
