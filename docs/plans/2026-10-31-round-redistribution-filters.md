# Round redistribution filters investigation and fix plan

## Scope and findings

Investigate filters configured when creating a round, then reused by "Distribute new files".
Existing-round filters remain read-only. Saving an edit must preserve their stored values;
existing assignments and votes must survive redistribution. Pasted file lists intentionally
bypass ordinary image filters and are outside this change.

Base: local `ukwikidev/conflict-of-interest` at `e59fb167`, on branch
`codex/round-redistribution-filters`. `origin` has no ref of that name.

- `Round.updateRound` saves only name and active state, preserving saved filters.
- Creation, redistribution and count previews now share `RoundDistributionHandler`.
- Historical minimum-megapixel loss was fixed by `687e3501` (2026-10-07):
  `Round.create` omitted `min_mpx`. Earlier synchronous creation used the returned
  in-memory round, whereas redistribution loaded the incomplete database row.
- Category exclusion remains absent from the schema, `Round.create` and `Round.extract`.
  Earlier synchronous creation could apply the in-memory value, but it was lost on reload.
  Current background creation also reloads the round, so this filter is lost immediately.
- Values already lost from older rounds cannot be reconstructed from the round row.
  Recent creation jobs may retain them in `params.settings.excludeCategory` (and
  `params.settings.minMpx` for the earlier megapixel bug). Before repairing a deployed
  round, inspect its original creation job; do not assume later redistribution jobs
  contain the original value. This schema migration defaults existing rows to NULL
  and does not change their historical assignments or guess their intended filters.

## Approach

Persist category exclusion beside the included category with a nullable `VARCHAR(4000)`
column, map it in both DAO directions, and reuse the existing filter engine. This fixes
the missing state at its source. Passing submitted filters only to a redistribution job
would leave later jobs and count previews inconsistent and would allow editing filters.

## Steps

- [x] Add the DAO round-trip test and reproduce exclusion loss with creation/edit tests.
- [x] Add `V69__Rounds_exclude_category.sql`; map `excludeCategory` in create/extract.
- [x] Verify category include/exclude and combined filters, regions, monuments, minimum
  megapixels, minimum size, media type, and combined image filters over two imports.
- [x] Verify previous-round selection and rating thresholds after their source gains files.
- [x] Verify count previews agree, repeated redistribution adds nothing, and votes survive.
- [x] Exercise edit POST with omitted read-only fields and actual redistribution; confirm
  every saved filter is preserved and minimum megapixels remains effective.
- [x] Compile tests first, run affected specs through `scripts/agent-check.py`, and record results.

## Verification results

- Before the fix: 35 passed, 2 failed. The new failures showed excluded category members
  admitted after reload, and `excludeCategory` missing from the stored round.
- After the fix: all 63 examples across nine targeted suites pass (latest result per suite).
  These include `RoundRedistributionFiltersSpec`, `RoundDistributionJobSpec`,
  `RoundEditPageSpec`, `PrevRoundHiddenInputSpec`, `RoundSpec`,
  `ImageWithRatingSeqFilterSpec`, `MigrationNamingSpec`, `RoundFileListSpec`, and `CoiRoundSpec`.
- One initial post-fix assertion compared the complete round including `created_at`, which
  the legacy schema changes on every update. The regression now normalizes that unrelated
  timestamp while comparing every other field; its rerun passed all 11 controller examples.
- `Test/compile` and `git diff --check` pass. The full application suite was not run.
- Full logs: `target/checks/20261031-120453-16300.log` (before fix),
  `target/checks/20261031-121005-18404.log` (post-fix first run), and
  `target/checks/20261031-121324-27516.log` (corrected controller check and additional suites).

## Review focus

New rounds must retain exclusion after reload; existing rows must accept NULL. Edit POSTs
must not clear filters. Excluded images must stay out across chunk boundaries and later
imports. Category lookup is stubbed in tests so checks need no real Commons requests.

## Existing-round recovery check

For a reported round, inspect its creation snapshot before proposing a data repair:

```sql
SELECT id, subject_id, created_at,
       JSON_EXTRACT(params, '$.completingCreation') AS completing_creation,
       JSON_EXTRACT(params, '$.settings.excludeCategory') AS exclude_category,
       JSON_EXTRACT(params, '$.settings.minMpx') AS min_mpx
FROM job
WHERE kind = 'round_distribution' AND subject_type = 'round'
  AND subject_id = :round_id
ORDER BY id;
```

Only restore settings supported by the original record. A later redistribution snapshot
can contain the already-lost value. Restoring a filter should affect future distribution;
removing existing assignments would require a separate decision about existing votes.
