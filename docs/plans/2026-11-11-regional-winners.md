<!-- Recovered 2026-11-25 after its file was lost: plan v2 as last edited on 2026-11-11.
     Changes made on recovery are marked "(updated 2026-11-25)". -->

# Regional winners report (plan v2)

**Status:** plan only. Nothing is built. Date: 2026-11-11.

**Status (updated 2026-11-25):** recovered 2026-11-25 after its file was lost; decisions 1–3 and
6–14 decided; 4, 5, 15–16 open; phase 0 done for WLE 2026, rerun for WLM 2026 below (§7.4, §7.5).
The maintainer made this the top priority for WLM 2026 Ukraine, whose jury final is at the
beginning of December.

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

---

## Short answer

- **One read-only page per contest:** `/contest/:id/regional-winners`, for the contest's Admins and
  Organizers (and root). It shows one row per region of the contest's country. Each row has:
  - the region's best photos;
  - the round each comes from, with "filled from round X" on files taken from a later candidate
    when the source has fewer than N (updated 2026-11-25, decision 9);
  - why that round was used: "From round 2 (R-02): round 3 has no files of this region."
- **The rule:**
  - **The default `F`** is the deepest round of the contest's "Main" track (round lineage's computed
    track rule plus the organizer's manual label, lineage decision 4) (updated 2026-11-25, decision
    2). Until a contest has track data, it is the deepest round that is not any of these: a
    nomination round, a regional round, a leave-out round, or a review of rejected files; the page
    then says that it guessed. When several rounds tie at that depth, the page names them and asks
    the organizer to pick one.
  - **For each region**, the candidates are tried in a fixed order:
    1. the region's regional rounds, newest first;
    2. the chain rounds, deepest first;
    3. the add-ons, each placed next to the chain round it came from.

    The source is the first candidate with a file of the region that has a **positive result**: a
    "yes", or a grade sum above 0.

    If the source has fewer than N such files, the region is filled up from the next candidates in
    the same order until N (updated 2026-11-25, decision 9).
- **"Best" uses each round's own terms.**
  - **Ranking:** files are ranked as the round's all-jurors "by rate" gallery ranks them
    (`ImageWithRating.RankKey.of`, `ImageWithRating.scala:262-277`).
  - **Yes/no rounds:** they use the order the next chain round stored for its top-N, else the
    default order.
  - **Scales:** rounds with different scales are never compared. In a filled region, the files of
    a later round always come before those of an earlier one (updated 2026-11-25, decision 9).
- **No region can go missing quietly.**
  - These are always shown:
    - a region with no source;
    - a region with no files in the contest;
    - the round-by-round trail for each region.
  - For a region with no source, the page names the unlinked rounds that hold its files (e.g. 2025's
    copies made by hand).
  - When the chain starts much smaller than the contest, the header warns that steps may be missing.
- **Reads:**
  - **Normal path:** the votes are read, as the all-jurors lists do while `roundImage.read` is off
    (the default, `conf/application.conf:433-443`). This is one GROUP BY of a round's votes per
    candidate round, read in order and stopping once every region has a source.
  - **Exact consumers** (the wiki and CSV exports (updated 2026-11-25, decision 11)) always read the votes.
  - **`round_image` counts** are used only when `roundImage.read` is on and the clean-generation
    proof holds.
  - There is no new table and no migration.
- **Builds on round lineage's track core** (updated 2026-11-25, decision 2). It needs the graph core
  (ancestors and depth) that the lineage plan (§1.3) assigns to a shared `services.RoundLineage`,
  plus the track computation for "Main" and the manual label (`round.track`, lineage decision 4).
  That is the core shared with lineage's minimum slice (A): tracks with the manual label, the chip
  and the Track menu, about 3–4 days. Order of work: lineage's track part first, or together with
  this plan's phase 1. The track label's migration is lineage's (number: next free at merge).
- **No migration of its own** (updated 2026-11-25). Production is at V113, so it already has
  `round_image` (V84), the counts (V89), `average_rule` (V90), `exclude_rounds` (V95) and
  `round.status` (V105). V84's backfill rebuilt `round_image` from the votes, so the report also
  works for WLE 2026 after the fact.
- **Size:** about 4.5–6.5 agent days.
  - **Phase 0** (S) checks the rule on WLE Ukraine 2026, locally, on the 26.11 rehearsal copy.
    Done, and rerun for WLM 2026 Ukraine on a production copy of 2026-11-25 (§7.4, §7.5)
    (updated 2026-11-25).
  - **Phase 1** (M–L) is the page. It builds on lineage's slice A (3–4 days, if not landed)
    (updated 2026-11-25, decision 2).
  - **Phase 2** (S–M) is the export, as wiki text and as CSV (updated 2026-11-25, decision 11).
  - **Phase 3** (S) comes after lineage phase 1 and multi-role's pre-screening rounds
    (migration numbers: next free at merge). `round.status` (V105) is in production already, so
    phase 1 reads it (§2.1) (updated 2026-11-25).

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
| Comments only (`commentsOnlyRound`, `Round.scala:317`) | none | — | not a candidate |

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

## 2. The rule

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
     (updated 2026-11-25, decision 2).
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

  Round 3 then drops out of the chain, and every region falls back to round 2. Organizers are likely
  to create exactly such a round in response to this report.
- **The fix.** Such rounds never become the default `F` (item 3). They are add-ons (item 6), placed
  in the candidate order next to the round they came from (§2.3). A tie between plain rounds is
  warned about, not guessed. Decision 3 asks about this.

### 2.3 The candidate order for a region `x`

1. Regional rounds of `G` whose filter includes `x`, **newest first** (largest id). A backtrack is
   newer but shallower: K-02 from Q-01 is meant to replace K-01 from M-02 (lineage §4).
2. The chain `A`, **deepest first**, then the largest id. The add-ons `B` are inserted into this
   list. Each add-on's anchor `P` is its previous round in `A` that comes first in the list (the
   deepest one).
   - **A leave-out add-on goes directly above `P`.** It is the organizers' newer decision on `P`'s
     files that the left-out round didn't take. So it ranks below the left-out round (round 3), and
     a region present there keeps round 3. For a region missing from round 3, it ranks above round 2
     itself.
   - **Exception: an add-on whose anchor is `F` itself** (e.g. "round 3's files not in the national
     final") goes directly **below** `F` until decision 14 is answered. Placed above `F`, it would
     outrank `F` for every region, which would quietly settle decision 14 (national winners left
     out of the regions).
   - **A rejected-review add-on goes directly below `P`.** Its files were rejected in `P`, so they
     matter only when `P` has no positive file of `x`.
   - Several add-ons at the same place go newest first.
3. **Explicit list:** the same order, by kind.

### 2.4 The source of a region, and the reasons

1. **Source:** the first candidate in which `x` has at least one **eligible** file (§2.5, not
   excluded under decisions 12–13 (updated 2026-11-25, decisions 12–13)) with a **positive result**
   (decision 4):
   - yes/no: `approvals > 0`;
   - rated and criteria: grade sum > 0 (`rate_sum + rejections`, `RoundImageQuery.GradeSum`, `:189`).
2. **Every candidate above the source gets its reason:**
   - "round 3 has no files of this region";
   - "round 3 has 4 files of this region, none with a result yet" (no votes cast);
   - "round 3 has 4 files of this region, none selected" (yes/no) or "… only ✗ marks" (rated: a
     grade sum of 0 with votes cast means only ✗ marks).

   Rounds in `X` never appear here (§2.1 item 1).
3. **Unlinked rounds (review point 2).** For each region without a source, one statement counts its
   files in the contest's *other* rounds, i.e. not in `X` and not candidates (§4.2). Example row: "No
   round of the chain has a file of this region with a result. Files of this region are in K-07
   (12 files) and Обл-02 (4), which aren't linked to the chain. Add them with 'rounds' if they belong
   to it." Up to 3 rounds are named, newest first, with a "+k more" count.
4. **No files anywhere** (no source and no unlinked round):
   - "The contest has 7 files of this region; none reached round 1 (Q-00)", using the cached
     category counts;
   - or "The contest has no files of this region."
5. **A chain that looks broken** (header warning). When the largest root of `A` (a round of `A`
   without a previous round in `A`) has under about 10% of the contest's files, the header says
   the following. (Updated 2026-11-25: "half" was the v2 guess. Phase 0 on WLE 2026 showed that it
   flags a main root when the files are split over parallel first rounds, 44% and 56%, while about
   10% separates the real side roots, 0.3% and 2.1%; §7.4.)

   > The chain starts at R-01 with 2,100 files; the contest has 38,000. Earlier steps may have been
   > done outside the app.

   The numbers come from `RoundImageCounts` (cached) and the category count (cached).
6. **Filling up to N** (updated 2026-11-25, decision 9). If the source has fewer than N
   positive files of `x`, the region is filled up:
   - after the source's positive files, walk the region's candidate order (§2.3, the same order the
     source was chosen from) below the source;
   - from each candidate, append its positive files of `x` that are not already shown, in that
     round's own rank order, until N files are shown;
   - the round where N is reached is the **last round used**. Decision 8 applies there: every file
     tied with the N-th file in that round is shown too;
   - if the candidates run out first, the row shows the files found, with "only 2 files with a
     result in the chain";
   - the trail (item 2) runs down to the last round used, naming the files each round added.

   With the default N = 1 (decision 7), the source always has enough, so filling happens only with
   `n` ≥ 2.

   **Open point (not decided):** a filled file may have been judged and not selected in a later
   round of the chain (e.g. rejected in round 3, positive in round 2). Phase 0 on WLM 2026 shows this
   is the usual case (§7.5). This plan shows such a file with a "not selected in round 3" note and
   counts it in N; skipping it instead is the alternative to ask the maintainer.
7. **Open rounds** (decision 6):
   - an active round, or one with unrated votes for `x`, is a valid source, with a "provisional"
     badge and the votes still to cast;
   - a round with files of `x` but no positive result yet doesn't count as present.
   - (updated 2026-11-25) "Active" is `status = active`. A `paused` round with `distributing = 0` is
     stopped; `finalized`, once written, never shows "provisional".

Every order is fixed: rounds by kind, depth and id; files by the rank key, then page id
(`BinaryOrder.scala:5-6`).

### 2.5 Best photos of the source round, and settled points

**Ineligible files are excluded first** (updated 2026-11-25, decisions 12–13). A file whose author
has a conflict of interest (decision 12) or that is deleted on Commons (decision 13) is not among
the files a winner is chosen from. It is removed from every candidate round's files before anything
else, so:
- the source test (§2.4 item 1), the ranks, the tie at the N-th place (decision 8), the fill-up
  (decision 9) and the national top k (decision 14) all count eligible files only;
- the next eligible file takes its place;
- it is not shown on the page and never in the export.

The row's "Why" gives the count only ("2 files excluded: conflict of interest or deleted on
Commons"), so a region never loses a file without a trace.

**Ranking:** the eligible files of `x` in round `R` with a positive result, by `RankKey.of(R, order)`.
- **The order of a yes/no `R` is settled** (review point 7). It is the stored `top_binary_order` of
  the round that took its top-N from `R`:
  - the next chain round that has `R` among its previous rounds;
  - else any round that has `R` among its previous rounds, the newest first.

  It falls back to `BinaryOrder.Default` when none stored one, or `NULL` (legacy). The page names
  the order used.
- **The rank is within the region.** A parity spec checks that it equals the rank in the round's
  all-jurors "by rate" gallery with that region and order, when no file of the region is excluded.
  With exclusions, the rank is among the eligible files (updated 2026-11-25, decisions 12–13).

**N per region:** the `n` parameter, 1–10, default 1 (updated 2026-11-25, decision 7). **Ties at
the N-th place:** all tied files are shown (updated 2026-11-25, decision 8), with a link to the
region's "by rate" gallery (`/byrate/round/:r/user/0/region/:region/page/1`, `conf/routes:65`).
Ranks are written as ranges (decision 8).

**Filled files** (updated 2026-11-25, decision 9):
- **Order:** files of a later round always come before files of an earlier one; within a round, by
  that round's rank key. Scores of different rounds, and of a yes/no and a rated round, are never
  compared.
- **Labels:** each file shows its round and its rank within that round. A file from a round other
  than the source also shows "filled from round X".
- **Badges:** the national-winner mark (decision 14) and "provisional" (decision 6) apply to filled
  files as to the source's. Files excluded under decisions 12–13 are left out of the fill-up too,
  before ranking (updated 2026-11-25, decisions 12–13). A national winner skipped under decision
  14's per-view option makes the walk go on to the next file.

**Score in the round's own terms:**
- yes/no: "3 of 3 jurors: yes", plus the rejections;
- rated: `rateText(R)` with its scale ("of 10");
- criteria: `criteriaRateString(R)`.

The score rule is named as on the round form.

**Files that may not be eligible:**
- **CoI files** (decision 12): the contest's CoI people come from `CoiMembers.forContest`
  (`CoiMembers.scala:66`). Authors are stored normalized as its keys are (`:97`), so the queries
  exclude them before ranking with `i.author NOT IN (<CoI accounts>)` (updated 2026-11-25,
  decisions 12–13).
- **Files deleted on Commons** (decision 13): excluded the same way, with `i.deleted_at IS NULL`
  (updated 2026-11-25, decisions 12–13).
- **The national winners** (decision 14): the final round's overall top among its eligible files,
  when asked for (updated 2026-11-25, decisions 12–13).

**Settled, not open** (review point 7):
- **Pre-screening rounds are never candidates.** Multi-role decision 2 makes a pre-screening round a
  yes/no "may go on" screen, and decision 3 gives its results only through Admin or Organizer. Until
  multi-role's migration (next free at merge) every round is a jury round (updated 2026-11-25).
- **Access is Admins and Organizers of the contest, and root.** This is `canViewOrgInfo` and
  multi-role decision 3 (§3.1). Jurors never get the page.

---

## 3. The page

### 3.1 Route and access

- **Route:**
  `GET /contest/:contestId/regional-winners` → `RegionalWinnersController.page(contestId: Long, round: Option[Long] ?= None, rounds: Option[String] ?= None, n: Int ?= 1, national: Int ?= 0, format: String ?= "html")`
  (`n` default 1: updated 2026-11-25, decision 7).
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
  - `format` is `html`, `wiki` or `csv` (updated 2026-11-25, decision 11).
- **Contest switch:** it is a page GET, so it switches the session's contest
  (`Secured.switchingContest`, `Secured.scala:889-902`).
- **Read-only.** No POST, no stored state.
- **Linked from:**
  - the round stats pager (`roundStat.scala.html`) and the rounds page (admins);
  - later, the lineage map's regions table.

### 3.2 Layout

Server-rendered Twirl with Bootstrap. There is no script, no inline style, and nothing from a CDN
(UI practices). The table rules are those of the lineage map (§2.2 there): the table scrolls in its
own box, with captions and row headers.

1. **Header:**
   - "Chain: R-01 → R-02 → R-03 (final, chosen automatically)";
   - the add-ons and regional rounds, with their anchors;
   - the warnings (§2.1 item 3, §2.4 item 5);
   - dropped or missing rounds;
   - a GET form with the final-round select, `rounds`, N and `national`.
2. **Summary:** "27 regions: 21 from round 3, 4 from round 2 (fallback), 1 with files only in
   unlinked rounds, 1 with no files". With `n` ≥ 2 it adds "3 filled from earlier rounds"
   (updated 2026-11-25, decision 9). The parts are in-page anchors.
3. **The table, one row per region,** in `RegionScheme.regions` order. Its columns:
   - **Region:** the name, the contest's file count, and "fallback" and "filled" badges
     (updated 2026-11-25, decision 9).
   - **Best photos:** thumbnails (`Global.resizeTo`, `app/controllers/Global.scala:58`). Each has its
     rank, title, score and badges (provisional, national winner; no CoI or deleted badge:
     those files are excluded, updated 2026-11-25, decisions 12–13), and links to the
     large view (`conf/routes:97`). Each also names its round, and a filled one says "filled from
     round X" (updated 2026-11-25, decision 9).
   - **Source round** (and the fill rounds, if any; updated 2026-11-25, decision 9): number and
     name (`Round.description`, `Round.scala:114-117`), rating and scale, "2 of 3 in the chain" /
     "regional" / "add-on to R-02", and links to its stats and its region gallery.
   - **Why:** the reasons (§2.4) and the trail, down to the source (with filling, down to the last
     round used; updated 2026-11-25, decision 9): "R-03: 0 files · R-02: 12 files,
     5 selected".
4. **Regions with no files in the contest,** in a collapsed group (`<details>`). The groups of
   nomination prefixes, Other prefixes and files without a monument id are removed: the page covers
   geographic regions only (updated 2026-11-25, decision 10).
5. **Filters:**
   - "only fallbacks, filled regions and regions without a source" (updated 2026-11-25, decision 9);
   - "hide regions with no files".

   Both are GET flags. The default shows every region.

**The region list** is every geographic region of the contest's country (`RegionScheme.of(contest)`;
the 27 KOATUU ids for Ukraine), and nothing else: other ids that a candidate round or the contest's
files have are not shown (updated 2026-11-25, decision 10). The regions without files get `Some(0)`
in `RegionScheme.regions`.

**A contest without regions** has no monument-id template, or has many countries. The page shows a
notice only.

### 3.3 Export (phase 2)

**Wiki text** (`format=wiki`, decision 11): a read-only, Twirl-escaped `<textarea>` to copy, with
one block per region:

```
== Kyiv ==
''Round 2 (R-02); round 3 has no files of this region.''
<gallery>
File:Example.jpg|1. 8.50 (17 / 2 jurors)
</gallery>
```

- **An exact consumer:** it always reads the votes, whatever `roundImage.read` is
  (`RoundImageQuery.scala:28-29`).
- **Captions** are numbers and message text only, with no author wikitext. File titles can't contain
  `|`, `[`, `]`, `{` or `}`.
- **Not listed:** files that may not be eligible (decisions 12–14) are left out of the export.
  CoI files and files deleted on Commons are never in it, as they are excluded before ranking
  (updated 2026-11-25, decisions 12–13).
- **Filled files** name their round in the caption ("filled from round 2") (updated 2026-11-25,
  decision 9).

**CSV** (`format=csv`, decision 11; part of phase 2 (updated 2026-11-25, decision 11)):
- **Columns** follow the page rows: region, place, file, round, score or result, national-winner
  mark. One row per file shown; a filled file's round says "filled from round X".
- **Not listed:** CoI files and files deleted on Commons are never included (decisions 12–13).
- **An exact consumer:** it always reads the votes, as the wiki text does.
- **Path:** through `controllers.Csv` and `GalleryController.csvStream`, the only CSV path, which
  needs an `ExportsSpec` change (`ExportsSpec.scala:5-20`) in phase 2.

---

## 4. Queries and cost

### 4.1 Per candidate round: the region summary

**Votes path** (the normal one; the shape of `ImageDbNew.scala:467-487`):

```sql
SELECT <regionOf(i.monument_id)> AS region, COUNT(*) AS files,
       SUM(<positive>) AS positive, SUM(p.assigned - p.voted) AS unrated
FROM (SELECT s.page_id, COUNT(*) AS assigned, SUM(s.rate <> 0) AS voted,
             SUM(s.rate > 0) AS approvals, SUM(s.rate < 0) AS rejections,
             SUM(GREATEST(s.rate, 0)) AS grade_sum
      FROM vote s WHERE s.round_id = ? GROUP BY s.page_id) p
JOIN images i FORCE INDEX (images_page_monument_idx) ON i.page_id = p.page_id
GROUP BY region ORDER BY NULL
```

- `<positive>` is `p.approvals > 0` for yes/no, and `p.grade_sum > 0` for rated.
- **Eligible files only** (updated 2026-11-25, decisions 12–13): the join on `images` adds
  `AND i.deleted_at IS NULL AND (i.author IS NULL OR i.author NOT IN (<CoI accounts>))`, and the
  excluded files are counted per region for the "Why" note.
- **Geographic regions only** (updated 2026-11-25, decision 10): rows of other prefixes and of files
  without an id are dropped (`region IN (<the country's regions>)`).
- `vote` holds only `in` files, because `dq` and `deleted` votes are in `vote_aside`.
- **Counts path:** used only with `roundImage.read = true` and the proof of §4.3. It is the same
  aggregate over `round_image` with the dirty overlay, filtered on `status = 'in'` and grouped by
  `ri.region`.
- **Code:** `SelectionQuery.regionSummary()` and `RoundImageQuery.regionSummary()`, both in files
  `RawSqlSpec` already allows.

### 4.2 For regions without a source: unlinked rounds

```sql
SELECT ri.round_id, ri.region, COUNT(*) FROM round_image ri
WHERE ri.round_id IN (<other rounds>) AND ri.status = 'in' AND ri.region IN (<regions without a source>)
GROUP BY ri.round_id, ri.region
```

- It runs once, only when a region has no source.
- It reads the other rounds' primary-key ranges `(round_id, page_id)`.
- `round_image`'s membership and region are kept in the vote's transaction (V84 comment), so they
  are exact whatever `roundImage.read` is. Only the counts are background-kept.

### 4.3 Per source round: the top N of its regions (and fill rounds, updated 2026-11-25, decision 9)

- **The query:** a window over the same per-file aggregate (votes path, or counts path under the
  proof). It computes `RANK()` over `PARTITION BY region ORDER BY <rank key>`. All tied files are
  shown, so the `ROW_NUMBER()` cap and the tie count are not needed (updated 2026-11-25, decision 8).
  - It is limited to the regions this round is the source or a fill round for (updated
    2026-11-25, decision 9).
  - It filters `<positive> AND rk <= N`, which keeps every file tied at the N-th place
    (updated 2026-11-25, decision 8).
  - **Fill rounds** (updated 2026-11-25, decision 9): the ranks stay the round's own, over all its
    positive files of `x`. The filter is `rk <= k + s`, where `k` is the number of files still needed and `s` the
    number of already shown files of `x` that this round holds. The rule (Scala) then drops the
    shown files and cuts at `k`, keeping the tie at the cut.
- **Eligible files only,** with the same condition as §4.1, before the window, so ranks and ties
  are among eligible files (updated 2026-11-25, decisions 12–13).
- **Rank key columns:** `RankKey.of(round, order).columns`, translated with
  `RoundImageQuery.translate` (`:213`) on the counts path. A file with no positive result ranks
  below one with it, so filtering after ranking leaves the ranks unchanged.
- **Rows** are mapped to grouped `ImageWithRating`, so `rateText`, `criteriaRateString` and `Ranks`
  work unchanged.
- MariaDB 10.6+ has window functions, and the code already uses them (`RoundImageQuery.scala:160`,
  `ImageDbNew.scala:130-131`).
- **The proof (counts path only):** a `fromCounts`-style check per round, the same as
  `RoundList.fromCounts`, reused or factored out. If it fails, the votes are read.

### 4.4 Cost *(inf.; the benchmark decides)*

- **Lazy, in candidate order** (review point 4):
  - summaries are read round by round in the order of §2.3, newest regional rounds and the deepest
    chain round first;
  - each round resolves the regions it has a positive file for;
  - reading stops when every region has a source and N files, or the candidates run out
    (updated 2026-11-25, decision 9);
  - a region keeps its trail only down to its last round used.
- **Typical case:** 1–2 summaries, because the final round covers most regions and the previous
  round the rest.
- **Worst case:** all of `A ∪ G ∪ B`.
  - **WLE:** 3 rounds *(inf.)*.
  - **WLM:** more than v1's "8 + 1". Future contests have the main spine, its merge feeders
    (quality, quantity; lineage §10.3) and regional rounds, so about 10–15 rounds *(inf.)*.
  - Each summary is one GROUP BY of the round's votes: about 38k files × 3 jurors for an early
    round, far less later *(inf.)*.
- **Header numbers:** file totals for the chain's rounds come from the cached `RoundImageCounts`, so
  rounds below the sources are not scanned.
- **Top N:** one window statement per source or fill round, usually 1–3. With `n` ≥ 2, a region
  with few files can reach back to a root (§7.5: region 18 of WLM 2026 at `n=3`), so up to all of
  `A ∪ G ∪ B` (updated 2026-11-25, decision 9).
- **Unlinked rounds:** one statement, only when needed.
- **Thumbnails:** 27 × N plus the files tied at the N-th place, with no cap (updated 2026-11-25,
  decision 8), through the existing proxy and cache.
- **Nothing else changes:** no new query on the voting path, the galleries or the large view.
- **Caching:** none in phase 1. If needed, a stopped round's summary can be cached later by
  `(round_id, counts_gen)` or by the round's last vote time.

---

## 5. Edge cases

| Case | Behaviour |
|---|---|
| A file "in several regions" | Not possible: one monument id per file (§1.1) |
| Kyiv city (`80`) and Kyiv oblast (`32`) | Separate regions. Sub-region ids (`80-361`) in a filter count as their top-level region |
| A region in round 3 with only rejected files | Not present (decision 4). The reason says "none selected", and the report falls back |
| A region in round 3 with files not yet voted | Not present until it has a positive result. "Provisional" on the source (decision 6) |
| A region in round 3 with 1 positive file, N = 3 | Round 3's file, then 2 filled from the next candidates, each labelled "filled from round X" (decision 9; updated 2026-11-25, decision 9) |
| A filled candidate holds a file already shown from a later round | Shown once, from the later round (updated 2026-11-25, decision 9) |
| A filled file was not selected in a later round | Shown with a "not selected in round 3" note; open point (§2.4 item 6; updated 2026-11-25, decision 9) |
| Fewer than N positive files in the whole candidate order | The files found, with "only k files with a result in the chain" (updated 2026-11-25, decision 9) |
| A "round 2 not in round 3" round, created after this report | An add-on above round 2, below round 3 (§2.3). It never becomes `F` (§2.2) |
| A review round of round 2's rejected files | An add-on below round 2 |
| A trial round at round 3's depth (until lineage's Cancel; updated 2026-11-25) | Named in the ambiguity warning; `F` is the larger id, and the organizer can pick the other |
| A regional round created in the app | In `G`; it wins for its regions (decision 5) |
| A regional round copied by hand (2025, no edge) | Named under "unlinked rounds" for its regions (§2.4 item 3). It can be added with `rounds=` |
| WLE rounds without `previous` | The chain is just `F`. Missing regions name the unlinked rounds, and the header warns about the small root (§2.4 item 5) |
| A chain round with a filter of all regions | Not regional (lineage rule 4) |
| Rounds with different scales | Each file is shown on its own round's scale; files of a later round come first; scores of different rounds are never compared (updated 2026-11-25, decision 9) |
| A merge | All feeders are in `A`, below the merge |
| Score rule changed after the announcement | The current rule is shown and named |
| Stopped jurors | Their votes count (§1.3) |
| Disqualified files, and files deleted while their round was open | Out |
| Deleted on Commons after the round | Excluded before ranking; the next eligible file takes its place; counted in the "Why" note; never exported (decision 13; updated 2026-11-25, decisions 12–13) |
| A conflict of interest | Excluded before ranking; the next eligible file takes its place; counted in the "Why" note; never exported (decision 12; updated 2026-11-25, decisions 12–13) |
| A region whose only positive files in round 3 are excluded | Round 3 doesn't count as present; the region falls back (updated 2026-11-25, decisions 12–13) |
| A national winner that is also the region's best | Decision 14 |
| A winner chosen outside the app | Not modelled; facts only (decision 16) |
| WLM nomination prefixes, Other prefixes, files without an id | Not shown (decision 10; updated 2026-11-25, decision 10) |
| A cycle | Reported; no ranking |
| A ghost id | Named in the header |
| A contest without regions | Notice only |

---

## 6. Relation to other plans

| Plan | How this fits |
|---|---|
| Round lineage (`2026-11-10-round-lineage.md`) | **Graph core.** Uses the shared `services.RoundLineage` core (§1.3): graph, Kahn order, ancestors, depth. **Track core** (updated 2026-11-25, decision 2): also the "Main" track computation and the manual label (`round.track`), shared with lineage's minimum slice A (track label, chip, Track menu; about 3–4 days), built first or together with phase 1. **Chain.** "Chain" here is `F` and its ancestors, not a track; only the default `F` reads the track label. **Kinds.** Rule 4 is reused for "regional", and §4's leave-out steps become add-ons. **Status.** V105's `cancelled`, `draft` and in-review `paused` join `X` (updated 2026-11-25). **No storage** (decision 15 there). **Back-reference.** The lineage plan's §6 table should get a row for this report, linking its regions table and backtracking (§4 there) to this page. Not edited here |
| Lineage §4 backtracking | The report shows which regions need a backtrack. The round is created through the normal form, and the report then uses it (§2.3) |
| Multi-role (migration: next free at merge; updated 2026-11-25) | Pre-screening rounds are never candidates. Pure pre-screeners get 403 (decisions 2 and 3 there) |
| Combined juror votes | The report is never shown to jurors |
| Round images P2b/P3 | The counts path turns on with `roundImage.read`. DQ is already out. `finalized` can drop the "provisional" badge |
| Per-region top-N for the next round | Not here. It is the preventive fix, and could be a later round-form option *(inf.)* |

---

## 7. Phases

S ≈ ½ day, M ≈ 1–2 days, L ≈ 3+ days of agent work, each with review and targeted specs.

| Phase | Content | Size |
|---|---|---|
| 0 | **WLE Ukraine 2026 check, local** (§7.1). On the 26.11 rehearsal copy of production, migrated to V95, with the read-only SQL below. A local script (`python -I`) applies §2. It shows the chain, the add-ons, the ambiguity, the regions that fell back, the unlinked rounds, and whether WLE's `previous` links are recorded. It also verifies WLE's region prefixes and tunes the "small root" threshold. It is phase 1's acceptance case, and it also serves lineage decision 3. Done for WLE 2026 (§7.4) and WLM 2026 (§7.5) (updated 2026-11-25) | S |
| 1 | **The page.** On lineage's slice A, built first or together (the `RoundLineage` core with the Main track and the manual label, about 3–4 d, if not landed; it replaces v2's ½–1 d core estimate; updated 2026-11-25, decision 2). `RegionalWinners`: the pure rule of §2, unit-tested (1 d). Region summaries, top N and unlinked rounds, on the votes path, plus the counts path behind `roundImage.read` with its proof (1–1.5 d). Controller, route, view, messages in en/uk/ru/fr, links, screenshots in both themes (1–1.5 d) | M–L (3.5–4.5 d) |
| 2 | **Wiki text** (S) and **CSV** (S), both from the start, with the `ExportsSpec` change (decision 11; updated 2026-11-25, decision 11) | S–M (½–1 d) |
| 3 | **After lineage phase 1 and multi-role's pre-screening rounds:** pre-screening rounds join `X`; a link from the lineage map. The default `F` from the "Main" track moves to phase 1 (updated 2026-11-25, decision 2). V105's statuses are read in phase 1 already (§2.1) (updated 2026-11-25) | S (½ d) |

**Total:** about 4.5–6.5 agent days. (updated 2026-11-25, decision 2) The ½–1 d core moves into
lineage's slice A (3–4 d), so with slice A not landed yet, about 6.5–10 days in all.

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

### 7.2 Tests (phase 1)

- **`RegionalWinnersSpec`** (unit, no DB):
  - a 3-round chain where B is missing in round 3 → round 2, with the reason;
  - only rejected files in round 3 → fallback;
  - an open round with no votes;
  - **side branches** (review point 1):
    - a "round 2 not in round 3" round with a larger id than round 3 → `F` stays round 3, and the
      add-on serves only the regions missing from round 3;
    - a rejected review → below round 2;
    - a trial round at the same depth → warned and named;
  - a regional round, and a regional backtrack with a smaller depth but a larger id, wins;
  - a merge;
  - a nomination root is excluded from the default `F`;
  - the default `F` is the Main track's deepest round, by the track rule and by the manual label;
    without track data, the deepest plain round with the "guessed" note (updated 2026-11-25,
    decision 2);
  - a listed round in `X` is dropped and named;
  - a cycle, and a ghost id;
  - no source with unlinked rounds named, no files at all, and the small-root warning;
  - ties at N: all tied files are shown (updated 2026-11-25, decision 8);
  - **filling** (updated 2026-11-25, decision 9):
    - 1 positive file in round 3 at N = 3 → 2 more from round 2, labelled "filled from round 2";
    - a file shown from round 3 is not repeated from round 2;
    - a tie at the cut in the last round used → all tied files of that round shown;
    - a yes/no round's files after a rated round's, never interleaved by score;
    - the candidates run out before N → the files found, with the note;
    - a national winner among the filled files gets its mark; a CoI file and a deleted file are
      excluded from the fill-up (updated 2026-11-25, decisions 12–13);
    - a filled file not selected in a later round gets the note;
    - WLM 2026 phase 0's region 18 (1 file in Q-02 at `n=3`) fills 2 files from Qn-00 (§7.5);
  - the stored `top_binary_order` is used, else the default;
  - **exclusions** (updated 2026-11-25, decisions 12–13): a CoI file and a deleted file are not
    shown and the next eligible file takes their place; a region whose only positive files in round
    3 are excluded falls back; a tie at the N-th place counts eligible files only; the national top
    k skips them; the "Why" note gives the count;
  - only geographic regions are rows; nomination prefixes, Other and files without an id are not
    shown (updated 2026-11-25, decision 10).
- **`RegionalBestQuerySpec`** (DB, truncate isolation):
  - parity with `RoundList` per region ("by rate", all jurors) for yes/no under each order, rated
    under `rated`, `assigned` and `sum`, criteria, and ✗;
  - `dq` and `deleted` files are out, and stopped jurors are counted;
  - CoI files and files with `images.deleted_at` are excluded before ranking, with their count per
    region (updated 2026-11-25, decisions 12–13);
  - both read paths give the same rows (votes; counts with a clean generation), and a dirty
    generation falls back;
  - the unlinked-rounds query;
  - the fill query: the round's own ranks, the `rk <= k + s` bound and the cut with ties
    (updated 2026-11-25, decision 9).
- **`RegionalWinnersPageSpec`** (controller):
  - admin 200, organizer 200, root 200, and 404 for a missing contest;
  - negative: a juror of the contest, a non-member, an admin of another contest and a pre-screener
    (after multi-role's migration; updated 2026-11-25) each get 403;
  - foreign `round` and `rounds` ids are dropped, never `.get`;
  - `n` and `national` are clamped;
  - no per-juror names or votes on the page;
  - the CSV: its columns, one row per file shown, the filled files' rounds, and no CoI or deleted
    file (updated 2026-11-25, decision 11);
  - no CoI file, deleted file or non-geographic region on the page or in the export
    (updated 2026-11-25, decisions 12–13).
- **Guards:** `RouteAuthSpec` and `SignedOutSpec` cover the route. `RawSqlSpec` is unchanged if the
  SQL stays in `ImageDbNew.scala` / `RoundImageQuery.scala`. `ExportsSpec` changes in phase 2, for the CSV
  (updated 2026-11-25, decision 11).
- **The browser story:** from the stats pager to a fallback region, then to the large view.

### 7.3 Benchmark note

- **The main path is the votes** (`roundImage.read` off). The counts path is measured only once the
  P2b pilot turns it on.
- **Fixture:** the Gatling fixture (38k images, 20 jurors, a yes/no and a rated round). The run
  creates a third round through the round form (a top-N of the rated round, so that regions drop out)
  instead of changing `GatlingDbSetup.load`, which would need a `FixtureVersion` bump *(inf.)*.
- **What is measured:** the report with every region resolved by the final round, and with a
  fallback to round 2, in absolute milliseconds and with the run variation. SQL debug logging is off.
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
  - Regions 23, 56 and 59 have files in 1938 but no yes there, so their source is a root (23 and 59:
    1920 only; 56: both).
- **"Positive":** in the rated rounds 1942 and 1952, every file is positive, so the top N ranks by
  rate. In 1938, whole regions have no positive file, so decision 4's (a) and (b) differ.
- **Fewer than N = 3** files in 1952: 11 of its 16 regions.
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
  today; a jury-final round made from Q-02 would become `F`, with Q-02 as its fallback.
- **Side rounds (decision 3):** "[delete] Q-00-1", a trial copy (640 files, no votes, paused, depth
  1), can't be cancelled under V105 and is harmless outside the chain. The leave-out Q-00-1 feeds
  the merge, so it is a chain round, not an add-on. `G` and `B` are empty.
- **Regional rounds (decision 5):** none. Ten rounds filter on all 27 KOATUU codes (in two orders);
  only lineage rule 4, compared as a set, keeps them from counting as regional.
- **Nomination round:** Kha-00 (`special_nomination` Харків; root; 2,920 files, all region 63;
  1,166 positive) is not an ancestor of Q-02 and drops out, as assumed.
- **Coverage:** all 27 regions have positive files in Q-02, so no region falls back today. Per
  region, 1 (18) to 55 (63) files; **3 regions have fewer than N = 3** (18: 1; 35, 85: 2).
  At `n=3`, region 18 fills from Qn-00: Q-02's feeders hold no other positive file of 18, and all
  43 of Qn-00's positive files of 18 got no yes in Qn-00-1, so every filled file is one a later
  round didn't select (§2.4 item 6's open point) (updated 2026-11-25, decision 9).
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

## 8. Open decisions

They will be asked one by one. (updated 2026-11-25) In decisions 2 and 3, "V99" means lineage's Cancel
of a distributed round: V105's status is in production, but it cancels only creations. Settled
in the plan, so not asked: pre-screening rounds are never
candidates, access is limited to the contest's Admins and Organizers, and a yes/no round's order
comes from the stored `top_binary_order` (§2.5).

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
tie of plain end rounds is warned about and named; a leave-out add-on off the final ranks below it.

**4. What "present in a round" means.**
- (a) At least one file with a positive result.
- (b) Any file.
- (c) A score threshold.

**Recommendation: (a).** Reasons:
- (b) hides "all rejected in round 3";
- (c) needs a threshold per scale.

**5. Regional rounds versus the chain.**
- (a) A regional round for X always wins for X; among them, the newest wins.
- (b) Lineage depth for all.

**Recommendation: (a).** Reasons:
- a regional round is the explicit step for that region;
- (b) picks the round a backtrack replaced.

**6. Open rounds.**
- (a) Valid sources, with a "provisional" badge.
- (b) Only stopped rounds count.

**Recommendation: (a).** Reasons:
- the report is most useful while round 3 runs;
- (b) points at round 2 while round 3 still decides.

**Decided (maintainer, 2026-11-25): (a)**: open rounds count, with a "provisional" badge.

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

**Decided (maintainer, 2026-11-25): (b)**: a region with fewer than N positive files in its source
round is filled up from earlier rounds in the chain until N, each file labelled with the round it
comes from (not the recommended "one source per region").

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
Regional winners phase 1 builds on it; lineage's track part comes first or together with it.*

**16. Hand-made decisions and overrides.**
- (a) No stored overrides; `rounds=` for chains done by hand.
- (b) A stored per-contest chain and N.
- (c) Stored "confirmed winner" marks.

**Recommendation: (a)** now. Reasons:
- (b) and (c) need a table, a migration, CSRF writes and an audit trail;
- revisit (b) if organizers bookmark long URLs.

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
