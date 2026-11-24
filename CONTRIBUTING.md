# Contributing

How to set up, make a change and get it merged. The detailed rules are in the practices docs
linked below; this page is the short version.

## Setup

- Run the app locally: [DEV.md](DEV.md) (JDK 17, sbt, MariaDB, scalawiki's `publishLocal`, the
  first root account; or docker-compose).
- What is documented where: [docs/README.md](docs/README.md). The rules never broken, architecture
  and roles: [CLAUDE.md](CLAUDE.md) (notes for AI coding assistants, useful to people too).
- Never link or copy `node_modules` between checkouts or worktrees: `sbt test` runs `npm ci`
  and empties the target of a link.
- Enable the repository's git hooks once per clone: `git config core.hooksPath .githooks`
  (see [Private strings](#private-strings); worktrees share the setting).

## Branches, PRs and merging

1. Branch from master: `git fetch origin && git switch -c <topic>/<short-name> origin/master`
   (`fix/`, `feature/`, `docs/`, ...).
2. One PR per change, against `master`. One theme per PR; put an unrelated finding in its own
   PR or an issue.
3. Fixes during review are new commits on the branch. To take newer master, merge it
   (`git merge origin/master`); don't rebase or force-push a branch others review.
4. A maintainer merges the PR with GitHub's **Squash and merge**. `master` requires a PR and
   linear history and refuses force pushes and deletion. Planned, once the maintainer sets
   them: squash as the only merge method, and head branches deleted after the merge. The
   **PR title and description become the commit message** on master, so the title says what
   changed and the description is a short summary of why. Keep long notes, screenshots and
   test logs in PR comments.
5. CI must be green, above all **`sbt test (mariadb:10.6.22)`** (the full sbt suite and Jest);
   making it a required check is planned, once the maintainer sets it. The browser tests and
   coverage run on every PR too; a red one is looked at, but they are not required.

### Stacked PRs

Stack only when a change needs another unmerged one. Open the child PR against the parent's
branch, and merge bottom-up. After the parent PR is squash-merged, make the child's diff its
own again, in the child branch (`NNN` is the parent's PR number):

```bash
git fetch origin
Sp=$(git log origin/master --grep '(#NNN)' --format=%H -1)   # the parent's squash commit
git merge "$Sp^"          # a normal merge of master just before the squash; on a conflict keep the child's side
git merge -s ours "$Sp"   # records that the branch has the squash's content
git diff origin/master...HEAD --stat   # only the child's own changes
git push
```

If the parent's branch was deleted, GitHub retargets the child PR to master; otherwise change
its base to `master`. Merges only; no rebase or force-push.

### Branches from before 2026-11-20

Until then work reached GitHub as stacked PRs on `stack/*` branches. Master got that work as
squash commits, one per stacked PR (#184–#390, top `88a0355e`), so a branch made on the old
`stack/*` or `ukwikidev/followups` history can't merge master plainly. Bridge it once: if it
contains `origin/stack/207-guide-shots`, run `git merge -s ours 88a0355e`; if not, first
`git merge origin/stack/207-guide-shots`, then the same. Check with
`git diff 88a0355e...HEAD --stat`. Details: [workflow practices](docs/workflow/practices.md)
§6.7.

## Private strings

Commits must not carry personal data or local paths: names, account names, personal email
addresses, home folders, local paths of a checkout or a worktree, worktree names.
`scripts/check-private-strings.py` refuses the strings on a list kept outside the repository:

- the `pre-commit` hook checks the staged files (`--staged`), the `commit-msg` hook the message and
  the author and committer identity (`--message`); enable them as in [Setup](#setup);
- CI's `private-strings` job checks a PR's added lines, its commits' identities and messages
  (`--range`) and the whole tree (`--all`), with the list from the repository secret
  `PRIVATE_STRINGS`; without the secret the job fails;
- a PR from a fork gets no secrets, so for it the job skips the list checks with a notice. Before
  merging a fork's PR, a maintainer checks out its branch and runs
  `python3 scripts/check-private-strings.py --range origin/master..HEAD` with the list.

The list has one rule per line, `CATEGORY<TAB>REGEX`: a category of lowercase letters, digits and
`-` (`^[a-z][a-z0-9-]{0,30}$`), a tab, and a Python regex; `#` starts a comment line. A line that
isn't a valid rule is refused by its line number, never its text. The script reads it, in order,
from `--patterns FILE`, the file named by `WLXJURY_PRIVATE_STRINGS_FILE` (a missing file is an
error, not "no list"), the content of `WLXJURY_PRIVATE_STRINGS` (CI's secret), or the default file
`%APPDATA%\wlxjury\private-strings` (Windows), `$XDG_CONFIG_HOME/wlxjury/private-strings` or
`~/.config/wlxjury/private-strings`; files are UTF-8, a byte order mark is fine. Samples for
`--self-test` sit next to it in `private-strings.samples`, one `hit<TAB>TEXT` or `pass<TAB>TEXT`
per line. Maintainers have the list; without it, or with one that has no rule but `allow` and
`skip-path`, the hooks warn and let the commit through, and CI fails; CI also fails on a refused
line. The file may have comments, but keep them out of the `PRIVATE_STRINGS` secret: GitHub masks
each line of a secret in all logs, and a short comment line would mask ordinary words. A hit names
the file and line and the rule's category, never the matched text. The list doesn't cover every
name or home folder, so review for those too. Write repository-relative paths, `{user}`-style
placeholders and neutral sample names (`Example`, `ExampleBot`, `example.org`). Server commands
name no account: `ssh wlm.org.ua` takes it from your `~/.ssh/config`. A real hit is fixed, not
bypassed.

## Running tests locally

CI runs everything on each PR; locally, run the tests of your change. What to test and how:
[testing practices](docs/testing/practices.md).

If your environment has Commons bot credentials, unset them for test runs (sbt and the
Playwright fixture server alike) by prefixing the command with
`env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD`.

- **sbt** (Docker must run; the tests start MariaDB in a container):
  `sbt "testOnly views.MessagesCompletenessSpec"`. On Windows use Git Bash and the direct
  launcher with UTF-8, through `scripts/agent-check.py` (it keeps the full log under
  `target/checks/` and prints a summary):

  ```bash
  python scripts/agent-check.py -- java -Xmx512m -Dfile.encoding=UTF-8 -jar 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar' 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8")' 'Test/compile' 'testOnly views.LargeViewSideSpec'
  ```

  Run one test JVM at a time.
- **Jest** (page scripts in `public/javascripts/`): `cd javascript-test && npm ci && npm test`;
  the lint: `npm --prefix javascript-test run lint`.
- **Playwright** (Chromium, Firefox, WebKit): in `browser-test/`, `npm ci`, start the fixture
  server first (`node fixture-server.mjs start`), then `npx playwright test` (one engine:
  `--project=firefox`), and `node fixture-server.mjs stop` at the end. Setup and reports:
  [testing practices](docs/testing/practices.md) §10.6.
- For templates, the specs under `test/views/`; a large-view template change also needs
  `views.LargeViewFixturesSpec` and `views.LargeViewSideSpec`. Documentation-only changes need
  no local tests.

## UI and translations

- [UI practices](docs/ui/practices.md): message keys, scripts under the CSP (no inline
  JavaScript), theme modes, accessibility and the keyboard, the screenshot harness (shoot before
  and after a visible change), supported browsers.
- Every text a user sees is a message key, added to all four files in the same commit:
  `conf/messages.en`, `.fr`, `.uk` and `.ru`, with the same placeholders
  (`MessagesCompletenessSpec` checks it). List the new or changed keys in the PR description:
  the maintainer checks Ukrainian and Russian and asks a French speaker for French.
- Front-end changes are reviewed with the [UI review checklist](docs/ui/review-checklist.md). If
  you copy text or code from Modern Web Guidance (CC-BY 4.0 text, Apache-2.0 code), credit it with
  a source link where it is copied and add a NOTICE entry.

## Security

Read [secure coding](docs/security/secure-coding.md) before changing routes, controllers or
`Secured`, roles or accounts, sessions, SQL, views or JavaScript, config or deployment, exports,
logs or dependencies. For such a change:

- apply the [review checklist](docs/security/review-checklist.md);
- add negative authorization tests for every changed permission (another contest's admin,
  organizer, juror, an inactive member, signed out);
- run `testOnly security.*`; an allowlist entry covers one file (or action) and gives a reason;
- update [the register](docs/security/register.md) if an item's status changed;
- say in the PR description which of these areas it touches.

## Releases and hotfixes

From 26.11.15 on, releases are cut from master: a release-commit PR (the version in `build.sbt`
and the changelog heading), squash-merged, and the tag on that commit. Earlier releases are
recorded by their `release/<version>` branches. A hotfix goes to master first; then a
`release/<version>` branch from the last release's tag (once release tags exist; until then from
its `release/<version>` branch) gets the fix with `git cherry-pick -x`, and the hotfix is built
and tagged there. Details: [workflow practices](docs/workflow/practices.md) §7.

### Changelog entries

Add one short line per user-visible change to `CHANGELOG.md` under "Unreleased", grouped as
**Features**, **Fixes**, **Security** or **Operations** (deploy, infrastructure and tooling that
admins or operators notice; a release's migrations go on one line, "Migrations: V108, V109.").
Keep it to a few words in plain language for what a juror, organizer, admin or operator notices
("Sticky top bar on desktop."). No class names, file paths, SQL, spec names, decision ids or
measurements, and no step-by-step behaviour: that belongs in the PR and the git history. Merge
micro-changes into one line, leave out refactors, tests-only, docs-only and CI changes, and cite
only PRs of this repository, as `(#123)`.
