# Round setup: juror rating choices

Move rating type and file score from the new round's file-selection step to its jurors step. Keep the existing round edit controls. Move criteria and worst-mark controls to the jurors step as well, so they remain configurable after choosing a rating. Save the choices atomically with distribution, validating them and preserving the restrictions on changing a rating after votes or use by a later round. Clear incompatible criteria and worst-mark settings when changing to an unrated type.

Checks: exercise the page and distribution, locked and invalid inputs; run the security guards and relevant round tests.

## Checks

- `testOnly controllers.RoundReviewJurorsReapplyPageSpec controllers.RoundEditPageSpec controllers.RoundCriteriaSpec`: 71 passed, 0 failed; `testOnly security.*`: 58 passed, 0 failed (`target/checks/20261128-094258-20672.log`).
- `round-form.test.js`: 21 passed; changelog fragments check passed.
- Security review: the existing scoped POST and CSRF token are retained; new form values are validated against enumerated ratings, score rules and criteria, and changes are checked and written under the round lock with the distribution job. No new SQL, raw HTML, logging, routes or permissions.

Review fixes: reject stale rating settings under the round lock; retain legacy criteria and inert worst marks; show the existing score-change warning; clear an incompatible worst mark on an unrated change, including without JavaScript.

Final review-fix validation: 77 round examples and 58 security examples passed (`target/checks/20261128-095214-26336.log`). The earlier full-suite run was stopped after the review changed its source snapshot; its JavaScript portion passed 567 tests.
