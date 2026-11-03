# Round images and two-step rounds

Status: plan, not started. Written 2026-11-02 from a planning agent's investigation of
`ukwikidev/followups` at b641c78d, the Montage review (`2026-11-02-montage-rounds-parity.md`)
and the user's answers. References are to files and functions; line numbers drift (B11 has
changed `RoundList` and `ImageDbNew` since).

Marks: **[user]** decided by the user; **[rec]** recommendation, not yet confirmed.

## 1. Goals

- A round's images are stored once per round (`round_image`), not inferred from its jurors'
  vote rows. "Which images does round R have" stops grouping every juror's votes.
- The all-jurors views (by rate, file list order and ranks, large view neighbours, region menu,
  next-round reads) read per-image counts instead of `GROUP BY page_id` over the votes: the
  by-rate all-jurors page takes ~0.8–1.2 s on the 38k-file fixture today, most of it the
  grouped query.
- Wrong filters are caught before jurors get files: every round is filled first, reviewed (or
  not), then distributed.
- A base for Montage parity on rounds (disqualification with reasons, round states, juror
  changes, audit), the goal after this plan.

## 2. Decisions

All D1–D20 decided by the user (the last recommendations confirmed 2026-11-02).

| # | Decision | |
|---|---|---|
| D1 | Add `round_image` (round membership plus per-image vote counts) | [user] |
| D2 | Every round has two steps: fill (and review), then distribute; no one-pass mode. Skipping review = distributing straight away | [user] |
| D3 | "Create and review images" is the round form's main button | [user] |
| D4 | A "Distribute as soon as the images are ready" checkbox (off by default) queues distribution after the fill | [user] |
| D5 | Disqualified votes reuse the existing aside table (today `selection_deleted`); `round_image.status` says why they are aside. No new `selection_dq` | [user] |
| D6 | No single "source" per image (an image passes several filters, can come from several previous rounds and categories). Store only how the row was added (`added_how`) and by which job (`added_by_job_id`); why it qualifies is computed on demand | [user] |
| D7 | Removing a file for a conflict of interest (CoI) disqualifies it and keeps its votes; today's "leave out now" deletes them | [user] |
| D8 | Requalifying (a mistaken match or an ended conflict) is a manual admin action with a reason; no stored override for now. "Re-check conflicts" and "Disqualify new matches" are admin-driven, never automatic | [user] |
| D9 | "Leave out now" stays as it is (deleting votes) until CoI disqualification ships; accepted | [user] |
| D10 | Rename `selection` → `vote`; also `rounds` → `round` (user decision 2026-11-02, folded into P0; `users`, `images`, `category_members` keep their names) | [user] |
| D11 | New tables: singular snake_case (rule in CLAUDE.md) | [user] |
| D12 | Rename as its own step with no behaviour change, after the open branches merge; Scala rename in a separate commit | [user] |
| D13 | Counts kept by same-transaction deltas in the DAO, plus a repair job; no triggers | [user] |
| D14 | Phases P0 rename → P1 membership → P1b CoI disqualification → P2a counts in shadow → P2b reads behind a flag → P3 two-step flow | [user] |
| D15 | A general `round.status` (draft / paused / active / finalized / cancelled) instead of a review flag | [user] |
| D16 | Rows are never deleted, except: a re-apply in review, before any vote exists in the round, deletes the `fill` rows that no longer pass | [user] |
| D17 | Top-N on re-apply: re-ranked from scratch, disqualified files skipped; already distributed files count toward N (today's rule) | [user] |
| D18 | Previous rounds are not frozen during review; the review page warns if their votes changed since the fill | [user] |
| D19 | Filter edits after distribution only add files | [user] |
| D20 | Organizers may view the review page; admins and root re-apply, distribute, disqualify, requalify | [user] |

Migration numbers below are placeholders: they are assigned at merge time (V76 is taken by S1's
role allowlist on `ukwikidev/security-s1`).

## 3. P0: rename `selection` to `vote`

- One migration: `RENAME TABLE selection TO vote, selection_deleted TO vote_aside,
  selection_orphans TO vote_orphan`; `ALTER TABLE criteria_rate RENAME COLUMN selection TO
  vote_id`, and `criteria_rate_orphan`'s (C1's V78 archive) likewise; `ALTER TABLE … RENAME
  INDEX` so index names start with the table name. All are metadata-only in InnoDB (MariaDB
  10.5+), instant at 3M rows; foreign keys follow the table. Done as V82: see §12.
- Also `rounds` → `round` (D10, user decision 2026-11-02), in the same `RENAME TABLE`, with its
  indexes; SQL in its own commit. Scala names (`Round`, …), routes/URLs and JSON stay.
- Commit 1, SQL: raw SQL in ~15 app files (`ImageJdbc`, `ImageDbNew`, `RoundDistributionHandler`,
  `DistributeImages`, `CriteriaRate`, `SelectionJdbc.tableName`, `User`, `LargeViewController`,
  `RoundConflicts`, `CoiMembers`, `Round`, …), the specs' raw SQL, the migration specs,
  `test/gatling/setup/ExplainQueries.scala`, `SharedTestDb.dataTables`.
- Commit 2 (optional, mechanical): `Selection` → `Vote`, `SelectionJdbc` → `VoteJdbc`,
  `ImageWithRating.selection` → `votes`.
- No behaviour change: the full suite once; the Gatling fixture cache rebuilds (the migration
  changes its key).
- When: right after the open branches merge (every raw SQL line conflicts otherwise).
- Rollback: old code fails on the renamed tables. Up to `reopen`, `wlxjury-release restore`
  (its pre-migration dump); after it, the reverse SQL by hand (instant) and removing the
  migration's row from `flyway_schema_history`. No compatibility view. See §12, "Deploy note".

The rest of this plan uses the new names.

## 4. Schema

### `round_image` (P1)

```sql
CREATE TABLE IF NOT EXISTS round_image (
  round_id        BIGINT UNSIGNED NOT NULL,
  page_id         BIGINT          NOT NULL,
  status          VARCHAR(16)     NOT NULL DEFAULT 'in',     -- in | dq | deleted
  added_how       VARCHAR(16)     NOT NULL DEFAULT 'legacy', -- fill | list | new_files | manual | legacy
  added_by_job_id BIGINT UNSIGNED NULL,                      -- its params.settings: the filters
  added_at        TIMESTAMP(3)    NULL,                      -- NULL for backfilled rows
  dq_code         VARCHAR(16)     NULL,                      -- coi | min_mpx | category | date | manual
  dq_detail       VARCHAR(255)    NULL,                      -- e.g. the matched account and role
  dq_by           BIGINT UNSIGNED NULL,
  dq_at           TIMESTAMP(3)    NULL,
  monument_id     VARCHAR(190)    NULL,                      -- copy of images.monument_id (as vote's, V48)
  region          VARCHAR(3) AS (NULLIF(LEFT(SUBSTRING_INDEX(monument_id,'-',1),3),'')) STORED,
  PRIMARY KEY (round_id, page_id),
  KEY round_image_page_idx (page_id),
  CONSTRAINT round_image_round_fk FOREIGN KEY (round_id) REFERENCES rounds(id) ON DELETE CASCADE,
  CONSTRAINT round_image_page_fk  FOREIGN KEY (page_id)  REFERENCES images(page_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

- Statuses: `in` (in the round; distributed or not is derived from its votes, `assigned > 0`
  after P2a); `dq` (disqualified, votes in `vote_aside`); `deleted` (file deleted on Commons in
  an open round, votes in `vote_aside`).
- The PK is the unique key Montage lacks; it also serves membership, the anti-joins, the vote's
  count update, page-id windows and a round scan (38k rows of ~80 B). `round_image_page_idx`
  serves the FK and per-file lookups. MariaDB 10.6 ignores DESC index parts, so the binary
  orders filesort 38k narrow rows: cheap. Add `(round_id, region, page_id)` only if
  `ExplainQueries` shows a need.
- `region` is `ImageDbNew.regionOf`'s expression.
- Why an image qualifies is not stored (D6). "Why is this image here" on the review page
  evaluates the per-image filter predicates (`DistributeImages.perImageFilters`,
  `ImageFilterGen`, `CategoryRule.accepts`) for one file, and lists its previous rounds
  (`round_image` with status `in`) and categories (`category_members`).
- Backfill: the distinct (round, page) pairs of `vote` (an ordered scan of V56's
  `(round_id, page_id, rate)` index) joined to `round` and `images`, status `in`, `legacy`;
  then the `vote_aside`-only pairs as `deleted`; `INSERT IGNORE` throughout. Production had 3.0M
  vote rows (V55_1's header): ~0.3–1M rows, ~30–100 MB, an estimated 10–60 s per statement. Time
  it on a production copy (`2026-10-07-prod-like-benchmark.md`) before deploying.

### Counts (P2a, `ALGORITHM=INSTANT`)

`assigned` (vote rows), `voted` (`rate <> 0`), `approvals` (`rate > 0`), `rejections`
(`rate < 0`), `rate_sum` (`SUM(rate)`), all `NOT NULL DEFAULT 0`; unrated = assigned − voted.
Backfilled by one `UPDATE … JOIN (GROUP BY round_id, page_id)`.

- Today's averaging (unrated as 0 over `assigned`, or over the round-level denominators of
  `RoundDistributionHandler`'s `Denominator`), Montage's (over `voted`) and a damped score
  `(rate_sum + C·m) / (voted + C)` all come from these.
- Binary orders: approvals, rejections, `Difference` = `rate_sum`; the legacy top-N rule =
  `GREATEST(rate_sum, 0)`.
- Criteria rounds count `vote.rate`, already the criteria sum (`CriteriaRate.updateRate`).
  Since C2 (U5) a criteria vote's `vote.rate` is that sum only once every criterion of the round
  is rated, and 0 before (the user's decision, 2026-11-03): a departure from the criteria plan's §3 "vote.rate stays the sum"
  for partial votes, so `rate <> 0` is the criteria-aware "voted" and the delta rule needs no
  criteria count.
  optional_rate's denominator stays round-level (`numberOfActiveJurors`), computed when read.
- Votes with a NULL rate: see open items.

### `round.status` (P3)

`draft | paused | active | finalized | cancelled`, NULL for existing rounds until the backfill
sets it from `active`/`distributing`. `active` and `distributing` stay written in step during
the transition, so `Round.openToJurors`, `activeRounds` and `openCond` keep working.

| status | distributing | active | when |
|---|---|---|---|
| draft | 1 | 0 | the fill job runs |
| paused (before first activation) | 1 | 0 | images ready, review |
| paused, distributing | 1 | 0 | the distribute job runs |
| active | 0 | 1 | `completeCreation` |

`finalized` and `cancelled` are for the Montage-parity work; P3 only writes the first three.

## 5. Keeping `round_image` correct

Every writer of `vote` keeps it in step, in the same transaction:

- Votes (`SelectionJdbc.rate` from `GalleryController.selectWS` and
  `LargeViewController.rateByPageId`, `SelectionJdbc.destroy`, `CriteriaRate.updateRate`, which
  today is two autocommit statements): one `localTx` that locks the vote row
  (`SELECT … FOR UPDATE`), updates it (and `criteria_rate`), and if the rate changed adds the
  deltas to the image's counts. Retry once on a deadlock or lock wait. ~1–3 ms per vote.
- `CriteriaRate.updateRate` (C2): runs at READ COMMITTED (`db.scalikejdbc.ReadCommitted`), and
  sums the vote's rates with a plain read after locking the vote row (a locking range read
  deadlocked with the next vote's upsert). P2a's `round_image` delta goes inside
  `updateRateIn`, after the vote lock; its `round_image` row is read locked (`FOR UPDATE`), as
  every delta, and READ COMMITTED keeps any plain read there current.
- Inserts (`create`, `batchInsert`, so the distribution, the in-memory path, the Gatling fixture
  and the test helpers): `round_image` upsert per (round, page), `assigned = assigned + n`.
- Bulk writers recompute the pages they touch (`RoundImageJdbc.recompute(roundId, pageIds)`,
  a locking-read GROUP BY, then write): `moveVotesAside`/`moveVotesBack`, `moveVotes`,
  `removeImage`, `removeUnrated`, `RoundConflicts`, disqualify/requalify;
  `syncSelectionMonumentId` also updates `round_image.monument_id`.
- Image deletion and round deletion: FK cascade.
- Repair job: new kind `round_image_check`, dedupe `round_image_check:round:R`, admin/root, run
  after each deploy or rollback; page-id windows; anti-joins both ways plus count comparisons;
  fixes and records drift in `details`, logs it at WARN; refuses while the round's distribution
  job is active.
- Triggers rejected (D13): `CREATE TRIGGER` needs SUPER or `log_bin_trust_function_creators`,
  they fire per row of a 15k-row chunk, the migration-spec splitter has no DELIMITER, and the
  logic is hidden from the Scala specs.

### Invariants

Checked by `RoundImageInvariants.violations(roundId)`; specs assert it empty after each scenario.

1. Every (round, page) with a `vote` row has a `round_image` row with status `in`.
2. Every `vote_aside` pair has a row with status `deleted` or `dq`.
3. `dq` and `deleted` rows have no `vote` rows.
4. A `deleted` row's image has `deleted_at` set.
5. The counts equal the GROUP BY over the pair's `vote` rows (P2a on).
6. `monument_id` equals `images.monument_id`.

### Lock order

`round` → `images` → `vote` → `vote_aside` → `round_image` → `criteria_rate`. It extends
today's rounds → files → votes (`DeletedFiles`). Two jurors voting on one image each hold their
own vote row and queue on the one `round_image` row: no cycle. Never recompute from a
non-locking snapshot (a concurrent delta would be lost).

### `vote_aside` and status

- File deleted on Commons: votes aside (as today), status `deleted`.
- File restored on Commons: `restoreVotes` moves back only the rows whose `round_image` status
  is `deleted`, then sets `in`. A disqualified round keeps its votes aside.
- Disqualify: votes aside unchanged (ids kept, so `criteria_rate` survives), status `dq`,
  `dq_*` set.
- Requalify: votes back with their exact rates, status `in`, `dq_*` cleared (the reason goes to
  the job/audit log); if the image now has `deleted_at`, status `deleted` and the votes stay
  aside.
- Disqualifying a file already deleted: only the status changes.

## 6. The two-step flow

### Job

One kind, `round_distribution` (dedupe key, freezing, Retry, Cancel, the CoI lock and the rounds
list's job column unchanged), with `params.mode`:

- `fill`: Rank (top-N) → Fill (category/previous-round windows and per-image filters write
  `round_image` rows, `added_how = fill`) → List (pasted titles, `list`) → Prune (re-apply only) →
  count check → round `paused`. With `distribute_after` (D4) it queues `distribute` when done.
- `distribute`: keyset windows by page id over rows with status `in` and no `vote` row;
  `DistributeImages.assign` with the running position; insert votes → count check →
  `completeCreation`. Idempotent and incremental (Montage's activation rule). Skips `in` rows whose
  image now has `deleted_at`.
- Jobs queued or running at deploy have no `mode`: they run as `fill` then `distribute` (their
  old behaviour).
- Fill's anti-join: NOT EXISTS any `round_image` row of the round, whatever its status, so `dq`
  and `deleted` files never come back. It replaces `notInRound`; P1 keeps the extra `vote` /
  `vote_aside` check until the repair job reports no drift.
- Fill freezes the previous rounds only while it runs (released with `close = false`);
  distribute freezes them again and closes them at the end, as creation does now.

### Count checks

- Fill: the `round_image` rows expected (`imagesBefore + added`).
- Distribute: every `in` row has its votes (`assigned` = the round's jurors per image after P2a;
  before, EXISTS a vote), plus invariant 1. Replaces `imageCount`/`visibleImageCount`.

### Re-applying filters (status paused, no votes in the round)

- `Round.updateFilters` (new: `updateRound` changes only the name today) takes the round lock
  and checks that no job is active, then queues `fill` in re-apply mode.
- Re-apply inserts the new matches and marks the rows it sees; Prune deletes the unseen `fill`
  rows (D16). `list`, `manual` and `dq` rows are never pruned.
- Its result lists +added / −removed, and for each removed file the filter that now rejects it.
- Top-N: D17. Previous rounds' votes changed since the fill (a fingerprint of their counts in
  the job state): the review page warns, and "Distribute anyway" needs confirming (D18).
- After distribution: additive only (D19).

### "Distribute new files"

Fill (`added_how = new_files`) then distribute, on an active round; the round stays active.
Jurors see only files they have votes for; the organizers' all-jurors views skip undistributed
rows (`assigned > 0`). The checkbox (D4) applies; without it the review page lists them as "new,
not distributed". Adding a juror: new files rotate over the active jurors; existing images are
not reassigned (as now).

### UI

- Round form: "Create and review images" (D3) and the "Distribute as soon as ready" checkbox. In
  `paused` the filter fields are editable and the button is "Re-apply filters".
- Rounds list: "Images ready: N — Review / Distribute" for paused rounds.
- Review page: the contest files page with a new `ImageJdbc.FileList.RoundImages(roundId,
  status)` beside `RoundDeleted` (tiles, pager, region menu with counts, large view). A summary by
  `added_how` and region, deleted files, CoI matches, the re-apply diff. Actions: Distribute, Edit
  filters, Disqualify / Requalify with a reason. "Why is this image here" per file. A CSV export of
  the round's images.
- Threshold preview (round form, after P2b): one GROUP BY over the previous rounds' `in` rows
  (summed per page first for several rounds): cumulative counts for "selected by ≥ N" and
  "rejected by ≥ N", average buckets, the cut-off at rank N; JSON, with `newFilesCount`'s
  authorization.

### Authorization

- GET the review page: the round's contest's admins and organizers (as `contestFiles`), the
  round in that contest or 404.
- POST re-apply, distribute, disqualify, requalify: `withAdminRound` (`Secured.inContest`, no
  contest switch), each with `csrfField`, guarded by `round.status` and the active job.
- Specs (MutationAuthorizationSpec style, `CsrfTestApp.withCsrfApp`): a juror of the round 403;
  an organizer GET 200, POST 403; an admin of another contest 403/404 (also another contest's
  round id under one's own contest); signed out → login page / JSON 401; no CSRF token refused;
  the wrong status refused.

## 7. Conflicts of interest

- "Leave out now" (`RoundConflicts.removeFromRound`) becomes: status `dq`, `dq_code = coi`,
  `dq_detail` = the matched account and role (`CoiMembers.matchOf`), `dq_by`, `dq_at`, votes to
  `vote_aside` (D7). It no longer deletes `vote`, `vote_aside` or `criteria_rate` rows.
- Until then it keeps deleting votes (D9).
- Requalify: manual, admin, with a reason (D8).
- "Re-check conflicts" on the round (beside `RoundController.coiCounts`): lists `coi` rows whose
  author no longer matches the contest's current conflict set (`CoiMembers.forContest`), with who
  matched before, and offers "Requalify"; lists new matches among `in` rows and offers
  "Disqualify". Never automatic.
- A disqualified file isn't carried into a next round (next rounds read `in` only); a file
  requalified later is picked up by the next round's "Distribute new files".
- Interim limitation (no overrides): a file requalified after a mistaken match is offered for
  disqualification again by a later re-check, and a re-apply with the conflict filter on drops it
  again.
- Later: CoI overrides, recorded once per contest as "author X is not account Y" and consulted by
  every round's check and by a re-apply with `exclude_coi`; a per-row override only for one-off
  exceptions.

## 8. Read paths (P2b)

- A `RoundImageQuery` (`round_image s STRAIGHT_JOIN images i`, status `in`, `assigned > 0`, no
  GROUP BY) replaces `SelectionQuery`'s `grouped` / `filesByVotes` / `tally` branches;
  `RoundList.query` uses it for all jurors (`asUserId == 0`) when `roundImage.read = true`. Its
  reader builds the same `ImageWithRating` (`rate_sum`, `countFromDb = assigned`, approvals,
  rejections), so the views don't change. Pages as a deferred join.
- Order columns: one vocabulary rendered for either source (`ListOrder`, `BinaryOrder`,
  `RankKey`): approvals, rejections, `rate_sum`, `monument_id`/`page_id`.
- Vote filters stay file-level (decision 3 of `2026-11-01-binary-aggregate-votes.md`): Unrated →
  `assigned > voted`; Selected/Rated → `approvals > 0`; Rejected → `rejections > 0`; plus
  `OR page_id = withPageId`.
- Region: `s.region = ?`; several regions or a sub-region join `monument` on `monument_id`.
- `count`, `regionCounts`, `keyCounts`/rank counts, `pageKeys`, `imageCountsByRate`,
  `byRoundMerged` read the columns; `position`/`around`/`voteMoved` use the keyed path over the
  round's rows instead of ROW_NUMBER.
- Distribution: "selected by" → `approvals` (binary) or `rate_sum` (rated); `rejectedBy` →
  `SUM(rejections) < n`; top-N pages sort `round_image` rows (one per previous round and image).
- Stays on `vote`: a juror's own lists and counts, `withVotes` (file list and CSV juror
  columns), `juryStat`, `roundUserStat`, juror membership, `CriteriaRate.countOf`.
- B12 (narrow grouped page) is pending a user decision; P2b would supersede it.

Expected on the 38k-file round (estimates, not measured): grouped page 0.5–0.9 s → ~10–30 ms;
count ~130 ms → <10 ms; all-jurors large view position + window ~1.6 s → ~20–40 ms; region menu
~135 ms → ~10 ms; the by-rate all-jurors page ~0.8–1.2 s → ~50–100 ms. Votes ~1–3 ms slower, a
distribution chunk ~5–15% slower.

## 9. Phases

Each phase ships alone. Specs follow CLAUDE.md's isolation table: DAO specs with
`AutoRollbackDb`/`dbTest`, service and controller specs with truncation; add the new tables to
`SharedTestDb.dataTables`.

### P0: rename (§3)

Files: the SQL users listed in §3. Specs: the full suite once (no behaviour change). Rollback:
manual reverse rename.

### P1: membership, backfill, "which images" reads

- Work: the `round_image` migration and backfill; `RoundImageJdbc`; membership on vote insert;
  status in the bulk writers; switch to `round_image`: `RoundImageCounts`'
  `imageCountByRounds`, the distribution count check, `notInRound` (keeping the `vote` check),
  `ImageJdbc.byRound`, all-jurors `byRegionStat`, `CoiMembers.roundFiles`, `ImageJdbc.inRound`/
  `hasVotes`, `roundDeletedFileCounts`, `lockOpenRoundsOf`, organizers' `SelectionJdbc.inRound`,
  previous-round windows without vote filters; the repair job's membership part.
- Risk: a missing row re-distributes an image; mitigated by the kept `vote` check and the repair
  job right after deploy. Rollback: old code ignores the table; run the repair job before going
  forward again.
- Specs: `RoundImageJdbcSpec`; the migration spec (the `PrevRejectedByMigrationSpec` pattern:
  truncate, insert, run the file, compare with GROUP BY, rerun unchanged); `RoundDistributionJobSpec`
  (rows written, resume idempotent, a failed chunk leaves none), `RoundRedistributionFiltersSpec`,
  `CoiRoundSpec`, `DeletedFilesSpec` (deleted and restored), `RoundImageCountsDbSpec`, a repair
  job spec with drift injected by raw SQL; fix the specs' raw vote SQL.
- Gatling: `GatlingDbSetup.load` fills it through the DAO; bump `FixtureVersion`.

### P1b: CoI disqualification (§7)

Cheap right after P1, so not left to P3: it needs only P1's `status`, today's `moveVotesAside`,
the `restoreVotes` condition, a requalify action and the re-check listing; no counts exist yet
to recompute. It ends the vote deletion of D9 early.

- Specs: disqualify then requalify restores exact rates and `criteria_rate`; restoring a deleted
  file doesn't restore disqualified votes; requalify of a file deleted meanwhile leaves it
  `deleted`; the re-check lists ended and new matches; authorization and CSRF as in §6.

### P2a: counts in shadow

- Work: the count columns and backfill; deltas in vote and insert paths; `recompute` in bulk
  paths; the repair job's count part. Reads unchanged.
- Risk: vote latency, deadlocks. Rollback: code only.
- Specs: vote endpoints through a Play app (`testDbApp`: `OwnVotesSpec`,
  `LargeViewControllerSpec`, half-star and criteria rounds), invariants after each; a
  concurrency spec (20 futures voting on one image plus a repair chunk).
- Gatling: bump `FixtureVersion`.

### P2b: reads behind a flag

- Work: §8. Flag `roundImage.read`, off until the pilot, then on.
- Rollback: the flag.
- Specs: old/new parity (equal pages, counts, ranks, positions over random data) in
  `ImageDbNewDbSpec`/`RoundListDbSpec`; `GalleryOrderSpec`, `AllJurorsListSpec`,
  `ListPositionSpec`, `GalleryRegionsSpec`, `RoundFileListSpec`, `CsvSpec`; the distribution
  specs give the same ids as before.
- Benchmarks (progressive, paired on one build by the flag, SQL debug logging off in both):
  pilot `GalleryLatencyBenchmark` with `-Dgatling.timing=true -Dbench.samples=20
  -Dbench.cases='^(organizer-|regions-.*-organizer)'`, flag off/on/off/on; then
  `-Dbench.cases='^(large|vote)'` for the vote cost; only if a result could change a decision,
  `AggregatedRatingsSimulation`, a `VotingSimulation` stress run (watch for 5xx from deadlocks),
  `RoundDistributionSimulation`. Report absolute deltas and run variation separately.

### P3: two-step flow

- Work: `round.status` and its backfill, job modes, Prune, the form, the review page, re-apply,
  the threshold preview, POST routes.
- Risk: rounds left paused at a rollback; finish or cancel them first.
- Specs: `RoundEditPageSpec`, `RoundControllerSpec`, `RoundCreationResubmitSpec`, a new
  `RoundImagesReviewSpec` (§6 authorization and CSRF); job specs: fill then distribute gives the
  same vote rows (jurors and order) as today's path; jobs without a mode run both steps; the
  re-apply diff; top-N with disqualified files; the fingerprint warning; "Distribute as soon as
  ready" queues distribution.
- Gatling: bump `FixtureVersion` only if `load` writes the status.

## 10. Rollout and checks

- Per phase: compile, the affected specs (negative authorization included), then one full suite
  at the release boundary; the repair job on staging after the migration, then on production.
- Time each backfill on a production copy before deploying.
- Deploy notes per phase: the migration, the rollback above, the repair job run.

## 11. Open items

- Production query before P2a: how many votes have a NULL rate. Count them as unrated
  (`COALESCE`) or normalise them to 0 in the backfill.
- B11 follow-up: `rateString`/`totalRate` show `rateSum` (rates below 0 as 0) while order and
  ranks use the SQL sum; decide what a rated round's vote below 0 means, then show a grouped row's
  `rate_sum`.
- B12 pending a user decision (superseded by P2b if P2b ships).
- Later, after this plan: CoI overrides (§7); juror add/swap/remove and quorum change (candidates:
  `in` rows with `assigned < round.distribution`, moved-aside votes in `vote_aside` with a
  reason); finalize locking votes; a `round_event` audit table written with status changes and
  disqualifications. None is blocked by this schema.

## 12. P0 results

Branch `ukwikidev/p0-vote-rename` (from `ukwikidev/followups` 18c6f704, `ukwikidev/followups`
8c2b3d67 merged before the gate), 2026-11-02. Commits: A `e8a97f31` (vote SQL and V82), B
`617f3bf2` (Scala names), C `f460673a` (`rounds` → `round`), then these notes.

### Migration V82 (`V82__Rename_selection_to_vote.sql`)

| Step | Statement | How MariaDB does it |
|---|---|---|
| 1 | On the old table names: `ALTER TABLE selection / selection_deleted / selection_orphans RENAME INDEX …` (10 each, to `vote_…`, `vote_aside_…`, `vote_orphan_…`); `rounds`: `rounds_submit_token_uidx` → `round_submit_token_uidx`, `idx_rounds_contest_active` → `round_contest_active_idx`; `ALGORITHM=INSTANT` | instant |
| 2 | `FK_selection_page_id` → `vote_page_id_fk` on `selection`: `DROP FOREIGN KEY` + `ADD CONSTRAINT` in one `ALTER`, `foreign_key_checks = 0`, `ALGORITHM=NOCOPY` | no copy, no row check (MariaDB 10.6 can't rename a constraint; with checks on, adding a key needs `ALGORITHM=COPY`: verified, it refuses NOCOPY) |
| 3 | `ALTER TABLE criteria_rate RENAME COLUMN selection TO vote_id, RENAME INDEX criteria_selection_index TO criteria_rate_vote_id_idx, ALGORITHM=INSTANT`; the same on `criteria_rate_orphan` (index `criteria_rate_orphan_vote_id_idx`) | instant |
| 4 | Last: `RENAME TABLE selection TO vote, selection_deleted TO vote_aside, selection_orphans TO vote_orphan, rounds TO round` | atomic, metadata only; V58's foreign key moves with the table, and `round_user`'s `FK_round_user_round_id` now references `round` (on the test schema; production's dump has no such key) |

Steps 1–2 change only names old code never reads, except `RoundService`'s duplicate-submit check,
which now matches `submit_token_uidx` under either name. A run that stops before step 4 leaves
the old table names; only step 3 affects old code (criteria rounds' rates).

`ALGORITHM=INSTANT`/`NOCOPY` make a statement fail rather than copy a table. Index names:
`vote_page_jury_round_uidx`, `vote_rate_idx`, `vote_round_idx`, `vote_jury_id_idx`,
`vote_page_id_idx`, `vote_round_rate_idx`, `vote_jury_round_rate_mon_page_idx`,
`vote_round_jury_rate_idx`, `vote_round_monument_id_idx`, `vote_round_page_rate_idx` (the same
with `vote_aside_`/`vote_orphan_`). The id keys keep their names (`PRIMARY` on the test schema,
`UNIQUE KEY id` on production, the V53 dump; production's `rounds` has both). No foreign key or
check constraint name mentions `rounds`; only `round_user` references it. `criteria_rate_orphan` keeps its name (already
singular, CLAUDE.md rules). `selection_backup` (a legacy production table no code reads) is left.

Checked on a scratch MariaDB 10.6.28 container (the `mariadb:10.6` image): the selection-family
index names are identical on the three schema paths (B44 + V46–V81, V1–V81, and production's
V53 dump + V54–V81); V82 ran on all three. With 1M `vote` rows (100k aside, 200k criteria
rates) every statement took ≤ 0.034 s and the InnoDB table ids stayed the same (no rebuild).
The foreign key still refuses a vote of a missing image and cascades a deleted image's votes.
Forward + rollback on a fresh B44 schema gave a `SHOW CREATE TABLE` of every table identical
to the schema before V82 (rerun after `rounds` was added, `round_user`'s key included). `rounds`'
index names are the same on the three paths too; its rename and index renames took 0.03–0.07 s. `ConvertToInnoDbMigrationSpec`'s chain example now runs V82 on the
production-like schema and checks the names, the foreign key, rows, `AUTO_INCREMENT` and that
every index of the renamed tables starts with its table's name.

### Deploy note

- **Order.** `ukwikidev/followups` (V71–V81, V78 creating `criteria_rate_orphan`) deploys as a
  release of its own **before** P0, and is reopened and checked. P0 is then the only migration of
  its release, from schema V81. (V82 also runs if both land together, since V78 runs first, but
  then the recovery below restores both releases' migrations at once.)
- **Before** (read-only, as the admin user, on production; any unexpected row stops the deploy):
  ```sql
  SELECT VERSION();
  SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
  SELECT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME IN ('selection', 'selection_deleted', 'selection_orphans',
                       'criteria_rate', 'criteria_rate_orphan', 'rounds')
  GROUP BY TABLE_NAME, INDEX_NAME ORDER BY 1, 2;
  SELECT COUNT(*) AS fk_selection_page_id FROM information_schema.REFERENTIAL_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'FK_selection_page_id'
    AND TABLE_NAME = 'selection';
  SELECT TABLE_NAME AS already_there FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('vote', 'vote_aside', 'vote_orphan', 'round');
  SELECT 'view' AS kind, TABLE_NAME AS name,
         VIEW_DEFINITION REGEXP '[[:<:]](selection|selection_deleted|selection_orphans|rounds)[[:>:]]' AS names_old_table
  FROM information_schema.VIEWS WHERE TABLE_SCHEMA = DATABASE()
  UNION ALL
  SELECT 'trigger', TRIGGER_NAME,
         EVENT_OBJECT_TABLE IN ('selection', 'selection_deleted', 'selection_orphans', 'rounds', 'criteria_rate')
           OR ACTION_STATEMENT REGEXP '[[:<:]](selection|selection_deleted|selection_orphans|rounds)[[:>:]]'
  FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()
  UNION ALL
  SELECT 'routine', ROUTINE_NAME,
         ROUTINE_DEFINITION REGEXP '[[:<:]](selection|selection_deleted|selection_orphans|rounds)[[:>:]]'
  FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE();
  SELECT GRANTEE, TABLE_NAME, PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME IN ('selection', 'selection_deleted', 'selection_orphans', 'rounds',
                       'criteria_rate', 'criteria_rate_orphan');
  SELECT GRANTEE, TABLE_NAME, COLUMN_NAME, PRIVILEGE_TYPE FROM information_schema.COLUMN_PRIVILEGES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('criteria_rate', 'criteria_rate_orphan');
  ```
  Expected: MariaDB 10.6+ (`RENAME INDEX`/`RENAME COLUMN` need 10.5.2); the last Flyway row
  `81`, success 1; per selection table: `id` and/or `PRIMARY`, `idx_selection_jury_round_rate_mon_page`,
  `idx_selection_round_jury_rate`, `idx_selection_round_monument_id`,
  `idx_selection_round_page_rate`, `idx_selection_round_rate`, `selection_jury_id_index`,
  `selection_page_id_index`, `selection_page_jury_round_uidx`, `selection_rate_index`,
  `selection_round_index`; `criteria_rate`: `criteria_selection_index`, `id`;
  `criteria_rate_orphan` (exists only once V78 has run): `PRIMARY`,
  `criteria_rate_orphan_selection`; `rounds`: `id` and/or `PRIMARY`, `idx_rounds_contest_active`,
  `rounds_submit_token_uidx`; `fk_selection_page_id` = 1; no `already_there` rows; no
  views, triggers or routines naming the old tables (a view would break: it is not rewritten);
  no table- or column-level grants on them (`RENAME TABLE` doesn't carry those; database-level
  grants are unaffected).
- **Deploy** with `wlxjury-release deploy` as usual: the release has a new migration, so the tool
  dumps the database before Flyway runs (`database.sql.gz` in the operation directory) and
  stops at "Ready under maintenance". Smoke-test locally, then `wlxjury-release reopen OPERATION`.
- **Recovery up to `reopen`, the primary path:** `wlxjury-release restore OPERATION`. It
  restores the pre-migration dump and switches back to the previous release under maintenance,
  also when V82 failed part-way (Flyway marks it failed; the tool refuses any later deploy until
  then). Then `wlxjury-release reopen OPERATION`. No SQL by hand.
- **After `reopen`** (writes since then would be lost by `restore`, which wants
  `--accept-data-loss` and a reconciliation decision): the manual reverse SQL below, then deploy
  the previous release. It works only when the previous release is at schema V81 (the
  followups release): it gives back V81's names exactly and deletes V82's history row, so
  Flyway sees V81. All its statements are instant; no compatibility view.
- The Gatling fixture cache rebuilds (the migration is in its key).

### Rollback SQL after reopen (tested: forward + this gives back the pre-V82 schema exactly)

Only with the app stopped and the previous release at V81. It reverses V82 in the opposite
order: table names first, then the column, the foreign key, the index names.

```sql
RENAME TABLE vote TO selection,
             vote_aside TO selection_deleted,
             vote_orphan TO selection_orphans,
             round TO rounds;

ALTER TABLE criteria_rate_orphan
  RENAME COLUMN vote_id TO selection,
  RENAME INDEX criteria_rate_orphan_vote_id_idx TO criteria_rate_orphan_selection,
  ALGORITHM=INSTANT;

ALTER TABLE criteria_rate
  RENAME COLUMN vote_id TO selection,
  RENAME INDEX criteria_rate_vote_id_idx TO criteria_selection_index,
  ALGORITHM=INSTANT;

SET @saved_fk_checks = @@foreign_key_checks;
SET foreign_key_checks = 0;
ALTER TABLE selection
  DROP FOREIGN KEY vote_page_id_fk,
  ADD CONSTRAINT FK_selection_page_id FOREIGN KEY (page_id) REFERENCES images (page_id) ON DELETE CASCADE,
  ALGORITHM=NOCOPY;
SET foreign_key_checks = @saved_fk_checks;

ALTER TABLE rounds
  RENAME INDEX round_submit_token_uidx TO rounds_submit_token_uidx,
  RENAME INDEX round_contest_active_idx TO idx_rounds_contest_active,
  ALGORITHM=INSTANT;

ALTER TABLE selection_orphans
  RENAME INDEX vote_orphan_page_jury_round_uidx TO selection_page_jury_round_uidx,
  RENAME INDEX vote_orphan_rate_idx TO selection_rate_index,
  RENAME INDEX vote_orphan_round_idx TO selection_round_index,
  RENAME INDEX vote_orphan_jury_id_idx TO selection_jury_id_index,
  RENAME INDEX vote_orphan_page_id_idx TO selection_page_id_index,
  RENAME INDEX vote_orphan_round_rate_idx TO idx_selection_round_rate,
  RENAME INDEX vote_orphan_jury_round_rate_mon_page_idx TO idx_selection_jury_round_rate_mon_page,
  RENAME INDEX vote_orphan_round_jury_rate_idx TO idx_selection_round_jury_rate,
  RENAME INDEX vote_orphan_round_monument_id_idx TO idx_selection_round_monument_id,
  RENAME INDEX vote_orphan_round_page_rate_idx TO idx_selection_round_page_rate,
  ALGORITHM=INSTANT;

ALTER TABLE selection_deleted
  RENAME INDEX vote_aside_page_jury_round_uidx TO selection_page_jury_round_uidx,
  RENAME INDEX vote_aside_rate_idx TO selection_rate_index,
  RENAME INDEX vote_aside_round_idx TO selection_round_index,
  RENAME INDEX vote_aside_jury_id_idx TO selection_jury_id_index,
  RENAME INDEX vote_aside_page_id_idx TO selection_page_id_index,
  RENAME INDEX vote_aside_round_rate_idx TO idx_selection_round_rate,
  RENAME INDEX vote_aside_jury_round_rate_mon_page_idx TO idx_selection_jury_round_rate_mon_page,
  RENAME INDEX vote_aside_round_jury_rate_idx TO idx_selection_round_jury_rate,
  RENAME INDEX vote_aside_round_monument_id_idx TO idx_selection_round_monument_id,
  RENAME INDEX vote_aside_round_page_rate_idx TO idx_selection_round_page_rate,
  ALGORITHM=INSTANT;

ALTER TABLE selection
  RENAME INDEX vote_page_jury_round_uidx TO selection_page_jury_round_uidx,
  RENAME INDEX vote_rate_idx TO selection_rate_index,
  RENAME INDEX vote_round_idx TO selection_round_index,
  RENAME INDEX vote_jury_id_idx TO selection_jury_id_index,
  RENAME INDEX vote_page_id_idx TO selection_page_id_index,
  RENAME INDEX vote_round_rate_idx TO idx_selection_round_rate,
  RENAME INDEX vote_jury_round_rate_mon_page_idx TO idx_selection_jury_round_rate_mon_page,
  RENAME INDEX vote_round_jury_rate_idx TO idx_selection_round_jury_rate,
  RENAME INDEX vote_round_monument_id_idx TO idx_selection_round_monument_id,
  RENAME INDEX vote_round_page_rate_idx TO idx_selection_round_page_rate,
  ALGORITHM=INSTANT;

DELETE FROM flyway_schema_history WHERE version = '82';
```

### Code

- Commit A (SQL): every raw SQL string, `VoteJdbc.tableName` (then `SelectionJdbc`),
  `CriteriaRate`'s column (a `nameConverters` entry until commit B renamed the field), the specs'
  SQL and table-name strings, `SharedTestDb`/`SharedPlayApp` table lists, Gatling setup and
  benchmarks (`ExplainQueries`, `ANALYZE TABLE` in `GatlingDbSetup`/`GatlingDbCache`),
  `scripts/timing-summary.py`'s log patterns, comments naming the tables or their indexes,
  CLAUDE.md. Specs that run an old migration's text on the shared (renamed) database replace the
  names first (V57, V77) or copy the renamed tables back to their old shape (V58: `t_selection
  LIKE vote`; V78: `t_criteria_rate` with `vote_id` renamed back); `ConvertToInnoDbMigrationSpec`'s
  V62 examples stop before V82 (V62 reads `selection`).
- Commit C (`rounds` → `round`, SQL, after B): V82's extra rename and indexes, `Round.tableName`,
  every raw SQL `rounds` in `app/` and `test/`, `round.<column>` in SQL, the truncation lists,
  `ANALYZE TABLE` lists, `timing-summary.py`'s pattern; specs that run V70/V72/V73/V74's text on
  the shared database rename the table in it first (and their `information_schema` checks read
  `round`); `ConvertToInnoDbMigrationSpec`'s chain also checks `round`. One string named an
  index: `RoundService.submit` recognises a concurrent identical submit by the duplicate-key
  message naming `rounds_submit_token_uidx`; it now looks for `submit_token_uidx` (either name; review L3)
  (`RoundCreationResubmitSpec` "concurrent identical submits" failed until then). The other
  such matches name unrenamed keys (`job_active_dedupe_uidx`, `images_page_monument_idx`).
- Commit B (Scala, mechanical): `Selection` → `Vote`, `SelectionJdbc` → `VoteJdbc` (files
  moved), `ImageWithRating.selection` → `votes`, `CriteriaRate.selection` → `voteId` (the
  converter goes), the templates' parameters (`showRate`, `juryStat`, `large`) and the large
  view's locals. The large view's JSON (`Accepts.Json`) keeps the key `"selection"` for the votes
  (`iwrWrites` renames the field back), so its output is unchanged.
- Not renamed (names, not tables; follow-ups if wanted): `TestDb.selectionDao` (126 uses in 31
  specs, left to keep the concurrent test-spec refactor's merge mechanical),
  `ImageDbNew.SelectionQuery` (named in `secure-coding.md`), spec file and class names
  (`SelectionSpec`, `SelectionFactorySpec`, `FindByRoundSelectionSpec`, …), `insertSelections`,
  `DistributeImages`' local `selection` and its debug log text, and English uses of
  "selection" for a juror's vote in comments and spec titles.
- Left as they are (history): migrations V1–V81 and B44, `test/resources/db/prod-v53-myisam-schema.sql`,
  `scripts/report-alias-accounts.sql` (run before V62 only), the V45 file names in
  `GatlingCompare`, and earlier plans' results.
- `GatlingDbCache.FixtureVersion` not bumped: the cache key already includes the migrations, and
  the loader writes through the DAOs and `vote` SQL.

### Rebasing the open branches onto P0

- **`ukwikidev/mariadb-10.11`:** its `V61__Selection_gallery_index_rate_desc.sql` must be
  renumbered (V83 or later: V61 is taken, and it must run after V82), target `vote`, drop
  `vote_jury_round_rate_mon_page_idx` (V82's name for `idx_selection_jury_round_rate_mon_page`)
  and name the new index `vote_jury_round_ratedesc_mon_page_idx`. Otherwise its
  `DROP INDEX IF EXISTS idx_selection_…` silently does nothing and `vote` keeps two gallery
  indexes. Decide whether `vote_aside`/`vote_orphan` get the same index (nothing pages them
  today). Update `SelectionGalleryIndexSpec` (`FROM selection s FORCE INDEX (…)`,
  `INSERT INTO selection`, `TABLE_NAME = 'selection'`, the two index names), and the specs and
  comments naming the old gallery index (`RegionCountsSpec`'s plan check of
  `vote_jury_round_rate_mon_page_idx`, `User.contestsWorkedIn`'s comment).
- **`ukwikidev/oauth-login`:** a trivial conflict in `SharedTestDb.dataTables` and
  `SharedPlayApp`'s list: it adds `"user_identity"` on the line where P0 renamed `"rounds"` to
  `"round"`; keep both. Its own SQL must use `round`/`vote` if it touches them.

### Checks

Logs under `target/checks/` of the worktree.

| Step | Specs | Result | Log |
|---|---|---|---|
| Commit A | `Test/compile`, `Gatling/compile` | ok | `20261102-201120-13972.log` |
| Commit A | `db.*`, `security.*`, `org.intracer.wmua.*`, `services.imports.*`, `services.distribution.*`, `RoundCreationResubmitSpec`, 7 controller specs | 766 passed, 1 error: `AccountVerificationMigrationSpec` ran V77's text (`selection`) on the renamed DB; fixed like V57 | `20261102-203748-27212.log` |
| Commit A | that spec and `CriteriaRateOrphansMigrationSpec` | 6 passed | `20261102-205456-15412.log` |
| Commit B | `Test/compile`, `Gatling/compile`; the set above plus `OwnVotesSpec`, `views.*` | 963 passed, 0 failed (2 skipped, 1 pending, as before) | `20261102-205823-25368.log`, `20261102-210222-6472.log` |
| Commit C | `Test/compile`, `Gatling/compile`; `db.*`, `security.*`, `org.intracer.wmua.*`, `services.*`, 7 controller specs | 929 passed, 1 failed: the concurrent-submit example (the index name in `RoundService`), fixed | `20261102-214547-7536.log` |
| Commit C | `RoundCreationResubmitSpec`, `RoundEditPageSpec` | 30 passed | `20261102-220756-14888.log` |
| Gate | full suite (`Test/compile`, `test`), after merging `ukwikidev/followups` 8c2b3d67 | **1667: 1661 passed, 0 failed, 0 errors, 6 skipped, 1 pending** | `20261102-220955-27740.log` |

`rg -n -i '\bselection(_deleted|_orphans)?\b' app conf test` leaves: migrations V1–V81 and B44
(history), V82 itself, `test/resources/db/prod-v53-myisam-schema.sql`, the old-migration specs
(V57, V58, V77, V78 and `ConvertToInnoDbMigrationSpec`'s pre-V82 inserts and checks), the large
view's JSON key `"selection"`, and English uses ("the juror's own selection", spec titles, local
names such as `DistributeImages`' `selection`, `RebalanceSpec`'s `val selection`).
`rg -n -i '\brounds\b' app conf test` leaves (besides migrations and the V53 dump): English
("the rounds of a contest"), Scala collections named `rounds`, routes and URLs (`/admin/rounds`,
`RoundController.rounds`, `rounds.scala.html`), message keys (`rounds`,
`error.previous.rounds.mixed.type`), and the pre-V82 SQL of `ConvertToInnoDbMigrationSpec`.

### P0 review fixes

The independent review found no Critical or High issue; these were fixed before merging.

| # | Finding | Fix |
|---|---|---|
| M1 | Deploy and recovery relied on hand-written SQL | "Deploy note": followups (V71–V81) deploys first as its own release; `wlxjury-release restore OPERATION` (the tool's pre-migration dump) is the recovery up to `reopen`, a part-way V82 failure included; the reverse SQL only after `reopen`, and only from a previous release at V81 |
| M2 | `ukwikidev/mariadb-10.11`'s gallery index migration would no-op after P0 | "Rebasing the open branches onto P0" and the roadmap: renumber to V83+, target `vote`, drop `vote_jury_round_rate_mon_page_idx`, new `vote_jury_round_ratedesc_mon_page_idx`, update `SelectionGalleryIndexSpec` |
| L1 | `bench/scripts/run.sh` read `wlxjury.rounds` after the app migrated | it picks `round` or `rounds` from `information_schema`; `bench/README.md` says `convert.sh`, `trim-old-years.sql`, `reopen-contest.sql` run on pre-migration snapshots, so their old names are right |
| L2 | The pre-deploy check was incomplete | adds the version and last Flyway row, the foreign key (`FK_selection_page_id` on `selection`), views/triggers/routines naming the old tables, table- and column-level grants, the new names not existing yet; notes `criteria_rate_orphan` exists only after V78. Checked on a scratch MariaDB: it flags a view on `selection` and a grant on `rounds` |
| L3 | A part-way V82 left new table names with old indexes | V82 reordered: index renames and the foreign key swap on the old table names, then the column, the atomic `RENAME TABLE` last. `RoundService` matches `submit_token_uidx`, true under both names. Re-verified on a scratch `mariadb:10.6` (10.6.28): forward on the three schema paths gives identical names (34 indexes) and the foreign keys (`vote_page_id_fk`; `FK_round_user_round_id` → `round` where it exists); forward + the reverse SQL gives back every table's pre-V82 `SHOW CREATE TABLE` (367 lines, identical) |
| L4 | `criteria-rounds.md` C0 queries named the old tables | marked "before V82", with an "after V82" block (`round`, `vote`, `vote_aside`, `cr.vote_id`) |
| Nits | rollback written as a template; `secure-coding.md` `rounds.roles`; `oauth-login`'s rebase | rollback written out in full; `round.roles`; the `SharedTestDb`/`SharedPlayApp` conflict noted |
