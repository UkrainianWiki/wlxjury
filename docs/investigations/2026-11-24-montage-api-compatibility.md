# Montage API compatibility: one backend for both tools? (investigation, 2026-11-24)

The maintainer's idea (2026-11-24): "Could we clone the Montage API? That would allow running both
tools from one backend, potentially fixing some existing Montage errors that come from its backend,
let users easily switch between the two, or have tools that work with the APIs (also import/export
between the two). It would also guarantee feature parity. The question is how features/logic unique
to WLX Jury would be expressed then."

**Status:** investigation only; nothing is planned or built. Static reading of both codebases and of
Montage's GitHub issues; nothing was run. Effort figures are estimates for one developer who knows
this code base.

- **WLX Jury** at `a9c9adcf` (`master`, 2026-11-24). Paths without a prefix are in this repository.
- **Montage** ([hatnote/montage](https://github.com/hatnote/montage), BSD-3-Clause) at
  [`9dec5dd`](https://github.com/hatnote/montage/tree/9dec5ddfc29d1ce61fd768041fbe540b245b5928)
  (2026-11-23). Paths that start with `M:` are at that commit. The backend (`montage/`) is unchanged
  since `f9ddfbd` (2026-10-26): since then only translations and the narrow-screen vote layout
  (Montage PR #663) changed. The earlier full comparison (local notes, not in this repository; at
  `f9ddfbd`) is the source of many line numbers below; they were spot-checked at `9dec5dd`.
- **Montage issues:** the 173 open issues on 2026-11-24 (`gh issue list -R hatnote/montage`), by
  number: https://github.com/hatnote/montage/issues/N.
- Related here: [GraphQL plan](../plans/2026-11-24-graphql-sangria.md) (PR #135),
  [UI approach](2026-11-24-ui-approach.md), [React and the gallery](2026-11-24-react-gallery-alternative.md),
  [REST API](../api.md), [Montage parity on rounds](../plans/2026-11-02-montage-rounds-parity.md),
  [roles compared with Montage](../plans/2026-11-10-roles-prejury-montage.md),
  [contest series](../plans/2026-11-18-contest-series.md), [Wikimedia Cloud](2026-11-04-wikimedia-cloud.md),
  [sign-in with Wikimedia](../oauth.md).

"Inferred" marks a conclusion the code does not state directly.

## Summary and recommendation

**Short answers.**

1. **Montage's API is small but not a contract.** The Vue SPA calls 33 backend endpoints under
   `/v1/` (of 87 route definitions), plus `/login` and `/logout`, plus three Commons API queries made
   from the browser. Every response is wrapped in `{status, errors, data, user}`. There is no
   OpenAPI, no versioning beyond `/v1/`, a partly stale hand-written `M:montage/docs/api.md`, no
   tokens, no CSRF tokens. Sign-in is Wikimedia OAuth 2.0 and a signed cookie, nothing else. The
   rules live in the backend's DAOs (`M:montage/rdb.py`, 3807 lines); the SPA is presentation.
2. **The models map well at the core and diverge at the edges.** Contest ↔ campaign, round ↔
   round, `round_image` ↔ `round_entries`, vote rows as tasks ↔ `votes`, yes/no ↔ binary,
   `round.status` names (WLX copied Montage's in V105), quorum ↔ `distribution`, series ↔ series:
   equivalent or close. Montage's 5-star rating is a subset of WLX's 1–N scales. **Ranking rounds
   (Schulze) are missing in WLX**; so are finalize, the final report, directions and deadline,
   quorum change, faves and the CSV/sheet import (all in the parity plan). WLX's criteria, 3–20 star
   scales, half stars, the ✗ mark, comments-only rounds, regions, monuments, special nominations,
   filters, top-N, multi-source rounds, per-contest roles, read-only organizers, series admins,
   author-based conflicts of interest, background jobs, image cache and comments are **missing in
   Montage** and have no place in its API (section 2).
3. **A WLX backend would not fix most Montage errors users see.** Of the 173 open issues about 45
   are backend bugs or backend performance, about 35 are frontend bugs, about 80 are feature
   requests, about 10 are infrastructure. A WLX backend would avoid perhaps 20–25 of the backend
   ones (import timeouts on the replicas, out-of-memory on big rounds, no background jobs, a few
   500s), but only after WLX had built the same features. The ranking and finalize issues would
   come back as open design questions. The frontend issues would stay as they are. Several of the
   backend bugs filed on 2026-10-31 already have open Montage PRs (#677, #678, #679, #683, #684,
   #686, #630/#631) (section 3).
4. **Options.** (A) A facade that runs Montage's unmodified SPA on WLX data: not possible
   unmodified. WLX requires a CSRF token on every POST and runs long work as background jobs.
   Montage's sign-in, identities (Wikimedia usernames created on demand) and "one open round per
   campaign" also differ. A juror-only subset with a lightly patched SPA is possible (12–20 days,
   then upkeep on every Montage release). The full coordinator surface needs WLX to build ranking,
   finalize, the report and Montage's synchronous import check first (60–100 days in all). (B) A
   sync between the two backends: Montage offers no API a server could use (cookie-only, OAuth,
   no tokens) and its data is in the montage tool's ToolsDB, so B needs the Montage maintainers'
   work and consent. It also creates two sources of truth. Not recommended. (C) An exchange format:
   cheap, useful and partly free, because Montage already reads a file list (a `filename` column or
   one name per line) and writes entries, results and reviews exports. (D) A neutral API (the
   GraphQL work) with Montage's vocabulary where it fits and a capabilities query: the right
   long-term frame. A "Montage-compat profile" on top of it is option A again (section 4).
5. **WLX-only features in a Montage-shaped API** can only be extension fields that Montage's SPA
   ignores (it shows unknown `config` keys raw and re-posts them), a `/wlx/` namespace, or a
   capabilities document. The SPA renders nothing for a vote method it doesn't know. So a WLX round
   Montage can't represent must be **hidden** from a Montage client, or at most listed read-only for
   its coordinators (section 5).

**Recommendation: don't clone the Montage API now.** Do the exchange format (C) in small steps.
Keep the parity plan as the way to feature parity, and shape the GraphQL schema (D) so that a
Montage-shaped view stays possible later. Talk to the Montage maintainers before anything that
touches their users or their UI.

- **Why not A now.** "One backend" makes WLX the host of Montage's campaigns (data on a chapter's
  server instead of Toolforge), or makes WLX run on Toolforge in an OAuth-only mode that does not
  exist yet ([Wikimedia Cloud](2026-11-04-wikimedia-cloud.md)). It ties WLX's release cycle to an
  unversioned API that is changing now (background imports, Montage PRs #630/#631, change the import
  contract). It gives feature parity in one direction only: WLX must implement Montage's semantics,
  while WLX's own features stay invisible in Montage's UI. The parity plan delivers the same features
  without the contract cost.
- **Why C.** Moving a contest between tools is what "users switch easily" needs in practice, and
  two of its parts need nobody's consent: WLX can write the file list Montage's import reads, and
  WLX can read Montage's exports.

**Staged path (estimates):**

| Step | What | Effort | Needs Montage maintainers |
|---|---|---|---|
| C1 | A round's or contest's files as a list Montage's import reads (`filename` column, plus WLX's page id), formula-guarded | 0.5–1 day | no |
| C2 | Montage's entries or results CSV as a WLX file source (paste or upload), with a test fixture | 1–2 days | no |
| C3 | Montage's results CSV (`filename, average, <juror>…`) as a closed, read-only WLX round with its votes, jurors matched by wiki username | 4–8 days | no |
| C4 | A documented contest archive (JSON: contest, rounds, files, members by wiki username, votes, disqualifications), export and import in WLX | 8–15 days | only if Montage is to read or write it too |
| D1 | In the GraphQL schema: a `scale` type, `capabilities`, Montage's names where they fit | 2–4 days on top of the GraphQL slices | no |
| A1 | Only if the maintainers of both tools want it: a juror-only Montage-compatible facade (yes/no and 5-star rounds) | 12–20 days, then upkeep | yes |

## 1. Montage's API surface

### 1.1 Architecture, conventions and sign-in

- **Backend:** Python, Clastic 24 (not Flask, as the UI approach investigation §4.2 says), SQLAlchemy
  1.2, gunicorn with 4 workers and a 120 s timeout (`M:Procfile`). Three Clastic apps: the SPA at
  `/` and `/a/`, a small UI app (login, logout, HTML report, docs), and the JSON API at `/v1/`
  (`M:montage/app.py:227-234`). One DB transaction per request, committed when the status is below
  400 (`M:montage/mw/__init__.py:243-271`). There are no background jobs: imports and activation run
  inside the request (Montage PRs #630/#631 for a worker are open). Deployed on Toolforge (Build
  Service, ToolsDB MariaDB; Commons wiki replicas for imports) at montage.toolforge.org.
- **Envelope:** every JSON response is `{"status": "success"|"failure"|"exception", "errors": [...],
  "data": ..., "user": {...}}` (`MessageMiddleware`, `M:montage/mw/__init__.py:28-100`). Errors are
  exception classes with HTTP 400/403/404 (`InvalidAction`, `PermissionDenied`, `DoesNotExist`,
  `M:montage/utils.py:49-63`); import-check errors add `error_type`, `reason_code`, `reason_params`
  for translated messages (`M:montage/utils.py:66-112`). Query string and JSON body are merged into
  one `request_dict`.
- **Sign-in:** `GET /login` starts Wikimedia OAuth 2.0 with PKCE on meta.wikimedia.org (scope
  `basic`); `GET /complete_login` creates the user row on first sign-in (id = Wikimedia central user
  id) and writes `userid`/`username` into an HMAC-signed, HttpOnly cookie; `GET /logout` clears it
  (`M:montage/public_endpoints.py:167-280`). No passwords, no tokens, no server-side sessions.
  Superusers may act as anyone with `?su_to=<username>` (`M:montage/mw/__init__.py:151-162`).
- **CSRF:** none (no token; state changes are JSON POSTs, and the protection relies on browsers'
  SameSite defaults and the JSON content type). CORS only in `dev`.
- **SPA side:** axios with `baseURL = VITE_API_ENDPOINT + '/v1/'` and `withCredentials`
  (`M:frontend/src/services/api.js`); `VITE_API_ENDPOINT` is empty in production (same origin) and a
  build-time setting, so the SPA can be built for another host or path prefix. Sign-in and sign-out
  are `window.location = VITE_API_ENDPOINT + '/login'` / `'/logout'`; the router guard calls
  `GET /v1/admin/user` once and reads the envelope's `user` (`M:frontend/src/stores/user.js`).
  The browser also calls the Commons API directly (imageinfo for each task's file history,
  `globalallusers` for the juror picker, `opensearch` for categories;
  `M:frontend/src/services/dataService.js`), and loads every image straight from Commons
  `Special:Redirect/file/<name>` built from the file name (`getCommonsImageUrl`,
  `M:frontend/src/utils.js:65-82`), not from URLs the backend returns.

### 1.2 What the SPA calls (the surface a facade would have to serve)

From the service modules (`M:frontend/src/services/adminService.js`, `jurorService.js`) and their
callers in `M:frontend/src/components/`. The request and response shapes are the handlers' and the
models' `to_info_dict`/`to_details_dict` (`M:montage/rdb.py`).

| # | Method, path (under `/v1/`) | Called from | Request | Response `data` (main fields) |
|---|---|---|---|---|
| 1 | `GET admin/user` | user store (every page) | – | `[]`; the envelope's `user` = `{id, username, is_organizer, is_maintainer}` |
| 2 | `GET admin` | ActiveCampaign | – | coordinator's active campaigns: `{id, name, url_name, open_date, close_date, create_date, status, is_archived, rounds[], coordinators[], active_round}` |
| 3 | `GET admin/campaigns/all` | AllCampaign | – | same, incl. archived/finalized |
| 4 | `GET admin/campaign/<id>` | ViewCampaign | – | one campaign as above |
| 5 | `POST admin/add_campaign` | NewCampaign | `{name, url, open_date, close_date, coordinators[usernames], series_id?}` | the campaign |
| 6 | `POST admin/campaign/<id>/edit` | ViewCampaign | `{name?, is_archived?, open_date?, close_date?}` | – |
| 7–8 | `POST admin/campaign/<id>/add_coordinator`, `/remove_coordinator` | ViewCampaign | `{username}` | – |
| 9 | `POST admin/campaign/<id>/finalize` | ViewCampaign | `{post: true}` | report summary (needs a finished ranking round) |
| 10 | `POST admin/add_organizer` | AddOrganizer (maintainers) | `{username}` | – |
| 11 | `POST admin/campaign/<id>/import/check` | RoundNew | `{import_method: category\|csv\|gistcsv\|selected, category?, csv_url?, gist_url?, file_names?}` | check summary with per-status counts, issues, `check_token` (valid 1 h) |
| 12–13 | `GET admin/campaign/<id>/import/check/<token>/download`, `/issues` | RoundNew (links) | – | CSV files |
| 14 | `POST admin/campaign/<id>/add_round` | RoundNew | `{name, vote_method: yesno\|rating\|ranking, jurors[usernames], quorum, deadline_date, directions?, description?, config?, show_stats?, import: {import_method, check_token}}` | round details + `progress` + `import` result |
| 15 | `GET admin/round/<id>` | RoundInfo | – | round: `{id, name, directions, vote_method, status, quorum, config, deadline_date, jurors[], is_closable, campaign, total_round_entries, stats, juror_details[]}` |
| 16 | `POST admin/round/<id>/edit` | RoundEdit | `{name, deadline_date, directions, show_stats, quorum?, new_jurors?, config}` | – |
| 17–18 | `POST admin/round/<id>/activate`, `/pause` | RoundInfo | `{post: true}` | – (activate creates every task, synchronously) |
| 19 | `POST admin/round/<id>/finalize` | RoundInfo | – | – |
| 20 | `POST admin/round/<id>/cancel` | RoundEdit ("Delete round") | – | – |
| 21 | `GET admin/round/<id>/preview_results` | RoundInfo, RoundNew | – | counts, completion %, histogram, damped histogram, `thresholds` (threshold → files at or above); ranking: Schulze order |
| 22 | `POST admin/round/<id>/advance` | RoundNew | `{threshold, next_round: {name, vote_method, jurors, quorum, deadline_date, …}}` | the next round (source round finalized) |
| 23–24 | `GET admin/round/<id>/results/download`, `/entries/download` | RoundInfo (links) | – | CSV: `filename, average, <juror>…`; entries CSV (dimensions, uploader, upload time, `file_id`, source) |
| 25 | `GET juror` | ActiveCampaign | – | the juror's active rounds, grouped by campaign |
| 26 | `GET juror/campaigns/all` | AllCampaign | – | same, all |
| 27 | `GET juror/round/<id>` | Vote, VoteEdit | – | `{id, name, directions, vote_method, status, deadline_date, config, total_tasks, total_open_tasks, percent_tasks_open, ballot, campaign, show_stats}` |
| 28 | `GET juror/round/<id>/tasks?count=10&offset=` | Vote, VoteYesNo, VoteRating | – | `{stats, tasks: [{id (vote id), name, user, value, date, round_id, is_fave, review, entry: {id, name, mime_major, mime_minor, width, height, resolution, upload_date, url, url_sm, url_med, file_id}}]}` |
| 29 | `POST juror/round/<id>/tasks/submit` | all vote views, VoteEdit | `{ratings: [{vote_id, value, review?}]}` (≤100; yesno 0/1, rating 0, .25, .5, .75, 1; ranking 0…N-1, all at once) | `{}` |
| 30 | `POST juror/round/<id>/tasks/skip` | VoteYesNo, VoteRating | `{vote_id}` | – |
| 31 | `GET juror/round/<id>/votes?offset&order_by&sort` | VoteEdit | – | the juror's completed votes |
| 32–33 | `POST juror/round/<r>/<entry>/fave`, `/unfave` | vote views | `{}` | – |
| – | `GET /login`, `/logout` (UI app, not `/v1/`) | LoginBox, header | `next` | redirects |

Defined but with no SPA caller (API or CLI only): disqualification (auto, preview, manual,
requalify, list), flags, reviews, juror stats, faves list, remove juror, campaign cancel and reopen,
report publish/unpublish, series, users, audit log, log viewers, and `admin/round/<id>/import`
(`M:montage/admin_endpoints.py:40-98`, `M:montage/juror_endpoints.py:37-53`). Public endpoints:
published reports, series, entry info, Commons lookup utilities, health
(`M:montage/public_endpoints.py:55-68`).

### 1.3 Data model (`M:montage/rdb.py`, 14 tables)

- `users` (id = Wikimedia central id, `username`, global `is_organizer`); maintainers are a
  hard-coded list (`MAINTAINERS`, `:145`).
- `series` → `campaigns` (name, url, open/close date = the eligible **upload window**, not the
  voting window; status active/finalized/cancelled; `is_archived`) with `campaign_coords`
  (coordinators).
- `rounds` (name, `directions` for jurors, `description` for coordinators, `vote_method`, `quorum`,
  `deadline_date` (shown, not enforced), `config` JSON, status paused → active ↔ paused → finalized |
  cancelled; one active or paused round per campaign, `:1359-1362`) with `round_jurors` (`is_active`,
  skipped task ids).
- `entries` (global files: name, MIME, size in pixels, uploader, upload date, `file_id`),
  `round_entries` (membership, `dq_reason`, `dq_user_id`), `round_sources` (import method and
  parameters).
- `votes`: task and vote in one row (status active = open task, completed, cancelled; `value`;
  `modified_date`; review text in `flags`). `favorites`, `flags`, `results_summaries` (the final
  report, publishable), `audit_log_entries` (admin actions, not votes).
- **Roles:** maintainer (global, code), organizer (global flag: creates campaigns and series),
  coordinator (per campaign: everything in it), juror (per round). No read-only role; a
  coordinator who is also a juror sees all votes mid-round.
- **Vote methods:** yesno (0/1), rating (1–5 stars stored as 0–1 in quarters), ranking (full order
  of all non-disqualified files, at most 100 in practice; Schulze aggregation, `:2153-2207`).
- **Task assignment:** on activation, shuffled files × quorum cut into blocks per juror; ranking
  gives everyone everything (`:1717-1746`, `:3345-3457`). While paused: swap or change jurors with
  reassignment, change the quorum, remove a juror (`:2290-2529`).
- **Disqualification:** rules for upload date outside the campaign window, uploader is a juror,
  coordinator, organizer or maintainer, minimum resolution, file type; run after each import, with a
  preview, manual disqualify and requalify (`:1452-1640`).
- **Advance:** finalize a yes/no or rating round and create the next with every file whose average
  of completed votes is at or above a threshold (`:2084-2108`, `M:montage/admin_endpoints.py:704-759`).
  The campaign ends with a ranking round, then finalize builds the report (`:2559-2627`).
- **Exports:** results CSV (`filename, average, <juror>…`, `tbv` for "to be voted"), entries CSV
  (`img_id, img_name, mime, width, height, uploader, timestamp, file_id, source_method,
  source_params`), reviews JSON, report JSON/HTML. **Import:** a Commons category (direct members, via
  the replicas), a CSV/Google Sheet/gist URL or a pasted list (a `filename` or `img_name` column,
  optional `file_id`, other columns ignored, or one name per line), or the previous round.
  There is no whole-campaign export or import.

### 1.4 Server logic and frontend logic

The backend holds every rule: permissions (in the DAO constructors), vote validation, task
assignment and reassignment, quorum, disqualification, thresholds, Schulze, the report, the import
check (row classification, collation-exact name collisions, SSRF-guarded fetches). The SPA holds
presentation and a few defaults (round 1 yes/no, 2 rating, 3 ranking; quorum 1; jurors copied from
the previous round), client-side validation (zod), the batch-of-10 task fetch and image preloading,
and the Commons lookups. Some SPA behaviour is a rule in practice. Only the first round's form
shows `config`, and advance sends none. "Add round" is blocked while a round is active or paused.

## 2. Mapping: Montage ↔ WLX Jury

Legend: **=** equivalent; **≈** adaptable (a facade or exchange can translate, with losses noted);
**WLX –** missing in WLX; **MT –** missing in Montage. WLX state is `master` at `a9c9adcf`; "parity
#N" is the row of the [parity plan](../plans/2026-11-02-montage-rounds-parity.md) §3.

| Montage | WLX Jury | Verdict | Notes |
|---|---|---|---|
| `series` (name, url, status; API/CLI only, no admins) | `series` (V109, CY-1, on master) with contest type and country; series admins (CY-2, V112, built on a branch, not yet on master) | ≈ / **MT –** for series admins | Close in shape; WLX's series admins and the start-year rule have no Montage equivalent |
| campaign | contest (`ContestJury`: name, country, year, images source, monument-id template, campaign code) | ≈ | Montage's open/close dates are an upload window (WLX –, parity #3); WLX's country/year/campaign/monument template are MT – |
| campaign status active/finalized/cancelled, archive | contest `retired` | ≈ | No contest finalize in WLX |
| round (name, vote method, quorum, status, config) | `round` (number, name, `rates`, `distribution`, `status` V105, filters) | ≈ | Status names are the same (draft/paused/active/finalized/cancelled); WLX writes no `finalized` yet (parity #9). Montage allows one open round per campaign; WLX allows several at once |
| `directions`, `description`, `deadline_date` | – | **WLX –** | parity #10 |
| yesno (0/1) | binary (`rates` −1/0/+1: rejected/unrated/selected) | = | 1.0 ↔ +1, 0.0 ↔ −1, open task ↔ 0 |
| rating (5 stars as 0–1) | rated 1–N, N = 3..20, half stars, ✗ mark (−1), score rule per round | ≈ (Montage is a subset) | Only a 1–5 round without half stars, ✗ or criteria fits Montage's `rating` |
| ranking + Schulze, ≤100 files | – | **WLX –** | parity #20 (L); needs finalize |
| – | criteria rounds, comments-only rounds | **MT –** | |
| quorum (editable while paused) | `distribution` (0 = all jurors), fixed after creation | ≈ / **WLX –** for the change | parity #14 |
| task = `votes` row (active/completed/cancelled) | `vote` row (rate 0 = unrated), created by distribution | = | Both keep task and vote in one row |
| activation creates tasks synchronously | "Distribute" queues a `round_distribution` job; the round is hidden from jurors until its count check passes | ≈ | A Montage client expects tasks after the POST returns; WLX's arrive later |
| swap/add/remove jurors, reassign | add a juror with a fair share, reassign a juror's unrated files (parity #12–13) | ≈ | Remove-and-discard votes and quorum change missing in WLX |
| skip (skipped tasks served last) | – (galleries: any order, go back) | n/a | A facade could keep a skip list per juror and round |
| faves | – | **WLX –** | parity #18 |
| flags (API only), reviews on votes | comments per file and contest, comments report and CSV | ≈ | Different visibility: Montage reviews go to coordinators and (aliased) into the report; WLX comments are seen by the contest's members |
| entries (global, `file_id`, uploader, upload date) | `images` (page id, title, size in bytes, MIME, media type, author, description, monument id) | ≈ | Keys differ: Montage `file_id` (from the replicas' `file` table) vs WLX page id; names match both. WLX stores no upload date or uploader (parity #3–4) |
| `round_entries` + DQ reason/user | `round_image` (V84) + disqualification code, who, when, history (V88); votes kept aside | = (WLX ahead) | Montage reopens votes on requalify (Montage issue #637); WLX restores them |
| import: category (replicas, direct members), CSV/sheet/gist URL, pasted list, with a check token | contest import job (category, template embeds, page's files, pasted list, very tolerant parser), round picks from the contest pool | ≈ | CSV URL source **WLX –** (parity #24); checked import with per-row report partial (parity #23); template/page sources **MT –** |
| DQ rules: upload window, uploader is staff, resolution, file type | filters: region, monuments, category in/out, Mpx, MB, media type, special nomination, top-N, "not in rounds X"; CoI by author over wider roles | ≈ | Date rule **WLX –**; region/monument/nomination/top-N/multi-source **MT –** |
| advance: one source round, average ≥ threshold | new round from several previous rounds: min average, selected by ≥ N, rejected by ≥ N, top-N | ≈ (WLX richer) | Threshold preview **WLX –** (parity #6, P3-6 decided) |
| finalize campaign, report, publish | – | **WLX –** | parity #21 |
| results CSV, entries CSV, reviews JSON | results CSV (rank, file, overall, per juror; formula guard), comments CSV | ≈ | Entries export with provenance partial (parity #22) |
| maintainer (code list) | root (`users.is_root`) | ≈ | |
| organizer (global: create campaigns) | root; series admins create contests of their series (CY-3) | ≈ | |
| coordinator (per campaign, full write) | Admin (per contest, `user_contest`) | = | |
| – | Organizer (per contest, read-only: statistics, all jurors' votes) | **MT –** | Montage has no read-only role |
| juror (per round) | Juror (per contest role) + `round_user` (per round, active flag) | ≈ | Pre-screener role and pre-screening rounds (multi-role plan) **MT –** |
| coordinator-juror sees all votes | a juror of a round sees only their own votes in it, whatever their role (CY-2, #140) | ≠ | A facade must apply WLX's rule; a Montage coordinator would then see less |
| any Wikimedia account signs in; users created on demand from a username | email/password accounts, invitations; Wikimedia/Google sign-in built but off until configured ([oauth.md](../oauth.md)), and only into existing accounts; wiki-only placeholders by username (`/admin/users/add-wiki-confirmed`) | ≈ | Identity is the hardest mapping: a Montage client names people by Wikimedia username, many WLX accounts have none |
| signed cookie, no CSRF, no tokens, `su_to` | session cookie + CSRF on every POST; personal API tokens (`Bearer wlxj_…`, full or read-only-local) under `/api` | ≈ | Montage's SPA sends no CSRF token (section 4, A) |
| audit log of admin actions | per-file DQ history, round status history, contest change log (V109), account change log; no single audit page | ≈ | parity #15 |
| round stats map (entries, tasks, open %, DQ, uploaders, MIME) | Round statistics, contest health page, round warnings | ≈ (WLX richer for organizers) | parity #8 |
| – | background jobs (leases, cancel, retry), image proxy and cache, deleted/renamed file tracking, region bar, video, large view with filmstrip | **MT –** | |
| public reports, entry info, Commons utils | – (no public pages) | **WLX –** | |
| 28 locales via translatewiki.net | 4 languages | (UI, not API) | |

## 3. "Montage errors that come from the backend"

The 173 open issues (2026-11-24), sorted by reading their titles and sampling bodies. The groups
are approximate: some issues fit two groups.

| Group | About | Examples | Would a WLX backend fix them? |
|---|---|---|---|
| Backend bugs and backend performance | 45 | #675 cancel campaign is a 500; #674 skipping another juror's task "succeeds"; #676 dead ranking check; #681, #685, #644 finalize and report 500s; #638 advance counts open votes; #637 requalify reopens votes; #634 same file twice for a juror; #633, #639 out of memory on large rounds; #650, #478 category imports time out; #645, #641 collation collisions; #621, #622, #629 no background jobs; #526, #527, #541 fave collisions; #543, #519 ranking thresholds undefined; #363, #373 ranking over 100 files; #370 jurors get "permission denied"; #138 votes not recorded | **Partly.** By design WLX avoids about 20–25 of them: imports and distribution run as resumable jobs, `round_image` has a unique key, votes are kept aside on disqualification and restored, files are keyed by page id with one import per contest at a time, and the replicas are not used. The ranking, finalize and report issues (#543, #519, #669, #681, #644, #685, #680) would come back as WLX design work, since WLX has no ranking or report. Faves don't exist in WLX. #370-type problems would get **more** likely, because Wikimedia usernames would have to match WLX accounts |
| Frontend bugs and UX | 35 | #689, #688 Edit Votes paging and counts; #682, #643 stale SPA after a deploy; #664, #577 "Add round" errors; #320–#322 Edit Votes; #203 broken thumbnails after a move; #129 file names with `?`; #206, #197, #196, #116, #113 | **No.** They are in the SPA, which a facade would serve unchanged. #203 and #85 could be fixed only if the SPA took image URLs from the backend (WLX's proxy) |
| Feature requests | 80 | #123 image discussion; #97, #30 moved and deleted files; #33 go back; #96, #61 enter many jurors; #100, #36, #59 manual DQ and its list; #74 no self-reviews; #46 import into a running round; #43 import provenance; #29 final report; #567 video; #579 merge rounds; #291 notify jurors | **No, though WLX has many of them** (comments, file tracking, galleries, bulk juror import, CoI, adding files, video, multi-source rounds). An unmodified SPA would not show them |
| Infrastructure, process | 10 | #599, #569 deploy script; #470 CI depends on Toolforge; #300 migrations; #233 config | Not applicable |

Several backend issues filed on 2026-10-31 already have open Montage PRs (#677, #678, #679, #683,
#684, #672, #686; background imports #630/#631). The cheapest way to fix Montage's backend errors is
to help land those, not to replace the backend.

## 4. Options

### 4.0 What "feature parity" means here

Two different things:

- **Same capabilities in both tools.** WLX gets ranking, finalize and report, directions, deadline,
  quorum change, faves, CSV import and the date rule; Montage gets nothing from WLX unless its
  maintainers build it. This is the parity plan; it is about half done on rounds (4 of 25 rows
  closed and 12 partly closed on 2026-11-20); its open rows are sized S or M there, apart from
  ranking with the report (L).
- **The same contract.** Every Montage endpoint behaves as in Montage, including its error envelope,
  its synchronous activation and import check, and its defaults. It adds a compatibility layer and
  a test suite against Montage's own behaviour on top of the capabilities. It guarantees parity
  only for what Montage's SPA exposes, and only until the next Montage change. WLX's own features
  stay invisible to Montage's users.

So "cloning the API guarantees parity" is true only in one direction, and the guarantee costs the
whole parity plan plus a contract layer that has to follow an unversioned API.

### 4.1 (A) A Montage-compatible facade so Montage's SPA runs on WLX

**What it needs:** the 33 endpoints of §1.2 under a prefix (for example `/montage/v1/`), the envelope
and error shapes, `/montage/login` and `/montage/logout`, and the SPA built with
`VITE_API_ENDPOINT=/montage` and served by WLX under its own CSP (Commons API in `connect-src`,
Commons images in `img-src`).

**Blockers for an unmodified SPA:**

1. **CSRF.** WLX refuses every POST without the CSRF token (`play.filters.csrf.header.protectHeaders
   = null`, `conf/application.conf`; N6 in [the security register](../security/register.md)). Montage's SPA sends none. Either patch the
   SPA (one axios interceptor that sends `Csrf-Token`), which makes the SPA no longer unmodified, or
   exempt the facade, which weakens a security rule; the second would need the
   maintainer's decision and a register entry.
2. **Synchronous work.** The SPA expects `import/check` to return the check, `add_round` to return
   the imported round, and `activate` to have created every task. WLX never runs that work on a
   request thread (background jobs). The facade could only answer "queued" and let the counts catch
   up later. Montage issue #664 shows that users read zero counts after a create as a failure.
3. **Identity.** Montage names jurors and coordinators by Wikimedia username and creates users on
   demand. WLX invites people and signs in with Wikimedia only into existing accounts, and many
   accounts have no wiki username. Its `/login` would be WLX's sign-in, not Montage's OAuth
   (WLX's Wikimedia sign-in is built but not yet offered).
4. **Model limits.** No ranking, finalize or report in WLX, and several open rounds per contest
   (the SPA assumes one `active_round`). WLX-only rounds (criteria, 1–N≠5 scales, comments-only)
   render a blank page in the SPA (`M:frontend/src/components/Vote/Vote.vue` picks the view by
   `vote_method` only).
5. **Visibility rules.** WLX's rule that a juror of a round sees only their own votes in it must hold
   behind the facade. A Montage coordinator who is a juror would get 403 on the results download.
6. **Images.** The SPA loads images from Commons by name, so WLX's proxy, cache and deleted-file
   handling are bypassed.

**Feasible subset:** juror voting in yes/no rounds and in 1–5 star rounds without half stars, ✗ or
criteria (endpoints 1, 25–33). It also needs a CSRF patch to the SPA, the facade hiding every other
round, and jurors who have a wiki username in WLX. Skip needs a small store. Faves need a table
(WLX –) or a no-op. **Estimate: 12–20 days**, then about 1–3 days of upkeep per Montage release that
touches the juror API. **The coordinator surface:** first ranking with the report (parity #20–21),
directions and deadline, quorum change and the CSV source, then an emulation of the synchronous
import check and activation. **Estimate: 60–100 days in all**, much of it the parity plan.

**Hosting and data:** the facade runs where WLX runs (a chapter's server today), so Montage users who
use it put their campaigns' data there instead of on Toolforge. That is a privacy-policy and
governance change for affiliates worldwide, not a technical detail. The alternative, WLX on
Toolforge, needs an OAuth-only mode and WMCS's answers ([Wikimedia Cloud](2026-11-04-wikimedia-cloud.md)).

**Montage maintainers:** the BSD-3 licence allows serving their SPA without asking. But a facade
under their UI and name sends confused bug reports to their tracker, and depends on an API they
change freely (their import contract is being reworked now). Their agreement is needed in practice.

### 4.2 (B) A translation or sync layer between the two backends

Montage has no server-to-server API: sign-in is OAuth into a cookie, there are no tokens, and the
data is in the montage tool's ToolsDB, which only its maintainers can reach. A sync would need them
to add an authenticated API, or to give WLX database access. It would also make two sources of
truth for votes, with conflict rules for every concurrent change (a vote in one tool while the
round is paused in the other). **Not recommended; not estimated.** If Montage ever offers a token API,
a one-way, one-time copy (C) still covers the real need, moving a contest, without a live sync.

### 4.3 (C) A shared import/export format

**What exists already:**

- **Montage reads** a file list: a `filename` (or `img_name`) column with optional `file_id`, other
  columns ignored and reported, or one name per line, pasted or from a public https URL
  (`M:montage/loaders.py`, `M:montage/import_check.py`). So **WLX → Montage files needs nothing from
  Montage** (C1).
- **Montage writes** a results CSV, an entries CSV, reviews JSON and the report JSON. So
  **Montage → WLX files and votes needs nothing from Montage** (C2, C3). WLX's pasted-list parser
  already accepts spreadsheet rows; whether it takes Montage's entries CSV as is must be tested
  (C2).
- **Neither has a whole-contest format.** Montage's `api.md` names an "entry export" format "based on
  the MediaWiki schema" (`M:montage/docs/api.md`, sections "entry export" and "round entry export"),
  but only for files.

**Proposed archive (C4)**, JSON in a zip, versioned:

- `manifest.json`: format version, source tool and version, export time, capabilities used.
- `contest.json`: name, year, country, series (type, country), upload window if known.
- `rounds.json`: per round: number, name, `scale` (`binary` | `stars` {max, half, worstMark} |
  `ranking` | `criteria` {criteria[]} | `comments`), quorum, status, source rounds and rule (threshold,
  top-N, selected-by), filters as opaque named entries.
- `files.jsonl`: name, Commons page id, `file_id` if known, MIME, width, height, uploader, upload
  time.
- `members.jsonl`: by **Wikimedia username** where known (the only identity both tools share), role
  per contest or round; no emails, no WLX ids.
- `votes.jsonl`: round, file, juror (username), value in the round's own scale, time; optional
  review text. Disqualifications with code and reason.

Comments, statistics, CoI rules and jobs stay out (they are WLX's). A Montage importer or exporter
for it would be Montage's code: Python and BSD-3, written fresh, not copied from this GPL-3
repository. **Estimate:** C1 0.5–1 day, C2 1–2 days, C3 4–8 days, C4 8–15 days (WLX side).

### 4.4 (D) A neutral superset API with a Montage-compat profile

The GraphQL plan already builds WLX's own API for the React UI. Making it neutral costs little if
done now:

- Montage's words where the meaning is the same (`round.status`, `quorum` as an alias of
  `distribution`, `directions` when it exists).
- A `scale` type instead of WLX's `rates` id (example in §5).
- Filters as a list of typed entries, not columns.
- A `capabilities` query: what this server supports (`ranking: false`, `criteria: true`, …), so a
  generic client can hide what it cannot do.

Estimate: 2–4 days on top of the GraphQL slices (D1). A "Montage-compat profile" over it, meaning
Montage's REST shapes and envelope, is option A again, with the same blockers. So D makes A cheaper
later, but not free.

**Licence summary for all options:** Montage's BSD-3 code and Codex (GPL-2.0-or-later) may come into
this GPL-3.0-or-later repository, keeping their notices. Code from here may not go into Montage
without a relicence by every author of that code. That only matters if WLX people contribute an
importer to Montage, so such code is written for Montage under BSD-3 from the start.

## 5. Expressing WLX-only features

Four ways, which can be combined:

1. **Extension fields** in Montage-shaped objects, which Montage clients ignore. One trap: Montage's
   round `config` is free-form JSON, and its SPA prints every `config` key it gets, with the label
   `montage-round-<key>`, untranslated when unknown (`M:frontend/src/components/Round/RoundInfo.vue:92-100`).
   Its edit form posts `config` back. So extension keys there would show up raw to coordinators,
   and the facade would have to accept them back unchanged.
2. **A separate namespace** (`/montage/v1/...` for the Montage shapes, WLX's own `/api` and
   `/api/graphql` for everything else). Nothing WLX-only leaks into Montage clients; a WLX client
   never uses the Montage shapes.
3. **A capabilities document** (`GET /montage/v1/wlx/capabilities` or the GraphQL `capabilities`
   query) listing which vote methods, scales and features the server has. Montage's SPA would not
   read it; a new client would.
4. **Profiles and versions:** `X-Wlx-Profile: montage-1` or a path version, so the compat shapes can
   freeze while WLX's own API moves.

**Examples:**

- **Criteria ratings.** WLX's API: `scale: {kind: "criteria", max: 10, criteria: [{id, name}]}`.
  Montage shape: none. The round is left out of `GET /v1/juror` and `GET /v1/admin/campaign/<id>`
  `rounds` for Montage clients. A coordinator view could list it with `vote_method: "wlx-criteria"`
  and `status`, but the SPA would show an empty vote page for it, so hiding is safer.
- **Region and monument filters.** WLX's API: `filters: [{type: "region", values: ["80", "32"]},
  {type: "monuments", values: [...]}, {type: "specialNomination", value: "…"}]`. Montage shape:
  shown read-only as `config.wlx_filters` (summarised text) only if the coordinator view is offered.
  Never writable from Montage's edit form.
- **Per-contest roles, read-only organizers, series admins.** WLX's API: `me.memberships: [{contest,
  roles}]`, `series.admins`. Montage shape: `user.is_organizer` true only for root or a series admin
  allowed to create contests; a WLX Admin is a coordinator of that campaign. A WLX Organizer has no
  Montage role: they would see nothing in a Montage client, and WLX's own UI stays their tool.
- **"A juror of an open round sees only own votes".** No field to add: the rule is enforced in every
  shape. `GET /v1/admin/round/<id>/results/download` is refused for a coordinator who is a juror of
  that round, with Montage's envelope (`status: "failure"`, a 403) and WLX's denial log line and
  reference.
- **Background jobs and distribution.** WLX's API: `round.distribution: {state: "queued" | "running"
  | "done" | "failed", job}`. Montage shape: `status: "paused"` until done, and `stats` counting only
  the tasks that exist. The SPA would show zero tasks until the job ends (Montage issue #664's
  confusion).
- **Comments, statistics, image cache.** Only in WLX's own API and pages; Montage's `review` on a vote
  is not a comment and should not be mapped to one.

**What a Montage client does with a WLX contest it can't fully represent:** list the contest only if
it has at least one round the client can show. Show only those rounds, and offer no create or edit
actions on the contest. A contest made only of WLX-only rounds is hidden. Read-only is safer than
partial write, because a Montage edit form round-trips only the fields it knows.

## 6. Recommendation, staged path and open questions

**Recommendation.** Don't clone the Montage API now. In order:

1. **C1 and C2** (1.5–3 days): files out to Montage, files in from Montage. Useful at once to an
   admin who runs a contest in both tools, or who moves between them. Needs nobody's consent.
2. **Talk to the Montage maintainers** before anything else (an issue or a call), with this
   document's sections 2 and 3: whether they want a shared archive (C4), a token API, or a
   compat-layer experiment, and which backend bugs they would like help with (their open PRs first).
3. **D1** while the GraphQL slices are built (2–4 days): `scale`, typed filters, `capabilities`,
   Montage's words where they fit.
4. **C3, then C4** if moving contests between tools turns out to be wanted (12–23 days).
5. **The parity plan** as it is, with ranking and the report (parity #20–21) when a contest asks for
   them. That gives feature parity without a contract.
6. **A1 (juror-only facade)** only if both maintainers want Montage's UI on WLX data and the hosting
   question is answered.

**Effort summary (estimates):**

| Option | Effort | Upkeep | Montage maintainers | Risk |
|---|---|---|---|---|
| A, juror-only | 12–20 days | every Montage juror-API change | needed in practice | CSRF exception or SPA patch; identity mismatch; data hosting |
| A, full coordinator surface | 60–100 days (incl. parity work) | high | needed | an unversioned, moving contract; sync emulation against WLX's job rule |
| B, sync | not estimated | high | required (API or DB access) | two sources of truth |
| C1–C3 | 5.5–11 days | low (CSV columns) | no | Montage's CSV columns change (small) |
| C4 | 8–15 days | low | only for Montage's side | none beyond the format's upkeep |
| D1 | 2–4 days | none extra | no | none |

**Open questions for the maintainer** (one decision each):

1. **Which direction matters most?** (a) Users switching tools for a contest, (b) import and export
   between the tools, (c) running Montage's UI on WLX data, (d) feature parity as such. The
   recommendation assumes (a) and (b); (c) is option A.
2. **Talk to the Montage maintainers first?** Recommended before C4, A or B; C1–C2 need no
   agreement.
3. **Hosting:** if A ever goes ahead, would Montage users' data live on this tool's server, or does
   "one backend" mean a WLX installation on Toolforge (OAuth-only mode, not built)?
4. **Identity:** is the Wikimedia username the shared identity for exchange (members and votes in
   C3/C4)? It leaves out WLX accounts without a wiki account.
5. **CSRF:** if a compat layer is ever built, may it patch Montage's SPA to send `Csrf-Token`
   (recommended), or must it run the SPA unmodified (which would need a security exception)?
6. **Ranking rounds:** should parity #20–21 (ranking with Schulze and the report) move up in the
   roadmap? Montage's final rounds use them, and A or a full C4 round-trip needs them.
7. **Hide or read-only:** for any Montage-shaped view, should WLX-only rounds be hidden (recommended)
   or listed read-only?
8. **Help Montage's backend instead?** Is it worth offering reviews or patches for Montage's open
   backend PRs (#677, #678, #679, #683, #684, #630/#631), as the cheaper way to "fix Montage errors"?
