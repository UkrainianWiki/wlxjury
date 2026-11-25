package org.intracer.wmua

sealed abstract class CategoryOperation(
    val value: String,
    val requiresFirst: Boolean = true,
    val requiresSecond: Boolean = false
) {
  def formValue: String = value
  def storedValue: Option[String] = Option.unless(value == "none")(value)
}

object CategoryOperation {
  case object Unrestricted extends CategoryOperation("none", requiresFirst = false)
  case object Include extends CategoryOperation("include")
  case object Exclude extends CategoryOperation("exclude")
  case object And extends CategoryOperation("and", requiresSecond = true)
  case object Union extends CategoryOperation("or", requiresSecond = true)
  case object Minus extends CategoryOperation("minus", requiresSecond = true)

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
    def clean(value: Option[String]) = value.map(services.imports.WikiTitle.normalize).filter(_.nonEmpty)
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

/** What the round form's second category box does, chosen under "More category options": leave
  * its files out (the default), files in both categories, or files in either. `secondLabelKey` is
  * that box's label with the choice.
  */
sealed abstract class CategoryCombine(val value: String, val labelKey: String, val secondLabelKey: String) {
  def operation: CategoryOperation
}

object CategoryCombine {
  case object LeaveOut extends CategoryCombine("minus", "category.combine.minus", "category.leave.out") {
    def operation: CategoryOperation = CategoryOperation.Minus
  }
  case object InBoth extends CategoryCombine("and", "category.combine.and", "category.also.in") {
    def operation: CategoryOperation = CategoryOperation.And
  }
  case object InEither extends CategoryCombine("or", "category.combine.or", "category.or.in") {
    def operation: CategoryOperation = CategoryOperation.Union
  }

  lazy val values: Seq[CategoryCombine] = Seq(LeaveOut, InBoth, InEither)
  def Default: CategoryCombine = LeaveOut
  def parse(value: String): Option[CategoryCombine] = values.find(_.value == value)
}

/** The round form's two category boxes: "Only files in category" (`first`) and the second box,
  * which `combine` gives its meaning. Every [[CategoryRule]] has its boxes ([[CategoryBoxes.ofRule]])
  * and they give it back ([[rule]]): an exclusion's one category, stored as the rule's first
  * operand, is in the second box. Operands are cleaned titles (none empty).
  */
case class CategoryBoxes(first: Option[String], second: Option[String], combine: CategoryCombine) {
  import CategoryOperation._

  /** No filter with both boxes empty, whatever the choice. With "in both" or "in either", that
    * operation, which needs both boxes (the rule's validation says which is missing); otherwise
    * only the first category, without the second's files, or the first minus the second.
    */
  def rule: CategoryRule = (first, second) match {
    case (None, None)                             => CategoryRule(Unrestricted, None, None)
    case _ if combine != CategoryCombine.LeaveOut => CategoryRule(combine.operation, first, second)
    case (Some(_), None)                          => CategoryRule(Include, first, None)
    case (None, Some(_))                          => CategoryRule(Exclude, second, None)
    case (Some(_), Some(_))                       => CategoryRule(Minus, first, second)
  }
}

object CategoryBoxes {
  import CategoryOperation._

  def ofRule(rule: CategoryRule): CategoryBoxes = rule.operation match {
    case Unrestricted => CategoryBoxes(None, None, CategoryCombine.LeaveOut)
    case Include      => CategoryBoxes(rule.first, None, CategoryCombine.LeaveOut)
    case Exclude      => CategoryBoxes(None, rule.first, CategoryCombine.LeaveOut)
    case Minus        => CategoryBoxes(rule.first, rule.second, CategoryCombine.LeaveOut)
    case And          => CategoryBoxes(rule.first, rule.second, CategoryCombine.InBoth)
    case Union        => CategoryBoxes(rule.first, rule.second, CategoryCombine.InEither)
  }
}
