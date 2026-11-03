# Montage parity on rounds

Status: goal after the two-step rounds (`2026-11-02-round-images-two-step.md`) [user]; the
order below is a recommendation. Written 2026-11-02 from a review of Montage `origin/master`
d879cb8 (2026-10-31, read with `git show` in `C:/python/montage`) and wlxjury `ukwikidev/followups`
b641c78d. The detailed Montage feature write-up is `docs/comparison/02-montage.md` in the main
checkout (MT-F ids below refer to it; still valid at d879cb8: `rdb.py`, `admin_endpoints.py` and
`juror_endpoints.py` are unchanged since its f9ddfbd). `01-wlxjury.md` and `03-comparison.md`
there are out of date on the wlxjury side and will be refreshed later.

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
- Next round (`admin_endpoints.py:664-759`): a preview of thresholds (each distinct average →
  entries at or above it), then advance by average ≥ threshold. No top-N, no several source
  rounds; disqualifications and config are not carried over.
- Disqualification (MT-F5.6): upload date outside the campaign, resolution, file type, uploader
  is a juror/coordinator/organizer/maintainer. Each sets `dq_reason`/`dq_user_id` and cancels
  all the entry's votes; preview, manual disqualify and requalify while paused; API only.
- Mid-round (MT-F6.3–F6.5, paused): swap a juror (open tasks move), reassign open tasks
  (avoiding entries a juror voted on, evening queues), change quorum, remove a juror (optionally
  cancelling completed votes, then re-covering).
- Also: skip, faves (no list page), flags and juror stats (API only), round stats map (:391-430),
  results/entries CSV and reviews JSON, a publishable Schulze report, an audit log of admin
  actions.

## 2. Taken into the round-images plan

- Round membership apart from votes, filled and reviewed before tasks (`round_image`).
- Activation rule: distribute rows `in` with no vote row yet (idempotent, incremental).
- A unique key on (round, image) (Montage needed duplicate-repair tools without one).
- Disqualified rows kept with a reason, who and when (Montage lacks the date); votes kept aside
  with their rates so requalifying restores them (Montage reopens them as tasks).
- Stored counts `assigned`, `voted`, approvals, rejections, `rate_sum`: both averaging rules, the
  damped score and "votes yet to come" from one row.
- Next rounds read only `in` rows, so disqualifications carry forward.
- `vote` stays task and vote; no task table (Montage merged its task table into votes in 2017).

## 3. Gaps

S/M/L = size. "RI" = the round-images plan.

| # | Feature | Montage | wlxjury now | Gap | Depends on |
|---|---|---|---|---|---|
| 1 | Round membership, review before tasks | `round_entries`, paused | none (vote rows are membership) | closed by RI | – |
| 2 | Disqualification: reason, who/when, list, preview, manual, requalify | API only | filters only; CoI "leave out now" deletes votes | S after RI (P1b covers CoI) | RI status |
| 3 | Auto-disqualify by upload date | :1452 | no upload date stored | M | import stores upload time |
| 4 | Auto-disqualify uploader in jury/staff | uploader of first revision | CoI by `{{Information}}` author, wider roles (`CoiMembers`) | partial, S–M | store uploader at import |
| 5 | Resolution / file-type rules | min px, MIME allowlist | min Mpx, min MB, image/video | S (MIME list) | RI re-apply |
| 6 | Threshold preview with counts | `preview_results` | "Count new files" only | S | RI counts |
| 7 | Damped score view | :2123-2151 | none | S | RI counts |
| 8 | Round stats (entries, tasks, open %, DQ, uploaders, types) | :391-430 | per-juror counts | S | RI |
| 9 | Lifecycle paused/active/finalized/cancelled | :82-90 | `active` + `distributing`; no finalize/cancel | M | RI `rounds.status` |
| 10 | Directions for jurors, notes, deadline | yes | none | S | – |
| 11 | Edit settings after creation | name/directions/config any time; quorum/jurors paused | name only, CoI toggle | M | RI re-apply covers filters |
| 12 | Add/swap jurors, redistribute avoiding seen files | MT-F6.3 | toggle `round_user.active` only | L | 13, 17, RI `assigned` |
| 13 | Remove juror, keep/discard votes, re-cover | MT-F6.5 | none | M after 12 | `vote_aside` with a reason |
| 14 | Quorum change mid-round | MT-F6.4 | `distribution` fixed | M after 12 | same rebalancer |
| 15 | Audit log | `audit_log_entries` | logger lines only | M | before 2, 9, 12–14 |
| 16 | Vote time | `modified_date` | `created_at` only | S | feeds audit and stats |
| 17 | Skip | `skipped_ids` | n/a (galleries) | optional S | – |
| 18 | Faves | yes | none | S–M | – |
| 19 | Flags to organizers | API only | comments | S | comment type or RI count |
| 20 | Ranking rounds + Schulze | MT-F7.4 | none | L | 9 (finalize) |
| 21 | Final report, publish | MT-F9.4–F9.5 | none | M–L | 20 or a rating-only variant |
| 22 | Round images export (metadata, how added) | MT-F9.2 | none | S | RI `added_how` |
| 23 | Checked import with per-row report | MT-F4.1–F4.7 | unreadable lines, titles not found | M | – |
| 24 | CSV / sheet / gist URL source | MT-F4.3 | none | M | – |
| 25 | CSV formula guard | yes | no | S | – |

wlxjury is ahead on: several previous rounds merged; top-N with a stored binary order;
"selected by"/"rejected by at least N" in votes; region, monument, category and size filters;
background distribution with resume and source-round freezing; adding files to a running round;
deleted and renamed file tracking; 3–20 star scales and video; organizers' all-jurors views;
comments; a unique vote index; author-based CoI over more roles; galleries where jurors can go
back.

## 4. Order after the two-step rounds

1. Audit log and vote times (15, 16): every later change is then traceable.
2. Disqualification beyond CoI (2, 22): codes, list, export; nearly free on `round_image`.
3. Threshold preview, damped score, round stats (6–8): cheap with the stored counts; checks them
   early.
4. Lifecycle, directions, deadline, editable settings (9–11): finalize locks votes and records the
   advance rule; needed before jury changes.
5. Jury changes (12–13), then quorum change (14): the largest practical gap.
6. Upload date and uploader at import (3–4), MIME allowlist (5).
7. Faves and flags (18–19), optional skip (17).
8. Ranking rounds, Schulze, final report (20–21).
9. Import: checked report, URL sources, formula guard (23–25); earlier if organizers ask.

Montage's own open defects to avoid: no `dq_date`; "is disqualified" tested sometimes on
`dq_user_id`, sometimes on `dq_reason`; requalify reopens votes; jurors can submit values for
cancelled tasks (MT-F7.5); unfave keyed differently from fave (MT-F7.7).
