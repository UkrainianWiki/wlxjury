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
| D10 | Rename `selection` → `vote` | [user] |
| D11 | New tables: singular snake_case (rule in CLAUDE.md) | [user] |
| D12 | Rename as its own step with no behaviour change, after the open branches merge; Scala rename in a separate commit | [user] |
| D13 | Counts kept by same-transaction deltas in the DAO, plus a repair job; no triggers | [user] |
| D14 | Phases P0 rename → P1 membership → P1b CoI disqualification → P2a counts in shadow → P2b reads behind a flag → P3 two-step flow | [user] |
| D15 | A general `rounds.status` (draft / paused / active / finalized / cancelled) instead of a review flag | [user] |
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
  vote_id`; `ALTER TABLE … RENAME INDEX` so index names start with the table name. All are
  metadata-only in InnoDB (MariaDB 10.5+), instant at 3M rows; foreign keys follow the table.
- Commit 1, SQL: raw SQL in ~15 app files (`ImageJdbc`, `ImageDbNew`, `RoundDistributionHandler`,
  `DistributeImages`, `CriteriaRate`, `SelectionJdbc.tableName`, `User`, `LargeViewController`,
  `RoundConflicts`, `CoiMembers`, `Round`, …), the specs' raw SQL, the migration specs,
  `test/gatling/setup/ExplainQueries.scala`, `SharedTestDb.dataTables`.
- Commit 2 (optional, mechanical): `Selection` → `Vote`, `SelectionJdbc` → `VoteJdbc`,
  `ImageWithRating.selection` → `votes`.
- No behaviour change: the full suite once; the Gatling fixture cache rebuilds (the migration
  changes its key).
- When: right after the open branches merge (every raw SQL line conflicts otherwise).
- Rollback: old code fails on the renamed tables. Reverse `RENAME TABLE` by hand (instant) and
  remove the migration's row from `flyway_schema_history`; write this into the deploy notes. No
  compatibility view.

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
  `(round_id, page_id, rate)` index) joined to `rounds` and `images`, status `in`, `legacy`;
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
  optional_rate's denominator stays round-level (`numberOfActiveJurors`), computed when read.
- Votes with a NULL rate: see open items.

### `rounds.status` (P3)

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

`rounds` → `images` → `vote` → `vote_aside` → `round_image` → `criteria_rate`. It extends
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
  contest switch), each with `csrfField`, guarded by `rounds.status` and the active job.
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

- Work: `rounds.status` and its backfill, job modes, Prune, the form, the review page, re-apply,
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
