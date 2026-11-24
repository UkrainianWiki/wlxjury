package api

import graphql.GraphQLController
import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter

import javax.inject.Inject

/** The REST API ([[Api]]) and the GraphQL endpoint ([[GraphQLController]], `/graphql`), mounted at
  * `/api` in `conf/routes`: Play strips the prefix, so the endpoints' paths start below it
  * (`/contests`, `/docs`, `/graphql`). Both take the same callers ([[ApiAuth.caller]]).
  */
class ApiRouter @Inject() (api: Api, graphql: GraphQLController) extends SimpleRouter {
  override def routes: Routes = graphql.routes.orElse(api.routes)
}
