package modules

import org.apache.pekko.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

import javax.inject.{Inject, Singleton}

/** The `blocking-dispatcher` thread pool (conf/application.conf) for actions that run
  * slow blocking JDBC work: the round stat and edit pages, round saving (which may
  * distribute images) and the galleries. Off Play's default dispatcher, they no longer
  * hold the few threads every other request (logins, votes) needs.
  */
@Singleton
class BlockingExecutionContext @Inject() (system: ActorSystem)
    extends CustomExecutionContext(system, "blocking-dispatcher")
