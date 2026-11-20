# Montage parity on rounds

Status: goal after the two-step rounds (`2026-11-02-round-images-two-step.md`); the
order in §4 is a recommendation. Written 2026-11-02 from a review of Montage
(https://github.com/hatnote/montage) at d879cb8 (2026-10-31) and wlxjury at b641c78d.
**Refreshed 2026-11-20:** §3 and §4 checked against wlxjury `master` at 2bea73cb (squash commits
up to #399) and the open PRs #391–#395; Montage checked at 871a67a (2026-11-19), where only
translations changed since d879cb8, so the line numbers below still hold; §5 added (Montage's
flagging and what it means for questions on yes/no rounds, compared 2026-11-19). File paths in
§1, §3 and §5 are inside the Montage repository.

## 1. Montage's round model (`montage/rdb.py`)

- Tables: `campaigns` → `rounds` (name, description, `directions`, `status`, `vote_method`,
  `quorum`, `config` JSON, deadline, open/close dates); `round_jurors` (`is_active`, flags with
  `skipped_ids`); `entries` (global files); `round_entries` (:624-642: round membership,
  `round_source_id`, `dq_user_id`, `dq_reason`; no score columns, no unique key on
  (round, entry)); `round_sources` (:735-751: method category/csv/gistcsv/selected/round, params);
  `votes` (:809-828: task and vote in one row, status active/completed/cancelled, `modified_date`);
  `flags`, `favorites`, `results_summaries`, `audit_log_entries`.
- Two-phase already: a round is created `paused` (one active or paused round per campaign,
  :1359-1362); import, disqualification, quorum and jury changes need `paused`; `activate`
  (:1717-1746) tasks every non-disqualified entry with no vote rows yet (:3360-3376), so
  re-activating is incremental. `cancel` cancels open votes; finalize via advance; unfinalize is
  CLI only.
- Vote methods: yesno (0/1), rating (0–1 in quarters), ranking (ranks; capped at ~100 entries,
  Schulze over completed ballots, :2153-2207).
- No stored per-entry score: views group votes on the fly; averages over completed votes only
  (entries with none drop out, :2084-2108), hence a damped score `(n·avg + m·C)/(n+m)`
  (:2123-2151).
- Next round (`montage/admin_endpoints.py`:664-759): a preview of thresholds (each distinct
  average → entries at or above it), then advance by average ≥ threshold. No top-N, no several
  source rounds; disqualifications and config are not carried over.
- Disqualification (`autodisqualify_by_date`, `_by_resolution`, `_by_filetype`, `_by_uploader`,
  :1452-1640): upload date outside the campaign, resolution, file type, uploader is a
  juror/coordinator/organizer/maintainer. Each sets `dq_reason`/`dq_user_id` and cancels all the
  entry's votes; preview, manual disqualify and requalify while paused; API only.
- Mid-round, paused only: change the jury (`modify_jurors`, :2290-2346: a swap moves open
  tasks; otherwise open tasks are reassigned, avoiding entries a juror voted on, evening the
  queues), change the quorum (`modify_quorum`, :2467-2529), remove a juror (`remove_juror`,
  :2348-2465, optionally cancelling their completed votes, then re-covering; API and CLI only).
- Also: skip, faves (no list page), flags and juror stats (API only), round stats map (:391-430),
  results/entries CSV and reviews JSON, a publishable Schulze report, an audit log of admin
  actions.

**Changes since d879cb8 (checked 2026-11-20 at 871a67a):** only translatewiki.net updates to
`frontend/src/i18n/`; no code change. Open Montage PRs (not merged) touch what this plan uses:
#683 (finalize a round through the DAO: close date, audit log, status check), #672 and #684
(finalize a campaign after its ranking round), #679 (coordinators may cancel a campaign), #677
(refuse skipping a task that isn't the juror's own), #678 (remove the dead mixed-status check on
ranking ballots). None changes the round model above.

## 2. Taken into the round-images plan

Built, except where noted (the two-step plan and the round editing plan record the details).

- Round membership apart from votes, filled and reviewed before tasks (`round_image`, V84;
  review before distribution, P3-3, #349).
- Activation rule: distribute rows `in` with no vote row yet (idempotent, incremental; P3-3's
  `distribute` mode).
- A unique key on (round, image) (Montage needed duplicate-repair tools without one).
- Disqualified rows kept with a reason, who and when (Montage lacks the date); votes kept aside
  with their rates so requalifying restores them (Montage reopens them as tasks) (#244, #262).
- Stored counts `assigned`, `voted`, approvals, rejections, `rate_sum` (V89, V90): written and
  kept up to date, but the lists don't read them yet (`roundImage.read = false` in
  `conf/application.conf` until the pilot). With them, both averaging rules and "votes yet to
  come" come from one row. The damped score isn't built (row 7).
- Next rounds read only `in` rows, so disqualifications carry forward.
- `vote` stays task and vote; no task table (Montage merged its task table into votes in 2017).

## 3. Gaps

S/M/L = size. "RI" = the round-images plan. State on wlxjury `master` on 2026-11-20; open PRs
are named as such. Numbers 1–25 are as in the first version; new rows are added at the end.

| # | Feature | Montage | wlxjury on master | State | What's left |
|---|---|---|---|---|---|
| 1 | Round membership, review before tasks | `round_entries`, created paused | `round_image` (V84, #236); a file keeps its row whatever its votes (#347); "Create and review images", the review page and Distribute (#349); jurors chosen at Distribute, filters re-applied in review (#351) | **Closed** | – |
| 2 | Disqualification: reason, who/when, list, preview, manual, requalify | `disqualify_entry`, `requalify_entry`, `get_disqualified`, preview; API only | Conflicts of interest: a preview on the round's conflicts page, then disqualify; votes kept aside, requalify restores them (#244). Code, who and when stored (V84); a history row per disqualify and requalify (V88, #262), shown on the round's conflicts page (linked from Round statistics) | **Partly closed** | Disqualify any file by hand with a reason (issue #82); Remove / Put back in review (P3-4, not built). Only the `coi` code is written; `manual`, `min_mpx`, `category`, `date` are reserved. A list of all disqualified files with export (see 22) |
| 3 | Auto-disqualify by upload date | `autodisqualify_by_date` (:1452) | Upload date not stored | **Open**, M | Store each file's Commons upload time at import (support diagnostics plan, D-6 "import provenance"), then a `date` rule |
| 4 | Auto-disqualify uploader in jury/staff | uploader of first revision | CoI by the `{{Information}}` author over wider roles (`CoiMembers`). The author clean-up job (#312) writes the original uploader into the author field when a credit template names nobody, so CoI matches it there. No separate uploader field is stored at import | **Partly closed**, S–M | A separate uploader field at import (with D-6), matched as well as the author |
| 5 | Resolution / file-type rules | min px, MIME allowlist | Min Mpx, min MB, image/video; each file's MIME type is stored; filters re-applied in review (#351) | **Partly closed**, S | A MIME allowlist filter |
| 6 | Threshold preview with counts | `preview_results` | "Count new files" only | **Open**, S | P3-6 of the round editing plan (decided, ~1.5 days); the stored counts are written (V89, V90) but not read yet (`roundImage.read` off until the pilot) |
| 7 | Damped score view | :2123-2151 | One score rule per round: over the jurors who rated, over the jurors assigned (unrated as 0), or the sum (V90, #267) | **Partly closed**, S | The damped score, as a view or a fourth rule |
| 8 | Round stats (entries, tasks, open %, DQ, uploaders, types) | :391-430 | Round statistics: per-juror counts, totals, waiting and deleted files, links with counts to comments, conflicts and authors (#329, #336); filters (#370, #380). Contest health page: rounds, imports, distribution checks (#375); warnings when no juror can vote in an open round (#385) | **Partly closed**, S | Open %, uploaders and file types per round |
| 9 | Lifecycle paused/active/finalized/cancelled | :82-90 | `round.status` draft / paused / active / finalized / cancelled, with who and when (V105, #348). `paused` is a round in review or a stopped one; `cancelled` is a creation an admin cancelled | **Partly closed**, M | Finalize (the status exists, nothing writes it yet): lock votes, record the advance rule. Cancel and Delete of a round (round lineage plan, not built) |
| 10 | Directions for jurors, notes, deadline | yes | None | **Open**, S | – |
| 11 | Edit settings after creation | name/directions/config any time; quorum/jurors paused | Name, score rule, ✗ mark, CoI flag; filters re-applied while in review (#351); criteria editable until the round runs or has votes (#386); jurors through 12–13 | **Partly closed**, M | Filters of a distributed round; directions (10); jurors per file (14) |
| 12 | Add/swap jurors, redistribute avoiding seen files | `modify_jurors` | Add a juror to a running round (#345), with a fair share in rounds of N jurors per file (#346); the add takes waiting files first (#368); "Start and give a fair share" for a stopped juror. A juror never gets a file they already have a row for. A swap is "add Y, then reassign X" (13) | **Closed** (a small fix in open PR #394) | #394: offer only people outside the round; stopped jurors restart from Round statistics |
| 13 | Remove juror, keep/discard votes, re-cover | `remove_juror` | Reassign a juror's unrated files to the other jurors; their votes stay and the juror ends stopped (#368). "Reassign files…" only when someone can take the files (#377, #383) | **Partly closed**, M | Discard the juror's votes and re-cover them (deferred in the round editing plan, §8) |
| 14 | Quorum change mid-round | `modify_quorum` | `distribution` fixed after creation | **Open**, M | Uses the same rebalance job as 12–13 (`round_rebalance`) |
| 15 | Audit log | `audit_log_entries` | Per-file disqualify/requalify history (V88); round status with who and when (V105); job rows record who queued what; account change log (V96, #294). Contest change log (`contest_change`, V109) in open PR #392 (contest series CY-1) | **Partly closed**, M | A round's history of settings and jury changes (`round_event`, left out of the round editing plan); one page to read it |
| 16 | Vote time | `modified_date` | `vote.voted_at` (V97, #298), old votes back-filled (#299); sort and show by vote time (#298, #303); index (V99, #305) | **Closed** | – |
| 17 | Skip | `skipped_ids` | n/a (galleries: jurors vote in any order and go back) | Not needed | Optional |
| 18 | Faves | yes | None | **Open**, S–M | – |
| 19 | Flags to organizers | API only | Comments on files: the comments report with scope, latest-comment order and CSV (#313, #319, #331); comment counts and chips on files (#310, #352) | **Partly closed**, S | Tags and short notes on yes/no votes (26, §5) |
| 20 | Ranking rounds + Schulze | :2153-2207 | None | **Open**, L | Needs finalize (9). Issue #119 (STV) |
| 21 | Final report, publish | `build_campaign_report`, `publish_report` (:2559-2644) | Results of criteria rounds (#247) only | **Open**, M–L | 20, or a rating-only variant |
| 22 | Round images export (metadata, how added) | `get_round_entry_export_rows` (:1248-1285) | A round's results as CSV; the review page counts files by how they were added (#349) | **Partly closed**, S | A CSV of the round's files with metadata, how and when each was added, and disqualifications |
| 23 | Checked import with per-row report | `montage/import_check.py` | Unreadable lines and titles not found are reported; a file without image info no longer blocks the import (#276); the form checks a source without a prefix on Commons and warns when it doesn't match the contest (#374, #375); the images page says when an import found no files; the health page lists each import's counts (#375) | **Partly closed**, M | A downloadable per-row report |
| 24 | CSV / sheet / gist URL source | `montage/import_check.py` | None | **Open**, M | – |
| 25 | CSV formula guard | yes | Every CSV export cell goes through `Csv.text`, which quotes a leading formula character (#227) | **Closed** | – |
| 26 | Notes and tags on votes | review text on ranking votes (`votes.flags['review']`); flags | Comments only, apart from votes | **Open**, M | §5 |

**Count:** of rows 1–25, 4 closed, 12 partly closed, 8 open and 1 not needed (17). Row 26 is new
and open.

wlxjury is ahead on:
- **Files and filters:** several previous rounds merged; top-N with a stored binary order;
  "selected by" / "rejected by at least N" in votes, with modes (#268); region, monument,
  category (with exclusions, #210) and size filters; "leave out files that are in round(s) X"
  (V95, #286); adding files to a running round, from a pasted list too (#203); deleted and
  renamed file tracking (#202).
- **Review and jury changes:** review before distribution with re-apply and prune (#349, #351);
  add a juror with a fair share and reassign unrated files in a running round, which pauses
  itself only while the job runs (#345, #346, #368); background distribution with resume and source-round freezing.
- **Voting:** 3–20 star scales and video; the ✗ mark in rated rounds (#268); criteria rounds
  with a catalogue and results (#226, #235, #247, #386, #388); a score rule per round (#267);
  galleries where jurors can go back; vote times (#298).
- **Conflicts of interest:** author-based CoI over more roles (#209); disqualify keeps votes and
  requalify restores them, with history (#244, #262).
- **Organizers and support:** all-jurors views; comments with a report and CSV (#313); round
  statistics with filters (#370, #380); the contest health page and round warnings (#375,
  #385); import source checks (#374); an account change log (#294); a unique vote index.
- **Accounts:** one account across contests with a role in each (#201); add existing accounts
  and people from another contest (#354, #376); join duplicate accounts (#363). In open PRs:
  contest series across years (#392, #395) and read-only API tokens for support (#393).

## 4. Order after the two-step rounds

Item numbers stay as in the first version (issue comments link them). The table lists them in
the recommended order from 2026-11-20.

| Order | Item | Gaps | State on 2026-11-20 | Next |
|---|---|---|---|---|
| 1st | **Item 2.** Disqualification beyond CoI | 2, 22 | Partly done: CoI disqualify, codes, who/when, history (#244, #262) | Disqualify any file by hand with a reason (issue #82, P3-4's Remove / Put back); the list of disqualified files; the export (22) |
| 2nd | **Item 7.** Faves and flags, optional skip | 18, 19, 17, 26 | Partly done: comments and the comments report (#313) | Questions and tags on yes/no rounds (§5), then faves |
| 3rd | **Item 3.** Threshold preview, damped score, round stats | 6–8 | Partly done: score rules (#267), Round statistics and the health page (#336, #375) | Threshold preview (P3-6, decided); then the damped score and the missing stats |
| 4th | **Item 4.** Lifecycle, directions, deadline, editable settings | 9–11 | Partly done: `round.status` (#348) | Finalize (locks votes, records the advance rule); directions and deadline; round lineage's Cancel and Delete |
| 5th | **Item 6.** Upload date and uploader at import, MIME allowlist | 3–5 | Open (planned as D-6 in the support diagnostics plan) | Store the upload time and the uploader; then the date and uploader rules and the MIME list |
| 6th | **Item 1.** Audit log and vote times | 15, 16 | Mostly done: vote times (#298), file history (#262), account log (#294); contest log in open PR #392 | A round's settings and jury history (`round_event`) |
| 7th | **Item 5.** Jury changes, then quorum change | 12–14 | Mostly done: add a juror and reassign unrated files (#345, #346, #368); #394 open | Discard a juror's votes (deferred); then the quorum change |
| 8th | **Item 9.** Import: checked report, URL sources, formula guard | 23–25 | Partly done: formula guard (#227), source checks (#374, #375) | A per-row report; URL sources if organizers ask |
| 9th | **Item 8.** Ranking rounds, Schulze, final report | 20–21 | Open | After finalize (item 4) |

Why this order: disqualifying any file by hand is asked for (issue #82) and builds on what
exists. The questions design for yes/no rounds comes next and can feed disqualification later
(§5). The threshold preview is decided and small. Finalize is needed before ranking rounds. The
rest of the jury changes were deferred in the round editing plan.

Montage's own open defects to avoid: no `dq_date`; "is disqualified" tested sometimes on
`dq_user_id`, sometimes on `dq_reason`; requalify reopens votes; jurors can submit values for
cancelled tasks (`edit_rating` and `apply_ranking` don't check the task's status); a fave is
found by (entry, user) but unfave by round, so unfave in a later round can fail. Montage PRs
#677 (open) adds a missing check on skip; #678 (open) removes a dead check on ranking ballots.

## 5. Montage's flagging, and questions on yes/no rounds (compared 2026-11-19)

**What Montage has:**
- **Flag:** a free-text reason on a round's entry (`Flag` in `montage/rdb.py`:754-777,
  `submit_flag` in `montage/juror_endpoints.py`). Any signed-in user can flag: the lookup
  doesn't check that they are a juror of the round (`get_round_entry`, :2893). Nothing resolves
  or clears a flag. Admins get a list of flagged entries sorted by flag count
  (`get_grouped_flags`, :1337; `/admin/round/<id>/flags`). The current frontend has no flag
  button and no flag list: flags are API only.
- **Disqualification** (manual, or the automatic rules in §1) is what actually leaves a file
  out. A flag doesn't disqualify.
- **Review text:** a juror can write a review on a ranking vote (`votes.flags['review']`, up to
  8192 characters). It shows in the ranking view, the reviews JSON and the final report.
- **Fave:** a juror's own mark, apart from the vote.
- **Skip:** puts a task back for later; the juror's queue moves on.
- **No criteria and no yes/no questions:** a vote is one value (yes/no, a rating or a rank).

**What it means for wlxjury's questions on yes/no rounds (design to come):**
1. **An organizer view grouped by reason:** counts per tag or question, sorted by count, with a
   filter that opens the files with that tag. This is what Montage's flag list does, but with
   fixed reasons, so counts are meaningful.
2. **An optional short note on a vote:** like Montage's review text, but on any vote, and short.
   Comments stay for discussion.
3. **Tags apart from the vote:** a juror can say "yes" and still tag "possible copyright
   issue". A tag never changes the vote or the score.
4. **A catalogue of tags or questions,** like the criteria catalogue (V83): the admin picks
   them per round from the contest's list, with translations.
5. **Later, a tag can lead to disqualification** through `RoundConflicts` (the path CoI uses):
   the admin reviews the tagged files and disqualifies them with a code, keeping the votes so a
   requalify restores them. Never automatic.
