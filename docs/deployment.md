# Deployment and recovery

Production runs one `wlxjury.service` from immutable, versioned ZIP releases under
`/opt/wlxjury/releases`, with `/opt/wlxjury/current` pointing at the running one. A release is
deployed with `wlxjury-release` (`scripts/deployment/wlxjury-release.sh`) in a maintenance window:
it backs up the database when the release has migrations, switches the release, and can restore
the previous one. The server itself is set up by the separate `wlxjury-infra` repository (Ansible).

Run every `deploy`, `restore` and `reopen` inside `tmux` (or `screen`). Each operation logs its
steps to its folder (`/var/backups/wlxjury/UTC_TIMESTAMP-BUILD/operation.log`), and `tmux` keeps it
running when the connection drops, so a dropped connection doesn't interrupt a backup, migration
or restore. You still need the terminal to follow it and act on the result.

## Why it works this way

One service, immutable releases, a maintenance window and a rehearsal on an isolated copy of the
database. Blue/green gave neither zero downtime nor a safe rollback after a migration, and two app
processes and two databases don't fit the server's disk. No standard tool covers the recovery part
(operation records, `restore` with its dump, refusing a restore after reopening), so the release
tool is a small script with a rehearsed runbook. The release artifact is the ZIP from `sbt dist`.
No containers on production for now: the reasons, and the path if that changes, are in
[operations](operations/practices.md) §4.5. The history is in `docs/history/` and the CHANGELOG.

## The server

**`wlxjury-infra`** manages the Apache vhosts with the maintenance include and its snippet, the
certificates, `/etc/wlxjury-runtime` and its files (secrets from an encrypted vault), the systemd
unit and the app's MariaDB settings, and installs the release tools. Each playbook run takes the
deployment tools from a worktree (`-e wlxjury_tools_source=…`) at the running release's commit, or
a later pushed commit for a tools fix the next deploy needs, and refuses an older or unrelated
commit before any change; its README ("Running") has the steps. The playbook never restarts the
app. After a run that changed the unit, restart it at a quiet time, as the last step:

```bash
ssh -t wlm.org.ua 'sudo systemctl restart wlxjury'
```

**Java 17 is required.** Releases are compiled for Java 17 (`-release:17` in `build.sbt`); on Java
11 the app fails at start with `UnsupportedClassVersionError`. The launcher (`bin/wlxjury`) runs
`$JAVA_HOME/bin/java` if `JAVA_HOME` is set, else the `java` on `PATH`, so keep 17 the default and
don't point `JAVA_HOME` at another JDK. With several JDKs installed, `update-alternatives` in auto
mode makes the newest one the `java` on `PATH`, so a package that pulls in a newer JDK silently
switches the app to it. Set the alternative to 17 explicitly
(`update-alternatives --set java <the 17 JVM's bin/java>`) and check `java -version` after package
changes. Running on a newer Java is its own tested change (roadmap, "Java 21 runtime").

**A UTF-8 locale is required.** The image cache stores Commons file names URL-decoded, so Cyrillic
and other non-ASCII names become file names. Under the POSIX locale JDK 17's `sun.jnu.encoding` is
ASCII and those paths throw `InvalidPathException: Malformed input`; `-Dfile.encoding=UTF-8`
doesn't cover file names. Run the service with `LANG=C.UTF-8` (in a container image too).

### Runtime files

`/etc/wlxjury-runtime` holds the server's settings; nothing secret belongs in the checkout, the
ZIP, command-line arguments or console output.

| File | Mode | What |
|---|---|---|
| `runtime.env` | `0600 root` | Credentials and host settings: one fully double-quoted value per line (escape backslashes and double quotes; spaces, `$`, `#` and backticks are literal); keys as in `conf/wlxjury/runtime.env.example`. Never source it as shell. |
| `production.conf` | `0640 root:wlxjury` | The production overlay: includes the release's defaults, requires the signing key, maps DB and Commons credentials to the environment, and runs Flyway migration and validation with baseline and clean prohibited. |
| `deployment.json` | `0600 root` | The datadir, every jury HTTP and HTTPS URL (`maintenance_urls`), the writer services and timers to stop (the example pauses the database-backup timer), free-space gates, retention, the image cache folder. Paths must be real, root-owned directories. |
| `db-admin.cnf` | `0600 root` | The administrative database login; the example uses root's Unix-socket authentication. Application passwords are never passed to DB client commands. |

The directory itself is `0755`, so Apache can stat the nonsecret maintenance flag in it. JVM tuning
stays in `/etc/default/wlxjury`. The logging config ships with each release
(`conf/logback-production.xml`); the unit starts the release with it, so a backout's next start
reads the old release's file.

Sign-in with Google and Wikimedia accounts is off until `production.conf` lists hosts and
credentials for it: [oauth.md](oauth.md).

Two settings name a contact and a page on Wikimedia (`application.conf`):

- `WLXJURY_USER_AGENT_CONTACT` (`wlxjury.userAgent.contact`, no default): the operator's contact,
  an address or a URL, added to the User-Agent of the thumbnail cache's downloads from Wikimedia.
  Unset, that User-Agent names only the tool's Commons page. Commons API calls use scalawiki's own
  User-Agent (`SCALAWIKI_USER_AGENT`, [integrations](integrations/practices.md) §1.4).
  **Production sets it through wlxjury-infra** (`vault_wlxjury_user_agent_contact` in the
  encrypted vault, written to `runtime.env`); the value stays out of both repositories.
- `WLXJURY_COMMONS_ENABLE_EMAIL_PRELOAD` (`wlxjury.commons.enableEmailPreload`): the page whose
  text pre-fills the users page's "please enable wiki email" message on a juror's talk page;
  default `Commons:WLX Jury Tool/enableEmailText`. **Before the first release with it**, copy the
  text of the page used until now to that subpage on Commons.

**Maintenance.** `conf/apache/wlxjury-maintenance.conf` is included inside every jury HTTP and
HTTPS VirtualHost, before redirect rules. The flag is read on every request, so deployments need no
Apache edits or reload. Every URL in `maintenance_urls` must resolve to this server from the server
itself, with a valid certificate: the tool expects 503 with the snippet's maintenance page before it
stops anything (a vhost without the snippet also answers 503 while the app is stopped, but with
Apache's own page), and the app (2xx/3xx) after `reopen`. A failure names the URL and the cause.
`/health/ready` answers only requests from the server itself; through Apache it is a 404.

**The database host.** The release tool's database commands always use the app's host from
`runtime.env`, whatever `db-admin.cnf` says. For `WLXJURY_DB_HOST=localhost` they connect through
the socket in `db-admin.cnf` with root socket authentication, and check that this server listens on
port 3306. For any other host, or an explicit port, they connect by TCP, so `db-admin.cnf` then
needs an administrative account that can log in over TCP. Each operation records the database name
and endpoint, and `restore` refuses a backup whose database differs from what `runtime.env` names.

**The cutover tool.** `wlxjury-deploy` (`scripts/deployment/wlxjury-deploy.py`) moved production
from the old Debian package to these releases in October 2026. It stays installed only for its last
open record, `20261016T092501Z-45c7ed3a`, which keeps `legacy-0.12-v53` from being pruned; closing
it is a roadmap follow-up. Never use `wlxjury-deploy deploy`: it can't verify releases it didn't
prepare. Both tools take the same lock (`/opt/wlxjury/deployment.lock`).

## Build and rehearse

```bash
sbt test
sbt -Dwlxjury.buildId=26.11 dist
sha256sum target/universal/wlxjury-26.11.zip
```

Versions are calendar-based, `YY.MM`, with `.N` for another deploy in the same month; the release
commit sets `version` in `build.sbt` (between releases it is the next one's snapshot). The steps
are in [workflow practices](workflow/practices.md) §7.4. A requested build ID is refused if `app`,
`conf`, `public`, `project` or `build.sbt` has uncommitted **or untracked** changes, and the short
commit is appended unless the name already contains it, e.g. `26.11-dbedca565807`; without
`-Dwlxjury.buildId` the ID is version and commit, with `-dirty` for any such change (rules in
`project/BuildIdentity.scala`, tested by `deployment.BuildIdentitySpec`). The release folder on the
server is that ID plus the ZIP hash's first 12 characters. `wlxjury-build.json`, at the ZIP's root
and embedded in the app, names the build, the version and the latest migration. Server setup files
(`conf/wlxjury`, `conf/apache`, `conf/systemd`) are not in the ZIP.

Rehearse the **exact ZIP** against an isolated, consistent copy of production on production's
MariaDB version, Flyway history included: production's MyISAM tables need locking or stopped
writers (`--single-transaction` alone is not enough). Disable real email, isolate access and the
image cache, and keep copied jobs from contacting production services. Measure migration, startup,
backup, restore and peak disk space, and test restoring the backup and the old release.

## Deploying a release

Install the tool once (it needs `curl` and `unzip`):

```bash
sudo install -o root -g root -m 0755 scripts/deployment/wlxjury-release.sh /usr/local/sbin/wlxjury-release
```

Run it inside `tmux`, with the reviewed ZIP and its SHA-256, from the directory the ZIP was
uploaded to:

```bash
cd ~                                          # where the ZIP was uploaded
sudo wlxjury-release deploy SHA256            # the first 16 (or more) hex characters sent with the build
# Smoke-test http://127.0.0.1:9000/ with a jury host name (curl -H 'Host: jury.wle.org.ua'). Then:
sudo wlxjury-release reopen                   # the current operation
sudo wlxjury-release status                   # at any moment: what is open, and the next command
```

**The last line is the command to run.** `deploy`, `restore` and `status` end with the one command
to run next (where there is one), alone and indented on the last line. Nothing follows it, and no
back-out command is printed after a success, so copying "the last command" is always right:

```text
26.11.6 is ready under maintenance (schema 101, backup skipped).
Smoke-test http://127.0.0.1:9000/ locally, then reopen:
  sudo wlxjury-release reopen
```

The usage, which it prints for a wrong command:

```text
usage: wlxjury-release [--config FILE] COMMAND
  status [--verbose]                      what is open, and the one command to run next
  deploy [RELEASE.zip] SHA256 [--backup] [--allow-running-jobs]
                                          SHA256: all 64 hex characters, or at least the first 16;
                                          without RELEASE.zip, the wlxjury-*.zip here with that hash
  reopen [OPERATION]                      default: the current operation (status names it)
  restore [OPERATION] [--yes]             default: the current operation, under maintenance only;
                                          after ready it asks to type RESTORE (--yes: no terminal)
  restore OPERATION --accept-data-loss [--yes]
                                          after traffic resumed: always named
  prune [--dry-run]
```

**The hash** proves that the ZIP on the server is the one that was built and reviewed, so it comes
by another channel than the upload (the build's message), never from a file next to the ZIP.
`deploy` takes all 64 hex characters or a prefix of at least 16 (64 bits: a wrong or stale ZIP never
matches, and forging a match would take about 2^64 hashes), compares it with the ZIP's whole
SHA-256 and records that. Without `RELEASE.zip` it hashes every `wlxjury-*.zip` in the current
directory and takes the one that matches; with none it refuses and lists each ZIP with the start of
its hash. `sudo` keeps the current directory, so run it where the ZIP was uploaded, or name the ZIP.

**The operation.** Each deployment is an operation, a folder `/var/backups/wlxjury/UTC_TIMESTAMP-BUILD`
with `record`, `milestones` and `operation.log`. Without a name, `reopen` and `restore` act on the
**current** operation: the newest operation folder of either tool, if it is `wlxjury-release`'s,
not `complete`, and `/opt/wlxjury/current` is its previous or candidate release. They print
`Operation NAME: picked as the current operation` and pick it under the lock. Any other open
operation is **stale** (left open, then followed by a later one, or with `current` pointing
elsewhere) and is never picked: with no current operation they refuse, change nothing, and list the
open ones with why each is stale. `restore` picks the current operation only while maintenance is
on; with traffic open, name it. `restore OPERATION --accept-data-loss` always needs the name. The
name is printed by `deploy` (`Operation PATH: PREVIOUS -> RELEASE`) and by `status`.

**Confirming a restore.** A `restore` of an operation that reached `ready` undoes a deployment that
worked, so it asks `Type RESTORE to switch back to PREVIOUS (what happens to the database):` and
changes nothing unless the answer is exactly `RESTORE`; without a terminal it refuses unless `--yes`
is given. It asks after its own checks, just before maintenance goes on. The recovery restores stay
one command: after `migration-may-start` without `ready`, and the rerun of a restore that didn't
finish (confirmed when it started; after a `reopen` in between it asks again).

A named `restore` also refuses, before anything changes, when `current` is neither the operation's
previous nor its candidate release, or when a later operation deployed the current release again:
its dump would replace newer data. If such a backup is really wanted, import it by hand.

**`status`** changes nothing and never takes the lock (it reads `/proc/locks`, so it can't make a
running operation fail; run it on the host, not in a container). It prints maintenance on or off with
the current release's build and schema, the current operation with its releases and state (or the
last finished one), one line per stale operation, and then the next step for that state, as the
[recovery runbook](#recovery-runbook) gives it, ending with its one command:

```text
Maintenance off. Current release: 26.11.6 (schema 101).
Last operation 20261113T101500Z-26.11.6: complete.
To deploy:
  sudo wlxjury-release deploy [RELEASE.zip] SHA256
```

While an operation runs it says the lock is held and its next command is `status` again. With no
current operation and maintenance on, it offers no `deploy` and no back-out: find out what turned
maintenance on and finish that first. `status --verbose` adds the paths, the database's schema,
each operation's milestones, and under `Other commands` the named form of the next command and how
to back out (a ready deployment's `restore`, marked as asking to type `RESTORE`).

**Deploying a release folder again.** `deploy` unpacks each release into `releases/BUILD-HASH12`
and writes the ZIP's whole SHA-256 into it as `.wlxjury-zip-sha256`. If that folder exists:
- it is the current release: `deploy` refuses and never overwrites it;
- it is not current and came from this same ZIP (its marker, or an operation's `record` naming it
  with this `zip_sha256`), as after a restored deployment: `deploy` replaces it with the fresh,
  verified unpack and prints `Prepared … again from this ZIP` (if that fails, the old folder is put
  back, or `deploy` names where it is and the `mv` that moves it back);
- anything else: `deploy` refuses before anything changes. Remove the folder by hand only once no
  operation needs it (`prune --dry-run --verbose` lists what it keeps).

**Backups.** A release with new migrations always gets a database backup. A release without them
(its `schemaVersion` equals the database's) skips the dump, which shortens the outage, and records
`backup-skipped`; `--backup` takes one anyway. Backing out such a deployment only switches the
release back, keeping every write made since.

**Before changing anything**, `deploy` takes the lock and checks: the ZIP hash, with no symlinks and
one top-level folder, on a copy in a root-only staging folder (so a ZIP in a folder others can write
to can't be swapped between the check and the unpacking); the manifest; that `db-admin.cnf` reaches
the app's database; that the release is not older than the database and there is no failed
migration; that no database event is enabled; that no background job is queued or running; free
space; and that [the image cache folder](#the-image-cache-folder) is owned by the service user and
not group- or world-writable.

A queued or running background job (a round distribution, an image check) stops `deploy`, which
lists each with its id, kind, contest and status. Wait for them, or cancel them in the app (the
Rounds or Edit round page, or `/admin/jobs/ID`). `--allow-running-jobs` deploys anyway with a
warning: stopping the app puts running jobs back in the queue, and the new release continues each
from its last committed step (a job of a killed app waits until its lease expires).

**Then it:**
1. creates the operation folder;
2. enables maintenance and requires HTTP 503 with the maintenance page at every configured URL;
3. turns off automatic restarts (so a failed start never reruns migrations on its own) and stops
   **every** configured writer unit and the app, checking each has stopped;
4. rechecks database events;
5. dumps the database with `--databases --add-drop-database --lock-tables` unless the backup is
   skipped, checks the dump and saves its checksum and a config snapshot;
6. flushes them and rechecks free space;
7. switches `/opt/wlxjury/current`, starts the app, and waits until `/health/ready` reports the new
   build;
8. prints the release, its schema and whether the backup was taken, and, last, `sudo wlxjury-release reopen`.

**`reopen`** requires intact milestones, no unfinished restore, `current` at the candidate release
(or the previous one after a finished restore), and the release to be ready. It records
`traffic-may-resume`, restores automatic restarts, removes maintenance, checks every URL reaches the
app, and restarts the writer units that ran before. If a URL fails, fix routing and run `reopen`
again. Once `complete`, it [prunes](#retention-old-releases-and-dumps) and ends with
`Traffic resumed with RELEASE; OPERATION is complete.`

**`restore`** backs out a deployment, holding the lock and stopping at the first failure:
1. It refuses an operation without a complete backup, one whose releases are both other than
   `current`, one after `traffic-may-resume` without `--accept-data-loss`, a backup of another
   database, and a pruned dump.
2. It verifies the backup's checksum and gzip integrity and checks free space (plus the database's
   size when a rescue dump is needed); for an operation that reached `ready` it asks to confirm.
3. It enables maintenance, turns off automatic restarts, and stops every writer and the app.
4. It ends the app account's leftover sessions (waiting up to `session_end_timeout_seconds` for a
   killed `ALTER`) and refuses while any other session uses the database.
5. If writes may have happened since the last import attempt, it saves the current database as a
   new `before-restore-UTC_TIMESTAMP.sql.gz` (checked, flushed, recorded as a `rescue` milestone).
   Rescue dumps are never replaced; a retry after an unfinished import takes none.
6. It records `restoring` and imports the backup.
7. It checks the previous release's schema and no failed migration, switches `current` back,
   records `database-restored`, starts the previous release and waits until it is ready.

After `backup-skipped`, `restore` imports nothing and needs no `--accept-data-loss`: it checks the
database still has the previous release's schema, asks to confirm as above, switches `current`
back, records `release-restored` and starts the previous release. It never restores config: if the
release changed config, restore `config.tgz` by hand before reopening.

### Retention: old releases and dumps

Release folders and pre-deployment dumps stay only as long as a `restore` can need them; the
server's long-term backup is separate. `wlxjury-release prune` deletes the rest. It runs at the end
of every successful `reopen` (never after a failed one) and prints only what it deletes. By hand:

```bash
sudo wlxjury-release prune --dry-run --verbose   # what it keeps and why, and what it would delete
sudo wlxjury-release prune
```

It takes the lock and refuses while maintenance is on. If only the prune at the end of a `reopen`
fails, `reopen` prints `PRUNE FAILED after a successful reopen` and exits 3: traffic is open and the
operation is `complete`, so don't rerun `reopen`; fix the cause and run `prune`. Settings in
`deployment.json`:

| Setting | Default | Keeps |
|---|---|---|
| `keep_previous_releases` | 4 | the current release, plus the 4 releases an operation named most recently. At least 1 |
| `keep_backup_dumps` | 5 | the dumps of the 5 newest operations that took one. At least 1 |
| `keep_backup_days` | 0 (off) | when above 0, also every dump younger than that many days |

Whatever the numbers, it never deletes: the current release or one named by an operation that isn't
`complete` (of either tool; damaged milestones count as open); the dump of an open operation or of
the one that deployed the current release; **the previous release of every dump it keeps**, so
every kept dump can be restored; rescue dumps, operation folders, `wlxjury-deploy`'s records; symlinks
and folders without a `wlxjury-build.json`. With the defaults about 5–6 releases stay. It decides
everything before deleting anything, and deletes nothing when it can't tell what is safe (an open
operation it can't read, a path outside `releases`, a symlinked operation folder, a name with a line
break or tab). It also removes `.staging.*` folders a killed `deploy` left. Each deletion is logged
with its size to `prune.log`, and after a `reopen` to that operation's log.

### The image cache folder

The image cache (`wlxjury.thumbs.local-path`, default `/var/www/jury-images`) holds the thumbnails
the app downloads from Commons. Only the app reads and writes it (`ImageProxyController` serves it
to signed-in users), so it is owned by the service user (`wlxjury`), mode `0755`, files `0644`.
`deploy` refuses while the folder named by `image_cache_dir` is not owned by `service_user` or is
group- or world-writable (checked if it exists, at its target if it is a symlink). To fix it:

```bash
sudo chown wlxjury:wlxjury /var/www/jury-images
sudo chmod 0755 /var/www/jury-images
# Then anything below that others could write to; only entries that need it change.
sudo find /var/www/jury-images -xdev \! -user wlxjury -print            # expect nothing
sudo find /var/www/jury-images -xdev -type d -perm /022 -exec chmod go-w {} +
sudo find /var/www/jury-images -xdev -type f -perm /022 -exec chmod go-w {} +
stat -c '%a %U:%G' /var/www/jury-images                                 # 755 wlxjury:wlxjury
```

The cache only grows. `scripts/local-images-maintenance.sh` shows its size and deletes old files
(regular files only, never through symlinks; run as the service user):

```bash
scripts/local-images-maintenance.sh report                          # files and bytes per modification month
scripts/local-images-maintenance.sh prune --months 6 --dry-run      # list what would go; deletes nothing
scripts/local-images-maintenance.sh prune --months 6 --quiet        # delete, then remove empty folders
```

Files are deleted by age since they were cached (their modification time), not because they are
unused: a thumbnail that is still in use is fetched again from Commons on its next request. If
`wlxjury.thumbs.local-path` is not `/var/www/jury-images`, pass that folder every time, with
`--path FOLDER` or `export WLXJURY_THUMBS_LOCAL_PATH=FOLDER` (the script's own variable; the app does
not read it), or the script works on the wrong folder. A folder the script cannot read, or a file it
cannot delete, makes it exit 1 with a message on stderr and a partial result; fix the permissions
(run as the service user) and run it again. The script's tests are `scripts/test_local_images_maintenance.py`.

### Monitoring with monit

[`conf/monit/wlxjury`](../conf/monit/wlxjury) adds the app to monit, **alert only**: systemd already
restarts it after a crash, and a restart by monit during a deployment would bypass the deployment's
hold and could rerun a migration (for the same reason, other monit checks on the host should not
restart MariaDB). It runs [`conf/monit/wlxjury-check`](../conf/monit/wlxjury-check) with
`deployment.json`, and fails when the service is not active, `http://127.0.0.1:PORT/health/ready`
doesn't answer 200, or `deployment.json` can't be read. It is quiet while the maintenance flag is on,
unless the flag is more than two hours old, and alerts after three failed cycles in a row. Install:

```bash
sudo install -D -o root -g root -m 0755 conf/monit/wlxjury-check /usr/local/lib/wlxjury/monit-check
sudo install -o root -g root -m 0644 conf/monit/wlxjury /etc/monit/conf.d/wlxjury
sudo /usr/local/lib/wlxjury/monit-check /etc/wlxjury-runtime/deployment.json; echo "exit $?"   # ready, exit 0
sudo monit -t && sudo monit reload    # if monit -t fails: sudo rm /etc/monit/conf.d/wlxjury
sudo tail -n 5 /var/log/monit.log     # a reload line, no 'wlxjury' error
```

Undo: remove both files and `sudo monit reload`. Check that the server's monit config alerts
someone (`set alert`, `set mailserver`); a filesystem space check on the releases' disk is worth
adding there.

### Static assets

`sbt dist` fingerprints `/public` (sbt-digest): pages link `/assets/<md5>-<name>`, served with
`Cache-Control: public, max-age=31536000, immutable`, so a changed file gets a new URL. Plain names
(raty's star images, old links) are still served, cached for an hour. To check a release:

```bash
curl -s -H 'Host: jury.wle.org.ua' http://127.0.0.1:9000/auth | grep -o '/assets/[^"]*main.css'   # /assets/<32 hex digits>-main.css
curl -sI -H 'Host: jury.wle.org.ua' http://127.0.0.1:9000/assets/stylesheets/THAT_NAME | grep -i cache-control
```

`sbt run` and the tests serve plain names: the digests exist only in the packaged assets jar.

### Host names and secure cookies

The app answers only requests whose `Host` it names (Play's `AllowedHostsFilter`); any other gets a
400 page. The names come from `WLXJURY_ALLOWED_HOSTS` in `runtime.env`: every `ServerName` of the
jury vhosts, comma-separated, e.g. `"jury.wle.org.ua,jury.wlm.org.ua"`, without default ports; a
suffix such as `.wle.org.ua` allows its subdomains (at least two labels). The vhosts keep the
client's `Host` (`ProxyPreserveHost On`). Loopback names aren't allowed, since Apache's default
vhost would pass a client's `Host: localhost` through. `/health/ready` is routed for any host and
answers only clients on the server itself. A production start without a usable name fails, so a
missing variable stops a deployment at the readiness check with maintenance still on; a name missing
from the list answers 400, and `reopen`'s URL check names it.

`production.conf` marks the session, flash, language, theme and gallery-order cookies `Secure`, so
every jury HTTP vhost must redirect to HTTPS (the production vhosts come from `wlxjury-infra`'s
templates). HSTS on the HTTPS vhosts is recommended. Nothing in the app reads `request.secure`, so
no `X-Forwarded-Proto` header is needed; if one is ever added, set it on every vhost, since Play
trusts it from Apache on loopback. `sbt run` and the tests allow Play's default names and send
cookies without `Secure`.

### Accounts, invitations and the first account

There is no sign-up. An organizer's add (the users page, "Import users") creates an account without
a password and mails an invitation link; following it sets the password and proves the email. Root's
"reset password" mails a link the same way. These mails go through `play.mailer`, so the server's
SMTP settings must work, or nobody new can sign in (a failed mail is counted on the users page and
logged on `security.account`; the invitation can be sent again). A link points at the host the
request came to only if that host is named exactly in `WLXJURY_ALLOWED_HOSTS` (a suffix pattern
never counts); otherwise, and for mail sent without a request, at `wlxjury.host`. A forged `Host`
header therefore never chooses where a password link points. The old address of a changed email
gets a short notice from `wlxjury.mail.from` (`WLXJURY_MAIL_FROM`; unset: the SMTP user if it is an
address, else `no-reply@` the host of `wlxjury.host`): set it to an address the mail server may send
from.

The link's token is in the URL path (`/invitation/<token>`, `/email/confirm/<token>`), so the Apache
access log records it. A link is single use and expires (7 days, 24 hours for an email change), and
the app's own logs mask it, but treat the access logs as sensitive while links are live, or exclude
those paths from them.

A new, empty database has no account. To create the first one (root), set a setup token of at least
32 characters for the install only, e.g. `WLXJURY_SETUP_TOKEN=$(openssl rand -hex 32)` in
`runtime.env`, restart, open `/setup`, enter the token, an email and a password, then remove the
variable and restart. `/setup` is a 404 whenever an account exists or no token is set.

### Sessions and the signing key

A session lasts at most a year from sign-in and a month without a request
(`wlxjury.session.absoluteDays`, `idleDays`). "Keep me signed in" keeps the cookie across browser
restarts. Each request checks the account's session version (`users.auth_version`): "Sign out
everywhere", a password change or password link, a confirmed email change and root's "End sessions"
end every session of that account (a password change keeps the browser that made it). API tokens
are not sessions: an accepted password link or invitation revokes them, a user's own password change
doesn't (the profile's "Revoke all tokens" does). For an account that may be compromised, root uses
"End sessions" and revokes its API tokens at once. Releases keep everyone signed in: cookies are
signed with `PLAY_HTTP_SECRET_KEY`, which a release doesn't change.

Cookies from before the session-time change (security batch S3) are upgraded on their first request
until `session_upgrade_window` ends (30 days after that deploy; V80 records the end). To close the
window early, `DELETE FROM session_upgrade_window` and restart the app.

**Rotating the signing key** ends every session at once, and invalidates the flash and the CSRF
tokens of open pages (a form from a page loaded before the restart shows the "reload the page"
page once). API tokens and mailed links are not affected. Do it when the key may have leaked, when
someone who could read it leaves, or to end every session without touching accounts:

1. Generate a new key on the server (`openssl rand -base64 48`) and write it as the
   `PLAY_HTTP_SECRET_KEY` value in `runtime.env` (one double-quoted value; the file stays `0600`).
   Don't print it into a shared terminal, a ticket or a document, and don't keep the old value.
2. Restart the app: with the next release, or at once with `sudo systemctl restart wlxjury` at a
   quiet time.
3. Check `/health/ready` and sign in once.
4. Old copies: operation folders keep a config snapshot of their time, and a restore of a
   `wlxjury-deploy` record writes its `runtime.env` back. Replace the old key in those copies (or
   delete copies no longer needed), and after any restore check that `runtime.env` holds the current
   key (compare a `sha256sum` of the line, without printing it).

### MariaDB 10.11: the vote gallery index (V85, V99)

V85 stores `vote.rate` descending in the juror gallery's index, which needs MariaDB 10.8 or later;
V99 adds `vote.voted_at` as the index's last column and, on 10.8+, also does V85's change. On
production's 10.6 both log a warning that the index stayed ascending and are recorded as applied.
The server move is planned in `docs/plans/2026-10-19-mariadb-10.11.md`.

**After the move to 10.11**, check the index. **Run V99 again, not V85**: V85's script builds the
index without `voted_at`.

1. Connect as the release tool does (database and host from `runtime.env`; production: `localhost`
   through the socket; for another host add `--host`, `--port` and `--protocol=TCP`):

   ```bash
   sudo mariadb --defaults-extra-file=/etc/wlxjury-runtime/db-admin.cnf --host=localhost --protocol=SOCKET wlxjury
   ```

2. Check each gallery index, its `rate` direction and its columns:

   ```sql
   SELECT INDEX_NAME,
     MAX(IF(COLUMN_NAME = 'rate', COLLATION, NULL)) AS rate_dir,
     GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
   FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote'
     AND INDEX_NAME IN ('vote_jury_round_rate_mon_page_idx', 'vote_jury_round_ratedesc_mon_page_idx')
   GROUP BY INDEX_NAME;
   ```

3. Done is one row, `vote_jury_round_ratedesc_mon_page_idx`, `D`,
   `jury_id,round_id,rate,monument_id,page_id,voted_at`. No row at all: stop and investigate. Any
   other result: run V99 again, with the app stopped (maintenance on), as part of the move:

   ```bash
   sudo mariadb --defaults-extra-file=/etc/wlxjury-runtime/db-admin.cnf --host=localhost --protocol=SOCKET wlxjury \
     < /opt/wlxjury/current/conf/db/migration/default/V99__Vote_gallery_index_voted_at.sql
   ```

   The script reads the state and runs only the `ALTER` it needs (online, about 13 s on 3.35M rows),
   then `ANALYZE TABLE vote`. It waits up to 60 s for `vote`'s metadata lock, and while it waits every
   vote queues behind it, hence the stopped app. Don't add a row to `flyway_schema_history`. Then
   repeat the check.

Every start logs a warning while the index has `rate` ascending on a 10.8+ server or lacks
`voted_at`. A backup taken before the re-run brings the ascending index back when restored: after
every restore on 10.11, check again.

### One app instance

The app assumes one instance per database. Some state lives in the JVM: `RoundImageCounts` (10
minutes), the job scheduler's per-kind limits and turn-taking, the bcrypt sign-in limits, image
download progress, the distribution's cached chunks and Import users results between the redirect
and the page. Rounds are locked in the database and jobs hold a lease, but two instances on one
database were never designed for or tested. The release tool stops the old service before starting
the new one.

### Before rolling back past "Add juror…"

A release from before "Add juror…" has no handler for `round_rebalance` jobs: a queued or running
one never runs there, keeps its round frozen (hidden from its jurors) and can't be cancelled. So,
**on the new release, before restoring an older one**: let every "Adding juror …" and "Reassigning
juror …'s unrated files" job finish or cancel it, likewise a distribution in its `topup` phase ("…
and its files waiting for a juror"), and check that no round is held with no job (root's
**Release** ends one). A failed job needs nothing: its failure released the round.

### Recovery runbook

Maintenance is never cleared automatically. A command that stops ends with
`sudo wlxjury-release status`: start there. It names the current operation and its state, lists
stale operations, and ends with the next command of the section below (`status --verbose` adds the
milestones, the named forms and how to back out). For detail, read `OPERATION/milestones`, `record`
and `operation.log` (`OPERATION` is `/var/backups/wlxjury/NAME`); `previous` and `candidate` in
`record` name the releases. For the current operation the commands work without `OPERATION`;
`restore` only while maintenance is on, `--accept-data-loss` always needs it, and a stale operation
always needs its name.

| Milestones | Meaning |
|---|---|
| No operation folder | `deploy` stopped during its checks. Nothing changed, apart from a new folder under `releases`. |
| Missing file, or the first line is not `started` | Unknown. Keep maintenance on, and inspect `current`, the Flyway history and the log. Every command refuses this operation. |
| `started`, without `migration-may-start` | The database was never migrated and `current` was never switched. |
| `backup-skipped` | No new migrations, so no dump; `restore` only switches the release back. |
| `migration-may-start` | A migration may have run, even if the app never became ready. |
| `ready` | The migration completed; the smoke test decides. |
| `traffic-may-resume` | Writes may have resumed. |
| `restoring` as the last restore milestone | A restore did not finish: the database may be partly imported. |

**Before `migration-may-start`:** if the app was stopped, `sudo systemctl start wlxjury`, then
`sudo wlxjury-release reopen`.

**From `migration-may-start` until reopening**, including a failed smoke test after `ready`: never
just restart the previous release, since its readiness can't show a half-applied migration. Prefer a
forward fix if one is ready; otherwise:

```bash
sudo wlxjury-release restore          # or: restore OPERATION; after ready it asks to type RESTORE
# Check the previous release locally. Then:
sudo wlxjury-release reopen           # or: reopen OPERATION
```

After a failed smoke test, `status` still shows `reopen` as the next command: backing out is a
decision, so take the command from `status --verbose` or this runbook, and confirm it.

**After `restoring` without `database-restored`:** fix what stopped it (a leftover session, space),
then rerun `restore` (with `--accept-data-loss` if it was a restore after reopening). `reopen`
refuses until a restore finishes.

**After `backup-skipped`**, at any later milestone: `sudo wlxjury-release restore OPERATION`, then
`reopen`. No data is lost. To deploy the same ZIP again afterwards, run `deploy` as usual.

**`reopen` exited 3 with `PRUNE FAILED after a successful reopen`:** traffic is open and the
deployment `complete`. Don't rerun `reopen`: fix the cause, then `prune --dry-run` and `prune`.

**After `traffic-may-resume`:** if only `reopen` failed, fix the reported URL and rerun it.
Otherwise prefer a forward fix: restoring now loses later votes, comments and jobs, and needs an
explicit decision and a plan for reconciling them from the rescue dump. Then, naming the operation:

```bash
sudo wlxjury-release restore OPERATION --accept-data-loss
```

Rehearse the deployment and each section of this runbook on a production copy before relying on
them.

## Release checklist

The checks made by hand before or after a release. The deploy itself is
[Deploying a release](#deploying-a-release), the way back the [Recovery runbook](#recovery-runbook);
a release's own items (config keys, migrations, queries) are in the roadmap's
["Before any production deploy"](plans/2026-11-02-roadmap.md#before-any-production-deploy-of-followups).
A change that adds, removes or automates a manual check updates this section
([docs/README.md](README.md) §3).

### Before the release

| Check | What to do | Why by hand | When |
|---|---|---|---|
| The release's own items | The roadmap's [pre-deploy list](plans/2026-11-02-roadmap.md#before-any-production-deploy-of-followups) for this release; [operations](operations/practices.md) §4.2 | They read production's config and data, which no test has | Every release |
| Schema parity with production | On the rehearsal's production copy (after the release's migrations) and on a database migrated from empty by the same ZIP: `python scripts/schema_parity.py --print-query > columns.sql`, then `mariadb -N -B <db> < columns.sql > <side>.tsv` for each, then `python scripts/schema_parity.py fresh.tsv copy.tsv --ignore monument.id`. A `PROBLEM` line is a column `NOT NULL` on production but nullable in the tests: fix it with an idempotent migration before the deploy | Production's tables are older than Flyway in places; the tests only see the migrated schema | Every release with a migration, and after any hand change to production's schema |
| Adding people on the production copy | On the rehearsal app (mail mocked), as a non-root admin: add a new juror on the users page (invitation off and once on), and import one new person on **Import users**; both land with no error and no password (`SELECT password IS NULL FROM users WHERE id = …`). Delete the rows afterwards | It writes into production's schema, which the tests don't have | Every release |
| The `auto` colour mode, looked at | `target/ui-venv/Scripts/python scripts/ui-shots.py shoot --label <release> --mode auto --scheme light --start-server`, the same with `--scheme dark`; look through the shots or `diff` them against the last release's ([UI](ui/practices.md) §5.1) | The browser tests check each frame's mode, not how a page looks | Every release; necessarily when a template, `theme.css` or `main.css` changed |
| Back after a vote in Safari | In Safari (macOS), as a juror: vote with Space in a binary round's large view, press Back: the file shows the new vote; the same with a digit in a rated round | Playwright's WebKit brings the page back fresh even without `no-store` ([testing](testing/practices.md) §10.3) | Every release; necessarily when the large view, its keys or `Secured.noStore` changed |
| Category suggestions against live Commons | As an organizer, in a category field: type a few letters of a real category; Commons' categories are suggested and one can be picked | The tests stub every Commons call | Every release; necessarily when `category-suggest.js`, Tom Select or the endpoint changed |
| Signing in with Google or Wikimedia | On a staging host first: sign in with each provider and link an account ([integrations](integrations/practices.md) §3.3) | Real providers, which the tests fake | Before a production host is listed for OAuth, and when its config or the sign-in code changed |

### After the deploy

| Check | What to do | Why by hand | When |
|---|---|---|---|
| Smoke test before reopening | The release's pages on `127.0.0.1:9000` with a jury host name, then `reopen` | The production server, its config and data | Every deploy |
| Back out if needed | The [Recovery runbook](#recovery-runbook), by the last milestone | A decision | When the smoke test fails |
| What was pruned, and monit | Read `reopen`'s `Keep` and `Deleted` lines; `df -h /`; `stat -c '%a %U:%G' /var/www/jury-images` shows `755 wlxjury:wlxjury`; `/var/log/monit.log` has no `wlxjury` error since the reopen | The server's disk and monit, which no test sees | Every deploy |

### Covered by the browser tests: not repeated by hand

On every PR, in a current build of each engine ([testing](testing/practices.md) §10), `browser.yml`
checks: the theme flash in both schemes and the theme switches (`theme.spec.js`); Back after a vote
in Firefox and voting by key, button, thumb and star (`voting.spec.js`); zero CSP violations,
console errors and failed requests on every page, and the production CSP (`guards.spec.js`);
signing in and out with the CSRF token (`sign-in.spec.js`); 403 pages (`access.spec.js`); the
organizer and admin pages (`organizer.spec.js`); the large view's side sections
(`sections.spec.js`); one `h1` per page and an accessibility scan (`pages.spec.js`).

### Known gaps

- **Back after a vote in Chrome shows the old vote** (U23, [UI](ui/practices.md) §9): the browser
  tests assert that stale page, and fail once it is fixed.
- **The previous majors and the floor (Chrome 109, Firefox 115) are linted, not run**
  ([UI](ui/practices.md) §6.1): a release that changes sign-in or voting can be tried by hand there.
- **The side sections' first frame** (U18) and **Firefox's page error when leaving a gallery**
  (U22) are open triage items.
- **Phones:** not supported yet ([UI](ui/practices.md) §6.2).

## Run with docker-compose

`docker-compose.yml` runs the app and MariaDB in containers, for development and as a simple
deployment for others. It has no TLS, no proxy, no mail server, no backups and no maintenance mode.
From a checkout:

1. Install JDK 17, sbt and Docker (with Compose v2).
2. Publish scalawiki locally at the commit in `project/scalawiki.sha` ([DEV.md](../DEV.md)).
3. `sbt Docker/publishLocal`: builds the image `wlxjury:local` (and `wlxjury:<version>`).
4. `scripts/docker/generate-env.sh`: writes random secrets to `.env` (git-ignored; the names are in
   `.env.example`), keeping values already there.
5. `docker compose up -d --wait`, then open <http://localhost:9000/setup> and create the first
   account (root) with the setup token. Stop with `docker compose down`; `down -v` also deletes the
   database and the thumbnail cache.

The image is the ZIP's files under `/opt/docker`, on Temurin's Java 17 JRE (pinned by digest), run
as `wlxjury` (uid and gid 10001). Its jars are in three layers (third-party, scalawiki, wlxjury's own
with `bin/` and `conf/`), so a code change rebuilds only the top one (`dockerGroupLayers` in
`build.sbt`).

**What the compose file sets.** MariaDB 10.6.22, pinned by digest, with the recommended 256 MiB
buffer pool (`bench/cnf/recommended-60-wlxjury.cnf`; production runs 128 MiB, performance §4.6) as
a command-line option and its data in the volume `db`; its port is not published. The app
takes `WLXJURY_COMPOSE_DB_PASSWORD`, `PLAY_HTTP_SECRET_KEY` and `WLXJURY_SETUP_TOKEN` from `.env`
(compose refuses to start without the first two; MariaDB's root password
`WLXJURY_COMPOSE_DB_ROOT_PASSWORD` is required too), migrates at start, uses Commons anonymously,
and answers to `WLXJURY_ALLOWED_HOSTS` (default `localhost`). The container may use 1.5 GB, the heap
75% of it. Its health check is `/health/ready`, so `up --wait` returns once the schema is the build's.

**Settings and their limits.**
- The port is published on `127.0.0.1:9000` only; `WLXJURY_HTTP_BIND` and `WLXJURY_HTTP_PORT` change
  that. Another port needs `WLXJURY_HOST` to match (the links use it).
- A published port skips the host firewall's input rules (ufw). Never bind `0.0.0.0` counting on the
  firewall; put a TLS proxy on the host in front of `127.0.0.1`
  (`docs/investigations/2026-11-04-simpler-deployment.md`, §3.1).
- `JAVA_OPTS` is set in `docker-compose.yml`, so `.env` can't add to it; override the whole value in
  a `docker-compose.override.yml`.
- Serving others needs that proxy, its host name in `WLXJURY_ALLOWED_HOSTS` and `WLXJURY_HOST`, and
  secure cookies (as `production.conf` sets them, through `JAVA_OPTS`). The proxy reaches the
  container from the Docker bridge's gateway, so the app sees the gateway as every client's address.

**Tested.** `scripts/docker/smoke-test.sh` runs the file as its own project with throwaway secrets:
readiness and its schema, the sign-in and setup pages, the first account, a thumbnail written to the
cache volume, and a restart that keeps both. CI runs it on every PR (`.github/workflows/compose.yml`).

## Local verification

```bash
docker run --rm --network none --mount type=bind,source="$PWD",target=/workspace,readonly --workdir /workspace/scripts/deployment python:3.8-slim python -B -m unittest -v test_deployment
sbt "testOnly deployment.* controllers.ReadinessControllerSpec services.jobs.JobSchedulerSpec db.ConvertToInnoDbMigrationSpec"
sbt dist
docker build -f scripts/deployment/Dockerfile.test -t wlxjury-deployment-test:local scripts/deployment
# as CI runs it
docker run --rm --network none --mount type=bind,source="$PWD",target=/workspace,readonly --workdir /workspace/scripts/deployment wlxjury-deployment-test:local -B -m unittest -v test_database_restore test_release_script
docker run --rm --mount type=bind,source="$PWD",target=/s,readonly koalaman/shellcheck:stable -S style /s/scripts/deployment/wlxjury-release.sh /s/conf/monit/wlxjury-check
```

From Git Bash on Windows prefix the `docker run` lines with `MSYS_NO_PATHCONV=1`, or `/workspace` is
rewritten to a Windows path. At a release build run only the 6 restore tests, which start and
restore the build's real ZIP from `target/universal` (add `test_release_script`, 57 tests on
synthetic ZIPs, only when `scripts/deployment/` changed since the last release; `-e CI` makes a
missing ZIP fail instead of skipping):

```bash
MSYS_NO_PATHCONV=1 docker run --rm -e CI --network none --mount type=bind,source="$PWD",target=/workspace,readonly --workdir /workspace/scripts/deployment wlxjury-deployment-test:local -B -m unittest -v test_database_restore
```

The restore image is self-contained: Ubuntu 22.04's MariaDB 10.6 (`--build-arg UBUNTU=24.04` for
10.11), OpenJDK 17, Python 3 (3.8 is the oldest supported), `curl`, `unzip` and `monit`.
`test_release_script` runs `wlxjury-release.sh` unmodified against a real MariaDB, with stand-ins
only for systemd and Apache; its test names list what it covers (deploy and reopen, skipped backups,
the last-line rule, restore confirmation, deploying a restored release again, refusals, background
jobs, stale operations, `status`, the hash, interrupted migrations and imports, rescue dumps, the
image cache folder, pruning). `MonitCheckTest` covers `conf/monit/wlxjury-check`. The tests use
synthetic data: they don't certify real backups, disk capacity, Apache coverage or outage time,
which stay rehearsal gates.
