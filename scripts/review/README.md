# scripts/review/

Tools for reviewing the stacked PRs: requesting CodeRabbit reviews one PR at a time and posting
the triage replies. The process (who runs them, the triage classes, the triage document) is in
[workflow practices](../../docs/workflow/practices.md) §3 and §6.3; the triage document itself is
`docs/plans/2026-11-03-pr-review-triage.md` on `origin/stack/series-doc`.

Python 3.8+, stdlib only; run from Git Bash with `python -I scripts/review/<tool>.py`. Every
tool takes `--gh PATH` (default: `$GH`, else `gh` on `PATH`; on the orchestrator's machine
`GH=/c/Users/ilya/tools/gh/bin/gh.exe`) and `--repo` (default `UkrainianWiki/wlxjury`). Inputs and
outputs default to `target/review/` (ignored by git). **Only the orchestrator runs the tools that
write to GitHub** (`coderabbit_loop.py`, `post_replies.py`, `edit_replies.py`), and they write
only with `--post` (or `--write`): without it they read GitHub and print what they would do
(`--dry-run` is accepted and means the same). Each of the three has a `--self-test` that checks
its matching on sample data without GitHub.

| Tool | Writes to GitHub | What it does |
|---|---|---|
| `coderabbit_loop.py` | comments `@coderabbitai review` | requests reviews in PR order, one at a time, waiting out the limits; stops after `--batch` reviews for triage |
| `threads.py` | no | lists a PR's CodeRabbit threads, answered or open; `--save` keeps the raw JSON for triage |
| `first_fix.py` | no | walks the stack's tips and prints where a problem's regex stops matching: the PR a class 1 reply cites |
| `reply_bodies.py` | no | writes reply bodies from a JSON list (`pr`, `id`, `class`, `label`, `text`) and prints the record rows |
| `post_replies.py` | thread replies, issue comments | posts one PR's bodies, skipping those already posted |
| `edit_replies.py` | edits our comments | after a stack rebuild, replaces old commit ids with new ones in our replies on a PR |

## State files can be rebuilt from GitHub

`target/review/requests.json` (the loop's log) and `target/review/triage/replies.json` (posted
replies) are not committed. Deleting them loses nothing:

- the loop skips every PR that has a CodeRabbit review, and treats a PR whose latest
  `@coderabbitai` comment by `ukwikidev` has no answer yet (see below) as already requested: it
  waits instead of posting again. After a limit reply it waits out the spacing from that reply;
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
(`<!-- This is an auto-generated comment: summarize by coderabbit.ai -->`) is never read for
answers: its "Currently processing" edit means a review is running, and its text is about the PR
(a finding may well mention rate limits).

The wait for one request ends 3 hours after it was posted at the latest; with no reply to the
command at all after 30 minutes, the loop stops (exit 2).

## CodeRabbit's limits

- **Reviews:** about one per 45–50 minutes on this plan (measured 2026-11-03 on #185–#194: a
  request 30–50 minutes after the last review was refused, the next one ~50 minutes later went
  through). The reply CodeRabbit actually sends states no time:

  ```text
  <summary>Action not completed</summary> … Review rate limited.
  > Note: CodeRabbit is an incremental review system and does not re-review already reviewed commits. …
  ```

  The loop waits 50 minutes and asks again, up to 4 times; then it stops (exit 3) with a message
  saying how many retries were refused. Start it again later. (The note about incremental reviews
  is on every command reply and means nothing on its own.)
- **A stated time:** "Next included review available in N minutes" or "Please wait
  **N minutes and S seconds**" (bold or not) makes the loop wait that long plus 2 minutes and ask
  again (it counts as a retry). A stated wait over 3 hours, or a monthly or plan quota message,
  stops it.
- **Chat messages per hour:** each reply in a CodeRabbit thread may make it answer, and those
  answers count against an hourly limit; CodeRabbit then answers a command with "Rate Limit
  Exceeded … Please wait **7 minutes and 9 seconds**", handled as above. Post replies 5 s apart
  (`post_replies.py` does) and don't post a large triage while a review is being requested.
- A command reply that says "already reviewed" or "no new commits" (beyond the standard note) is
  asked again once as `@coderabbitai full review`.

## Running the loop

One PR at a time, with triage after each review (the user's choice, 2026-11-03). Run it in the
background and keep its log:

```bash
export GH=/c/Users/ilya/tools/gh/bin/gh.exe
python -I scripts/review/coderabbit_loop.py --self-test
python -I scripts/review/coderabbit_loop.py --first 194 --last 249 --batch 1          # what it would do
python -I scripts/review/coderabbit_loop.py --first 194 --last 249 --batch 1 --post >> target/review/loop.log 2>&1
```

Exit codes: 0 batch done (or all PRs done), 2 no reply to the command in 30 minutes or no review
3 hours after the request, 3 still limited after the retries or a stated wait over 3 hours,
4 an unexpected answer or a quota message (read the message in `requests.json` and the PR).

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
5. Start the loop again for the next PR.

Edits to another agent's PR body, and to the triage document on `stack/series-doc`, go through
the orchestrator or the user.

Not kept here: the one-off scripts of the first triage rounds (the stack-aware review comments on
#198–#230, the triage document's table builder, the #184 checks); their results are in the
triage document.
