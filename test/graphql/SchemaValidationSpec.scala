package graphql

import db.scalikejdbc.SharedTestDb
import munit.FunSuite
import sangria.parser.QueryParser

class SchemaValidationSpec extends FunSuite {

  test("schema.graphql parses without errors") {
    val sdl = scala.io.Source.fromResource("graphql/schema.graphql").mkString
    val result = QueryParser.parse(sdl)
    assert(result.isSuccess, result.failed.map(_.getMessage).getOrElse("parse failed"))
    assert(result.get.definitions.nonEmpty, "schema document has no definitions")
  }

  test("SchemaDefinition.schema builds without errors") {
    SharedTestDb.init()
    val s = graphql.SchemaDefinition.schema("test-secret")
    assert(s.allTypes.nonEmpty)
    assert(s.query.fieldsByName.contains("contests"))
    assert(s.mutation.exists(_.fieldsByName.contains("login")))
    assert(s.subscription.exists(_.fieldsByName.contains("imageRated")))
  }
}
