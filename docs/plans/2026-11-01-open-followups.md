# Open follow-ups, gaps and cleanup from the plans and reviews

Status: trimmed on 2026-11-03 to what is still open. Collected on `ukwikidev/theme-modes` at
`cb103b24` (2026-11-01) and worked off as batches B1–B12 on `ukwikidev/followups`. The batch
plans, results, review fixes and suite runs that were here are in the commits below (the full
text: `git show 98a8a79e:docs/plans/2026-11-01-open-followups.md`). Pre-deploy items went to the
roadmap's "Before any production deploy"; lasting rules to `docs/security/secure-coding.md`,
`docs/db/practices.md` and `docs/performance/practices.md`.

| Batch | Items | Landed (stack PR tip or merge) |
|---|---|---|
| B1, B2 | bugs and server messages (1.1, 1.3–1.5, 1.7, 4.2, 6.1–6.3); one pool (1.2) | `faa5fb92` (PR 33) |
| B3 | no inline scripts (5.1–5.3), 1.6, 4.4, 6.5 | `1069e146` (PR 34) |
| api-auth | the served, authenticated API (6.4) | merge `992d8eb2` (PR 35) |
| B5 | allowed hosts, Secure cookies, CSP by hash (2.2–2.4) | `4aeaf523` (PR 36) |
| B4 | bcrypt (2.1) | merge `d9dbe0ed` (PR 37) |
| B6, B8 | escaping, secrets, docs, dead code (2.5–2.8, 4.1, 4.8); the by-rate benchmark (3.5) | `3f98b77a` (PR 38) |
| B9–B11 | approval sums, `RegionCountPairedBenchmark`, ranks in the page query | `fa9d627c` (PR 39) |
| B12 | grouped pages from `vote` alone | merge `ef78bbaf` (PR 41) |
| Test speed-up | recommendations 1–4 (tmpfs test DB, opt-in live Commons, batched fixture, shared migration server) | `2d5d0ef4` (PR 50) |
| Shared production-filter test app | | merge `bc3d7936` |

Decisions of 2026-11-01 (all applied): password sign-in stays, with bcrypt cost 12 and rehash
on sign-in; round merging deleted; the denial line logs the account id only.

## Still open

**Manual and production checks (the user):** 3.1 the `auto` screenshots (the theme flash is now
the browser tests', in one current build of each engine), 3.2 category suggestions against live
Commons, 3.3 Back after a vote in Safari (Firefox's is the browser tests'; Chromium's shows the
old vote, UI practices §9 U23): listed under the roadmap's pre-deploy "Every release".

3.4 **The criteria count of round 1327** (`CriteriaRate.countOf` replaced the hard-coded 2):
check it against production data. Read-only SQL on production can only be run by the user
(no DB login, see memory).

3.6 **Benchmark workload gaps** (prod-like-benchmark): "Rated avg 7" and the concurrent "New
files" step insert no rows, so two of the ten concurrent operations measure nothing. Make the
fixture give them rows (eligible rated images, a top-up target with new files) in a new suite
label, so that old results stay comparable. Do this only before the next capacity
measurement.

4.5 **The logging config ships with each release**
(`docs/plans/2026-10-30-logging-config-in-releases.md`, not started). Production reads
`/etc/wlxjury-runtime/logback.xml` from the infra role, so the `coi` logger's INFO lines from
the COI plan are written only if that file has them. Steps are in that plan: move the file to
`conf/logback-production.xml`, the packaging test, the deploy tool's lists, then the unit
template in `wlxjury-infra`. That last step is a server change, which the user applies.

4.6 **Retire the one-time cutover code in `wlxjury-deploy.py`** (`adopt-legacy`,
`scrub-legacy`, about 350 lines of the tool and 250 of tests; tooling-alternatives step 1).
Do it only after the user confirms that the cutover is accepted and that every recovery
record targeting the adopted legacy release is retired. Keep `wait_ready`'s legacy `/auth`
check until then.

4.7 **Benchmark leftovers** (bench/README §9): `C:\scala\wlxjury-bench-work\proddata\` still
holds a production dump copy with personal data, plus the `wlxjury-bench-*` volumes and
image. Remove them with the user's agreement once the results are accepted.

6.6 **Alias accounts** (`98a8a79e:docs/superpowers/specs/2026-04-12-multi-contest-user-design.md`,
"Recommended handling"): after V62, run `scripts/report-alias-accounts.sql` on a production
copy, triage the groups, and merge only clear cases. **That design's merge SQL is stale:** it
predates V82 (`selection`, `rounds`) and misses `vote_aside`, `round_image`, `api_token` and the
identity tables (`user_identity`, `account_token`); a merge follows the account-join plan
([2026-11-03-account-join.md](2026-11-03-account-join.md)) instead. An operational step for
the user.

6.7 **ChronicleMap jars** (performance-and-memory "Side issues"): the deployed tool bundled
`net.openhft.*` through scalawiki. Check whether the current release still bundles them:
`unzip -l` on the release ZIP, then grep for `openhft`. If it does, that is a scalawiki
upgrade (see the scalawiki memory: wlxjury builds with `origin/page-categories`).

6.8 **Job titles and statuses inside localized messages** (found in batch B1's review). Since
B1, "Already running: {0}." (`import.already.running`) and "Can't retry: the job is {0}, not
failed." (`job.retry.not.failed`) are message keys. Their arguments are still English: the
job's title from `JobHandler.title` (e.g. "Importing files from …") and the raw status
(`running`, `done`, …). The job status panel (`snippets/jobStatus`, `JobController.json`) shows
the same titles. Fix: give each handler a title key with arguments, and add one key per
`JobStatus`, in all four files. This is a follow-up, not part of B1.

**Test-suite runtime, open recommendations** (measured on the suite of 2026-11-02; 1–4 done,
6 and 7 moot with tmpfs):

| # | Change | Spec(s) | Saving | Risk | Effort |
|---|---|---|---|---|---|
| 5 | `ListPositionSpec`: the oracle from one read of each list (rank and offset window in Scala) instead of `rankPosition`/`aroundAtOffset` per file | ListPositionSpec | About a third of 49 669 queries: 15–18 s (about 14 s on tmpfs), estimate | Low: the oracle is still the plain ordered list, independent of the keyset code; a new oracle could have its own bug | 1–2 h |
| 8 | A new baseline, B82 | fresh migrates, Gatling | 6–8 s per suite, about 30 s per `gatlingAll` | Medium: needs an exact-equality check; P0's V82 in flight | Half a day, after P0/P1 |

## Left out as new features

The Commons `refresh` mode for deleted and renamed files, with its button and periodic ticker
(deleted-renamed-files §2 and §4; only the thumbnail-triggered check exists). Importing the
top N of another contest's round. More ranking methods (STV, normalised ratings). The Tabler
trial and Codex colours (theme decision 2). Keyset paging of galleries. A design for faster
organisers' all-jurors views. "Count new files" as a job. Moving local image downloads to the
job table. Prefetching import pages. The import's open questions (unifying the monument
template, WLE nominations). The Debian package and Ansible deployment options.
