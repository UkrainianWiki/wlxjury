# Criteria rounds

A criteria round is a rated round where each juror rates every file on several criteria
(e.g. "Технічна якість", "Корисність для Вікіпедії"). This plan records what exists today
(`ukwikidev/followups` at fa9d627c / c2af5f41), what is missing, and how to finish it so that an
organiser can create and run one. Investigation only; nothing implemented.

Line numbers are at c2af5f41.

## 1. Current state

### Schema

| Object | Migration | State |
|---|---|---|
| `criteria (id, round, name, contest)` | V9 (B44 baseline: `id` BIGINT UNSIGNED AUTO_INCREMENT, unique, no PK) | **Never read or written by the code.** Contents in production unknown. |
| `selection.criteria_id` | V9 | Mapped as `Selection.criteriaId` (`Selection.scala:11`), never set or read. |
| `criteria_rate (id, selection, criteria, rate)` | V10 | `id` BIGINT UNSIGNED NOT NULL, UNIQUE but not AUTO_INCREMENT and no PK; index on `selection`; no FK to `selection`, no UNIQUE `(selection, criteria)`. ~166k rows in the January 2026 dump; ~25k (estimate) in the copy trimmed to contests from 2024 on (`docs/history/2026-10-07-prod-like-benchmark.md`), so criteria rounds ran in 2024 or later. |
| `rounds.has_criteria` | V19 | Read (`Round.scala:294`); **not written by `Round.create`** (`Round.scala:310-343`), not in the round form (`EditRound.scala`). Only set by hand-run SQL (tests do `UPDATE rounds SET has_criteria = 1`). |
| V53 | | deletes `criteria_rate` rows of duplicate selections before the unique index. |

History: started 2015 (`b057460c`…`a37cb8c1`), rates per criterion and the total in 2016
(`80a05e4d`, `ea365301`), WLM UA 2023 names (`1d1a7d87`), names chosen by contest name
(`26541719`), then a fixed id→name map (`d87b7d0e`). Round 1327 (February 2024) got two criteria
by changing the code: `CriteriaIds = Seq(6, 7)` (`bb916499`), `numberOfStars` × 2 for
`id.contains(1327)` (`bddd46f7`), and a one-off `Tools.addCriteria()` for round 1327 (removed in
`4eb71633`). Ids 1–5 are Wiki Science Competition names, 6–9 WLM UA 2023. Recent work:
`5689cbe0` (missing selections → 404), `3b3564b8` (`CriteriaRate.countOf` replaced the 4/2),
`d2767358` (half-star criteria numbers). Montage has no criteria (`docs/comparison/03-comparison.md:132`).

### What works end to end (for a round given `has_criteria = 1` by SQL)

- **Distribution** writes one `criteria_rate` row (rate 0) per vote row and criterion, in the
  chunk transaction (`DistributeImages.insertRows`/`addCriteriaRates`, `DistributeImages.scala:332-350`;
  `RoundDistributionJobSpec` "write criteria rates in the chunk transaction"). Ids continue from
  `SELECT MAX(id) … FOR UPDATE`, which serialises concurrent criteria distributions.
- **Voting** a criterion in the large view: `POST …/select/:rate?criteria=n`
  (`conf/routes:62-63`, `LargeViewController.rateByPageId:44-90`): the juror's own vote only,
  rate checked by `Round.acceptsRate` per criterion (`MutationAuthorizationSpec:232-237`), 404
  without a vote (`:269`), CSRF as every vote. `CriteriaRate.updateRate` (`CriteriaRate.scala:23-40`)
  sets the criterion, then `selection.rate = SUM(rate > 0)` of the vote's criteria. The view stays
  on the same file (`LargeViewController.scala:85`). Round 0 (current round) works
  (`LargeImageSpec:440-532`).
- **Large view display** (juror, or an organiser viewing one juror): a number and raty stars per
  criterion, half stars included (`navigationButtons.scala.html:81-91`, `LargeViewSideSpec:124-142`,
  `LargeViewUrlsSpec:110-133`).
- **Results** use `selection.rate` (the criteria sum) like any rated round: by-rate gallery, file
  list, CSV, ranks (`RankKey` by `SUM(rate)`), top-N and min-average from a previous round
  (`ImageWithRatingSeqFilter.scala:100-112`). Sums are never negative, so B11's rank sums and
  `rateSum` agree here.
- **CoI "leave out now"** deletes the files' `criteria_rate` rows in `selection` and
  `selection_deleted` (`RoundConflicts.scala:67-70`, `CoiRoundSpec:174-214`).
- **Deleted files**: votes moved aside keep their ids, so their `criteria_rate` rows survive a
  delete and restore on Commons.

### Partial, hard-coded or broken

| # | Problem | Where |
|---|---|---|
| S1 | No way to create a criteria round or define criteria: `has_criteria` not written, no form field. | `Round.scala:310-343`, `EditRound.scala` |
| S2 | Criterion ids hard-coded `Seq(6, 7, 8, 9) // TODO load from DB` for every criteria round. | `DistributeImages.scala:288` |
| S3 | Names hard-coded in Ukrainian (ids 1–9) in the controller; ids outside 1–9 get no name and no stars (the view zips names, not rates); names come from a `Map`'s iteration order, rates from sorted ids. | `LargeViewController.scala:157-169`, `navigationButtons.scala.html:83-90` |
| S4 | Digit keys vote the **overall** rate (no `criteria`) in a criteria round: `SelectionJdbc.rate` overwrites `selection.rate`, desynchronised from the criteria until the next criterion vote recomputes it. The endpoint accepts such a vote from any client. | `keyboardNav.scala.html:16-17`, `LargeViewController.scala:71-73` |
| S5 | A `criteria` value not among the vote's criteria updates nothing but answers 303 as a success. | `LargeViewController.scala:74-78` |
| S6 | `updateRate` is two autocommit statements, no lock or transaction. | `CriteriaRate.scala:23-40` |
| S7 | All-jurors large view (`asUserId = 0`) shows **no rate at all**: `byCriteria` is empty, so the criteria branch renders nothing. | `LargeViewController.scala:149`, `navigationButtons.scala.html:81` |
| S8 | The organiser's per-juror list in the large view draws `rates.id` stars for the criteria sum: e.g. 4 criteria rated 7 → 28 on 10 stars, all full. | `showRate.scala.html:18-22` via `juryStat.scala.html:11` |
| S9 | By-rate gallery draws `rates × criteria` stars (40 for 4×1–10); the file list and CSV show sums with no hint of the scale. `criteriaCount` is read only for Gallery/ByRate (`CriteriaRate.countOf`, from one vote's rows), defaulting to `CriteriaIds.size`. | `Round.scala:67,109-114`, `GalleryController.scala:214-216`, `galleryByRate.scala.html:32` |
| S10 | No per-criterion results anywhere (gallery, file list, CSV, round statistics). | `Csv.scala:80` |
| S11 | A partly rated vote (1 of 4 criteria) counts as rated: leaves the juror's Unrated filter, counts in `ratedJurors`, "selected by at least", `activeJurors`. | `ImageWithRating.scala:89-100`, `RateDistribution` (`GalleryController.scala:663`) |
| S12 | "Min average rate" from a criteria previous round compares the per-juror **sum** (0…max×n, in stored half-star units) to the number typed in the form. | `ImageWithRatingSeqFilter.scala:110-112` |
| S13 | Orphan `criteria_rate` rows: every vote delete except CoI leaves them: `removeImage`, `removeUnrated`, `moveVotes`' duplicate drop, `moveVotesBack`' dropped and replaced rows. An FK with `ON DELETE CASCADE` is not a fix: moving a vote aside deletes it from `selection`. | `SelectionJdbc.scala:225-268`, `ImageJdbc.scala:403-414` |
| S14 | Dead code: `CriteriaRate.batchInsert` (would fail: no `id`), `SelectionJdbc.byRoundWithCriteria` (averages, while `updateRate` sums). | `CriteriaRate.scala:58-72`, `SelectionJdbc.scala:147-163` |
| S15 | Juror UX: only the large view rates criteria (tiles excluded, `TileStars.scala:15`); no keyboard, no sign of a file with criteria left unrated, no move to the next file after the last criterion. | |

Open item 3.4 (`2026-11-01-open-followups.md:151`): `countOf` gives round 1327's 2 only if its
votes have two `criteria_rate` rows each; not checked against production.

### Tests

`RoundCriteriaStarsSpec` (stars, `countOf`), `LargeImageSpec` "a round with criteria" (display,
vote, round 0, no rows), `MutationAuthorizationSpec` (rate range, 404), `RoundDistributionJobSpec`
(chunk transaction, rollback, unique ids), `CoiRoundSpec` (deletion), views (`LargeViewSideSpec`,
`LargeViewUrlsSpec`, `GalleryByRateViewSpec`, `GalleryTileStarsViewSpec`, `RoundStatTableViewSpec`;
`LargeViewRenders.criteriaRound` has names "Quality", "Composition"). Not covered: S4, S5, S7, S8,
S11–S13, CSV/file list of a criteria round, next round from a criteria round, deleted files with
criteria.

## 2. Gaps, by impact

1. **Creation and definition (S1–S3):** without them no new criteria round can exist; every past
   one needed a code change. Criteria per round, names entered by the organiser, stored in the DB.
2. **Data integrity of votes (S4–S6, S13):** the digit keys silently corrupt the overall rate;
   success answers for no-ops; non-atomic writes; orphans.
3. **Overall rate meaning and display (S7–S9, S11, S12):** organisers can't read results in the
   all-jurors view; stars overflow; partial votes count as rated; threshold on the wrong scale.
4. **Per-criterion results (S10):** CSV/file list/large view per criterion.
5. **Juror UX (S15):** keyboard, progress, next file.
6. **Cleanup (S14, unused `criteria` columns, `selection.criteria_id`).**

Weights and localisation: no evidence either was ever used (names were always one language per
contest); both listed as decisions.

## 3. Interaction with the round-images plan (merged into `2026-11-02-round-images-two-step.md` §5, §8 with P1, 2026-11-03)

- **P0 rename:** keep `criteria_rate.selection → vote_id` in P0 as planned. Leave the other
  criteria changes (column `criteria → criterion_id`, keys) to C2 so P0 stays behaviour-free.
  C1 touches `CriteriaRate`/`LargeViewController`/`SelectionJdbc` SQL: land it before P0 (as one of
  the "open branches") or rebase it after; not in between.
- **`round_image` counts:** `rate_sum = SUM(vote.rate)` is correct for criteria rounds as long as
  `vote.rate` stays the criteria sum (decision U2); ranks, top-N and averages follow. `voted`
  (`rate <> 0`) means "any criterion rated" today; if U5 makes a vote count only when all its
  criteria are rated, the vote needs that state stored (recommended: `vote.rate` stays the sum,
  and a vote row is "voted" by a criteria-aware delta: the writer knows the criteria), so the
  delta rule in §5 must take it from `updateRate`, not from `rate <> 0`. Note it in §4 "Counts".
- **Per-criterion sums:** not in `round_image` (a variable number of criteria, NULL for nearly
  every round) and no child table now. Per-criterion averages are read on demand by a
  `GROUP BY criterion_id` over `criteria_rate JOIN vote` for one round or one page of files
  (criteria rounds are small: 166k rates in all of production). Add
  `round_image_criterion (round_id, page_id, criterion_id, rate_sum, voted)` only if a measured
  criteria round makes the on-demand read slow, or if sorting by one criterion is wanted (U9).
- **Vote writes (§5):** `CriteriaRate.updateRate` becomes the one `localTx` §5 describes in C1
  (lock the vote row, upsert the criterion, recompute `vote.rate`); P2a adds the `round_image`
  delta to that same transaction. **Lock order:** §5 lists `… vote → vote_aside → round_image →
  criteria_rate`, but a criterion vote writes `criteria_rate` before the delta. Recommend
  `round → images → vote → criteria_rate → vote_aside → round_image`, with the rule that
  `criteria_rate` rows are written only while their vote row is locked; `RoundConflicts` today
  deletes `criteria_rate` before locking the votes (`RoundConflicts.scala:67-71`): lock the votes
  first (C1), until P1b removes that delete.
- **`vote_aside`:** criteria rates follow their vote by id (aside, back, disqualified,
  requalified: no change needed). Add invariant 7 to §5: every `criteria_rate.vote_id` exists in
  `vote` or `vote_aside`; the repair job deletes orphans and records them. P1b spec "requalify
  restores exact rates and `criteria_rate`" already covers the criteria side.
- **P2b reads:** `CriteriaRate.countOf` disappears with C2 (criteria come from the round's
  definition), so drop it from §8's "stays on `vote`" list once C2 ships.
- **P3 two-step:** criteria are defined in the round form, before the fill; they can change freely
  while the round is draft/paused with no votes (re-apply), names only afterwards (U10). With
  upsert-on-vote (U6), neither fill nor distribute writes `criteria_rate`; if pre-created rows
  stay, the distribute step (not fill) writes them.
- **Placement:** C1 before P0; C2 after P0 (new SQL uses `vote`/`vote_id`), independent of P1;
  C3 after C2 (if P2b is on, the all-jurors order uses `rate_sum`, per-criterion columns stay
  on-demand); C4 after C2; invariant 7 with P1's repair job.

## 4. Plan

Migration numbers are assigned at merge time. Each batch: compile, its specs (negative
authorization and CSRF where a form or endpoint is new), the Jest file for changed JS; the full
suite once at the release boundary (CLAUDE.md).

### C0: production facts (read-only SQL, run by the user)

No code. Results decide U1, U6, U11 and close open item 3.4.

Before P0 (V82), as written below; after it, the second block (`round`, `vote`, `vote_aside`,
`criteria_rate.vote_id`), and from V83 on its damage query replaced by the third block (R223-1).
The damage queries read the votes moved aside too (`selection_deleted`, `vote_aside`: a file
deleted on Commons keeps its votes there, and they come back with their rates).

```sql
-- before V82
SELECT r.id, r.contest_id, r.name, r.rates, r.half_star, r.active, c.name, c.year
  FROM rounds r JOIN contest_jury c ON c.id = r.contest_id WHERE r.has_criteria = 1;
SELECT s.round_id, cr.criteria, COUNT(*), SUM(cr.rate > 0)
  FROM criteria_rate cr JOIN selection s ON s.id = cr.selection GROUP BY s.round_id, cr.criteria;
SELECT * FROM criteria;
SELECT COUNT(*) FROM criteria_rate cr WHERE NOT EXISTS (SELECT 1 FROM selection s WHERE s.id = cr.selection)
  AND NOT EXISTS (SELECT 1 FROM selection_deleted d WHERE d.id = cr.selection);   -- orphans
SELECT selection, criteria, COUNT(*) FROM criteria_rate GROUP BY 1, 2 HAVING COUNT(*) > 1 LIMIT 20;
SELECT COUNT(*) FROM selection WHERE criteria_id IS NOT NULL;
-- votes, live and aside, whose rate differs from their criteria sum (S4 damage)
SELECT v.src, v.round_id, COUNT(*)
  FROM (SELECT 'selection' src, id, round_id, rate FROM selection
        UNION ALL SELECT 'selection_deleted', id, round_id, rate FROM selection_deleted) v
  JOIN (SELECT selection, SUM(IF(rate > 0, rate, 0)) crit_sum FROM criteria_rate GROUP BY selection) x
    ON x.selection = v.id
 WHERE v.rate <> x.crit_sum GROUP BY v.src, v.round_id;
```

```sql
-- after V82
SELECT r.id, r.contest_id, r.name, r.rates, r.half_star, r.active, c.name, c.year
  FROM round r JOIN contest_jury c ON c.id = r.contest_id WHERE r.has_criteria = 1;
SELECT v.round_id, cr.criteria, COUNT(*), SUM(cr.rate > 0)
  FROM criteria_rate cr JOIN vote v ON v.id = cr.vote_id GROUP BY v.round_id, cr.criteria;
SELECT * FROM criteria;
SELECT COUNT(*) FROM criteria_rate cr WHERE NOT EXISTS (SELECT 1 FROM vote v WHERE v.id = cr.vote_id)
  AND NOT EXISTS (SELECT 1 FROM vote_aside d WHERE d.id = cr.vote_id);   -- orphans
SELECT vote_id, criteria, COUNT(*) FROM criteria_rate GROUP BY 1, 2 HAVING COUNT(*) > 1 LIMIT 20;
SELECT COUNT(*) FROM vote WHERE criteria_id IS NOT NULL;
-- votes, live and aside, whose rate differs from their criteria sum (S4 damage); before V83 only
SELECT v.src, v.round_id, COUNT(*)
  FROM (SELECT 'vote' src, id, round_id, rate FROM vote
        UNION ALL SELECT 'vote_aside', id, round_id, rate FROM vote_aside) v
  JOIN (SELECT vote_id, SUM(IF(rate > 0, rate, 0)) crit_sum FROM criteria_rate GROUP BY vote_id) x
    ON x.vote_id = v.id
 WHERE v.rate <> x.crit_sum GROUP BY v.src, v.round_id;
```

From V83 on (C2's U5), a vote rated in only some of its round's criteria has `rate` 0 by design,
and the query above would list it as damage; one rated in part before U5 keeps its partial sum.
Damage is then a rate that is neither its criteria sum nor U5's value (the sum of its round's
criteria once every one is rated above 0, else 0):

```sql
-- after V83: S4 damage, live and aside
SELECT d.src, d.round_id, COUNT(*) FROM (
  SELECT v.src, v.round_id, v.id
    FROM (SELECT 'vote' src, id, round_id, rate FROM vote
          UNION ALL SELECT 'vote_aside', id, round_id, rate FROM vote_aside) v
    JOIN (SELECT vote_id, SUM(IF(rate > 0, rate, 0)) crit_sum FROM criteria_rate GROUP BY vote_id) x
      ON x.vote_id = v.id
    JOIN round_criterion rc ON rc.round_id = v.round_id
    LEFT JOIN criteria_rate cr ON cr.vote_id = v.id AND cr.criterion_id = rc.criterion_id
   GROUP BY v.src, v.round_id, v.id, v.rate, x.crit_sum
  HAVING v.rate <> x.crit_sum AND v.rate <> IF(MIN(IFNULL(cr.rate, 0)) > 0, SUM(cr.rate), 0)
) d GROUP BY d.src, d.round_id;
```

### C1: vote integrity, no schema change (before P0)

- `rateByPageId`: in a criteria round a vote without `criteria` is 400
  (`vote.error.invalid.rate`); a `criteria` not among the vote's criteria is 404 (as a missing
  vote); `criteria` on a round without criteria is 400.
- `keyboardNav`: no digit vote keys in a criteria round (C4 adds criterion keys).
- `CriteriaRate.updateRate`: one `DB.localTx`, `SELECT … FROM selection WHERE id = ? FOR UPDATE`,
  update the criterion, recompute `rate`; returns whether a row matched. Retry once on deadlock.
- Orphans: delete the `criteria_rate` rows of the votes `removeImage`, `removeUnrated`,
  `moveVotes` (dropped duplicates) and `moveVotesBack` (deleted new rows, dropped aside rows)
  delete, in their transactions, after locking the votes; `RoundConflicts` locks votes before
  deleting rates. One migration deletes existing orphans (count from C0 in the commit message).
- Optional repair of S4 damage: one migration recomputing `selection.rate` from the criteria for
  the votes C0 lists (U12), live and aside (`selection_deleted`/`vote_aside`); run after V83, to
  U5's value (the sum once every criterion of the round is rated, else 0), for the votes the
  "after V83" query lists.
- Remove `CriteriaRate.batchInsert` and `SelectionJdbc.byRoundWithCriteria`.
- Files: `LargeViewController`, `keyboardNav.scala.html`, `CriteriaRate`, `SelectionJdbc`,
  `ImageJdbc`, `RoundConflicts`, `conf/messages*` (none new expected).
- Specs: `LargeImageSpec` (vote without criterion 400 and rates unchanged; unknown criterion 404;
  criterion on a plain round 400), `MutationAuthorizationSpec` (another juror's file, a juror of
  another contest, signed out → existing denials, for `?criteria=`), CSRF refused for a criterion
  vote (`CsrfTestApp.withCsrfApp`), `LargeViewUrlsSpec` (no digit keys in a criteria round),
  orphan specs in `SelectionSpec`/`DeletedFilesSpec`/`CoiRoundSpec` (no `criteria_rate`
  without a vote after each path), a concurrency spec (two criteria of one vote in parallel
  futures, sum correct).

#### C1 results (branch `ukwikidev/criteria-c1`, from `ukwikidev/followups` 1d4020b0)

Implemented as planned, with the deviations noted. No schema change but V78.

- **Votes refused** (`LargeViewController.rateByPageId`): in a criteria round a vote without
  `criteria` is 400 (`vote.error.invalid.rate`, the page the out-of-range rate gets); `criteria` on
  a round without criteria is 400; a `criteria` the juror's vote hasn't is 404 (`updateRate`
  returns false), as a vote that isn't the juror's. The gallery's tile vote (`selectWS`) answers
  400 `invalid_rate` for any rate in a criteria round (its tiles have no stars; vote-errors.js
  already explains `invalid_rate`). Checks keep their order: permission (403), round open
  (redirect/409), rate and criteria (400), own vote (404). CSRF unchanged (the filter, before the
  action).
- **Keys:** `keyboardNav.scala.html` renders no `data-vote-key-<digit>` in a criteria round;
  large-keys.js binds only the keys it finds, so no JS change (Jest not rerun for it).
- **`CriteriaRate.updateRate(selection, criteria, rate): Boolean`**: one `DB.localTx`:
  `SELECT id FROM selection WHERE id = ? FOR UPDATE`, `UPDATE criteria_rate` (rows matched, as
  `SelectionJdbc.rate` counts them), `SELECT SUM(IF(rate > 0, rate, 0)) … FOR UPDATE` (a locking
  read: the latest rates, not a snapshot), `UPDATE selection SET rate`. Retried once on a
  deadlock (`DbErrors.isDeadlock`: error 1213 / SQLState 40001 in the cause chain; a lock wait
  timeout is not retried; `JobScheduler.isTransient` uses the same classifier, with lock wait
  timeouts).
  `updateRateIn` is the same in the caller's transaction (used by the lock spec). Still an update
  of pre-created rows: the upsert is C2's (U6).
- **Every vote delete deletes its rates**, after locking the votes:
  `SelectionJdbc.deleteVotes(where on alias v)`, per table (selection, then selection_deleted):
  `SELECT COUNT(*) … FOR UPDATE` (locks without sending rows), `DELETE cr FROM v STRAIGHT_JOIN
  criteria_rate cr` (from the votes to their rates by `criteria_selection_index`, so only their
  rates are locked, not every rate scanned), `DELETE v`.
  Used by `removeImage`, `removeUnrated` and `RoundConflicts.removeFromRound` (which now locks the
  votes before deleting their rates). `moveVotes` (`setRound`) locks the source votes and deletes
  the rates of the duplicates it drops; moved votes keep their ids and rates.
  `ImageJdbc.moveVotesAside` deletes the rates of an older aside row that its `REPLACE` drops
  (same vote under another id: a file deleted, distributed again, deleted again).
  `moveVotesBack` deletes the rates of the new unrated rows a moved-back rated row replaces and of
  the aside rows it drops (round gone, or a new rated vote won); rows moved back keep theirs.
  No other path deletes votes in app code (`rg` over `app/`: `Selection.destroy` sets rate -1;
  images are never deleted, V63; rounds are deleted only by hand, which `moveVotesBack` covers).
- **Lock order, deviation:** §3 recommends `rounds → images → selection → criteria_rate →
  selection_deleted` with "rates written only while their vote is locked". For a vote moved aside
  the vote row *is* the selection_deleted row, so both can't hold literally. As implemented,
  every vote delete or move locks `rounds → images → selection → selection_deleted`, and a
  `criteria_rate` row only ever while its vote row (in selection or selection_deleted) is held.
  Gap locks don't conflict with each other and these deletes take no insert intention on
  `criteria_rate`, so a lock cycle can only come from `selection`/`selection_deleted`; review
  M1 found one there (below), now fixed: `moveVotesBack` locks the files' `selection` rows (and
  gaps) first.
- **V78 `Criteria_rate_orphans`** (provisional number): archives the orphans to
  `criteria_rate_orphan` (singular, CLAUDE.md table names; criteria_rate's columns under an
  `archive_id` key of its own, `archived_at`, plain `INSERT`, so a reused criteria_rate id
  archived later keeps both rows), then deletes them. It also archives the rates of the votes V58
  moved to `selection_orphans`. Idempotent: a rerun archives and deletes nothing. On the test and
  Gatling fixtures it deletes 0 rows (neither has criteria rates); the production count is C0's
  orphans query (not run: C0 skipped).
- **Recommendation for P0 (merge into the round-images plan):** add `criteria_rate_orphan` to
  P0's rename list next to `selection_orphans` (its `selection` column → `vote_id`).
- **Removed:** `CriteriaRate.batchInsert` and `SelectionJdbc.byRoundWithCriteria` (no callers),
  and `SelectionJdbc.c`.
- Not done (as scoped): U12 repair of S4 damage, U5 (C3), upsert (C2).

Specs:
- `org.intracer.wmua.CriteriaRateSpec` (new): sum and 0-reset; unknown criterion and vote gone
  false, nothing written; a second criterion vote waits for the first's uncommitted transaction
  and then sums both (this passes on the old code too: its `UPDATE criteria_rate` locked the
  vote's rate rows by index, so it does not prove the vote-row lock; the review-fix spec does); 20 votes × 3 criteria voted at once
  on 6 threads, three passes, every sum right; retry once on deadlock only; no orphans after
  `removeImage`, `removeUnrated`, `setRound`, delete + restore on Commons (rates kept), restore
  replacing / dropping rows, restore of a deleted round's votes, and moving aside again.
- `db.CriteriaRateOrphansMigrationSpec` (new): V78 on table copies, orphans archived and deleted,
  rates of votes and of aside votes kept, rerun no-op; the migrated DB has the archive table.
- `MutationAuthorizationSpec`: criterion rate range in a criteria round; 400 for the file's own
  rate (gallery and large view) and 404 for an unknown criterion; 400 for a criterion on a plain
  round; another juror's file 404, an organiser who isn't a juror 404, another contest's juror
  403, signed out to the login page, nothing changed.
- `LargeImageSpec`: own-rate vote 400 with rates and sum unchanged; unknown criterion 404; a
  criterion vote through the router with the CSRF filter: refused without or with a forged
  token, voted with it.
- `LargeViewUrlsSpec`: no key votes in a criteria round. `CoiRoundSpec`: no orphans after the
  removal.
- After review (below): a criterion vote waits for its vote row held by a delete, then writes
  nothing (old code wrote the rate of a vote being deleted); a restore waits at the file's
  `selection` rows while another transaction locks its aside rows (old order deadlocked); both
  wait on `information_schema.INNODB_TRX` `LOCK WAIT` (20 s at most), no sleeps.

Checks (Git Bash, `scripts/agent-check.py`, sbt launcher, one JVM): `Test/compile`,
`Gatling/compile`, and `testOnly` of CriteriaRateSpec, CriteriaRateOrphansMigrationSpec,
MutationAuthorizationSpec, LargeImageSpec, LargeViewUrlsSpec, LargeViewControllerSpec,
LargeViewSideSpec, CoiRoundSpec, RoundDistributionJobSpec, DeletedFilesSpec, ImageDistributorSpec,
RoundImageCountsDbSpec, RoundCriteriaStarsSpec, MigrationNamingSpec: 219 passed, 0 failed, 1
pending (ImageDistributorSpec's existing "Pending until fixed"); log
`target/checks/20261102-141447-24852.log` in the C1 worktree. No full suite (not a release
boundary); no Jest run (no JS changed).

**Merge checklist (C1):**
- Renumber V78 to the next free version at merge time: after S2's V77 if S2 merges first (production
  runs Flyway with `validateOnMigrate = true` and no `outOfOrder`, so a version below one already
  applied would fail). Rename the file and its path in `CriteriaRateOrphansMigrationSpec`.
- Put the production orphan count (C0's query) in the commit message or here.

Open: the production orphan count (C0's query) for V78's commit message; whether `removeUnrated`
should treat a partly rated criteria vote as unrated once U5 lands (C3: today `rate = 0`, i.e. no
criterion rated).

#### C1 review fixes

| # | Finding | Fix |
|---|---|---|
| M1 | `moveVotesBack` locked `selection_deleted` before `selection`; `RoundConflicts`, `archiveDeletedVotesInRound` and `moveVotesAside` go the other way, without a file lock (restore vs. "leave out now" or opening a round: deadlock 1213, not retried) | `moveVotesBack(pageIds)` first locks `selection WHERE page_id IN (…) FOR UPDATE`; every path is now selection → selection_deleted → criteria_rate. Spec: restore waits at `selection` while another transaction locks the aside rows. |
| M2 | The concurrency specs pass on the old code too | New spec: vote row held `FOR UPDATE` by a delete; `updateRate` waits (`LOCK WAIT` in `INNODB_TRX`), `criteria_rate` unchanged meanwhile, then returns false and writes nothing. Sleeps replaced by the bounded `LOCK WAIT` poll; "which failed before" dropped. |
| M3 (follow-up review) | After M1, `DeletedFiles.restore` locked images → selection → selection_deleted and only then read `rounds` with a shared lock (the `INSERT … WHERE EXISTS (SELECT 1 FROM rounds …)` in `moveVotesBack`, REPEATABLE READ), while "leave out now" and a distribution chunk lock the round first: restore vs. either deadlocked | `DeletedFiles.restore` first takes shared locks on the rounds of the files' aside votes, in id order (`ImageJdbc.lockRoundsOfArchived`, read from the snapshot as `markDeleted`'s `lockOpenRoundsOf`), then the files. Chosen over reading round ids without a lock and using `d.round_id IN (…)`: a round missed by that read would drop its votes, a round missed here only risks a retried deadlock. Spec: the round held `FOR UPDATE` as `RoundConflicts` holds it; the restore waits at the round; the holder then deletes the file's votes (aside) without waiting. |
| M3 check, flake | Case-1 spec unproven; the one lock-spec flake | With `lockRoundsOfArchived` commented out, the case-1 spec fails with a deadlock (`target/checks/20261102-160820-10276.log`); restored, the tree identical. The flake: InnoDB refreshes `INNODB_TRX`'s cache only when it wasn't read for 100 ms, and the helper polled every 20 ms, so after an early read it saw stale rows for 20 s; it now polls every 200 ms (commented). `RoundConflicts.removeFromRound` names the spec at its lock order. Then CriteriaRateSpec 3 of 3, DeletedFilesSpec and CoiRoundSpec passed (`target/checks/20261102-161107-13360.log`). V78 is renumbered at merge by the orchestrator. |
| M1/M2 check | | With the two locks removed by hand (`moveVotesBack`'s first `selection` lock, `updateRateIn`'s `FOR UPDATE`), all three lock specs fail (1 failure, 2 errors, log `target/checks/20261102-145527-26248.log`); with them, all pass. `INNODB_TRX` needs PROCESS: the spec reads it as the container's root. |
| L1 | Rate deletes driven from `criteria_rate` could lock (and scan) every rate | `DELETE cr FROM <votes> STRAIGHT_JOIN criteria_rate cr ON cr.selection = v.id` in `deleteVotes`, `moveVotes`, `moveVotesAside`, `moveVotesBack`. |
| L2 | Lock-order argument | Reworded above. |
| L3 | Archive table name; numbering | `criteria_rate_orphan`; P0 rename recommendation; merge checklist item. |
| L4 | Legacy criteria rounds without `criteria_rate` rows | Noted in C2 (votes). |
| Nits | `where` unqualified in `moveVotesAside`; V78 comment; `INSERT IGNORE`; two deadlock classifiers; Space/Del in a binary round with `has_criteria = 1` | `moveVotesAside` takes the alias (`s`/`v`); comment names `selection_orphans`; own archive key, plain INSERT; `DbErrors` shared with `JobScheduler`; `keyboardNav` renders no vote keys at all when `hasCriteria`. |

Review-fix checks: `Test/compile`, `Gatling/compile`, `testOnly` of CriteriaRateSpec,
CriteriaRateOrphansMigrationSpec, DeletedFilesSpec, CoiRoundSpec, MutationAuthorizationSpec,
LargeImageSpec, LargeViewUrlsSpec, RoundDistributionJobSpec, MigrationNamingSpec,
JobSchedulerSpec, RoundImageCountsDbSpec: 208 passed and the 3 new lock specs erred on the
PROCESS privilege (`target/checks/20261102-144106-26384.log`); after reading `INNODB_TRX` as root,
CriteriaRateSpec 14/14 (`target/checks/20261102-144904-8148.log`).

Follow-up checks (M3): the affected set with the import specs (CriteriaRateSpec,
CriteriaRateOrphansMigrationSpec, DeletedFilesSpec, CoiRoundSpec, MutationAuthorizationSpec,
LargeImageSpec, LargeViewUrlsSpec, RoundDistributionJobSpec, MigrationNamingSpec,
JobSchedulerSpec, RoundImageCountsDbSpec, CommonsFileCheckSpec, RegionCountInvalidationSpec,
PageImportSpec, AppendImagesSpec, ImportFilesPageSpec): all passed but one example
(`target/checks/20261102-150736-24412.log`): the first lock spec saw no `LOCK WAIT` in 20 s, once
(cause found later: `INNODB_TRX`'s 100 ms cache, see "M3 check, flake"). Its helper now reports what
happened instead (the waiter done, or `INNODB_TRX`'s rows); CriteriaRateSpec then passed 3 runs of
3 (`target/checks/20261102-153722-29680.log`). Final run of that set on 5eef2db7: 263 passed, 0
failed (`target/checks/20261102-155458-22196.log`).

### C2: criteria defined per round (after P0)

- Schema (one migration, `criteria` is unused today, so it is reshaped in place; table keeps its
  name, or becomes `round_criterion` per D11 (U3)):
  `criterion (id BIGINT UNSIGNED AUTO_INCREMENT PK, round_id BIGINT UNSIGNED NOT NULL FK rounds
  ON DELETE CASCADE, position INT NOT NULL, name VARCHAR(255) NOT NULL, UNIQUE (round_id, position))`.
  Backfill: one row per distinct `(round, criteria)` of existing `criteria_rate`, named from
  today's map (ids 1–9), else "Criterion n"; `criteria_rate.criteria` is rewritten to the new
  ids and renamed `criterion_id`. `criteria_rate`: `id` becomes the AUTO_INCREMENT PK, add
  `UNIQUE (vote_id, criterion_id)` (after removing C0's duplicates) and FK `criterion_id` →
  `criterion` ON DELETE CASCADE. Drop `selection.criteria_id` (`vote.criteria_id` after P0) if C0
  shows it unused (U11). `round.has_criteria` (`rounds` before P0) stays, kept equal to "has criterion rows".
- Model: `Round.criteria: Seq[Criterion]` loaded by `CriterionJdbc.ofRound(s)`; remove
  `CriteriaIds`, `criteriaCount`'s default, `criteriaNames`, `CriteriaRate.countOf` and the name
  map; `Round.create` writes `has_criteria`.
- Votes: `updateRate` upserts (`INSERT … ON DUPLICATE KEY UPDATE`) if U6 = on vote, and the
  distribution stops writing rows (`addCriteriaRates` and its `MAX(id) FOR UPDATE` go); missing
  rows read as 0. Otherwise keep pre-creation with the round's ids. Since C1, a legacy criteria
  round whose votes have no `criteria_rate` rows (`has_criteria` set after the distribution, e.g.
  round 1327 if it was set by hand) answers 404 to every criterion vote and has no digit keys,
  so its jurors can't vote until this upsert (U6) ships.
- Round form (rated rates only): "Rate on several criteria" checkbox and a list of names (add,
  remove, reorder, up to 10), prefilled from the previous round's criteria when it has some.
  Before any vote (and in P3's draft/paused state): full edit. After votes: names only; the
  server refuses adding/removing (U10). Validation: 2–10 non-empty distinct names, ≤ 255 chars.
- Large view iterates `round.criteria` (names in order, rate 0 when absent).
- Files: migration, `Criterion`/`CriterionJdbc` (new), `Round`, `EditRound`, `RoundController`,
  `RoundService`, `editRound.scala.html`, `round-form.js`, `DistributeImages`, `CriteriaRate`,
  `LargeViewController`, `navigationButtons.scala.html`, `conf/messages*` (form labels, en/uk/ru
  as the form has), `SharedTestDb.dataTables`, the Gatling fixture only if it makes criteria
  rounds (no: leave it).
- Specs: migration spec (backfill ids/names from fixture rows, rerun unchanged), `CriterionJdbcSpec`
  (AutoRollback), `RoundEditPageSpec`/`RoundControllerSpec` (create with criteria, edit names,
  add after votes refused), `RoundDistributionJobSpec` (no rates written, or the round's ids),
  `LargeImageSpec` (names in order, custom ids), negative authorization: organizer, juror, an admin
  of another contest cannot save criteria (403, nothing written); CSRF missing on the form → refused.

#### C2 results (branch `ukwikidev/criteria-c2`, from `ukwikidev/followups` 82cfba45)

Implemented with the decided U1–U12 (U11/U12 deferred: C0 not run). Migration **V83**
(`V83__Criteria_catalogue.sql`).

**Schema (V83).**

| Object | Change |
|---|---|
| `criterion` (new) | `id` BIGINT UNSIGNED AUTO_INCREMENT PK, `message_key` VARCHAR(100) UNIQUE, `fallback_name` VARCHAR(255) UNIQUE (case-insensitive by its collation), `created_by` (user id, no FK), `created_at`. Rows 1–9: today's criteria under their ids (`criterion.science` … `criterion.extra.points`, the old map's Ukrainian names as fallback texts). |
| `round_criterion` (new) | `(round_id, position)` PK, UNIQUE `(round_id, criterion_id)`, FK `round_id` → `round` ON DELETE CASCADE, FK `criterion_id` → `criterion` (RESTRICT). `round.has_criteria` stays, kept equal to "has rows here" (`Round.create`, `RoundCriterion.insert`/`update`). |
| `criteria_rate` | `id` AUTO_INCREMENT PRIMARY KEY (the `UNIQUE id` key dropped); `criteria` → `criterion_id` BIGINT UNSIGNED, FK → `criterion`; `vote_id` BIGINT UNSIGNED (vote.id's type); UNIQUE `criteria_rate_vote_criterion_uidx (vote_id, criterion_id)` (replaces `criteria_rate_vote_id_idx`, its prefix); KEY `criteria_rate_criterion_id_idx`. No FK on `vote_id` (a vote moved aside leaves `vote`). One table copy (~166k rows in January 2026). |
| `criteria_rate_orphan` | `reason` VARCHAR(32) NULL: `duplicate` for V83's archived duplicates, NULL for V78's orphans. |
| left as they are | the legacy `criteria` table and `vote.criteria_id` (U11: drop after C0); `Vote.criteriaId` still mapped. |

**Backfill, and unknown production data** (C0 not run: V83 is safe on any data, or stops before
changing anything):
- Criterion ids 1–9 keep their ids, so existing rates need no rewrite. Any other positive id in
  `criteria_rate` gets a catalogue row under the same id (`criterion.legacy.<id>`, "Criterion
  <id>"), so the foreign key holds for unexpected ids and for rates of orphan votes too.
- A criteria or vote id ≤ 0, or `criteria_rate` keys other than every known schema's (a PRIMARY
  KEY, no `UNIQUE id`, no `criteria_rate_vote_id_idx`), stop V83 at its first statements
  (`SIGNAL`: "V83 stopped, nothing changed: criteria_rate has …" with the counts), before any
  change: then a decision for the user (review fix F5).
- `round_criterion`: per round with `has_criteria = 1`, the distinct criteria of its votes' rates
  (`vote` and `vote_aside`), positions in id order (the large view's order until now); such a
  round without rates gets 6, 7, 8, 9 (what its distribution would have written). A round with
  rates but not `has_criteria = 1` gets none (no criteria round; its rates stay, read by nothing,
  as before).
- Duplicate `(vote_id, criteria)` rows: archived in `criteria_rate_orphan` (reason `duplicate`),
  the row with the highest rate kept (then the lowest id), and those votes' rate recomputed as the
  sum of the positive rates left (the rule they were written by; the old code counted duplicates
  twice). No other vote's rate changes (U12 stays open).
- Every statement but the final `ALTER` runs again unchanged after a failure part-way (`IF NOT
  EXISTS`, `ON DUPLICATE KEY UPDATE id = id`, `NOT EXISTS` guards; spec'd).

**Design choices.**
- **Names: a message key plus a fallback text** (the plan's lean). `Criterion.name`: the
  message of `message_key` in the viewer's language if defined, else `fallback_name`. Shipped
  keys have messages in en/fr/uk/ru (`MessagesCompletenessSpec` lists `Criterion.ShippedKeys`);
  a criterion an admin adds gets the key `criterion.custom.<id>`, which no messages file has until
  translatewiki (or a messages change) adds it, and shows its fallback text meanwhile. Adding a
  criterion needs no deploy.
- **Catalogue authorization: global; root and contest admins.** `GET/POST /admin/criteria`
  (`CriteriaController`) with `contestPermission(ADMIN_ROLES, contestId)`: root, or the admin role
  in the session's contest (or in `contestId` when given). Any contest's admin may add: the
  catalogue belongs to no contest (U3). Nobody edits or deletes an entry in the app: rounds of
  every contest and their rates refer to it, and one contest's admin would rename other contests'
  criteria. Bounds: name 1–255 characters, trimmed; refused if an entry has that name as shown in
  the viewer's language or as fallback text (any case); at most 500 entries
  (`Criterion.MaxEntries`, counted without a lock: adds at the same moment may pass it by one
  each; a lock would hold up every criterion vote's FK check). `created_by` records who added it;
  the add is logged with ids only.
- **Round form:** a "Criteria" section with ten selects of the catalogue (`criteria[0..9]`, the
  empty ones left out, by position); no JS (`round-form.js` unchanged, so no Jest run).
  Validation (`Criterion.validationError`): none, or 2–10, each once, all in the catalogue, in a
  rated round (`error.criteria.*`). For an existing round its stored rating decides (the form's
  is read-only). A hidden `criteriaShown` marks a form with the selects: a client without them (a
  script, an older page) leaves an existing round's criteria as they are.
- **Prefill (U3):** a new round's form takes the criteria (and the rating) of the contest's
  latest round, if that one has criteria; none otherwise. Written with the round in
  `RoundService.insert`'s transaction (`Round.create` → `RoundCriterion.insert`).
- **Edits (U10), `RoundCriterion.update`:** before the round's first vote (a vote rate ≠ 0 or any
  `criteria_rate` row, in `vote` or `vote_aside`), any change, also to none (`has_criteria = 0`).
  After it, only a criterion replaced in its place by one the round hasn't (the catalogue's
  "renaming"), whose rates in `vote` and `vote_aside` move to the new criterion; adding, removing,
  moving or reusing one is refused (`error.criteria.voted`, 400, nothing of the form saved).
- **Votes (U6):** `CriteriaRate.updateRate` upserts (`INSERT … ON DUPLICATE KEY UPDATE`) a
  criterion of the vote's round (`round_criterion`; another is false → 404); the distribution
  writes no rates (`addCriteriaRates`, its `MAX(id) FOR UPDATE` and `CriteriaIds` gone;
  `DistributeImages` left `RawSqlSpec`'s allowlist); a missing row reads as 0 (the large view
  draws every criterion's stars). Legacy rounds whose votes have no rows (L4) can be voted now.
- **U5 on the write side:** `vote.rate` = the sum of the criteria's rates once every criterion of
  the round is rated (> 0), else 0. A partly rated vote is so unrated wherever `rate` is read
  (Unrated filter, `ratedJurors`, "selected by", `activeJurors`, results, top-N, P2a's
  `rate <> 0` delta) with no read-side change, and C3 needs no `rated_criteria` count. A
  deviation from §3's "vote.rate stays the sum" for partial votes (U2 holds for rated ones).
  `removeUnrated` (no production caller) removes partly rated votes with their rates (C1's open
  question, answered by U5).
- **Lock order:** C1's `round → images → vote → vote_aside → criteria_rate`, `round_criterion`
  after the votes: a criterion vote locks its `vote` row, reads `round_criterion` (shared), writes
  `criteria_rate`; a criteria change locks `round`, the round's `vote` and `vote_aside` rows, then
  writes `round_criterion` and `criteria_rate`. A change never holds `round_criterion` while it
  waits for a vote row: no cycle. Spec: a change waits (`INNODB_TRX` `LOCK WAIT`) for a first vote
  in progress, then refuses. Change from C1: the vote's rates are summed by a plain read (the
  transaction's first; its vote row is held, and its rates are written only then), no longer
  `FOR UPDATE`: with inserts, that range read locked past the vote's last rate into the next
  vote's and deadlocked with its upsert (once in `CriteriaRateSpec`'s concurrent spec, retried;
  none in three reruns after the change).
- **Large view:** the round's criteria in order with the viewed juror's rates (0 when absent),
  names by `Criterion.name`; the all-jurors view still shows none (C3). `criteriaNames` and the
  hard-coded id → name map are gone. By-rate stars: `criteriaCount` from `RoundCriterion.countOf`
  (`CriteriaRate.countOf` gone; its default is now 1, set where read).
- Not done (C3/C4): per-criterion results, the all-jurors view, keyboard.

**Decided by the user (2026-11-03): keep the design as implemented, improve later.**
1. Q1/F6: contest admins (and root) add catalogue entries, as implemented. Editing and retiring
   entries is left for later; the recommendation on record: a root-only `retired` flag and a
   root-only correction of a fallback text (also the remedy if one admin fills the catalogue).
2. Q2: existing partial sums (partly rated criteria votes written under the old rule) are not
   zeroed by migration.
3. Q3: the U5 approach stands: `vote.rate` stays 0 until every criterion of the round is rated,
   a departure from §3's "vote.rate stays the sum" for partial votes (also recorded in the
   round-images plan, §4 "Counts").

**Pre-deploy (read-only, production, before the release with V83):** the C0 queries "after
V82" (above), plus:
```sql
SELECT COUNT(*) FROM criteria_rate WHERE criteria <= 0;                         -- must be 0 (else V83 stops)
SELECT DISTINCT criteria FROM criteria_rate WHERE criteria NOT BETWEEN 1 AND 9; -- legacy catalogue rows V83 adds
SELECT r.id FROM round r WHERE r.has_criteria = 1 AND NOT EXISTS
  (SELECT 1 FROM vote v JOIN criteria_rate cr ON cr.vote_id = v.id WHERE v.round_id = r.id);  -- get 6-9
SHOW CREATE TABLE criteria_rate;   -- UNIQUE KEY `id`, KEY `criteria_rate_vote_id_idx`, no PRIMARY KEY
SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('criterion', 'round_criterion');                          -- none
SELECT @@log_bin, @@binlog_format;  -- 0, or MIXED/ROW: criterion votes run at READ COMMITTED,
                                    -- which InnoDB refuses to write with STATEMENT binlogging (error 1665)
```
The C0 duplicates query's rows are what V83 archives (`criteria_rate_orphan.reason =
'duplicate'`), and their votes the ones whose rate it recomputes. Deploy with `wlxjury-release
deploy` (dump before Flyway; `restore` is the recovery up to `reopen`).

**Rollback SQL after reopen** (app stopped, previous release at V82; spec'd: V83 then this gives
the pre-V83 test-schema tables' `SHOW CREATE TABLE`). Criteria rounds created or changed under C2
lose their criteria; rates written by votes since have no pre-created siblings, so the old
release answers 404 to a criterion a vote has no row for; custom criteria (id > 9) have no name
there. Rounds created under C2 keep `has_criteria = 1`, so the old code pre-creates rates of
criteria 6–9 for their votes at their next distribution, whatever criteria they had. Production's `criteria_rate.id` had AUTO_INCREMENT (its MyISAM-era schema); the old code
always gives ids, so it is not restored. Archived duplicates stay archived.

```sql
ALTER TABLE criteria_rate
  DROP FOREIGN KEY criteria_rate_criterion_id_fk,
  DROP INDEX criteria_rate_criterion_id_idx,
  DROP INDEX criteria_rate_vote_criterion_uidx,
  ADD KEY criteria_rate_vote_id_idx (vote_id),
  CHANGE COLUMN criterion_id criteria INT NOT NULL,
  MODIFY vote_id INT NOT NULL,
  MODIFY id BIGINT UNSIGNED NOT NULL,
  DROP PRIMARY KEY,
  ADD UNIQUE KEY id (id);
DROP TABLE round_criterion;
DROP TABLE criterion;
ALTER TABLE criteria_rate_orphan DROP COLUMN reason;
DELETE FROM flyway_schema_history WHERE version = '83';
```

**Files.** `V83__Criteria_catalogue.sql`; new `org.intracer.wmua.Criterion`,
`db.scalikejdbc.CriterionJdbc` (with `RoundCriterion`), `controllers.CriteriaController`,
`views/criteriaCatalogue.scala.html`, routes `/admin/criteria`; changed `CriteriaRate`, `Round`
(`criterionIds`, `isRated`, `create`), `EditRound`, `RoundController`, `editRound.scala.html`,
`DistributeImages`, `RoundDistributionHandler` (doc), `LargeViewController`,
`large/large.scala.html`, `large/navigationButtons.scala.html`, `GalleryController`,
`conf/messages.{en,fr,uk,ru}`, `docs/security/secure-coding.md` (RawSql and ErrorText
exceptions).

**Specs.** New: `db.CriteriaCatalogueMigrationSpec` (backfill of ids and names, legacy id 12,
rounds' criteria with the 6–9 default and a round without `has_criteria`, duplicates archived and
votes recomputed, keys and FK, a rerun after stopping before the last statement, the stop with
nothing changed, the rollback SQL, the migrated DB), `db.scalikejdbc.CriterionJdbcSpec`
(AutoRollback: catalogue, add, duplicate names, limit, round criteria, edits before and after
votes, renaming moves rates in vote and vote_aside), `controllers.RoundCriteriaSpec` (create with
criteria, no rates written; refusals; prefill; edits before and after votes; `criteriaShown`; a
binary round; juror, organizer, another contest's admin and a juror who is another contest's
admin 403 with nothing written, root allowed; CSRF; the catalogue: list, add, duplicates,
limits, 403s, signed out, CSRF). Changed: `CriteriaRateSpec` (U5, upsert, custom ids, the lock
spec), `LargeImageSpec` (names in order and language, custom ids, missing → 0),
`LargeViewSideSpec`, `LargeViewRenders`, `MutationAuthorizationSpec`, `RoundDistributionJobSpec`
(no rates written), `RoundCriteriaStarsSpec`, `CoiRoundSpec`, `CriteriaRateOrphansMigrationSpec`,
`ConvertToInnoDbMigrationSpec` (V83 on the production-like schema), `MessagesCompletenessSpec`,
`RawSqlSpec` (`DistributeImages` removed), `ErrorTextSpec` (`CriterionJdbc`, 1).

**Checks** (Git Bash, `scripts/agent-check.py`, sbt launcher, one JVM at a time):
- Affected specs (the new ones, the changed ones above, `RoundEditPageSpec`,
  `CoiAuthorizationSpec`, `MigrationNamingSpec`, `security.*`): 304 run, 303 passed, 1 failed
  (`MessagesCompletenessSpec`: a literal `"error.criteria"` prefix read as a key; now
  `Criterion.ErrorKeys`), `target/checks/20261103-040405-16276.log`; after the fix and the
  sum-read change, CriteriaRateSpec, MessagesCompletenessSpec, RoundCriteriaSpec, LargeImageSpec,
  CriterionJdbcSpec: 94/94 (`20261103-040757-9500.log`); CriteriaRateSpec three times, 18/18
  each, no deadlock logged (`20261103-040938-28744.log`).
- Full suite (`sbt test`, Jest included): 1699 total, 1691 passed, 8 skipped, 1 pending, 0
  failed; Jest 251/251; exit 0, 8:12 (`target/checks/20261103-041119-19132.log`).

#### C2 review fixes

| # | Finding | Fix |
|---|---|---|
| F1 | A legacy criteria round (V83's backfill: one criterion, more than 10, or a rating that isn't rated) could no longer be saved: the form validated its stored criteria on every submit, posted only 10 of 11, and refused criteria of a yes/no round | Criteria posted as stored are not validated (`EditRound.validCriteria`, `RoundController`'s rating check) and stay as they are; the form shows one select per stored criterion (10, or more). Specs: one criterion, eleven, a yes/no round with `has_criteria = 1`, each saved with a new name and its criteria kept. Pre-deploy query below. |
| F2 | A plain vote (`VoteJdbc.rate`, gallery and large view) checked `hasCriteria` on a round read before a criteria change | Its `UPDATE` refuses a round with `round_criterion` rows (0 rows: 404). The change locks the round's votes first, so the waiting `UPDATE` then reads the committed criteria (its subquery is a locking read). Spec: criteria given while a plain vote waits: refused, `vote.rate` stays 0. |
| F3 | The sum's plain read was right only while nothing in the transaction read before the vote lock | `updateRate` runs at READ COMMITTED (`db.scalikejdbc.ReadCommitted`: the pool's connection given back at its level; neither ScalikeJDBC's `isolationLevel` nor the pool resets it). The other reads there are locking (`vote FOR UPDATE`, `round_criterion LOCK IN SHARE MODE`), so their meaning stays; at READ COMMITTED they lock no gaps. C1's lock specs pass. Spec: a read sees a rate committed after the transaction's first read, and the connections are back at REPEATABLE READ. Rule for P2a in the round-images plan, §5. |
| F4 | U5 made partial work look unrated where a delete tests `rate = 0` | `moveVotesBack` and `removeUnrated` count a vote with a criterion rated (`criteria_rate.rate > 0`, read with a shared lock after the votes, `VoteJdbc.votedByCriteria`) as rated: a partly rated new vote is kept over one aside, a partly rated one aside replaces an unrated new one, `removeUnrated` keeps them. Specs: the restore with partial votes both ways, `removeUnrated` keeping one. |
| F5 | V83's final `ALTER` could fail after the other steps on unexpected keys | Step 0 also stops at `vote_id <= 0`, a PRIMARY KEY on `criteria_rate`, or no `UNIQUE id` / `criteria_rate_vote_id_idx` (`information_schema.STATISTICS`): one `SIGNAL` with the counts. Specs: each of the four, nothing changed. |
| F7 | Nits | The prefill copies `halfStar` (a hidden field: the form has none); `round-form.js` hides and disables (so doesn't send) a new round's criteria for a yes/no or comments-only rating (Jest: `round-form.test.js`); `Round.create` returns `hasCriteria` as written; criterion ids are `Long` through the vote route and `RoundList` (no `toInt`); the rollback text below. Left as a limitation: a round edit saves its criteria in a transaction of its own before the name (`updateRound`) and the rest; a failure after it leaves the criteria saved. |
| Gaps | | Specs: a criterion replaced while a vote of it waits (the vote refused, the rates under the new criterion); a partly rated vote stays in the juror's Unrated list and out of `activeJurors` until every criterion is rated. |

Pre-deploy, added for F1 (the legacy rounds the form keeps as they are; to know them):
```sql
SELECT r.id, r.rates, COUNT(DISTINCT x.criteria) AS criteria
FROM round r
LEFT JOIN (SELECT v.round_id, cr.criteria FROM criteria_rate cr JOIN vote v ON v.id = cr.vote_id
           UNION SELECT d.round_id, cr.criteria FROM criteria_rate cr JOIN vote_aside d ON d.id = cr.vote_id) x
  ON x.round_id = r.id
WHERE r.has_criteria = 1
GROUP BY r.id, r.rates
HAVING COUNT(DISTINCT x.criteria) NOT BETWEEN 2 AND 10 OR r.rates <= 1;
```
(A round without rates gets 6–9 and shows here only for a rating that isn't rated.)

Review-fix checks: the criteria specs, `RoundCriteriaSpec`, `CriterionJdbcSpec`,
`CriteriaCatalogueMigrationSpec`, `ConvertToInnoDbMigrationSpec`, `LargeImageSpec`,
`MutationAuthorizationSpec`, `LargeViewControllerSpec`, `DeletedFilesSpec`,
`ImageDistributorSpec`, `RoundImageCountsDbSpec`, the round form's and views' specs and
`security.*`: 378 passed, 1 pending (ImageDistributorSpec's), then `CriteriaRateSpec` again
21/21, no deadlock logged, exit 0 (`target/checks/20261103-044633-18548.log`); Jest
`round-form.test.js` 10/10.

After merging `ukwikidev/followups` `ec3aef58` (Flyway 13.12.0, `db.Migrations`, no B44; merge
`b1045063`, no conflicts): `FlywayUpgradeSpec` (V83 on a fresh database from V1, and on the
Flyway 9.16.3 V70 and fresh-V82 fixtures), `MigrationNamingSpec`, `MigrationsSettingsSpec`,
`MigrationOrderSpec`, the migration and criteria specs and `security.*`: 222 passed, 1 skipped
(the fixture regeneration), exit 0 (`target/checks/20261103-045054-20444.log`). Full suite on
the merged branch: 1719 total, 1710 passed, 9 skipped, 1 pending, 0 failed; Jest 253/253; exit
0, 7:25 (`target/checks/20261103-045338-20528.log`).

### C3: results for criteria rounds (after C2)

- Overall per U2 (recommended: `vote.rate` stays the sum; shown as the average per criterion,
  `sum / n` in stars, with the sum in brackets). `numberOfStars` stops multiplying by criteria;
  `showRate`, by-rate, file list and large view draw the round's stars of `sum / n`.
- All-jurors large view: each criterion's average over the jurors who rated it, and the overall.
  Organiser's per-juror list: each juror's per-criterion rates.
- CSV: after "Overall", one average column per criterion; juror columns stay the sum (U8 for
  juror × criterion columns). File list: per-criterion averages in the all-jurors table.
- Read: `CriteriaRate.averages(roundId, pageIds)` (GROUP BY page, criterion over `criteria_rate
  JOIN vote`), one query per page or CSV chunk.
- From the C2 review: per-criterion averages use only votes with `vote.rate > 0` (rated, U5)
  and join `round_criterion` (the round's criteria): `VoteJdbc.setRound` (moving a file's votes
  to another round) can carry another round's rates. P2a's `round_image` delta stays inside
  `updateRateIn`, after the vote lock.
- Partial votes (U5, recommended "rated only when every criterion is rated"): the juror's
  Unrated filter and tabs, `ratedJurors`, "selected by", `activeJurors` read a vote as rated when
  its rated criteria = n. Today's SQL tests `rate > 0`; for criteria rounds the query adds
  `(SELECT COUNT(*) FROM criteria_rate WHERE vote_id = s.id AND rate > 0) = n`, or C2 stores a
  `vote.rated_criteria` count kept by `updateRate` (preferred: no subquery; and it gives P2a's
  `voted` delta).
- Next round: "min average rate" from a criteria round is read in per-criterion stars (U7):
  compare `sum / (n × starUnits)`; the form's hint says so.
- Files: `Round`, `ImageWithRating`, `ImageWithRatingSeqFilter`, `ImageDbNew` (Unrated, rated),
  `Csv`, `fileList`, `galleryByRate`, `showRate`, `juryStat`, `navigationButtons`,
  `LargeViewController`, `GalleryController`.
- Specs: `CsvSpec` (criteria columns), `RoundFileListSpec`, `GalleryByRateViewSpec` (star count),
  `LargeImageSpec` (all-jurors view shows averages), `ImageDbNewDbSpec` (partial vote stays
  Unrated), `RoundDistributionJobSpec`/`ImageDistributorSpec` (top-N and min-average from a
  criteria round), `OwnVotesSpec` (a juror still sees only own rates per criterion; another
  juror's per-criterion rates 404 as today's `viewAs`).

#### C3 results (branch `ukwikidev/c3-criteria-results`, from stack `7f88363c`)

Implemented with U2, U5, U7, U8, U9 as decided. No migration and no schema change: every read is
on demand from `vote` joined with `criteria_rate` and `round_criterion`. It keeps no counts, so
P2b's `round_image` source can sit under it unchanged (§3, round-images §8).

**Design choices.**
- **Display (U2; Q1 decided by the user 2026-11-03, the reviewer's recommendation).**
  `vote.rate` stays the sum. Stars show **one criterion's scale**: the round's own stars
  (`Round.numberOfStars` no longer multiplies by the criteria), filled to `sum / n`
  (`Round.perCriterion`). Before, 2 criteria of 10 stars drew 20 stars.
  - Where stars are drawn, the number matches them: the per-criterion value first, in stars,
    then the sum beside it (`ImageWithRating.criteriaRateString`). One vote's reads
    `3.50 (Σ 7)`. Several jurors' reads `4.83 (Σ 9.67: 29 / 3 jurors)`: their average sum,
    then the rate sum over the jurors it is averaged over (Q2, below). This applies to the
    by-rate gallery and the all-jurors large view (under "Overall, per criterion").
  - The file list and the CSV keep plain sums (`rateString`, `average (sum / jurors)`), labelled
    "Total rating (sum of the criteria)" in the file list. Sums are the scale of the rank, the
    top-N and the U7 threshold.
  - Each criterion's average sits next to these.
- **Averages:** `CriteriaRate.averages(roundId, pageIds, juror)`. It returns `AVG(cr.rate)` per
  file and criterion, from these rows only:
  - votes rated in every criterion (`vote.rate > 0`, U5);
  - of those, criteria rated (`cr.rate > 0`). A partly rated vote from before U5 keeps its sum
    (C2 Q2), and its pre-created rows of 0 are not averaged in;
  - criteria of the round only (`STRAIGHT_JOIN round_criterion`). A vote moved by `setRound`
    keeps the other round's rates.

  The read takes one query per 1,000 files, either for one page or for one CSV chunk. A juror's
  list reads only that juror's votes (`juror`). The large view's per-juror list uses
  `CriteriaRate.ratesOfFile(roundId, pageId)` (same joins, a partly rated vote's rated criteria
  included). Only organisers see it: `votes` is empty otherwise.
- **Denominators (Q2 decided: shown now, unified in P2b).** The overall rate keeps each view's
  existing rule (`ImageWithRating.ratedJurors`):
  - The file list and the CSV read each vote (`withVotes`) and average over the jurors with a
    rate (> 0). That is also the criteria averages' set (fully rated votes), so they agree
    there: `14.50 (29 / 2)` with 7 and 7.5.
  - In an `optionalRate` round, `ratedJurors` returns the round's active jurors (any rated
    vote, `VoteJdbc.activeJurors`) even for these per-vote rows. A file there can average over
    more jurors than rated it, while its criteria averages stay over its fully rated votes
    (spec'd: `9.67 (29 / 3)` with 7 and 7.5).
  - The by-rate gallery and the all-jurors large view read grouped rows (`count(s.rate)`) and
    average over the assigned jurors, with unrated and partly rated votes as 0, as in a plain
    rated round (also active jurors in an `optionalRate` round). With an unrated juror their
    rate per criterion is lower: the spec's file shows 4.83 against 7 and 7.5.

  The averages' own denominator is unchanged. The grouped views now show their denominator
  (`… 29 / 3 jurors`), so the difference is visible. The rule gets unified in P2b (round-images
  plan, P2b item), which replaces the grouped source anyway.

  **Done in P2b (V90; round-images plan §17).** One rule per round, chosen on the round form
  (`round.average_rule`, the user's decision of 2026-11-05) and used by every view and by a next
  round's top-N and minimum: `rated` (the default: over the fully rated votes, U5, the same set
  as the criteria averages, so the overall's per-criterion value is their mean: `7.25 (Σ 14.50:
  29 / 2 of 3 jurors)` beside 7.00 and 7.50), `assigned` (over the vote rows, a partly rated vote
  as 0: `4.83 (Σ 9.67: 29 / 3 jurors)`) or `sum` (the sum itself: `7.25 (Σ 29, sum of 3
  jurors)`, its stars the rated jurors' average). `optionalRate` is folded into `sum` (V90 makes
  those rounds `sum`; the code no longer reads it), so "active jurors" is gone. The criteria
  averages themselves stay over a criterion's fully rated votes.
- **Two scales in a half-star round.** The averages (file list, by-rate list, CSV), the sums
  and the juror columns are in stored half-star units (rates 1–10 for five stars), as every rate
  in those tables already is. The large view's numbers, and the per-criterion value of
  `criteriaRateString`, are in stars (units / 2), as its stars are. The U7 help's example says
  "without half stars".
- **Stars round to the nearest unit** in a criteria round (`Round.starsRate`): the nearest half
  star in a half-star round, else the nearest star. A file's average is rarely a whole number.
  Plain rated rounds keep their truncation, which predates C3 and is unchanged.
- **Where:** the by-rate gallery puts each file's averages under its stars (`criteriaAverages`).
  The file list adds one column per criterion after the rate. The CSV has one average column per
  criterion after "Overall", and the juror columns stay the sums (U8). The large view for all
  the jurors shows each criterion's average, read-only. In the organisers' list of votes, each
  juror's stars are per criterion and show that juror's rates (S8 fixed). The juror's own large
  view is unchanged. Not done: round statistics, and sorting by one criterion (U9).
- **Names:** `Criterion.name` (message key, else the fallback text) in the viewer's language. The
  CSV uses the request's language, and its header is otherwise English. Names are escaped by
  Twirl everywhere (no raw sink added) and pass through `Csv.writeRow` (formula-safe).
- **U7:** `SelectMinAvgRating` and the top-N already compare the per-juror sums (`totalRate`), so
  the code is unchanged. The round form's "Average file rating is at least" gets the help "From
  a round with criteria: a sum of the criteria rates, averaged over the jurors (e.g. up to 40
  for 4 criteria of 10 stars, without half stars)" when a rated
  round the form offers has criteria. It is static and server-side, with no JS. Partly rated
  votes count 0 there, as unrated ones do.
- **Authorization:** unchanged gates. `GalleryService.viewAs` decides the list's juror (0 only
  for organisers, admins, root and `juryOrgView`), and the averages read only that juror's
  votes. The large view's all-jurors averages and per-juror rates are read only when the
  viewer may see them.

**Files.** New: `org.intracer.wmua.CriteriaResults` (with `JurorCriteria`),
`views/criteriaAverages.scala.html`, `test/gatling/setup/CriteriaResultsBenchmark.scala`.
Changed: `CriteriaRate` (`averages`, `ratesOfFile`), `Round` (`numberOfStars`, `perCriterion`),
`Csv`, `GalleryController`, `LargeViewController`, `galleryByRate`, `fileList`, `showRate`,
`large/large`, `large/navigationButtons`, `large/juryStat`, `round/edit/imageFiltering`,
`conf/messages.{en,fr,uk,ru}` (`criteria.overall`, `criteria.sum`,
`average.file.rating.criteria.sum`).

**Benchmarks** (`gatling.setup.CriteriaResultsBenchmark`, a paired pilot; SQL debug logging
off; MariaDB 10.6.22 in the test container; 20 pairs after 3 warm-ups, interleaved, order
alternating; two runs: `target/gatling/criteria-results-bench-20261103-164510` and
`-165355` in the C3 worktree). The Gatling fixture has no criteria round, so the harness seeds
one. It has 5,000 files × 10 jurors × 4 criteria (90% of the votes fully rated, 5% partly, 5%
not: 185k `criteria_rate` rows, more than production's 166k in all rounds), beside a plain
round of 20,000 × 5 votes. A is what the page already reads, and B is C3's addition. Medians
from the second run (first run in brackets), with p10–p90:

| Path | A (before C3) | B (C3's addition) |
|---|---|---|
| File list page, 1,000 files, all jurors | 144 ms (150), 123–162 | +138 ms (140), 116–168 |
| By-rate page, 15 files | 79 ms (74) | +4.9 ms (4.7) |
| CSV, 5,000 files by 1,000 | 265 ms (272) | +671 ms (720) |
| Large view, all jurors, 1 file | 1.9 ms (2.2) | +2.2 ms (2.9): averages and each juror's rates |

Variants, paired against the query as built:
- `ORDER BY NULL` (no filesort): 124 against 129 ms, within the variation.
- One query by round for the CSV, instead of five chunks of 1,000: 620 against 687 ms.
- The 1,000 files' rate rows alone, without aggregation: 73 ms, against 7 ms for counting their
  votes.

So the cost is the `criteria_rate` lookups, a secondary-index probe and then the row for
`rate`. The grouping and the IN list barely matter. EXPLAIN:
- `vote` by `vote_round_page_rate_idx` (ref, `Using index`), or for one juror's 15 files by
  `vote_page_jury_round_uidx` (range);
- `criteria_rate` by `criteria_rate_vote_criterion_uidx` (ref on `v.id`, one row);
- `round_criterion` by its PRIMARY (`round_id`, 4 rows);
- a temporary table and filesort for the GROUP BY. The 1,000-id list becomes MariaDB's
  materialised IN subquery.

No per-row query. **Decision (Q3, the user 2026-11-03): no index now.** On a round larger than
any in production, an organiser's 1,000-file list page takes about 0.14 s more, and the whole
round's CSV about 0.7 s. Jurors' pages (15 files) take about 5 ms more. `round_image_criterion`
(§3) is not needed.

**Queued: a benchmark at real round sizes** (roadmap, "Batches").
- Take the real criteria rounds' sizes (files × jurors × criteria, and the share fully rated)
  from production's shape: C0's per-round query
  (`SELECT v.round_id, cr.criteria, COUNT(*), SUM(cr.rate > 0) …`) and the rounds' vote counts.
- Seed those sizes with `CriteriaResultsBenchmark` (`-Dbench.files/jurors/criteria`), or run it
  on a production-like copy.
- If one turns out slow, compare two migrations, paired on the same data:
  - a covering `criteria_rate (vote_id, criterion_id, rate)` index;
  - `(vote_id, criterion_id)` as the clustered primary key (instead of `id`), which needs every
    writer and the orphan archive checked for `id`.

**Specs.**

New:
- `org.intracer.wmua.CriteriaResultsSpec` (truncation; votes written by `updateRate`):
  - partly rated votes left out (U5);
  - a pre-U5 partial vote counted by its rated criteria only;
  - rates carried by `setRound` left out through `round_criterion`;
  - one juror's votes only;
  - chunks of 1,000 and none for no files;
  - none without criteria;
  - `ratesOfFile` with a partial vote;
  - the number format.
- `views.CriteriaResultsViewSpec`:
  - the by-rate gallery: 10 stars and not 20, 7 filled, averages and dashes;
  - the file list: columns, the "sum of the criteria" label, none without criteria;
  - the all-jurors large view: the rate per criterion, the averages, read-only;
  - the per-juror list: stars per criterion, rates, a partial vote;
  - each with a `<script>`/`onerror` fallback name escaped.
- `controllers.CriteriaResultsPageSpec` (through the router):
  - the file list, the by-rate gallery, the CSV and the large view for an organiser or admin;
  - names in en/uk/ru/fr, and an admin's by its fallback text in each;
  - the CSV header formula-safe (`'=HYPERLINK…`);
  - a juror's file list and CSV averaging only their own votes, and their large view without
    others' or averages;
  - 403 for a juror asking for all jurors or another juror, and for another contest's juror,
    on the file list, by-rate, CSV and large view;
  - signed out to the login page;
  - the round form's U7 help shown only when a criteria round is offered.

Changed:
- `CsvSpec`: criteria columns, none without criteria, formula-safe names.
- `RoundCriteriaStarsSpec`: `numberOfStars`, `perCriterion`.
- `LargeImageSpec`: the by-rate stars, 10 with 3 filled.
- `RoundDistributionJobSpec`: from a criteria round, "min average rate" 11 read as a sum picks
  files 1 and 2 (no file reaches 11 per criterion), and 6 adds the file whose partly rated vote
  counts 0; top-2 and top-3 by the sums.
- `LargeViewRenders`: the criteria's type.

**Checks** (Git Bash, `scripts/agent-check.py`, the sbt launcher, one JVM at a time under the
shared lock, Commons variables unset):
- Affected specs (the above, `CriteriaRateSpec`, `MessagesCompletenessSpec`, `OwnVotesSpec`,
  `GalleryByRateViewSpec`, `FileListViewSpec`, the large view specs, `GalleryTileStarsViewSpec`,
  `RoundStatTableViewSpec`, `RoundCriteriaSpec`, `AllJurorsListSpec`, `GroupedListParitySpec`,
  `MutationAuthorizationSpec`, the guards `db.MigrationNamingSpec`, `db.MigrationSchemaSpec`,
  `db.VoteRowLockSpec`, and `security.*`): 379 passed, 0 failed
  (`target/checks/20261103-163545-15284.log`). An earlier run (52 examples) failed on three
  expectations of the spec itself: the file list's rate over its rated jurors (see
  "Denominators") and a selector for the votes' list.
- Full suite (`sbt test`, Jest included): 1934 total, 1924 passed, 10 skipped, 1 pending, 0
  failed; Jest 253/253; exit 0, 15:47 (`target/checks/20261103-165606-24316.log`).

**Decided by the user (2026-11-03), after the review (approve): the reviewer's recommendations.**
1. Q1: where stars are drawn, the per-criterion value with the sum beside it; the file list and
   the CSV keep sums labelled "sum of the criteria" (see "Display").
2. Q2: the grouped views show their denominator now; the rule is unified in P2b (see
   "Denominators").
3. Q3: no index now; a benchmark at real round sizes is queued (see "Benchmarks").

#### C3 review fixes

| # | Finding | Fix |
|---|---|---|
| M1 / Q1 | The by-rate gallery's and the all-jurors large view's number (a sum) didn't match their stars (per criterion) | `ImageWithRating.criteriaRateString`: `3.50 (Σ 7)`, `4.83 (Σ 9.67: 29 / 3 jurors)` (messages `criteria.rate.one`/`.many` in en/fr/uk/ru); the file list and the CSV unchanged |
| Q2 | Two denominators | Shown in the grouped views; recorded for P2b (round-images plan, P2b item) |
| Q3 | Index | None now; benchmark at real sizes queued |
| Minor 1 | "Denominators" missed `optionalRate` | Said so; spec'd |
| Minor 2 | Half-star rounds have two scales; the U7 help's example is wrong for them | The scales noted ("Two scales in a half-star round"); the help says "without half stars" |
| Minor 3 | Stars truncated an average | `Round.starsRate`: the nearest unit (half star) in a criteria round; plain rounds unchanged (pre-existing) |
| Gaps | | Specs: a rendered half-star criteria round (by-rate stars and number, the large view's `rate / k`, `showRate`'s half star); a one-criterion round; the denominator display in the by-rate gallery and the large view with a partly rated assigned juror; a `juryOrgView` juror seeing all-jurors averages and each juror's criteria; an `optionalRate` round's file-list denominator |

Review-fix checks: `Test/compile`, then `testOnly` of `RoundCriteriaStarsSpec`,
`CriteriaResultsSpec`, `CriteriaResultsViewSpec`, `CsvSpec`, `CriteriaResultsPageSpec`,
`MessagesCompletenessSpec`, `LargeImageSpec`, `OwnVotesSpec`, `GalleryByRateViewSpec`,
`FileListViewSpec`, the large view specs, `GalleryTileStarsViewSpec`, `RoundCriteriaSpec`,
`AllJurorsListSpec`, `RoundDistributionJobSpec` and `security.*`: 280 passed, 0 failed
(`target/checks/20261103-195033-27840.log`). No full suite rerun (a view and message change
on top of the green full suite; CI runs it).


### C4: juror UX (after C2)

- Large view keyboard (U4, recommended): Left/Right (or Tab) select a criterion, digits rate the
  selected one, the selection moves to the next unrated criterion after a vote; after the last
  one the view goes to the next file (as a plain rated round does). Paths only (CLAUDE.md
  keyboard rule).
- Gallery tiles of a criteria round: "3 / 4" rated badge instead of stars; no tile voting.
- Files: `keyboardNav.scala.html`, `navigationButtons.scala.html`, `large-keys.js`,
  `large-stars.js`, `LargeViewController` (next file after the last criterion), gallery tile
  template.
- Specs: `javascript-test/large-keys-stars.test.js`, `LargeViewControllerSpec` (keys are paths),
  `LargeViewUrlsSpec`, `LargeImageSpec` (redirect target after the last criterion).

### Decisions for the user

**Decided by the user (2026-11-02):**
- **U1:** criteria rounds stay and become creatable.
- **U2:** the overall rate is the sum of the criteria, stored in `vote.rate`; no weights. (How it is
  shown, sum or per-criterion average, follows from U2 in C3 and is still a design choice.)
- **U3:** global criteria templates: admins can add criteria and choose them for a round; a
  round's criteria are copied to the next round. Table name `criterion` (singular). C2 changes
  from "criteria per round" to a global `criterion` catalogue plus a per-round link
  (`round_criterion`, with its position), copied on round creation from the previous round.
- **U7:** the "min average rate" threshold from a criteria previous round is read in sums, not in
  per-criterion stars. C3's "read in per-criterion stars" item is dropped; the form must say the
  value is a sum.
- **Names:** message keys, not free text. Later, translatewiki integration will let translations
  be edited. Implication for C2: an admin-added criterion needs a key that exists in
  `conf/messages.*`; until translatewiki, either adding a criterion needs a messages change and a
  deploy, or the `criterion` row keeps a fallback text shown when the key has no translation
  (`MessagesCompletenessSpec` must cover the shipped keys). Choose in C2's design.

- **U5, U6, U8–U12:** as recommended: a criteria vote counts as rated only when all criteria are
  rated; rates are written on vote (upsert); the CSV has per-criterion averages only; no sorting by
  one criterion for now; after the first vote only names can change; drop `vote.criteria_id`
  and `criteria.contest` after C0; repair votes whose rate differs from their criteria sum by
  migration if C0 finds any.

- **U4:** the C4 keyboard model, accepted.

The options as first written:

- **U1** Keep criteria rounds and make them creatable (this plan assumes yes), or freeze them as
  legacy read-only.
- **U2** Overall rate: sum of criteria stored in `vote.rate` and shown as the per-criterion
  average [rec], a stored average, or a weighted sum (weights per criterion) [rec: no weights].
- **U3** Criteria per round [rec], with "copy from previous round", or per contest templates; table
  name `criterion` (singular, D11) [rec] vs keeping `criteria`.
- **U4** Keyboard model for criteria in the large view (above [rec]).
- **U5** When a criteria vote counts as rated: any criterion (today) or all criteria [rec].
- **U6** Criteria rates written on vote (upsert) [rec] or pre-created at distribution (today).
- **U7** Min-average threshold from a criteria previous round in per-criterion stars [rec] or in
  sums.
- **U8** CSV: per-criterion averages only [rec], or also juror × criterion columns.
- **U9** Sorting/ranking by one criterion (needs the child table of §3) [rec: not now].
- **U10** Edits after the first vote: names only [rec], or also add/remove (rates of a removed
  criterion are dropped and sums recomputed).
- **U11** Drop `vote.criteria_id` (`selection.criteria_id` before P0) and the old `criteria.contest` column [rec, after C0].
- **U12** Repair votes whose `rate` differs from their criteria sum (S4 damage) by migration
  [rec if C0 finds any], or leave history as is. Live and aside votes both; after V83 by the
  "after V83" damage query and to U5's value (C0, R223-1).
- **Names' language:** free text per round in the contest's language [rec]; no message keys.
