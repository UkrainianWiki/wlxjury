# Documentation

What is documented where, how a plan becomes a rule, and how to write here. Agents read
[CLAUDE.md](../CLAUDE.md) first: it points to the practices doc to read before each kind of change.

## 1. Index

**Practices** (rules with a why, an example and their guards; each has a "where the code doesn't
follow these yet" list for triage where one exists):

| Folder | Practices | Checklist | Read before |
|---|---|---|---|
| [security/](security/) | [secure-coding.md](security/secure-coding.md) | [review-checklist.md](security/review-checklist.md); status: [register.md](security/register.md) | auth, sessions, roles, SQL, views and JS, config, exports, logs, personal data |
| [db/](db/) | [practices.md](db/practices.md) | [review-checklist.md](db/review-checklist.md) | SQL, schema, migrations, transactions, locking |
| [performance/](performance/) | [practices.md](performance/practices.md) | [review-checklist.md](performance/review-checklist.md) | load tests, benchmarks, speed or memory changes |
| [testing/](testing/) | [practices.md](testing/practices.md) | [review-checklist.md](testing/review-checklist.md) | writing or running tests, CI, coverage |
| [workflow/](workflow/) | [practices.md](workflow/practices.md) | — | branches, PRs, reviews, commits, releases |
| [operations/](operations/) | [practices.md](operations/practices.md) | — | logging, readiness, backups, deploys, monitoring |
| [integrations/](integrations/) | [practices.md](integrations/practices.md) | — | Wikimedia API, scalawiki, OAuth providers |
| [ui/](ui/) | [practices.md](ui/practices.md) | — | templates, scripts, messages, themes, accessibility |
| [dependencies/](dependencies/) | [practices.md](dependencies/practices.md) | — | adding or updating a library, action, image or tool |

**References** (how a part works today): [api.md](api.md) (REST API), [deployment.md](deployment.md)
(deploy and recovery runbook), [oauth.md](oauth.md) (Google and Wikimedia sign-in);
[`bench/README.md`](../bench/README.md) (the production-like benchmark),
[`scripts/README.md`](../scripts/README.md), [`scripts/review/README.md`](../scripts/review/README.md)
(CodeRabbit requests and review triage); [CHANGELOG.md](../CHANGELOG.md) (by release).

**Plans:** [plans/](plans/), active work only, indexed by the
[roadmap](plans/2026-11-02-roadmap.md); [plans/ci-drafts/](plans/ci-drafts/) holds diffs for other
repositories. **History:** [history/](history/README.md), records still cited, and the index of
every deleted document with its last commit.

## 2. The lifecycle of a plan

1. **Active:** `docs/plans/YYYY-MM-DD-<topic>.md`, listed in the roadmap with its branch and
   state; decisions recorded in it with "(user, <date>)"; results with commit and run ids.
2. **Done:** the batch merged; the plan's status line says so and names the merge commit.
3. **Lasting rules folded in:** every rule that outlives the work goes into the practices doc it
   belongs to (§3), with its why and example; a reference doc (api, deployment, oauth) gets what
   describes how the thing works; open items move to the roadmap or the open-followups list.
4. **Deleted,** with a row in `history/README.md` (path, last commit, where its content went);
   **or moved to `history/`** with a status header, only if its reasons or baselines are still
   cited (by code, a practices doc or a runbook) or it is the runbook for something not yet on
   production. Delete a history record when nothing cites it any more.

## 3. Which doc to update when

| A change that… | Updates |
|---|---|
| adds a security rule, guard or exception | secure-coding (section and §21), its checklist, the register if an item's status changed |
| adds a query pattern, migration rule, lock or isolation rule | db practices (and §10 guards, §11 triage) |
| adds a test layer, test flag, fixture, guard self-test or CI job | testing practices (§1, §5 or §8, §11) |
| changes how batches, reviews, commits, stacks or releases work | workflow practices |
| adds a log event or logger, a readiness check, a backup or deploy step | operations practices; deployment.md for the steps |
| calls a new external service or changes limits, retries or the scalawiki pin | integrations practices |
| adds a message key, a page pattern, a script, a theme variable, a vendored patch | UI practices |
| adds or updates a dependency, action, image or downloaded tool | dependencies practices |
| ships a user-visible change | CHANGELOG.md "Unreleased" |
| starts, lands or decides a batch | the roadmap |

The rule is in workflow §8: the update is in the same PR, and the reviewer checks it.

## 4. Writing rules

- **Rules, not investigation detail.** A practices doc says what to do, why (one or two
  sentences), and an example; measurements, logs and alternatives stay in the plan or the
  commit.
- **Measurements carry ids:** the commit, the CI run id or the log path, and the settings
  (`4a861818`, run 41649422116).
- **Link to what lasts:** a section of a practices or reference doc, a `history/` file, or
  `<commit>:<path>` (`git show 98a8a79e:docs/plans/…`) for a deleted file. Never leave a stub file
  that only points elsewhere. Run a link check over every tracked `.md` before a docs PR.
- **Never edit an applied migration to fix a link** (Flyway's checksum would change on
  production); `history/README.md` notes such citations.
- **State what is, plainly:** "not supported yet" for a planned capability, never "broken";
  "planned" for a guard not built; one name per thing (the code's).
- **English only** in the repository, user docs included.
- **Write files with the Write and Edit tools,** never shell heredocs or `echo` redirection:
  backslashes and quotes get lost (a copied SQL query lost an escape that way). Commit messages
  too: written to a file, committed with `git commit -F`.
- **Size:** a practices doc stays readable in one sitting (about 150–500 lines); a rule that
  belongs to another doc is pointed to, not copied.
- **The changelog is a development changelog** (the user, 2026-11-03): each release has
  "Features" (what organizers and jurors notice) and "Development" (the important engineering
  changes). It leaves out unmerged work, hosting and server details, and trivial refactors.
  Deployed releases are dated from their migrations (production's
  `flyway_schema_history.installed_on`), earlier ones by their development period or git tag;
  release headings are calendar versions, `## 26.11 (YYYY-MM-DD)`. The upkeep is in
  [workflow](workflow/practices.md) §7.3–7.4.

## 5. User docs

`docs/user/`, planned: an English juror guide, then organizer and admin guides after P3. The
English source lives in the repository and is reviewed with the code. A release step publishes it
to a wiki page and marks it for translation, and translations happen there with the Translate
extension. Interface messages are to go through translatewiki (planned; [UI](ui/practices.md) §1.4).

The wiki is Commons (the user, 2026-11-03). The guide is written from what the tool does today, not
from the existing `Commons:WLX Jury Tool` pages, which were barely written; the WLE Resources Center
pages may link to it. Nothing is published, by an agent or a release step, until the user decides.
