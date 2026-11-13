# Round lineage: a rounds map, file provenance, backtracking and round cleanup (plan v5)

**Status:** decisions taken by the maintainer, 2026-11-10/11 (§9). Nothing is built.

**Revisions:**
- **v2:** the first review, and the question "HTML table instead of SVG?".
- **v3:** the second review, and **phase 0**: the track rule run on WLM Ukraine 2025's 61 rounds
  (§10).
- **v4:** the third review, and three maintainer directions:
  - no overlap guessing: in-app actions for everything that needed DB work (§11);
  - Q-02-nofop was a real step;
  - manual archive and delete instead of heuristics.
- **v5:** the maintainer's decisions on all 18 open questions (§9 is now "Decisions"):
  - no archive flag: P3's `round.status` comes forward into V101, and `cancelled` is the hidden
    state (decision 5);
  - provenance by round type: shown in a pre-screening round, hidden in a jury round the viewer
    votes in (decision 11);
  - no map actions and no unsaved-form count for now; the map is read-only apart from Cancel,
    Reinstate and Delete (decision 12; decision 13 deferred with it);
  - §11 confirmed: item 5 is built here (phase 3b), item 8 belongs to P3, items 3 and 9 are not
    planned (decision 18);
  - WLE prefix nominations are checked before phase 1 (decision 3).
- **v5, after review** (2026-11-11): provenance loads on demand (decision 10+); the maintainer's
  R1–R3 (decision 11's reading, Reinstate, phase 3b); status writers, locks and V101's DDL fixed
  (§2.6). See "Review".

See "Review" at the end.

**Sources:** the survey is read-only, of two trees:
- `ukwikidev/followups`;
- `stack/103-exclude-round-files` (#286) at **`ed740ddf`**: V95 at
  `9c96675a`, then its review fixes, committed and clean.

Line citations are to `ed740ddf`; later commits will shift them. Inferences are marked *(inf.)*.

**The request (2026-11-10, paraphrased).** WLM Ukraine 2025 has 61 rounds, some created by mistake.
Track the rounds' lineage:
- quality and quantity screening rounds are joined into the main rating rounds;
- those feed regional winner-selection rounds;
- those can go back to earlier rounds when the latest have no good photos of a region;
- each special nomination has its own track.

"Useful regardless of DB storage."

**Later directions (2026-11-10):**
- "Implement all that needed direct DB work in the app, so it would be tracked without guessing,
  so there would be no missing links."
- "We would need a way to hide/archive/delete rounds by hand so we would not need to guess/draw
  them too."

**Short answer.**
- **Merges are the core path.** Quality + quantity → main rating rounds → regional rounds is the
  shape the map must show first.
  - A merge is recorded as one round with several previous rounds.
  - A regional backtrack is recorded as previous + leave-out (V95) + a region filter.
- **2025's data doesn't show those steps.** WLM UA 2025 recorded no merges and no regional steps.
  Several previous rounds weren't implemented then, so the maintainer did those steps by hand in
  the DB, copying files and votes. Future contests will record them.
- **No guessing.** Every operation that needed direct DB work is listed with its in-app action,
  which records its edge, or its status: supported, planned, or not planned for now (§11,
  decision 18). No overlap analysis.
- **Cleanup is manual.** Admins cancel (hide) rounds by hand from the rounds list and the map, and
  reinstate them. "Cancelled" is a value of P3's `round.status`, not a separate flag (decision 5).
  A round with no juror input can also be deleted (decision 9). No heuristic flags rounds as
  mistakes.
- **The map is read-only** for organizers. Admins get only Cancel, Reinstate and Delete there.
  Backtracking and merging go through the normal round form (§4, decision 12).
- **Phase 0 on the real contest** (§10): the rule gives 13 tracks with several rounds each, all
  correct (6 category tracks, 7 nomination tracks), 8 positions wide. The clutter is 11 "[test]"
  rounds, which cancelling them by hand removes.
- **Before phase 1:** rule 2 (WLE prefix nominations) is checked on a WLE Ukraine rounds export
  (decision 3).
- **Storage.** The read-only map and provenance need no new table. One `Round.findByContest`
  (≤100 rows) builds the graph in memory.
- **One migration, V101, in phase 1** with the map:
  - `round.status`, P3's column, with its backfill, and `status_changed_at` / `status_changed_by`;
  - the manual track label, `round.track`;
  - `round.created_at` without `ON UPDATE` (§1.4, decision 17).

  A 2025-style contest is hard to read until its trial rounds are cancelled.

---

## 1. Data available today

### 1.1 Edges

| Edge | Source | Derivable? | Notes |
|---|---|---|---|
| previous → round | `round.previous` CSV (V13; CSV since V52), `Round.previousIds` (`Round.scala:190-193`) | yes | **Each previous round is evaluated on its own**, with its jurors, votes and file score; a file is taken if it passes in **any** of them (`docs/user/admin-guide.md:461-464`; `DistributionSource.previousCandidates`, one row per file and previous round, `RoundDistributionHandler.scala:984-995`). Same contest (`previousRounds`, `:746-759`), same rating (`Round.sameRateType`, `Round.scala:481-490`). No CHECK; the parser skips any part that isn't an id. |
| excluded → round | `round.exclude_rounds` (V95), `Round.excludeRounds` (`Round.scala:62-64, 349`) | yes | Other rounds of the contest, none also a previous round (`excludedRoundsError`, `RoundDistributionHandler.scala:808-826`). A missing id is refused (`ExcludeRoundsMissing`, `:838`). A CHECK on its form. Not on production (V70). |
| "Distribute new files" | `job` rows | no new edge | Re-reads the round's `previous` and filters (`RoundService.scala:244-296`); `params.settings` keeps a copy (`:188-215`). |
| file → round, and how | `round_image` (V84): `status`, `added_how`, `added_by_job_id`, `added_at` (`V84__Round_image.sql:33-50`) | yes, per file | **Not on production yet** (V70). The V84 backfill builds it from the votes, so files copied by hand appear too, as `legacy`. |
| steps done by hand in the DB | — | **no** | The 2025 merges and regional steps (§10.4). Remedy: in-app actions that record them (§11), not guessing. |
| another contest's round → round | — | no | Only planned (`2026-10-29-import-top-of-another-contests-round.md`). |

**No cycles and immutable edges: true today, not by contract.**
- **Today:** the selects offer only existing rounds and are disabled on an existing round
  (`imageFiltering.scala.html:27-32, 118`). `Round.updateRound` writes only `name`, `average_rule`
  and `worst_mark` (`Round.scala:412-416`). Phase 0 found no edge to a larger id.
- **After P3:** filters become editable while a round is paused
  (`2026-11-02-round-images-two-step.md:296-318`, a new `Round.updateFilters`). So
  `RoundLineage` orders the rounds with Kahn's algorithm, reports any cycle, and provides
  `wouldCreateCycle(roundId, newPrevious)`, which `updateFilters` must call.
- **Leave-out edges don't place rounds,** so a forward leave-out is just the text "leaves out R".

**Rounds can disappear.** Today no code path deletes a round. Decision 9 adds a narrow delete that
refuses any round another round refers to. SQL can still delete one, and the code expects it:
- `ExcludeRoundsMissing` refuses a missing excluded round;
- `vote_orphan` holds votes whose round is gone;
- `vote.round_id` has no foreign key.

An id that isn't found becomes a ghost cell, "missing round N".

### 1.2 Can filters label the input?

Filters are stored on the round being filled. With several previous rounds they are applied **in
each of them** and the results united: "from any of Q-00, Q-01: selected by ≥1".

| Filter | Stored as | Label | Reusable text | WLM UA 2025 |
|---|---|---|---|---|
| Selected by ≥N | `prev_selected_by` | "selected by ≥1" | new key | 29 rounds (one more stores 0, no filter) |
| Rejected by ≥N, mode | V74, V93 | "without files rejected by ≥2" / "only those" | `RejectedMode.messageKey` | not at V70 |
| Min score | `prev_min_avg_rate` | "score ≥ 4.9" | new key | 10 rounds |
| Top N, order | `top_images`, V72 | "top 50 (by approvals)" | `BinaryOrder.messageKey` | none |
| Regions | `regions` CSV | "Kyiv, Lviv"; **all real regions = no region** | `RegionScheme.region` | 16 rounds with all 27; one subset (Обласні, 8) |
| Monuments | `monuments` | "12 monuments" | new key | none |
| Category operation | `category`, `exclude_category`, `category_clause` | "in X minus Y" | `CategoryOperation` keys | the roots' categories |
| Special nomination | `special_nomination` | its name | WLM Ukraine only (`RoundController.scala:254-266`) | 10 roots |
| Min Mpx, size, media | `min_mpx`, `min_image_size`, `media_type` | "≥2 Mpx", "video" | new keys | `media_type` only |
| CoI | `exclude_coi` | "conflicts of interest left out" | `coi.*` | — |
| Leave out rounds | `exclude_rounds` | "leaves out R" | `exclude.rounds` | not at V70 |
| Jurors per file | `distribution` | "each file to 3 jurors" | `each.file.to.i.juror.s` | — |

**Labels.** Every filter can be labelled. A new pure `RoundLineage.inputSummary(round, scheme)(messages)`
writes them.

**What's missing:**
- **A track:** derived (§2.3), or a manual label (decision 4).
- **A purpose:** the multi-role `round_type` (V97), plus names *(inf.)*.
- **A lifecycle state** ("finished", "cancelled"): not stored. P3's `round.status` (`draft | paused
  | active | finalized | cancelled`) comes forward into V101 (§2.6, decision 5); phase 1 writes only
  `cancelled` and the backfill's values.

### 1.3 In memory per contest: confirmed

`Round.findByContest` (≤100 rows) is already read by the rounds page (`RoundController.scala:46`).
Kahn's algorithm is O(n + e); phase 0 built 61 rounds and 43 edges instantly. The combined-votes
plan specifies the same walk for D9 (`2026-11-10-combined-juror-votes.md:152-161`).

**One shared `services.RoundLineage`:**
- **What it provides:** graph, order, ancestors, descendants, tracks, `inputSummary`,
  `wouldCreateCycle`.
- **Who builds it:** whichever lands first, lineage phase 1 or V96. For D9, leave-out edges are not
  "later rounds".
- **Who reads through it:** the map, the menus, form help, provenance, `ScoreRuleWarning.laterRounds`
  and D9.
- **Who keeps reading `previousIds` directly:** distribution, freezing and validation.

| Number per round | Query | Cost |
|---|---|---|
| Files | `RoundImageCounts.get(ids)(ImageJdbc.imageCountByRounds)` (`RoundController.scala:51`) | cached, on the rounds page already |
| Job state | `JobJdbc.latestBySubjects` (`:52`) | on the rounds page already |
| Jurors (active/all) | new `RoundUser.countsByRounds(ids)`, `GROUP BY round_id` | tens of rows |
| Files from a list or "new files" | `GROUP BY round_id, added_how` on `round_image` | measure first *(inf.: ~200k rows)*; cache or drop |
| Share of votes cast | `vote` GROUP BY | too heavy *(inf.)*; once P2a's counts are read |

### 1.4 Creation order: `id`, not `created_at`

**Production's `round.created_at` changes on update.** It has
`DEFAULT current_timestamp() ON UPDATE current_timestamp()` (`prod-v53-myisam-schema.sql`).
V86 gives servers without it the same definition: on 10.10+ it *adds* `ON UPDATE`
(`V86__Timestamp_defaults_as_on_10_6.sql:9, 28`). So every UPDATE of a round row that doesn't set
`created_at` itself overwrites it.

**Which writers keep it:**
- **Preserve** (`created_at = created_at`): `Round.updateRound` (`Round.scala:415`) and
  `RoundCounts` (`RoundCounts.scala:108, 289`).
- **Overwrite:**
  - `setActiveGuarded` (`Round.scala:506-511`, every start and stop);
  - `freeze` and `release` (`:562-584`, every distribution from the round);
  - `completeCreation` (`:588`);
  - `setExcludeCoi` and `setActive` (`updateById`);
  - `setInactiveAllInContest`.

**Phase 0 shows it:**
- 6 of 43 previous edges point backwards in time. For example, Q-02-nofop was "created" at 06:10
  from Q-02, which was "created" at 14:11 the same day.
- 224 pairs disagree between id order and `created_at` order.
- On 2026-05-06, 10 rounds carry times from 08:54:58 to 08:56:55, seconds apart: they were stopped
  one by one, not by one statement.
- Only 2026-10-22 09:37:12 (5 rounds, the same second) looks like a single statement *(inf.)*.

**Consequences:**
- Positions and "created before" use **`id`**.
- `created_at` is shown nowhere as a creation time.
- V101's setters write `created_at = created_at`.
- Decision 17 drops `ON UPDATE` in V101.

---

## 2. The lineage view ("rounds map")

### 2.1 Page and access

- **Route:** `GET /contest/:contestId/rounds/map` →
  `RoundLineageController.map(contestId, cancelled: Boolean ?= false)`.
- **Permission:** `withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES,
  Some(contestId)))`, the set of `User.canViewContestFiles` (`User.scala:119-120`).
- **Refusals:**
  - a non-member or juror gets 403 first;
  - only root sees a 404 for a missing contest;
  - after V97, a pure pre-screener gets 403 (negative spec).
- **Read-only for organizers** (decisions 1, 12). Admins get only these actions, CSRF POSTs
  (§2.6):
  - Cancel / Reinstate on each cell (phase 1);
  - Delete where allowed (phase 3, decision 9).

  There are no new-round links: backtracking and merging go through the normal round form (§4).
- **Linked from:** the rounds page (admins, `rounds.scala.html:83-96`) and the round stats pager
  (`roundStat.scala.html:9-17`).

### 2.2 Rendering: an HTML table now, SVG lines only if needed

The maintainer asked (2026-11-10): "do we need svg? … maybe html table with colspans/rowspan can
suffice?" It can. Phase 0 (§10.2) is the table in Markdown.

**Constraints:**
- **Shipped:** jQuery 3.7.1, Bootstrap 5.3.8, bootstrap-icons and Tom Select are webjars
  (`build.sbt:274-280`); unitegallery, raty and hotkeys are vendored. No graph library.
- **CSP** (`conf/application.conf:116-131`): `script-src 'self'` plus two hashes,
  `style-src 'self' 'unsafe-inline'`.
- **The binding rule** is the project's: no inline script, no inline style added, no CDN (UI §2.1,
  secure coding §5).

| Option | For | Against |
|---|---|---|
| **A. Tracks × positions as plain HTML tables (phase 1)** | No JS, no dependency, no endpoint; Bootstrap tables, both themes, screen readers, narrow screens; relations are text and links | No drawn lines |
| B. Server-side SVG over the same grid (optional phase 4) | Lines show merges, forks and backtracks at a glance | Routing, Cyrillic truncation without font metrics, still needs a text form: L |
| C. A JS library (dagre, elkjs, cytoscape) | Better crossing reduction | Dependency review, a script and an endpoint; mermaid writes `style` attributes |

**How it's drawn.** Server-rendered Twirl from `RoundLineage.grid(contest)`: row groups (tracks),
each with sub-rows of cells (`RoundCell`, `Empty(cols)`). Positions are computed in Scala and
unit-tested.

```html
<div class="table-responsive wlx-lineage-box">      <!-- scrolls on its own, as the rounds page -->
<table class="table table-sm table-bordered wlx-lineage">
  <caption>Rounds by track. Positions: left to right within a track and along "from" links</caption>
  <thead><tr><th scope="col">Track</th><th scope="col">Position 1</th> … </tr></thead>
  <tbody>                                              <!-- the main track first -->
    <tr><th scope="rowgroup">Main</th><td></td><td></td>
        <td id="round-31">M-01 · from Q-01 + Qt-01 …</td><td id="round-35">M-02 …</td></tr>
  </tbody>
  <tbody>                                              <!-- then its feeders, in joining order -->
    <tr><th scope="rowgroup">Quality</th><td id="round-20">Q-00</td><td id="round-25">Q-01 · feeds M-01</td> … </tr>
  </tbody>
  <tbody><tr><th scope="rowgroup">Quantity</th> … </tr></tbody>
  <tbody> … nominations … </tbody>
</table></div>

<table class="table table-sm wlx-lineage-regions">   <!-- every region track, in one place -->
  <caption>Regional rounds</caption>
  <thead><tr><th scope="col">Regions</th><th scope="col">Rounds</th><th scope="col">From</th><th scope="col">State</th></tr></thead>
  <tbody><tr><th scope="row">Kyiv</th>
         <td><a href="#round-40">K-01</a> → <a href="#round-44">K-02 (backtrack from Q-01)</a></td>
         <td><a href="#round-35">M-02</a> (Main)</td><td>Stopped</td></tr></tbody>
</table>
```

**Structure:**
- **A track is a `<tbody>`** with one `<th scope="rowgroup" rowspan=…>` over its sub-rows, so a
  screen reader announces each cell with its track.
- **A fork opens a sub-row** with "from X" (§2.3).
- **Merges are the core path**, written as "from A + B" in the merge round's cell. The feeding
  tracks sit directly under Main, in the order they join, and their cells say "feeds M-01".
- **Merge `rowspan` is the first addition if needed.** A cell spanning the feeders' rows comes
  later, if the text form proves hard to read (§2.3 has its rules).
- **The regions table holds every region track**, with or without several rounds:
  - columns Regions | Rounds | From | State, the same in §10.2;
  - the Rounds column chains them ("K-01 → K-02 (backtrack from Q-01)");
  - From names the source round and its track.
- **`colspan`** only joins runs of empty cells.

**Relations in a cell:**
- "from A + B";
- "leaves out Q-01";
- "backtrack from M1";
- "feeds …".

Each is an in-page link. CSS `:target` highlights the cell reached, with no JS.

**Layout and themes:**
- **Wide tables:** the tables scroll inside their box (`.table-responsive`, as at
  `rounds.scala.html:35-37`), with a sticky track column (in `main.css`) and cells of about 11rem
  minimum. Phase 0 gives 8 positions.
- **Mobile:** the same box. The regions table wraps.
- **Dark theme:** Bootstrap's table variables; accents are classes over `--wlx-*` in `theme.css`.

**Screen readers:**
- a caption;
- "Position n" column headers;
- `rowgroup` headers;
- every relation as text.

A view spec asserts these.

**What it loses compared with lines:**
- a merge, one round feeding many, and a long backtrack are read from text and links, not seen;
- there is no hover highlight of ancestors.

**Filter summary.** It goes in the round's cell: it describes the input as a whole. The full text is
in the cell's `title` and the regions table.

### 2.3 Tracks, sub-rows and positions (rule v4)

**Track rule.** One numbering, used everywhere. The first match wins:
1. **Own nomination:** its own `special_nomination` → that nomination (kind *nomination*).
2. **Prefix nomination:** its own `regions`/`monuments` mapping to **one nomination** by
   monument-id prefix, through `RegionScheme`'s `byPrefix` (`RegionScheme.scala:17, 23, 50`). For
   WLE; phase 0 doesn't exercise it. It is checked on a WLE Ukraine export before phase 1
   (decision 3).
3. **Inherited nomination:** previous rounds all in one *nomination* track → that track. A
   regional round of a nomination stays in it.
4. **Region subset:** its own `regions` a **real subset** (all real regions is no region) → a
   region track, in the regions table. From names the source round and its track (e.g. "Q-02
   (Quality)").
5. **Inherited track:** previous rounds all in one track → that track.
6. **Merge:** previous rounds in different tracks → **Main**. This is the core path:
   quality + quantity → main rating rounds.
7. **Root scope:** a root takes:
   - its category minus **the contest's common prefix** (defined below);
   - else its media type ("Video");
   - else its name.

   Roots of the same scope share a track.

**The contest's common prefix.** Take the longest common prefix of the roots' categories, leaving
out the contest's own category (`ContestJury` images category). Cut it back so it ends at " - ".
With no such prefix, the full category name stays. For WLM UA 2025 that is
"Category:Wiki Loves Monuments in Ukraine 2025 - ". Phase 0's script hard-codes it (§10.1).

**Main and its feeders:**
- Main's rows come first.
- Directly under them are the tracks that feed Main, in the order they join (the id of the first
  merge round that takes each).
- After them, the other category tracks by name, then nomination tracks by name, then the regions
  table.
- A merge into Main may also take a round already in Main (e.g. M-03 from M-02 + Qt-02); it stays
  in Main.

**When merge `rowspan` is added** (only if the text proves hard to read):
- a spanning cell occupies every spanned row in the column rule;
- a later Main round spans the union of the feeder rows merged so far, in joining order;
- the `rowgroup` header and `headers` attributes cover the spanned rows, with a view spec.

**Sub-rows within a track (forks):**
- The **spine** is the chain from the track's first round to its deepest round, the latest by id on
  ties. It skips cancelled rounds and takes the first row.
- Another round continues the sub-row whose last cell is its previous round. Otherwise it opens a
  sub-row and shows "from X".
- Real side branches stay visible as sub-rows: Q-02-nofop (§10.3), and Q-00-1 from Q-00 leaving out
  Q-01.

**Positions:**
`col(R) = max(1 + max col(previous rounds of R), 1 + col(previous cell in R's sub-row))`.
- Kahn order over the graph, `id` order within a sub-row (§1.4).
- The caption says positions run left to right within a track and along "from" links.

**Backtrack.** A→R is a backtrack if a round S with a smaller id than R descends from A and is
either in R's track, or in the track R's region subset came from, or left out by R. The cell says
"backtrack from A".

**Labels** are truncated by character count, without font metrics.

**Manual label** (V101, phase 1; decision 4):
- it names the round's track and is inherited by its later rounds without one;
- identical labels share a track;
- "Main" puts the track in the Main group.

**Quiet** (Cancel and delete guards, §2.6). A round is quiet when it has no queued or running job,
and either:
- `distributing = 0`, or
- its latest creation job failed or was cancelled.

A failed or cancelled creation keeps `distributing = 1` forever:
- only `Round.completeCreation` clears it (`Round.scala:588`);
- the job's `end` only releases frozen rounds (`RoundDistributionHandler.scala:291-317`);
- `distributingNow` and `openCond` count it as busy (`:831-832`; `Round.scala:538-540`).

**No mistake heuristics** (decision 8). The map shows facts only: states (§2.4), including what
admins cancelled. It doesn't guess which rounds are mistakes. v3's structural "trial" rule would have flagged
real side branches such as Q-02-nofop, so it is dropped.

### 2.4 Cell content

- **Line 1:** "number · name", built from both fields. `Round.description` gives one or the other
  (`Round.scala:93-96`).
- **Line 2:** rating type (`views.RatingType.label`), criteria, half stars; a pre-screening badge
  with V97.
- **Line 3:** jurors (active/all), files, and the state, from `round.status`, the freeze columns
  and the latest creation job:
  - Active;
  - Being created (job %);
  - **Creation failed** (`draft`, its latest creation job failed; Retry works, decision 7);
  - **Creation incomplete** (`draft`, reinstated after a cancelled creation, or with no creation
    job at all; finished by "Distribute new files", §2.6);
  - Frozen (`freeze_count > 0`);
  - Stopped (`paused`);
  - **Cancelled**, with who and when (`status_changed_by`, `status_changed_at`). A creation the
    admin cancelled is one of these.

  `active_before_freeze` isn't loaded by `Round`; the map reads it. `finalized` isn't written before
  Montage parity (§2.6).
- **Line 4:** relations, and `inputSummary` cut short.
- **Links:** stats (both roles); the round form, Cancel / Reinstate and Delete where allowed
  (admins); the all-jurors gallery.

### 2.5 Phasing of the view

- **Phase 1:** `RoundLineage`, the tables, V101 (`round.status` and its backfill, the track label,
  `created_at`), Cancel and Reinstate, hiding and the toggles, the manual label.
- **Phase 1c:** menus grouped by track, after multi-role.
- **Phase 4:** SVG lines, optional.

### 2.6 Cancel and reinstate (manual, phase 1) and delete (phase 3)

**What exists today:**
- stopping a round (`Round.setActiveGuarded`, `Round.scala:503-517`);
- the unused `deleted_at`;
- no lifecycle state, no cancel, no delete. A round's state is `active`, `distributing` and the
  freeze columns.

**`round.status`, brought forward from P3** (decision 5). P3 defines `draft | paused | active |
finalized | cancelled`, NULL until a backfill, with `active` and `distributing` kept written in
step so that `Round.openToJurors`, `activeRounds` and `openCond` keep working
(`2026-11-02-round-images-two-step.md:148-161`). V101 adds the column and its backfill now.
`cancelled` is the hidden state. There is no separate archive flag.

**The states in step with the flags.** P3's table (`:154-159`) has no row for a stopped round, and
its `paused` always has `distributing = 1`. This plan adds the row:

| status | distributing | active | when |
|---|---|---|---|
| draft | 1 | 0 | the creation job runs, or failed (decision 7), or reinstated with an incomplete creation |
| active | 0 | 1 | `completeCreation`, or a start |
| **paused (stopped)** | **0** | **0** | a stop, or closed by a later round's creation; every finished round |
| cancelled | as before Cancel | 0 | Cancel, or a creation job the admin cancelled |

**What phase 1 writes:**
- `cancelled`, by Cancel and by an admin cancelling a creation job (decision 7).
  `JobScheduler.cancel(jobId)` takes no user (`JobScheduler.scala:296`); `JobController.cancel`
  passes the admin's id, so the round's `status_changed_by` names them;
- `draft` or `paused`, by Reinstate (below);
- the backfill's values, by the writers that already set `active` and `distributing`, in the same
  statement:
  - `Round.create`: from its flags (`distributing` → `draft`, `active` → `active`, else `paused`).
    Tests insert active rounds through it;
  - a creation job enqueued: `draft`;
  - `completeCreation` (`Round.scala:588`): `active`;
  - start and stop, in `setActiveGuarded`'s UPDATE (`Round.scala:509-510`): `active` / `paused`,
    **only where `distributing = 0` and the status isn't `cancelled`**. It also runs on rounds
    being created: `setCurrentRound` stops the previous rounds through it
    (`RoundService.scala:362`). On a frozen round the status follows the flag the freeze saved;
  - `release(close = true)` closing a previous round: `paused`, with the same condition;
  - `freeze` and a plain `release`: no change. Frozen stays a column state (decision 5).
- never `finalized`: it stays with Montage parity, where it locks votes
  (`2026-11-02-montage-rounds-parity.md:69, 100`).

A spec covers `setActiveGuarded` and `release` on a `draft` and on a `cancelled` round: the status
stays. `Round.setActive(id, …)` (`Round.scala:492`) and `setInactiveAllInContest` (`:590`) have no
app callers; phase 1 deletes them instead of keeping them in step. If Gatling's
`GatlingDbSetup.load` writes the status, `FixtureVersion` is bumped.

`openCond`, `openToJurors` and `activeRounds` still read `active` and `distributing` in phase 1.
Readers of the status take a NULL one from the flags, by the table's rule (`draft` if
`distributing`, `active` if open, else `paused`).

**The backfill**, in V101, from `active`, `distributing`, the freeze columns and the round's latest
creation job (as `JobJdbc.latestBySubjects` reads it):

| Today | `status` |
|---|---|
| `distributing = 1`, latest creation job queued, running or **failed** | `draft`. A failed creation stays draft, so Retry works (decision 7) |
| `distributing = 1`, latest creation job cancelled by an admin | `cancelled` |
| `distributing = 0`, open (`active = 1`, or frozen with `active_before_freeze = 1`) | `active` |
| `distributing = 0`, otherwise (stopped) | `paused` |

- A round with `distributing = 1` and no creation job *(inf.: none expected)* becomes `draft`.
- For WLM UA 2025 every round becomes `paused`: none is active or distributing (§10.1).
- **After a rollback** to a pre-V101 binary, rounds it creates have a NULL status, which readers
  derive from the flags, and its starts and stops leave the status stale. Before the V101 binary
  is deployed again, the maintainer reruns the backfill's UPDATE, kept verbatim in the
  migration's comment and in the release's rollback notes. It leaves `cancelled` rows alone, so a
  rerun is safe.

**Who changed it:** `status_changed_at TIMESTAMP(3) NULL` and `status_changed_by BIGINT UNSIGNED
NULL`, written with every status write. `_by` is the admin for Cancel, Reinstate and a cancelled
creation job, and NULL for a job's write or the backfill. The map and the rounds list show
"Cancelled by X on D" from them. The alternative is the `round.admin` audit line alone (below)
until P3's later `round_event` table (`round-images-two-step.md:553`); that line of UI would then
need log reading.

**P3 afterwards** adds its own transitions and readers, the review page and the status-gated
writers (`round-images-two-step.md:505-516`). Its "`round.status` and its backfill" work item is
done by V101. Two points P3 has to take on, recorded here only (decision 18c: P3's file isn't
changed now):
- **"Before first activation"** is told by `distributing = 1`, since `paused` also covers stopped
  rounds with `distributing = 0`;
- **P3's paused-gated review and Distribute** (`:505-508`) must not apply to finished rounds,
  i.e. `paused` with `distributing = 0`.

**V101, in phase 1** (decisions 4, 5, 17): one migration. "V101" is the roadmap's next free number
today (renumbered from V99 on 2026-11-13, when V99 went to the vote gallery index's `voted_at`, then from V100 the same day, when V100 went to dropping vote's four single-column indexes); the number is assigned at merge, per the roadmap's migration-number rule
(`2026-11-02-roadmap.md:463-474`: "Assigned at merge time … Renumber on merge if two collide"),
and doesn't depend on V96–V98 landing first. Its statements follow V95's pattern
(`V95__Round_exclude_rounds.sql:20-26`), where `ALGORITHM=INSTANT` with a CHECK fails rather than
copies:
1. `ADD COLUMN IF NOT EXISTS` with `ALGORITHM=INSTANT`: `status VARCHAR(16)` (ascii, `ascii_bin`)
   `NULL DEFAULT NULL`, `status_changed_at TIMESTAMP(3) NULL DEFAULT NULL`,
   `status_changed_by BIGINT UNSIGNED NULL DEFAULT NULL`, `track VARCHAR(64) NULL DEFAULT NULL`;
2. the CHECK as its own statement (`DROP CONSTRAINT IF EXISTS`, then `ADD CONSTRAINT
   round_status_chk CHECK (status IS NULL OR BINARY status IN (…P3's five values…))`). MariaDB
   checks the rows as it adds it, which copies `round`: milliseconds, as V95's;
3. the backfill above, with a spec per row of its table;
4. `created_at` redefined in its own statement as `TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`,
   without `ON UPDATE` (decision 17). It states NOT NULL and a DEFAULT, as `MigrationNamingSpec`
   requires from V85 on (`:36`) *(inf.: a metadata change on 10.6+; checked in the migration
   review and `FlywayUpgradeSpec`)*.

**Cancel and Reinstate** (decisions 5–7):
- **Where:** a button per row on the **rounds list** and per cell on the **map**, for the contest's
  admins (`Secured.administersRound`, `Secured.scala:331`). Organizers see the state, not the
  buttons.
- **How:** CSRF POSTs `/admin/rounds/:id/cancel` and `/reinstate`.
- **Cancel's guards**, checked after locking the round (`FOR UPDATE`, as `Round.isOpen` does,
  `Round.scala:543`):
  - the round must be **quiet** (§2.3);
  - not open (`openCond`), except a round whose creation never completed, which `openCond` counts
    as open (`distributing = 1`). An active round is stopped first;
  - not frozen.

  A finished round with votes can be cancelled: the 2025 trials are such rounds.
- **Reinstate** (R2): `cancelled` → `draft` if the creation never completed (`distributing = 1`),
  `paused` otherwise. Such a `draft` round, with no job queued or running and a creation job that
  didn't fail (or no creation job at all, as the backfill's no-job `draft`), shows **"Creation
  incomplete"** on the map (§2.4). It is finished by "Distribute new files": its `case _`
  re-enqueues with `completingCreation = round.distributing` (`RoundService.scala:265`). Retry is not the
  path: `JobScheduler.retry` refuses a job that hasn't failed (`:317-323`), and a cancelled
  creation's job was cancelled.
- **The writes:** `status`, `status_changed_at` / `_by`, `created_at = created_at`, and an audit
  line (below). `active` and `distributing` are left as they are.

**A cancelled round can't run.** Refused while it is cancelled; the admin reinstates it first.
Each refusal reads the status under the round lock:
- **start:** inside `setActiveGuarded`'s UPDATE, which leaves a cancelled round inactive and
  returns the refusal;
- **"Distribute new files" and a resubmit:** a check in `RoundService.enqueueOrFail`
  (`RoundService.scala:146`);
- **Retry**, the job page's and the implicit one: `onRetry` can't refuse (it returns a
  `JsObject`, `JobHandler.scala:85`), and `JobScheduler.retry` catches only duplicate-active
  errors (`:337-340`). So a new hook, e.g. `JobHandler.retryRefusal(job, ctx):
  Option[RetryRefused]`, is called inside `retry`'s transaction; `RoundDistributionHandler`'s
  locks the round and returns e.g. `job.retry.round.cancelled`. `JobController.retry` already
  flashes refusals. `distributeNewFiles` and `submit` must map this refusal themselves: today
  their `Left(_)` branches read as "already running" (`RoundService.scala:258`, `:101`).

Cancel's lock and these checks serialize, so a job can't be queued between Cancel's "quiet" check
and its write. A failed creation is `draft`, not cancelled, so Retry works on it until an admin
cancels it (decision 7). Jobs that read a cancelled round as a previous or left-out round run
unchanged.

**Hidden while cancelled**, by default (decision 6):
- **the map** (a "Show cancelled" toggle);
- **the rounds list** (a collapsed "Cancelled rounds" section);
- **the round stats pager**, and `currentRoundStat`'s fallbacks (`RoundController.scala:525, 533`);
- **the navbar round menu**, filtered **in the view** (`navbar.scala.html:22-28`), not in
  `GalleryService.viewerRounds`. That one feeds `isNotAuthorized` (`GalleryService.scala:11-19`),
  and multi-role rewrites it;
- **the new-round form's previous and leave-out selects.** A "Show cancelled rounds" checkbox
  (`round-form.js`, options' `data-cancelled`) adds them back, because a real round may build on
  one.

**Kept:**
- URLs;
- an existing round's disabled selects;
- a ghost cell for a cancelled round that feeds a live one.

**Delete** (decision 9, phase 3): only a round with **no juror input**. The conditions:
- **who:** the contest's admins (or root);
- **state:** quiet; not open (`openCond`), except a round whose creation never completed, which
  `openCond` counts as open (`distributing = 1`, `Round.scala:538-540`); not frozen. Cancel's
  wording; "not open" alone would refuse the main case;
- **no votes cast:** no `vote` with `rate <> 0`, no `criteria_rate`, no `vote_aside`, no `comment`
  of the round;
- **nothing refers to it:**
  - no other round lists it in `previous` or `exclude_rounds`;
  - it is not the contest's `current_round`.

A failed or cancelled creation, or an empty test round, qualifies, whatever its status. Jurors
never saw it, since a round being created is hidden from them.

**The checks run inside the delete transaction**, after the round row is locked (`FOR UPDATE`,
as `Round.isOpen` does), so a job, vote or new round referring to it can't slip in between the
check and the delete. A refused check rolls back.

**What the delete removes.** One transaction, in the lock order (`round → images → vote →
criteria_rate → vote_aside → round_image`):
- deletes its unrated assignment `vote` rows (rate 0) and `round_image_dirty` entries;
- deletes its `round_user` rows **explicitly**. Production's `round_user` has no foreign key to
  the round: it was born MyISAM with only a plain `KEY FK_round_user_round_id`
  (`test/resources/db/prod-v53-myisam-schema.sql:70-76`), V55_1 added none, and V82 notes that
  the test schema has V37's FK but production doesn't (`V82__Rename_selection_to_vote.sql:92-94`).
  No cascade would remove them there;
- deletes the round. The cascades that do exist remove `round_criterion` (V83:94), `round_image`
  (V84:48) and `round_image_event` (V88:48).

**Spec without the FK:** the delete spec also runs on a schema whose `round_user` lacks
`FK_round_user_round_id` (dropped in the spec, as production), and checks that no `round_user`
row of the round remains.

**What it keeps:**
- the round's **job rows** stay as history. The job page shows "round deleted", and Retry is
  refused;
- other rounds' `previous` / `exclude_rounds` can't be affected (refused above). A round deleted by
  SQL still gets V95's `ExcludeRoundsMissing` and the map's ghost cell.

**Numbering.** Today a new round's number is the contest's round count + 1: `RoundService` sets
`number = countByContest + 1` (`RoundService.scala:123`), and the form's hidden field uses
`rounds.size + 1` (`RoundController.scala:82`). After a delete the next round would repeat a
number. Phase 3 changes both to `MAX(number) + 1` for the contest, with a spec (delete round 2
of 3; the next round is 4).

**UI:**
- a `btn-danger` "Delete" on the round form and the map cell, shown only when allowed;
- it leads to a confirmation page naming the round and what goes (N assignments, 0 votes);
- the confirmation is a CSRF POST with `data-submit-once`.

**Audit:** one INFO line per cancel, reinstate and delete, on a new logger `round.admin`:
`action=… accountId=… contestId=… roundId=… number=… assignments=…`. Ids only (operations
practices; CLAUDE.md "Denials" style).

---

## 3. File provenance

**Where:** a section for organizers and admins only, "Rounds of this file" (decision 10).
- **In the round large view** (beside `juryStat`, `large/large.scala.html:30-36`):
  - the **path**: the file's rounds that are ancestors of the current round, in topological order;
  - then **"also in"**: its other rounds.
- **In the contest-file large view:** all the file's rounds in topological order.

**Loaded on demand only** (decision 10+). The large view must not get slower for voting:
- **The control:** the large view renders only a collapsed "Rounds of this file" control
  (`<details>`), with no data. The large view's script file fetches the section when the viewer
  expands it, as an HTML fragment. Without script, the summary is a link to the same URL as a page.
  Since it is fetched by script, the script handles a 401 (signed out: a sign-in link), a 403 and
  a 404 answer with a short message in the section, never the raw response.
- **The endpoint:** a separate GET per (round, file) and per (contest, file) *(route names at
  implementation)*, `withAuthOn(blocking)`. It runs as the viewer in the resource's contest
  (`Secured.inContest`, no contest switch on a script request) and refuses jurors, pure
  pre-screeners and the jury-round case below (R1), with negative specs for each.
- **Never on the voting path:** no provenance query when the large view renders, on a vote, on
  keyboard navigation or on preload. `views.LargeViewControllerSpec` is extended to assert that
  those requests run none (counting the provenance DAO's calls).
- **Benchmark:** a paired `GalleryLatencyBenchmark` pilot (`-Dbench.cases='^(large|vote)'`), with
  and without the control, on the same fixture. It reports the absolute response deltas and the run
  variation separately; the expand request is measured on its own.

**Each step shows:**
- status (`in`, `dq` + `dq_code`, `deleted`);
- `added_how` / `added_at`;
- the result as the result pages show it;
- per-juror votes behind a toggle.

**Steps done by hand.** `round_image` comes from the votes (V84 backfill, then every writer), so a
file copied by hand into a round shows there, under "also in". For 2025 that is the only place such
steps appear. The map shows them only once they are done in the app (§11).

**Viewer who also votes** (decision 11, R1), by multi-role's round type (V97). Decided:
- **a pre-screening round:** Organizers and Admins who also pre-screen see the control. Pure
  pre-screeners see none: decision 10 and multi-role's decision 3 hold;
- **a jury round:** hidden from anyone voting there. "Votes in" is `User.isRoundJuror`. Multi-role
  keeps Juror apart from Admin and Organizer (its decision J), so only root or a leftover
  `round_user` row reaches this case;
- before V97 every round counts as a jury round (V97's default `jury`).

The control isn't rendered where the endpoint would refuse. Jurors never get it.

**Scores:** one `ImageWithRating` per (file, round), with `totalRate(round)` / `rateString(round)`
(`ImageWithRating.scala:47, 133-146`). The distribution's per-job `denominators` (`:224`) is the
ranking rule, not this.

**Queries** (DAO, `RawSqlSpec` entries), run only by the endpoint:
1. `round_image ri JOIN round r` with `page_id = ? AND contest_id = ?`;
2. `vote` with `page_id = ? AND round_id IN (…)`;
3. `vote_aside`, the same way;
4. the yes/no rounds' assigned jurors in one `GROUP BY round_id`, instead of a lazy
   `numberOfAssignedJurors` per round (`Round.scala:91`, `VoteJdbc.scala:214-216`). EXPLAIN in
   review; cache for stopped rounds.

No timing is claimed before the benchmark.

**Later:** the file list's "rounds" column (2b). P3's "Why is this image here" reuses the section.

---

## 4. Backtracking

**Through the normal round form** (decision 12). With V95 every step is recorded:
- **a regional backtrack:** a new round with previous = A, regions = X, and leave out = X's later
  rounds;
- **a merge:** a new round with several previous rounds (same rating);
- **"files of A not in round Y":** previous = A, leave out = Y.

The admin picks these in the form as for any round. The form's position line and soft warnings
(§5) and its "Show cancelled rounds" toggle (§2.6) help.

**Left out for now** (decision 12):
- the map's prefilled links ("new round from this one", "merging this one with …", "not in round
  Y", "never reached a later round", "back to this round for region X");
- a count for an unsaved form. It would be M: the count path takes a saved round
  (`countNew(roundId)`, `RoundDistributionHandler.scala:596`), may query Commons and queues on the
  single `countContext` thread.

Decision 13 (what "never reached a later round" leaves out) stays open with them.

**"Reached"** = a `round_image` row in any status (`notInExcluded`, `RoundDistributionHandler.scala:918`).

**Limits:**
- `exclude_rounds` holds about 40–50 ids;
- **a round being distributed can't be left out** (`ExcludeRoundsBusy`). That includes a round
  whose creation never completed (`distributing = 1`), failed or cancelled. Deleting it
  (decision 9) is the way out. A follow-up could also let `excludedRoundsError` accept it;
- one rating scale across previous rounds (`sameRateType`).

---

## 5. Round form help

**Position line:** "Will follow: Q-01 + Qt-01 → (Main)", with the track and position, from the
options' `data-*`, read by `round-form.js`.

**Soft warnings** (decision 14):

| Case | Today | Proposed |
|---|---|---|
| A previous round is also left out | refused | keep |
| A left-out round is an ancestor of every previous round | allowed | warn |
| A previous round is open | allowed; creation **closes** it (`Round.release(close = creating)`, `RoundDistributionHandler.scala:481`) | warn "R5 is open; creating this round stops it" |
| Previous rounds of different ratings (a merge) | refused (`sameRateType`) | keep; the form names the odd one |
| A previous round is cancelled (via the toggle) | — | warn |
| A previous round already feeds others | — | info only (a fork; Q-02 → Q-02-nofop, Q-03-nofop, Обласні are real) |

---

## 6. Relation to existing plans

| Plan | Overlap | How to avoid conflicts |
|---|---|---|
| Multi-role, V97/V98 | `round_type`; U22 (rounds page); navbar §5 (`:360`); decision 11 reads the round type | Type as a badge. Phase 1c lands after U22 and the navbar changes. The Cancel button on the rounds list is rebased on U22. Before V97, provenance treats every round as a jury round |
| Combined votes, V96 | D9's descendant walk (`:152-161`); D9 is a default-on checkbox an admin can untick (`:110`) | One `RoundLineage.descendants`; leave-out edges excluded |
| Round images P3 | `round.status` (`:148-161`); "Why is this image here"; filters editable while paused (`:296-318`); disqualify with a reason; §11 item 8 | V101 brings `round.status` and its backfill forward (§2.6). P3 then adds its own transitions and readers; `finalized` stays with Montage parity. P3's table lacks a stopped round (`paused`, `distributing = 0`, `active = 0`), which §2.6 adds: P3 must tell "before first activation" by `distributing = 1`, and its paused-gated review and Distribute (`:505-508`) must not apply to finished rounds. Provenance is the "why" page's rounds part. `updateFilters` calls `wouldCreateCycle`. §11 item 8 (jurors added to an existing round) belongs to P3, with the Montage gaps (decision 18). P3's file isn't changed now |
| Montage parity | lifecycle (item 9, `:69`); jury changes (items 12–13, `:103`) | Phase 1's `cancelled` is the first part of item 9; `finalized`, which locks votes, stays there. Jury changes reach the app through P3 (§11 item 8) |
| Import top of another contest | the first cross-contest edge | Text "from contest C, round R" |
| Roadmap numbers | V101 next free (V99: the vote gallery index's `voted_at`; V100: vote's four single-column indexes dropped) | Assigned at merge, renumbered on collision; not tied to V96–V98 landing first |

---

## 7. Storage

**The current columns suffice** (decision 15). Phase 0: 43 edges for 61 rounds, all in the contest,
read whole. The steps 2025 did by hand in the DB left no edge, and no storage would have recorded
them. The remedy is in-app actions (§11).

**A `round_relation(round_id, other_round_id, kind)` table pays off later**, for:
- cross-contest edges;
- lineage inside SQL;
- per-edge attributes, such as a rejection threshold per listed round (phase 3b's `reject_rounds`
  has one per round, if that proves too coarse), or "files added from" (§11 item 3, not planned).
  Copying votes needs none: several previous rounds cover it (item 4);
- foreign keys.

Graph readers go through `RoundLineage`, so a table can be swapped in.

---

## 8. Size and phases

S ≈ ½ day, M ≈ 1–2 days, L ≈ 3+ days of agent work, each with review and targeted specs.

| Phase | Content | Size |
|---|---|---|
| 0 | **Done** for WLM UA 2025 (§10). **Before phase 1** (decision 3): repeat on a WLE Ukraine rounds export, with rule 2 (prefix nominations) added to the script. The maintainer provides the export (agents don't query production) | S |
| 1 | **Entry condition:** phase 0's WLE check done, and any rule 2 fix written into §2.3. **The map:** `RoundLineage` (Kahn, cycles, ghosts, rule v4, Main and feeder order, sub-rows, positions by id, backtracks, quiet, `inputSummary` in 4 languages); the map's two tables, read-only for organizers; entry links. **Unit spec with a synthetic fixture:** quality + quantity → Main → two regional rounds, one of them a V95 backtrack, plus a nomination, a cancelled test root and a failed creation. **V101** (V95's statement pattern): `round.status` with INSTANT, its CHECK apart, the backfill (a spec per backfill row, rerun safe), `status_changed_at` / `_by`, the track column, `created_at` in its own statement, `FlywayUpgradeSpec`, `MigrationNamingSpec`. **Status in step** in `Round.create`, the creation enqueue, `completeCreation`, `setActiveGuarded` and `release(close)` (only where `distributing = 0` and not `cancelled`, with a spec); `Round.setActive(id, …)` and `setInactiveAllInContest` deleted; readers derive a NULL status; `FixtureVersion` bumped if Gatling's `load` writes the status. **Cancel and Reinstate** (rounds list and map, guards under the round lock, Reinstate to `draft` or `paused`, "Creation incomplete", the admin's id through `JobScheduler.cancel`, audit); the refused start in `setActiveGuarded`'s UPDATE and the status check in `enqueueOrFail`; the `JobHandler.retryRefusal` hook in `JobScheduler.retry` (`job.retry.round.cancelled`), and `distributeNewFiles` / `submit` mapping that refusal instead of "already running"; the hidden places and both toggles; the label setter with a form spec. Negative specs (juror, non-member, organizer without admin actions, root 404, pre-screener after V97, Cancel of an open or busy round, start, "Distribute new files", resubmit and Retry of a cancelled round); view spec; screenshots in both themes | L (7–9 d) |
| 1c | Stats pager and navbar grouped by track, after multi-role | S–M |
| 2 | Provenance in both large views, loaded on demand (decision 10+): the collapsed control, the separate GET with `Secured.inContest` and its refusals by round type (decision 11, R1) and negative specs, `LargeViewControllerSpec` asserting no provenance query on render, vote, keyboard navigation or preload, the paired `GalleryLatencyBenchmark` pilot; 2b the file-list column | M–L (2.5–3.5 d) |
| 3 | Form help and warnings (M); **delete** (M: guards in the transaction, cascade spec and a `round_user` spec without the FK, `MAX(number) + 1` numbering, confirmation page, audit, negative specs) | M–L (3–4 d) |
| 3b | **Leave out by vote** (§11 item 5, decision 18, R3): "leave out files rejected in round B". A separate CSV column (e.g. `reject_rounds`, a migration numbered at merge) with V95's CHECK form, and a threshold column; `exclude_rounds` unchanged. `reject_rounds` always excludes: a file with ≥N "no" votes in a listed round is left out, as `prev_rejected_by` counts (V74). No mode: V93's `only` keeps only rejected files (`V93:3-5`), which means nothing for a leave-out. "Not selected" (fewer than M "yes" votes, as `prev_selected_by`, a second threshold) is an open question for the maintainer at the start of the phase; otherwise it is left out of 3b. `vote_aside` ignored; B not open at creation ("Distribute new files" reads B's votes again); in `countNew` / `previousCount`, the map and `inputSummary`; the form, validation, specs. Binary B rounds only; rated B rounds are a later extension. After V95 merges | M–L (2–3 d) |
| 4 (optional) | SVG lines | L (3–4 d) |
| Left out | Map actions (prefilled form links) and the unsaved-form count (decision 12); decision 13 with them | — |
| §11 others | Item 8 in P3; items 3 and 9 not planned; item 11 its own plan (decision 18) | not in this total |

**Total:**
- phases 1–3b: **15–21.5 agent days**, the sum of the phase ranges (1c counted as ½–2 d);
- phase 4: 3–4 d, only if needed.

**Phase 1 is larger than v3's** because V101 and Cancel move into it, and a day larger than v4's
for the status writes in step and the backfill specs. A 2025-style contest (61 rounds, 11 of them
tests) reads badly until its trials are cancelled. **Phase 2 grows by about half a day** for the
on-demand endpoint, its specs and the benchmark (decision 10+). **Phase 3 is smaller** than v4's:
the prefill links and the unsaved-form count are left out.

---

## 9. Decisions

Taken by the maintainer on 2026-11-10/11. **Changed** marks a decision that differs from v4's
recommendation: 5, 11, 12 and 18 (in part); 13 is deferred. Decision 10 was amended (10+), and the
review follow-ups R1–R3 (2026-11-11) settle decisions 11, 5/7 (Reinstate) and 18 (phase 3b).

**1. Where the map lives and who sees it: as recommended.** A new page,
`/contest/:id/rounds/map`, for the contest's admins and organizers, linked from the rounds page and
from the round stats pager.

Organizers have no rounds page, and their only round pages are the stats, so the stats pager is
where they find it. Organizers see the tables read-only; admins also get Cancel, Reinstate and
Delete (decision 12). Jurors and non-members get 403: the map exposes every round's size and
filters, against "own votes only". A page of its own leaves the busy rounds and stats pages
untouched.

**2. Table now, SVG later only if needed: as recommended.** Phase 1's map is plain HTML tables
(§2.2):
- tracks are row groups, positions are columns, and each round is a cell;
- forks are sub-rows;
- merges, leave-outs and backtracks are text and links ("from Q-01 + Qt-01", "leaves out Q-01",
  "backtrack from M1");
- Main sits on top, with its feeders directly under it in joining order;
- all region tracks are in one table below.

Merges are the core path, quality + quantity → Main → regions; 2025 recorded none only because they
were done by hand. Placing the feeders right under Main makes the join readable without lines. A
merge cell spanning rows (`rowspan`) is the first addition if organizers find the text hard to
follow; SVG lines are optional phase 4. The table needs no script, library or endpoint, inherits
the themes, works on phones and is the screen-reader version by itself.

**3. The automatic track rule (v4): adopted, with a check first.** Rule v4 (§2.3, rules 1–7):
1. own nomination;
2. prefix nomination;
3. inherited nomination;
4. region subset, to the regions table with its source track;
5. inherited track;
6. merge → Main;
7. root scope (category minus the contest's common prefix, media type, name).

On WLM UA 2025 it places all 61 rounds correctly: 6 category tracks and 7 nomination tracks.
Nominations are named only on their first rounds, and rules 3 and 5 carry them on. Rule 6 is the
core of future contests; the phase 1 fixture tests it. The common prefix is defined, not
hard-coded.

**Condition (maintainer):** WLE's prefix nominations (rule 2) are verified on a WLE Ukraine rounds
export **before phase 1 starts** (§8, phase 0). If rule 2 proves wrong, §2.3 is corrected first.

**4. The manual track label: in V101, edited any time. As recommended.** `round.track`, phase 1:
- the label names the track and is inherited by later rounds without one;
- identical labels share a track;
- "Main" joins the Main group;
- an empty label means automatic.

It is set on the round form, also for existing rounds, through a setter of its own that writes
`created_at = created_at`, with a form spec. It fixes what the rule can't know. For 2025 that is
mainly grouping: "Main" on Quality and Quantity, since those rounds were merged by hand and no
stored field says they are the main competition.

**5. Hiding rounds: `round.status`, not an archive flag. Changed.** v4 recommended a new
`archived_at` / `archived_by`. The maintainer asked to keep hiding in step with the states already
planned: "why status is not enough?". So:
- **P3's `round.status`** (`draft | paused | active | finalized | cancelled`) comes forward into
  V101 with its backfill: being created → `draft`, stopped → `paused`, open → `active`, a creation
  job the admin cancelled → `cancelled`. A failed creation stays `draft` (decision 7 refines the
  backfill). `active` and `distributing` stay written in step, as P3 plans.
- **`cancelled` is the hidden state.** Admins **Cancel** a round that is quiet, not open and not
  frozen, also a finished round with votes, and **Reinstate** it (R2: `draft` if the creation
  never completed, "Creation incomplete", finished by "Distribute new files"; `paused` otherwise).
- **Who and when:** `status_changed_at` / `status_changed_by`, or the audit line alone (§2.6).
- **Phase 1 writes only `cancelled`** and the backfill's values (Reinstate's included).
  `finalized` stays with Montage parity, where it locks votes. Frozen is unchanged: the freeze
  columns, not a status. A start or stop changes the status only where `distributing = 0` and it
  isn't `cancelled`.
- **P3** later adds its own transitions. Its table gains the stopped row (`paused`,
  `distributing = 0`), which P3 has to tell apart from "before first activation" (§2.6, §6).

One state column answers "is this round in use" for the map, the menus and P3. `deleted_at` stays
unused: `ScoreRuleWarning.laterRounds` reads it as "deleted". Cancelling the 11 "[test]" rounds and
GH-00 takes the 2025 grid from 26 rows to 15 (§10.2).

**6. What a cancelled round is hidden from: as recommended.**
- **Hidden by default:**
  - the map (a "Show cancelled" toggle);
  - the rounds list (a collapsed "Cancelled rounds" section);
  - the round stats pager and `currentRoundStat`'s fallbacks;
  - the navbar round menu, filtered in the view only;
  - the new-round form's previous and leave-out selects, with a "Show cancelled rounds" checkbox
    that adds them back.
- **Kept:**
  - URLs;
  - an existing round's selects;
  - a ghost cell for a cancelled round that feeds a live one.

These are the lists where 61 rounds hurt. The navbar is filtered in the view because
`viewerRounds` also decides authorization, and multi-role rewrites it; filtering there would turn
Cancel into an access change. The select toggle exists because a real round can build on a
cancelled one: Q-00-2 ← "[test] Q-00-1" in 2025.

**7. Cancelled rounds can't run, and what "quiet" means: as recommended.**
- **Quiet:** no queued or running job, and either `distributing = 0` or the latest creation job
  failed or was cancelled.
- **Cancel needs a quiet round** that is not open (apart from a round whose creation never
  completed) and not frozen. The checks run after locking the round (`FOR UPDATE`).
- **Creations:** a failed creation stays `draft`, so Retry works, until an admin cancels the round.
  An admin cancelling a creation job makes the round `cancelled`, naming the admin.
- **While cancelled, refused:** start (inside `setActiveGuarded`'s UPDATE), "Distribute new
  files" and a resubmit (a check in `enqueueOrFail`), and Retry (a new `JobHandler.retryRefusal`
  hook inside `JobScheduler.retry`'s transaction, after locking the round; `onRetry` can't refuse).
  `distributeNewFiles` and `submit` map the refusal themselves, not as "already running". Reinstate makes it `draft` or `paused` (R2, §2.6).
- **Unchanged:** other rounds' jobs reading it as a previous or left-out round.

A failed or cancelled creation keeps `distributing = 1` forever, because only `completeCreation`
clears it. A guard on "not distributing" alone would refuse to cancel exactly the rounds most
likely to be mistakes. Refusing to run a cancelled round keeps "cancelled" meaning "not in use".

**8. Mistake hints: none, beyond facts. As recommended.** No heuristic hints, and no marker for
names starting "[test]". The map shows only facts: the states (Creation failed, Creation
incomplete, Stopped, Cancelled, …) and what admins cancelled. v3's structural rule flagged Q-02-nofop, a real step, and "[test]" is
a naming habit, not a stored fact. Rounds are listed by name, so an admin cleaning up sees "[test]"
without help.

**9. Delete rounds: only without juror input. As recommended (option a), phase 3.**
- **Allowed:** the contest's admins (and root) may hard-delete a round that is quiet, not open
  (except a round whose creation never completed, which `openCond` counts as open), not frozen,
  and has no juror input:
  - no vote with a rate;
  - no criteria rate;
  - no `vote_aside` row;
  - no comment.

  It also must not be listed in another round's `previous` or `exclude_rounds`, and must not be the
  contest's current round. The checks run inside the delete transaction, after locking the round.
- **What goes:** its rate-0 assignment votes, dirty entries and `round_user` rows are deleted
  explicitly (production's `round_user` has no foreign key, §2.6), and the cascades remove
  `round_criterion`, `round_image` and `round_image_event`. A spec runs without the `round_user`
  FK.
- **What stays:** job rows remain as history.
- **Numbering:** a new round's number becomes `MAX(number) + 1` (today the round count + 1,
  `RoundService.scala:123`, `RoundController.scala:82`), so numbers don't repeat after a delete.
- **Code:** this brings back a narrower `Round.delete`. The old one (`Round.delete(roundIds)`,
  used only by the unrouted merge) was removed in `2a1600a7` with the merge code, which several
  previous rounds replace (§11 item 4).
- **How:** a confirmation page and a CSRF POST, with an audit line on `round.admin` (ids only).
- **Otherwise:** Cancel.

A failed or cancelled creation is the main case: jurors never saw it, its assignments carry no
rates, and V95 can't leave it out (`ExcludeRoundsBusy`). Refusing a round that others refer to
keeps every recorded edge intact, so the app creates no ghost cells.

**10. Provenance: where and how much. As recommended.** An organizers-and-admins-only section in
both large views. In the round large view it shows the path along the current round's ancestors,
then "also in". In the contest-file view, all the file's rounds in order. Each step shows status,
how and when the file was added, and its result from `ImageWithRating`, so it matches the result
pages. The yes/no assigned-juror counts are batched. For 2025 this is the only place the steps
done by hand show: the files copied by hand appear under "also in", from their votes. For later
contests the path itself is complete, because the in-app steps record their edges.

**10+. Amended (maintainer, 2026-11-11): loaded on demand only.** Provenance must not affect voting
performance. The large view renders only a collapsed control; the section comes from a separate
GET when the viewer expands it (§3). That endpoint checks the contest with `Secured.inContest` and
refuses jurors, pure pre-screeners and the jury-round case (decision 11), with negative specs. No
provenance query runs on render, on a vote, on keyboard navigation or on preload;
`LargeViewControllerSpec` asserts it. A paired `GalleryLatencyBenchmark` pilot
(`bench.cases='^(large|vote)'`), with and without the control, reports the absolute deltas.

**11. Provenance in rounds the viewer votes in: by round type. Changed, then read by R1.** v4
recommended leaving the section out of any round the viewer votes in. The maintainer: "show for
prescreener, hide for jury", read as follows (R1, decided):
- **a pre-screening round:** only Organizers and Admins who also pre-screen see it. Pure
  pre-screeners see none: decision 10 and multi-role's decision 3 hold;
- **a jury round:** hidden from anyone voting there. "Votes in" is `User.isRoundJuror`. Under
  multi-role's decision J only root or a leftover `round_user` row reaches this case.

Before V97 every round counts as a jury round. Jurors never get the section. It costs one
condition on the round type, in the control and in the endpoint.

**12. Map actions and the unsaved-form count: left out for now. Changed.** v4 recommended GET
links on the map that prefill the round form, and an admin-only count for an unsaved form (M). The
maintainer: "let's leave this out at least for now". So:
- the map is read-only, apart from admins' Cancel and Reinstate (phase 1) and Delete (phase 3);
- backtracking and merging stay possible through the normal round form (§4);
- phase 3 loses the prefill links and the count (§8).

**13. What "never reached a later round" leaves out: open, deferred with 12.** It matters only if
decision 12's actions come back. v4's recommendation, as the starting point then: by default leave
out only R's own track's or region's later rounds, prefilled and editable, not every descendant of
A. Leaving out all descendants also drops files that passed into M2 and failed in M3, which may be
exactly the regional photos wanted. "Reached" stays V95's meaning (any `round_image` row).

**14. Round form warnings: as recommended.** Soft warnings and information only, with no new
refusals (§5):
- a left-out round that is an ancestor of every previous round;
- an open previous round (creation stops it silently today);
- a cancelled previous round (via the toggle);
- information that a previous round already feeds others.

The two existing refusals stay: previous and left-out overlapping, and mixed ratings in a merge.
"Already feeds others" is neutral information, not a warning. Q-02 feeding Q-02-nofop, Q-03-nofop
and Обласні was all real, so a fork must not read as a mistake.

**15. Storage: the current columns now, a relation table later. As recommended.** No table in
phases 1–3b, with graph readers behind `RoundLineage`. Edges stay within a contest, are few and are
read whole. Missing ids are ghosts. The 2025 gaps are solved by in-app actions, not storage. A
table pays off later, for cross-contest edges, lineage inside SQL and per-edge attributes (§7).

**16. Grouping round menus by track: as recommended.** The stats pager and the organizers' navbar
round menu are grouped by track, leaving cancelled rounds out, after multi-role's U22 and navbar
changes (`navbar.scala.html:22-28, 131-139`). Phase 0's 61 rounds become 13 track groups of 2–15
rounds plus a regions group: Quality has 15, and 11 once its tests are cancelled. It reuses the
map's computation at no query cost, and jurors' menus don't change.

**17. Drop `ON UPDATE` from `round.created_at` in V101: as recommended.** The column is redefined as
`TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`, without `ON UPDATE`, in V101.

Today every start, stop, freeze, release, completed creation and CoI flag change overwrites the
creation time. Only `updateRound` and `RoundCounts` remember to write `created_at = created_at`, and
every new writer must remember too, or silently break it. Phase 0 shows the result: 6 of 43 edges
point backwards in time. Dropping the clause makes the column mean what its name says from V101 on.
Old values stay wrong and the map keeps ordering by id, but new rounds keep a true creation time.

The cost:
- one more clause in a migration phase 1 makes anyway;
- a check in review that the change is metadata-only on 10.6 and 10.11 *(inf.)*;
- the guard comments in `updateRound` and `RoundCounts` become unnecessary but harmless.

**18. Missing in-app operations (§11): confirmed, with statuses. Changed in part.**
- **Item 5 is confirmed and built here:** Q-02-nofop's verdict was used that way. "Leave out files
  rejected (or not selected) in round B" is phase 3b (§8). R3 settles it: a separate CSV column
  (e.g. `reject_rounds`) with V95's check and a threshold column, `exclude_rounds` unchanged. It
  always excludes files with ≥N "no" votes in B, as `prev_rejected_by` counts (V74); V93's mode
  doesn't apply, since `only` would keep only rejected files. `vote_aside` ignored; B not open at
  creation; included in the counts, the map and `inputSummary`; binary B rounds only, 2–3 d.
  **Open for the maintainer at the start of 3b:** "not selected" (fewer than M "yes" votes, as
  `prev_selected_by`, a second threshold); otherwise it is left out of 3b.
- **Item 8** (add a juror to an existing round, or redistribute a stopped juror's files) belongs to
  the two-step rounds plan (P3), as part of closing the Montage gaps. Only a cross-reference here
  (§6); P3's file isn't changed now.
- **Items 3 and 9** (add files of round X to an existing round; fix a rating scale) are not planned
  anywhere for now. The maintainer: "do not add anything for now". No other 2025 steps are added,
  and the list stays as recorded.
- **Item 11** stays its own plan.

Each built item records its edge as `previous`, `exclude_rounds`, phase 3b's `reject_rounds` or,
where none fits, a later relation table (decision 15).

---

## 10. Phase 0 result: WLM UA 2025

### 10.1 Input and method

**The export:** `wlm-ua-2025-rounds.csv`, contest 603's 61 `rounds` rows from production
(`f9c31ce5`, V70), with a header row.
- The header matches the V70 column order: the prod-v53 schema dump at `f9c31ce5`, plus V59, V60,
  V68 and V69 (V70 only retypes `category_clause`).
- The parser stops on any other header.

**The scripts** (local to the planning session, run with `python -I`; neither is in the repo, and the
CSV isn't copied anywhere):
- `lineage_phase0.py`: parse, Kahn, a field dump;
- `phase0_rows.py`: the track rule, sub-rows, positions by id, statistics.

**Script limits:**
- the contest prefix is hard-coded ("Wiki Loves Monuments in Ukraine 2025 - "), where rule v4
  computes it;
- rule 2 (prefix nominations) isn't exercised. The WLE Ukraine run before phase 1 adds it
  (decision 3);
- the spine doesn't skip cancelled rounds, since none are. With the trials cancelled the spines are
  the same, because each real round has a larger id than its trial sibling.

| Fact | Value |
|---|---|
| Rounds, roots, previous edges | 61, 18, 43 |
| Merges | 0 recorded. The 2025 merges were done by hand in the DB, because several previous rounds weren't available yet |
| Leave-outs | none (V95 is newer) |
| Cycles, missing ids | none |
| Forks, leaves | 7, 27 |
| Named "[test]" / "[Test]" | 11 |
| Ratings | 36 yes/no; 25 rated (1–5 … 1–20) |
| Filters used | selected by (29), min score (10), regions (16 all, 1 subset), media type; no top-N, monuments or Mpx |
| States | none active or distributing; `deleted_at` NULL on all. V101's backfill would make all 61 `paused` (§2.6) |
| Round numbers | exactly 1–61 in id order (§10.5) |

### 10.2 The grid as the phase-1 table renders it (nothing cancelled)

"↳" is a fork sub-row; "(from X)" marks a cell whose source isn't the cell to its left.

| Track | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|
| Exterior | EE-00 | EE-01 | EE-02 |  |  |  |  |  |
| ↳ |  | [test] EE-01 (from EE-00) |  |  |  |  |  |  |
| Film | ФП-00 | ФП-01 | ФП-02 |  |  |  |  |  |
| Interior | I-00 | I-01 | I-02 | I-02-nofop | I-03 |  |  |  |
| Quality | Q-00 | Q-01 | Q-01-2 | Q-02 | Q-03-nofop | Q-03 | Q-04 | Q-05-10 right |
| ↳ | [test] Q-00 |  |  |  |  |  |  |  |
| ↳ |  | [test] Q-00-1 (from Q-00) | Q-00-2 | Q-00-2-1 |  |  |  |  |
| ↳ |  |  |  |  | Q-02-nofop (from Q-02) |  |  |  |
| ↳ |  |  |  |  |  |  |  | [test] Q-05 (from Q-04) |
| ↳ |  |  |  |  |  |  |  | [test] Q-05-10 (from Q-04) |
| Quantity | Qt-00 | Qt-01 | Qt-01-2 |  |  |  |  |  |
| ↳ | [test] Qt-00 |  |  |  |  |  |  |  |
| Video | V-00 | V-01 | V-02 |  |  |  |  |  |
| ↳ |  |  | [Test] V-02 (from V-01) |  |  |  |  |  |
| Єврейська спадщина | JH-01 | JH-02 |  |  |  |  |  |  |
| Болгарська спадщина | BH-01 | BH-01.5 | BH-02 |  |  |  |  |  |
| ↳ |  |  | [test] BH-02 (from BH-01.5) |  |  |  |  |  |
| Війна руйнує пам'ятки | WAR-00 | WAR-00-1 | WAR-01 |  |  |  |  |  |
| Німецька спадщина | GH-01 | GH-02 |  |  |  |  |  |  |
| ↳ | GH-00 |  |  |  |  |  |  |  |
| Пам'ятки дерев'яної архітектури України | WA-00 | WA-01 | WA-02 |  |  |  |  |  |
| ↳ |  |  | [test] WA-02 (from WA-01) |  |  |  |  |  |
| Польська спадщина | Pl-00 | PL-01 | PL-02 |  |  |  |  |  |
| ↳ | [Test] PL-00 |  |  |  |  |  |  |  |
| ↳ | [Test] PL-00 (trailing space) |  |  |  |  |  |  |  |
| Харків | Kh-00 | Kh-01 | Kh-01.5 | Kh-02 |  |  |  |  |

Regions table (same columns as §2.2):

| Regions | Rounds | From | State |
|---|---|---|---|
| 8 of 27 (ids 26, 32, 51, 53, 56, 63, 68, 71; names via `RegionScheme`) | Обласні | Q-02 (Quality) | Stopped |

**The grid has 26 rows.** Once an admin cancels the 11 "[test]" rounds and GH-00, it has **15 rows ×
8 positions**: one row per track, plus Quality's two real side branches (Q-00-2's chain under the
ghost "[test] Q-00-1", and Q-02-nofop). The regions table keeps 1 row. Had the 2025 merges been
done in the app, Main would sit on top, with Quality and Quantity directly under it *(inf.: the
shape in decision 2)*.

### 10.3 Assessment

| Expected | Result |
|---|---|
| Special nominations, each with its own track | **Right.** 7 tracks; `special_nomination` is on the first rounds only (10 roots), later rounds inherit (rule 3, then 5) |
| Category nominations (Interior, Exterior, Film, video) | **Right.** Category minus the common prefix. V-00 uses the contest's own category with media type video, so it is "Video" |
| Quality + quantity → main | **Not recorded in 2025:** merged by hand, as several previous rounds weren't available. The map shows Quality and Quantity apart; Q-02's only recorded previous round is Q-01-2. Future contests record the merge (rule 6), which the phase 1 fixture tests |
| Main rating rounds | **Inside Quality**, on its spine: Q-02 (1–10) → Q-03-nofop → Q-03 (1–13) → Q-04 (1–18) → Q-05-10 right (1–18). The label "Main" (decision 4) can name them |
| Regional winners | **One recorded round**, Обласні (from Q-02, 8 regions, 1–10). The rest was done by hand, copying files and votes. In the regions table, with its source track |
| `-nofop` branches | **Real steps** (maintainer): Q-03-nofop and I-02-nofop on their spines, and **Q-02-nofop a real side branch**. Its result was used by hand to leave files out (maintainer, decision 18), hence §11 item 5 (phase 3b). The rule shows them as forks and flags nothing |
| [test] rounds | 11. The admin cancels them by hand (decision 5). "[test] Q-00-1" feeds the real Q-00-2, so it stays as a ghost cell when cancelled, and the select toggle lets new rounds build on it |
| Duplicates | Trial-then-real siblings (BH-02, V-02, WA-02, EE-01, Q-05-10), whose settings differ; two "[Test] PL-00" roots; GH-00. No heuristic marks them; the admin cancels them |
| Identical timestamps | 2026-10-22 09:37:12: 5 rounds in one second, probably one statement. 2026-05-06 08:54:58–08:56:55: 10 rounds stopped one by one. Positions use `id` (§1.4) |

### 10.4 The steps done by hand

The merges and the regional backtracking copied files and votes in the DB (maintainer). The app
could not record a merge then: a round with several previous rounds became usable only with
`fee054ce` ("allow building a round from multiple previous rounds", 2026-09-07); V52 had only
made `previous` a CSV column.
- **On the map** they don't appear: Quality and Quantity are apart, and there is nothing after
  Обласні.
- **In provenance** a file's copied rounds appear under "also in", since `round_image` is built
  from its votes.
- **Going forward,** the in-app equivalents (§11) record each step as an edge, so the map has no
  missing links.

### 10.5 Were any 2025 rounds deleted by hand?

**Not by the evidence available.** Gaps in ids 1720–1854 mean nothing, because round ids are global
across contests. What does count is `round.number`:
- a new round gets `rounds.size + 1` for its contest (`RoundController.scala:82`, a hidden form
  field; `RoundService.scala:123` sets `countByContest + 1`);
- if a round had been deleted, the next round created would repeat an existing number.

The 61 rows have numbers **exactly 1–61 in id order**. So no round of contest 603 was deleted before
its last round was created, unless numbers were also edited by hand. A deletion *after* round 61,
of a round numbered 62 or later, would leave no trace here.

This evidence stays valid for 2025: those rounds were numbered by the count rule. Phase 3's
`MAX(number) + 1` (decision 9) applies only to rounds created after it, and makes this check
unusable for later contests.

**Further checks for the maintainer** (agents don't query production): `vote`, `vote_orphan`,
`comment`, `round_user` and `job` rows of contest 603 whose round id isn't in `round`. Hiding, as
far as the data shows, was done by "[test]" names and stopping.

---

## 11. DB-only operations of 2025 and their in-app equivalents

**The maintainer's rule:** nothing should need direct DB work, so every step is tracked and
recorded as an edge. The list is inferred from the request, phase 0, the plans and the code. The
maintainer confirmed it with the statuses below (decision 18); no other 2025 steps are added.

| # | Operation done (or likely done) by hand | In-app equivalent, and the edge it records | Status |
|---|---|---|---|
| 1 | **Merge**: quality + quantity results into the main rating round | A new round with several previous rounds (`previous` = A, B; same rating, `sameRateType`); edges A→M, B→M | **Supported** since `fee054ce` ("allow building a round from multiple previous rounds", 2026-09-07); V52, the same day, only made the column a CSV. So the 2025 merges couldn't be done in the app |
| 2 | **Regional backtracking**: an earlier round's files of region X not judged in later rounds | previous = A, regions = X, `exclude_rounds` = later rounds (V95); edges A→R and the leave-outs | **Supported in 26.11** (V95, `stack/103`, not merged), through the round form (§4); no map shortcuts for now (decision 12) |
| 3 | **Add files of round X to an existing round R** (a late source) | No such action. "Distribute new files" re-reads only R's own previous rounds. A pasted list adds files (`added_how = list`) without naming X | **Not planned** for now (decision 18). P3's re-apply of edited filters, including `previous`, covers only a *paused* round (`round-images-two-step.md:296-318`); an active round has no path |
| 4 | **Copy votes from round X into round R** (carry earlier votes) | A new round with several previous rounds (item 1), which does what merging rounds did (maintainer); edges A→R, B→R. That is why the unrouted `mergeRounds` (vote moving) and `Round.delete` were removed in `2a1600a7` after the 2026-11-01 decision "Round merging isn't wanted" (`98560474`); production's `f9c31ce5` still has them, without a route | **Covered** by several previous rounds (`fee054ce`, 2026-09-07). Not a missing operation |
| 5 | **Use another round's yes/no result as a filter** (e.g. Q-02-nofop's verdict applied to a round from Q-02) | The vote filters read only the round's previous rounds. A leave-out drops *all* of a round's files, whatever their votes | **Planned here, phase 3b** (decision 18, R3): "leave out files rejected in round B" (≥N "no" votes), in a separate `reject_rounds` column; binary B rounds only. "Not selected" is an open question at the start of 3b. Q-02-nofop's result was used so (maintainer) |
| 6 | **Hide or delete mistaken rounds** | Cancel and Reinstate (`round.status`, phase 1); delete without juror input (phase 3) | **Planned here** (decisions 5–9) |
| 7 | Re-open a stopped round | Start (`setActiveGuarded`) | **Supported** |
| 8 | **Add a juror to an existing round** or redistribute a stopped juror's files | Stop or start a juror (`setRoundUser`, `RoundController.scala:473-490`). Saving an existing round changes no jurors (`saveEdit`, `:347-379`) | **Missing; belongs to P3** (decision 18), as part of closing the Montage gaps (parity items 12–13). Cross-reference only. P3 mentions new files rotating over added jurors (`round-images-two-step.md:307-313`) |
| 9 | Fix a round's rating scale or filters after creation (Q-05-10 → "Q-05-10 right") | Recreate | **Not planned** for now (decision 18c). P3's re-apply of edited filters needs a paused round with no votes (`round-images-two-step.md:304-305`), so it doesn't fix a round already voted in; a rating scale has no path |
| 10 | Disqualify files for reasons other than CoI | CoI disqualify (P1b) | **Planned** (P3 review page, "Disqualify / Requalify with a reason") |
| 11 | Take another contest's round's top N | A pasted list | **Planned**, its own plan (`2026-10-29-…`) |
| 12 | Delete rounds | — | No sign it happened in 2025 (§10.5); decision 9 adds a narrow delete |

---

## Review

**v2 (first review, 14 findings):** all applied; finding 11 in part.

**v3 (second review, 9 findings):** all applied.

**v4.** All checked against `ed740ddf` and the export; none rejected.

**Maintainer directions:**
- *Steps done by hand, no overlap analysis:* v3's decision 16 and phase 2c (suggested edges) dropped;
  §11 lists the DB-only operations, with decision 18.
- *Q-02-nofop was real:* the structural trial rule is dropped, and Q-02-nofop is recorded as a real
  branch (§10.3, §11 item 5).
- *Hide, archive and delete by hand:*
  - archiving is manual, from the rounds list and the map;
  - V101 and archiving move into phase 1;
  - hints become "none" (decision 8);
  - a narrow delete is added (decision 9);
  - §10.5 checks for deleted rounds.

**Third review:**
1. Merges as the core path:
   - the short answer, §10.3 and decision 2 are reworded;
   - rule 6 is core;
   - feeders sit under Main in joining order;
   - a synthetic fixture is added in phase 1;
   - merge `rowspan` is the first thing to add if needed.
2. The structural trial rule is dropped altogether, by the maintainer's answer. That supersedes
   making it neutral; decisions 8 and 14 are consistent with it.
3. Superseded (v3's decision 16 dropped).
4. `created_at`:
   - verified that V86 adds `ON UPDATE` on 10.10+ and production already had it;
   - the writers that overwrite and that preserve it are listed;
   - V101's setters preserve it;
   - decision 17 added;
   - bulk-update note corrected (only 09:37:12 is one statement).
5. Rule details:
   - the common prefix is defined;
   - the spine skips archived rounds;
   - one numbering, rules 1–7;
   - a region subset goes to the regions table with its source track.
6. Small fixes:
   - decision 5 says 26 → 15 rows (the original finding said decision 6);
   - one set of regions-table columns;
   - §1.1 notes production lacks `round_image`;
   - the script docstring is fixed, and its limits are noted (§10.1).
7. The relation table is mentioned only under decision 15's "later".

**v5 (the maintainer's decisions, 2026-11-10/11; reviewed below).** The v4 notes above are kept as
written, "archive" included; v5 replaces archiving with Cancel.
- Decision 5 changed: no `archived_at` / `archived_by`. P3's `round.status` comes forward into V101
  with its backfill and `status_changed_at` / `_by`; `cancelled` is the hidden state; Cancel and
  Reinstate replace archive and unarchive (short answer, §1.2, §2.1, §2.3–§2.6, §5–§8, §10, §11).
- Decision 7 refines the backfill: a failed creation stays `draft`.
- Decision 11 changed: provenance by round type (§3).
- Decision 12 changed: no map actions and no unsaved-form count; §4 describes the normal round
  form; phase 3 shrinks; decision 13 stays open, deferred with 12.
- Decision 18: item 5 becomes phase 3b; item 8 goes to P3 (cross-reference only); items 3 and 9 are
  not planned; item 11 stays its own plan.
- Decision 3: the WLE Ukraine check of rule 2 is phase 1's entry condition.
- Decisions 1, 2, 4, 6–10 and 14–17 as recommended; text that argued only against the rejected
  options is trimmed.
- Sizes: phase 1 L (7–9 d), phase 3 M–L (3–4 d), phase 3b M–L (2–3 d); phases 1–3b about 15–20
  agent days.

**v5 review (independent review, and the maintainer's answers 10+ and R1–R3, 2026-11-11):** all
applied.
1. **Blocking, decision 10+:** provenance is a collapsed control, fetched by a separate GET on
   expand; the endpoint uses `Secured.inContest` and refuses jurors, pure pre-screeners and the
   jury-round case, with negative specs; no provenance query on render, vote, keyboard navigation
   or preload (`LargeViewControllerSpec`); a paired `GalleryLatencyBenchmark` pilot
   (`bench.cases='^(large|vote)'`) with absolute deltas. §3, decision 10+ and phase 2
   (2.5–3.5 d); total 16–21 d.
2. Status writers: `setActiveGuarded` and `release(close)` change the status only where
   `distributing = 0` and it isn't `cancelled` (`setCurrentRound` stops rounds being created,
   `RoundService.scala:362`), with a spec.
3. Reinstate (R2): `draft` if the creation never completed, "Creation incomplete", finished by
   "Distribute new files" (`RoundService.scala:265`), not Retry (`JobScheduler.scala:317-323`);
   `paused` otherwise.
4. P3's table gains the stopped row (`paused`, `distributing = 0`); P3 tells "before first
   activation" by `distributing = 1`, and its paused-gated review and Distribute skip finished
   rounds. Recorded here only (§2.6, §6; decision 18c).
5. Locks: Cancel checks after `FOR UPDATE`; start refused in `setActiveGuarded`'s UPDATE;
   `enqueueOrFail` and `onRetry` check the status under the round lock.
6. V101's DDL follows V95: INSTANT columns, the CHECK apart, `created_at` in its own statement
   (`MigrationNamingSpec:36`); after a rollback the maintainer reruns the backfill, and readers
   derive a NULL status from the flags.
7. Phase 3b (R3): a separate `reject_rounds` column, `prevRejectedBy` / `prevRejectedMode`
   semantics, `vote_aside` ignored, B not open at creation, in the counts, map and
   `inputSummary`; binary B rounds only.
8. Decision 11 (R1): the reading is decided, not inferred; "votes in" = `User.isRoundJuror`.
9. Nits: `JobScheduler.cancel` gets the admin's id; `Round.setActive(id, …)` and
   `setInactiveAllInContest` are deleted; `Round.create` takes the status from its flags, and
   `FixtureVersion` is bumped if Gatling's `load` writes it; §11 item 9 is "not planned" (P3's
   re-apply needs a round with no votes).

**v5 follow-up review (2026-11-11):** all applied. Retry is refused through a new
`JobHandler.retryRefusal` hook in `JobScheduler.retry` (`onRetry` can't refuse), mapped by
`distributeNewFiles` and `submit`. Phase 3b's `reject_rounds` always excludes, with a threshold
column; V93's mode is dropped, and "not selected" is an open question. The provenance script handles
401/403/404. "Creation incomplete" also covers a draft with no creation job. The total is the sum
of the ranges, 15–21.5 d (item 1 above said 16–21).
