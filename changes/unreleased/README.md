# Changelog fragments

A pull request with a user-visible change adds its [CHANGELOG.md](../../CHANGELOG.md) line(s) as
one file here, **not** in CHANGELOG.md: every PR appending under "Unreleased" made the open PRs
conflict there (decided 2026-11-26). The release commit folds the files into CHANGELOG.md and
deletes them ([workflow practices](../../docs/workflow/practices.md) §7.3, §7.4).

**Name:** the branch's name with `/` as `-`, ending in `.md`: branch `fix/track-name-clashes` adds
`fix-track-name-clashes.md`. Letters, digits, `.`, `_` and `-` only.

**Content:** a `section:` line naming the group (`Features`, `Fixes`, `Security` or
`Operations`), then the line(s) exactly as they will read in CHANGELOG.md, each starting with `- `
at the line's start, with the PR number. Blank lines are ignored. A PR with lines in several groups
repeats `section:`:

```markdown
section: Fixes

- Round form: a track label keeps its spaces as typed (#209).

section: Operations

- Migrations: V116.
```

The wording rules are CONTRIBUTING.md's "Changelog entries". A PR's first push has no number yet:
add it once the PR is open. A migration goes under `Operations` as `- Migrations: V116.`; the fold
merges all of a release's migrations into one line.

**Check:** `python3 scripts/changelog-fragments.py check` (CI runs it with a guard that fails when
a PR adds a line under CHANGELOG.md's Unreleased). **Release:**
`python3 scripts/changelog-fragments.py fold` adds the lines under their groups in Unreleased,
after the lines already there, ordered by PR number (fragments without one last, by file name),
and deletes the fragments; `--into "### 26.11.17.3"` folds into a release block instead,
`--dry-run` shows the diff. A malformed fragment stops it before anything changes; a line already
in CHANGELOG.md is skipped, so a rerun is safe.

This README is not a fragment.
