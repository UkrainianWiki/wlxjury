# OAuth login: port onto followups (2026-11-03)

The WIP branch `ukwikidev/oauth-login` (2 commits on a base 469+ commits behind: `97cf9d07` "Add
login with Google and Wikimedia accounts", `405f6a7e` "Fix OAuth login review findings") ported
onto `ukwikidev/followups` at `7a79a4bd`, as branch `ukwikidev/oauth-login-rebased` (worktree
`C:/scala/wlxjury-oauth-on-followups`). The original branch and its worktree are untouched. How it
works now: [docs/oauth.md](../oauth.md).

## How

A cherry-pick of `97cf9d07` conflicted in 21 files (the controllers, views, routes, messages,
config, build, specs: S2, S3, S7, S10, P0 and the Flyway batch rewrote all of them), so both
commits were **re-applied by hand**, in two code commits that can be compared with the original:

1. *Re-apply the providers and the identity table*: `services.oauth.{OAuthProvider, OAuthSettings,
   GoogleProvider, WikimediaProvider}`, `UserIdentityJdbc`, the migration (V63 → **V87**), the
   Nimbus dependency, `ProvidersSpec`, `UserIdentityJdbcSpec`: the original's files with small
   changes (below).
2. *Sign-in and linking on S2/S3*: `IdentityLinker`, `OAuthController`, `LoginResults`,
   `LinkedAccountsCard`, the views, routes, messages, config and specs, written against the new
   account and session model.

**pac4j:** nothing to replace. The original had already dropped the unused `play-pac4j` for its
own controller on the Nimbus OAuth 2.0 SDK (`com.nimbusds:oauth2-oidc-sdk` 11.38.2: PKCE, state,
nonce, the token exchange, Google's discovery and ID token checks); S10 had removed pac4j on
followups too. pac4j is not re-added. Nimbus pulls `nimbus-jose-jwt`, `json-smart`,
`content-type`, `lang-tag`, `jcip-annotations` (no Pekko: secure-coding §19's Pekko/scalawiki
rule is unaffected).

## What changed against the original

| Area | Original | Now | Why |
|---|---|---|---|
| Migration | V63 `user_identity` | V87, `TIMESTAMP(3)`, `utf8mb4_bin` provider/subject, names `user_identity_*` | V85/V86 are the mariadb-10.11 port's (coordinator, 2026-11-03); table-name rule; exact subject compare |
| Session | `user_id` key, `Secured` read it first; `username` only if unique | the S2/S3 session (`signedInSession`: name, `account_id`, `auth_version`, `issued_at`, `last_seen`, `keep`); `Secured` unchanged | S2/S3 already carry the id and version; one model for both sign-ins |
| Login name | left out if shared | a sign-in whose account has no name of its own (email, else a unique wiki account) is refused (`oauth.ambiguous`) | `Secured.sessionUser` resolves sessions by name, then checks the id |
| First-sign-in link by email | any account with the verified email | only if its email is verified, or it has no password (invitee) | S2 pre-claim (O2): whoever registered an address without owning it holds the password |
| First-sign-in link by wiki account | any account with the name | only if the wiki account is verified, or the account has no password and no email | S2/H1: no wiki account was ever verified; admins' typos and pre-S2 self-set names |
| Unverified match | linked | refused, `oauth.unverified` ("accept your invitation or sign in with your password, then link") | as above |
| Wiki account verification | — | a Wikimedia sign-in or link by the account's own wiki account sets `wiki_account_verified_at` | V77 reserved it for this login |
| Own wiki account | user-editable (S2 made it read-only) | linking a Wikimedia account from the profile sets it, verified (not if another account has the name, or another is verified); a changed name bumps `auth_version`, this browser keeps the new one | user: "they would be able to do it but would need to do OAuth login" |
| Admin changes | — | an admin's change of another's wiki account unlinks its Wikimedia identities; of an invitee's email, all its identities | the account may change hands |
| `LoginResults` | login page + redirect + session | the sign-in result shared with `LoginController` (`signedIn`, `indexRedirect`) | S2/S3 `signedIn` (theme, contest, `returnTo`, keep) |
| `returnTo` | — | the login button keeps it; `safeLocalPath` at start and callback | S3 login page; open redirects |
| Profile card | on every edit render, from `UserController` | own profile only (`ownProfile`), `LinkedAccountsCard` partial, flash keys allowlisted | S2's own-profile page |
| Errors | `e.getMessage` in exception texts and logs | our own texts only; causes logged by class; `security.account` key-value lines with ids | ErrorTextSpec (G13), LogHygieneSpec (G9), §13 |
| Limits | — | `oauth.maxConcurrent` (8) exchanges at once, else `oauth.busy` | §18: a new public endpoint gets a limit |
| Callback answer | `no-store` | `no-store` + `Referrer-Policy: no-referrer` | its URL holds the code |
| Allowed hosts | "AllowedHostsFilter is off" | the filter is on (B5); the oauth host lists narrow it | S7 |
| CSP | — | unchanged: the starts are links, the provider's redirect a navigation; `form-action 'self'` stays | S7; a POST start would need the providers in `form-action` |
| CSRF | unlink POST with token | the same, through `@csrfField()`; `OAuthRouterSpec` checks a POST without the token is refused | CSRF everywhere |
| Routes | — | `login`, `callback` in `RouteAuthSpec.Public` with reasons; `link`, `unlink` on `withAuthOn`/`withAuth` | G2 |
| Messages | 18 keys × 4 | + `oauth.unverified`, `oauth.busy`, `oauth.linked.wiki.set`, `oauth.linked.wiki.taken`; `oauth.no.account` says "invite" | invitations only |

The original's review fixes (`405f6a7e`), re-checked:

- exact wiki-name match after the first letter: kept (`User.normalizeWikiAccount` both sides);
- a wiki account and an email of different accounts refused: kept (`oauth.ambiguous`); saving a
  wiki account another account has: S2's `wiki.account.taken` already does it (stricter: by the
  column's collation);
- a message on any callback failure: kept (also for a database error, with a logged reference);
- the duplicate insert re-checked: kept;
- deleted users skipped by `byUserName`: S2 already does;
- the linked accounts kept on error re-renders: kept on the own profile's renders (`ownProfile`;
  not on its 503 "password busy" page);
- the login name only if the user's alone: superseded (above);
- lazy ID token validation with one key source: kept;
- sign-up through `loggedIn`: gone with sign-up (S2).

## Specs

- Ported: `ProvidersSpec` (the real providers against a local fake server: discovery, PKCE, nonce,
  audience, issuer, timeouts), `UserIdentityJdbcSpec`, `IdentityLinkerSpec` (S2 rules),
  `OAuthControllerSpec` (fake providers; the original's cases).
- New negative cases: an uninvited email or wiki user → refused, no account and no identity
  created; an unverified email with a password, an unverified wiki account → refused; a forged or
  missing state, another provider's flow, a timed-out flow → refused before any exchange; the
  nonce: `ProvidersSpec` ("an ID token for another sign-in"); `returnTo` to another site (start
  and a forged session key) → the start page; a deleted account → refused; a deactivated member
  (inactive membership) → the same denial page as a password sign-in; an `auth_version` bump ends
  an OAuth session, and a link whose session ended meanwhile is refused; a link from a session
  whose user changed → refused; busy → `oauth.busy`.
- `OAuthRouterSpec`: through the release's filters (CSP, allowed hosts, S3 filter, CSRF on): the
  start under the unchanged CSP, the callback's persistent S3 cookie, the session ended by "sign
  out everywhere", a forged state, an unconfigured host (400) and provider (404), unlink without
  the CSRF token (403).

No spec calls Google or Wikimedia.

Results (2026-11-03, on `eb57ee6f`): the OAuth specs (`services.oauth.*`, `UserIdentityJdbcSpec`,
`OAuthControllerSpec`, `OAuthRouterSpec`) 70 passed, exit 0; the affected and guard specs
(`security.*`, the login, account, profile, session and token specs, `deployment.*`, `views.*`,
`FlywayUpgradeSpec`) 421, one failure: `FlywayUpgradeSpec`'s V84 rollback example assumed V84 was
the latest migration; it now migrates to V84 only (rerun: 6, 5 passed, 1 skipped, exit 0). Full
suite: 1832 total, 0 failed, 0 errors, 9 skipped, 1 pending; JS tests 253 passed; exit 0.

## Still WIP

- Not tried against the real Google and Wikimedia endpoints (no clients registered); only the
  local fake. A manual end-to-end check with an owner-only Wikimedia consumer and a Google test
  client on `localhost:9000` (`-Doauth.scheme=http`) is needed before enabling it.
- No UI for an admin to see or remove a user's linked identities (root can delete the account).
- The 503 "password busy" render of the own profile lacks the linked-accounts card.
- Sign-in with a provider is always "kept signed in" (links can't carry the checkbox).
- Not run through the browser screenshot harness.

## Decisions (user, 2026-11-03)

1. A provider-verified email for an invitee marks the email verified and applies pending
   invitations, as accepting the invitation does (with the review's B3 safeguards, below).
2. A confirmed email change by root does **not** unlink identities.
3. A Wikimedia link from one's own profile **asks** before setting the wiki account (B4).
4. "Keep me signed in" is always on for provider sign-ins.
5. Root may sign in with a provider and follows the same linking rules as every account (safe
   with M4 and M1; the root accounts are 3 people with Gmail addresses and verified wiki
   accounts); a root link or sign-in is logged at WARN. A recent-sign-in window, a notification
   mail, a `linked_via` column, "never matched by a first sign-in" and "`setRoot` unlinks" were
   considered and dropped.
7. M4: Google's `email_verified` proves an address only when Google is authoritative for it: a
   Gmail address (`@gmail.com`, `@googlemail.com`, any case), or a Workspace account (an `hd`
   claim present; no domain list, `hd` need not equal the address's domain). Otherwise a Google
   sign-in neither matches nor verifies the email: the person accepts the mailed invitation, links
   Google from their profile, and signs in with it after that (`oauth.google.email.not.proof`).
6. Duplicate wiki accounts: an account chooser (a later commit of this branch); joining accounts is
   planned only ([2026-11-03-account-join.md](2026-11-03-account-join.md)).

## Security review fixes (2026-11-03)

The reviewer's verdict: merge after fixes, blocking before any host is enabled.

| Item | Fix | Specs |
|---|---|---|
| B1 identities survive a change of hands | `AccountTokenService.proveEmail` (the part shared by `acceptPasswordLink` and the provider path, so they can't drift) unlinks the account's identities in the acceptance's transaction, logged (`identitiesUnlinked=`); root's "reset password" unlinks them when it sends the link (`oauth_unlinked_by_admin`); a link start or callback is refused while the account has a password and an unverified email (`User.mayTakeAccessRole`, `oauth.link.unverified`) | `OAuthControllerSpec` "unlink every identity when an invitation or a password link is accepted", "… when root sends a password link", "refuse a link while the account has a password and an unverified email" |
| B2 root (decision 5) | root follows every account's rules (M4, B3, M1); every root link logs WARN `oauth_linked … root=true` and every root provider sign-in WARN `oauth_signin … root=true`; unlinking ends sessions (B5) | `OAuthControllerSpec` "link a root account with a Gmail address on its first sign-in, logging it at WARN", "let root link from its profile, then sign in through that identity" |
| B3 decision 1 with safeguards | a no-password account links by email only with a live invitation or password link (unused, unrevoked, unexpired) whose address is the provider-verified one and the account's current one: `AccountTokenService.acceptByProvider`, one transaction, the links' rows then the user's locked `FOR UPDATE`; it does what accepting the link does but the password (email verified, links used, the address's pending invitations under `mayStillGrant`, other links revoked, `auth_version` bumped, API tokens revoked, identities unlinked), links the identity in the same transaction (a refusal undoes it all), and signs in with the account as re-read. On the subject path, the same runs only when the provider's verified email is the account's. An account with a password and an unverified email stays refused | `IdentityLinkerSpec` "accept an invitee's live invitation …", "not link an invitee without a live invitation: none, revoked, expired, or to another address"; `OAuthControllerSpec` "sign an invitee in … (B3)" |
| B4 ask before setting the wiki account | the link no longer sets it: if the account's wiki account is empty, the callback goes to `GET /oauth/wiki-account?subject=…`, whose POST (CSRF) sets it, the name taken from the session user's own linked identity (`user_identity.username`), never the form; `User.setVerifiedWikiAccount` re-checks "empty or the same" and "nobody else's" in one transaction with the row locked; the same name is verified without asking; another (organizer-set) name: `oauth.linked.wiki.other` / `oauth.wiki.ask.organizer` (the CoI filter keys on it); `wiki_account_set` logged | `OAuthControllerSpec` "ask before setting an empty own wiki account …", "not offer another name …; verify the same one", "not set a wiki account another account has, nor another user's identity's name" |
| B5 unlink | ends every other session (`User.endSessions`; this browser keeps the new version), on `withAuthOn(blocking)`. The first review's choice (re-link by email after unlinking) is replaced by F2's tombstone below | `OAuthControllerSpec` "unlink only the user's own identity" (other sessions end) |
| M1 rename takeover | a by-name first sign-in links only a wiki-only placeholder that no Wikimedia identity has or had (none linked, wiki account never verified: only a Wikimedia identity verifies one), else `oauth.wiki.other.identity`; a sign-in whose identity's username moved away from the verified wiki account clears `wiki_account_verified_at` (WARN `wiki_account_unverified reason=wikimedia_renamed`) | `IdentityLinkerSpec` "not link by a wiki account a Wikimedia identity has or had", "unverify the wiki account when its Wikimedia account is renamed away from it" |
| M3 link start CSRF | `/oauth/*/link` needs the profile page's nonce (`n`, `OAuthController.LinkNonceKey`, new per rendering) and, if the browser sends it, `Sec-Fetch-Site` `same-origin` or `none` | `OAuthControllerSpec` "start only from the own profile …" |
| M2 audit | pre-deploy queries below | — |
| M4 Google `email_verified` for non-Gmail domains | decision 7: `ExternalProfile.emailAuthoritative` (Gmail, or an `hd` claim; `GoogleProvider` reads `hd`); `verifiedEmail` is None otherwise, so neither first-sign-in matching nor B3 uses it; a Google sign-in that matches nothing because of it gets `oauth.google.email.not.proof` | `IdentityLinkerSpec` "take Google's verified email as proof only for Gmail or a Workspace account" (Gmail trusted; `hd` with another address trusted; no `hd` and another address refused, nothing linked or verified, `email_verified` true; `email_verified` false refused) |
| L1, L4 | documented, no IP limiter now: the public callback is bounded by `oauth.maxConcurrent` and the state check (no exchange without this browser's flow); per-address throttling stays with fail2ban (infra plan, P1) | — |
| L2 Google discovery | fetched in the background at start when Google is offered, else at the first sign-in; a start waits at most connect + read timeouts; no lock held while fetching; a failure is retried | `ProvidersSpec` |
| L3 | the callback's unexpected failure logs the exception class and the reference only | — |
| L5 one normalisation | `User.normalizeWikiAccount` validates `CoiMembers.normalize`'s result (title case of the first letter, `user :` prefixes): the names stored now are the CoI filter's keys. Names stored before keep their form (the CoI filter normalises them when it reads) | `CoiMembersSpec`, `OwnAccountSpec` |
| L6 | V87 kept; renumbered at merge | — |
| Added specs | ID tokens: another key, an unknown `kid`, `alg` none, HS256 with the client secret, expired, `iat` in the future, `email_verified` `false`/`"false"`; Wikimedia `confirmed_email=false`, no `sub`; the unlink-without-CSRF 403's positive control (with the token: 303, unlinked); an allowed host not listed for the provider: 404; an admin's email change of an invitee unlinks its identities | `ProvidersSpec`, `OAuthRouterSpec`, `OAuthControllerSpec` |

## Account chooser (decision 6, 2026-11-03)

A Wikimedia sign-in whose username several accounts have as their wiki account lists them
(`OAuthChooserController`, `GET /oauth/choose?flow=…`) instead of refusing: the choice waits in the
signed session (15 minutes) under an opaque flow token, each account under an opaque option token
(the page and its forms carry those, never account ids); each entry shows the account's contests and
roles and a masked email. Root accounts are never listed, deleted ones never found.

- An account the linking rules let in (a never-linked placeholder: no password, no email, no
  Wikimedia identity now or before, M1) is linked on its POST (CSRF), its wiki account marked
  verified, and the user signed in.
- Any other needs proof of control first: its password (a failure logs WARN
  `oauth_choose_proof_failed accountId=…` on `security.auth`, where fail2ban will match sign-in
  failures; there is no app-level limiter), or a link mailed to its verified address
  (`account_token` purpose `oauth_choose`, V87 widens V77's check; single use, 30 minutes, one per
  minute), opened in the browser that made the choice (its page is a button; the POST checks the
  link is live, the account's, and one of the session's options).
- Then linked like any identity: the next sign-in goes straight to that account by its subject.
- Specs: `OAuthChooserSpec` (a list of two, one direct and one needing a password; a wrong password
  links nothing and logs; the mailed link, not in another browser, single use; a tampered or
  expired flow, an option not of the session's; root never listed); `OAuthRouterSpec` (both POSTs
  refused without the CSRF token).

Joining duplicates is planned only: [2026-11-03-account-join.md](2026-11-03-account-join.md).

## Results after the review fixes and the chooser (2026-11-03, `410e9723`)

OAuth specs (`services.oauth.*`, `UserIdentityJdbcSpec`, `OAuthControllerSpec`, `OAuthRouterSpec`,
`OAuthChooserSpec`): all passed. Account, session, login specs with `security.*`, `deployment.*`,
`CoiMembersSpec`, `MessagesCompletenessSpec`, `FlywayUpgradeSpec`, `AccountVerificationMigrationSpec`:
308, 307 passed, 1 skipped, exit 0. Full suite: 1854 total, 0 failed, 0 errors, 9 skipped,
1 pending; JS 253 passed; exit 0.

## Follow-up review fixes (2026-11-03)

The reviewer's verdict: merge after fixes; no blocking item left from the first review.

| Item | Fix | Specs |
|---|---|---|
| F1 a chooser pick of an email-less account linked but never signed in | the login name (`OAuthController.loginName`: an email, or a wiki account naming that account alone) is computed **before** linking, and its absence refuses with no side effect (`oauth.choose.ask.organizer`); the list shows such an account as "ask your organizer". The spec's twins are both `Twin Name`, as S2 stores them. **Follow-up** (not done: core session semantics): resolve post-S2 sessions by `account_id` instead of by name; then a placeholder with a shared wiki account could be chosen directly | `OAuthChooserSpec` "not link an account without a login name of its own …" |
| F2 an unlinked compromised account re-linked by email | `user_identity.unlinked_at` (V87): unlinking and every account-changes-hands removal leave a tombstone; a first sign-in never re-links a tombstoned (provider, subject) (`oauth.unlinked.before`); only a link from the own profile does (`IdentityLinker.link(relink = true)`), and the B3 subject path re-links the identity signing in. Replaces B5's choice. M1's "had a Wikimedia identity" counts tombstones | `OAuthControllerSpec` "not be linked again by a first sign-in's email, only from the profile" |
| F3 chooser disclosure | contests and roles only for an account chosen directly; an account that needs a proof shows its masked email (local part and domain: `a***@e***.org`) and the proofs | `OAuthChooserSpec` "list them by option tokens …" |
| F4 password proof | only for an account `User.mayTakeAccessRole` lets in (a pre-claimer's password with an unverified email is no proof); at most 5 wrong passwords per choice (in memory, by flow token: a replayed cookie can't reset it); `oauth_choose_proof_failed accountId=… proof=password ip=…` (`request.remoteAddress`, Play's resolution of `X-Forwarded-For` from the trusted proxy); `PasswordHasher.Busy` answers `oauth.busy` | `OAuthChooserSpec` "offer no password proof for a password with an unverified email", "stop password proofs after five wrong ones" |
| F5 mail proof | the link lives 15 minutes, as the choice; the mail names the Wikimedia account; the once-a-minute check and a daily cap of 5 per account are checked and the link created under the account's row lock (`UserService.sendChooseLink`). Not done (optional): binding the token to the identity's subject (the confirm already needs this browser's choice, which holds it) | `OAuthChooserSpec` "prove an account by a link mailed …" |
| F6 normalisation | pre-deploy query below | — |
| F7 | `docs/oauth.md`: Wikimedia's `confirmed_email` is a one-time confirmation, accepted for the reason given under M4 | — |

## Results after the follow-up fixes (2026-11-03, `f0b9e4ec`)

OAuth and chooser specs: 95 passed, exit 0. Account, session, login specs with `security.*`,
`deployment.*`: 258, 257 passed, 1 skipped, exit 0. Full suite (run without the machine's Commons
credentials: `env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD`):
1857 total, 0 failed, 0 errors, 9 skipped, 1 pending; JS 253 passed; exit 0.

## Results on the merged branch (2026-11-03, `08f30c66`)

Followups merged in twice: `df096837` (the MariaDB 10.11 port: V85, V86, the timestamp lint; V87
stays after V86 and passes it) as `f51a2df3`, and `1c15fade` (the write-path robustness batch) as
`08f30c66`, which also moves the acceptance's log line after the commit and runs
`acceptByProvider` under `WriteTx.write`. `FlywayUpgradeSpec`, `MigrationNamingSpec`,
`StructuralWritesSpec`, `security.*`, the OAuth and chooser specs, `AccountInvitationSpec`: 154,
153 passed, 1 skipped, exit 0. Full suite with the `env -u COMMONS_USER -u COMMONS_PASSWORD
-u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD` prefix: 1884 total, 0 failed, 0 errors, 10 skipped,
1 pending; JS 253 passed; exit 0.

## Pre-deploy items

- Migration V87 (`user_identity`): after the mariadb-10.11 port's V85 and V86; the orchestrator
  renumbers it at merge if the order changes (coordinate via `conf/db/migration/default`).
- Register the clients: one Google web client with each host's
  `https://<host>/oauth/google/callback`; one Wikimedia OAuth 2.0 consumer per host (confidential,
  "identity verification only, with real name and email"), its callback the host's
  `https://<host>/oauth/wikimedia/callback`; approval can take days.
- `production.conf` (the deployed one from `wlxjury-infra`'s template, not edited here): the
  `oauth.google { clientId, clientSecret, hosts }` and `oauth.wikimedia.clients` blocks as in
  `conf/wlxjury/production.conf`'s comment, secrets as `${?VAR}`; `runtime.env`:
  `WLXJURY_GOOGLE_CLIENT_ID`, `WLXJURY_GOOGLE_CLIENT_SECRET`, `WLXJURY_WIKIMEDIA_<HOST>_CLIENT_ID`,
  `..._CLIENT_SECRET` (root-only `0600`). Each OAuth host must be in `WLXJURY_ALLOWED_HOSTS`.
  Without these the feature stays off (no buttons, the routes answer 404).
- Outbound HTTPS from the server to `accounts.google.com`, `oauth2.googleapis.com`,
  `www.googleapis.com` (keys) and `meta.wikimedia.org`.
- Logs: `security.account` at INFO in the production logback (the `oauth_*` lines).
- The manual end-to-end check above, on a staging host, before listing a production host.
- (review M2) Before listing a host, audit, read-only:
  - wiki-only placeholders (no password, no email) with an access role, which a Wikimedia sign-in
    by that name would open: `SELECT u.id, u.wiki_account, uc.contest_id, uc.role FROM users u
    JOIN user_contest uc ON uc.user_id = u.id WHERE u.password IS NULL AND (u.email IS NULL OR
    u.email = '') AND u.deleted_at IS NULL AND BINARY uc.role IN ('jury', 'organizer', 'admin')`;
    each is confirmed by its organizer, given an email (an invitation), or its wiki account cleared;
  - root accounts' wiki accounts (`SELECT id, wiki_account, wiki_account_verified_at FROM users
    WHERE is_root = 1 AND wiki_account IS NOT NULL`): the user says one root has one; it must be
    verified (a profile link) or cleared;
  - admin and organizer memberships of accounts with an unverified wiki account and no verified
    email, and for each placeholder above, that its Wikimedia username still exists and is the
    intended person's (a renamed or vanished account's name may be registered by anyone).
- (follow-up review) Before enabling **Wikimedia**: F1 and F2 fixed (done on this branch, check
  they are in the deployed build).
- (F6) Wiki accounts stored before S2's normalisation, which `User.normalizeWikiAccount` would
  store differently (an underscore, a `User:` prefix, outer or double spaces, a lower-case first
  letter); review the list, then fix (read-only first):
  `SELECT id, wiki_account FROM users WHERE wiki_account IS NOT NULL AND wiki_account <> '' AND
  (wiki_account LIKE '%\_%' OR wiki_account LIKE 'User:%' OR wiki_account LIKE 'user:%' OR
  wiki_account <> TRIM(wiki_account) OR wiki_account LIKE '%  %' OR
  BINARY LEFT(wiki_account, 1) <> BINARY UPPER(LEFT(wiki_account, 1)))`; then, for the reviewed
  ids: `UPDATE users u JOIN (SELECT id, TRIM(REGEXP_REPLACE(REGEXP_REPLACE(REPLACE(wiki_account,
  '_', ' '), '^[Uu]ser *: *', ''), ' {2,}', ' ')) AS n FROM users WHERE id IN (...)) x ON x.id = u.id
  SET u.auth_version = u.auth_version + 1, u.wiki_account = CONCAT(UPPER(LEFT(x.n, 1)),
  SUBSTRING(x.n, 2))` (`auth_version` first: a changed name ends the account's sessions, as
  `User.setWikiAccount` does). A Georgian Mkhedruli first letter stays as it is (the app's rule is
  `CoiMembers.normalize`): check those rows by hand.
- (follow-up review) The root wiki-account check and the M2 audit above.
- (merge) Confirm that Apache proxies to Play on `127.0.0.1` (not the host's public or LAN
  address): Play trusts `X-Forwarded-For` only from loopback (`play.http.forwarded.trustedProxies`'s
  default), so the `ip=` of `oauth_choose_proof_failed` (and fail2ban's match on it) is the client's
  only then; otherwise it is Apache's.
- (follow-up review) fail2ban: the filter that will match sign-in failures (infra plan, P1) also
  matches `security.auth` WARN `oauth_choose_proof_failed accountId=… proof=password ip=<addr>`.
