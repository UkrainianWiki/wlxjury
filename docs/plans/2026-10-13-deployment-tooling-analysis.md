# Deployment tooling analysis

Date: 2026-10-13

Status: revised after cross-review and investigation of a reduced feature set on 2026-10-13. The current tool is the baseline; simpler profiles below are proposals, not implemented or tested replacements.

## Recommendation

**A simpler deployment can automate the successful path with standard tools and use a tested manual recovery runbook for failures.** The largest additional reduction comes from making the automated recovery commands optional, rather than recreating every current feature in Ansible. This trades guided recovery commands for more operator work and potentially longer outages.

For the initial V53-to-V60 cutover, retain the current tool as the verified local baseline until an alternative has been rehearsed. For routine deployment afterward, evaluate the reduced profile described below. Keep the existing core if automated restore and resumable commands are required. In either profile, separate the one-time adoption code and provide a thin Windows SSH frontend.

The original recommendation favored Ansible because its modules cover many generic operations. The [other agent's alternatives analysis](2026-10-13-deployment-tooling-alternatives.md) makes a stronger case about the actual maintenance saving: the generic helpers are small and shared by deployment and recovery, while the one-time cutover code is a larger, more complex removal target. Tool capability coverage alone does not demonstrate that rewriting the existing workflow would reduce maintenance.

That comparison assumed the current automated recovery contract had to remain intact. The reduced-feature investigation changes that premise: preserving safe recovery outcomes does not require preserving all the commands and automation that currently provide them.

A single-command entry point can use Windows OpenSSH and a server-side tmux session without introducing another deployment framework. Pyinfra remains an option for broader automation from native Windows, but native controller support is not a reason on its own to replace a tool that executes on the Linux server.

## Current design and guarantees

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

This is a capability map, not a recommendation to recreate the entire current tool in YAML. The cross-review explains the cost of retaining all its features; the reduced-feature profile below considers a smaller operational contract.

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

## Simpler deployment by reducing the feature set

Investigation date: 2026-10-13. This section examines which current behaviors are essential outcomes, which can use standard implementations, and which are optional automation. It does not authorize removing any safeguard from the running system.

### Essential outcomes versus optional automation

The earlier analyses correctly found no turnkey substitute for the complete recovery engine. That does not establish that a complete recovery engine is necessary for this single server. An operator can recover using standard database and service tools if the procedure, evidence and backups are sufficient and have been rehearsed.

The current tool already requires an operator to choose and invoke recovery; it does not automatically restore after a failed deployment. The feature being made optional is the guarded automation inside those recovery commands, not a previously implicit rollback.

| Outcome to retain | Why it matters here | Simpler implementation |
|---|---|---|
| Credentials and site overrides remain outside releases | This solves the original manual-merge problem. | Existing systemd environment file and external Play overlay; protected files installed once, or an Ansible setup role. |
| Reviewed artifact, verified transfer and retained old executable | Avoid deploying unintended source and preserve a usable recovery target. | Clean build, ZIP SHA-256 checked on the server, root-owned versioned release directories and retained original ZIPs. |
| One deployment at a time | Concurrent operators must not switch releases or operate on the database independently. | One lock held for the whole server-side job, using `flock`; pending maintenance also blocks a fresh deployment. |
| Maintenance and stopped writers | The current production tables include MyISAM; blocking HTTP alone does not stop in-flight work or other writers. | Keep the Apache flag and all-host checks, then stop the app and the known writer services/timers. |
| No repeated migration starts after failure | A partially applied migration must not be rerun blindly by systemd. | Keep `Restart=no` during the operation and restore normal policy only after acceptance. |
| Consistent, usable pre-migration backup | Swapping code cannot undo incompatible or destructive DDL. | `mariadb-dump`, compression with pipeline failure detection, backup checksum/integrity checks, and a rehearsed import procedure. |
| Capacity and database identity checks | The inspected server has limited free disk; a valid backup of the wrong database is not recovery. | Fixed host/database configuration, explicit checks, and a measured free-space budget using standard tools. |
| Application readiness and explicit reopening | An active process alone does not prove migrations completed or the intended binary is running. | Keep the existing readiness endpoint and expected build/schema checks; reopen only after smoke-test acceptance. |
| Enough durable evidence for an interrupted run | The operator must not assume an interrupted start ran no migrations or that no writes resumed. | A small protected operation record and conservative milestones, rather than a record of every resumable phase. |

The essential part of backup is its consistency and demonstrated restorability, not the custom Python dump wrapper. `--single-transaction` alone is unsuitable while MyISAM tables remain; even after conversion, concurrent DDL requires coordination. See [MariaDB dump consistency](https://mariadb.com/docs/server/clients-and-utilities/backup-restore-and-import-clients/mariadb-dump).

### Features that can be removed or reduced

| Current feature | Smaller alternative | Tradeoff or condition |
|---|---|---|
| Automatic legacy credential export, externalization, HOCON scanning and scrub commands | One reviewed manual cutover, optionally assisted by a secret scanner; then remove the bootstrap machinery. | The signing key and all required credentials still need to be preserved and reviewed. Keep legacy readiness support while retained recovery records need it. |
| Fine-grained phase engine and the `abort`, `resume-original` and automated `restore` commands | A linear deployment recipe that leaves maintenance and restart suppression in place on failure; a separate manual recovery runbook. | Recovery takes more operator work. An interrupted migration is never handled by simply restarting the old binary. |
| Integrated post-cutover restore, data-loss flag and rescue-dump workflow | Exclude post-cutover restoration from the routine deployment command. Use a separate incident procedure, with a fresh preservation backup and an explicit data-loss/reconciliation decision. | The automatic command can disappear; protection of later votes and the explicit decision cannot. Prefer a forward fix when traffic has resumed. |
| Per-file release hash catalog and repeated tree verification | Verify the ZIP hash before unpacking; retain that ZIP/hash and prevent the app account from modifying releases. | Loses automatic detection of edits to extracted files. Recovery can re-extract the verified ZIP into a new directory if the installed tree is suspect. |
| General-purpose ZIP validator and custom extractor | Standard `unzip` or Ansible `unarchive` into a new staging directory, limited to artifacts produced by the reviewed project build. | This narrows the trust model. Hashing does not make a hostile archive safe; arbitrary third-party ZIPs remain unsupported, staging must be contained, and extraction should run with limited privileges before publishing a root-owned release. |
| Generic DB endpoint selection and parsing app secrets to construct administrative client arguments | One explicit database name and local socket in server configuration, plus a protected MariaDB client option file. | App and admin connections must still be checked to identify the same instance/database. Remote hosts or nondefault ports require revisiting this profile. |
| A custom parser for systemd environment-file values | Let systemd read the app's environment file and MariaDB read its own option file. Keep nonsecret deployment inputs separate. | Never source the systemd environment file as shell. Secret presence and app startup still need validation. |
| Snapshotting and automatically restoring all runtime configuration on every deployment | Keep runtime config unchanged by normal releases; handle configuration changes separately with their own protected backup and compatibility review. | A code/DB restore no longer automatically restores old credentials or site overrides. The runbook must identify compatible config for the old executable. |
| Custom operation logging and SSH-hangup handling | A server-side tmux session or systemd job, with persistent logs and the small operation record. | Logs require suitable retention. They are evidence, not a substitute for backups or the traffic/migration milestones. |
| Arbitrary build labels and broad deployment settings | A fixed layout for this server, a commit-derived build identity, one configured DB and a known writer list. | Less reusable across hosts. Keep the existing small build/schema manifest unless there is a concrete reason to change it. |

Automatic session killing during restore is also optional: the manual runbook can inspect and end leftover app sessions and wait for DDL rollback. The requirement to quiesce them before a rescue dump or database replacement remains. A timeout or failed service start does not prove a server-side query has ended.

Do not remove cheap checks merely to shorten the script. All-host maintenance verification, the schema downgrade check, capacity checks, private-file permissions and backup integrity checks prevent failures already identified on this deployment and have little implementation cost when expressed with standard tools.

### Minimal evidence instead of a full recovery state machine

Keep a small root-protected record containing the operation ID, previous and candidate release references, artifact hash, database name/endpoint, backup path/checksum, original database charset/collation or equivalent CREATE DATABASE metadata, and the prior state of paused writers. Record configuration identity separately when config changes are involved. It contains no credential values.

Three conservative milestones are sufficient candidates for the reduced recipe:

1. **Backup complete:** written only after the dump succeeds and integrity checks finish.
2. **Migration may have started:** written durably before starting the candidate.
3. **Traffic may have resumed:** written durably before removing maintenance.

This is still a small amount of custom orchestration, but it does not require automatic phase-specific recovery or an arbitrary historical-record restore API. Missing or damaged evidence after an interruption must be treated conservatively: it is not proof that a migration or traffic reopening never occurred. Preserve the backup and stop for manual investigation.

With Ansible, ownership of these milestones and the lock must be defined once for the whole workflow. An in-memory registered variable or a separate `flock` invocation around each task does not provide that guarantee. A single remote job can hold `flock` for its entire lifetime; a distributed controller would need an explicit persistent lock protocol instead. See [util-linux flock](https://github.com/util-linux/util-linux/blob/master/sys-utils/flock.1.adoc).

### Candidate standard-tools workflow

For one VM, a short server-side recipe using the existing OS tools is the first candidate. An Ansible playbook is an alternative when its controller and setup roles already have value. In either case, the normal sequence is:

1. Build or select the reviewed artifact on Windows; upload it with OpenSSH.
2. On the server, acquire the deployment lock, refuse an unresolved maintenance operation, verify the artifact, unpack it into a new release, and save the small operation record.
3. Enable maintenance and verify every configured HTTP/HTTPS host, then suppress restarts and stop the app and coordinated writers.
4. Check for remaining writers/sessions; take and verify the backup, preserving original database creation metadata and Flyway history.
5. Record that migrations may start, switch the release atomically and start the app.
6. Wait for the expected local build/schema readiness, then pause for smoke-test acceptance.
7. Record possible traffic reopening, restore the normal restart policy, remove maintenance, verify public routing and resume only the writer units that should resume.

Any failure stops the recipe and leaves existing maintenance/restart suppression in place. There is no automatic restore and no unconditional failure handler that opens traffic. An early failure before maintenance was enabled leaves the live application alone. A noninteractive invocation ends under maintenance.

The standard building blocks are `ssh`/`scp`, `tmux` or `systemd-run`, `flock`, `sha256sum`, archive tools, `systemctl`, `mariadb-dump`, `mariadb`, compression tools and HTTP checks. These replace infrastructure implementations, not the need to specify their ordering and failure behavior. A systemd job can run independently of its launching SSH session; see [systemd-run documentation source](https://github.com/systemd/systemd/blob/main/man/systemd-run.xml).

Ansible provides [SHA-256 file checks](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/stat_module.html), [archive extraction](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/unarchive_module.html), service and HTTP modules, and [MariaDB dump/import](https://docs.ansible.com/projects/ansible/latest/collections/ansible/mariadb/mariadb_db_module.html). `unarchive` does not itself verify the supplied artifact's expected checksum. For acceptance, an untimed [pause](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/pause_module.html) can collect input, but the recipe must check that input explicitly; a timed pause or pressing Enter is not automatic approval.

### What manual recovery must still cover

The reduced deployment needs a separate, rehearsed runbook for four situations:

- A maintenance check failed before stopping the original service: verify the original is still healthy before removing the flag.
- Writers stopped but candidate startup is positively known not to have begun: resume the verified old release and the appropriate units, then verify before reopening.
- Candidate startup/migration may have begun: keep writers stopped, clear leftover DB sessions, verify the backup and target identity, recreate/import the intended database, select compatible old code/config, validate Flyway history and readiness, then decide whether to reopen.
- Traffic may have resumed: preserve the current data, prefer a forward fix, and make a separate explicit decision about losing or reconciling later writes before restoring an old snapshot.

An exact restore must not leave candidate-only tables behind. The runbook must recreate the intended database with its original defaults before import, or use an equivalently rehearsed self-contained dump; importing an old dump over the migrated database is not enough. Restoring the whole MariaDB datadir is not a shortcut for restoring only the jury database.

This moves complexity from code to an operational procedure. It is a valid trade only if manual recovery time and operator responsibility are acceptable and the procedure has been exercised on an isolated production copy. It cannot support a promise of one-command unattended recovery.

### A further packaging option: use Debian packages

The server currently uses a Debian package, and `build.sbt` already defines `packageDebSystemd`. A reviewed `.deb` installed through standard package tools could eliminate the custom ZIP release manager and `current` symlink altogether. Old package files would be retained for manual code rollback, while credentials and the Play overlay remain in the external runtime directory.

This is a separate candidate, not a ready replacement. The current external systemd drop-in launches `/opt/wlxjury/current/bin/wlxjury`, so it would have to be adapted to the package installation path. The actual package contents, Windows/Linux build route, old-package reinstall behavior and maintainer scripts have not been verified in this investigation.

Package installation can request service starts or restarts. Those actions must be prevented until the backup is complete and the deliberate migration start occurs; `Restart=no` alone does not block an explicit start requested by a package script. Debian also preserves locally modified configuration files, so moving site overrides outside package defaults remains necessary to avoid the original merge problem. See [Debian service policy](https://www.debian.org/doc/debian-policy/ch-opersys.html#starting-system-services) and [configuration-file policy](https://www.debian.org/doc/debian-policy/ch-files.html#configuration-files).

Keep this option behind the smaller ZIP/standard-tools recipe in priority: changing packaging adds an additional deployment behavior to validate during the initial migration-heavy release.

### Choice of operating contract

| Profile | Code and dependencies | Failure handling | Assessment |
|---|---|---|---|
| Current core, bootstrap separated | Existing Python engine and shared helpers | Automated phase-aware recovery commands | Keep when those capabilities are required; initial-cutover baseline. |
| Standard-tools recipe, manual recovery | Small orchestration recipe, minimal evidence and runbook; Ansible optional | Operator diagnoses and follows the rehearsed recovery procedure | Main simplification candidate for one server with infrequent releases. |
| Debian package workflow, manual recovery | Standard package tools plus maintenance/backup recipe | Operator reinstalls compatible code and restores DB if necessary | Potential further reduction, after packaging behavior is verified. |

The earlier estimate of only about 140 replaceable helper lines applies when all current recovery automation is retained. Dropping that automation changes the scope and could remove more custom code. No reduced implementation exists yet, so a final line count or claimed percentage saving would be speculative.

## Suggested next steps

1. Let the current deployment fixes finish and preserve the resulting behavior as the baseline.
2. Separate one-time legacy adoption and credential externalization from routine deployment. Complete the production cutover with the existing safeguards, then retire code that is no longer needed for recovery.
3. Decide whether failure recovery must remain automated. If it must, keep the core commands and shared helpers. If manual recovery is acceptable, evaluate the reduced standard-tools profile and its runbook before replacing them.
4. Add a thin Windows SSH/tmux frontend for single-command normal deployment, with explicit smoke-test acceptance before reopening.
5. Adopt Ansible for repeatable setup or the reduced recipe only when its toolchain is justified. Do not duplicate the full Python recovery engine in YAML.
6. Rehearse the exact release and interrupted recovery against an isolated production copy, including the conservative handling of uncertain migration/traffic state. Verify the candidate's actual maintenance reduction before adopting it.

The immediate maintenance target is completed one-time cutover machinery. The additional target is optional recovery automation, if the operational contract permits manual incidents. A frontend alone improves convenience; a smaller contract is what makes a substantially smaller implementation possible.

## Investigation limits

This analysis covers documented capabilities and their fit to the repository, a read-only cross-review and verification of file counts, and a proposed reduced-feature deployment contract. It does not certify module behavior against this server, its credentials, its Apache configuration, or its database. No tools were installed, no production changes were made, and no tests were run during this investigation or cross-review. Neither the reduced recipe nor the Debian package alternative has been implemented or rehearsed. Testing is deferred until the other agent's fixes are complete, as requested.
