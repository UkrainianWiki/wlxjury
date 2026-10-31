package org.intracer.wmua

sealed abstract class CategoryOperation(
    val value: String,
    val labelKey: String,
    val requiresFirst: Boolean = true,
    val requiresSecond: Boolean = false
) {
  def formValue: String = value
  def storedValue: Option[String] = Option.unless(value == "none")(value)
}

object CategoryOperation {
  case object Unrestricted extends CategoryOperation("none", "category.none", requiresFirst = false)
  case object Include extends CategoryOperation("include", "category.include")
  case object Exclude extends CategoryOperation("exclude", "category.exclude")
  case object And extends CategoryOperation("and", "category.and", requiresSecond = true)
  case object Union extends CategoryOperation("or", "category.union", requiresSecond = true)
  case object Minus extends CategoryOperation("minus", "category.minus", requiresSecond = true)

  // A case object's constructor defaults can initialize this companion first.
  // Wait until that object has finished before retaining its instance in a table.
  lazy val values: Seq[CategoryOperation] = Seq(Unrestricted, Include, Exclude, And, Union, Minus)
  private lazy val legacy: Map[String, CategoryOperation] = Map("1" -> Include, "-1" -> Exclude, "0" -> Exclude)
  def fromForm(value: String): Option[CategoryOperation] =
    values.find(_.value == value).orElse(legacy.get(value).filter(_ => value != "0"))
  def fromStored(value: String): Option[CategoryOperation] =
    values.find(_.value == value).orElse(legacy.get(value))
}

/** Effective operands of the persisted category fields. No historical row is rewritten. */
case class CategoryRule(operation: CategoryOperation, first: Option[String], second: Option[String]) {
  import CategoryOperation._

  def validationErrors: Seq[String] = Seq(
    Option.when(operation.requiresFirst && first.isEmpty)("error.category.first.required"),
    Option.when(operation.requiresSecond && second.isEmpty)("error.category.second.required")
  ).flatten

  def requireValid(): Unit = require(validationErrors.isEmpty, validationErrors.mkString(", "))

  def accepts(pageId: Long, firstIds: Set[Long], secondIds: Set[Long]): Boolean = operation match {
    case Unrestricted => true
    case Include => firstIds.contains(pageId)
    case Exclude => !firstIds.contains(pageId)
    case And => firstIds.contains(pageId) && secondIds.contains(pageId)
    case Union => firstIds.contains(pageId) || secondIds.contains(pageId)
    case Minus => firstIds.contains(pageId) && !secondIds.contains(pageId)
  }
}

object CategoryRule {
  import CategoryOperation._

  def fromStored(first: Option[String], second: Option[String], clause: Option[String]): CategoryRule = {
    def clean(value: Option[String]) = value.map(_.trim).filter(_.nonEmpty)
    val a = clean(first)
    val b = clean(second)
    clause match {
      case Some(value) =>
        val operation = CategoryOperation.fromStored(value).getOrElse(
          throw new IllegalArgumentException(s"Unsupported category clause: $value"))
        CategoryRule(operation, Option.when(operation.requiresFirst)(a).flatten,
          Option.when(operation.requiresSecond)(b).flatten)
      case None => (a, b) match {
        case (Some(_), Some(_)) => CategoryRule(Minus, a, b)
        case (Some(_), None) => CategoryRule(Include, a, None)
        case (None, Some(_)) => CategoryRule(Exclude, b, None)
        case _ => CategoryRule(Unrestricted, None, None)
      }
    }
  }
}
