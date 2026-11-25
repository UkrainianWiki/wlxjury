# Contest export, snapshots, and copy + archive (plan, 2026-11-25)

**Status:** plan; nothing built. The maintainer's decisions of 2026-11-25 are recorded in §12; the
questions still open are in §13, one per decision. Round-save change logging ships first as a separate
PR (in progress). **No production repairs and no series assignment until the export and the snapshot
store exist** (D3).

Read from: `origin/master` d037bf4e (V109 `live_series_id`, AccountNotices), `feature/list-repair`
(#174, the file-list repair), `pr/series-cy4-split` (#124, CY-4 draft;
[contest series plan](2026-11-18-contest-series.md) §4.4–4.6), and the Montage investigation (#155,
`docs/montage-api-investigation`, step C4). Round data: a production copy at V107 (no dates; years
estimated from round ids). No DB access.

## 1. Purpose and scope

- **Adjusting contests** (a reused file list, rounds of other years in one contest) changes many rows.
  Today the safety net is typed undo logs plus the nightly dump. The maintainer wants the before-state
  to be a contest people can open, not only a log.
- **Design B, copy + archive (adopted):** root makes a fixed copy with a new contest id; the original
  is frozen as **archived** and is itself the before-view, opened with the normal pages by the same
  people, for as long as it is kept.
- **Building blocks, in order:** a contest data export (§3), a snapshot store fed by it (§4), the
  archived state (§5), a copy engine that imports an export into a new contest (§6–7), a compare
  page with notices (§8). CY-4 is redone on top of them (§9). In-place repair (#174) stays for the
  contests where a copy makes no sense (§10).
- **Not in scope:** importing from other servers or Montage (full C4, later on top of the engine),
  restoring over a live contest, replacing the typed undo logs.

## 2. Order of work

| # | Step | Size |
|---|---|---|
| 1 | Round-save change logging (separate PR, in progress) | — |
| 2 | Release 26.11.16 | — |
| 3 | Regional winners, for the WLM 2026 Ukraine jury final at the beginning of December (separate plan) | — |
| 4 | Round lineage, for its special nomination tracks after the main nomination final in December ([round lineage plan](2026-11-10-round-lineage.md)) | — |
| 5 | Full contest export (§3), incl. the internal variant (to confirm, Q7) | 5–8 d |
| 6 | Snapshot store (§4) | 2–3 d |
| 7 | Archived state (§5) | 3–4 d |
| 8 | Copy engine (§6–7) | 6–9 d |
| 9 | Compare page, notices, "verified", root list (§8) | 3–4 d |
| 10 | Pilot on 449 (§10) | — |
| 11 | Repairs: B for the finished contests, #174 in place for 108, 446, 538, 625 (§10) | — |
| 12 | Series assignment (archived contests left out) | — |
| 13 | CY-4 as copy + archive (§9; #124 rework) | 4–6 d on top of the engine |

Steps 5–9 add up to about 19–28 days (23–34 with CY-4, step 13).

**Reprioritised by the maintainer, 2026-11-25:** what the current WLM 2026 Ukraine contest needs
comes first (steps 3–4). **The target of steps 5–13 is a fixed state before the next WLE,** several
months away; their order is unchanged.

## 3. Contest data export

### 3.1 Who and how

- **Now: root**, through `GET /api/contests/{id}/export` and `wlxjury-diag export`.
- **Later: contest Admins** (including the series-derived Admin) through a button in the UI.
- Every export writes a `contest_change` entry (who, variant, sha256, size).

### 3.2 Variants

| Variant | Jurors | Comment texts | Who |
|---|---|---|---|
| default | stable WLX user id | no | root (Admins later) |
| with names | WLX user id + full name + wiki account; **never email** | yes | root only |
| full internal | every copied table with its internal ids and columns (§6) | yes | root / internal only; feeds the copy engine (to confirm, Q7) |

The default variant already carries WLX user ids, so the internal variant differs by fidelity: all the
tables the copy needs (round_user, round_criterion, round_image with status and counts, votes,
criteria_rate, vote_aside, round_image_event, user_contest with active flags, comments), with ids
kept for remapping.

### 3.3 Format

A ZIP with **JSONL plus a CSV copy of each table**, in #155's C4 layout plus WLX extras:

| Part | Content |
|---|---|
| `manifest.json` | see §3.4 |
| C4 layout | contest, rounds (scale, source rounds and rule, filters), files, members, votes (with disqualifications) |
| `wlx/` | round_images (with `added_how`), jobs, contest_change, comments, category_members |
| Montage-compatible | `files.csv` (`filename` column); per-round results CSVs in Montage's shape (`filename, average, <juror>…`) |

C4 proposed members by Wikimedia username only and no WLX ids; this plan uses the WLX user id (D1),
with the wiki account in the names variant.

### 3.4 Versioning

- `manifest.json`: `format: "wlx-contest-export"`, `formatVersion` 0.x, the app build, the schema
  V-number, `exportedAt`, `variant`.
- 0.x has **no compatibility promise**. Bump only on breaking changes; added fields don't bump;
  readers ignore unknown fields. 1.0 comes when something depends on the format.
- Field docs are generated from the exporter and checked by a test, like `schema.graphql`.

### 3.5 Consistency and cost

- One read-only transaction `WITH CONSISTENT SNAPSHOT` (InnoDB, no locks).
- A `contest_export` job with one long read step, an exception to the one-short-transaction-per-step
  rule. Large contests take 10–60 s.
- Sizes (estimated): the largest contest has 38k files and up to 0.5–1M vote rows, a ZIP of
  ~15–30 MB; most of ~630 contests are under 1 MB. The whole DB dump is ~85–90 MB gzipped.

## 4. Snapshot store

### 4.1 Storage

- Files under the app's data dir (`exports/<contest>/<id>.zip`, directory 0700, files 0600, owner the
  app user), not DB blobs (they would grow every nightly and release dump).
- A `contest_export` row per file: id, contest (null for a series-state snapshot), kind, purpose
  (manual, before, after), `change_id`, sha256, bytes, created_by, created_at, expires_at.

### 4.2 Kinds

| Kind | Content | Used by | How taken |
|---|---|---|---|
| series state | all series, every contest's series link and identity, series contest_change | series assignment | inside the batch's transaction (kilobytes) |
| contest file list | the contest's list | repairs (#174) | job |
| full contest export | §3, full internal | copy engine, CY-4 | job |
| round | a round's settings plus its files | round save, re-apply | job |

### 4.3 When taken

- A snapshot is **evidence, not the undo mechanism**: the typed undo logs (`list_repair_file`, the
  series batch, the split items) stay, being the exact delta, transactional and tested.
- **Before:** a gate before Confirm. The plan page starts the export job; Confirm is enabled when it
  is done. Whether the gate may be skipped is Q11.
- **After:** queued on commit.
- The series-state snapshot is the exception: written in the batch's own transaction.

### 4.4 Retention

- 90 days, a total cap (new snapshots refused past it, a snapshot an open change still needs never
  deleted), and a daily prune job; each deletion logged in `contest_change`. 90 days for before/after is
  decided (D4); manual snapshots' lifetime and the cap value: Q14.
- Off-site copies (the server's backup timer beside the nightly dump, an ops change) and encryption:
  Q12, Q13.

### 4.5 Visibility

- **Root only, for now:** before/after/diff links on change-log entries, a per-contest snapshots page,
  and a root overview of all snapshots.
- **Diff view, kept:** per table, keyed (files by page id; round_images by round + page; votes by
  round + page + juror; members by user id; rounds by id): counts added, removed and changed, and the
  first 50 rows of each. Its main use is showing that nothing else changed.
- **A browsable snapshot viewer is dropped** unless support needs it.
- **Restore in the app** (a contest's file list from a snapshot): Q16. Votes, round_image, accounts
  and moved ids are never restored over a live contest.

### 4.6 What snapshots don't replace

The nightly dump (global data, accounts, disasters) and release dumps (schema rollback). Global
operations (account join and retire, author normalisation) keep relying on the nightly dump: a contest
export doesn't capture accounts.

## 5. The archived state

- New columns on `contest_jury`: `archived_at`, `archived_by`, `replaced_by_contest_id`.
- **Separate from retired:** retired = no access, hidden; archived = **visible and read-only**. Not
  both at once.
- **Read-only:** no imports, rounds (create, edit, start, re-apply, distribute), votes,
  disqualifications, juror or member changes, share links, invitations or jobs. One guard
  (`contest.writable`, beside the retired checks) plus a write-path test per controller. Comments: Q1.
  Global account operations: Q3.
- **Banners both ways:** the archive: "Archived on <date> by <root>, replaced by <copy> (fixed copy).
  Read-only."; the copy: "Fixed copy of <original> (archived), made on <date>: compare". Both link to
  the compare page.
- **Access:** memberships keep working; the own-votes and conflict-of-interest rules apply as before.
  Jurors' access: Q2.
- **Left out of:** series proposals, the reuse scan, import sources, jurors' contest menus, the
  session's default contest, and the default contests list (a "Show archived" toggle).
- **Identity:** `live_series_id` skips archived contests
  (`IF(retired_at IS NULL AND archived_at IS NULL, series_id, NULL)`); the copy takes the
  (series, year) slot; the archive keeps `series_id` for display. Series admins reach archived contests
  of their series read-only.
- **Un-archive:** root only, and only by **"Undo copy"** (§7).
- How long archives are kept: Q6.

## 6. The copy

| Copied (ids remapped) | Not copied |
|---|---|
| contest settings (name, type, country, year, campaign, series); the images source cleared unless it names the contest's own Commons category | jobs, `round_image_dirty`, caches, thumbnails (shared by page id) |
| `user_contest` (same accounts, roles, active flags) | share links, invitations, API tokens, `submit_token` (new ones) |
| the copy's own list `#contest <copy>` = the files #174's plan keeps | foreign files' `category_members`; the old shared row |
| rounds with all settings; `previous` and `exclude_rounds` remapped; status as in the original | the original's split items and `list_repair*` rows |
| `round_user`, `round_criterion`, `round_image` (status, counts), votes, `criteria_rate`, `vote_aside`, `round_image_event`, comments (with by-file threads) | account rows, `images` rows (global, by page id) |
| `contest_change` history (copied, marked) plus a `copied_from` entry; the original gets `archived` and `copied_to` | |

- **Round 1222** (485, WLE 2021 Wales: a round over the whole shared list, 835 files outside the clean
  list) is **copied as is** (round_image and votes, so its results don't change) and marked **Mixed**
  (the reuse report's Mixed, `ReuseReport`). New rounds and "Distribute new files" read only the clean list. Confirmation: Q4.
- **Links:** old URLs stay on the archive. A map table `contest_copy_round(copy_id, old_round_id,
  new_round_id)` serves the juror notice, compare and Undo. Notice or redirect for juror links: Q5.

## 7. The copy engine

A `contest_copy` job; root confirms a plan with a hash.

1. **Archive** the original (writes stop; a contest with an open round is refused).
2. **Full export** of it (the snapshot store's before-snapshot, consistent read). The archive equals
   it because it froze first.
3. **Filtered import** of that ZIP into a new contest through a transform: a file filter (#174's
   `FileListRepair.plan`) or a round subset (CY-4). Chunked, one transaction per chunk,
   `INSERT IGNORE` by natural keys so Retry is safe. The copy stays hidden until step 4 passes.
4. **Count check** per table, copy against the ZIP; a mismatch fails the job.
5. **Notices** (§8).

- **Minimal engine:** same server only, one contest into one new contest, no user mapping by wiki name.
  Full C4 (other servers, Montage, user mapping) later on top, +4–6 d.
- **Fidelity test:** export → import → export equal modulo ids.
- **Undo copy:** deletes the copy's rows and un-archives the original; refused once the copy has its
  own votes, rounds, imports or members.

## 8. Verification

- **Compare page** `/contest/<copy>/compare`, for Admins and Organizers of either contest and root:
  per round side by side (files, votes, jurors, voted %, top-10 identical or not); the file list
  (archive count, copy count, files only in the archive by country, with gallery links on both sides
  through a `copy=` filter like #174's `repair=`); Mixed rounds named. Exact equality everywhere
  except the filtered files; any other difference is shown red (an engine bug).
- **Notices:** for Admins and Organizers, a bar at sign-in leading to the compare page, from the
  `copied_to` / `copied_from` entries in `contest_change`. AccountNotices reads `account_change` only,
  so it needs a notice source for these `contest_change` entries, or a new notice mechanism is added.
  Root's unticked "also mail them" box. Root also contacts Admins personally where mail is unreliable.
- **Verified:** an Admin's "Verified" button writes a `copy_verified` entry. A root list
  (`/admin/contest-copies`) shows copies awaiting verification, "Verified by <admin> on <date>", and
  Undo while it is allowed.

## 9. CY-4 as copy + archive

- Each year's cluster of a reused contest becomes a new contest copied from it (transform: its rounds
  and their matching files), the reused contest's own year too (466 → a fixed "WLE 2020 Nepal"); then
  the original is archived: n+1 copies (n = one per year cluster, + 1 for the reused contest's own
  year), the original untouched.
- **Replaces** #124's in-place moves, row locks on live rounds, pause and resume, the split-item
  inverse and the §4.6 redirects. Undo = delete the copies and un-archive.
- **Costs:** votes duplicated (466: 48 rounds); new round ids for juror links.
- **Reused from #124:** the reuse report offer, plan and hash, the confirmation (clusters, targets, R4
  admin ticks), the people step's rules (O14, protected accounts, the skipped list), the notices.
- **The repair is folded in:** each per-year copy gets its own clean list, so no separate repair copy
  first.
- The details (Q8) and whether CY-4 drops `wlxjury-release snapshot` and `dump_ref` (Q15) are open.

## 10. Repairs in place and the 14 contests

- **#174 (open draft PR) stays, root only,** for the contests where a copy makes no sense: **108,
  446, 538** (empty lists, decided; nothing to compare) and **625** (an open round; timing: Q9). Its
  plan is B's file filter.
- **Pilot: 449** (small, finished); its Admins verify on the compare page before the rest (Q10).

**The 14 contests** (estimated from a V107 copy; to be confirmed on the 2026-11-25 copy). Round ids
≥ ~1800 are 2026, 1550–1800 are 2025.

| Contest | Rounds (last; active flag) | State | Path (estimate) |
|---|---|---|---|
| 108 test 2017 | 10 (249) | finished, test | in place via #174 (empty list) |
| 446 WLE 2020 Indonesia / 538 WLE 2022 IE+NI | 1 empty / 0 | finished | in place via #174 (empty lists) |
| 160 WLO 2016 Brazil | 3 (366; flag stale since 2016) | finished | B (close 366 first) |
| 449 WLE 2020 Armenia | 3 (745) | finished | B: **pilot** |
| 281 WLE 2018 Philippines | 29 (729) | finished; spans to ~2020 | B, or CY-4 as B if the CY-0 report moves clusters |
| 454 WLE 2020 Brazil | 47 (991) | finished; 2021 cluster | CY-4 as B |
| 485 WLE 2021 Wales | 16 (1235; listed active) | finished in fact?; Mixed 1222 | CY-4 as B after 1235 is closed |
| 594 WLE 2025 Ghana | 7 (1653; 1647 flag) | 2025, probably done | B after root confirms and closes 1647 |
| 599 WLM 2025 Ghana | 6 (1713 active) | maybe still judging | wait; B when closed |
| 590 WLE 2025 Slovakia | 5 (1675) | 2025 done; list holds 329 2026 files | B (ask the contest's organizers whether 2026 is meant) |
| 572 WLE 2024 Ireland | 88 (1906, 2026) | recent; Folklore 2025, Thatch 2026 clusters | CY-4 as B once 2026 judging is over |
| 466 WLE 2020 Nepal | 48 (1900 active, WLE 2026 Bhutan) | **in use** | wait for 1900; then CY-4 as B |
| 625 WLE 2026 Malaysia | 8 (1936 active) | **in use** | in place via #174 (now or later: Q9) |

Order after the pilot: 160, 594, 590, 281 (B) and 108, 446, 538, 625 (#174); series assignment; then
CY-4 as B for 454 and 485, later 572 and 466 after their rounds close, 599 when closed.

## 11. Migrations

Numbers are taken as the next free number at merge (D7); the drafts' V113–V115 are not kept (master's
V113 is the legacy-tables InnoDB conversion). Expected: the `contest_export` table; the archived
columns with the redefined `live_series_id`; `contest_copy_round`; the `contest_change` kind CHECK
extended (export, archived, copied_to, copied_from, copy_verified, prune), plus `ContestChangeJdbc.Kinds`.
That migration goes after #174's V114 and after the round-change-log migration, which extend the same
CHECK.

## 12. Decisions (2026-11-25)

- **D1. Contest data export.** Root first (`GET /api/contests/{id}/export`, `wlxjury-diag export`);
  contest Admins, series-derived included, later through a UI button. Jurors as a stable WLX user id by
  default; a "with names" variant (full name + wiki account, never email) for root only; comment texts
  only in that variant. A ZIP with JSONL plus CSV copies, C4 layout plus `wlx/` extras, Montage
  `files.csv` and per-round results CSVs. Manifest with `format`, `formatVersion` 0.x (rules in §3.4),
  build, schema V-number, `exportedAt`, `variant`; generated field docs checked by a test. Each export
  writes a `contest_change` entry. A full internal export feeds the copy engine (to confirm: Q7).
- **D2. Round-save change logging** ships first, as a separate PR (in progress).
- **D3. No production repairs or series assignment** until export and snapshots exist.
- **D4. Snapshot store:** data-dir files with `contest_export` rows, 90-day retention, a cap and a
  daily prune; exported by a job; the before-snapshot a gate before Confirm, the after-snapshot queued
  on commit; the four kinds of §4.2; root-only visibility (change-log links, per-contest page, root
  overview); the diff view kept, the browsable viewer dropped unless support needs it.
- **D5. Design B (copy + archive) adopted** for adjusting contests: the archived state (§5), the copy
  and its remapping (§6), round 1222 as is and Mixed (Q4), the copy engine (§7), the compare page,
  AccountNotices and "verified" (§8), CY-4 redone as copy + archive (§9). In-place repair (#174) stays
  for 108, 446, 538 and 625; pilot 449.
- **D6. Order of work** as in §2. Reprioritised the same day: after the round change log and
  26.11.16 come regional winners (WLM 2026 Ukraine jury final, early December) and round lineage
  (its special nomination tracks after the main final), then this plan's sequence, unchanged; the
  target is a fixed state before the next WLE.
- **D7. Migration numbers** taken as the next free number at merge.

## 13. Open questions (not yet decided)

Each is a separate decision, to be asked one at a time.

- **Q1. Comments in archives:** closed (recommended in the design; existing ones shown) or still
  allowed?
- **Q2. Jurors' access to archives:** their own votes read-only, or Admins and Organizers only?
- **Q3. Global account operations** (account join and retire, author normalisation): still applied to
  archived contests (recommended, to keep them consistent with accounts), or frozen for them too?
- **Q4. Round 1222 as is:** copied as is and marked Mixed (the plan), or its foreign files dropped
  from the copy?
- **Q5. Juror links to old round ids:** a notice with the copy's link (recommended; a redirect would
  hide the before-view), or a redirect?
- **Q6. How long archives are kept:** forever, or deletable by root after N months with the export
  kept?
- **Q7. The full internal export:** confirm a root/internal-only variant with internal ids beside the
  default.
- **Q8. The CY-4 details:** copy per year + archive (n+1 copies, votes duplicated) as in §9, and what of
  #124 is kept.
- **Q9. 625 now or later:** in place via #174 now, or after round 1936 closes?
- **Q10. The 449 pilot:** on 449, with its Admins asked to verify on the compare page before the rest?
- **Q11. Before-snapshot required or only offered:** may root confirm without one (logged)?
- **Q12. Off-site copies of snapshots:** copied by the server's backup timer beside the nightly dump,
  or kept only on the server?
- **Q13. Encryption:** is the off-site copy of the nightly dump encrypted, and should snapshot copies
  be? (No encryption at rest on the server itself: same data, same disk as the DB.)
- **Q14. Retention and cap values:** manual snapshots' lifetime, and the total cap (e.g. 5 GB).
  (90 days for before/after is decided, D4.)
- **Q15. CY-4 and `wlxjury-release snapshot` / `dump_ref`:** dropped in favour of exports?
- **Q16. Restore in the app:** may root restore a contest's file list from a snapshot in the app, or
  only by hand from the file?
