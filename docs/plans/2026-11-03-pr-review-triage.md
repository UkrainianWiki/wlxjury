# PR review triage: CodeRabbit comments on the stack (2026-11-03)

This document collects review comments on the stacked PRs (#184 to #237, see
[the PR series](2026-11-03-pr-series.md)) that are valid, are not fixed later in
the stack, and are not covered by an existing plan. Replies on GitHub link to the
entries here.

Each comment gets one of four classes:

1. **Fixed later in the stack**: the reply names the first later PR whose tip no
   longer has the problem.
2. **Already planned**: the reply links the plan section that covers it.
3. **Not fixed and not planned**: an entry below, with the plan or batch that
   should take it.
4. **Not applicable or a false positive**: the reply gives the reason.

## Open entries

None yet.

Entry format: `### <PR>-<n>: <short title>`, then PR, file:line, the issue, its
severity, and where it goes (append after an existing plan, change an existing
plan's batch, or fold into in-flight work: the robustness batch, the OAuth port,
CI or the deploy-adoption fix).

## Reviewed PRs

### PR 184 (01, transient revert)

CodeRabbit reviewed `c48cd68c`: 8 inline comments and 3 review-level remarks.
All 11 are changes this PR backs out on purpose (the five 2026-04-22 review fixes
on GitHub master) or tests for them, so none needs an entry here:

| Comment | Class | Where it is fixed |
|---|---|---|
| `EditRound.scala:97` special nomination contest and year check | 1 | #196 (`07d02180`) |
| `LoginController.scala:59` `no-store` on invalid sign-in | 1 | #196 (`07d02180`) |
| `LoginController.scala:75` `no-store` on sign-up | 1 | #196 (`07d02180`) |
| `Tools.scala:23` MySQL driver class (and `ConnectDb`) | 1 | #196 (`07d02180`) |
| `ContestService.scala:70` campaign not passed to `create` | 1 | #196 (`07d02180`) |
| `conf/routes:13` `+ nocsrf` on `POST /auth` | 1 | #196 (`07d02180`) |
| `ContestSpec.scala:16` live Commons bot | 1 | #196 (`07d02180`) |
| `ContestSpec.scala:30` expected contest without campaign | 1 | #196 (`07d02180`) |
| `Api.scala:37` `getContest` 404 (review-level) | 1 | #196 (`07d02180`) |
| `ApiSpec.scala:17` routed unknown-id and PUT tests (review-level) | 1 | #218 (`60c86a35`) |
| `conf/routes:13` routed tokenless `POST /auth` test (review-level) | 1 | #231 (`19815fa9`) |
