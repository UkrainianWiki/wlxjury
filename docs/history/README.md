# History: records and deleted documents

Records still useful after their work is done: the reasons for decisions in force, runbooks
for what isn't on production yet, and baselines that are still cited. Everything else was
deleted on 2026-11-03 (`docs/plans/2026-11-03-docs-consolidation.md`); git keeps it. Read any
deleted or moved file at its old path with `git show 98a8a79e:<old path>` (98a8a79e is
`ukwikidev/followups` before the consolidation). The lasting rules are in the practices:
[security](../security/secure-coding.md), [database](../db/practices.md),
[performance](../performance/practices.md), and the others indexed in
[docs/README.md](../README.md).

## Kept records

| File | Old path | Why it is kept |
|---|---|---|
| [2026-10-07-prod-like-benchmark.md](2026-10-07-prod-like-benchmark.md) | `docs/plans/` | Production-like baselines cited by `bench/README.md` and code; the MariaDB configuration and archiving decisions |
| [2026-10-11-deployment-procedure.md](2026-10-11-deployment-procedure.md) | `docs/plans/` | Why one service and versioned ZIPs, not blue/green (summary in `docs/deployment.md`) |
| [2026-10-31-category-operators-design.md](2026-10-31-category-operators-design.md) | `docs/plans/` | The legacy-compatible category rule model in force |
| [2026-11-01-binary-aggregate-votes.md](2026-11-01-binary-aggregate-votes.md) | `docs/plans/` | Decisions and issues cited by six code comments |
| [db-performance-analysis.md](db-performance-analysis.md) | `docs/` | The 2026-03 index analysis (pre-V82 names); what is left is db practices §11 V11 |
| [security/2026-11-02-rereview-report.md](security/2026-11-02-rereview-report.md), [-previous](security/2026-11-02-rereview-previous.md), [-new](security/2026-11-02-rereview-new.md) | `docs/security/` | The evidence behind the register's N*, O*, P* ids |
| [security/2026-11-02-role-allowlist-hotfix.md](security/2026-11-02-role-allowlist-hotfix.md), [data script](security/2026-11-02-role-allowlist-data.sql) | `docs/security/` | The deployed hotfix's runbook and data script; delete once a followups release replaces it |

One citation keeps an old path: the comment in
`conf/db/migration/default/V55_1__Convert_to_InnoDB.sql` names
`docs/plans/2026-10-07-prod-like-benchmark.md`. A migration applied on production can't change
(Flyway's checksum), so read it as the file above. (The bench server's `Dockerfile`,
`Dockerfile.noble` and `recommended-60-wlxjury.cnf` were repointed on 2026-11-03.)

## Deleted

Every file below is present at `98a8a79e`.

| Deleted file | Last changed | Content went to |
|---|---|---|
| `docs/plans/2026-10-31-theme-modes-and-design-system.md` | `972237af` 2026-11-03 | secure-coding §5, §6; fixture how-to: performance §1.7 |
| `docs/plans/2026-11-01-api-auth.md` | `972237af` 2026-11-03 | secure-coding §9, §11; docs/api.md |
| `docs/plans/2026-10-27-error-handling.md` | `972237af` 2026-11-03 | secure-coding §9, §12; CLAUDE.md "Authentication & Authorization" |
| `docs/plans/2026-10-28-gallery-latency.md` | `972237af` 2026-11-03 | performance §1.4, §2; db §9.3 |
| `docs/plans/2026-10-05-performance-and-memory.md` | `4fb4bf3c` 2026-10-06 | performance §1, §4 |
| `docs/plans/2026-10-07-background-distribution.md` | `c3f8e68b` 2026-10-07 | db §5.5a; performance §3.5, §4.1 |
| `docs/plans/2026-10-17-background-import.md` | `7e20a35d` 2026-10-21 | db §5.5a |
| `docs/plans/2026-10-25-region-improvements.md` | `bbddb076` 2026-11-02 | performance §3.3 |
| `docs/benchmarks/region-counts-2026-10-25.json` | `e4cab274` 2026-10-25 | (data of region-improvements) |
| `docs/plans/2026-10-27-gallery-position-and-sorting.md` | `01346c85` 2026-10-27 | implemented (`GalleryOrderSpec`, `LargeViewUrlsSpec`) |
| `docs/plans/2026-10-29-conflict-of-interest-filter.md` | `00670c1b` 2026-10-30 | implemented (`CoiAuthorizationSpec`); P1b in the round-images plan |
| `docs/plans/2026-10-31-category-operators-plan.md` | `f9c31ce5` 2026-10-31 | implemented; design in history |
| `docs/plans/2026-10-31-round-redistribution-filters.md` | `95093df2` 2026-10-31 | superseded by the category-operators design |
| `docs/gatling-comparison.txt` | `0a43cc9f` 2026-03-25 | performance §1.6, §3.1 (`GatlingCompare` now writes `target/gatling/gatling-comparison.txt`) |
| `docs/schema-performance-analysis.txt` | `ae8bc190` 2026-03-25 | draft of db-performance-analysis (history) |
| `docs/security/review.md` | `2a732d4f` 2026-11-02 | re-checked in security/2026-11-02-rereview-previous; register |
| `docs/security/local-images-review-followups.md` | `2a732d4f` 2026-11-02 | re-checked in rereview-previous; register |
| `docs/security/incident-prevention-guidelines.md` | `2a732d4f` 2026-11-02 | secure-coding |
| `docs/security/incident-prevention-implementation-plan.md` | `2a732d4f` 2026-11-02 | register; secure-coding |
| `docs/plans/2026-10-13-deployment-tooling-alternatives.md` | `0fae64cb` 2026-10-13 | docs/deployment.md "Why it works this way" |
| `docs/plans/2026-10-13-deployment-tooling-analysis.md` | `02791bbd` 2026-10-13 | docs/deployment.md "Why it works this way" |
| `docs/plans/2026-11-02-security-practices.md` | `99b31047` 2026-11-02 | secure-coding §21 (guards); checklist; register |
| `docs/superpowers/benchmark-results/2026-03-10-cascade.txt` | `c60f6bb2` 2026-03-10 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/plans/2026-03-10-saveresized-cascade-benchmark.md` | `ed9094d8` 2026-11-02 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/plans/2026-03-25-autorollback-tests.md` | `ed9094d8` 2026-11-02 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/plans/2026-03-25-flyway-baseline.md` | `ed9094d8` 2026-11-02 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/plans/2026-03-25-gatling-perf-tests.md` | `ed9094d8` 2026-11-02 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/plans/2026-04-06-selection-monument-id-denormalize.md` | `ed9094d8` 2026-11-02 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/plans/2026-04-12-multi-contest-user.md` | `3a535c21` 2026-10-17 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/plans/2026-04-14-blue-green-deployment.md` | `ed9094d8` 2026-11-02 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/plans/2026-04-20-sql-injection-fix.md` | `ed9094d8` 2026-11-02 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/specs/2026-03-10-saveresized-cascade-benchmark-design.md` | `701a6119` 2026-04-22 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/specs/2026-03-25-flyway-baseline-design.md` | `813f096d` 2026-03-25 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/specs/2026-03-25-gatling-db-perf-tests-design.md` | `84b5263f` 2026-03-25 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/specs/2026-04-12-multi-contest-user-design.md` | `6c65c107` 2026-10-17 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/specs/2026-04-14-blue-green-deployment-design.md` | `ed9094d8` 2026-11-02 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/superpowers/specs/2026-04-14-blue-green-promotion-sync-design.md` | `ed9094d8` 2026-11-02 | early drafts; implemented or superseded (B44 baseline, blue-green) |
| `docs/plans/2026-03-06-local-image-cache-integration-tests-design.md` | `701a6119` 2026-04-22 | early drafts; implemented (image cache, proxy tests) |
| `docs/plans/2026-03-06-local-image-cache-integration-tests.md` | `125d2b8f` 2026-04-22 | early drafts; implemented (image cache, proxy tests) |
| `docs/plans/2026-03-07-docker-image-cache-tests-design.md` | `ae797e9c` 2026-03-07 | early drafts; implemented (image cache, proxy tests) |
| `docs/plans/2026-03-07-docker-image-cache-tests.md` | `37806515` 2026-03-07 | early drafts; implemented (image cache, proxy tests) |
| `docs/plans/2026-03-07-round-image-cache.md` | `37806515` 2026-03-07 | early drafts; implemented (image cache, proxy tests) |
| `docs/plans/2026-03-08-play-image-proxy-design.md` | `9bbfcb43` 2026-03-08 | early drafts; implemented (image cache, proxy tests) |
| `docs/plans/2026-03-08-play-image-proxy.md` | `46a391c4` 2026-03-08 | early drafts; implemented (image cache, proxy tests) |
