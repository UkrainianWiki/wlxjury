# Screen-size analytics: options and decisions (research, recorded for later)

**Status:** Plan only, not scheduled; recorded 2026-11-15. The maintainer decided to keep the
research and plan it for later; nothing is built now and no decision below is taken.

## Summary

Question: which screen sizes do our users have, so that the layout breakpoints match reality?
Recommendation: an **in-app MariaDB aggregate table** fed by one small beacon, because it is the
only option that records viewport, devicePixelRatio, screen and role together without a new
service, a third party, a CSP change or a second database, and it keeps data across years in the
database we already back up. Of the existing tools, Matomo On-Premise (or GoatCounter) fits if a
general analytics dashboard is wanted later. Grafana Cloud Free does not fit (third party,
14-day retention). A cheap interim step exists, an Apache log summary (section 4), but it only
has value if it is run during a campaign, because the logs rotate in about 14 days.

**The maintainer's points (2026-11-15):**

- **Multi-year retention matters.** WLM runs in autumn and WLE in spring and summer; last year's
  numbers must be available before the next campaign. A 14-day store is not enough.
- **Prefer an existing tool if one fits.** Section 3 checks each; none records viewport and DPR
  without custom code, so a tool saves only the dashboard (section 5).
- **The data should validate the current UI breakpoints** (as of #340): the staff bar goes
  compact below **1800 px**, and the comments list switches below **2100 px**. The bins must
  resolve the neighbourhood of both (see decision 3).

The research follows; the decisions are in section 6.

## 1. Constraints that decide most of it

- **Server** (docs/operations/practices.md §4.5, §6, on master): one small VPS shared with
  Apache/WordPress, Apache proxies to Play on `127.0.0.1:9000`, MariaDB on the host, Ubuntu
  24.04 move under way, `-Xmx1g` JVM, MariaDB buffer pool 128 MiB. **No containers on production
  now**; systemd services are fine.
- **CSP** (`conf/application.conf`, `play.filters.csp`): `default-src 'self'`, connect-src only
  this host and the Commons API, and the comment says *"It never gets wider."* Any browser-side
  collector on another host would widen it. So a tool's endpoint must sit **under our own origin**
  (an Apache `ProxyPass /<path>` before the catch-all, as `/excluded` already is), or the app
  must forward the data server-side.
- **Users** are signed-in volunteers. Requirement: no third party, no IPs or user ids stored, no
  fingerprinting, no new cookies, aggregate storage preferred.

**URLs matter.** Routes carry ids: `/gallery/user/:user/...`, `/byrate/round/:round/user/:user/...`,
`/large/round/:round/user/:user/pageid/:pageid`, `/comment/region/:region/pageid/:pageid`. The
`:user` segment is an account id, so a URL plus a device profile is pseudonymous personal data;
round and file ids identify the contest. Never send raw URLs. Send a **view name** instead
(`gallery`, `byrate`, `filelist`, `comments`, `large`, `admin-rounds`, ...), derived server-side
from the route (Play's `HandlerDef` on the request) or, in a tool's snippet, by overriding the
path/url (GoatCounter `path` callback, Umami `url` prop, Matomo `setCustomUrl`, Faro `beforeSend`).
Query strings (monument/author filters) are reduced to booleans (`filter=monument`), never values.

## 2. What to record

Required, per page load of a signed-in page:

| Metric | Source | Stored as |
|---|---|---|
| viewport width | `window.innerWidth` (CSS px, includes OS scale and zoom) | 40 px bins 320-2560, `<320`, `>=2560` |
| viewport height | `window.innerHeight` | 80 px bins (the large view and the sticky bars depend on it) |
| devicePixelRatio | `window.devicePixelRatio` | 1, 1.1, 1.25, 1.5, 1.75, 2, 2.25, 2.5, 3, other |
| screen width | `screen.width` (CSS px) | 80 px bins; plus "maximized" = viewport >= 95% of screen |
| role | server-side from the session (`jury` / `organizer` / `admin`; root apart or excluded) | dimension |

Worth adding (cheap, same beacon, no fingerprinting value in buckets):

- **staff bar overflow**: whether the staff bar's `scrollWidth > clientWidth` at load. This
  measures the actual problem the breakpoints try to avoid, not a proxy for it.
- **breakpoint crossings**: one count when a resize crosses 1800 or 2100 (the current breakpoints) during a page (users who
  resize to escape a layout).
- **view** (list above), **language** (UI locale), **theme** (light/dark/auto and the resolved
  scheme), **pointer** (`matchMedia('(pointer: coarse)')`: touch vs mouse), **browser/OS family**
  (server-side from the User-Agent, reduced to ~10 families; the UA itself is not kept).
- **campaign** (WLM / WLE / other, from the current contest) and **month**. Not the contest id.

Not recorded: IPs, user/contest/round/file ids, full UA, timestamps finer than a month, and the
fingerprint-grade signals (fonts, canvas, `hardwareConcurrency`, exact screen height+width+DPR
tuples per person).

Counting: **page loads** (weights by use, which is what layout decisions need) and **user-days**
(each account counted once per day per bucket combination). User-day dedup uses an in-memory set
of salted hashes, cleared daily and never written, as GoatCounter does for its sessions
([sessions](https://www.goatcounter.com/help/sessions)). A restart only overcounts slightly.

## 3. Options compared

| | GoatCounter | Umami | Matomo On-Premise | Plausible CE | Grafana Cloud Free | In-app aggregate table |
|---|---|---|---|---|---|---|
| Viewport (innerWidth) | no | no | no | no (device type from UA) | yes, Faro `browser.viewportWidth` | yes |
| Screen width | yes, `screen.width` only | yes, `WxH` | yes, `res=WxH` | via custom prop | via custom attribute | yes |
| DPR | no | no | no | no | no | yes |
| Custom properties | no (event name only) | yes, event data (50 props) | yes, custom dimensions | yes (30 props) | yes (event attributes / metric labels) | anything we choose |
| Cookies | none | none | first-party by default; `disableCookies` | none | Faro session id in browser storage | none new |
| Stores raw hits | yes (random session id) | yes (sessions + events) | yes (visit log, masked IP); purge option | yes (ClickHouse) | yes (logs) or counters | no, counts only |
| Runtime | one static Go binary | Node.js >= 20.19 + pnpm build | PHP 8.x on Apache | Elixir app, **Docker Compose only** | SaaS (+ Alloy agent or OTLP code) | the existing JVM |
| DB | SQLite or PostgreSQL | **PostgreSQL only** | **MariaDB/MySQL** | ClickHouse + PostgreSQL | Grafana's (Mimir/Loki) | **MariaDB (existing)** |
| Resources | small | a Next.js server + Postgres | 2 CPU / 2 GB for <100k pv/month (whole host) | >= 2 GB RAM, SSE 4.2 CPU | none locally | negligible |
| Retention | no limit documented; CSV export | "retained indefinitely" self-hosted | reports forever; raw purge optional | no limit documented | **14 days** | forever (rows per month) |
| Maintenance | replace binary; backup one file | Node + Postgres upgrades, rebuild | PHP app updates, cron archiving, admin UI to secure | Docker, ClickHouse | account; export job for retention | our code, covered by normal tests/backups |
| How we send role + viewport | encode in event name, or server API `/api/v0/count` | `umami.track('ui', {...})` | Tracking API from the app, `dimensionN=` | event props | (a) counters to remote-write/OTLP, (b) Faro `pushEvent` | own beacon endpoint |
| Fits constraints | yes (SQLite) | partly (new Node + Postgres) | yes | **no** (Docker) | **no** (third party, 14 d) | yes |

### 3.1 GoatCounter

Static Go binary, SQLite by default or PostgreSQL, install = download + `goatcounter serve` under
a systemd unit ([README](https://github.com/arp242/goatcounter)). No cookies; IP and UA are used
only in memory for an 8-hour session key and "never stored to the database or disk"
([sessions](https://www.goatcounter.com/help/sessions)). `count.js` sends `s: window.screen.width`
only, no height, no DPR, no innerWidth
([count.js](https://github.com/arp242/goatcounter/blob/master/public/count.js)). No custom
properties: an event is a `path` with `event: true`
([js](https://www.goatcounter.com/help/js), [events](https://www.goatcounter.com/help/events)), so
our buckets would have to be packed into event names (`vw1760-1799/dpr1.25/staff`), one dimension
combination per name; the dashboard then shows a flat list, and analysis means the CSV export
([API: export](https://www.goatcounter.com/help/api)). The server API needs a key and is limited
to 4 requests/s ([API](https://www.goatcounter.com/help/api)). Verdict: the lightest real tool,
good for "which views are used", poor for the multi-dimensional breakpoint question.

### 3.2 Umami

PostgreSQL only (>= 12.14), Node.js >= 20.19 (22 LTS recommended), built from source with pnpm,
PM2 suggested as the process manager ([install](https://docs.umami.is/docs/install)). No cookies;
self-hosted data "retained indefinitely unless you manually delete it"
([FAQ](https://docs.umami.is/docs/faq)). Sends `screen` as `WxH`; custom events carry data
(`umami.track('ui', {vw: '1760-1799', dpr: '1.25', role: 'staff'})`, up to 50 properties, strings
<= 500 chars) and `identify` session data ([tracker functions](https://docs.umami.is/docs/tracker-functions)).
Good data model, but two new runtimes (Node, Postgres) on a small shared host, and per-session raw
rows with country/browser/screen.

### 3.3 Matomo On-Premise

PHP on the existing Apache with MariaDB: Matomo 5 (current 5.14.2) on PHP 8 with MySQL/MariaDB;
Matomo 6 needs PHP 8.1+ and MariaDB 10.6+ (we have 10.6, 10.11 next); sizing "2 CPUs, 2 GB RAM"
for up to 100k pageviews/month ([requirements](https://matomo.org/faq/on-premise/matomo-requirements/),
[install](https://matomo.org/faq/on-premise/installing-matomo/)). Records `res=WxH`, no viewport,
no DPR; custom dimensions (`dimension1..999`, Custom Dimensions plugin) take our buckets; the HTTP
Tracking API (GET/POST, bulk JSON) lets the **app** send hits so the browser never talks to Matomo
and the CSP stays as is ([Tracking API](https://developer.matomo.org/api-reference/tracking-api)).
Privacy: IP masked 2 bytes by default
([privacy settings](https://matomo.org/faq/general/configure-privacy-settings-in-matomo/));
cookies on by default, `disableCookies` turns them off
([cookies](https://matomo.org/faq/how-to/faq_157/)); raw logs can be purged after N days while
aggregated reports are kept indefinitely ([data retention](https://matomo.org/faq/general/configure-data-retention/)).
Cost: a PHP application with its own admin UI and update cadence, a cron archiver, and a second
database in the backup set. The only existing tool that fits "MariaDB, no Docker, multi-year
reports" out of the box.

### 3.4 Plausible Community Edition

Docker and Docker Compose required, ClickHouse needs an SSE 4.2/NEON CPU, >= 2 GB RAM recommended
([community-edition](https://github.com/plausible/community-edition),
[self-hosting](https://plausible.io/docs/self-hosting)). Device category comes from the UA, exact
resolution only through a custom property ([devices](https://plausible.io/docs/devices),
[custom props](https://plausible.io/docs/custom-props/introduction)). Excluded by the
no-containers rule and the memory budget.

### 3.5 Grafana Cloud Free (the maintainer's suggestion)

Free tier: 10k active metric series, 50 GB logs, 50 GB traces, 50k Frontend Observability
sessions per month, 3 users, and **14-day retention** for metrics, logs and traces; 13 months of
metrics needs the paid Pro tier ([pricing](https://grafana.com/pricing/)). EU regions exist
(Germany, Ireland, Sweden on AWS directly); a stack's region cannot be changed later
([regions](https://grafana.com/docs/grafana-cloud/security-and-account-management/regional-availability/)).

**(a) App pushes aggregate counters.** The beacon endpoint increments a counter labelled
`role, vw_bin, dpr_bin, sw_bin` (and optionally `view`). Either the app exposes `/metrics` on
loopback and Grafana Alloy (a binary with a systemd unit) remote-writes it, or the app adds the
OpenTelemetry Java SDK and pushes OTLP. Only counts leave the server: no user IPs, no UA, no URLs
(Grafana sees our server's IP). Cardinality: the full product is ~2 x 57 x 10 x 30 = 34k, above
the 10k limit in theory; real combinations are a few hundred, but split it into two or three
counters (role x vw x dpr, role x sw x maximized, role x view) to stay safe.

**(b) Faro web SDK.** Defaults (`getWebInstrumentations()`): errors, console (warn/info/error),
web vitals, session, view and navigation instrumentation, with browser, OS, page and view metas
([web-sdk](https://github.com/grafana/faro-web-sdk/blob/main/packages/web-sdk/README.md)). The
browser meta carries `userAgent`, name, version, OS, language, brands and
`viewportWidth`/`viewportHeight` from `window.innerWidth/innerHeight`
([browser meta](https://github.com/grafana/faro-web-sdk/blob/main/packages/web-sdk/src/metas/browser/meta.ts));
the page meta carries the full URL (our ids); the session instrumentation keeps a session id in
browser storage. To restrict it: an empty `instrumentations` list (or only what we need), session
tracking off, metas reduced, and a `beforeSend` that drops every item except our
`pushEvent('ui', {role, vw, dpr, sw})` and deletes `meta.page.url` and `meta.browser.userAgent`
([exclude endpoints / ignoreUrls](https://grafana.com/docs/grafana-cloud/observe-and-act/monitor-applications/frontend-observability/configure/exclude-endpoints/)).
The collector still receives each user's IP and UA headers at the network level unless we proxy
`/collect` through Apache (which also keeps the CSP at `'self'`); the bundle must be self-hosted
(script-src `'self'`). Rate-limited data is dropped
([architecture](https://grafana.com/docs/grafana-cloud/monitor-applications/frontend-observability/architecture/)).

**Assessment.** Data residency: a US company processing data in a chosen region; with (a) only
aggregate counts leave, with (b) per-page-load events leave unless stripped and proxied. Either
way it is a third-party service, which the requirements exclude. Retention: 14 days, so
year-over-year needs an **export job** (Prometheus/Loki query API, run more often than every 14
days, written to a local table or CSV). That local copy then becomes the source of truth, which is
the in-app table plus a remote viewer. Effort: (a) about the in-app option plus Alloy/OTLP and the
export job; (b) more, plus the redaction review. Grafana's value is the dashboards, and those are
available without the cloud: Grafana OSS (Debian package, systemd) can chart the in-app MariaDB
table through its MySQL data source, bound to loopback and opened over SSH
([MySQL data source](https://grafana.com/docs/grafana/latest/datasources/mysql/)).

### 3.6 Minimal in-app aggregate table

- One Flyway migration: `ui_metric(month, campaign, role, view, vw_bin, vh_bin, dpr_bin, sw_bin,
  maximized, bar_overflow, lang, theme, pointer, browser_family, os_family, page_loads, user_days)`,
  unique key on the dimensions, `INSERT ... ON DUPLICATE KEY UPDATE page_loads = page_loads + 1`.
  Optionally split into two or three narrower tables so rare combinations stay invisible.
- One POST endpoint (signed-in only, `withAuthJson`, CSRF header from `data-csrf-token` as the
  gallery votes do, small body limit, values validated against the bin lists, a per-session rate
  cap). Role, view, campaign and browser/OS family are computed **server-side**; the browser sends
  only the numbers. A ~30-line self-hosted script sends it once per page load via
  `fetch(..., {keepalive: true})`. No CSP change, no new cookie (the existing session is used).
- Readout: a root-only page with the table and a CSV download, or SQL; Grafana OSS optional.
- Retention: forever by default (a few thousand rows a year), inside the existing database backups
  and restore drills; no personal data, so dumps don't change classification.
- Effort: about one to two days with tests and the review cycle; no new service to patch.

## 4. What Apache logs can tell

The logs hold IP, time, URL and User-Agent, so they give the **browser/OS family and
mobile/desktop mix**, and a rough juror/staff split (a client that requests `/admin/*` that day is
staff). They cannot give viewport, DPR or screen. Caveats: Chromium's UA reduction freezes the OS
version (Windows 11 reports `Windows NT 10.0`, macOS `10_15_7`) and Android models
([UA reduction](https://www.chromium.org/updates/ua-reduction/)); iPads report as Macs. The
Debian/Ubuntu `logrotate` default for Apache keeps about 14 days (check `/etc/logrotate.d/apache2`
and the vhosts' `CustomLog` lines), so only the current campaign's last two weeks are ever available. The summary below therefore has
value only if it is run while a campaign is under way.

Privacy-safe method, on the server only, output aggregates only:

1. Read `access.log*` (an account that can read the Apache logs); keep only signed-in page GETs with
   status 200 (`/gallery`, `/byrate`, `/filelist`, `/large`, `/comment`, `/admin/...`); skip
   `/assets`, `/wikipedia/commons/thumb`, `/health`, bots.
2. Map the UA to (browser family, OS family, mobile/tablet/desktop) with a few regexes; drop the UA.
3. Count user-days with an in-memory set of `sha256(salt, ip, ua, date)`, the salt random per run
   and never saved; mark a key as staff if it hit `/admin/` that day.
4. Write `month, role_guess, browser, os, device, page_loads, user_days` to a CSV; nothing else
   leaves memory. The CSV can be kept; a periodic run of the same script during campaigns would keep
   year-over-year data until the in-app table exists.

Alternative existing tool: GoAccess (Debian package) with its BROWSERS, OS and VISITORS panels
(a visitor = same IP, date and UA), run with `--anonymize-ip` and the HOSTS, GEO_LOCATION and
REQUESTS panels ignored (REQUESTS lists URLs with ids), no on-disk persistence
([man page](https://goaccess.io/man)). Fine for a one-off look; the script gives the staff split
and a CSV.

## 5. Recommendation

1. **Interim, if picked up during a campaign:** run the log summary (section 4) on the then-current
   logs for the browser/OS/device mix.
2. **When this is picked up, build the in-app aggregate table (3.6).** It is the only option that records viewport, DPR,
   screen and role together without a new service, a third party, a CSP change or a second
   database, and it keeps multi-year data in the MariaDB we already back up. Every existing tool
   would need the same custom properties for viewport and DPR anyway, so a tool saves only the
   dashboard, at the price of a new runtime, raw-hit storage or a third party.
3. **If a general analytics dashboard is wanted later**, Matomo On-Premise (MariaDB, PHP on the
   existing Apache, fed server-side by the app, raw logs purged after 90 days, reports kept) is the
   existing tool that fits; GoatCounter is the lighter choice if a SQLite file is acceptable and
   view counts are all we need from it. Not Umami (Node + Postgres), Plausible CE (Docker,
   ClickHouse) or Grafana Cloud (third party, 14-day retention, export job needed).
4. Charts: start with the root-only page + CSV; add Grafana OSS on loopback only if the CSV is
   not enough.

## 6. Decisions for the maintainer

1. **Where the data lives.**
   (a) In-app aggregate table in MariaDB *(recommended)*: records everything we need, no new
   service or third party, multi-year in our backups; costs about one to two days of our code.
   (b) Matomo On-Premise fed by the app: a ready dashboard on MariaDB and Apache; a PHP app to
   patch, a cron archiver, no viewport or DPR without custom dimensions.
   (c) GoatCounter (SQLite): lightest tool; no custom properties, so a flat list and CSV export.
   (d) Grafana Cloud Free with an export job: third party and 14-day retention; we would keep our
   own copy anyway.
   (e) Umami: good data model; needs Node and PostgreSQL on a small shared host.
2. **Whether to run an Apache log summary when a campaign is under way.** The summary is a
   server-side script that reads the Apache access logs, maps user agents to device, OS and
   browser families, and writes only an aggregate CSV (no IPs, URLs or user agents kept).
   Options: (a) yes, that script *(recommended, if run during a campaign)*; (b) GoAccess with IP
   anonymization and the HOSTS/REQUESTS panels off (a one-off look, no staff split); (c) skip.
3. **Bin widths.** (a) Viewport 40 px, screen 80 px, DPR in the bins 1, 1.1, 1.25, 1.5, 1.75, 2,
   2.25, 2.5, 3 and other *(recommended)*;
   (b) 20 px around 1700-2200 only (both current breakpoints); (c) coarse classes only (phone/tablet/laptop/desktop).
4. **Time granularity.** (a) Month plus campaign (WLM/WLE/other) *(recommended)*; (b) ISO week;
   (c) day.
5. **Role dimension.** (a) jury / organizer / admin, root excluded *(recommended: the maintainer's
   own testing would skew staff numbers)*; (b) juror vs staff only; (c) root included as its own
   value.
6. **Extra metrics.** (a) Staff bar overflow, breakpoint crossings, view, language, theme,
   pointer, browser/OS family, viewport height *(recommended)*; (b) only the required five (viewport width, viewport height, devicePixelRatio, screen width,
   role);
   (c) also web vitals (LCP) for the gallery.
7. **Counting unit.** (a) Both page loads and user-days, dedup in memory only *(recommended)*;
   (b) page loads only.
8. **One table or several.** (a) Two or three narrow tables (layout: role x viewport x DPR x
   screen; usage: role x view x language x theme; client: role x browser x OS) *(recommended)*;
   (b) one wide table.
9. **Retention.** (a) Keep forever (no personal data, tiny) *(recommended)*; (b) keep 5 years.
10. **Readout.** (a) Root-only page with CSV download *(recommended)*; (b) plus Grafana OSS on
    loopback via SSH tunnel; (c) SQL only.
11. **Privacy notice.** (a) One sentence in the sign-in page/privacy text: anonymous aggregate
    counts of screen size and features, no cookies, no ids *(recommended)*; (b) none.

## 7. Unverified claims

Unverified, per the research report (2026-11-15) and this write-up's own notes on what was not
checked. The research read vendor documentation and our repository; none of it was run. Check
before relying on it:

- Vendor facts (requirements, limits, retention, cookie and IP behaviour) come from the cited
  pages as of 2026-11-15 and were not tested: GoatCounter sessions and `count.js`, Umami
  install/FAQ, Matomo requirements and version numbers (5.14.2, Matomo 6 needing PHP 8.1+ and
  MariaDB 10.6+), Plausible CE sizing, Grafana Cloud free-tier limits, regions and Faro metas.
- The Apache log rotation of about 14 days is the Debian/Ubuntu default; the real value must be
  read from `/etc/logrotate.d/apache2` and the vhosts' `CustomLog` lines on the server.
- The statements about our own setup (docs/operations/practices.md §4.5 and §6, the
  `play.filters.csp` comment, the Apache `/excluded` proxy, the shared VPS sizing) were read from
  the repository, not re-checked on production.
- Effort ("one to two days") and the cardinality estimate for Grafana (34k in theory, a few
  hundred in practice) are estimates, not measurements.
- The claim that a signed-in beacon needs no CSP change assumes a same-origin `fetch`; confirm
  against `connect-src` when building.

Reported as unverified by the research itself:

- Faro keeping its session id in browser storage.
- The Debian/Ubuntu Apache logrotate default of about 14 days (check `/etc/logrotate.d/apache2`
  on the server).
- The GoAccess flag names (`--anonymize-ip` and the panel flags): the man page confirmed the
  panels and the definition of a visitor, not the flags.
- The Chromium UA-reduction page was cited without being fetched.
