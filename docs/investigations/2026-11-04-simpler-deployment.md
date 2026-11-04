# A simpler deployment for wlxjury: containers or not

Investigation results, 2026-11-04. Not planned: the user decided to record the findings only
(2026-11-04). It describes what wlxjury needs from a host and what containers would change; the
deployment itself is in [deployment.md](../deployment.md). The companion record on Wikimedia's
platforms is [2026-11-04-wikimedia-cloud.md](2026-11-04-wikimedia-cloud.md).

**Summary.** Containers don't make wlxjury's production deployment simpler today. The app runs as
one JVM behind a reverse proxy, with MariaDB and an SMTP server reached on loopback. When the
database and the mail server run on the host and another web server owns ports 80 and 443, a
container wraps only the JVM: it needs host networking to reach them and to keep Play's loopback
proxy trust, and the reverse proxy and the release tool stay. A container adds a runtime to run,
base images to patch and an image store to prune, and doesn't shorten the outage, because
migrations run under maintenance and the app must run as one instance. The recommendation is to
keep the ZIP, systemd and Apache, and to offer containers to *other* deployers as a reference
image with a Compose example. If a container is ever wanted in production, the best fit is
**rootful Podman with a Quadlet unit and `Network=host`**: no daemon, no `docker` group, no
published ports, so Docker's firewall bypass doesn't arise. Quadlet needs Podman 4.4 or later
(e.g. Ubuntu 24.04's 4.9.3; 22.04 ships 3.4, which has none). Kamal doesn't fit.

## 1. What wlxjury needs from a host

- One JVM (Java 17), started by systemd from a versioned `sbt dist` ZIP, bound to `127.0.0.1:9000`.
- A reverse proxy in front (Apache today, with the maintenance flag, the probe filter and the
  image-cache vhost in `conf/apache`). Play trusts forwarded headers from loopback only, and
  `/health/ready` answers literal-loopback clients only.
- MariaDB, reached as `wlxjury@localhost`, and an SMTP server (`localhost:25` by default).
- The image cache directory (`wlxjury.thumbs.local-path`), written by the app and served directly
  by the proxy's image vhost.
- `wlxjury-release` for routine releases: lock, maintenance flag, writer pause, dump before
  migrations, symlink switch, readiness, `restore` and `reopen`, tested on every PR against a real
  MariaDB.

## 2. The options

- **1. Keep and simplify.** The ZIP, systemd and Apache stay as they are.
- **2a. Compose, app only.** The Docker Engine with one app container, run by a systemd unit.
  MariaDB, the proxy and mail stay on the host.
- **2b. Compose, app and MariaDB.** As 2a, with a second container for the database.
- **3. Podman Quadlet.** A `wlxjury.container` file, from which systemd generates
  `wlxjury.service`: rootful with `Network=host` and the service account's user (3r), or rootless
  under that account (3u). Rootless Docker is the near equivalent of 3u.
- **4. Kamal 2.** Its deploy tool with kamal-proxy, over SSH.

| | 1 Simplify | 2a Compose, app | 2b Compose, app + DB | 3r/3u Podman Quadlet | 4 Kamal |
|---|---|---|---|---|---|
| Deploy | `wlxjury-release deploy ZIP SHA` (as today) | pull or load the image by digest before maintenance; the tool records the digest instead of switching `current`; `systemctl restart` | as 2a | as 2a, with `podman` and a digest in the `.container` file (or an `Image=` drop-in) | `kamal deploy` |
| Rollback | `restore OPERATION` (as today) | `restore` imports the dump and puts back the previous digest, which must still be local | the dump runs through `docker exec` | as 2a | `kamal rollback` switches the container, but **can't restore a migrated database** |
| Migration dump and restore | unchanged | unchanged (DB on the host) | changed: `exec`'d dump and import; every backup job must reach into the container | unchanged | not covered: hooks only |
| Downtime | maintenance window; dump skipped without migrations | the same; the image is pulled beforehand | the same | the same | kamal-proxy runs old and new **at once**, which breaks "one app instance" |
| TLS / proxy | Apache | Apache stays when another web server owns 80/443 | Apache | Apache | kamal-proxy wants 80/443 |
| Logs | logback file + journal | logback file on a mount; stdout to `journald` or `local` | the same + DB logs | logback file; stdout to journald (Podman's default under systemd) | Docker's driver |
| Security updates | the host JRE is patched by the distribution | the JRE in the image is **not** patched by apt: a scheduled rebuild with a new base digest, then a restart | plus the MariaDB image | as 2a; `AutoUpdate=registry` would restart outside maintenance (not wanted) | as 2a |
| Extra memory | none | dockerd + containerd, roughly 50–150 MB (estimate) | + a second mariadbd with its buffer pool | conmon only, a few MB | Docker + kamal-proxy |
| Extra disk | old releases, pruned | a base layer (about 250 MB) per base update + an app layer per release | + a DB volume | the same | the same + proxy |
| Effort | small | about 1–2 weeks: Dockerfile, CI build, tool changes, test rewrite, rehearsal | 2–3 weeks | about 1–2 weeks; 3u adds 2–3 days (ID mapping, linger, ownership) | doesn't fit |
| For other deployers | Ansible roles that assume a particular OS and Apache | an image plus `compose.yaml`, up in an hour | the easiest start: `docker compose up` with DB | Quadlet files: easy on recent Fedora, RHEL and Debian | only for Kamal users |

**Why Kamal doesn't fit.** kamal-proxy binds ports 80 and 443 and issues its own Let's Encrypt
certificates, which conflicts with any other web server on the host. Turning it off
(`proxy: false`) also turns off what Kamal is for, the gapless deploy. That deploy runs the new
container beside the old one, which the app forbids ("one app instance": per-JVM caches and
limits, job leases, migrations under maintenance). Kamal has no dump before a migration and no
restore: it would replace the tested part (`wlxjury-release`) and leave the hard part manual.

**Why 2b is worse than 2a.** Two MariaDB servers on one host when other databases stay on the
host; every dump, `wlxjury-release`'s dump and restore and the restore tests would need rewriting.
The one gain, a newer MariaDB (V85's descending index needs 10.8+), comes from upgrading the host's
MariaDB too.

**What containers would change in the release tool.** `current` (a symlink to a release folder)
becomes "the image digest the unit runs". The lock, maintenance, writer pause, dump, readiness,
milestones, `restore` and `reopen` stay. The restore tests stub `systemctl` and run the ZIP's
launcher; they would need a container runtime in CI or a stub that runs the image's command. The
deploy tooling doesn't shrink: containers rework it, and a release is still a reviewed ZIP plus its
digest.

## 3. Docker deployment problems and their mitigations

### 3.1 Published ports bypass the host firewall

Rootful Docker routes published ports in the `nat` table, before a host firewall's INPUT rules
(ufw, for example), so `-p 9000:9000` is reachable from the internet even when the firewall denies
9000 (Docker, "Packet filtering and firewalls").

| Mitigation | What it does | Verdict |
|---|---|---|
| **No published port: host networking** (`network_mode: host`, `Network=host`) | No DNAT rule at all. The app binds `127.0.0.1:9000` itself. It reaches a host MariaDB and SMTP server on loopback unchanged, and the proxy connects from 127.0.0.1, so `trustedProxies` (loopback), `/health/ready`'s loopback check and client IPs keep working | **Recommended** when the proxy, database and mail run on the host, with Docker or Podman |
| Publish on `127.0.0.1` only (`-p 127.0.0.1:9000:9000`); as a backstop, the daemon's `"ip": "127.0.0.1"` or the network option `host_binding_ipv4` | Only the host reaches the port. Before Docker **28.0.0**, hosts on the same L2 segment could reach ports published to localhost (moby#45610); 28.0 closed that | Right for bridge-network setups (the reference Compose). With a host proxy the app sees connections from the bridge gateway (172.x), not 127.0.0.1, so proxy trust, the readiness check and client IPs break, and a host database or SMTP server needs new grants and a non-loopback bind |
| `DOCKER-USER` chain | Your own iptables filter rules, run before Docker's | Works with the iptables backend. **It doesn't exist with the nftables backend** (experimental since 29.0.0, still experimental in 29.9.3 of 2026-11-03): you add your own table and base chain at the `forward` hook. A second firewall beside the host's |
| `ufw-docker` (chaifeng) | A third-party script that patches ufw's `after.rules` so `DOCKER-USER` jumps to ufw's forward rules | Only when containers must publish public ports, which wlxjury never needs. Unofficial, iptables only; its README warns rules sometimes need a reboot |
| `"iptables": false` | Docker writes no rules | **No.** The docs say it is likely to break container networking: bridge containers lose outbound NAT (no Commons API or images), and their ports become reachable from the local network |
| Rootless (Podman or Docker) | A user-space forwarder (rootlesskit, slirp4netns, pasta) opens ports as ordinary listening sockets, so the firewall's INPUT rules apply | Avoids the bypass, but `-p` loses the client's source address by default, which breaks the loopback trust as for bridges. Rootless with host networking avoids this; rootless Docker supports it from 29.5 |
| firewalld integration | Docker puts its bridges in a `docker` zone (target ACCEPT) with a `docker-forwarding` policy (29.9.1 fixed zone handling) | Relevant only on firewalld hosts |

**Check from outside after any change,** from a host on another network: `nmap -Pn -p-` against
the public name, over IPv4 and IPv6. Only SSH, 80 and 443 should be open; 9000, 3306 and 25 must be
filtered or closed. On the host: `ss -tlnp` shows 9000 only on `127.0.0.1`, and
`iptables -t nat -S | grep DNAT` (or `nft list ruleset | grep dnat`) shows no rule for 9000. Scan
once at the switch and again after a Docker or firewall upgrade.

### 3.2 Who has root

The Docker daemon runs as root, and membership of the `docker` group equals root without a
password (`docker run -v /:/host`). **Never add an operator's or an agent's account to the
`docker` group**: it turns read-only access into passwordless root and bypasses the sudo prompt.
Use `sudo docker …`. Rootful Podman needs sudo the same way and has no group to misuse. Rootless
Podman is managed with `sudo -u <account>` or `machinectl shell`. With journald logging the app's
logs stay readable by the groups that can read the journal today; check that for a rootless user's
journal.

### 3.3 Logs

The default `json-file` driver doesn't rotate. Set `{"log-driver": "local"}` in `daemon.json`
(defaults 20 MB × 5 files, compressed) or use `journald` with journald's own `SystemMaxUse`. Prefer
**`journald`**: it is Podman's default under systemd and needs no logrotate. Keep the logback file
appender on a bind mount of `/var/log/wlxjury`.

### 3.4 Secrets

`runtime.env` has one double-quoted value per line, read by systemd's `EnvironmentFile`, which
strips the quotes. `docker run --env-file` (and probably Podman's) takes the quotes literally
(check on the target version). So either render a second, unquoted env file from the same vault
(root-only `0600`), or keep systemd parsing it: `[Service] EnvironmentFile=` plus Quadlet's
`EnvironmentHost=true` (which also passes the unit's `PATH`; test it). "Docker secrets" are a Swarm
feature; Compose secrets without Swarm are bind-mounted files under `/run/secrets`, which would need
`*_FILE` support in the app; Podman `Secret=` is stored unencrypted by its file driver. **Keep
`/etc/wlxjury-runtime` as the single source**, mount `production.conf` and `logback.xml` read-only.
Root (and the `docker` group) sees the variables in `docker inspect`, as root sees
`/proc/<pid>/environ` today.

### 3.5 Startup, ordering and health

A Docker restart policy (`unless-stopped`, `always`) starts the container at boot and after crashes
**outside systemd**. That defeats `After=mariadb.service` and the release tool's hold drop-in
(`Restart=no`), which stops a failed start from re-running migrations. Use `restart: "no"` and a
systemd unit that runs `docker compose up` in the foreground, with
`After=docker.service mariadb.service network-online.target`, `Restart=on-failure` and
`TimeoutStopSec=360` (and `docker stop -t 360`: the default 10 s kills the job workers). Quadlet
gets this naturally: the generated unit takes the `[Unit]` ordering and drop-ins, the hold
included. Keep the release tool's readiness check from the host (`curl 127.0.0.1:9000/health/ready`,
which works with host networking). A container `HEALTHCHECK` needs `curl` in the image and is
informational only; Podman's `Notify=healthy` can make systemd wait for it (check the version).

### 3.6 Locale, time zone and file ownership

- **Locale is a real trap.** The image cache stores decoded Commons names (Cyrillic and others;
  `LocalImageCacheService`). Under JDK 17 with no `LANG` (the POSIX locale), `sun.jnu.encoding` is
  ASCII and those paths throw `InvalidPathException: Malformed input`; `-Dfile.encoding=UTF-8`
  doesn't fix file *names*. Set `ENV LANG=C.UTF-8` in the image, and test a Cyrillic file name at
  start-up ([deployment.md](../deployment.md), "One-time setup").
- **Time zone:** mount `/etc/localtime:ro`, set `Timezone=local` (Quadlet) or `TZ`, so log times
  line up with the proxy's and the journal.
- **Ownership:** run as the service account's user and group. Bind-mount the cache directory at the
  same host path, because the proxy's image vhost serves it directly; a named volume would hide it.
  In 3u use `UserNS=keep-id`, or files get mapped owners the vhost may not expect. Check the cache
  directory's mode: only the app should be able to write it.

### 3.7 Pinning and the two-week rule

Pin the base by digest (`FROM eclipse-temurin:17-jre-noble@sha256:…`), let Renovate or Dependabot
propose bumps, and keep the rule that a dependency must be at least two weeks old
([dependencies](../dependencies/practices.md)). One exception would need deciding: a base digest
carrying only OS or JRE security fixes, since waiting two weeks on a JRE CVE is worse than a
distribution's JRE patched within a day. Record the base digest in an image label
(`org.opencontainers.image.base.digest`) next to the build identity from `wlxjury-build.json`.

### 3.8 Supply chain

- **Built in GitHub Actions from the reviewed ZIP and pushed to GHCR** with
  `actions/attest-build-provenance`; verify with `gh attestation verify oci://ghcr.io/… --owner …`
  before the pull, and deploy **by digest**, never by tag.
- **Or built on a developer machine and sent with `docker save | ssh … sudo podman load`**, checked
  by sha256 as the ZIP is. No registry or credentials on the host.

Don't build on the production host: sbt's Coursier and build caches take gigabytes and the build
competes with the app for memory. Docker Desktop's WSL disk image grows and never shrinks by itself
(`docker builder prune`, compacting the vhdx); building only in CI avoids it.

### 3.9 The image cache and one instance (OP5)

Bind-mount the cache read-write into exactly one container. Two must never share it: the cache's
start-up walk deletes every `.tmp-` file, so a second instance would delete the first's in-flight
writes ([operations](../operations/practices.md), OP5). That is one more reason for "one app
instance" and against Kamal's overlapping deploy, or a rolling `docker compose up` that starts the
new container before stopping the old one. Fix OP5 (`walkFileTree` with `visitFileFailed` set to
CONTINUE, deleting only `.tmp-` files older than about an hour) before any setup that could start a
second container, even briefly.

## 4. Recommendation

1. **Production: Option 1.** Keep the ZIP, systemd, Apache and a host MariaDB. The deploy stays one
   tested script, the JRE is patched by the distribution, and no second firewall or image store
   appears.
2. **Other deployers: a reference container that production doesn't use.** A `Dockerfile` wrapping
   the reviewed ZIP: a Temurin 17 JRE base pinned by digest, a fixed non-root user,
   `LANG=C.UTF-8`, `EXPOSE 9000`. A Compose file that runs the app and MariaDB with secure
   defaults: no published DB port and `127.0.0.1:9000:9000`; the `local` log driver and
   `restart: "no"` under a documented systemd unit; an optional Caddy profile for a host with
   nothing else on 80/443; a mounted `production.conf` and an env file. Published to GHCR with
   provenance. The repository's `docker-compose.yml` is being fixed and kept as a development and
   possibly a deployment option (roadmap, "docker-compose: fix and support"); this reference would
   build on it.
3. **If production ever needs a container,** Option 3r: rootful Podman Quadlet, `Network=host`, the
   service account's user, `ReadOnly=true` with the cache and log mounts, `DropCapability=all` and
   `NoNewPrivileges=true`. Never as part of another host change: one change at a time.

**Option 3r, if it is ever chosen** (each step reversible):
1. A `Dockerfile` and a CI job building the image from the reviewed ZIP, pinned, with provenance.
2. `wlxjury-release` accepts `--image DIGEST` beside the ZIP; `current` becomes a file holding the
   digest; `restore` puts the previous digest back; the tests get a runtime stub. The ZIP path stays
   the default.
3. The infra roles install Podman and render `wlxjury.container` (`Network=host`, the mounts of
   §3.6, `LANG`, `Timezone=local`, the env file of §3.4, `After=mariadb.service`,
   `TimeoutStopSec=360`), keeping the classic unit to switch back to.
4. Rehearse on a production copy: a deploy with and without a migration, `restore` at each
   milestone, a Cyrillic thumbnail write, mail, and the outside port scan.
5. Switch in a maintenance window by deploying the running build as an image (no schema change, so
   `restore` is only a switch back).
6. After a few releases, drop the ZIP path from the host. `sbt dist` stays: the image is built
   from it.

## 5. Risks

- **A firewall slip.** A Compose edit that adds `ports: ["9000:9000"]` exposes the app whatever the
  host firewall says. Mitigate with host networking, `"ip": "127.0.0.1"` in `daemon.json`, and the
  outside scan whenever the container config changes.
- **Stale JRE in images.** No apt patching inside them: a weekly rebuild and a CVE scan in CI,
  deploy by digest.
- **Restart behaviour.** A restart policy or `AutoUpdate` restarts outside the release tool's hold
  and re-runs migrations: `restart: "no"`, no `AutoUpdate`.
- **Locale and time zone** differences break Cyrillic file names or shift log times (§3.6).
- **Root equivalence** through the `docker` group (§3.2).
- **Rollback depends on local images.** A restore needs the previous image on the host: never prune
  it, and don't pull during a recovery.

## 6. The user's open questions

1. Accept "don't containerise production now", or want containers anyway (then Option 3r)?
2. Is a reference image and Compose file for other deployers wanted, and when? Published under the
   app repository or the organisation on GHCR?
3. The two-week rule for base images: an exception for security-only base digests? (Only if images
   are adopted.)
4. Image transport, if adopted: GHCR with attestations, or `save`/`load` over SSH?
5. The runtime for the reference Compose: Docker (the most familiar) or Podman?

## Sources

- Docker: [Packet filtering and firewalls](https://docs.docker.com/engine/network/packet-filtering-firewalls/)
  (firewall bypass, `iptables: false`, firewalld zone and policy);
  [nftables backend](https://docs.docker.com/engine/network/firewall-nftables/) (experimental since
  29.0.0, no `DOCKER-USER`); [Port publishing](https://docs.docker.com/engine/network/port-publishing/)
  (loopback binding, the pre-28.0.0 L2 gap); [Engine release notes](https://docs.docker.com/engine/release-notes/)
  (29.9.3 of 2026-11-03, 29.9.1's firewalld fixes);
  [`local` log driver](https://docs.docker.com/engine/logging/drivers/local/);
  [Rootless troubleshooting](https://docs.docker.com/engine/security/rootless/troubleshoot/) (source
  IP not preserved, host networking from 29.5).
- Podman: [podman-systemd.unit(5)](https://docs.podman.io/en/latest/markdown/podman-systemd.unit.5.html)
  (Quadlet); [Ubuntu noble podman package](https://packages.ubuntu.com/noble/arm64/podman) (4.9.3);
  Podman 5.7 on Ubuntu 26.04 per [one secondary source](https://computingforgeeks.com/install-podman-ubuntu-2604/);
  Podman 3.4 on 22.04 per [linuxcapable](https://linuxcapable.com/how-to-install-podman-on-ubuntu-linux/).
- [ufw-docker](https://github.com/chaifeng/ufw-docker) (the fork
  [sschoen/ufw-docker](https://github.com/sschoen/ufw-docker) was read; check upstream before use).
- [Kamal proxy configuration](https://kamal-deploy.org/docs/configuration/proxy/) (ports 80/443,
  `proxy: false`, gapless deploys).
- Estimates, not measured: the Docker daemon's RSS, the Temurin 17 JRE image size (about 90 MB
  compressed for comparable `-jre-noble` tags), and whether `podman --env-file` strips quotes.
