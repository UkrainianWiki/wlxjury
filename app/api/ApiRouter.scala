package api

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter

import javax.inject.Inject

/** The REST API ([[Api]]), mounted at `/api` in `conf/routes`: Play strips the prefix, so the
  * endpoints' paths start below it (`/contests`, `/docs`).
  */
class ApiRouter @Inject() (api: Api) extends SimpleRouter {
  override def routes: Routes = api.routes
}
