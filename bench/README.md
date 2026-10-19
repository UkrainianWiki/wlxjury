# Production-like benchmark: runbook

This runbook lets an agent or a person, with no other context, finish and reproduce the production-like benchmark: the jury tool and MariaDB running as on the production server, measured with Gatling, on an anonymised copy of production data.

- **The why and the design:** [`docs/plans/2026-10-07-prod-like-benchmark.md`](../docs/plans/2026-10-07-prod-like-benchmark.md). Read it first: production facts, the phases, and **Results so far**.
- **Completed 2026-10-10:** phases 3–5, validated full-suite runs, including the post-crash recovery pair. The plan contains the measurements, configuration and archiving decisions, deployment steps and runbook gaps. Raw data stays outside Git. Use new labels when reproducing completed points; existing labels are refused.

- **Latest bgdist follow-up, 2026-10-10:** fresh full-data 128/256 MiB suites passed. Production choice updated 2026-10-11: select 256 MiB to reduce database read demand competing with cached-image reads and shared VPS storage. Latency results remain mixed. This supersedes the earlier 512 MiB recommendation and the subsequent 128 MiB preference; see the plan’s final follow-up section.

- **This file:** the how. Prerequisites, what already exists, how to rebuild any of it, the exact commands for the remaining work, comparability rules, troubleshooting, and what to report.

Everything here runs on a Windows laptop with Git Bash and Docker Desktop. Commands are for Git Bash.

---

## 1. Rules that always apply

- **Personal data.** The production dump contains user emails, names and password hashes. It lives only in `C:\scala\wlxjury-bench-work\proddata\`, outside every repository.
  - It goes straight into the benchmark container's database (a Docker volume) and is anonymised there at once (`scripts/anonymise.sql`).
  - Never commit the dump, a restored copy, a derived dump or query output containing personal data. Never put them in `data/cache` or anywhere in a repository. **Commit scripts; never data.**
  - If a step would copy personal data elsewhere, stop and ask.
- **Fidelity:** model the server, don't throttle it.
  - `--cpuset-cpus=0,1` (2 CPUs by count only), `--memory=4900m --memory-swap=6900m`, MariaDB and the app sharing them in one container.
  - No CPU quota, no I/O throttling.
  - Production's exact packages and MariaDB configuration; production's JVM flags. All of this is built into `bench/server` and `scripts/bench.sh`; don't change it without saying so in the results.
- **Comparability:** compare only runs made under the same conditions:
  - the same suite, the same kind of snapshot, the same machine state;
  - the laptop on mains power;
  - nothing else heavy running.

  If conditions changed between two runs you want to compare, re-run the older one back to back.
- **Don't touch:**
  - other git worktrees and branches (`C:\scala\wlxjury` = `ukwikidev/performance-memory` (PR #183), `C:\scala\wlxjury-bgdist` = `ukwikidev/background-distribution`);
  - other Docker containers;
  - other people's processes (the user's IntelliJ and sbt shell);
  - any server.

  Don't push. Work in `C:\scala\wlxjury-prodbench` (branch `ukwikidev/prod-like-benchmark`).

## 2. Before every session: checklist

1. **Power: on mains, and sleep disabled for the session.** A run that took 6 h because the laptop slept had to be discarded.
   ```
   powershell -NoProfile -Command "(Get-CimInstance Win32_Battery).BatteryStatus"   # must be 2 (mains)
   powercfg /change standby-timeout-ac 0
   powercfg /change hibernate-timeout-ac 0
   ```
   Afterwards, restore the user's previous values. Record them first with `powercfg /query SCHEME_CURRENT SUB_SLEEP`, or ask the user. `run.sh` checks mains before startup and between simulations. For monitoring during simulations, use `run-monitored.ps1` (section 5).
2. **Free memory: at least 8 GB.** Check with `powershell -NoProfile -Command "[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB,1)"`.
   - The container may use 4.9 GB plus swap, Gatling and sbt about 2 GB more, Windows the rest.
   - An earlier run was killed when the system ran low on memory.
   - If memory is short, **ask the user** to close applications (e.g. IntelliJ); never kill them yourself.
   - Docker Desktop's VM must allow at least 7 GB: check with `docker info --format '{{.MemTotal}}'`.
3. **Free disk on C: at least 10 GB.** Check with `df -h /c` and `docker system df`.
   - Snapshots take about 1.3–3.2 GB each (InnoDB about 2.6 GB).
   - A conversion peaks at the datadir size + 1 GB.
   - If short, reclaim Docker build cache (`docker builder prune`), not volumes listed in section 3.
4. **Docker Desktop is running**, and no `wlxjury-bench` container is left over (`bench.sh stop` is safe).
5. **Environment** for every command below:
   ```
   export BENCH_SCRATCH=C:/scala/wlxjury-bench-work/bench   # builds and results, outside the repo
   cd C:/scala/wlxjury-prodbench
   B=bench/scripts/bench.sh; R=bench/scripts/run.sh
   source bench/scripts/suite.sh                            # defines SUITE=(...)
   ```
   `$B sql` runs `mariadb wlxjury` in the container as root, so pass a script on stdin (`$B sql < file.sql`) or a statement with `-e "…"`. Ports: app 19000, MariaDB 13306 (`BENCH_APP_PORT`, `BENCH_DB_PORT`). Gatling's external mode expects these (`test/resources/gatling-perf.conf`, `gatling.external`).

## 3. What already exists (2026-10-07)

**Check what's present before rebuilding anything:**
```
docker images wlxjury-bench-server
docker volume ls | grep wlxjury-bench
docker run --rm -v wlxjury-bench-dists:/d --entrypoint bash wlxjury-bench-server -c 'for d in /d/*; do echo "$(basename $d) $(cat $d/COMMIT)"; done'
ls $BENCH_SCRATCH/results
```

| Kind | Name | What it is |
|---|---|---|
| Image | `wlxjury-bench-server` | Ubuntu 24.04, MariaDB **10.11.14-0ubuntu0.24.04.1** (the new server's package, version-checked), OpenJDK 17, production's `/etc/mysql` files (`bench/server/mysql-conf`), entrypoint as `wlxjury.service` (runs `mariadb-upgrade`, so 10.6 snapshots start). Built from `bench/server/Dockerfile`. |
| Image | `wlxjury-bench-server:jammy-10.6` | The server before the move: Ubuntu 22.04, MariaDB **10.6.22-0ubuntu0.22.04.1** (debs from Launchpad). Use with `BENCH_DOCKERFILE=Dockerfile.jammy BENCH_IMAGE=wlxjury-bench-server:jammy-10.6`. Compared in `docs/plans/2026-10-19-mariadb-10.11.md`. |
| Dist | `prod` | 705689c = deployed 8fdaa7d + only the scalawiki API adaptation needed to build today; migrations up to V53. "Production's current code." |
| Dist | `pr183v551` | 76542ba. **Identical tree to `bcdf984`**, the head of `ukwikidev/performance-memory` (PR #183) with V55.1. |
| Dist | `bgdistv551` | 32a1253. **Same `app/`, `conf/`, `build.sbt`, `project/` as `2a22eba`**, the head of `ukwikidev/background-distribution` with V55.1. |
| Dist | `pr183`, `bgdist` | The same without V55.1. They fail on MyISAM (V56); keep them only for the migration-failure check. |
| Snapshot | `v43-myisam-anon` | The 31 Jan 2026 dump restored and anonymised: MyISAM, Flyway V43 |
| Snapshot | `v53-myisam` | `v43-myisam-anon` migrated to V53 by `prod`: **production's state** (MyISAM) |
| Snapshot | `v53-innodb` | `v53-myisam` with V55.1's conversion done by hand (`convert.sh`): InnoDB, still V53 |
| Results | `p2-prod-myisam` | Phase 2 baseline: `prod` on `v53-myisam`, production config, the suite. **Valid.** |
| Results | `p2-prod-myisam-withcommons` | The first baseline, before round 1721's Commons filter was dropped. Kept for the Commons finding only. |
| Results | `convert-prodcnf` | Phase 3 conversion timing per table. **Valid.** |
| Results | `smoke` | Environment smoke test. |
| Results | `p3-prod-innodb-DISCARDED-interrupted` | Interrupted: the laptop slept, then ran on battery. **Don't use.** |

**Contest re-opening.** The suite needs contest 603 (WLM 2025 Ukraine) re-opened: rounds 1721 and 1775 active, with their jurors, and round 1721's Commons category filter dropped (`scripts/reopen-contest.sql`). **Verify each snapshot** before a run that uses it:
```
$B load v53-innodb && $B start - && $B sql -e "SELECT id, active, category FROM wlxjury.rounds WHERE id IN (1721,1775); SELECT round_id, SUM(active), COUNT(*) FROM wlxjury.round_user WHERE round_id IN (1721,1775) GROUP BY round_id"
```
Both rounds must be `active = 1`, all their `round_user` rows active, and round 1721's `category` NULL. If not:
```
$B sql < bench/scripts/reopen-contest.sql
$B stop && $B save v53-innodb
```
Do the same for every snapshot you create.

## 4. Rebuilding (only what's missing)

**Image:** `$B image`

**Dists** (`sbt dist` of a commit, built outside the repo, stored in the `wlxjury-bench-dists` volume):
```
$B dist prod       705689c
$B dist pr183v551  bcdf984     # performance-memory head (= 76542ba)
$B dist bgdistv551 2a22eba     # background-distribution head
```
705689c, 76542ba and 32a1253 are reachable from this worktree: `git cat-file -t <hash>`. If the branch heads have moved since, build the new heads under new names and say which commits you measured.

**Snapshots:**
```
# 1. restore + anonymise (about 2 min), then save
$B restore-dump C:/scala/wlxjury-bench-work/proddata/wlxjury31012026.sql.gz
$B stop && $B save v43-myisam-anon

# 2. production's state: migrate to V53 with production's code on MyISAM (about 7 min)
$B load v43-myisam-anon && $B start prod
BENCH_APP_TIMEOUT=3600 $B wait-app
$B stop
$B start - && $B sql < bench/scripts/reopen-contest.sql && $B stop
$B save v53-myisam

# 3. the InnoDB conversion by hand, timed per table (about 2.5 min on the laptop SSD)
$B load v53-myisam && $B start -
bench/scripts/convert.sh $BENCH_SCRATCH/results/convert-<label>
$B stop && $B save v53-innodb
```
Check after each step: `$B sql -e "SELECT engine, COUNT(*) FROM information_schema.tables WHERE table_schema='wlxjury' GROUP BY engine"`, and the Flyway version (`SELECT version FROM wlxjury.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1`).

## 5. One measured run

```
$R <label> <dist> <snapshot> <cnf|-> "${SUITE[@]}" [-- <extra -D options for every simulation>]
```

**Monitored Windows full-suite runs (PowerShell 7):**
```
pwsh -NoProfile -File bench/scripts/run-monitored.ps1 -Label <label> -Dist <dist> -Snapshot <snapshot> -Cnf <cnf|->
```
Set `$env:BENCH_SCRATCH = 'C:/scala/wlxjury-bench-work/bench'` in the PowerShell session before invoking it. The wrapper sources the same `suite.sh`, writes `conditions.json` and UTC host samples every 10 seconds, checks power throughout preparation and simulations, and aborts for a power change, three samples below 768 MiB free host RAM, less than 2 GiB free disk, or a host-monitor sample gap over 30 seconds. It validates seven reports, zero failed HTTP requests, the distribution row-count checks and completed jobs, intended buffer sizes, container limits, and the full successful Flyway history. Failures are stopped and renamed `...-DISCARDED-<reason>-<timestamp>`; existing result labels are refused. For a new dist name pass `-ExpectedFlywayVersion <version>`. Use `-AllowLowMemory` only after an explicit exception has been approved, and record the actual starting and minimum measured free RAM. This session's user approved proceeding at 6.9 GiB rather than the 8 GiB prerequisite.

**What it does:**
- checks mains power;
- loads the snapshot (a fresh copy every time, so migrations re-run at startup when the dist has newer ones);
- drops the page cache, starts the server with `bench/cnf/<cnf>.cnf` as `60-wlxjury.cnf` (`-` = production's files only), and waits for the app (up to an hour, enough for migrations);
- checks synthetic emails and required re-opening before warm-up, then runs each simulation of the suite in Gatling's external mode;
- records per simulation: Gatling results, `SHOW GLOBAL STATUS` before and after, `docker stats` and RSS samples. Per run: the effective variables, the Flyway history, the GC log, NMT, MariaDB's slow log, full app/stdout logs and a warning summary.

Results go to `$BENCH_SCRATCH/results/<label>/`. **A full suite takes about 20 min** (the Phase 2 baseline ran from 14:42 to 15:01), plus the startup migrations: about 6 min when a `*v551` dist migrates a V53 snapshot.

**Summarise:**
```
node bench/scripts/summarize.js $BENCH_SCRATCH/results/<label> [$BENCH_SCRATCH/results/<other>...] [--requests "cast vote,gallery page"]
```
It gives per request: count, KO, mean / p50 / p95 / p99. Also the status deltas that matter (`Table_locks_waited`, key cache and buffer pool reads, temp disk tables, slow queries), and CPU / memory.

**A run is valid only if:**
- `run.log` shows mains power throughout;
- no gap in its timestamps longer than the simulation (sleep);
- every simulation `ok`;
- the effective variables match the intended config;
- `flyway.txt` shows the expected migrations.

Otherwise rename the folder `…-DISCARDED-<reason>` and re-run.

**One run at a time.** Runs share the container name, ports and volumes.

## 6. Measured matrix, in order (completed 2026-10-10)

### Phase 3: InnoDB, three code versions (about 1.5 h with the baseline re-run)

```
$R p3-prod-innodb    prod       v53-innodb - "${SUITE[@]}"
$R p3-pr183-innodb   pr183v551  v53-innodb - "${SUITE[@]}"
$R p3-bgdist-innodb  bgdistv551 v53-innodb - "${SUITE[@]}"
```
- `pr183v551` and `bgdistv551` run V54–V59 (and V60) at startup on the InnoDB snapshot; V55.1 is a no-op there. Check `flyway.txt`.
- **Then re-run the Phase 2 baseline** under the same conditions, so MyISAM and InnoDB are compared back to back:
  ```
  $R p2-prod-myisam-rerun prod v53-myisam - "${SUITE[@]}"
  ```
- **Compare:**
  - production's code, MyISAM against InnoDB: the proposed conversion package (engine, row format and charset/collation);
  - production's code against #183 against background distribution, all on InnoDB: the deployable code-and-schema packages.
  - Look especially at votes, galleries and logins **during concurrent round creation**, and at `Table_locks_waited`.

### Phase 4: MariaDB configuration (about 25 min per config)

**Measure the configs with the code that will be deployed** (`pr183v551`) on `v53-innodb`. `p3-pr183-innodb` is the production-defaults point (128M buffer pool).
```
$R p4-pr183-bp512  pr183v551 v53-innodb bp512  "${SUITE[@]}"
$R p4-pr183-bp1g   pr183v551 v53-innodb bp1g   "${SUITE[@]}"
$R p4-pr183-bp1536 pr183v551 v53-innodb bp1536 "${SUITE[@]}"
```
**MyISAM, without converting:** `$R p4-prod-myisam-kb512 prod v53-myisam myisam-kb512 "${SUITE[@]}"` against `p2-prod-myisam-rerun`.

**Memory budget** (the server's 4.9 GB is shared). The container models only the app and MariaDB, so the recommendation must leave room for what it doesn't model:
- the jury tool: about 1.3 GB RSS (heap 1 GB + native);
- Apache/WordPress: about 1 GB;
- the nightly stats job: about 1.3 GB peak at 00:00 Kyiv;
- the OS: about 0.3 GB.

That leaves about **1 GB for all of MariaDB**. Treat `bp1g` and `bp1536` as points on the curve, and recommend the best value that fits: based on measured user latencies/job completion and MariaDB's other buffers, without presuming a larger pool wins. Say what the extra memory would buy if the server got more RAM.

**Other settings worth one run each,** if the first results point at them: `innodb_log_file_size` (96M default), `innodb_flush_log_at_trx_commit=2` (explain the durability trade-off), `tmp_table_size` / `max_heap_table_size` (16M), `max_connections`. Add each as a new file in `bench/cnf/` with a comment saying why.

**Deliverable:** `bench/cnf/recommended-60-wlxjury.cnf`, with every value justified, its memory accounted for, and the expected effect taken from the runs.

### Phase 5: does data size matter? (about 45 min, including the trim)

```
$B load v53-innodb && $B start -
$B sql < bench/scripts/trim-old-years.sql          # keeps contests from 2024 on, rebuilds tables
$B sql -e "SELECT table_name, table_rows, ROUND((data_length+index_length)/1048576) mb FROM information_schema.tables WHERE table_schema='wlxjury' ORDER BY mb DESC"
$B stop && $B save v53-innodb-trim-new
$R p5-pr183-trim-new pr183v551 v53-innodb-trim-new - "${SUITE[@]}"
```
Contest 603 (2025) is kept, so the suite still works. Re-check the re-opening (section 3).

- **Compare against `p3-pr183-innodb`,** and repeat the full-versus-trimmed comparison with `bp512` (measured as `p5-pr183-trim-bp512-recovery` against `p5-pr183-full-bp512-recovery`): latencies, buffer-pool reads and hit rate, memory.
- **If old years cost a lot,** evaluate archiving, with the app changes each option needs and measure the chosen one. Options (see the plan):
  - partitioning `selection` by `round_id`;
  - moving closed contests to archive tables or a schema that **the app's DB account cannot read** (ScalikeJDBC's column discovery breaks logins otherwise; see section 8);
  - compressed read-only tables.
- **If they don't,** say so with the numbers and recommend not archiving.

### Latest background distribution: 128 versus 256 MiB (follow-up complete)

Use fresh labels for another back-to-back full-data pair, with the same latest application tree verified before reusing/building a dist:

```powershell
pwsh -NoProfile -File bench/scripts/run-monitored.ps1 -Label <fresh-bgdist-128> -Dist bgdistv551 -Snapshot v53-innodb -Cnf -
pwsh -NoProfile -File bench/scripts/run-monitored.ps1 -Label <fresh-bgdist-256> -Dist bgdistv551 -Snapshot v53-innodb -Cnf bp256
```

Export BENCH_SCRATCH as in section 5 and satisfy prerequisites; add -AllowLowMemory only with an explicit approved exception. Completed labels p6-bgdist-bp128 / p6-bgdist-bp256 are retained and cannot be reused. The plan records source identity, conditions, latency/job/memory tables and gaps. The current recommendation selects the measured 256 MiB midpoint for reduced read demand under production disk contention. These full-data runs do not retest the older PR #183 / 512 MiB archiving comparison.

## 7. Recording results

- **The plan:** after each phase, update **Results so far** in `docs/plans/2026-10-07-prod-like-benchmark.md`: summary tables from `summarize.js`, the runs' labels, and conditions such as the date and anything unusual.
- **Commits:** commit script and config changes as logical steps, staging only your own files. Never data. Raw results stay in `$BENCH_SCRATCH/results`. Server memory comes from GC/RSS/NMT; the simulation HeapWatcher observes the Gatling client JVM in external mode. Report job completion separately from HTTP form response time.
- **Final report** (the plan's **Rules** section):
  - the baseline and after-conversion numbers;
  - the conversion runbook and times (`convert-prodcnf`);
  - the recommended MariaDB configuration;
  - the archiving recommendation;
  - the deployment order for PR #182 → #183 (with V55.1) → background distribution, and the production steps: backup, free disk, conversion (by Flyway at startup, about 5.5 min down on the SSD, or by hand first), config, restart;
  - open risks.

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `run.sh` exits 2: "NOT on mains power" / "power changed" | Plug in. Discard the partial result and re-run. |
| A simulation takes hours; `run.log` has a long gap | The laptop slept. Disable sleep (section 2) and discard the run. |
| Run killed, or Docker errors about memory | Free memory (section 2). Don't run anything else heavy alongside. |
| Every login fails (Gatling KO on `login`) | The app's DB account must have privileges **on its own database only**. With `SELECT` on `*.*`, ScalikeJDBC's column discovery picks up `performance_schema.users`. The entrypoint grants correctly; check with `$B sql -e "SHOW GRANTS FOR 'wlxjury'@'%'"`. Also check the passwords: `bench<user id>` (anonymise.sql). |
| App never comes up (`wait-app` timeout) | Startup runs pending migrations; V55.1 alone takes about 3 min, everything from V53 about 5.5 min. Look at `docker exec wlxjury-bench tail -100 /var/log/wlxjury/stdout.log`. A failed migration leaves a failed row in `flyway_schema_history`: fix the cause, then delete that row (in a benchmark copy only) or reload the snapshot. |
| "ALGORITHM=INPLACE is not supported" at startup | A dist without V55.1 (`pr183`, `bgdist`) on a MyISAM snapshot. Expected: that's the finding. Use the `*v551` dists. |
| `ServerAlreadyBootingException` from sbt | Another sbt is running on this machine. `-Dsbt.server.forcestart=true` is already in the scripts; don't kill the other one. |
| Paths like `C:/Program Files/Git/var/lib/mysql` in Docker errors | Git Bash path conversion; the scripts set `MSYS_NO_PATHCONV=1`. Do the same for ad-hoc `docker` commands. |
| Disk full | `docker system df`; prune the build cache and stopped containers. Remove only `wlxjury-bench-*` volumes you created and no longer need, never the snapshots in section 3 before the results are accepted. |
| The edit page of round 1721 times out after 75 s | Its Commons category filter was not dropped (section 3). The benchmark must never call Commons; the image maps the Wikimedia hosts to 127.0.0.1. |

## 9. Cleanup when the work is accepted

- `$B stop`, and restore the power settings.
- Keep the volumes and `C:\scala\wlxjury-bench-work` until the user accepts the results. Then, with the user's agreement:
  - remove the `wlxjury-bench-*` volumes, the `wlxjury-bench-server` image and `C:\scala\wlxjury-bench-work\bench\build`;
  - delete the dump copy in `C:\scala\wlxjury-bench-work\proddata\`. It holds personal data.
