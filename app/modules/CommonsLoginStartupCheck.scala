package modules

import play.api.Logger
import services.imports.CommonsLogin

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Checks the Commons login once when the app starts (bound eagerly in [[AppModule]]), so the
  * post-deploy look at `/health/ready` (its `commons` field, [[CommonsLogin.state]]) shows a
  * refused bot password at once, not only after the first import. It runs in the background:
  * neither the start nor readiness waits for it. It is an ordinary [[CommonsLogin.ensure]], so
  * the refused-login gap applies to it as to an import step. Without an account (the tests'
  * config) it asks nothing.
  */
@Singleton
class CommonsLoginStartupCheck @Inject() (login: CommonsLogin, ec: BlockingExecutionContext) {
  val done: Future[CommonsLogin.Status] = CommonsLoginStartupCheck.start(login)(ec)
}

object CommonsLoginStartupCheck {
  private val logger = Logger(classOf[CommonsLoginStartupCheck])

  def start(login: CommonsLogin)(implicit ec: ExecutionContext): Future[CommonsLogin.Status] =
    Future(login.ensure()).recover { case NonFatal(e) =>
      // ensure() logs its own failures; this only guards the future
      logger.warn(s"Commons login check at start failed: ${e.getClass.getSimpleName}")
      CommonsLogin.Status.Anonymous
    }
}
