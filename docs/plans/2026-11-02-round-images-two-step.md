# Round images and two-step rounds

Status: P0 merged (V82, §12); P1 implemented (V84, §13); P1b implemented (no migration, §14);
the `round_image_event` batch implemented (V88, §15). Written 2026-11-02 from a planning agent's investigation of
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
| D16a | From P3, removing a file from a round (`removeImage`) excludes it instead of deleting its row: status `dq`, `dq_code = 'manual'`, the reason kept, its votes moved aside as D7 does for CoI; "Distribute new files" and a re-apply never re-add it. P1 keeps its behaviour (the row goes with the votes, invariant 8) until then (user, 2026-11-03) | [user] |
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

As V84 created it (§13; the names follow §12's rule, `round` after V82):

```sql
CREATE TABLE IF NOT EXISTS round_image (
  round_id        BIGINT UNSIGNED NOT NULL,
  page_id         BIGINT          NOT NULL,
  status          VARCHAR(16)     NOT NULL DEFAULT 'in',     -- in | dq | deleted
  added_how       VARCHAR(16)     NOT NULL DEFAULT 'legacy', -- fill | list | new_files | manual | repair | legacy
  added_by_job_id BIGINT UNSIGNED NULL DEFAULT NULL,         -- its params.settings: the filters
  added_at        TIMESTAMP(3)    NULL DEFAULT NULL,         -- NULL for backfilled rows
  dq_code         VARCHAR(16)     NULL DEFAULT NULL,         -- coi | min_mpx | category | date | manual
  dq_detail       VARCHAR(255)    NULL DEFAULT NULL,         -- e.g. the matched account and role
  dq_by           BIGINT UNSIGNED NULL DEFAULT NULL,
  dq_at           TIMESTAMP(3)    NULL DEFAULT NULL,
  monument_id     VARCHAR(255)    NULL DEFAULT NULL,         -- copy of images.monument_id (255: production's images column)
  region          VARCHAR(3) AS (NULLIF(LEFT(SUBSTRING_INDEX(monument_id, '-', 1), 3), '')) STORED,
  PRIMARY KEY (round_id, page_id),
  KEY round_image_page_id_idx (page_id),
  CONSTRAINT round_image_round_id_fk FOREIGN KEY (round_id) REFERENCES round (id) ON DELETE CASCADE,
  CONSTRAINT round_image_page_id_fk FOREIGN KEY (page_id) REFERENCES images (page_id) ON DELETE CASCADE,
  CONSTRAINT round_image_status_chk CHECK (BINARY status IN ('in', 'dq', 'deleted')),
  CONSTRAINT round_image_added_how_chk
    CHECK (BINARY added_how IN ('fill', 'list', 'new_files', 'manual', 'repair', 'legacy'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
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
  it on a production copy (`docs/history/2026-10-07-prod-like-benchmark.md`) before deploying.

### Counts (P2a, `ALGORITHM=INSTANT`)

`assigned` (vote rows), `voted` (`rate <> 0`), `approvals` (`rate > 0`), `rejections`
(`rate < 0`), `rate_sum` (`SUM(rate)`), all `NOT NULL DEFAULT 0`; unrated = assigned − voted.
As built (V89, §16): added `INSTANT`, filled by the background recompute (every round is dirty
after the migration), not by an `UPDATE … JOIN` in the migration.

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

Every writer of `vote` keeps it in step, in the same transaction (membership and status; the
**counts** are no longer kept by deltas: W6, §9 "P2a", as built in §16, a vote marks its file and
a bulk writer its round, and a background recompute writes the counts):

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
  job is active. P1 built its membership part and invariant 7 (§13); P2a adds the counts.
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
7. Every `criteria_rate.vote_id` exists in `vote` or `vote_aside` (criteria plan §3). The
   repair job archives orphans in `criteria_rate_orphan` (V78's table, reason `orphan`) and
   deletes them.
8. P1 to P2b (until P3 fills rows before distributing): a row has votes, `in` in `vote`,
   `deleted` in `vote_aside`; a pair with no votes left has no row. A `dq` row (P1b, §14) has
   its votes in `vote_aside`, or none: it is kept whatever its votes, so the file never comes
   back.

Only pairs of a round and an image that exist count (vote.round_id and vote_aside have no
foreign keys). A pair with votes in both tables (a file moved into a round that isn't open) is
`in` (1 wins over 2).

### Lock order

**Since the write-path robustness batch (2026-11-03, `docs/plans/2026-11-03-write-path-simplification.md`,
W1–W3):** the rule between writers is no longer this table order but the **round-first rule**:
every structural write (anything but a juror's own vote) locks the rounds it touches **for
update, first** in its transaction (`RoundWrites.lock`; writers that start from files lock the
files' rounds first, `RoundWrites.lockForFiles`), so the round row serializes the structural
writers of a round; the DAO's writers check it at run time (`RoundWrites.requireRounds` /
`requireFiles`). Juror votes stay row-level (`VoteJdbc.rate` by primary key, then `round_user`
and `round_criterion` with share locks; `CriteriaRate.updateRate`) and never lock the round.
Every connection runs at **READ COMMITTED** (`db.default.hikaricp.transactionIsolation`; the
tests' pool by the driver's `transactionIsolation`): no gap locks, plain reads current. Write
transactions are retried on a deadlock (`Tx.write`, 3 attempts; job steps by the scheduler).
Below the round the order still holds and is what each writer does, but it is no longer the
safety argument between two structural writers of one round. Kept from before:

`round` → `images` → `vote` → `criteria_rate` → `vote_aside` → `round_image` →
`round_image_event` (criteria plan §3, folded in with P1; the event table since V88, §15: a
disqualify's or requalify's events are inserted after its rows, the round held for update). It
extends today's rounds → files → votes (`DeletedFiles`). Rule:
`criteria_rate` rows are written only while their vote row is locked (`CriteriaRate.updateRate`,
`VoteJdbc.deleteVotes`, the moves), so a criterion vote writes `criteria_rate` before any
delta. Two jurors voting on one image each hold their own vote row and queue on the one
`round_image` row: no cycle. Never recompute from a non-locking snapshot (a concurrent delta
would be lost). As implemented in P1 (§13): every `round_image` write comes last in its
transaction; `RoundImageJdbc.sync` reads the pairs' votes in share mode, then locks the rows.
"round_image last" holds per chunk when one transaction syncs several chunks (`removeUnrated`'s
chunks, `sync`'s 2,000-page groups). `removeUnrated` chunks by vote id, so one page's votes can
span two chunks; what prevents a cycle is that each `sync` chunk share-locks all the votes of its
pages (`vote`, then `vote_aside`) before it locks their `round_image` rows. Another transaction
that holds a vote of one of those pages is then waited for before any of their rows is locked,
and every writer of a `round_image` row holds the pair's votes first. A vote insert locks its rounds
in share mode before the votes (`RoundImageJdbc.lockRounds`): round_image's foreign key would
otherwise take the round after them (review B1).

### `vote_aside` and status

- File deleted on Commons: votes aside (as today), status `deleted`.
- File restored on Commons: `restoreVotes` moves back only the rows whose `round_image` status
  is `deleted`, then sets `in`. A disqualified round keeps its votes aside.
- Disqualify: votes aside unchanged (ids kept, so `criteria_rate` survives), status `dq`,
  `dq_*` set.
- Requalify: votes back with their exact rates, status `in`, `dq_*` cleared (the reason goes to
  the job/audit log); if the image now has `deleted_at`, status `deleted` and the votes stay
  aside. **P1b (§14, review finding 1):** `in` with its votes instead, as any deleted file of a
  round that is not open (the only rounds requalify runs on); they move aside when it opens.
  **Since V88 (§15):** the status before the disqualify, from the file's latest
  `round_image_event`: `deleted` (votes left aside) if it was `deleted` and is still deleted;
  otherwise, or with no event (disqualified before V88), `in` with its votes. Two rare residual
  cases stay (§15, "Design choices": A, B), both invariant-clean.
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
- Until then it keeps deleting votes (D9). **Done in P1b (§14)**: disqualify and requalify,
  gated to rounds not open (W7), on the round form and the round's conflicts page
  (`/admin/rounds/:id/conflicts`, the re-check below).
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
- Vote filters stay file-level (decision 3 of `docs/history/2026-11-01-binary-aggregate-votes.md`): Unrated →
  `assigned > voted`; Selected/Rated → `approvals > 0`; Rejected → `rejections > 0`; plus
  `OR page_id = withPageId`.
- Region: `s.region = ?`; several regions or a sub-region join `monument` on `monument_id`.
- `count`, `regionCounts`, `keyCounts`/rank counts, `pageKeys`, `imageCountsByRate`,
  `byRoundMerged` read the columns; `position`/`around`/`voteMoved` use the keyed path over the
  round's rows instead of ROW_NUMBER.
- Distribution: "selected by" → `approvals` (binary) or `rate_sum` (rated); `rejectedBy` →
  `SUM(rejections) < n`; top-N pages sort `round_image` rows (one per previous round and image).
- Stays on `vote`: a juror's own lists and counts, `withVotes` (file list and CSV juror
  columns), `juryStat`, `roundUserStat`, juror membership. (`CriteriaRate.countOf` went with C2:
  a round's criteria come from `round_criterion`.)
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

**Implemented (§14).**

**Write-path rules (user, 2026-11-03, W2/W7 of the write-path plan):** disqualify and requalify
are structural writers (`RoundWrites.lock` first; `RoundConflicts` already locks the round), and
once P3's `paused` exists they are allowed only on a round that isn't open, the status checked
under the round's X lock in the action's own transaction; row-level correctness and the retry
stay (a vote already in flight can still land).

Cheap right after P1, so not left to P3: it needs only P1's `status`, today's `moveVotesAside`,
the `restoreVotes` condition, a requalify action and the re-check listing; no counts exist yet
to recompute. It ends the vote deletion of D9 early.

- Guard needed (P1 review): P1's `RoundImageJdbc.addVoted` (`ON DUPLICATE KEY UPDATE status =
  'in'`) and `sync` ("has votes → `in`") would requalify a `dq` file whose votes come back.
  P1b keeps `dq` in both: `addVoted`'s update becomes `status = IF(status = 'dq', 'dq', 'in')`
  (and the vote insert is refused for a `dq` file: the fill's anti-join already excludes it),
  `sync` keeps a `dq` row `dq` whatever its votes, and only requalify sets `in`; invariant 3
  (`dq` rows have no `vote` rows) then catches any vote written for one.
- Before P3 (rows filled before any vote): `sync`'s "no votes → no row" and the repair job's
  removal of rows with no votes must become status- and phase-aware: an `in` row of a `paused`
  round, or a `fill`/`list`/`new_files` row not yet distributed, is kept; invariant 8 ends.

- Specs: disqualify then requalify restores exact rates and `criteria_rate`; restoring a deleted
  file doesn't restore disqualified votes; requalify of a file deleted meanwhile leaves it
  `deleted` (as built, §14: `in` with its votes, the round's opening moves them aside); the
  re-check lists ended and new matches; authorization and CSRF as in §6.

### P2a: counts in shadow

**Replaced (user, 2026-11-03, W6 of the write-path plan):** no deltas in the vote
transaction; the count columns are kept by a dirty-round mark and a background recompute,
done correctly: a dirty-generation counter (or the mark cleared before the read); each window's
`round_image` rows locked `FOR UPDATE` before the votes are read, at READ COMMITTED; a per-file
dirty set; the bulk writers' marks persisted on the round row; exact consumers (top-N, CSV,
finalize) read the votes directly. The items below describe the original plan. **Built (§16)**,
V89.

- Work: the count columns and backfill; deltas in vote and insert paths; `recompute` in bulk
  paths; the repair job's count part. Reads unchanged.
- Risk: vote latency, deadlocks. Rollback: code only.
- Specs: vote endpoints through a Play app (`testDbApp`: `OwnVotesSpec`,
  `LargeViewControllerSpec`, half-star and criteria rounds), invariants after each; a
  concurrency spec (20 futures voting on one image plus a repair chunk).
- Gatling: bump `FixtureVersion`.

### P2b: reads behind a flag

**Built (§17), V90**, with the user's per-round score rule (2026-11-05: `rated`, `assigned`,
`sum`; `optionalRate` folded into `sum`) in place of "one rule for every round" below.

- Work: §8. Flag `roundImage.read`, off until the pilot, then on.
- One rule for a file's average (decided by the user, 2026-11-03, after C3's review: criteria
  plan, "C3 results", "Denominators"). Today the grouped rows (by-rate gallery, all-jurors
  large view) average a file's rate sum over its assigned jurors, unrated votes as 0, while the
  per-vote rows (file list, CSV) average over its rated jurors; both use the active jurors in an
  `optionalRate` round. C3 shows the grouped views' denominator (`… 29 / 3 jurors`). P2b's
  `round_image` source picks one rule for every view (and `assigned`/`voted` give either), with
  a parity spec of the chosen rule and C3's criteria averages (fully rated votes) beside it.
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

**Write-path rules (user, 2026-11-03, W2/W7):** re-apply, Prune and D16a's `removeImage`
(`dq`) are round-first writers gated to `paused`/`draft` (the status read under the round's X
lock); the fill runs in `draft`. The test-only `removeImage` (`TestVoteWrites`) is not the app's:
P3 writes D16a's anew.

- Work: `round.status` and its backfill, job modes, Prune, the form, the review page, re-apply,
  the threshold preview, POST routes. `removeImage` excludes the file (D16a: `dq`, `dq_code =
  'manual'`, the reason kept, votes moved aside); first, `RoundImageJdbc.sync`'s "no votes → no
  row" and the repair job's removal of rows with no votes become status- and phase-aware (a
  `dq` row, an `in` row of a `paused` round, a filled row not yet distributed are kept), and
  invariant 8 ends.
- Risk: rounds left paused at a rollback; finish or cancel them first.
- Specs: `RoundEditPageSpec`, `RoundControllerSpec`, `RoundCreationResubmitSpec`, a new
  `RoundImagesReviewSpec` (§6 authorization and CSRF); job specs: fill then distribute gives the
  same vote rows (jurors and order) as today's path; jobs without a mode run both steps; the
  re-apply diff; top-N with disqualified files; the fingerprint warning; "Distribute as soon as
  ready" queues distribution.
- Gatling: bump `FixtureVersion` only if `load` writes the status.

## 10. Rollout and checks

- Per phase: compile, the affected specs (negative authorization included), then one full suite
  at the release boundary; the repair job on a local copy of production after the migration
  (the user's, §17 "Rollout"; there is no staging server), then on production.
- Time each backfill on a production copy before deploying.
- Deploy notes per phase: the migration, the rollback above, the repair job run.

## 11. Open items

- Production query before P2a: how many votes have a NULL rate. Count them as unrated
  (`COALESCE`) or normalise them to 0 in the backfill. **Goes to the ✗ item** (roadmap queue
  item 6, the user, 2026-11-05): `vote.rate` and `vote_aside.rate` made `NOT NULL DEFAULT 0`
  after the user's pre-deploy count, NULLs set to 0 first. **Done: V91 (§18).**
- B11 follow-up: `rateString`/`totalRate` show `rateSum` (rates below 0 as 0) while order and
  ranks use the SQL sum; decide what a rated round's vote below 0 means, then show a grouped row's
  `rate_sum`. **To be settled by the ✗ mark** (roadmap queue item 6, the user, 2026-11-05): a
  rated round's −1 is a ✗, counted as 0. **Settled (§18):** the order, the ranks and every text
  use the sum of grades (`SUM(GREATEST(s.rate, 0))`, the counts' `rate_sum + rejections`).
- B12 pending a user decision (superseded by P2b if P2b ships).
- Decided (user, 2026-11-03; D16a): in P3, `removeImage` marks the row `dq` (`dq_code =
  'manual'`, its votes moved aside as D7 does for CoI) instead of deleting it, so a removed file
  never comes back by "Distribute new files" or a re-apply. P1 keeps its behaviour (invariant 8)
  until then.
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

## 13. P1 results

Branch `ukwikidev/round-image-p1` (from `ukwikidev/followups` 4540659a: P0's V82, C1, C2's V83,
the Flyway batch), 2026-11-03. Commits: `617dcd67` (code, V84, specs), then the truncation fix and these notes.

### Migration V84 (`V84__Round_image.sql`)

| Step | Statement | Note |
|---|---|---|
| 1 | `CREATE TABLE IF NOT EXISTS round_image` (§4's SQL) | keys `PRIMARY (round_id, page_id)`, `round_image_page_id_idx`; FKs `round_image_round_id_fk` → `round`, `round_image_page_id_fk` → `images`, both `ON DELETE CASCADE`; `round_image_status_chk`, `round_image_added_how_chk` (BINARY, as V76) |
| 2 | `INSERT … SELECT` the distinct `(round_id, page_id)` of `vote` joined to `round` and `images`: `in`, `legacy` | a loose scan of `vote_round_page_rate_idx` |
| 3 | the same over `vote_aside`: `deleted`, `legacy` | a pair with votes in both stays `in` |

Both inserts end in a no-op `ON DUPLICATE KEY UPDATE round_id = round_image.round_id`: a rerun
after a failure part-way adds only what is missing and changes no row (spec'd); `INSERT IGNORE`
would also hide a truncation. Pairs of a missing round (`vote.round_id` has no FK) or a missing
image (`vote_aside` has none) are left out, and the invariants count only existing ones. V84
needs nothing of `round_user`: production's missing `FK_round_user_round_id` doesn't matter.
`monument_id` is VARCHAR(255) (production's `images.monument_id`; the test schema's is 190), its
collation explicit, `utf8mb4_general_ci`, as `images.monument_id`'s since V55.1 (spec'd on the
test schema and the production-like chain).

### Backfill: in the migration

Measured on the Gatling fixture (767,340 vote rows, 367 MB with indexes, 76,734 pairs, MariaDB
10.6.22, 512 MB buffer pool): step 2 1.01–1.15 s (three runs), step 3 2 ms (no rows aside),
`CREATE` 1–3 ms; round_image 10 MB. Production had 3.0M vote rows (V55_1's header): the scan
scales with the votes (~4×, ~4.5 s), the insert with the pairs (unknown: 0.3–1M, 4–13× the
fixture's). Estimate **5–20 s** with the fixture's 512M buffer pool (more with production's
128M if `vote` doesn't fit: time it on a copy, see the deploy note), with the app stopped (`wlxjury-release deploy` runs Flyway
under maintenance), so its shared locks on `vote` block nobody. Decision: backfill in V84, not
as a job: the reads switch at deploy and need every row; a job would need a dual read path
until it finished. The pre-deploy queries below size it on production.

### Repair job: needed now, built

`round_image_check` (`services.distribution.RoundImageCheckHandler`), default concurrency 1:
- a round's (`params.roundId`, dedupe `round_image_check:round:R`, the round's contest), queued
  by its contest's admins (`POST /admin/rounds/:id/check-images`, "Check round images" on the
  round's edit page); every round's then the criteria rates (`params.all`, dedupe
  `round_image_check:all`, no contest), by root (`POST /admin/round-images/check`, "Check every
  round's images" on the rounds page). CSRF on both; a check already active isn't queued again.
- steps: per round, windows of page ids (≤ 10,000 rows of each of `vote`, `vote_aside`,
  `round_image`) synced as the bulk writers sync (`RoundImageJdbc.sync`, rows added as `repair`
  with the job's id); then, in steps of their own, the rows' monument ids from `images` and a
  count of `deleted` rows whose file isn't marked deleted (invariant 4: reported, not repaired).
  Each step first takes the round's row in share mode and leaves the round out (`skipped`)
  while its distribution is queued or running.
- invariant 7 (all-rounds job only): `criteria_rate` windows of 5,000 ids; candidates read
  plainly, their vote ids then looked up with locks in `vote` and `vote_aside`, the rates still
  of no vote locked, archived in `criteria_rate_orphan` (reason `orphan`) and deleted.
- drift is logged at WARN per round and kept in the job's details (`added`, `statusFixed`,
  `removed`, `monumentFixed`, `notMarkedDeleted`, `orphansArchived`, `skipped`, the first 100
  rounds' lines). After each step's commit the caches follow, as the other writers do: the
  round's image count (`RoundImageCounts.invalidate`) when a window changed rows, the monument
  ids (`RegionCountCache.invalidateMonumentIds`) after a monument fix (review N4).

Why now: P1's reads rely on the rows (a missing one would hide a file from the count, the
regions, a CoI removal and a deleted file's move aside), and any release older than P1 writes
votes without them.

### Keeping it correct (as built)

- `RoundImageJdbc.addVoted`, after the votes' insert in its transaction (`VoteJdbc.create`,
  `batchInsert`; an auto-commit session gets a transaction of its own): one `INSERT … SELECT`
  from `round` and `images` per 2,000 pages (the FK check has locked the images' rows already),
  `ON DUPLICATE KEY UPDATE status = 'in'`. A vote of a round that doesn't exist adds no row.
  The distribution passes `fill` (creation), `new_files` ("Distribute new files"), `list` (a
  pasted list), with its job id; everything else is `manual`.
- `RoundImageJdbc.sync(roundId, pageIds)` after a bulk write in its transaction: the pairs'
  votes read in share mode (`vote`, then `vote_aside`), the rows `FOR UPDATE`, then `in` where
  votes remain, `deleted` where only aside ones (`dq` kept), no row where none; a missing row
  added with the image's monument id. Callers: `VoteJdbc.deleteVotes` (so `removeImage`,
  `removeUnrated`, `RoundConflicts.removeFromRound`), `moveVotes` (`setRound`: out of the
  source, into the target as `manual`), `ImageJdbc.moveVotesAside` (`archiveVotes`,
  `archiveDeletedVotesInRound`: a round's opening, a distribution's end, `settle`),
  `moveVotesBack` (restore). `updateMonumentIdIn` updates the rows' `monument_id` last.
- Rows follow the votes in P1 (invariant 8): a file whose votes are deleted (`removeImage`, the
  unrated, CoI "leave out now" per D9, moved to another round) loses its row, as it left the
  round before; "Distribute new files" may add it back, as before. D16 ("rows are never
  deleted") applies from P3, when rows exist before votes. Decided by the user (2026-11-03,
  D16a): in P3 `removeImage` marks the row `dq` (`dq_code = 'manual'`, the reason kept, votes
  moved aside as D7 does); "Distribute new files" and a re-apply never re-add it. Before that,
  `sync`'s "no votes → no row" and the repair job's removal must become status- and
  phase-aware (§9, P1b and P3).
- Lock order (§5, now `round → images → vote → criteria_rate → vote_aside → round_image`):
  every `round_image` write is last. A vote insert (`create`, `batchInsert`, so every chunk)
  first locks its rounds in share mode (`RoundImageJdbc.lockRounds`), before the votes (review
  B1: the FK check took the round after them, and a "Distribute new files" chunk deadlocked with
  a criteria change, which holds the round for update and then locks its votes; reproduced
  with ERROR 1213 on a scratch MariaDB, spec'd in `RoundImageLockSpec`). `setRound` locks both
  rounds (share), then the file's image row (`ImageJdbc.lockFiles`, for update), then the votes
  (review N1: the round_image row it adds needed the image after the votes, against
  `updateMonumentIdIn`; spec'd). The repair job's monument step reads the differing rows
  plainly, locks their images in share mode in page id order, then writes the rows by key, in
  a transaction that touches no vote (review N2); its "distribution active" check is a locking
  read (N3). The list phase of a distribution locks its round (share mode) at the top of its
  step, before it inserts fetched files and updates stored ones (`updateStored` locks their
  images and every vote of them, some in the round): fixed after the follow-up review, which
  found it otherwise took the round after those votes. Remaining: `sync` adds a row only to
  repair drift (a pair with votes and no row). Every caller that may do so locks the round
  first, except `removeImage` and `removeUnrated`. There it would need such drift, plus a
  concurrent transaction holding the round for update while it waits for that pair's votes.
  A lock spec for the list phase was not added: it needs a stored file that Commons reports
  changed, inside a step held open mid-transaction; the order is the vote inserts' (spec'd).
- `CriteriaRate.updateRate` (READ COMMITTED) and `VoteJdbc.rate` are untouched: a rate doesn't
  change membership. P2a puts its delta inside `updateRateIn`, after the vote lock.
- P1b hooks (comments in the code, no code): `moveVotesBack` gets a condition on the row's
  status (`deleted` only), `RoundConflicts` moves votes aside and sets `dq`; `sync` already
  keeps `dq`; `addVoted` never meets a `dq` file since the anti-join reads round_image.

### Reads moved to round_image

`ImageJdbc.imageCountByRounds` (`RoundImageJdbc.countsIn`), the distribution's count check
(`countAll`, `countIn`), its anti-join `notInRound` (round_image first; the `vote` and
`vote_aside` checks kept until the job has shown no drift on production), `ImageJdbc.byRound`,
the whole round's `SelectionQuery.byRegionStat` (`gallery.regionCounts = off`, and sub-regions;
the default menu's `regionCounts` is P2b's), `CoiMembers.roundFiles`, `ImageJdbc`'s `inRound`
(the round's deleted-file list), `roundDeletedFileCounts`, `lockOpenRoundsOf`, the organizers'
`VoteJdbc.inRound`, `DistributionSource.previousWindow`/`previousCount` without vote filters.
Two edges change, both for the better: a vote with no page id no longer adds its monument's
region to the whole round's regions, and votes of a round that doesn't exist add no row.

### Benchmarks (`gatling.setup.RoundImageBenchmark`, SQL debug logging off)

Paired on the fixture, the replaced vote query (A) against round_image (B), 20 interleaved
pairs (order alternating) after 3 warm-ups, results compared per pair (all equal). Log
`target/checks/20261103-054802-2860.log`, `target/gatling/round-image-bench-20261103-054847/`.

| Read | A median (min–max) | B median (min–max) | paired B−A median (min..max) | B faster |
|---|---|---|---|---|
| `imageCountByRounds` (both rounds) | 142.7 ms (117.8–203.5) | 25.9 ms (23.8–42.7) | **−113.6 ms** (−171.2..−93.8) | 20/20 |
| count check (rated round) | 124.8 ms (120.0–162.0) | 7.7 ms (7.5–8.4) | **−116.9 ms** (−153.8..−112.4) | 20/20 |
| whole round `byRegionStat` (rated) | 194.2 ms (184.5–292.7) | 20.9 ms (19.6–28.6) | **−173.7 ms** (−264.5..−163.7) | 20/20 |
| whole round sub-regions | 0.9 ms | 0.9 ms | 0.0 | 13/20 (the fixture's monuments have no `adm1`: both empty) |
| previous-round windows, a full walk (750 files each) | 879.0 ms (745.7–2182.4) | 65.2 ms (56.9–220.7) | **−799.2 ms** (−2057.5..−681.4) | 20/20 |
| open rounds of 200 files | 4.7 ms (3.4–6.7) | 2.9 ms (2.5–5.2) | −1.4 ms (−3.0..−0.4) | 20/20 |

Write side, 5 distribution-sized chunks (750 files × 20 jurors, 15,000 vote rows, rolled
back): the votes' batch insert 497 ms median (353–1261), `addVoted` 14 ms (8.7–63): **+2.8%**
per chunk. A vote's rate is unchanged in P1 (no round_image write). Reused, not rerun: the
by-rate/all-jurors pages and the default region menu (B11/B12, follow-ups plan), whose reads
P1 doesn't touch (P2b's). Not expanded: every moved read is one-sided and large, and no result
could change a P1 decision; P2b's flag pilot is the next measurement.

### Deploy note

- **Order.** Followups (V71–V81) first, as its own release; then the release with V82 onward.
  V84 runs after V82 and V83 in the same Flyway run if they ship together.
- **Before** (read-only, production, as the admin user; an unexpected row stops the deploy):
  ```sql
  SELECT VERSION();                                                       -- 10.6+
  SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
  SELECT TABLE_NAME AS already_there FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'round_image';           -- none
  SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND (TABLE_NAME, COLUMN_NAME) IN (('round', 'id'), ('images', 'page_id'));  -- bigint(20) unsigned, bigint(20)
  SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('round', 'images');    -- InnoDB (the FKs)
  SELECT MAX(CHAR_LENGTH(monument_id)) FROM images;                         -- <= 255
  SELECT COLLATION_NAME FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'images' AND COLUMN_NAME = 'monument_id';
                                       -- utf8mb4_general_ci (round_image.monument_id's)
  SELECT COUNT(*) FROM vote v JOIN images i ON i.page_id = v.page_id
  WHERE NOT (v.monument_id <=> i.monument_id);  -- 0: the whole round's regions now come from
                                                -- images.monument_id, not the votes' copy
  SELECT @@log_bin, @@binlog_format;   -- 0, or MIXED/ROW (as V83's check; INSERT ... SELECT
                                       -- ... ON DUPLICATE KEY UPDATE is unsafe for STATEMENT)
  -- the backfill's size: vote rows, the pairs it writes, those aside only
  SELECT COUNT(*) FROM vote;
  SELECT COUNT(*) FROM (SELECT DISTINCT round_id, page_id FROM vote) p;
  SELECT COUNT(*) FROM (SELECT DISTINCT round_id, page_id FROM vote_aside) d
  WHERE NOT EXISTS (SELECT 1 FROM vote s WHERE s.round_id = d.round_id AND s.page_id = d.page_id);
  -- left out: pairs of no round or no image (expected 0)
  SELECT COUNT(*) FROM (SELECT DISTINCT round_id, page_id FROM vote) p
  WHERE NOT EXISTS (SELECT 1 FROM round r WHERE r.id = p.round_id) OR p.page_id IS NULL;
  SELECT COUNT(*) FROM (SELECT DISTINCT round_id, page_id FROM vote_aside) p
  WHERE NOT EXISTS (SELECT 1 FROM round r WHERE r.id = p.round_id)
     OR NOT EXISTS (SELECT 1 FROM images i WHERE i.page_id = p.page_id);
  -- 'deleted' rows whose file isn't marked deleted (invariant 4; the check job reports them)
  SELECT COUNT(*) FROM (SELECT DISTINCT d.round_id, d.page_id FROM vote_aside d
                        JOIN images i ON i.page_id = d.page_id AND i.deleted_at IS NULL
                        WHERE NOT EXISTS (SELECT 1 FROM vote s WHERE s.round_id = d.round_id
                                          AND s.page_id = d.page_id)) x;
  ```
  Always time the backfill on a production copy (`docs/history/2026-10-07-prod-like-benchmark.md`) with
  production's buffer pool (128M, MariaDB's default): the fixture's ~1 s ran with 512M, so the
  5–20 s estimate may be optimistic if `vote` doesn't fit in memory.
- **Deploy** with `wlxjury-release deploy` (dump before Flyway, "Ready under maintenance").
  Then, as root, "Check every round's images" (rounds page): it must report no drift (the
  backfill just ran), and archives any criteria rate of no vote; see the job's details
  (`/admin/jobs/:id`) or its WARN lines. Then `wlxjury-release reopen OPERATION`.
- **Recovery up to `reopen`:** `wlxjury-release restore OPERATION` (the pre-migration dump;
  also after a part-way V84 failure).
- **After `reopen`:** the SQL below, then deploy the previous release (at V83). Old code ignores
  round_image, but `db.Migrations` validates without `*:future`, so the history row must go;
  dropping the table makes the next forward deploy run V84 again, rebuilding every row from the
  votes the older release wrote (spec'd). Then the check job, as above.

### Rollback SQL after reopen (tested: forward, this, forward again)

Only with the app stopped and the previous release at V83. If V82–V84 shipped together and the
target is V81, run this first, then C2's and P0's rollback SQL.

```sql
DROP TABLE round_image;
DELETE FROM flyway_schema_history WHERE version = '84';
```

`RoundImageMigrationSpec` (on copies: forward, this, forward gives the same rows, the other
tables' `SHOW CREATE TABLE` unchanged) and `FlywayUpgradeSpec` "V84's rollback SQL after reopen"
(a migrated database: this gives V83's schema and history exactly; votes written meanwhile; a
second migrate rebuilds round_image from them).

### Files

New: `V84__Round_image.sql`, `db.scalikejdbc.RoundImageJdbc`, `RoundImageInvariants`,
`services.distribution.RoundImageCheckHandler`, `controllers.RoundImageCheckController`; routes
`POST /admin/rounds/:id/check-images`, `POST /admin/round-images/check`; message keys
`round.images.check*` (en, fr, uk, ru). Changed: `VoteJdbc`, `ImageJdbc`, `ImageDbNew`
(`byRegionStat`), `CoiMembers`, `RoundDistributionHandler`, `DistributeImages.insertRows`,
`JobSchedulerProvider`, the `editRound`/`rounds` views, CLAUDE.md. Gatling: `FixtureVersion`
`fixture-v6`, `round_image` in the `ANALYZE` lists, `RoundImageBenchmark`.

### Specs

New: `db.RoundImageMigrationSpec` (backfill = GROUP BY, rerun, regions, keys and checks,
rollback), `db.scalikejdbc.RoundImageJdbcSpec` (every write path: inserts and their rollback,
`removeImage`, `removeUnrated`, `setRound`, deleted and restored on Commons, a round opening, a
round deleted while aside, monument ids, round and image deletion; `sync`; reads; each
invariant detected), `db.scalikejdbc.RoundImageReadParitySpec` (every moved read against the
replaced query over random data from the app's writers, three seeds),
`services.distribution.RoundImageCheckSpec` (drift injected by raw SQL repaired, invariant 4
reported, orphans archived, a clean rerun, one round only, a distributing round skipped),
`controllers.RoundImageCheckAuthorizationSpec` (admin and root 303; juror, organizer, another
contest's admin (also on this contest's round) 403; missing round 404; signed out; all rounds
root only; CSRF refused; the buttons). Extended: `RoundDistributionJobSpec` (rows `fill`,
`new_files`, `list` with job ids; a failed chunk leaves none; resume; the anti-join),
`CoiRoundSpec` (leave out now), `ConvertToInnoDbMigrationSpec` (V84 on the production-like
chain: rows, regions, FKs), `FlywayUpgradeSpec` (rollback). Raw vote SQL fixed in
`DeletedFilesSpec`, `CriteriaRateSpec` (its `vote` helper syncs the row; C1/C2's lock specs
unchanged and green), `RoundDistributionJobSpec`, `RegionStatSpec` (its rounds now exist).

### Checks

Logs under `target/checks/` of the worktree.

| Step | Specs | Result | Log |
|---|---|---|---|
| First compile | `Test/compile` | ok | `20261103-052642-7332.log` |
| Breakage scan | `db.*`, `services.*`, `org.intracer.wmua.*`, `security.*`, `controllers.*`, `views.*`, `api.*`, `modules.*`, `gatling.*` | 1687: 4 failed, 41 errors (votes of rounds that don't exist failing the FK; raw vote SQL in 3 specs; the chain spec's FK list) | `20261103-052945-5840.log` |
| After fixes | the new specs, the failed/errored ones, `security.*`, `MigrationNamingSpec`, `FlywayUpgradeSpec`, `MessagesCompletenessSpec` | 213: 5 failed, 1 error (two expectations of the new specs; `RegionStatSpec`'s fake rounds) | `20261103-054011-19012.log` |
| Fixed | `RoundImageJdbcSpec`, `RoundImageReadParitySpec`, `RegionStatSpec`, `CoiRoundSpec` | 36/36 | `20261103-054347-13888.log` |
| Rollback | `FlywayUpgradeSpec`, `RoundImageMigrationSpec` | 13: 12 passed, 1 skipped (fixture regeneration) | `20261103-055045-13944.log` |
| Benchmark | `RoundImageBenchmark` (fixture-v6 built) | exit 0, no parity problem | `20261103-054802-2860.log` |
| Gate | full suite (`Test/compile`, `test`, Jest included) at `617dcd67` | **1760: 1750 passed, 1 failed, 0 errors, 9 skipped, 1 pending**; Jest 253/253; 7:55. The failure: `CriteriaRateOrphansMigrationSpec` expects the shared `criteria_rate_orphan` empty, and `RoundImageCheckSpec` had archived rows there: the table is now in `SharedTestDb`/`SharedPlayApp`'s truncation lists | `20261103-055207-26244.log` |
| After the fix | `RoundImageCheckSpec`, `CriteriaRateOrphansMigrationSpec`, `CriteriaCatalogueMigrationSpec`, `CriteriaRateSpec`, `RoundImageCheckAuthorizationSpec`, `RoundImageJdbcSpec` | 58/58 | `20261103-060145-2980.log` |
| Review fixes (`c169b3e5`) | the round-image specs, the new `RoundImageLockSpec`, `CriteriaRateSpec`, `RoundCriteriaSpec`, `RoundDistributionJobSpec`, `CoiRoundSpec`, `DeletedFilesSpec`, `ConvertToInnoDbMigrationSpec`, `FlywayUpgradeSpec`, `security.*` | 184: 183 passed, 1 skipped | `20261103-062328-26772.log` |
| B1/N1 regression check | `RoundImageLockSpec` with the fixes taken out by hand (then restored) | both examples error with `Deadlock found` (1213) | (scratch run, 2026-11-03 06:26) |
| Gate | full suite at `ebd21539` (Jest included) | **1762: 1752 passed, 0 failed, 1 error, 9 skipped, 1 pending**; Jest 253/253; 7:47. The error: `LocalImageCacheServiceSpec` "be populated by initRegistry", an `AccessDeniedException` renaming a temp file under `%TEMP%` (Windows; P1 doesn't touch the service or its spec) | `20261103-062738-3256.log` |
| Rerun | `LocalImageCacheServiceSpec` alone | 78/78 | `20261103-063613-26104.log` |
| Follow-up nits (list phase's round lock) | `RoundDistributionJobSpec`, `RoundImageLockSpec`, the round-image specs, `RoundFileListSpec` | 72/72 | `20261103-064134-13104.log` |

## 14. P1b results

Branch `ukwikidev/p1b-coi-disqualification` (from `stack/59-db-access-practices` `7f88363c`, the
followups tree with P1, the robustness batch and the practices), 2026-11-03. No migration: V84's
`status` and `dq_*` columns are enough; V88 is not taken.

### What changed

| Item | Code |
|---|---|
| Disqualify (D7) | `RoundConflicts.disqualify`: one `WriteTx` transaction; the round `FOR UPDATE` first (recorded, `RoundWrites`), the gate read under it; the round's conflict-of-interest files not yet `dq` (`CoiMembers.roundFiles`, now `in`/`deleted` rows only), optionally only those asked for; their votes moved aside with their ids (`ImageJdbc.moveVotesAsideInRound`, today's `moveVotesAside`: `criteria_rate` rows follow by vote id); then the rows `dq` (`RoundImageJdbc.disqualify`: `dq_code = 'coi'`, `dq_detail` = the matched account and role, `dq_by`, `dq_at`), round_image last. A file already `deleted` only changes status. No `vote`, `vote_aside` or `criteria_rate` row is deleted any more (D9 ends) |
| Requalify (D8) | `RoundConflicts.requalify(roundId, pageIds, reason, by)`: the same gate; the `dq` rows among `pageIds` (read plainly under the round lock); their votes back with their ids and rates (`ImageJdbc.moveVotesBackInRound`, today's `moveVotesBack` scoped to the round and pages); then `RoundImageJdbc.requalifyRows` (`private[scalikejdbc]`): `in`, `dq_*` cleared. A file deleted on Commons meanwhile is `in` too, as it was: a round that isn't open keeps a deleted file's votes, and moves them aside when it opens (review finding 1; §5 first said `deleted`). The reason is a code (`mistaken_match`, `conflict_ended`), logged, not stored |
| W7 gating | `RoundConflicts.gate`, under the round's X lock in the action's transaction: refused `Distributing` (being created, or a distribution job of the round queued or running: its dedupe key read plainly), `Frozen` (`freeze_count > 0`: a next round's distribution reads its votes), `Open` (`Round.openCond`), `NotFound`. Juror votes in flight stay row-level and land or change nothing (spec'd) |
| `addVoted` guard | `ON DUPLICATE KEY UPDATE status = IF(status = 'dq', 'dq', 'in')`, then any `dq` row among the pages refuses the insert (`IllegalStateException`: the caller's transaction rolls back with its votes) |
| `sync` guard | a `dq` row stays `dq` whatever its votes (with some: invariant 3 reports it; with none: the row is kept, so the file is never distributed again); only `requalifyRows` lifts it; a row leaving `dq` loses its `dq_*` |
| Restore from Commons | `restoreVotes`, `deletedOrArchived` and `lockRoundsOfArchived` leave out the votes aside of a pair whose row is `dq` (`notDisqualified`): a restored file's disqualified votes stay aside, and an import doesn't lock a round for them |
| Repair job | its sync keeps `dq` rows (removed 0, statusFixed 0); a new `dqWithVotes` count (invariant 3 for `dq`: reported, not repaired) in the details and the WARN line |
| Invariants | 8 no longer requires votes of a `dq` row; 3 unchanged; new 9: a `dq` row has `dq_code` and `dq_at`, any other row no `dq_*` |
| Distribution | unchanged: the fill, previous-round and list anti-join (`notInRound`) already reads any `round_image` row, so "Distribute new files" and a pasted list never re-add a `dq` file (spec'd); next rounds read `in` rows only |
| Round form | "Leave out conflicts of interest" ticked on an existing round that didn't leave them out: disqualifies on a stopped round (`coi.disqualified`); on an open or frozen round only the flag is set, under the round's lock in the same transaction (review finding 4), and the flash says to stop it first (`coi.disqualify.open`, `coi.disqualify.frozen`). Saved ticked again, it does nothing (review finding 2: a requalified file stays); later matches are disqualified on the conflicts page. The browser's confirm only when a save would disqualify (`CoiCounts.toConfirm`); help and confirm texts say the votes are kept |
| Conflicts page | `GET /admin/rounds/:id/conflicts` (`RoundConflictsController.page`, `views.round.conflicts`), linked from the round's edit page: the re-check of §7. It lists the files that are conflicts now (offered for disqualifying) and the disqualified ones with code, detail, date and whether they still match ("no longer a conflict" is an ended match). `POST …/conflicts/disqualify` (ticked `pageId`s, only those still conflicts) and `POST …/conflicts/requalify` (`pageId`s and a `reason` code) |
| Authorization | page: `rolePermission(admin ∪ organizer)`, then the round's contest (`Secured.inContest`); organizers read only (no form, no checkboxes); root; 404 for no round. POSTs: the round's contest's admins and root, CSRF (`csrfField`), no contest switch |
| Audit | `coi` logger at INFO, after commit: round id, page ids, user ids, roles, the reason code; no names or accounts (the old line had accounts) |
| Stress harness | `coi_remove` became `coi_disqualify` (disqualify, then requalify; refused while the round is open) |

### Design choices

- No V88. A CHECK on the `dq_code` values, or `status = 'dq'` ⇔ `dq_code IS NOT NULL`, would
  need a table copy (ADD CONSTRAINT CHECK validates every row) for no reader that relies on
  them; the code writes both together. Can come with P3.
- The requalify reason is a code from a fixed list, not free text: the log line stays ids and
  codes (secure-coding §13), and nothing stores it (§5: "the reason goes to the audit log").
  Today a requalify clears `dq_*`, leaving only the INFO log line. **Decided (user,
  2026-11-03), as recommended:** requalifications are recorded in an append-only
  `round_image_event` table (round, page, action, code, by, at, prior status), written in the
  action's transaction; its own small batch, landing **before P3**. It also lifts the known
  limitation below (the prior status). Free text not needed.
- The round form on an open round sets the flag and refuses the disqualification (W7) rather
  than stopping the round itself. **Decided (user, 2026-11-03), as recommended:** no one-step
  "stop and disqualify". A separate "Stop the round" button with its own confirm on the
  conflicts page is optional, and goes with the event-table batch.
- A file deleted on Commons meanwhile is requalified `in` (review finding 1), not `deleted`
  as §5 first said: the gate only admits rounds that aren't open, which keep deleted files'
  votes until they open.
- **Known limitation:** a file deleted on Commons while the round was open (votes aside,
  `deleted`), then disqualified, comes back `in` with its votes when requalified, not
  `deleted`: without its prior status it can't be told from a file deleted while the round was
  stopped (which keeps its votes). The round's opening moves the votes aside again. Pinned by
  `CoiRoundSpec`; the event-table batch (prior status, decided 2026-11-03) fixes it. **Fixed
  by V88 (§15)**: it comes back `deleted`, its votes aside; `CoiRoundSpec` pins that now. Two
  rarer residual cases remain (§15, "Design choices").
- Frozen rounds are refused too (beyond W7's wording): a next round's distribution reads the
  frozen round's votes, and moving them mid-read would make its fill inconsistent.
- A `dq` row with no votes left (only after a hand edit or the test-only deletes) is kept, so
  the file stays out; requalifying it removes the row (P1's invariant 8: no votes, no row) and a
  later "Distribute new files" may add the file again.
- Organizers may view the conflicts page (D20); jurors never (it tells which files are out).

### Review (independent, of `793e5c00`)

No blocker. Fixed in the review-fix commit:

| # | Finding | Fix |
|---|---|---|
| 1 | Requalifying a file deleted on Commons while the round was stopped made it `deleted`, its votes hidden from a closed round's results: not the state before | requalify always gives `in` with the votes; the round's opening moves a deleted file's votes aside as for any file; `lockDeleted` dropped; spec "requalify a file deleted on Commons meanwhile as it was" (deleted while stopped, then the round opens, then restored) |
| 2 | Every save of the round form with the flag set disqualified again, undoing a "mistaken match" requalify | the form disqualifies only when the flag goes from off to on; no confirm otherwise; spec "not disqualify again on a later save" |
| 3 | The page's pre-check (`rolePermission`) would refuse an admin of the round's contest whose session is in another contest | not a bug: `Secured.permitted` tries the user's other active memberships, then `withRound` checks the round's contest. Spec added: an admin of contest 10 in a contest-20 session (where a juror) gets the page and acts, the session's contest unchanged by the POST; a juror of 10 who is admin of 20 is refused the page and both POSTs from either session |
| 4 | On an open or frozen round the form set the flag in its own autocommit after the gate's transaction | `disqualify(excludeCoi = true)` sets it under the round lock in the gate's transaction for `Open`/`Frozen` too |
| 5 | The confirm asked "Disqualify…?" on an open round | `CoiCounts.toConfirm`: 0 (no confirm) when the round is open or already leaves them out; the help text says what happens. Not done: the conflicts page's warning for a frozen round (the action's flash says it) |
| 6 | Stale `removeFromRound` references; `VoteJdbc.deleteVotes` has no app caller and would delete a `dq` file's aside votes | references fixed; `deleteVotes` kept for the test-only writers (`TestVoteWrites`) and its guard spec, noted here |
| 7 | `sync(…, requalify)` public | `requalifyRows`, `private[scalikejdbc]` |
| 8 | No invariant for `dq_*` | invariant 9 |
| 9 | Heading "round 3 3" for an unnamed round | the round's name, if any |

Follow-up review (of `0196326f`, approve): the rollback note corrected (above, "Pre-deploy");
the form's open-round message points to the round's conflicts page (linked on the rounds list,
flash `coiRound`), and a tick while the round is being distributed says so also when it has no
conflicts; requalify locks the files (`ImageJdbc.lockFiles`) right after the gate, so its order
is round → images → vote … (spec'd against a restore from Commons); the limitation of a file
deleted while the round was open documented and pinned; frozen rounds in the page's warning and
the form's confirm; the page says its disqualify doesn't change `exclude_coi`. Cosmetic
follow-up, not done: the conflicts page calls `Secured.switchingContest` itself after
`withAuthOn` has (both pick the round's contest when they differ from the session's; harmless,
one is enough).

On the open items the reviewer recommends: record who requalified, when and the reason code
durably (a later `round_event` table, before P3), free text not needed; and no "stop and
disqualify" in one step (stopping a round is visible to jurors and stays explicit).

### Pre-deploy

Read-only, production, as the admin user:
```sql
SELECT status, COUNT(*) FROM round_image GROUP BY status;   -- no 'dq' rows yet: nothing wrote them
SELECT COUNT(*) FROM round_image WHERE dq_code IS NOT NULL OR dq_by IS NOT NULL;  -- 0
```
No migration, no rollback SQL. A behaviour change to announce: "Leave out conflicts of
interest" on an open round now only sets the flag; stop the round and use its conflicts page to
disqualify.

**Rolling back to a release before P1b** (corrected after the follow-up review; the first
version of this note was wrong about `sync`, which at `7f88363c` keeps `dq` while the votes are
aside). What the older code does to `dq` rows:
- **Data loss:** its `CoiMembers.roundFiles` has no status filter, so it lists `dq` files; its
  `leaveOutConflicts` then calls `removeFromRound`, whose `deleteVotes` runs over `vote` and
  `vote_aside`. Any save of an open round's form with "Leave out conflicts of interest" ticked
  deletes the `dq` files' aside votes and criteria rates for good.
- Its `deletedOrArchived`/`restoreVotes` bring back every `dq` file the next import sees.

Before rolling back, run (read-only):
```sql
SELECT round_id, COUNT(*) FROM round_image WHERE status = 'dq' GROUP BY round_id;
```
For each round listed: requalify its files first (the conflicts page, rounds stopped), or, if
they must stay disqualified, announce that the box must not be saved ticked on those rounds and
accept that an import may bring their votes back.

### Files

New: `app/controllers/RoundConflictsController.scala`, `app/views/round/conflicts.scala.html`,
`test/controllers/RoundConflictsAuthorizationSpec.scala`,
`test/db/scalikejdbc/CoiDisqualifyLockSpec.scala`; routes `GET /admin/rounds/:id/conflicts`,
`POST /admin/rounds/:id/conflicts/disqualify`, `POST /admin/rounds/:id/conflicts/requalify`;
message keys `coi.*` (en, fr, uk, ru). Changed: `RoundConflicts` (rewritten), `RoundImageJdbc`,
`ImageJdbc`, `CoiMembers.roundFiles`, `RoundImageInvariants`, `RoundImageCheckHandler`,
`RoundController.leaveOutConflicts`, `editRound.scala.html`; specs `CoiRoundSpec` (the
disqualification block replaces "leave out now"), `CoiAuthorizationSpec`, `RoundWritesGuardSpec`,
`StructuralWritesSpec` (`RoundImageJdbc#disqualify`, requireRounds), `MessagesCompletenessSpec`
(prefixes `coi.requalify.reason.`, `coi.code.`), `test/stress/WritePathStress.scala`.

### Specs

- `CoiRoundSpec` "disqualifying conflicts of interest": votes and criteria rates kept exactly
  (ids, rates); rows `dq` with code, detail, by, at; counts; another round untouched; a restore
  from Commons keeping them aside; a second run finding nothing. Requalify restoring the exact
  votes, criteria rates and rows (`dq_*` cleared); requalify of a file deleted on Commons
  while the round was stopped → `in` with its votes, the round's opening → `deleted`, a
  restore → `in`; requalify of a file deleted while the round was open → `in` (the known
  limitation below, pinned); only the files asked for. W7 refusals (open, being
  created, frozen, job queued, missing) writing nothing; requalify refused on an open round.
  "Distribute new files" and a pasted list never re-adding a `dq` file (also once the role is
  gone), requalify bringing it back. The guard against a vote insert (refused), a sync (kept,
  invariant 3 reported), a `dq` row with no votes, the repair job (kept, `dqWithVotes` 1).
  Invariants after each step.
- `CoiDisqualifyLockSpec` (INNODB_TRX): a requalify waits for a restore from Commons holding the
  file (it locks the images after the round, before the votes) and both commit; a disqualify
  waits for a juror's vote in flight and moves
  the voted row (the requalify gives back that rate); a vote after it changes nothing; it waits
  for a distribution chunk holding the round, then disqualifies the chunk's file too; with the
  chunk's job active it waits, then refuses and writes nothing.
- `RoundConflictsAuthorizationSpec`: the page for the admin (forms), the organizer (no forms),
  root; juror, board, another contest's admin 403; missing 404. POSTs by the admin and root 303;
  juror, organizer, board, another contest's admin (also for this contest's round) 403 with
  nothing written; missing 404; signed out; an open round refused with the message; no reason,
  an unknown reason, an empty selection refused; CSRF (missing, forged, then valid) on both.
- `CoiAuthorizationSpec`: the round form on an open round sets the flag only; on a stopped one
  it disqualifies (admin, root); CSRF.
- `RoundWritesGuardSpec`: the new writers refuse without the round lock; a restore leaves `dq`
  votes aside.

### Checks

Logs under `target/checks/` of the worktree.

| Step | Specs | Result | Log |
|---|---|---|---|
| First compile | `Test/compile` | ok after `I18nSupport` on the new controller | `20261103-160445-27664.log` |
| Affected, first run | `CoiRoundSpec`, `CoiDisqualifyLockSpec`, `RoundWritesGuardSpec`, `RoundConflictsAuthorizationSpec`, `CoiAuthorizationSpec`, `StructuralWritesSpec`, `MessagesCompletenessSpec`, `RoundImageJdbcSpec`, `RoundImageCheckSpec` | 83: 81 passed, 2 failed, both expectations of the new specs (a vote for a `dq` file violates invariant 1 too; a signed-out POST redirects to the login page) | `20261103-161326-24844.log` |
| Affected, fixed, with the guards and neighbours | the two specs, `db.MigrationSchemaSpec`, `db.VoteRowLockSpec`, `db.MigrationNamingSpec`, `security.*`, `DeletedFilesSpec`, `RoundDistributionJobSpec`, `CriteriaRateSpec`, `RoundImageLockSpec`, `RoundImageReadParitySpec`, `WritePathLockSpec`, `RoundImageCheckAuthorizationSpec`, `MutationAuthorizationSpec`, `CoiMembersSpec`, `RoundRedistributionFiltersSpec` | 212/212 | `20261103-161800-4800.log` |
| Gate | full suite at `793e5c00` (`Test/compile`, `test`, Jest included) | **1922: 1912 passed, 0 failed, 0 errors, 10 skipped, 1 pending**; Jest 253/253; 11:37 | `20261103-162154-25116.log` |
| Review fixes, affected | `CoiRoundSpec`, `CoiDisqualifyLockSpec`, `RoundWritesGuardSpec`, `RoundConflictsAuthorizationSpec`, `CoiAuthorizationSpec`, `security.*`, `MessagesCompletenessSpec`, `RoundImageJdbcSpec`, `RoundImageCheckSpec`, `DeletedFilesSpec`, `MigrationSchemaSpec`, `VoteRowLockSpec`, `RoundDistributionJobSpec`, `RoundImageLockSpec`, `RoundImageReadParitySpec`, `MutationAuthorizationSpec` | all passed but 2 expectations of the specs themselves (the form's flag now set on an open round; no confirm on an open round) | `20261103-164620-26792.log` |
| Review fixes, those rerun | `CoiRoundSpec`, `CoiAuthorizationSpec`, `RoundConflictsAuthorizationSpec` | 39/39 | `20261103-165040-14512.log` |
| CI | PR #244 at `ad9d3003` (before the review fixes): CI and deployment tests | success (8m04s, 5m37s) | GitHub Actions |
| CI | PR #244 at `0196326f`: deployment tests success (4m29s); CI failed once on `LocalImageCacheServiceSpec` "not create files for sizes where px >= image.width" (an async timing assertion; P1b doesn't touch it), passed on the rerun of the failed job (attempt 2) | GitHub Actions run 41658076144 |
| Follow-up review fixes | `CoiRoundSpec`, `CoiDisqualifyLockSpec`, `RoundConflictsAuthorizationSpec`, `CoiAuthorizationSpec`, `RoundWritesGuardSpec`, `MessagesCompletenessSpec`, `StructuralWritesSpec`, `RawHtmlSinksSpec`, `RouteAuthSpec`, `VoteRowLockSpec`, `DeletedFilesSpec`, `RoundImageJdbcSpec` | 109/109 | `20261103-171838-6136.log` |

## 15. `round_image_event` results

Branch `ukwikidev/round-image-event` (from `origin/stack/78-test-pool-hikari` `9ba40a68`, PR
#261, the stack tip), 2026-11-04. Roadmap queue item 4, decided by the user 2026-11-03 (§14,
"Design choices"), before P3. Migration **V88** (`V88__Round_image_event.sql`).

### Migration V88

```sql
CREATE TABLE IF NOT EXISTS round_image_event (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  round_id     BIGINT UNSIGNED NOT NULL,
  page_id      BIGINT          NOT NULL,
  action       VARCHAR(16)     NOT NULL,                 -- disqualify | requalify
  code         VARCHAR(16)     NOT NULL,                 -- a dq_code, or a requalify reason
  prior_status VARCHAR(16)     NOT NULL,                 -- the row's status before
  new_status   VARCHAR(16)     NULL DEFAULT NULL,        -- after; NULL: the row went (no votes)
  created_by   BIGINT UNSIGNED NULL DEFAULT NULL,        -- users.id, no foreign key
  created_at   TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY round_image_event_round_page_idx (round_id, page_id, id),
  CONSTRAINT round_image_event_round_id_fk FOREIGN KEY (round_id) REFERENCES round (id) ON DELETE CASCADE,
  CONSTRAINT round_image_event_action_chk CHECK (BINARY action IN ('disqualify', 'requalify')),
  CONSTRAINT round_image_event_prior_status_chk CHECK (BINARY prior_status IN ('in', 'dq', 'deleted')),
  CONSTRAINT round_image_event_new_status_chk CHECK (new_status IS NULL OR BINARY new_status IN ('in', 'dq', 'deleted'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
```

- Types follow `round_image` (`round_id BIGINT UNSIGNED`, `page_id BIGINT`, statuses and codes
  `VARCHAR(16)`, `TIMESTAMP(3)`) and `job` (`created_by`, `created_at`). A surrogate key: a file
  has many events. Ids grow with time (each action holds its round's lock), so "latest" and
  "in order" are by id.
- `round_image_event_round_page_idx`: a file's history in order and its latest event (the
  requalify's read, a range of the index per page), and a round's history (a range, then a
  sort of the round's events, tens to hundreds). No index on `created_at`: nothing reads by it.
- Foreign key to `round` with cascade, as `round_image`'s (db practices §2.4): its check
  share-locks the round row, which the writer holds for update. **None to `images`**: its check
  would share-lock the image row after `round_image`, out of the lock order (a disqualify doesn't
  lock its images); an image deleted takes its `round_image` rows and keeps its events, which the
  round's history still shows (as `#pageId`, the title gone). None to `users`: a deleted account
  keeps its history (shown as `#id`).
- **The cascade to `round` stays** (the app never deletes rounds; it matches `round_image` and
  `round_criterion`). If events should ever outlive their round, drop the foreign key rather
  than switch it to RESTRICT, which would block a round's deletion on its history. The user may
  revisit this.
- CHECKs on action and statuses only: the codes grow with P3 (`manual`), and widening a CHECK on
  a growing table copies it; the code checks them (`require`).
- New and empty: a `CREATE TABLE`, no backfill, cheap on 10.6 and 10.11 (no copy, no lock on an
  existing table but the `round` FK's metadata). Rerunnable (`IF NOT EXISTS`). `dq` rows from
  before V88 have no event: a requalify of one falls back to the behaviour before (`in`).

### What changed

| Item | Code |
|---|---|
| Events | `RoundImageEventJdbc` (new): `record` (`private[scalikejdbc]`, `requireRounds`, one multi-row `INSERT` per 2,000 files), `latestDisqualify`, `byRound` (newest first, 500 at most), `ofFile` |
| Disqualify | `RoundConflicts.disqualify` reads the files' statuses (`RoundImageJdbc.statuses`, plain, under the round's lock) **before** moving their votes aside (which makes them `deleted`); `RoundImageJdbc.disqualify(…, prior)` (`prior` required, no default: a row to mark that it lacks is refused before any write, so no caller, P3's D16a included, records a guessed status) marks them `dq`, then records one `disqualify` event per row it marked (code `coi`, prior `in` or `deleted`, new `dq`). Rows already `dq` get no event |
| Requalify | `RoundConflicts.requalify`, after the gate and `lockFiles`: the files whose latest event is a `disqualify` with prior `deleted` and whose image is still deleted (`deleted_at`, read under the image's lock) keep their votes aside; the others' votes move back. `RoundImageJdbc.requalifyRows(…, reason, by)` lifts `dq` (now `in`, `deleted`, or no row when no vote is left) and records one `requalify` event per row that left `dq` (code `mistaken_match` / `conflict_ended`, prior `dq`, new the status, NULL for a removed row). `moveVotesBack` no longer lifts the round's rows itself (its `requalify` pairs are skipped by its sync), so each lift is recorded once |
| Lock order | round → images → vote → criteria_rate → vote_aside → round_image → **round_image_event** (§5, db practices §7.1) |
| Guards | `StructuralWritesSpec` scans `round_image_event` and lists `RoundImageEventJdbc#record` (requireRounds); `RoundWrites` refuses `record` without the round lock |
| History | the round's conflicts page (`RoundConflicts.Review.history`, `views.round.conflicts`): "History", newest first, 500 at most: when, file, action, reason code, status before → after, by (the account's name; `#id` for an account deleted or soft-deleted, `deleted_at`). Message keys only (`coi.history.*`, `coi.event.*`, `coi.status.*`; codes through `coi.code.*` and `coi.requalify.reason.*`), en/fr/uk/ru. Shown to all who may see the page (the round's admins and organizers, root), also on an open round; never jurors (D20: the page is 403 for them) |
| Stop the round | on an open round's conflicts page, for its admins (`mayEdit`, not while distributing): a form to the rounds list's own action (`POST /admin/setround`, `currentId`, `setActive=false`, CSRF field; `withAdminRound` unchanged) with `returnTo=conflicts` (a fixed word, never a path), which redirects back to the page with `coi.round.stopped`; a confirm (`data-confirm`, `public/javascripts/confirm-submit.js`, loaded only where the button is). No new action, no one-step "stop and disqualify" |

Status changes recorded: **disqualify and requalify only**. Not recorded, decided from §5 and
§14: a file deleted on Commons or restored (`in` ↔ `deleted`, by the imports and the round's
opening: they follow Commons and are in the import job's details and logs), `sync`'s recounts and
the repair job (they bring rows in line with the votes, no decision), rows added by a
distribution (the job records them, `added_by_job_id`). P3's D16a (`removeImage` as `dq`,
`dq_code = 'manual'`) goes through `RoundImageJdbc.disqualify` and so records itself.

### Design choices

- **Both actions, not only requalify.** The decision said requalifications; the prior status a
  requalify restores is only known if the disqualify recorded it, so both are recorded. A new
  `new_status` column beside the decided `prior_status`: a requalify's event says which status it
  restored (`in`, `deleted`, or the row removed).
- **The prior status is read before the move.** Moving a file's votes aside makes its row
  `deleted` (sync) before it is marked `dq`, so the row's status at the mark is not the prior one;
  `RoundConflicts.disqualify` reads it first, under the round's lock (every writer of the round's
  statuses holds it; a juror's vote changes no status).
- **Restore `deleted` only while still deleted.** A file restored on Commons while disqualified
  keeps its votes aside (P1b); requalified, it comes back `in` with them, as the restore would
  have done. Invariant 4 (a `deleted` row's image is deleted) holds either way.
- **No event: as before V88.** Rows disqualified before V88 come back `in` (the known limitation
  for those only). Production can't have such a row: nothing writes `dq` before P1b, and P1b and
  V88 ship together in release 2 (V82–V88). Only a test or a hand edit makes one.
- **Residual limitations** (rare, both keep the invariants; telling them apart needs the round's
  open/closed history, which nothing records). The event keeps the status at the disqualify, not
  what happened to the file while it was disqualified:
  - **A:** disqualified on a stopped round (`in`), then the round opened, then the file deleted on
    Commons (its votes already aside), then stopped and requalified: it comes back `in` with its
    votes, though an opening would have made a file not disqualified `deleted`. The round's next
    opening moves its votes aside.
  - **B:** prior `deleted`, restored on Commons while disqualified, deleted again while the round
    is stopped, then requalified: it comes back `deleted` with its votes aside, where review
    finding 1's rule (a file deleted while the round is stopped keeps its votes) says `in`. A
    restore on Commons brings them back.
  Both are pinned by `CoiRoundSpec` ("pin the residual cases").
- **"Stop the round" reuses the existing action.** It is the rounds list's start/stop
  (`RoundService.setCurrentRound` → `Round.setActiveGuarded`), with the same authorization and
  CSRF; only the redirect target is chosen by a fixed form word. The round's status flow is
  unchanged; disqualifying stays a second, explicit step (§14: no one-step "stop and
  disqualify").
- The history shows the account's full name (D20, §14: organizers see who acted): account data
  shown to the round's admins and organizers, no request text; a soft-deleted account shows as
  `#id`. The log lines stay ids and codes (secure-coding §13).

### Pre-deploy (V88)

V88 ships in release 2 with V82–V87 (roadmap, "Release 2"): before the deploy production is at
release 1, its last Flyway row **81**, and neither `round` (V82 renames `rounds`) nor
`round_image` (V84) exists yet, so nothing here may read them. Read-only, production, as the
admin user, before the deploy:
```sql
SELECT VERSION();                                                    -- 10.6 today; 10.11 after the move
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;  -- 81, success 1
SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'round_image_event';            -- 0
SELECT ENGINE, ROW_FORMAT FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rounds';                        -- InnoDB, Dynamic (V82 renames it round)
SELECT COLUMN_TYPE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rounds' AND COLUMN_NAME = 'id'; -- bigint(20) unsigned (V84's key needs it too)
```

A `dq` row without an event can't exist on production: nothing writes `dq` before P1b, and P1b
and V88 ship together. Post-deploy (read-only), as a check that nothing wrote `dq` outside the
two actions:
```sql
SELECT COUNT(*) FROM round_image ri WHERE ri.status = 'dq' AND NOT EXISTS
  (SELECT 1 FROM round_image_event e WHERE e.round_id = ri.round_id AND e.page_id = ri.page_id);  -- 0
```

- **Deploy:** V88 runs at start-up with the other pending migrations; a `CREATE TABLE` of an
  empty table, well under a second on both servers. Nothing to backfill, no job to run.
- **Rollback after `reopen`** (tested forward, back, forward again: `FlywayUpgradeSpec` "V88's
  rollback SQL after reopen"; the schema after it equals V87's):
  ```sql
  DROP TABLE round_image_event;
  DELETE FROM flyway_schema_history WHERE version = '88';
  ```
  The older code ignores the table. What it loses: the history, and a requalify then restores
  `in` for every file (the behaviour before V88). Before `reopen`, `wlxjury-release restore`.
- Announce: the conflicts page shows the round's disqualify/requalify history to its admins and
  organizers, and a "Stop the round" button on an open round.

### Files

New: `conf/db/migration/default/V88__Round_image_event.sql`,
`app/db/scalikejdbc/RoundImageEventJdbc.scala`, `public/javascripts/confirm-submit.js`,
`javascript-test/confirm-submit.test.js`, `test/db/scalikejdbc/RoundImageEventSpec.scala`.
Changed: `RoundImageJdbc` (`disqualify` with `prior` and its events, `requalifyRows` with reason
and by and its events, `statuses`), `RoundConflicts` (prior read, the requalify's prior status,
the history), `ImageJdbc.moveVotesBack`, `RoundController.setRound` (`returnTo`),
`views/round/conflicts.scala.html`, `conf/messages.{en,fr,uk,ru}`; specs `CoiRoundSpec`,
`CoiDisqualifyLockSpec`, `RoundConflictsAuthorizationSpec`, `StructuralWritesSpec`,
`MessagesCompletenessSpec` (prefixes `coi.event.`, `coi.status.`), `FlywayUpgradeSpec`,
`SharedTestDb`/`SharedPlayApp` (truncate the table).

### Specs

- `CoiRoundSpec`: the pinned case now passes the fix: a file deleted while the round was open,
  disqualified, requalified → `deleted`, its votes aside with their ids and rates, the events
  `disqualify coi deleted→dq`, `requalify mistaken_match dq→deleted`; the round's opening leaves
  it; a restore brings the votes back. Prior `in` (every conflict, exact votes, criteria rates and
  rows; events `in→dq`, `dq→in`). Restored on Commons while disqualified → `in`. No event (rows
  from before V88, the events deleted) → `in`, the opening moves it aside. Repeated (in→dq→in,
  deleted→dq→deleted, deleted→dq→in after a restore): six events in order, each requalify from
  the latest disqualify. Refused actions (open round) and files already `dq` record nothing. The
  residual cases A and B pinned as they behave (follow-up).
- `RoundImageEventSpec`: the table as created on the test server (10.6: columns, key, FK with
  cascade, the three CHECKs, engine, collation); CHECKs refuse an unknown action or status, and
  are case-sensitive (`'Disqualify'`, `'IN'`, `'DQ'` refused; follow-up); the code length is
  checked; a disqualify without a row's prior status refused before any write (follow-up);
  events go with their round; `record` refused without the round lock
  (and outside a transaction); **a failing event (its CHECK) rolls back the moved votes and the
  `dq` rows**; a transaction failing after the event takes it with it; a requalify records only
  the rows that left `dq`, once; a `dq` row with no votes left, requalified, recorded with
  `new_status` NULL.
- `CoiDisqualifyLockSpec` (INNODB_TRX): a disqualify waiting for a juror's vote in flight on the
  file records it `in→dq`, and the requalify `dq→in` with the juror's rate back; one waiting for
  a distribution chunk records the chunk's file too; one refused after waiting records nothing.
- `RoundConflictsAuthorizationSpec`: the history for the round's admin and organizer (also on an
  open round, every event's row, the reason and status texts), "no history yet", a soft-deleted
  account shown as `#id` (follow-up); 403 without it
  for a juror, the board and another contest's admin. "Stop the round": offered to the admin on
  an open round only, never to the organizer (nor its script); the admin's POST stops the round
  and returns to the page, after which a disqualify goes through; without `returnTo` the rounds
  list as before; 403 for a juror, an organizer, the board, another contest's admin, and this
  contest's admin for another contest's round, the round still open; signed out refused; CSRF
  (missing refused, valid accepted).
- `FlywayUpgradeSpec`: V88's rollback SQL gives back V87's schema and history; V88 again creates
  the table, empty; a rerun of its statement changes nothing.
- Jest `confirm-submit.test.js`: asks with the form's text, sends when confirmed, drops when
  declined, leaves other forms alone.
- Browser tests: the conflicts page is not in the page tour (`browser-test/tests/pages.spec.js`),
  so its accessibility scan doesn't cover it; nothing to rerun there.

### Checks

Logs under `target/checks/` of the worktree.

| Step | Specs | Result | Log |
|---|---|---|---|
| First compile | `Test/compile` | ok | `20261104-205049-5040.log` |
| Affected | `CoiRoundSpec`, `CoiDisqualifyLockSpec`, `RoundImageEventSpec`, `RoundConflictsAuthorizationSpec`, `StructuralWritesSpec`, `MessagesCompletenessSpec`, `RoundWritesGuardSpec`, `FlywayUpgradeSpec`, `MigrationSchemaSpec`, `MigrationNamingSpec`, `CoiAuthorizationSpec`, `RawHtmlSinksSpec`, `RouteAuthSpec`, `MutationAuthorizationSpec`, `RoundImageJdbcSpec`, `RoundImageCheckSpec`, `DeletedFilesSpec` | **188: 187 passed, 0 failed, 1 skipped** (a `FlywayUpgradeSpec` fixture generator, off by default) | `20261104-205822-6520.log` (the first try, `20261104-205730-25996.log`, stopped at a spec's compile error) |
| Stress harness | `db.stress.WritePathStress`, baseline, 90 s (`-Dstress.seconds=90`), all 16 paths; `coi_disqualify` now writes the events | `coi_disqualify` 85 operations, 0 deadlocks, 0 lock wait timeouts, 0 failures; invariants clean. 12 server deadlocks, every one with the test-only `remove_unrated` (victim each time) against juror votes or a criteria change; 73 `distribute_new`/`distribute_list` count mismatches against the test-only `remove_image`/`set_round` (write paths plan §2, break 7). None involves `round_image_event`. The base commit `9ba40a68`, run by the reviewer the same way: 14 deadlocks from the same test-only paths, 66 count mismatches, `coi_disqualify` 85 operations with 0 deadlocks: the branch's are pre-existing | `20261104-210109-28684.log`, `target/stress/write-paths-20261104-210150/`; base: the reviewer's summary of the same harness on `9ba40a68` (not kept in the repository) |
| Gate | full suite at `e3170f17` (`Test/compile`, `test`, Jest included), MariaDB 10.6 container | **1998: 1990 passed, 0 failed, 0 errors, 8 skipped, 1 pending**; Jest 22 suites, 285/285; 10:47 | `20261104-210412-26052.log` |

| Review follow-ups | `CoiRoundSpec`, `RoundImageEventSpec`, `RoundConflictsAuthorizationSpec`, `CoiDisqualifyLockSpec`, `MessagesCompletenessSpec`, `StructuralWritesSpec`, `FlywayUpgradeSpec`, `RoundWritesGuardSpec`, `MigrationSchemaSpec` | **85: 84 passed, 0 failed, 1 skipped** (the fixture generator) | `20261104-213308-25896.log` |

Reviewed (independent, of `f9c97645`): approved, nothing blocking; its follow-ups are the
commit after it (required `prior`, residual cases A and B named and pinned, release 2's
pre-deploy, deleted accounts as `#id`, the wording, the case-sensitive CHECKs, the cascade note).

Not run: the suite on MariaDB 10.11 (`-Dwlxjury.test.mariadb-image`); V88's DDL has no
version-dependent part (CI runs both).

## 16. P2a results

Branch `ukwikidev/p2a-counts` (from `origin/stack/79-round-image-event` `04c1d310`, PR #262,
the stack tip), 2026-11-05. Roadmap queue item 5, first part; the design is W6 (§9 "P2a", the
write-path plan's decisions), replacing the deltas. Migration **V89**
(`V89__Round_image_counts.sql`). Shadow only: no reader uses the counts (P2b).

### Migration V89

```sql
ALTER TABLE round_image
  ADD COLUMN IF NOT EXISTS assigned   INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS voted      INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS approvals  INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS rejections INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS rate_sum   INT NOT NULL DEFAULT 0,
  ALGORITHM = INSTANT;
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS counts_gen      BIGINT UNSIGNED NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS counts_gen_done BIGINT UNSIGNED NOT NULL DEFAULT 0,
  ALGORITHM = INSTANT;
CREATE TABLE IF NOT EXISTS round_image_dirty (
  round_id BIGINT UNSIGNED NOT NULL,
  page_id  BIGINT          NOT NULL,
  PRIMARY KEY (round_id, page_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
```

- The counts are §4's, over the pair's `vote` rows: a `deleted` or `dq` row (votes in
  `vote_aside`) counts 0. A NULL rate counts as unrated (§11's open item, decided here: it is
  neither voted nor summed, as `COUNT`/`SUM` treat it). Signed `INT`: `assigned - voted` never
  goes out of range.
- **No backfill.** The defaults (`counts_gen` 1, `counts_gen_done` 0) make every round, and every
  round created later, dirty; the recompute fills the counts in the background after the deploy.
  Measured on the Gatling fixture (MariaDB 10.6.22): V89's three statements 27, 30 and 18 ms; the
  rollback's 17, 32 and 26 ms; the recount of the whole fixture (2 rounds, 76,734 rows, all
  changed) 15.5 s in 40 windows (~390 ms each: every row written). Production's 0.3–1M rows
  (§13's estimate): ~1–3.5 minutes of background work after the deploy, ~4–12 minutes of wall
  time at the timer's 2 s budget per ~7 s (deploy note), in windows that hold a round's lock
  ~0.4 s each.
- `round_image_dirty` has **no foreign key**: its check would share-lock the round (or the image)
  in every vote, and a vote never locks the round. Entries of a round that no longer exists are
  dropped by the recompute.

### The design against W6's list

| W6 condition | As built |
|---|---|
| a dirty-generation counter, or the mark cleared before the read | Both, per kind of mark. Bulk writes: `round.counts_gen`, bumped in the writer's transaction; a full recount reads it in its first window and sets `counts_gen_done` to that value in its last, so a bulk write between two windows leaves `counts_gen_done < counts_gen` and the round is recounted again (`RoundCountsSpec`). Votes: the entry is locked before the votes are read and deleted after, by key, in the same transaction; a vote that marks it later waits, then re-inserts it |
| each window's `round_image` rows locked `FOR UPDATE` before the vote read, at READ COMMITTED | The window's rows `FOR UPDATE`, then their dirty entries `FOR UPDATE`, then a plain read of the votes (READ COMMITTED: the latest committed), then the writes. The entry lock is what a vote meets: a vote holding its entry is waited for and read; one arriving after waits for the window, and its entry survives (`RoundCountsLockSpec`, INNODB_TRX both ways) |
| a per-file dirty set | `round_image_dirty (round_id, page_id)`, upserted by a juror's vote whose rate changes (`VoteJdbc.rateIn`, `CriteriaRate.updateRateIn`; not when the rate is unchanged, a partly rated criteria vote included) as its last statement |
| the bulk writers' marks persisted on the round row | `RoundCounts.markRounds`, under the round lock the writer holds (`requireRounds`): no new lock. The production writers: `RoundImageJdbc.addVoted` (every vote insert: a distribution chunk, `VoteJdbc.create`/`batchInsert`); `ImageJdbc.moveVotesAside`/`moveVotesBack` through `syncPairs` (a file deleted or restored on Commons, a round's opening, a distribution's end, a disqualify's and a requalify's moves); `RoundImageJdbc.requalifyRows`; the repair job's `sync`. `VoteJdbc.deleteVotes` marks too, but only test-only writers call it (`TestVoteWrites`: `removeImage`, `removeUnrated`, `setRound`, out of the app since W8). `created_at = created_at` in the `UPDATE`: production's `round.created_at` has `ON UPDATE CURRENT_TIMESTAMP` (V86) |
| exact consumers read the votes directly | Nothing reads the counts yet: top-N distributions, the CSV and the results read `vote` as before. P2b must keep them there (or recount first); `RoundCounts`' scaladoc says so |

**Writes that need no mark**, since the counts read only `vote`'s rows and rates: a change of
a round's criteria (`RoundCriterion.update`: before the first vote no rate is set; after it only
a criterion's renaming is taken, which moves `criteria_rate` rows and leaves `vote.rate`);
monument id and region updates (no count reads them); a juror stopped or started on the round
(`round_user.active`: their vote rows stay). **For P2b:** the counts therefore include a stopped
juror's votes, as today's grouped reads over `vote` do (`ImageDbNew` doesn't join `round_user`);
P2b keeps that, or leaves them out on both sides.

**The one assumption: READ COMMITTED.** A window reads the votes plainly after locking its rows
and dirty entries; only at READ COMMITTED does that read see every vote it waited for (at
REPEATABLE READ a snapshot could be older). Every connection is READ COMMITTED (db practices
§6.1, `/ready` checks it), and since the review the recompute checks too: each window's
transaction reads `@@tx_isolation` first (MariaDB 10.6 has no `@@transaction_isolation`) and, at
any other level, the tick stops, writes nothing, keeps the marks and logs
`round_counts_skipped reason=isolation level=…` at WARN (`RoundCountsSpec`, on a REPEATABLE READ
pool). The counts catch up once the pool is fixed.

**Why a table, not a column on `round_image`.** A `counts_dirty` column would make every vote
write the row all the file's jurors share, and finding the dirty rows would need a secondary
index (written by every first vote) or a scan of each round. The table is tiny, a vote writes
only its entry, and the recompute finds work with a loose scan of its key.

**The upsert, not `INSERT IGNORE`.** The first build used `INSERT IGNORE`: its duplicate check
takes a shared record lock, and when the recompute deleted an entry while two votes waited on
it, both held shared locks on the delete-marked record and both upgraded to re-insert it:
`RoundCountsLockSpec`'s 20 jurors on one file saw **56 server deadlocks and 2 votes failing**
after `WriteTx`'s 3 attempts (log `20261105-000751-19828.log`). `INSERT … ON DUPLICATE KEY UPDATE
round_id = round_id` takes an exclusive record lock on the duplicate (no gap), so the waiting
votes queue one at a time: **0 deadlocks** since (10.6 and 10.11).

**Lock order** (§5, db practices §7.1): `round` → `images` → `vote` → `criteria_rate` →
`vote_aside` → `round_image` → `round_image_dirty` → `round_image_event`. A juror's vote:
`vote` (→ `round_user`, `round_criterion` shared → `criteria_rate`) → `round_image_dirty`, never
`round` or `round_image`. The recompute: `round` (for update) → `round_image` →
`round_image_dirty`, `vote` read without a lock. No other transaction locks `round_image_dirty`.

### The recompute

- `db.scalikejdbc.RoundCounts`: the marks, `fileWindow` (up to 2,000 marked files of a round,
  lowest page ids first), `roundWindow` (a full recount's window by page id; it waits while the
  round is being distributed, `distributing` or its distribution job active, whose chunks bump the
  generation), `drift` and `isClean`. Counts are written only where they differ, in one JDBC batch
  of key updates, not a multi-table `UPDATE … JOIN`: as far as InnoDB's source reads (a
  multi-table update is not among the statements it reads consistently at READ COMMITTED), it
  share-locks the read side, which would lock the votes; not tested here, the plain read
  avoids the question.
- `services.counts.RoundCountsRecompute.runOnce`: one tick, each window its own `WriteTx`
  (retried on 1213): first every round's marked files, then full recounts (those under way, then
  open rounds, then the newest), stopping at the tick's budget; a recount left part-way goes on
  from its last page at the next tick (kept in memory; after a restart it starts again). Errors
  are logged as `round_counts_failed error=<class>` (WARN) and leave the round for the next tick.
- `modules.RoundCountsTask`: a timer of its own (`RoundCountsTimer`, one thread), eager, after
  the migrations (`MigrationOrderSpec`), `roundCounts.interval` 5 s after the previous tick,
  `budget` 2 s, `window` 2,000 (below 1 refused at start-up). Each tick runs in a guard that logs
  anything it throws (`round_counts_tick_failed error=<class>`, WARN) and rethrows only a
  `VirtualMachineError`, so one escaped error doesn't cancel every later tick
  (`scheduleWithFixedDelay` would; `RoundCountsTaskSpec`, with real ticks). `roundCounts.mode =
  manual` in the tests' config (specs call `untilClean`); the Gatling fixture runs it in the
  background (`-Dgatling.roundCounts.mode=manual` turns it off).
- **Not a job.** A `job` row per tick would pile up (nothing prunes the table: ~17k rows a day for
  a round voted on all day), and the work needs no lease: every window commits on its own, the
  marks are in the database, and two instances would only repeat some windows (each holds its
  round).
- **Invariant 5** (`RoundImageInvariants.counts`): the rows whose counts differ from the GROUP BY,
  checked once the round is clean (no marked file, `counts_gen_done >= counts_gen`); every spec
  that asserts `violations` after a recount checks it.

### Vote latency and recompute cost (`gatling.setup.RoundCountsBenchmark`, fixture-v7)

Rated round of 38,367 files and 383,670 votes, MariaDB 10.6.22, SQL debug logging off and the
app's recompute off in both variants; one committed transaction per vote, the rate always
changed; 200 interleaved pairs after 20 warm-ups. Log `target/checks/20261105-002014-27856.log`,
`target/gatling/round-counts-bench-20261105-002121/summary.txt`.

| Measurement | Result |
|---|---|
| A: the base commit's vote (its statements, copied) | median 7.63 ms (p95 9.58; 5.90–16.17) |
| B: `VoteJdbc.rate` with the mark | median 8.40 ms (p95 11.23; 6.66–22.46) |
| paired B − A | **+0.71 ms** median (p05 −0.79 .. p95 +2.82); B slower in 168/200 |
| B alone / B while full recounts of its round run on another thread | 8.39 / 9.40 ms median (+1.00); p95 10.85 / 11.23 (+0.37) |
| full recount of the round (20 windows) | 590, 491, 471 ms (24–29 ms a window); the write-path plan's one-statement recount took 1.92 s |
| per-file pass | 220 files 51 ms; 50 files 21 ms (one window) |

The write-path plan measured P2a's delta at +1.3 ms with a lock on the shared `round_image` row;
the mark costs about half that, and the counts are seconds old instead of exact (W6). A criteria
vote was not benchmarked (the fixture has no criteria round); it adds the same one statement.

### Stress harness (`db.stress.WritePathStress`, 90 s, all paths, seed 42)

The branch adds a 17th path, `recount` (`RoundCountsRecompute.runOnce` every 0–500 ms), and
checks invariant 5 after a final recount. Base `04c1d310` run on the same settings, two runs each:

| Run | server deadlocks | involving `recount` / `round_image_dirty` | vote_binary p50 / p95 ms | vote_rated | vote_criteria | invariants |
|---|---|---|---|---|---|---|
| base #1 | 10 | — | 9.5 / 18.8 | 9.5 / 18.1 | 9.9 / 31.0 | clean |
| base #2 | 13 | — | 9.3 / 19.5 | 9.3 / 19.4 | 9.7 / 31.8 | clean |
| branch #1 | 5 | 0 | 10.7 / 22.8 | 11.0 / 23.1 | 10.5 / 40.4 | clean (counts too); 3 stale `vote.monument_id` copies |
| branch #2 | 9 | 0 | 10.1 / 21.2 | 10.3 / 21.5 | 9.9 / 37.2 | clean (counts too) |

- Every deadlock in the four runs has the test-only `remove_unrated` in it but one (branch #2:
  `remove_image` × `vote_rated`, the vote retried by `WriteTx`, no failure); none touches
  `round_image_dirty`. Visible failures: only the known `distribute_new`/`distribute_list`
  count mismatches against the test-only `remove_image`/`set_round` (break 7), in both.
- Juror votes: **+0.6 to +1.7 ms** at p50 and +2 to +4 ms at p95 (criteria +5 to +9 ms at p95)
  under stress, with one more thread on the database; base run-to-run spread ~0.2 ms at p50,
  ~1 ms at p95.
- The 3 stale `vote.monument_id` copies of branch #1 didn't recur in branch #2 or the base runs.
  P2a writes no monument id, and h6 (the image rows share-locked before the vote rows are built)
  is built in the base, so 3 copies in 1 of 4 runs point to a residual window that predates P2a:
  an open item of the write-path plan (§12). The harness now prints the offending rows (table,
  round, page, juror, the vote's and the image's ids) when the invariant fails.
- Two more base runs, to see whether the copies show there too, were not obtained: the host
  went to sleep 1.5 minutes into the first (01:15 to 04:15, and again to 07:40; Windows'
  Kernel-Power events). Its result (`target/stress/write-paths-20261105-011407/`, invariants
  clean, no stale copy) has its timings spoiled by the sleeps; the second was stopped by hand.
  Not a harness fault: its run timer and operations measure wall time (write-path plan §12).

Logs: branch `target/checks/20261105-002355-12908.log` and `…-003413-29380.log` (results
`target/stress/write-paths-20261105-002443/`, `…-003537/`); base `…-002705-21480.log` and
`…-003121-4836.log` (`…-002816/`, `…-003205/`).

### Deploy note

V89 ships in release 2 with V82–V88: before the deploy production is at release 1 (its last
Flyway row **81**, tables `rounds` and `selection`; `round` and `round_image` are V82's and
V84's). Read-only, production, as the admin user, before the deploy:
```sql
SELECT VERSION();   -- 10.6+ (INSTANT ADD COLUMN; the rollback's INSTANT DROP COLUMN: 10.4+)
SELECT @@innodb_instant_alter_column_allowed;   -- not 'never' (V89's ALTERs would fail);
                                                -- 'add_drop_reorder' (the default) for the rollback's DROP
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;   -- 81, 1
SELECT ENGINE, ROW_FORMAT FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rounds';         -- InnoDB, Dynamic (not Compressed)
SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'rounds' AND COLUMN_NAME IN ('counts_gen', 'counts_gen_done');          -- 0
SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'round_image_dirty';                    -- 0
-- the background recount's size: V84's pairs (§13's query), ~0.4 s per 2,000 rows the first time
SELECT COUNT(*) FROM (SELECT DISTINCT round_id, page_id FROM selection) p;
SELECT COUNT(*) FROM selection WHERE rate IS NULL;   -- informational: any number is fine,
                                                     -- a NULL rate counts as unrated (§16)
```
- **Deploy:** V89 runs with the others at start-up: metadata-only `ALTER`s and an empty table
  (tens of ms on the fixture). Nothing to run by hand: the app recounts every round in the
  background, open rounds first. **Wall time of that first recount:** ~1–3.5 minutes of work for
  0.3–1M rows, done 2 s per tick (the budget) every ~7 s (the 5 s interval runs after the tick
  ends), so about 3.5 times as long: **~4–12 minutes** until every round is clean.
- **The timer writes from start-up**, during maintenance and before `reopen`: the first ticks
  recount rounds while `wlxjury-release deploy` holds the site under maintenance. Only derived
  data (the counts, `counts_gen_done`, cleared marks), recomputed from the votes: a `wlxjury-release
  restore` before `reopen` loses nothing, and after it the restored rounds are recounted again.
- **After `reopen`** (read-only): `SELECT COUNT(*) FROM round WHERE counts_gen_done < counts_gen`
  falls to 0 within minutes (a round being distributed waits for its job);
  `SELECT COUNT(*) FROM round_image_dirty` stays small (emptied every few seconds while jurors
  vote). WARN lines `round_counts_failed` mean a tick's window failed (retried at the next).
- **Rollback after `reopen`** (tested forward, back, forward again: `FlywayUpgradeSpec` "V89's
  rollback SQL after reopen"; the schema after it equals V88's), with the app stopped:
  ```sql
  DROP TABLE round_image_dirty;
  ALTER TABLE round DROP COLUMN counts_gen, DROP COLUMN counts_gen_done, ALGORITHM = INSTANT;
  ALTER TABLE round_image DROP COLUMN assigned, DROP COLUMN voted, DROP COLUMN approvals,
    DROP COLUMN rejections, DROP COLUMN rate_sum, ALGORITHM = INSTANT;
  DELETE FROM flyway_schema_history WHERE version = '89';
  ```
  The older code never read the counts, so nothing is lost; its votes mark nothing, and a
  forward deploy runs V89 again, which makes every round dirty and recounts it from the votes.
  Before `reopen`, `wlxjury-release restore`.
- Nothing to announce: no page changes.

### Files

New: `conf/db/migration/default/V89__Round_image_counts.sql`, `app/db/scalikejdbc/RoundCounts.scala`,
`app/services/counts/RoundCountsRecompute.scala`, `app/modules/RoundCountsTask.scala`,
`test/db/scalikejdbc/RoundCountsSpec.scala`, `test/db/scalikejdbc/RoundCountsLockSpec.scala`,
`test/controllers/RoundCountsVoteSpec.scala`, `test/modules/RoundCountsTaskSpec.scala`,
`test/gatling/setup/RoundCountsBenchmark.scala`.
Changed: `VoteJdbc.rateIn`, `CriteriaRate.updateRateIn` (the mark), `RoundImageJdbc` (`addVoted`
and `sync` bump the generation), `RoundImageInvariants` (5), `AppModule`, `conf/application.conf`
(`roundCounts`), the tests' config (`roundCounts.mode = manual`), `SharedTestDb`/`SharedPlayApp`
(truncate the table), `StructuralWritesSpec` (scans `round_image_dirty`; `RoundCounts`' writers
listed), `MigrationOrderSpec`, `FlywayUpgradeSpec`, the Gatling fixture (recounted after its
load, `fixture-v7`), `WritePathStress` (`recount`, invariant 5, the stale monument copies'
rows printed).

### Specs

- `RoundCountsSpec`: V89's schema on the test server; every round dirty until recounted; a vote
  marks its file, not the round, only when its rate changes; refused votes mark nothing; a
  criteria vote marks once its rate changes (all criteria rated, and back); bulk writers bump the
  generation (`batchInsert`; `sync` through the test-only `removeImage` and `setRound`, both
  rounds) keeping `created_at`, and
  are refused without the round lock; counts equal the votes after a recount in windows of 2
  (binary, rated, half stars, criteria, a deleted file's row 0); a hand-changed count reported
  as invariant 5 once clean, repaired by the next recount; a bulk write between two windows
  leaves the round dirty and is recounted; a vote between two windows keeps its mark; a round
  being distributed waits, its marked files counted; entries of a missing round dropped; the
  tick's budget; a REPEATABLE READ pool refused (nothing written, the marks kept), then recounted
  on the READ COMMITTED one.
- `RoundCountsTaskSpec`: real ticks on a 100 ms timer make a dirty round clean; ticks that throw
  an `IllegalStateException` and a `NoClassDefFoundError` (beyond NonFatal) are followed by
  ticks that run; a `StackOverflowError` is rethrown; `roundCounts.window` 0 or below refused.
- `RoundCountsLockSpec` (INNODB_TRX): a file window waits for a vote holding its existing entry
  and reads it; a full recount's window waits for a vote's new entry and reads it; a vote waits
  for a window holding its entry and leaves its mark for the next pass; 20 jurors voting 40
  times each on one file (binary and criteria rounds) while the recompute ticks: 800 votes, 0
  failures, 0 server deadlocks, invariant 5 afterwards (on a 24-connection pool).
- `RoundCountsVoteSpec` (a Play app, the routes): the gallery's and the large view's votes in a
  binary round (marks, then counts), a juror changing their mind, an unchanged vote marking
  nothing; a rated, a half-star and a criteria round's counts; invariant 5 after each recount.
- `FlywayUpgradeSpec`: V89's rollback SQL gives V88's schema and history; V89 again makes every
  round dirty and the counts 0; a rerun of its statements changes nothing.

### Checks

Logs under `target/checks/` of the worktree.

| Step | Specs | Result | Log |
|---|---|---|---|
| First compile | `Test/compile` | ok | `20261105-000001-15056.log` |
| New specs, first run | the three new specs, `StructuralWritesSpec`, `MigrationOrderSpec`, `FlywayUpgradeSpec`, `MigrationSchemaSpec`, `MigrationNamingSpec` | 2 failures: invariant 4 in a spec's own setup (a file aside not marked deleted), and the 20-juror run's 56 deadlocks with `INSERT IGNORE` (above) | `20261105-000751-19828.log` |
| Fixed (the upsert) | `RoundCountsSpec`, `RoundCountsLockSpec`, `FlywayUpgradeSpec` | 26: 25 passed, 1 skipped (the Flyway fixture generator) | `20261105-001107-25208.log` |
| Breakage scan | `db.*`, `org.intracer.wmua.*`, `services.*`, `security.*`, `modules.*` | **961: 955 passed, 0 failed, 6 skipped, 1 pending** | `20261105-001236-19504.log` |
| MariaDB 10.11 | the three new specs and `FlywayUpgradeSpec` on `mariadb:10.11.14` | 31: 30 passed, 1 skipped; the 20-juror run 0 deadlocks | `20261105-003751-16636.log`; `RoundCountsSpec` again with the server's version printed (`10.11.14-MariaDB`), `20261105-003951-29056.log` |
| Benchmark | `RoundCountsBenchmark` (fixture-v7 built) | exit 0, no problem | `20261105-002014-27856.log` |
| Stress | `WritePathStress`, branch and base, two runs each | above | above |
| Gate | full suite at `e32dbde8` (`Test/compile`, `test`, Jest included), MariaDB 10.6 container | **2025: 2017 passed, 0 failed, 0 errors, 8 skipped, 1 pending**; Jest 22 suites, 285/285; 10:30 | `20261105-004358-16720.log` |

### Open questions

- **Bulk writers touching a few files** (a file deleted on Commons in an open round, a CoI
  disqualify of a few files) mark the whole round, so its next tick recounts every file (~0.5 s
  for 38k). W6 asked for the round row; marking those writers' files in `round_image_dirty`
  instead (after `round_image`, in the lock order) would make that a per-file pass. Not needed
  at production's rate of such writes; a candidate if P2b's "as of" lag matters.
- **The first recount after the deploy** writes every row (~0.4 s windows on the fixture). A
  smaller window for rounds never recounted, or a `CASE` update, would shorten the round lock a
  window holds; it only delays structural writers by under a second.
- **P2b's "as of"**: `RoundCounts.state` gives a round's generation, last recount and marked
  files; nothing records when a round was last clean. P2b decides whether it shows that.
  **Decided in P2b (§17):** not shown; the reads are exact (the marked files overlaid from
  their votes, a round with a recount pending read from its votes).
- The repair job (`round_image_check`) bumps the generation through its sync windows, so a run
  forces a recount; it doesn't compare counts itself (invariant 5 is the spec's and the harness's
  check).

## 17. P2b results

Branch `ukwikidev/p2b-reads` (from `origin/stack/82-p2a-counts` `69dbf142`, PR #265, the stack
tip), 2026-11-05. Roadmap queue item 5, second part. Migration **V90**
(`V90__Round_average_rule.sql`). Two parts: the user's per-round score rule (2026-11-05), and
all the jurors' lists read from `round_image`'s counts behind `roundImage.read`, off by default.

### Migration V90

```sql
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS average_rule VARCHAR(16) NOT NULL DEFAULT 'rated',
  ALGORITHM = INSTANT;
UPDATE round SET average_rule = 'sum', created_at = created_at
  WHERE optional_rate = 1 AND BINARY average_rule = 'rated';
ALTER TABLE round DROP CONSTRAINT IF EXISTS round_average_rule_chk,
  ADD CONSTRAINT round_average_rule_chk CHECK (BINARY average_rule IN ('rated', 'assigned', 'sum'));
```

- The column is metadata only (`INSTANT`). The `UPDATE` touches `round`'s few rows; `created_at`
  is set to itself (production's has `ON UPDATE CURRENT_TIMESTAMP`, V86). The check is one
  statement (db practices §8.0) and is checked against the existing rows as it is added; `round`
  has one row per round, so its copy is instant. Every statement can be rerun.
- **`optional_rate` is no longer read** (the user, 2026-11-05: one setting instead of two). The
  column stays for a rollback; dropping it is a later clean-up.
- `FlywayUpgradeSpec` "V90's average rule": an `optional_rate` round becomes `sum`, the others
  `rated`, `created_at` unchanged; `'average'` and `'SUM'` refused; a rerun of V90 changes
  nothing; the rollback below gives V89's schema and history; forward again gives the same rules.

### The score rule (the user's decision, 2026-11-05)

`org.intracer.wmua.AverageRule`, one option of a rated round, used by every view of its results
and by a next round's top-N and "min average rate" from it. The sum is the file's rate sum; the
rule picks what it is divided by:

| Rule | Score | Form label (en) |
|---|---|---|
| `rated` (default) | the sum over the jurors who rated the file (`rate > 0`; in a criteria round, rated in every criterion, U5) | Average over the jurors who rated it |
| `assigned` | the sum over the file's vote rows, unrated as 0 | Average over its assigned jurors (unrated counts as 0) |
| `sum` | the sum itself | Total of the rates (a file a juror skipped adds nothing) |

- **Defaults (confirmed by the user, 2026-11-05):** `rated` for new rounds and every existing
  round; `sum` for the `optional_rate` rounds. `rated` goes with the coming ✗ "worst mark"
  (roadmap queue item 6), where a ✗ counts as a rated 0: a juror's ✗ then lowers a file's
  average, and only a file the juror left unrated is left out of it. Their old denominator (the round's
  active jurors) was the same for every file, so their order was the sum's already; they now
  show the sum instead of `sum / active jurors`.
- **A yes/no round** has no average: its lists count approvals and rejections whatever its rule
  (`AverageRuleSpec`, `GroupedListParitySpec`).
- **Stopped jurors' votes count** under every rule, as before (their vote rows stay;
  `round_user.active` only stops new votes): the vote source doesn't join `round_user`, nor do
  the counts (`AverageRulePagesSpec`).
- **The criteria averages** stay over a criterion's fully rated votes (C3); the overall score
  follows the rule. Under `rated` both are over the same votes, so the overall's per-criterion
  value is the criteria averages' mean (`7.25` beside `7.00` and `7.50`).
- **Ranking.** A rated round's files rank by the rule's average, then the sum (a `sum` round's by
  the sum alone): the CSV, the file list, the by-rate gallery for all the jurors (in any order
  but file order), the ranks, the large view's neighbours and the top-N. The average is an exact
  integer key in SQL and in memory (`sum * 10^9 DIV n`: ties exact, and averages that differ
  with fewer than 30,000 jurors stay apart), so ranks read from SQL match a page's values.
  Before, the lists ranked by the sum and showed averages over another denominator.
- **Shown denominators** (`ImageWithRating.rateText`): `14.50 (29 / 2 of 3 jurors)`, or
  `(29 / 3 jurors)` when every juror rated it; `9.67 (29 / 3 jurors)` under `assigned`; `29 (sum
  of 3 jurors)` under `sum`. A criteria round: `7.25 (Σ 14.50: 29 / 2 of 3 jurors)`, a sum round
  `7.25 (Σ 29, sum of 3 jurors)`. The CSV keeps numbers only: `14.50 (29 / 2)`, a sum round's
  `29`. A `sum` round's stars show its rated jurors' average (the sum can exceed the stars),
  with the sum beside them. The large view for all the jurors now shows the text under its stars
  in a plain rated round too. Round statistics show the round's rule.

**What each view's number becomes** (a file of `m` assigned jurors, `n` who rated it, sum `S`;
`A` the round's jurors with any rated vote):

| View | Before V90 | `rated` | `assigned` | `sum` |
|---|---|---|---|---|
| By-rate gallery, large view (all jurors) | `S/m`, ordered by `S` | `S/n`, by `S/n` then `S` | `S/m`, by `S/m` then `S` | `S`, by `S` |
| File list, CSV | `S/n`, ranked by `S` | `S/n`, by `S/n` then `S` | `S/m` | `S` |
| Top-N from it | by `S/m` then `S` | by `S/n` then `S` | unchanged | by `S` |
| "Min average rate" from it | `S/m ≥ x` | `S/n ≥ x` | unchanged | `S ≥ x` (a minimum score) |
| An `optional_rate` round, every view | `S/A`, ordered by `S` | | | `S`, ordered by `S` |
| Yes/no round, juror's own lists | unchanged | | | |

**Behaviour changes to note.** With the default `rated`, the grouped views and a next round's
top-N or minimum from an existing rated round no longer count an unrated vote as 0: a file a
juror skipped ranks by its other jurors' average. A next round's "min average rate" from a former
`optional_rate` round now compares the sum (before, the sum over the round's active jurors): a
threshold must be given as a score; the form's hint says so when a `sum` round is offered
(`average.file.rating.sum`). A round from before V90 that should keep the old grouped numbers
gets `assigned`.

**Editing (the user, 2026-11-05: stays, with a warning): at any time, open, stopped or frozen.** The rule changes
how results are shown, not the votes. A distribution reads the previous round's rule from its
job's settings (`params.settings.prevAverageRule`, recorded when the job is queued, under the
previous round's lock), so a change while a distribution runs doesn't change its top-N or
minimum, and a later "Distribute new files" uses the rule then current. A job queued before V90
has no recorded rule and applies `assigned`, the rule its distribution applied then (the rate sum
over each file's vote rows), not the round's `rated` after V90, so a job resumed across the
deploy applies one rule (`jobAverageRule`; review follow-up). A former `optional_rate` source's
old rule (the sum over the round's active jurors) can't be reproduced: let such a distribution
finish before the deploy (deploy note below). The next round's settings record which rule it used. The form is the admins'
only (`RoundAverageRuleFormSpec`: a juror, an organizer and another contest's admin get 403; an
unknown value 400; a client without the select keeps the stored rule).

**A change takes effect at once; nothing runs in the background** (checked in the code,
2026-11-05). The save is one `UPDATE round SET name, average_rule` (`Round.updateRound`): it
bumps no `counts_gen`, marks no file, queues no job. The counts (`round_image`'s `rate_sum`,
`assigned`, `voted`, `approvals`, `rejections`) and the votes don't depend on the rule, and
`RoundCounts` doesn't read it; every read applies the round's rule as it loads the round, in SQL
(`AverageRule.keySql` over `VoteTerms` or `CountTerms`) and in memory (`ImageWithRating`). No
cache holds a score (`RegionCountCache` and `RoundImageCounts` hold file counts; the galleries
and the large view are `no-store`). So the next page after the save shows the new scores and
order, exactly. The only work that keeps the old rule is a distribution queued before the
change (its job's recorded rule, above).

**The round form's warning** (the user, 2026-11-05). For a rated round with a rated vote
(`VoteJdbc.hasRatedVote`: a vote with `rate <> 0`, not deleted; without one every file scores 0
under each rule), a note under the select (`RoundController.ScoreRuleWarning`,
`round.average.rule.change.warning`): "Changing the score rule re-scores and re-orders this
round's results on every page, and takes effect at once: nothing is recomputed in the background.
A distribution already queued or running keeps the rule it started with." When later rounds of
the contest list it among their previous rounds with a top-N or a "min average rate"
(`round.average.rule.change.later`): "Round(s) 2 (Top), 3 used this round's scores when they were
created; their files won't change. A later "Distribute new files" there uses the new rule." The
note is rendered server-side, so it shows without JavaScript; `round-form.js` hides it while the
select shows the saved rule (`data-stored-rule`) and shows it once the select differs. The
select's `aria-describedby` names it with the help. Not on a new round's form, a round whose
votes are all unrated, a yes/no or comments-only round (`RoundScoreRuleWarningSpec`; Jest
`round-form.test.js`).

**The ✗ item (roadmap, after P2b).** This code reads "rated" as `rate > 0` (`ratedVotes`,
`approvals`, `AverageRule.VoteTerms.rated`, `RoundImageQuery.CountTerms`). The "worst mark (✗)"
item stores ✗ as `rate = -1` and changes "rated" to `rate <> 0` (P2a's `voted`), with ✗ counted
as 0 in every average and total: a `sum` round's sum of grades is then `rate_sum + rejections`
(the counts have both). `sum(s.rate)` in the rank keys becomes that sum too.

### Reads behind `roundImage.read`

- `db.scalikejdbc.rewrite.RoundImageQuery` (§8): all the jurors' by-rate pages and file-list
  page ids, `count`, rank counts, positions (`ROW_NUMBER` over the round's narrow rows), the
  large view's neighbours and the region counts, from `round_image` (`status = 'in'`,
  `assigned > 0`), one row per file, no GROUP BY. Its reader builds the same `ImageWithRating`
  (rate sum, `countFromDb = assigned`, approvals, rejections). Order and rank columns are one
  vocabulary for both sources (`RoundImageQuery.translate`: `ListOrder`, `BinaryOrder`, the rank
  keys of each rule); a filter the counts can't tell (one rate of a rated round) and a juror's
  list read the votes. Vote filters stay file-level: Unrated `assigned > voted`, Rated and
  selected `approvals > 0`, rejected `rejections > 0`, plus the large view's file.
- `RoundList.countsQuery` says whether a list may be read from the counts;
  `GalleryController` and `LargeViewController` read `roundImage.read` (`conf/application.conf`,
  false). The CSV reads the votes always (an exact
  consumer), as do a top-N, a "min average rate" and the juror's own lists, counts and regions.
- **Exactness, the fallback for dirty rounds.** Every query overlays the files marked dirty by
  votes (`round_image_dirty`, usually a few) with their counts read from their votes, in the
  same statement (`LEFT JOIN` of their grouped votes, `COALESCE`). At READ COMMITTED a statement
  reads one snapshot, and in every committed state a file's stored counts equal its votes or the
  file is marked (P2a: a vote marks its file in its transaction; the recompute writes the counts
  and clears the marks it read in one), so the numbers are exact while no bulk write has
  committed since the round's last full recount. **Each statement proves that for its own
  snapshot** (`RoundList.fromCounts`, after the review: the first build checked the generation in
  a statement of its own before the counts statement, so a bulk write committing between the two,
  `moveVotesBack`, a distribution chunk adding votes to files already in the round, the repair
  job's sync, would have been read with stale counts and no marks): the round's generation is
  clean (`counts_gen_done >= counts_gen`, `RoundCounts.cleanGeneration`) when the request's first
  counts statement begins, and unchanged (`RoundCounts.generation`) after each statement ends.
  Every bulk writer bumps `counts_gen` in its own transaction and it only grows, so an unchanged
  generation means no bulk write committed in between. When the check fails (a distribution, a
  file deleted on Commons, a disqualification, until its recount, seconds), that statement and
  the rest of the request read the votes. The source is decided once per request
  (`CountsSession`, shared by a list's copies), so a page doesn't go back to the counts once a
  statement has fallen back. Cost: two primary-key reads of `round` for the first statement, one
  for each later. Spec'd: a bulk insert of a vote for a file already in the round between the
  check and the statement is read from the votes (`GroupedListParitySpec`). Why not the votes for any dirty
  round, or an "updating" marker: a round being judged has marked files nearly all the time
  (marks are cleared every ~5–7 s), so the first would read the votes exactly when the pages are
  used, and the second would show numbers seconds old. The overlay is exact at a measured cost
  below. No "as of" is shown: nothing shown is stale.
- **W10 (§851).** A list's page, rank counts, position and neighbours come from one source with
  the same order columns: the request's `CountsSession` decides the source for the position and the
  files around it. The counts' order columns are `round_image`'s `monument_id`/`page_id` copies,
  the vote source's the vote's copies, equal by invariants 1 and 3.
- **NULL rates.** The counts count a NULL rate as an assigned, unrated vote; the vote source now
  counts vote rows with `COUNT(*)` too (before `COUNT(s.rate)`). Its Unrated filter is still
  `s.rate = 0` (a NULL-rate vote isn't in it, but is in the counts' `assigned > voted`):
  production has none to our knowledge (§16's pre-deploy query counts them). The ✗ item makes
  `vote.rate` `NOT NULL DEFAULT 0` (roadmap queue item 6), and this difference goes.

### Benchmarks (`gatling.setup.GalleryLatencyBenchmark`, fixture of 38k files, paired by the flag)

One build (`48cb1c4e`), MariaDB 10.6.22 in the fixture's container, SQL debug logging off in
every run (`-Dscalikejdbc.global.loggingSQLAndTime.enabled=false`), the app's recompute in the
background (counts clean before the organizer cases), `-Dgatling.timing=true
-Dbench.samples=20`, the fixture rebuilt once for V90 (cache `gatling-67def70bcd05b63b`). Each
run is a fresh JVM with the fixture restored. "Off" is this build's vote source, which already
ranks the rated round by the `rated` rule's average.

**Pilot, `-Dbench.cases='^(organizer-|regions-.*-organizer)'`, flag off/on/off/on** (wall 353,
149, 279, 145 s: the first rebuilt the fixture, the off runs are slower by their pages; no sleep:
each run's wall time matches its requests). Client medians in ms (p10–p90), per run:

| Case | off #1 | on #1 | off #2 | on #2 | on − off (mean of medians) |
|---|---|---|---|---|---|
| binary by-rate p1 | 456 (428–585) | 119 (113–128) | 451 (438–475) | 121 (111–138) | **−334** |
| binary file list p1 (1,000 files) | 506 (473–632) | 235 (226–277) | 472 (468–496) | 231 (224–295) | **−256** |
| binary large view, middle | 561 (512–676) | 125 (115–134) | 531 (519–621) | 124 (118–141) | **−422** |
| rated by-rate p1 | 1035 (1019–1293) | 150 (146–158) | 1020 (1012–1044) | 157 (148–196) | **−874** |
| rated file list p1 (1,000 files) | 1429 (1295–1757) | 273 (247–327) | 1037 (1011–1155) | 265 (244–314) | **−964** |
| rated large view, middle | 1819 (1769–2069) | 266 (261–315) | 1772 (1758–1854) | 262 (255–288) | **−1532** |
| region menu, binary | 179 (142–255) | 64 (59–70) | 155 (145–180) | 63 (55–75) | **−104** |
| region menu, rated | 214 (189–258) | 50 (48–54) | 146 (141–177) | 51 (48–56) | **−130** |

Run-to-run spread of a case's median: off 5–68 ms, and 392 ms for the rated file list (its
first run, right after the fixture's rebuild); on 1–8 ms. From the timing logs (server-side, run 2 of
each), what remains with the flag on: the rated large view's position (`ROW_NUMBER` over the
round's 38k rows, 145 ms; 915 ms from the votes) and its page at the file's offset (96 ms; 836);
the rated by-rate page 88 ms (822) and its rank counts 42 ms (174); the file list's page ids 79
ms (727), its votes by page id 31 ms (unchanged); the region menu 32–39 ms (131–134). The
generation check is 1–2 ms. The overlay's cost with marked files is not measured here: no file
was marked during the organizer cases (the votes come last); the dirty case is exact by the specs.

**Then `-Dbench.cases='^(large|vote)'`, flag off then on** (wall 133 and 134 s): the juror's
large views (binary and rated, file and rate order, a file early, in the middle and at the end)
and 20 votes of each kind, each followed by the next large view. The flag doesn't touch these
paths (a juror's list reads the votes either way), and the medians moved by −6 to +15 ms with
the p10–p90 ranges overlapping (a juror's large view 40–83 ms, a vote 53–65 ms, the next large
view 45–56 ms): no difference beyond run noise. Logs `target/checks/20261105-085934-392.log`,
`…-090147-10404.log`; summaries `target/gatling/p2b-pilot-summary.txt`,
`p2b-large-vote-summary.txt` in the worktree.

**Not run** (no result above could change a decision): `AggregatedRatingsSimulation`, a
`VotingSimulation` stress run, `RoundDistributionSimulation` (the votes, the recompute and the
distribution are unchanged by the flag; the distribution's queries add `SUM(s.rate > 0)`).
**A later option**, if the large view's ~260 ms matters: the keyed path (§8) over the counts
(the file's key, then `COUNT(*)` of the rows before it and a keyset page of five) instead of
`ROW_NUMBER` and an offset page.

### Rollout (a pilot; when is the user's call)

1. Deploy with `roundImage.read = false` (the default): V90 and the score rule are live, the
   lists read the votes as before.
2. The pilot (the user, 2026-11-05: there is no staging server):
   - (a) **On a local copy of production, run by the user.** The user restores a production
     backup locally and runs the app on it with `roundImage.read = true`, and compares an
     organizer's by-rate page, file list, large view and region menu with the flag off, once
     `SELECT COUNT(*) FROM round WHERE counts_gen_done < counts_gen` is 0 (the background
     recount after the migrations). `scripts/run-with-prod-dump.sh` does the restore: with no
     argument it dumps production (credentials from `.env`), restores the dump into a local
     MariaDB 10.6 container (`wlxjury-local-dev`, port 3307) and starts `sbt run` on it with
     migrations on; `--skip-dump` reuses `/tmp/wlxjury-prod.sql.gz`, `--dump-only` stops after
     the restore, `--reuse-container` starts sbt on the running container (the usage text,
     `--help`). It passes sbt no other setting, so the flag goes in another way, for example
     `SBT_OPTS=-DroundImage.read=true` (as the script's own `-Ddb.default.migration.auto=true`;
     not tried by an agent) or a local edit of `conf/application.conf`. The query runs in the
     container: `docker exec wlxjury-local-dev mysql wlxjury -e "…"`. **Agents never run the
     script, nor anything else against real data.**
   - (b) **Then production:** `roundImage.read = true` in its `production.conf`, after the same
     query is 0 there (§16's deploy note). Rollback: the flag off and a restart.

### Deploy note (V90)

Read-only, production, before the deploy (with §16's for V89):
```sql
SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'rounds' AND COLUMN_NAME = 'average_rule';                  -- 0
SELECT COUNT(*), SUM(optional_rate = 1) FROM rounds;                            -- rounds, and those that become 'sum'
SELECT r.id, r.contest_id, r.prev_min_avg_rate FROM rounds r                  -- minimums taken from them:
  WHERE r.prev_min_avg_rate IS NOT NULL AND EXISTS (SELECT 1 FROM rounds o      -- read as sums from now on
        WHERE o.optional_rate = 1 AND FIND_IN_SET(o.id, r.previous));
```
Rollback after `reopen` (tested: `FlywayUpgradeSpec`), with the app stopped:
```sql
ALTER TABLE round DROP CONSTRAINT round_average_rule_chk;
ALTER TABLE round DROP COLUMN average_rule, ALGORITHM = INSTANT;
DELETE FROM flyway_schema_history WHERE version = '90';
```
The older code reads `optional_rate`, which V90 left as it was.

**Before the deploy (release 2):** let any distribution with a "min average rate" or a top-N from
a former `optional_rate` round finish (`SELECT id, status FROM job WHERE kind =
'round_distribution' AND status IN ('queued', 'running')`, with its round's previous rounds). Its
threshold was the sum over that round's active jurors, which V90's code no longer computes
(`numberOfActiveJurors` is gone): resumed after the deploy it would compare the sum.

### Files

New: `conf/db/migration/default/V90__Round_average_rule.sql`, `app/org/intracer/wmua/AverageRule.scala`,
`app/db/scalikejdbc/rewrite/RoundImageQuery.scala`, `test/org/intracer/wmua/AverageRuleSpec.scala`,
`test/controllers/AverageRulePagesSpec.scala`, `test/controllers/RoundAverageRuleFormSpec.scala`.
Changed: `Round` (`averageRule`; `optionalRate`, `numberOfActiveJurors` removed), `VoteJdbc`
(`activeJurors` removed), `ImageWithRating` (`ratedJurors`, `rateText`, `denominator`,
`starRate`, `RankKey`), `ImageDbNew` (`COUNT(*)`, Long keys), `RoundCounts.cleanGeneration` and `generation`,
`RoundList` (`countsQuery`, the by-rate order), `GalleryController`, `LargeViewController`,
`EditRound`, `RoundController`, `RoundService` (`prevAverageRule`), `RoundDistributionHandler`
(`PerImageRated`, the recorded rule), `DistributeImages`, the views (`editRound`,
`imageFiltering`, `galleryByRate`, `fileList`, `large`, `navigationButtons`, `roundStat`),
`conf/messages.{en,fr,uk,ru}`, `conf/application.conf`, `GatlingTestFixture`
(`-Dgatling.roundImage.read`).

### Specs

- `GroupedListParitySpec`: every list (pages, sums, counts, tallies, ranks, positions, the large
  view's neighbours; every filter, region, sub-region, several regions, binary order, criteria
  round, past the end) against the in-memory model, **read from the votes and from the counts**,
  for **each rule**; with eight files re-voted after the recount (dirty: read through the
  overlay, the counts' source used) and with a recount pending (read from the votes);
  `RoundImageQuery` directly for several regions.
- `AverageRuleSpec`: each rule's score, page text, CSV text, stars, criteria text and rank key,
  from vote rows and grouped rows alike; half stars; one juror; a yes/no round unchanged; the
  key's exact ties.
- `AverageRulePagesSpec` (through the router): the by-rate gallery's order and text, the
  all-jurors large view, the file list and the CSV for each rule, from the votes and from the
  counts; a stopped juror's votes counted; a vote after the recount shown at once from the counts.
- `RoundDistributionJobSpec`: the streamed distribution against the in-memory filter for
  `sum` and `assigned` previous rounds (min score, min average, top-N with ties); a sum round's
  minimum is its files' sum; from a criteria round, each rule's picks.
- `RoundAverageRuleFormSpec`, `CriteriaResultsPageSpec` (each rule's file list and large view),
  `FlywayUpgradeSpec` (V90); expectations moved to `rated` in `CriteriaResultsViewSpec`,
  `GalleryByRateViewSpec`, `RoundListDbSpec` (whose by-sum cases are a sum round's now),
  `RoundCriteriaSpec`, `RoundListSpec` (every grouped list reads its tally); `RawSqlSpec` lists
  `RoundImageQuery` among its reviewed files.

### Checks

Logs under `target/checks/` of the worktree (Git Bash, `scripts/agent-check.py`, the sbt
launcher, one JVM at a time under the shared lock, Commons variables unset).

| Step | Specs | Result | Log |
|---|---|---|---|
| Compile | `Test/compile` | ok, after two callers of the distribution's context and two test helpers' types | `20261105-082824-19780.log` |
| Affected specs | 26 specs: the parity, rule, distribution, criteria, list, gallery, CSV, view and message specs | 367: 345 passed, 10 failed, 12 errors, 1 pending: expectations of the old rule (the by-rate text and stars, the file list's text, a criteria pick by `assigned`), view fixtures' grouped rows without a tally, the grouped SQL's text (`COUNT(*)`) | `20261105-083102-5048.log` |
| Rerun | the failing ones, `FlywayUpgradeSpec` | 122: 119 passed, 2 failed (two view texts), 1 skipped | `20261105-083657-20204.log` |
| New specs | `AverageRulePagesSpec`, `RoundAverageRuleFormSpec`, the two view specs | 30 passed | `20261105-084018-21024.log` |
| Benchmarks | above | exit 0 each | `20261105-084239-9164.log`, `…-084832-26576.log`, `…-085101-18372.log`, `…-085540-5180.log`, `…-085934-392.log`, `…-090147-10404.log` |
| Full suite | `Test/compile`, `test` (Jest included), MariaDB 10.6 | **2052: 2042 passed, 2 failed, 8 skipped, 1 pending**; Jest 22 suites, 285/285; 11:17. The two: `RoundListSpec` (a rated round's list now always reads its tally) and `RawSqlSpec` (`RoundImageQuery` added to its reviewed files) | `20261105-090927-18596.log` |
| Fixed | `RoundListSpec`, `RawSqlSpec` | 26 passed | `20261105-092541-18052.log` |

No second full suite: the two fixes are a spec's expectation and the guard's allowlist.

**The score rule's warning** (`ukwikidev/p2b-rule-warning`, on `origin/stack/84-p2b-reads`
`7f64460b`, worktree `C:/scala/wlxjury-rulewarn`):

| Step | Specs | Result | Log |
|---|---|---|---|
| Affected specs | `Test/compile`; `RoundScoreRuleWarningSpec`, `RoundAverageRuleFormSpec`, `RoundEditPageSpec`, `RoundCriteriaSpec`, `MessagesCompletenessSpec` | 50: 49 passed, 1 failed: the select's `aria-describedby` read `Some(averageRule_warning)` (the bs helpers render an `Option`'s text; the view now passes a string or `None`) | `20261105-102740-15516.log` |
| Rerun | the same | **50 passed** | `20261105-103331-3636.log` |
| Jest | `round-form.test.js` (the worktree's own `npm ci`); ESLint on `round-form.js` | 12 passed; lint clean | console |

### Open questions

- ~~Confirm the defaults and the editing policy~~: confirmed by the user (2026-11-05): `rated`
  (`sum` for the `optional_rate` rounds), with the ✗ item; editing at any time stays, with the
  round form's warning (above).
- **Lock the rule once a next round has taken its top-N or minimum from it?** The user
  (2026-11-05): **decide later**; no lock is built. Today the next round's job settings record
  the rule it used, the warning names such rounds, and nothing stops a change.
- `imageCountsByRate` (a yes/no round's statistics footer) and `byRoundMerged` still read the
  votes; small, not on a page an organizer reloads often.
- The `optional_rate` column's drop (a later clean-up, after the rollback window).

## 18. The worst mark (✗) results

Branch `ukwikidev/worst-mark` (from `origin/stack/84-p2b-reads` `7f64460b`, PR #267, P2b; merged
with its `83dcc488`), 2026-11-05. Roadmap queue item 6. Migrations **V91**
(`V91__Vote_rate_not_null.sql`) and **V92** (`V92__Round_worst_mark.sql`). It settles §11's
NULL-rate item (V91) and B11 (what a rated round's vote below 0 means: a ✗, counted as 0).

**Scope change (the user, 2026-11-05, during the batch):** decisions 6 and 7 below (V91 and the
naming of every vote check) became a later non-feature item, after release 2 (roadmap item 21).
They were already committed (the first two commits) and are kept; whether they ship with the ✗
or are split off is the orchestrator's call (the ✗ commits use `VoteSql`, and V92 follows V91).

### The user's decisions (2026-11-05)

1. **The option.** `round.worst_mark`, off by default: jurors of a rated round may give a file ✗,
   stored as `rate = -1`. 0 is unrated, stars 1…N unchanged. A yes/no round keeps −1/0/1 and never
   has the option; a criteria round neither (the round form refuses both; a vote of −1 in a round
   without the option, or in a criteria round whatever is stored, is a 400).
2. **✗ counts as 0** in every average and total: the sum of grades is `SUM(GREATEST(rate, 0))` on
   the votes and `rate_sum + rejections` on the counts. "The jurors who rated it" is any vote
   (`rate <> 0`, the counts' `voted`). Under `rated`, the grades over the jurors with any vote; under
   `assigned`, over its vote rows; under `sum`, the grades' sum.
3. **A ✗ is a vote** in a juror's progress (the tabs' Rated, the statistics' rated column) and in
   "the jurors who rated it". A cleared vote is 0, unrated; "cleared" isn't tracked apart.
4. **A next round can leave out files with at least N ✗** of a rated source, the yes/no rounds'
   `rejectedBy` extended (`Round.rejects`: a yes/no round, or a rated one with the option). The
   aggregate views show "✗ n"; a juror's own views their ✗.
5. **Give ✗ and clear apart**, by their own buttons and keys (below).
6. **`vote.rate`, `vote_aside.rate` NOT NULL DEFAULT 0** (V91), the NULL handling removed.
7. **The vote checks named** in Scala and SQL.

### Migration V91

```sql
UPDATE vote SET rate = 0 WHERE rate IS NULL;
UPDATE vote_aside SET rate = 0 WHERE rate IS NULL;
-- each only while the column is still nullable (information_schema.COLUMNS.IS_NULLABLE = 'YES'),
-- through EXECUTE IMMEDIATE, so a rerun rebuilds nothing:
ALTER TABLE vote MODIFY rate INT NOT NULL DEFAULT 0, ALGORITHM = INPLACE, LOCK = NONE;
ALTER TABLE vote_aside MODIFY rate INT NOT NULL DEFAULT 0, ALGORITHM = INPLACE, LOCK = NONE;
```

- The columns were `rate int(11) DEFAULT NULL` in both tables (the fixtures in `test/resources/db/`
  and a fresh replay: V1's `rate int`, `vote_aside` `LIKE` it, V63). Their indexes (`vote_rate_idx`,
  `vote_round_rate_idx`, `vote_jury_round_rate…`, `vote_round_jury_rate_idx`,
  `vote_round_page_rate_idx` and `vote_aside`'s copies) are rebuilt with the table; none changes.
  `vote_orphan` keeps its nullable column (an archive nothing reads).
- **Not INSTANT:** NULL to NOT NULL is an online rebuild (`INPLACE, LOCK=NONE`: writes go on;
  the statement fails rather than copies). MariaDB needs strict mode for it: each ALTER runs as
  `SET STATEMENT sql_mode = '<the session's>,STRICT_TRANS_TABLES', lock_wait_timeout = 60 FOR …`
  (after the review), so the server's mode doesn't matter, and the rebuild's metadata lock waits
  at most 60 s for a long transaction or a backup, then V91 fails with nothing changed but the
  UPDATEs (rerunnable).
- `FlywayUpgradeSpec` "V91's NOT NULL rates" (10.6 and 10.11): NULL rates become 0, others kept
  (a stored −1 too); both columns `NO` / default `0`; an insert without a rate is 0, an update to
  NULL refused; a rerun of the script changes nothing; the rollback gives V90's schema and history;
  a NULL written meanwhile becomes 0 on the forward again. The full chains (the V70 fixture, the
  9.16.3 fresh V82 dump, `ConvertToInnoDbMigrationSpec` from production's V53 schema, whose
  `selection` has no primary key) reach the fresh schema with V91 applied.

**The NULL handling removed** (each checked): the rates read with `intOpt(..).getOrElse(0)`
(`VoteJdbc.rateIn`'s rate before, `ImageJdbc.moveVotesBack`'s pairs, `CriteriaRate.updateRateIn`,
the distribution's streamed rows), the recompute's `COALESCE(SUM(..), 0)` (`RoundCounts.votedCounts`),
P2b's `COALESCE(SUM(s.rate), 0)` (`AverageRule.VoteTerms`), the grouped reader's `intOpt`, and
`binaryPage`'s `COUNT(s.rate)` (now `COUNT(*)`). **Kept**, as not about NULL rates: the counts'
overlay `COALESCE`s (a marked file without votes, its `LEFT JOIN`), invariant 5's (`drift`, a row
without votes), `imageCountsByRate`'s outer sums (an empty round). V53's `COALESCE(rate, 0)` stays:
migrations are immutable. The §17 note on the Unrated filter's difference for a NULL rate goes:
`assigned > voted` is `s.rate = 0` now.

### Migration V92

```sql
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS worst_mark BOOLEAN NOT NULL DEFAULT FALSE,
  ALGORITHM = INSTANT;
```

Metadata only; every round reads `FALSE`. No CHECK: whether a round may have it depends on its
rating and its criteria (`round_criterion`), which the round form and the vote check
(`Round.takesWorstMark`) enforce. `FlywayUpgradeSpec` "V92's worst mark": off for an existing
round, `created_at` unchanged, a rerun changes nothing, the rollback gives V91's schema, forward
again the same.

### Migration V93: the rejected-by mode (the user, 2026-11-05, after the review)

```sql
ALTER TABLE round
  ADD COLUMN IF NOT EXISTS prev_rejected_mode VARCHAR(8) NOT NULL DEFAULT 'exclude',
  ALGORITHM = INSTANT;
ALTER TABLE round DROP CONSTRAINT IF EXISTS round_prev_rejected_mode_chk,
  ADD CONSTRAINT round_prev_rejected_mode_chk CHECK (BINARY prev_rejected_mode IN ('exclude', 'only'));
```

"Rejected by at least N" (`prev_rejected_by`, V74) gets a second mode
(`org.intracer.wmua.RejectedMode`): **exclude** those files (`SUM(rejected) < N` pass; every
existing round's, the default) or **only** them (`SUM(rejected) >= N`), to review the rejected
files in a round of their own. Rejections are a yes/no round's "no" and a rated round's ✗
(`Round.rejects`). The round form has one select: no filter / exclude / only, N the same field; a
client without it excludes; an unknown mode is a 400. Both modes everywhere the filter is
applied: the in-memory filter (`RejectedByLessThan`, `RejectedByAtLeast`), the streamed candidates
(`votesCond`: `NOT EXISTS` / `EXISTS (… HAVING COUNT(*) >= N)`), the yes/no top-N candidates
(`binaryPage`'s HAVING), "Distribute new files". `RoundService` records the mode in the job's
settings (`prevRejectedMode`, beside `prevRejectedBy`), and the job applies the recorded one
(`jobRejectedMode`; a job from before V93 excludes). No settings page lists it. `FlywayUpgradeSpec`
"V93's rejected-by mode": an existing round's filter stays `exclude`, `'ONLY'` and `'all'` refused,
a rerun changes nothing, the rollback gives V92's schema, forward again the same.

### Design

- **Values and checks.** `Vote.Unrated` 0, `Selected` 1, `Rejected` −1; `Vote.isPositive` (a yes,
  stars), `isRejected` (a no, a ✗), `isUnrated`, `isVoted` (any vote), `grade` (stars, a ✗ as 0).
  SQL: `db.scalikejdbc.VoteSql` fragments over a rate column of a fixed alias (`positive`,
  `rejected`, `unrated`, `selected`, `voted`, `grade`, and the texts `RateSum`, `GradeSum`,
  `VotedCount` that name order and rank columns in every vocabulary). The first commit replaced the
  comparisons (about 40, in 13 app files) with no change of behaviour; the ✗ commit changed only
  the rated rounds' meaning.
- **Which rounds a query is for.** A yes/no round's "rated" is a yes and its sum the rates' (its
  Difference order, the legacy top-N); a rated round's "rated" is any vote and its sum of grades.
  The vote source: `SelectionQuery.grades` (set by `RoundList` and the distribution's
  `byRoundMerged` for rated rounds): the grouped `rate` is `sum(GREATEST(s.rate, 0))`, the rated
  filter `s.rate <> 0`. The counts: `RoundImageQuery.grades`: the sum `rate_sum + rejections`, the
  Rated filter `voted > 0`; `CountTerms` (sum of grades, `voted`, `assigned`) and
  `AverageRule.VoteTerms` (`GradeSum`, `VotedCount`, `COUNT(*)`) keep the two sources in parity. The
  rank key's sum column is `GradeSum` (in memory `rateSum`, the grades). The streamed distribution:
  `PrevVotes.grades` (the per-round sum of grades, any vote as rated, `votedFromDb` on its
  candidates). A rated round without the option takes no vote below 0 (`acceptsRate`, since
  68c4716c), so its results are unchanged **if production has none stored from before** (the
  deploy note's query 1; a −1 found there becomes a ✗, and a rate below −1 must be set first).
- **The counts need no change**: P2a counts `voted` (`rate <> 0`), `approvals` (`> 0`),
  `rejections` (`< 0`) and `rate_sum` (`SUM(rate)`) already; a ✗ or a clear changes the rate, so
  `VoteJdbc.rateIn` marks the file (`RoundCountsVoteSpec`).
- **`ImageWithRating`:** `ratedVotes` any vote (a grouped row's `votedFromDb`, else approvals plus
  rejections); `rateSum` the grades; `totalRate` of one juror's ✗ is 0; `worstMarks` and
  `isWorstMark` for the views.
- **Authorization unchanged:** a juror of the round, their own assignment, an open round, CSRF;
  `Round.acceptsRate(-1)` only where `takesWorstMark` (a rated round without criteria with the
  option); else 400 (`invalid_rate`). A yes/no round's −1 as before.
- **The round form:** a checkbox (`worstMark`) with a hidden `worstMarkShown` (a client without
  them keeps the stored option); hidden and not sent for a yes/no or comments-only rating
  (`round-form.js`); refused for a yes/no round (the form's check) and for a criteria round, by the
  stored rating and the criteria after the edit (`RoundController`). Editable at any time: turned
  off, the given ✗ stay (shown, counted as 0) and no new one is taken. "Rejected by at least N"
  moved into its own `#rejectFilters`, offered for the yes/no rounds and the rated ones with the
  option (`Round.rejects`); the job records it for those (`RoundService`).

### What each view shows

| View | A file with ✗ |
|---|---|
| By-rate gallery, file list, large view (all the jurors) | the score of grades and its denominator, then "✗ n" (`snippets/worstMarks`, the mark `aria-hidden`, "n worst marks (✗)" for screen readers) |
| CSV | the Overall cell `5.00 (15 / 3) ✗ 1`; the juror's column −1; a legend row in a round with the option |
| Large view, a juror's votes | each juror's ✗ as the mark, labelled |
| The juror's large view | the number above the stars "✗" ("Rating: worst mark (✗)"); the stars without raty's cancel icon; under them **✗** (pressed when given) and **clear** (an eraser, only once the file has a vote) |
| The juror's tiles and by-rate list | a ✗ toggle button after the stars (pressed, red, for a ✗; Enter or Space as a click); the cancel icon an eraser, labelled, hidden while unrated; the list's rate text "✗" |
| The juror's tabs, round statistics | a ✗ is rated (progress) |
| "Your vote moved" note | "Worst mark (✗)" |

**Keys** (`large-keys.js`, paths only): in a rated round with the option, Delete gives ✗ and
Backspace clears; digits rate and 0 clears, as in any rated round (no conflict). Without the
option: as before (no Delete, no Backspace).

### Files

New: `app/db/scalikejdbc/VoteSql.scala`, `app/views/snippets/worstMarks.scala.html`,
`conf/db/migration/default/V91__Vote_rate_not_null.sql`, `V92__Round_worst_mark.sql`,
`test/controllers/RoundWorstMarkFormSpec.scala`, `test/gatling/setup/VoteRateNotNullBenchmark.scala`,
`javascript-test/worst-mark.test.js`. Changed: `Vote`, `Round` (`worstMark`, `takesWorstMark`,
`rejects`, `acceptsRate`), `ImageWithRating`, `AverageRule`, `BinaryOrder`, `ImageDbNew`
(`grades`), `RoundImageQuery` (`grades`, `GradeSum`), `RoundCounts`, `VoteJdbc`, `ImageJdbc`,
`ImageRepo`, `CriteriaRate`, `CriterionJdbc`, `RoundDistributionHandler` (`PrevVotes.grades`),
`DistributeImages`, `RoundService`, `RoundList`, `EditRound`, `RoundController`, `Csv`, the views
(`editRound`, `imageFiltering`, `galleryByRate`, `fileList`, `showRate`, `roundStatLinks`,
`voteTexts`, `GalleryTiles`, `large/navigationButtons`, `large/keyboardNav`, `large/voteMoved`),
`large-keys.js`, `large-stars.js`, `tile-votes.js`, `round-form.js`, `main.css`,
`conf/messages.{en,fr,ru,uk}`.

### Specs

- `RoundCountsVoteSpec` "a rated round's worst mark (✗)": −1 from the gallery and the large view
  marks the file and is counted (voted, a rejection, `rate_sum` −1); a clear marks and recounts; a
  round without the option and a criteria round refuse −1 with 400, the vote unchanged; a yes/no
  round's −1 as before; invariant 5 after each.
- `GroupedListParitySpec` "with the worst mark": every list for each rule, rates −1…3, from the
  votes and the counts, and with files marked dirty by ✗ and clears (the overlay); the model counts
  grades and any vote.
- `AverageRuleSpec`: ✗ as 0 under each rule, as a rated juror, its rank key, text, CSV text; one
  juror's own ✗; which rounds take −1.
- `RoundDistributionJobSpec`: from a rated round with the option, the streamed and in-memory paths
  agree for the rejection filter (N = 1, 2), a minimum average, both, "selected by" with it, and a
  top-N with the rejected left out; the files picked are those the votes give.
- `RoundWorstMarkFormSpec`: offered unchecked; saved on and off; kept by a client without it;
  refused for a yes/no and a criteria round; a juror, an organizer and another contest's admin get
  403. `RejectedByChoiceSpec`: the filter's rounds. `FlywayUpgradeSpec`: V91, V92.
- `LargeViewControllerSpec`: Delete, Backspace and the buttons by paths, the pressed state, the
  stars without the cancel icon, the clear only with a vote, none without the option.
  `GalleryByRateViewSpec` ("✗ n", its text), `GalleryTileStarsViewSpec` (`"worst"`), `CsvSpec`.
- Jest `worst-mark.test.js`: the tile's ✗ button (label, pressed, a path with the CSRF header,
  Enter/Space, a failure shown back, one at a time), the eraser shown only with a vote, the list's
  text, Delete and Backspace bound only with the option, `data-cancel`, the round form's checkbox
  and `#rejectFilters`.

### Measurements

**V91 on the Gatling fixture** (`gatling.setup.VoteRateNotNullBenchmark`, fixture rebuilt for V92,
cache `gatling-2464f9d74b1b08cc`; MariaDB 10.6.22 in the fixture's container, `sql_mode`
`IGNORE_SPACE,STRICT_TRANS_TABLES,…`; `vote` 767,340 rows (38k files, 20 jurors, two rounds),
`vote_aside` empty; SQL logging off, the app's recompute off; 3 runs, each the rollback then V91):

| Run | rollback `vote` / `vote_aside` | V91: the UPDATEs | `vote`'s ALTER | `vote_aside`'s ALTER | V91 in all |
|---|---|---|---|---|---|
| 1 | 10,749 / 78 ms | 1 / 1 ms | 10,367 ms | 143 ms | 10.5 s |
| 2 | 14,512 / 190 ms | 2 / 1 ms | 15,200 ms | 184 ms | 15.4 s |
| 3 | 13,707 / 182 ms | 2 / 1 ms | 15,371 ms | 174 ms | 15.6 s |

The rebuild is ~13–20 µs a row on this laptop (10–15 s for 0.77M rows, run-to-run spread 5 s); the
UPDATEs find nothing (the rate index). Writes go on during it (`LOCK=NONE`), but a long rebuild
holds an exclusive lock at its start and end, and the change log it replays grows with the writes
meanwhile. Production needs its own timing on a copy: for scale, V85's index build on `vote`
took 10.9 s on 3.07M rows (db practices §8.6); a full rebuild at this laptop's rate would take
40–60 s there. Deploy in a quiet hour. Log `target/checks/20261105-110928-22384.log`, results
`target/gatling/vote-rate-not-null-20261105-111023.txt` (the worktree).

The vote and list paths add no statement: the read changes are in the SQL text (a `GREATEST` per
row in a rated round's grouped sums, `rate_sum + rejections` per row on the counts), not measured
apart; P2b's benchmarks (§17) stand.

### Deploy note (V91, V92, V93)

Read-only, production, before the deploy (run by the user). Production is at V81 then: its
tables are still `selection`, `selection_deleted` and `rounds` (V82 renames them to `vote`,
`vote_aside` and `round` in the same release); the columns are the same.
```sql
-- 1. Negative rates already stored in rated rounds (before acceptsRate, 68c4716c, any integer
--    could be): after the deploy a -1 there is a ✗, and a rate below -1 breaks the votes' and the
--    counts' parity (grade 0 against rate_sum + rejections < 0). Expected: none.
SELECT s.round_id, MIN(s.rate), COUNT(*) FROM selection s JOIN rounds r ON r.id = s.round_id
  WHERE r.rates > 1 AND s.rate < 0 GROUP BY s.round_id;
SELECT s.round_id, MIN(s.rate), COUNT(*) FROM selection_deleted s JOIN rounds r ON r.id = s.round_id
  WHERE r.rates > 1 AND s.rate < 0 GROUP BY s.round_id;
-- 2. V91: the NULL rates it sets to 0 (unrated), the columns it changes, the rows it rebuilds
SELECT COUNT(*) FROM selection WHERE rate IS NULL;
SELECT COUNT(*) FROM selection_deleted WHERE rate IS NULL;
SELECT TABLE_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('selection', 'selection_deleted')
    AND COLUMN_NAME = 'rate';                         -- int(11), YES, NULL
SELECT COUNT(*) FROM selection; SELECT COUNT(*) FROM selection_deleted;
-- 3. Nothing long-running when V91 rebuilds: no backup, no long transaction
SHOW PROCESSLIST;
```
**If query 1 finds rows**, the user decides before the deploy, per round: keep them as −1 (✗:
counted as 0, shown as ✗; a rate below −1 is set to −1 first), or set them to 0 (unrated):
`UPDATE selection s JOIN rounds r ON r.id = s.round_id SET s.rate = 0 WHERE r.rates > 1 AND s.rate < 0`
(and `selection_deleted`); either before the deploy, so V89's backfill counts the result.

V91's ALTERs add strict mode to the session's `sql_mode` and a 60 s `lock_wait_timeout` for
themselves (`SET STATEMENT … FOR ALTER TABLE …`, through `EXECUTE IMMEDIATE`; accepted by 10.6, run
in `FlywayUpgradeSpec` also on a session without strict mode): the server's `sql_mode` needn't be
strict, and a rebuild that can't get its metadata lock within 60 s fails with nothing changed (a
rerun of V91 then goes on). Time V91 on a production-sized copy before the release
(`VoteRateNotNullBenchmark` on a restored backup, run by the user): the fixture's timing below is
not production's.

Rollback after `reopen` (tested, `FlywayUpgradeSpec`), with the app stopped; V93, then V92 first:
```sql
ALTER TABLE round DROP CONSTRAINT round_prev_rejected_mode_chk;
ALTER TABLE round DROP COLUMN prev_rejected_mode, ALGORITHM = INSTANT;
DELETE FROM flyway_schema_history WHERE version = '93';
ALTER TABLE round DROP COLUMN worst_mark, ALGORITHM = INSTANT;
DELETE FROM flyway_schema_history WHERE version = '92';
ALTER TABLE vote MODIFY rate INT NULL DEFAULT NULL, ALGORITHM = INPLACE, LOCK = NONE;
ALTER TABLE vote_aside MODIFY rate INT NULL DEFAULT NULL, ALGORITHM = INPLACE, LOCK = NONE;
DELETE FROM flyway_schema_history WHERE version = '91';
```
V91's rollback is **not** metadata only, as planned: MariaDB makes a column nullable instantly
only in `ROW_FORMAT=REDUNDANT` tables (`ALGORITHM=INSTANT is not supported`, 10.6); ours are
`DYNAMIC`, so it is an online rebuild like the forward. The older code reads a −1 in a rated round
as a rate below 0 (as 0 in its texts and averages, but its "rated" is `rate > 0` and its ranks' sum
counts −1: B11). Before rolling back the code of a round with ✗ given, the user decides whether
to keep them so or to clear them (`UPDATE vote v JOIN round r ON r.id = v.round_id SET v.rate = 0
WHERE r.rates > 1 AND v.rate < 0`, then `UPDATE round SET counts_gen = counts_gen + 1,
created_at = created_at WHERE rates > 1` so the counts are recounted: a bulk write marks no file).

### Checks

Logs under `target/checks/` of the worktree (Git Bash, `scripts/agent-check.py`, the sbt
launcher, one JVM at a time under the shared lock, Commons variables unset).

| Step | Specs | Result | Log |
|---|---|---|---|
| The naming commit | 11 specs: the SQL guards, the list, parity, counts and distribution specs | 162 passed | `20261105-102318-16720.log` |
| The option, first run | 27 affected specs | 270: 262 passed, 5 failed, 2 errors, 1 skipped: a rank column's text, V91's rollback refused as INSTANT, the moved rejected-by select, the tiles' JSON, a view fixture's grouped row without rejections | `20261105-104739-26880.log` |
| After the fixes, with the new specs and Jest | 27 specs, `jsTest` | 276: 275 passed, 1 skipped; Jest 23 suites, 302/302; `npm run lint` clean | `20261105-110333-12152.log` |
| V91's timing | `VoteRateNotNullBenchmark` | above | `20261105-110928-22384.log` |
| Full suite | `test` (Jest included), MariaDB 10.6, specs2's smart diffs off (a first run hung in them printing a failure's large diff, and was stopped) | **2099: 2087 passed, 2 failed, 2 errors, 8 skipped, 1 pending**; Jest 23 suites, 302/302; 11:58. Failed: `ListPositionSpec`, `JurorCountsSpec` (the Rated filter matched any vote in memory also in a yes/no round, whose SQL reads a yes: `VoteFilter.matches` now takes the round's kind). Errors: `ImportFilesPageSpec`, `MwMonumentListSourceSpec` (connect timeouts to Wikipedia). `RoundListDbSpec`'s sum-round ranks moved to the ✗-as-0 rule (B11) before it | `20261105-113213-2632.log` |
| Rerun after the fix | `ListPositionSpec`, `JurorCountsSpec`, `RoundListDbSpec`, `RoundListSpec`, `GalleryOrderSpec`, `GroupedListParitySpec`, `RoundCountsVoteSpec`, and the two network specs | **98 passed** | `20261105-114541-24324.log` |

No second full suite: the fix is `VoteFilter.matches`' round kind, rerun with every spec that
reads it.

**After the independent review** (follow-ups 1–7, and the user's rejected-by mode, V93):

| Step | Specs | Result | Log |
|---|---|---|---|
| Affected specs | 18 specs (Flyway V91 with `SET STATEMENT` and V93, the migration guards, the distribution and redistribution specs, the form, CSV, rule, parity and vote specs, `RawSqlSpec`) | 180: 177 passed, 2 failed, 1 skipped: `RoundRedistributionFiltersSpec` pinned the old rule (a rated source ignores rejected-by; now its ✗ count, follow-up 1) and the form's "no filter" sent as "" (Play reads it as absent: now `none`) | `20261105-121344-23040.log` |
| Rerun, with Jest | the two, the form and edit-page specs; `jsTest` | 41 passed; Jest 23 suites, 303/303; `npm run lint` clean | `20261105-122139-13136.log` |
| MariaDB 10.11 | `FlywayUpgradeSpec` (`-Dwlxjury.test.mariadb-image=mariadb:10.11.14`; V85's 10.6-only warning absent) | 12: 11 passed, 1 skipped (the 9.16.3 fixture's regeneration); V91 (`SET STATEMENT`, a non-strict session too), V92 and V93 pass | `20261105-122547-2224.log` |

### Decisions for the user (conservative choices made here)

- **The option can be turned off at any time**; the ✗ given stay, shown and counted as 0, and no
  new one is taken (the juror may still change or clear them). Refusing to turn it off while ✗
  exist is the alternative.
- **A juror's own by-rate list** sorts by the raw rate, so a ✗ (−1) comes after the unrated files.
- **The CSV** appends ` ✗ n` to the Overall cell (as the pages put it beside the score) rather than
  adding a column; the jurors' columns keep −1, explained by a legend row.
- **"Rejected by at least N" from a rated round** (changed after the review): offered and applied
  for any rated previous round without criteria (`Round.rejects`, the `takesWorstMark` rating
  condition without the live option), so turning the option off keeps a stored filter on a
  resubmit, "Distribute new files" and a job's restart; from a round without any ✗ it leaves out
  nothing. From several previous rounds: if any of them may reject, in the form, `RoundService`
  and the job alike.
- **A ✗ racing the option's turn-off** is taken: a vote doesn't lock the round row
  (`VoteJdbc.rateIn`'s note), and the ✗ stays, counted as 0, as one given just before.
- **The rejected-by mode's form:** the mode select's empty choice is no filter; a mode with X
  empty is no filter too; a client without the mode field (an older page, a script) excludes, the
  meaning before V93.
- **The tiles' clear** is raty's cancel icon, made an eraser and hidden while the file is
  unrated, rather than a separate button.
- **`hasRatedVote`** (the score-rule warning) counts a ✗ as a vote, as the coordinator asked,
  though a round with only ✗ scores every file 0.
- **V91's rollback is a rebuild**, not metadata only (above).
