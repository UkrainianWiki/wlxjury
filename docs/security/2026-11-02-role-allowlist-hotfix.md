# Hotfix: contest role allowlist (N1), on top of 0.13-authz-6fa5b56fb021

**What it fixes.** A contest admin could make any account root through the edit form's free-text
`roles` field (`jury,root`): `UserContest.roles` split `user_contest.role` on commas and the
user's roles took root from it. The hotfix (branch `ukwikidev/hotfix-role-allowlist`, from the
deployed `6fa5b56f`) makes a contest role exactly one of `no`, `jury`, `organizer`, `admin`
(`User.ContestRoles`, the roles the deployed users page offers), compared byte for byte:

- the edit form refuses any other value (root aside) with a 400; the controller refuses root
  from anyone but root, before writing anything; `UserService.addToContest` refuses it too;
- `UserContestJdbc.createMembership` / `updateRole` and `User.create` `require` an allowlisted role;
- the reader takes a stored role only if it is allowlisted (else none: the membership is
  inactive, one WARN line on `security.roles` with account and contest id), and never root:
  root comes only from `users.is_root`.

**No migration** (production is at schema 66; the followups branch owns V67–V75; the forward
fix adds V76). The release's `schemaVersion` equals the database's, so `wlxjury-release`
skips the dump (`backup-skipped`) unless `--backup` is given.

## 1. Build the ZIP (done locally; rebuild only if the branch changes)

From a clean checkout of the branch (the build ID is refused with uncommitted or untracked
changes in `app`, `conf`, `public`, `project` or `build.sbt`; the short commit is appended):

```bash
sbt -Dwlxjury.buildId=0.13-authz-rolefix dist
sha256sum target/universal/wlxjury-0.13.zip
```

Its `wlxjury-build.json` names the build `0.13-authz-rolefix-<commit>` and schema 66.

## 2. Deploy (the user runs these; inside `tmux` on the server)

```bash
# upload the ZIP to /srv/releases/, then
readlink /opt/wlxjury/current            # expect .../0.13-authz-6fa5b56fb021
sudo wlxjury-release deploy /srv/releases/wlxjury-0.13-rolefix.zip FULL_LOWERCASE_SHA256
# smoke test (below) against http://127.0.0.1:9000/ while maintenance is on, then
sudo wlxjury-release reopen OPERATION
```

If the smoke test fails: `sudo wlxjury-release restore OPERATION`, then `reopen` (no data is
lost after `backup-skipped`).

## 3. Smoke test (localhost:9000, before `reopen`)

- `curl -s http://127.0.0.1:9000/health/ready` is 200 with the new build name and schema 66.
- Sign in as an admin of a contest: the users page lists the members with their roles; change
  a test member's role (e.g. `jury` → `organizer` → `jury`): saved.
- Hand-craft the attack (as that admin, with the page's CSRF token, e.g. from the browser's
  devtools on the edit form): set `roles` to `jury,root` and submit. Expected: the form again
  with an error (HTTP 400), the member's role unchanged; `journalctl -u wlxjury` shows
  `refused ... accountId=` or nothing (the form refuses it first), never a stack trace.
- A juror signs in, sees their rounds and votes once (reversible).
- `journalctl -u wlxjury --since "10 min ago" | grep -E 'ERROR|security.roles'`: no errors; a
  `security.roles` WARN appears for each of the 22 legacy rows read until step 4 runs.

## 4. Data (after the deploy; the user runs it)

`docs/security/2026-11-02-role-allowlist-data.sql`: lists the rows that are not exactly one
role, sets the 22 hand-disabled jurors (`jury1`, `jury-`) to `no` in a transaction (check the
count is 22 before `COMMIT`), and lists what is left (expected: nothing). Anything left is
unexpected: record it and look for that account's `POST /users/save` before changing it.
It treats `board`, `auditor` and `staff` as valid (the forward fix's roles); the hotfix code
reads them as no role (inactive), which the deployed code would have given no access anyway.

## 5. Afterwards

- Merge (or cherry-pick) `ukwikidev/hotfix-role-allowlist` into the forward branch; there the
  allowlist is `FORM_ROLES` (adds `board`, `auditor`, `staff`) and V76 adds a binary-exact
  `CHECK` constraint after the same `jury1`/`jury-` reset. V76 fails on any other value.
- Do not restore a release older than this hotfix (pre-0.13 and 0.13-authz-6fa5b56fb021 are
  vulnerable).
