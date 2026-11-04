# External integrations

The services the jury tool talks to and how: the Wikimedia API and image hosts, the scalawiki
library that wraps them, and the OAuth sign-in providers. Each rule has a short why and an
example. Which hosts may be called at all is [secure-coding.md](../security/secure-coding.md)
§17; how tests avoid these services is [testing/practices.md](../testing/practices.md) §5;
the OAuth setup steps are [oauth.md](../oauth.md). **Sources:** CLAUDE.md ("Testing",
"Wikimedia Commons Integration"); secure-coding §17, §19; `docs/oauth.md` and the
[OAuth port notes](../plans/2026-11-03-oauth-login-port.md); the
[CI and scalawiki packages plan](../plans/2026-11-03-ci-and-scalawiki-packages.md);
`services.imports.{CommonsLogin, MwApi, AnonymousBots}`, `LocalImageCacheService`.

## Contents

1. [The Wikimedia API](#1-the-wikimedia-api)
2. [scalawiki](#2-scalawiki)
3. [OAuth providers](#3-oauth-providers)
4. [Guards](#4-guards)
5. [Where the code doesn't follow these yet (for triage)](#5-where-the-code-doesnt-follow-these-yet-for-triage)

## 1. The Wikimedia API

**1.1 Anonymous by default.** Every bot wlxjury creates comes from
`services.imports.AnonymousBots` (`loginInfo = None`): `AppModule.bot`, `Global.commons`,
`MonumentListSource`, `SpecialNominationFilter`'s monument query. `AppModule` seeds scalawiki's
bot cache (one bot per domain) with the anonymous commons and uk.wikipedia bots at start, so
scalawiki's own default calls (`SpecialNomination.getNominationMonuments`, `CampaignList`) get
them instead of logging in from `SCALAWIKI_LOGIN`. *Why:* scalawiki's `MwBot.fromHost`/`fromSite`
default to `LoginInfo.fromEnv()`; a machine with the bot's credentials logged it in from every JVM
(CI plan, "Anonymous Wikimedia access"). Not covered: a scalawiki default call for another domain
in a JVM where no bot of that domain was created first; wlxjury has none, and a new one must
seed its bot first.

**1.2 A bot password only in production.** `commons.user` / `commons.password` (`COMMONS_USER` /
`COMMONS_PASSWORD` in the server's `runtime.env`) is a bot password (`User@name`,
Special:BotPasswords with "High-volume (bot) access") of a Commons account in the bot group.
Nowhere else: tests are anonymous by config and by `CommonsLogin`'s Mode.Test rule (testing §5.1),
the bench server passes no Commons environment, CI passes no secrets but `GITHUB_TOKEN`.

**1.3 `apihighlimits` sets the page sizes.** `CommonsLogin` logs the shared bot in and checks
`meta=userinfo` for `apihighlimits`: 500 page ids or titles per request with it
(`CommonsLogin.HighLimit`), 50 without (`Limit`); import steps take one request's worth
(`jobs.commonsImport.pageSize`, unset). A session that went anonymous (MediaWiki ends idle
sessions, and reads then succeed anonymously without an error) is found by the recheck and
logged in again; a refused login waits `loginRetry` before trying again. *Why:* the wiki limits
failed logins per account, and a wrong bot password must not become a stream of failed logins.
An anonymous session still works, in smaller batches.

**1.4 Identify the client.** API requests carry scalawiki's User-Agent (`HttpClientPekko`:
`ScalaWiki/0.7 (https://github.com/UkrainianWiki/scalawiki; ukwikidev@wikimedia.org.ua)`,
`SCALAWIKI_USER_AGENT` overrides it); image downloads send
`WLXJury/1.0 (https://commons.wikimedia.org/wiki/Commons:WLX_Jury_Tool; …)`
(`LocalImageCacheService`). *Why:* Wikimedia's API etiquette and
its User-Agent policy require a descriptive agent with contact details; anonymous-looking clients
are throttled or blocked.

**1.5 Retry what may succeed, not what will answer the same.** `MwApi.withRetries`: 4 retries,
2 s doubling, a 2-minute request timeout; it retries timeouts, HTTP errors, non-JSON answers and
the API codes `maxlag`, `ratelimited`, `readonly`, `internal_api_error_DBQueryError`, never other
API errors. Image downloads retry 429 with backoff and jitter, honour `Retry-After`
(`wlxjury.thumbs.max-attempts`, 3), and give up on other statuses; a 404 is a deleted or renamed
file (`ThumbnailNotFound`). Downloads are throttled (`wlxjury.thumbs.rate-per-second` 10,
`parallelism` 2). *Why:* Wikimedia asks clients to back off when lagged or limited; a tight retry
loop makes it worse.

**1.6 Long fetches run as jobs, outside transactions.** Imports call the API in a
`FetchingJobHandler`'s fetch lane (`jobs.fetchWorkers`) or in `prepare`, never inside a step's
transaction or on a request thread; a step records its cursor (`continue`) so a restart resumes
(`MwApi.page`). *Why:* a remote call can take minutes; a transaction or a request thread held for
it blocks others (db §5.5a).

**1.7 Only configured hosts.** Commons (`commons.host`), `upload.wikimedia.org`, the contest's lists
host (set by root); never a URL from a request; redirects not followed (secure-coding §17).

## 2. scalawiki

**2.1 Pinned to a commit.** wlxjury builds against `UkrainianWiki/scalawiki` `origin/page-categories`
at `25951dbe` (version `0.7.0-SNAPSHOT`). Locally it comes from `~/.ivy2/local` via scalawiki's
`publishLocal`; CI's composite action `.github/actions/scalawiki` fetches that commit and runs
`publishLocal`, cached by SHA. The SHA is `SCALAWIKI_SHA` in `ci.yml`, `coverage.yml`,
`deployment.yml` and `integration.yml`: bump all of them together. *Why:* a snapshot that exists
only in each developer's `~/.ivy2/local` can't be reproduced; the pin makes CI and laptops build
the same code.

**2.2 Read scalawiki at its pinned commit.** A local scalawiki checkout or `target/dep-sources` may
be on another branch or older: read `git show 25951dbe:<path>` (or `origin/page-categories`)
before concluding that something is or isn't in scalawiki.

**2.3 Pekko in step.** Pekko and Pekko HTTP come with scalawiki too; wlxjury's `PekkoVersion` (1.0.3)
and `PekkoHttpVersion` (1.0.1) in `build.sbt` match scalawiki's `project/Dependencies.scala`
(`Library.Pekko`). Bump scalawiki first, then wlxjury, then rerun the image cache specs
(secure-coding §19, [dependencies](../dependencies/practices.md) §3).

**2.4 Planned: a published package.** GitHub Packages under the existing group `org.scalawiki`
(`scalawiki-core_2.13`, `scalawiki-wlx_2.13`, `http-extensions_2.13`), released only from tags
(`0.7.0-M1` first, on `25951dbe`), no published snapshots, versions never moved or deleted;
wlxjury resolves `~/.ivy2/local`, `~/.m2`, Maven Central, then GitHub Packages last, and
`-Dscalawiki.version=0.7.0-SNAPSHOT` for joint work. The drafts are in `docs/plans/ci-drafts/`.

**2.5 Changes to scalawiki** go to that repository as their own PRs (the anonymous-tests diff
before the publishing one), then the pin moves.

## 3. OAuth providers

**3.1 Register per host.** One Google web client with every host's
`https://<host>/oauth/google/callback`; one Wikimedia OAuth 2.0 consumer per host (it has a single
callback), confidential, "identity verification only, with real name and email". A host is listed
under the provider (`oauth.google.hosts`, `oauth.wikimedia.clients`) and in
`wlxjury.allowedHosts`; the redirect URI is built from the request's host only if listed
exactly. Secrets are `${?VAR}` references (oauth.md). *Why:* the session cookie belongs to the
host, so the provider must come back to the same host.

**3.2 Apache proxies to `127.0.0.1`.** Play trusts `X-Forwarded-For` only from loopback
(`play.http.forwarded.trustedProxies`' default), so the client's address in `security.auth` lines
(and fail2ban's match on it) is right only if Apache proxies to `127.0.0.1`, not to the host's
public or LAN address (`conf/apache/0002-wlxjury.conf`; to confirm on the server before listing a
host).

**3.3 Check on staging first.** The manual end-to-end sign-in and link check runs on a staging host
before a production host is listed; the read-only audits of wiki-only placeholders and root wiki
accounts come before enabling Wikimedia (OAuth port notes, "Pre-deploy items").

**3.4 Trust an email only where the provider is authoritative.** Google's verified email counts
only for Gmail (`@gmail.com`, `@googlemail.com`) or a Google Workspace account (an `hd` claim);
Wikimedia's confirmed email when the consumer may read it. Any other address needs the mailed
invitation first. *Why:* Google's flag for other domains says nothing about who holds the address
now (`ExternalProfile.emailAuthoritative`; oauth.md, secure-coding §14).

**3.5 Bounded calls.** Only `oauth.google.issuer` (discovery, keys) and `oauth.wikimedia.baseUrl`,
with `oauth.connectTimeout`/`readTimeout`; at most `oauth.maxConcurrent` exchanges at once
(secure-coding §17, §18). The server needs outbound HTTPS to `accounts.google.com`,
`oauth2.googleapis.com`, `www.googleapis.com` and `meta.wikimedia.org`.

**3.6 Specs never call the providers** (testing §5.4).

## 4. Guards

| Rule | Enforced by | State |
|---|---|---|
| 1.1 bots anonymous, seeded at start | `AnonymousBotsSpec` | enforced |
| 1.2 no credentials in tests or CI | `TestConfigAnonymousSpec` | enforced |
| 1.3 page sizes and relogin | `CommonsLoginSpec`, `MwCommonsSourceSpec` | enforced |
| 1.5 retries | `MwCommonsSourceSpec` (with a short `retryDelay`); `LocalImageCacheServiceSpec` (429, `Retry-After`, `maxAttempts`) | enforced |
| 1.7 outbound hosts | `LocalImageCacheServiceSpec` ("a fetch"), `ImageProxyFileCheckSpec`; G10 `OutboundHostsSpec` | enforced per path; G10 planned |
| 2.1 one SHA in every workflow | — | planned: a check that the four `SCALAWIKI_SHA` values agree (until the package replaces them) |
| 2.3 Pekko in step | `build.sbt` comment | review only |
| 3.1 hosts listed exactly | `OAuthControllerSpec`, `OAuthRouterSpec` | enforced |
| 3.4 authoritative emails | `ProvidersSpec`, `IdentityLinkerSpec` | enforced |
| 3.2, 3.3 proxy and staging checks | OAuth port pre-deploy list; the [release checklist](../deployment.md#release-checklist) | manual |

## 5. Where the code doesn't follow these yet (for triage)

- **I1 (1.4):** `LocalImageCacheService.downloadWithRetry` logs the full URL on 429 and on other
  statuses (`Giving up on $url`, `HTTP … for $url`), while its refusal logs the host only. URLs are
  Commons file paths (no personal data), but the lines are not key-value (operations §1.1).
- **I2 (1.4):** API requests use scalawiki's generic agent (`ScalaWiki/0.7`), not one naming the
  jury tool; set `SCALAWIKI_USER_AGENT` on the server or pass an agent from wlxjury.
