# Background, Chunked Image Distribution Plan (October 2026)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Distributing images to a round no longer runs inside the HTTP request, and no longer holds a thread, a transaction or the whole candidate set in memory for the entire distribution.
- Many admins can create rounds at the same time: several special-nomination admins after a coordination call, admins of different countries' contests whose deadlines coincide, or a training session for new admins.
- A small round finishes in seconds even while a big first round is running.
- Votes and galleries stay fast throughout.

**Builds on:** branch `ukwikidev/performance-memory` (PR #183) at `d616319` (Task 12 done). The entry point to run in the background is `RoundService.fillRound(roundId, completingCreation)`. It takes only the round id; it's the single fill path for a new round, a resumed creation and "Distribute new files"; and it runs under a per-round lock striped over 64 locks, with active jurors only and the previous rounds frozen. `RoundService.submit(round, jurorIds, token)` returns `Created` / `Resubmitted`; the token is `rounds.submit_token` (V59). In particular:
- the one-time submission token;
- "resume = continue the same round" (`d616319`);
- active-jurors-only distribution (`17db12b`);
- chunked selection inserts (`467fd11`);
- the per-round image-count cache `RoundImageCounts`;
- the RoundDistribution Gatling simulation (Task 2b of `docs/plans/2026-10-05-performance-and-memory.md`).

Read those first.

**Why (findings, 2026-10-06):**
- **Creating a round runs the whole distribution inside `POST /admin/rounds/save`,** on the blocking dispatcher (8 threads, `b46fafd`). For the 38k-image fixture that's 5–6 s locally, more on the 2-CPU production server, and about a minute for 40k images × 20 jurors.
  - A few concurrent creations plus "Count new files" (Commons API calls) can occupy all 8 threads; galleries and `/roundstat` then stall.
  - Long requests also depend on Play's `idleTimeout` (5 min) and Apache's `ProxyTimeout` (60 s default).
- **The whole distribution is one transaction** (all chunks or none). It holds locks and undo for its full length, and the previous rounds stay frozen the whole time.
- **The whole candidate set is loaded into memory:**
  - every contest image (`findByContestId`), or every previous-round selection merged per image (`byRoundMerged` + `mergeByPageId`);
  - plus the **whole target round**, only to exclude its existing images (`getFilteredImages`: `imageRepo.byRoundMerged(round.getId)`).
- **Review finding (unfixed):** `DistributeImages.addCriteriaRates` runs `CriteriaRate.batchInsert` outside the transaction and unchunked. That is 4× the selection rows, so it hits the same `max_allowed_packet` failure that chunking fixed for selections, and a failure doesn't roll it back.

**Design summary:**
- **A distribution is a job** made of small **chunk tasks**, scheduled in turn across all running jobs on a few worker threads.
- **Each chunk does the whole cycle:** read the next keyset page of candidates, filter it with the existing filter chain, assign jurors from a running position, insert its selection and criteria rows, and commit.
- **Until the job completes and its count check passes,** the round stays in a hidden "distributing" state that jurors never see.
- **Resume is idempotent:** existing images are excluded by an anti-join.
- **Top-N rounds use one ranking query in SQL.**

---

## Task 13.1: Job model and status: a generic `job` table (migration + DAO)

> **Design change (maintainer, 2026-10-07):** other features will need background jobs too (fetching images from Commons, fetching monuments from the monument lists), so job state lives in a **generic `job` table** with generic scheduler code, not a distribution-specific table. Only the `round_distribution` kind is implemented now.

- **`job` table** (`V60__Background_jobs.sql`), one row per job, kept as history:
  - `id`, `kind` (`round_distribution` now; later `commons_image_import`, `monument_list_import`, …);
  - `contest_id` (nullable): listing, permissions, and fairness between contests;
  - `subject_type` + `subject_id`: what it works on (round 42, contest 7);
  - `status`: `queued` / `running` / `failed` / `done` / `cancelled`;
  - `params` (JSON): kind-specific inputs, fixed at creation. A distribution stores the round id, `completingCreation` and a snapshot of the round's settings, for the record: the job reads the round itself, whose settings don't change after creation;
  - `state` (JSON): the kind-specific resume cursor, written in the same transaction as each step's work. A distribution stores its phase, the keyset cursor (last `page_id`), the juror position, counters, the count before the job, the ranked top-N ids and the previous rounds it holds frozen;
  - `progress_done`, `progress_total` (nullable: unknown for some kinds), `progress_unit`;
  - `error`, `attempts`, `created_by`, `created_at` / `started_at` / `updated_at` / `finished_at`;
  - `owner` + `heartbeat_at`: a lease (13.4).
- **One active job per subject, in the database:** a `dedupe_key` (e.g. `round_distribution:round:42`) and a STORED generated column `active_dedupe_key = IF(status IN ('queued','running'), dedupe_key, NULL)` with a UNIQUE index (MariaDB 10.6 supports it; verified by `JobSchedulerSpec` on the 10.6 test container). A second request for the subject fails its insert and attaches to the active job. A **failed** job is not active: it doesn't block the subject, and the round service resumes it (Retry) instead of creating another.
- **Indexes:** `(status, kind)`, `(contest_id, created_at)`, `(subject_type, subject_id)`, the unique `active_dedupe_key`.
- **Round visibility stays on the round** (decision): `rounds.distributing` (V60). A round being created is inserted with `distributing = 1` and `active = 0`, and the job's final step, after the count check, sets `distributing = 0, active = 1`. While `distributing`:
  - juror-facing queries hide it: `Round.activeRounds` (round switcher, `/roundstat` redirect) requires `active AND NOT distributing`; the gallery refuses it to non-organizers even with `juryOrgView`;
  - it can't be started: `Round.setActiveGuarded` (the start/stop button and the edit form's save, which sets `active = true`) refuses;
  - votes are refused: `selectWS` (POST /rate) now requires `round.openToJurors` (`active && !distributing`), as the large view already required `active`. **Behaviour change:** votes on a stopped round, and on a previous round frozen during a distribution, are refused too (409). The freeze never stopped votes through `/rate` before.
  - It **stays set when the creation fails or is cancelled**, so a partial round is never shown; finishing it (Retry, or "Distribute new files", whose job completes the creation because the round is still `distributing`) clears it.
  - Why a column and not "has an active job": a failed or cancelled creation has no active job, yet its round must stay hidden; and the round row is what every juror-facing check already loads.
- **Freezing is counted per round** (`rounds.freeze_count`, `active_before_freeze`): see 13.3.
- **The submission token (Task 12)** maps a resubmit to the round, and so to its latest job: a resubmit of a running job shows its progress, of a failed job resumes it.

## Task 13.2: Streamed candidates (no full-set load)

- **Keyset-page the source by `page_id`** (`… WHERE page_id > :cursor ORDER BY page_id LIMIT :chunk`):
  - **First round of a contest:** `category_links` ⋈ `images` for the contest's category.
  - **From previous rounds:** their selections, grouped per `page_id` in SQL. This replaces `mergeByPageId`: sum/count of rates and juror counts per image across all previous rounds.
- **Exclude images already in the target round with `NOT EXISTS`** in the page query, instead of loading the round. That is what makes resume idempotent.
- **Apply the existing `ImageWithRatingSeqFilter` chain to each chunk,** keeping its semantics and tests. All filters except top-N decide per image:
  - region and monument, page ids and titles, jurors;
  - megapixels and size, media type, special nomination;
  - Commons category include/exclude, `selectedAtLeast`, minimum average rating.

  Pushing filters into SQL is a later, measured optimisation, not part of this task.
- **Commons category include/exclude:** fetch the file-id set once per job, paging the API as today, and keep it as `Set[Long]`. Keep this blocking API call **off the request thread** and inside the job.
- **Top-N by rating** (`SelectTopByRating`) needs a global ranking. Do it as **one SQL ranking query** over the grouped previous-round ratings, with the same rating and scaling semantics as the Scala filter. Use `ORDER BY` plus `LIMIT N` and the same tie-breaking, with the per-image filters applied as SQL predicates where they are simple, or applied before ranking otherwise.
  - Only N images are held, then distributed in chunks.
  - Verify equivalence with the Scala filter on the fixture, and add a spec.
- **Juror assignment streams:**
  - With `distribution = x`, image *i* gets jurors `(i + j) % n`. Carry *i* (the running position) in the job row so a resume continues the rotation.
  - With `distribution = 0`, every juror gets each image.
  - Replace the whole-list duplicate check with an up-front check that the jurors are distinct; chunk keys are unique per image.
- **Ordering:** keyset order is by `page_id`, not today's order, so which juror gets which image changes. The per-juror balance stays the same. Update the specs that pin exact assignments, and state this in the PR.

## Task 13.3: Chunk transactions, freezing, verification

- **Each chunk is its own transaction:**
  - insert its selection rows (and criteria rates);
  - advance the job's cursor, position and counters;
  - commit.

  If a chunk fails, it rolls back alone; the job becomes `failed` with its cursor at the last committed chunk.
- **Criteria rates are written in the same chunk transaction, chunked.** This fixes the review finding.
- **Freeze the previous rounds** (`active=false`) for the whole job, as creation does today. Once the job finishes, fails or is cancelled, restore the state they had **before the job**. Reads across chunks then stay consistent. Previous rounds are normally closed once the next round starts anyway.
- **Completion:**
  - run `distributeAndVerify`'s exact count check over the whole round (expected = images added by this job + images already present);
  - then activate the round, invalidate `RoundImageCounts`, and mark the job `done`.
  - A count mismatch marks the job `failed`; a retry resumes it.

## Task 13.4: Generic scheduler and workers (`services.jobs`)

- **`JobHandler` per kind:** `step(job, ctx)` does one step (one chunk) in the transaction of `ctx`, with the job's row locked (`SELECT … FOR UPDATE`), and returns `Continue(state, progress)`, `Done(…)` or `Failed(error, …)`; the scheduler writes the state, progress and status in that same transaction. Hooks, each in the transaction of its status change: `onFailed`, `onCancelled`, `onRetry`. `prepare(job)` runs before each step **outside any transaction** (the place for external calls whose result isn't in the state, e.g. a Commons category's file ids), `forget` drops its cache. `details(job)` adds kind-specific figures to the status JSON. A step that throws rolls back and fails the job; deadlocks and lock-wait timeouts are retried (3 times) first.
- **`JobScheduler`:**
  - its own small thread pool (`jobs.workers`), separate from Play's dispatchers and from `blocking-dispatcher`;
  - **turns:** a worker runs one step of one job, then the job goes back in line. The next step goes to the **contest with the least step time so far**, and within it to the job with the least (a fair share by "virtual time"): a job whose steps are cheap gets more of them, so a small job finishes while a big one runs, and one contest's many jobs can't starve another contest's. A new job or contest starts level with the least served one;
  - **per-kind concurrency limits** (`jobs.concurrency.<kind>`, e.g. distribution 2; Commons imports would get 1–2 for the API's rate limits), under the global `workers`;
  - **lease, heartbeat, restart recovery:** every `pollInterval` (2 s) the scheduler claims queued jobs of its kinds, and running jobs whose `heartbeat_at` is older than the `lease` (60 s: their instance died). It renews the heartbeat of the jobs it holds every `heartbeatInterval` (10 s), and each step renews it too. A graceful stop finishes the running steps and hands its running jobs back to `queued`, so the next instance takes them at once; after a crash they are taken over when the lease expires. Each step checks under the row lock that this instance still owns the job, so two instances (a blue-green switch) never run one job;
  - **cancel** (`queued`, `running` or `failed` → `cancelled`) takes the job's row lock, so it waits for the running step to commit (a chunk: well under a second) and the job then stops;
  - **retry** (`failed` → `queued`) runs the handler's `onRetry` and resumes from the committed state; refused if another job of the subject is active;
  - **inline mode** (`jobs.mode = inline`: tests, scripts): no threads; a job is claimed when created and run to its end by the call that starts it.
- **Chunk size and workers:** see the 13.7 measurements; the defaults are `jobs.workers = 2`, `jobs.concurrency.round_distribution = 2`, `jobs.roundDistribution.chunkRows = 15000` (selection rows per step; the window holds rows / jurors-per-image candidate images, at most 20,000).
- **Top-N jobs rank in their first step** (phase `rank`), storing the chosen ids in the state.
- **Cancel keeps the rows** (decision): deleting them would be another long write as big as the insert, needing its own chunked job, and they are harmless: the round stays hidden (`distributing`) and a later "Distribute new files" continues from them (the anti-join skips them) and completes the creation. The previous rounds are restored.
- **Generic endpoints:** `GET /admin/jobs/:id` (status JSON), `POST /admin/jobs/:id/cancel`, `POST /admin/jobs/:id/retry`, for the admins of the job's contest (root for a job without one).

## Task 13.5: Requests and UI

- **`POST /admin/rounds/save`** creates the round (hidden), its jurors and its job, and freezes its previous rounds, in one transaction, and **returns 303 to the rounds list at once**; "Distribute new files" on an existing round creates (or resumes a failed) job the same way. A resubmit of a failed creation resumes its job; of a running one, shows it (flash message). Bad input is still rejected before anything is created, re-rendering the form.
- **"Count new files"** runs the job's own windows and filters read-only, on a single thread of its own (`count-new-files`), off `blocking-dispatcher`; concurrent counts queue there. It is still an HTTP request that waits for the count (seconds on a big contest); making it a job kind is possible later.
- **Rounds list and edit page:** a reusable status component (`app/views/snippets/jobStatus.scala.html` + `public/javascripts/job-status.js`, same conventions as `cache-status.js`, Jest-tested) shows queued (with the number of queued jobs ahead), running ("Distributing images to round 7: 40,000 / 115,101 images (34%), 12,345 added…"), failed (with the error and **Retry**), cancelled, done; **Cancel** for queued, running and failed jobs. It polls `GET /admin/jobs/:id` every 3 s while the job is active, and reloads the page when it finishes so the counts and the round's state show.
- **Permissions:** the round form's contest checks as before; the job endpoints check the job's contest.

## Task 13.6: Tests

- **Job lifecycle:** create → done, a failure mid-way → failed with a cursor → resume → done, cancel, restart recovery.
- **Resume is idempotent:** no duplicate selection rows, and the juror rotation continues.
- **Round visibility:** a distributing round is invisible to jurors and accepts no votes; it's active after completion.
- **Previous rounds:** frozen during the job, and their prior state restored after done, failed and cancel.
- **Equivalence with today's behaviour:**
  - for each filter combination covered by `DistributeImages` / `ImageWithRatingSeqFilter` specs, the streamed job selects the same image set as the old full-list path;
  - top-N matches the Scala filter, ties included;
  - per-juror counts are balanced.
- **Criteria rounds:** criteria rates are written in the chunk transactions and roll back with them.
- **Concurrency:** at most one active job per round, even with concurrent submits; with several jobs in parallel, all complete correctly.
- **Memory:** a job over the full fixture holds at most about one chunk of candidates, as asserted via HeapWatcher in the benchmark.

## Task 13.7: Multi-admin benchmark

- **New Gatling scenario `ConcurrentRoundCreationSimulation`,** or an option of RoundDistribution: 5–10 admins create a mix of rounds at the same moment, while VotingSimulation's jurors vote and JurorGallery browses:
  - 1–2 large first rounds from the contest category;
  - several special-nomination rounds;
  - top-N rounds from the rated round;
  - a "distribute new files".

  It needs more organizer accounts, and possibly special-nomination fixture data; see `GatlingDbSetup.ensureExtraOrganizers` and the fixture cache key.
- **Measure:**
  - per admin, the time from submit to "done" (the status endpoint);
  - the time small jobs spend waiting behind large ones;
  - vote and gallery latency, against the Task 11 / 12 numbers;
  - heap after GC, with the production JVM settings (`-Xmx1g`, plus a run at `-Xmx512m`), one GC log per JVM (`gc-%p.log`).
- **Choose** the worker count and chunk size from these runs, and record them in this plan.
- **Also re-run the RoundDistribution simulation** (all four steps), to compare single-job time with the old in-request path.

### Results (measured 2026-10-07, this Windows laptop on mains power, production JVM flags)

JVM: `-XX:ActiveProcessorCount=2 -Xmx1g -XX:MaxDirectMemorySize=128m -XX:G1PeriodicGCInterval=60000 -XX:+ExitOnOutOfMemoryError`, GC log per JVM. The fixture's MariaDB container is not CPU-limited (the laptop has 8 logical CPUs); its buffer pool is 512 MB. Single runs; run-to-run noise on the latencies is about ±30%.

**The scenario** (`ConcurrentRoundCreationSimulation`): 10 admins submit at the same moment: 2 large first rounds (38,367 images × 3 jurors = 115,101 rows each), 4 region rounds of 1,500–2,000 images (as special nominations: a full scan of the category), top 100 and top 500 of the rated round, the rated round's images averaging 7+ (2,000 images), and "Distribute new files" on the binary round (nothing new: a full scan). Meanwhile jurors vote and browse in the binary round (20 sessions per 10 s each, for 120 s). Special nominations themselves need the monument lists from the wiki (`SpecialNominationFilter`), so region rounds stand in for them: their cost (a scan of every candidate, few pass) is the same.

**Workers and chunk size** (1g, submit-to-done in seconds; "small" is the 8 small jobs' range; latencies in ms, mean / p95, while the jobs run):

| workers / chunk rows | large (2) | small (8) | everything | gallery | vote |
|---|---|---|---|---|---|
| 1 / 15,000 | 60 | 11–41 | 60 | 1839 / 2408 | 128 / 226 |
| **2 / 15,000** | 44 | 5–25 | 44 | 1805 / 2574 | 81 / 149 |
| 2 / 5,000 | 44 | 8–36 | 45 | 2742 / 3878 | 187 / 318 |
| 2 / 50,000 | 37 | 19–25 | 38 | 3361 / 4492 | 472 / 907 |
| 3 / 15,000 | 30 | 6–16 | 30 | 2701 / 3545 | 243 / 544 |

The same small jobs **alone**, one after another: 0.6 s (top-N) and 0.9–2.6 s (the scans). With the first scheduler (turns by "served longest ago") the small jobs took 18–37 s, as long as the large ones: a region round's steps scan as many candidates as a large round's, so equal turns finish them together. Turns by step time (a fair share, `c6ce49a`) brought them to 5–25 s.

**Chosen:** `jobs.workers = 2`, `jobs.concurrency.round_distribution = 2`, `jobs.roundDistribution.chunkRows = 15000`.
- 15,000 rows: the smallest chunk without a cost; 5,000 rows didn't finish sooner and made each step's fixed cost (window, count, job row) a bigger share; 50,000 rows made the steps (transactions) long, and votes waited behind them (p95 907 ms).
- 2 workers: 3 finished sooner on this laptop, whose database has 8 CPUs, at the price of votes and galleries; production has 2 CPUs shared by the app and MariaDB, where a third concurrent writer only queues. 1 worker was slower without sparing the votes or the galleries.

**Against the old in-request path** (`d616319`, the same scenario, 1g, back to back): the 10 POSTs held 8 blocking-dispatcher threads, distributing 8 rounds at once. Everything finished in 21.5 s (small rounds 2.8–5.4 s, large 20–21.5 s); meanwhile the gallery took 2415 / 4434 ms, votes 87 / 271 ms, and the organizers' requests waited up to 21 s. With jobs: 44 s for everything, small rounds 5–25 s (region rounds 15–17 s), gallery 1805 / 2574 ms, votes 81 / 149 ms, and every request (submit 156 ms mean, status polls 28–78 ms) returns at once. On this laptop the old path's 8-way parallelism finishes sooner, because its database has 8 CPUs to spare; on the 2-CPU server it would put 8 distributions on 2 CPUs and block the request threads.

**Vote and gallery latency, the reference:** the jurors alone (no admins): gallery 217 / 636, vote 23 / 62. (The Task 11 / 12 numbers, gallery 136 / 399 and vote 9 / 16, were measured with each scenario alone.) After the jobs end, the gallery stays at 580–820 ms for the rest of the run, with the old path as with jobs: the 270k new selection rows and their indexes push the gallery's pages out of the fixture's 512 MB buffer pool. So most of the gallery's slowdown is the database writing and reading as much as it does, not where the distribution runs; a production buffer pool bigger than the selection table avoids the after-effect.

**Heap after GC (max), 0 full GCs in every run:**
- 10 admins, 1g: 131–161 MB (old path: 328 MB);
- 10 admins, 512m: 200 MB; everything done in 34.6 s, gallery 2180 / 2949, vote 167 / 301 while the jobs ran;
- single job (RoundDistribution, 512m): 62–64 MB per step (old path: 116–462 MB).

**RoundDistribution, one job at a time** (512m, back to back with `d616319`; seconds, submit to done):

| step | d616319 (in the request) | jobs | brief's baseline |
|---|---|---|---|
| first round from the category (115k rows) | 5.9 | 6.5 | 6.1 |
| from the binary round (115k rows) | 5.2 | 6.2 | 5.8 |
| top 100 of the rated round | 0.78 | 0.49 | 0.68 |
| "distribute new files", nothing new | 0.86 | 0.58 | 0.92 |

A single large job is 10–20% slower: 8 steps, each with its window query, the job row and a commit, plus up to 0.2 s of polling. The top-N and the no-op top-up are faster: one ranking query; no load of the round.

## Future job kinds (not implemented)

How the next kinds would map onto the generic model:

- **`commons_image_import`** (fetch a contest's images from a Commons category):
  - subject: the contest (`subject_type = contest`), `dedupe_key = commons_image_import:contest:7`; concurrency 1–2 for the API's rate limits;
  - `params`: the category, the contest id, options (e.g. whether to refresh existing images);
  - `state`: the API's `continue` token (`gcmcontinue` and friends) and counters (pages fetched, images inserted, updated);
  - a step: one API page. The external call can't run inside the database transaction (a slow API would hold the job's row lock and a connection), so the step fetches the page with the token from the state **before** opening its transaction (the handler's `prepare`, or a two-part step), then upserts the page's images and stores the next token in one transaction. A crash between the fetch and the commit refetches that page: the upsert is idempotent;
  - progress: pages or images so far; the total is often unknown (`progress_total` NULL), or the category's size from one cheap `categoryinfo` call.
- **`monument_list_import`** (fetch monuments from the monument list pages):
  - subject: the contest (or the monument list), `dedupe_key = monument_list_import:contest:7`;
  - `params`: the list's root page or template, the region filter;
  - `state`: the index of the next list page (and the list of pages, fetched by the first step), counters;
  - a step: one list page, fetched outside the transaction, its monuments upserted with the next index in one transaction;
  - progress: list pages done / total.
- Both get a handler class and one line in `JobSchedulerProvider`; the table, scheduler, endpoints and status component are reused unchanged.

---

**Out of scope:**
- pushing the filters into SQL (beyond top-N);
- the MariaDB upgrade (being evaluated separately);
- the other open review findings of PR #183.

**Rules:**
- branch `ukwikidev/background-distribution` from the final `ukwikidev/performance-memory`;
- one commit per logical step;
- the full `sbt test` must pass;
- run Gatling with the production JVM flags;
- no server changes.
