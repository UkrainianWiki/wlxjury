package gatling.setup

import com.sun.management.GarbageCollectionNotificationInfo
import com.zaxxer.hikari.HikariDataSource

import java.io.{File, PrintWriter}
import java.lang.management.ManagementFactory
import java.util.concurrent.{Executors, TimeUnit}
import javax.management.openmbean.CompositeData
import javax.management.{Notification, NotificationEmitter, NotificationListener}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** The app's DB pool and this JVM's GC pauses during a simulation (the app runs in the Gatling
  * JVM): every 100 ms the pool's active and idle connections and the threads waiting for one,
  * and every GC's pause. [[stop]] writes a summary next to the timing log
  * (docs/plans/2026-10-28-gallery-latency.md).
  */
object PoolWatcher {

  private val samples = mutable.ArrayBuffer.empty[(Long, Int, Int, Int)]
  private val pauses = mutable.ArrayBuffer.empty[(Long, String, String, Long)]
  @volatile private var running = false
  private var started = 0L

  private val listener = new NotificationListener {
    override def handleNotification(n: Notification, handback: Any): Unit =
      if (running && n.getType == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
        val info = GarbageCollectionNotificationInfo.from(n.getUserData.asInstanceOf[CompositeData])
        PoolWatcher.synchronized {
          pauses += ((System.currentTimeMillis(), info.getGcName, info.getGcAction, info.getGcInfo.getDuration))
        }
      }
  }

  private lazy val scheduler = Executors.newSingleThreadScheduledExecutor { r =>
    val t = new Thread(r, "pool-watcher"); t.setDaemon(true); t
  }

  def start(): Unit = synchronized {
    if (!running) {
      running = true
      started = System.currentTimeMillis()
      ManagementFactory.getGarbageCollectorMXBeans.asScala.foreach {
        case e: NotificationEmitter => e.addNotificationListener(listener, null, null)
        case _                      =>
      }
      val pool = scalikejdbc.ConnectionPool.get().dataSource.unwrap(classOf[HikariDataSource]).getHikariPoolMXBean
      scheduler.scheduleAtFixedRate(() => {
        val s = (System.currentTimeMillis(), pool.getActiveConnections, pool.getIdleConnections,
          pool.getThreadsAwaitingConnection)
        PoolWatcher.synchronized(samples += s)
      }, 0, 100, TimeUnit.MILLISECONDS)
    }
  }

  def stop(name: String): Unit = synchronized {
    if (running) {
      running = false
      scheduler.shutdownNow()
      def pct(xs: Seq[Int], p: Double): Int =
        if (xs.isEmpty) 0 else xs.sorted.apply(math.max(0, math.ceil(p * xs.size).toInt - 1))
      val active = samples.map(_._2).toSeq
      val waiting = samples.map(_._4).toSeq
      val seconds = (System.currentTimeMillis() - started) / 1000.0
      val gcMs = pauses.map(_._4)
      val text =
        f"$name: ${samples.size} samples over $seconds%.0f s; pool active p50=${pct(active, .5)} " +
          f"p95=${pct(active, .95)} max=${active.maxOption.getOrElse(0)}; threads waiting for a connection " +
          f"max=${waiting.maxOption.getOrElse(0)} (in ${waiting.count(_ > 0)} samples); " +
          f"GC: ${pauses.size} pauses, ${gcMs.sum} ms in all, max ${gcMs.maxOption.getOrElse(0L)} ms, " +
          f"${pauses.count(_._3.contains("major"))} major"
      println(s"[pool] $text")
      val f = new File(GatlingConfig.timingFile.stripSuffix(".jsonl") + "-pool.txt")
      f.getAbsoluteFile.getParentFile.mkdirs()
      val w = new PrintWriter(f, "UTF-8")
      try {
        w.println(text)
        w.println("# epochMs,active,idle,waiting")
        samples.foreach { case (t, a, i, wt) => w.println(s"$t,$a,$i,$wt") }
        w.println("# gc epochMs,name,action,ms")
        pauses.foreach { case (t, n, a, ms) => w.println(s"$t,$n,$a,$ms") }
      } finally w.close()
      samples.clear(); pauses.clear()
    }
  }
}
