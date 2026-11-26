package modules

import play.api.Logger
import play.api.inject.ApplicationLifecycle
import services.imports.CommonsLogin

import java.util.concurrent.{ExecutorService, Executors}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Checks the Commons login once when the app starts (bound eagerly in [[AppModule]]), so the
  * post-deploy look at `/health/ready` (its `commons` field, [[CommonsLogin.state]]) shows a
  * refused bot password at once, not only after the first import. It runs in the background,
  * on a thread of its own (a hanging Commons may hold it for minutes, and the blocking pool is
  * the galleries'), stopped with the app: neither the start nor readiness waits for it. It is
  * an ordinary [[CommonsLogin.ensure]], so the refused-login gap applies to it as to an import
  * step. Without an account (the tests' config) it asks nothing. It runs in every mode with an
  * account, Dev included: `sbt run` with `COMMONS_USER` set logs in at start.
  */
@Singleton
class CommonsLoginStartupCheck @Inject() (login: CommonsLogin, lifecycle: ApplicationLifecycle) {
  private val executor: ExecutorService = Executors.newSingleThreadExecutor { (r: Runnable) =>
    val t = new Thread(r, "commons-login-start")
    t.setDaemon(true)
    t
  }
  lifecycle.addStopHook(() => Future.successful(executor.shutdownNow()).map(_ => ())(ExecutionContext.parasitic))

  val done: Future[CommonsLogin.Status] =
    CommonsLoginStartupCheck.start(() => login.ensure())(ExecutionContext.fromExecutorService(executor))
}

object CommonsLoginStartupCheck {
  private val logger = Logger(classOf[CommonsLoginStartupCheck])

  def start(ensure: () => CommonsLogin.Status)(implicit ec: ExecutionContext): Future[CommonsLogin.Status] =
    Future(ensure()).recover { case NonFatal(e) =>
      // ensure() logs its own failures; this only guards the future
      logger.warn(s"Commons login check at start failed: ${e.getClass.getSimpleName}")
      CommonsLogin.Status.Anonymous
    }
}
