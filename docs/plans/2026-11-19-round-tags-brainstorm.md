# Brainstorm: round tags, the round tree, second looks and NOFOP (WLM Ukraine 2026)

Recovered 2026-11-25; originally written 2026-11-19.

Status: Input for the December round-lineage slice (track label, chip, Track menu); 5 questions for the organizer still open; the "Second look at rejects" idea would reopen lineage decision 12.

Written 2026-11-19 from the WLM UA organizer's answer about rounds 1972, 1973, 1976 and 1977. Read-only: no repo files
changed. Sources:
- code at `origin/stack/199-signin-redirect`;
- the plans: `2026-11-10-round-lineage.md` (#288, decided), `2026-11-16-round-editing.md`,
  `2026-11-10-multi-role-plan.md`, `2026-11-18-contest-series.md` and the roadmap;
- Montage at `origin/master` 871a67a, which differs from the local checkout only in i18n, and
  `docs/comparison/02-montage.md`.

## 0. Gaps in this brainstorm

**The production copy could not be queried.** The application's
database login was refused with "Access denied", as were the other credentials at hand, and the
permission system blocked the root login that earlier sessions used. I did not try to get around it. So the
round tree below is **inferred**, from:
- the WLM UA organizer's naming scheme;
- an earlier session's snapshot of contest 628 (2026-11-16): 1958 `Q-00` (paused), 1959 `Qn-00`
  (active), 1965 `Q-01` (active), 1966 `Q-00-1` (paused);
- the code.

Ids 1974 and 1975 belong to the Nepal contest (466), so they are not part of this tree.

**To verify, run this read-only as an account that has access** (the table is `round` since V82):
```sql
SELECT id, number, name, previous, active, status, rates, prev_selected_by, prev_rejected_by,
       prev_rejected_mode, prev_min_avg_rate, top_images, category, exclude_category,
       category_clause, exclude_rounds, special_nomination, created_at
FROM round WHERE contest_id = (SELECT contest_id FROM round WHERE id = 1972) ORDER BY id;
```
Also run `SELECT round_id, COUNT(*) FROM round_user GROUP BY round_id` for the same ids. It shows
whether the second-look rounds really have different jurors.

## 1. the WLM UA organizer's tree, inferred

```
Quality:  Q-00 (1958) ──selected──▶ Q-01 (1965) ──rejected?──▶ Q-01-nf (1976)  NOFOP check
             └──rejected, other people──▶ Q-00-1 (1966) ──rejected?──▶ Q-00-1-nf (1972)
                                               └── Q-00-1.5 (1973)  trim: "too many for round 2"
Quantity: Qn-00 (1959) ──selected──▶ Qn-00-1 (screening) ──rejected──▶ Qn-00-1-nf (1977)
```

**How the "rejects" rounds can be built today.** The app has a first-class way to do it, shipped
in 26.11: previous round = X, "Rejected by at least N jurors" = 1, mode **"only those"**
(`RejectedMode.Only`, V93; `round/edit/imageFiltering.scala.html:61-81`). Its doc comment says it
is "to review the rejected files in a round of their own". So it isn't a workaround. Whether the WLM UA organizer
used it, or for example a pasted list, needs the SQL above.

**Jurors are chosen on the form for each round.** "Different people" is the admin picking other
jurors. Nothing checks it or helps with it.

**Merging rescued files back exists too**, but has to be found:
- a later round can take **several previous rounds** (since `fee054ce`, 2026-09-07), for example
  "Q-02 from Q-01 + Q-00-1, selected by ≥1";
- a file passes if it passes in any of them.

**What the tool lacks:**
- nothing *shows* this structure: names are the only record;
- there is no notes field;
- there is no "use a NOFOP round's verdict to leave files out". That is lineage phase 3b, decided
  but not built.

`vote_aside` is **not** a "set aside" feature. It is the internal table for votes of deleted or
disqualified files (V63/V82, `ImageJdbc.archiveVotes`). Don't reuse the name for a user feature.

## 2. What exists vs what is decided vs what is new

| Piece | State |
|---|---|
| Status/Type/Order menus, type icons (#370, #378, #380) | shipped |
| `round.status` (V105, P3-2) | built (lineage §2.6) |
| "Rejected by ≥N, only those" (V93), several previous rounds, leave out rounds (V95) | shipped |
| `special_nomination` (WLM UA only), category operations | shipped |
| Lineage map (HTML table, tracks), **manual track label `round.track`** (decision 4), `inputSummary` (§1.2), Cancel/Reinstate | decided, not built (~15–21.5 d in total) |
| Phase 3b "leave out files rejected in round B" (`reject_rounds`) | decided, not built |
| Menus grouped by track (decision 16) | decided, not built |
| Map shortcuts and prefilled form links | **left out by decision 12** |
| Pre-screening round type (V97) | decided, not built |
| P3 disqualify/requalify with a reason | decided (round-images plan), not built |
| Round templates | an explicit **non-goal** of the series plan (#373) |
| Round notes, juror directions, several tags | new |

## 3. Ideas

### 3.1 Round tags and labels

**A. The decided track label as the "tag" chip** (build part of lineage phase 1 early).
- **What:** `round.track`, one free-text label per round, inherited by later rounds that have none.
  It is shown as a coloured chip after the round name on:
  - the Rounds page;
  - the Statistics pager;
  - the gallery round menu.

  The input autocompletes from the contest's existing labels. The colour is a stable hash of the
  label onto 6–8 `--wlx-*` tokens, checked in both themes. The chip's text carries the meaning, so
  colour is not the only cue.
- **The automatic default** is lineage rule v4:
  - a special nomination gives its nomination name;
  - a category track gives the category minus the common prefix;
  - so most rounds get a chip with no typing, and the WLM UA organizer types only "Quality" and "Quantity" once on
    the roots.
- **Value:** "tags would help me visually" and "explain to someone if I weren't there". It covers
  her Q / Qn / nomination dimension directly.
- **Size:** S–M. It needs the migration that lineage phase 1 plans (`round.track`). Take the next
  free number. V103 is retired once V104 is deployed.
- **Builds on:** lineage decisions 3, 4 and 16.
- **Risks:**
  - it splits phase 1. Agree that `round.track` and its setter can ship ahead of the map;
  - inheritance must use the same `RoundLineage` code the map will use, not a second rule.

**B. A "Track" menu on Rounds and Statistics**, next to Status and Type, kept in the URL as
`track=`. `RoundsFilter` gains `track: Option[String]`, and `statRound` already handles "switch to
the first match".
- **Size:** S after A.
- **Value:** "show me only the quantity line".

**C. Derived step badges** (no typing). Small grey badges computed from the round's own settings:
- "Rejects of Q-00": `prevRejectedMode = Only`;
- "Merge": several previous rounds;
- "Top 50": `top_images`;
- "Regions (8)": a region subset;
- "Pre-screening": V97 later.

They are the short form of lineage's `RoundLineage.inputSummary`.
- **Value:** the "-1", "-nf" and "1.5" parts of her names are partly *about the filter*, and the
  tool can say that itself.
- **Size:** S–M, mostly i18n in 4 languages.
- **Builds on:** lineage §1.2 (all filters already have labels or keys).
- **Risk:** badge clutter. Show at most 2, with the full summary in a tooltip and on the stats
  header.

**D. Several free tags per round, or a fixed per-contest set.**
- **Free tags:** a `round_tag` table, chips and a menu. M.
- **A fixed set** (quality, quantity, special nomination, NOFOP, second look): simpler to filter and
  translate, but it is WLM-UA-specific, and "special nomination" and "second look" are already
  derivable (A, C).
- **Recommendation:** don't build D now. A (one track label) plus C (derived step) covers her
  dimensions: the *line* (Q / Qn / nomination) and the *step* (screening, rejects, NOFOP, trim).
  The step "NOFOP check" is the only one not derivable, and E covers it. Ask the WLM UA organizer (question 3).

**How tags relate to the existing pieces:**
- the **type icon** stays the rating (Yes/No vs stars) in its fixed slot before the name;
- the **track chip** comes after the name;
- **step badges** come after that, smaller and grey;
- `special_nomination` *feeds* the track automatically, and it is not a separate tag.

### 3.2 Making the tree visible: the lineage map

**F. Lineage phase 1 as decided** (HTML table, rows = tracks, read-only for organizers,
Cancel/Reinstate).
- **For the WLM UA organizer's 2026 tree:**
  - Quality's spine is Q-00 → Q-01;
  - Q-00-1 is a fork sub-row, "from Q-00 (rejects)";
  - Q-00-1-nf, Q-00-1.5 and Q-01-nf are forks;
  - Quantity is its own track;
  - once she merges (for example Q-02 from Q-01 + Q-00-1 + Qn-…), Main appears on top.
- **What to add for her**, as cell line-4 text (within decision 2):
  - the derived badge from C ("rejects of Q-00");
  - the jurors' count, plus "**other jurors**" when the round's jurors don't overlap its previous
    round's;
  - **counts flowing**: in = files, out = selected and rejected.

  Lineage §1.3 calls a vote GROUP BY "too heavy". For *stopped* rounds the result is fixed, so cache
  it as `RoundCounts` does. Open rounds show "in progress".
- **Size:** L (7–9 d, as planned). Counts on stopped rounds are +S–M.
- **Value:** a successor organiser opens one page and reads "Q-00-1 = rejects of Q-00 by 3 other
  jurors; 3,500 in, 410 rescued".
- **Risks:**
  - the planned entry condition (the WLE phase-0 check, decision 3) is still open;
  - a measurement is needed before the count queries.

### 3.3 First-class "second look at the rejects"

**G. A "Second look at rejects" shortcut** on a stopped round's stats page or Rounds row. It opens
the normal round form prefilled with:
- previous = X;
- rejected by ≥1, only those;
- the same rating;
- name `X-1`, or `X-2` if that exists;
- track inherited;
- jurors = the contest's jurors **minus X's jurors**, pre-ticked as a suggestion.
- **Size:** S–M (a GET with query params the form already understands, plus juror preselection).
- **Value:** the step the WLM UA organizer does by hand every year, with "different people" built in.
- **Conflict:** decision 12 left *map* prefill links out "at least for now". This is a different
  place, the stats page, and has one link only. It still re-opens that decision, so ask the
  maintainer before building it.

**H. "Not the same juror" distribution.** Optional: a file goes only to jurors who have not voted
on it in the previous rounds. Then the same pool can do a second look, crossed over.
- **Size:** M–L (a distribution constraint, fairness with fewer eligible jurors, a count preview).
- Only if the WLM UA organizer says the pool is the bottleneck (question 4).

**I. Merging back is already possible.** A round with several previous rounds. What helps:
- the form's position line ("Will follow: Q-01 + Q-00-1 → Main", lineage §5);
- provenance in the large view, lineage phase 2: "rescued in Q-00-1".

No new mechanism is needed. Add a help sentence in the admin guide: "To bring rescued files back,
pick both rounds as previous rounds".

**Not recommended:** a per-file "pull back into round Y" button. It is §11 item 3 ("add files of
round X to an existing round"), which the maintainer marked not planned.

### 3.4 NOFOP as a step or a filter, not a full round

**E. Phase 3b as decided: "leave out files rejected in round B".**
- A NOFOP round's "no" votes then drop files from every later round with no copying.
- 2025's Q-02-nofop was used exactly this way by hand. That is why 3b exists.
- **Size:** M–L (2–3 d).
- Her -nf rounds stay rounds, but they stop needing bookkeeping later.

**J. NOFOP as a disqualify reason** (P3 "Disqualify / Requalify with a reason"). An organizer marks
a file "NOFOP" in the large view, contest-wide:
- `round_image.status = dq` with a `dq_code`, which already exists for conflicts of interest;
- the file disappears from the open round and is never distributed again;
- requalify undoes it.
- **Value:** "whether one of us has time to drop NOFOP as an intermediate round". Agonk, nikk or
  the WLM UA organizer can drop NOFOP files *while* any round runs, with no separate round.
- **Size:** M on top of P3's disqualify.
- **Risks:**
  - P3 isn't built;
  - contest-wide versus per-round scope;
  - who may do it: Admin and Organizer? Organizers are read-only today (see the roles in the roadmap), so this would be their first write.

**K. A third answer for jurors, "No: NOFOP"** (a reject reason in Yes/No rounds).
- **Size:** L. It changes the vote model, the keys and the stats.
- **Recommendation:** no, unless question 1 shows that jurors, not organizers, should find NOFOP.

**L. A Commons category filter.** If NOFOP files get a Commons template or category (for example a
"FoP-Ukraine" deletion-request category), `exclude_category` already leaves them out.
- **Size:** 0 in the app.
- **Open:** whether Commons tagging is fast enough during the contest. Ask.

### 3.5 Naming and descriptions

**M. Suggested names on the form**, from the lineage:
- `X-1` for a rejects round from X (the first free number);
- `X-nf` for a round from X named by a NOFOP preset;
- `X.5` is not guessable, so leave it to her.

The suggestion is editable, never forced. **S.**

**N. The summary under the name** (the tool writes what the round is): "from Q-00 · rejected by ≥1,
only those · 3 jurors (none from Q-00) · 1–10 stars". Shown:
- on the Rounds page, as a muted second line;
- in the stats header;
- on the map, the same `inputSummary`.

**S–M. The single most useful "if I weren't there" piece,** because it needs no discipline from
anyone.

**O. A round note.** An organizer-only free text: why the round exists, who runs it, what to do
next. It is Montage's `rounds.description` ("coordinator comments, not shown to jurors").
- **Size:** S (a migration, a form field, shown on stats and the map, escaped plain text).
- **Optional sibling:** juror-facing **directions** (Montage's `rounds.directions`), for example
  "Reject only for NOFOP: modern buildings and artworks by living authors". Shown on the juror's
  round page. S–M.

### 3.6 Less bookkeeping elsewhere

**P. A target count per round** for "1.5" decisions. `RoundLimits(min, max, recommended)` already
exists in the model, but its columns are commented out in `Round.scala:363-367, 412-413`. Revive
`recommended` as a "target for the next round":
- the stats page shows "selected by ≥1: 1,240; target 500 → consider a trim round or top-N";
- it is the information Montage's threshold preview gives (§4).

**S–M.** Value: decide early whether a .5 round is needed.

**Q. Copy last year's round skeleton** ("playbook"):
- clone names, track labels, notes, ratings and filter shapes as draft rounds with no jurors;
- year-shift the categories.

**L.** It is a series-plan non-goal ("Round templates"). Later, after #373. A cheaper first step is
the map plus notes, printable, which documents last year's playbook for free.

**R. "Who to ask"**, as a list of the contest's Admins on the map or stats page. It probably exists
via the Users page. S. Low priority.

## 4. Montage comparison

**Sources:** Montage at `origin/master` 871a67a (a local checkout differs in i18n
only); `docs/comparison/02-montage.md` (MT-F4.5, F5, F7.8, F8).

| Topic | Montage | Evidence |
|---|---|---|
| Round chain | **Strictly linear.** `create_round` refuses a second round while one is active or paused ("can only create one active/paused round at a time"). Rounds are numbered by `campaign_seq`. | `montage/rdb.py:1357-1362, 327, 1380` |
| Advance | Finalise a yes/no or rating round at an **average threshold**. Entries with avg ≥ t (non-DQ, completed votes) go to the next round, which is created paused. The source is exactly one round; no top-N, no merge, no completion check (a TODO). | `rdb.py:1964-1979, 2084-2108`; `admin_endpoints.py:704-759` |
| Threshold preview | `preview_results` gives a threshold → count map. The next-round form offers "x.xx / 10 (N images)". | `admin_endpoints.py:664-701`; `RoundNew.vue:183-191, 337-339` |
| Import from a round | `import_method=round` + `previous_round_id` + `threshold`, **≥ threshold only**: there is no "below threshold" / rejects. | `admin_endpoints.py:423-428` |
| Second look at rejects | **None.** Only the passes advance, and only one round can be open, so a parallel rejects round is impossible. The workaround is to download the results CSV and import a file list into another campaign. | as above; MT-F4.4 |
| Tags and labels | **None.** A round has `name`, `description` ("coordinator comments, not shown to jurors") and `directions` (shown to jurors). The UI edits and shows `directions` (`RoundEdit.vue:21`, `RoundInfo.vue:12`, `JurorCampaignCard.vue:35-41`); `description` is API-only (`admin_endpoints.py:542`), not in the Vue UI. | `rdb.py:302-312` |
| Disqualification | `round_entries.dq_reason` + `dq_user_id`, per round. Auto rules: upload date, resolution, file type, uploader. Manual DQ with a free-text reason, **only while paused**; requalify restores votes. **No UI** for manual DQ. | `rdb.py:633, 1495-1540`; MT-F5.6 |
| Entry flags | A `flags` table (round entry, user, free-text `reason`): jurors "report a problem"; coordinators list flagged entries by count. **No UI**, and no membership check. | `rdb.py:754-777`; MT-F7.8 |
| Favourites | A juror's per-entry bookmark (heart). Not visible to coordinators as a list. | MT-F7.7 |
| Campaign view | The campaign page lists the round cards in sequence and hides cancelled rounds (`rounds.filter(status !== 'cancelled')`). No graph, since the chain is a line. | `ViewCampaign.vue:32-34, 351` |
| States | `paused → active ↔ paused → finalized / cancelled`. `finalized` locks the round. | `rdb.py:316`; MT-F5.1 |

**Worth borrowing:**
1. **`description` + `directions`.** Coordinator notes and juror instructions, the two text fields
   per round, are exactly O and its sibling. Montage's split (notes hidden from jurors, directions
   shown) is the right model, and Montage's UI even forgot to show `description`. Show ours on the
   stats page and the map.
2. **Threshold → count preview.** "If you cut here, N files go on" is what the WLM UA organizer needs for "1.5"
   decisions. wlxjury's count check runs on a *saved* round (lineage decision 12 left the
   unsaved-form count out). A results-side table on the stats page of a stopped round (avg ≥ x /
   selected by ≥ n → N files) avoids the form problem. Fits P. S–M.
3. **A reason on DQ, and entry flags as a coordinator worklist.** This supports J: a disqualify
   reason code "NOFOP", and optionally a juror "flag: possible NOFOP" that lands on an organizer
   list instead of a full NOFOP round. Montage built both but never gave them a UI, so we would be
   doing the useful part.
4. **Cancelled rounds hidden from the campaign view.** This is the same as lineage decision 6, so
   it confirms the decided design.

**What doesn't fit the WLM UA organizer's workflow:**
- **One open round at a time.** WLM UA runs Quality, Quantity, second looks and nominations in
  parallel, with different volunteers. Montage's linear chain cannot express her tree at all.
- **Advance by average threshold only.** She needs "rejected, only those", "selected by ≥1 in any
  of several rounds" and top-N, which wlxjury already has.
- **Free-text DQ reasons per round.** NOFOP is a property of the *file* in the contest, not of one
  round, and must be filterable. Use a code (`dq_code`), not prose.
- **No visual of the sequence.** Montage needs none for a line. Hers is a tree, so the lineage map
  is wlxjury's own advantage, not something to copy.

**The upshot:** Montage has nothing like tags or a second look. Its contribution is the **two text
fields** (notes and directions) and the **threshold → count preview**. Both are cheap and fill real
gaps here.

## 5. Recommended order

1. **The summary line + round note** (N + O; S–M).
   - The tool writes what each round is, and the WLM UA organizer adds why.
   - No migration for N; one small migration for O (the next free number).
   - Directions for jurors (Montage's split) can come in the same PR or next.
   - It delivers "explain to someone if I weren't there" immediately.
2. **The track label chip + Track menu** (A + B; S–M).
   - The decided `round.track`, shipped ahead of the map with lineage's inheritance rule, plus
     derived step badges (C).
   - It delivers "tags would help me visually".
   - Needs agreement that this part of phase 1 can go first.
3. **Lineage map phase 1 with flow counts** (F; L), then **phase 3b** (E: a NOFOP round's verdict
   leaves files out).
   - The "Second look" shortcut (G; S–M) after a one-line decision that re-opens decision 12 for
     the stats page.
   - The threshold/target preview (P / Montage) alongside.

Later: J (NOFOP disqualify reason, with P3), H (cross-juror distribution), Q (playbook clone,
after series).

## 6. Questions for the WLM UA organizer

1. **In a "-nf" round, what happens to a photo you mark?**
   - Is it dropped from the contest for NOFOP?
   - Or is it a reject you give back because the reason was only NOFOP?
   - Or are you collecting the files to nominate them on Commons?

   (This decides between E, J and L.)
2. **When a second look rescues a photo, where should it go?** Into the same next round as the main
   line's selected photos (a merge), or along its own line to the end?
3. **Tags:** would one label per round for the *line* (Quality, Quantity, a nomination) be enough,
   if the tool wrote the *step* itself ("rejects of Q-00, other jurors", "top 50")? Or do you need
   several tags you type yourself?
4. **"Different people":** does the second look need new people, or only someone other than the
   juror who rejected that photo? (If the latter, the same pool can cross over: H.)
5. **Next year:** would you start from a copy of this year's round setup? Or would a written note
   per round, plus the map, be enough for someone else to repeat it?
