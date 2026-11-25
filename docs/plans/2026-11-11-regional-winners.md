<!-- Recovered 2026-11-25 after its file was lost: plan v2 as last edited on 2026-11-11.
     Changes made on recovery are marked "(updated 2026-11-25)". -->

# Regional winners report (plan v5: show, don't pick; record outcomes and re-jury)

**Status:** plan only. Nothing is built. Date: 2026-11-11.

**Status (v4, 2026-11-25):** the report (phases 1–3) is unchanged. v4 adds the re-jury loop (§10):
recorded regional decisions, an "unclear winner" mark, a cut picker (candidate cuts and a gallery
to choose N) shared by the round form's top-N and each region of a re-jury, and a re-jury round with its own top-N per region. Decisions
18, 19 and 21 are the maintainer's **[user]**; the rest of §10 is a proposal, with questions in §11.
**Status (v5, 2026-11-26): All questions decided (2026-11-26).** A clear region's winners are its top k (chosen per region when it is accepted,
default 1) in its region round, recorded by an Admin's "Accept region"; a flagged region gets one
plain line with the next step and goes to a re-jury. No per-file accept/reject, no pasted list,
no small-region rule.
**Priority:** the round improvements of 26.11.17 and 26.11.18 come first. 26.11.18 brings the
numbered setup steps that §10.4–§10.5 build on, and now also the cut picker for main rounds' top-N
(SN, question 14 **[user]**). Then this plan's work in the order of §10.8.

**Status (updated 2026-11-25):** recovered 2026-11-25 after its file was lost; all decisions made (4
and 9 replaced by "show, don't pick"); ready to build after lineage slice A. Order of work (v4:
after the round improvements of 26.11.17 and 26.11.18): lineage slice A → regional winners phase 1
→ phase 2 (exports) → phase 3. Phase 0 is done for WLE 2026 and WLM 2026 (§7.4, §7.5). The
maintainer made this the top priority for WLM 2026 Ukraine's jury final. (v4, 2026-11-25: dates
replaced by a priority order.)

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
- **v4, the re-jury loop** (the maintainer, 2026-11-25): "The report is fine, but in many cases it
  requires re-jurying when the winner is unclear, or deemed unworthy by an offline jury session.
  The path to select the top 10 fast for the international deadline does not rate each region with
  the same attention, as follow-up rounds' selection." And: "we don't have top-N for main rounds
  yet, maybe makes sense to have them too." New §10 (decisions record, unclear mark, cut picker,
  re-jury round), §11 (questions); decisions 18–19 added **[user]**, 20–23 proposed; decision 16
  superseded for the final step, 11 and 17 amended (dated notes); §6 and §7 gain rows. The report
  itself still picks nothing automatically.
- **v5, the maintainer's decisions of 2026-11-26** (questions 2–10, §11), applied to the short
  answer, §10 and §11, with two review points from #181 in §2–§5 and §7.2:
  - results come from the votes in the region round (the re-jury or the final round): a clear
    region's winner is its top-1 there, recorded by one Admin's "Accept region"; "no winner" and
    "needs re-jury" are the other outcomes. The per-file accept/reject record and the pasted list
    (R4) are dropped; the table becomes `regional_outcome`, one row per region (§10.2);
  - four plain cases flag a region, each with one line and the next step; "close scores" and "only
    yes/no votes" are dropped (§10.3);
  - a region's window for candidate cuts comes from past contests' data; the N control offers
    only valid values (§10.4). (A small-region rule `S`, written here first, was dropped the same
    day, below.);
  - one re-jury round as the goal, per-region rounds as the interim; the source round per region
    defaults to the round that decided it (§10.5);
  - an offline session votes as jury in a round an Admin creates; no Organizer or juror grant;
  - an explicit `rounds=` list without the automatic final round, and regions whose files are only
    in hidden columns (§2.1, §2.3, §2.4, §4.2);
  - a "Later" list (§10.10).
- **v5, follow-up decisions of 2026-11-26** **[user]**: question 11 (all jurors by default, no
  warning when changed); the round form's free top-N box is replaced by "Choose N" for main rounds
  and regions; **K winners per region** (default 1), stored as winner rows with places
  (§10.2–§10.3, §10.7); open points to confirm listed in §11.
- **v5, second follow-up of 2026-11-26** **[user]**: the small-region rule `S` and its `small`
  parameter are dropped ("let the organizers decide on the top-N count they want to use"); a small
  region gets candidate cuts like any region, "N = m: all candidates" among them, never
  preselected. K is not a separate setting: it is each region's own top-k, chosen with the same
  "Choose N" control in the region round when the region is accepted, and saved with the outcome;
  the `winners` parameter is dropped (§10.2–§10.5, §10.7, §10.9, §11).
- **v5, last decisions of 2026-11-26** **[user]**: ties with several winners as proposed (a tie
  inside the top k is a shared place; a tie across place k needs a re-jury, or a k at a tie
  boundary); files the offline jury rejected may come back in a re-jury from an earlier round, with
  no exclusion rule; question 13 (phase 1 → R0/R1 → phase 2 → slice B) and question 14 (the cut
  picker for main rounds now, in 26.11.18, before the regional report); question 12 (local jurors:
  nothing in the app now). A pass over the whole plan
  for leftovers of dropped ideas.

**Code read:** `911255f7`, a mirror of `stack/105-round-lineage-plan` (PR #288). Inferences are
marked *(inf.)*. No DB or server was queried for the plan itself. (updated 2026-11-25) File:line
citations are refreshed to `origin/master` at `d037bf4e`; where code moved by more than a line
shift, the symbol is named instead. (v4, 2026-11-25) §10's citations are to `origin/master` at
`86d7abeb`, and to `RoundLineage` on lineage slice A's branch (#193).

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
  where each region's best photos are and decide. (v5, 2026-11-26: the winner an Admin accepts is
  the region's top k (chosen per region, default 1) by the jury's votes in its region round, §10.2–§10.3.)
- **What still applies:**
  - files with a conflict of interest and files deleted on Commons are excluded before ranking
    (decisions 12, 13); each cell can note how many were excluded;
  - the final round's national top k are marked "national winner" (decision 14);
  - cells of open rounds are marked "provisional" (decision 6);
  - N is 1–10, default 1 (decision 7).
- **No region can go missing quietly:** the regions with no files sit in a collapsed group; a region
  with no file in any shown column names the hidden columns and the unlinked rounds that hold its
  files (widened 2026-11-26, review of #181); the header warns when the chain starts much smaller
  than the contest.
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
  first in priority, after the round improvements of 26.11.17 and 26.11.18 (v4, 2026-11-25; v5:
  the full order, with R0/R1 and slice B, is §10.8).
  - **Phase 0** (S): done for WLE 2026 and WLM 2026 (§7.4, §7.5).
  - **Phase 1** (M, 2.5–3 days) is the page, on lineage's slice A.
  - **Phase 2** (S–M) is the export, as wiki text and as CSV (decision 11).
  - **Phase 3** (S) comes after lineage phase 1 and multi-role's pre-screening rounds (migration
    numbers: next free at merge).

## Short answer, v4–v5: the re-jury loop (added 2026-11-25; updated 2026-11-26)

- **The report still picks nothing on its own.** A region's **region round** is the newest re-jury
  round that holds it, else the final round. When the region is clear there, its winners are its
  top k by the jury's votes, k chosen per region by the Admin when accepting (default 1), and one
  Admin's "Accept region" records them. An Admin can also accept
  a region with "no winner" (a note is required) or mark it "needs re-jury" (questions 8–9
  **[user]**). One row per region in one new table, numbered at build time; every change goes to
  the contest change log (#178's `contest_change`); Organizers read, Admins write (decision 18
  **[user]**); jurors get no such action (question 6 **[user]**) (§10.2).
- **A flagged region gets one plain line with the next step,** computed on each request, never
  stored. Four cases: an Admin marked it "needs re-jury"; no photo of the region reached the final
  round ("its best photos are in round 3"); a tie for 1st place; few votes on the top photo ("2 of 5 jurors voted") (question 5 **[user]**, §10.3).
- **An offline jury session** votes as normal jury work in a round an Admin creates, for example
  through one juror account voting for the offline jury (question 6 **[user]**, §10.1).
- **A cut picker,** one shared component (§10.4): the app lists several candidate cuts, each with
  its reason ("N = 7: scores drop 8.4 → 7.1"), and a gallery of the candidates in rank order with
  the cut lines drawn between files. The admin picks N from valid values only (a value inside a tie
  can't be entered, question 3 **[user]**; "Choose N" replaces today's free top-N box); the chosen
  N is stored. No single N is proposed
  (question 1 **[user]**); for a small region, "all candidates" is one of the cuts (question 2
  **[user]**). Used in three places: the
  round form's top-N (step 1, "Which files"), for main rounds and the national path (decision 21
  **[user]**), each region's N in a re-jury (decision 19 **[user]**), and each region's number of
  winners when it is accepted.
- **"Re-jury these regions"** on the report (decision 20, question 7 **[user]**): one new round
  holding each selected region's own top N from its own source round (by default the round that
  decided it, changeable per region; question 10 **[user]**), prefilled into the round form's step
  1, jurors in step 3, every juror on every file by default (question 11 **[user]**). No manual track label: lineage's rule 4 puts it
  on the region track of its regions (§10.5). It becomes the region round for its regions.
- **The loop:** report → accept the clear regions → re-jury the flagged ones → report again, until
  every region is accepted. The exports (wiki text, CSV) then list the winners (§10.7).
- **Priority order** (§10.8; questions 13–14 **[user]**): the round improvements of 26.11.17 and
  26.11.18 first, 26.11.18 now with the cut picker for main rounds (SN); then lineage slice A,
  report phase 1, the flagged regions and region outcomes (R0/R1), phase 2 with the export of
  accepted winners, then lineage slice B. Per-region N in the report (R2) and the combined re-jury
  round (R3) follow. A re-jury until R3 lands is made with today's form, one round per region
  (§10.8).

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
   - **With an explicit list** (`rounds=`, item 7) (added 2026-11-26, review of #181): `F` is the
     `round` parameter if given (added to the columns if the list lacks it); else the automatic
     `F` above, if the list holds it; else the deepest plain listed round, then the largest id
     (any listed round if none is plain). When the automatic `F` is not in the list, the header
     says so: "R-03, the final round, is not in your list; showing R-05, the deepest listed
     round, as final." `export=final` (§3.3) and §10.3's "final round" use this `F`.
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
4. **Explicit list** (`rounds=`, decision 1): the same order, by kind, with `F` (§2.1 item 3)
   first. Every listed round is shown: the list is the admin's own choice of columns, so the
   default walk below is not used, and "Show all rounds" adds nothing (added 2026-11-26, review of
   #181).

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
3. **Hidden and unlinked rounds (review point 2; widened 2026-11-26 after the review of #181).**
   For a region with no file in any *shown* column, one statement counts its files in the hidden
   columns (side feeders of a merge, add-ons and regional rounds, behind "Show all rounds") and in
   the contest's *other* rounds, i.e. not in `X` and not columns (§4.2). The row says, hidden
   rounds first: "Files of this region are only in Q-01 (12 files), not shown by default [Show all
   rounds], and in K-07 (4), which isn't linked to the chain. Add it with 'rounds' if it belongs
   to it." Up to 3 rounds are named, newest first, with a "+k more" count. With "Show all rounds",
   only the unlinked part remains.
4. **No files anywhere** (no file in any column, shown or hidden, and no unlinked round):
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
    contests' ids and missing ids are dropped and named, never `.get`. With `rounds` and no
    `round`, `F` is chosen as §2.1 item 3 says for an explicit list (added 2026-11-26);
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

What is shown, by default the final round's column per region (`export=final`; with an explicit
`rounds=` list, the column of `F` as §2.1 item 3 picks it, added 2026-11-26); `export=shown`
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

### 4.2 For regions with no file in any shown column: hidden columns and unlinked rounds

```sql
SELECT ri.round_id, ri.region, COUNT(*) FROM round_image ri
WHERE ri.round_id IN (<hidden columns and other rounds>) AND ri.status = 'in'
  AND ri.region IN (<regions with no file in a shown column>)
GROUP BY ri.round_id, ri.region
```

- It runs once, only when such a region exists (widened 2026-11-26, review of #181: a region whose
  files are only in a hidden side feeder is named in the default view, §2.4 item 3).
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
| A region whose files are only in a hidden side feeder (added 2026-11-26) | The default view names that round, with a "Show all rounds" link (§2.4 item 3) |
| An explicit `rounds=` list without the automatic final round (added 2026-11-26) | `F` is the deepest plain listed round, named in the header; `export=final` exports it (§2.1 item 3) |
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
| Per-region top-N for the next round | Not here. It is the preventive fix, and could be a later round-form option *(inf.)*. *(v4, 2026-11-25: now here, as the re-jury round's per-region N, §10.5, chosen with the cut picker, §10.4)* |
| Lineage slice A (#193, V115) and slice B (v4, added 2026-11-25) | The re-jury round gets no manual label: slice A checks `round.track` before rule 4, so a label would replace its region track; left empty, rule 4 puts it on the track of its region set (§10.5). Slice B's Cancel hides a re-jury round that was a mistake; an outcome accepted from it stays, and the report says "the top photo changed since it was accepted" (§10.2; v5, 2026-11-26) |
| Contest change log (#178, V114) (v4) | Regional outcomes are logged as a `contest_change` kind of their own (§10.2), shown in the contest's change log and on the report |
| Contest export, snapshots, copy, archive (`2026-11-25-contest-export-snapshots-copy-archive.md`) (v4) | `regional_outcome` (v5; v4's `regional_decision`) is contest data: an export or snapshot carries it, a copy leaves it out (a new contest has no outcomes), an archive keeps it. That plan should list the table; not edited here |
| Conflict of interest (`CoiMembers`, `round.exclude_coi`) (v4) | A re-jury round leaves CoI files out (ticked by default), so a juror's own photo never reaches the re-jury; decision 12 already keeps it from winning. No data says which juror is local to a region (§10.6) |
| UI path (draft PR #189: numbered setup steps) (v4) | The re-jury opens the round form at step 1 "Which files", prefilled; step 2 "Check the matching files" shows the per-region counts; step 3 "Jurors" as for any round (§10.5) |

---

## 7. Phases

S ≈ ½ day, M ≈ 1–2 days, L ≈ 3+ days of agent work, each with review and targeted specs.

| Phase | Content | Size |
|---|---|---|
| 0 | **WLE Ukraine 2026 check, local** (§7.1). On the 26.11 rehearsal copy of production, migrated to V95, with the read-only SQL below. A local script (`python -I`) applies §2. It shows the chain, the add-ons, the ambiguity, the regions that fell back, the unlinked rounds, and whether WLE's `previous` links are recorded. It also verifies WLE's region prefixes and tunes the "small root" threshold. It is phase 1's acceptance case, and it also serves lineage decision 3. Done for WLE 2026 (§7.4) and WLM 2026 (§7.5) (updated 2026-11-25) | S |
| 1 | **The page,** on lineage's slice A (the `RoundLineage` core with the Main track and the manual label, about 3–4 d, built first (decision 15); not counted here). The column model: rounds, order, default columns, `F` (pure, unit-tested; ½ d). Region summaries, top N and unlinked rounds on the votes path, plus the counts path behind `roundImage.read` with its proof (1 d). Controller, route, view, messages in en/uk/ru/fr, links, screenshots in both themes (1–1.5 d). (updated 2026-11-25: "show, don't pick") | M (2.5–3 d) |
| 2 | **Wiki text** (S) and **CSV** (S), both from the start, with the `ExportsSpec` change (decision 11; updated 2026-11-25, decision 11) | S–M (½–1 d) |
| 3 | **After lineage phase 1 and multi-role's pre-screening rounds:** pre-screening rounds join `X`; a link from the lineage map. The default `F` from the "Main" track moves to phase 1 (updated 2026-11-25, decision 2). V105's statuses are read in phase 1 already (§2.1) (updated 2026-11-25) | S (½ d) |
| R0 (v4) | **Flagged regions** (§10.3; v5: four cases, one line each): computed from what phase 1 reads, with the region round and its top photo; the line and its next step in the region's row; unit spec of the cases | S (½–1 d) |
| R1 (v4) | **Region outcomes** (§10.2; v5: one row per region, no per-file entry): migration (table, `contest_change` kind), DAO, the report's region controls (Accept region with its number of winners, No winner, Needs re-jury, Clear), CSRF POSTs, change-log entries, the region's state and filter; specs incl. negative authorization (organizer, juror, other contest's admin, signed out) | M (1.5–2 d) |
| 2' (v4) | **Exports from accepted winners** (§10.7): `export=accepted`, the default once a contest has an outcome; on top of phase 2 | S (+½ d) |
| SN (v4; in 26.11.18, question 14 **[user]**) | **Cut picker** (§10.4): the pure candidate-cut finder, its valid values and its reasons (unit spec over rated, yes/no, tie and small-region shapes; ½–1 d); the ranked scores read with the fill's own rank key (½ d); the cut gallery (a shared Twirl partial: thumbnails in rank order, scores, tie brackets, labelled cut lines, "Cut here"), on the round form's step 1 with "Choose N" and an N list of valid values only (v5), the preview "37 of 412 files, lowest included score 7.2", the choice logged with the round's change-log entry (1.5–2 d). No migration | M (2.5–3.5 d) |
| R2 (v4) | **Per-region N in the report** (§10.4–§10.5): each region's candidate cuts with their reasons, its cut gallery page, the source round per region, the "Re-jury these regions" form | S–M (1–1.5 d) |
| R3 (v4) | **The re-jury round** (§10.5): `round.region_top` (migration at build time), the round form's per-region fieldset and prefill, per-region ranking with an SQL region condition in `DistributionSource.rank`, the regional track label, the report's re-jury column rule; specs incl. re-apply, resubmit and "Distribute new files" | L (3–4 d) |
| ~~R4~~ (v4) | ~~Pasted results~~: dropped (question 9 **[user]**, 2026-11-26: results come from the votes in the region round) | — |

**Total:** about 3.5–4.5 agent days (updated 2026-11-25: "show, don't pick"): phase 1 2.5–3, phase 2
½–1, phase 3 ½. With lineage's slice A, about 6.5–8.5 days in all.

**Order of work** (updated 2026-11-25, decision 15): lineage slice A → regional winners phase 1 →
phase 2 (exports) → phase 3. Slice A and phase 1 come first (v4, 2026-11-25: after the round
improvements of 26.11.17 and 26.11.18; dates replaced by a priority order); phase 3 waits for
lineage phase 1 and multi-role's pre-screening rounds.

**v4–v5 order (decided **[user]** 2026-11-26, questions 13 and 14; §10.8):** the round
improvements of 26.11.17 and 26.11.18 (26.11.18 with SN, the cut picker for main rounds) → slice A
→ phase 1 → R0/R1 → phase 2 with 2' → slice B → R2 → R3. v4 adds about
9–12.5 agent days (R0 ½–1, R1 1.5–2, 2' ½, SN 2.5–3.5, R2 1–1.5, R3 3–4; v5, 2026-11-26: R1 smaller,
R4 dropped).

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
  - a region whose files are only in a hidden side feeder → the default view names that round with
    "Show all rounds"; with `all`, it is not named (added 2026-11-26, review of #181);
  - an explicit `rounds=` list: without the automatic `F`, `F` is the deepest plain listed round,
    named in the header; with `round` not in the list, it is added first; every listed round is
    shown (no default walk) (added 2026-11-26, review of #181);
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
  - the exports: `export=final` and `export=shown`, each file with its round; `export=final` with
    an explicit list lacking the automatic `F` exports the listed `F` (added 2026-11-26); the CSV's columns; no
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

(v4, 2026-11-25) Decisions 18, 19 and 21 are the maintainer's **[user]**; 20, 22 and 23 are
proposals, asked in §11. Decision 16 is superseded for the final step; 11 and 17 are amended
(dated notes under each). (v5, 2026-11-26) 20 and 22 are now **[user]** (questions 7 and 5); 18,
19 and 21 are refined by questions 2–4, 6, 8 and 9; 23 is **[user]** too (question 11).

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

*Amended (2026-11-25, decision 18 **[user]**):* "the regional export (accepted winners)": once a
contest has a decision, the default export is `export=accepted`, the accepted winners per region
with their places (§10.7); `final` and `shown` stay as options.
*Amended (2026-11-26, question 9 **[user]**, and several winners **[user]** later that day):* each
region's top k winners in its region round (k chosen per region when accepted, default 1), so the
accepted export lists each region's winners with their places ("Regional winner" for one).

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
core, both first in priority. (v4, 2026-11-25: "before the WLM final in early December" replaced by
the priority order, after the round improvements of 26.11.17 and 26.11.18.)

**16. Hand-made decisions and overrides.**
- (a) No stored overrides; `rounds=` for chains done by hand.
- (b) A stored per-contest chain and N.
- (c) Stored "confirmed winner" marks.

**Recommendation: (a)** now. Reasons:
- (b) and (c) need a table, a migration, CSRF writes and an audit trail;
- revisit (b) if organizers bookmark long URLs.

**Decided (maintainer, 2026-11-25): (a)**: no storage now. Everything is in the link; revisit if
organizers need to record confirmed winners.

*Superseded for the final step (2026-11-25, decision 18 **[user]**):* the offline jury's decisions
are recorded (option (c), "confirmed winner" marks, widened to accepted / rejected / needs
re-jury). *(2026-11-26, questions 8–9 **[user]**: per region only, accepted with its top-1 (later: top k) as the
winner, accepted with "no winner", or needs re-jury; no per-file accept/reject.)* Everything else stays in the link: no stored chain or N for the report (option (b) is
still not chosen); a re-jury round stores its per-region N on the round (§10.5).

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

*Clarified (2026-11-26, review of #181):* with an explicit `rounds=` list, every listed round is
shown and the walk is not used; `F` is picked as §2.1 item 3 says for a list (§2.3 item 4).

*Amended (proposal, 2026-11-25, v4; follows decision 20):* a re-jury round (a round with
`region_top`, §10.5) is a default column, placed first, before `F`, blank for the regions it
doesn't hold; several re-jury rounds go newest first. Without this, a re-jury round, which is off
the Main track (rule 4 puts it on its regions' track), would sit under "Show all rounds" as a
regional round (decision 5). *(2026-11-26: decision 20 is decided (question 7 **[user]**), so this
applies: the re-jury round is the region round for its regions, §10.3.)*

**18. Record regional decisions (new, 2026-11-25).**

**Decided [user] (maintainer, 2026-11-25):** the app records regional decisions, per contest,
region and file: *accepted*, *rejected* (by the offline jury, with a note), and per region *needs
re-jury*; who and when. Admins write, organizers read. Shown in the report, used by re-jury rounds
(rejected files left out) and by the regional export (accepted winners), and logged in the contest
change log (#178's mechanism). This replaces "show, don't pick" for the final step only: the
report itself still picks nothing automatically. Design: §10.2. Open details: questions 6, 8, 9.

*Refined [user] (maintainer, 2026-11-26, questions 6, 8 and 9):* results come from the votes in
the region round (the re-jury or the final round). A clear region's winner is its top-1 there,
recorded by one Admin's "Accept region"; the other outcomes are "no winner" (with a note) and
"needs re-jury". No per-file accept/reject and no pasted list; rejected files are no longer stored,
so a re-jury no longer leaves them out (accepted **[user]** 2026-11-26: they may come back in a
re-jury from an earlier round; the Admin picks the source round, and the offline jury's own round
ranks them low). An offline session votes as jury in a round an Admin
creates; no Organizer or juror grant (§10.1–§10.2). Later that day **[user]**: several winners
per region, each region's own top-k chosen when it is accepted (default 1), stored as winner rows
with places.

**19. Each region's own top-N for a re-jury (new, 2026-11-25).**

**Decided [user] (maintainer, 2026-11-25):** each region gets its own top-N for a re-jury, "based
on amount and quality of candidates". The report suggests an N per region, with the reasoning in
the cell, and the admin can override each before creating the re-jury. Design: §10.4–§10.5. The
rule's details: questions 1–4.

*Refined [user] (maintainer, 2026-11-25, question 1):* not a single suggested N: "show possible
numbers and a gallery to view them and decide". The report lists each region's candidate cuts with
their reasons and links its cut gallery; the admin picks N (§10.4).

*Refined [user] (maintainer, 2026-11-26, questions 2 and 10):* the organizers choose each
region's N; candidate cuts are looked for in the window `[min(3, m), min(15, max(lo, ⌈m/3⌉))]`
(defaults), and "all candidates" is a cut for any region of up to 25 files. (A small-region rule
`S` was dropped the same day.) The source round is chosen per region,
by default the round that decided it (§10.4–§10.5).

**20. One re-jury round for all selected regions (proposal, 2026-11-25).** Question 7. §10.5
recommends one round with each region's own N; one round per region is the alternative (§10.5,
"Alternative").

**Decided [user] (maintainer, 2026-11-26, question 7):** one re-jury round with per-region N as
the goal (R3); ordinary per-region rounds (today's region filter and top N) as the interim
(§10.5, §10.8).

**21. A shared cut picker, also for main rounds' top-N (new, 2026-11-25).**

**Decided [user] (maintainer, 2026-11-25):** "we don't have top-N for main rounds yet, maybe makes
sense to have them too." One component (§10.4) for the round form's top-N and for each region's N
in a re-jury, with the same rules and the same explanation text. The tie at the cut is always shown,
with a preview, never cut silently. Open details: questions 3, 4 and 14.

*Refined [user] (maintainer, 2026-11-25, question 1):* the component shows candidate cuts and a
gallery and the admin decides; it proposes no single N (§10.4).

*Refined [user] (maintainer, 2026-11-26, questions 3 and 4):* the N control offers only valid
values, so an N inside a tie can't be entered; the server still refuses one from a crafted post.
Yes/no sources get candidate cuts at level boundaries, the reason naming the level (§10.4).
Confirmed **[user]** later that day: "Choose N" replaces the round form's free top-N box, for main
rounds and regions.

**22. The "unclear" mark: computed, not stored (proposal, 2026-11-25; v5: "flagged regions").**
§10.3. Its rules: question 5.

**Decided [user] (maintainer, 2026-11-26, question 5), simplified:** no rule codes; a flagged
region gets one plain line with the next step. Four cases: needs re-jury (an Admin's mark); no
photo reached the final round; a tie for 1st place; few votes on the top photo. Close scores and
yes/no only are dropped; thresholds are tuned on real data (§10.3).

**23. Every juror on every file of a re-jury (proposal, 2026-11-25).** §10.6, question 11.

**Decided [user] (maintainer, 2026-11-26, question 11):** all jurors on every file by default; no
warning when the Admin changes it.

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
  (v5, 2026-11-26: the app records each region's own top-k winners, chosen when it is accepted,
  default 1 **[user]**; §10.2.)
- **When `roundImage.read` turns on** (the P2b pilot), and so when the counts path matters.

---

## 10. The re-jury loop (v4, added 2026-11-25; v5, 2026-11-26)

### 10.1 Why

- **The maintainer (2026-11-25):** "The report is fine, but in many cases it requires re-jurying when
  the winner is unclear, or deemed unworthy by an offline jury session. The path to select the top
  10 fast for the international deadline does not rate each region with the same attention, as
  follow-up rounds' selection."
- **What phase 0 shows** (§7.4, §7.5):
  - WLE 2026: 11 of the 27 regions have no positive file in the final round 1952, because its
    contest-wide minimum average (10.88) dropped them; 8 fall back to 1942 or 1938, and 3 (23, 56,
    59) have a "yes" only in a root.
  - WLM 2026: a contest-wide top 10 of Q-02 keeps 5 of 27 regions, a top 50 keeps 17. A region
    outside the national top is judged by fewer, broader rounds.
- **So the report alone is not the end.** Each region's winner has to be recorded, the regions the
  jury couldn't settle re-juried with the same attention for every region, and the winners
  exported when every region is done.
- **The loop** (v5, 2026-11-26):
  1. the report shows each region's best files per round. A region that isn't clear gets one plain
     line: what is wrong and the next step (§10.3);
  2. a clear region's winners are its top k photos (k chosen by the Admin for that region, default
     1) in its **region round**: the newest re-jury round that holds the region, else the final round `F`.
     An Admin records them with "Accept region" (§10.2);
  3. the flagged regions go to one re-jury round, each with its own top N ("Re-jury these regions",
     §10.4–§10.6);
  4. the re-jury round becomes the region round for its regions; back to 1, until every region with
     files is accepted;
  5. the exports list the accepted winners (§10.7).
- **An offline jury session** (question 6 **[user]**, 2026-11-26): "why mix up admin and jury? jury
  does normal jury work in rounds created by admins." If the session's choices go into the app,
  they go in as normal jury work: an Admin creates a round (for example a re-jury of the regions to
  discuss), and the session votes in it, for example through one juror account voting for the
  offline jury. Its votes then give each region's top photo like any round's. Jurors never get
  admin-like actions; recording outcomes stays Admin-only.

### 10.2 Recording outcomes (decision 18 [user]; questions 8 and 9 [user])

*(Reworked 2026-11-26 after questions 8 and 9 **[user]**: no per-file Accept/Reject buttons and no
pasted list. Results come from the votes in the region round. What is stored is the accepted
outcome per region. Later the same day **[user]**: several winners per region, each region's own
top-k, stored as rows with places.)*

**The number of winners, per region** **[user]**, 2026-11-26: "they can also just choose
per-region top-k for the latest round and that would be the K." Each region's number of winners
is the top-k the Admin chooses for it in its region round, with the same "Choose N" control as the
cut picker (§10.4: valid values only, never inside a tie), default 1. Ukraine has 27 regions and
takes the top-1 of each; a contest with few regions (e.g. Belgium's three upload campaigns,
Brussels, Flanders and Wallonia) may take 3. The choice is saved with the region's outcome (its
winner rows), not as a report parameter or a contest setting. It is not the report's `n` (the
files shown per cell, decision 7).

**What an Admin can do, per region** (in the region's row of the report; Admins only):
- **Accept region,** with "Winners [1 ▾]" (the region's top-k, valid values only): offered when
  the region is clear (none of §10.3's cases). It records the region's top k in its region round
  as its winners, in order, with their places.
- **No winner…:** accepts the region without a winner. A note is required ("no photo good enough").
- **Needs re-jury…:** marks the region for the re-jury, with an optional note ("the jury rejected
  all its candidates"). This is §10.3's first case.
- **Clear:** back to undecided.

A region is *done* when it is accepted: one Admin's click, with a winner or a "no winner" note
(question 8 **[user]**). The change log records who and when.

**Table** (migration number assigned at build time: master is at V114, lineage slice A (#193) takes
V115; the add-juror-on-stopped-rounds work may need none):

```sql
CREATE TABLE IF NOT EXISTS regional_outcome (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  contest_id  BIGINT UNSIGNED NOT NULL,          -- contest_jury.id (SERIAL, V4), as V61/V77/V109's keys
  region      VARCHAR(3)   NOT NULL,             -- a geographic region id (decision 10)
  status      VARCHAR(16)  NOT NULL,             -- accepted | needs_rejury
  round_id    BIGINT UNSIGNED NULL,              -- round.id: the region round the winners came from
  note        VARCHAR(500) NULL,                 -- the "no winner" reason, or why a re-jury is needed
  decided_by  BIGINT UNSIGNED NOT NULL,          -- users.id, never an email
  decided_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY regional_outcome_region (contest_id, region),          -- one row per region
  CONSTRAINT regional_outcome_chk CHECK (
    (status = 'accepted' AND round_id IS NOT NULL) OR                -- with winners
    (status = 'accepted' AND round_id IS NULL AND note IS NOT NULL) OR  -- "no winner"
    (status = 'needs_rejury' AND round_id IS NULL)),
  CONSTRAINT regional_outcome_contest_fk FOREIGN KEY (contest_id) REFERENCES contest_jury (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS regional_winner (
  outcome_id  BIGINT UNSIGNED NOT NULL,          -- regional_outcome.id
  contest_id  BIGINT UNSIGNED NOT NULL,          -- the outcome's contest, for the unique key below
  place       TINYINT UNSIGNED NOT NULL,         -- 1, 2, 3 ...; equal for a shared place
  page_id     BIGINT NOT NULL,                   -- images.page_id (BIGINT, V5)
  PRIMARY KEY (outcome_id, page_id),
  UNIQUE KEY regional_winner_file (contest_id, page_id),            -- a file wins at most once
  CONSTRAINT regional_winner_place_chk CHECK (place BETWEEN 1 AND 10),
  CONSTRAINT regional_winner_outcome_fk FOREIGN KEY (outcome_id) REFERENCES regional_outcome (id) ON DELETE CASCADE
) ENGINE = InnoDB;
```

- **One outcome row per region: the current state,** and one winner row per winning file. No
  outcome row means *undecided*. History is the change log, not these tables. A "no winner" note
  must not be blank, and an outcome with a round has at least one winner row: both checked in
  Scala, in the write's transaction.
- **Winners in order** **[user]**: the region's top k in its region round, with their places by
  the round's rank key; k is the number of winner rows, so no other column stores it. With k = 1,
  one winner. Since k is chosen among valid values only, a tie never crosses place k. A tie inside
  the top k is a shared place (two files at place 2, the next at place 4). A tie that would cross
  place k needs a re-jury, or a k at a tie boundary that takes the whole tie as a shared place
  (§10.3). *(Decided **[user]** 2026-11-26, as proposed.)* The values offered stop at the region's positive files, so a region never
  gets more winners than it has candidates.
- **Key types:** `contest_id`, `round_id`, `decided_by` and `outcome_id` are `BIGINT UNSIGNED`,
  as `contest_jury.id` (SERIAL, V4), `round.id`, `users.id` and `regional_outcome.id`, and as the
  foreign keys of V61, V77 and V109. `page_id` is `BIGINT`, the type of `images.page_id` (V5) and
  `round_image.page_id` (V84).
- **Accept is checked on the server.** The request carries only the region and the state the form
  was made from. The server computes the region round and the cases of §10.3 as the report does;
  it refuses if the region is not clear, and takes the winners and their places from its own
  ranking, never from the request. The request carries k, refused if it is not a valid value (in
  a tie, above 10, or above the region's positive files).
- **Files the offline jury rejected** are not stored as rejected (decided **[user]** 2026-11-26).
  They may come back in a re-jury taken from an earlier round; there is no exclusion rule. The
  Admin picks the source round per region (§10.5), and if the offline jury voted in the app, its
  own round already ranks them low.
- **If the winners change after acceptance** (votes still coming in an open round, the region
  round cancelled, a file disqualified), the region round's top k (k = the stored winners' count)
  no longer matches the stored winners. Changing what the report shows never does this. The region then shows one line: "The top photos changed
  since they were accepted. Next: accept again." The stored outcome never changes by itself.
- **If a winner's region changes** (a corrected monument id), the report shows "accepted under
  07, the file is now in 32. Next: accept again." The outcome counts for neither region until an
  Admin clears it and accepts again (logged).
- **Files deleted on Commons after acceptance** (decision 13): `export=accepted` leaves them out
  (`images.deleted_at IS NULL`). The report shows a "deleted on Commons since accepted" badge, and
  the region's header warns: "an accepted winner was deleted on Commons". The region stays
  accepted; the export's comment line names it (§10.7).
- **The change log** (#178's `contest_change`), in the transaction of the write: one kind,
  `regional_outcome_set`, added to `contest_change_kind_chk`. The migration copies the kind list
  from the newest migration at build time, since slice A adds a kind of its own. Detail:
  `{"region", "from": {"status", "round", "winners": [{"place", "page"}]}, "to": {…}}`, the note in `comment` (500 code
  points, as `ContestChangeJdbc.CommentMaxLength`); a new row is `"from": null`, a Clear is
  `"to": null`. Shown in the contest's change log and in a "Decisions" list on the report.
- **Undo:**
  - **In the app:** "Clear" puts a region back to undecided (the row deleted, logged with its old
    values).
  - **The migration's undo** (in its comment, as V114 has): `DROP TABLE regional_winner`, `DROP
    TABLE regional_outcome`, the kind's rows deleted, the CHECK restored, the Flyway history row deleted. The outcomes are lost.
- **Access:** writes are CSRF POSTs under
  `withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId)))`: the contest's Admins and root.
  Organizers see the outcomes, notes and log read-only (decision 18 [user]). Jurors never see them
  and never get any of these actions (question 6 **[user]**: no Organizer or juror grant).
- **Concurrency:** each form carries the state it was made from (status and `decided_at`). A write
  that finds a newer state refuses with "changed by another admin meanwhile" and shows it. The
  write and its log row are in one transaction.
- **Privacy:**
  - notes are the jury's internal reasoning and may name people. They are shown only to the
    contest's Admins and Organizers and root. They are never in the regional winners export (wiki
    text, CSV), never put in a URL and never shown to jurors;
  - the log keeps account ids (as V114 does);
  - the regional winners export carries Commons file titles (public), places and rounds only;
  - the contest export, snapshot and archive plan (§6) is another export, of the contest's data
    under its own access rules. It carries this table, notes included, as it carries the change
    log.

**In the report** (R1), Admins see the controls in each region's row; Organizers see the state only:

```
| 07 Volyn O. (412 files)    Tie for 1st place: 2 photos at 8.50. Next: re-jury this region. | R-03 (final) ...
|   [No winner…] [Needs re-jury…]                                                             |
| 21 Zakarpattia (88 files)  Clear: top photo File:Example 3.jpg (R-03, 8.90)                  |
|   Winners [ 1 ▾ ] [Accept region] [No winner…] [Needs re-jury…]                             |
| 32 Kyiv O. (240 files)     Accepted 2026-12-02: File:Example 4.jpg (R-03)   [Clear]          |
```

### 10.3 Flagged regions: one plain line with the next step (decision 22, question 5 [user])

*(Simplified 2026-11-26 after question 5 **[user]**: four cases, no rule codes on the page.)*

Computed on each request, never stored, over the region's **region round**: the newest re-jury
round that holds the region (§10.5), else the final round `F` (§2.1 item 3). A region is **clear**
when none of the four cases applies; its top k there (k chosen by the Admin, default 1, §10.2)
are then the winners an Admin can accept. The cases are computed for one winner, the default. A flagged region gets **one** line: what is wrong and the next step. When several cases
apply, the line is the first that applies, in this order:

| Case | When | The line (example) |
|---|---|---|
| Needs re-jury | an Admin marked the region "needs re-jury", e.g. the jury rejected all its candidates (§10.2) | "Needs re-jury: the jury rejected all its candidates. Next: add it to a re-jury round." |
| Not in the final round | the region round holds no file of the region with a positive result | "No photo from this region reached the final round; its best photos are in round 3. Next: re-jury from round 3." |
| Tie for 1st place | two or more photos share the best result in the region round (the same rank key) | "Tie for 1st place: 3 photos at 8.50. Next: re-jury this region, or accept all 3 as a shared 1st place." |
| Few votes | fewer than half of the top photo's jurors voted on it (in an open round, votes still to cast count as not voted); on Accept with k winners, checked on each of them | "Only 2 of 5 jurors voted on the top photo. Next: wait for the votes, or re-jury." |

- **"Round 3"** in the second line is the newest default column (§2.3) with a positive file of the
  region: the last round it was still in, before the cut. If that round is hidden or not linked to
  the chain, the line says so and links it (§2.4 item 3).
- **Ties with several winners:** a tie inside the chosen top k (e.g. k = 3, places 2 and 3 tied)
  is a shared place, and a tie for 1st place can be accepted as a shared 1st place by choosing a
  k that takes the whole tie. A k inside a tie is never offered (§10.2; decided **[user]**
  2026-11-26).
- **Accepted regions** show "Accepted" and the winners, and no line.
- **Dropped** (question 5 **[user]**): v4's "close scores" and "only yes/no votes" rules, and the
  minimum of 3 votes. Without that minimum, a round in which one juror account votes for an offline
  jury (§10.1) is not flagged once that juror has voted.
- **The threshold** ("half of the jurors") is a default, tuned on real data with phase 0c, without
  a new decision unless a case changes.
- **Phase 0 evidence** (§7.4, §7.5; not re-run):
  - WLE 2026: "not in the final round" for 11 regions: 05, 07, 21, 35, 44, 61 and 85 (best in 1942,
    cut by 1952's minimum), 63 (best in the yes/no 1938), and 23, 56 and 59 (a yes only in a root).
    Ties and few votes can't be told from the kept summary.
  - WLM 2026 today: Q-02 is open with 3.3 of 13 marks per file, so "few votes" flags every region
    until its votes are in. Region 18 has one file, which is clear once its votes are in.
- **Phase 0c (to run, read-only, on a local copy; counts and public page ids only).** The top 12
  files per region of the final round, with their votes, to tune "few votes" and the cut picker's
  gap rule (§10.4):

  ```sql
  SELECT region, page_id, avg_rate, grade_sum, voted, rk FROM (
    SELECT NULLIF(LEFT(SUBSTRING_INDEX(i.monument_id, '-', 1), 3), '') AS region, p.page_id,
           p.grade_sum / NULLIF(p.voted, 0) AS avg_rate, p.grade_sum, p.voted,
           RANK() OVER (PARTITION BY NULLIF(LEFT(SUBSTRING_INDEX(i.monument_id, '-', 1), 3), '')
                        ORDER BY p.grade_sum / NULLIF(p.voted, 0) DESC, p.grade_sum DESC) AS rk
    FROM (SELECT s.page_id, SUM(s.rate <> 0) AS voted, SUM(GREATEST(s.rate, 0)) AS grade_sum
          FROM vote s WHERE s.round_id = @f GROUP BY s.page_id) p
    JOIN images i ON i.page_id = p.page_id) t
  WHERE rk <= 12 ORDER BY region, rk;
  ```

  Run it for WLE 2026's 1952 and 1942 and for WLM 2026's Q-02 once its votes are in. Record per
  region whether places 1 and 2 tie, and places 3 and 4, the votes on the top files against their jurors, and the largest
  gap within places 3–10.

### 10.4 The cut picker: candidate cuts and a gallery, three places (decisions 19 and 21 [user]; questions 2–4 [user])

*(Reworked 2026-11-25 after question 1 **[user]**: "show possible numbers and a gallery to view
them and decide". The gap rules find and rank candidate cuts; they no longer give one answer.
Updated 2026-11-26 after questions 2–4 **[user]**: a new window for regions and an N control that
offers only valid values. A small-region rule `S`, added the same day, was dropped **[user]**: "do
we need to unconditionally take all files of a small region? let the organizers decide on the
top-N count they want to use.")*

**What it does.**
- It takes a ranked list of eligible candidates, by the same rank key the fill uses
  (`DistributionSource.rank`: rated rows scored in their round, then by the sum; yes/no rows by
  the `BinaryOrder`), with the same filters (CoI, deleted on Commons, region).
- It lists **candidate cuts**, each with its reason, and draws them in a **cut gallery**.
- The admin picks N from the valid values only: a candidate cut, a "Cut here", or the N list,
  which has no value inside a tie (below). The chosen N is stored. Nothing is preselected, for a
  region of any size or a main round.
- The same pure code, the same Twirl partial and the same message keys serve the round form's
  top-N, each region's N in a re-jury, and each region's number of winners when it is accepted.

**The score range** (for the 5% tests below): the largest score the round's rank key can give,
from 0, since a ✗ (rate -1) counts as 0, and under `assigned` so does an unrated vote:
- rated, `average_rule` `rated` or `assigned`: the round's maximum rate (1–10: 10; 1–20: 20);
- criteria rounds: the vote rate is the sum of the criteria (V89), so the criteria count × the
  maximum rate;
- `sum` rounds: the score is the sum itself (`denominatorOf` gives `Const(1)`), so the above × the
  jurors per file (`distribution`, or all the round's jurors with 0);
- yes/no: no range; levels instead (below).

**Candidate cuts** (decided in principle by question 1 **[user]**). For eligible scores
`s1 ≥ s2 ≥ … ≥ sm` and a window `[lo, hi]` (below):
- **Cut positions:** only between different scores (`sk > s(k+1)`), never inside a tie, and never
  N = 0. These are the **valid values** of N.
- **C1, a significant drop:** a cut position in the window whose gap `gk = sk − s(k+1)` is at least
  5% of the range, and also, with 3 or more cut positions in the window, at least twice their
  median gap (ties are not cut positions, so every gap counted is above 0). Reason: "N = 7: scores
  drop 8.4 → 7.1." The largest is marked "largest drop".
- **C2, close to the best** (regions; "quality"): N = the number of files within 5% of the range
  of `s1`, when that is at least 2 and at most 25, moved to the end of a tie it falls in. Reason:
  "N = 6: 6 files within 0.5 of the best (8.3)."
- **C3, all candidates:** N = m, when `m ≤ hi` (main rounds) or `m ≤ 25` (regions, so a small
  region always has "all candidates" among its cuts). Reason: "N = 7: all 7 candidates."
- **C4, around the target** (when C1 finds none): the cut positions nearest the target (main
  rounds) or the middle of `[lo, hi]` (regions), one on each side. Reason: "N = 6: no clear drop
  between files 3 and 10 (scores 8.1 to 7.4)."
- **C5, tie boundaries at the window's edge:** when a tie spans `lo` or `hi`, or the whole
  window (e.g. `lo = hi = 3` with `s3 = s4`), its two boundaries are candidates, even outside the
  window. Bounds yield to ties. The lower boundary is not offered when it is 0 (places 1 to `hi`
  all tied); the tie's end is. Reason: "N = 39: includes the tie at 6.8 (files 36–39)" /
  "N = 35: before the tie at 6.8."
- **The list:** at most 6 candidates, by N, duplicates merged (one N, several reasons), none above
  25 for a region. The gallery draws every cut position as a thin line, and the listed candidates
  as labelled lines.

**A region's window** (question 2 **[user]**, 2026-11-26: the m/5 formula is rejected; the
organizers choose each region's N). `m` is the region's eligible positive files in its source
round. The numbers below are **defaults**:
- `lo = min(3, m)`, `hi = min(15, max(lo, ⌈m / 3⌉))`. C2, C3 and C5 may list a cut above `hi`, up
  to 25. Nothing is preselected.
- **Examples** for WLM 2026's Q-02 (1–55 files per region): region 18 (1 file) → only N = 1, "all
  1 candidate"; a 7-file region → [3, 3], plus "N = 7: all 7 candidates"; a 12-file region →
  [3, 4], plus "all 12 candidates"; region 63 (55 files) → [3, 15].
- **For a small region** the gallery shows every file (it opens to the window's end plus 5, and
  at least to C3's N), so the organizers see all its candidates and choose.
- **Why** (past Ukrainian WLM and WLE contests, 2014–2025, read-only on a copy of production,
  2026-11-26):
  - past contests never cut a region at a fixed top N, and never recorded regional winners in the
    tool; no regional round used the top-N setting;
  - the organizers chose N per region; most small regions took all their files (in the rounds
    that showed whole regions, 92 of 94 regions carried every file on). This is why "all
    candidates" is a candidate cut up to 25, not a rule;
  - in the rounds that cut by rating, a region carried a median of 8 files (90th percentile 18,
    at most 23);
  - N varied from region to region inside one round (WLM 2024: 4–8; WLM 2019: 11–32), so the
    picker never preselects an N;
  - v4's `hi = min(10, ⌈m / 5⌉)` gave 4–7 for a region of 21–35 files, half of what rated rounds
    carried (median 16).

**Main rounds:** the admin may give a target `T` (e.g. 40), and the window is `[⌈0.8 T⌉,
⌊1.25 T⌋]`. Without a target, the window is the first 100 places, and C1 lists the largest drops
in it.

**The N control** (question 3 **[user]**, 2026-11-26: "why would we allow entering a wrong value
in the UI in the first place?"):
- It offers only valid values: a list (`<select>`) of every cut position up to the gallery's end,
  the candidate cuts labelled with their reasons. A value inside a tie is not in the list. With a
  script, it may become a stepper that jumps from one tie boundary to the next.
- **"Choose N" replaces the free top-N box** (confirmed **[user]**, 2026-11-26, for main rounds
  and regions): before the candidates are loaded, the round form shows the stored N (or none) and
  "Choose N", which loads them (it posts the form to the preview action, below). There is no free
  number box for the top N any more.
- The server still refuses an N inside a tie from a crafted post: "N = 37 is inside a tie (files
  36–39); choose 35 or 39" (the lower boundary only if above 0). Today the fill would cut such a
  tie by page id, silently.

**The cut gallery** (a shared Twirl partial; server-rendered, no script needed):

```
Round R-03, region 07 Volyn O.: 23 candidates, ranked by average (rated, 1–10)
  1  [thumb] File:A.jpg   8.70
  2  [thumb] File:B.jpg   8.40
 ─── N = 2 · scores drop 8.4 → 7.1 (largest drop) ··························· [Cut here]
  3  [thumb] File:C.jpg   7.10
  4  [thumb] File:D.jpg   7.00 ┐ tied
  5  [thumb] File:E.jpg   7.00 ┘
 ─── N = 5 · scores drop 7.0 → 6.2 ··········································· [Cut here]
  6  [thumb] File:F.jpg   6.20
  …  (shown up to the window's end plus 5; "Show more")
```

- Thumbnails in rank order, with place, title, score in the round's terms, the votes cast, and a
  link to the large view. Ties are bracketed and never split by a line.
- Every cut position has a thin line; candidate cuts have a labelled line with "Cut here".
- **Preview** of the current choice: "37 of 412 files, lowest included score 7.2."

**Yes/no source rounds** (question 4 **[user]**, 2026-11-26: (a)). The key is the `BinaryOrder`
chosen in the form (`top.images.binary.order`: approvals then fewest rejections, by default;
difference; approvals only), or the legacy rule's score for a round without a stored order,
exactly as the fill ranks.
- Scores are counts, so each distinct key is a **level**, and every level boundary is a candidate
  cut in the window. C1's gap test and C2 don't apply.
- The reason names the level: "N = 37: all files with 3 yes and no no; the next 120 files have
  3 yes, 1 no."
- With more than 6 boundaries in the window, those nearest the target or the middle are listed;
  the gallery draws them all, and the N list offers them all.

**Several previous rounds:** the fill ranks a file by its best row, each scored in its own round,
so the list mixes rounds. The 5% tests use the largest range among them, and the gallery warns
"previous rounds on different scales" when they differ. A round has one `top_binary_order`, so all
its yes/no previous rounds are ranked by the same order, today and here.

**Code:**
- `services.TopNCandidates`: pure, from (keys, window, range, binary?) to the valid values and the
  candidate cuts with their reasons. Unit tested on rated, yes/no, tie, small-region and
  few-candidate shapes.
- `DistributionSource.rankedScores`: the `rank` method's pages (`ratedPage` / `binaryPage`) with
  the filters, read up to the gallery's end, so the preview equals what the fill takes.
- `views.html.cutGallery`: the partial.

**Place 1: the round form, step 1 "Which files"** (UI-path plan's numbered steps). In place of
today's number box "Number of top files from the previous rounds" (`top.images`), after "Choose N":

```
Number of top files from the previous rounds  N [ 37 · scores drop 7.2 → 6.1, largest drop ▾ ]   Target (optional) [ 40 ]
  Candidate cuts:  [N = 33 · scores drop 7.6 → 7.2]  [N = 37 · scores drop 7.2 → 6.1, largest drop]
                   [N = 46 · scores drop 5.9 → 5.5]
  Preview (N = 37): 37 of 412 files, lowest included score 7.2.
  (the cut gallery below, from place 1 to 55)
```

- "Choose N" (and a changed target) posts the form to a preview action that re-renders step 1 with
  its values, the N list, the candidates and the gallery. A candidate or "Cut here" is a submit
  button that sets N and re-renders the same way. It saves nothing and needs no script; a script
  may enhance it later.
- Saving keeps `top_images` as today. The choice (target, the candidates shown, the chosen cut's
  reason, or "chosen from the list") goes into the round's V114 change-log entry as
  `topImagesBasis`. So the round form and lineage can say "N = 37, chosen 2026-12-01: scores drop
  7.2 → 6.1". **No migration.**
- A re-apply, a resubmit or "Distribute new files" ranks with the stored N; nothing is chosen
  again. If the previous rounds' votes changed since, the preview warns.

**Place 2: each region's N in the report** (R2), as §10.5 shows. Each region's cut gallery is its
own GET page (`/contest/:id/regional-winners/cuts?round=…&region=…`, Admins and
Organizers). Its "Cut here" links back to the report with that region's N set in the re-jury
section's GET parameters, so the report stays read-only.

**Place 3: each region's number of winners** (R1), in the region's Accept form (§10.2): the same N
list over the region round's ranked files, default 1, with the gallery one click away. SN ships
first, in 26.11.18 (question 14 **[user]**), so R1 reuses its N list and gallery.

### 10.5 The re-jury round (decisions 19 [user] and 20 [user])

**On the report** (R2), Admins see a re-jury section under the table. Regions marked *needs
re-jury* are ticked; the other flagged regions are listed, unticked, each with its line (§10.3):

```
Re-jury these regions                                                       [Re-jury these regions]
[x] 07 Volyn O.    from [R-03 ▾]  N [ 5 · drop 7.9 → 6.4 ▾ ]  cuts: [2 · drop 8.4 → 7.1] [5 · drop 7.9 → 6.4]  [View gallery]
    Tie for 1st place: 2 photos at 8.50.
[x] 21 Zakarpattia from [R-03 ▾]  N [ 8 · all 8 candidates ▾ ]  cuts: [3 · drop 7.9 → 7.0] [8 · all 8 candidates]  [View gallery]
    Needs re-jury: "decide between the two night views".
[ ] 63 Kharkiv O.  from [R-03 ▾]  N [ — ▾ ]  cuts: [4 · drop 8.1 → 7.5] [8 · within 0.5 of the best (8.6)]
                                                [10 · drop 7.0 → 6.6]                                       [View gallery]
    Only 2 of 5 jurors voted on the top photo.
[ ] 44 Luhansk O.  from [R-02 ▾]  N [ — ▾ ]  cuts: [3 · drop 8.0 → 6.9] [6 · all 6 candidates]                [View gallery]
    No photo from this region reached the final round; its best photos are in round R-02.
```

- **N per region:** empty until the admin picks a valid value (a candidate, a "Cut here" in the
  region's gallery, or the N list), for a region of any size. A ticked region without N stops the
  button with "choose N for 63".
- **Source round per region** (question 10 **[user]**, 2026-11-26: (a)): chosen per region. The
  default is the round that decided the region in the report: its region round (§10.3), or, for a
  region missing from the final round, the last round it was still in (the round its line names,
  before the cut). The Admin can change it per region. Its scale and rank key are the round's own,
  so the candidate cuts are found within one round.
- **The button** opens the round form (GET, no write) at step 1, prefilled:
  `/admin/rounds/edit?contestId=…&rejury=07:5@102,21:8@102` (region, N, source round id;
  here 102 is R-03).

**The round form, step 1, prefilled:**

```
Step 1 of 3: Which files
Name                [ Regional re-jury 1 ]
Track               [          ]   (empty: lineage puts the round on its regions' track)
Rating              [ 1–10 ]
Regions, each with its own top files:
  07 Volyn O.     from R-03   top [ 5 ▾ ]   chosen: scores drop 7.9 → 6.4    [View gallery] [remove]
  21 Zakarpattia  from R-03   top [ 8 ▾ ]   chosen: all 8 candidates      [View gallery] [remove]
[x] Leave out conflicts of interest
Jurors per file     [ all jurors ]  "Every juror rates every file: each region gets the same attention."
[Save and find matching files]
```

- **What the form sets:**
  - `previous` = the distinct source rounds (so lineage has the edges);
  - `regions` = the ticked regions, so galleries' region menus, the "by rate" pages per region and
    lineage rule 4 ("regional") work as they do;
  - `top_images` empty: `region_top` and `top_images` refuse each other on save;
  - `exclude_coi` ticked; `distribution` 0 (all jurors, §10.6);
  - no manual track label. In slice A's `RoundLineage` the manual label is checked before rule 4
    (`label(r)` before `regionSubset(r)`). A label such as "Regional re-jury" would put every
    re-jury round on one Label track, shared by all of them and by the rounds that follow them,
    not on a Region track. Left empty, rule 4 puts the round on the Region track of its region set
    (named by its sorted region ids, e.g. "07,21"), which is the regional track lineage shows. An
    admin may still label it; the label then replaces rule 4, and the form's help says so.
- **Two region readings.** The per-image region filter (`IncludeRegionIds`,
  `ImageWithRatingSeqFilter.scala:42-44`) reads `Image.region`: the whole prefix before the first
  dash. The report and R3's SQL read `regionOf`: up to 3 characters (§1.1). They agree for every
  geographic region (two-digit KOATUU ids), which is all the re-jury offers (decision 10). They
  differ only for longer prefixes, which a re-jury never holds. R3 adds a spec that pins this;
  unifying the two readings is outside this plan.
- **One order for yes/no sources:** the round's single `top_binary_order` ranks every yes/no
  source of every region (as today for several previous rounds). Each region's `basis` records
  the order used.
- **Step 2 "Check the matching files"** shows the count per region ("07: 5 of 5; 21: 8 of 8"),
  with a short region named when it found fewer. **Step 3 "Jurors"** is unchanged.

**Stored on the round** (R3; migration number at build time): `round.region_top`, a nullable
TEXT holding JSON (CHECK `JSON_VALID`), validated in Scala: geographic regions of the contest, the
sources among `previous`, N from 1 to 50 and at a tie boundary of its source, at most one entry
per region.

```json
{"v": 1,
 "regions": [{"region": "07", "source": 102, "n": 5,
              "basis": {"chosen": "drop", "from": 7.9, "to": 6.4, "lo": 3, "hi": 8,
                        "candidates": [2, 5]}},
             {"region": "21", "source": 102, "n": 8,
              "basis": {"chosen": "all", "lo": 3, "hi": 3, "candidates": [3, 8]}}]}
```

- It is a round setting, so #178's `RoundChanges` logs it (create, edit, re-apply).
- Lineage's input summary reads it: "Top 5 of 07 from R-03, top 8 (all candidates) of 21 from
  R-03."
- The report and the export read it to say why a file is in the round.

**The fill** (`RoundDistributionHandler`):
- `initialState` starts at `Phase.Rank` when `region_top` is set, as for `topImages`
  (`RoundDistributionHandler.scala:415-417`).
- `Phase.Rank` ranks **one region per step**, the index in `DistributionState`, so each
  transaction stays short. For each entry it calls `DistributionSource.rank` with
  `prevIds = Seq(source)`, that source's denominator and order (`top_binary_order` for a yes/no
  source, the same for every region), `n`, the round's filter, and a new optional argument
  `region`: in `ratedPage` and `binaryPage`, `JOIN images ri ON ri.page_id = s.page_id AND
  <regionOf(ri.monument_id)> = ?` (`images_page_monument_idx`). Without it, a small region of a
  38k-file source would page through the whole round, since today's region filter is applied in
  Scala after each page (`DistributeImages.perImageFilters`, `includeRegionIds`).
- The picks are united (a file has one region, §1.1). `Context.topN`'s uses (`estimate`, the
  window) take the union's size.
- **"Distribute new files"** ranks again; as today, the files already in the round count in the
  ranking, so N doesn't grow.
- **Tie at a region's cut:** N is a tie boundary (§10.4: the N list has no value inside a tie, and
  the server refuses one). If the source's votes changed since, the fill cuts by page id as today,
  and step 2 says how many tied files were left out.

**In the report:** the re-jury round is the region round for its regions: a default column, first
(amended decision 17), with its own rank, and §10.3's cases computed over it. A second re-jury of
some regions comes before the first.

**One round per region** (question 7 **[user]**, 2026-11-26: one re-jury round with per-region N
as the goal (R3); one ordinary round per region as the interim until R3 ships, §10.8).
- *For the interim:*
  - today's form can make it: previous = the source, regions = one region, top N. The top-N's
    ranking applies the region filter before the cut (`DistributionSource.rank`'s `filter`), so
    it is the region's own top N;
  - jurors can differ per region (local experts);
  - each round can be started or stopped alone.
- *Against it as the goal:*
  - 10–20 rounds per loop clutter the rounds list, the report's columns and the jurors' menus;
  - jurors switch between many small rounds;
  - with different juror sets, regions don't get the same attention;
  - nothing ties the rounds together as one re-jury.

### 10.6 Equal attention and conflicts of interest

- **Every juror on every file** (decision 23, question 11 **[user]**, 2026-11-26): the form
  defaults to all jurors per file (`distribution = 0`). The Admin may change it, with no warning
  (`DistributeImages.assign` is region-blind, so with fewer jurors per file regions may get
  different jurors). A re-jury is small (27 regions ×
  about 5 files ≈ 135 files), so all jurors on all files is affordable.
- **One scale** for all regions, as it is one rated round.
- **The report shows votes per region** in the re-jury column ("07: 5 files, 13 of 13 jurors
  voted"). "Few votes" (§10.3) flags a region whose top photo got fewer.
- **Conflicts of interest:**
  - `exclude_coi` is ticked by default. `CoiMembers.forContest` covers the contest's admins,
    organizers, jurors, board, auditors, staff and root (`CoiMembers.scala`), so a local juror's own
    photos never reach the re-jury. Decision 12 already keeps them from winning.
  - **Local jurors judging their neighbours** have no data in the app: no juror has a region.
    Nothing in the app now (question 12 **[user]**, 2026-11-26: local bias matters more for the
    country's top than for regional winners, and choosing a good jury is the organizers' duty).
    Later, a
    per-juror "abstains for region X" could skip their assignments for that region's files (a table
    and a change in `assign`), at the cost of unequal juror counts per region.

### 10.7 The loop's end and the exports

- **The report's header** counts the regions: "27 regions: 19 accepted, 5 need re-jury, 3
  undecided". A filter shows "not accepted", "needs re-jury" or "flagged".
- **When every region with files is accepted:** "All regions are done" and the accepted export is
  offered first.
- **`export=accepted`** (the default once the contest has an outcome; amended decision 11):
  - **Wiki text:** per region, its winners in order (one winner: "Regional winner"):
    ```
    == Volyn O. ==
    <gallery>
    File:Example 1.jpg|Regional winner (R-03)
    </gallery>

    == Brussels ==                                (3 winners)
    <gallery>
    File:Example 5.jpg|1st place (R-04)
    File:Example 6.jpg|2nd place, shared (R-04)
    File:Example 7.jpg|2nd place, shared (R-04)
    </gallery>
    ```
    Comment lines at the top list the regions not accepted yet (`<!-- not accepted yet: 21, 63
    -->`), those accepted with no winner (`<!-- no winner: 44 -->`), and those whose winner was
    deleted on Commons (`<!-- accepted winner deleted on Commons: 07 -->`).
  - **CSV:** region, place, file (both empty for "no winner"), round, accepted at.
  - **Never exported:** notes, who accepted, and winners deleted on Commons
    (`images.deleted_at IS NULL`, decision 13).
  - It goes through the same paths and guards as phase 2 (`controllers.Csv`, `ExportsSpec`).
- **National winners** (decision 14) are unchanged: marked, or skipped per view. An accepted
  regional winner that is also a national winner is exported in both places unless the contest's
  rules say otherwise (§9).

### 10.8 Priority order

The round improvements of 26.11.17 and 26.11.18 come first: 26.11.18 brings the numbered setup
steps (UI-path plan, draft PR #189) that the round form's cut picker (§10.4) and the re-jury's
prefilled step 1 (§10.5) build on, and the cut picker for main rounds itself (SN; question 14
**[user]**, 2026-11-26: "now, in 26.11.18, before the regional report"). Then, one builder at a
time (the shared run lock), in this order (question 13 **[user]**, 2026-11-26):

| Priority | Work | Size | Why here |
|---|---|---|---|
| 0 | Round improvements 26.11.17 and 26.11.18 (incl. the numbered setup steps) | (their own plans) | §10.4–§10.5 build on the setup steps |
| 0' | SN cut picker (main rounds' top-N), in 26.11.18 | 2.5–3.5 d | question 14 **[user]**: now, before the regional report; R1–R3 reuse it |
| 1 | Lineage slice A (#193, being built) | 3–4 d | the report's default final round and columns (decision 15) |
| 2 | Report phase 1 | 2.5–3 d | what the jury final needs first |
| 3 | R0 flagged regions | ½–1 d | small; points the session at the regions to discuss |
| 4 | R1 region outcomes (Accept region) | 1.5–2 d | the winners, the re-jury's input |
| 5 | Phase 2 exports + 2' accepted | 1–1.5 d | the winners' list |
| 6 | Lineage slice B (Cancel / Reinstate) | 2.5–3.5 d | after phase 2 (question 13 **[user]**); decided to follow phase 1 (lineage §8.1) |
| 7 | R2 per-region N in the report | 1–1.5 d | reuses SN |
| 8 | R3 the re-jury round | 3–4 d | reuses SN, R1 and R2 |
| — | ~~R4 pasted results~~ | — | dropped: question 9 **[user]**, results come from votes |

- **The jury final needs the report (phase 1) first.** R0, R1 and the exports follow. If they
  land after the session, the regions are accepted afterwards from the votes of the session's
  round.
- **An offline session before R3** votes in an ordinary round the Admin creates (§10.1): one
  round per region, or one round of several regions with today's region filter.
- **A re-jury before R3** (question 7 **[user]**: the interim) uses today's form, one round per
  region: previous = the region's source, the region filter, top N chosen per region,
  all jurors per file, CoI left out, no manual track label (rule 4 gives each its region's track).
  The report (phase 1) shows these rounds under "Show all rounds" as regional rounds; once R3
  lands, a re-jury round is the region round for its regions.

### 10.9 Tests (v4; updated 2026-11-26)

- **`TopNCandidatesSpec`** (unit): C1–C5 on rated lists, at most 6, duplicates merged, never N = 0,
  none above 25 for a region; the valid values never include an N inside a tie; nothing
  preselected; a small region lists "N = m: all candidates" (m ≤ 25); the window
  `[min(3, m), min(15, max(lo, ⌈m/3⌉))]`; yes/no levels under each `BinaryOrder` and the legacy
  rule, the reason naming the level; ties at `lo`, at `hi` and across the whole window; few
  candidates; mixed scales; each reason's message arguments.
- **`RegionalFlagSpec`** (unit): each of the four cases, the order when several apply (one line
  only), a clear region's top k for k = 1 and 3 (a tie inside the top k is a shared place; a tie
  for 1st accepted as a shared 1st place with k = the tie's end), an accepted region (no line), a re-jury round as the region round,
  "not in the final round" naming the last round the region was in (and a hidden round), one juror
  of one voting (not flagged), "the top photos changed since they were accepted" (new votes, a
  cancelled round), never because of a report parameter.
- **`RegionalOutcomeSpec`** (DB, truncate isolation):
  - the CHECK refuses an accepted row with neither a round nor a note, and a `needs_rejury` row
    with a round; an accepted row with a round always has winner rows, in place order, a shared
    place stored as equal places; a file wins at most once per contest;
  - one row per region; Accept on a flagged region is refused; the winners are the server's top k,
    whatever the request says; a k inside a tie, above 10 or above the region's positive files is
    refused;
  - each write logs `regional_outcome_set` with from and to; Clear;
  - a stale form is refused;
  - a region outside the contest's geographic regions is refused.
- **Controller (negative authorization):**
  - an organizer, a juror, an admin of another contest and a signed-out request (JSON 401 and the
    login page) are each refused on every outcome POST;
  - an organizer sees outcomes and notes read-only, and a juror gets 403 on the page;
  - CSRF is enforced (`CsrfTestApp.withCsrfApp`);
  - notes never appear in the wiki text or the CSV.
- **Distribution (R3):** per-region ranking equals "by rate" per region in the source; a region's
  "all candidates" N takes all its files; "Distribute new files" doesn't grow N; `region_top` with
  `top_images` is refused, and so is an N inside a tie; a resubmit and a Retry keep the regions;
  the region SQL condition's query plan uses `images_page_monument_idx` (query-plan checks plan).
- **Round form (SN):** the free top-N box is gone; "Choose N" saves nothing; the preview's count equals the fill's; the N list
  offers only valid values; a candidate and "Cut here" set N; a crafted N inside a tie is refused
  on save; the gallery never splits a tie; `topImagesBasis` is logged; a juror and an organizer
  can't reach the preview.
- **Migrations:** `FlywayUpgradeSpec` and `MigrationNamingSpec` for each migration, with the undo in
  its comment.
- **Screenshots** of the report's region controls and lines, the re-jury section and the
  prefilled form, in both themes, at 1366 and 390 px wide.

### 10.10 Later (not this plan's scope)

- **Jurors vetting rounds:** jurors confirm a round's results or settings (raised by the
  maintainer, 2026-11-26).

---

## 11. Questions for the maintainer (v4, 2026-11-25; decisions of 2026-11-26 marked)

One decision each, asked one by one. The recommendation comes last in each.

1. **How N is chosen.** **Decided [user] (maintainer, 2026-11-25):** not a single suggested N:
   "show possible numbers and a gallery to view them and decide". The app lists candidate cuts
   (C1–C5, §10.4), each with its reason, and a cut gallery; the admin picks N; the chosen N is
   stored. Asked as: (a) the largest gap as one suggested N; (b) a fixed share; (c) natural breaks
   (Jenks); (d) a fixed distance from the best. The recommendation was (a).
2. **A region's window ("amount").** Asked as: `lo = min(3, m)`, `hi = min(10, max(lo, ⌈m / 5⌉))`,
   or fixed bounds of 3–10; the recommendation was the formula.
   **Decided [user] 2026-11-26:** the m/5 formula is rejected. First decided as "small regions:
   all their files, up to an Admin setting `S`; large regions: cut among the best"; later the same
   day **[user]**, `S` was dropped: "let the organizers decide on the top-N count they want to
   use." Defaults, from past Ukrainian contests: `lo = min(3, m)`, `hi = min(15, max(lo,
   ⌈m / 3⌉))`, candidate cuts up to 25, "all candidates" among them for a small region; the
   picker never preselects an N (§10.4).
3. **A typed N inside a tie.** Asked as: (a) not saved, the form offers the tie's boundaries; (b)
   saved and cut by page id with a warning; the recommendation was (a).
   **Decided [user] 2026-11-26:** "why would we allow entering a wrong value in the UI in the first
   place?" The N control offers only valid values (it steps through tie boundaries; an N inside a
   tie can't be entered). The server still refuses an N inside a tie from a crafted post (§10.4).
4. **Yes/no source rounds.** Asked as: (a) candidate cuts at the level boundaries of the chosen
   `top.images.binary.order`; (b) no candidates, only the gallery and the preview.
   **Decided [user] 2026-11-26: (a)**, the reason naming the level (§10.4).
5. **What makes a region unclear.** Asked as: accept v4's six rules (tie at the top, thin votes,
   close scores, only yes/no votes, dropped by a contest-wide cut, all candidates rejected).
   **Decided [user] 2026-11-26, simplified:** no rule codes on the page; a flagged region gets one
   plain line with the next step. Four cases only: needs re-jury (an Admin's mark, e.g. all
   candidates rejected); no photo from the region reached the final round ("its best photos are
   in round 3", the WLE 2026 case); a tie for 1st place; few votes on the top photo ("2 of 5
   jurors voted"). Dropped: close scores and yes/no only. Thresholds tuned on real data (§10.3).
6. **An organizer as the session's secretary?** Asked as a per-contest grant for one organizer to
   enter the session's results; the recommendation was no grant.
   **Decided [user] 2026-11-26: no grant.** "Why mix up admin and jury? Jury does normal jury work
   in rounds created by admins." Jurors never get admin-like actions; an offline session's choices
   go in as normal jury work in a round an Admin creates (e.g. one juror account voting for the
   offline jury). Recording outcomes stays Admin-only (§10.1, §10.2).
7. **One re-jury round, or one per region.** **Decided [user] 2026-11-26:** one re-jury round with
   per-region N as the goal (R3); ordinary per-region rounds (today's region filter and top N) as
   the interim (§10.5, §10.8).
8. **What "done" (accepted) requires.** Asked as: (a) one admin's click, with at least one winner
   or a "no winner" note; (b) also a session record; (c) two admins.
   **Decided [user] 2026-11-26: (a).** A region is done by one Admin's "Accept region" click; it
   needs a winner or a "no winner" note; the change log records who and when (§10.2).
9. **How the offline session's results get in.** Asked as: (a) per file in the report, Accept with
   place or Reject with note; (b) a pasted list; (c) both.
   **Decided [user] 2026-11-26: neither per-file entry nor a pasted list.** Results come from the
   votes in the region round (the re-jury or the final round). When a region is clear (none of
   question 5's cases), its winner is its top-1 in that round (later that day: its top k,
   chosen per region when accepted, default 1), recorded by the Admin's "Accept region" (question 8). Flagged
   regions go to a re-jury round. What is stored is the accepted outcome per region (§10.2). R4
   is dropped.
10. **The re-jury's source round per region.** Options:
    - (a) per region, defaulting to its deciding column, or to the round before the cut for a
      region missing from the final round, and changeable;
    - (b) one source for all regions (e.g. `F`).

    (b) can't re-jury a region `F` doesn't hold, which is the WLE 2026 case. The recommendation
    was (a).
    **Decided [user] 2026-11-26: (a).** Chosen per region; the default is the round that decided
    the region in the report (for a region missing from the final round, the last round it was
    still in, before the cut), and the Admin can change it per region (§10.5).
11. **Jurors per file in a re-jury.** Options:
    - (a) all jurors on every file, as the default with a warning when changed;
    - (b) the admin's choice, no default.

    Equal attention needs the same jurors on every region, and a re-jury is small (about 135
    files). The recommendation was (a).
    **Decided [user] 2026-11-26:** all jurors on every file by default, **no warning** when the
    Admin changes it (§10.6).
12. **Local jurors** (not authors) judging their own region. Options:
    - (a) nothing in the app now; the offline session handles it;
    - (b) a per-juror "abstains for region X" (a table, a change in the assignment, unequal juror
      counts).

    CoI already removes jurors' own photos. The recommendation was (a), revisited if a juror asks
    to abstain.
    **Decided [user] 2026-11-26: (a)**, nothing in the app now. The maintainer's reasoning: local
    bias would matter more for the country's top than for regional winners, since each region
    gets its own winner while the country's winner can come from any region; and choosing a good
    jury is the organizers' duty (§10.6).
13. **Priority: the outcomes record or slice B first?** Slice B (Cancel / Reinstate) is decided
    to follow regional winners phase 1 (lineage §8.1). R1 and the accepted export are what the
    final's results need. Slice B tidies trial rounds; meanwhile the report names a trial round
    in its tie warning (decision 3), and the admin can pick the final round.
    The recommendation was phase 1 → R0 → R1 → phase 2 with 2' → slice B.
    **Decided [user] 2026-11-26:** phase 1 → R0/R1 → phase 2 (with 2') → slice B (§10.8).
14. **Where the cut picker goes in the order.** Options:
    - (a) after slice B, before R2–R3, which reuse it;
    - (b) before R1, if a national top-N cut is still to be made for WLM 2026.

    It is useful on its own for every contest's main rounds; R2–R3 need it in any case, and its
    gallery is now a larger part (2.5–3.5 days).
    The recommendation was (a), unless WLM 2026 still had a national top-N cut to make.
    **Decided [user] 2026-11-26:** the cut picker for main rounds now, in 26.11.18, before the
    regional report (§10.8).

**Open points:** none (2026-11-26). The last two were decided **[user]** that day: ties with
several winners as proposed (§10.2, §10.3), and files the offline jury rejected may come back in a
re-jury from an earlier round, with no exclusion rule (§10.2). Question 12 was decided **[user]**
the same day: (a), nothing in the app now.
