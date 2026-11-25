# Development workflow

How work moves from a plan to production: branches and PRs to master, the batch cycle, review
triage, worktrees and the shared sbt lock, commits, integration, releases and the changelog.
Each rule has a short why and an example in this repository. Agent rules (models, effort,
`agent-check.py`, bounded waits) are §9; what to test and when is
[testing/practices.md](../testing/practices.md) §8. **Sources:** the roadmap's "Running agents
(how)" ([`2026-11-02-roadmap.md`](../plans/2026-11-02-roadmap.md#running-agents-how)); the PR series
and the review triage ([`2026-11-03-pr-series.md`](../plans/2026-11-03-pr-series.md),
[`2026-11-03-pr-review-triage.md`](../plans/2026-11-03-pr-review-triage.md)); the hotfix record in
[`docs/history/security/`](../history/security/2026-11-02-role-allowlist-hotfix.md);
[`CHANGELOG.md`](../../CHANGELOG.md).

## Contents

1. [Plans and the roadmap](#1-plans-and-the-roadmap)
2. [The batch cycle](#2-the-batch-cycle)
3. [Review triage](#3-review-triage)
4. [Worktrees, the sbt lock and the environment](#4-worktrees-the-sbt-lock-and-the-environment)
5. [Commits](#5-commits)
6. [Branches, PRs and merging](#6-branches-prs-and-merging)
7. [Releases, hotfixes and the changelog](#7-releases-hotfixes-and-the-changelog)
8. [Keeping the practices docs current](#8-keeping-the-practices-docs-current)
9. [Agent efficiency](#9-agent-efficiency)
10. [Guards](#10-guards)

## 1. Plans and the roadmap

**1.1 Work starts from a plan, and the roadmap indexes every plan.** A batch has a plan section
(scope, decisions, checks) under `docs/plans/`; the roadmap names its branch, state and what it
waits for, and is updated when a batch starts, lands or gets a decision. *Why:* an agent or a
person restarting from the roadmap finds every open thread (roadmap header).

**1.2 Decisions are the user's and are recorded.** A plan lists its open questions; the answer
goes into the plan with "(user, <date>)", and the roadmap's "Inputs still needed" lists what
still waits. *Example:* round-images D1–D20, all decided 2026-11-02.

**1.3 A plan's lifecycle** (active → done → rules folded into practices → deleted, or moved to
`docs/history/`) is in [docs/README.md](../README.md) §2.

**1.4 A plan exists in the repository from its first draft.** A plan or design is committed to
`docs/plans/` (a draft PR while undecided), never kept only in an agent's scratchpad or a temp
directory; see CLAUDE.md "Plans and decisions as an agent". *Why:* a reviewed plan with 16
open decisions was lost with a cleaned scratchpad (2026-11-25).

## 2. The batch cycle

**2.1 Implement → orchestrator check → independent review → fixes → follow-up review.** The
implementer works on its own branch and worktree and records results in the plan; the
orchestrator checks the diff and the results before asking for review; an independent reviewer
gets the final diff, the invariants and the validation results; the implementer fixes; a second
review only if the fixes are material. *Why:* an author doesn't see their own gaps (the docs
consolidation's review found a lost escape in a copied query), and one review plus one follow-up
is normally enough (§9.6).

**2.2 One batch, one theme.** A batch does what its plan section says; a finding outside it goes
to a plan or the triage (§3), not into the diff. At most 2–3 batches are open at once (PR series,
"Per-PR gate"). *Why:* a reviewer can check one theme; parallel batches touching the same files
conflict on merge.

**2.3 Results with ids.** A batch records the commit it tested, the command, totals and the log
path under `target/checks/` (performance §2.5 for measurements). *Example:* "235 total, 0 failed,
1 skipped (`target/checks/20261103-142924-27616.log`)" (PR series, revision 5).

## 3. Review triage

**3.1 Every valid review comment gets one class** (CodeRabbit's and our own stack-aware
reviews alike):
1. **fixed later in the stack**: the reply names the first later PR whose tip no longer has it;
2. **already planned**: the reply links the plan section;
3. **not fixed and not planned**: a new entry in the triage document (id `R<PR>-<n>`, severity,
   the tip's file:line and at the final tip, the failure scenario, a suggested fix, the plan or
   batch that should take it), and the reply links it;
4. **not applicable or a false positive**: the reply gives the reason.

*Why:* in a stack, most comments on an early PR are already fixed later; replying with the PR
that fixes it avoids reopening done work, and the entries keep the rest from being lost.
*Example:* R198-1 (a job that crashes the JVM is retried forever) is cited by performance §4.2.

**3.2 A stack-aware review** checks each finding against the later tips and the plans before
flagging it, and flags only what is neither fixed later nor planned.

**3.3 CodeRabbit one PR at a time.** Its reviews come about one per 45–50 minutes and its chat
answers are limited per hour, so reviews are requested one PR at a time, in stack order. The
user's choice of 2026-11-03 was to request one review, triage it, then request the next. On
2026-11-13 the maintainer asked for the loop to run continuously on its own (started, restarted
and continued automatically, from a scheduled task) with a triage queue, so it no longer waits
for triage: each review with comments, and each parked PR, is queued, and the queue can run
ahead of triage. The orchestrator triages from the queue (replies in each thread, new entries
in the triage document) and marks the PR triaged. A fix pushed to a PR during triage changes the
PR's own-diff hash, so the loop requests a re-review (queued again if it has comments); a merge
of the base, or a stack rebuild that keeps the PR's changes, does not. The loop halts until
`--resume` after 3 different PRs in a row without a usable answer, or on a reply saying reviews
are paused or disabled. The tools and their limits:
[`scripts/review/`](../../scripts/review/README.md); the loop's files live outside every checkout
and its request state is rebuilt from GitHub, so nothing is lost with a session.

## 4. Worktrees, the sbt lock and the environment

**4.1 One worktree per batch.** `git worktree add ../wlxjury-<name> -b <topic>/<name> origin/master`,
with the topic prefixes of CONTRIBUTING.md (`fix/`, `feature/`, `docs/`, ...).
The user's own checkout of this repository (with untracked files) and of wlxjury-infra (uncommitted
work and secrets) are never modified by agents. Never link or copy
`node_modules` or `data/cache` between worktrees: `sbt test`'s `jsTest` runs `npm ci`, which wiped
the original checkout's packages through a junction; a copied fixture dump measures another schema.

**4.2 One sbt or test JVM at a time, across all worktrees.** The lock is the directory
`.sbt-jvm.lock` in the folder that holds the repository and its worktrees, the same for every
worktree: `LOCK="$(cd "$(git rev-parse --git-common-dir)/../.." && pwd)/.sbt-jvm.lock"`
(`AGENT_CHECK_LOCK` overrides it for `agent-check.py`), with an `owner` file inside: `owner=` (the agent or person), `pid=`,
`host=`, `started=` (ISO time with offset), `timeout=` (seconds, 0 for none), `cwd=` and
`command=`, one per line. Prefer `python scripts/agent-check.py --lock --owner <name> -- <sbt
command>`: it takes the lock, writes `owner` (its own PID: the process that holds the lock for
the whole run), and removes both when the run ends, a timeout (exit 124) included; if writing
`owner` fails it removes the lock again. A held lock is waited for (`--lock-wait`, default
3600 s). Name yourself with `--owner` or
`AGENT_CHECK_OWNER`. By hand: take it with its own command, `mkdir
"$LOCK"`, check that it succeeded, then write `owner` without `pid=` (a Bash tool
shell's `$$` exits with the command, so the lock would read as abandoned): `printf
'owner=%s\nstarted=%s\ncommand=%s\n' <name> "$(date -Iseconds)" '<command>' >
"$LOCK/owner"`; release with `rm "$LOCK/owner"` and `rmdir "$LOCK"`, only if you took it. If the directory exists, another run holds it: wait
and retry, never delete it. Not waited on, but reported (`agent-check.py` exits 75) with the `owner` file to the orchestrator
or the user, who releases it only under §4.6 (no script or agent removes it on its own): a lock
whose `pid=` on this `host=` no longer runs (`agent-check` prints `LOCK OWNER GONE`: a run killed
hard, e.g. by a tool's time limit), and one older than max(1 h, `timeout=` + 5 min) by `started=`,
or the directory's time if there is no `owner` (`STALE LOCK`). Release it between runs, not at the
end of a session, and never chain the lock, the run or the release with `git commit` in one
command. *Why:* two test JVMs share the Docker VM's memory and the laptop's, and race on ports
and containers; a lock removed by a chained command that failed halfway is held forever or
released for someone else's run; and a lock without an owner, left by an agent that stopped while
its host slept, had others waiting all night with nobody to ask.

**4.3 The `env -u` prefix.** Every sbt or Docker run by hand on a machine with bot credentials:
`env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD <command>`
(testing §5.2).

**4.4 Windows.** sbt runs from Git Bash with the direct launcher and UTF-8, since `sbt.bat` can
strip quotes from `set ... Seq(...)` commands; Windows PowerShell 5.1 splits
`-Dfile.encoding=UTF-8` at the dot and drops the inner double quotes of the `set` argument when
passing them to a native program. The command lines are in CLAUDE.md, "Build, run and test".

**4.5 gh.** Scripts take the GitHub CLI from `GH` or `--gh`, else `gh` on `PATH` (set `GH` where
it isn't on `PATH`). Agents use it to read (PR lists, checks,
logs) unless the task says to write.

**4.6 After an agent fails, stalls or is stopped, the orchestrator cleans up what it left.** Right
away, before starting another run: list the processes it may have left (`java`, `node`, `python`
running `agent-check.py`; on Windows PowerShell's `Get-CimInstance Win32_Process -Filter
"Name='java.exe'" | Select ProcessId,ParentProcessId,CreationDate,CommandLine`, since `wmic` is
gone from current Windows 11), the Docker
containers it started (`docker ps --format '{{.ID}} {{.CreatedAt}} {{.Names}} {{.Image}}'`:
Testcontainers' `mariadb` and its Ryuk container), and the sbt lock (§4.2, its `owner` file).
Stop or release only what is verifiably that agent's: the command line names its worktree or its
command, and the start time falls within its run; the lock's `owner` names it, or its `pid=` is
gone and `started=` is within its run. Anything else (another agent's JVM, the user's dev server,
a lock with another owner) is left alone and reported. Record what was found and done (PIDs,
container ids, the lock's `owner` text) in the batch's results or the session's notes. *Why:*
after the host slept mid-run, a stopped agent's forked test JVM and its container kept the
memory and the lock, and the next runs waited on them; cleaning up by name alone (`taskkill /IM
java.exe`) would have killed a parallel batch's run.

## 5. Commits

**5.1 Commit only your paths.** `git commit -- <paths>`, never `git commit -a` in a shared tree.
`git add <paths>` and then a bare `git commit` commits everything staged, someone else's staged
files too: check `git diff --cached --name-only` first, or keep the pathspec on the commit.
*Why:* other agents' and the user's files are in the same status and the same index.

**5.2 Messages from a file.** Write the message with the Write tool into your own scratch folder
and commit with `git commit -F <file>`; never a heredoc or a multi-line `-m` (backslashes and
quotes get lost in the shell). The subject says what changed; the body says why and names the
plan.

**5.3 Trailers.** End the message with the attribution line the session gives (for example
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`). A squashed or curated commit keeps
the author, committer and dates of the last original commit in its range, adds
`Co-authored-by:` for every other distinct author in it, and one Claude trailer only if an
original had one (PR series, "Authorship and dates"). *Why:* the stack must show who wrote the
work and when, not when it was rearranged.

**5.4 Never amend or force.** New commits for fixes; no `--amend`, rebase or force push on a
pushed branch; no `--no-verify`.

## 6. Branches, PRs and merging

Since 2026-11-20 every change is a PR to `master`, merged as one squash commit. The short version
for people is [CONTRIBUTING.md](../../CONTRIBUTING.md).

**6.1 Branch from master, one PR per change.** `git switch -c <topic>/<short-name> origin/master`
(`fix/`, `feature/`, `docs/`), in its own worktree when several changes are in flight (§4.1); the
PR targets `master` and keeps to one theme (§2.2). *Why:* a squash makes one commit per PR, which
is reviewed, bisected and reverted as one change.

**6.2 Squash with GitHub's button.** `master` requires a PR, linear history, no force push and no
deletion (enforced). Planned, once the maintainer sets them: "Squash and merge" as the only merge
method and head branches deleted after the merge; until then, use "Squash and merge". The PR
title and description become the commit's message, so the title says what changed and the
description is a short summary of why (the PR template); long notes, screenshots and logs go in
comments. The PR's own commits stay on the PR, not on master. *Why:* master reads as one line of
reviewed changes; the work history stays one click away.

**6.3 Who pushes and edits PRs, and without force.** Contributors push their own branches and
open their PRs. Nobody force-pushes a branch under review. Only the PR's author or a maintainer
edits a PR body; anyone else who needs a change there asks for it. A PR is merged by a maintainer.
*Why:* two writers of one body overwrite each other.

**6.4 Review fixes are new commits; master comes in by a merge.** Fix on the branch with new
commits (§5.4); take newer master with `git merge origin/master`, never a rebase. The squash
flattens it all anyway. *Why:* reviewers' threads stay attached to the commits they commented on.

**6.5 Stack only when a change needs an unmerged one.** The child PR targets the parent's
branch; merge bottom-up. After the parent PR is squash-merged, find its squash commit
(`Sp=$(git log origin/master --grep '(#NNN)' --format=%H -1)`, with the parent's PR number), and
in the child branch run `git merge "$Sp^"` (a normal merge of master just before the squash; on
a conflict keep the child's side), then `git merge -s ours "$Sp"`, then check that
`git diff origin/master...HEAD --stat` shows only the child's changes, and push. If the parent's
branch was deleted, GitHub retargets the child PR to master; otherwise change its base to
`master` by hand. Merges only. *Why:* the squash shares no history with the parent's commits, so
a plain merge of master would conflict on the parent's own lines.

**6.6 CI must be green** before merging (testing §8.3). Planned (pending the maintainer's
settings): `sbt test (mariadb:10.6.22)` as a required check. Until then a maintainer checks it
before merging. The browser tests and coverage run on every PR but are not to be required until
reruns on one master SHA separate flakes from failures; a red one is still looked at (testing §7).

**6.7 Branches from before 2026-11-20.** Until then finished work collected on
`ukwikidev/followups` and reached GitHub as stacked PRs (`[NN] <title>` on `stack/NN-<theme>`,
each based on the previous one; the series and its rules are described in
`docs/plans/2026-11-03-pr-series.md`). Master then got that work as squash commits, one per
stacked PR, #184–#390, with the top `88a0355e` (its tree is `stack/207-guide-shots`'s). A branch
made on the old history bridges once: if it contains `origin/stack/207-guide-shots`,
`git merge -s ours -m "Bridge: master's squash of #184-#390 has this content" 88a0355e`; if not,
first `git merge origin/stack/207-guide-shots`, then the same. `git diff 88a0355e...HEAD --stat`
must show only the branch's own changes. The rest of the stack (#391–#395) goes to master
bottom-up by §6.5. `stack/207-guide-shots` stays until every bridged branch is merged.

## 7. Releases, hotfixes and the changelog

**7.1 A release is a tree that was tested.** Its commit had the full suite run (or the same tree
did); the roadmap's "Before any production deploy" lists the maintainer's manual checks per release
(every release, then per migration range); the ZIP is built only after them. At the local release
build `test_database_restore` always runs and `test_release_script` only if `scripts/deployment/`
changed since the last released version; the release record lists which ran and why (operations
§3.3). The deploy itself is
[deployment.md](../deployment.md) and [operations](../operations/practices.md) §4.

**7.2 Releases are cut from master; hotfixes go to master first.** From 26.11.15 on, a release
is a release-commit PR on master (§7.4), squash-merged, and its tag goes on that master commit.
Earlier releases are recorded by their `release/<version>` branches (26.11.14 was deployed on
2026-11-24 from `release/26.11.14`, kept in the private archive; 26.11.15 is the first release
cut from this repository's master). A hotfix while master has unreleased work: the smallest fix as a PR to
master first; then a `release/<version>` branch from the last release's tag (once release tags
exist; until then from its `release/<version>` branch) gets the squash commit with
`git cherry-pick -x`, and the hotfix release is built, deployed and tagged there. Planned
(pending the maintainer's settings): a ruleset protecting `release/*`. A fix that needs its own
runbook or data script gets them in the same PR; the hotfix record moves to `docs/history/` and
is deleted when a release replaces it. *Why:* production can't take the untested distance
between its release and master for one fix, and a fix made on master first can't be lost by the
next release. *Example:* 26.11.13.1, on `release/26.11.13.1` from `release/26.11.13`.

**7.3 The changelog** (`CHANGELOG.md`) lists each change in one short user-facing line (decided
2026-11-24; [CONTRIBUTING.md](../../CONTRIBUTING.md), "Changelog entries"). Each release has
**Features**, **Fixes**, **Security** and **Operations** groups as needed, with its migrations on one
line; implementation detail stays in the PRs and the git history. It leaves out unmerged work,
hosting and server details, refactors, tests-only, docs-only and CI changes. "Unreleased
(on master, not yet deployed)" comes first, then the release built but not deployed. Deployed releases are dated from their migrations
(production's `flyway_schema_history.installed_on`, the first migration a deploy applied; a change
without a migration is dated from a deploy record); earlier releases by their development period
or git tag. A batch adds its line under Unreleased in the same PR; a deploy moves the block under
its release heading (§7.4) with its date. Cite only this repository's PRs, as `(#123)`.

**7.4 Calendar versions** (decided 2026-11-03). A release is named `YY.MM` for the month it
ships (`26.11`), with `.N` for an extra deploy in the same month (`26.11.1`, `26.11.2`). Between
releases `build.sbt` carries the next release as a snapshot (`version := "26.11-SNAPSHOT"`;
rename it if the release slips to a later month). Releasing:
1. the release commit sets `version := "26.11"`: a PR to master, squash-merged, then the tag on
   that commit (a hotfix release: on its `release/<version>` branch, §7.2; up to 26.11.14 each
   release commit was on its own `release/<version>` branch);
2. build with `-Dwlxjury.buildId=26.11` (the ID becomes `26.11-<commit>`, the ZIP
   `wlxjury-26.11.zip`; [deployment.md](../deployment.md), "Build and rehearse");
3. after the deploy, the changelog heading becomes `## 26.11 (YYYY-MM-DD)` with the deploy date
   (§7.3), and the next PR to master sets the next snapshot:
   `26.12-SNAPSHOT`, or `26.11.1-SNAPSHOT` if another deploy is planned in the same month. Never
   leave `26.11-SNAPSHOT` after `26.11` ships: a snapshot names the release still to come, and
   later builds would read as older than the deployed `26.11`.

The release artifact is the ZIP (`sbt dist`); there are no Debian or RPM packages (dropped
2026-11-04). Builds before the scheme are `0.13-…` and keep their names; `26.11` sorts after
`0.13`. *Why:* the version says when a deployed build shipped. *Guard:* `deployment.BuildIdentitySpec` checks the version's form in the packaged
manifest.

## 8. Keeping the practices docs current

**8.1 The rule.** A batch that adds a test layer, a tool, a guard or a rule updates the matching
practices doc (and its checklist line and guards table) in the same PR; the reviewer checks it.
Which doc is which: [docs/README.md](../README.md) §3. *Why:* the docs are read before a change
(CLAUDE.md's pointers); a rule only in a plan is lost when the plan is deleted.

**8.2 Code that contradicts a rule** goes to the practices doc's "where the code doesn't follow
these yet" list (db §11) or the triage, not silently into the rule's wording.

**8.3 Lists that follow the code.** A PR that fixes an entry of a "where the code doesn't follow
these yet" list, or a security register item, updates that entry in the same PR; a release
that deploys a register item's fix updates its status. *Why:* stale lists send triage after
fixed code and hide what is really open.

## 9. Agent efficiency

These rules apply to every coding agent (Codex and Claude). What to test and when is
[testing](../testing/practices.md) §8; how to benchmark is
[performance](../performance/practices.md) §2.

**9.1 Choose the model and effort for the task**, not by defaulting to the most capable option.
Use a lower-cost near-peer for routine implementation and independent reviews when it meets the
need: GPT-6.1 Sol is described as near GPT-6 Astra on coding, computer use and professional work
at one-fifth of Astra's standard input/output token prices; Claude Opus 5.5 is described as
performing at Claude Fable 5.1 level on most work, with 60% lower standard input/output token
prices ($4/$20 vs. $10/$50 per million tokens). Check current prices and task-specific benchmark
gaps before extended or high-volume use; token prices do not necessarily map directly to a
product's subscription quota. Escalate to the more expensive model when a relevant capability
gap, task difficulty or a failed first attempt justifies it. Use medium effort for ordinary
implementation and low effort/a smaller available model for mechanical extraction, log summaries
and simple edits when the host allows selection. Reserve high effort for unresolved
authorization, migrations, concurrency or architecture decisions; high effort does not by itself
require the most expensive model. Instructions cannot change a running model: report the actual
setting rather than claiming a switch. Codex defaults are in `.codex/config.toml`; explicit
app/session choices may override them. Sources:
[GPT-6.1 Sol](https://openai.com/index/introducing-gpt-6-1-sol/) and
[Claude Opus 5.5](https://www.anthropic.com/claude-opus-5-5).

**9.2 Keep the task bounded.** Confirm the signed-in account and target contest before
investigating a reported permission failure. Do not weaken authorization because another account
has the expected role.

**9.3 Keep the context small, not the evidence.** Search with `rg -n` first, then read the
relevant range. Do not repeatedly dump whole controllers, plans, dependency catalogs or tool
inventories. Parse structured results with scripts; give the model the derived table and
anomalies, not raw samples. Preserve full logs and machine-readable benchmark results. Reduce
what enters model context, not evidence or required safety/release checks. Do not change
sandbox, approval or deployment safeguards to save usage.

**9.4 Run checks through `scripts/agent-check.py`.** `python scripts/agent-check.py -- COMMAND`
saves full output under `target/checks/`, prints a bounded summary, and preserves the exit code.
To inspect an existing log: `python scripts/agent-check.py --summarize PATH --exit-code 1`.
Inspect a named failing block only if the summary is insufficient. A successful failure-path test
may log `[error]`; use test totals and the process exit code, not that prefix alone. `--lock`
takes the shared sbt lock (§4.2); `--timeout` stops a stalled run (testing §8.1a). Compile
changed Scala/tests before a costly run; on Windows use the direct sbt launcher from Git Bash
(§4.4).

**9.5 Long runs and waits.** The Bash tool's foreground limit is 600 s (10 min): run a check
expected to take longer (a cold compile, the full suite, a Gatling or stress run) with
`run_in_background`, inside `agent-check.py --timeout …`, and wait for its completion notice
instead of a foreground call that the tool would cut off. While a process runs, use the tool's
bounded wait (up to 60 seconds) rather than alternating sleep, clock, tail and status calls. Keep
required user updates short. Do not reopen unchanged source, reanalyse the plan, or print raw
logs on each wait. Resume investigation only on completion, failure, or a meaningful progress
change.

**9.6 The independent reviewer.** Use an independent reviewer only when requested or required.
Give it the final diff, relevant invariants and concise validation results. One review plus one
follow-up for material fixes is normally enough; do not re-review each assertion, import or
helper qualification. Do not rerun tests in the reviewer unless a specific uncertainty requires
it.

## 10. Guards

| Rule | Enforced by | State |
|---|---|---|
| 4.2 one sbt JVM at a time | the lock directory; `agent-check.py --lock` writes `owner`, waits, reports a lock older than an hour (`scripts/test_agent_check.py`) | by convention (taking it by hand stays possible) |
| 4.2, testing 8.1a time limit per run | `agent-check.py --timeout` (default 1200 s, tree killed, exit 124; `scripts/test_agent_check.py`) | enforced for runs through the script |
| 4.6 cleanup after a failed agent | the orchestrator | manual |
| 4.3 no credentials in CI | `TestConfigAnonymousSpec` (workflows) | enforced |
| 5.3 trailers | review | planned: none |
| 6.1, 6.2 PR required, linear history, no force push or deletion on master | the `master` ruleset | enforced |
| 6.2 squash as the only merge method; head branches deleted after the merge | the repository's merge settings | planned (pending maintainer settings) |
| 6.4, 6.5 merges, no rebase or force push on PR branches | review | by convention only |
| 6.6 green CI on PRs | `ci.yml` on every PR; `sbt test (mariadb:10.6.22)` as a required check on `master` | runs on every PR; required check planned (pending maintainer settings); browser tests and coverage not required |
| 7.2 `release/*` protected | a ruleset | planned (pending maintainer settings) |
| 7.1 pre-deploy checks | roadmap list, done by the user | manual |
| 7.3 changelog line per batch | review | planned |
| 7.4 calendar version form | `deployment.BuildIdentitySpec` (the packaged manifest's version) | enforced |
| 8.1 practices updated with the batch | the reviewer (testing checklist's last line) | review only |
