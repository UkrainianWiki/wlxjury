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
| `criteria_rate (id, selection, criteria, rate)` | V10 | `id` BIGINT UNSIGNED NOT NULL, UNIQUE but not AUTO_INCREMENT and no PK; index on `selection`; no FK to `selection`, no UNIQUE `(selection, criteria)`. ~166k rows in the January 2026 dump; ~25k (estimate) in the copy trimmed to contests from 2024 on (`2026-10-07-prod-like-benchmark.md`), so criteria rounds ran in 2024 or later. |
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

## 3. Interaction with the round-images plan (recommendations to merge into `2026-11-02-round-images-two-step.md`)

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
  `rounds → images → vote → criteria_rate → vote_aside → round_image`, with the rule that
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

```sql
SELECT r.id, r.contest_id, r.name, r.rates, r.half_star, r.active, c.name, c.year
  FROM rounds r JOIN contest_jury c ON c.id = r.contest_id WHERE r.has_criteria = 1;
SELECT s.round_id, cr.criteria, COUNT(*), SUM(cr.rate > 0)
  FROM criteria_rate cr JOIN selection s ON s.id = cr.selection GROUP BY s.round_id, cr.criteria;
SELECT * FROM criteria;
SELECT COUNT(*) FROM criteria_rate cr WHERE NOT EXISTS (SELECT 1 FROM selection s WHERE s.id = cr.selection)
  AND NOT EXISTS (SELECT 1 FROM selection_deleted d WHERE d.id = cr.selection);   -- orphans
SELECT selection, criteria, COUNT(*) FROM criteria_rate GROUP BY 1, 2 HAVING COUNT(*) > 1 LIMIT 20;
SELECT COUNT(*) FROM selection WHERE criteria_id IS NOT NULL;
-- votes whose rate differs from their criteria sum (S4 damage)
SELECT s.round_id, COUNT(*) FROM selection s JOIN (SELECT selection, SUM(IF(rate > 0, rate, 0)) t
  FROM criteria_rate GROUP BY selection) x ON x.selection = s.id WHERE s.rate <> x.t GROUP BY s.round_id;
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
  the votes C0 lists (U12).
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
  shows it unused (U11). `rounds.has_criteria` stays, kept equal to "has criterion rows".
- Model: `Round.criteria: Seq[Criterion]` loaded by `CriterionJdbc.ofRound(s)`; remove
  `CriteriaIds`, `criteriaCount`'s default, `criteriaNames`, `CriteriaRate.countOf` and the name
  map; `Round.create` writes `has_criteria`.
- Votes: `updateRate` upserts (`INSERT … ON DUPLICATE KEY UPDATE`) if U6 = on vote, and the
  distribution stops writing rows (`addCriteriaRates` and its `MAX(id) FOR UPDATE` go); missing
  rows read as 0. Otherwise keep pre-creation with the round's ids.
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
  one criterion for now; after the first vote only names can change; drop `selection.criteria_id`
  and `criteria.contest` after C0; repair votes whose rate differs from their criteria sum by
  migration if C0 finds any.

Still open: U4 (the C4 keyboard model, explained to the user).

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
- **U11** Drop `selection.criteria_id` and the old `criteria.contest` column [rec, after C0].
- **U12** Repair votes whose `rate` differs from their criteria sum (S4 damage) by migration
  [rec if C0 finds any], or leave history as is.
- **Names' language:** free text per round in the contest's language [rec]; no message keys.
