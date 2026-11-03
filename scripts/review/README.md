# scripts/review/

Tools for reviewing the stacked PRs: requesting CodeRabbit reviews one PR at a time and posting
the triage replies. The process (who runs them, the triage classes, the triage document) is in
[workflow practices](../../docs/workflow/practices.md) §3 and §6.3; the triage document itself is
`docs/plans/2026-11-03-pr-review-triage.md` on `origin/stack/series-doc`.

Python 3.8+, stdlib only; run from Git Bash with `python -I scripts/review/<tool>.py`. Every
tool takes `--gh PATH` (default: `$GH`, else `gh` on `PATH`; on the orchestrator's machine
`GH=/c/Users/ilya/tools/gh/bin/gh.exe`) and `--repo` (default `UkrainianWiki/wlxjury`). Inputs and
outputs default to `target/review/` (ignored by git). **Only the orchestrator runs the tools that
write to GitHub** (`coderabbit_loop.py`, `post_replies.py`, `edit_replies.py`); each has
`--dry-run`.

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
  `@coderabbitai` comment by `ukwikidev` has no CodeRabbit answer yet as already requested (it
  waits instead of posting again);
- `post_replies.py` skips a thread that `ukwikidev` already answered (a reply whose
  `in_reply_to_id` is the thread's comment), and an issue comment that `ukwikidev` already posted
  (its `<!-- wlxjury-review:<pr>:<name> -->` marker, added on posting, or the same first line);
- `edit_replies.py` reads our comments from GitHub, not from a state file.

The reply bodies (`target/review/triage/bodies/`) are the work product of a triage: keep them
until the replies are posted.

## CodeRabbit's limits

- **Reviews:** about one per 30–60 minutes on this plan (measured 2026-11-03: waits of 45–49
  minutes between reviews). The answer "Next included review available in N minutes" makes the
  loop wait N+2 minutes and ask again; a wait past 3 hours, or a monthly or plan quota message,
  stops it.
- **Chat messages per hour:** each reply in a CodeRabbit thread may make it answer, and those
  answers count against an hourly limit; "Please wait N minutes" is handled like the review
  limit. Post replies 5 s apart (`post_replies.py` does) and don't post a large triage while a
  review is being requested.
- A review that says "incremental" or "already reviewed" is asked again once as
  `@coderabbitai full review`.

## Running the loop

One PR at a time, with triage after each review (the user's choice, 2026-11-03). Run it in the
background and keep its log:

```bash
export GH=/c/Users/ilya/tools/gh/bin/gh.exe
python -I scripts/review/coderabbit_loop.py --first 191 --last 249 --batch 1 --dry-run   # what it would do
python -I scripts/review/coderabbit_loop.py --first 191 --last 249 --batch 1 >> target/review/loop.log 2>&1
```

Exit codes: 0 batch done (or all PRs done), 2 no answer in 30 minutes, 3 limit wait too long,
4 an unexpected answer (read the message in `requests.json` and the PR).

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
4. `post_replies.py N --dry-run`, then `post_replies.py N`.
5. Start the loop again for the next PR.

Edits to another agent's PR body, and to the triage document on `stack/series-doc`, go through
the orchestrator or the user.

Not kept here: the one-off scripts of the first triage rounds (the stack-aware review comments on
#198–#230, the triage document's table builder, the #184 checks); their results are in the
triage document.
