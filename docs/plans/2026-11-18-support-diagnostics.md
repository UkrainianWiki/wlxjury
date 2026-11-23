# Support diagnostics: what happened and what could be wrong (plan, 2026-11-18)

**Status:** D-0a built (#372, `stack/189-logging-config-in-releases`); D-0b built
(`stack/191-diagnostics-quick-fixes`, 2026-11-18); D-1a built (`stack/192-contest-health`,
2026-11-18: `services.diagnostics.ContestHealth`, `/admin/contests/:id/health`, the import form's
DG10 warning; H2's read measured on the Gatling fixture DB, `ContestHealthBenchmark`); D-1b onwards
not built. The plan was revised
after its independent review and a follow-up review (2026-11-18).

- **Decided by the maintainer, 2026-11-18:** the whole first batch.
  - **DG1:** D-0a + D-0b + D-1a first, then D-1b and D-1c.
  - **DG2:** root and the contest's admins.
  - **DG3:** a new "read-only, from the server only" scope for personal API tokens.
  - **DG7:** a 200 state page, with an INFO `round.access` line.
  - **DG10:** warn on the import form and the health page, no refusal.
  - **DG11:** for a prefix-less source, check Commons for the page and the category; when both
    exist, show the choice with both counts, the category preselected (refined 2026-11-18).
- **Deferred** until their PRs are scheduled: DG4, DG5, DG6, DG8, DG9.

The maintainer asked on 2026-11-18: "as often reports are like this — I'm having trouble / it's
not working etc — can we investigate and plan some tool to identify the taken steps, what was
going on, and what could be wrong?" The same day an admin reported "trouble importing and
jurying pictures for WLM 2026 in Nepal". A read-only investigation of the production logs
answered that report, but slowly and partly by inference. It is the worked example here (§2):
every option is judged by how fast it would have answered it.

**Builds on, does not reopen:**

- **Error rules:** message keys and a logged reference, never request or exception text
  (secure-coding §12; `ErrorHandler`, `CsrfErrorHandler`, `JobError`).
- **The denial line** and its reference (`Secured.denialLogLine`, operations §1.1–§1.4).
- **What may go in a log line** (secure-coding §13) and **privacy** (§22).
- **Personal API tokens** (V75, P13):
  - 32 random bytes, stored as SHA-256;
  - expiry and revocation;
  - refused tokens logged by prefix (`ApiTokenService`);
  - Bearer authentication through the same `Secured` permissions (`api.ApiAuth`), served under
    `/api` (`conf/routes`: `-> /api api.ApiRouter`). CLAUDE.md's "not served" is stale.
- **The logging plan**
  [`2026-10-30-logging-config-in-releases.md`](2026-10-30-logging-config-in-releases.md), which
  ships the production logback with each release. It is designed but not scheduled; this plan
  takes it as is.
- **The round lineage plan's** `round.admin` line and `status_changed_at` / `_by` (§2.6 there).
- **The rounds-parity audit log** (row 15, security register P19). It is not designed here; the
  timeline reads it once it exists.
- **The account change log** (V96, `/admin/account-changes`).
- **The existing-accounts plan's** `security.account` lines (`account_search`, one per search;
  `add_confirmed_*`).
- **The operator output rule** of `wlxjury-release status`: a few lines, with exactly one next
  command as the last line.

## Goals

1. When someone says "it doesn't work for X", a supporter can answer, within minutes and without
   guessing:
   - who did what, when, and in which contest or round;
   - what the app answered (status, error key, reference);
   - which jobs ran and how they ended;
   - what Commons answered;
   - what state the contest is in now.
2. The answers are reachable the ways the maintainer and Claude actually reach production:
   - as root in a browser;
   - as the support account over ssh, with no sudo and no database login (§6).
3. Admins can check their own contest themselves, and see nothing of other contests.
4. Every finding carries a short hint, in the user's language, that names the likely fix.
5. Contests are named in every report ("Wiki Loves Monuments 2026 in Nepal"), not only by id.

## Non-goals

- Metrics, dashboards and external monitoring. These belong to operations OP3 and the
  screen-size analytics plan.
- The admin-action audit log itself (P19).
- Changing what an import does (it only adds files), letting admins create contests, or
  removing files from a contest. These are product decisions (§8).
- Session replay, client-side error capture and third-party telemetry.
- Weakening any authorization check. New pages and endpoints use the existing action builders
  and permissions.

## 1. What exists today: the gaps that matter

**Detailed sources:**

- operations §1 (log lines), §5.2 (jobs) and §8 (OP1–OP7);
- secure-coding §12 (errors shown to users), §13 (personal data in logs) and §22 (privacy);
- `docs/deployment.md` for the server layout.

**The gaps found while answering the Nepal report:**

1. **Production writes WARN and above, plus a few INFO loggers.**
   - **The logback file.** Production uses `/etc/wlxjury-runtime/logback.xml`, from
     `wlxjury-infra`'s template. That is a copy of `conf/wlxjury/logback.xml`. `conf/logback.xml`
     is the development config.
   - **What infra `main` has.** The B2 change (cbdf8cc, 2026-11-10) put `coi` next to
     `application` and `services.ApiTokenService`.
   - **Still missing, so not written:**
     - the class loggers of `services.imports.*` and `services.jobs.*` (the import's summary
       "n files, i inserted, l linked", `JobScheduler`'s INFO);
     - `security.account`, `security.roles`;
     - `round.admin`, which already logs `freeze_release`, `juror_add` and `juror_reassign`;
     - `round.counts`.
   - Whether the B2 playbook has run on production is not known (§10).
2. **No per-request record.**
   - No line ties a request to an account.
   - `server_error` and `csrf_failure` lines carry neither an account nor a contest.
   - Apache's `other_vhosts_access.log*` (readable through group `adm`, kept 14 days,
     `scripts/backfill/voted_at_from_logs.py`) has IPs, URIs and statuses, but no account.
3. **Ids only.** Log lines never name a contest or round, and the support account can't query the database to
   resolve an id.
4. **Job details live only in the database.**
   - Import source, inserted, linked, missing and `notInCountries` are in `job.params` /
     `job.state`.
   - They are shown on `/admin/jobs/:id` (the contest's admins and root) and in the images
     page's latest-jobs list.
   - Nobody has a list of jobs across contests.
5. **Untrusted text in some WARN lines.** These quote text without `logValue`, against
   operations §1.1:
   - Commons exception messages (`MwApi`, `CommonsLogin`);
   - file titles (`CommonsFileCheck`, `LocalImageCacheService`);
   - the import source in the INFO summary.
6. **History the database keeps, and what it doesn't.**
   - **Kept:**
     - `account_change` (V96);
     - `round.status` with `status_changed_at` / `_by` (V105);
     - `round_image_event` (V88);
     - `vote.voted_at` (V97);
     - `job.created_by` and the job times;
     - `round_image` with its counts (V84; counts in V89).
   - **Not kept:**
     - contest settings changes (the files source, name, year, country);
     - round settings changes;
     - sign-in times;
     - a file's Commons upload date;
     - which import brought a file.
7. **Country categories are derived from the contest's own name and year.**
   `ContestCountries.prefixes` derives them from `<root> in ` and
   `Category:<name> <year> in `. A file is a country file of this contest only if it sits in
   such a category. So WLM 2026 Nepal files imported into a WLE contest of an earlier year belong
   to no country: a page source leaves them out (`notInCountries`), and the region and country
   menus don't show them.
8. **The stopped-round 403 has no reason.**
   - `GalleryService.isNotAuthorized` folds several cases into one boolean, so the gallery and
     the large view log `reason=permission_check_failed` (from `Secured.denialReason`). The
     cases are:
     - the round is stopped (not among the juror's active rounds);
     - the round is still distributing;
     - the juror is paused in it;
     - other contest, no role.
   - The 403 page has a `round_stopped` branch (`accessDenied.scala.html`), but it is the
     comment path's (`ImageDiscussionController.RoundStopped`), with comment-specific text.
   - A related precedent: a juror whose round a job is moving gets `roundPaused`, a 200 page
     (a state, not a denial, with no authz line).

## 2. The worked example: "trouble importing and jurying pictures for WLM 2026 in Nepal"

**What the investigation found (production logs, read-only, 2026-11-18):**

- **Nothing failed.** There were no failed jobs, no Commons API errors and no 500s, and the
  server was healthy.
- **The contest.** The admin worked in contest 466 on jury.wikilovesearth.org. It is an old
  contest, reused, and already held about 2020 WLE files from Armenia, Sri Lanka, Bhutan and
  other countries. No WLM 2026 Nepal contest was ever created; only root can create one.
- **The imports.** Four import jobs (50, 51, 56, 57) ran and completed. An import only adds
  files.
- **The rounds.**
  - Round 1974 was made from all of the contest's files, so it showed jurors the old foreign
    photos.
  - Round 1975 came out empty or nearly so, most likely because of its source or filters.
- **The 403.** A juror got a 403 on round 1974 after the admin stopped it. It was logged as
  `reason=permission_check_failed` (gap 8) and showed the generic forbidden page.

**What was slow or impossible:**

| Question | How it was answered | Gap |
|---|---|---|
| Which contest is 466? | Not answered without DB access | 3 |
| What did the imports bring? | Only through root's `/admin/jobs/<id>` page, in a browser | 1, 4 |
| What did the admin type as the source? | Unknown | 1 (no summary line). The form is pre-filled with the contest's old `contest.images`, and a source without `Category:` is read as a page (`ImportSource.apply` → `PageFiles`) |
| Why don't the Nepal files show by country? | Not seen | 7: in a WLE 2020 contest, "Images from Wiki Loves Monuments 2026 in Nepal" is no country category of this contest |
| How big are rounds 1974 and 1975? | Guessed from Apache response sizes | 4 (nothing reports a round's size outside the browser) |
| Whose browser was the juror's? | IP matching in the Apache logs | 2 |
| Why the 403? | Misread as "has the role but denied" | 8 |

**What would have answered it at once:** one report for "Wiki Loves Earth 2020" (contest 466),
showing the following, each with its hint.

- **H1, the decisive check.** The contest's identity against its import sources: "The contest
  is Wiki Loves Earth 2020; the imports since 2026-11-… came from WLM 2026 Nepal. Imported files
  are added to the old ones. Ask the tool administrators for a new contest."
- **H2.** Its files by country category, and the count in no country category. The Nepal files
  land in the second group (gap 7).
- **H4.** Its import jobs with source, inserted, linked and `notInCountries`.
- **H7.** Round 1975 with 0 files, with its source rounds, filters and the distribution's count
  check numbers. Round 1974 taking every file (H8).
- **H17.** Jurors refused on stopped round 1974.

`wlxjury-diag recent` would also have found the contest by itself, without an id: "contests
with jobs, round changes or denials in the last 24 h", by name.

## 3. Options

### 3.1 (a) A per-request structured line with a correlation id

**What it does.** A filter makes one reference per request, kept as a request attribute, and
logs one key-value line on the logger `request` when the request ends:

    request ref=3fa9c1d2 accountId=812 contestId=466 method=POST route=/admin/rounds/save status=303 ms=184 err=-

- **The `route` field** is the route's pattern from Play's `HandlerDef.path`, normalised
  (`$id<[^/]+>` becomes `:id`). It is never the raw URI, so no tokens, query strings or typed
  text get in. The Tapir sub-router under `/api` has no `HandlerDef`. Its fallback is
  `route=/api/*` plus the method, or Tapir's endpoint name where the router sets one.
- **The `err` field** carries the error key or the denial reason.
- **One reference per request.** The denial, CSRF and server-error lines reuse the request's
  reference, so one reference finds both lines. They also gain `accountId` and `contestId`
  (ids only, §13).
- **Separate file.** The lines go to `/var/log/wlxjury/requests.log` with its own retention
  (DG5, deferred).
- **Left out:** `/assets`, image proxy and thumbnails, `/health/ready`, and requests for other
  hosts.

| | |
|---|---|
| Effort | M (2–3 days) |
| Privacy | New: which account opened which pages and when. Mitigated by account ids only, route templates, no IP by default and short retention |
| Security | Untrusted input only as quoted values |
| Usefulness | High for "what did X do and what did the app answer": it replaces the IP matching. It says nothing about contest state |

### 3.2 (b) A user activity timeline

**What it is.** A root page (later a scoped admin page) that merges per account or per contest:

- the request lines of (a), or the event table of (f1);
- the database history of gap 6.

| | |
|---|---|
| Effort | M–L (3–5 days) |
| Privacy | The most sensitive view. Root only at first. An admin version would show only actions in their contest, by its members |
| Security | A new read surface over every account. Negative specs needed |
| Usefulness | High, but only after (a) or (f1). In the Nepal case its database part would have shown the imports and the round creation and stop |

### 3.3 (c) A contest health report (the recommended core)

**What it is.** One service, `services.diagnostics.ContestHealth`. It reads the contest and
returns a summary and checks (§5). Each check has:

- pass, warn, fail or info;
- the facts it read;
- a hint (a message key with numeric and id arguments only).

The page `/admin/contests/:id/health` renders the report. The API endpoint (§3.4) returns it as
JSON for the CLI.

| | |
|---|---|
| Effort | §4: split into D-1a, D-1b, D-1c |
| Privacy | Admins see what their rounds, statistics, images and job pages already show, gathered on one page. Per-account rows name only members of this contest |
| Security | `withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, Some(id)))`; root passes. Scoping in §7. Negative specs: an organizer, a juror, another contest's admin, an inactive admin |
| Usefulness | Highest. It answers "what state is it in and what's likely wrong" from data that exists, with no new personal data |

### 3.4 (d) A CLI for the maintainer over ssh

**What it is.** `wlxjury-diag`, run as the support account, no sudo. It installs to `/usr/local/bin` through
`wlxjury-infra`, or is shipped in the ZIP with a `scripts/diagnostics` mapping in `build.sbt` and
a packaging test (the ZIP has no `scripts/` today). It has three commands.

- **`wlxjury-diag recent [--since 24h]`.** Contests with activity in the window, by name and id,
  worst finding first.
  - **From the app:** jobs and round status changes, from `GET /api/diagnostics/recent` (root
    only). The app doesn't read log files, so before D-3 the endpoint has no denials.
  - **From the log:** the CLI adds denial counts per contest, read by `targetContestId` (and the
    `round_access_refused` lines of D-0b).
  - **With D-3:** the endpoint lists denials too, from `support_event`.
- **`wlxjury-diag contest 466`** (or `--name "Nepal"`). The health report from
  `GET /api/contests/466/health`, printed in a few lines: failures and warnings first, details
  behind `--verbose`.
- **`wlxjury-diag log --contest 466 | --account 812 | --ref 3fa9c1d2 [--since 48h]`.**
  - It reads `/var/log/wlxjury/application.log*` and, after D-2, `requests.log*` (gzip
    included).
  - It filters denials by `targetContestId` / `accountId`, which the denial line already has,
    and prints counts by event and reason plus the last lines of each kind.
  - It also covers job failures and crashes and Commons retries.
  - Control characters are stripped.
  - With `--apache` it aggregates the access logs by masked path (`Secured.logPath`'s rules, no
    query string) and status. IPs are printed only with `--ip`.

**Every command follows the release tool's output rule.** A few lines, then exactly one next
command as the last line, filled with the real ids it found, never a placeholder. For example:

- `recent` ends with `wlxjury-diag contest 466` for the worst contest;
- `contest 466` ends with `wlxjury-diag log --contest 466 --since 48h`;
- `log` ends with the `contest` command of the contest it read, or with `--ref` of the newest
  reference it printed.

**How the CLI calls the app (DG3, decided: the token scope).**

- **Chosen (DG3): a read-only, server-only API token.** The CLI uses loopback HTTP to the
  existing `/api`, with a new personal API token of the maintainer's account kept in
  `~/.config/wlxjury-diag/token` of the support account (mode 600).
  - **The restriction is on the token, not on the account.** D-1b adds a token scope chosen when
    the token is created: "full" (today's behaviour, the default for every existing token) or
    "read-only, from the server only".
  - **What D-1b builds for it:**
    - a migration `V108__Api_token_scope` (`api_token.scope`, default `full`; V108 has since gone
      to the quick fixes' NOT NULL `round_user.active`, so it takes the next free number at its
      merge, V109 now);
    - the choice on the token page;
    - the check in `ApiAuth`.
  - **The check.** A read-only token is accepted only on GET endpoints and only from the server
    itself, by the same test as the fallback in §6: `ReadinessController.isLocal` **and** no
    `X-Forwarded-For` or `Forwarded` header. A request through Apache never passes it: Play takes
    the address from `X-Forwarded-For`, and the header is present anyway.
  - **Why this, not a rule on root accounts.** All four `/api` endpoints are `rootOnly`, and
    `docs/api.md` documents remote GET and PUT with a root token. A rule on root accounts' tokens
    would end that use, break `ApiSpec`'s token blocks, and make
    `ApiAuth.bearerWithoutSession` dead code. Existing tokens keep working unchanged.
  - **What it gives.** No sudo, no new kind of secret, and authorization is the real one:
    `contestPermission` of the token's account, as in a browser. A leaked diagnostics token reads,
    from the server itself only, what its account may read, and writes nothing. It is revoked in
    the app like any token.
  - The CLI sends a jury `Host`, as the local smoke test in deployment.md does (the
    `WLXJURY_ALLOWED_HOSTS` paragraph).
- **Alternative: retire remote root writes** (DG3 option 2). Every root token becomes GET-only
  and server-only, with no scope column.
  - It is simpler, but it ends the remote contest create and update documented in `docs/api.md`,
    changes the shipped API's contract, and rewrites the `ApiSpec` token tests.
  - Any future API client would need the scope anyway.
- **Fallback, if the maintainer prefers no token on his account** (DG3 option 3). A separate
  diagnostics token:
  - Its SHA-256 sits in a root-owned file named by `production.conf`
    (`diagnostics.tokenHashFile`), not in `runtime.env`, which `wlxjury-infra` renders from the
    vault and overwrites.
  - It needs a one-time sudo step or an infra task.
  - It is used on a separate loopback route (§6).

| | |
|---|---|
| Effort | `recent` + `contest`: S–M with D-1b; `log`: S–M in D-1c (Python, tests over sample logs like `test_browser_stats.py`) |
| Privacy | Ids, counts, contest names, category titles. No emails; names of accounts only as root sees them on the health page, and only with `--names` |
| Security | Existing token mechanism plus the new read-only, server-only scope. Read-only |
| Usefulness | High for Claude and the maintainer, who reach production by ssh. It would have answered the Nepal case without a browser |

### 3.5 (e) A "report a problem" button

**What it is.** A footer and error-page link to a short form: what you were doing (free text,
optional), and a consent tick to send the page, the time, your account and your recent request
references. It stores a `support_report` row, never a log line. The reporter sees a reference.
Root and that contest's admins see the reports in a list. No mail is sent.

| | |
|---|---|
| Effort | M (2–3 days) |
| Privacy | Free text: consent per report, retention limit |
| Security | A write endpoint: CSRF, a rate limit, a length cap, escaped rendering |
| Usefulness | Medium. Reports today come by personal contact. Its value is the captured contest and references. Worth building only after (a) and (c) |

### 3.6 (f) Other options that fit

1. **`support_event` table** (D-3). One row per denial, CSRF refusal, server error, job failure
   or empty import, holding:
   - `kind`, `contest_id`, `account_id`;
   - reason or key;
   - route template, `ref`, time.

   It is purged after 30 days. The health page and `recent` then show "recent problems of this
   contest's members" without reading log files from the app.
2. **Import provenance** (D-6). Each file's Commons upload timestamp and the job that linked it
   into the contest. With these, H3 (old files) is exact.
3. **Contest settings history** (`contest_change`). Who changed the source, name, year or
   country. It may fold into P19.
4. **Warnings at the point of action** (in D-0b):
   - a source with no namespace prefix is checked on Commons as a page and as a category before
     the job is queued (DG11, decided);
   - the images page says when an import ended with 0 files inserted and 0 linked, or left files
     out as "in no country category".
5. **A jobs list for root** (D-7), filterable by contest, kind and status.

## 4. Recommendation and PR split

**Combination.**

1. First: quick fixes and the contest health report, on a page and over ssh through `/api`.
2. Then the request line and the event table, which give the report and the CLI the "who did
   what and what the app answered" half.
3. The timeline and the report button only if real cases still need them.

**First PRs: D-0a + D-0b + D-1a, then D-1b and D-1c** (DG1, decided by the maintainer
2026-11-18). Of all the options, only the health report shows the whole Nepal answer:

- the contest's identity against its sources;
- its files by country, and those in none;
- its import jobs;
- the empty round with its sources and filters.

The report reads only data that exists. D-1b follows right after, so that Claude can print the
report over ssh.

| PR | Content | Size |
|---|---|---|
| **D-0a** | The logging plan as designed: `conf/logback-production.xml` in the ZIP, the unit switch in `wlxjury-infra`, the deploy tools' lists | S (half a day + 1 h infra; one release and one playbook run) |
| **D-0b** | **Logs:** the job summary lines as key-value lines with `contestId` and `logValue`-quoted source (`import_done job= contestId= source="…" seen= inserted= linked= updated= missing= notInCountries= byCountry=`); Commons and image-cache WARN lines quote titles and exception text; `server_error` and `csrf_failure` gain `accountId` (and `contestId` when the session has one); the production logback (via D-0a) gets at INFO: `services.imports`, `services.jobs`, `security.account`, `security.roles`, `round.admin`, and the new `round.access` (below). `round.counts` stays at WARN: its INFO line is the periodic recompute's summary (`round_counts windows= files= changed=`), with no support value, and its failures are already WARN. **Gallery and large view only:** `isNotAuthorized` returns a reason: `round_stopped`, `round_distributing`, `juror_stopped` (paused in the round), or the existing membership reasons; the juror sees a 200 state page for it (DG7, decided), with new message keys, since the existing `round_stopped` text on `accessDenied` is the comment path's and stays. With DG7 (decided: a 200 page, no authz line) each refusal is still logged once at INFO on the new logger `round.access`: `round_access_refused reason=round_stopped\|round_distributing\|juror_stopped roundId= accountId= contestId=`. H17 and the CLI read that line. The vote path's 409 `round_closed` is unchanged (`vote-errors.js` relies on it). **Import form:** DG11's Commons check of a prefix-less source (page and `Category:<name>`; use the one that exists, ask when both do, refuse when neither does, say so when Commons is unreachable), and the images page's note on an import that found 0 files | M (3–4 days) |
| **D-1a** | `ContestHealth`; the page `/admin/contests/:id/health`, linked from the contest's admin pages; the Nepal checks H1, H2, H4, H5, H7, H8, H9, H10, H12; the import form's H1 warning (DG10), from the same H1 logic run on the typed source before the job is queued; specs for each check, the form warning, the page's authz negatives, and H2's cost on the Gatling fixture DB (38k files) | M (3–4 days) |
| **D-1b** | The token scope "read-only, from the server only": `V108__Api_token_scope` (`api_token.scope`, default `full`), the choice on the token page, the check in `ApiAuth` (GET only; `isLocal` and no `X-Forwarded-For`/`Forwarded`), with negative specs (a scoped token on PUT/POST, through a forwarded request, from a remote address) and the existing `ApiSpec` token blocks unchanged; `GET /api/contests/{id}/health` and `GET /api/diagnostics/recent` (root; jobs and round status changes); `wlxjury-diag recent` (denial counts from the log) and `contest`; packaging or infra install; docs (`docs/api.md`, operations §1, §5; admin guide) | M (3–4 days) |
| **D-1c** | `wlxjury-diag log` (H17–H19 from the logs); the remaining checks H6, H11, H13–H16, H20, H21 | M (3 days) |
| **D-2** | (a) the request line and one reference per request; `requests.log` | M (2–3 days) |
| **D-3** | (f1) `support_event` and its purge; the health page shows it, and `GET /api/diagnostics/recent` gains denials | S–M (2 days) |
| **D-4** | (b) timeline, root first | M–L (3–5 days) |
| **D-5** | (e) report a problem | M (2–3 days) |
| **D-6** | (f2) import provenance, exact H3 | M (2–3 days; a migration and a backfill job) |
| **D-7** | (f5) jobs list for root | S (1 day) |

**Order.**

1. D-0a, D-0b and D-1a first (DG1, decided 2026-11-18); D-0a and D-0b can go in the next
   release.
2. Then D-1b and D-1c, in that order.
3. D-2 to D-7 are scheduled only if support cases call for them. Their decisions are asked then.

## 5. "What could be wrong": the checks

**Scoping for every check.** An admin sees only rows of `job.contest_id = :id` and of this
contest's rounds and members. Global jobs (`round_image_check` of all rounds, `author_normalize`),
the Commons login state and the image cache's global state are shown to root only.

**Thresholds.** The N minutes, shares and days live in `diagnostics.*` config. They are tuned in
D-1a's review on a production copy, and are not decisions for the maintainer.

| # | Check | Result and hint | Data | PR |
|---|---|---|---|---|
| H1 | **Contest identity against its sources:** type, year and country against each import job's `source` and `contest.images` (year number, "Monuments"/"Earth", country) | warn: "This contest is Wiki Loves Earth 2020; recent imports came from WLM 2026 Nepal. Files are added to the old ones. Ask the tool administrators for a new contest." The decisive check | `contest_jury`; `job.params.source` | D-1a |
| H2 | **Files by country category, and in none.** Counts per country category of this contest (`ContestCountries.prefixes`), and the count in no country category | warn when many files are in no country category: "These files are in no '<contest name> <year> in …' category. Files of another contest or year show up here." Measured on the Gatling fixture DB | `category_members`, `category`, `images` | D-1a |
| H3 | **Files older than the contest's year** | warn: "N files were uploaded before 2026" | upload date: not stored (D-6) | D-6 |
| H4 | **Import jobs:** status, source, inserted, linked, missing, `notInCountries`, creator, times | fail: a failed or crashed job, with its reference. warn: a done import with 0 inserted and 0 linked ("the source gave no files": check the name on Commons; imports before DG11's check read a name without `Category:` as a page). warn: `notInCountries > 0` ("N files were left out as in no country category of this contest: the source is likely another contest's or year's") | `job` (`commons_image_import`) | D-1a |
| H5 | **Source shape:** `contest.images` and the sources of imports before DG11's check without a `Category:` / `Template:` prefix (since DG11 a prefix-less source is resolved on the form) | warn, same hint as H4 | `contest_jury.images`, `job.params.source` | D-1a |
| H6 | **Commons count against the stored count** | warn when Commons has many more, or 0 (category misnamed or empty) | `CommonsSource.categoryFileCount`, one call, cached for minutes in D-1c | D-1c |
| H7 | **Empty or tiny rounds,** each with its source rounds (`previous`, `exclude_rounds`), its filters (regions, categories, size, media type, top N, …) and its distribution job's count check (expected and written, `job.error.count.mismatch` args) | fail: an active or paused round with 0 files: "Round 1975 has no files: its source is round 1974's selected files, its filter is region X." warn: far below its source | `round_image` (V84) and its counts (V89), `round`, the latest `round_distribution` job | D-1a |
| H8 | **A round made from all files of a mixed contest:** no filter while H1 or H2 warns | warn: "Round 1974 takes every file of the contest, old ones included" | `round` filters; H1/H2 | D-1a |
| H9 | **Distributing too long:** `distributing = 1` or status `draft`, with a queued or running distribution older than N minutes, or a failed or cancelled one | warn or fail, with the job link | `round`, `job` (`round_distribution`) | D-1a |
| H10 | **Frozen with no job holding it** | fail: "Round N is frozen with no job; root can release it" | `RoundFreezes.leftFrozen` | D-1a |
| H11 | **Stuck jobs:** a job of this contest queued longer than N minutes while **no job of its kind is running**, or running with `heartbeat_at` older than the lease | fail | `job`, `jobs.*` settings | D-1c |
| H12 | **No active round,** or the only active one has no jurors: only for a contest with activity in the last N days | info: "Jurors see no round" | `round.status`, `round_user`, recent jobs/votes | D-1a |
| H13 | **Jurors without votes** in an active round after N days, and paused jurors | info, members of this contest by name for admins | `round_image.voted` counts, or the statistics page's per-juror counts | D-1c |
| H14 | **Jurors who can't sign in yet.** The account states as `AccountTokenService` and the account pages define them: pending invitation; share link not used; set-password link pending; no password and no wiki identity; join in progress (V106); retired (V107); inactive membership | warn: "3 jurors have not accepted their invitation" | `user_contest`, `account_token`, `users`, the join and retirement tables | D-1c |
| H15 | **Roles:** no active admin, or a round juror without the jury role | warn | `user_contest`, `round_user` | D-1c |
| H16 | **Invariants:** the latest `round_image_check` job **of this round** (`subject_type = round`, `subject_id`) and its drift; the all-rounds check's figures for this contest's rounds for root only | warn: "run Check images" | `job` state fields (`drift` per round) | D-1c |
| H17 | **Stopped round, jurors still trying:** D-0b's `round_access_refused reason=round_stopped` lines (logger `round.access`) of this round | info: "2 jurors tried round 1974 after it was stopped" | log (CLI) / `support_event` (page, D-3) | D-1c |
| H18 | **Recent denials, CSRF refusals, server errors** of this contest's members, by reason | warn above a threshold, with references | log (CLI) / `support_event` (D-3) | D-1c |
| H19 | **Commons unreachable or throttled:** `MwApi` retries, `CommonsLogin` failures, anonymous session | warn (root only) | log; `CommonsLogin`'s state | D-1c |
| H20 | **Image cache** gaps for an active round | info (root, and admins for their round) | `LocalImageCacheService.progress` | D-1c |
| H21 | **Contest reused across years:** import jobs more than a year apart | info, folds into H1's hint | `job.created_at` | D-1c |
| H22 | **Who changed the source** | info | not stored: f3 or P19 | later |

## 6. Production constraints: Claude as the support account, no sudo, no database login

**What the support account can read today:**

- the app log;
- Apache and mail logs (group `adm`);
- `systemctl status`;
- loopback HTTP (`/health/ready`, and the app itself with a jury `Host`).

**What it can't:** the journal, `/etc/wlxjury-runtime`, the database, the release tool.

So diagnosis goes through two channels, neither needing sudo or a database login.

1. **Log files.** D-0b makes them hold the job and account lines; D-2 adds the request lines.
   `requests.log` gets the same owner, group and mode as `application.log` (§10).
2. **The app's own `/api`** on loopback, with a read-only, server-only token of the maintainer's
   account (§3.4).
   - The token's account is checked by `ApiAuth` and `Secured` exactly as in a browser. That
     reuse of scoping holds because the token belongs to a real account.
   - A userless diagnostics token would have no account to scope by. It could only be
     root-equivalent for reads, which is why it is the fallback, not the default.

**"From the server itself" is one test,** shared by the token scope and the fallback:
`ReadinessController.isLocal` **and** no `X-Forwarded-For` or `Forwarded` header on the request.

**The fallback's hardening** (DG3 option 3: a separate token on a loopback route `/diag/...`).

- **The token.** 256 bits from `SecureRandom`, its SHA-256 in a root-owned file named by
  `production.conf`, compared in constant time.
- **Only the server itself,** by the shared test above.
- **Apache.** Apache proxies `/` on every vhost, so the probes include
  (`wlxjury-probes.conf.j2`) adds `<Location /diag> Require all denied`.
- **Binding and routing.**
  - The app binds to 127.0.0.1 (`-Dhttp.address=127.0.0.1` in the unit).
  - The route is under `+ anyhost`, as `/health/ready`.
- **Refusals** are logged without the token.

That fallback needs one sudo step or an infra task. The default needs none.

**No database grant is needed.** A read-only MariaDB account on views was considered (DG3
option 4). It would bypass the app's scoping and duplicate the checks in SQL.

## 7. Privacy and security

- **Who sees what:**

  | Who | Sees |
  |---|---|
  | Root | Everything, including global jobs, Commons and image-cache state, `recent`, the timeline and reports |
  | A contest admin (active membership) | Only their contest's health page: its jobs (`job.contest_id`), rounds and members. A member's events in another contest are never shown, even for the same account |
  | Organizers | Nothing new (DG2, decided) |
  | Jurors | Nothing new, except the clearer stopped-round page |

  Every new page and endpoint uses `withAuth` / `withAuthOn` or `ApiAuth` with
  `contestPermission(User.ADMIN_ROLES, Some(contestId))`, and checks a resource's contest with
  `Secured.inContest`. Each one gets negative specs.
- **Emails** never appear in a log line, the CLI output or the endpoint. The health page names
  members as the statistics page does. Where an account outside the contest must be identified
  (root views), the email is masked (`OAuthChooserController.maskEmail`,
  `AccountSearch.maskEmailsIn`).
- **Untrusted text:**
  - log lines hold ids, keys, route templates and `logValue`-quoted values;
  - D-0b fixes the existing unquoted lines;
  - report text goes only to the database, rendered escaped;
  - hints are message keys;
  - category titles are escaped by Twirl and stripped of control characters by the CLI.
- **Minimisation and retention:**
  - `requests.log`: 14 days by default, no IP by default;
  - `support_event`: 30 days;
  - `support_report`: 90 days;
  - the app log: 30 days / 1 GB.

  These are recorded in operations §1 and secure-coding §22, and covered by `LogHygieneSpec`.
- **No weakening of authorization:**
  - **Tokens.** Existing tokens keep their full behaviour. The new token scope is opt-in at
    creation, and a scoped token is refused on every non-GET endpoint and on any request not
    from the server itself. Negative specs cover both refusals, and `ApiSpec`'s existing token
    blocks stay as they are.
  - **Gallery and large view.** The new reasons only name refusals that exist today. The juror
    is refused the same rounds as before.
  - **The page.** Under DG7 (decided) the juror gets a 200 page, as `roundPaused` does. It shows
    no files and only what the juror may already know: that the round is stopped. Every refusal
    still leaves a `round_access_refused` line.

## 8. Follow-ups outside this plan

- **Admins can't create contests.** The Nepal admin reused an old one. Options: a request flow
  to root, or scoped admins (the roles note). H1's hint covers this until then.
- **Removing files from a contest.** Imports only add files.
- **P19's audit log.** H22 and the timeline read it.

## 9. Risks

- **Log volume** of D-2. Mitigated by a separate file and cap and by the exclusions. Measure
  requests per day from the Apache logs first.
- **Heuristics that cry wolf** (H1, H2, H8). They are warnings with the facts shown, tuned on a
  production copy.
- **Health page cost** on big contests. Counts on indexed columns, `withAuthOn(blocking)`,
  measured on the fixture DB; the Commons count is cached.
- **A root account's token on the server.** It is created with the read-only, server-only scope,
  kept in a mode-600 file, and revocable in the app. A leak exposes reads from the server itself
  only.
- **Two logback copies drift** until D-0a. DG1 puts D-0a with D-0b, so the logger changes ship in
  the release's own file.

## 10. Not determined for this plan

- Whether the B2 playbook (infra cbdf8cc, `coi`) has run on production, and the server's actual
  logback file. Read the template here, not the server's file. The investigation's finding (root
  WARN, no job summaries) agrees with it.
- The owner, group and mode of `/var/log/wlxjury/*.log` beyond "readable by the support account".
- Requests per day on the jury vhosts, to size `requests.log`.
- What source the Nepal admin typed, and exactly why round 1975 came out empty. Neither is
  recorded where the support account can read it. D-0b's import line and H4, H5 and H7 make both visible next
  time.

## Decisions for the maintainer

### Decided

- **DG1: First PRs, and how the logger changes reach production. Decided by the maintainer,
  2026-11-18:** D-0a (the logging plan) + D-0b (quick fixes) + D-1a (the health page with the
  Nepal checks) first, then D-1b and D-1c.
  - The other options were:
    - D-0b with both logback copies edited and a playbook run instead of D-0a;
    - D-1a alone first.

- **DG2: Who sees the contest health page. Decided by the maintainer, 2026-11-18:** root and
  the contest's admins (the default).
  - The other options were:
    - root only;
    - also the contest's organizers, read-only.
- **DG3: How the maintainer and Claude reach the report over ssh. Decided by the maintainer,
  2026-11-18:** the new token scope (the default).
  - [chosen] **A new scope for personal API tokens.** A scope "read-only, from the server only",
    chosen when a token is created (`V108__Api_token_scope`, the token page, the check in
    `ApiAuth`):
    - the token works on GET endpoints only;
    - it works only from the server itself (`isLocal` and no `X-Forwarded-For` / `Forwarded`
      header).

    The maintainer creates one such token on his account for the CLI, kept in
    `~/.config/wlxjury-diag/token` of the support account (mode 600). Existing tokens and the remote API use in
    `docs/api.md` are unchanged. No sudo, no new secret type, and the scoping is the account's
    real permissions.
  - **Retire remote root writes.** Every root account's token becomes GET-only and server-only,
    with no scope column. It is simpler, but it ends the documented remote contest create and
    update, changes the API's contract, and rewrites `ApiSpec`'s token tests.
  - **A separate diagnostics token.** Its hash is in a root-owned file named by
    `production.conf`, with a loopback route `/diag` and the hardening of §6. It needs one sudo
    or infra step.
  - **A read-only DB account on views.**
  - **The browser only.**
- **DG7: What a juror sees on a stopped round** (gallery and large view; the vote path's 409
  `round_closed` stays). **Decided by the maintainer, 2026-11-18:** the 200 state page with the
  INFO `round.access` line (the default).
  - [chosen] **A 200 page, as the `roundPaused` precedent.** "This round is stopped. Your votes
    are kept. Ask the contest admin.", with the juror's other rounds.
    - It is a state, not a denial, so there is no authz line. Each refusal is logged once at INFO
      on `round.access` instead (`round_access_refused reason= roundId= accountId= contestId=`),
      which H17 reads.
    - The same for `round_distributing` and `juror_stopped`, each with its own text.
  - A 403 with reason `round_stopped` / `round_distributing` / `juror_stopped` in the denial line
    and its own page text.
  - Keep the generic 403 page and fix only the log reason.
- **DG10: Contest identity mismatch (H1) at the point of action. Decided by the maintainer,
  2026-11-18:** warn on the import form and the health page, with no refusal (the default).
  - [chosen] **A warning on the health page (H1) and on the import form, with no refusal.** The
    form's warning runs H1's logic on the typed source before the job is queued; D-1a builds
    it.
  - The import form asks for a confirmation tick when the source's year or type differs from the
    contest's.
  - The health page only.
- **DG11: An import source without a namespace prefix. Decided by the maintainer, 2026-11-18,
  with a new option:** the app checks Commons for both the page and `Category:<name>`.
  - **Outcomes:**
    - **Only one exists:** the import uses it.
    - **Both exist:** the form asks the admin to choose, showing the file count of each: the
      category's (its `categoryinfo`, as `CommonsSource.categoryFileCount` reads it) and the
      page's (the files it shows, one `prop=images` request, capped at 500, shown as "500+").
      **The category is preselected**, as the usual intent; the admin confirms it or picks the
      page. *Refined by the maintainer, 2026-11-18:* the choice is still shown even though the
      category is the likely answer, because a page of the same name can exist by coincidence,
      or the admin may have pasted the wrong name, and the two counts make either visible.
    - **Neither exists:** the form refuses with a clear message ("No page or category named …
      on Commons. Check the name, or add `Category:`.").
    - **An import that still finds 0 files** says so on the images page (D-0b's f4).
  - **Where the check runs.** In the form's validation in `ImageController.importImages`, before
    the job is queued, for both actions of the form ("Import files" and "Update monuments").
    - **Thread pool.** The action moves to `withAuthOn(blocking)`, because the check is a
      blocking Commons call.
    - **Timeout.** Both requests together are limited to a few seconds
      (`diagnostics.sourceCheckTimeout`, default 5 s).
    - **When Commons is unreachable** or times out, nothing is guessed and no job is queued. The
      form is shown again, with the typed source kept in the field and the message "Commons
      could not be reached to check this name, so nothing was started. Try again later, or add
      `Category:` in front of a category's name."
    - **No fallback to a page.** *Decided by the maintainer, 2026-11-18,* replacing the first
      text's "a second send reads it as a page": "the import needs Commons anyway, so deciding
      without Commons won't get any further." Every send of a prefix-less name is checked; the
      form's `sourceAs` (page or category) is only the both-exist choice's, and counts only when
      the check finds both again.
  - **Scope.** `ImportSource.apply` (page vs category) changes only for input without a
    namespace prefix. A source with an explicit `Category:` or `Template:` prefix, and a pasted
    file list, behave exactly as today, with no Commons check.
  - **Built in:** D-0b, with the f4 warnings, which DG11 replaces for the form. D-0b is re-sized
    to M (3–4 days).
  - **Specs** (with a stub `CommonsSource`):
    - page only;
    - category only;
    - both (the choice is shown with both counts, and the chosen one is queued);
    - neither (refused, no job);
    - Commons down or timing out (the form says so and queues no job, on every send; a
      prefixed source still works);
    - a prefixed source makes no check;
    - a done import with 0 files says so.
  - **The options that were offered:**
    - keep reading it as a page, warn on the form, and say so when it gave 0 files (the
      default);
    - treat "Images from …" without a prefix as a category;
    - refuse a source with no prefix.

### Deferred: ask when the PR is scheduled

- **DG4: Build the per-request line (D-2).** Yes, in `requests.log` / no.
- **DG5: Retention.** `requests.log` 14 / 30 / 7 days, and `support_event` 30 days (D-3).
- **DG6: Client IP in the request line.**
  - No IP (default).
  - A keyed hash. Note that a keyed hash of an IPv4 address can be reversed by anyone with the
    key (2³² candidates), and fail2ban's planned `security.auth` line logs the raw `ip=` anyway.
  - The IP itself.
- **DG8: Report a problem (D-5).** Later with consent and 90 days / now / never.
- **DG9: Activity timeline (D-4).** Root first / root and admins / not at all.
