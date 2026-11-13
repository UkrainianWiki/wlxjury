# Release provenance: CI-built releases and reproducible builds (v2)

**Status:** plan, 2026-11-13, for the maintainer's decisions (§8). Nothing is implemented. Two
plans: **A**, GitHub Actions builds the release ZIP from a tag and attests it, and the server's
`sudo wlxjury-release deploy 26.11.6` fetches it and verifies it before deploying; **B**, the
same commit gives a byte-identical ZIP on any machine, so anyone can rebuild it and compare.
Recommended order: A, then B (§7).

**Revisions:**
- **v1** (82081463): CI build, attestation, `deploy VERSION` with cosign, `release-train`.
- **v2**, after an independent review. v1 let an agent produce a release the server accepts:
  `ukwikidev` is an admin, so it could get past a ruleset with admin bypass, and the build was
  attested before anyone approved it. A re-run or a re-created tag could produce a second valid
  attestation, and publishing wasn't gated. v2 makes these changes:
  - `ukwikidev` loses admin. Only `intracer` creates release tags, and tags can't be moved or
    deleted.
  - Only the first push run of a tag attests.
  - A gated `publish` job comes after the rehearsal.
  - The server itself checks publication, the run and its `publish` job, and ancestry.
  - It also adds: pinning cosign and its trust root, the offline form taking the hash, the end of
    `release-train` (it can't merge into master), green CI as a precondition, protecting the
    scalawiki pin, building in a pinned container from A1, and the revised effort (§4, §8).

## 1. Today

1. The orchestrator cuts `release/26.11.N` from the top of the stack (`release/26.11.4` is
   `stack/123…` plus "Release 26.11.4", not on master) and sets `version := "26.11.N"`.
2. It runs the full suite locally, under the shared sbt lock. As this was written, the 26.11.5
   suite had held the lock for up to an hour.
3. It builds on Windows: `sbt -Dwlxjury.buildId=26.11.N dist`.
4. The ZIP is rehearsed on a local production copy and `scp`'d to `~ilya/`.
5. `sudo wlxjury-release deploy <≥16 hex of its SHA-256>` deploys it (`deploy()`, `find_zip`;
   docs/deployment.md, "The hash").

The hash proves that the server has the ZIP that was rehearsed. It says nothing about what the
ZIP was built from, or by what: it is the hash of a build on one PC.

## 2. Findings

| # | Finding | Evidence |
|---|---|---|
| F1 | **The repository is public.** Release assets and the Actions API (runs, jobs, compare) can be read without a token. Artifact attestations are available; private repositories need Enterprise Cloud for them. | `gh repo view --json visibility` |
| F2 | **The ZIP holds nothing private.** `wlxjury-26.11.4.zip` is 109 MB with 304 entries: `bin/`, `conf/` (application.conf, routes, messages.\*, logback.xml, KOATUU, monit, 100 migrations), `lib/` (185 jars), `README.md` and `wlxjury-build.json`. Every secret comes from the environment (`${?PLAY_HTTP_SECRET_KEY}`, `${WLXJURY_DB_PASSWORD}`, `${?WLXJURY_GOOGLE_CLIENT_SECRET}`, `${?WLXJURY_SETUP_TOKEN}`, `${?COMMONS_*}`). The default `"changeme"` key and the host names are public anyway. There are no keys and no test data. | listing of `C:/scala/wlxjury-release-2611-4/target/universal/wlxjury-26.11.4.zip` |
| F3 | **The server can reach GitHub and Sigstore.** Production runs Ubuntu 22.04.5 with Python 3.10, curl, unzip and openssl. `api.github.com` and `github.com` answer 200, the asset hosts answer, and `tuf-repo-cdn.sigstore.dev` answers 200. **Neither `gh` nor `cosign` is installed.** | unprivileged `ssh ilya@wlm.org.ua` |
| F4 | **Releases are not built from master, and CI never tests the release commit.** `release/26.11.4` is not an ancestor of master. Master's ruleset covers deletion, force pushes, linear history and "PR required" with 0 approvals, but has **no required checks**. There is **no tag ruleset** and no GitHub Release; the only tags are `0.8`–`0.10`. CI doesn't run on `release/*` pushes. | rulesets API, git |
| F5 | **`ukwikidev` (the agents' account) and `onca26` are repository admins**, as is `intracer`. Admins can edit rulesets and environments, so no protection holds against an admin account. | collaborators API |
| F6 | **Local ZIPs may not contain the pinned scalawiki.** The jar in `~/.ivy2/local` dates from 2026-10-28; CI builds the pin `25951dbe` (pinned 2026-11-04). That commit is reachable only from scalawiki's unprotected `page-categories` branch. `.github/actions/scalawiki` always restores `actions/cache`. | file dates, action source |
| F7 | **CI is red now.** Compatibility lint has failed on every stack run since about 10:33 UTC 2026-11-13. A release workflow that requires CI fails until that is fixed. | review |
| F8 | **What differs between two builds**, read from the 26.11.4 ZIP. The double-build experiment was skipped because the 26.11.5 suite held the sbt lock; it is B0. | |
| | (a) Outer ZIP entries carry the build time. native-packager 1.13.0's `ZipHelper` refers to `SOURCE_DATE_EPOCH`, to be confirmed. | |
| | (b) Entries are not sorted. | |
| | (c) The Windows build gives every entry mode 0755. | |
| | (d) The Windows checkout has `core.autocrlf=true`, so conf, routes, messages, migrations, public files and the compiled Twirl constants are CRLF. Flyway checksums lines, so migrations should validate either way; to be confirmed once. | |
| | (e) Inner jars are already normalized (entries at 2010-01-01). Generated sources carry relative paths and no dates. There is no BuildInfo, and npm is not in `dist`. | |
| | (f) The JDK patch is not pinned (local 17.0.9; CI uses the runner's Temurin 17). sbt 1.9.6 and Scala 2.13.17 are pinned. | |

## 3. Threat model

These are the claims for plan A as designed in §4, which assumes D4–D7 are taken.

| Threat | Today (hash by message) | A | B | A + B |
|---|---|---|---|---|
| Wrong, stale or truncated ZIP | caught | caught | — | caught |
| ZIP swapped on the server or in transfer | caught if the hash came by another channel | caught: attestation, release asset digest | — | caught |
| **Builder PC compromised** and injecting into the build | **not caught** | caught (the PC doesn't build). A malicious commit still needs `intracer`'s approval to reach master or `release-train` | a rebuild elsewhere disagrees | caught, unless `intracer` approves malicious source |
| **Agent account `ukwikidev` misused** (token on the PC): tag, re-run, dispatch, an edited workflow, a draft or a publish | n/a (the PC builds anyway) | caught. It can't create, move or delete release tags. A re-run or dispatch doesn't attest. Its own branches' workflows sign as another identity. The server requires the tag's single push run, that run's `publish` job approved by `intracer`, and the commit on master or `release-train` | — | caught |
| CI build compromised (action, runner, poisoned cache) | n/a | **not caught**: GitHub attests whatever it built; no caches narrows this | caught: CI's ZIP ≠ an independent rebuild | caught |
| **Maintainer account `intracer` compromised** | not caught | not caught: it holds every approval. What remains is the public commit history, the Rekor log and hardware-key 2FA | not caught (the source is malicious) | not caught |
| An outsider checking what runs | impossible | provenance: commit and run | rebuild and compare | both |

## 4. Plan A: CI-built releases

### 4.1 Where releases come from, and who can make one

- **End state.** A release is a **tag `26.11.6` on a commit of master**. The release commit
  (version, changelog) reaches master by PR. The tag has no `v`, like `0.8`–`0.10`, and is what
  `deploy` takes.
- **Transition, until the stack (#184–#313) merges after CodeRabbit's review.** A protected
  branch **`release-train`**. Each release is a PR that merges the stack's top into it, then the
  release-commit PR, then the tag. `stack/*` branches (unprotected, force-pushed by agents) are
  never a release source, and `release/26.11.N` branches are no longer cut.
- **End of `release-train`** (M1). It can't be merged into master: master requires linear history
  and `release-train` holds merge commits. Once the stack lands on master, the release commits'
  content (versions, changelog headings) is replayed onto master by an ordinary PR. A last
  ruleset then makes `release-train` read-only (no updates, no deletion), and its tags keep its
  commits. From then on, the server's ancestry check accepts master only.
- **Repository rules (A0):**
  1. **`ukwikidev` goes from admin to write.** This is required: an admin can switch off any of
     the rules below. `onca26` is a separate decision (D5).
  2. **Release tag ruleset** on `refs/tags/[0-9]*`. Update and deletion are restricted with **no**
     bypass, so a tag is never moved or re-created. Creation is restricted, and its bypass actor
     is a team `release-managers` containing `intracer` only, not the admin role.
  3. **Master and `release-train`:** a PR is required with an approving review from a code owner
     (`CODEOWNERS`: `* @intracer`). A required check `CI ok` aggregates the jobs, so its name
     doesn't change with a MariaDB patch in the matrix name (L7).
  4. **Environment `release`:** required reviewer `intracer`; administrators can't bypass it
     (`can_admins_bypass: false`); the deployment policy allows tags `[0-9]*` only. Self-review
     is **not** prevented: `intracer` both tags and approves, as separate deliberate steps.
  5. **Immutable releases** are turned on (D20).

### 4.2 The release workflow (`.github/workflows/release.yml`)

It runs on `push` of tags `[0-9][0-9].[0-9]*`, with `contents: read` at the top.

1. **guard**:
   - the tag has the form `^[0-9]{2}\.[0-9]{2}(\.[0-9]+)?$`;
   - `build.sbt`'s version equals the tag;
   - the commit is reachable from master or `release-train`;
   - `project/scalawiki.sha` equals the commit of the protected scalawiki tag that names it (D18).
2. **test**: `ci.yml` as a reusable workflow on this commit, which must pass (`CI ok`; D13). It
   replaces the local full suite at the release boundary (D14).
3. **build** (`id-token: write`, `attestations: write`), on a clean runner **inside the pinned
   build container**. That is the Temurin 17 JDK image pinned by digest, so there is one JDK pin
   (L5, shared with B):
   - no caches; the scalawiki action gets a `cache: false` input (M6);
   - scalawiki is built from its pin; its SHA goes into `wlxjury-build.json` (`"scalawiki"`);
   - `SOURCE_DATE_EPOCH` is the commit time, and `sbt -Dwlxjury.buildId=$TAG dist`;
   - `sha256sum`, then upload as artifact `wlxjury-$TAG` (retention 30 days);
   - `actions/attest-build-provenance` runs **only if `github.event_name == 'push'` and
     `github.run_attempt == 1`**. Its bundle is saved as `wlxjury-$TAG.zip.sigstore.json`.

   Because a tag can't be re-created and a re-run is attempt 2 or later, **each tag has at most
   one attested ZIP**: the one the rehearsal then verifies (M2, L1).
4. **package tests**:
   - `deployment.yml`'s release job (restore and `test_release_script`) on **this** ZIP;
   - the server's exact cosign command, with the shared pin (D11), on this bundle.
5. **publish**, in environment `release` (`contents: write`). It waits for `intracer`'s approval
   after the rehearsal, for up to 30 days.
   - It checks that the artifact's SHA-256 equals the attested subject.
   - It creates the release with the ZIP, `.sha256` and `.sigstore.json`, then publishes it,
     which makes it immutable.
   - Its summary shows the SHA-256. The runbook says to approve only if that matches the
     rehearsal record (M2).

CI wall time per release is about 30–40 minutes: the suite plus about 10 minutes of build and
5 of package tests. Approval and rehearsal time come on top (L10).

### 4.3 Rehearsal (release agent)

1. Download the run's artifact: `gh run download <run> -n wlxjury-26.11.6`.
2. Verify it:
   `gh attestation verify wlxjury-26.11.6.zip --repo UkrainianWiki/wlxjury --signer-workflow UkrainianWiki/wlxjury/.github/workflows/release.yml --source-ref refs/tags/26.11.6 --deny-self-hosted-runners`.
3. Rehearse on the production copy as today, and record the SHA-256.
4. The maintainer approves `publish` (§4.2, step 5).

If the rehearsal fails, the `publish` approval is rejected and the number is burnt (D16). That
tag's attested ZIP never gets a successful `publish` job, so the server refuses it even if
someone attaches it to a release by hand.

There are no drafts: a draft can be read and edited by any writer (L4).

### 4.4 Release tool: `deploy VERSION [SHA256]`

`sudo wlxjury-release deploy 26.11.6 [SHA256] [--backup] [--allow-running-jobs]`.

- The argument is a version (it has dots) or a hash (16–64 hex characters, no dots).
- An optional hash prefix is accepted and checked; it is not required (D7).
- `deploy [ZIP] SHA256` is unchanged, and it is **the** offline form (D12).

Everything up to the maintenance flag changes nothing. **The lock is taken first**, because
`prune` deletes `.staging.*` folders (L2).

1. `GET https://api.github.com/repos/UkrainianWiki/wlxjury/releases/tags/26.11.6`. The release
   must be published (drafts aren't visible without a token) and `immutable`, with assets
   `wlxjury-26.11.6.zip` and `.sigstore.json`. Their `digest` is used where the API gives it.
2. Download both into root-only staging, after the room check:
   `curl -fL --proto =https --proto-redir =https --max-time … --max-filesize …` (L3).
3. Verify with
   `/usr/local/bin/cosign verify-blob-attestation --new-bundle-format --bundle … --type slsaprovenance1`
   and these options:
   - `--certificate-oidc-issuer https://token.actions.githubusercontent.com`
   - `--certificate-identity https://github.com/UkrainianWiki/wlxjury/.github/workflows/release.yml@refs/tags/26.11.6`
   - `--certificate-github-workflow-trigger push`
   - `--certificate-github-workflow-repository UkrainianWiki/wlxjury`
   - `TUF_ROOT=/var/lib/wlxjury-release/sigstore`, which is root-only and not sudo's `HOME` (M4).
4. From the provenance (python3, JSON only): the commit, the run ID, and a GitHub-hosted runner.
5. `GET …/actions/runs/{id}`: `event == push`, `path == .github/workflows/release.yml`,
   `head_sha ==` the commit. `GET …/actions/runs/{id}/jobs?filter=all`: the job `publish`
   concluded `success`. This is `intracer`'s post-rehearsal approval of this very build.
6. `GET …/compare/master...{commit}`: `status` is `identical` or `behind`, meaning the commit is
   on master. During the transition, `release-train` is also accepted.
7. The manifest's `version` is the tag, and `build` contains the commit's first 12 characters.
   If a hash was given, it matches.
8. Then the existing path continues with the computed SHA-256. The record gains `source`, `tag`,
   `commit`, `run` and `provenance=verified`, and `deploy` and `status` print them.

The repository, workflow, issuer and trigger are hard-coded. `deployment.json` may only change
the base URLs, for tests (L8).

**Failure modes.** Every one of these refuses with nothing changed, and none of them falls back
to the hash form silently.

- GitHub, its API or Sigstore is unreachable, or the API is rate-limited (60 unauthenticated
  requests per hour; `deploy` makes about 5). The message prints the hash form as the fallback,
  with the hash from the release page or the rehearsal record.
- The version isn't published: "26.11.6 is not a published release".
- Verification fails, or the ref, trigger, run, `publish` job or ancestry is wrong. The message
  says this may be tampering, and the staging copy is deleted.
- The manifest or the given hash doesn't match.
- The release folder already exists: the existing "never overwrite" check.
- A local `wlxjury-26.11.6.zip` is never read by the version form.

**Tests.** `test_release_script.py` runs the unmodified script against a real MariaDB.

- A local HTTPS server with a test CA in the image's trust store stands in for the release host and the API (base URLs in `deployment.json`), so `--proto =https` stays on.
- A stub at `/usr/local/bin/cosign` in the test image checks its arguments.
- Cases: success and its record; the optional hash, matching and not; 404 or draft; unreachable;
  rate-limited; verification failure; wrong ref or trigger; no successful `publish` job; a run
  for another commit; a commit not on master or `release-train`; manifest mismatch; no room;
  prune and deploy under the lock; the hash form unchanged.
- shellcheck.
- The real cosign runs in `release.yml`, step 4.

**cosign and trust material (M4, D11).**
- One pin file, `scripts/deployment/cosign.pin`, holds the version and the linux-amd64 SHA-256.
- CI step 4 installs from it, and so does the wlxjury-infra playbook, which reads it from the
  reviewed checkout. CI fails if the two differ.
- A bump follows dependency practices: the release must be at least 14 days old, pinned by
  checksum, and land as a PR.
- Sigstore's trust material is updated by cosign's TUF client in `TUF_ROOT`.
- The offline form needs no cosign.

### 4.5 Docs

- **docs/deployment.md:**
  - "Release build (CI)": tag, approvals, rehearsal from the artifact.
  - `deploy VERSION` first, then the hash form as the fallback and the offline form.
  - What `deploy` checks.
  - The checklist and the runbook.
- **workflow practices §7.4:** releases from master (`release-train` in the transition), no local
  release builds.
- **CLAUDE.md:** the release boundary's full suite is CI's.

### 4.6 Phases and effort (agent time, before reviews)

| Phase | Work | Effort |
|---|---|---|
| A0 | Settings (maintainer): roles, the `release-managers` team, tag rulesets, CODEOWNERS and required `CI ok`, environment `release`, immutable releases, `release-train` | 1 h |
| A1 | Fix the Compatibility lint (F7); the `CI ok` aggregate; `release.yml` in the pinned container; `workflow_call` for `ci.yml` and `deployment.yml`; the scalawiki `cache: false` input and its SHA in the manifest; a protected scalawiki tag and its guard; a dry run (`workflow_dispatch`: builds, doesn't attest or publish) | 2.5–3 d |
| A2 | `deploy VERSION [SHA]`: API checks, cosign, record, tests, shellcheck | 2.5–3 d |
| A3 | `cosign.pin`, the infra playbook, `TUF_ROOT`; a verify of a real bundle on the server (maintainer's sudo) | 0.5–1 d |
| A4 | Docs, runbook, practices | 0.5–1 d |
| A5 | First CI release: `deploy VERSION SHA16` with the rehearsal's hash as a cross-check | with the release |

The total is **6–8 days**.

## 5. Plan B: reproducible builds

Goal: the same tag gives the same ZIP bytes from CI, from the maintainer's PC and from anyone.

| Phase | Work | Effort |
|---|---|---|
| B0 | Experiment (take the sbt lock as `agent-check.py --lock` does): one commit built twice on Windows, twice in the pinned container, once in CI; `diffoscope` in a container. Confirms F8 and finds what reading missed (jar entry order, compiler output, asset digests). | 0.5 d |
| B1 | Normalize: sort `Universal / mappings`; `SOURCE_DATE_EPOCH` (A1 already sets it); `umask 022` and fixed modes (644, 755 for `bin/*`) in the mappings or a ZIP post-process (L6); Linux checkouts only, so LF (D19). `sbt-reproducible-builds` only if B0 shows sbt's packaging still varies. | 1 d |
| B2 | `scripts/release/rebuild.sh 26.11.6`: the pinned container (A1's), a fresh clone of the tag, scalawiki from its tag, the same flags, then compare with the release's `.sha256`, with a diffoscope report on a mismatch. `release.yml` builds twice (on the runner and in the container, or two runners) and refuses to publish if they differ. | 0.5–1 d |
| B3 | Docs: "Verify a release" (one command), linked from the release notes | 0.5 d |

The total is about **2.5–3 days**.

**How B fits with A.** A says who built the ZIP and from what. In B, CI's second build and the
rehearsal agent's rebuild on the maintainer's PC (another machine and network) confirm that the
bytes follow from the commit before `publish` is approved. Outsiders run the same script. The
server keeps checking only A's evidence.

## 6. Precondition

CI has to be green (F7). `release.yml` requires `CI ok` on the release commit, so a release is
impossible while Compatibility lint fails. A1 fixes it first.

## 7. Recommendation

1. **A0 now**, since it is cheap and closes F5 whatever else is decided. Then **A1–A4**. This
   takes the PC out of the build and makes the release commit tested in CI. Releases then come
   only from protected branches through `intracer`'s approvals, and the server verifies all of
   that itself.
2. **B** afterwards, starting with B0. It is the only check against a compromised CI build, and
   it lets anyone verify a release.
3. **Hardware-key 2FA for `intracer`**: the one account both plans have to trust.

## 8. Decisions for the maintainer

One per item; the recommended option comes first.

1. **Release source.** Tags on master commits. Until the stack merges: a protected
   `release-train` fed by PRs with `intracer`'s code-owner approval. Alternatives: keep cutting
   `release/26.11.N` from the stack as a dated exception (unprotected history: weak), or no CI
   releases until the stack merges.
2. **End of `release-train`.** When the stack lands, replay the release commits onto master by
   PR and make `release-train` read-only (tags keep its commits). Alternatives: a one-off ruleset
   bypass to merge it (it breaks master's linear history), or keep `release-train` as the release
   branch for good (then master is never the release source).
3. **Tag name.** `26.11.6`, exactly what `deploy` takes. Alternative: `v26.11.6`.
4. **`ukwikidev`'s role.** **Write. This is required**: as an admin it can undo every rule.
5. **`onca26`'s role.** Write or maintain, unless they need admin. Alternative: keep admin and
   accept that their account can switch the protections off.
6. **Who pushes release tags.** `intracer` only, through the `release-managers` team as the
   ruleset's bypass actor (not the admin role). Alternative: agents tag too; then the build job
   must also be in the `release` environment (D7's second gate).
7. **Approval gates.** One gate: `publish`, approved after the rehearsal, and checked by the
   server (that run's `publish` succeeded). With tags by `intracer` only (D6) and attestation only
   on push attempt 1, an unapproved build can only follow `intracer`'s own tag push. Alternative:
   two gates, build and publish; required if D6 lets agents tag.
8. **Rehearsed ZIP = deployed ZIP.** One attestation per tag, checked by the server, plus the
   SHA-256 in `publish`'s summary checked against the rehearsal record before approving.
   `deploy VERSION [SHA]` takes an optional hash. Alternative: the hash is required (back to
   pasting it).
9. **Where the ZIP lives.** The run's artifact (30 days) for the rehearsal, and the published
   immutable release for the server. No drafts.
10. **Visibility.** Stay public: attestations and API reads need no token, and the ZIP holds
    nothing private (F2). Private would need Enterprise Cloud and a root-only fine-grained token.
11. **Server verifier and its updates.** cosign at `/usr/local/bin/cosign` from one shared
    `cosign.pin` (CI and infra), `TUF_ROOT` root-only, bumps by PR per dependency practices.
    Alternatives: `gh attestation verify` (an apt repository; whether `--bundle` works without
    login must be checked); sigstore-python; hand-written curl + openssl (rejected).
12. **Offline deploys.** Only the hash form (`deploy ZIP SHA256`) is offline, with no provenance
    claim. Alternative: an offline bundle check with a maintained `trusted_root.json` plus a
    required hash; it still can't check publication or the `publish` job.
13. **Green CI first.** Fix Compatibility lint, then require the whole `CI ok`. Alternative:
    require only the suite's steps (the release would then skip the lint, story and
    query-plan gates).
14. **Full suite at the release boundary.** CI's run on the tagged commit replaces the local
    one; local runs are for affected specs.
15. **Version.** The release commit sets `version := "26.11.6"`, and CI checks that it equals
    the tag. Alternative: derive it from the tag (a rebuild needs the same override, and the
    commit says SNAPSHOT).
16. **Failed rehearsal.** Reject `publish` and burn the number; the next is 26.11.7, and the
    changelog notes it. (Re-tagging is impossible: tags can't be deleted.)
17. **Caches in the release build.** None. Alternative: CI's caches (faster, and a cache
    poisoned from another ref would end up attested).
18. **The scalawiki pin.** Tag the pinned commit in scalawiki (for example `wlxjury-25951dbe`)
    under a tag ruleset; the guard checks that the pin equals that tag's commit; the SHA goes
    into `wlxjury-build.json`. Alternative: first publish a scalawiki release package
    (docs/plans/2026-11-03-ci-and-scalawiki-packages.md), which is longer.
19. **Line endings (B).** Build only on Linux checkouts (CI, the pinned container); no
    renormalization. Alternative: `eol=lf` for `app conf public` and a renormalizing commit (a
    large diff that conflicts with the stack).
20. **Immutable releases.** Turn them on, and have the server require `immutable`.
21. **Order.** A0, A1–A4, then B. Alternative: B first (verifiable builds, but the PC stays the
    builder until A).
