# Round editing: review before distribution, adding a juror, reassigning unrated files

**Status:** decided. Written 2026-11-16 from the maintainer's request of the same day. Revised after
an independent review and the maintainer's direction (v3: **build what is already decided, plan
only what is new**), and after a follow-up review (v4). All decisions were taken by the maintainer
the same day (v5, §13, marked **[user]**). Nothing is built.

Marks: **[user]** decided by the maintainer; a default is this plan's, open to the maintainer.

**What is already decided, and taken as it is:**
- **Seeing a round's images before jurors get them is P3** of
  [`2026-11-02-round-images-two-step.md`](2026-11-02-round-images-two-step.md), as decided:
  D1–D20, D16a, the write-path rules W2/W7, §6 "The two-step flow" and §9 "P3".
  - This plan adds only implementation notes for what changed in the code since (§6), and a PR
    split (§3).
- **`round.status`** is built as decided in
  [`2026-11-10-round-lineage.md`](2026-11-10-round-lineage.md) §2.6. P3's status was already
  brought forward there; this batch builds it as P3's second PR (§6).
- **Vote-moving writes run only on rounds that aren't open:** the write-path plan §4 (g) and P3's
  "Write-path rules" (the user, 2026-11-03). Adding and reassigning follow it by pausing the round
  (§5).

**What is new:** adding a juror to a running round, and reassigning a juror's unrated files. The
parity plan had them only as gap rows 12–13.

**Sources:** read-only survey of `stack/160-large-image-size` at `683e65f5` (#343); the plans
above, `2026-11-02-montage-rounds-parity.md` (§1 mid-round, §3 gaps 11–15),
`2026-11-03-write-path-simplification.md` (§4 (g)) and `2026-11-02-roadmap.md` ("Migration
numbers"). References are to files and functions; line numbers drift. Measurements: read-only
SELECTs on the local production copy (`wlxjury-rehearsal2`, §12), which is now off by the
maintainer's instruction.

**The request (2026-11-16):**
1. See a round's images before they are distributed to jurors.
2. Add another juror to an existing round; the round's unrated images are spread evenly over the
   jurors, the new one included.
3. (As requested; narrowed by D2.) Suspend or remove a juror who can't continue: their unrated images go to the other jurors. The
   admin chooses to keep the images that juror already rated (their votes stay), or to unrate them
   and give those to the other jurors too.
   - **[user]** This batch builds only the first part: unrated files are reassigned and votes
     always stay. Discarding votes is deferred (§8).

## 1. Goals and non-goals

**Goals**
- **Add a juror** to a running round. The round's unrated work is spread evenly, with the new juror
  included.
- **P3 as decided:** fill, review, distribute first; then remove/put back, re-apply and the
  threshold preview.
- **Reassign a juror's unrated files** to other jurors in a running round. Their votes stay, and the
  juror ends stopped.
- For each move:
  - no image goes to a juror who already has a row for it, in `vote` or `vote_aside`;
  - before confirming, the admin sees the figures ("Juror X has 340 unrated and 120 rated files.
    The 340 go to 4 jurors, about 85 each"), and afterwards the counts actually moved;
  - the job row and a log line record it.

**Non-goals**
- Discarding a juror's votes. Deferred, not rejected (§8).
- Changing the jurors per image (`round.distribution`) of a running round: gap 14.
- An audit table (`round_event`, gap 15).
- Swapping two jurors in one action: it is "add Y", then "reassign X".
- Finalize, and lineage's map.
- Rounds without `round_user` rows (legacy): refused.
- Rounds that aren't running: refused (§13 D3); the admin starts the round first.

## 2. What exists today (code survey)

**Jurors of a round**
- `round_user` (V37) has `(user_id, round_id)` unique, `role` and `active`. Production's table has
  no FK to the round (lineage §2.6).
- `Round.addUsersIn` is called only at creation (`RoundService.insert`).
- **No UI adds a juror to an existing round:**
  - the edit form's jurors select is hidden (`editRound.scala.html`, `noEdit`);
  - `saveEdit` ignores `editForm.jurors`;
  - `Round.updateRound` writes only the name, `average_rule` and `worst_mark`.
- **Start/stop of one juror:** `RoundController.setRoundUser` → `RoundUser.setActive`, an
  autocommit update with no round lock (contest check since `b36876bf`). It is offered in the round
  stat table (`roundStatTable.scala.html`, admins only: `mayPause`), on the edit page and on
  `/roundstat/:id`.
- A stopped juror:
  - may not vote: `VoteJdbc.rateIn` reads the inactive row in share mode, and `selectWS` answers
    409 `round_closed`;
  - doesn't see the round (`Round.activeRounds(user)`, `contestsWithOpenRound`,
    `GalleryService.isNotAuthorized`);
  - gets no new files: `User.distributionJurors` lists active jurors only, except in a round with
    no `round_user` rows, where it falls back to the jurors with votes;
  - **keeps their unrated rows**: those images stay one juror short until the juror is started
    again;
  - still counts in every result: `AverageRule`'s doc, and the reads use `vote` without
    `round_user`.

**Distribution and jobs**
- `RoundDistributionHandler` (kind `round_distribution`, dedupe key `round_distribution:round:R`)
  is the only fill path. Its hooks freeze and release the previous rounds.
  - It reads its jurors once per job run (`context`; `forget` on Retry).
  - `Verify` calls `Round.completeCreation` (`UPDATE round SET distributing = 0, active = 1`) as
    soon as the count check passes. **No point exists where a filled round waits** (P3 adds it).
- **Readers of the round's latest `round_distribution` job** (`JobJdbc.latestBySubjects`):
  - `RoundService.distributionJob`, used by `distributeNewFiles` (which retries a failed job) and
    by `resubmitted`;
  - the rounds list's job column (`RoundController.rounds`);
  - lineage's backfill and decision 7.
- **Readers of the active dedupe key** (`job.active_dedupe_key`, V60, unique across every kind):
  - `RoundService.enqueueOrFail`;
  - `RoundConflicts.gate`;
  - `RoundCounts.roundWindow` (`RecountStep.Distributing`);
  - `RoundDistributionHandler.distributingNow` / `excluderBusy`;
  - the repair job's `lockRound`.
- `DistributeImages.assign` rotates over the active jurors in id order. "Distribute new files":
  - starts at `position = 0`, so small batches favour the lowest-id jurors;
  - enqueues with `completingCreation = round.distributing`;
  - takes only files with no `round_image` row of any status.
- Staff may open a `distributing` round's galleries (`GalleryService.isNotAuthorized`); its jurors
  may not.
- `JobScheduler`:
  - `jobs.workers = 2`;
  - `defaultConcurrency = 1`;
  - jobs take turns one step each;
  - `giveUpIfExhausted` records a failure without `onFailed` if that cleanup throws.

**Votes and `round_image`**
- `vote(page_id, jury_id, round_id)` is unique. Unrated means `rate = 0`:
  - binary rounds: `1` yes, `-1` no;
  - rated rounds: `-1` is the ✗ (V92);
  - criteria rounds: a vote with a criterion rated above 0 holds the juror's work
    (`VoteJdbc.votedByCriteria`), even with `vote.rate = 0` (U5).
- `vote_aside` mirrors `vote`'s columns, and rows move with `SELECT *`. **It has no reason
  column**: the reason is `round_image.status`, per file.
- `ImageJdbc.restoreVotes` and `moveVotesBackInRound` (requalify) move back every aside row of the
  file (but a `dq` pair's, for restore). So a juror's aside rows always return to that juror.
- **Invariant 8** (two-step plan §5, "until P3"): a `round_image` row has votes, or is `dq`.
  - `RoundImageJdbc.syncChunk` (behind `sync` / `syncPairs`) deletes the row of a pair with no
    votes in `vote` or `vote_aside`, and re-creates a missing one as `Origin.Manual`.
  - The repair job (`RoundImageCheckHandler.syncStep`) runs the same `sync` and counts the
    deletions as `removed`.
- **Who calls `sync` / `syncPairs`:**
  - `ImageJdbc.moveVotesAside` (a Commons deletion, a round opening, a disqualify) and
    `moveVotesBack` (a restore, a requalify): the pair keeps rows, so it never loses its row;
  - `VoteJdbc.deleteVotes`: the tests only (`TestVoteWrites`);
  - the repair job.
  - So in the app, the deletion fires only in the repair job, on drift.
- `addVoted` keeps an existing row's `added_how`, `added_by_job_id` and events.
- `DeletedFiles.markDeleted` → `ImageJdbc.archiveVotes`, `archiveDeletedVotesInRound` and
  `moveVotesAside` all start from vote rows.
- `vote_round_jury_rate_idx` is `(round_id, jury_id, rate, [id])`.

**Concurrency and state**
- A juror's vote is row-level: lock by primary key, then `round_user` and `round_criterion` in
  share mode. It never locks the round. A row deleted meanwhile gives 404 `not_found`.
- Structural writers lock the round first (`RoundWrites`). Connections run at READ COMMITTED, with
  retries on deadlock.
- Gating (§4 (g)): vote-moving writes run only on rounds that aren't open; "Distribute new files"
  and Commons moves are the stated exceptions. A vote already in flight can still land, so
  row-level correctness stays needed.
- `openCond` counts `distributing = 1` as open. `openToJurors = active && !distributing`.
  - `RoundConflicts.gate` refuses while `distributing`, frozen, open, or a job holds the key.
  - `setActiveGuarded` refuses to start a round with `distributing = 1`. Stopping a round being
    created writes `active = 0`, which `completeCreation` then overwrites with 1.
- `Round.freeze` / `release` make a round temporarily inactive and restore its active flag
  (`active_before_freeze`). Distributions use them on previous rounds.

## 3. Order of work [user], PR split, parallel work

The maintainer's order (2026-11-16):
1. **Add a juror** (§5): needed now, to add a new juror to a running round.
2. **See images before distribution, P3** (§6): needed now, for a round about to start. Until it
   ships, see the stopgap (§4).
3. **Reassign a juror's unrated files** (§7): can wait. The juror is planned to vote until a future
   date, then their unrated files are reassigned.

**Prerequisites:**
- **Adding a juror doesn't need invariant 8 to end.**
  - With N > 0, a row moves only to a new juror eligible for that page, and the new row is inserted
    before the donor's is deleted. Every file keeps at least one vote, so `sync` never deletes its
    row, and `addVoted` keeps its origin.
  - With distribution 0, adding only inserts rows.
  - No file is ever left without a juror, so there are no "waiting" files to show.
- **P3 needs invariant 8 to end**: filled files not yet distributed have no votes. That is P3's
  first PR.
- **Reassigning needs it too**: a file whose only receiver-eligible juror is missing would lose its
  last row. It also needs step 1's job machinery. So it comes after step 1 and P3-1.

**PR split and sizes:**

| Step | PR | Content | Size | Migration | Depends on |
|---|---|---|---|---|---|
| 1 | 1a | Kind `round_rebalance` (sharing the dedupe key), scheduler priority, the self-pause (`round_paused` state, page and vote code), the left-frozen flag with root Release, refusals in both directions, `setRoundUser` under the round lock; add a juror to a **distribution-0** round (inserts only) | M–L, ~5–6 days | – | – |
| 1 | 1b | Add a juror with **N > 0** (donor quotas, moves, the per-step check); "Start and give a fair share" for a stopped juror | M, ~3 days | – | 1a |
| 2 | P3-1 | End invariant 8 (§6.1): `sync` keeps rows, repair job, invariant list, deleted-file paths for vote-less rows, the readers audit | M, ~3 days | – | – |
| 2 | P3-2 | `round.status` (lineage §2.6), with §6.2's held-round rows | M, ~2–3 days | next free (V105 today) | – |
| 2 | P3-3 | **The first review:** `fill` and `distribute` modes, the round held in review after fill (status `paused`, `distributing = 1`), the form's "Create and review images" and "Distribute as soon as ready" (D3/D4), the review page (`FileList.RoundImages`, summary, organizers' view), the Distribute action, §6.2's reader changes | L, ~5–6 days | – | P3-1, P3-2 |
| 2 | P3-4 | Remove / Put back a file held in review (D16a's `dq` manual, the gate change) | S, ~1.5 days | – | P3-3 |
| 2 | P3-5 | Re-apply filters, Prune, top-N on re-apply (D16, D17), the D18 warning | M, ~3 days | – | P3-3 |
| 2 | P3-6 | Threshold preview | S, ~1.5 days | – | P3-3 |
| 3 | 3 | Reassign unrated files (§7): the page with the receivers list, moves, aside rows; the waiting count and the opt-in top-up | M, ~4–5 days | – | 1a, 1b (water-filling and row moves), P3-1 |
| – | follow-up | "Distribute new files" rotation starting at the least-loaded juror | S, 0.5 day | – | – |

- **Totals:**
  - step 1: about 8–9 days;
  - the first review (P3-1 + P3-2 + P3-3): about 10–12 days;
  - all of P3: about 16–18 days;
  - step 3: about 4–5 days.
- Each PR is independently reviewed, with translation keys listed in its body.
- **Two agents in parallel:** agent A builds step 1 (1a, 1b); agent B builds P3-1 → P3-2 → P3-3.
  - **P3-1 is independent of step 1.** It touches `RoundImageJdbc`, `RoundImageCheckHandler`,
    `RoundImageInvariants`, `ImageJdbc` / `DeletedFiles` and their specs, none of which step 1
    touches.
  - **P3-2 and P3-3 overlap step 1 lightly**, with textual conflicts only:
    - `RoundController` and `conf/routes`: new actions on both sides;
    - the four `messages` files;
    - `roundStatTable.scala.html` (step 1's buttons) vs `editRound.scala.html` (P3-3's form);
    - `RoundService.enqueueOrFail` (step 1's previous-round key check vs P3-3's modes);
    - `Round.scala`: P3-2's status writers in `release` / `setActiveGuarded`, which step 1's
      self-pause calls.
  - Merge step 1a first or rebase onto it. Freezing doesn't change the status (lineage §2.6), so
    the two meet only in code, not in behaviour.
  - P3-4, P3-5 and P3-6 can then run alongside step 3 (step 3 touches the rebalance handler, P3
    the distribution handler).

## 4. Stopgap until P3 ships

Is there a way today to see a new round's images before its jurors can vote? Checked against the
code:

- **Stopping the round while it is being created doesn't work.**
  - `setActiveGuarded` writes `active = 0` on a round being created.
  - The job's `Verify` then runs `completeCreation`, `UPDATE round SET distributing = 0,
    active = 1`, which opens the round anyway.
- **Stopping jurors (per-juror Stop) during creation doesn't work.**
  - The job reads its jurors once per run, so stopping them mid-run changes nothing until a Retry.
  - After a Retry, it would give their files to the remaining jurors, or fail with an empty jury.
- **Stopping the round right after creation works, with a gap.**
  - When the rounds list shows the creation done, the admin clicks Stop. Jurors then can't see or
    vote in it.
  - The admin reviews in the all-jurors galleries (staff may view a stopped round) and starts it
    when satisfied.
  - **The gap:** between `Verify`'s commit and the click, from seconds to the time the admin takes
    to notice, the round is open. A juror who loads a page then sees it and can vote. Nothing is
    sent to jurors, so the risk is a juror browsing at that moment. Votes cast then stay.
  - Per-juror Stop after creation has the same gap and takes more clicks.
- **A trial round has no gap.**
  - Create the round with the same filters, assigned only to a test juror account. Jurors see only
    rounds they are assigned to (`activeRounds(user)` needs their `round_user` row).
  - Review its files in the all-jurors galleries, then create the real round with the real jurors
    and stop the trial round.
  - **Costs:**
    - the distribution runs twice;
    - the trial round stays in the rounds list (lineage's Cancel and Delete aren't built), which
      is the 2025 "[test]" clutter;
    - its creation closes the previous rounds as any creation does (`release(close = true)`),
      harmless if they are finished;
    - a test juror account is needed in the contest.
- **Recommendation until P3-3 ships:**
  - if the round's jurors are active right now, use the trial round;
  - otherwise, stop the round immediately after creation.
  - Either way, nothing is built for it.

## 5. Step 1: add a juror to a running round

### "Running" and the pause

- **Running** means open to jurors: `active = 1`, `distributing = 0` (`openToJurors`), and not
  frozen by another job (`freeze_count = 0`).
  - Once `round.status` exists (P3-2), it also means `status = active`.
  - A round frozen as a previous round of a running distribution (`active = 0`,
    `active_before_freeze = 1`) is not running; it is refused until that distribution ends.
  - **During its own job**, a round counts as running when the job froze it (`state.frozen`). The
    per-step check is `freeze_count > (state.frozen ? 1 : 0)`.
- **Default for add:** the same rule as D3 for reassigning. A stopped or finished round is started
  first.
  - **An allowed exception:** a P3 round held in review (after P3-3). Adding a juror there only
    adds the `round_user` row, with no move and no pause, and the distribute step gives them files.
- **The pause** (the 2026-11-03 rule): the job freezes the round in its first step and releases it
  in its last, and on failure or cancel. It uses `Round.freeze` / `release`, as distributions do.
- **What the round's jurors see while it is paused.** Today `freeze` drops the round from their
  menu (`activeRounds(user)`, `contestsWithOpenRound`) and gives the 403 page with a
  `security.authz` WARN line (`GalleryService.isNotAuthorized`). A vote gets `round_closed`.
- **So a paused round gets its own state:** `freeze_count > 0` and a `round_rebalance` job holds
  its key. For a juror with an active `round_user` row:
  - the menu keeps the round;
  - the gallery and large view show a "paused" page (200, no denial line): "Files are being
    reassigned; this takes a few seconds. Reload shortly";
  - a vote gets 409 `round_paused`, with its own text in `vote-errors.js`.
  - Stopped jurors and stopped rounds keep `round_closed`.
- **The pause can't outlive the job:**
  - Retry releases `state.frozen` before freezing again (the distribution's `onRetry` pattern);
  - a round with `freeze_count > 0` that no active job froze (no active job whose `state.frozen`
    lists it) is flagged on the rounds list and by the repair job, with a root **Release** action (a
    CSRF POST, logged). This covers `giveUpIfExhausted`'s failed cleanup;
  - rebalance jobs are finished or cancelled on the **new** binary before a rollback (§11).
- **How long:**
  - `round_rebalance` gets scheduler priority: its next step is claimed before other kinds' while
    it holds a freeze. Without that, it would take turns with imports and distributions on two
    workers;
  - `jobs.concurrency.round_rebalance = 1` is set explicitly (`defaultConcurrency`'s value): one
    rebalance at a time across all contests;
  - an add with N > 0 moves about 1.5k rows (1 step), a few seconds of pause;
  - an add to a distribution-0 round of 22,577 files is about 12 steps, about 15–40 s.

### UI

- **Where:** under the round stat table, **Add juror…** (admins). The select lists contest jurors
  with an active membership who aren't in the round.
- A stopped juror's row also offers **Start and give a fair share…** beside the plain Start (1b).
- **The page (GET), with the jury listed as decided in D1:**
  - active jurors are listed and ticked by default as donors;
  - stopped jurors are listed, unticked, and visibly marked "stopped". Ticking one lets their
    unrated files go to the new juror. **Default (not part of D1, which covers receivers):** a
    ticked stopped donor stays stopped, since giving files away needs no voting;
  - N > 0: "9,120 unrated files over 5 jurors. Y gets about 1,520: from A 410, B 390, C 300, D 260,
    E 160";
  - distribution 0: "Y gets all 22,577 files of the round";
  - when the round is open: "The round pauses for a few seconds while this runs".
- **POST** (CSRF, `data-submit-once`):
  - carries the figures shown and re-checks them under the round lock;
  - if the jury changed meanwhile, shows the page again with fresh figures;
  - otherwise queues the job.
  - The flash and the job page show the counts actually moved.

### Refusals

Checked on the page, on POST under the round lock, and in every job step under the round lock (a
failure names the reason; Retry checks again):
- the round isn't running;
- it has no `round_user` rows;
- it is cancelled, or another job holds its key, or another job froze it;
- Y has no active jury membership of the round's contest. *(Built: an already active juror is
  not refused; the add tops them up, see the build notes.)*
- a ticked donor isn't a juror of the round.

The other direction:
- creating or distributing a round that reads or excludes this one is refused while its rebalance
  runs (`enqueueOrFail` / `previousRounds` / `excludedRounds` test the previous rounds' keys);
- plain Start/Stop (`setRoundUser`) takes the round lock and is refused while a job holds the key.

### The job (kind `round_rebalance`, `params.add = Y`)

- **Kind and key:** the same dedupe key string as the distribution, `round_distribution:round:R`
  (default, §13).
  - The rounds list's job column reads both kinds' latest job (`latestBySubjects` for each) and
    shows the newer one.
- `params`: `add`, `donors`, `by`. `state`: `phase`, `cursor` (last vote id per donor), `quota`,
  `given`, `frozen`.

1. **`stop`**:
   - lock the round and check the refusals;
   - freeze it (`openToJurors`), record `state.frozen`;
   - insert or reactivate Y's `round_user` row (role from the contest membership);
   - compute the donor quotas: water-filling from above, so donors end with about the same
     unrated count as Y (default, §13);
   - `round_user` is written only in this step, avoiding a cycle with a vote (vote row, then
     `round_user`).
2. **`move`** (N > 0, PR 1b), one window per step:
   - each ticked donor's unrated rows (`rate = 0`, no criterion above 0), last assigned first (by
     descending vote id on `vote_round_jury_rate_idx`), up to 2,000 rows;
   - lock the round and re-check; lock the rows `FOR UPDATE` (one rated just before is skipped);
   - a row moves only if Y has no row for its page in `vote` or `vote_aside`; each donor stops at
     its quota;
   - write Y's row first, then delete the donor's row with its rate-0 criteria rates, then one
     `syncPairs`;
   - check in the same transaction: each page's vote rows are unchanged in number; otherwise roll
     back.
3. **Distribution 0** (PR 1a), instead of `move`: keyset windows over the round's `in` rows where Y
   has no row in `vote` or `vote_aside`, and the image isn't deleted (`images.deleted_at`);
   `DistributeImages.assign` with {Y}. This is P3's `distribute` step for one juror, written to be
   reused by it.
4. **`verify`**: Y holds `given`; each donor lost exactly its moved rows; invariants hold. Then
   release the round if this job froze it, log, invalidate the counts.

- `onFailed` / `onCancelled` release the round if the job froze it.
- **Rated rows never move.** Y takes only unrated work.
- **No vote is lost or duplicated:** the unique index and the eligibility rule exclude a second row;
  an in-flight donor vote waits for the row lock and either finds its row or gets 404; the lock
  order is the existing one (round → vote → `vote_aside` → `criteria_rate` → `round_image`).
- **Audit:** a `round.admin` INFO line at queue time and at Done (`action=juror_add accountId=
  contestId= roundId= jurorId= moved= jobId=`); the job row keeps `params` and `given`.

### Build notes (PR 1a, 2026-11-16)

Where the build chose the safer option than the text above (details in
`docs/pr/stack-162-add-juror.md`):
- **Cancel and top-up (default, the orchestrator's decision after review, to be confirmed by the
  maintainer):** Cancel stops the juror again only if the job gave them nothing (`filesGiven = 0`)
  and they weren't active before; otherwise they stay started, so no file the job moved sits with
  a stopped juror. An already active juror is not refused: adding them is a top-up (N = 0: the
  files they lack; N > 0: the water-filling counts their own unrated files), which is how a
  cancelled add is finished.
- Every `stop` step (a Retry's too) writes the juror's row active again; its state before is
  recorded the first time only. A distribution's Retry, and "Distribute new files" resuming a
  failed one, are refused while a previous round has a rebalance (its freeze would fail the
  rebalance). A rebalance's Retry rebaselines its count check by what others changed meanwhile.
- **Priority** is per kind, not "while it holds a freeze" (it holds one from its first step to its
  last).
- **Left frozen**: only active jobs count as holders; root's Release also takes the round off a
  failed job's `state.frozen`, so that job's Retry or Cancel doesn't release it a second time.
- The previous-round refusal applies to rebalance jobs, not to a list-only distribution.
- There is no job page: the rounds list's job column shows the newer of the two kinds' jobs, and
  keeps a done rebalance with its count.

## 6. Step 2: P3 as decided

Build P3 as `round-images-two-step.md` decides it: §4 "`round.status` (P3)"; §6 (job modes, count
checks, re-apply, "Distribute new files", UI, authorization); §9 "P3" (write-path rules, work,
specs); D1–D20 and D16a. The split into PRs is §3's: P3-3 gives admins the review; re-apply and the
threshold preview follow in P3-5 and P3-6, all within P3 as decided.

### 6.1 P3-1: end invariant 8

The simplest form that covers P3's three cases (a `dq` row, an `in` row of a round held in review, a filled
row not yet distributed): **no row is ever deleted by a sync.**

- **`syncChunk`:**
  - votes in `vote` → `in`;
  - only in `vote_aside` → `deleted`;
  - `dq` stays `dq`;
  - **no votes anywhere: the row and its status stay.** The `gone` branch and
    `SyncResult.removed` go away.
- The repair job removes nothing. `RoundImageInvariants` drops invariant 8.
- **Files deleted on Commons.** The deletion paths (§2) start from vote rows, so a vote-less `in`
  row would stay `in`.
  - Fix: they also mark a vote-less `in` row of the file `deleted`, in the same transaction, after
    its open rounds' locks.
  - `restoreVotes` reverses it: a `deleted` row with no aside rows becomes `in` again.
- **Readers of `round_image` as a round's file list**, and how a vote-less `in` file shows (checked
  in the code):

| Reader | What it reads | A vote-less `in` file |
|---|---|---|
| `rewrite/RoundImageQuery` (P2b's counts path: all-jurors lists, by-rate, large view neighbours, region menu; `GalleryController`, `RoundList`, `LargeViewController` behind `roundImage.read`) | `in` rows with `assigned > 0` | not listed, as today for a file without votes |
| `rewrite/ImageDbNew` (the vote source), the CSV | `vote` rows | not listed |
| `RoundImageJdbc.countIn` / `countsIn` → `imageCountByRounds` → `RoundImageCounts` | `in` rows | counted (a round held in review shows its filled files; step 3 shows "N files (M waiting)") |
| `ImageJdbc.byRound`, `inRound` / `hasVotes`, `RoundStatLinks`, `AuthorStats`, `CoiMembers.roundFiles` | `in` rows | included, as a file with no votes: right for CoI and per-author counts |
| `previousCount` / `previousWindow` (a next round) | unfiltered: `in` rows; rate filters: `vote` | unfiltered: passes, as an unrated file does today; filtered: doesn't |
| `notInRound`, `notInExcluded` | any row | never re-added; still left out by a round excluding it |
| `RoundCountsRecompute` (`writeCounts`) | counts from `votedCounts` | a row absent from it gets `Counts.Zero`: invariant 5 holds |
| Score rules | `assigned` / `voted` | `AverageRule.keySql` guards a zero denominator |

- **Cost and risk:**
  - no app write path changes behaviour (the deletion fires only in the repair job);
  - the risk is in the deleted-file paths and in tests that delete votes through `TestVoteWrites`
    and expect the row to go.
- **Specs:**
  - `sync` keeps vote-less rows;
  - the repair job removes nothing;
  - each reader behaves as its table row says;
  - the recompute writes zero counts for a row absent from `votedCounts`;
  - a vote-less file deleted on Commons becomes `deleted`, and `in` on restore;
  - the specs that relied on deletion are updated.

### 6.2 What changed in the code since P3 was written

For P3-2 and P3-3 to handle, each with a spec:

| Point | Today | For P3 |
|---|---|---|
| `Verify` | opens the round as soon as the count check passes | the `distribute` mode's end; `fill` ends with the round held in review (status `paused`) |
| `RoundConflicts.gate` (disqualify, requalify, D16a's `removeImage`) | refuses `distributing = 1`; `openCond` counts it as open | allows a round held in review (status `paused`, `distributing = 1`, no job). The gate tests status and the job, not `openCond` (P3-4) |
| `RoundCounts.roundWindow` | skips `distributing = 1` | may keep skipping while held in review: filled rows have no votes. The distribute job's end makes the round dirty |
| `distributingNow` (the `exclude_rounds` busy test, `EditRound.validExcludedRounds`, queueing) | busy while `distributing = 1` | a round held in review isn't busy: its files change only through a job, which holds the key `distributingNow` checks. Otherwise a round excluding it is blocked for the whole review |
| `distributeNewFiles` / `saveEdit` | `completingCreation = round.distributing` | on a round held in review, queues `fill` (new files) only; distributing stays the review's action |
| Lineage Reinstate | `cancelled` → `draft` if `distributing = 1` | → status `paused` (held in review) when the round's last fill job is done. Amend lineage §2.6 |
| Lineage backfill (and its rerun) | no row for `distributing = 1` with a finished job | add: latest job done in mode `fill` → `paused` |
| Adding a juror (step 1) | – | on a round held in review: only the `round_user` row (an allowed exception to "running rounds only"); the distribute step gives files |

## 7. Step 3: reassign a juror's unrated files

**[user]** One action, unrated files only, votes always stay, the juror ends stopped, running
rounds only.

- **Where:** the round stat table, per juror, beside Start/Stop: **Reassign files…** (admins). It is
  shown only while the round is running (§5), and the POST is refused otherwise; the page says to
  start the round first.
- **The page (GET):**
  - the juror's counts: assigned, rated (✗ and partial criteria included; they stay), unrated, set
    aside;
  - **the receivers, as decided in D1:**
    - active jurors are listed and ticked by default;
    - stopped jurors are listed, unticked, and visibly marked "stopped";
    - ticking a stopped juror starts them again, and the page says so, including that they can
      then change their earlier votes in the round;
  - the result before confirming: "340 unrated → 4 jurors: A +85, B +85, C +85, D +85. 3 files
    can't move (every ticked juror already has them); they wait for a juror." Also "The round
    pauses for a few seconds while this runs".
- **POST:** as in §5 (re-check, fresh figures if the jury changed, the actual counts afterwards).
- **Afterwards** X is stopped. Their votes stay in every view. Bringing them back is "Start and give
  a fair share" (§5).
- **Refusals:** §5's, and:
  - fewer ticked receivers than N ("add a juror first");
  - a receiver that isn't a juror of the round with an active jury membership (an inactive or
    removed membership can't be restarted);
  - X not in the round.
- **The job** (kind `round_rebalance`, `params.from = X`):
  1. `stop`: freeze, set X inactive and the ticked stopped receivers active, snapshot the quotas
     (water-filling of open work, §13).
  2. `move`: X's unrated rows by vote id, ≤ 2,000 per step. Each page goes to the eligible
     receiver (no row in `vote` or `vote_aside`) with the largest remaining quota.
     - If none is eligible, X's row still goes and the file stays `in`, **waiting for a juror**.
       That relies on P3-1.
     - The writes and checks are §5's.
  3. `aside`: X's unrated rows in `vote_aside` (files deleted on Commons or disqualified) are
     replaced by aside rows of eligible receivers, so a restore or requalify brings the file back to
     an active juror, not the stopped one. X's rated aside rows stay.
  4. `verify`: rescanning from the start, so a file restored meanwhile is seen. X has no unrated
     rows left in `vote` or `vote_aside`; each receiver holds `given`; waiting files are `in`;
     invariants hold. One pass back to `move` is allowed. Then release the round, log
     (`action=juror_reassign … moved= waiting= aside=`), invalidate the counts.
- **Distribution 0:** nothing is given. X's unrated rows are deleted, so the `assigned` rule doesn't
  count them as zeros. A file left with no rows waits.
- **Waiting files:**
  - the round stat table and the edit form show "**N files waiting for a juror**" (`in` rows with
    fewer than N vote rows, N = `distribution` > 0, files not deleted);
  - **topping up is opt-in:** "Distribute new files" gets a tick "Also give out the N waiting
    files", off by default. With it, a keyset pass gives each waiting file its missing jurors from
    the eligible active jurors, least loaded first (rows keep their origin; counted separately in
    the count check). An add (§5) offers them to the new juror first.
  - Off by default because production's count of such files is unmeasured (hand-edited rounds,
    lineage §11). **The query to run later** on the production copy:
    `SELECT ri.round_id, COUNT(*) FROM round_image ri JOIN round r ON r.id = ri.round_id
    WHERE ri.status = 'in' AND r.distribution > 0 AND ri.assigned < r.distribution GROUP BY
    ri.round_id;` (after a clean recount), with the repair job's past `removed` figures from
    `job.state`.

## 8. Later: discarding a juror's votes

Deferred by the maintainer (2026-11-16), not rejected. The design kept for then:
- **The page:** a "Discard their votes too" choice. The rated rows move to a new table
  (`vote_withdrawn`: vote's columns plus `withdrawn_by`, `withdrawn_at`), keeping their ids so
  criteria rates follow.
- **Their images** are then covered by receivers as unrated files.
- **Why a table of its own:** restore and requalify never read it, so a discarded vote can't come
  back. `vote_aside` and its `SELECT *` moves stay untouched.
- **To be taught about it:**
  - the repair job and invariant 7 (its criteria rates aren't orphans);
  - eligibility (no row in the store either);
  - X's rated aside rows (to the store too).
- **Refused** once a later round lists the round in `previous` or `exclude_rounds`.
- **One additive migration.**

## 9. Authorization

- **Admins of the round's contest and root:** the pages and POSTs (`Secured.administersRound`,
  `withAdminRound`, `Secured.inContest`, no contest switch).
- **Organizers:** read-only. They see the job progress, the waiting count and P3's review page (P3
  D20), and no buttons.
- **Every POST carries the CSRF token.** Posted juror ids are validated as in the refusals.
- **Specs** (`MutationAuthorizationSpec`, `CsrfTestApp.withCsrfApp`), for each new GET and POST:
  - juror 403; organizer 403;
  - an admin of another contest 403/404, including another contest's round under one's own
    contest;
  - root allowed;
  - a juror id not in the round 404;
  - a juror of another contest, a non-member or an inactive member refused, with nothing queued;
  - signed out: the login page, or JSON 401;
  - no CSRF token refused;
  - a round not running refused;
  - no contest switch.

## 10. `round.status` and migrations

- **`round.status`** is lineage §2.6 as decided (the column, `status_changed_at` / `_by`, the CHECK,
  the backfill, the writers in step), built as P3-2, with §6.2's backfill row and Reinstate rule.
  - Lineage later adds only `track` and `created_at` without `ON UPDATE`. Its §2.6 "V103" step list
    is amended when P3-2 lands: steps 1–3 done, `track` and step 4 left.
- **Migration numbers:**
  - V104 ships in 26.11.8. With `outOfOrder` off, V103 can't be used after it: **V103 is retired**.
  - This batch has **one** migration, `round.status` (P3-2), at the next free number at merge
    time: V105 today.
  - No discarded-votes table now (§8).
  - The rule (roadmap): assigned at merge; never below the highest number deployed; renumber on
    collision; record the change in the roadmap and in `FlywayUpgradeSpec`.

## 11. Specs (steps 1 and 3), Gatling, deploy

**Specs**
- **Unit:** water-filling (equal open work gives an equal split; the rounding sums exactly);
  eligibility; donor quotas.
- **Add (1a, 1b):**
  - binary, rated, ✗, criteria (partial votes count as rated and never move);
  - distribution 0 and N = 1, 2, 3;
  - only ticked donors give, and a ticked stopped donor stays stopped (§13's default, not D1);
  - the file's `added_how`, `added_by_job_id` and events are kept;
  - the per-step check rolls back;
  - Retry after an injected failure gives the same result as an uninterrupted run;
  - Cancel and failure release the round they froze;
  - refusals: not running, legacy round, frozen by another job, Y already active.
- **Reassign (3):**
  - the same round types;
  - unrated only, and rated votes stay with X;
  - ticked stopped receivers are restarted, and unticked ones get nothing;
  - unplaceable files wait, and are topped up with the tick or by an add;
  - aside rows are never given to their juror, and restore and requalify after a reassign give the
    file to the receiver;
  - a restore between steps is caught by `verify`;
  - refusals: not running, fewer receivers than N, an inactive member as receiver.
- **The pause and interaction:**
  - during the pause, a juror of the round gets 409 `round_paused` on a vote and the paused page
    (200, no `security.authz` line) on the gallery and large view; the menu keeps the round. A
    stopped juror still gets `round_closed`;
  - the round is open again after Done, Cancel, failure and Retry, with `freeze_count` back to its
    value before;
  - a round left frozen with no job is flagged, and Release reopens it;
  - creating or distributing a round that reads or excludes this one is refused during the job; a
    freeze between steps fails the next step;
  - "Distribute new files" after a failed rebalance doesn't retry it;
  - Start/Stop is refused during a job;
  - invariants hold after each scenario.
- **Concurrency** (`WritePathStress` gets a rebalance path, and a spec in P2a's style):
  - votes in flight from donors, X, receivers and others while steps run;
  - every vote that returned success is kept with its juror;
  - no pair appears twice, and no file leaves the round.
- **Authorization:** §9.
- **Browser:** a story each for the add and reassign pages.
- **P3's specs** are its §9 plus §6.1 and §6.2's rows.

**Gatling**
- No new simulation.
- One paired `VotingSimulation` run with a rebalance running (the pause, the round lock), SQL debug
  logging off.
- Bump `FixtureVersion` only if P3-2's `GatlingDbSetup.load` writes the status.

**Deploy and rollback**
- **Before rolling back past step 1:** on the **new** binary, finish or cancel every
  `round_rebalance` job and check that no round is left frozen. On an old binary such a job never
  runs (no handler), keeps the round's dedupe key active (its distributions are refused as already
  running) and keeps its freeze, and Cancel there skips the release.
- **Before rolling back past P3-1:** an old sync deletes a vote-less row the next time that pair is
  synced. Finish rounds held in review and top up waiting files first.
- **Past P3-2 and P3-3:** P3's own note (distribute or cancel rounds held in review).
- **Forward again:** rerun the status backfill UPDATE (lineage §2.6) and run the repair job.

## 12. Measured (local production copy, read-only, before it was switched off)

- **Recent rounds** (2024–2026, the 25 largest by vote rows): 1–17 jurors, up to 26,380 files.
  Distribution is 0 in 16 of the 25, otherwise 1–3. The largest distribution-0 round has 22,577
  files.
- **A juror leaving unrated work is common.** In each of the last three WLM UA main rounds, one
  stopped juror left most of their share unrated:
  - round 1959 (2026, N = 1): 882 of 1,541;
  - round 1958 (N = 2): 760 of 1,768;
  - round 1804 (2025, N = 3): 756 of 856.
- **"Stopped" usually means "finished".** In round 1959, 11 of 12 jurors are stopped, all but one
  with 0 unrated. Of 492 recent finished rounds, 92 have every juror stopped. That is the background
  of D1's marking of stopped jurors.
- **Sizing:** a reassign moves 1–2k rows (1 step); an add with N > 0 about 1.5k; an add to a
  distribution-0 round up to 22,577 rows (about 12 steps).
- `vote_aside` holds 3 rows in all.
- **Not measured** (the copy is off): the waiting-file count and the repair job's past `removed`
  figures. The design doesn't need them (the top-up is opt-in); §7 has the query.

## 13. Decisions and defaults

**Decisions [user] (the maintainer, 2026-11-16)**
- **Order [user]:** add a juror → P3 → reassign (§3). This replaces this plan's earlier default
  (invariant 8 → take-off → add → P3).
- **D1 [user]. Which jurors are offered, on the reassign and add pages.**
  - Active jurors are listed and ticked by default.
  - Stopped jurors are also listed, unticked, and visibly marked as stopped, so the admin can tick
    them knowingly.
  - A ticked stopped juror is started again when receiving; the page says so, including that they
    can then change their earlier votes.
  - On the add page the same list and marking show the donors.
- **D2 [user]. One action, unrated files only.** "Reassign files…" moves only unrated files; rated
  votes always stay, and the juror ends stopped. Discarding votes is deferred (§8): no
  discarded-votes table or migration now.
- **D3 [user]. Running rounds only.** "Reassign files…" isn't offered, and its POST is refused, for a
  round that isn't running (§5's definition); the admin starts it first.

**Defaults** (each with its reason; the maintainer may overrule any):
- **"Evenly" = water-filling of open work:** jurors end with about the same number of unrated files.
  It is an equal split when they are equally far along, and the same rule serves add and reassign.
- **Donors give their last-assigned unrated rows:** the files they are least likely to have open.
- **A separate job kind sharing the round's dedupe key:**
  - the key's unique index makes distribution and rebalance exclude each other, and every busy
    check sees it;
  - the distribution's "latest job" readers (retry, resubmit, freeze hooks, lineage's backfill)
    don't pick it up;
  - an old binary has no handler for it, so rebalance jobs are finished or cancelled on the new
    binary before a rollback.
- **Terms:** "held in review" is P3's post-fill hold (status `paused`, `distributing = 1`);
  "paused" is only the self-pause (`freeze_count`) while a rebalance runs.
- **The round pauses while a move runs** (the 2026-11-03 rule), with its own "paused" state for its
  jurors and scheduler priority: seconds for most moves, under a minute for the largest add.
- **Adding a juror follows D3's rule too:** running rounds only, with one allowed exception: a P3
  round held in review, where adding only writes the `round_user` row.
- **A ticked stopped donor stays stopped** (add page): D1 covers receivers, who are started again;
  donors only give files away and need not vote.
- **The reassigned juror's unrated aside rows go to receivers:** otherwise a file restored on
  Commons would come back to the stopped juror.
- **Topping up waiting files is opt-in** until the production count (§7's query) shows it can be
  the default.
- **Refused:** fewer receivers than N; legacy rounds.
- **Audit:** log lines and the job row until gap 15.
