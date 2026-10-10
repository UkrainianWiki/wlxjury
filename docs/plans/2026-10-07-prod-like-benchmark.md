# Production-like Benchmark, Storage Engine and MariaDB Configuration Plan (October 2026)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan phase by phase.
>
> **To run or resume the benchmark, follow the runbook [`bench/README.md`](../../bench/README.md).** It has the prerequisites, the existing images, snapshots and results, the rebuild steps, the exact commands for the remaining phases, the validity rules and troubleshooting. Raw results and the dump live outside the repository in `C:\scala\wlxjury-bench-work\`.

**Goal:** Measure the jury tool the way it runs in production, and use those measurements to decide:
1. converting MyISAM to InnoDB (and how to deploy it);
2. a deliberate MariaDB configuration;
3. whether old contest years should be archived.

**Why:** everything measured so far ran on assumptions that don't match production.

**Production facts (2026-10-07):**
- **Server:** a 2-vCPU QEMU VM, Ubuntu 22.04, MariaDB 10.6.22 (Ubuntu package), OpenJDK 17. 4.9 GB RAM plus a 2 GB swapfile. The jury tool runs with `JAVA_OPTS="-Xmx1g -XX:MaxDirectMemorySize=128m -XX:G1PeriodicGCInterval=60000 -XX:NativeMemoryTracking=summary -XX:+ExitOnOutOfMemoryError"` and `MALLOC_ARENA_MAX=2` (`/etc/default/wlxjury`). The same MariaDB also hosts the WordPress databases (`wp_wlm_ua`, `wp_wle`, …), and Apache runs on the same server.
- **Storage engine: all jury tables are MyISAM,** except `flyway_schema_history` (InnoDB). This is from the 31 January 2026 dump; no migration since has changed an engine.
  - The test baseline `B44__Baseline.sql` creates **InnoDB**, so every test and benchmark so far ran on InnoDB.
  - **On MyISAM there are no transactions** (rollbacks are no-ops), **foreign keys are ignored**, and **locking is per table**: every vote `UPDATE` on `selection` locks the whole table against gallery reads.
- **MariaDB configuration: the Ubuntu package defaults,** read from the active files `mariadb.cnf` → `conf.d/*.cnf`, `mariadb.conf.d/*.cnf`. Only the slow log (`long_query_time = 1`, `log_output = FILE,TABLE`) and `max_allowed_packet = 16M` are set. `/etc/mysql/my.cnf.dpkg-new` (with `innodb_buffer_pool_size = 256M`, `query_cache_size = 64M`, …) is a leftover from a package upgrade and is **not** read. Nobody has configured or tuned MariaDB on purpose.
  - Copies of the active files, without `debian.cnf`, are in the session scratchpad: `proddata/mysql-conf/`.
  - Confirm the effective values in the container with `SHOW VARIABLES`, especially `innodb_buffer_pool_size`, `key_buffer_size`, `query_cache_type` and `max_connections`.
- **Flyway:** production has applied up to **V53** (2026-09-07). Deploying PR #183 would run **V54–V59**, and background distribution adds V60.
  - **V56 and V58 use `ALGORITHM = INPLACE, LOCK = NONE`.** MyISAM most likely rejects that, so the deploy would fail at startup. This needs verifying (Phase 3).
  - V54 (an `ALTER` on `images`) rewrites the whole MyISAM table.
- **Data size** (from the January dump, approximate rows):

  | Table | Rows |
  |---|---|
  | `selection` | 3.0 M |
  | `category_members` | 1.37 M |
  | `images` | 960 k |
  | `criteria_rate` | 165 k |
  | `monument` | 122 k |
  | `comment` | 20 k |
  | `round_user` | 7.8 k |
  | `users` | 2.5 k |
  | `rounds` | 1,655 |
  | `contest_jury` | 607 |

  - **Legacy tables:** `selection_backup` (244 k), `rounds_test`, `monuments_all`, `museum`, `schema_version`, `play_evolutions`.
  - **Compared with the Gatling fixture** (38k images, 767k selection rows, 1 contest), production has 25× the images and 4× the selection rows.

**Data:** `wlxjury31012026.sql.gz` (85 MB, MariaDB 10.6.22 dump, no 2026 contests, so it has nothing still being updated) is in the session scratchpad: `proddata/`.
- It contains personal data: user emails and names, password hashes, and maybe comments. **Never commit it, its restored copies or derived dumps, or put them in `data/cache`.**
- Restore it only inside the benchmark container, then **anonymise** the copy right after restoring:
  - replace users' emails and names with synthetic ones (`juror1234@bench.test`, …), keeping ids and roles;
  - set every password to known values, using the app's hashing, so the benchmark can log in as real jurors and organizers;
  - clear or replace free-text personal data where it exists.
- Anonymise **before** any benchmark data leaves the container's volume. Scripts may be committed; data may not.

---

## Phase 1: A server-like environment

**The "server" container image** (Dockerfile under, e.g., `bench/server/`):
- `ubuntu:22.04`;
- `mariadb-server` 10.6 and `openjdk-17-jre-headless` from apt, so the packages match production;
- the copied `/etc/mysql` configuration files;
- the jury tool from `sbt dist`;
- an entrypoint that starts MariaDB, then the app as `/usr/share/wlxjury/bin/wlxjury` does: the same `JAVA_OPTS` and `MALLOC_ARENA_MAX=2`, with `/etc/default/wlxjury` values passed as environment variables;
- the app configuration from the repo's `conf/application.conf`, with the DB, secret and so on overridden by env.

**Run with the server's resource limits, by count only:**
- `--cpuset-cpus=0,1`: 2 CPUs, no speed throttling;
- `--memory=4900m --memory-swap=6900m`;
- no disk I/O throttling;
- the app and MariaDB **share** the 2 CPUs and the memory, as on the server.

Expose 9000 (the app) and 3306 (data loading and checks from the host). Note what isn't modelled: Apache's and WordPress's CPU and memory use, QEMU vCPU speed, and the virtual disk's speed.

**Gatling on the Windows host as the clients.** Give `GatlingTestFixture` an **external-target mode**, e.g. `-Dgatling.target=external -Dgatling.baseUrl=http://localhost:9000 -Dgatling.db.url=jdbc:mariadb://localhost:3306/wlxjury …`:
- no `TestServer` and no testcontainer;
- the data comes from the restored production copy;
- the fixture reads ids (contests, rounds, jurors, images) from it via JDBC.

**Scenarios on real data:**
- pick a large real contest, e.g. WLM-UA 2025 or the largest in the dump, with its real jurors and rounds;
- a closed contest can be "re-opened" in the copy (rounds active again) for voting load;
- keep the existing simulations' flows; organizers are real organizer accounts of that contest.

**Measurement:**
- Gatling stats;
- the app's GC log, and `jcmd <pid> VM.native_memory summary` inside the container (real Linux glibc, so `MALLOC_ARENA_MAX` can finally be checked);
- `docker stats` sampling (CPU, memory);
- MariaDB's slow log and the `SHOW GLOBAL STATUS` deltas: table lock waits, `Key_reads`, `Innodb_buffer_pool_reads`, `Created_tmp_disk_tables`.
- HeapWatcher can't run in-process any more; use the GC log.

## Phase 2: Baseline on production's own setup

- **Production's current code** (branch `ukwikidev/security-local_images` at the deployed version; check what V53 corresponds to), on the restored **MyISAM** data, with production's MariaDB configuration.
- Measure: voting, juror gallery, region filter, round management, round stats, and round creation (distribution) of a realistic size, alone and with several organizers creating rounds while jurors vote.
- This is the "today" reference.

## Phase 3: MyISAM → InnoDB

- **First confirm the hypothesis** on the MyISAM copy: run the pending migrations V54–V59 (and V60) and record which fail. V56 and V58, with `LOCK = NONE`, are expected to fail.
- **Write `V55_1__Convert_to_InnoDB.sql`.** Flyway sorts it between V55 and V56, so it runs before the migrations that need InnoDB, with no renumbering. It contains:
  - `ALTER TABLE … ENGINE = InnoDB` for every live table, with explicit `ROW_FORMAT=DYNAMIC` and a consistent charset;
  - a check of index key lengths, FULLTEXT indexes (InnoDB supports them) and AUTO_INCREMENT behaviour.
- **Decide on the legacy tables** (`selection_backup`, `rounds_test`, `monuments_all`, `museum`, `schema_version`, `play_evolutions`): drop them, keep them as MyISAM, or move them to an archive schema. Check that the app doesn't use them. Propose; don't drop silently.
- **Measure on production-size data:** the conversion time per table, peak disk use (InnoDB copies the table), and the memory needed. Decide whether the conversion can run during Flyway at startup (downtime = conversion time) or should run by hand in a maintenance window before deploying. Write the runbook.
- **Then measure on InnoDB,** against Phase 2:
  - production's current code;
  - PR #183's head (`ukwikidev/performance-memory`);
  - background distribution (`ukwikidev/background-distribution`).
- **The conversion migration belongs in PR #183.** Write it as **one self-contained commit** touching only the migration file (and any test proving it), so it can be cherry-picked onto `ukwikidev/performance-memory`. Don't commit to that branch yourself; report the commit hash.
- **Make tests representative:** add a test, or at least a note, that production may still be MyISAM until V55.1 runs. Consider a test that runs V54–V60 starting from a MyISAM schema.

## Phase 4: MariaDB configuration

- **Benchmark a small, explained matrix** within the server's memory budget: 4.9 GB shared by the jury tool (heap 1 GB plus native, about 1.3 GB RSS), MariaDB, Apache/WordPress and the nightly stats job (about 1.3 GB peak at 00:00 Kyiv).
- **InnoDB** (after Phase 3):
  - `innodb_buffer_pool_size`: 128M default, 512M, 1G, 1.5G;
  - `innodb_log_file_size`;
  - `innodb_flush_log_at_trx_commit` (1 against 2, with its durability trade-off explained);
  - `innodb_flush_method`, `innodb_io_capacity`;
  - `tmp_table_size` / `max_heap_table_size`;
  - `max_connections` (the app's pool is 30; WordPress needs some too);
  - `table_open_cache`; query cache off.
- **MyISAM baseline only:** `key_buffer_size`, to show what production could gain without converting.
- **Deliverable:** a recommended configuration file (`/etc/mysql/mariadb.conf.d/60-wlxjury.cnf`) with each value justified and its memory accounted for, plus the expected effect.

## Phase 5: Data size and archiving old years

- **Quantify how much the data size matters:** the same workload on the full data against a copy with only the last 1–2 years (delete older contests' rounds, selections, criteria rates and images not used by remaining contests in the copy). Compare latencies, buffer pool hit rates and memory.
- **If old years cost a lot,** evaluate archiving options, with the app changes each needs and their effect measured:
  - partition `selection` by `round_id` ranges (check constraints: MariaDB partitioning and unique keys or foreign keys);
  - move closed contests' rows to archive tables or an `wlxjury_archive` schema, readable on demand (results pages, history);
  - compressed read-only tables (InnoDB `ROW_FORMAT=COMPRESSED`, or Aria / MyISAM packed for archives).

  Previous years are read-only in practice; state which features still read old data (e.g. previous-round selection for new rounds only within the same contest).
- If the data size **doesn't** matter much after Phases 3–4, say so and recommend not archiving.

---

## Rules

- **Branch:** `ukwikidev/prod-like-benchmark` (worktree `C:\scala\wlxjury-prodbench`), created from `ukwikidev/background-distribution` at `c3f8e68`. One commit per logical step. Never commit dumps or personal data. No push; nothing on servers.
- **Exception:** the conversion migration is a cherry-pickable commit, per Phase 3.
- **Laptop:** on mains power for every measurement.
- **Builds:** sbt with `-Dsbt.server.forcestart=true`. Docker Desktop is available.
- **Final report:**
  - the baseline and after-conversion numbers;
  - the conversion runbook and measured times;
  - the recommended MariaDB configuration;
  - the archiving recommendation;
  - the deployment order for PR #182 → #183 (+ V55.1) → background distribution, and the production steps (backup, conversion, config change, restart).

---

## Results so far (2026-10-07; Phases 1-2 done, Phase 3 partly)

### Phase 1: the environment
- `bench/server` (Ubuntu 22.04, MariaDB **10.6.22-0ubuntu0.22.04.1**, production's build, from Launchpad; OpenJDK 17.0.20), production's `/etc/mysql` files, `/etc/default/wlxjury` (JAVA_OPTS, `MALLOC_ARENA_MAX=2`, checked in `/proc/<pid>/environ`); `bench/scripts/bench.sh` / `run.sh` / `summarize.js`; Gatling `-Dgatling.target=external` (`GatlingExternalTarget`).
- Run with `--cpuset-cpus=0,1 --memory=4900m --memory-swap=6900m`, no CPU quota, no I/O limits.
- Effective MariaDB values (SHOW VARIABLES): `innodb_buffer_pool_size` 128M, `key_buffer_size` 128M, `query_cache_type` OFF (size 1M), `max_connections` 151, `max_allowed_packet` 16M (the server default; production sets it only for mysqldump), `table_open_cache` 2000, `open_files_limit` 32184, `tmp_table_size` = `max_heap_table_size` 16M, `innodb_log_file_size` 96M, `innodb_flush_log_at_trx_commit` 1, `innodb_flush_method` O_DIRECT, `innodb_io_capacity` 200, slow log on (1 s, FILE,TABLE).
- Data: the 31 Jan 2026 dump restores in 2 min, is anonymised at once (`anonymise.sql`), and is at **V43**: production applied V44-V53 after January. The production code (705689c) migrates it to V53 on MyISAM in 7 min (V53 161 s, V48 84 s, V44 80 s).
- Contest: WLM 2025 Ukraine (603), the largest: 38,392 category images, 103 jurors, 4 organizers. Binary round 1721 (26,382 images, 17 jurors, ~1,550 per juror), rated round 1775 (872 images, 13 jurors). Real rounds are 15-25x smaller per juror than the Gatling fixture's.
- Not modelled: Apache/WordPress and the nightly stats job (CPU, memory, the shared buffer pool), QEMU vCPU speed and the virtual disk (the laptop's SSD is faster), the thumbnail cache directory (LocalImageCacheService's registry on the heap), real network latency.

### Phase 2: production's code (705689c = deployed 8fdaa7d + the scalawiki API adaptation, migrations up to V53) on MyISAM, production's config
mean / p95 ms, one run:

| request | alone | 10 admins creating rounds + jurors voting and browsing |
|---|---|---|
| cast vote | 5 / 9 | 1,419 / 4,523 |
| gallery page | 62 / 135 | 1,770 / 5,203 |
| juror login | 12 / 19 | 6,295 / 18,956 |
| region gallery page | 78 / 165 | |
| rounds list / edit round / round stats | 487 / 608, 1,004 / 1,692, 161 / 325 | |
| first round from the category (76,784 rows) | 9.4 s | large rounds 16.5 / 25.0 s, small 0.6-7.6 s |

- `Table_locks_waited`: 0 alone, **11,409** during the concurrent creations: MyISAM's table locks, as expected.
- Max heap after GC 521 MB (1g heap), no full GC.
- **Finding:** round 1721 filters by a Commons category. On production's code every view of its edit page lists that category through the Commons API: more than 75 s, so Play's default idle timeout (75 s) cuts the page off (7 of 13 edit views timed out at 60 s in Gatling), and "Distribute new files" to it is cut off the same way ("Premature close"). The benchmark copy drops that filter, and the server maps the Wikimedia hosts to 127.0.0.1.

### Phase 3 so far
- **On MyISAM, V56 fails** (`ALGORITHM=INPLACE is not supported for this operation. Try ALGORITHM=COPY`): PR #183's deploy stops at startup after V54 and V55 (59 s) have run, leaving a failed V56 row in `flyway_schema_history` (MariaDB DDL isn't transactional). Run by hand, V58 fails the same way after archiving and deleting its 15 orphans; V57, V59, V60 succeed (V60's `job` table is created InnoDB explicitly).
- **V55.1** (`32a1253`): measured per table, by hand, production config: selection 77 s, images 51 s, category_members 6.3 s, monument 3.2 s, the rest < 1 s; ANALYZE 1.1 s; **145 s in all**. Datadir 1.35 GB -> 2.62 GB, **peak 3.17 GB** (+1.83 GB) while selection is copied. mariadbd RSS peak 223 MB.
- **Through Flyway from MyISAM at V53**: PR #183 + V55.1 and background distribution + V55.1 both start; V54 15 s, V55 44 s, V55.1 160 s, V56 10 s, V57 28 s, V58 61 s, V59/V60 < 1 s: **5.5 min of downtime**. All live tables InnoDB, FK_selection_page_id present.
- The InnoDB runs (production's code, #183, background distribution) are not done yet.

### Phase 3 measurements (2026-10-10)

Same laptop, on mains power with AC sleep and hibernation disabled. The user closed heavy applications and explicitly approved proceeding below the 8 GiB free-host-RAM prerequisite (6.9 GiB at authorization). Each run has `conditions.json` and 10-second power/free-RAM/free-disk samples. Fresh snapshot per run, identical seven-simulation suite and warm-up, unchanged container resource limits and production JVM flags. Full Flyway history is captured; all seven simulations and all HTTP requests passed. Raw results remain outside the repository.

Latency is mean / p95 in milliseconds. Locks are the concurrent-creation simulation delta; MariaDB RSS is the maximum sampled across the suite.

| run | vote alone | gallery alone | concurrent vote | concurrent gallery | concurrent juror login | table-lock waits | MariaDB RSS MiB | minimum free host MiB during measurements |
|---|---|---|---|---|---|---:|---:|---:|
| p2-prod-myisam-rerun | 5 / 9 | 75 / 197 | 1216 / 3321 | 1435 / 3332 | 6295 / 19450 | 11486 | 239 | 4195 |
| p3-prod-innodb | 7 / 12 | 19 / 30 | 88 / 531 | 176 / 813 | 536 / 3309 | 0 | 306 | 3719 |
| p3-pr183-innodb | 7 / 12 | 13 / 19 | 11 / 43 | 62 / 396 | 22 / 91 | 0 | 302 | 4120 |
| p3-bgdist-innodb | 7 / 11 | 13 / 21 | 10 / 32 | 44 / 193 | 21 / 60 | 0 | 267 | 4713 |

| run | region gallery | rounds list | edit round | round statistics |
|---|---|---|---|---|
| p2-prod-myisam-rerun | 130 / 343 | 568 / 921 | 1173 / 2675 | 155 / 352 |
| p3-prod-innodb | 27 / 45 | 287 / 512 | 791 / 1396 | 94 / 198 |
| p3-pr183-innodb | 19 / 36 | 13 / 26 | 24 / 43 | 23 / 39 |
| p3-bgdist-innodb | 21 / 45 | 12 / 19 | 22 / 38 | 25 / 36 |

The PR #183 and background-distribution comparisons include their accompanying migrations (V54-V59 / V60), so they measure the deployable code-and-schema packages rather than Scala-only changes. Round creation figures are submit-to-completion from the simulation report; its heap figures belong to the Gatling client JVM in external mode and must not be used as server heap measurements. Use each run's server `gc.log` and NMT capture instead.

The runbook and runner originally used `MAX(version)` / `installed_rank > 52` to inspect Flyway. Versions are strings and installation ranks are not versions: the former returned `9`, and the earlier baseline history file was empty. This session records the full history ordered by installation rank and validates the final successful migration (V53 / V59 / V60). ISO timestamps, refusal to overwrite results, exit cleanup, and nonzero exit on measured-simulation failure were added before all these runs; the workload was unchanged.
