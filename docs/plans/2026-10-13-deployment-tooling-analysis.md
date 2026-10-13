# Deployment tooling analysis

Date: 2026-10-13

Status: recommendation revised after cross-review of the alternatives analysis against deployment code at `b9fe181`; no replacement has been implemented or tested.

## Recommendation

**Keep the deployment/recovery core, separate and eventually retire the one-time adoption code, and add a thin Windows SSH frontend for single-command operation.** Use Ansible for repeatable server provisioning and configuration if that becomes worthwhile.

The original recommendation favored Ansible because its modules cover many generic operations. The [other agent's alternatives analysis](2026-10-13-deployment-tooling-alternatives.md) makes a stronger case about the actual maintenance saving: the generic helpers are small and shared by deployment and recovery, while the one-time cutover code is a larger, more complex removal target. Tool capability coverage alone does not demonstrate that rewriting the existing workflow would reduce maintenance.

A single-command entry point can use Windows OpenSSH and a server-side tmux session without introducing another deployment framework. Pyinfra remains an option for broader automation from native Windows, but native controller support is not a reason on its own to replace a tool that executes on the Linux server.

## Current requirements

The proposed procedure deploys a Play application to a Linux server using one systemd service, Apache, and a shared MariaDB database. It keeps production credentials and site configuration outside the release; the production cutover remains pending. The selected application baseline includes the benchmarked background-distribution release and migrations through V60.

The deployment must preserve these behaviors:

- Verify the reviewed artifact and release identity.
- Enter maintenance and verify coverage of every public jury hostname before stopping the application.
- Stop the application and other coordinated writers, suppress automatic application restarts, and take a consistent database backup.
- Record enough state to recover from interruptions or partially applied migrations.
- Check the candidate's readiness and retain maintenance for smoke tests.
- Reopen traffic only after acceptance.
- Require an explicit data-loss decision before restoring a snapshot after traffic has reopened.

See the existing [deployment and recovery guide](../deployment.md) and [deployment procedure plan](2026-10-11-deployment-procedure.md).

## Alternatives

| Tool | Existing capabilities | Assessment for WLX Jury |
|---|---|---|
| **Ansible** | SSH orchestration, file/configuration management, permissions, service management, HTTP checks, database dump/import modules | Strong option for repeatable server setup. No demonstrated maintenance benefit from replacing the current deploy/recovery core. |
| **pyinfra** | Python deployment definitions, SSH, file uploads/templates, systemd operations, and database operations | Good fit for a Python-based workflow and a Windows controller. Database recovery policy still needs explicit implementation. |
| **Capistrano / Deployer** | Release directories, shared configuration, release switching, and code rollback | Closely matches the current release layout. Requires custom tasks for Play/systemd and database-aware recovery; also introduces Ruby or PHP tooling. |
| **Fabric** | SSH commands, uploads/downloads, sudo, and command-line tasks | Useful for a small single-command frontend. Provides less replacement for the existing recovery machinery. |
| **Kamal** | Container deployment, health checks, and image rollback | An option if moving the application to containers is independently desired. Requires a larger change to the current deployment architecture. |

Sources: [Ansible installation and SSH management](https://docs.ansible.com/projects/ansible/latest/intro_installation.html), [pyinfra operations](https://docs.pyinfra.com/en/3.x/operations/files.html), [Capistrano release structure](https://capistranorb.com/documentation/getting-started/structure/), [Deployer common recipe](https://deployer.org/docs/8.x/recipe/common), [Fabric tutorial](https://docs.fabfile.org/en/3.0/getting-started.html), and [Kamal deployment](https://kamal-deploy.org/docs/commands/deploy/).

## What Ansible could replace

This is a capability map, not a recommendation to rewrite all these operations. The cross-review below explains why retaining the current core is preferable for this server.

| Current responsibility | Standard building block | Remaining application-specific work |
|---|---|---|
| Uploading artifacts and installing files | `copy`, `unarchive` | Reviewed build identity, SHA-256 verification, immutable release policy, and archive-safety requirements must remain enforced. |
| External configuration and permissions | `copy`, `template`, `file` | Keep secrets outside releases and preserve server-specific values. Routine deployment should not require config merges. |
| Application and writer services/timers | `systemd_service` | Specify which writers to pause, retain their prior state, and control restart policy during migration. |
| Maintenance and readiness checks | `uri` with explicit assertions and retries | Verify every hostname, the maintenance response, and the expected build/schema; preserve the acceptance gate. |
| Database backup and import | `ansible.mariadb.mariadb_db` | Coordinate writers and leftover sessions, verify backup integrity and database identity, preserve charset/collation, and guard destructive recovery. |

The relevant module documentation is available for [copy](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/copy_module.html), [unarchive](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/unarchive_module.html), [systemd services](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/systemd_service_module.html), [HTTP checks](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/uri_module.html), and [MariaDB dump/import](https://docs.ansible.com/projects/ansible/latest/collections/ansible/mariadb/mariadb_db_module.html).

These modules are building blocks, not a complete deployment transaction. For example, the database module's import operation runs again on each invocation; rerunning a playbook cannot be assumed to resume a destructive restore safely. Durable phase records, locking, and recovery guards need to survive the refactoring.

Ansible Vault is available if encrypted configuration needs to be stored in the repository. It is optional: the existing protected server-local credential files can remain the source of secrets. See [Ansible Vault](https://docs.ansible.com/projects/ansible/latest/vault_guide/index.html).

## Database rollback remains a separate problem

Release rollback usually changes the selected code or container image. It does not restore data deleted by a migration or make an older binary compatible with a newer schema.

Capistrano explicitly documents that its Rails rollback tasks do nothing special with database migrations. Deployer's rollback recipe selects a prior release. Kamal's rollback selects a prior image. These capabilities do not provide the shared-database recovery guarantees needed here. See [Capistrano rollback limitations](https://capistranorb.com/documentation/getting-started/rollbacks/), [Deployer rollback](https://deployer.org/docs/8.x/recipe/deploy/rollback), and [Kamal rollback](https://kamal-deploy.org/docs/commands/rollback/).

Flyway's optional undo migrations also have limits: they assume the forward migration completed, cannot generally recover destructively changed data, and do not resolve arbitrary partial failures on databases without transactional DDL. The documented undo feature is a Teams-edition feature, so it is not an open-source replacement for the backup/restore workflow. See [Flyway undo migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/undo-migrations).

For this release, retain the maintenance-window backup and verified restore procedure. Future migrations designed for compatibility with the previous application version could permit simpler code rollback, but that is a separate application/schema design change.

## Windows and a single-command workflow

Ansible does not support native Windows as a control node. A Linux controller or container can run it while Windows remains the operator's entry point. The existing Docker setup is a candidate for this. See [Ansible controller requirements](https://docs.ansible.com/projects/ansible/latest/intro_installation.html).

Pyinfra explicitly supports Windows controllers and provides [file operations](https://docs.pyinfra.com/en/3.x/operations/files.html) and [systemd operations](https://docs.pyinfra.com/en/3.x/operations/systemd.html). It is the stronger alternative if avoiding a Linux controller is a priority. See [pyinfra compatibility](https://docs.pyinfra.com/en/3.x/compatibility.html).

The intended operator experience is one invocation that:

1. Builds from reviewed source, or accepts an already reviewed and rehearsed ZIP.
2. Calculates its hash and uploads it.
3. Starts the server-side deployment and reports its progress and recovery record.
4. Leaves maintenance enabled while the operator performs smoke tests.
5. Requests explicit acceptance, then reopens traffic in the same invocation.

A noninteractive mode should stop under maintenance and report the record ID. It should not silently accept smoke tests or automatically restore the database after failure.

Long server-side operations must remain recoverable after SSH disconnects. Ansible has [asynchronous task support](https://docs.ansible.com/projects/ansible/latest/playbooks_async.html), but its async timeout terminates the remote task when exceeded. Migration and restore execution need deliberate timeout and process-lifetime handling; adopting an orchestration tool alone does not settle this requirement.

## Comparison with the alternatives analysis

Cross-review date: 2026-10-13. Compared with [Deployment tooling alternatives](2026-10-13-deployment-tooling-alternatives.md), including its comparison of this document.

### Agreement and stronger evidence

Both analyses agree that code rollback does not solve database recovery after incompatible migrations, that the recovery rules need explicit implementation, and that one-time legacy adoption should be separated from routine deployment.

The other analysis is stronger on cost and benefit. Its file counts match the repository at `b9fe181`: 1,107 lines in the deployment tool, 932 in its unit tests, 294 in the MariaDB integration tests, 54 in `BuildIdentity.scala`, and 143 in the operational guide. The estimates of about 350 lines of one-time cutover code and about 140 lines of generic helpers are useful approximations; they are not a measured saving from an implemented replacement.

The generic helpers serve both deployment and recovery. Replacing them only in deployment risks duplicating behavior and splitting ownership of the lock and phase records. Moving both workflows to Ansible requires retaining the same recovery guards in a different implementation. Adding another toolchain therefore has no established maintenance advantage here.

| Topic | Stronger contribution | Combined conclusion |
|---|---|---|
| Maintenance saving | Alternatives analysis identifies and sizes the one-time adoption code. | Separate that code first; retire it after cutover and recovery requirements are satisfied. |
| Server setup | This analysis covers configuration, permissions and provisioning that are currently manual. | Ansible can help with setup independently of the deployment/recovery engine. |
| Database recovery | Both distinguish release rollback from data restoration. | Preserve durable records, backup verification, restart holds and the data-loss decision. |
| Windows operation | This analysis covers controller support; the alternatives analysis identifies a simpler SSH/tmux frontend. | Start with the existing Windows SSH tools; no additional controller framework is required. |
| Build plugins and physical backups | Alternatives analysis adds sbt-dynver, scanners and snapshots. | Evaluate them separately; none is a complete replacement for the current safeguards. |

### Corrections and qualifications

- **Snapshot restore scope must be explicit.** Restoring an entire MariaDB datadir affects all databases in that instance; restoring a filesystem can also affect unrelated applications, configuration and release files. Before choosing snapshots, verify storage isolation, database consistency, snapshot growth/headroom and restore scope. Faster restoration is not automatically simpler or safe for this host. See [MariaDB snapshot guidance](https://mariadb.com/docs/server/server-usage/backup-and-restore/backup-and-restore-overview) and [full physical restore scope](https://mariadb.com/docs/server/server-usage/backup-and-restore/mariadb-backup/full-backup-and-restore-with-mariadb-backup).
- **Ansible `block/rescue` does not automatically roll back.** It runs explicitly written tasks after a task fails, and unreachable hosts do not trigger rescue. Ansible can persist records using tasks, but it provides no equivalent durable deployment transaction by default. The alternatives analysis's conclusion about custom recovery remains sound; its statement that rescue rolls back and Ansible keeps no state is too absolute. See [Ansible block error handling](https://docs.ansible.com/projects/ansible/latest/playbook_guide/playbooks_blocks.html).
- **sbt-dynver replaces only part of `BuildIdentity.scala`.** Numeric migration-version calculation, manifest generation, reviewed-build validation and the untracked-source check would still be needed. The repository's tag-prefix observation is correct. For a 54-line helper, adding plugins needs a separate reason beyond saving lines. See [sbt-dynver capabilities](https://github.com/sbt/sbt-dynver).
- **Kamal does not require removing Apache.** Its proxy ports are configurable, so Apache could remain in front of an internal Kamal proxy. Containerization and database recovery still make it a larger architectural change than this deployment requires. See [Kamal proxy configuration](https://kamal-deploy.org/docs/configuration/proxy/).
- **Use current scanner commands and preserve review.** Modern Gitleaks documents `gitleaks dir PATH` for directory scans. Scanners can assist a manual cutover, but do not prove all credentials were externalized. See [Gitleaks usage](https://github.com/gitleaks/gitleaks#commands).

Retiring adoption commands must not accidentally remove the legacy readiness fallback or metadata support while an adopted release is still a required recovery target. The remaining roughly 750 lines would also include shared validation and release handling, so describing all of them as recovery-specific would overstate the breakdown.

### Single-command execution and disconnects

The other agent's SSH/tmux suggestion is a practical first choice. Keeping the interactive workflow in a server-side session lets the operator reconnect without interrupting it. A server-side systemd job with durable phase records could also support a reconnecting frontend; acceptance does not inherently need to reside in tmux. In either design, a disconnected frontend must leave a ready deployment under maintenance until explicit acceptance.

The frontend should reuse the existing commands and report failures and record IDs. It should not become a second recovery engine or silently decide to reopen or restore.

## Suggested next steps

1. Let the current deployment fixes finish and preserve the resulting behavior as the baseline.
2. Separate one-time legacy adoption and credential externalization from routine deployment. Complete the production cutover with the existing safeguards, then retire code that is no longer needed for recovery.
3. Keep the core deploy and recovery commands and their shared helpers.
4. Add a thin Windows SSH/tmux frontend for single-command operation, with explicit smoke-test acceptance before reopening.
5. Adopt Ansible for server provisioning and external configuration only if repeatable setup justifies the additional toolchain. Consider pyinfra if broader native Windows automation is needed.
6. Rehearse the exact release and interrupted recovery against an isolated production copy; any later replacement must preserve the verified behavior.

The concrete maintenance target is removing completed one-time cutover machinery. A frontend improves operation, while provisioning automation improves repeatable setup; neither by itself reduces the existing recovery code.

## Investigation limits

This analysis covers documented capabilities and their fit to the repository, plus a read-only cross-review and verification of file counts. It does not certify module behavior against this server, its credentials, its Apache configuration, or its database. No tools were installed, no production changes were made, and no tests were run during this investigation or cross-review. Testing is deferred until the other agent's fixes are complete, as requested.
