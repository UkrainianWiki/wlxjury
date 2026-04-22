package api

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.server.Directives.concat
import org.intracer.wmua.ContestJury
import services.ContestService
import sttp.model.StatusCode
import sttp.tapir.generic.auto.schemaForCaseClass
import sttp.tapir.json.play._
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.{endpoint, path, statusCode}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class Api @Inject()(val contestService: ContestService, actorSystem: ActorSystem) extends JsonFormat {
  private implicit val blockingDispatcher: ExecutionContext =
    actorSystem.dispatchers.lookup("blocking-dispatcher")

  private val api = endpoint.in("api")

  private val contests = api.in("contests")
  private val createContest = contests.post.in(jsonBody[ContestJury]).out(jsonBody[ContestJury])
  private val getContest = contests.in(path[Long]).get
    .errorOut(statusCode(StatusCode.NotFound))
    .out(jsonBody[ContestJury])
  private val updateContest = contests.put.in(jsonBody[ContestJury])
    .errorOut(statusCode(StatusCode.BadRequest))
  private val listContests = contests.get.out(jsonBody[List[ContestJury]])

  private val endpoints = List(createContest, getContest, updateContest, listContests)

  private val swaggerEndpoints = SwaggerInterpreter().fromEndpoints[Future](endpoints, "WLX Jury", "1.0")

  private val swaggerRoute = PekkoHttpServerInterpreter().toRoute(swaggerEndpoints)

  private val apiRoutes = PekkoHttpServerInterpreter().toRoute(List(
    createContest.serverLogicSuccess { contest =>
      Future(contestService.createContest(contest))
    },
    getContest.serverLogic { id =>
      Future(contestService.getContest(id).toRight(()))
    },
    updateContest.serverLogic { contest =>
      contest.id match {
        case Some(_) => Future(contestService.updateContest(contest)).map(Right(_))
        case None    => Future.successful(Left(()))
      }
    },
    listContests.serverLogicSuccess { _ =>
      Future(contestService.findContests())
    }
  ))

  val routes = concat(swaggerRoute, apiRoutes)
}
