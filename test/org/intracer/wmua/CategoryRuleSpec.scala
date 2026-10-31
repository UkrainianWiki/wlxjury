package org.intracer.wmua

import org.specs2.mutable.Specification

class CategoryRuleSpec extends Specification {
  private val a = Set[Long](2, 4)
  private val b = Set[Long](3, 4)
  private def selected(rule: CategoryRule, first: Set[Long] = a, second: Set[Long] = b) =
    (1L to 4L).filter(rule.accepts(_, first, second)).toSet
  private def rule(value: String) = CategoryRule.fromStored(Some("A"), Some("B"), Some(value))

  "category rules" should {
    "implement all five membership truth tables" in {
      (selected(rule("include")) === Set[Long](2, 4)) and
        (selected(rule("exclude")) === Set[Long](1, 3)) and
        (selected(rule("and")) === Set[Long](4)) and
        (selected(rule("or")) === Set[Long](2, 3, 4)) and
        (selected(rule("minus")) === Set[Long](2))
    }

    "treat an empty category as an empty set" in {
      (selected(rule("include"), Set.empty, b) must beEmpty) and
        (selected(rule("exclude"), Set.empty, b) === Set[Long](1, 2, 3, 4)) and
        (selected(rule("and"), a, Set.empty) must beEmpty) and
        (selected(rule("or"), Set.empty, b) === b) and
        (selected(rule("minus"), a, Set.empty) === a)
    }

    "support disjoint and identical operands" in {
      (selected(rule("and"), Set(1L), Set(2L)) must beEmpty) and
        (selected(rule("or"), a, a) === a) and
        (selected(rule("minus"), a, a) must beEmpty)
    }

    "infer existing null-clause settings and normalize exclusion-only operands" in {
      val exclude = CategoryRule.fromStored(None, Some(" B "), None)
      (exclude === CategoryRule(CategoryOperation.Exclude, Some("B"), None)) and
        (CategoryRule.fromStored(Some("A"), None, None).operation === CategoryOperation.Include) and
        (CategoryRule.fromStored(Some("A"), Some("B"), None).operation === CategoryOperation.Minus) and
        (selected(CategoryRule.fromStored(None, None, None)) === Set[Long](1, 2, 3, 4)) and
        (rule("0").operation === CategoryOperation.Exclude) and
        (rule("-1").operation === CategoryOperation.Exclude) and
        (rule("1").operation === CategoryOperation.Include)
    }

    "reject unsupported stored clauses and invalid operand configurations" in {
      (CategoryRule.fromStored(Some("A"), None, Some("unexpected")) must throwA[IllegalArgumentException]) and
        (CategoryRule.fromStored(Some("A"), None, Some("and")).validationErrors === Seq("error.category.second.required")) and
        (CategoryRule.fromStored(Some("  "), None, Some("exclude")).validationErrors === Seq("error.category.first.required"))
    }

    "keep persisted and form identifiers unambiguous" in {
      (CategoryOperation.values.flatMap(_.storedValue).distinct.size === 5) and
        (CategoryOperation.fromForm("none") === Some(CategoryOperation.Unrestricted)) and
        (CategoryOperation.fromForm("0") must beNone) and
        (CategoryOperation.fromForm("999") must beNone)
    }
  }
}
