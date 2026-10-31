# Category operators

Approved in chat: retain single-category Include and Exclude and add AND, OR/union,
and MINUS. Both category fields are required for binary operations. Existing round
filters remain immutable; existing assignments and votes are not rewritten.

## Behavior

The form labels its inputs Category A and Category B and offers:

| Operation | Stored clause | Membership condition |
| --- | --- | --- |
| Include A | include | A |
| Exclude A | exclude | not A |
| AND | and | A and B |
| OR / union | or | A or B |
| MINUS | minus | A and not B |

No category filter is also available. It clears both names and stores no clause.
The operations only select from the existing eligible contest/previous-round images.
All other filters still apply, and top-N ranking follows category filtering.
A configured category with no members is an empty set, not an absent filter.

## Compatibility and approach

Keep category_clause, changing its storage to VARCHAR(16), as requested in chat.
V70 converts legacy 1 to include and -1/0 to exclude; unknown values remain detectable.
The typed rule also reads those numeric strings for compatibility with old callers.
Resolve historical NULL clauses:
A only means Include A; the existing second field alone means Exclude that category;
both mean MINUS; neither means unrestricted. Existing negative clauses exclude A again.
Category names and assignments/votes are not rewritten. New round submissions store
canonical named operators and operands; existing round edits still preserve stored data.

Reuse category and the new exclude_category storage from V69. The second field's old
internal/request name remains for compatibility, but the UI calls it Category B.
A shared, typed CategoryRule interprets those fields; both distribution paths and count
previews use it. Explicit clauses control their operands; unary modes ignore/clear B.
New requests validate known operations and required operands. Older requests without
the operator retain the NULL-clause inference above. An unknown persisted operator
fails rather than silently distributing unrestricted images.
V70 is deployed with this build; older binaries expect an integer clause.

Alternatives: moving old category names would obscure their original operands;
changing category-name column/request names is not needed and would break old
callers. Keeping compatibility in the shared rule avoids both costs.

## Validation

Exercise each operator through database reload, creation, count preview, later imports,
redistribution, idempotence and vote preservation. Include historical 1/-1/NULL rows,
empty/disjoint/identical categories, overlap and out-of-source membership, and other
filters/top-N. Test form validation and immutable edits, and the operator UI behavior.
Run focused Scala and Jest suites before a full-suite release check. Keep full logs on
disk and use scripts/agent-check.py summaries. Request an independent review follow-up.
