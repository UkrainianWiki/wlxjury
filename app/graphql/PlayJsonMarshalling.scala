package graphql

import play.api.libs.json._
import sangria.marshalling.{ArrayMapBuilder, InputUnmarshaller, ResultMarshaller, ScalarValueInfo}

/** Sangria's result and variable marshalling over Play's own play-json (Play 3's
  * `org.playframework` artifact). `sangria-play-json` would do it, but it depends on the old
  * `com.typesafe.play` play-json, the same classes under another artifact: two copies of
  * `play.api.libs.json` on the class path. Nothing else is needed from it.
  */
object PlayJsonMarshalling {

  /** The response's JSON. */
  implicit object JsonResultMarshaller extends ResultMarshaller {
    type Node = JsValue
    type MapBuilder = ArrayMapBuilder[JsValue]

    def emptyMapNode(keys: Seq[String]): MapBuilder = new ArrayMapBuilder[JsValue](keys)
    def addMapNodeElem(builder: MapBuilder, key: String, value: JsValue, optional: Boolean): MapBuilder =
      builder.add(key, value)
    def mapNode(builder: MapBuilder): JsValue = JsObject(builder.toSeq)
    def mapNode(keyValues: Seq[(String, JsValue)]): JsValue = JsObject(keyValues)
    def arrayNode(values: Vector[JsValue]): JsValue = JsArray(values)
    def optionalArrayNodeValue(value: Option[JsValue]): JsValue = value.getOrElse(JsNull)

    def scalarNode(value: Any, typeName: String, info: Set[ScalarValueInfo]): JsValue = value match {
      case v: String     => JsString(v)
      case v: Boolean    => JsBoolean(v)
      case v: Int        => JsNumber(BigDecimal(v))
      case v: Long       => JsNumber(BigDecimal(v))
      case v: Double     => JsNumber(BigDecimal(v))
      case v: Float      => JsNumber(BigDecimal(v.toDouble))
      case v: BigInt     => JsNumber(BigDecimal(v))
      case v: BigDecimal => JsNumber(v)
      // the schema's scalars are the built-in ones: nothing else reaches here
      case _ => throw new IllegalArgumentException(s"unsupported scalar of type $typeName")
    }

    def enumNode(value: String, typeName: String): JsValue = JsString(value)
    def nullNode: JsValue = JsNull
    def renderCompact(node: JsValue): String = Json.stringify(node)
    def renderPretty(node: JsValue): String = Json.prettyPrint(node)
  }

  /** The request's `variables` object. */
  implicit object JsonInputUnmarshaller extends InputUnmarshaller[JsValue] {
    def getRootMapValue(node: JsValue, key: String): Option[JsValue] = getMapValue(node, key)
    def isMapNode(node: JsValue): Boolean = node.isInstanceOf[JsObject]
    def getMapValue(node: JsValue, key: String): Option[JsValue] = node match {
      case o: JsObject => o.value.get(key)
      case _           => None
    }
    def getMapKeys(node: JsValue): Iterable[String] = node match {
      case o: JsObject => o.keys
      case _           => Nil
    }
    def isListNode(node: JsValue): Boolean = node.isInstanceOf[JsArray]
    def getListValue(node: JsValue): Seq[JsValue] = node match {
      case a: JsArray => a.value.toSeq
      case _          => Nil
    }
    def isDefined(node: JsValue): Boolean = node != JsNull
    def isScalarNode(node: JsValue): Boolean = node match {
      case _: JsBoolean | _: JsNumber | _: JsString => true
      case _                                        => false
    }
    def isEnumNode(node: JsValue): Boolean = node.isInstanceOf[JsString]
    def isVariableNode(node: JsValue): Boolean = false
    def getScalarValue(node: JsValue): Any = node match {
      case JsBoolean(b) => b
      case JsNumber(n)  => n.toBigIntExact.getOrElse(n)
      case JsString(s)  => s
      case _            => throw new IllegalArgumentException("not a scalar")
    }
    def getScalaScalarValue(node: JsValue): Any = getScalarValue(node)
    def getVariableName(node: JsValue): String = throw new IllegalArgumentException("no variables in variables")
    def render(node: JsValue): String = Json.stringify(node)
  }
}
