# Security resolution plan (2026-11-02)

Status: trimmed on 2026-11-03 to what is still open. It resolved the items of [the consolidated
report](../history/security/2026-11-02-rereview-report.md) (IDs N*, O*, P* as there); the live
status of every item is [register.md](../security/register.md), the rules are
[secure-coding.md](../security/secure-coding.md). The batch designs, results and review fixes
that were here are in the merge commits below (full text: `git show
98a8a79e:docs/plans/2026-11-02-security-resolution.md`). Pre-deploy items (V76 role queries,
V77 wiki-account count, V80 sessions, V81 grace period, O1, HSTS, loggers) went to the roadmap's
"Before any production deploy".

| Batch | Items | State |
|---|---|---|
| S1 | N1 role allowlist (V76); production hotfix | merge `93160a59`; hotfix deployed 2026-11-02, merged `7c89f40b` |
| S2 | Account integrity (V77): O2, N2, N4, N7, P7, P14, P18, P20 | merge `3399d24a` |
| S3 | Sessions (V80): O3, N2, P16 | merge `60e77c0f`, spec fixes `074314c7` |
| S4 | Throttling: P1, N6, P13 (failed tokens) | N6 done in S7; the rest open (below) |
| S5, S10 | Output and log hygiene (V79), dependencies, root token revocation | merge `969a73c9` |
| S6 | Proxy widths, stopped jurors, legacy SHA-1 retirement (V81), outbound hosts | merge `a21f012f` |
| S7 | Headers, `Permissions-Policy`, CSP config key, login CSRF | merge `dad6ae55` |
| S8 | Infra: O1, P2, P15, `useSSL` | open (below) |
| S9 | Ops | skipped by the user (below) |

Open beyond these (register): P3 `style-src` (later), P5's per-user miss limit, P13's failed-token
limit, N5 (postponed), P19 audit log (rounds-parity plan); the S7 nits (no-referrer on error
pages for token URLs; the bad-Host comment).

## S4: Throttling

**Items:** P1, N6, P13 (failed tokens).

**Design:** decision 1 between (a) in the app: a bounded in-memory limiter keyed by client
address (Play's resolved remote address behind the trusted proxy) and by account, with
exponential back-off and a 429 page/JSON with `Retry-After`, applied before the bcrypt turn; the
single-instance assumption is documented already (`docs/deployment.md:227`); or (b) fail2ban on a
dedicated `security.auth` log line plus Apache. Same limiter for unknown/invalid API tokens. N6:
set `play.filters.csrf.header.protectHeaders = null` so that `/auth` and `/signup` need the token
too (check that the login page always sets it), or check `Origin`/`Sec-Fetch-Site` there.

**Specs:** `LoginControllerSpec` (the N+1st failure from one address is a 429; a different
account from the same address also; success resets; the limiter is bounded); `ApiSpec` (failed
tokens limited); `CsrfTestApp` router spec: a cookieless `POST /auth` without a token is refused.

**Decisions (user):** (1) app vs. fail2ban; (2) limits (e.g. 10 failures per 15 minutes per
address, 5 per account).

**Decided (user, 2026-11-02):** fail2ban, not in the app; S4 is skipped here and goes to the
infra plan (`security.auth` log line it can match, limits set there). N6 (login CSRF) is not
throttling and moves to S3 or S7.

**Dependencies:** after S3 if both touch `LoginController` in the same week; otherwise free.

## S8: Infra (for the infra plan)

**Items:** O1, P2 (HSTS), P15 (logback), `useSSL` if the DB is ever remote.

Not done here: they belong to `docs/plans/2026-11-01-infra-in-repo.md` (branch
`ukwikidev/infra-in-repo`). Mapping:

- **O1:** until the roles move, the user adds B5's block (`wlxjury.allowedHosts =
  ${WLXJURY_ALLOWED_HOSTS}`, `play.filters.hosts.allowed = []`, the three `secure` lines) to
  `wlxjury-infra`'s `production.conf.j2` and `WLXJURY_ALLOWED_HOSTS` to `runtime.env`, **before**
  deploying the followups branch. After the infra plan's Step 3, `ProductionConfigSpec` reads the
  template itself, which ends the drift (see the practices plan's parity guard).
- **P2:** `Header always set Strict-Transport-Security "max-age=31536000"` in the SSL vhost
  template (a later `includeSubDomains` only after checking every subdomain); add it to the infra
  plan's Step 2 or 3 container test (`test_check_vhosts.py`).
- **P15:** the infra plan's Step 4 (`conf/logback-production.xml` with `ApiTokenService`, `coi`).
- **`useSSL`:** only if `WLXJURY_DB_HOST` is not local; record the choice in the template.

**Decision (user):** patch `wlxjury-infra` now for O1/P2, or wait for the infra plan's Steps 2–4
(then the followups branch waits too).

**Decided (user, 2026-11-02):** folded into the infra plan (section "Security items folded in" on
`ukwikidev/infra-in-repo`, 088b76e9), together with S4's fail2ban throttling. The followups branch
is not deployed before O1 is in place.

## S9: Ops

**Items:** P10, P8 (count), key rotation, the S1 data check.

- P10: delete `C:\scala\wlxjury-bench-work\proddata\*.sql.gz` and the seven `wlxjury-bench-*`
  volumes once B10 no longer needs them (decision: keep an anonymised fixture instead).
- P8: `SELECT COUNT(*) FROM users WHERE password REGEXP '^[0-9a-f]{40}$';` (read-only) before S6
  decides the grace period.
- Key rotation after S3 is deployed, following the new procedure, if any session may have leaked
  (decision).
- S1's queries, and again after S1's migration runs.

**Decided (user, 2026-11-02):** S9 skipped for now.
