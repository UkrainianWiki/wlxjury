# Performance practices: load tests, benchmarks, optimization, memory

The rules for measuring and changing the jury tool's speed and memory: the rule, why, and an
example in this repository. Query-level rules (indexes, `EXPLAIN`, paging SQL, batches, locks)
are in [db/practices.md](../db/practices.md) §3, §5 and §7, not repeated here. Reviewers tick
[review-checklist.md](review-checklist.md). **Sources:** CLAUDE.md ("Agent efficiency",
"Performance tests (Gatling)"), [`bench/README.md`](../../bench/README.md), the history records
in `docs/history/` (production-like benchmark, 2026-10-07), and the deleted plans named in
`docs/history/README.md` (performance and memory, background distribution, gallery latency,
region improvements).

Production today: one VPS shared with Apache/WordPress; the app with `-Xmx1g
-XX:MaxDirectMemorySize=128m -XX:G1PeriodicGCInterval=60000 -XX:+ExitOnOutOfMemoryError`,
`MALLOC_ARENA_MAX=2` (`/etc/default/wlxjury`, mirrored in `bench/server/default-wlxjury`);
MariaDB with a 128 MiB buffer pool. Real rounds are 15–25× smaller per juror than the Gatling
fixture's.

## Contents

1. [Load tests (Gatling)](#1-load-tests-gatling)
2. [Benchmarks](#2-benchmarks)
3. [Optimization](#3-optimization)
4. [Memory and capacity sizing](#4-memory-and-capacity-sizing)

## 1. Load tests (Gatling)

**1.1 Pick the simulation for the path.** `test/gatling/simulations/`, settings in
`test/resources/gatling-perf.conf` (any key by `-Dgatling.<key>=…`); `sbt gatlingAll` runs all,
`RoundDistributionSimulation` first.

| Simulation | Run it for |
|---|---|
| `VotingSimulation`, `JurorGallerySimulation`, `JurorClickSimulation` | juror pages and votes: gallery, large view, vote → next file (per-click latency) |
| `RegionFilterSimulation` | the region filter and its counts |
| `RoundManagementSimulation`, `AggregatedRatingsSimulation` | organizer pages (round list, all-jurors views) |
| `RoundDistributionSimulation` | creating rounds through the form: each step's submit-to-done time and heap after GC (`target/gatling/distribution-memory.txt`) |
| `ConcurrentRoundCreationSimulation` | 10 admins creating rounds at once while jurors vote (`target/gatling/concurrent-distribution.txt`); `gatling.jobs.*` sets job workers and chunk size |

*Why:* each covers one code path; a change to a juror query isn't measured by the organizer
simulations.

**1.2 The fixture.** As CLAUDE.md "Performance tests (Gatling)" describes: the ~38k-file CSV,
20 jurors, a binary and a rated round, cached in `data/cache/` by `GatlingDbCache.cacheKey`;
bump `GatlingDbCache.FixtureVersion` whenever `GatlingDbSetup.load` writes something different.
Its container has a 512 MiB buffer pool (`GatlingTestFixture`, §4.6). *Why:* a stale dump
measures yesterday's schema. Never copy `data/cache` between worktrees.

**1.3 Organizers think; stress is opt-in.** The organizer flows model a few organizers with
think time; `-Dgatling.roundMgmt.stress=true` / `gatling.aggRatings.stress` run the old
many-sessions worst case. *Why:* dozens of organizers hammering the all-jurors views never
happens and drowned the juror numbers (2026-10 performance plan). Report which mode you ran.

**1.4 Production-like JVM.** Each simulation forks a JVM running `TestServer`. Set its heap and one GC log per JVM:
`set Gatling / javaOptions ++= Seq("-Xmx1g", "-Xlog:gc*:file=target/gatling/gc-%p.log:time,uptime,level,tags")`
(`%p`: one log per forked JVM). `-Xmx1g` is production's heap; run `-Xmx512m` too to check
headroom (§4.1).
`-Dgatling.timing=true` adds `modules.RequestTimingFilter`'s per-request JSON lines
(`target/gatling/timing-*.jsonl`; `scripts/timing-summary.py`); keep it on for both variants.

**1.5 The external target.** `-Dgatling.target=external` runs the simulations against an app
that is already running (the production-like bench server, `bench/scripts/bench.sh`) on whatever
data its database holds (`GatlingExternalTarget`, `gatling.external.*`). *Why:* production's data
shape and server packages, which the fixture doesn't have.

**1.6 Results.** `target/gatling/<simulation>-<timestamp>/`, per-request statistics in
`js/stats.json`. `GatlingCompare` compares runs pass by pass and writes
`target/gatling/gatling-comparison.txt` (until 2026-11-03 it wrote into `docs/`); the V45 index
run is `git show 0a43cc9f:docs/gatling-comparison.txt`. Copy the numbers you cite into the
plan; the directories stay on disk.

**1.7 The fixture by hand (UI checks, screenshots).** `Test/runMain gatling.setup.UiShotsServer`
serves the fixture with its own container. From Git Bash, with Docker running:

```bash
java -Xmx512m -Dfile.encoding=UTF-8 -jar 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar' \
  'set Test / javaOptions ++= Seq("-Xmx1g", "-Dfile.encoding=UTF-8")' \
  'set Test / unmanagedClasspath += Attributed.blank((Assets / packageBin).value)' \
  'set Test / javaOptions += "-Dui.csp=production"' \
  'Test/runMain gatling.setup.UiShotsServer'
```

The third `set` (the production CSP, as `ui-shots.py --csp`) is optional. When
`target/ui-shots/server.json` appears, open its `baseUrl` and sign in as `organizer@gatling.test`
/ `orgpass` (admin of contest 1) or a juror `jurorN@gatling.test` / `passN` (`server.json` names
one with files in both rounds; round 1 is binary, round 2 rated). Create
`target/ui-shots/server.stop` to stop it (app, container and both files). `ui-shots.py shoot
--start-server` uses the same files, so stop a hand-started server first. While it runs, another
sbt in the checkout needs `-Dsbt.server.forcestart=true`. The server sets each image's Commons URL
from its title in its own container only; thumbnails then need the network, and files deleted or
renamed on Commons since the CSV are checked by the proxy as in production.

## 2. Benchmarks

The progressive method of CLAUDE.md ("Agent efficiency"). It applies to SQL, the app path and
the production-like server alike.

**2.1 Reuse, then pilot, then expand.** Check the plans and `docs/history/` for a measurement of
the same path first (B10–B12, write-path §4). Then a small paired pilot on the *app path*
(`GalleryLatencyBenchmark`, a Gatling simulation), not only the narrow SQL: W10's −497 ms was
the SQL's; the app path's ordering constraints moved it to P2b. Expand only if the result could
change the decision; estimate the runtime before a matrix (bench/README lists each phase's).

**2.2 Paired and interleaved.** A and B in one warmed JVM on the same inputs, order alternating
(A, B, B, A per block, or AB/BA pairs), every pair's results compared for equality (B10 added
that: a wrong count had been timed unnoticed). *Examples:* `RegionCountPairedBenchmark`,
`RoundImageBenchmark`; `WritePathReadBenchmark` (15 interleaved pairs after 2 warm-ups) is on
`ukwikidev/write-path-investigation` only.

**2.3 Same settings for both, like with like.** SQL debug logging off
(`-Dscalikejdbc.global.loggingSQLAndTime.enabled=false`), recorded for both variants; the same
buffer pool, fixture and JVM flags; no other DB benchmark or test running against the server, and
no tests while a benchmark runs (check free memory first: the laptop is short of it). Never
benchmark in an sbt session with `coverage` on: scoverage-instrumented classes run slower
(CLAUDE.md; start a new session or run `coverageOff`). A
production-like run models the server rather than throttling it: production's packages, MariaDB
configuration and JVM flags; a CPU count by `--cpuset-cpus`, no CPU quota or I/O limit. Compare
only runs made under the same conditions (suite, snapshot, machine state, on mains power); if
they changed, re-run the older one back to back. *Source:* bench/README §1.

**2.4 Absolute deltas and variation.** Medians with min–max (or p50/p95), the paired delta's
median and range, "B faster in k of n", per-block deltas. Never a percentage of one query as user
impact. Keep outliers; state the limitations (one run, the Docker VM, harness vs production
rates). Metrics of different scope (sampled RSS peaks, GC over the server's life, submit-to-done
including polling) are never substituted for one another (prod-like benchmark).

**2.5 Record it.** In the plan's results section: the commit ids of A and B, the command and
settings, the table, the log under `target/checks/` and the results directory. Machine-readable
results stay on disk. Stop when the decision is supported.

**2.6 The bench server.** `bench/server`: Ubuntu 22.04 with production's MariaDB 10.6.22 build,
OpenJDK 17, production's `/etc/mysql` and `/etc/default/wlxjury`, the app as `wlxjury.service`;
`Dockerfile.noble` is the server after the move (24.04, MariaDB 10.11.14). Runs use
`--cpuset-cpus=0,1 --memory=4900m`. One run at a time: runs share the container name, ports and
volumes. Snapshots (`v53-myisam`, `v53-innodb`, …) and dists live in `wlxjury-bench-*` Docker
volumes: never prune them while results are pending, and remove them only with the user's
agreement (register P10). The runbook is `bench/README.md`. Not modelled: Apache/WordPress and
the nightly stats job, the VPS's vCPU and disk, real network latency.

**2.7 Production data stays out of repositories.** A production dump holds emails, names and
password hashes: it lives outside every repository (`C:\scala\wlxjury-bench-work\`), is restored
straight into the benchmark's database and anonymised there at once (`bench/scripts/anonymise.sql`).
Never commit a dump, a restored or derived copy, or query output with personal data, and never
put one under `data/cache`: commit scripts, never data. *Why:* a committed copy can't be
recalled.

**2.8 The stress harness.** `test/stress/WritePathStress.scala` (`db.stress.WritePathStress`, a
`main`, opt-in, never in `sbt test`): 16 production write paths on threads, each as its own
MariaDB user, invariants checked at the end (db practices §7.7). Its numbers are relative,
between variants of one harness: it uses 600-row chunks (production 15,000), runs jobs inline
without worker limits, and every statement is a round trip to the Docker VM, so a five-statement
vote shows 10 ms p50 there and 1.5 ms on the app path. Count deadlocks and invariant breaks; don't
quote its latencies as production's.

## 3. Optimization

**3.1 Measure before changing.** A change for speed starts from a measured problem on the
fixture or the bench server (§1, §2) and ends with the paired result. *Why:* the 2026-03 index
analysis estimated "20–40 % faster"; the measured V45 indexes did far less on some paths and
most on others (`git show 0a43cc9f:docs/gatling-comparison.txt`).

**3.2 Queries.** Indexes, `EXPLAIN`, covering indexes and deferred joins, keyset paging with a
unique tie-breaker, no filesort, no N+1, batch sizes: db practices §3.1–§3.8.

**3.3 Cache counts, invalidate after commit.** A count shown on a hot page is cached in memory:
`RoundImageCounts` (images per round) and `RegionCountCache` (a category's region counts, also
`gallery.regionCounts` in `conf/application.conf`), each with a 10-minute TTL that only bounds
staleness from outside writers, a version check against a load racing a commit, and an
invalidation by the writers *after* commit (`StepContext.afterCommit`). One app instance is
assumed (`docs/deployment.md`, "One app instance"). *Why:* a recount per view was the slow part
of the galleries (region improvements, 2026-10-25).

**3.4 No heavy aggregation per request.** A per-request `GROUP BY` over a round's votes is read
from `round_image` or a cache instead (db §3.4, §3.7). The region menu loads its counts by script
(`data-regions-url`) so the gallery page doesn't wait for them.

**3.5 Long work is a job.** Filling a round, imports and checks over every round run as jobs
(db §5.5a), in bounded steps (the sizes are in db §5.5). *Why:* in the request, ten admins creating rounds held 328 MB of heap and the threads;
as jobs, 131–161 MB and form answers in ~100 ms (background distribution, 2026-10-07).

**3.6 Image proxy and thumbnails.** The proxy serves a stored file only at the widths the pages
ask for (`LocalImageCacheService.servedWidths`); another width is a 302 to the nearest
(secure-coding §18, P5), so a URL can't make the server rescale per request. `MaxAllowedPx`
caps the width at 3840 and `MaxViewers` the size of its per-viewer cache. Thumbnails are resized in a
cascade from the next larger stored size (`saveResized`, `c60f6bb2`).

## 4. Memory and capacity sizing

**4.1 Heap after GC is the metric.** Judge memory by the live heap after GC from the GC log
(`HeapWatcher` in the distribution simulations), not by RSS or `-Xmx`. Pass criteria for a
memory change: no `OutOfMemoryError` and no full GC at `-Xmx512m`, max heap after GC under about
half of `-Xmx`. *Measured:* one distribution job 62–64 MB per step; 10 concurrent admins
131–161 MB at 1g, 200 MB at 512m (background distribution); the production-like suite 521 MB max
at 1g (prod-like benchmark).

**4.2 Out of memory ends the JVM.** Production runs `-XX:+ExitOnOutOfMemoryError` with systemd
`Restart=on-failure` (`conf/systemd/wlxjury.service.d/deployment.conf`): a clean restart beats a
half-broken JVM. Known risk (R198-1, PR review triage on `origin/stack/series-doc`): a job step
that runs out of memory is claimed again after its 60 s lease and crashes again, so the site
restarts about once a minute until the job is cancelled in SQL. Until `claim` fails a job after
repeated claims, keep steps small (4.3) and watch `job.attempts`.

**4.3 Chunks bound memory.** A step's memory is its chunk: the distribution step, the insert
batch, the import page and `IN` lists have the sizes of db §1.4, §3.5 and §5.5. A bigger chunk
needs a heap and lock measurement (§4.1, db §5.5).

**4.4 Packets.** A JDBC batch is one packet: it must fit `max_allowed_packet` (production 16 MB;
the tests keep 1 MB so an oversized batch fails there first, `DistributeImagesBatchSpec`; db §3.5).

**4.5 Pool and thread budget.** The connection pool, the dispatchers and the job workers have the
sizes of db §5.5–§5.6; a new pool, dispatcher or worker count is checked against that budget, and
MariaDB's `max_connections` (151) must allow the app's pool.

**4.6 MariaDB memory.** Production: a 128 MiB buffer pool. The measured recommendation is
512 MiB (`bench/cnf/recommended-60-wlxjury.cnf`: concurrent buffer-pool reads 8,660 → 24,
category-round completion 4.99 → 3.60 s, ~690 MiB MariaDB RSS), everything else as production;
1 GiB and more bought nothing on this data. The Gatling fixture uses 512 MiB; the test server
(`SharedTestDb.forTests`) 128 MiB on a tmpfs data directory (`size=1g`, so a runaway spec fails
with "table is full" instead of filling the Docker VM), 48 MiB redo log, no doublewrite, flush at
commit off: fast, not durable, never a benchmark. Keep production's durable flushing
(`innodb_flush_log_at_trx_commit=1`).

**4.7 Native memory.** `MALLOC_ARENA_MAX=2` and `-XX:MaxDirectMemorySize=128m` bound what the heap
setting doesn't (glibc arenas, Netty buffers); `-XX:NativeMemoryTracking=summary` on the bench
server shows it. Check RSS on the server after a release that adds a library with native or
direct buffers.
