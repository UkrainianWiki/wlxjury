# Security register

The one live status table of security items. IDs are the [consolidated
report](2026-11-02-rereview-report.md)'s (N* new findings, O* open from the previous review,
P* its remaining items); batches are the [resolution
plan](../plans/2026-11-02-security-resolution.md)'s (S1–S10). Dated reviews and plans are
snapshots: their tables are not updated, this one is. Rules: [secure-coding.md](secure-coding.md);
reviews: [review-checklist.md](review-checklist.md).

State on `ukwikidev/followups` at `58c5a2b8` (2026-11-02): S1, S2, S5, S10 and C1 merged; S3
in progress; S6 and S7 queued; S4 and S8 folded into the (postponed) infra plan; S9 skipped.

## Open and in progress

| ID | Title | Severity | Status | Owner | Batch | Guard | Last checked |
|---|---|---|---|---|---|---|---|
| O3 | No server-side session expiry; sign-out and password change don't end other copies | Medium | In progress (S3: version, sign-in time, 1 year absolute, 1 month idle; old cookies upgraded in place) | app | S3 | `SecuredSpec`; planned G7 | 2026-11-02 |
| N2 | Own password/email change without the current password; tokens survive a password change | Low | Half fixed (S2: current password, email change bumps the version); own password change bumping the version in S3 | app | S2, S3 | `OwnAccountSpec`, `PasswordStorageSpec` | 2026-11-02 |
| P16 | Stale docs; no secret-rotation procedure | Info | Texts fixed (S10); key-rotation procedure with S3 | app (docs) + ops | S3, S10 | — | 2026-11-02 |
| O1 | Infra `production.conf.j2` lacks B5's allowed hosts and Secure-cookie lines | Medium | Open; applied by hand before any followups deploy (roadmap) | infra | S8 (infra plan, postponed) | `ProductionConfigSpec` (repo side); planned G14 | 2026-11-02 |
| P1 | No sign-in throttling per address or account | Medium | Open; fail2ban on a `security.auth` line (user decision) | infra | S4 (infra plan, postponed) | — | 2026-11-02 |
| P2 | No HSTS on the HTTPS vhosts | Low | Open; pre-deploy item | infra | S8 | planned infra `test_check_vhosts.py` | 2026-11-02 |
| P15 | Infra logback lacks `ApiTokenService`/`coi` at INFO (and `org.mariadb.jdbc` at ERROR) | Info | Open; pre-deploy item (the repo's `conf/wlxjury/logback.xml` has all three) | infra | S8 | — | 2026-11-02 |
| N11 / P12 | SQL logged with bound values; JDBC `useSSL=false` | Info | App part fixed (S5, driver logger `e02a86de`); `useSSL` open (only if the DB is remote) | app + infra | S5, S8 | `LogHygieneSpec` (G9), `SqlLoggingSpec` | 2026-11-02 |
| P13 | API: failed tokens unlimited; root revocation; double click | Info | Root revocation and double click fixed (S10); failed-token limit open | app / infra | S4, S10 | `ApiTokenPageSpec` | 2026-11-02 |
| N6 | Login CSRF from a browser with no cookie for the site | Info | Open; moved out of S4 to S3 or S7 | app | S3 or S7 | planned G3 | 2026-11-02 |
| P4 | Security headers never asserted; no `Permissions-Policy` | Low | Queued | app | S7 | planned G6 | 2026-11-02 |
| P11 | CSP set through the deprecated key | Info | Queued | app | S7 | `ContentSecurityPolicySpec` | 2026-11-02 |
| P17 | `/api/docs` under the production CSP not checked in a browser | Info | Queued | app | S7 | — | 2026-11-02 |
| P3 | `style-src 'unsafe-inline'`; broad `img-src`/`media-src` | Low | Later (user decision) | app | S7 (later) | `ContentSecurityPolicySpec` | 2026-11-02 |
| P5 | Image proxy: off-set widths rescaled per request, no rate limit | Low | Partial; queued (after B10's benchmark) | app | S6 | planned with S6 | 2026-11-02 |
| P6 | A juror deactivated in a round can still vote | Low | Queued (decided: both vote actions check `round_user.active`, 409) | app | S6 | `MutationAuthorizationSpec` (to extend) | 2026-11-02 |
| P8 | Dormant legacy SHA-1 hashes | Low | Queued: replaced after a 3-month grace period; the production count (S9) skipped | app + ops | S6, S9 | `PasswordStorageSpec` | 2026-11-02 |
| P9 | Original-file fetch has no host allowlist | Low | Partial; queued | app | S6 | planned G10 | 2026-11-02 |
| N5 | Greeting/invitation mail "From" the inviter | Low | Postponed (user decision) | app | — | — | 2026-11-02 |
| P10 | Production dump and bench volumes on the workstation | Low | Open; S9 skipped for now | ops | S9 | — | 2026-11-02 |
| P19 | No audit log of admin actions | Info | Scheduled with the rounds-parity plan's audit log | app | parity plan | — | 2026-11-02 |
| N1 (production) | The role-allowlist hotfix on the server | High | Hotfix deployed 2026-11-02 as `0.13-round-filters-rolefix-f3f2c73617de` (branch `ukwikidev/hotfix-role-allowlist-f9c31ce5`, runbook [2026-11-02-role-allowlist-hotfix.md](2026-11-02-role-allowlist-hotfix.md) on that branch); the schema-66 ZIP from `ukwikidev/hotfix-role-allowlist` is superseded and must not be deployed; `jury1`/`jury-` rows reset; open: the user's `roles=root` browser check and the leftover query | ops | S1 | — | 2026-11-02 |

## Fixed

| ID | Title | Severity | Status | Owner | Batch | Guard | Last checked |
|---|---|---|---|---|---|---|---|
| N1 | Contest admin can make any account root through the free-text role | High | Fixed (exact allowlist, root only from `is_root`, V76 `CHECK`) | app | S1 | `RoleValuesSpec` (G1), `RoleAssignmentSpec`, `UserContestSpec`, `UserContestRolesSpec`, `UserContestRoleMigrationSpec` | 2026-11-02 |
| O2 | Account pre-claim through open sign-up and unverified identifiers | Medium | Fixed (invitations only, verified emails) | app | S2 | `AccountInvitationSpec`, `AccountReviewFixesSpec` | 2026-11-02 |
| N4 | Wiki account names not validated or unique; unencoded in links | Low | Fixed (validated, normalised, ambiguous sign-in refused; merging duplicates postponed) | app | S2 | `OwnAccountSpec`, `AccountReviewFixesSpec` | 2026-11-02 |
| N7 | Sign-up tells whether an email has an account | Info | Fixed (sign-up gone; same page for every refused link) | app | S2 | `OwnAccountSpec` | 2026-11-02 |
| P7 | Root's "reset password" sent nothing | Low | Fixed (a mailed single-use link) | app | S2 | `AccountInvitationSpec`, `PasswordStorageSpec` | 2026-11-02 |
| P14 | Sign-in and sessions ignore `deleted_at` | Info | Fixed | app | S2 | `OwnAccountSpec` (P14, P18, N4) | 2026-11-02 |
| P18 | `byUserName` looks up the untrimmed name | Info | Fixed | app | S2 | `OwnAccountSpec` (P14, P18, N4) | 2026-11-02 |
| P20 | First sign-up on an empty database becomes root | Info | Fixed (`/setup` with a setup token) | app | S2 | `SetupSpec` | 2026-11-02 |
| N3 | CSV formula injection | Low | Fixed (`Csv.cell`/`Csv.text`) | app | S5 | `ExportsSpec` (G8), `CsvSpec` | 2026-11-02 |
| N8 | Tile caption HTML inserted with `.html()` | Info | Fixed (escaped once more; unitegallery patched) | app | S5 | `RawHtmlSinksSpec` (G5), `GalleryDescriptionViewSpec`, Jest `unitegallery-alt` | 2026-11-02 |
| N9 | Job status JSON shows the exception message | Info | Fixed (`JobError` key + reference) | app | S5 | `ErrorTextSpec` (G13), `JobControllerSpec` | 2026-11-02 |
| N10 | Unused pac4j; `pekko-http` 1.0.1; vendored JS | Info | Fixed (pac4j removed, vendored JS listed); `pekko-http` deferred to a scalawiki-first bump | app | S10 | planned G11 | 2026-11-02 |
| C1 | Criteria votes: misplaced votes accepted, unlocked sums, orphan rates | — (integrity) | Fixed (refused, locked, orphans removed; V78) | app | criteria C1 | `LargeImageSpec`, `MutationAuthorizationSpec` | 2026-11-02 |

Fixed before the re-review and still guarded (2026-11-02 re-check, no regression): SQL
injection in `ImageDbNew` (Vuln 1; `RawSqlSpec`, `SelectionQuerySpec`); the committed secret
(Vuln 2; `ProductionConfigSpec`); SHA-1 password storage (Vuln 3; `PasswordStorageSpec`,
`RawSqlSpec` G4b); `MYSQL_PWD` in scripts (Vuln 4; `test_release_script.py`); CSP for scripts
(`NoInlineScriptsSpec`, `InlineScriptHashesSpec`); Secure cookies and allowed hosts (B5;
`ProductionConfigSpec`, `AllowedHostsSpec`); `@Html` audit (`WikiLinksSpec`, G5); CSRF on every
POST (`SignOutAndSwitchSpec`, `ThemeSwitchSpec`); mutation and view authorization
(`MutationAuthorizationSpec`, `CommentAuthorizationSpec`, `OwnVotesSpec`,
`CoiAuthorizationSpec`; G2 `RouteAuthSpec`, G2b `SignedOutSpec`); error pages with keys only
(`ErrorHandlerSpec`); denial logs by account id (`SecuredSpec`). Details:
[2026-11-02-rereview-previous.md](2026-11-02-rereview-previous.md).

## Updating this register

- **Who:** the implementer of a batch, in the same commit as the fix: status, batch, guard,
  last checked. The orchestrator updates the header line's merge state when it merges.
- **New items:** a review or incident adds a row (next free N/P number, or the batch's own ID)
  with severity and owner; the dated review document keeps the details.
- **When the checklist applies:** every change of the kinds listed in
  [review-checklist.md](review-checklist.md) (the PR template's boxes).
- **Re-review cadence (decision 2):** a full re-review before each contest season (WLE in
  spring, WLM in late summer) and after any change to authentication, roles or sessions; it
  re-checks every row and sets "last checked".
- **Release gate:** the checklist's release gate; its results are recorded with the release.
