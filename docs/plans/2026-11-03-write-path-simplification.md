# Write paths: simplification and deadlock robustness

Status: investigation (2026-11-03) and the robustness batch (§11, `ukwikidev/write-path-robustness`). Investigation branch `ukwikidev/write-path-investigation` from
`ukwikidev/followups` `7a79a4bd` (P0, C1, C2/V83, P1/V84, Flyway 13.12.0). Not merged
production code: a decision document, a stress harness and spikes behind flags. The user
decides afterwards.

Marks: **[rec]** recommendation, not decided.

## Summary

- **Matrix (§2):** 21 write paths; 16 run in production (W1–W16); `removeImage`,
  `removeUnrated` and `setRound` are test-only today (P3's D16a makes `removeImage` real). The
  documented table order doesn't decide the order of *rows* within a table: votes are locked
  round-major by some writers and file-major by others (Commons imports, monument ids, the list
  phase, restore), and the file-major ones never lock the rounds whose votes they lock. Eleven
  breaks are listed, among them an unsorted per-file loop in the import, images before rounds
  in `settle`, a juror vote's subquery gap lock reaching another round, one vote row
  deadlocking across two indexes, and `job` before `round` in job steps.
- **Baseline (§3, production paths, 16 threads, 90 s):** 49 and 53 deadlocks in two runs; 13
  and 11 failures a caller sees (juror votes: 2 each; run 2's also 3 criteria changes, 1 CoI
  removal, 7 file checks). **Two data defects:** a restored file's
  votes left aside by a concurrent round activation (invariant 4, 2–11 a run) and
  `vote.monument_id` copies left stale by a distribution chunk (up to 36 rows). With the
  test-only writers too: 158–165 deadlocks, and 86–93 distributions failing the count check
  when files leave a round mid-distribution.
- **Spikes (§4):** (b) READ COMMITTED: −60 % (19); (a) round-first: −85 % (7), no structural ×
  structural pair left, invariant 4 closed, admin p50 +60–170 ms under stress, juror p95 lower;
  (h) targeted fixes alone: within noise; (d) retry: 0 visible failures; **RC + (a) + (h): 0–4
  deadlocks per 90 s under stress, all absorbed by retry (d)**; its clean monument-copy check
  was luck (run 1's round-first variants left 27 and 36 stale copies: h6 not spiked), and it
  did 20–30 % fewer admin operations. (c) without the vote's monument copy: the
  all-jurors rated page −497 ms, region count −55 ms; a juror's region page +6 ms and region
  menu +107 ms (via `images`). (f) a status column: +438 ms on the grouped page with indexes
  unchanged, +25 ms (+22 %) with rebuilt indexes and a predicate in every vote read. (e) a
  per-round job lane and (g) Montage's status gating as designs; Montage compared in §5.
- **P2a (§6):** +1.3 ms and a shared row lock per vote; the count columns read 105–138 ms
  faster than the grouped page; a full background recount of a 38k-file round 1.9 s. [rec] a
  dirty-round mark plus a background recompute (ii) instead of in-transaction deltas.
- **Recommended (§7):** RC (after rewriting the plain vote's criteria check) + the round-first
  rule with a guard + targeted fixes + one retry wrapper; then drop `vote.monument_id`; (ii)
  for counts; gating for P1b/P3's admin actions; not now: (e), (f). Ten decisions in §9.

## 1. Why

C1, C2 and P1 reviews each found new deadlocks (a distribution chunk vs a criteria change;
`setRound` vs a monument update; the list step vs a criteria change; the repair job's monument
step; next-key locks in criteria votes). All rolled back, none corrupted data, but correctness
rests on ten-odd write paths following one lock order across six tables
(`round → images → vote → criteria_rate → vote_aside → round_image`, round-images plan §5).
P2a (counts in the vote transaction) and P3 (two-step rounds) add paths. The question: can the
write model be made simpler, so that a new path cannot break it, before P1b/P2a/P3 are built?

## 2. Write-path matrix

Every path that writes `vote`, `vote_aside`, `criteria_rate`, `round_image`,
`round_criterion` or a monument id copy (`images.monument_id` → `vote.monument_id`,
`vote_aside.monument_id`, `round_image.monument_id`), at `7a79a4bd`. All transactions run at
MariaDB's default REPEATABLE READ (RR) unless marked RC. "Retry": who reruns the transaction
after a deadlock (1213). Job steps run in `JobScheduler.runStep`: one transaction per step,
the job row locked first (`JobJdbc.lock`), retried 3 times on a deadlock *or* a lock wait
timeout (`isTransient`), 100/200/300 ms apart.

Lock notation: `X`/`S` row locks; *range* = next-key locks over an index range (RR: the rows
and the gaps before them; RC: the rows only); *FK S* = the shared lock a foreign key check
takes on the parent row; *subq S* = the shared next-key locks InnoDB takes for a subquery of
an `UPDATE`/`INSERT … SELECT`/`REPLACE … SELECT` under RR (a consistent read under RC).

| # | Path | Entry point | Transaction, isolation | Locks, in order | Retry | Under (a) round-first / (e) per-round queue |
|---|---|---|---|---|---|---|
| W1 | Juror vote, binary or rated (`VoteJdbc.rate`) | request: `GalleryController.selectWS`, `LargeViewController.rateByPageId` | one autocommit `UPDATE`, RR | `vote` X (unique `(page_id, jury_id, round_id)`, one row); *subq S* on `round_user` (round, juror) and `round_criterion` (the round's range; a round without criteria locks the gap up to the **next round's first criterion**) | none: a 1213 is a 500 for the juror | (a) unchanged, row-level. (e) unchanged: still meets the queued step on its vote rows |
| W2 | Juror criterion rate (`CriteriaRate.updateRate`) | request: `rateByPageId?criteria=` | own tx, **RC** (`ReadCommitted.localTx`) | `vote` X (`FOR UPDATE`, `round_user` read with it) → `round_criterion` S (records) → `criteria_rate` X (upsert; FK S `criterion`) → `vote` X (its rate) | once | unchanged |
| W3 | Criteria change (`RoundCriterion.update`) | request: the round edit, own tx before the rest of the edit | own tx, RR | `round` X → the round's `vote` (range X on `round_id`) → its `vote_aside` (range X) → `vote`, `criteria_rate` S (voted?) → `round_criterion` X (FK S `round`, `criterion`) → `criteria_rate` X (rename) | once | (a) already round-first. (e) queued; a refusal ("voted") becomes asynchronous unless checked first |
| W4 | Round edit: name, "leave out CoI" flag (`Round.updateRound`, `setExcludeCoi`) | request | autocommit each, apart from W3 (C2 F7 limitation) | `round` X | none | (e) could share W3's queued action |
| W5 | Round activation / stop (`Round.setActiveGuarded`, `RoundService.setCurrentRound`) | request | plain read outside, then own tx RR | `round` X (`isOpen` `FOR UPDATE`) → `UPDATE round` → opening: plain read `images ⋈ vote` (deleted files) → `vote` X (pairs) → `vote_aside` X (`REPLACE … SELECT`, subq S `vote`) → `criteria_rate` X → `vote` delete → `round_image` (sync: `vote` S, `vote_aside` S, `round_image` X; insert … select with `round`, `images` S on drift) | none | (a) already round-first. (e) queued ("open") |
| W6 | Round creation (`RoundService.submit`) | request | own tx RR | `round` insert, `round_criterion` insert (FK S `round`, `criterion`), `round_user` inserts → `round` X (`Round.lock`: previous rounds and the new one) → freeze `UPDATE round` → `job` insert | none | (e) the round's first queued action |
| W7 | "Distribute new files", retry (`distributeNewFiles` → `enqueueOrFail`) | request | own tx RR | `round` X (previous + round) → `job` insert / update | none | (e) is the enqueue |
| W8 | Distribution, Distribute phase (fill, new files) | job step (`RoundDistributionHandler.step`) | one tx per step, RR | `job` X → plain (snapshot) reads: candidates, `notInRound` (`round_image`, `vote`, `vote_aside`) → per 2,000 rows: `round` S (`RoundImageJdbc.lockRounds`) → `vote` inserts (FK S `images` per row; insert intention on the 5 indexes; the vote's `monument_id` **from the snapshot read**) → `round_image` `INSERT … SELECT … ON DUPLICATE KEY` (`round`, `images` S, `round_image` X) | step ×3 | (a) `round` X instead of S. (e) the queued action itself |
| W9 | Distribution, List phase | job step | one tx, RR | `job` X → `round` S → `images` `INSERT IGNORE` → per changed stored file, **in list order**: `images` X → `vote` X of **every round of the file** (`page_id` index) → `vote_aside` X → `round_image` X (monument copies) → as W8 | step ×3 | (a) the rounds of the changed files X first (`RoundWrites.lockForFiles`). (e) the files' other rounds are not this queue's |
| W10 | Distribution, Verify; job end, failure, cancel, retry | job step | one tx, RR | `job` X → count (plain read of `round_image`) → `round` X (previous + round) → `UPDATE round` → settle (`isOpen` X, then W5's opening part) | step ×3 | (e) ends the action |
| W11 | CoI "leave out now" (`RoundConflicts.removeFromRound`) | request | own tx, RR | `round` X → `job` plain read → `UPDATE round` → plain reads (`round_image`, `images`, members) → per table `vote` then `vote_aside`: the CoI pairs X (`round_id` range) → `criteria_rate` X (join) → deletes → `round_image` sync | none | (e) queued; P1b's disqualify / requalify join it |
| W12 | File deleted on Commons (`CommonsFileCheck.write` → `DeletedFiles.markDeleted`) | background (the thumbnail cache's file check), not a job | own tx, RR | plain read `round_image` (open rounds of the files) → `round` S (open ones, id order) → `images` X (`lockFiles`: deleted + changed, page order) → `UPDATE images` → W5's move aside for the open rounds → `images` file info | none | (a) rounds X. (e) not round-scoped (a file is in many rounds): stays outside, or enqueues a settle per round |
| W13 | …then settle (`DeletedFiles.settle`) | same, a second tx | own tx, RR | **`images` S (`lockDeleted`) → `round` S (`lockOpenRoundsOf`)** → move aside | none (a failure is logged and dropped) | (a) rounds first |
| W14 | Files restored, changed files (`ImageImportHandler.writeImages`) | job step (`commons_image_import`) | one tx per page, RR | [only if a file is to be restored] plain read `vote_aside` → `round` S (`lockRoundsOfArchived`) → `images` X (restored + changed, page order) → `vote` X (`page_id` range) → `vote_aside` X → `vote`, `criteria_rate` S → deletes, `INSERT vote … SELECT` (FK S `images`, subq S `round`, `vote`) → `round_image` sync; then **per changed file, in Commons listing order**: `images` X → `vote` X of every round of the file → `vote_aside` X → `round_image` X | step ×3 | (a) the rounds of all stored files X first. (e) as W12 |
| W15 | Monument ids (`writeMonumentIds`, import mode `monumentIds`) | job step | one tx per page, RR | per file, page order: `images` X → `vote` X (every round) → `vote_aside` X → `round_image` X | step ×3 | (a) rounds first. (c) only `images` and `round_image` |
| W16 | Repair job (`round_image_check`) | job step; admins per round, root all rounds | one tx per step, RR | sync: `job` X → `round` S, `job` S (distribution active?) → plain window reads → `vote` S, `vote_aside` S (window) → `round_image` X (+ insert … select). Monument step: plain read → `images` S (page order) → `round_image` X by key. Orphans (all rounds): `criteria_rate` plain → `vote` / `vote_aside` X by id → `criteria_rate` X | step ×3 | (a) `round` X. (e) queued per round; the orphan step stays global |
| W17 | `VoteJdbc.removeImage` | **test-only today** (no route); P3 (D16a) makes it an admin action (`dq`) | own tx, RR | `vote` X (pairs) → `criteria_rate` X → delete → `vote_aside` likewise → `round_image` sync. **No round lock** | none | (a) round X first. (e) queued |
| W18 | `VoteJdbc.removeUnrated` | **test-only** (the in-memory `DistributeImages` path) | own tx, RR | `vote`, `vote_aside` X (the round's unrated: a range) → `criteria_rate` S → chunks of 5,000 as W17. **No round lock** | none | (a) round X first |
| W19 | `VoteJdbc.setRound` (a file to another round) | **test-only** (no route) | own tx RR, then a settle tx | both `round` S → `images` X → source `vote`, `vote_aside` X → `criteria_rate` X → `UPDATE … SET round_id` (insert intention in the target's range) → `round_image` sync (both rounds) | none | (a) both rounds X |
| W20 | `VoteJdbc.create`, `batchInsert` outside a job; `DistributeImages.distributeImages` | test helpers, the Gatling fixture, scripts | the caller's tx, or own | as W8's insert | none | — |
| W21 | Round / image deletion | by hand only / never (V63) | — | FK cascades to `round_image`, `round_criterion`, `vote` (images) | — | — |

Not writers of these tables but in the picture: `round_user` (a juror stopped: W1 and W2 read
it), `job` (each step locks its row first; W11 and W16 read the distribution job's dedupe key).

Under (f) (§4) W5's opening, W12–W14 and P1b's disqualify become `UPDATE vote SET status`
on the same rows (no copy to `vote_aside`); under (g) W3's rename, W11, W17 and P1b/P3's
actions run only on a round that isn't open; under (c) W9, W14 and W15 stop writing `vote`
and `vote_aside` at all (`images` and `round_image` only).

### Where the documented order is broken (or does not decide)

1. **The order fixes tables, not rows within a table.** `vote` is one tier, but its rows are
   locked through different indexes: round-major (`round_id` ranges: W3, W11, W18, a chunk's
   inserts) and file-major (`page_id`: W9, W14, W15, W19's source, restore). Two transactions
   that need the same vote rows of one round through different indexes take them in different
   orders, with nothing above them (no round lock on the file-major side) to serialize them.
   This is the class every review found a new member of, and no rule over tables removes it.
2. **File-major writers never lock the rounds whose votes they lock** (W9, W14, W15; W12/W13
   lock only the *open* rounds; W19 locks two). They touch every round of a file, so the
   round-first rule can only hold if they look the rounds up first (`RoundWrites.lockForFiles`),
   from an unlocked read that a concurrent move or distribution can outdate.
3. **W14 locks its changed files in page order only when something is restored.**
   `DeletedFiles.restore` returns before `lockFiles(deleted ++ alsoLock)` when nothing was
   deleted, so on the common page `writeImages` updates the changed files one by one in the
   Commons listing's order (category sort key, not page id): `images` X of file B, then of
   file A < B, against any page-ordered locker (W12's `lockFiles`, W16's monument step, a
   chunk's FK checks). W9's list order is the same.
4. **W13 (`settle`) locks images before rounds** (`lockDeleted`, then `lockOpenRoundsOf`), the
   reverse of W12 and of every round-first path; with S locks only, it deadlocks with a path
   that holds the round and wants the image X, or holds the image X and waits on the round (W5
   opening, W11).
5. **W1's subquery locks reach other rounds.** Under RR the `NOT EXISTS (SELECT … FROM
   round_criterion WHERE round_id = R)` of a plain vote takes a next-key lock; in a round with
   no criteria that is the gap before the next round's first criterion. A criteria change of
   another round (W3) inserting there waits for it, and W3 holds that round's votes.
   Cross-round coupling through a gap lock; at RC the subquery is a consistent read.
6. **W8 builds a vote's `monument_id` from its snapshot**, read before any lock; a monument
   update (W14/W15) committing between the read and the insert updates the existing copies
   but not the rows the chunk then inserts. The harness found such rows (invariant: copy =
   `images.monument_id`): the copy is wrong, not only the locks. `round_image.monument_id` is
   right (its `INSERT … SELECT` reads `images` with the FK lock).
7. **The count check assumes no other structural writer on the round.** W8/W10 count the
   round's files before and after; a concurrent W11, W17 or W19 on the same round changes the
   count, and the job fails `job.error.count.mismatch` (a visible failure, not a deadlock).
   Today W17–W19 are test-only, so only W11 (refused while a distribution is active, which
   checks under the round lock) and W12–W14 (move aside: `countAll` counts aside rows, so no
   change) apply. P3/P1b's admin actions would hit it.
8. **Juror votes are not retried** (W1: autocommit, a 1213 is a 500; W2 once). Every other
   request path (W3–W7, W11) retries at most once or not at all; W12/W13 not at all. Only job
   steps retry (3×, and they also retry lock wait timeouts).
9. **Several request paths are split over transactions** (W3 then W4; W5's read outside its
   transaction; W12 then W13; W19 then its settle): each part is consistent, the whole is not
   atomic, and a retry of one part is not a retry of the action.
10. **One row, two indexes.** A juror's `UPDATE vote … WHERE page_id = ? AND jury_id = ? AND
    round_id = ?` locks the unique secondary entry, then the clustered row; a delete or move
    of the same vote that found it through another index (W11's `round_id` range, W12's
    `page_id` list) holds the clustered row and then needs the unique entry to delete it. A
    two-statement cycle on a single vote, seen in every variant (`coi_remove × vote_*`,
    `file_check × vote_rated`): no table order or round lock can prevent it, since the juror
    takes neither. Fix: the juror's statement by primary key (`WHERE id = ?`, the id read
    first), or retry it.
11. **`job` is outside the documented order.** A job step locks its `job` row first, then the
    round (W8, W16); enqueueing or retrying (W6, W7) locks the round, then writes the `job`
    row (the active dedupe key's unique check meets the running step's row). Seen as
    `distribute_list × distribute_new` under RC: one request enqueueing while a step of the
    same round runs.

## 3. Stress harness

`test/stress/WritePathStress.scala` (`db.stress.WritePathStress`, a `main`, not a spec: never
in `sbt test`):

```bash
python scripts/agent-check.py -- java -Xmx1536m -Dfile.encoding=UTF-8 -jar 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar' \
  'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8", "-Dstress.seconds=90", "-Dstress.paths=(?!remove_|set_round).*", "-Dstress.variants=baseline,rc,fixes,rc+fixes")' \
  'Test/runMain db.stress.WritePathStress'
```

- **Database:** the shared test MariaDB (`SharedTestDb`, `forTests`: mariadb:10.6.22, tmpfs,
  128M buffer pool, `innodb_flush_log_at_trx_commit=0`), migrated to V84; seeded per variant
  (truncate, then): one contest, 2,000 files (5 regions, every 40th by juror 1: a conflict of
  interest), 10 jurors, an admin; rounds created through `RoundService.createNewRound` (the real
  distribution job, inline): binary, rated (5 stars), criteria (criteria 1, 2, 3), a fourth
  binary round whose activation is toggled, each 3 jurors a file (6,000 vote rows each, 24,000
  in all), and an empty inactive target round. `innodb_print_all_deadlocks = ON`,
  `innodb_lock_wait_timeout = 50` (production's default).
- **Paths (16, 19 threads):** each runs in a loop on its own thread(s), with a random think time
  (votes 0–10 ms, admin actions 0–300 ms … 0–3.6 s), as its entry point calls the app's code:
  juror votes `VoteJdbc.rate` (binary, rated; 2 threads each) and `CriteriaRate.updateRate`
  (2 threads); the criteria change `RoundCriterion.update` (renames criterion 3 ↔ 4: the round
  has votes); the round edit; activation `Round.setActiveGuarded`; CoI removal
  `RoundConflicts.removeFromRound` (then the flag cleared, so files come back); "Distribute new
  files" and a pasted-list job through `RoundService` and the inline `JobScheduler` (chunks of
  600 rows; the list's Commons is a fake whose files differ from the stored ones, so the list
  phase's `updateStored` runs); the repair job `round_image_check` of a random round; the
  Commons image import's page write (`writeImages`' statements in its order: restore with the
  changed files, then `updateIn` per changed file in listing order), its monument-id mode
  (`updateMonumentIdIn` in page order), and the file check (`markDeleted` + file info, then
  `settle`); and the test-only `removeImage`, `setRound`, `removeUnrated` (run 1 only).
- **Attribution:** each path connects as a MariaDB user of its own (`s_<path>`, a routing
  connection pool registered as ScalikeJDBC's default; jobs run inline on the path's thread),
  so every deadlock MariaDB prints names its two paths, the statements and the locks waited
  for (`DeadlockLog`).
- **Counted:** server deadlocks (`Innodb_deadlocks`), row lock waits and their time; per path
  operations, throughput, p50/p95/max latency, deadlocks caught (after the app's own retries:
  `updateRate` and `RoundCriterion.update` once, the scheduler's steps 3×; the import's page
  write is a job step, so the harness retries it 3× as the scheduler would), lock wait
  timeouts, failures the caller sees (exceptions out of the app's code, failed jobs with their
  error), job steps the scheduler retried.
- **Invariants at the end:** `RoundImageInvariants.all()` (1–4, 6–8), every criteria vote's
  `vote.rate` = the sum of its round's criteria once all are rated else 0 (U5), no rate of a
  criterion its round hasn't, `vote.monument_id` and `vote_aside.monument_id` =
  `images.monument_id`, no round left frozen or distributing, job outcomes.
- Output: `target/stress/write-paths-<stamp>/` (`summary.txt`, `results.json`,
  `deadlocks-<variant>.txt` with each deadlock's two transactions, `server-log-<variant>.txt`).
- Variants in one JVM (`stress.variants`, `+` to combine): `baseline`, `rc` (the server's
  default isolation READ COMMITTED for new connections: every transaction, as a global
  setting would), `roundfirst` (spike (a)), `fixes` (spike (h)), `retry` (spike (d): every
  operation retried 3× on a deadlock). Repeats (`baseline#2`) give the run-to-run variation.

### How the harness differs from production

Read the numbers below as relative, between variants of one harness, not as production rates:
- **Chunks of 600 vote rows** (`chunkRows`), against production's 15,000: 25× more, shorter
  distribution steps, so more interleaving with everything else.
- **Jobs run inline** on the calling thread, several at once (distribution, list, repair, one
  each), with no 2-worker limit and no per-contest turns.
- **No round creation (W6) and no large fill:** the rounds are created during seeding; the
  distributions under stress only re-add the few files other paths removed.
- **Every file is already in every distributed round** (3 jurors a file), so every file-major
  writer meets every round's votes.
- **`settle` failures are counted** as visible failures (`file_check`); production logs and
  drops them (`CommonsFileCheck.write`), so its "visible" count is an upper bound.
- **Retry spacing:** the harness retries the import's page write 3× 20/40/60 ms apart (and,
  in the `retry` variants, every operation); the scheduler waits 100/200/300 ms.
- **The deadlock log's victim** column is unreliable (it disagrees with which path saw the
  1213); victims here come from the harness's own counts.
- Admin actions every 0.3–3.6 s on the same five rounds, 16–19 threads, 90 s.

### Baseline and spikes: production paths (runs 2 and 3)

The paths that run in production today (the test-only W17–W19 left out:
`-Dstress.paths=(?!remove_|set_round).*`), 16 threads, 90 s a variant, seed 42; logs
`target/checks/20261103-073009-28872.log` (run 2; it stopped at its 7th variant on "Too many
connections": the old pools weren't closed, fixed) and `…-074439-25728.log` (run 3), results
`target/stress/write-paths-20261103-073125/` and `…-074540/`.

| Variant | Server deadlocks | Row lock waits (s waited) | Ops | Caller-visible failures: total (juror votes) | Deadlocks caught (after app retries) | Steps retried by the scheduler | p50/p95 ms: criteria change, CoI removal, activation, import page, file check, "distribute new files" | Invariants at the end |
|---|---|---|---|---|---|---|---|---|
| baseline #1 | 49 | 2,033 (134) | 53,016 | **13** (2): criteria change 3, CoI 1, file check 7 | 24 | 14 | 176/455, 83/492, 41/215, 132/324, 123/338, 376/1074 | inv. 4 ×9; `vote.monument_id` ×3 |
| baseline #2 | 53 | 2,005 (123) | 54,901 | **11** (2) | 22 | 16 | 142/404, 107/465, 22/185, 113/333, 94/387, 281/1008 | inv. 4 ×10 |
| (b) rc | 19 | 1,754 (94) | 56,692 | 4 (2) | 5 | 8 | 115/383, 71/305, 32/116, 117/270, 72/238, 292/955 | inv. 4 ×10 |
| (h) fixes | 47 | 1,890 (185) | 47,516 | 12 (2) | 26 | 8 | 172/828, 138/580, 49/308, 153/568, 136/629, 384/1336 | inv. 4 ×6 |
| rc + fixes | 20 | 1,771 (111) | 56,047 | 6 (3) | 7 | 7 | 125/410, 110/456, 29/193, 121/290, 72/277, 273/949 | inv. 4 ×11 |
| (a) roundfirst | 7 | 2,922 (233) | 57,413 | 4 (2; and two distribution jobs: a step deadlocked 4 times) | 4 | 2 | 112/285, 154/799, 34/146, 172/437, 293/838, 326/2095 | none |
| rc + roundfirst + fixes | 4 | 3,058 (203) | 61,913 | 2 (2) | 2 | 1 | 99/217, 145/520, 40/104, 161/322, 272/608, 277/1850 | none |
| (d) retry | 56 | 2,017 (112) | 55,977 | 0 | 28 (all retried) | 13 | 144/344, 82/372, 31/169, 119/277, 96/331, 313/973 | inv. 4 ×11 |
| rc + fixes + retry | 27 | 2,016 (159) | 57,419 | 0 | 12 (all retried) | 7 | 181/505, 158/445, 33/296, 189/421, 121/477, 304/1156 | inv. 4 ×7; `vote.monument_id` ×27 |
| rc + roundfirst + fixes + retry | 0 | 2,968 (220) | 59,432 | 0 | 0 | 0 | 104/320, 192/653, 26/129, 168/434, 274/658, 297/1888 | none |

**The combined result, restated.** RC + round-first + fixes gives **0–4 deadlocks per 90 s
under this stress** (4 without retry, 0 in the one run with it), **all absorbed by retry**.
"All invariants held" in those runs is partly luck for the monument copy: run 1's `roundfirst`
and `rc + roundfirst` left 27 and 36 stale `vote.monument_id` copies, because the chunk still
builds the copy from an unlocked read (h6 was never spiked). And the round-first variants did
**20–30 % fewer admin operations** in the same 90 s (they wait on the round row): the lower
deadlock count is partly fewer chances.

Juror votes (≈ 600 a second over the three vote paths) in every variant: binary/rated p50
1.3–1.7 ms, p95 2.4–9.5 ms; criterion rates p50 7–11 ms, p95 14–52 ms. The round-first
variants had the lowest vote p95 (2.4–3.5 ms against 4.2–4.9 at baseline).

Variation: the two baselines differ by 4 deadlocks (49, 53) and 2 visible failures (13, 11),
with the same shapes. Each spike ran once; RC's −60 % and round-first's −85 % are well outside
that spread, (h) alone (47) is not.

Deadlock pairs at baseline #1 (#2 alike): criteria change × import page 10 (file-major vs
round-major votes, break 1), repair job × import 7, file check × import 7 (votes moved aside
and back at once), criteria change × monument ids 4, repair × file check 4, import × monument
ids 3, distribution × monument ids or import 5 (round_image's insert reading `images` through
another index, (h1)), file check × juror vote 2 (break 10), activation × import 1, CoI ×
monument ids 1, criteria change × list 1. Under RC: 19, the same kinds, fewer. Under
round-first: 7, none between two structural writers of one round: CoI removal × juror vote 2
(break 10), criteria change × criterion rate 1, list × "distribute new files" 2 (break 11),
repair × distribution 2.

**Invariants.** Every round_image invariant but 4 held in every run; the criteria invariants
(U5 sums, no rate of a criterion the round hasn't) held everywhere. Two real defects (their
production impact: §3a):
- **Invariant 4 (a `deleted` row whose file isn't deleted), 2–11 a run without round-first:**
  activation (W5) reads the round's deleted files with a plain read and moves their votes
  aside; a restore (W14) committing in between leaves a live file's votes aside, hidden from
  the round. The repair job reports it, doesn't fix it.
- **`vote.monument_id` ≠ `images.monument_id` (3–36 rows in 6 of 16 runs):** a distribution
  chunk builds its rows' monument copy from its snapshot (W8); a monument change committing
  between the read and the insert updates the old copies, not the new rows.

### All paths, the test-only ones included (run 1)

With `removeImage`, `setRound`, `removeUnrated` too (19 threads; the import's page write not yet
retried in this run), log `target/checks/20261103-071534-28060.log`,
`target/stress/write-paths-20261103-071619/`.

| Variant | Server deadlocks | Juror vote failures (of ops) | Other deadlock failures | Distribution jobs failed on the count check | Invariant drift |
|---|---|---|---|---|---|
| baseline #1 | 165 | 41 (44,030) | 101 | 93 | none |
| baseline #2 | 158 | 2 (43,476) | 112 | 86 | inv. 4 ×2; `vote.monument_id` ×36 |
| rc | 77 | 25 (50,111) | 35 | 109 | inv. 4 ×8 |
| roundfirst | 70 | 50 (48,501) | 6 | 66 | `vote.monument_id` ×27 |
| retry | 227 | 0 (41,065) | 0 | 100 | `vote.monument_id` ×33 |
| rc + roundfirst | 53 | 37 (51,345) | 1 | 72 | `vote.monument_id` ×36 |

- `removeUnrated` (test-only) dominates: its `SELECT … WHERE v.id IN (5,000 ids) FOR UPDATE`
  scans `vote_page_jury_round_uidx` and deadlocks with juror votes (break 10's shape at scale):
  all of round-first's 70.
- **Count-check failures (66–109 a run, every variant):** a concurrent `removeImage`/`setRound`
  on a round changes its file count while its distribution runs (break 7). None in the
  production-path runs. P1b/P3's admin actions bring the case to production unless gated (g)
  or queued (e); (a) serializes transactions, not an action against another's steps.

### 3a. Production impact of the two defects

Both are in the deployed `f9c31ce5` (schema 70: tables `selection`, `selection_deleted`,
`rounds`; no `round_image`). Both need two rare events on the same file within milliseconds,
and neither loses a vote. **No hotfix needed**; the checks below can run read-only at any time,
and the repair at the next maintenance window (or never, if the checks return 0).

**1. A restored file's votes left aside by a round's activation.** Needs an organizer opening
a round (`setActiveGuarded`) in the same few milliseconds as an import restoring a file of that
round that had been deleted on Commons. Effect: the file's votes in that round stay in
`selection_deleted`, so jurors don't see the file and its votes don't count, until the next
import of the file: `DeletedFiles.restore` also picks files with votes aside
(`deletedOrArchived`), so a re-import of the contest repairs it by itself.

```sql
-- detection, production today (pre-V82)
SELECT d.round_id, d.page_id, COUNT(*) AS votes
FROM selection_deleted d JOIN images i ON i.page_id = d.page_id AND i.deleted_at IS NULL
GROUP BY d.round_id, d.page_id;
-- after V82/V84
SELECT d.round_id, d.page_id, COUNT(*) AS votes
FROM vote_aside d JOIN images i ON i.page_id = d.page_id AND i.deleted_at IS NULL
GROUP BY d.round_id, d.page_id;       -- = RoundImageInvariants' invariant 4 over round_image

-- repair (app stopped, or re-run the contest's image import instead), pre-V82; the moved rows
-- keep their ids, so their criteria rates follow
INSERT INTO selection SELECT d.* FROM selection_deleted d
JOIN images i ON i.page_id = d.page_id AND i.deleted_at IS NULL
WHERE EXISTS (SELECT 1 FROM rounds r WHERE r.id = d.round_id)
  AND NOT EXISTS (SELECT 1 FROM selection s
                  WHERE s.page_id = d.page_id AND s.jury_id = d.jury_id AND s.round_id = d.round_id);
DELETE d FROM selection_deleted d WHERE EXISTS (SELECT 1 FROM selection s WHERE s.id = d.id);
-- after V82/V84: the same with vote, vote_aside and round, then the repair job
-- ("Check every round's images") to set the rows' status back to 'in'
```

**2. A stale `selection.monument_id` copy.** Needs a monument id change (an import's
monument-id mode, or a page write with a changed id) committing during the distribution chunk
that inserts votes for the same file. Effect: that file's votes carry the old monument id, so a
juror's region filter and region menu place it in the old region (all-jurors region reads are
from `round_image` since P1). V84's pre-deploy queries already count it.

```sql
-- detection, pre-V82
SELECT COUNT(*) FROM selection s JOIN images i ON i.page_id = s.page_id
WHERE NOT (s.monument_id <=> i.monument_id);
SELECT COUNT(*) FROM selection_deleted s JOIN images i ON i.page_id = s.page_id
WHERE NOT (s.monument_id <=> i.monument_id);
-- repair, pre-V82
UPDATE selection s JOIN images i ON i.page_id = s.page_id
SET s.monument_id = i.monument_id WHERE NOT (s.monument_id <=> i.monument_id);
UPDATE selection_deleted s JOIN images i ON i.page_id = s.page_id
SET s.monument_id = i.monument_id WHERE NOT (s.monument_id <=> i.monument_id);
-- after V82: the same with vote and vote_aside; round_image's copy by the repair job's
-- monument step
```

## 4. Spikes

All spikes are behind system properties, off by default (the code behaves as before with them
off; the harness's baselines run that code): `db.scalikejdbc.RoundWrites`.

### (a) One structural-write rule per round: the round first, for update

`RoundWrites.lock(roundIds)` (`FOR UPDATE`, id order) at the top of every structural
writer's transaction, `RoundWrites.lockForFiles(pageIds, roundIds)` for writers that start
from files (the rounds the files have `round_image` rows in, read without a lock, then
locked), and `roundLockMode` turning the existing round share locks into `FOR UPDATE`.
Flag `-Dwlxjury.writes.roundFirst=true`. Call sites (14 lines): `VoteJdbc.create`,
`batchInsert` (so every chunk), `setRound`, `removeImage`, `removeUnrated`;
`ImageJdbc.lockOpenRoundsOf`, `lockRoundsOfArchived`; `DeletedFiles.settle`; the list phase;
`ImageImportHandler.writeImages`, `writeMonumentIds`; `CommonsFileCheck.write`; the repair
job's round lock. W3, W5, W6, W7, W10, W11 already lock the round for update first.
Juror votes (W1, W2) are untouched.

Guard: `test/security/StructuralWritesSpec` lists the files with SQL that writes the six
tables or the monument copies (6, the DAO layer) and the files that call the DAO's structural
writers (14), each with its lock rule; a new writer or caller fails until listed. With (a)
adopted the rule per entry would become "calls `RoundWrites`" and the spec could check that
each listed writer's transaction starts with it (a source scan of the enclosing `localTx`, or
a runtime check: `RoundWrites` records the locked rounds in the session and the DAO's write
methods `require` them, the stronger form).

Results (§3): deadlocks 49–53 → 7 (with RC and (h): 4; with retry too: 0); none left between
two structural writers of one round; invariant 4's race closed. Cost: admin actions wait on
the round row instead of interleaving: under this stress the file check p50 94–123 → 293 ms,
CoI removal 83–107 → 154 ms, the import page 113–132 → 172 ms, "distribute new files" p95
~1.0 → 2.1 s; total row-lock wait time 123–134 → 233 s over 90 s of 16 threads. At
production's rate (a handful of admin actions an hour per contest) the wait is one other
action's transaction, ≤ ~1 s (a chunk). Juror votes got faster (p95 4.2–4.9 → 3.5 ms).

Remaining gap: `lockForFiles` reads a file's rounds without a lock; a round that gains the
file between that read and the write (a concurrent distribution chunk inserting it) is not
locked. The chunk holds that round for update and then waits for the file's image row, which
the file-major writer holds: a wait, not a cycle, unless the writer then needs that round's
votes (it doesn't: they aren't committed). Not seen in the runs.

### (b) READ COMMITTED for write transactions

`-Dstress.variants=rc` sets the server's default isolation for new connections (so every
transaction, requests and jobs). Deadlocks 49–53 → 19 (−60 %); the import and monument-id
shapes mostly disappear (their `INSERT … SELECT`/`UPDATE` subquery locks and gap locks);
throughput +5 %; admin p50s 10–40 % lower. Alone it leaves invariant 4's race and the
cross-index pairs (breaks 1, 10).

#### RC audit: what relies on REPEATABLE READ

Searched every `DB.localTx`/job step that writes these tables for plain reads whose answer the
transaction then relies on, `INSERT … SELECT`/`UPDATE … (subquery)` checks, and count checks.

| Place | Relies on RR? | Under RC |
|---|---|---|
| The code's own comments | The opposite: `lockOpenRoundsOf`, `lockRoundsOfArchived`, `moveVotesAside`, `sync`, the repair job, `JobJdbc` ("as committed now, not the snapshot") all use locking reads *because* RR's snapshot was stale | the workarounds stay correct; plain reads become current, so the snapshot-staleness class of bugs shrinks |
| W1 `VoteJdbc.rate`: `NOT EXISTS (round_criterion …)` in the `UPDATE` | **yes (C2 F2)**: a plain vote waiting behind a criteria change relies on the subquery being a *locking* read, so it sees the committed criteria and is refused | at RC the subquery is a consistent read from the statement's start: a vote waiting behind a criteria change could still pass on a round that now has criteria. Needs W1 as W2: one tx, `vote … FOR UPDATE`, then `round_criterion LOCK IN SHARE MODE`, then the update (two more round trips per vote) |
| W14 `moveVotesBack`: `INSERT INTO vote SELECT … WHERE EXISTS (round) AND NOT EXISTS (vote …)` | partly: the round's S lock comes from `lockRoundsOfArchived` first (explicit), the `NOT EXISTS` reads the file's votes, locked first by `COUNT(*) … FOR UPDATE` | the explicit locks still hold; under RC the `FOR UPDATE` locks the rows but not the gaps, so a vote inserted for the same (page, juror, round) meanwhile is possible only through a distribution chunk, which takes `images` S that restore holds X: excluded by the file lock |
| W8 distribution: candidates and `notInRound` read plainly before the insert | no (a snapshot read before the round lock is *staler* than an RC read) | better: fewer files read as candidates that another writer just added; the unique key still catches a duplicate |
| W10 count check | no: counts are plain reads in separate steps, compared across transactions | unchanged |
| W11, W5 opening: plain reads after the round lock | no (RR's snapshot starts at the first plain read, after the lock) | unchanged |
| **W5 opening vs restore (found by the harness, both isolations)** | it reads deleted files with a plain read and moves their votes aside; a restore committing in between leaves a live file's votes aside (`deleted` with `images.deleted_at` NULL: invariant 4, seen 2–8 times a run) | fix either way: read the deleted files with `LOCK IN SHARE MODE` on `images` (after the round, so in order) |
| Gallery/large-view reads | autocommit statements, no snapshot across them today | unchanged |
| Binlog | — | `binlog_format` must be MIXED or ROW when `log_bin` is on: C2's pre-deploy query (`SELECT @@log_bin, @@binlog_format`) already gates V83/V84 |

Ways to set it: per transaction (`ReadCommitted.localTx`, as W2 today), per pool
(`transactionIsolation` in the HikariCP config, `db.default.hikaricp.transactionIsolation =
TRANSACTION_READ_COMMITTED`: every connection), or the server's `transaction_isolation`
(infra). The per-pool setting keeps it in the app's repository and covers jobs and requests.

### (c) Less denormalization: no `vote.monument_id` (or `round_image.monument_id`)

Measured on the Gatling fixture (`gatling.setup.WritePathReadBenchmark`, 38k files, rated round
384k vote rows, MariaDB 10.6.22, 512 MB buffer pool, SQL logging off; 15 interleaved pairs
after 2 warm-ups, order alternating, every pair's results equal; log
`target/checks/20261103-075306-17020.log`, `target/gatling/write-path-read-bench-20261103-075349/`):

| Read | A: today (vote copy) median (min–max) ms | B: without it | Paired B − A median (min..max) | B faster |
|---|---|---|---|---|
| A juror's region page, rated (page 1) | 10.0 (9.1–14.5) | `JOIN images`: 16.7 (15.8–18.8) | **+6.5** (+3.5..+8.7) | 0/15 |
| …page 20 | 10.7 (10.2–31.8) | 17.1 (15.2–46.0) | +5.1 (−2.1..+15.4) | 1/15 |
| A juror's region counts by rate (the juror's region menu) | 14.9 (13.3–19.7) | `JOIN images`: 121.8 (115.2–178.4) | **+107.0** (+97.6..+164.6) | 0/15 |
| All jurors, rated page in rate then monument order | 668.3 (595.0–917.5) | grouped on `vote_round_page_rate_idx`, ordered by `round_image.monument_id`: 172.6 (155.5–239.9) | **−496.8** (−682.2..−435.9) | 15/15 |
| All jurors, a region's file count | 67.8 (65.2–74.5) | `round_image.region`: 12.5 (11.8–14.3) | **−55.3** (−62.1..−52.6) | 15/15 |

So the copy helps only the juror's own reads (its covering index
`vote_jury_round_rate_mon_page_idx`), and most there the juror's region menu (+107 ms
without it, via `images`; via `round_image`'s `(round_id, page_id)` key and stored `region`
not measured, expected lower: a 13 MB table). For all-jurors reads it is a cost: the rated
order's `s.monument_id` is not in the grouping index (B12's finding), and `round_image`'s copy
is 0.5 s faster on the same page, a gain available today, independent of P2b.

On the write side the copy is what makes W9, W14 and W15 lock **every vote of a file in every
round** (file-major, break 1–2): without it a monument change writes `images` and
`round_image` only, a single row per round, last in the order. Those writes are the largest
group of the baseline's deadlock pairs (criteria change × import 10 + 4, CoI × monument ids,
distribution × monument ids), and the copy is wrong in 6 of 16 runs (§3). [rec] Drop
`vote.monument_id` and `vote_aside.monument_id`; all-jurors reads use `round_image`'s copy,
the juror's region reads join `round_image` (measure first; the juror's region counts are also
cacheable per juror, `RegionCountCache`). Migration: drop the columns and rebuild
`vote_jury_round_rate_mon_page_idx` as `(jury_id, round_id, rate, page_id)` and drop
`vote_round_monument_id_idx` (an online `ALTER` of ~3M rows; the fixture's 767k-row index
rebuild below took 3.5 s); rollback: add the column back and backfill from `images` (V48's
statement). Keep `round_image.monument_id`.

### (d) Uniform retry

Measured as the harness retrying every operation up to 3 times on a deadlock (`retry`): 0
visible failures in 90 s (56 deadlocks, all absorbed; 28 operations retried), against 11 at
baseline; latency unchanged at p50. As code: one wrapper at every transaction *owner*
(`Tx.write(retries = 3) { implicit s => … }` replacing `DB.localTx` where a write transaction
starts: the request paths W1, W3–W7, W11, W12/W13; job steps already retry), never inside a
caller's transaction (`updateIn`-style methods: after a deadlock the whole outer transaction is
gone, and an inner retry would run on without it).

Which are not idempotent (unsafe to rerun as a whole after a rollback):
- none of the transaction bodies writes outside the database before commit: caches are
  invalidated after commit (`afterCommit`, `RoundImageCounts.invalidate` after the block), log
  lines after, Commons fetched before (`prepare`), mails never inside;
- **reads before the transaction** decide what it does: `setActiveGuarded`'s `refused` read,
  the round form's validation before W3, `CommonsFileCheck.write`'s split into W12 then W13,
  `setRound` then its settle, the in-memory `contexts` of a distribution (re-read per attempt).
  A retry of the transaction alone reruns on the old decision: harmless for these (each
  transaction re-checks under its locks), but a wrapper must wrap the *transaction*, and the
  rule must be that every decision is re-read inside it;
- `Round.create` without a submit token creates a new round per call: safe to retry after a
  rollback (nothing committed), unsafe to retry after an *unknown* outcome (a lost
  connection after commit): `retryOnDeadlock` only retries 1213, where the outcome is known;
- `JobScheduler` steps: already retried; a step that fails after 3 deadlocks fails the job
  (seen twice in the round-first run: a distribution step losing 4 times in a row).

### (e) A per-round queue of admin actions

Every structural, non-vote write to a round becomes a job with a per-round exclusivity key and
runs serially: distribution (W8–W10), the criteria change and the rest of the round edit
(W3/W4), activation (W5), CoI removal and P1b's disqualify/requalify (W11), P3's
`removeImage` (W17), and the repair job (W16).

**What the scheduler needs.** Today a job has `dedupe_key`/`active_dedupe_key` (one active
job per key, a unique index), per-kind concurrency limits and per-contest turns. A per-round
lane needs a second key that *queues* instead of refusing:
- column `lane_key` (`round:R`, nullable) and a claim rule: a job is claimable only if no job
  with the same `lane_key` is `running`, and it is the oldest `queued` one of its lane
  (`SELECT … FROM job WHERE lane_key = ? AND status IN ('queued','running') ORDER BY id LIMIT 1`
  inside the claim transaction, the lane's head row locked). A unique `active_lane_key`
  (set while running, NULL otherwise, as `active_dedupe_key` is) makes "one running per lane"
  a constraint, not only a rule;
- the dedupe key stays for "the same action twice" (a double submit); the lane key orders
  different actions;
- every step still runs in its own transaction; a lane holds only between steps, so a step
  of action A and a juror's vote still meet on vote rows. Cancel and retry keep their meaning;
  a failed action blocks its lane until retried or cancelled (or the lane skips failed jobs:
  a decision).

**Lock ordering.**
- No longer needs a cross-path order: any two queued actions of the same round (W3×W8,
  W5×W11, W8×W16, W11×W17 …: the C1/C2/P1 review findings "chunk vs criteria change" and
  "list step vs criteria change" become impossible), and the count check (break 7) can't be
  upset by a same-round admin action.
- Still needs it: juror votes (W1/W2) against every queued step (rows, not round); Commons
  writers (W12–W15: a file is in many rounds, so no single lane; they would enqueue one
  per-round settle action, or stay outside with their own order); the setRound-like moves
  between two rounds (two lanes: take both lanes in id order, or a contest lane); anything
  left on request threads. The harness's top pairs (§3) are of these kinds, so (e) alone
  removes the minority of them; (a) or RC still has to cover the rest.
- Different rounds still share gap locks (break 5) and file-major rows (break 1).

**UX and semantics.**
- Validation stays synchronous (form errors, permissions, "criteria can't change after the
  first vote" read without a lock), then the action is enqueued; the request answers "queued"
  and the page shows the round's lane (pending, running, failed with its message, as the job
  page does for distributions). The final check (the same rule, under the round's lock in the
  step) can still refuse: that refusal arrives asynchronously, on the job page or a flash on
  the next load. Today's round form answers a refused criteria change on the same page; that
  becomes "queued, then refused" unless the synchronous pre-check catches it first (it almost
  always does: the only race is a first vote between the check and the step).
- Activation and "leave out CoI now" become "queued": the round list must show a pending
  state; a juror may still vote in the gap, which is harmless (the step re-reads).

**Queueing delay.** A quick edit behind a long distribution of the same round waits for it:
the fixture's 38k-file round distributes in tens of seconds to minutes
(`RoundDistributionSimulation`); production's ~3M-vote contests longer. Options: (1) step
interleaving: the lane serializes *actions*, but a short action may run between two steps of
a long one if it commutes with it (a rename, the "leave out CoI" flag; not a criteria change
during a fill), which needs a per-kind compatibility table; (2) priority: interactive actions
go before background ones at the next step boundary (the scheduler's per-contest turns
already pick between jobs per step). Either keeps the worst wait to one step (≤ ~1 s,
`chunkRows`) for compatible actions and to the whole job for incompatible ones, which is
today's rule anyway ("a distribution of the round is queued or running": W11 refuses).

**Fit.** P3's two-step flow is already job-shaped (fill, review, re-apply, distribute):
its re-apply and distribute are lane actions. D16a's `removeImage` (`dq`, reason kept) and
P1b's disqualify/requalify are lane actions too. The `job` table with `params`, `created_by`,
timestamps and outcome is an audit trail of admin actions per round for free: the
Montage-parity `round_event` audit log could be the lane's history (job rows are kept), plus
the events that aren't jobs (juror changes, status changes by hand).

**Against the harness's numbers.** Of round-first's 7 remaining deadlocks (run 2), a lane
would also remove the 4 between two jobs of one round (list × "distribute new files" 2,
repair × distribution 2: break 11 and step interleaving) and leave the 3 juror × structural
ones (CoI × juror vote 2, criteria change × criterion rate 1): the residue (d) retries. What
(a) can't do and a lane can: the count-check failures of run 1 (66–109 a run with
`removeImage`/`setRound` on a distributing round), since (a) serializes transactions, not an
action against the *steps* of another; gating (g) does the same for P3's actions more simply.

**Cost against (a).** (a): one helper, ~15 call sites (spiked here: 14 lines of calls), a
guard spec, no schema, no UX change; it removes cycles between structural writers by making
the round row a mutex (blocking, inside each transaction). (e): a migration (`lane_key`,
`active_lane_key`), the claim rule and its specs (restart recovery, cancel, failed head of
lane), converting ~6 request actions to jobs with pending UI and async errors, and per-kind
compatibility if interleaving is wanted: weeks rather than days. (e) gives what (a) gives for
same-round actions (serialization), plus no request thread ever waits on a long job (it
queues instead of blocking ≤ `innodb_lock_wait_timeout`), plus the audit trail; it does not
remove the vote-vs-step and Commons-vs-step pairs, which (a) doesn't either.

### (f) A status column on `vote` instead of `vote_aside`

Measured on the same fixture (the column added with `ALGORITHM=INSTANT`, 24 ms):

| Read | Today | With `status = 0`, indexes unchanged | Paired Δ | With the two read indexes rebuilt with `status` (3.5 s) | Δ against today's (before the rebuild) |
|---|---|---|---|---|---|
| All jurors, binary grouped page | 110.8 (106.0–133.4) | 558.2 (513.0–754.7) | **+438.2** | 135.7 (130.4–189.3) | **+25** (+22 %) |
| A juror's region page | 8.6 (8.2–16.8) | 116.5 (110.0–170.9) | +108.2 | 8.5 (8.3–9.1) | 0 |
| The round's file count | 127.8 (111.0–147.0) | 200.7 (185.1–245.2) | +74.2 | 136.7 (125.3–210.2) | +9 |

After the rebuild, today's statement *without* the predicate got slower (188.6 ms: the
grouping index now starts `(round_id, status, …)`), so every vote read must carry the
predicate, as Montage's do (`Vote.status == ACTIVE_STATUS`, §5): about 60 statements here,
and a forgotten one shows cancelled votes (a correctness hazard a guard spec would have to
catch). The vote table's other indexes (`vote_round_idx`, `vote_page_id_idx`, the rate and
monument indexes) would need the same treatment. (V100, 2026-11-13, has since dropped
`vote_round_idx`, `vote_page_id_idx` and the rate index `vote_rate_idx`.)

What it would remove: the moves between `vote` and `vote_aside` (W5's opening, W12–W14,
`moveVotes`, P1b's disqualify): row copies across two tables with their gap and insert
intention locks (file check × import page: 7 at baseline). What it would not: the Commons
writers still lock every vote of a file across rounds (now `UPDATE vote SET status`), so break
1 stays unless (c) and (a) take it. [rec] Not now: the moves are rare events and (a) + RC
already order them; a status filter in every vote read on a 3M-row table is the larger,
riskier change. Reconsider with the Montage-parity work if disqualifications become frequent.

### (g) Status gating

Which structural writes could be allowed only while a round is not open to jurors (paused,
draft, or before its first vote), so they never race a juror's vote:

| Write | Gate it? | Why |
|---|---|---|
| Criteria change (W3) | **yes, already almost**: after the first vote only a rename is taken; gate the rename to `paused` | a rename moves every rate of the round (`criteria_rate` update over the round's votes) while jurors rate: the one W3 shape that holds many vote rows |
| Re-apply filters, Prune (P3) | yes (D16 already: "before any vote exists in the round") | |
| Disqualify / requalify (P1b), `removeImage` as `dq` (D16a) | **yes [rec]**, as Montage | moves votes aside / back; a juror's open large view of the file then 404s mid-vote today anyway |
| CoI "leave out now" (W11) | yes, as part of P1b (it becomes disqualify) | |
| Fill (P3's first distribution) | yes (`draft`, by construction) | |
| "Distribute new files" into an open round (W8 on an open round) | **no** | D19: filter edits after distribution only add files, and admins add late uploads while the round runs; the inserts are new rows, jurors' votes touch existing rows: the conflict is on gaps and the round lock, not the votes |
| Activation (W5) | n/a (it is the gate) | |
| Commons-driven moves (W12–W15) | **no** | they happen when Commons changes; a deleted file must leave the open rounds now |
| Repair job (W16) | no (it must fix live rounds), but it can skip a round that is not paused when it would *add* rows | |

Gating does **not** stop a gated action from overlapping juror votes: the vote's "round open"
check is the controller's earlier plain read (`Round.openToJurors` on the round read for the
request), not a lock, so a vote that read the round as open before the pause still writes
during the action. What gating gives is that such overlaps are few and short (only votes
already in flight); row-level correctness under the round lock, and the retry, stay needed.
The status must be checked under the round's X lock inside the action's own transaction (W7).
Gating does not remove "structural × structural" pairs between
non-gated writers (distribution of new files × Commons moves), nor cross-round coupling.

### (h) Targeted fixes (no new rule)

`-Dwlxjury.writes.fixes=true`: (h1) round_image's `INSERT … SELECT` reads `images`
`FORCE INDEX (PRIMARY)` (the rows the FK check locked) instead of the covering
`images_page_monument_idx`, a second lock pass on another index; (h2) the per-file update
loops (`writeImages`, the list phase) lock their files in page order first, also when nothing
is restored (break 3); (h3) `settle` locks the open rounds before the images (break 4).
Alone: 47 deadlocks against 49–53, inside the noise; the distribution × import shapes it
targets went from 5–8 to 1, but file check × import rose (12). With RC: 20 vs 19. Worth keeping
as correctness of the order (they remove known cycles), not as a lever on the count.
Not spiked, and recommended with them: (h4) the juror vote by primary key (break 10); (h5) the
activation's deleted-file read as a locking read (invariant 4); (h6) the vote's monument copy
from the locked image row, if (c) is not taken.

## 5. Montage

Read from `git show origin/master:montage/rdb.py` in `C:/python/montage` at `d879cb8`
(2026-10-31); `DBSessionMiddleware` is `montage/mw/__init__.py:243`.

| Montage | Where (`rdb.py` at `d879cb8`) | Here |
|---|---|---|
| Structural writes gated by round status: entries are added only to a **paused** round, disqualify/requalify only while paused, quorum and jurors edited only while paused | `add_round_entries` 1879–1912 ("round must be paused to add new entries"), `lock_round_for_import` 1683–1715, `disqualify` 1495–1519, `requalify` 1521–1544, quorum/jurors 1428, 1437 | Every structural writer runs while jurors vote (W3, W5, W8–W16); P3's `paused` status is planned but not a gate |
| Activation creates the vote rows ("tasks") in one transaction, under the round's lock | `activate_round` 1717–1746 (`_lock_round`, then `create_initial_tasks`) | The fill is a background job of many steps (W8), each its own transaction, while the round may already be open ("Distribute new files") |
| One coarse lock per round: `SELECT … FOR UPDATE` on the round row | `_lock_round` 1673–1681; used by import (#635) and activation (#334) only. Only those two check the status under the lock; `disqualify`, `requalify` (1495–1544) and the quorum/juror edits (1428, 1437) check `rnd.status` on a plain read (`get_round`), so a concurrent activation can slip between check and write | Proposal (a) |
| RR snapshot guard: the import compares the snapshot's entry count with a fresh count from a second connection taken after the lock, and refuses if they differ | `lock_round_for_import` 1683–1715 | Our count check (W10) compares before/after counts across steps; RC (b) makes plain reads after the lock current without a second connection |
| One transaction per request (`DBSessionMiddleware`), no background jobs | `mw/__init__.py:243` | Requests plus job steps plus a background file check |
| Lock errors 1205/1213 become a 400 "the server is busy, please retry" | `lock_errors_as_busy` 108–120 (#621), used **only** by `activate_round` (1718): imports, disqualify and the edits let a lock error through as a 500 | A 1213 in W1 is a 500 (`ErrorHandler`); jobs retry 3× |
| Nothing moves between tables: disqualification sets `round_entries.dq_reason`/`dq_user_id` and the entry's votes `status = 'cancelled'`; requalify sets them back to `active`; cancelling a round cancels its votes in place | `disqualify` 1509–1516, `requalify` 1536–1540, `cancel_round` 1914–1932; `Vote` 809, its `status` (`active`, `cancelled`, `complete`) 817, reads filter `Vote.status == ACTIVE_STATUS` (377, 454, 535, 1303) | `vote` ↔ `vote_aside` moves with ids kept (W5, W12–W14), each a multi-table lock sequence |
| No maintained counts: results computed on read; `results_summaries` stored at finalize | `RoundResultsSummary` 898, table `results_summaries` 921 | P2a/P2b plan maintained counts |
| `audit_log_entries`: every coordinator action | `AuditLogEntry` 947, `log_action` calls throughout | none yet (the Montage-parity plan wants it) |

What doesn't transfer:
- **Synchronous activation.** Montage's rounds are small enough to create every task in one
  request. Ours are up to ~384k vote rows a round (the fixture: 38k files × 10 jurors);
  one transaction of that size holds the round's locks (and undo) for minutes and is why the
  distribution became a stepped job (V60). The gate transfers; the single transaction doesn't.
- **"Paused" while jurors wait.** Montage pauses a round for every import or
  disqualification. Our admins add files to open rounds ("Distribute new files", D19) and
  file deletions on Commons arrive whenever the import or the file check sees them (W12–W15):
  those can't wait for a pause. Gating fits admin actions, not Commons-driven ones.
- **400 "busy".** Montage's lock errors surface to coordinators. Ours would surface mostly to
  jurors (W1), where a silent server-side retry is better than a message.
- **Cancelled votes in place** fit our `dq` (D7) and `deleted` cases exactly; measured as (f).

## 6. P2a reconsidered

The plan's P2a keeps `assigned`, `voted`, `approvals`, `rejections`, `rate_sum` on `round_image`
by deltas in the vote transaction (and recomputes in every bulk writer); P2b reads them.
Measured (same benchmark, the columns added `INSTANT` in 33 ms):

| Measurement | Result |
|---|---|
| P2a's cost per vote: one committed vote (row locked, rate 1 ↔ 2) without (A) and with (B) the `round_image` row locked and its counts updated, 200 interleaved pairs | A 5.0 ms (4.0–16.4), B 6.4 ms (5.1–16.1): **+1.3 ms** median (−3.9..+7.4), B slower in 193/200; plus a new lock in every vote, on the row every juror of the file shares |
| The rated round's narrow page (30 files, rate order): grouped from `vote` (with B12's deferred shape and `round_image`'s monument copy) / from the count columns | 168.6 (159.7–252.1) / 30.2 (27.6–36.8): **−138 ms** (−218..−131), 15/15 |
| The binary page (approvals order) | 123.9 (108.4–182.2) / 16.8 (16.1–24.2): **−105 ms** (−161..−92), 15/15 |
| Option (i): every file's counts of the rated round read for a cache (`GROUP BY page_id`, index-only, 38k rows to the app) | **203.7 ms** (191.3–340.2) per miss |
| Option (ii): the rated round's full recount (`UPDATE round_image JOIN (GROUP BY)`, committed), 5 runs | **1.92 s** (1.73–2.82): one statement locks the round's 38k rows that long; in windows of 2,000 files, ~100 ms each |

The read side B11/B12 measured (follow-ups plan, B12 pilot): by-rate all-jurors page 819 ms
(rated) / 545 (yes/no) client, of which the grouped page ~660 / ~310 server; the large view
1.5 s / 0.6 s (the grouped page plus the file's rank); the file list 991 / 760; `keyCounts`
~125. P2b replaces the grouped part of each with a read of the columns (the measured −105 to
−138 ms on the narrow page; ~0.3–0.6 s a page with the deferred join's other parts and the
rank, estimated, P2b's pilot measures it).

Options:
- **P2a/P2b (the plan):** exact counts at every moment; +1.3 ms and a shared row lock per vote;
  every bulk writer (W5, W8–W14, W17–W19, P1b, P3) must keep the deltas or recompute, and the
  lock order must keep `round_image` last in each (the class this document is about); drift is
  a correctness bug, so the repair job grows a count part.
- **(i) counts on read plus a cache:** no write-side change at all; ~200 ms per round per cache
  miss, then pages from memory (38k entries a round). An exact cache is invalidated by every
  vote (a busy round: several a second), so it must be a TTL cache (organizers see counts up
  to N seconds old); exact consumers bypass it.
- **(ii) a dirty-round mark plus a background recompute:** the vote transaction unchanged; an
  in-memory set of rounds voted on since their last recount (one app instance: production
  runs one; a restart marks every open round dirty), a job recomputing a dirty round in page
  windows every few seconds (~2 s of work per 38k-file round, in 100 ms transactions), P2b
  reading the columns with an "as of" time. Bulk writers only mark the round dirty. Exact
  consumers (a top-N distribution, finalize, CSV) recompute the round first or read the votes,
  as today.

[rec] **(ii).** It keeps the vote transaction as it is (no new lock, no ordering duty), turns
count drift from a bug into a delay, and gives P2b the same columns and the same page speed.
The price is staleness of a few seconds in organizers' all-jurors views, which already show
other jurors' work as it arrives. (i) is the fallback if the columns are not wanted at all.
If the user wants exact live counts, P2a as planned, done after (a) and RC, whose harness
variant shows the order holding with no deadlocks.

## 7. Recommendation

**[rec] READ COMMITTED + the round-first rule (a) + targeted fixes (h) + one retry wrapper (d),
then (c), with (ii) instead of P2a; (g) for P1b/P3's admin actions; (e) and (f) not now.**

Harness, production paths: 0–4 deadlocks per 90 s under stress, all absorbed by the retry, 0
visible failures with it; the monument copy still needs h6 (round-first alone left 27–36 stale
copies in run 1); juror vote p95 2.4–3.5 ms (baseline 4.2–4.9); 20–30 % fewer admin operations
in the same time, at an admin wait cost under stress of +60–170 ms p50 on file
check, CoI removal and import pages (§4 (a)). What each part does:

| Part | Removes | Cost | Rollout |
|---|---|---|---|
| RC for every connection (`db.default.hikaricp.transactionIsolation = "TRANSACTION_READ_COMMITTED"`); W1 rewritten first (vote row locked, criteria read with a share lock, then the update; or W1 by primary key, (h4)) | gap and subquery locks: −60 % of deadlocks; RR's stale snapshots (the workarounds stay) | W1: two more statements per vote | config + W1; pre-deploy: C2's binlog query (MIXED/ROW); rollback: config. Run the lock specs: C1/C2's assume RR's gap locks for some waits |
| (a) `RoundWrites`: the round(s) `FOR UPDATE` first in every structural writer, `lockForFiles` for file-major ones; guard spec; later a runtime `require` in the DAO's write methods | every cycle between two structural writers of a round (the class the C1/C2/P1 reviews kept finding); invariant 4's race | admin actions serialize per round (blocking, ≤ one chunk's transaction) | code only, ~15 call sites (spiked), flag-free in the real change; rollback: code |
| (h) h1–h6 (§4 (h)) | the remaining known cycles (index passes, unsorted loops, settle, one row/two indexes) and the two data defects | small | code only |
| (d) `Tx.write(retries = 3)` at every transaction owner | the residue (also unknown future cycles) becomes latency, not a 500 | rule: decisions re-read inside the transaction | code only |
| (c) drop `vote.monument_id`/`vote_aside.monument_id` | the file-major vote locking of every Commons monument change (break 1's biggest pairs) and the copy's drift; −0.5 s on the all-jurors rated page | juror region menu +~100 ms unless read via `round_image` or cached; one migration | a V-migration (online `ALTER` of `vote`, `vote_aside`), reads moved; rollback SQL re-adds and backfills |
| (ii) instead of P2a | the count deltas' write paths before they exist | seconds of staleness for organizers | P2b as planned on columns kept by a job |

Order: (h) and (d) first (small, independent); RC and W1 next (one release, full suite, the
lock specs rerun); (a) with its guard; then P1b on top; (c) with or before P2b; (ii) as P2a's
replacement.

Why not (e) now: it removes the same structural × structural pairs as (a) and adds a
non-blocking UX and an audit trail, but leaves the juror × step, Commons × step and
cross-round pairs (§4 (e)), and costs a scheduler change, pending UI and async errors for six
request actions. It fits P3's job-shaped flow: build P3's actions as lane jobs then, with (a)
still underneath (a lane serializes actions, not their steps against Commons writers).

## 8. Effect on the other plans

- **Round-images plan §5 (lock order):** becomes "structural writers lock their round(s) for
  update first (`RoundWrites`), file-major writers the files' rounds; juror votes row-level;
  RC". The detailed per-table order stays as documentation of what each writer does after the
  round, no longer the safety argument between writers of one round.
- **P1b (CoI disqualification):** disqualify/requalify as round-first writers; [rec] gated to
  a round that isn't open (paused, P3) once P3 exists, open rounds meanwhile as today's "leave
  out now" (refused while a distribution is active). `RoundConflicts` already locks first.
- **P2a:** replaced by (ii) (decision W6). The count columns and their backfill stay
  (`INSTANT`); no deltas in `VoteJdbc.rate`, `updateRateIn`, `batchInsert`, the bulk writers;
  the repair job's count part becomes the recompute job.
- **P2b:** unchanged reads, from the columns, with an "as of"; exact consumers (top-N, CSV,
  finalize) recompute first. Independently now: order the all-jurors rated page by
  `round_image.monument_id` (−0.5 s measured).
- **P3 (two-step):** the fill runs in `draft` (no juror race by construction, (g)); re-apply,
  Prune and D16a's `removeImage` (`dq`) are round-first writers gated to `paused`; the count
  check then can't be upset by an admin action (break 7). If (e) is taken later, these are its
  first lane actions.
- **D16a:** unchanged in meaning; `removeImage` gains the round lock (spiked).
- **C3 (criteria results):** reads only; unaffected. Its per-criterion averages over
  `criteria_rate JOIN vote` don't use the monument copy.
- **C4 (juror UX):** unaffected; W2 stays as it is (already RC, retried).

## 9. Decisions (the user, 2026-11-03)

The user agreed to W1–W10 on 2026-11-03, after the review ("sound with corrections"), with the
reviewer's changes below. Implemented in the robustness batch
(`ukwikidev/write-path-robustness`): W1, W2, W3, W8, W9, W10 (if the app-path check confirms
it). Recorded for later: W4 (measurement), W6 and W7 (round-images plan P2a, P1b, P3).

| # | Decision **[user]** | With the review's changes |
|---|---|---|
| W1 | READ COMMITTED for every write transaction | RC on production's pool (`db.default.hikaricp.transactionIsolation`) **and** on the tests' DBCP pool (`SharedTestDb.registerPools`: `ConnectionPool.singleton`; by the JDBC URL or `connectionInitSql`, so both match); `CriteriaRateSpec`'s RR check (`:326`) updated. Ships **with or after (a), never before**: `RoundImageJdbc.syncChunk` relies on RR's gap locks against `moveVotesBack`. The plain vote (W1) rewritten: the vote id by a plain read, then `SELECT … WHERE id = ? FOR UPDATE` by primary key, then `round_user` (the P6 stop race) and `round_criterion` read with a lock, then the update |
| W2 | The round-first rule (a) | The import's `lockForFiles` (`ImageImportHandler.scala:316`) narrowed to restored ∪ changed files, skipped when empty. The runtime guard now: `RoundWrites` records the rounds locked per transaction and the DAO's writers `require` them. Break 11 resolved: under RC the dedupe key is read plainly under the round's X lock (no `LOCK IN SHARE MODE`). Hikari pool occupancy (30 connections) measured under import convoys |
| W3 | One retry wrapper at every transaction owner | 1213 only on request paths (not 1205); a fresh after-commit buffer per attempt; audit/log lines written inside transaction bodies moved after commit (`ApiTokenService.scala:126`, `AccountTokenService.scala:193`, and the others); the transactions `RoundImageJdbc.inTransaction` opens covered; the file check's `settle` wrapped |
| W4 | Drop `vote.monument_id` (c) | **Deferred until measured on the app path:** `regionRateCounts` (`ImageDbNew.scala:394`, every juror gallery page) through `round_image`, and an incrementally kept per-juror count cache. Only within about +15 ms; otherwise keep the copy and rely on h6 |
| W5 | No status column instead of `vote_aside` (f) | unchanged |
| W6 | Counts by (ii), a dirty mark and a background recompute, instead of P2a's deltas | To be correct: a dirty-generation counter (or the mark cleared before the read); each window's `round_image` rows locked `FOR UPDATE` before the vote read, at RC; a per-file dirty set; the bulk writers' dirty marks persisted on the round row; exact consumers (top-N, CSV, finalize) read the votes directly. Recorded in the round-images plan's P2a item |
| W7 | Gate P1b/P3's admin actions to rounds that aren't open (g) | The status checked under the round's X lock in the action's own transaction; row-level correctness and the retry kept (a gated action can still meet a juror vote, §4 (g)). Recorded in P1b and P3 |
| W8 | Test-only writers out of the app | Plus: delete `VoteJdbc.destroy` / `Vote.destroy`; split `DistributeImages`' in-memory insert path from the filters `RoundDistributionHandler` uses |
| W9 | Targeted fixes h1–h6 | Plus: h4–h6 spiked and spec'd; h5 reuses `lockDeleted` (the candidate files share-locked after the round lock); h6 a locking S read of the image rows in page order before building the vote rows; h1 makes no difference under RC; h4 doesn't fully remove break 10, so the retry stays |
| W10 | All-jurors rated page ordered by `round_image.monument_id` | Confirmed first on the app's `deferredGroupedPage` path (the −497 ms is the narrow SQL); the inner join depends on invariant 1 |

**The guard spec.** `StructuralWritesSpec` is a raw-SQL tripwire only: it misses CRUDMapper and
QueryDSL writes (`VoteJdbc.destroy`, `ImageJdbc.updateIn`'s monument write), `${table}`
interpolation, `updateMonumentId(`, and any new method in a listed file. The runtime guard (W2)
covers these.

## 10. Files, commits, checks

- New: `test/stress/WritePathStress.scala` (`db.stress.WritePathStress`, the harness, and
  `DeadlockLog`), `test/gatling/setup/WritePathReadBenchmark.scala`,
  `app/db/scalikejdbc/RoundWrites.scala` (spikes (a) and (h), off by default),
  `test/security/StructuralWritesSpec.scala` (the writers' guard), this document.
- Commits: `68908f5c` spikes (a) and (h) behind flags, `StructuralWritesSpec`; `0e7600aa` the
  harness; `6628fd07` the read benchmark; then this document and the roadmap row.
- Runtime: about 1 h 20 min wall (06:55–08:15), of which JVM runs ~45 min: three harness
  runs (6, 6 + 1 interrupted, 4 variants of 90 s), one benchmark (3 min, the fixture cached),
  compiles and the affected specs.
- Changed (flag-gated lines only): `VoteJdbc`, `ImageJdbc`, `RoundImageJdbc`, `DeletedFiles`,
  `ImageImportHandler`, `CommonsFileCheck`, `RoundDistributionHandler`,
  `RoundImageCheckHandler`; `docs/plans/2026-11-02-roadmap.md` (a row).
- Checks (logs under `target/checks/` of the worktree): `Test/compile`; harness smoke
  `20261103-071152-14652.log`; run 1 `…-071534-28060.log`; run 2 `…-073009-28872.log`; run 3
  `…-074439-25728.log`; benchmark `…-075306-17020.log`; with the flags off,
  `security.*`, `RoundImageLockSpec`, `RoundImageJdbcSpec`, `CriteriaRateSpec`,
  `DeletedFilesSpec`, `RoundDistributionJobSpec`, `RoundImageCheckSpec`, `CoiRoundSpec`: 137
  passed, 0 failed (`…-075642-7540.log`). No full suite (no behaviour change with the flags
  off; the spikes are not for merging).
- Not done: a `GalleryLatencyBenchmark`/`VotingSimulation` pilot of RC or (a) on the app path
  (the harness's juror latencies and the per-vote measurement above stand in; to run before
  W1/W2 ship); (e) as code; (c) and (f) as migrations; the juror region menu through
  `round_image`.

## 11. Robustness batch results (W1, W2, W3, W8, W9)

Branch `ukwikidev/write-path-robustness` (worktree `C:/scala/wlxjury-robustness`) from
`ukwikidev/followups` `7a79a4bd`, 2026-11-03. Real code, no flags.

### What changed

| Item | Code |
|---|---|
| W1 READ COMMITTED | `db.default.hikaricp.transactionIsolation = "TRANSACTION_READ_COMMITTED"` in `conf/application.conf` and the tests' `application.conf`; the tests' DBCP pool through the driver's `transactionIsolation=READ-COMMITTED` URL option (`SharedTestDb.readCommittedUrl`). Ships together with (a). `CriteriaRateSpec`'s isolation example now expects READ-COMMITTED everywhere |
| W1/h4 the juror vote | `VoteJdbc.rate`: the vote id by a plain read, then in one `WriteTx` transaction `SELECT … WHERE id = ? FOR UPDATE` (the clustered row first), `round_user` (stopped?) and `round_criterion` (criteria round?) read `LOCK IN SHARE MODE`, then `UPDATE … WHERE id = ?`. In a caller's transaction it runs there |
| W2 round-first rule | `db.scalikejdbc.RoundWrites`: `lock(roundIds)` (`FOR UPDATE`, id order), `lockForFiles(pageIds, roundIds)` (the rounds of the files' `round_image` rows, then those), `lockFiles`; every other round lock records itself (`Round.lock`, `Round.isOpen`, `Round.create`'s new row, `RoundCriterion.updateIn`, `RoundConflicts`, the distribution's `lockRounds`, the repair job's `lockRound`, `ImageJdbc.lockOpenRoundsOf` and `lockRoundsOfArchived`, both now `FOR UPDATE`). Vote inserts (`create`, `batchInsert`) lock their rounds for update (was share). `RoundImageJdbc.lockRounds` removed |
| W2 runtime guard | the locks are recorded per thread and borrowed connection (by identity, weakly); the DAO's structural writers `require` them before writing: `VoteJdbc.deleteVotes`, `ImageJdbc.moveVotesAside`/`moveVotesBack`, `updateIn`/`updateMonumentIdIn` (`requireFiles`), `RoundImageJdbc.addVoted`/`sync`/`syncMonumentId`. A writer without its lock throws `IllegalStateException` before any write (`RoundWritesGuardSpec`: a delete, a monument change, a sync, a move aside, without the lock, with another round's, outside a transaction, with a lock of an earlier transaction on the pool; each with the lock passes) |
| W2 import scope | `writeImages` locks the rounds of the files to restore ∪ the changed files only (none for a page with neither), then the changed files in page order; `writeMonumentIds` the changed files' rounds; the list phase the changed stored files' rounds and its round, then the files |
| W2 break 11 | `RoundService.enqueueOrFail` refuses an active distribution by a plain read of the dedupe key under the round's lock, before the insert; `JobScheduler.enqueue`'s duplicate path reads it plainly (`lockActiveByDedupeKey` removed); the repair job's "distribution active?" is a plain read under the round's X lock |
| W3 retry | `db.scalikejdbc.WriteTx.write`/`retry`: 3 attempts, 1213 only. Used by the juror vote, `RoundImageJdbc.inTransaction` (vote inserts outside a caller's transaction), `ImageJdbc.update`/`updateMonumentId`, `Round.setActiveGuarded`/`settleDeletedFiles`, `RoundConflicts`, `RoundService`'s two enqueue transactions, the repair-check enqueue, `CommonsFileCheck.write`, `DeletedFiles.settle`, `ApiTokenService.createOnce`; `DbErrors.retryOnDeadlock` (criteria votes, criteria changes) delegates to it. Job steps keep the scheduler's retry (fresh `StepContext`, so a fresh after-commit buffer, per attempt). Log lines moved after commit: `ApiTokenService.createOnce`'s "replaced", `AccountTokenService.confirmEmailChange`'s "confirmed" (that service isn't retried; its other in-body lines stay) |
| W8 | `VoteJdbc.destroy`/`Vote.destroy` deleted; `removeImage`, `removeUnrated`, `setRound` (with `moveVotes`) moved to `test/db/scalikejdbc/TestVoteWrites` (round-first); the in-memory `DistributeImages.distributeImages` to `test/…/cmd/InMemoryDistribution` (an extension; the filters `RoundDistributionHandler` uses stay in `DistributeImages`) |
| h1 | not needed at READ COMMITTED (an `INSERT … SELECT` reads its source without locks); not applied |
| h2 | the per-file update loops lock their files in page order first (`writeImages`, the list phase) |
| h3 | `DeletedFiles.settle`: the files' open rounds first, then `lockDeleted` |
| h5 | `ImageJdbc.archiveDeletedVotesInRound` re-reads its candidates with `lockDeleted` (share-locked, after the round's lock) |
| h6 | the distribution's Distribute and List phases re-read their files' monument ids with a share lock in page order, after the round's lock, before building the vote rows (`withLockedMonumentIds`) |
| W10 | **not done**: the all-jurors page's order must stay consistent with ranks, positions and the large view's neighbours (`orderBy` keys across `deferredGroupedPage`, `pageKeys`, `keyCounts`, `position`): a P2b-sized change. The −497 ms is the narrow SQL's; moved to P2b |
| Harness | `test/stress/WritePathStress.scala` on this branch: production code paths, RC by default (`rr` for the old level), `-Dstress.chunkRows`, `-Dstress.importPage`, `-Dstress.importChanged` |

**What the guard covers (corrected after the batch review).** The runtime guard checks only
the nine guarded DAO entry points (`deleteVotes`, `moveVotesAside`, `moveVotesBack`, `updateIn`,
`updateMonumentIdIn`, `addVoted`, `sync`, `syncMonumentId`, and the vote inserts, which lock their
rounds themselves). A raw write added anywhere else would bypass it, so the source-scan tripwire
is back: `test/security/StructuralWritesSpec` fails on raw `INSERT`/`UPDATE`/`DELETE`/`REPLACE`
of `vote`, `vote_aside`, `criteria_rate`, `round_image`, `round_criterion` or a monument copy in
any method but the guarded writers and these, listed with the lock they take themselves:
- `RoundImageCheckHandler.monumentStep`: the repair job's `UPDATE round_image SET monument_id`,
  under the round's `FOR UPDATE` (`lockRound`), images share-locked first;
- `RoundCriterion.updateIn` (the round `FOR UPDATE`, then its votes) and `RoundCriterion.insert`
  (a new round's criteria in its creating transaction);
- `RoundImageCheckHandler.orphansStep`: criteria rates of no vote (their vote ids looked up
  `FOR UPDATE` first; no round has them);
- the juror's own writes, row-level by design: `VoteJdbc.rateIn`, `CriteriaRate.updateRateIn`.
The spec also fails on a listed method that no longer writes. CRUDMapper/QueryDSL writes
(`updateById`, `insert.into`) aren't scanned; where they touch these tables they are guarded at
run time.

### Follow-ups after the batch review ("sound, approve the merge")

| # | Change |
|---|---|
| 1 | **Isolation at readiness.** `ReadinessCheck.isolation()` reads `SELECT @@tx_isolation` on a pooled connection; `/ready` answers 503 and logs an ERROR (`readiness` logger) unless it is `READ-COMMITTED` (`ReadinessControllerSpec`: REPEATABLE-READ refused; `ReadinessDatabaseSpec`: the app pool reports READ-COMMITTED). `ProductionConfigSpec`: `conf/wlxjury/production.conf` inherits `db.default.hikaricp.transactionIsolation = TRANSACTION_READ_COMMITTED` from the release |
| 2 | The guard's claim corrected (above); `StructuralWritesSpec` restored as the tripwire |
| 3 | **Finding 3.** `writeImages`' `lockForFiles` reads the files' rounds from `round_image` unlocked; a round whose aside rows appeared since (a concurrent move aside) wasn't locked, and `moveVotesBack`'s `requireRounds` then failed the import job non-transiently. `moveVotesBack` now locks such rounds itself (`RoundWrites.lockMissing`, after its locked read of the aside rows): out of id order, so a deadlock it may cause is rolled back and retried (transient), unlike the refusal. Spec: `RoundWritesGuardSpec` "a restore … lock a round whose votes aside its caller's lookup missed" |
| 4 | `ukwikidev/followups` `df096837` (the MariaDB 10.11 port) merged in (`f4b780b5`, no conflicts); RC stays on the DBCP URL (`SharedTestDb.readCommittedUrl`) beside the image selection (`-Dwlxjury.test.mariadb-image`) |
| 6 | Nits: `RoundImageLockSpec`'s monument example renamed (it now waits at the file's round); the vote's id read inside its transaction on the same connection (one borrow less); the distribution's candidates read after the round lock |

**Pre-deploy checks for this batch** (production, read-only):
```sql
SELECT @@log_bin, @@binlog_format;   -- 0, or MIXED/ROW: STATEMENT with READ COMMITTED makes writes fail (C2's check)
SELECT @@tx_isolation;               -- informational: the server default; the pool sets its own
```
and confirm that the server's `production.conf` (the release's `conf/wlxjury/production.conf`
plus any local override) does not set `db.default.hikaricp.transactionIsolation`: it inherits
`TRANSACTION_READ_COMMITTED` from the release's `application.conf` (spec'd), and `/ready`
refuses otherwise.

### Harness, production-like (3 runs)

`chunkRows` 15,000, import pages of 500 stored files with 15 changed (the real scope rule),
production paths (16 threads), 90 s each, RC; log
`target/checks/20261103-103520-3024.log`, `target/stress/write-paths-20261103-103608/`.

| Run | Server deadlocks | Visible failures | Deadlocks caught | Steps retried | Invariants (round_image, criteria, monument copies, frozen rounds) | Jobs |
|---|---|---|---|---|---|---|
| 1 | 0 | 0 | 0 | 0 | all held | 235 done |
| 2 | 1 (criteria change × criterion rate, absorbed by the retry) | 0 | 0 | 0 | all held | 276 done |
| 3 | 0 | 0 | 0 | 0 | all held | 243 done |

Against the investigation's baselines (49 and 53 deadlocks, 13 and 11 visible failures, 2–11
invariant-4 rows and up to 36 stale copies) at the harness's old settings: the settings differ
(chunks 600 → 15,000, import pages 15 → 500 files), so these are not a paired comparison.
Under this stress the harness's juror votes are slower (binary p50 10–11 ms, p95 31–33 ms,
against 1.5/4–5 ms of the single autocommit `UPDATE`): the vote is now a transaction of five
statements, each a round trip to the Docker VM, with DBCP's return reset; the app path (Hikari)
below shows no such cost. Admin p50s under stress 190–460 ms (criteria change 191–220, CoI
removal 384–460, import page 322–460, file check 316–472).

### App path: Gatling on the fixture (before `9913bace` = `7a79a4bd`'s code, after this batch)

`-Dscalikejdbc.global.loggingSQLAndTime.enabled=false` in both; order A, B, B, A for
`VotingSimulation` (20 jurors, 60 s), one run each of `ConcurrentRoundCreationSimulation` (10
admins creating rounds while jurors vote and browse). Results
`target/gatling/votingsimulation-*` and `concurrentroundcreationsimulation-*` in each tree.

| `VotingSimulation`, cast vote (2,100) | mean | p50 | p95 | p99 | failed |
|---|---|---|---|---|---|
| before, run 1 | 24 | 15 | 72 | 171 | 0 |
| after, run 1 | 23 | 13 | 79 | 220 | 0 |
| after, run 2 | 32 | 20 | 97 | 195 | 0 |
| before, run 2 | 27 | 14 | 110 | 255 | 0 |

The vote's latency is unchanged within the runs' spread (p50 13–20 after, 14–15 before; p95
79–97 after, 72–110 before): no measurable cost of the transaction on the app path.

| `ConcurrentRoundCreationSimulation` | before | after |
|---|---|---|
| all ten admins' rounds done | 101.0 s | 79.7 s |
| two large rounds (38k files, 115k rows each), submit to done | 95.7, 100.9 s | 79.2, 79.6 s |
| six small rounds (region / top-N), submit to done | 24.1–42.5 s | 21.3–33.1 s |
| "New files", "Rated avg 7" | 55.3, 57.5 s | 44.2, 44.8 s |
| cast vote (3,900) p50 / p95 / p99 | 134 / 409 / 607 | 151 / 373 / 607 |
| gallery page (2,600) p50 / p95 | 6,071 / 9,888 | 2,297 / 3,083 |
| concurrent submit of the round form p50 / p95 | 273 / 392 | 197 / 239 |

One run each: the distribution and gallery gains are large against the votes' spread, but not
repeated (RC's shorter-lived read views and no gap locks under ten concurrent fills are the
likely cause); the votes are within it. Pool occupancy under import convoys (the reviewer's
W2 item) was not measured: no simulation runs Commons imports; an import step holds one
connection, and with the round-first rule a request that waits on a round row holds its
connection while it waits (at most one chunk's transaction).

### Specs and checks

Logs under `target/checks/` of the robustness worktree.

| Step | Specs | Result | Log |
|---|---|---|---|
| Compile | `Test/compile` | ok (after renaming the wrapper to `WriteTx`: scalikejdbc has a `Tx`) | `20261103-102226-16292.log` |
| Affected | `db.scalikejdbc.*`, `org.intracer.wmua.*`, `services.distribution.*`, `services.imports.*`, `RoundCreationResubmitSpec`, `security.*` | 610: 2 failed, 24 errors, all the guard refusing specs that called the DAO's writers without a round lock (the guard working); the new `WritePathLockSpec` 4/4 | `20261103-102606-6052.log` |
| Specs given their locks | `CriteriaRateSpec`, `RoundImageJdbcSpec`, `RoundImageLockSpec`, `DeletedFilesSpec`, `RoundWritesGuardSpec` | then `CriteriaRateSpec` 21/21 | `…-103138-25340.log`, `…-103353-13468.log` |
| Harness | 3 production-like runs | 0 visible failures, invariants held, deadlocks 0, 1, 0 | `…-103520-3024.log` |
| Gatling | `VotingSimulation` ×2, `ConcurrentRoundCreationSimulation` ×1 (this tree; the before runs in the investigation tree) | no failed requests | `…-104141-8876.log`, `…-104845-10852.log` |
| Full suite | `test` (Jest included) | **1775: 1766 passed, 0 failed, 0 errors, 9 skipped, 1 pending**; Jest 253/253; 9:32 | `…-114436-24748.log` |
| After the review follow-ups and the followups merge (`df096837`) | `security.*`, readiness and production-config specs, the round-write, lock, criteria, import and distribution specs | 328: 327 passed, 1 skipped | `…-123340-27812.log` |
| Full suite, 10.6 | `test` (Jest included) | **1789: 1779 passed, 0 failed, 0 errors, 10 skipped, 1 pending**; Jest 253/253; 9:08 | `…-123819-24856.log` |
| `ConcurrentRoundCreationSimulation`, a repeat of "after" | | all ten admins' rounds done 76.9 s (first run 79.7, before 101.0); large rounds 72.8, 76.9 s; small 17.9–34.3 s; gallery page p50/p95 1,672/2,219 ms (first 2,297/3,083, before 6,071/9,888); cast vote 138/353/529 ms; concurrent submit p50 381 ms (first 197, before 273). Includes the followups merge, whose V85 (a gallery index) also bears on the gallery page; the round fills' gain repeats | `…-124809-22000.log` |

The C1/C2/P1 lock specs (`CriteriaRateSpec`, `RoundImageLockSpec`, `DeletedFilesSpec`'s) run at
READ COMMITTED now (the pool's level) and pass; two of them hold the round as the writers now
do, so they wait at the round instead of at a vote row. A first full-suite attempt stopped
part-way (the background wrapper ended; no failure in its log) and was rerun.

## 12. Open items

- **A residual stale `vote.monument_id` window (found 2026-11-05 in P2a's stress runs, round
  images plan §16).** h6 is built (the image rows share-locked in page order before the vote rows
  are built), yet one of P2a's four 90 s harness runs (branch #1) ended with 3 vote rows whose copy
  differs from `images.monument_id`; the other three, both base runs on `04c1d310` (before P2a)
  and a third base run (timing spoiled, below) had none. P2a writes no monument id, so the window
  predates it: some writer still builds or keeps a vote's copy from a read a monument change can
  pass. Not yet found. The harness now prints the offending rows (table, round, page, juror, the
  vote's and the image's ids) when the invariant fails, for the next run that shows it. W4 (drop
  the copy) would remove the class.
- **A harness run spoiled by the host's sleep (2026-11-05).** Two more base runs
  (`-Dstress.seconds=90 -Dstress.variants=baseline#3,baseline#4` on `04c1d310`) ran from 01:13
  to 07:41 and were stopped by hand, still logging deadlocks and `job.error.count.mismatch`.
  Not the harness: Windows went to sleep at 01:15:46 (Kernel-Power event 42), woke and slept
  again at 04:15, and woke at 07:40:40, while the harness's run timer (a `Thread.sleep` of the
  run's seconds) and its operations measure wall time. `baseline#3` then ended as usual
  (`target/stress/write-paths-20261105-011407/`: 23,185 s "elapsed", every path's longest
  operation 12,298 s or about 23,100 s, the two sleeps; operation counts of a normal 90 s run; 3
  server deadlocks; invariants clean, no stale monument copy) and `baseline#4` started: it was
  what kept running, its distribution jobs failing with the count mismatch every run has (break
  7, against the test-only `remove_image`/`set_round`). The harness's end does not wait for
  retried jobs (it stops the paths' threads, joins each for 180 s at most, then checks), so
  nothing in it was changed. The two extra base runs were not obtained. The AC power had dropped
  at 01:15:40 and the laptop, its lid closed, applied the on-battery lid action 6 s later (the
  user changes that action; agents don't touch power settings).
  *Since the run-hardening batch:* the harness marks such a variant **INVALID** (summary and
  `results.json`: `valid`, `invalidReason` "host slept or clock jumped") when its `nanoTime`
  elapsed time exceeds `stress.seconds + 300` or HikariCP logged a clock leap during it
  (`WallTimeCheck`, `WallTimeCheckSpec`; testing practices §8.1b); `agent-check.py` stops a
  run's whole process tree at `--timeout` (exit 124) and, on Windows, holds a keep-awake
  request while it runs, which stops idle sleep but not a lid action.
- **Two job-scheduler findings of the same night's investigation, fixed in the run-hardening
  batch.** (a) A running job whose lease expired was claimed again every lease forever when each
  run ended without recording an outcome (a crash `fail()` couldn't write, `JobScheduler.fetch`/
  `work`/`fail`, or a step that ends the JVM, R198-1): `attempts` was counted, never checked.
  Now after `jobs.maxAttempts` claims (default 5, counted again from a Retry) the next poll fails
  it with `job.error.attempts` and its handler's cleanup (without it if that fails too)
  (`JobSchedulerSpec`). (b) A distribution's Retry after a failure other than the count check
  kept its old `imagesBefore`, so a change in the round's other files while it was failed (a file
  removed, a repair) failed the next count check once for nothing. Now the Retry moves
  `imagesBefore` by the change in the round's rows the job didn't add (`othersBefore`,
  `round_image.added_by_job_id`): its own files that went missing still fail the check
  (`RoundDistributionJobSpec`).
  *Review follow-ups:* only the job page's explicit Retry resets `attempts`; the round form again
  and "Distribute new files" keep it and refuse a given-up job (`job.retry.attempts`,
  `round.distribute.attempts`, `round.resubmitted.attempts`, with its reference); a graceful
  handback (`requeueOwned`) gives its claim back (`GREATEST(attempts - 1, 0)`; a separate counter
  would have needed a migration for the same effect); a give-up that throws is logged per job and
  no longer aborts the poll. The rebaseline moved to the resumed job's first step (a change while
  it waits in the queue is in the window), and the count check also requires exactly `ownBefore +
  imagesAdded` rows with the job's id, so a lost own file can't hide behind another's added one.
  A file it added that was removed and added again by another job fails that check once; the
  Retry rescans, skips the file (it has a row) and passes: nothing lost or doubled.
