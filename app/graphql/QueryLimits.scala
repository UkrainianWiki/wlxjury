package graphql

import sangria.ast

/** The limits a query document is checked against before Sangria validates or runs it (plan §4.7),
  * read from `wlxjury.graphql` ([[GraphQLSettings]]). The complexity budget, which multiplies list
  * fields by their page size, is Sangria's reducer at execution ([[GraphQLController]]); these are
  * the checks on the document's shape, with fragments expanded:
  *
  *  - `maxDepth`: the nesting of fields, outside introspection;
  *  - introspection (`__schema`, `__type`): at most `maxIntrospectionDepth` deep, and its list
  *    fields that lead back to types (`fields`, `interfaces`, `possibleTypes`, `inputFields`)
  *    nested at most [[MaxIntrospectionListNesting]] deep, as graphql-js's
  *    `MaxIntrospectionDepthRule` (the standard introspection query nests them once);
  *  - `maxAliases`: aliased fields;
  *  - `maxFields`: field selections, each fragment spread counted again where it is spread, so
  *    that fragments spreading each other many times (a "fragment bomb") stop the walk early.
  */
object QueryLimits {

  final case class Limits(maxDepth: Int, maxIntrospectionDepth: Int, maxAliases: Int, maxFields: Int)

  /** The nesting of introspection's type-listing fields allowed (graphql-js allows 2). */
  val MaxIntrospectionListNesting = 2

  private val IntrospectionRoots = Set("__schema", "__type")
  private val IntrospectionLists = Set("fields", "interfaces", "possibleTypes", "inputFields")

  /** Why the document breaks a limit (the error's message key), if it does. */
  def check(document: ast.Document, limits: Limits): Option[String] = {
    val fragments = document.fragments
    var fields = 0
    var aliases = 0

    final class Stop(val key: String) extends RuntimeException(key, null, false, false)

    def walk(selections: Vector[ast.Selection], depth: Int, intro: Option[(Int, Int)], visiting: Set[String]): Unit =
      selections.foreach {
        case f: ast.Field =>
          fields += 1
          if (fields > limits.maxFields) throw new Stop("query_too_large")
          if (f.alias.isDefined) {
            aliases += 1
            if (aliases > limits.maxAliases) throw new Stop("too_many_aliases")
          }
          intro match {
            case None if IntrospectionRoots.contains(f.name) =>
              walk(f.selections, depth, Some((1, 0)), visiting)
            case None =>
              if (depth + 1 > limits.maxDepth) throw new Stop("query_too_deep")
              walk(f.selections, depth + 1, None, visiting)
            case Some((introDepth, lists)) =>
              val nested = if (IntrospectionLists.contains(f.name)) lists + 1 else lists
              if (introDepth + 1 > limits.maxIntrospectionDepth || nested > MaxIntrospectionListNesting)
                throw new Stop("introspection_too_deep")
              walk(f.selections, depth, Some((introDepth + 1, nested)), visiting)
          }
        case i: ast.InlineFragment => walk(i.selections, depth, intro, visiting)
        case s: ast.FragmentSpread =>
          // a cycle is Sangria's validation error (NoFragmentCycles); here it only ends the walk
          if (!visiting.contains(s.name))
            fragments.get(s.name).foreach(d => walk(d.selections, depth, intro, visiting + s.name))
      }

    try {
      document.definitions.foreach {
        case op: ast.OperationDefinition => walk(op.selections, 0, None, Set.empty)
        case _                           => ()
      }
      None
    } catch {
      case stop: Stop => Some(stop.key)
    }
  }
}
