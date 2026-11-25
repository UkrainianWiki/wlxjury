<!-- Recovered 2026-11-25 after its file was lost: plan v2 as last edited on 2026-11-11.
     Changes made on recovery are marked "(updated 2026-11-25)". -->

# Regional winners report (plan v3: show, don't pick)

**Status:** plan only. Nothing is built. Date: 2026-11-11.

**Status (updated 2026-11-25):** recovered 2026-11-25 after its file was lost; all decisions made (4
and 9 replaced by "show, don't pick"); ready to build after lineage slice A. Order of work: lineage
slice A → regional winners phase 1 → phase 2 (exports) → phase 3. Phase 0 is done for WLE 2026 and
WLM 2026 (§7.4, §7.5). The maintainer made this the top priority for WLM 2026 Ukraine, whose jury
final is at the beginning of December.

**Revisions:**
- **v1:** the first draft. It was recovered with this plan and is not committed (updated 2026-11-25).
- **v2:** the independent review (points 1–9), all applied:
  - side branches no longer take the default final round; they are add-ons, and a tie at the
    deepest level is warned about (§2.1, §2.3);
  - rounds not linked to the chain are found for regions without a source (§2.4);
  - the votes are the normal read path while `roundImage.read` is off (§4.3);
  - summaries are read lazily (§4.4);
  - phase 0 runs locally on the 26.11 rehearsal copy, with its SQL written out (§7.1);
  - the decisions are split, settled or added (§8);
  - citations are fixed.
- **v2, after the follow-up review** (approved, 2026-11-11), two fixes:
  - an add-on anchored on `F` ranks below `F` until decision 14 is answered (§2.3, decision 3);
  - a rated round's grade sum of 0 is worded "only ✗ marks" (§2.4).
- **v3, "show, don't pick"** (the maintainer, 2026-11-25): the page no longer picks a source round
  or fills a region from earlier rounds. It shows one column per round of the chain, latest first,
  each cell with the region's best files by that round's own ranking, and the organizers decide.
  The short answer, §2.3–§2.5, §3.2–§3.3, §4, §5 and §7.2 are rewritten; decisions 4 and 9 are
  replaced, and decision 5 is narrowed.

**Code read:** `911255f7`, a mirror of `stack/105-round-lineage-plan` (PR #288). Inferences are
marked *(inf.)*. No DB or server was queried for the plan itself. (updated 2026-11-25) File:line
citations are refreshed to `origin/master` at `d037bf4e`; where code moved by more than a line
shift, the symbol is named instead.

**The request (2026-11-11, verbatim):** "This year WLE missed regional winner for some regions because
they were missing in the latest 3rd round, but organizers did not look back into 2nd round (which was
done each previous year). Please plan a regional winners report page that will present regional best
photos and tell which round they are from."

**Terms used throughout:**
- **The final round `F`:** the round the report ends at (§2.1).
- **The chain `A`:** `F` and its ancestors through `previous`, nothing else. It is not a lineage
  *track* (lineage §2.3). It is a set of rounds computed from one round.
- **Regional rounds `G`:** rounds with a real-subset region filter that branch from the chain.
- **Add-ons `B`:** other rounds that branch from the chain:
  - a leave-out round ("files of A not in round Y");
  - a review round of rejected files (mode `only`).
- **`X`:** the rounds left out of everything (§2.1).
- **Columns** (updated 2026-11-25: "show, don't pick"): the rounds of `A ∪ G ∪ B`, one column each,
  in the order of §2.3.

---

## Short answer (rewritten 2026-11-25: "show, don't pick")

- **One read-only page per contest:** `/contest/:id/regional-winners`, for the contest's Admins and
  Organizers (and root). One row per geographic region of the contest's country (decision 10).
- **One column per round of the chain, latest first:**
  - the final round `F` (decision 2): the deepest round of the "Main" track, or, until the contest
    has track data, the deepest plain round, with a note that it was guessed;
  - then the rounds before it in the chain (decision 1), with the side add-ons (decision 3) and
    regional rounds as extra columns, placed by §2.3;
  - shown by default: the final round and its main line, latest first (decision 17): stepping
    back from the final round, the previous round on the "Main" track, else the one that supplied
    the most files (a guess, in the same note as the final round's). Side feeders, add-ons and
    regional rounds are behind "Show all rounds".
- **Each cell** shows the region's top N files in that round by that round's **own** ranking, all
  ties shown (decisions 7, 8), and how many files the region had there.
- **No algorithm picks a winner.** Scores are never compared across rounds. The organizers see
  where each region's best photos are and decide.
- **What still applies:**
  - files with a conflict of interest and files deleted on Commons are excluded before ranking
    (decisions 12, 13); each cell can note how many were excluded;
  - the final round's national top k are marked "national winner" (decision 14);
  - cells of open rounds are marked "provisional" (decision 6);
  - N is 1–10, default 1 (decision 7).
- **No region can go missing quietly:** the regions with no files sit in a collapsed group; a region
  with no file in any column names the unlinked rounds that hold its files; the header warns when
  the chain starts much smaller than the contest.
- **Export** (decision 11): wiki text and CSV of what is shown. By default the final round's column
  per region; an option exports all shown columns. Each file carries its round.
- **Reads:**
  - **Normal path:** the votes are read, as the all-jurors lists do while `roundImage.read` is off
    (the default, `conf/application.conf:433-443`): per shown column, one region summary and one
    ranked top-N query.
  - **Exact consumers** (the wiki and CSV exports) always read the votes.
  - **`round_image` counts** are used only when `roundImage.read` is on and the clean-generation
    proof holds.
  - There is no new table and no migration.
- **Builds on round lineage's track core** (decision 2). It needs the graph core (ancestors and
  depth) that the lineage plan (§1.3) assigns to a shared `services.RoundLineage`, plus the track
  computation for "Main" and the manual label (`round.track`, lineage decision 4). That is the core
  shared with lineage's minimum slice (A): tracks with the manual label, the chip and the Track
  menu, about 3–4 days. Order of work (decision 15): lineage's slice A first, then this plan's phase
  1 on top of it. The track label's migration is lineage's (number: next free at merge).
- **No migration of its own.** Production is at V113, so it already has `round_image` (V84), the
  counts (V89), `average_rule` (V90), `exclude_rounds` (V95) and `round.status` (V105). V84's
  backfill rebuilt `round_image` from the votes, so the report also works for WLE 2026 after the
  fact.
- **Size:** about 3.5–4.5 agent days, after lineage's slice A (3–4 days). Order of work (decision
  15): lineage slice A → regional winners phase 1 → phase 2 (exports) → phase 3, the first two
  before the WLM final in early December.
  - **Phase 0** (S): done for WLE 2026 and WLM 2026 (§7.4, §7.5).
  - **Phase 1** (M, 2.5–3 days) is the page, on lineage's slice A.
  - **Phase 2** (S–M) is the export, as wiki text and as CSV (decision 11).
  - **Phase 3** (S) comes after lineage phase 1 and multi-role's pre-screening rounds (migration
    numbers: next free at merge).

---

## 1. What exists today

### 1.1 What a "region" is

- **A file's region** is the start of its monument id: up to 3 characters before the first dash,
  NULL without an id.
  - In SQL: `ImageDbNew.regionOf` (`app/db/scalikejdbc/rewrite/ImageDbNew.scala:17-22`).
  - In Scala: `Region.of` (`app/org/intracer/wmua/Region.scala:23-27`).
  - In tables: `monument.adm0` stores the same value (`MonumentJdbc.scala:101`), and so does
    `round_image.region`, a STORED generated column (`V84__Round_image.sql:44-45`).
- **`Image.region` is not the same.** It is the whole part before the first dash, without the cut to
  3 characters (`app/org/intracer/wmua/Image.scala:22`). The report uses `Region.of` and SQL's
  `regionOf`, never `Image.region`.
- **One region per file.**
  - A file has a single `images.monument_id`, and an import keeps or replaces it
    (`ImageService.withStoredText`, `app/services/ImageService.scala:143-148`). So no file is in two
    regions.
  - `round_image.monument_id` is kept in step with the image (`V84:16-18`), so a corrected id moves
    the file to its new region in every round *(inf. from the V84 comment)*.
- **Names and kinds** come from `RegionScheme` (`app/services/regions/RegionScheme.scala`):
  - **Geographic:** the country's region, by code.
    - The comment at `RegionScheme.scala:11-13` says WLM and WLE alike use the country's regions,
      KOATUU's for Ukraine.
    - Ukrainian names come from the messages (`07=Volyn O.`, `conf/messages.en:102`).
    - The 27 KOATUU codes are in `conf/KOATUU.properties`.
  - **Nomination:** a prefix that one of the campaign's nomination lists uses
    (`RegionScheme.scala:43-55`, `byPrefix`). WLM Ukraine has these (`wlm_ua.conf`, "nominations").
  - **Other:** any other prefix (`region.other`).
  - **No country:** the id itself is the name.
  - **Order** (`RegionScheme.regions`, `:61-78`): geographic regions by id, then nominations, then
    others.
- **The contest's scheme** is `RegionScheme.of(contest)` (`:88-99`), found from the campaign or from
  the name and country (`ContestKind`, `app/services/regions/ContestKind.scala`).
- **No regions at all:** galleries show no region menu when the contest has no monument-id template
  (`ContestKind.monumentIdTemplate(contest).isEmpty`, `GalleryController.scala:421`).
- **Contests of many countries** (`ContestCountries.ofManyCountries`,
  `app/services/imports/ContestCountries.scala:23`) have a country menu built from category links.
  They have no regions (`GalleryController`'s `countryMenu`, which now asks
  `services.places.PlaceScheme.byCountries`; line numbers dropped (updated 2026-11-25)).

### 1.2 WLE and WLM

| | WLM Ukraine | WLE Ukraine |
|---|---|---|
| Config (scalawiki-wlx, `page-categories` checkout) | `wlm_ua.conf`, with `nominations` | `wle_ua.conf`: `fileTemplate: UkrainianNaturalHeritageSite`, country Ukraine, **no** `nominations` (`ContestKind.nominations` gives none, `ContestKind.scala:63-70`) |
| Region ids | KOATUU 2-digit prefixes (e.g. `80-385-0043` in `data/wlm-UA-images-2025.csv`) | KOATUU prefixes (`RegionScheme.scala:11-13`); phase 0 found 27 two-digit KOATUU prefixes (§7.4) (updated 2026-11-25) |
| Nomination prefixes | yes (e.g. `99-141-…`), kind `Nomination` | none from config; any odd prefix becomes "Other (xx)" |
| Special-nomination rounds | `special_nomination` is offered only for WLM Ukraine (`RoundController.contestSpecialNominations`, `RoundController.scala:285-296`) | none |
| Rounds of 2025/2026 | 61 rounds, many tracks; the regional steps were copied by hand (lineage §10.3–10.4). 2026: 12 rounds, one linked chain into a merge, no regional rounds (§7.5) (updated 2026-11-25) | 2026: 11 rounds, `previous` recorded, no regional rounds (§7.4) (updated 2026-11-25) |

**Consequence.** In WLM, a file listed under a nomination prefix (`99-…`) never counts for its
geographic region, because its region is the prefix (`monument.adm0` is the prefix too). The report
doesn't show nomination prefixes, "Other" prefixes or files without an id: it covers geographic
regions only (updated 2026-11-25, decision 10).

### 1.3 Results per round type

| Round | Score of a file | Rank key (all jurors) | Shown as |
|---|---|---|---|
| Yes/no (`isBinary`, `app/db/scalikejdbc/Round.scala:119`) | approvals and rejections | a `BinaryOrder` (`app/org/intracer/wmua/BinaryOrder.scala`); default: approvals descending, then rejections ascending (`:33-37, 53`); ties in page-id order | `rateString`: the approvals (`ImageWithRating.scala:146-153`) |
| Rated | grade sum (a ✗, rate -1, counts as 0) and the average under `average_rule` (V90): `rated` (the default), `assigned` or `sum` (`AverageRule.scala:5-24, 50-63`) | average key, then sum; `sum` rounds rank by the sum alone (`ImageWithRating.scala:262-277`) | `rateText(round)`: e.g. "14.50 (29 / 2 of 3 jurors)" (`:161-164`) |
| Criteria (`hasCriteria`) | vote rate = sum of the criteria once all are rated (`V89__Round_image_counts.sql:10-12`) | as rated | `criteriaRateString` (`:210-217`) |
| Comments only (`commentsOnlyRound`, `Round.scala:317`) | none | — | not a column (updated 2026-11-25: "show, don't pick") |

**The order of a yes/no round lives on the next round.** `round.top_binary_order` (V72) is stored on
the round that took a top-N *from* a yes/no round (`Round.scala:65-68`; `BinaryOrder.scala:10`).
`NULL` means the legacy rule (`BinaryOrder.LegacyParam`, `:61-67`). §2.5 settles how the report uses
it.

**Rules that hold for every view, and so for this report:**
- **Stopped jurors' votes count** under every rule (`AverageRule.scala:21-23`). Neither read path
  joins `round_user` (`RoundImageQuery.scala:30-31`).
- **Disqualified files, and files deleted on Commons while their round was open, are out.** Their
  votes are in `vote_aside`, and their `round_image.status` is `dq` or `deleted` (`V84:6-9`).
  - The votes path never sees them, since their rows left `vote`.
  - The counts path reads only `status = 'in'` (`RoundImageQuery.scala:95`).
  - A file deleted on Commons *after* its round stopped keeps its votes and `in` status (`V84:6-8`)
    and has `images.deleted_at` (V63). Decision 13 settles how the report shows it.
- **Ties share a rank range** ("4-9"): `ImageWithRating.Ranks` (`:283-300`).
- **The score rule can change later.** Saving another rule re-scores the round on every page at once
  (`ScoreRuleWarning`, `RoundController.scala:880-912`). The report shows the current rule's result
  and names the rule.

### 1.4 Aggregates to reuse, and which are read today

- **`roundImage.read` is off by default** (`conf/application.conf:433-443`; read at
  `GalleryController.scala:52`; `RoundList.countsQuery`, `RoundList.scala:406-410`).
  - All-jurors lists read the votes until the P2b pilot turns it on.
  - "The CSV and a next round's top-N read the votes regardless" (`application.conf:442`;
    `RoundImageQuery.scala:28-29`).
  - **So phase 1's normal path is the votes.**
- **The votes path for a whole round:** `SelectionQuery.regionCounts` (all jurors) groups a round's
  votes by page with `vote`'s `(round_id, page_id, rate)` index (V56). It then joins `images` for the
  region through `images_page_monument_idx` (`ImageDbNew.scala:467-487`). The report's summary uses
  the same shape (§4.1).
- **`round_image`** has one row per (round, file), with `region` (`V84:33-50`) and the counts
  `assigned`, `voted`, `approvals`, `rejections` and `rate_sum` (V89:36-41).
  - A background recompute keeps the counts (`db.scalikejdbc.RoundCounts`).
  - Files that changed since are listed in `round_image_dirty` and read from their votes in the same
    statement (`RoundImageQuery.scala:63-77`).
- **`RoundImageQuery`** already does the following over the counts:
  - the all-jurors list, with a rank-key vocabulary (`translate`, `:203-213`) and `CountTerms`
    (`:197`);
  - `regionCounts()` (`:168`);
  - a `ROW_NUMBER()` window (`:160`).

  It is in the raw-SQL guard's allowlist (`test/security/RawSqlSpec.scala:18-19`).
- **Exactness proof.** `RoundList.fromCounts` (`RoundList.scala:415-441`) checks two things:
  - the round's generation was clean before the first statement;
  - the generation is unchanged after each statement.

  If either check fails, it reads the votes.
- **Cached file totals per round:** `RoundImageCounts.get(ids)(ImageJdbc.imageCountByRounds)`
  (`RoundImageCounts.scala:33`, used at `RoundController.scala:59`). They are totals, not per region.
- **Cached region counts of the contest's files** per category (`RegionCountCache.category`,
  `ImageJdbc.categoryRegionCounts`; `GalleryController.scala:55-57`, `ImageJdbc.scala:686`).

### 1.5 Lineage today

- **`round.previous`** is a CSV of ids: `Round.previousIds` (`Round.scala:217`). Each previous round
  is evaluated on its own (lineage §1.1).
- **`round.regions`** is a CSV region filter: `regionIds` (`Round.scala:180`). Lineage rule 4 treats
  "all real regions" as no filter (lineage §1.2, §2.3).
- **`round.exclude_rounds`** (V95, `Round.scala:62-64`) lists the rounds whose files a round leaves
  out. Lineage §4 records three steps with it (lineage plan lines 709–714):
  - a regional backtrack: previous + region filter + leave-out;
  - a merge;
  - "files of A not in round Y": previous = A, leave out = Y.
- **`prev_rejected_mode = 'only'`** (V93:1-5) keeps only the files rejected in the previous rounds,
  "to review the rejected files in a round of their own".
- **2025's regional steps** were copied by hand in the DB and left no edges (lineage §10.4).
- **`round.status`** (V105, in production) (updated 2026-11-25) is one of `draft`, `paused`, `active`,
  `finalized` and `cancelled` (`V105__Round_status.sql`; `Round.lifecycle` and `Round.inReview`,
  `app/db/scalikejdbc/Round.scala:90-97`):
  - `draft`: being created (`distributing = 1`);
  - `paused`: either in review (filled, not yet distributed, `distributing = 1`; the two-step
    plan's P3) or stopped or finished (`distributing = 0`);
  - `active`: open to jurors;
  - `cancelled`: written only for a creation an admin cancelled. A distributed trial round still
    can't be cancelled until lineage's Cancel lands;
  - `finalized`: not written yet.

  `deleted_at` still exists, and `ScoreRuleWarning` skips rounds that have it
  (`RoundController.scala:907`).
- **Top-N is contest-wide.** A next round's top-N or minimum score has no per-region option
  (`RoundDistributionHandler.scala:340, 415`). That is how a region drops out of round 3. Phase 0
  confirmed it for WLE 2026: round 3 took a contest-wide minimum average of 10.88 (§7.4)
  (updated 2026-11-25).

### 1.6 Access today

- **Organizer pages.** The round stats use `rolePermission(admin, root, organizer)` plus
  `user.canViewOrgInfo(round)` in the round's contest (`RoundController.scala:711-719`).
  `User.canViewOrgInfo` allows root and the contest's admins and organizers, never a juror
  (`app/db/scalikejdbc/User.scala:178-180`).
- **Contest-wide organizer pages** use
  `contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))` (on `master`
  with `derived = true`, so a series grant's derived role counts (updated 2026-11-25)):
  - the contest files page (`GalleryController.scala:587`);
  - the lineage map plan (§2.1).
- **Jurors see only their own votes** (`GalleryService.viewAs`, `app/services/GalleryService.scala:32-36`).
  The large view with `user/0` needs `canViewOrgInfo`. So a link to
  `/large/round/:r/user/0/region/:region/pageid/:p` (`conf/routes:97`) exposes nothing new.
- **Pre-screeners** (multi-role decision 3) see statistics and results only through Admin or
  Organizer.

### 1.7 Existing exports

- **The file list** (`conf/routes:79-82`) has two formats:
  - `wiki` (default): Commons links in a `contenteditable` block (`fileList.scala.html:67-76, 125-127`);
  - `html`: thumbnails.
- **CSV** goes only through `controllers.Csv` and `GalleryController.csvStream`
  (`GalleryController.scala:258-262, 501-504`). `test/security/ExportsSpec.scala` enforces this.
- **Nothing builds a per-region winners list** or Commons `<gallery>` text today.

---

## 2. What the page shows

### 2.1 Rounds that take part

Let `Rs` be `Round.findByContest(contestId)` (`Round.scala:619-622`): at most about 100 rows, with
the graph built in memory (lineage §1.3).

**Depth:** `depth(R) = 0` for a root, else `1 + max depth(previous)`.
- It is computed over the whole contest graph in Kahn order. This is lineage §2.3's position without
  the sub-row term.
- A cycle (impossible today, lineage §1.1) is reported, and the page doesn't rank.
- Ghost ids are skipped and named in the header ("missing round 1234").

1. **`X`, left out of everything, the `rounds` parameter included:**
   - `deleted_at` set;
   - `status = cancelled` (V105) (updated 2026-11-25);
   - `status = draft`, being created (`distributing`): its votes are incomplete and jurors don't
     see it;
   - `status = paused` while in review (`Round.inReview`: filled, `distributing = 1`, not yet
     distributed) (updated 2026-11-25): jurors don't see it and it has no votes yet. Its reason is "in
     review". A `paused` round with `distributing = 0` (stopped or finished) takes part as usual;
   - comments-only rounds;
   - pre-screening rounds (after multi-role's migration; §2.5).

   A listed round in `X` is dropped, and the header names it with its reason ("R-12 left out: being
   created"). The walk passes through an ancestor in `X` to that ancestor's own ancestors.
2. **Kinds of round** (outside `X`), each a test on the round alone:
   - **nomination:** any of its roots has `special_nomination`. After lineage phase 1, a nomination
     track (rule 1–3).
   - **regional:** its `regionIds`, taken to top-level ids with `Region.of`, form a real subset of
     the contest's geographic regions (lineage rule 4).
   - **leave-out:** a non-empty `exclude_rounds`, and not regional. (A regional backtrack has a
     leave-out too, but it is regional.)
   - **rejected review:** `prev_rejected_mode = 'only'`.
   - **plain:** none of the above.
3. **`F`, the final round:**
   - the `round` parameter, if it is a contest round outside `X`; any kind may be picked;
   - else the default (updated 2026-11-25, decision 2): the deepest round of the "Main" track, as
     round lineage computes it (its track rule plus the organizer's manual label, lineage decision
     4);
   - until the contest has track data, the deepest **plain** round, then the largest id ("plain"
     also excludes nomination roots). The header then says that the final round was guessed: "No
     Main track yet; showing R-03, the deepest plain round. Label the Main track to fix it."
     (updated 2026-11-25, decision 2). The same note covers the default columns' guessed steps
     (§2.3): "… and, before Q-02, Q-01-nf, which gave it the most files (guessed). Label the Main
     track to fix it." (refined 2026-11-25 after the lineage track check).
   - **Ambiguity:** other plain leaves (rounds no plain round descends from) at the same maximum depth
     are not ancestors of `F`. When there are any, the header warns and names them, with a link for
     each: "R-03 and [test] R-03 both end the chain at depth 3; showing R-03 (larger id). Pick
     another, or cancel the trial round (once lineage's Cancel lands)."
     - This covers trial rounds, which can't be cancelled yet (V105's `cancelled` covers only
       cancelled creations) (updated 2026-11-25), and two real end rounds.
4. **`A`, the chain:** `F` and all its ancestors, minus `X`. A merge brings in all its feeders.
5. **`G`, regional rounds:** regional rounds outside `A` with at least one ancestor in `A`. A chain
   of regional rounds (K-01 → K-02) is covered, because K-02's ancestors include `A`.
6. **`B`, add-ons:** leave-out and rejected-review rounds outside `A` with at least one ancestor in
   `A`.
7. **Explicit list:** the `rounds` parameter replaces `A ∪ G ∪ B` with the listed rounds, minus `X`.
   Each listed round is classified by item 2. This is for chains done by hand, as in 2025.

Special nominations and the category tracks (WLM Interior, Film, Video) drop out on their own,
because they aren't ancestors of `F`.

### 2.2 Why side branches are add-ons, not `F` (review point 1)

- **The risk.** With v1's "deepest, then largest id", any of these at the same depth as round 3, and
  with a larger id, becomes `F`:
  - a "files of round 2 not in round 3" round (lineage §4);
  - a review round of round 2's rejected files (V93 `only`);
  - a trial round.

  Round 3 then drops out of the chain and of the columns (updated 2026-11-25: "show, don't pick").
  Organizers are likely to create exactly such a round in response to this report.
- **The fix.** Such rounds never become the default `F` (item 3). They are add-ons (item 6), placed
  in the column order next to the round they came from (§2.3). A tie between plain rounds is
  warned about, not guessed. Decision 3 asks about this.

### 2.3 Column order (rewritten 2026-11-25: "show, don't pick")

1. **The chain `A`, deepest first,** then the largest id. `F` is the first column.
2. **Add-ons `B`** (decision 3), placed next to their anchor `P`, the previous round in `A` that
   comes first in the order (the deepest one):
   - a leave-out add-on goes directly before `P`: it is the organizers' newer decision on `P`'s
     files that the left-out round didn't take;
   - an add-on whose anchor is `F` itself goes directly after `F`;
   - a rejected-review add-on goes directly after `P`;
   - several add-ons at the same place go newest first.
3. **Regional rounds `G`:** by lineage depth, like any chain round (decision 5). Their cells for
   regions outside their filter are blank ("not in this round's regions").
4. **Explicit list** (`rounds=`, decision 1): the same order, by kind.

**Shown by default** (decision 17, refined 2026-11-25 after the lineage track check): `F` and its
main line, latest first.
Starting at `F`, step back one previous round at a time:
- if one of the current round's previous rounds is on the "Main" track (the same lineage track as
  decision 2), take it;
- otherwise take the previous round that supplied the most files to the current one (its files in
  the current round, by `round_image` membership); with a single previous round, that is the one;
- stop at a root.

A step taken by the "most files" rule among several previous rounds is marked as guessed, in the
same header note as decision 2's `F` (§2.1 item 3), which suggests labelling the Main track. Side
feeders of a merge, add-ons and regional rounds are behind "Show all rounds" (a GET flag, so a link,
with no script), which shows every column in the order above. A column is never dropped for being
empty.

- **WLM 2026** (§7.5): the automatic Main track is Q-02 alone (the merge round; Q-00 → Q-01 → … are
  on the "Quality" track), so the first step is guessed: Q-02 → Q-01-nf (494 of its 507 files) →
  Q-01 → Q-00, the last two steps having a single previous round. With the two labels ("Main" on
  Q-00, its own label on Q-00-1), the same path is on the Main track and nothing is guessed.
- **WLE 2026** (§7.4): Main is 1937, 1938, 1942 and 1952 automatically, so 1952 → 1942 → 1938 follow
  the track. 1938's previous rounds, 1916 and 1920, are not on it; 1920 supplied 777 of 1938's 856
  files (1916: 79), so the last step, → 1920, is guessed. 1937 is not a previous round of 1938, so
  the path never reaches it.

### 2.4 What a cell shows (rewritten 2026-11-25: "show, don't pick")

1. **The files:** the region's top N eligible files (§2.5) with a positive result in that round,
   by the round's own rank key, with every file tied at the N-th place (decision 8). Each shows
   its rank in the region, title, score in the round's terms and the national-winner mark, and
   links to the large view.
2. **The numbers:** how many files the region had in the round, how many with a positive result,
   and how many were excluded (decisions 12–13). Without a positive file, the cell has numbers only:
   - "no files of this region";
   - "4 files, none with a result yet" (no votes cast);
   - "4 files, none selected" (yes/no) or "4 files, only ✗ marks" (rated: a grade sum of 0 with
     votes cast).
3. **Unlinked rounds (review point 2).** For a region with no file in any column, one statement
   counts its files in the contest's *other* rounds, i.e. not in `X` and not columns (§4.2). The
   row says: "Files of this region are in K-07 (12 files) and Обл-02 (4), which aren't linked to
   the chain. Add them with 'rounds' if they belong to it." Up to 3 rounds are named, newest first,
   with a "+k more" count.
4. **No files anywhere** (no file in any column and no unlinked round):
   - "The contest has 7 files of this region; none reached round 1 (Q-00)", using the cached
     category counts;
   - or "The contest has no files of this region" (the collapsed group of §3.2).
5. **A chain that looks broken** (header warning). When the largest root of `A` (a round of `A`
   without a previous round in `A`) has under about 10% of the contest's files, the header says
   the following. (Updated 2026-11-25: "half" was the v2 guess. Phase 0 on WLE 2026 showed that it
   flags a main root when the files are split over parallel first rounds, 44% and 56%, while about
   10% separates the real side roots, 0.3% and 2.1%; §7.4.)

   > The chain starts at R-01 with 2,100 files; the contest has 38,000. Earlier steps may have been
   > done outside the app.

   The numbers come from `RoundImageCounts` (cached) and the category count (cached).
6. **Open rounds** (decision 6): the column of a round with `status = active` and its cells are
   marked "provisional", with the votes still to cast. A `paused` round with `distributing = 0` is
   stopped; `finalized`, once written, is never provisional.

Every order is fixed: columns by §2.3; files by the rank key, then page id (`BinaryOrder.scala:5-6`).

### 2.5 Ranking in a column, and settled points

**Ineligible files are excluded first** (decisions 12–13). A file whose author has a conflict of
interest (decision 12) or that is deleted on Commons (decision 13) is removed from every column's
files before ranking, so the ranks, the tie at the N-th place (decision 8) and the national top k
(decision 14) count eligible files only, and the next eligible file takes its place. It is not
shown and never exported; the cell gives the count only ("2 files excluded: conflict of interest or
deleted on Commons").

**Ranking:** the eligible files of `x` in round `R` with a positive result, by `RankKey.of(R, order)`.
- **The order of a yes/no `R` is settled** (review point 7). It is the stored `top_binary_order` of
  the round that took its top-N from `R`:
  - the next chain round that has `R` among its previous rounds;
  - else any round that has `R` among its previous rounds, the newest first.

  It falls back to `BinaryOrder.Default` when none stored one, or `NULL` (legacy). The column names
  the order used.
- **The rank is within the region.** A parity spec checks that it equals the rank in the round's
  all-jurors "by rate" gallery with that region and order, when no file of the region is excluded.
  With exclusions, the rank is among the eligible files.
- **Scores are never compared across columns,** not even of two rated rounds on the same scale.

**N per region:** the `n` parameter, 1–10, default 1 (decision 7). **Ties at the N-th place:** all
tied files are shown (decision 8), with a link to the region's "by rate" gallery of that round
(`/byrate/round/:r/user/0/region/:region/page/1`, `conf/routes:65`). Ranks are written as ranges.

**Score in the round's own terms:**
- yes/no: "3 of 3 jurors: yes", plus the rejections;
- rated: `rateText(R)` with its scale ("of 10");
- criteria: `criteriaRateString(R)`.

The score rule is named in the column header, as on the round form.

**Files that may not be eligible:**
- **CoI files** (decision 12): the contest's CoI people come from `CoiMembers.forContest`
  (`CoiMembers.scala:66`). Authors are stored normalized as its keys are (`:97`), so the queries
  exclude them before ranking with `i.author NOT IN (<CoI accounts>)`.
- **Files deleted on Commons** (decision 13): excluded the same way, with `i.deleted_at IS NULL`.
- **The national winners** (decision 14): with `national=k`, the final round's overall top k among
  its eligible files, marked "national winner" wherever they are shown. The per-view option
  `hideNational` leaves them out of the final round's column, so the region shows its next file
  there.

**Settled, not open** (review point 7):
- **Pre-screening rounds are never columns.** Multi-role decision 2 makes a pre-screening round a
  yes/no "may go on" screen, and decision 3 gives its results only through Admin or Organizer. Until
  multi-role's migration (next free at merge) every round is a jury round.
- **Access is Admins and Organizers of the contest, and root.** This is `canViewOrgInfo` and
  multi-role decision 3 (§3.1). Jurors never get the page.

---

## 3. The page

### 3.1 Route and access

- **Route:**
  `GET /contest/:contestId/regional-winners` → `RegionalWinnersController.page(contestId: Long, round: Option[Long] ?= None, rounds: Option[String] ?= None, n: Int ?= 1, all: Boolean ?= false, national: Int ?= 0, hideNational: Boolean ?= false, format: String ?= "html", export: String ?= "final")`
  (`n` default 1, decision 7; `all`, `hideNational` and `export` updated 2026-11-25: "show, don't
  pick").
- **Permission:** `withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId)))`,
  as for the contest files page (`GalleryController.scala:587`) and the lineage map.
- **Refusals:**
  - a juror, a non-member, or an admin or organizer of another contest gets 403 through
    `onUnAuthorized` with a `Secured.Denial`;
  - a missing contest gets a 404 `errorPage`;
  - signed out: the login page with `returnTo`, or JSON 401, as `SignedOutSpec` covers;
  - a pure pre-screener gets 403 (after multi-role's migration) (updated 2026-11-25).
- **Parameters:**
  - `round` and `rounds` go through `Round.findByIds(contestId, ids)` (`Round.scala:635`). Other
    contests' ids and missing ids are dropped and named, never `.get`;
  - `n` is clamped to 1–10, and `national` to 0–20;
  - `format` is `html`, `wiki` or `csv` (decision 11); `export` is `final` (the final round's
    column) or `shown` (the columns shown) (updated 2026-11-25: "show, don't pick").
- **Contest switch:** it is a page GET, so it switches the session's contest
  (`Secured.switchingContest`, `Secured.scala:889-902`).
- **Read-only.** No POST, no stored state.
- **Linked from:**
  - the round stats pager (`roundStat.scala.html`) and the rounds page (admins);
  - later, the lineage map's regions table.

### 3.2 Layout (rewritten 2026-11-25: "show, don't pick")

Server-rendered Twirl with Bootstrap. There is no script, no inline style, and nothing from a CDN
(UI practices). The table rules are those of the lineage map (§2.2 there): the table scrolls in its
own box, with captions and row headers.

1. **Header:**
   - "Chain: R-01 → R-02 → R-03 (final, from the Main track)", or "(final, guessed: no Main track
     yet)" (decision 2);
   - the add-ons and regional rounds, with their anchors;
   - the warnings (§2.1 item 3, §2.4 item 5);
   - dropped or missing rounds;
   - a GET form with the final-round select, `rounds`, N, `national` and `hideNational`, and the
     "Show all rounds" link.
2. **The table:** one row per geographic region, in `RegionScheme.regions` order.
   - **First column:** the region's name and the contest's file count.
   - **One column per shown round** (§2.3), headed by its number and name (`Round.description`,
     `Round.scala:114-117`), its rating and scale, its score rule, "final" / "regional" /
     "add-on to R-02", a "provisional" badge when open, and links to its stats.
   - **Cells:** §2.4: thumbnails (`Global.resizeTo`, `app/controllers/Global.scala:58`) with rank,
     title, score and the national-winner mark, linked to the large view (`conf/routes:97`); the
     numbers; the region's "by rate" gallery of that round.
3. **Regions with no files in the contest,** in a collapsed group (`<details>`). The page covers
   geographic regions only (decision 10).
4. **Filter:** "hide regions with no files" (a GET flag). The default shows every region.

**The region list** is every geographic region of the contest's country (`RegionScheme.of(contest)`;
the 27 KOATUU ids for Ukraine), and nothing else (decision 10). The regions without files get
`Some(0)` in `RegionScheme.regions`.

**Width:** the default columns (4 for WLM and WLE 2026) × N thumbnails fit a laptop screen at N = 1;
with all columns or a larger N the table scrolls sideways in its box, the region column fixed
(updated 2026-11-25, decision 17).

**A contest without regions** has no monument-id template, or has many countries. The page shows a
notice only.

### 3.3 Export (phase 2) (rewritten 2026-11-25: "show, don't pick")

What is shown, by default the final round's column per region (`export=final`); `export=shown`
exports every column shown (the default columns, or all with `all=true`; updated 2026-11-25,
decision 17). Each file carries its round.

**Wiki text** (`format=wiki`, decision 11): a read-only, Twirl-escaped `<textarea>` to copy, with
one block per region:

```
== Kyiv ==
''Round 3 (R-03)''
<gallery>
File:Example.jpg|1. 8.50 (17 / 2 jurors), R-03
</gallery>
```

With `export=shown`, each region's block has one gallery per column, headed by its round.

**CSV** (`format=csv`, decision 11): one row per file shown, with the columns region, round, place
(the rank in the region in that round), file, score or result, and the national-winner mark.

**Both:**
- **Exact consumers:** they always read the votes, whatever `roundImage.read` is
  (`RoundImageQuery.scala:28-29`).
- **Captions** are numbers and message text only, with no author wikitext. File titles can't contain
  `|`, `[`, `]`, `{` or `}`.
- **Never included:** CoI files and files deleted on Commons (decisions 12–13); national winners
  only when `hideNational` leaves them out of the final round's column (decision 14).
- **CSV path:** through `controllers.Csv` and `GalleryController.csvStream`, the only CSV path,
  which needs an `ExportsSpec` change (`ExportsSpec.scala:5-20`) in phase 2.

---

## 4. Queries and cost (rewritten 2026-11-25: "show, don't pick")

Per shown column, two statements: the region summary (§4.1) and the ranked top N (§4.3). Collapsed
columns are not read until "Show all rounds" is used.

### 4.1 Per shown column: the region summary

**Votes path** (the normal one; the shape of `ImageDbNew.scala:467-487`):

```sql
SELECT <regionOf(i.monument_id)> AS region, COUNT(*) AS files,
       SUM(<eligible> AND <positive>) AS positive, SUM(NOT <eligible>) AS excluded,
       SUM(p.assigned - p.voted) AS unrated
FROM (SELECT s.page_id, COUNT(*) AS assigned, SUM(s.rate <> 0) AS voted,
             SUM(s.rate > 0) AS approvals, SUM(s.rate < 0) AS rejections,
             SUM(GREATEST(s.rate, 0)) AS grade_sum
      FROM vote s WHERE s.round_id = ? GROUP BY s.page_id) p
JOIN images i FORCE INDEX (images_page_monument_idx) ON i.page_id = p.page_id
GROUP BY region ORDER BY NULL
```

- `<positive>` is `p.approvals > 0` for yes/no, and `p.grade_sum > 0` for rated.
- `<eligible>` is `i.deleted_at IS NULL AND (i.author IS NULL OR i.author NOT IN (<CoI accounts>))`
  (decisions 12–13).
- **Geographic regions only** (decision 10): other rows are dropped in Scala, or with
  `HAVING region IN (<the country's regions>)`.
- `vote` holds only `in` files, because `dq` and `deleted` votes are in `vote_aside`.
- **Counts path:** used only with `roundImage.read = true` and the proof of §4.3. It is the same
  aggregate over `round_image` with the dirty overlay, filtered on `status = 'in'` and grouped by
  `ri.region`.
- **Code:** `SelectionQuery.regionSummary()` and `RoundImageQuery.regionSummary()`, both in files
  `RawSqlSpec` already allows.

### 4.2 For regions with no file in any column: unlinked rounds

```sql
SELECT ri.round_id, ri.region, COUNT(*) FROM round_image ri
WHERE ri.round_id IN (<other rounds>) AND ri.status = 'in' AND ri.region IN (<regions with no file>)
GROUP BY ri.round_id, ri.region
```

- It runs once, only when such a region exists, over all columns (shown or not).
- It reads the other rounds' primary-key ranges `(round_id, page_id)`.
- `round_image`'s membership and region are kept in the vote's transaction (V84 comment), so they
  are exact whatever `roundImage.read` is. Only the counts are background-kept.

### 4.3 Per shown column: the top N of every region

- **The query:** a window over the same per-file aggregate (votes path, or counts path under the
  proof), with `<eligible> AND <positive>` in its `WHERE`, so ranks and ties are among eligible
  files. It computes `RANK()` over `PARTITION BY region ORDER BY <rank key>` and keeps
  `rk <= N`, which keeps every file tied at the N-th place (decision 8). There is no other cap.
- **Rank key columns:** `RankKey.of(round, order).columns`, translated with
  `RoundImageQuery.translate` (`:213`) on the counts path.
- **Rows** are mapped to grouped `ImageWithRating`, so `rateText`, `criteriaRateString` and `Ranks`
  work unchanged.
- **The national top k** (decision 14): one more statement on `F`, without the region partition,
  only when `national` is given.
- MariaDB 10.6+ has window functions, and the code already uses them (`RoundImageQuery.scala:160`,
  `ImageDbNew.scala:130-131`).
- **The proof (counts path only):** a `fromCounts`-style check per round, the same as
  `RoundList.fromCounts`, reused or factored out. If it fails, the votes are read.

### 4.4 Cost *(inf.; the benchmark decides)*

- **Default:** the default columns × 2 statements, plus the national top k if asked: 4 columns,
  so 8 statements, for WLM and WLE 2026 (updated 2026-11-25, decision 17). Each is one GROUP BY of
  a round's votes: the final rounds are small (WLM 2026's Q-02: 507 files × 13 jurors), the early
  ones large (about 38k files × 3 jurors).
- **All rounds:** 2 statements per column of `A ∪ G ∪ B`: 10 columns, so 20 statements, for WLM
  2026; 10–15 columns, so 20–30 statements, for future WLM contests *(inf.)*. Phase 0 read every WLM
  2026 round's region counts in one statement in about 0.5 s on a production copy (§7.5).
- **Header numbers:** file totals for the chain's rounds come from the cached `RoundImageCounts`.
- **Unlinked rounds:** one statement, only when needed.
- **Thumbnails:** 27 regions × shown columns × N, plus ties, through the existing proxy and cache.
- **Nothing else changes:** no new query on the voting path, the galleries or the large view.
- **Caching:** none in phase 1. If needed, a stopped round's column can be cached later by
  `(round_id, counts_gen)` or by the round's last vote time.

---

## 5. Edge cases (rewritten 2026-11-25: "show, don't pick")

| Case | Behaviour |
|---|---|
| A file "in several regions" | Not possible: one monument id per file (§1.1) |
| Kyiv city (`80`) and Kyiv oblast (`32`) | Separate regions. Sub-region ids (`80-361`) in a filter count as their top-level region |
| A region with only rejected files in a round | That cell has numbers only: "4 files, none selected" |
| A region with files not yet voted in a round | Numbers only, "none with a result yet"; the column is "provisional" if the round is open (decision 6) |
| A region with fewer than N positive files in a round | The cell shows those; earlier columns show the rest |
| The same file in several columns | Shown in each, with that round's rank and score |
| A "round 2 not in round 3" round, created after this report | An add-on column before round 2, under "Show all rounds" (§2.3). It never becomes `F` (§2.2) |
| A review round of round 2's rejected files | An add-on column after round 2, under "Show all rounds" |
| A trial round at round 3's depth (until lineage's Cancel) | Named in the ambiguity warning; `F` is the larger id, and the organizer can pick the other |
| A regional round created in the app | Under "Show all rounds", placed by depth (decision 5); blank cells for regions outside its filter |
| A regional round copied by hand (2025, no edge) | Named under "unlinked rounds" for regions with no file in any column (§2.4 item 3). It can be added with `rounds=` |
| WLE rounds without `previous` | The only column is `F`. Regions with no file name the unlinked rounds, and the header warns about the small root (§2.4 item 5) |
| A chain round with a filter of all regions | Not regional (lineage rule 4) |
| Rounds with different scales | Each column on its own scale; scores are never compared across columns |
| A merge | The feeder on the Main track (else, guessed, the one that supplied the most files) is a default column; the other feeders are under "Show all rounds" (decision 17, refined 2026-11-25 after the lineage track check) |
| Score rule changed after the announcement | The current rule is shown and named |
| Stopped jurors | Their votes count (§1.3) |
| Disqualified files, and files deleted while their round was open | Out |
| Deleted on Commons after the round, or a conflict of interest | Excluded before ranking; the next eligible file takes its place; counted in the cell; never exported (decisions 12–13) |
| A national winner that is also the region's best | Marked; or, with `hideNational`, left out of the final round's column (decision 14) |
| A winner chosen outside the app | Not modelled; facts only (decision 16) |
| WLM nomination prefixes, Other prefixes, files without an id | Not shown (decision 10) |
| A cycle | Reported; no columns |
| A ghost id | Named in the header |
| A contest without regions | Notice only |

---

## 6. Relation to other plans

| Plan | How this fits |
|---|---|
| Round lineage (`2026-11-10-round-lineage.md`) | **Graph core.** Uses the shared `services.RoundLineage` core (§1.3): graph, Kahn order, ancestors, depth. **Track core** (updated 2026-11-25, decision 2): also the "Main" track computation and the manual label (`round.track`), shared with lineage's minimum slice A (track label, chip, Track menu; about 3–4 days), built first, then phase 1 on top of it (updated 2026-11-25, decision 15). **Chain.** "Chain" here is `F` and its ancestors, not a track; only the default `F` reads the track label. **Kinds.** Rule 4 is reused for "regional", and §4's leave-out steps become add-ons. **Status.** V105's `cancelled`, `draft` and in-review `paused` join `X` (updated 2026-11-25). **No storage** (decision 15 there). **Back-reference.** The lineage plan's §6 table should get a row for this report, linking its regions table and backtracking (§4 there) to this page. Not edited here |
| Lineage §4 backtracking | The report shows which regions need a backtrack. The round is created through the normal form, and the report then shows it as a column (§2.3) (updated 2026-11-25: "show, don't pick") |
| Multi-role (migration: next free at merge; updated 2026-11-25) | Pre-screening rounds are never columns. Pure pre-screeners get 403 (decisions 2 and 3 there) |
| Combined juror votes | The report is never shown to jurors |
| Round images P2b/P3 | The counts path turns on with `roundImage.read`. DQ is already out. `finalized` can drop the "provisional" badge |
| Per-region top-N for the next round | Not here. It is the preventive fix, and could be a later round-form option *(inf.)* |

---

## 7. Phases

S ≈ ½ day, M ≈ 1–2 days, L ≈ 3+ days of agent work, each with review and targeted specs.

| Phase | Content | Size |
|---|---|---|
| 0 | **WLE Ukraine 2026 check, local** (§7.1). On the 26.11 rehearsal copy of production, migrated to V95, with the read-only SQL below. A local script (`python -I`) applies §2. It shows the chain, the add-ons, the ambiguity, the regions that fell back, the unlinked rounds, and whether WLE's `previous` links are recorded. It also verifies WLE's region prefixes and tunes the "small root" threshold. It is phase 1's acceptance case, and it also serves lineage decision 3. Done for WLE 2026 (§7.4) and WLM 2026 (§7.5) (updated 2026-11-25) | S |
| 1 | **The page,** on lineage's slice A (the `RoundLineage` core with the Main track and the manual label, about 3–4 d, built first (decision 15); not counted here). The column model: rounds, order, default columns, `F` (pure, unit-tested; ½ d). Region summaries, top N and unlinked rounds on the votes path, plus the counts path behind `roundImage.read` with its proof (1 d). Controller, route, view, messages in en/uk/ru/fr, links, screenshots in both themes (1–1.5 d). (updated 2026-11-25: "show, don't pick") | M (2.5–3 d) |
| 2 | **Wiki text** (S) and **CSV** (S), both from the start, with the `ExportsSpec` change (decision 11; updated 2026-11-25, decision 11) | S–M (½–1 d) |
| 3 | **After lineage phase 1 and multi-role's pre-screening rounds:** pre-screening rounds join `X`; a link from the lineage map. The default `F` from the "Main" track moves to phase 1 (updated 2026-11-25, decision 2). V105's statuses are read in phase 1 already (§2.1) (updated 2026-11-25) | S (½ d) |

**Total:** about 3.5–4.5 agent days (updated 2026-11-25: "show, don't pick"): phase 1 2.5–3, phase 2
½–1, phase 3 ½. With lineage's slice A, about 6.5–8.5 days in all.

**Order of work** (updated 2026-11-25, decision 15): lineage slice A → regional winners phase 1 →
phase 2 (exports) → phase 3. Slice A and phase 1 come before the WLM final in early December; phase
3 waits for lineage phase 1 and multi-role's pre-screening rounds.

### 7.1 Phase 0 SQL (read-only; V95 schema and later)

- **Where it runs** (updated 2026-11-25): a local rehearsal container holding a copy of production, in
  READ ONLY transactions, under the shared heavy-run lock. WLE 2026 ran on a copy migrated to V95,
  WLM 2026 on a V113 copy of 2026-11-25.
- **What production has** (updated 2026-11-25): production is at V113, with `vote`, `round_image`,
  `average_rule`, `exclude_rounds` and `round.status`, so the SQL runs on a copy as it is. (On
  2026-11-11 it was at V70, with the votes in `selection`, so the SQL was written against V95.)
- **Output:** ids, names, counts and region codes only. No juror ids, authors, titles or other
  personal data.

```sql
-- 0. The contest (its id is used as @c below).
SELECT id, name, year, country, campaign, monument_id_template
FROM contest_jury WHERE name LIKE 'Wiki Loves Earth%' AND year = 2026;

-- 1. Its rounds, with the columns the rule reads.
SELECT id, number, name, previous, regions, rates, has_criteria, special_nomination,
       top_images, top_binary_order, prev_selected_by, prev_min_avg_rate,
       prev_rejected_by, prev_rejected_mode, exclude_rounds, average_rule,
       active, distributing, deleted_at,
       created_at, status  -- status: V105 (updated 2026-11-25)
FROM round WHERE contest_id = @c ORDER BY id;

-- 2. Files and positive files per (round, region), from the votes (round_image's counts may
--    not be recomputed on the copy). "Positive": a yes in a yes/no round (rates = 1), a grade
--    sum above 0 otherwise.
SELECT p.round_id, NULLIF(LEFT(SUBSTRING_INDEX(i.monument_id, '-', 1), 3), '') AS region,
       COUNT(*) AS files,
       SUM(IF(r.rates = 1, p.approvals > 0, p.grade_sum > 0)) AS positive,
       SUM(p.assigned - p.voted) AS unrated
FROM (SELECT s.round_id, s.page_id, COUNT(*) AS assigned, SUM(s.rate <> 0) AS voted,
             SUM(s.rate > 0) AS approvals, SUM(GREATEST(s.rate, 0)) AS grade_sum
      FROM vote s JOIN round r0 ON r0.id = s.round_id AND r0.contest_id = @c
      GROUP BY s.round_id, s.page_id) p
JOIN round r ON r.id = p.round_id
JOIN images i ON i.page_id = p.page_id
GROUP BY p.round_id, region ORDER BY p.round_id, region;

-- 3. The contest's files per region (all its files, for "none reached round 1").
SELECT NULLIF(LEFT(SUBSTRING_INDEX(i.monument_id, '-', 1), 3), '') AS region, COUNT(*) AS files
FROM contest_jury c JOIN category_members cm ON cm.category_id = c.category_id
JOIN images i ON i.page_id = cm.page_id
WHERE c.id = @c GROUP BY region ORDER BY region;

-- 4. round_image membership per (round, region), to compare with query 2: V84's backfill
--    should give the same files.
SELECT ri.round_id, ri.region, ri.status, COUNT(*) FROM round_image ri
JOIN round r ON r.id = ri.round_id AND r.contest_id = @c
GROUP BY ri.round_id, ri.region, ri.status ORDER BY ri.round_id, ri.region, ri.status;
```

Phase 0 also records each query's time on the copy, as a first cost figure.

### 7.2 Tests (phase 1) (rewritten 2026-11-25: "show, don't pick")

- **`RegionalWinnersSpec`** (unit, no DB; the column model):
  - the columns of a 3-round chain, deepest first, `F` first;
  - the default `F` is the Main track's deepest round, by the track rule and by the manual label;
    without track data, the deepest plain round with the "guessed" note (decision 2);
  - **side branches** (review point 1, decision 3):
    - a "round 2 not in round 3" round with a larger id than round 3 → `F` stays round 3, and the
      add-on is a column before round 2;
    - a rejected review → a column after round 2;
    - an add-on of `F` → a column after `F`;
    - a trial round at the same depth → warned and named;
  - a regional round → a column by depth, blank for regions outside its filter;
  - a merge: all feeders are columns under `all`; the default shows only the Main or most-files
    feeder;
  - a nomination root is excluded from the default `F`;
  - a listed round in `X` is dropped and named;
  - a cycle, and a ghost id;
  - the default columns: stepping back from `F`, the previous round on the Main track, else the one
    that supplied the most files, marked guessed when there was a choice; a single previous round is
    not a guess; stop at a root; side feeders, add-ons and regional rounds only with `all`; the WLM
    2026 shape (Main = the merge round alone) and the WLE 2026 shape (Main down to 1938, roots off
    it) (refined 2026-11-25 after the lineage track check);
  - a region with no file in any column → unlinked rounds named; no files at all; the small-root
    warning;
  - the stored `top_binary_order` is used, else the default;
  - only geographic regions are rows (decision 10).
- **`RegionalBestQuerySpec`** (DB, truncate isolation):
  - parity with `RoundList` per region ("by rate", all jurors) for yes/no under each order, rated
    under `rated`, `assigned` and `sum`, criteria, and ✗;
  - all files tied at the N-th place are returned (decision 8);
  - `dq` and `deleted` files are out, and stopped jurors are counted;
  - CoI files and files with `images.deleted_at` are excluded before ranking, with their count per
    region; the next eligible file takes their place (decisions 12–13);
  - the national top k among eligible files (decision 14);
  - both read paths give the same rows (votes; counts with a clean generation), and a dirty
    generation falls back;
  - the unlinked-rounds query.
- **`RegionalWinnersPageSpec`** (controller):
  - admin 200, organizer 200, root 200, and 404 for a missing contest;
  - negative: a juror of the contest, a non-member, an admin of another contest and a pre-screener
    (after multi-role's migration) each get 403;
  - foreign `round` and `rounds` ids are dropped, never `.get`;
  - `n` and `national` are clamped;
  - a "provisional" column for an open round (decision 6); `hideNational` (decision 14);
  - no per-juror names or votes on the page;
  - the exports: `export=final` and `export=shown`, each file with its round; the CSV's columns; no
    CoI file, deleted file or non-geographic region (decisions 11–13).
- **Guards:** `RouteAuthSpec` and `SignedOutSpec` cover the route. `RawSqlSpec` is unchanged if the
  SQL stays in `ImageDbNew.scala` / `RoundImageQuery.scala`. `ExportsSpec` changes in phase 2, for
  the CSV.
- **The browser story:** from the stats pager to the page, "Show all rounds", then to the large
  view.

### 7.3 Benchmark note

- **The main path is the votes** (`roundImage.read` off). The counts path is measured only once the
  P2b pilot turns it on.
- **Fixture:** the Gatling fixture (38k images, 20 jurors, a yes/no and a rated round). The run
  creates a third round through the round form (a top-N of the rated round, so that regions drop out)
  instead of changing `GatlingDbSetup.load`, which would need a `FixtureVersion` bump *(inf.)*.
- **What is measured:** the page with the default columns and with all columns (updated
  2026-11-25: "show, don't pick"), in absolute milliseconds and with the run variation. SQL debug
  logging is off.
- **No paired comparison** is needed, because no existing path changes. One small pilot, extended
  only if the result could change the design (e.g. to add caching).
- **Phase 0's query times** on the rehearsal copy give a first, production-sized figure.

### 7.4 Phase 0 (WLE 2026) (added 2026-11-25)

Run on 2026-11-11 on the 26.11 rehearsal copy migrated to V95: read-only, counts only. The query
outputs were not kept; this is the run's summary.

- **Contest:** 619. Contest 626 shares its category (538) but has no rounds.
- **Rounds:** 11, all stopped, none being created or deleted. `regions`, `special_nomination`,
  `top_images`, `top_binary_order` and `exclude_rounds` are NULL everywhere; `average_rule` is
  `rated` everywhere.
- **Chain:** two roots, 1916 (quantity, 2,130 files) and 1920 (quality, 1,696; disjoint halves of
  3,826), feed 1938 (yes/no, the files with a yes). Then 1942 (rated 1–20) and 1952 (rated 1–20,
  minimum average 10.88). The `previous` links are recorded. 1937 is an unvoted duplicate of 1938.
  Other tracks: video 1915 → 1953; nature loss 1954 → 1960 (unvoted) and 1954 → 1961.
- **Default `F`:** 1952 (depth 3 as §2.1 counts it, unique; 1937 is a dead end at depth 1).
  "Largest id" would give 1961, which is wrong, and `current_round` is NULL.
- **Regions:** 3,826 files, 27 two-digit KOATUU prefixes, 13 files without an id, no nomination
  prefixes (`LEFT(…, 3)` is harmless).
- **Coverage:** regions with a positive file: 25 in 1938, 24 in 1942 (63 dropped), 16 in 1952.
  - Falling back to 1942: 05, 07, 21, 35, 44, 61, 85 and the files without an id.
  - Falling back to 1938: 63.
  - Regions 23, 56 and 59 have files in 1938 but no yes there, so their last round with a positive
    file is a root (23 and 59: 1920 only; 56: both).
- **"Positive":** in the rated rounds 1942 and 1952, every file is positive, so the top N ranks by
  rate. In 1938, whole regions have no positive file, so their cells there have numbers only.
- **Fewer than N = 3** files in 1952: 11 of its 16 regions (measured at N = 3; the page default is
  now 1).
- **Small root:** the side roots 1915 (0.3%) and 1954 (2.1%) against the main roots 1916 (55.7%) and
  1920 (44.3%). "Half" would flag 1920; about 10% separates the side roots cleanly.
- **`round_image`:** the same (round, region) files as the votes, all `in`, but every counter is 0
  (`counts_gen_done = 0`), so only the votes path could be checked there.
- **Not exercised:** no deleted or `dq` files, `exclude_coi = 0`, no rounds open, no regional,
  leave-out or rejected-review rounds.
- **Across 2025–26:** WLE records `previous` on about half its rounds (38 of 76 in 2025, 26 of 54
  in 2026). WLM 2025 has 17 regional rounds and 10 nomination rounds. `exclude_rounds` and
  `prev_rejected_by` are unused in all of them.
- **Query times:** queries 2–4 took 0.15–0.17 s each, including `docker exec`.

### 7.5 Phase 0 (WLM 2026) (added 2026-11-25)

Run on 2026-11-25 on a local copy of production (a dump of 2026-11-25, V113): READ ONLY
transactions, counts only. §7.1's queries plus a status column, an approximate CoI match and a
what-if; about 0.5 s each, including `docker exec`.

- **Contest 628.** 12 rounds, created 2026-11-11 to 11-19: 10 `paused` (stopped, `distributing = 0`),
  2 `active` (Q-02 and the nomination round Kha-00); none deleted, draft, in review, cancelled or
  finalized. Every step takes the files with a yes (`prev_selected_by = 1`); no top-N, minimum
  score, stored order or rejected-by; `exclude_coi = 1` and `average_rule = rated` everywhere.
- **Chain** (all links in `previous`): yes/no roots Q-00 (quality category, 8,839 files) and Qn-00
  (quantity, 18,442). Q-00 → Q-01 → Q-01-nf; Q-00 → Q-00-1 (leave-out: Q-00's files not in Q-01)
  → Q-00-1-nf → Q-00-1.5; Qn-00 → Qn-00-1 → Qn-00-1-nf. They merge into **Q-02** (rated 1–10,
  507 files = 494 + 7 + 6, active).
- **Default `F` (decision 2):** Q-02, depth 4, unique (next deepest: Q-00-1.5 at 3); no tie. Right
  today; a jury-final round made from Q-02 would become `F`, with Q-02 as the next column
  (updated 2026-11-25: "show, don't pick").
- **Side rounds (decision 3):** "[delete] Q-00-1", a trial copy (640 files, no votes, paused, depth
  1), can't be cancelled under V105 and is harmless outside the chain. The leave-out Q-00-1 feeds
  the merge, so it is a chain round, not an add-on. `G` and `B` are empty.
- **Regional rounds (decision 5):** none. Ten rounds filter on all 27 KOATUU codes (in two orders);
  only lineage rule 4, compared as a set, keeps them from counting as regional.
- **Nomination round:** Kha-00 (`special_nomination` Харків; root; 2,920 files, all region 63;
  1,166 positive) is not an ancestor of Q-02 and drops out, as assumed.
- **Coverage:** all 27 regions have positive files in Q-02, so every region has a file in the final
  round's column today (updated 2026-11-25: "show, don't pick"). Per region, 1 (18) to 55 (63)
  files; **3 regions have fewer than N = 3** (18: 1; 35, 85: 2; measured at N = 3; the page default
  is now 1).
- **Columns** (updated 2026-11-25: "show, don't pick"): Q-02 first, then by depth Q-00-1.5,
  Qn-00-1-nf, Q-01-nf, Q-00-1-nf, Qn-00-1, Q-00-1, Q-01, Qn-00, Q-00. By depth alone, the first 3
  would be Q-02 and two small feeders of the merge (6 and 7 of Q-02's files). Resolved by decision
  17 (refined 2026-11-25 after the lineage track check): the lineage track check found the automatic
  Main track to be Q-02 alone (the merge round), with Q-00 → Q-01 → … on the "Quality" track. The
  default columns are still Q-02 → Q-01-nf (494 of Q-02's 507 files, the one guessed step) → Q-01 →
  Q-00; with "Main" on Q-00 and its own label on Q-00-1, the same path without a guess. The rest are
  under "Show all rounds".
- **"Positive":** every Q-02 file has a mark above 0; in the yes/no feeders whole regions have
  files but no yes (Q-00-1.5: 105 files, 6 positive).
- **Open round (decision 6):** 13 jurors, each assigned every file; 1,689 of 6,591 marks cast (3.3
  of 13 per file), so every row would be "provisional" now.
- **What-if (provisional):** a contest-wide top N of Q-02 by today's averages keeps 5 / 10 / 13 /
  17 / 20 / 23 of 27 regions for N = 10 / 20 / 30 / 50 / 100 / 150.
- **Prefixes (decision 10):** of 32,531 contest files, 1,884 have non-KOATUU prefixes (`00`, `88`,
  `90`, `92`–`94`, `96`, `99`, `Q12`) and are in no round (the roots' 27-code filter); 14 have no
  id, 13 of them in the roots (3 positive in Q-00), none later.
- **Reconciliation:** 32,531 = 30,633 KOATUU + 1,884 other prefixes + 14 without an id. The roots
  are disjoint: 8,840 + 18,443 files in `round_image` (each with 1 set aside as deleted). Kha-00's
  2,920 overlap them by 2,900 (862 Q-00, 2,038 Qn-00), so 20 are in Kha-00 only. In none of the
  three: 5,228 (3,343 KOATUU, the 1,884 prefixed, 1 without an id).
- **Small root:** the chain's roots hold 27.2% and 56.7% of the files (Kha-00, outside it, 9.0%).
- **`round_image`:** counters populated (`counts_gen = counts_gen_done`) and equal to the votes on
  all 285 (round, region) cells, so the counts path can be checked on this copy.
- **Deleted and CoI (decisions 12–13):** 9 files deleted on Commons, 2 set aside while open
  (Q-00, Qn-00), no `dq`, none in a later round. An approximate author match: 55 files of one juror
  in Qn-00 (49) and Qn-00-1 (6), none later.

---

## 8. Decisions

(updated 2026-11-25) All decided by the maintainer on 2026-11-25; 4 and 9 replaced by "show, don't
pick", and 17 added. They were asked one by one. (updated 2026-11-25) In decisions 2 and 3, "V99"
means lineage's Cancel of a distributed round: V105's status is in production, but it cancels only
creations. Settled in the plan, so not asked: pre-screening rounds are never columns, access is
limited to the contest's Admins and Organizers, and a yes/no round's order comes from the stored
`top_binary_order` (§2.5).

**1. Which rounds are candidates.**
- (a) The chain, plus the regional rounds and add-ons that branch from it.
- (b) Every round outside nominations, latest by id.
- (c) Only the rounds the organizer lists.

**Recommendation: (a), with (c) as an override.** Reasons:
- (a) follows the recorded lineage;
- (b) would take a WLM Interior or Film round as "latest";
- (c) alone repeats this year's miss.

**Decided (maintainer, 2026-11-25): (a) with (c) as an override**: the chain plus regional rounds and
add-ons, with a "rounds=" list overriding it.

**2. The default final round.**
- (a) The deepest plain round, then the largest id; after lineage phase 1, the "Main" track's
  deepest round.
- (b) No default.
- (c) `contest.current_round` (legacy).

**Recommendation: (a).** Reasons:
- it gives round 3 for WLE and Q-05-10 for WLM 2025 *(inf.)*;
- the page names the choice, warns about ties, and offers the select;
- (c) isn't kept up to date.

*Note (updated 2026-11-25): (c) is moot, as the code no longer reads `current_round` (#153).*

**Decided (maintainer, 2026-11-25)**: the main nomination's winners final is tracked in the app
through round lineage's "Main" track. The final round is the deepest round of the Main track
(computed track rule plus the organizer's manual label, lineage decision 4). Until a contest has
track data, the page falls back to the deepest plain round of the chain (then largest id), and says
that it guessed.

**3. Side branches and an ambiguous final round (new).**
- (a) Leave-out and rejected-review rounds never become the default `F`. They are add-ons placed by
  §2.3, and a tie of plain end rounds is warned about and named, with the larger id shown. A
  leave-out add-on whose previous round is `F` itself ranks below `F` until decision 14 is
  answered: above `F`, it would outrank `F` for every region and so settle decision 14 quietly.
- (b) Like (a), but with a tie the page shows no result until the organizer picks.
- (c) No special handling: rely on cancelling trial rounds (V99) and on the select.

**Recommendation: (a).** Reasons:
- (c) silently drops round 3 when organizers make the very round this report suggests;
- (b) blocks the page on a trial round, which can't be cancelled before V99;
- (a) still names the tie.

**Decided (maintainer, 2026-11-25): (a)**: side rounds are add-ons, never the default final round; a
tie of plain end rounds is warned about and named; a leave-out add-on off the final gets the column
directly after it (§2.3).

**4. What "present in a round" means.**
- (a) At least one file with a positive result.
- (b) Any file.
- (c) A score threshold.

**Recommendation: (a).** Reasons:
- (b) hides "all rejected in round 3";
- (c) needs a threshold per scale.

**Replaced (maintainer, 2026-11-25):** no "present" rule; every chain round is shown as a column
("show, don't pick").

**5. Regional rounds versus the chain.**
- (a) A regional round for X always wins for X; among them, the newest wins.
- (b) Lineage depth for all.

**Recommendation: (a).** Reasons:
- a regional round is the explicit step for that region;
- (b) picks the round a backtrack replaced.

*Narrowed (updated 2026-11-25: "show, don't pick"): no round wins any more; a regional round is a
column like any other chain round. The only remaining question is where its column goes:*
- *(a) by lineage depth, like the other columns (§2.3);*
- *(b) newest first, before the chain's columns.*

*Recommendation: (a): one order for all columns. With (a), a shallow regional backtrack can fall
outside the default columns (decision 17) and be collapsed by default; (b) keeps it in view but
breaks the depth order.*

**Settled by the default-columns decision (2026-11-25)**: regional rounds appear under "Show all
rounds" in lineage-depth order.

**6. Open rounds.**
- (a) Valid sources, with a "provisional" badge.
- (b) Only stopped rounds count.

**Recommendation: (a).** Reasons:
- the report is most useful while round 3 runs;
- (b) points at round 2 while round 3 still decides.

**Decided (maintainer, 2026-11-25): (a)**: open rounds are shown, with a "provisional" badge.

**7. Photos per region.**
- (a) A parameter from 1 to 10, default 3.
- (b) A fixed 1.
- (c) A per-contest setting.

**Recommendation: (a).** Reason: it needs no storage, and contests award different numbers *(inf.)*.

**Decided (maintainer, 2026-11-25): (a) with default 1**: a parameter from 1 to 10 photos per
region, default **1** (not the recommended 3).

**8. Ties at the N-th place.**
- (a) Up to 2N, then "+k more tied".
- (b) Cut by page id.
- (c) Show all.

**Recommendation: (a).** Reasons:
- (b) hides a tie the organizers must resolve;
- (c) can mean 40 files at "3 of 3 yes".

**Decided (maintainer, 2026-11-25): (c)**: all files tied at the N-th place are shown (not the
recommended 2N cap).

**9. Fewer than N positive files in the source.**
- (a) Show those, with a link to the next candidate.
- (b) Fill up from earlier rounds.

**Recommendation: (a).** Reason: one source per region keeps the page explainable, and scales differ.

**Replaced (maintainer, 2026-11-25):** no filling; earlier rounds are visible as columns ("show,
don't pick"). History: (b) was decided earlier the same day, with files filled from earlier rounds
until N.

**10. Region scope.**
- (a) Geographic regions in the table; nomination prefixes, Other and files without an id in
  collapsed groups.
- (b) One table for all.
- (c) Geographic only.

**Recommendation: (a).** Reasons:
- regional prizes are geographic;
- (c) hides files that need a decision.

**Decided (maintainer, 2026-11-25): (c)**: geographic regions only. Nomination prefixes, "Other" and
files without a region id are not shown on this page.

**11. Export.**
- (a) Wiki text (phase 2); CSV only if asked.
- (b) None.
- (c) Wiki text and CSV.

**Recommendation: (a).** Reasons:
- the results are published on Commons;
- CSV needs an `ExportsSpec` change for little gain.

**Decided (maintainer, 2026-11-25): (c)**: export as wiki text and as CSV, both from the start (not
"CSV only if asked").

**12. Files with a conflict of interest** (the author matches the contest's CoI list, and the source
round didn't leave such files out).
- (a) Listed with a "CoI" badge, counted in N.
- (b) Skipped silently; the next file is shown.
- (c) Listed with a badge but not counted in N, so the next eligible file is shown too; left out of
  the export.

**Recommendation: (c).** Reasons:
- the organizers see both the problem and the replacement;
- (a) can leave a region with an ineligible winner only;
- (b) hides why a known photo is missing.

**Decided (maintainer, 2026-11-25)**: files with a conflict of interest are not among the files a
winner is chosen from. They are excluded from the candidates, so the next eligible file takes their
place, and they are never in the export.

**13. Files deleted on Commons after their round.**
- (a) Listed with a "deleted on Commons" badge, not counted in N, and left out of the export.
- (b) Left out.
- (c) Listed and counted.

**Recommendation: (a).** Reasons:
- a deleted file can't be published as a winner, so it shouldn't take a slot;
- hiding it would puzzle organizers who remember it.

**Decided (maintainer, 2026-11-25)**: files deleted on Commons are excluded from the candidates like
conflict-of-interest files (decision 12): the next eligible file takes their place, they are not
shown, and they are never in the export.

**14. Can a national winner also be a regional winner?** (Not in the code; the contest's rules
decide.)
- (a) Yes, with no marking.
- (b) Yes, marked "national winner" when `national=k` is given: the final round's overall top k by
  its rank key.
- (c) No: with `national=k`, those files are skipped in the regions, and the next file is shown.

**Recommendation: (b) by default, with (c) as a per-view option** if the rules exclude doubles.
Reason: WLE and WLM rules may differ by year *(inf.)*, and marking costs one more top-k query on
`F`.

**Decided (maintainer, 2026-11-25): (b) with (c) as a per-view option**: with `national=k`, the
final round's national top k are marked "national winner" in their regions; a per-view option
skips them so the region shows its next file.

**15. Dependency on the lineage plan.**
- (a) Build the small `RoundLineage` core now, or reuse it if lineage lands first.
- (b) Wait for lineage phase 1.

**Recommendation: (a).** Reasons:
- the core is ½–1 day and is shared by design;
- lineage phase 1 is L (7–9 d), with its own entry condition.

*Note (updated 2026-11-25, decision 2), not a decision: decision 2 makes the shared core larger.
"Build the core now" now includes the track computation for "Main" and the manual label: lineage's
minimum slice (A), with tracks, the manual label, the chip and the Track menu, about 3–4 days.
Regional winners phase 1 builds on it.*

**Decided (maintainer, 2026-11-25): lineage track core first**: build round lineage's slice A (track
rule, manual label, chip, Track menu) first, then regional winners phase 1 on top of it. One shared
core, both before the WLM final in early December.

**16. Hand-made decisions and overrides.**
- (a) No stored overrides; `rounds=` for chains done by hand.
- (b) A stored per-contest chain and N.
- (c) Stored "confirmed winner" marks.

**Recommendation: (a)** now. Reasons:
- (b) and (c) need a table, a migration, CSRF writes and an audit trail;
- revisit (b) if organizers bookmark long URLs.

**Decided (maintainer, 2026-11-25): (a)**: no storage now. Everything is in the link; revisit if
organizers need to record confirmed winners.

**17. Default columns (new, 2026-11-25).**

**Decided (maintainer, 2026-11-25)**: default columns are the final round and the rounds before it
on the "Main" track (the same lineage track as decision 2), latest first. Side feeders, add-ons and
regional rounds are behind "Show all rounds".

Without track data, the path is guessed like decision 2's `F`: through the previous round that gave
the next one the most files, with the same "guessed" note (§2.3).

**Refined (2026-11-25, after the lineage track check)**: the automatic Main track can stop at a merge
(WLM 2026: Q-02 alone). So the default columns step back from the final round one previous round at
a time, taking the previous round on the Main track if there is one, else the one that supplied the
most files to the current round, and stop at a root. Each "most files" step is marked guessed in
decision 2's header note, which suggests labelling the Main track. WLM 2026 gives Q-02 → Q-01-nf →
Q-01 → Q-00 with or without labels; WLE 2026 gives 1952 → 1942 → 1938 → 1920, the last step guessed
(§2.3).

---

## 9. What the code can't tell

- **WLE 2026's rounds:**
  - their `previous` links and leave-outs;
  - whether round 3 was a top-N or a minimum score;
  - which regions fell out;
  - any steps done by hand.

  Phase 0 answers these on the rehearsal copy. Answered in §7.4 (updated 2026-11-25).
- **WLE Ukraine's real prefixes:** the code says KOATUU (`RegionScheme.scala:11-13`), and phase 0
  verifies it. Verified: 27 two-digit KOATUU prefixes (§7.4) (updated 2026-11-25).
- **The "small root" threshold** of §2.4 item 5: about 10%, from phase 0 (§7.4, §7.5)
  (updated 2026-11-25).
- **How many winners per region** each contest awards, whether a national winner may also win a
  region (decision 14), and the Commons results page's format.
- **When `roundImage.read` turns on** (the P2b pilot), and so when the counts path matters.
