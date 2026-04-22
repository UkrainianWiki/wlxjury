package api

import org.intracer.wmua.ContestJury
import services.ContestService
import sttp.tapir.generic.auto.schemaForCaseClass
import sttp.tapir.json.play._
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.model.StatusCode
import sttp.tapir.{endpoint, path, statusCode}

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Api @Inject()(val contestService: ContestService) extends JsonFormat {
  private val api = endpoint.in("api")

  private val contests = api.in("contests")
  private val createContest = contests.post.in(jsonBody[ContestJury]).out(jsonBody[ContestJury])
  private val getContest = contests.in(path[Long]).get
    .errorOut(statusCode(StatusCode.NotFound))
    .out(jsonBody[ContestJury])
  private val updateContest = contests.put.in(jsonBody[ContestJury])
  private val listContests = contests.get.out(jsonBody[List[ContestJury]])

  private val endpoints = List(createContest, getContest, updateContest, listContests)

  // first interpret as swagger ui endpoints, backend by the appropriate yaml
  private val swaggerEndpoints = SwaggerInterpreter().fromEndpoints[Future](endpoints, "WLX Jury", "1.0")

  // add to your akka routes
  private val swaggerRoute = PekkoHttpServerInterpreter().toRoute(swaggerEndpoints)

  private[api] val routes = PekkoHttpServerInterpreter().toRoute(List(
    createContest.serverLogicSuccess { contest =>
      Future(contestService.createContest(contest))
    },
    getContest.serverLogic { id =>
      Future(contestService.getContest(id).toRight(()))
    },
    updateContest.serverLogicSuccess { contest =>
      Future(contestService.updateContest(contest))
    },
    listContests.serverLogicSuccess { _ =>
      Future(contestService.findContests())
    }
  ))
}
