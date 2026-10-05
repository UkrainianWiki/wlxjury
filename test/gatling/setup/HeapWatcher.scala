package gatling.setup

import com.sun.management.GarbageCollectionNotificationInfo

import java.io.{File, FileWriter, PrintWriter}
import java.lang.management.{ManagementFactory, MemoryType}
import java.time.Instant
import javax.management.openmbean.CompositeData
import javax.management.{Notification, NotificationEmitter, NotificationListener}
import scala.jdk.CollectionConverters._

/** Heap occupancy after each GC of this JVM, grouped into named steps.
  *
  * The simulations run the app in the Gatling JVM (TestServer), so this sees the app's
  * heap (plus Gatling's own). Heap after GC is the sum of the heap pools after a
  * collection: the live data plus whatever that collection didn't reclaim. "Full" counts
  * the collections MXBeans report as major ("end of major GC"), which on G1 are full GCs.
  */
object HeapWatcher {

  final case class StepMemory(
      name: String,
      millis: Long,
      gcs: Int,
      fullGcs: Int,
      maxAfterGcMb: Option[Long],
      usedAtEndMb: Long
  )

  private val heapPools: Set[String] =
    ManagementFactory.getMemoryPoolMXBeans.asScala
      .filter(_.getType == MemoryType.HEAP).map(_.getName).toSet

  private final class Current(val name: String, val start: Long) {
    var gcs = 0
    var fullGcs = 0
    var maxAfterGc: Option[Long] = None
  }

  @volatile private var current: Option[Current] = None

  private val listener = new NotificationListener {
    override def handleNotification(n: Notification, handback: Any): Unit =
      if (n.getType == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
        val info = GarbageCollectionNotificationInfo.from(n.getUserData.asInstanceOf[CompositeData])
        val after = info.getGcInfo.getMemoryUsageAfterGc.asScala.collect {
          case (pool, usage) if heapPools.contains(pool) => usage.getUsed
        }.sum
        HeapWatcher.synchronized {
          current.foreach { c =>
            c.gcs += 1
            if (info.getGcAction.contains("major")) c.fullGcs += 1
            c.maxAfterGc = Some(c.maxAfterGc.fold(after)(math.max(_, after)))
          }
        }
      }
  }

  ManagementFactory.getGarbageCollectorMXBeans.asScala.foreach {
    case e: NotificationEmitter => e.addNotificationListener(listener, null, null)
    case _                      =>
  }

  def maxHeapMb: Long = Runtime.getRuntime.maxMemory() / 1024 / 1024

  def start(name: String): Unit = synchronized {
    current = Some(new Current(name, System.currentTimeMillis()))
  }

  def end(): Option[StepMemory] = synchronized {
    val step = current.map { c =>
      val rt = Runtime.getRuntime
      StepMemory(c.name, System.currentTimeMillis() - c.start, c.gcs, c.fullGcs,
        c.maxAfterGc.map(_ / 1024 / 1024), (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024)
    }
    current = None
    step
  }

  /** Appends one line per step to target/gatling/distribution-memory.txt (and stdout). */
  def report(line: String): Unit = {
    val text = s"${Instant.now()} pid=${ProcessHandle.current().pid()} maxHeap=${maxHeapMb}MB $line"
    println(s"[distribution] $text")
    val f = new File("target/gatling/distribution-memory.txt")
    f.getParentFile.mkdirs()
    val w = new PrintWriter(new FileWriter(f, true))
    try w.println(text) finally w.close()
  }
}
