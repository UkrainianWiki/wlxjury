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

## Task 13.1: Distribution job model and status (migration + DAO)

- **Where job state is stored:**
  - **Option 1:** a `distribution_job` table, with one row per job: round id, kind (create / new files / resume), status (`queued`, `running`, `failed`, `done`, `cancelled`), totals and progress (`candidates_seen`, `images_added`, `rows_added`), the keyset cursor (last `page_id`), the juror round-robin position, the error message, who started it, and timestamps.
  - **Option 2:** status columns on `rounds`.

  Prefer the table: it keeps history, and a round can have several jobs over time (create, then "new files"). Enforce **at most one active job per round** in the database, e.g. a unique key on `(round_id, active_flag)` where only active jobs set the flag, or an equivalent. Justify the choice.
- **Round visibility:** a round with an active create/resume job is inactive and hidden from jurors (gallery, voting, round switcher). Decide the round state explicitly, e.g. the existing `active=false` plus "has an active job", or a new `rounds.state`. Make sure every juror-facing query respects it.
  - **Check:** `selectWS` (POST /rate) currently accepts votes on inactive rounds (a side issue). Votes must not land on a round that is still distributing.
- **The submission token (Task 12)** maps a resubmit to the round, and so to its job: a resubmit of a running job shows its progress; of a failed job, it resumes the job.

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

## Task 13.4: Scheduler and workers

- **A small worker pool runs chunk tasks,** taking turns between active jobs (round-robin, one chunk each). That gives fairness across admins and contests and lets small jobs finish fast.
  - Workers: configurable, default about 2–3 (CPU-bound on a 2-CPU server shared with MariaDB).
  - Chunk size: configurable, default 5000 images, or rows: pick and justify.
- **The pool is separate** from Play's default dispatcher and from `blocking-dispatcher`, so distribution can never starve request handling.
- **Top-N jobs run their ranking query as their first task.**
- **Cancellation** between chunks: the job becomes `cancelled`, the rows inserted so far are removed or kept for resume (decide and document), and the previous rounds are restored.
- **Restart recovery:** on application start, jobs left `running` or `queued` (after a crash or deploy) are re-queued and resume from their cursor, idempotently thanks to the anti-join.
- **Single instance:** jobs live in one app instance, as today. The database's one-active-job-per-round rule keeps two instances, e.g. during a blue-green switch, from running the same round twice.

## Task 13.5: Requests and UI

- **`POST /admin/rounds/save`** (create, and "distribute new files" on an existing round) **creates or resumes the job and returns 303 immediately** to the round's page, or the rounds list. Nothing long runs on a request thread any more, including the "Count new files" Commons calls; consider making that a job too, or at least moving it off `blocking-dispatcher`.
- **Rounds list and round edit page:**
  - show the job status: queued (with its position), distributing (with progress such as "40,000 / 115,101 images"), failed (with the error and a **Retry** button that resumes), done;
  - add a **Cancel** button for queued and running jobs;
  - refresh progress with a light JSON endpoint (`GET /admin/rounds/:id/distribution`) polled every few seconds while a job is active. Follow the existing JS and CSP conventions.
- **Permissions:** the same contest checks as `saveRound`, including Task 12's and the user's contest checks.

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
