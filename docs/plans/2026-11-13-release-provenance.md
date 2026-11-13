# Release provenance: CI-built releases and reproducible builds

**Status:** plan, 2026-11-13, for the maintainer's decisions (§7). Nothing is implemented. Two
plans: **A**, GitHub Actions builds the release ZIP from a tag, attests it, and the server's
`sudo wlxjury-release deploy 26.11.6` fetches and verifies it; **B**, the same commit gives a
byte-identical ZIP on any machine, so anyone can rebuild and compare. Recommended order: A, then
B (§6).

## 1. Today

1. The orchestrator cuts `release/26.11.N` from the top of the stack (e.g. `release/26.11.4` =
   `stack/123…` + "Release 26.11.4"; not on master), sets `version := "26.11.N"`, runs the full
   suite locally (under the shared sbt lock: the 26.11.5 suite holds it for up to an hour as this
   is written) and builds on Windows: `sbt -Dwlxjury.buildId=26.11.N dist`.
2. The ZIP is rehearsed on a local production copy, `scp`'d to `~ilya/` on the server, and
   deployed with `sudo wlxjury-release deploy <≥16 hex of its SHA-256>` (the tool finds the
   `wlxjury-*.zip` in the current directory with that hash; `deploy()` and `find_zip` in
   `scripts/deployment/wlxjury-release.sh`, docs/deployment.md "The hash").
3. The hash proves that the server has the ZIP that was rehearsed. It says nothing about what the
   ZIP was built from or by what: it is the hash of a build on one PC.

## 2. Findings

| # | Finding | Evidence |
|---|---|---|
| F1 | **The repository is public** (`gh repo view UkrainianWiki/wlxjury --json visibility` → `PUBLIC`). Release assets download without a token; artifact attestations are available (private repos need GitHub Enterprise Cloud for them). | gh, 2026-11-13 |
| F2 | **The ZIP holds nothing private.** `wlxjury-26.11.4.zip` (109 MB, 304 entries): `bin/`, `conf/` (application.conf, routes, messages.*, logback.xml, KOATUU, monit, 100 migrations), `lib/` (185 jars: Maven Central, scalawiki, wlxjury's own and assets jars), `README.md`, `wlxjury-build.json`. `application.conf` takes every secret from the environment (`${?PLAY_HTTP_SECRET_KEY}`, `${WLXJURY_DB_PASSWORD}`, `${?WLXJURY_GOOGLE_CLIENT_SECRET}`, `${?WLXJURY_SETUP_TOKEN}`, `${?COMMONS_*}`); its literal default `play.http.secret.key = "changeme"` and the host names are in the public repo anyway. No keys, test data or CSVs. Server setup files are already filtered out (build.sbt). | zip listing of `C:/scala/wlxjury-release-2611-4/target/universal/wlxjury-26.11.4.zip` |
| F3 | **The server can reach GitHub and Sigstore**: from production (Ubuntu 22.04.5, Python 3.10, curl, unzip, openssl), `api.github.com` and `github.com` 200, `objects.githubusercontent.com` / `release-assets.githubusercontent.com` answer (404 at `/`), `tuf-repo-cdn.sigstore.dev` 200. **Neither `gh` nor `cosign` is installed.** | unprivileged `ssh ilya@wlm.org.ua`, 2026-11-13 |
| F4 | **Releases are not from master.** `origin/release/26.11.4` is not an ancestor of `origin/master`; master's ruleset has deletion, non-fast-forward, linear history and "PR required" (0 approvals) but **no required status checks**; there is **no tag ruleset**, no GitHub Release, and the only tags are `0.8`–`0.10`. CI (`ci.yml`, `deployment.yml`) runs on PRs and on pushes to master/followups, **not on `release/*` pushes**: the release commit itself is tested only by the local suite. | gh api rulesets; git |
| F5 | **The agents' account is an admin.** `ukwikidev` (the account the agents' `gh` and git use) and `intracer`, `onca26` are repository admins; anything that relies on "only the maintainer can tag" must use a role or an approval, not trust in the token on the PC. | gh api collaborators |
| F6 | **Local release ZIPs may not contain the pinned scalawiki.** Locally scalawiki comes from `~/.ivy2/local` (`scalawiki-core_2.13.jar` dated 2026-10-28, origin unknown); CI builds the pin in `project/scalawiki.sha` (`25951dbe…`, pinned 2026-11-04) from source. A CI build removes this gap. | file dates, `.github/actions/scalawiki` |
| F7 | **What differs between builds today** (read from the 26.11.4 ZIP and the build; the double-build experiment was skipped because the release-26.11.5 suite held the sbt lock, see §5 B0): | |
| | a. **Outer ZIP entry times are the build time** (3 distinct stamps, 2026-11-13 12:48:58–12:49:02). sbt-native-packager 1.13.0's `ZipHelper` references `SOURCE_DATE_EPOCH` (string in the class file): setting it should fix them (to confirm in B0). | |
| | b. **Entry order is not sorted** (mapping order, possibly filesystem-dependent). | |
| | c. **Permissions: every entry is 0755** on the Windows build; a Linux build carries real modes. (`deploy` resets modes after unpacking, so this is bytes only.) | |
| | d. **Line endings: the Windows checkout has `core.autocrlf=true`**, so `conf/application.conf`, `routes`, `messages.*`, the migrations and `public/` files are CRLF in the release, and Twirl templates compile CRLF into their string constants; a Linux checkout gives LF. Flyway's checksum is computed line by line, so migrations should validate either way (confirm on the rehearsal copy once). | |
| | e. **Inner jars are already normalized**: wlxjury's, assets and scalawiki jars have every entry at 2010-01-01 (sbt ≥ 1.4 default). Generated Twirl/routes sources carry relative `SOURCE:` paths and a content `HASH`, no date. No BuildInfo plugin; `wlxjury-build.json` depends only on version, commit and migrations. npm is not in the `dist` path (`jsTest` only). | |
| | f. **Toolchain**: local JDK 17.0.9 vs CI's Temurin 17 from the runner's tool cache (unpinned patch); sbt 1.9.6 and Scala 2.13.17 are pinned. Class files rarely depend on the JDK patch with `-release 17`, but this must be pinned for a byte-identical promise. | |

## 3. Threat model

| Threat | Today (hash by message) | A: CI build + attestation | B: reproducible + rebuild | A + B |
|---|---|---|---|---|
| Wrong, stale or truncated ZIP | caught | caught | — | caught |
| ZIP swapped on the server (`~ilya`, scp path) | caught if the hash came by another channel | caught without any human-pasted hash: the attestation binds the digest to repo, workflow, tag and commit | — | caught |
| **Compromised builder PC** injecting into the build | **not caught** (the PC also produces the hash) | caught for build-time injection (the PC no longer builds); a malicious *commit* pushed from it is not, but is public and goes through a PR | the rebuild on a second machine disagrees | caught, except a malicious commit |
| Compromised CI build (poisoned cache, compromised action or runner) | n/a | **not caught**: GitHub attests whatever it built | caught: CI's ZIP ≠ an independent rebuild | caught |
| Agent token / `ukwikidev` account misused (tag pushed, workflow changed) | could build anything locally anyway | limited by a tag ruleset and a `release` environment that only `intracer` approves (D3) | — | same |
| **Compromised maintainer account** (`intracer`) | not caught | not caught: it can merge and approve; left: public commit history, Rekor transparency log, hardware-key 2FA | not caught (the source itself is malicious) | not caught |
| Outsiders checking what runs at jury.wle.org.ua | impossible | provenance: commit + workflow per release | anyone can rebuild and compare | both |

## 4. Plan A: CI-built releases

### Where releases come from (the maintainer's requirement)

**End state:** a release is a **tag on a commit of master**. The release commit (`version :=
"26.12"`, changelog) reaches master through a PR like any change; the tag (`26.12`, `26.12.1`;
no `v`, as the old `0.8`–`0.10` and as `deploy` takes it) is pushed on that commit. The workflow
refuses anything else: it runs only on `push: tags` matching the version form, and its first job
checks `git merge-base --is-ancestor $GITHUB_SHA origin/master` and that `build.sbt`'s version
equals the tag. Repository rules make the check meaningful: a tag ruleset on `refs/tags/[0-9]*`
(creation, update and deletion restricted, bypass: the admin role), required status checks on
master (`sbt test (mariadb:10.6.22)` and the deployment tests), and the publishing job in a
`release` environment whose required reviewer is `intracer` with "prevent self-review" (F5).

**Transition, until the stack (#184–#313) merges to master after CodeRabbit's review:** a
protected long-lived branch **`release-train`** (ruleset: PR required, no force push, no
deletion, the same required checks). Each release is a PR "Release train 26.11.6" merging the
stack's top into `release-train`, then the release commit (version, changelog) by PR, then the
tag. The guard accepts commits reachable from master **or** `release-train`. When the stack
merges, `release-train` (stack merges + release commits only) merges into master, the guard
drops it, and the branch is deleted. This keeps today's "ship the reviewed-by-us stack before
CodeRabbit" decision, but every release commit is on a protected branch, tested in CI and
recorded; feature branches (`stack/*`, unprotected, force-pushed by agents) are never a release
source. The `release/26.11.N` branches stop being cut.

### The release workflow (`.github/workflows/release.yml`)

On `push: tags: ['[0-9][0-9].[0-9]*']`, `permissions: contents: read` at the top:
1. **guard**: tag form `^[0-9]{2}\.[0-9]{2}(\.[0-9]+)?$`; reachable from master (or
   `release-train`); `build.sbt` version = tag; the tree is the tag's commit.
2. **test**: `ci.yml` as a reusable workflow (`workflow_call` added) on that exact commit: the
   full suite on 10.6. This **replaces the local full suite at the release boundary** (D8): no
   sbt-lock contention, and it tests the release commit itself (F4: today nothing in CI does).
3. **build** (`id-token: write`, `attestations: write`): clean `ubuntu-24.04` runner, **no
   caches** (sbt, Coursier and scalawiki's cache restored from other refs are a poisoning path;
   costs a few minutes), Temurin pinned to an exact 17.0.x, scalawiki built from
   `project/scalawiki.sha`, `SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)`, `sbt
   -Dwlxjury.buildId=$TAG dist` (ID `26.11.6-<sha12>` per `BuildIdentity`), then
   `sha256sum`, `actions/attest-build-provenance` (`subject-path` the ZIP; its `bundle-path`
   saved as `wlxjury-$TAG.zip.sigstore.json`), upload as an Actions artifact for the next jobs.
4. **package tests**: `deployment.yml`'s release job (restore + `test_release_script` in the
   Ubuntu/MariaDB image) on **that** ZIP (input: the artifact name) instead of its own `dist`;
   plus the server's exact verification command (D6) run on the real bundle, so the tool's
   flags can't drift from what CI produces.
5. **publish** (`contents: write`, environment `release`): a **draft** GitHub Release `$TAG`
   with `wlxjury-$TAG.zip`, `.sha256` and `.sigstore.json`, notes linking the changelog.

Actions artifacts serve only the hand-off between jobs: they need a token to download even on a
public repo, are wrapped in another ZIP and expire (≤ 90 days). Release assets are public,
permanent, and with **immutable releases** enabled (repository setting; if offered) the assets
and the tag can't change after publishing (D4, D13).

### Rehearsal (release agent)

`gh release download 26.11.6 -p 'wlxjury-26.11.6.zip*'` (draft: the maintainer's token),
`gh attestation verify wlxjury-26.11.6.zip --repo UkrainianWiki/wlxjury --signer-workflow
UkrainianWiki/wlxjury/.github/workflows/release.yml --source-ref refs/tags/26.11.6
--deny-self-hosted-runners`, then the usual rehearsal on the production copy with that ZIP
(schema parity, adding people, smoke). Passed: the maintainer publishes the draft (`gh release
edit 26.11.6 --draft=false`). Failed: the draft is deleted and the number is burnt; the fix ships
as 26.11.7 (D10: a rebuilt ZIP under the same name would not be the rehearsed one).

### Release tool: `deploy VERSION`

`sudo wlxjury-release deploy 26.11.6 [--backup] [--allow-running-jobs]`; the argument is a
version (dots) or a hex hash (≥ 16 hex, no dots), so the forms can't be confused.
`deploy [ZIP] SHA256` stays unchanged as the fallback.
1. Before the lock and before anything changes: room check (`MIN_FREE` + the asset's size), then
   download into a **root-only staging folder** (never the current directory) with `curl -fL
   --proto =https --max-time --max-filesize`:
   `https://github.com/UkrainianWiki/wlxjury/releases/download/26.11.6/wlxjury-26.11.6.zip` and
   `.sigstore.json`. Offline variant (D7): if both files are in the current directory, they are
   copied into staging instead of downloading; verified exactly the same way.
2. Verify (D6): `cosign verify-blob-attestation --new-bundle-format --bundle … --type
   slsaprovenance1 --certificate-oidc-issuer https://token.actions.githubusercontent.com
   --certificate-identity
   https://github.com/UkrainianWiki/wlxjury/.github/workflows/release.yml@refs/tags/26.11.6`
   (identity and issuer hard-coded in the tool; the repository may be set in `deployment.json`
   for tests only), then read the predicate with `python3` (JSON only, as today): source
   repository, `refs/tags/26.11.6`, GitHub-hosted runner, the commit.
3. The manifest's `version` must be `26.11.6` and its `build` must contain the commit's first 12.
4. Then the existing path with the computed SHA-256 (symlinks, one top folder, schema, jobs,
   capacity, `releases/$build-$sha12`). The operation record gains `source=github-release`,
   `tag`, `commit`, `provenance=verified`; `status` prints them.

**Failure modes:** GitHub or Sigstore unreachable → refuses, nothing changed, prints the
fallback (`deploy ~/wlxjury-26.11.6.zip <SHA-256 from the release page or the rehearsal
record>`, or upload ZIP + bundle and rerun); never falls back silently. Version not found (404,
or still a draft) → "26.11.6 is not a published release". Verification fails, wrong ref or
workflow → refuses and says it may be tampering; the staging copy is deleted. Manifest
version ≠ tag → refuses. A local `wlxjury-26.11.6.zip` that differs from the release → it is
never used in the download form; in the offline form it fails verification. Two ZIPs in the
directory → irrelevant to `deploy VERSION` (named file); `find_zip`'s refusal stays for the hash
form. Same release already deployed → the existing "never overwrite a release" check.

**Tests** (`scripts/deployment/test_release_script.py`, the unmodified script, real MariaDB): a
local HTTP server as the release host (`deployment.json` base URL) and a stub `cosign` on `PATH`
that checks the identity and issuer arguments; cases: success and its record; 404; host
unreachable (fallback text, nothing changed); verification failure; identity for another tag;
manifest version mismatch; offline files; insufficient space; the hash form unchanged.
shellcheck as today. The real `cosign` against a real bundle runs in `release.yml` step 4.

**Server:** `cosign` (one static binary, pinned version and SHA-256) installed by the
wlxjury-infra playbook, its Sigstore trusted root cached for root (F3: the TUF CDN is reachable).

### Docs

docs/deployment.md: "Build and rehearse" becomes "Release build (CI)" plus the local `sbt dist`
for development only; "Routine deployment" leads with `deploy VERSION`; "The hash" becomes the
fallback; the release checklist and recovery runbook name the release page. workflow practices
§7.4: tag on master (`release-train` in the transition), no local release builds. CLAUDE.md: the
release boundary's full suite is CI's.

### Phases and effort (agent time, before reviews)

| Phase | Work | Effort |
|---|---|---|
| A0 | Repository settings (maintainer): tag ruleset, required checks on master and `release-train`, `release` environment, immutable releases | 0.5 h |
| A1 | `release.yml`, `workflow_call` in `ci.yml` / `deployment.yml`, a `workflow_dispatch` dry run that builds and attests but doesn't publish | 1–1.5 d |
| A2 | `deploy VERSION` in the release tool, tests, shellcheck | 1.5 d |
| A3 | cosign on the server via wlxjury-infra; verify a dry-run bundle there (maintainer's sudo) | 0.5 d |
| A4 | Docs, runbook, practices | 0.5 d |
| A5 | First CI release; on the server deploy with `deploy VERSION` and compare the printed SHA-256 with the rehearsal's | with the release |

About 4–4.5 days.

## 5. Plan B: reproducible builds

Goal: the same tag → the same ZIP bytes from CI, from the maintainer's PC and from anyone.

| Phase | Work | Effort |
|---|---|---|
| B0 | Experiment (needs the sbt lock: take it like `agent-check.py --lock`): build one commit twice on Windows, twice in a Linux container, once in CI; compare with `diffoscope` (container). Confirms F7 a–f and finds anything reading did not (jar entry order, Scala/javac output across JDK patches, asset digests). | 0.5 d |
| B1 | Normalize: `SOURCE_DATE_EPOCH` = commit time (CI env and the rebuild script); `Universal / mappings` sorted by path; modes fixed by building on Linux only; line endings fixed by checking out on Linux (no repo-wide renormalization, D12); exact JDK pinned by image digest (Temurin 17 **JDK**, same as the Docker image's JRE base). Only if B0 shows sbt's own packaging still varies: `sbt-reproducible-builds`. | 1 d |
| B2 | `scripts/release/rebuild.sh 26.11.6`: in the pinned container, a fresh clone of the tag, scalawiki from its pin, `sbt dist` with the same flags, then `sha256sum` against the release's `.sha256`; on mismatch a diffoscope report. `release.yml` builds a second time in that container and refuses to publish when the two differ. | 1 d |
| B3 | Docs: "Verify a release" (one command), the release notes link it | 0.5 d |

About 3 days. **Composition with A:** CI attests the ZIP (who built it, from what); the second
CI build and the rehearsal agent's local container rebuild on the maintainer's PC (a different
machine and network) confirm the bytes follow from the commit before the draft is published;
outsiders run the same script. The server keeps checking the attestation only.

## 6. Recommendation

1. **A first.** It removes the builder PC from the trust path (§3), gives `deploy 26.11.6`,
   tests the release commit itself in CI, ends the hour-long local suite under the shared lock,
   and makes releases come only from protected branches. Fold in B's cheap parts now (Linux
   build, `SOURCE_DATE_EPOCH`, sorted mappings, pinned JDK) since A1 writes that build job.
2. **Then B** (B0 experiment first), which adds the only check against a compromised CI build
   and lets anyone verify. A compromised maintainer account stays out of reach of both; hardware-
   key 2FA for `intracer` is the cheap mitigation.
3. Transition: `release-train` from the next release; master tags once the stack merges.

## 7. Decisions for the maintainer (one per item; recommendation first)

1. **Release source.** Recommended: end state tags on master commits only; until the stack
   merges, a protected `release-train` branch fed by PRs (guard: reachable from master or
   `release-train`). Alternatives: keep cutting `release/26.11.N` from the stack as an explicit,
   dated exception (the workflow would have to accept unprotected `stack/*` history: weak); or
   no CI releases until the stack merges (delays everything).
2. **Tag name.** Recommended: `26.11.6`, exactly what `deploy` takes (as the old `0.8` tags).
   Alternative: `v26.11.6` (needs a mapping in the tool).
3. **Who can release.** Recommended: tag ruleset (admin bypass) + `release` environment with
   `intracer` as required reviewer and self-review prevented, so an agent-pushed tag builds but
   can't publish without the maintainer. Optional: lower `ukwikidev` from admin to maintain.
4. **Where the ZIP lives.** Recommended: draft GitHub Release for the rehearsal, published
   release for the server; Actions artifacts only between jobs. Alternative: Actions artifact
   for the rehearsal (token, 90-day expiry, double ZIP).
5. **Visibility.** Recommended: stay public (attestations available, no server token; the ZIP
   holds nothing private, F2). Private would need Enterprise Cloud for attestations and a
   fine-grained read-only token in `/etc/wlxjury-runtime` (root 0600).
6. **Server verifier.** Recommended: `cosign` (static binary, no GitHub login, pinned by the
   infra playbook). Alternatives: `gh attestation verify` (GitHub's own; the apt repo, and
   whether it needs a token with `--bundle` must be checked); sigstore-python in a venv (more
   dependencies); hand-written curl + openssl verification of a Sigstore bundle (rejected: too
   easy to get wrong).
7. **`deploy VERSION` ZIP source.** Recommended: download each time into root-only staging; an
   explicit offline variant uses ZIP + bundle uploaded to the current directory, verified the
   same; the hash form stays as the manual fallback, never automatic.
8. **Full suite at the release boundary.** Recommended: CI's run on the tagged commit (a
   required job of `release.yml`) replaces the local one; local runs stay for affected specs.
9. **Version.** Recommended: keep the release commit setting `version := "26.11.6"`; CI checks
   it equals the tag. Alternative: derive it from the tag in CI (a rebuild would need the same
   override, and the commit would still say SNAPSHOT).
10. **Failed rehearsal.** Recommended: delete the draft and burn the number (26.11.7 next);
    the changelog notes it. Alternative: re-tag the same number (the deployed ZIP would not be
    the rehearsed one unless B holds).
11. **Caches in the release build.** Recommended: none (a few extra minutes). Alternative: the
    CI caches (faster; a cache poisoned from another ref ends up in an attested release).
12. **Line endings for B.** Recommended: release builds only on Linux checkouts (CI, the rebuild
    container), no repo-wide renormalization. Alternative: `.gitattributes eol=lf` for
    `app conf public` and a renormalizing commit (large diff, conflicts with the open stack).
13. **Immutable releases.** Recommended: enable, if the repository offers it.
14. **Order.** Recommended: A (with B's cheap normalization), then B. Alternative: B first
    (verifiable builds, but the PC stays the builder until A).
