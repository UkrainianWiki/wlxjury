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
  *  - `maxFields`: selections (fields, fragment spreads and inline fragments), each fragment
  *    counted again where it is spread, so that fragments spreading each other many times (a
  *    "fragment bomb", with or without fields) stop the walk early;
  *  - every definition is walked: only operations and fragments (`non_executable_definition`),
  *    each fragment name once (`duplicate_fragment`) and each fragment spread from the operation
  *    (`unused_fragment`), so that nothing reaches Sangria's validation unchecked.
  *
  * Before the document is parsed, [[nestingExceeds]] refuses brackets nested deeper than
  * [[MaxNesting]]: Sangria's parser recurses with no limit of its own, and a stack overflow is a
  * fatal error.
  */
object QueryLimits {

  final case class Limits(maxDepth: Int, maxIntrospectionDepth: Int, maxAliases: Int, maxFields: Int)

  /** The nesting of `{`, `[` and `(` a query may have before it is parsed: far over any real query
    * (`maxDepth` 8 is about 10 braces, a list argument a few more), far under what overflows the
    * parser's stack.
    */
  val MaxNesting = 64

  /** Whether the query's brackets nest deeper than `max`, outside strings, block strings and
    * comments: a linear scan, before any parse.
    */
  def nestingExceeds(query: String, max: Int): Boolean = {
    var depth = 0
    var i = 0
    val n = query.length
    var exceeded = false
    while (i < n && !exceeded) {
      val c = query.charAt(i)
      if (query.startsWith("\"\"\"", i)) {
        // a block string ends at the first triple quote without a backslash before it
        var end = query.indexOf("\"\"\"", i + 3)
        while (end > 0 && query.charAt(end - 1) == '\\') end = query.indexOf("\"\"\"", end + 3)
        i = if (end < 0) n else end + 3
      } else if (c == '"') {
        i += 1
        while (i < n && query.charAt(i) != '"' && query.charAt(i) != '\n') {
          if (query.charAt(i) == '\\') i += 1
          i += 1
        }
        i += 1
      } else if (c == '#') {
        while (i < n && query.charAt(i) != '\n' && query.charAt(i) != '\r') i += 1
      } else {
        if (c == '{' || c == '[' || c == '(') {
          depth += 1
          if (depth > max) exceeded = true
        } else if (c == '}' || c == ']' || c == ')') depth = math.max(0, depth - 1) // closers never give credit
        i += 1
      }
    }
    exceeded
  }

  /** The nesting of introspection's type-listing fields allowed (graphql-js allows 2). */
  val MaxIntrospectionListNesting = 2

  private val IntrospectionRoots = Set("__schema", "__type")
  private val IntrospectionLists = Set("fields", "interfaces", "possibleTypes", "inputFields")

  /** Why the document breaks a limit (the error's message key), if it does. */
  def check(document: ast.Document, limits: Limits): Option[String] = {
    val fragments = document.fragments
    var fields = 0 // selections walked
    var aliases = 0

    final class Stop(val key: String) extends RuntimeException(key, null, false, false)

    val reached = scala.collection.mutable.Set.empty[String]

    def walk(selections: Vector[ast.Selection], depth: Int, intro: Option[(Int, Int)], visiting: Set[String]): Unit =
      selections.foreach { selection =>
        fields += 1
        if (fields > limits.maxFields) throw new Stop("query_too_large")
        selection match {
        case f: ast.Field =>
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
          reached += s.name
          if (!visiting.contains(s.name))
            fragments.get(s.name).foreach(d => walk(d.selections, depth, intro, visiting + s.name))
        }
      }

    val fragmentNames = document.definitions.collect { case f: ast.FragmentDefinition => f.name }
    if (document.definitions.exists {
          case _: ast.OperationDefinition | _: ast.FragmentDefinition => false
          case _                                                      => true
        }) Some("non_executable_definition")
    else if (fragmentNames.distinct.sizeIs != fragmentNames.size) Some("duplicate_fragment")
    else
      try {
        document.definitions.foreach {
          case op: ast.OperationDefinition => walk(op.selections, 0, None, Set.empty)
          case _                           => ()
        }
        if (fragmentNames.exists(n => !reached.contains(n))) Some("unused_fragment") else None
      } catch {
        case stop: Stop => Some(stop.key)
      }
  }
}
