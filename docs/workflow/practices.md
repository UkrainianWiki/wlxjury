# Development workflow

How work moves from a plan to production: branches and stacked PRs, the batch cycle, review
triage, worktrees and the shared sbt lock, commits, integration, releases and the changelog.
Each rule has a short why and an example in this repository. Agent rules (models, effort,
`agent-check.py`, bounded waits) stay in CLAUDE.md "Agent efficiency"; what to test and when is
[testing/practices.md](../testing/practices.md) §8. **Sources:** the roadmap's "Running agents
(how)" ([`2026-11-02-roadmap.md`](../plans/2026-11-02-roadmap.md#running-agents-how)); the PR series
and the review triage (`origin/stack/series-doc`: `docs/plans/2026-11-03-pr-series.md`,
`docs/plans/2026-11-03-pr-review-triage.md`); the hotfix record in
[`docs/history/security/`](../history/security/2026-11-02-role-allowlist-hotfix.md);
[`CHANGELOG.md`](../../CHANGELOG.md).

## Contents

1. [Plans and the roadmap](#1-plans-and-the-roadmap)
2. [The batch cycle](#2-the-batch-cycle)
3. [Review triage](#3-review-triage)
4. [Worktrees, the sbt lock and the environment](#4-worktrees-the-sbt-lock-and-the-environment)
5. [Commits](#5-commits)
6. [Integration: followups and the stack](#6-integration-followups-and-the-stack)
7. [Releases, hotfixes and the changelog](#7-releases-hotfixes-and-the-changelog)
8. [Keeping the practices docs current](#8-keeping-the-practices-docs-current)
9. [Guards](#9-guards)

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

## 2. The batch cycle

**2.1 Implement → orchestrator check → independent review → fixes → follow-up review.** The
implementer works on its own branch and worktree and records results in the plan; the
orchestrator checks the diff and the results before asking for review; an independent reviewer
gets the final diff, the invariants and the validation results; the implementer fixes; a second
review only if the fixes are material. *Why:* an author doesn't see their own gaps (the docs
consolidation's review found a lost escape in a copied query), and one review plus one follow-up
is normally enough (CLAUDE.md "Agent efficiency").

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
answers are limited per hour, so the orchestrator requests one review, triages it (replies in
each thread, new entries in the triage document), then requests the next (the user's choice,
2026-11-03). The tools and their limits: [`scripts/review/`](../../scripts/review/README.md);
their state files are rebuilt from GitHub, so nothing is lost with a session.

## 4. Worktrees, the sbt lock and the environment

**4.1 One worktree per batch.** `git worktree add C:/scala/wlxjury-<name> -b ukwikidev/<name> <base>`.
`C:/scala/wlxjury` (the main checkout, with the user's untracked files) and `C:/scala/wlxjury-infra`
(the user's uncommitted work and secrets) are never modified by agents. Never link or copy
`node_modules` or `data/cache` between worktrees: `sbt test`'s `jsTest` runs `npm ci`, which wiped
the main tree's packages through a junction; a copied fixture dump measures another schema.

**4.2 One sbt or test JVM at a time, across all worktrees.** The lock is the directory
`/c/scala/.sbt-jvm.lock` with an `owner` file inside: `owner=` (the agent or person), `pid=`,
`host=`, `started=` (ISO time with offset), `timeout=` (seconds, 0 for none), `cwd=` and
`command=`, one per line. Prefer `python scripts/agent-check.py --lock --owner <name> -- <sbt
command>`: it takes the lock, writes `owner` (its own PID: the process that holds the lock for
the whole run), and removes both when the run ends, a timeout (exit 124) included; if writing
`owner` fails it removes the lock again. By hand: take it with its own command, `mkdir
/c/scala/.sbt-jvm.lock`, check that it succeeded, then write `owner` without `pid=` (a Bash tool
shell's `$$` exits with the command, so the lock would read as abandoned): `printf
'owner=%s\nstarted=%s\ncommand=%s\n' <name> "$(date -Iseconds)" '<command>' >
/c/scala/.sbt-jvm.lock/owner`; release with `rm /c/scala/.sbt-jvm.lock/owner` and `rmdir
/c/scala/.sbt-jvm.lock`, only if you took it. If the directory exists, another run holds it: wait
and retry, never delete it. Not waited on, but reported with the `owner` file to the orchestrator
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

**4.4 Windows.** sbt runs from Git Bash with the direct launcher and UTF-8 (CLAUDE.md "Agent
efficiency"); PowerShell 5.1 splits `-Dfile.encoding=UTF-8` and drops quotes of `set` arguments.

**4.5 gh.** The GitHub CLI is `/c/Users/ilya/tools/gh/bin/gh.exe` on the orchestrator's machine
(not on `PATH`); scripts take it from `GH` or `--gh`. Agents use it to read (PR lists, checks,
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

**5.1 Commit only your paths.** `git commit -- <paths>` (or `git add <paths>` first), never
`git commit -a` in a shared tree. *Why:* other agents' and the user's files are in the same
status.

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

## 6. Integration: followups and the stack

**6.1 `ukwikidev/followups` collects finished work.** Each batch merges there (`--no-ff`, by the
orchestrator) after its review. It is local; nothing is pushed from it directly.

**6.2 GitHub sees it as stacked PRs.** Each batch becomes one PR, `[NN] <title>` on branch
`stack/NN-<theme>`, based on the previous `stack/NN-1-…` (the first on `master`). Its commits are
curated: one or a few per sub-theme, docs in a trailing commit when they exceed about 150 lines,
original authors and dates (§5.3). The tip's tree equals the followups commit it reproduces
(`git diff --stat` empty), and the body says which (`Original history`). *Why:* 844 commits
reviewed as 54 single-theme PRs (PR series).

**6.3 Who pushes, and without force.** The orchestrator pushes stack branches and opens the PRs;
an agent does so only when the user asks for it in that task. Check that the branch doesn't
exist (`git ls-remote origin
refs/heads/stack/NN-…`), then `git push origin <sha>:refs/heads/stack/NN-<theme>`. The PR body
ends its "Stacked PR" section with "Based on #<prev>; last in the series."; when the next one
opens, that line of the previous PR becomes "next: #<new>" and nothing else in its body changes.
Only the orchestrator (or the user) edits a PR body, including another agent's PR: an agent
that needs a change there asks for it. *Why:* two writers of one body overwrite each other, and
the stack's links must stay consistent.

**6.4 A later commit goes to both, and the trees stay equal.** When the stack tip and a
followups branch have the same tree, a new commit on the stack is mirrored on a followups branch
with the same tree: `git commit-tree <stack tip>^{tree} -p <followups tip>` (or a cherry-pick
with `-x`), then `git diff --stat <stack tip> <mirror>` must be empty. *Example:*
`ukwikidev/followups-ci` for PR 55, `ukwikidev/followups-refresh` for PR 67 (#250).

**6.5 Restack by merge, never by force.** When a lower stack PR changes (a review fix), the
branches above it take the change by a merge commit (or a new commit), never a rebase or a force
push of a pushed branch: reviewers' threads and the PR series' recorded tips stay valid.

**6.6 CI must be green** on the stack PR before the next batch builds on it (testing §8.3).

## 7. Releases, hotfixes and the changelog

**7.1 A release is a tree that was tested.** Its commit had the full suite run (or the same tree
did); the roadmap's "Before any production deploy" lists the user's manual checks per release
(every release, then per migration range); the ZIP is built only after them. The deploy itself is
[deployment.md](../deployment.md) and [operations](../operations/practices.md) §4.

**7.2 Hotfixes branch from what production runs.** `ukwikidev/hotfix-<what>-<deployed sha>` from
the deployed commit, the smallest fix, its own runbook and data script, deployed as a named build
(`0.13-round-filters-rolefix-f3f2c73617de` on `f9c31ce5`). The same fix is already in followups or
goes there; the hotfix record moves to `docs/history/` and is deleted when a release replaces it.
*Why:* production can't take the untested distance between its commit and followups for one fix.

**7.3 The changelog** (`CHANGELOG.md`) is a **development changelog** (the user, 2026-11-03):
each release has **Features** (what organizers and jurors notice) and **Development** (the
important engineering changes: frameworks, database and migrations, tests and CI, deployment
tooling). It leaves out unmerged work, hosting and server details (hosts, server paths,
accounts, infrastructure), and trivial refactors. "Unreleased (developed, not yet deployed)"
comes first. Deployed releases are dated from their migrations (production's
`flyway_schema_history.installed_on`, the first migration a deploy applied; a change without a
migration is "deployed by" the next deploy that contained it, or dated from a deploy record);
earlier releases by their development period (first to last commit) or their git tag. A batch
adds its line under Unreleased in the same PR; a deploy moves the block under its release heading
(§7.4) with its date. PR numbers are the stack's. *Why:* the changelog shows how the project
developed, without what changes with the hosting or what no reader needs.

**7.4 Calendar versions** (the user, 2026-11-03). A release is named `YY.MM` for the month it
ships (`26.11`), with `.N` for an extra deploy in the same month (`26.11.1`, `26.11.2`). Between
releases `build.sbt` carries the next release as a snapshot (`version := "26.11-SNAPSHOT"`;
rename it if the release slips to a later month). Releasing:
1. the release commit sets `version := "26.11"` (on followups, or on a release branch from the
   tested commit; `26.11` from the tip of `ukwikidev/release-2-prep`);
2. build with `-Dwlxjury.buildId=26.11` (the ID becomes `26.11-<commit>`, the ZIP
   `wlxjury-26.11.zip`; [deployment.md](../deployment.md), "Build and rehearse");
3. after the deploy, the changelog heading becomes `## 26.11 (YYYY-MM-DD)` with the deploy date
   (§7.3), and the next commit, on followups and on master, sets the next snapshot:
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

## 9. Guards

| Rule | Enforced by | State |
|---|---|---|
| 4.2 one sbt JVM at a time | the lock directory; `agent-check.py --lock` writes `owner`, waits, reports a lock older than an hour (`scripts/test_agent_check.py`) | by convention (taking it by hand stays possible) |
| 4.2, testing 8.1a time limit per run | `agent-check.py --timeout` (default 1200 s, tree killed, exit 124; `scripts/test_agent_check.py`) | enforced for runs through the script |
| 4.6 cleanup after a failed agent | the orchestrator | manual |
| 4.3 no credentials in CI | `TestConfigAnonymousSpec` (workflows) | enforced |
| 5.3 trailers | review | planned: none |
| 6.2, 6.4 stack tip equals its followups tree | `git diff --stat` by the orchestrator | manual check |
| 6.5 restack by merge, no force | review; the series' recorded tips | by convention only |
| 6.6 green CI on PRs | `ci.yml` on every PR | runs on every PR; branch protection requiring `CI / sbt test (mariadb:10.6.22)` on `master` is planned (CI plan, U8) |
| 7.1 pre-deploy checks | roadmap list, done by the user | manual |
| 7.3 changelog line per batch | review | planned |
| 7.4 calendar version form | `deployment.BuildIdentitySpec` (the packaged manifest's version) | enforced |
| 8.1 practices updated with the batch | the reviewer (testing checklist's last line) | review only |
