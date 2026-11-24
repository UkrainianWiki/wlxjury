package graphql

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

/** Writes the served schema's SDL to `conf/graphql/schema.graphql`, the UI's contract, after a
  * schema change: `sbt "Test/runMain graphql.RenderSchema"`. `GraphQLSchemaSpec` fails until the
  * file matches; review the diff as the change to the contract.
  */
object RenderSchema {

  /** The standard introspection query (GraphiQL's, codegen's), as text. */
  lazy val IntrospectionQuery: String =
    sangria.renderer.QueryRenderer.renderPretty(sangria.introspection.introspectionQuery)

  def main(args: Array[String]): Unit = {
    Files.write(Paths.get(GraphQLSchema.SdlPath), GraphQLSchema.sdl.getBytes(UTF_8))
    println(s"wrote ${GraphQLSchema.SdlPath}")
  }
}
