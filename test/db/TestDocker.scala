package db

import org.testcontainers.DockerClientFactory

import java.util.concurrent.{ExecutionException, Executors, TimeUnit, TimeoutException}
import scala.concurrent.duration._

/** Docker for the specs that start containers: they fail at once with what to do when
  * Docker doesn't answer (stopped, or hung, as Docker Desktop sometimes is), instead of
  * hanging the test run. The ping is asked once per JVM; its failure is kept, so the
  * other specs fail at once too.
  */
object TestDocker {

  /** How long Docker gets to answer: `-Dwlxjury.test.docker-timeout=<seconds>`. */
  val PingTimeout: FiniteDuration = seconds("wlxjury.test.docker-timeout", 30)

  /** How long a container gets to start, pulling its image the first time:
    * `-Dwlxjury.test.container-start-timeout=<seconds>`.
    */
  val StartTimeout: FiniteDuration = seconds("wlxjury.test.container-start-timeout", 600)

  private lazy val answer: Either[String, Unit] =
    try Right(within(PingTimeout, "answer")(DockerClientFactory.instance().client().pingCmd().exec(): Unit))
    catch { case e: IllegalStateException => Left(e.getMessage) }

  /** Fails unless Docker answers within [[PingTimeout]]. */
  def require(): Unit = answer.fold(message => throw new IllegalStateException(message), identity)

  /** Starts `what` (a container) once Docker answers, failing after [[StartTimeout]]. */
  def start(what: String)(start: => Unit): Unit = {
    require()
    within(StartTimeout, s"start $what")(start)
  }

  /** `body` on a thread of its own, failing after `timeout`: a call into a Docker that
    * doesn't answer blocks for good, so the thread is left to it (a daemon thread, which
    * doesn't keep the JVM from exiting).
    */
  private def within[T](timeout: FiniteDuration, what: String)(body: => T): T = {
    val executor = Executors.newSingleThreadExecutor { r =>
      val thread = new Thread(r, "test-docker")
      thread.setDaemon(true)
      thread
    }
    val result = executor.submit(() => body)
    executor.shutdown()
    try result.get(timeout.toMillis, TimeUnit.MILLISECONDS)
    catch {
      case _: TimeoutException =>
        result.cancel(true)
        throw new IllegalStateException(
          s"Docker didn't $what within $timeout: start or restart Docker (Docker Desktop on Windows and macOS)")
      case e: ExecutionException =>
        throw new IllegalStateException(s"Docker couldn't $what: ${e.getCause}", e.getCause)
    }
  }

  private def seconds(property: String, default: Int): FiniteDuration =
    sys.props.get(property).flatMap(_.toIntOption).getOrElse(default).seconds
}
