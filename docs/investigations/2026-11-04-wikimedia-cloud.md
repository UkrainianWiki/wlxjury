# Hosting wlxjury on Wikimedia Cloud Services

Investigation results, 2026-11-04. Not planned: the user decided to record the findings only
(2026-11-04). Nothing was requested from WMCS. Platform facts come from Wikitech, MediaWiki.org
and Phabricator, read on 2026-11-04, each with the page's last-edit date; **unconfirmed** marks a
claim the docs don't state. The companion record on containers is
[2026-11-04-simpler-deployment.md](2026-11-04-simpler-deployment.md).

**Summary.** In today's full mode (password accounts, invited emails, Google sign-in, mail),
Toolforge is blocked by policy: its Terms of Use let a tool hold no personal data beyond what OAuth
provides. A Cloud VPS project would fit, at the cost of our own backups, US hosting, hidden client
IPs, mail and domain requests, and WMCS's maintenance windows. With an **OAuth-only mode**
(Wikimedia sign-in only, jurors invited by wiki username, no mail, no names typed in), most of the
policy blocker goes away and Toolforge becomes the better Wikimedia option, as Montage shows, but
only for an installation that can be OAuth-only, after a pilot, and after WMCS answers three
questions: whether votes, comments and conflict-of-interest usernames are allowed data, ToolsDB's
binlog format (READ COMMITTED depends on it), and whether the 100-requests-per-minute per-IP limit
can be raised. Two configuration switches, `wlxjury.thumbs.mode` and `wlxjury.auth.mode`, are
useful on any host and are sketched in §6 and §7.

## 1. The platforms

| Platform | What it is | Status (2026-11) | Source |
|---|---|---|---|
| **Toolforge** | PaaS: each tool is a Kubernetes namespace; web services and jobs run as containers; a home directory on NFS | Active; Kubernetes is the only backend | [Help:Toolforge](https://wikitech.wikimedia.org/wiki/Help:Toolforge) (2026-09-24) |
| **Build Service** | Builds an image from a **public** git repo with Cloud Native Buildpacks (`heroku-24` builder); needs a `Procfile` | Active, recommended | [Building container images](https://wikitech.wikimedia.org/wiki/Help:Toolforge/Building_container_images), [Build Service](https://wikitech.wikimedia.org/wiki/Help:Toolforge/Build_Service) (2026-09-30) |
| **Webservices** | `<tool>.toolforge.org` behind a TLS proxy, port 8000 (`PORT`) | Active | [Help:Toolforge/Web](https://wikitech.wikimedia.org/wiki/Help:Toolforge/Web) (2026-11-02) |
| **Jobs** | One-off, scheduled and continuous jobs, health checks, `jobs.yaml` | Active | [Running jobs](https://wikitech.wikimedia.org/wiki/Help:Toolforge/Running_jobs) (2026-10-20) |
| **Deploy on push** | `toolforge.yaml`, deployed from CI with a token | **Beta**, "we don't recommend using it for production services"; no rollback, no webservice components | [Deploy your tool](https://wikitech.wikimedia.org/wiki/Help:Toolforge/Deploy_your_tool) (2026-09-23) |
| **ToolsDB** | A multi-tenant MariaDB primary and replica | **10.6.19** since 2024-11 ([cloud list](https://go.mail-archive.com/cloud@lists.wikimedia.org/msg02308.html), [T352206](https://phabricator.wikimedia.org/T352206)); 10.6.20 seen 2025-04 ([T392596](https://phabricator.wikimedia.org/T392596)); current version **unconfirmed** | [Help:Toolforge/ToolsDB](https://wikitech.wikimedia.org/wiki/Help:Toolforge/ToolsDB) (2026-06-08) |
| **Cloud VPS** | OpenStack: a project gets its own VMs, root via sudo | Active; default quota 8 instances; requested on Phabricator | [Cloud VPS project](https://wikitech.wikimedia.org/wiki/Help:Cloud_VPS_project) (2026-07-23), [Instances](https://wikitech.wikimedia.org/wiki/Help:Cloud_VPS_Instances) (2026-10-25) |
| **Trove** | MariaDB as a service in a Cloud VPS project | Active; small instances, about 10 GB by default; **no built-in backups** | [Trove guide](https://wikitech.wikimedia.org/wiki/Help:Trove_database_user_guide) (2026-02-07) |
| **Object storage** | Ceph RGW (S3, Swift) | Cloud VPS only; default 4,096 objects and 8 GB; **not backed up** | [Object storage](https://wikitech.wikimedia.org/wiki/Help:Object_storage_user_guide) (2026-04-14) |

Both run in **eqiad** (Ashburn, Virginia); Wikimedia's European caching data centres are esams (Amsterdam)
and drmrs (Marseille) ([Data centers](https://wikitech.wikimedia.org/wiki/Data_centers), 2026-08-19).

## 2. What wlxjury needs, and how each platform fits

wlxjury is one Play 3 JVM (Java 17, `-Xmx1g`, one instance only: job leases and the cache's
start-up walk, OP5), with MariaDB 10.6+ (10.8+ for V85's descending index), a Hikari pool of
`minimumIdle=10`/`maximumPoolSize=30` at READ COMMITTED (readiness refuses otherwise), an image
cache at `wlxjury.thumbs.local-path`, outbound calls to the Commons API, `upload.wikimedia.org`,
Meta and Google OAuth and SMTP, and a loopback-only `/health/ready`. Its largest contest so far had
103 jurors; a gallery page loads 15 thumbnails. scalawiki `0.7.0-SNAPSHOT` resolves only from
`mavenLocal` today. Licence Apache-2.0.

| Need | Toolforge | Cloud VPS |
|---|---|---|
| JVM web service | Needs work: `heroku/sbt` + `heroku/jvm`, `system.properties` `java.runtime.version=17` ([heroku/buildpacks-jvm](https://github.com/heroku/buildpacks-jvm)). Whether `heroku/sbt` is in Toolforge's builder is **unconfirmed**; no custom Dockerfiles | Fits |
| Building | Needs a public scalawiki release | Fits |
| Memory and CPU | Fits: default 0.5 CPU / 512Mi, raisable to 3 CPU / 6Gi per pod and 8 CPU / 8Gi per tool ([Web](https://wikitech.wikimedia.org/wiki/Help:Toolforge/Web)) | Fits: flavours 2 vCPU/4 GB to 4 vCPU/8 GB |
| One instance | Pods can be rescheduled; whether restarts overlap two pods is **unconfirmed** | Fits |
| MariaDB version | 10.6: V85 stays ascending, with a startup warning | Our own: e.g. Debian 12's 10.11 |
| READ COMMITTED | **Unconfirmed**: InnoDB refuses RC writes under `binlog_format=STATEMENT`; ToolsDB's format isn't documented | Fits |
| Connection pool | **Blocked as configured**: "Usage of connection pools … is not permitted", idle connections "will be killed" ([Wiki Replicas](https://wikitech.wikimedia.org/wiki/Wiki_Replicas), 2026-10-27); last published per-user cap 20 (**unconfirmed** now). Workable with `minimumIdle=0`, a short `idleTimeout`, a small maximum | Fits |
| Flyway DDL | Fits: "all privileges … on their databases" | Fits |
| DB backups | "We don't do offline backups": our own dump job | "No backups will be kept by Wikimedia Cloud Services": our own dumps, off-site |
| Image cache | NFS only, unsuitable for "IO intense operations" ([Shared storage](https://wikitech.wikimedia.org/wiki/Help:Shared_storage), 2026-08-26): drop the cache (§6) | A Cinder volume; dropping the cache is still advised |
| Commons API and media | Better than elsewhere: WMCS server-side requests with a compliant User-Agent are **exempt** from the API rate limits ([Rate limits](https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits), 2026-10-15; [Robot policy](https://wikitech.wikimedia.org/wiki/Robot_policy), 2026-03-16) | Same |
| Google OAuth | Unclear: ToU §5 allows third-party redirects in "limited circumstances only"; the [Cross Site Policy](https://wikitech.wikimedia.org/wiki/Wikitech:Cloud_Services_Cross_Site_Policy) is a 2023 draft | Unclear |
| Outbound mail | Via `mail.tools.wmcloud.org`, From `<tool>.*@toolforge.org`, rate limits, bulk mail needs approval ([Email](https://wikitech.wikimedia.org/wiki/Help:Toolforge/Email), 2026-02-10) | A relay for allowlisted domains; our own From domain added by admins ([Email in Cloud VPS](https://wikitech.wikimedia.org/wiki/Help:Email_in_Cloud_VPS), 2026-02-07) |
| Domain | `<tool>.toolforge.org` only | `*.wmcloud.org`; a vanity domain (`jury.wlm.org.ua`) needs a justified Phabricator request ([web proxy](https://wikitech.wikimedia.org/wiki/Help:Using_a_web_proxy_to_reach_Cloud_VPS_servers_from_the_internet), 2026-03-18) |
| Inbound rate limit | **"Each source IP is limited to 100 requests per minute across all of toolforge"** | Not documented (**ask**) |
| Client IP | Hidden by the anonymizing proxy, and must not be collected | Hidden by the proxy: IP-based throttling doesn't work |
| Health check | kubelet probes come from outside loopback: `/health/ready` is a 404 there | Fits |
| Personal data | **Blocked** in full mode (§3) | Fits with a privacy statement (ToU §7.3) |
| Licence and purpose | Fit: OSI licence, Wikimedia programmes ([Rules](https://wikitech.wikimedia.org/wiki/Help:Toolforge/Rules)) | Fit |

## 3. Blockers in full mode

1. **Toolforge's personal-data rule.** ToU §7.2
   ([Terms of use](https://wikitech.wikimedia.org/wiki/Wikitech:Cloud_Services_Terms_of_use),
   effective 2023-05-26, edited 2024-02-02): a Toolforge project must "Use Toolforge's anonymizing
   reverse proxy for all End User requests" and "Not collect any other Personal Information and
   Wikimedia Usernames from End Users", apart from the proxy's user agent and "OAuth provided
   usernames and email addresses". Personal Information (§2) covers emails, IPs and passwords.
   Full mode holds invited emails and real names typed in by organisers, password hashes and
   invitation tokens. Many WLM jurors are photographers without wiki accounts.
2. **The per-IP limit.** A gallery page plus 15 thumbnails through the app, and a keyboard vote
   (a POST and a redirected GET) every few seconds, bring one fast juror near 100 a minute; a jury
   behind one NAT shares the budget. Burst size, 429 behaviour and exemptions aren't documented.
3. **ToolsDB.** No pools, no backups, 10.6, an undocumented binlog format. Trove for a Toolforge
   tool needs a separate Trove-only Cloud VPS project.

Cloud VPS has no blocker. Its costs: no WMCS backups; US hosting (a "processed in the United
States" notice, more latency for European jurors); hidden client IPs; a request per vanity domain;
mail domain allowlisting; WMCS maintenance windows (at least 2 weeks' notice, faster in
emergencies), which may not avoid a contest's peak; and the annual purge, which must be answered
or VMs are shut off (enforced 2026-02-04 for the 2025 purge;
[2024 purge](https://wikitech.wikimedia.org/wiki/News/2024_Cloud_VPS_Purge),
[News](https://wikitech.wikimedia.org/wiki/News)). Its gains: free, exempt from API limits, a VM of
its own, and visible as a movement tool.

## 4. An OAuth-only mode under ToU §7.2

Definitions (§2): Personal Information is "Information that could be used to personally identify an
individual or that relates to an identifiable individual"; an End User is "An individual who
accesses or transmits data to a WMCS Project". The
[End User Terms](https://wikitech.wikimedia.org/wiki/Wikitech:Cloud_Services_End_User_Terms_of_use)
(effective 2023-05-26) say "all Toolforge projects will still adhere to the WMF Privacy Policy and
the WMF Data Retention Guidelines" and "WMCS is hosted and operated in the United States". No FAQ on
tool-generated data such as votes was found.

| Data | Verdict | What the mode does |
|---|---|---|
| Username, Wikimedia `sub` (`user_identity`, V87) | Allowed | "OAuth provided usernames" |
| Email | Allowed from OAuth, but not needed | No mail, so request identity only and store none; linking by wiki account ([oauth.md](../oauth.md)) |
| Real name | Not listed | Drop it, and `users.fullname`; show the username |
| A juror's username entered by an organiser before sign-in | Grey | Montage does exactly this. Ask |
| Conflict-of-interest accounts (`User.COI_ROLES`, `CoiMembers`) who may never sign in | Grey, the weakest case | Public wiki accounts used only to flag their own files. Ask; fallback: COI only for members who signed in |
| Votes, ratings, comments, round membership, audit log by username | Grey, common practice | Every OAuth tool stores what its users do; Montage keeps these on ToolsDB |
| File authors, regions, monuments | Allowed | Public Commons data, not collected "from End Users" |
| Session cookie | Allowed | No new personal data |
| IP addresses | Not available | Don't log any |
| Logs with email (`Secured.denialLogLine`) | Must change | Log `accountId` only |
| Password hashes, `account_token`, Google identities | Absent | Scrubbed before any data reaches Toolforge (§7) |

The mode removes the clear breaches. What remains is the grey area Montage already lives in, plus
the conflict-of-interest list, which goes beyond Montage.

## 5. Montage as precedent

[Montage](https://github.com/hatnote/montage) (`master` at `d879cb8`, 2026-10-31) runs on Toolforge
as an OAuth-only jury tool.

| Topic | Montage | wlxjury could |
|---|---|---|
| Per-IP limit | No 429 handling. Light by design: an SPA, images straight from Commons (`Special:Redirect/file/...&width=`), about one JSON call per vote, tasks 10 at a time | Load thumbnails from Commons (§6); a vote is still about 2 requests in the large view, 1 with a `fetch` vote |
| Pool rule | SQLAlchemy's default pool (5 + 10 overflow) in each of 4 gunicorn workers, `pool_recycle=60`: it pools despite the rule, apparently tolerated | Hikari `minimumIdle=0`, `idleTimeout` about 30 s, `maximumPoolSize` about 8, fewer job workers. Don't rely on tolerance |
| Backups | None in the app or its docs | A scheduled dump job to the tool's NFS (`/data/project`, mirrored and snapshotted) with retention; needs an image with a MariaDB client (**unconfirmed** which has one) |
| Webservice | `buildservice` with `--mount all`, gunicorn on 8000, three tools (montage, -beta, -dev), health at `/v1/health` | `buildservice`, `--mem 2Gi --cpu 1`, `--mount none`, one replica, a beta tool for rehearsals |
| Privacy | No statement; OAuth scope `basic` | The WMF Privacy Policy covers it; still add a short "what this tool stores" page |

Montage shows that WMCS tolerates this practice, not that WMCS has approved it.

**Residual costs on Toolforge in the mode:** about 1 request per vote is the floor, so 5 jurors in
one room voting every 2 s make about 150 a minute (ask for an exemption; fallback: own
connections); restores are manual from the bastion; V85 stays ascending on 10.6 and the juror
gallery uses a filesort; ToolsDB latency under other tools' load is unknown; an unauthenticated
`/health/live` is needed for `--health-check-path`; `play.http.forwarded.trustedProxies` and
`AllowedHostsFilter` need the ingress range and the tool's host; a custom domain can only redirect
to `*.toolforge.org`; the app runs in Ashburn while images come from the European edge. **If
ToolsDB uses `STATEMENT` binlogs, READ COMMITTED writes fail** and only Trove remains.

## 6. Design sketch: `wlxjury.thumbs.mode = local | commons`

On a Wikimedia platform the cache is slower than Commons, not faster: a juror's browser gets
standard-size thumbnails from the nearest edge (esams or drmrs), while an app-served cache comes
from Ashburn through the WMCS proxy with no CDN, and on Toolforge also from NFS and against the
per-IP limit.

- Browsers hotlinking `upload.wikimedia.org` must use the standard widths (20, 40, 60, 120, 250,
  330, 500, 960, 1280, 1920, 3840); others get a 429 "thumbnail steps" message
  ([Common thumbnail sizes](https://www.mediawiki.org/wiki/Common_thumbnail_sizes), 2026-10-20;
  [T414805](https://phabricator.wikimedia.org/T414805), [T360589](https://phabricator.wikimedia.org/T360589)).
  `Global.wikimediaSteps` already encodes them. Media 429s for non-browser apps are open
  ([T413570](https://phabricator.wikimedia.org/T413570), 2026-11-02).
- The robot policy asks for concurrency of at most 2, at most 25 Mbps, standard sizes or originals;
  `parallelism=2` and `rate-per-second=10` comply. No policy forbids or asks for caching. Whether
  an image proxy counts under ToU §4.5 ("Do not … proxy or relay traffic for other servers") is
  **unconfirmed**.

**The switch.** None exists: `Global.useLegacyThumbUrl` is a hard-coded `val`, gallery URLs are
relative, and `wlxjury.thumbs.local-path` is mandatory. In `commons` mode:
- `thumbUrl`/`srcSet` emit `https://upload.wikimedia.org/...` at `wikimediaStep` widths (a 330-px
  tile where about 300 px is needed);
- the cache service doesn't walk or prefetch, and `local-path` becomes optional;
- "Cache images" and the export's `thumb_urls` hide or point at Commons;
- a gallery tile's `onerror` falls back to the app's `/wikipedia/commons/thumb/...` route, as the
  large view does (`Global.largeImage`); whether tiles have one is unchecked (see
  [deleted and renamed files](../plans/2026-10-22-deleted-renamed-files.md)). A small cache of
  deleted or renamed files' thumbnails (`Global.largestCachedThumbnail`) can stay;
- the CSP `img-src 'self' data: https://*` already allows Commons; a spec per mode.

It is useful on any host, independently of hosting.

## 7. Design sketch: `wlxjury.auth.mode = wikimedia | full`

`full` (today) is the default. `wikimedia` refuses to start if Google, the mailer or any password
feature is configured, and readiness reports it.

| Turned off in `wikimedia` | Files |
|---|---|
| Password sign-in, `/auth` form | `LoginController.scala`, `views/login*`, `services/PasswordHasher.scala` |
| Invitations by mail, password links, email change, re-invite, root password reset | `LoginController` (`/invitation`), `UserController` (`resetPassword`, `reinvite`, `cancelInvitation`), `services/AccountTokenService.scala`, their routes |
| Google | `services/oauth/GoogleProvider.scala`, `oauth.google` |
| Mail | `services/SendMail.scala`, `play.mailer`, `wlxjury.mail.from`, the greeting in `UserService` |
| The chooser's proof by mail | `OAuthChooserController` (`/oauth/choose/confirm`) |
| Email and name in forms | `views/users.scala.html`; `User` without `email` and `fullname` |
| Wikimedia email and real-name grant | `WikimediaProvider.scala`: identity only |
| Logging email | `Secured.denialLogLine`: `accountId` only |

- **First root:** `/setup` keeps its token but starts a Wikimedia sign-in and binds root to that
  `sub` (`LoginController.setup`, `IdentityLinker`).
- **Invitations by username:** "Add juror" takes a wiki username and creates or adds a wiki-only
  placeholder (no password, no email), linked at the first Wikimedia sign-in by the normalised
  username ([oauth.md](../oauth.md), "How an account is found"; `CoiMembers.normalize`). No
  invitation link: the organiser shares the URL. The safety rules stay: a placeholder links only if
  no Wikimedia account was ever linked; renames clear verification; tombstones stay (V87
  `unlinked_at`). The `account_token` purposes go unused.
- **Moving existing accounts into the mode,** off Toolforge so no personal data reaches it: a
  report of accounts without, or with an unverified, wiki account and of duplicates; organisers set
  missing wiki accounts; duplicates joined through the
  [account join](../plans/2026-11-03-account-join.md) admin path, decided by root or the contest
  admins (its mail proof isn't available), **so the join must exist first**; a scrubbing step nulls
  `users.email`, `users.fullname` and `user_identity.email` and deletes password hashes,
  `account_token` rows and Google identities, keeping wiki accounts, `sub`s, votes, comments and
  the audit log. Accounts left without a wiki account stay as inactive history. A later move back
  to `full` means re-entering emails.

## 8. Comparison and recommendation

| | Toolforge, OAuth-only | Cloud VPS | Own host |
|---|---|---|---|
| Modes | `wikimedia` only | both | both |
| Operations work | Least: no OS, TLS, proxy or MariaDB | A VM, its OS, our own backups | As documented in deployment.md |
| Policy | Grey items (§4); Montage precedent | Privacy statement under §7.3 | Ours |
| Hard unknowns | binlog format; per-IP limit; COI usernames | vanity domains; mail From | none |
| Domain | `*.toolforge.org` | `*.wmcloud.org`, or vanity by request | any |
| Latency for European jurors | US app, EU images | US app | depends on the host |
| Cost | free | free | the host |

1. Build the two switches on their merits; also log by `accountId`.
2. If an installation can be OAuth-only, Toolforge beats Cloud VPS. Cloud VPS is only worth it for
   full mode on Wikimedia hosting, which costs more than it saves against an own host.
3. WLM-UA stays on its own host in `full` mode: its juries include people without wiki accounts,
   and the `*.org.ua` names stay.
4. A pilot, after WMCS answers Q1, Q4, Q6, Q9 and Q10: a `wlxjury-test` tool in `wikimedia` mode for
   one small contest or a copy, measuring ToolsDB latency, the per-IP limit with a jury in one room,
   and a release with dump-before-migrate.

## 9. Questions for WMCS (not asked)

1. Can a Cloud VPS project hold jurors' emails, names and password hashes under §7.3, given its
   advice "you should ask users to login via OAuth", with a privacy statement and Wikimedia OAuth
   offered first?
2. Is "Sign in with Google" acceptable under ToU §5 and the draft Cross Site Policy? Do §7.2's
   "OAuth provided … email addresses" include non-Wikimedia OAuth?
3. Would a vanity domain be granted for `jury.wlm.org.ua` and other contest hostnames, and is a
   per-host certificate fine for several?
4. Does the Cloud VPS web proxy have a per-IP limit, timeouts or a body-size limit? Can Toolforge's
   100-per-minute limit be raised for one tool, and what is its burst?
5. Can mail be sent From our own domain through the relay, or by authenticated submission to an
   external provider on 587? What are the limits for invitations to about 100 jurors at once?
6. ToolsDB: the current version, `binlog_format`, `max_user_connections`, and whether a small pool
   with `minimumIdle=0` is acceptable.
7. Is caching or proxying Commons thumbnails a §4.5 concern? Is hotlinking standard sizes from
   jurors' browsers the preferred pattern?
8. How are maintenance windows announced, and can a project ask to avoid a contest's judging weeks?
9. Do Toolforge webservice restarts overlap two pods?
10. Under §7.2, may a tool store organiser-entered usernames before sign-in, usernames of people who
    never use the tool (the COI list), and votes and comments tied to usernames?

## 10. The user's open questions

1. Is OAuth-only acceptable for WLM-UA's juries (every juror with a Wikimedia account), or only for
   other installations?
2. Build `wlxjury.auth.mode` and `wlxjury.thumbs.mode` now, independently of hosting?
3. Conflict of interest in the mode: keep board, auditor and staff accounts (pending WMCS), or only
   members who have signed in?
4. Usernames only, instead of real names, in the mode's UI, exports and reports?
5. Schedule the account join before any move to the mode?
6. A Toolforge pilot with a second maintainer (every maintainer can read all of ToolsDB)?
7. For full mode on Cloud VPS, if ever: accept the §7.3 duties (privacy statement, US processing,
   WMF and admin access, breach notice) and keep Google and passwords? Keep the `*.org.ua` names (a
   request each) or use `*.wmcloud.org`? Where do off-WMCS backups live?
