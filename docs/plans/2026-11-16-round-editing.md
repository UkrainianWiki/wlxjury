# Round editing: review before distribution, adding a juror, reassigning unrated files

**Status:** decided. Written 2026-11-16 from the maintainer's request of the same day. Revised after
an independent review and the maintainer's direction (v3: **build what is already decided, plan
only what is new**), and after a follow-up review (v4). All decisions were taken by the maintainer
the same day (v5, §13, marked **[user]**). What is built is in each section's build notes (step 3: §7).

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
| 2 | P3-4 | Remove / Put back a file held in review (D16a's `dq` manual, the gate change); the review page's "new, not distributed" list for a distributed round (§6 "Distribute new files", left out of P3-3) | S, ~2 days | – | P3-3 |
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
  first. (Revised 2026-11-25, see D3: a stopped round takes a juror too and stays stopped.)
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
- A stopped juror's row also offers **Start and give a fair share…** beside the plain Start (1b):
  the add page with that juror as its fixed subject (`?juror=`), not a select option; its confirm
  sends `restart` (decided 2026-11-20: the select lists only people outside the round).
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
- Y has no active jury membership of the round's contest;
- Y is already an active juror of the round (`job.error.rebalance.already.juror`, on the page and
  the POST; 2026-11-20, the maintainer's bug report: the select offered active jurors and the add
  topped them up);
- Y is a stopped juror of the round and the POST has no `restart` (only the restart page sends it)
  (`job.error.rebalance.stopped.juror`). A NULL `round_user.active` (before V108) is stopped on the
  page and in these checks.
- The steps check neither: their own `stop` makes Y active.
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
  a stopped juror. *(Changed 2026-11-20: an already active juror is refused, and isn't offered.
  A cancelled add that gave files is finished by Stop, then "Start and give a fair share…", which
  gives the files they lack (N = 0) or counts their own unrated files (N > 0).)*
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

### Build notes (PR 1b, 2026-11-16)

- Quotas are water-filling over the ticked donors and the new juror (`RoundRebalanceHandler.quotas`):
  the largest k donors give, each ending at the level's floor, the remainder one more each, the
  largest first; fixed by the `stop` step and kept on Retry (recomputed only after a failed count
  check). The page's figures use the same function.
- `move` takes candidates per donor by descending vote id (a plain read), reads their images in
  share mode, then locks the rows `FOR UPDATE` in id order; a row rated meanwhile or holding a
  criterion above 0 is skipped. Two donors holding the same file: one row moves, the other donor
  continues below it. The per-step check (each file's vote rows unchanged) throws, so the whole
  step rolls back and the job fails with a logged reference; Retry resumes.
- `verify` also checks each donor lost exactly the rows it gave (`job.error.rebalance.donor.verify`).
- "Start and give a fair share…" (the stat table's stopped-juror link) is the add page with the
  juror chosen. *(Changed 2026-11-20, the maintainer's decision: the select lists only the
  contest's jurors outside the round, never the round's active or stopped jurors; the stopped
  juror is the page's fixed subject, and the POST needs `restart` for them.)*

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
| `previousCount` / `previousWindow` (a next round) | unfiltered: `in` rows; rate filters: `vote` | doesn't (review fix: the unfiltered count and window require a vote there, as the candidates, read from `vote`, always did) |
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

**Built (P3-1, `stack/164-round-image-keeps-files`):**
- `RoundImageJdbc.syncChunk` keeps a vote-less row and its status; `SyncResult` has no `removed`.
  A requalified `dq` row with no votes stays in the round: `deleted` by `RoundConflicts.requalify`'s
  rule for a file still deleted on Commons (`keepAside`), else `in`.
- The repair job removes nothing; `ImageCheckState.removed` stays only to read jobs of older
  releases. `RoundImageInvariants` drops invariant 8 (its number isn't reused).
- Deleted files: `RoundImageJdbc.markVotelessDeleted` (from `ImageJdbc.archiveVotes`, so
  `DeletedFiles.markDeleted` and `settle`, and from `archiveDeletedVotesInRound`, so a round that
  opens and a distribution's `settleFiles`) and `restoreVoteless` (from `restoreVotes`;
  `lockRoundsOfArchived` also locks the rounds of the files' `deleted` rows).
- Specs: `RoundImageVotelessReadersSpec` (the table's readers), `DeletedFilesSpec` (vote-less
  files deleted, settled, opened, restored; a `dq` row kept), and the specs that expected a row to
  go (`RoundImageJdbcSpec`, `RoundImageCheckSpec`, `RoundImageEventSpec`).
- Not changed: `AverageRule.keySql` already guards a zero denominator, and the counts path lists
  only rows with `assigned > 0`, so a vote-less row reaches no score.

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

**Built (P3-2, `stack/165-round-status`):** V105 (`round.status`, its CHECK and backfill with the
rows above), the writers in step (lineage §2.6), `Round.lifecycle` / `inReview`. Gatling's
`FixtureVersion` is fixture-v9 (`GatlingDbSetup.load`'s `Round.create` writes the status).

**Built (P3-3, `stack/166-round-review`):**
- Job modes (`RoundDistributionHandler.Mode`): every new round is a `fill` (`RoundImageJdbc.addFilled`,
  no votes) that ends with the round held in review (`endFill`: previous rounds released, the round
  `paused`); with `distributeAfter` (D4, and "Distribute new files" on a distributed round) it queues
  the `distribute` job as it ends (`StepResult.Done.followUp`, enqueued by the scheduler in the
  same transaction). `distribute` walks the round's undistributed `in` files (`undistributedWindow`:
  no vote, none aside, image not deleted) from the running position; `complete` takes the round's
  lock, goes over the round once more if a file is left (a restore below the cursor), else fails,
  and opens the round. A distribute skips the exclusion checks and the Commons category fetch (it
  reads no candidates). Only a job without a mode (queued before P3) runs one pass. With a pasted
  list the jurors differ from the old one pass (the distribute walks page order; the list came
  last), every file still gets its jurors, balanced (spec'd).
- `RoundService.submit(review = …)`, `distributeReviewed` (refused unless `inReview`, read under
  `enqueueOrFail`'s sorted locks, with no job: `NotInReview`, `Running`). `distributeNewFiles`: on a
  round in review a fill only (`fromReview`, files `new_files`, the round stays `paused`; a failed
  distribute is never retried from there); on a creation never completed a fill with its intent (a
  cancelled one so reinstated, logged, who recorded); on a distributed round a fill then a
  distribute, which also gives out its files with no votes (a file restored after a deletion in
  review; a requalified one). The "new, not distributed" list is P3-4's (§3).
- §6.2 rows: `Verify` (fill → review, one pass → open); `distributingNow` treats a round in review
  as not busy; `distributeNewFiles` as above; the backfill row; `RoundCounts.roundWindow` unchanged
  (it keeps skipping `distributing = 1`; the distribute's end makes the round dirty). The gate change
  is P3-4's. A cancelled or failed `distribute` (or review fill) leaves the round `paused`.
- UI: the form's "Create and review images" (D3) and "Distribute as soon as the images are ready"
  (D4, off); the rounds list's "Images ready: N — Review / Distribute to jurors"; the review page
  (`RoundReviewController`: the contest files page with `FileList.RoundImages` by status, its
  region menu and large view at `/admin/rounds/:id/review…`, the summary by `added_how`, conflicts,
  the job, Distribute for admins and root); links from the round form and, for organizers, the
  round's statistics. Not built here (later P3 PRs or open): the CSV export and "why is this image
  here", the re-apply diff and D18 warning (P3-5), Remove / Put back (P3-4), the threshold preview
  (P3-6).
- After the second review and the merge of step 1: a fill's follow-up distribute freezes the
  previous rounds only once the scheduler has queued it (`onFollowUpQueued`; nothing frozen if another
  job held the key), and only for a creation; the creation form posted again never retries a failed
  distribute; adding a juror to a round held in review writes only their `round_user` row (no job, the
  review's Distribute gives them a share; `RoundRebalanceService.AddedToReview`); a previous round's
  rebalance refuses the review's Distribute as a flash.
- Specs: `RoundReviewFlowSpec` (service), `RoundImagesReviewSpec` (D20 authorization, CSRF, jurors
  never see a round in review, the form), and the form specs that now tick `distributeAfter`.

**Built (jurors at Distribute and P3-5, `stack/169-review-jurors-reapply`, the maintainer's 26.11.9 RC
report: "Create and review images" refused a round without jurors):**
- Jurors optional until distribution: the form's `jurors` has no required check; only "Distribute as
  soon as the images are ready" (D4) refuses none (under the field). `RoundService.submit(review)`
  takes no jurors; a fill skips `checkJury` (the context's `checkJurors`), as do "Count new files" and
  "Distribute new files" on a round in review. The review page's Distribute carries the round form's
  juror picker and file distribution (round-form.js), the round's jurors chosen, else the contest's;
  `distributeReviewed(jurors, distribution, confirmPrevChanged)` writes them under the round's lock
  before queueing, refused (`Refused`, nothing written) with no juror, more per file than jurors, a
  change once the round has a vote (a partial distribute), or D18 unconfirmed. A POST without the
  picker (the rounds list's button) uses the round's jurors. The add juror page takes a first juror for
  a round in review without `round_user` rows and no vote (no longer "legacy").
- Re-apply (P3-5): `RoundService.reapplyFilters` (only the filter columns, `Round.updateFilters`,
  written under the round's lock after the in-review, no-job and no-vote checks) queues a `fill` with
  `reapply`; the job's `scan` phase (after Rank) deletes the round's `in` `fill` rows from before it
  that no longer pass (`RoundImageJdbc.prune`, refused at each step if a vote exists), then the usual
  fill adds new matches (`fill` rows); the count check subtracts `pruned.count`. Top-N re-ranked with
  the round's `dq` files skipped (D17). The fill's end records `prevFingerprint` (count, sum and a
  CRC32 checksum of the previous rounds' votes); the review page warns when it changed and Distribute
  needs "Distribute anyway" (D18). The review page shows `+added / −removed` and the first 100 removed
  titles. Not built: the filter that rejects each removed file (only the titles are listed).
- After the independent review of `f448687d`:
  - Distribute posts the jury as shown (`RoundService.jurySignature`); a juror change is refused when the
    round's jurors changed since (a juror added would otherwise be dropped).
  - A failed or cancelled re-apply blocks Distribute (refused under the lock, warned on the page) until
    re-applied again or retried.
  - The D18 fingerprint is read on the review's main view only (page 1, all regions, files in), and
    recorded only by a fill that leaves the round in review.
  - The scan reads its window's files only (`page_id IN`).
  - The diff's removed count runs across Retries (`Pruned.total`).
  - Prune also takes `new_files` rows (the maintainer, 2026-11-16) and never a row with a vote.
  - Distribute re-reads the round in its transaction.
  - Previous rounds on a re-apply are rounds created before it (lower ids).
  - Deleted accounts are never jurors.
  - A review fill's Retry is refused once the round left review.
  - A refused re-apply saves nothing of the form (name, score rule, CoI flag are written with the filters
    under the lock) and keeps the pasted list in the form.
  - The monument ids filter trims spaces after commas.
- Follow-ups: a `round_image_event` per pruned file needs a new action in V88's CHECK (a migration),
  not added; the filter that rejects each removed file.
- Specs: `RoundReviewJurorsReapplySpec` (service), `RoundReviewJurorsReapplyPageSpec` (pages, D20,
  CSRF).

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

### Build notes (step 3, 2026-11-17)

Built on `stack/185-reassign-unrated` (no migration). Where the text above left a detail, the build
chose as follows:
- **The page** (`RoundJurorsController.reassignPage` / `reassign`, `/admin/rounds/:id/reassign-juror`,
  `round/reassignJuror.scala.html`): the stat table's two-arrow button per juror, shown to admins while
  the round is running (`RoundRebalanceHandler.isRunning`). A juror id not in the round is the 404
  page (GET and POST). The receivers are ticked by default from the round's active jurors **with an
  active jury membership** (an active round juror whose membership is inactive is listed unticked:
  ticking them is refused). The figures come from the job's own choice run over the juror's rows
  (`RoundReassign.allocate`), so "A +85, B +85" is what the job does when nothing changes meanwhile.
  In a round where every file goes to every juror the page lists no receivers.
- **The job** (`params.from`, `receivers`, `by`; `ReassignState`): "stop" freezes the round, locks the
  juror's and the receivers' `round_user` rows in id order, stops the juror and starts the ticked
  receivers (every "stop", a Retry's too; the states before recorded once), and fixes the quotas:
  water-filling from below over the receivers' unrated files (`RoundReassign.fillQuotas`), summing
  exactly to the juror's unrated rows. "move" takes the juror's unrated rows by ascending vote id,
  2,000 a step, with the add's lock order and per-step check (a file given keeps its number of vote
  rows, a file left waiting has one less). **A quota is soft:** a file goes to the eligible receiver
  with the most quota left even when that is zero or less, the lowest id on a tie; it waits only
  when every receiver has it. "aside" gives each unrated row set aside to an eligible receiver in
  place (`UPDATE vote_aside SET jury_id`, its id kept), else deletes it; the row's (rate 0) criteria
  rates are deleted. "verify" rescans: rows of the juror left send it back to "move" once
  (`repassed`), then fail; each receiver holds its rows before plus those given; the juror's rated
  rows are unchanged. Round where every file goes to every juror: the juror's unrated rows (and those
  aside) are deleted (`removed`), nothing given.
- **Cancel** (default, mirrors PR 1a's): if no row moved yet, the juror is active again if they were
  and the receivers the job started are stopped again; once a row moved, both stay as the job left
  them. Retry rebaselines the receivers' and the juror's counts; after a failed count check the
  accounting and quotas start again.
- **Refusals:** §5's frozen / not running / legacy (own messages for the reassign), the juror not in
  the round, a receiver not a round juror with an active jury membership, fewer receivers than N, and
  an account join holding the juror or a receiver (J-2), the receivers' users rows share-locked (A-4).
- **Waiting files** (`RoundReassign.waitingCount`): `in`, not deleted on Commons, fewer vote rows
  than N; not counted for a round being created or held in review. Shown under the stat table (also to
  organizers) and as the round form's tick **Also give out the N files waiting for a juror** (off;
  ticked alone it is "Distribute new files" with the tick). The top-up is the distribute job's
  `topup` phase after `assign` (`params.topUpWaiting`, kept from the fill to its distribute): a
  keyset pass, each file's missing jurors from the round's active jurors with no row of it, the
  least loaded (unrated files) first, rows with `Origin.Manual` (existing files keep their origin);
  its count check: no waiting file left that an active juror could take, one more pass, then failure.
  Its counts are `DistributionState.passes` (`toppedUp`, `topUpRows`): the state is at a case
  class's 22 fields, so `rescanned` moved into `Passes` too, read from the old top-level key.
  A waiting file with **no** vote row is also a file "Distribute new files" gives out without the tick
  (P3-3's distribute gives every `in` file without votes).
- **An add gives the waiting files first** (the maintainer's decision of 2026-11-17, built after the
  review): in a round of N > 0 the add's "stop" fixes the new juror's share as before (the
  water-filling, `RoundRebalanceHandler.quotas`) and counts the waiting files they could take
  (`RoundReassign.waitingForCond`: `in`, not deleted, fewer than N vote rows, none of theirs in `vote`
  or `vote_aside`). They count toward the share: the juror takes `min(waiting, share)` of them first
  (phase `waiting`, by page id, ≤ `chunkRows` a step, its check: each file one vote row more, at most
  N, else the step rolls back), the rest of the share comes from the donors levelled from the top
  (`RoundRebalanceHandler.fromTop`; without waiting files the quotas are unchanged). More waiting
  files than the share: the share is filled from them only, the others keep waiting. The add page
  shows the split ("Y gets about 81 files: 12 waiting + 69 from others"), the donors' part and the
  files left waiting (`addSplit`, the job's own function). Distribution 0 has no waiting files: the
  add is unchanged. **A share of 0** (no ticked donor has more unrated files than the new juror):
  the new juror still gets every waiting file they may take, nothing from the donors (the
  maintainer's decision of 2026-11-17 on the question the build left open); the page says "Y gets
  the N files waiting for a juror that they may take". Specs: `RoundRebalanceJobSpec` (fewer than,
  equal to and more than the share; N = 2 with a conflict of interest disqualified and a file the
  new juror has aside; a waiting step rolled back and retried; the invariant check; distribution 0),
  `RoundEditingFormatsSpec` (the split).
- **Defaults chosen by the maintainer (2026-11-17, recorded):** Cancel copies 1a's rule (above);
  the stat table's reassign button stays icon-only with a tooltip and a screen-reader label, as
  **Start and give a fair share…**.
- **Review fixes (2026-11-17):**
  - The round form's top-up is independent of "Distribute new files": ticked alone it queues only a
    distribute (its `assign` gives files with no vote row, then `topup`), no fill; with a pasted
    list the fill stays list-only; with both ticks, both. When a failed distribution is resumed
    instead, the flash says the tick wasn't applied (`round.waiting.topup.resumed`).
  - The add and reassign pages' confirm posts the choice the figures were shown for:
    `preview-confirm.js` disables it, with a hint, while the choose form's ticks or select differ
    from the rendered ones (the server checks the posted choice again).
  - "verify" (add and reassign) also checks no new violation of invariants 1–3 since the first
    "stop" (`VerifyChecks.violationsBefore`; kept on Retry, so a broken round fails until repaired)
    and that the files a reassign left waiting (`waitingPages`, the first 5,000) are still `in`
    (`job.error.rebalance.invariants`).
  - A reassign moves only rows whose file is `in` and not deleted on Commons
    (`RoundReassign.movableVote`); a row of a file deleted meanwhile, its votes not yet aside, stays.
  - Cancel once a row moved: the flash says to run **Reassign files…** again for the rest
    (`JobHandler.cancelledNote`). Distribution 0's done flash says what was removed.
  - The waiting count shows under the stat table only while the round is running. Plurals: job
    errors pass the arguments their pattern formats as numbers (`JobError.numericArgs`).
- **Follow-up review (2026-11-17):**
  - A file a reassign left waiting may be deleted on Commons (or disqualified) before "verify": the
    paused round is still open, so its row becomes `deleted`. "verify" counts a waiting file as lost
    only if its row is gone or has another status than `in`, `deleted` or `dq`.
  - The invariant baseline is bounded: per invariant the count and the first 50 pages
    (`ViolationBaseline`); a new page is a new violation while the list is complete, past it only a
    count above the baseline. Only invariants 1–3's queries run (`RoundImageInvariants.violations(r,
    only)`), not the counts' drift.
  - Cancel's note counts the files given, left waiting and given aside; distribution 0 has its own
    note (`round.juror.reassign.cancelled.removed`).
  - The stale hint is a status, always in the page and empty; `preview-confirm.js` fills its text and
    sets `aria-describedby` only while it applies.
  - Past a truncated baseline "verify" names no file: `job.error.rebalance.invariants.count` (how many
    more). A state from an earlier build whose `checks` don't read keeps the rest (no baseline); a job
    state that doesn't read is logged at WARN. Cancel's note follows the round's distribution and
    counts rows aside removed too.
- Messages: `job.error.rebalance.busy`, `round.juror.busy` and `error.previous.round.rebalancing` now
  name "jurors' files being moved". Rolling back past this: `docs/deployment.md`.
- Specs: `RoundReassignJobSpec` (quotas, binary/rated ✗/criteria, N = 0–3, D1 restart, waiting and the
  top-up with and without the tick, alone, with a list and with new files, aside rows with restore
  and requalify, a restore caught by verify, a waiting file no longer `in` caught by verify, a deleted
  file's row left, refusals, a freeze between steps, Cancel and its note, Retry = uninterrupted run,
  votes from every juror during the move), `RoundReassignAuthorizationSpec` (§9; the GET writes
  nothing; signed out: 401, or the CSRF 403 with the filter on; the flashes), `RoundEditingFormatsSpec`
  (the state's JSON across the `Passes` split, plurals), a browser story (the ticks and the confirm
  guard, on review.spec's round of one juror per file) and page-tour entry.
  `WritePathStress` was not extended (as in step 1: the concurrency is spec'd).

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
  **Revised 2026-11-25 [user]** for adding a juror only (2026-11-25-ui-path-testing.md §5 Q5): "Add
  juror…" and "Start and give a fair share…" are allowed on stopped rounds too. The job freezes and
  releases the stopped round as a running one (`active_before_freeze = 0`), so it stays stopped in
  every path (done, failed, Cancel, Retry, a restart's takeover) and its jurors keep `round_closed`;
  the juror added ends active. Both are offered exactly when allowed
  (`RoundRebalanceHandler.addOffered` = no `roundRefusal` and no job on the round's key): not on a
  round being set up (`distributing`; the held-in-review exception below stays on its page), frozen
  by another job, legacy, or of a retired contest (which retiring now refuses while a job holds a
  round frozen, `contest.retire.busy.round`). "Reassign files…" stays running-only.

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
  round held in review, where adding only writes the `round_user` row. (Revised 2026-11-25, see D3:
  stopped rounds too.)
- **A ticked stopped donor stays stopped** (add page): D1 covers receivers, who are started again;
  donors only give files away and need not vote.
- **The reassigned juror's unrated aside rows go to receivers:** otherwise a file restored on
  Commons would come back to the stopped juror.
- **Topping up waiting files is opt-in** until the production count (§7's query) shows it can be
  the default.
- **Refused:** fewer receivers than N; legacy rounds.
- **Audit:** log lines and the job row until gap 15.
