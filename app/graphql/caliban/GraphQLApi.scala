package graphql.caliban

import caliban._
import caliban.schema.Schema.auto._
import org.intracer.wmua.ContestJury

object GraphQLApi {
  case class ContestId(id: String)
  case class Queries(contests: List[ContestJury], contest: ContestId => Option[ContestJury])

  def getContests: List[ContestJury] = Nil
  def getContest(id: String): Option[ContestJury] = None

  val queries = Queries(getContests, args => getContest(args.id))

  val api = graphQL(RootResolver(queries))

  def main(args: Array[String]): Unit = {
    println(api.render)
  }

}
