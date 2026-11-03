# Docs consolidation: practices, plans and history (2026-11-03)

Status: **implemented** on `ukwikidev/docs-consolidation` (from `ukwikidev/followups`
`98a8a79e`). The user accepted all seven recommendations (§7) on 2026-11-03; what was done is
in "Implementation" below. §1–§5 are the proposal as written, kept for its reasoning; file paths
there are the old ones (the mapping is [docs/history/README.md](../history/README.md)).

## Implementation (2026-11-03)

- **Deleted 44 files:** the 41 of §1, the two deployment tooling records (their conclusion is
  `docs/deployment.md` "Why it works this way") and the security practices plan (its guards are
  secure-coding §21). Each is listed with its last commit in `docs/history/README.md`.
- **Moved 10 files** to `docs/history/` (12 proposed, minus the two tooling records), each with a
  status header; `db-performance-analysis.md` notes its pre-V82 table names. Incoming links in
  docs and in code comments repointed: to a practices section, a history file or
  `98a8a79e:<path>`. One citation can't move: the comment in the applied migration
  `V55_1__Convert_to_InnoDB.sql` (Flyway checksum).
- **Trimmed:** open-followups (2312 → 93 lines) and security-resolution (1095 → 90) to their
  open items, citing the commits the batches landed in; their pre-deploy items, release 1's
  V71–V81 included, moved to the roadmap's "Before any production deploy".
- **Folded:** db-performance-analysis's leftovers into db §11 V11; long work as jobs into db
  §5.5a; contest switching by page GETs only into secure-coding §9; the planned guards into
  secure-coding §21.
- **New:** `docs/performance/practices.md` and `review-checklist.md` (load tests, benchmarks,
  optimization, memory sizing); db practices §4 moved there and is a pointer. CLAUDE.md got one
  pointer line and one repointed link.
- **Contradictions:** C6 (roadmap row: DB practices merged as `1ea5cb74`) and C7 (the triage
  doc cited on `origin/stack/series-doc`) fixed.
- **Held for the testing doc:** messages in all four files (§6); written in testing practices
  §6.4 (docs phase).

## 1. Inventory

84 files: 81 under `docs/`, plus `CLAUDE.md`, `AGENTS.md`, `README.md` (`bench/README.md` is a
runbook outside `docs/`, kept). Sizes are lines; links are referring files (other docs /
code, scripts and config / commit messages), counted by a script over tracked files.

| Class | Files |
|---|---|
| Practice / reference (kept current) | 11 |
| Active plan (work still to do, incl. the 4 CI drafts) | 20 |
| Done plan or results record | 18 |
| Investigation or review record | 11 |
| Superseded or stale (early drafts, pointers) | 24 |

Actions: **keep** (reference or active plan), **fold → delete** (lasting rules moved into a
practices doc, the rest is task detail), **delete** (nothing lasting, or already summarised
elsewhere), **history** (a record still useful: reasons for decisions in force, runbooks and
rollback SQL for migrations not yet on production, cited baselines), **trim** (an active plan
that carries done-batch logs).

### Practice / reference: keep

| File | Lines | Links d/c/m | Notes |
|---|---|---|---|
| `CLAUDE.md` | 213 | 22/3/13 | Agent rules, commands, architecture. Testing sections feed the testing doc (§6). |
| `AGENTS.md` | 3 | 2/0/1 | Pointer for Codex. |
| `README.md` | 21 | 2/0/7 | Links three security records proposed for delete: fix in the same commit (§5). |
| `docs/api.md` | 73 | 3/0/0 | REST API reference. |
| `docs/deployment.md` | 446 | 22/10/2 | Deploy and recovery runbook. |
| `docs/oauth.md` | 90 | 3/5/2 | OAuth sign-in reference. |
| `docs/db/practices.md`, `docs/db/review-checklist.md` | 511, 74 | 2/2/1, 2/0/1 | Target. |
| `docs/security/secure-coding.md`, `review-checklist.md`, `register.md` | 461, 103, 84 | 13/2/3, 6/0/0, 5/0/0 | Target. |

### Active plans: keep in `docs/plans/`

| File | Lines | Links | State / action |
|---|---|---|---|
| `2026-11-02-roadmap.md` | 212 | 3/0/0 | The index of open work. Keep; its "Database access practices … not merged" row is stale (merged on followups). |
| `2026-11-01-open-followups.md` | 2312 | 7/6/3 | Open items, but most of its length is merge and results logs of B1–B12, S*, C1 (e.g. "Merged ukwikidev/api-auth", "Full suite after …"). **Trim**: keep open items and the invariants (now in practices), delete the logs (findable by their merge commits). |
| `2026-11-02-round-images-two-step.md` | 1041 | 5/9/0 | P1b–P3 open; V84 deploy note and rollback SQL until on production. Keep. |
| `2026-11-02-criteria-rounds.md` | 702 | 1/1/0 | C3, C4 open; V83 pre-deploy queries. Keep. |
| `2026-11-02-montage-rounds-parity.md` | 110 | 3/0/0 | Keep. |
| `2026-11-03-account-join.md` | 95 | 2/0/1 | Deferred plan. Keep. |
| `2026-11-03-ci-and-scalawiki-packages.md` + `ci-drafts/**` (4) | 385 + 280 | 5/3/0 | scalawiki part open. Keep; the parallel CI agent may edit it. |
| `2026-11-03-write-path-simplification.md` | 966 | 3/14/0 | Decisions W1–W10 in force, robustness batch; 14 code links. Keep (later: history once the batch lands). |
| `2026-10-19-mariadb-10.11.md` | 369 | 5/1/0 | Server move pending. Keep. |
| `2026-11-03-flyway.md` | 287 | 5/8/2 | Flyway 13 pre-deploy checks and rollback (§5) until on production. Keep, then history (§1 explains why no `B` files: a decision in force). |
| `2026-11-02-security-resolution.md` | 1095 | 4/0/0 | S4/S8 (infra) open. Keep; trim the batch logs later as for open-followups. |
| `2026-11-02-security-practices.md` | 191 | 3/1/0 | Planned guards G3, G7, G10, G10b, G11, G12, G14 that secure-coding names. Keep until they land, or move the guard list into secure-coding's header and delete. |
| `2026-11-03-oauth-login-port.md` | 265 | 4/1/4 | Open items, linked from `oauth.md`. Keep. |
| `2026-10-30-logging-config-in-releases.md` | 69 | 1/0/0 | Not started. Keep. |
| `2026-10-29-import-top-of-another-contests-round.md` | 86 | 0/0/0 | Backlog ("Left out as new features"). Keep while wanted. |
| `2026-10-22-deleted-renamed-files.md` | 107 | 0/0/0 | Thumbnail check done; the `refresh` mode §2, §4 is backlog. Keep while wanted, else delete. |

### Done plans and results records

| File | Lines | Links | Action |
|---|---|---|---|
| `2026-10-31-theme-modes-and-design-system.md` | 1078 | 1/2/2 | **Fold → delete** (folded: secure-coding §5, §6; testing rule to the testing doc). |
| `2026-11-01-api-auth.md` | 288 | 3/0/0 | **Fold → delete** (secure-coding §9, §11; `api.md` is the reference). |
| `2026-10-27-error-handling.md` | 115 | 2/0/1 | **Fold → delete** (secure-coding §9, §12; CLAUDE.md). |
| `2026-10-28-gallery-latency.md` | 280 | 0/4/2 | **Fold → delete** (db §4, §9.3). |
| `docs/history/2026-10-07-prod-like-benchmark.md` | 464 | 4/4/1 | Folded (db §4.3, §4.7). **History**: baselines cited by `bench/README.md` and code, MariaDB config and archiving decisions. |
| `2026-10-05-performance-and-memory.md` | 1126 | 1/1/0 | **Delete** (done; its open item, long round creations, was solved by the background jobs; db practices hold the rules). |
| `2026-10-07-background-distribution.md` | 227 | 1/0/0 | **Delete** (CLAUDE.md "Background jobs" is the reference). |
| `2026-10-17-background-import.md` | 161 | 0/0/0 | **Delete** (same). |
| `2026-10-25-region-improvements.md` + `docs/benchmarks/region-counts-2026-10-25.json` | 179 + 1404 | 1/4/0, 1/0/0 | **Delete** (benchmark done; `RegionCountCache` and db §3.4 hold the result). |
| `2026-10-27-gallery-position-and-sorting.md` | 279 | 1/1/4 | **Delete** (implemented; `GalleryOrderSpec`, `LargeViewUrlsSpec`). |
| `2026-10-29-conflict-of-interest-filter.md` | 301 | 0/0/0 | **Delete** (implemented, `CoiAuthorizationSpec`; P1b's disqualification is in the round-images plan). |
| `2026-10-31-category-operators-design.md` | 55 | 2/0/0 | **History** (the legacy-compatible rule model is in force). |
| `2026-10-31-category-operators-plan.md` | 94 | 0/0/0 | **Delete** (step plan, done in `95093df2`, `f9c31ce5`). |
| `2026-10-31-round-redistribution-filters.md` | 98 | 0/0/0 | **Delete** (records `58efaeda`; superseded by the design). |
| `docs/history/2026-11-01-binary-aggregate-votes.md` | 450 | 1/6/0 | **History** (user decisions in force, 6 code links), or delete with the code links repointed. |
| `2026-10-11-deployment-procedure.md` | 184 | 2/0/0 | **History** (why one `wlxjury.service` and versioned ZIPs, against blue-green). |
| `docs/security/2026-11-02-role-allowlist-hotfix.md` + `-data.sql` | 105 + 46 | 3/0/0, 2/0/1 | **History** until a followups release replaces the hotfix on production; then delete. |

### Investigation and review records

| File | Lines | Links | Action |
|---|---|---|---|
| `2026-10-13-deployment-tooling-analysis.md`, `-alternatives.md` | 250, 229 | 1/0/0, 2/0/0 | **History**, merged into one (why the custom tool stays). Or delete both and keep the one-paragraph conclusion in `deployment.md`. |
| `docs/db-performance-analysis.md` | 251 | 4/0/0 | **History** (db §3.8 cites its index candidates; pre-V82 names `selection`, `rounds`). Better: copy the still-open candidates into db §11 and delete. |
| `docs/gatling-comparison.txt` | 32 | 1/2/1 | **Delete** (V45 one-off; `GatlingCompare.scala` names it as an output example: repoint to the commit). |
| `docs/schema-performance-analysis.txt` | 155 | 1/0/0 | **Delete** (draft of `db-performance-analysis.md`). |
| `docs/security/2026-11-02-rereview-report.md`, `-previous.md`, `-new.md` | 156, 176, 318 | 3/0/0, 4/0/0, 1/0/0 | **History** (register's N/O/P ids and "the dated review document keeps the details"). |
| `docs/security/review.md` | 259 | 5/0/0 | **Delete** (XSS review 2026-03; every item re-checked in rereview-previous, status in the register). |
| `docs/security/local-images-review-followups.md` | 240 | 5/0/0 | **Delete** (same). |

### Superseded or stale

| File(s) | Lines | Links | Action |
|---|---|---|---|
| `docs/security/incident-prevention-guidelines.md` | 7 | 7/0/0 | **Delete** (a pointer to secure-coding since 2026-11-02). |
| `docs/security/incident-prevention-implementation-plan.md` | 181 | 4/0/0 | **Delete** (status in the register; rules in secure-coding). |
| `docs/plans/2026-03-0{6,7,8}-*` (7: local image cache tests, Docker image cache tests, round image cache, Play image proxy; design + plan each) | 2244 | 0/0/0 | **Delete** (early step-by-step drafts; the code and CLAUDE.md "Testing" describe what exists). |
| `docs/superpowers/**` (15: saveResized cascade + results, AutoRollback tests, Flyway baseline, Gatling perf tests, `monument_id` denormalisation, multi-contest user, blue-green deploy + sync, SQL injection fix) | 8591 | 2/0/0 (to each other, and `rereview-previous` → sql-injection-fix) | **Delete**. The Flyway-baseline (a `B44` baseline) and blue-green designs contradict what is in force (db §8.1: no `B` files; `deployment.md`: one service). |

## 2. Rules folded in (done)

| Target | Rule | Source |
|---|---|---|
| secure-coding §5 | No script, style or font from another host; no CDN | theme-modes, "Invariants that every reviewer checks" ("No external hosts") |
| secure-coding §6 | A cookie's value is parsed into its enumerated set, never written as given | theme-modes, "Invariants" ("The theme value") |
| secure-coding §9 | The API has no second role model; tokens only under `/api/`, never logged, flashed, in a URL or stored plain | api-auth, "Invariants (every reviewer checks)" |
| secure-coding §12 | JSON endpoints answer JSON; signed out, script → JSON 401, page GET → sign-in keeping `returnTo` | error-handling §3 "JSON endpoints get JSON", §4 "Unauthenticated requests keep their destination" |
| db §4.3 | Production-like runs model the server; compare only like runs, re-run the older back to back | `bench/README.md` §1 "Fidelity", "Comparability" |
| db §4.7 (new) | Production data stays out of repositories; anonymise at once; commit scripts, never data | `bench/README.md` §1 "Personal data"; prod-like benchmark "Rules" |
| db §9.3 | A parity spec covers juror/organizer, binary/rated, with/without region and filter | gallery-latency, "Rules (CLAUDE.md)": "Every query change has a test that its counts equal the old queries'…" |
| db §9.5, §10, §11 V4 | Corrected: the test config is anonymous by itself; guards `TestConfigAnonymousSpec`, `AnonymousBotsSpec` | `test/resources/application.conf`, CLAUDE.md "Testing" |

Checklists got one line per new rule. Not folded because already present: CSRF and keyboard
voting by paths (secure-coding §7, §11), error pages by keys (§12), negative authorization
cases (§9 "Tests"), paired runs and SQL logging off (db §4.2–§4.3), round-first and lock
order (db §7, from write-path), migration rules (db §8, from flyway and round-images).

### Candidates left for later (need a decision or belong elsewhere)

- **Messages in all four files** with the same placeholders (`MessagesCompletenessSpec`;
  open-followups and theme-modes invariants, error-handling "Constraints"): an i18n rule, no
  practices doc fits; testing doc or CLAUDE.md.
- **Tests assert on ids, data attributes and text, not styling classes** (theme-modes and
  open-followups invariants; CLAUDE.md "Agent efficiency"): testing doc.
- **Only full-page GETs switch the contest** (error-handling "Constraints"): already in
  CLAUDE.md and implied by secure-coding §9; could be stated there in one clause.
- **Long work is a job, never a request thread** (background-distribution, background-import;
  CLAUDE.md "Background jobs"): db §5 has short transactions but not this rule; add as §5.7 if
  the user wants it in practices rather than CLAUDE.md.
- **db-performance-analysis's open index candidates** → db §11 (then delete the analysis).

## 3. Duplicates and contradictions

| # | Where | Conflict | Current |
|---|---|---|---|
| C1 | db practices §9.5 and §11 V4 vs CLAUDE.md "Testing" and `test/resources/application.conf` | Practices said the test config still reads `${?COMMONS_USER}`; it doesn't (`9cc2fd85`) | Config / CLAUDE.md. **Fixed** in db practices. |
| C2 | error-handling plan §1 and "Out of scope" vs secure-coding §13, CLAUDE.md, `Secured.denialLogLine` | Plan: the denial line logs the email (`account=`); now `accountId` only | Code. Marked in the plan's status line. |
| C3 | `superpowers/*flyway-baseline*` vs db §8.1, flyway plan §1 | A `B44` baseline vs "no `B` files" | db §8.1. Delete the superpowers docs. |
| C4 | `superpowers/*blue-green*` vs `deployment.md`, deployment-procedure | Blue-green with promotion sync vs one service and versioned ZIPs | `deployment.md`. Delete. |
| C5 | `db-performance-analysis.md` vs the schema | Table names before V82 (`selection`, `rounds`), schema from the removed `B44` | The schema; db practices. History or fold (above). |
| C6 | roadmap "Database access practices … not merged" | The docs are on followups | Followups. Update the roadmap row. |
| C7 | db practices header cites `docs/plans/2026-11-03-pr-review-triage.md` on `origin/stack/series-doc` | Not in this tree | Link to the branch's commit or drop. |
| C8 | User memory "Flyway stays at 9.16.3" (outside the repo) vs CLAUDE.md, db §8.1 ("Flyway 13") | Flyway 13.12.0 merged (`ec3aef58`) | The repo. The memory note is stale. |
| D1 | Security invariants repeated in theme-modes, api-auth, open-followups and CLAUDE.md | Same rules four times | secure-coding is the one source; the plans' copies go with their delete or trim. |
| D2 | CLAUDE.md "Database Tests", "Table names" vs db §2.1, §9.1 | Duplicates, consistent today | Keep CLAUDE.md short with a pointer, or keep both (CLAUDE.md is loaded every session). |
| D3 | `review.md`, `local-images-review-followups.md`, `incident-prevention-implementation-plan.md` status tables vs the register | Snapshots (headers say so) | Register. Delete the three. |

## 4. Proposed layout

```
docs/
  api.md, deployment.md, oauth.md                 references
  db/practices.md, db/review-checklist.md         practices
  security/secure-coding.md, review-checklist.md, register.md
  testing/practices.md                            (planned, after the test-coverage work)
  plans/                                          active plans only (§1 "Active"), + ci-drafts/
  history/README.md                               index: file → what it decided → commit
  history/<area>/<original file name>             kept records (§1 "History")
```

Kept in history (12 files): prod-like benchmark, binary aggregate votes, category
operators design, deployment procedure, deployment tooling (one merged file), db performance
analysis (unless folded), the three security re-reviews, the role-allowlist hotfix and its
SQL; later flyway and write-path once their work lands. Everything else marked **delete**
(41 files, about 17,300 lines) goes, with its commit in the index's "Deleted" table.

## 5. Links that break, and how to fix them

Moving into `docs/history/` keeps the file name, so the index maps old path → new path; code
comments are repointed in the same commit (grep the file name). Commit messages can't change:
the index's old path column is enough to find a file. No stub redirects (they'd be 20+ tiny
files). For deletes:

| Deleted file | Incoming links | Fix |
|---|---|---|
| theme-modes | CLAUDE.md, secure-coding §5–§6, `scripts/ui-shots.py`, `UiShotsServer.scala` | Practices links: `98a8a79e:` path in the source note; code comments: drop the plan, keep the behaviour |
| api-auth | open-followups, roadmap, rereview-previous, secure-coding §9 | secure-coding: `docs/api.md`; plans: commit `1c2e14d9` |
| error-handling | open-followups, rereview-previous, secure-coding §12 | secure-coding: commit `b7349951`; others drop |
| gallery-latency | db §9.3, `RequestTiming.scala`, `GalleryLatencyBenchmark.scala`, `PoolWatcher.scala`, `JurorClickSimulation.scala` | db: commit `5529587f`; code: "results in commit `5529587f`" or db §4 |
| performance-and-memory | background-distribution (deleted too), `ExplainQueries.scala` | Code: db §3.1 |
| region-improvements (+ json) | open-followups, `GalleryController.scala`, `conf/application.conf`, `RegionCountBenchmark.scala`, `RegionCountPairedBenchmark.scala` | db §3.4 (counts cache) and §4.2 (paired) |
| gallery-position-and-sorting | gallery-latency (deleted), `LargeViewUrlsSpec.scala` | Commit `01346c85` |
| background-distribution | background-import (deleted) | — |
| gatling-comparison.txt | performance-and-memory, schema-performance-analysis (both deleted), `GatlingCompare.scala` (2) | Commit `0a43cc9f` |
| schema-performance-analysis.txt | performance-and-memory (deleted) | — |
| incident-prevention-guidelines | open-followups, security-practices (4), security-resolution (2), rereview-new, rereview-previous (2), implementation plan, secure-coding header | secure-coding (it is the replacement); in history records leave the text (they quote a past state) |
| incident-prevention-implementation-plan | README, security-practices (3), rereview-new, rereview-previous (2) | README: drop the line (register covers it); plans: commit `2a732d4f` |
| local-images-review-followups | README, security-practices (3), security-resolution (2), rereview-new, rereview-previous (3) | Same |
| security/review.md | README, open-followups (2), security-practices (3), rereview-new, rereview-previous (2) | Same |
| superpowers/sql-injection-fix | rereview-previous | Leave (history record) |
| 2026-03 plans, other superpowers, category-operators-plan, redistribution-filters, CoI filter | none outside their own group | — |

## 6. What feeds the next practices documents

**Written** (docs phase, 2026-11-03): every source listed here went into
`docs/testing/practices.md` and its checklist, `docs/workflow/`, `docs/operations/`,
`docs/integrations/`, `docs/ui/` and `docs/dependencies/practices.md`, and `docs/README.md`
(index: [docs/README.md](../README.md) §1); privacy into secure-coding §22, architecture
conventions into CLAUDE.md "Conventions". The list itself is at this file's earlier version
(`92ee9dc6:docs/plans/2026-11-03-docs-consolidation.md`).

Not written yet, tracked elsewhere: the test-suite runtime recommendations 5 and 8
(open-followups, "Test-suite runtime"); the patched gallery's inventory, targeted tests and
keep-or-replace decision (roadmap, "the patched gallery"; UI practices §7, dependencies §7).

## 7. Decisions for the user

1. Delete as a normal action: agree with the **delete** list (§1), or keep any of it.
2. `docs/history/` (proposed) or `docs/archive/`; index mapping vs stub redirects (proposed:
   index only).
3. Trim open-followups and security-resolution to their open items now, or after the next
   release.
4. db-performance-analysis: fold its open candidates into db §11 and delete, or history.
5. Deployment tooling records: merge into one history file, or reduce to a paragraph in
   `deployment.md`.
6. Planned security guards: keep security-practices as the list, or move it into
   secure-coding and delete the plan.
7. The "left for later" rules in §2 (messages files, long work as jobs, contest switching).

## Commits on this branch

- `f4b4bce7` Fold lasting security rules from finished plans into secure-coding
- `99363aba` Fold benchmark and parity rules into DB practices; fix stale 9.5
- `972237af` Mark the folded plans as historical
- this document
