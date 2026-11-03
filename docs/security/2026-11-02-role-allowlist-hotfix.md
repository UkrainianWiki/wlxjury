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

## 1. The release ZIP

Built on 2026-11-02 from commit `303c80e0` (the code fix); commits after it on this branch
change docs only and do not change the release:

- file: `wlxjury-0.13-authz-rolefix-303c80e0a6cb.zip` (a copy of `target/universal/wlxjury-0.13.zip`)
- SHA-256: `7a3ec8f33d90412853cee612934ccd96cc6d9ff5c3e2be4111946af8989b896e`
- `wlxjury-build.json`: build `0.13-authz-rolefix-303c80e0a6cb`, version 0.13, schema 66

To rebuild it (the build ID is refused with uncommitted or untracked changes in `app`, `conf`,
`public`, `project` or `build.sbt`; the short commit is appended, so build from `303c80e0`
itself, e.g. a detached checkout, to get the same name):

```bash
git checkout 303c80e0
sbt -Dwlxjury.buildId=0.13-authz-rolefix dist
sha256sum target/universal/wlxjury-0.13.zip
```

## 2. Deploy (the user runs these; inside `tmux` on the server)

```bash
# upload wlxjury-0.13-authz-rolefix-303c80e0a6cb.zip to /srv/releases/, then
readlink /opt/wlxjury/current            # expect .../0.13-authz-6fa5b56fb021
sha256sum /srv/releases/wlxjury-0.13-authz-rolefix-303c80e0a6cb.zip   # 7a3ec8f3...896e
sudo wlxjury-release deploy /srv/releases/wlxjury-0.13-authz-rolefix-303c80e0a6cb.zip   7a3ec8f33d90412853cee612934ccd96cc6d9ff5c3e2be4111946af8989b896e
# smoke test (below) against http://127.0.0.1:9000/ while maintenance is on, then
sudo wlxjury-release reopen OPERATION
```

If the smoke test fails: `sudo wlxjury-release restore OPERATION`, then `reopen` (no data is
lost after `backup-skipped`).

## 3. Smoke test (localhost:9000, before `reopen`)

- `curl -s http://127.0.0.1:9000/health/ready` is 200 with build
  `0.13-authz-rolefix-303c80e0a6cb` and schema 66.
- Sign in as an admin of a contest. Use a **throwaway member** for everything below (add one
  on the users page, e.g. `rolefix-smoke@example.invalid` as `jury`), never a real one.
  Change its role `jury` → `organizer` → `jury`: saved.
- Hand-craft the request as that admin (the edit form's CSRF token, e.g. edit the `<select>`
  in the browser's devtools), for the throwaway member:
  1. first `roles=root` (harmless even on the old code, which ignored root from an admin):
     expected the form again with an error (HTTP 400), and in
     `journalctl -u wlxjury` one line
     `security.roles - refused a role that is not assignable accountId=<admin id> targetId=<member id>`;
  2. only then, optionally, `roles=jury,root`: expected HTTP 400 (the form refuses it; no log
     line), never a stack trace.
  Afterwards, as root on the users page (or by the `is_root` query in step 4), check that the
  throwaway member is not root and its role is still `jury`; then delete it or set it to `no`.
- A juror signs in, sees their rounds and votes once (reversible).
- `journalctl -u wlxjury --since "10 min ago" | grep -E 'ERROR|security.roles'`: no errors; a
  `security.roles` "ignored a membership role" WARN appears for each of the 22 legacy rows read
  until step 4 runs.

## 4. Data and root audit (after the deploy; the user runs it)

`docs/security/2026-11-02-role-allowlist-data.sql`, **one statement at a time** in an
interactive `mysql` session (never pipe the file into `mysql`: the `COMMIT` must follow a
check of the count):

1. the root accounts (`is_root = 1`): compare with the known list (on 2026-11-02: ids 3, 651,
   1167, 1307, 1752; one is stale and may be revoked with root's edit form). **Any other id is
   an incident**: keep it as it is, record it, and look for `POST /users/save` around its
   `created_at` / since 0.13 in the Apache access logs of every jury vhost (they give only time
   and client IP, no request body or account), then take root away.
2. the rows that are not exactly one role (expected: 19 × `jury1`, 3 × `jury-`);
3. `START TRANSACTION;` then the `UPDATE` of the `jury1` / `jury-` rows: check that it reports
   **22 rows affected**; only then, as a separate step, `COMMIT;` (else `ROLLBACK;` and look
   at step 2's rows first);
4. what is left (expected: nothing). Anything left is unexpected: record it and look for that
   account's `POST /users/save` before changing it.

The role queries alone do not prove N1 was never used: an exploiter could have set their own
membership back to a clean role after becoming root; the `is_root` list is the check that
counts. The script treats `board`, `auditor` and `staff` as valid (the forward fix's roles);
the hotfix code reads them as no role (inactive), which the deployed code would have given no
access anyway.

## 5. Afterwards

- Merge (or cherry-pick) `ukwikidev/hotfix-role-allowlist` into the forward branch; there the
  allowlist is `FORM_ROLES` (adds `board`, `auditor`, `staff`) and V76 adds a binary-exact
  `CHECK` constraint after the same `jury1`/`jury-` reset. V76 fails on any other value.
- Do not restore a release older than this hotfix (pre-0.13 and 0.13-authz-6fa5b56fb021 are
  vulnerable).
