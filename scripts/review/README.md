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
| `coderabbit_loop.py` | comments `@coderabbitai review` (`full review` when that finished without a review and the summary does not show a clean review) | requests reviews in PR order, one at a time, waiting out the limits; stops after `--batch` reviews for triage |
| `threads.py` | no | lists a PR's CodeRabbit threads, answered or open; `--save` keeps the raw JSON for triage |
| `first_fix.py` | no | walks the stack's tips and prints where a problem's regex stops matching: the PR a class 1 reply cites |
| `reply_bodies.py` | no | writes reply bodies from a JSON list (`pr`, `id`, `class`, `label`, `text`) and prints the record rows |
| `post_replies.py` | thread replies, issue comments | posts one PR's bodies, skipping those already posted |
| `edit_replies.py` | edits our comments | after a stack rebuild, replaces old commit ids with new ones in our replies on a PR |

## State files can be rebuilt from GitHub

`target/review/requests.json` (the loop's log) and `target/review/triage/replies.json` (posted
replies) are not committed. Deleting them loses nothing:

- the loop skips every PR that has a CodeRabbit review or a clean summary at its head (see
  below), and treats a PR whose latest `@coderabbitai` comment by `ukwikidev` has no answer yet
  as already requested: it waits instead of posting again, and handles the answer as for a new
  request (a limit pauses and counts as a retry, no reply stops with exit 2). After a limit reply
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

The wait for one request ends 3 hours after it was posted at the latest; with no reply to the
command at all after 30 minutes, the loop stops (exit 2).

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

## Running the loop

One PR at a time, with triage after each review (the user's choice, 2026-11-03). Run it in the
background and keep its log:

```bash
export GH=/c/Users/ilya/tools/gh/bin/gh.exe
mkdir -p target/review
python -I scripts/review/coderabbit_loop.py --self-test
python -I scripts/review/coderabbit_loop.py --first 194 --last 249 --batch 1          # what it would do
python -I scripts/review/coderabbit_loop.py --first 194 --last 249 --batch 1 --post >> target/review/loop.log 2>&1
```

Exit codes: 0 batch done (or all PRs done), 2 no reply to the command in 30 minutes or no review
3 hours after the request, 3 still limited after the retries or a stated wait over 3 hours,
4 an unexpected answer or a quota message (read the message in `requests.json` and the PR).
"Review finished" without a review no longer stops it (see above).

A gh **read** that fails on the network (`common.is_transient`: a dropped, reset or refused
connection, `dial tcp` / `connectex` (Windows), a DNS failure, a timeout, EOF, a TLS handshake
error, HTTP 502/503/504) is retried every 30 s, up to 20 times; GitHub's answers (HTTP 4xx,
including a rate limit) raise at once, and a **write** (a comment, a reply, an edit) never
retries. `python -I -B -m unittest discover -s scripts/review` tests it (CI: deployment.yml,
"Review tools tests").

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
