# Category operators implementation plan

**Goal:** one persisted category rule works for legacy rounds and all new operations.
**Architecture:** a typed rule resolves category/category_clause/exclude_category, then
one predicate feeds both distribution paths. The form adds the operator outside its
22-field mapping and validates the operands. V70 changes the clause to named text;
category names and assignment rows are unchanged.
**Tech stack:** Scala 2.13, Play/Twirl, ScalikeJDBC, specs2, jQuery/Jest, MariaDB.
**Spec:** 2026-10-31-category-operators-design.md (approved behavior in chat).
**Execution:** inline implementation, with an independent review at the end.

## Tasks

- [x] Write CategoryClauseSpec using raw persisted clause values and legacy NULL cases;
  observe failures before implementation. Include an empty category regression.
- [x] Add CategoryRule/CategoryOperation and Round.categoryRule; use its operands and
  predicate in background and in-memory distribution. Validate before new-round insert.
- [x] Migrate category_clause to VARCHAR(16), converting legacy 1/-1/0 meanings and
  preserving NULL and unknown values. Test upgrading actual populated integer storage,
  idempotence, old caller compatibility, and canonical named values on new submissions.
- [x] Add CategoryRuleSpec for set truth tables, empty sets, unsupported operators,
  required operands and implicit legacy rules. Verify affected distribution specs.
- [x] Extend EditRound's outer mapping with the optional operator and validation;
  resolve legacy form display, normalize blank category names, add form/controller tests.
- [x] Render neutral Category A/B and a read-only-on-edit selector; add translated labels
  and validation messages. Add small JS operand visibility/required-state behavior and tests.
- [x] Compile changed sources/tests first; run affected Scala and Jest suites through
  agent-check.py. Run the full application suite once for the final shared-code change.
- [x] Commit, request an independent review follow-up, address material findings with
  regression tests, and fast-forward the original fix branch only if its checkout is clean.

## Review focus

Empty category results must never turn into no filter. OR must not escape the eligible
source or bypass other filters. Legacy exclusion-only names must be fetched as operand A.
Read-only edits must display normalized legacy rules but preserve stored original values.
Malformed operators must be rejected before creating a round. The second category is
required for binary operations and is ignored/cleared for unary operations.

## Execution ledger

Ruling: preserve existing internal column and request names for the second category;
the neutral UI and typed rule supply its new meaning. This avoids a schema/API rename;
the cost is that the old excludeCategory name remains in storage-facing code.
Ruling: use named text operators after the user's clarification; keep legacy numeric
strings readable, but store canonical names for new submissions. This adds V70 rather
than changing existing migration checksums.
Ruling: normalize legacy operation display on failed forms as well as successful fills;
otherwise an unrelated validation error could change the selected rule on resubmission.

Validation so far: the initial operator matrix failed on legacy exclusion, AND/OR, and
empty categories; the shared implementation then passed 28 tests. The text-column test
failed with int rather than varchar before V70. After named storage and form binding,
80 affected Scala tests passed, and all 100 Jest tests passed. Two additional failed-form
redisplay tests reproduced missing selected operations before the display normalization.
The redisplay fix then passed 35 related tests. Latest per-suite results total 90
affected Scala examples across nine suites, plus 100 Jest tests across seven suites.
Final validation of code commit 95093df2: Test/compile and the full application suite
passed with exit 0 in 962 seconds. Scala: 1,041 passed, 6 skipped, 1 pending, no failures
or errors (sbt Total 1,047). Jest: 100 passed across seven suites. Full output was
captured by agent-check.py; only bounded summaries were read. A hash-verified log and
fresh XML report archive are preserved in the original checkout:
target/checks/category-operators/20261031-full-suite-95093df2.log and
target/checks/category-operators/20261031-full-suite-95093df2-test-reports.zip.

Independent read-only review of all 23 changed files in 95093df2 against 58efaeda
completed with no actionable findings. It covered migration safety, legacy rules,
form redisplay, normalization, immutable edits, both distribution paths, empty sets,
eligibility, ranking, and persisted-job compatibility. The reviewer did not rerun tests.
The original fix branch receives these commits by fast-forward after checking its
branch, expected head, and tracked working-tree state; the managed checkout is retained.
