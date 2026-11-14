# scripts/review/

Tools for reviewing the stacked PRs: requesting CodeRabbit reviews one PR at a time and posting
the triage replies. The process (who runs them, the triage classes, the triage document) is in
[workflow practices](../../docs/workflow/practices.md) §3 and §6.3; the triage document itself is
`docs/plans/2026-11-03-pr-review-triage.md` on `origin/stack/series-doc`.

Python 3.8+, stdlib only; run from Git Bash with `python -I scripts/review/<tool>.py`. Every
tool takes `--gh PATH` (default: `$GH`, else `gh` on `PATH`; on the orchestrator's machine
`GH=/c/Users/ilya/tools/gh/bin/gh.exe`) and `--repo` (default `UkrainianWiki/wlxjury`). Inputs and
outputs default to `target/review/` (ignored by git), except the CodeRabbit loop's, which live in
`%LOCALAPPDATA%/wlxjury-review` (below). **Only the orchestrator runs the tools that
write to GitHub** (`coderabbit_loop.py`, `post_replies.py`, `edit_replies.py`), and they write
only with `--post` (or `--write`): without it they read GitHub and print what they would do
(`--dry-run` is accepted and means the same). Each of the three has a `--self-test` that checks
its matching on sample data without GitHub.

| Tool | Writes to GitHub | What it does |
|---|---|---|
| `coderabbit_loop.py` | comments `@coderabbitai review` (`full review` when that finished without a review and the summary does not show a clean review) | requests reviews in stack order, one at a time, waiting out the limits; with `--post` it runs until stopped and queues reviews for triage (`--batch N` for one manual batch) |
| `loop_task.py`, `loop-daemon.sh` | no | start the loop detached (`launch`), and register a Windows scheduled task that keeps it started |
| `threads.py` | no | lists a PR's CodeRabbit threads, answered or open; `--save` keeps the raw JSON for triage |
| `first_fix.py` | no | walks the stack's tips and prints where a problem's regex stops matching: the PR a class 1 reply cites |
| `reply_bodies.py` | no | writes reply bodies from a JSON list (`pr`, `id`, `class`, `label`, `text`) and prints the record rows |
| `post_replies.py` | thread replies, issue comments | posts one PR's bodies, skipping those already posted |
| `edit_replies.py` | edits our comments | after a stack rebuild, replaces old commit ids with new ones in our replies on a PR |

## State files can be rebuilt from GitHub

`%LOCALAPPDATA%/wlxjury-review/requests.json` (the loop's log) and
`target/review/triage/replies.json` (posted replies) are not committed. Deleting them loses almost nothing:

- the loop skips every PR that has a CodeRabbit review or a summary marked "reviewed" (a clean
  review, see below), taking its current diff as the reviewed one: what is lost is only a diff
  change made before the file was deleted (that PR is not re-requested). It treats a PR whose latest `@coderabbitai` comment by `ukwikidev` has no answer yet
  as already requested: it waits instead of posting again, and handles the answer as for a new
  request (a limit pauses and counts as a retry, no reply defers the PR). After a limit reply
  it waits out the spacing from that reply and posts the same command again. A latest
  `@coderabbitai review` that finished without a review (and not clean) gets
  `@coderabbitai full review`; a latest `@coderabbitai full review` that finished without one is
  "no review available" (see below). A request about an older head is ignored: the PR is treated
  as never requested and gets a plain `@coderabbitai review`. Stale means: the state's post for
  that request recorded another head sha (a commit pushed after the request); or, for a finished
  request, the summary edited after it covers another `sourceCommitId`; or the request is older
  than the head commit's committer date (commits API; a rebase, amend or triage fix sets it).
  Without the state file the last two still apply;
- `post_replies.py` skips a thread that `ukwikidev` already answered (a reply whose
  `in_reply_to_id` is the thread's comment), and an issue comment that `ukwikidev` already posted:
  one carrying its `<!-- wlxjury-review:<pr>:<name> -->` marker (added on posting), or, among our
  comments with no marker at all (posted before markers existed), one with the same whole body;
- `edit_replies.py` reads our comments from GitHub, not from a state file.

The reply bodies (`target/review/triage/bodies/`) are the work product of a triage: keep them
until the replies are posted.

## What answers a request

Only two things answer `@coderabbitai review`:

- a CodeRabbit **review** submitted after the request: done;
- CodeRabbit's **reply to the command** saying it was limited, skipped or refused.

CodeRabbit's reply to the command is an issue comment carrying
`<!-- This is an auto-generated reply by CodeRabbit -->` and
`<!-- CodeRabbit review command invocation: … -->` (or, for the chat limit, a "Rate Limit
Exceeded" heading), created after the request. It first says "Action performed: Review
triggered." and is edited to "Review finished." when the review is posted: both mean keep
waiting. The walkthrough/summary comment
(`<!-- This is an auto-generated comment: summarize by coderabbit.ai -->`) is never read as a
reply: its "Currently processing" edit means a review is running, and its text is about the PR
(a finding may well mention rate limits). Only its coverage marker is read, for a clean review
(below).

The wait for one request ends 3 hours (awake) after it was posted at the latest, and after 30
minutes with no reply to the command at all: the PR is deferred and asked again later (see
"Running the loop").

### "Review finished" with no review: clean, or a full review

When the reply says "Review finished" and no review was submitted after the request 5 minutes
after that edit, the loop reads the summary comment:

- **Clean** (0 comments): when CodeRabbit reviews a PR and finds nothing it submits **no review
  object**. The summary comment is edited (4–5 s before the reply's "Review finished" edit on
  #215 and #217, 2026-11-10) to "No actionable comments were generated in the recent review",
  its "Reviewing files … between" range runs up to the head, and it carries
  `<!-- final_review_risk_coverage:{"sourceCommitId":…,"coveredCommitId":"<head sha>","kind":"reviewed"} -->`.
  If that marker names the PR's current head with `"kind":"reviewed"` and the comment was edited
  after the request, the PR is recorded as `clean` in `requests.json` and logged as
  `reviewed, 0 comments (clean): … nothing to triage`; it counts toward `--batch`. A PR whose
  summary already covers its current head this way is skipped like a reviewed PR (so a rebuild
  never requests it again).
- **Otherwise**, the loop posts `@coderabbitai full review` once for the PR and waits for it as
  for any request: the same reply matching, limits, retries (counted again from 0) and timeouts.
  A reply saying "already reviewed" or "no new commits" in its main text (not in the blockquoted
  note, which mentions "already reviewed commits" and may be reworded) is handled the same way.
  A completed full review counts toward `--batch` like any review. If the full review also ends
  without a review, the PR is recorded as `no review available` (`outcome` in `requests.json`,
  and in the log) and the loop goes on with the next PR; it does not count toward `--batch`.

Without `--post` the loop logs a clean PR and goes past it, prints
`would post @coderabbitai full review` for a PR that needs one, and goes past a PR with no review
available to the next one.

## CodeRabbit's limits

- **Reviews:** about one per 45–50 minutes on this plan (measured 2026-11-03 on #185–#194: a
  request 30–50 minutes after the last review was refused, the next one ~50 minutes later went
  through). The reply CodeRabbit actually sends states no time:

  ```text
  <summary>Action not completed</summary> … Review rate limited.
  > Note: CodeRabbit is an incremental review system and does not re-review already reviewed commits. …
  ```

  The loop waits 50 minutes and asks again, as often as it takes (after 4 refusals in a row
  the log says "still limited after N retries"). (The note about incremental reviews
  is on every command reply and means nothing on its own.)
- **A stated time:** "Next included review available in N minutes" or "Please wait
  **N minutes and S seconds**" (bold or not) makes the loop wait that long plus 2 minutes and ask
  again (it counts as a retry), however long the wait. A monthly or plan quota message pauses
  the whole loop for `--quota-pause` hours (6) and defers that PR to then.
- **Chat messages per hour:** each reply in a CodeRabbit thread may make it answer, and those
  answers count against an hourly limit; CodeRabbit then answers a command with "Rate Limit
  Exceeded … Please wait **7 minutes and 9 seconds**", handled as above. Post replies 5 s apart
  (`post_replies.py` does) and don't post a large triage while a review is being requested.

## Running the loop

The loop runs on the orchestrator's laptop, in **watch mode**: started by a scheduled task, it
requests reviews until it is stopped and never exits for triage, a limit or a network failure.
Its files live in **`%LOCALAPPDATA%/wlxjury-review`** (`--dir`, or `$WLX_REVIEW_DIR`), outside every
checkout, so removing a worktree or `sbt clean` loses nothing; the scheduled task runs a copy of
the scripts installed there (`bin/`, with a `VERSION` file naming the commit).

```bash
export GH=/c/Users/ilya/tools/gh/bin/gh.exe
D="$LOCALAPPDATA/wlxjury-review"
python -I scripts/review/coderabbit_loop.py --self-test
python -I scripts/review/coderabbit_loop.py --migrate-from /c/scala/wlxjury-followups/target/review  # once
python -I scripts/review/coderabbit_loop.py --first 218        # dry run: what it would do next
python -I scripts/review/loop_task.py register                  # install into $D/bin + scheduled task
python -I "$D/bin/loop_task.py" launch                          # start it now, detached, no window
python -I "$D/bin/coderabbit_loop.py" --status                  # running? waiting for what? halt, budget, queues
python -I "$D/bin/coderabbit_loop.py" --stop                    # waits until it has exited; stays stopped
python -I "$D/bin/coderabbit_loop.py" --resume                  # clear a stop and a halt (then `launch`)
```

Files in `--dir`: `requests.json` (state), `loop.log` (appended), `triage-queue.json`,
`check.json` (PRs to check by hand), `loop-control.json` (halt, posts per day, unpark requests),
`loop-status.json`, `loop.lock` (held while it runs; the OS drops it when the process dies, so a
crash or power loss never blocks a restart), `loop.stop` (`--stop`), `loop.args` (one line of
extra arguments for `loop-daemon.sh`, e.g. `--first 218`), `loop-task.xml`, `bin/`. The JSON files
other commands change too are re-read under a lock file before each change; every JSON file is
written to a temporary file, flushed to disk and renamed.

**What a pass does.** It lists the open PRs with `stack/…` head branches in stack order (from
`--first` up to `--last` or the stack top, read again every pass), and for each PR in turn:

- reviewed, clean, skipped or "no review available" at the PR's current **own diff**: nothing
  (one read when the head did not move; the diff is fetched only when it did);
- never reviewed: `@coderabbitai review`, waiting for the answer as described above;
- reviewed, but its own diff changed since: `@coderabbitai review` (CodeRabbit reviews the new
  commits; a full review is never asked for, since it would post every comment again; "finished"
  without a review is recorded as `nothing new`);
- parked (below): nothing until `--unpark N`.

Then it idles 30 minutes (`--idle`), less when a deferred PR is due, and starts the next pass.

**Own diff, not head sha.** The stack branches often get merges of their base: the head moves,
the PR's diff does not, and re-reviewing all of them would use the review limit for nothing. So
the loop records with each request a hash of the PR's files and their `+`/`-` lines
(`pulls/N/files`, the diff against the merge base; context lines and hunk positions are left
out; a file without a patch counts by its blob sha) and asks again only when that hash differs
from the one of the last review. A triage fix pushed to the PR changes it; a merge of the base, a
stack rebuild that keeps the PR's changes, or a change below it in the stack does not.
`--no-rereview` turns re-requests off.

**Settled before diffs were recorded** (the old state, or found reviewed, clean or skipped on
GitHub): the current diff counts as reviewed and nothing is posted on a guess. If the head moved
after that review (the review's `commit_id`, the summary's covered commit or our post's head is
not the head), the PR goes to `check.json`, listed under "check by hand" by `--status`: look at
the pushes since, request a review by hand if needed, then `--checked N`. (2026-11-13: only #184.)

**Triage queue.** Every completed review with a review object (comments inline or in the body),
and every parked PR, is appended to `triage-queue.json` and listed by `--status`; clean reviews
are not. After triage: `coderabbit_loop.py --triaged 225 227`. `--queue-add N…` puts PRs in by
hand (e.g. older untriaged ones).

**Waits instead of stops.** Each of these is a wait, logged with the time it ends:

| Event | What the loop does |
|---|---|
| rate limit, stated or not, any length | sleeps until then, asks again (no retry cap; the post budget applies) |
| no reply to the command in 30 min, no review 3 h after it (45 min when asked again) | defers the PR (30 min, doubling to 6 h), goes on with the next |
| unexpected answer | defers the PR 6 h |
| quota or plan message | defers the PR and pauses the loop `--quota-pause` hours (6) |
| network error | a read retries every 30 s for 10 min; then the pass restarts after 1, 2, 4 … 30 min |
| gh hangs | each gh call is killed after 300 s: a read is retried, a post is not (the next pass finds it if it went through) |
| other GitHub error (HTTP 4xx/500), a failed post | the pass restarts after 1, 2, 4 … 30 min |
| gh not signed in (HTTP 401) | the pass restarts after `--auth-pause` minutes (60) |
| laptop asleep | waits end at wall-clock times checked every minute; the answer timeouts count awake time only |
| process killed, power loss, reboot | the scheduled task starts it again within 30 min; it resumes from the state and GitHub |

**Limits on posting** (so a reworded or broken CodeRabbit can't make it post all day):

| Limit | Default | Then |
|---|---|---|
| deferrals of one PR | 3 | the PR is **parked**: queued for a look, not asked again until `--unpark N` |
| PRs in a row without a usable answer (no reply, no review, unexpected, quota) | 3 different PRs | **halted**: nothing is posted until `--resume`; `--status` shows why |
| a reply saying reviews are paused / disabled / not allowed | at once | **halted**, as above; after `--resume` the same request is asked again |
| `@coderabbitai` comments per UTC day | 60 (`--posts-per-day`) | waits for the next UTC day |

60 a day: about 2 posts per review (the first is usually rate limited) at ~26 reviews a day.

The circuit breaker has **no time window**: it counts bad outcomes in a row, however far apart
(three PRs without an answer over two days halt it just as three in an hour do), and only a
usable answer (a review, a clean or skipped one, "no review available", a limit reply) starts the
count again. A halt always needs `--resume`.

`--batch N` (one pass, stop after N reviews) and `--once` (one pass) remain for manual use, with
the same waits; there a quota message ends the run with exit 4, a halt with exit 5.

### Scheduled task (Windows)

`loop_task.py register` installs the scripts into `DIR/bin` (as `loop_task.py install` does) and
creates the task **"wlxjury CodeRabbit loop"** for the current user (no elevation, only while
signed in): at logon and every 30 minutes (`--every`), missed runs started on waking, on battery
too, no run-time limit, it runs `pythonw.exe -I DIR/bin/loop_task.py launch --dir DIR`, which
starts `loop-daemon.sh` through Git Bash in a hidden console unless the loop runs (its lock) or
was stopped. If Windows refuses the task with the logon trigger, it registers the repeating
trigger alone. `--print` writes the XML and prints the command without installing or
registering; `loop_task.py show` and `loop_task.py unregister`.

The task runs the installed copy, not a checkout: worktrees are removed after a merge and the
followups tree changes with each stack rebuild. To update the loop, from the checkout to use:

```bash
python -I "$D/bin/coderabbit_loop.py" --stop      # waits (up to 2 min, --stop-wait) until no loop runs
python -I scripts/review/loop_task.py install     # or register; replaces each file by a rename
python -I "$D/bin/coderabbit_loop.py" --resume
python -I "$D/bin/loop_task.py" launch
```

`--stop` exits 1 if a loop still holds the lock after the wait (run it again, or look at
`--status`). `install` writes each file to a temporary name in `bin/` and renames it over the old
one, so a bash still running the old `loop-daemon.sh` keeps reading the old file; on Windows a
file held open without delete sharing makes `install` refuse with "stop the loop first".

## Triage after a review

1. `python -I scripts/review/threads.py N --save` lists the threads.
2. Class each comment (workflow practices §3.1), checking it against the later tips and the plans:
   1. **fixed later in the stack:** name the first later PR whose tip no longer has it
      (`first_fix.py --from N PATH REGEX`);
   2. **already planned:** link the plan section;
   3. **not fixed and not planned:** add an entry `R<PR>-<n>` to the triage document (severity,
      file:line at the PR's tip and at the final tip, the failure scenario, a suggested fix, the
      plan or batch that should take it) and link it;
   4. **not applicable or a false positive:** give the reason.
   Remarks outside the diff get one `summary` reply.
3. Write the replies as JSON, run `reply_bodies.py replies.json --table`, review the bodies, and
   add the table rows and new entries to the triage document (orchestrator).
4. `post_replies.py N` (lists what it would post), then `post_replies.py N --post`.
5. `coderabbit_loop.py --triaged N` (the loop kept requesting reviews meanwhile).

Edits to another agent's PR body, and to the triage document on `stack/series-doc`, go through
the orchestrator or the user.

Not kept here: the one-off scripts of the first triage rounds (the stack-aware review comments on
#198–#230, the triage document's table builder, the #184 checks); their results are in the
triage document.
