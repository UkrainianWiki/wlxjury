package modules

import org.apache.pekko.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import javax.inject.{Inject, Singleton}

/** The `sign-in-dispatcher` thread pool (conf/application.conf) for sign-in and sign-up,
  * which check or compute a bcrypt hash ([[services.PasswordHasher]]) and may wait for a
  * turn to do so: off Play's default dispatcher, a flood of sign-ins never holds the threads
  * every other request needs, nor the galleries' [[BlockingExecutionContext]].
  */
@Singleton
class SignInExecutionContext @Inject() (system: ActorSystem)
    extends CustomExecutionContext(system, "sign-in-dispatcher")
