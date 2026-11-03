# Production-like Benchmark, Storage Engine and MariaDB Configuration Plan (October 2026)

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

## Results (Phases 1–5 complete, 2026-10-10)

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

### Conversion and migration findings (2026-10-07)
- **On MyISAM, V56 fails** (`ALGORITHM=INPLACE is not supported for this operation. Try ALGORITHM=COPY`): PR #183's deploy stops at startup after V54 and V55 (59 s) have run, leaving a failed V56 row in `flyway_schema_history` (MariaDB DDL isn't transactional). Run by hand, V58 fails the same way after archiving and deleting its 15 orphans; V57, V59, V60 succeed (V60's `job` table is created InnoDB explicitly).
- **V55.1** (`32a1253`): measured per table, by hand, production config: selection 77 s, images 51 s, category_members 6.3 s, monument 3.2 s, the rest < 1 s; ANALYZE 1.1 s; **145 s in all**. Datadir 1.35 GB -> 2.62 GB, **peak 3.17 GB** (+1.83 GB) while selection is copied. mariadbd RSS peak 223 MB.
- **Through Flyway from MyISAM at V53**: PR #183 + V55.1 and background distribution + V55.1 both start; V54 15 s, V55 44 s, V55.1 160 s, V56 10 s, V57 28 s, V58 61 s, V59/V60 < 1 s: **5.5 min of downtime**. All live tables InnoDB, FK_selection_page_id present.
- The InnoDB runs and remaining phases are complete; see the dated measurements and final recommendation below.

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

### Phase 4 measurements (2026-10-10)

Same laptop, on mains power with AC sleep and hibernation disabled. The user closed heavy applications and explicitly approved proceeding below the 8 GiB free-host-RAM prerequisite (6.9 GiB at authorization). Each run has `conditions.json` and 10-second power/free-RAM/free-disk samples. Fresh snapshot per run, identical seven-simulation suite and warm-up, unchanged container resource limits and production JVM flags. Full Flyway history is captured; all seven simulations and all HTTP requests passed. Raw results remain outside the repository.

Latency is mean / p95 in milliseconds. Locks are the concurrent-creation simulation delta; MariaDB RSS is the maximum sampled across the suite.

| run | vote alone | gallery alone | concurrent vote | concurrent gallery | concurrent juror login | table-lock waits | MariaDB RSS MiB | minimum free host MiB during measurements |
|---|---|---|---|---|---|---:|---:|---:|
| p3-pr183-innodb | 7 / 12 | 13 / 19 | 11 / 43 | 62 / 396 | 22 / 91 | 0 | 302 | 4120 |
| p4-pr183-bp512 | 7 / 11 | 13 / 19 | 13 / 54 | 77 / 481 | 27 / 104 | 0 | 683 | 4175 |
| p4-pr183-bp1g | 7 / 11 | 13 / 20 | 14 / 56 | 101 / 632 | 29 / 117 | 0 | 1206 | 3549 |
| p4-pr183-bp1536 | 7 / 11 | 14 / 22 | 12 / 43 | 68 / 439 | 27 / 94 | 0 | 1661 | 3196 |
| p2-prod-myisam-rerun | 5 / 9 | 75 / 197 | 1216 / 3321 | 1435 / 3332 | 6295 / 19450 | 11486 | 239 | 4195 |
| p4-prod-myisam-kb512 | 5 / 9 | 98 / 289 | 1304 / 3285 | 1610 / 3475 | 6633 / 18127 | 11445 | 304 | 3934 |

| run | concurrent physical buffer-pool reads | logical buffer-pool requests | hit rate % | temporary disk tables | slow queries |
|---|---:|---:|---:|---:|---:|
| p3-pr183-innodb | 8660 | 20273344 | 99.9573 | 2624 | 18 |
| p4-pr183-bp512 | 24 | 18829187 | 99.9999 | 2624 | 24 |
| p4-pr183-bp1g | 13 | 22398644 | 99.9999 | 2624 | 31 |
| p4-pr183-bp1536 | 8 | 19000669 | 100.0000 | 2624 | 16 |
| p2-prod-myisam-rerun | 0 | 0 | n/a | 24 | 12 |
| p4-prod-myisam-kb512 | 0 | 0 | n/a | 24 | 11 |

### Original completed study and deployment recommendation (2026-10-10)

> Historical recommendation: the latest-bgdist 128/256 MiB follow-up below supersedes the 512 MiB configuration recommendation. The PR #183 / 512 MiB archiving result retains its original measured scope.

Phases 3–5 are complete: 11 validated full-suite runs, 77 measured simulations, 163,865 HTTP requests and zero failed HTTP requests. Each sequential distribution passed all four image/selection-count checks, and all ten concurrent creation operations completed in each run. Background jobs reached done; their completion times are reported separately from form response times. Raw files, including conditions, host samples, status deltas, GC/NMT and Gatling reports, remain in `C:\scala\wlxjury-bench-work\bench\results` and are not committed.

#### Conversion and deployable application packages

| run | category round completion s | concurrent large A / B completion s | concurrent form mean / p95 ms | peak app RSS MiB | maximum server heap after GC MiB | full GCs | total GC pause ms |
|---|---:|---|---|---:|---:|---:|---:|
| p2-prod-myisam-rerun | 8.928 | 14.781 / 23.810 | 8172 / 19739 | 1250 | 744 | 0 | 7454 |
| p3-prod-innodb | 4.094 | 27.012 / 26.877 | 9296 / 26915 | 1315 | 747 | 0 | 7171 |
| p3-pr183-innodb | 4.987 | 19.763 / 19.761 | 6434 / 19746 | 1312 | 757 | 0 | 3338 |
| p3-bgdist-innodb | 4.257 | 23.194 / 23.198 | 103 / 159 | 631 | 162 | 0 | 1713 |

The fresh MyISAM baseline had 11,486 concurrent table-lock waits. The proposed InnoDB conversion removed those table-lock waits and improved concurrent vote / gallery / login p95 from 3,321 / 3,332 / 19,450 ms to 531 / 813 / 3,309 ms on production code. PR #183 on InnoDB reached 43 / 396 / 91 ms, and background distribution reached 32 / 193 / 60 ms at the default 128 MiB pool. V55.1 also standardizes row format and charset/collation, so this is the proposed conversion package, not an isolated engine-only experiment. PR #183 and background distribution include V54–V59 / V60 and are code-and-schema comparisons.

Background distribution lowered peak app RSS and retained much less heap after GC. Its ten concurrent form submissions returned at mean / p95 103 / 159 ms, while work continued in jobs. The two large jobs completed in 23.194 / 23.198 s; PR #183 completed them in 19.763 / 19.761 s. Background small-job completion ranged from 7.456 to 12.848 s, versus 0.630 to 5.525 s for PR #183 (including the zero-output rated filter). Deployment therefore buys response responsiveness and lower memory, not universally faster job completion. Ten admin sessions share four organizer accounts.

RSS peaks are sampled during the measured suite; GC figures cover the captured server lifetime, including startup and warm-up. Background submit-to-done time includes the client’s status-polling delay. These metrics have different scopes and must not be substituted for one another.

#### MariaDB configuration decision

| configuration / data | concurrent vote mean / p95 ms | gallery mean / p95 ms | login mean / p95 ms | buffer-pool physical reads | hit rate % | peak MariaDB RSS MiB | large A / B completion s |
|---|---|---|---|---:|---:|---:|---|
| p3-pr183-innodb | 11 / 43 | 62 / 396 | 22 / 91 | 8660 | 99.9573 | 302 | 19.763 / 19.761 |
| p2-prod-myisam-rerun | 1216 / 3321 | 1435 / 3332 | 6295 / 19450 | 0 | n/a | 239 | 14.781 / 23.810 |
| p4-pr183-bp512 | 13 / 54 | 77 / 481 | 27 / 104 | 24 | 99.9999 | 683 | 20.422 / 20.628 |
| p4-pr183-bp1g | 14 / 56 | 101 / 632 | 29 / 117 | 13 | 99.9999 | 1206 | 25.423 / 25.370 |
| p4-pr183-bp1536 | 12 / 43 | 68 / 439 | 27 / 94 | 8 | 100.0000 | 1661 | 18.205 / 17.910 |
| p4-prod-myisam-kb512 | 1304 / 3285 | 1610 / 3475 | 6633 / 18127 | 0 | n/a | 304 | 14.912 / 22.593 |
| p5-pr183-trim | 8 / 21 | 31 / 84 | 16 / 35 | 5342 | 99.9628 | 301 | 14.328 / 14.248 |
| p5-pr183-full-bp512-recovery | 10 / 26 | 39 / 153 | 20 / 53 | 21 | 99.9999 | 698 | 15.711 / 15.589 |
| p5-pr183-trim-bp512-recovery | 10 / 29 | 39 / 157 | 21 / 59 | 325 | 99.9980 | 552 | 17.411 / 16.106 |

Recommend `bench/cnf/recommended-60-wlxjury.cnf`: a 512 MiB InnoDB buffer pool with all other active production defaults retained. On the full dataset this reduced concurrent buffer-pool reads from 8,660 to 24 and category-round completion from 4.987 to 3.597 s, with 683 MiB peak sampled MariaDB RSS in the original config run; the recovered full-data control reached 698 MiB. Gallery-only p95 remained 19 ms. It did not establish a concurrent p95 improvement (vote/gallery 54/481 ms versus 43/396 ms at 128 MiB); choose it for cache headroom and faster solo creation, not a promise of lower p95. The larger 1 GiB / 1.5 GiB points reached 1,206 / 1,661 MiB RSS and do not fit the allowance. Their gains were limited or mixed: the 1.5 GiB point finished large rounds around 18 s versus 20.5 s at 512 MiB, while concurrent gallery p95 was still 439 ms.

| MyISAM key cache | concurrent key reads | key-read requests | key-cache hit % | table-lock waits |
|---|---:|---:|---:|---:|
| p2-prod-myisam-rerun | 214 | 48450997 | 99.9996 | 11486 |
| p4-prod-myisam-kb512 | 217 | 48354747 | 99.9996 | 11445 |

The starting MyISAM indexes total approximately 546.3 MiB (the conversion’s retained before-table metadata). The `myisam-kb512.cnf` comment claiming its 512 MiB cache holds every index was corrected; the measured setting was unchanged.

Do not deploy a larger MyISAM key cache as the contention fix: the two cache points had essentially the same concurrent key-cache hit rate and 11,486 / 11,445 table-lock waits. Vote/gallery p95 stayed at 3,321/3,332 ms versus 3,285/3,475 ms, far above the converted packages.

The recommendation file sets only the measured buffer-pool change, keeping the rest of the active production configuration. At 512 MiB the principal configured global buffers total about 785 MiB: InnoDB 512, MyISAM key cache 128, Aria page cache 128, InnoDB log buffer 16, and the disabled query cache allocation 1. This leaves about 215 MiB of the runbook’s approximate 1,000 MiB MariaDB allowance for other memory, but per-connection buffers and WordPress demand remain workload dependent. Observed RSS is evidence for this suite, not a hard memory bound. A 768 MiB pool would already make that global-buffer sum about 1,041 MiB before other memory; the 1 GiB and 1.5 GiB points exceed the shared-server allowance. Keep the 1.3 GiB jury-app reserve even when the background build uses less in this dataset; thumbnail cache growth is unmodelled.

Keep durable commit flushing (`innodb_flush_log_at_trx_commit=1`), 96 MiB redo files, O_DIRECT, I/O capacity 200, 16 MiB temporary-table limits, 151 maximum connections and table-open cache 2,000 for this recommendation. No measured failure or sustained pressure isolated those settings as the bottleneck, so their alternatives were not run. Flush mode 2 would weaken crash durability and is not recommended without a separate durability decision. PR #183 creates one temporary disk table per gallery request even in gallery-only runs; increasing the buffer pool does not change that counter. The MEDIUMTEXT-bearing gallery query is a plausible explanation, not proof of memory exhaustion. Do not size temporary buffers from that counter alone.

Startup of PR #183 while migrating the fresh already-InnoDB V53 snapshot (V55.1 is a no-op): 128M 107 s; bp512 80 s; bp1g 76 s; bp1536 64 s. This measures pending index/schema migration startup, not routine restart or full MyISAM conversion downtime. The matrix shows what extra RAM bought on this dataset; larger caches are not justified on the current shared server, and any larger server should be sized with WordPress/statistics included.

Across the four PR #183 buffer-pool points, Max_used_connections peaked at 21; measured InnoDB log-buffer waits totalled 0 and buffer-pool wait-free events 0. These are checks against needless setting changes, not a guarantee for missing production workloads.

#### Concurrent database counters

| run | table waits | row waits / ms | disk temporary tables | slow queries | log waits / buffer wait-free | max connections |
|---|---:|---|---:|---:|---|---:|
| p2-prod-myisam-rerun | 11486 | 0 / 0 | 24 | 12 | 0 / 0 | 12 |
| p3-prod-innodb | 0 | 0 / 0 | 24 | 12 | 0 / 0 | 12 |
| p3-pr183-innodb | 0 | 0 / 0 | 2624 | 18 | 0 / 0 | 20 |
| p3-bgdist-innodb | 0 | 7 / 2414 | 2600 | 7 | 0 / 0 | 25 |
| p4-pr183-bp512 | 0 | 0 / 0 | 2624 | 24 | 0 / 0 | 19 |
| p4-pr183-bp1g | 0 | 0 / 0 | 2624 | 31 | 0 / 0 | 20 |
| p4-pr183-bp1536 | 0 | 0 / 0 | 2624 | 16 | 0 / 0 | 21 |
| p4-prod-myisam-kb512 | 11445 | 0 / 0 | 24 | 11 | 0 / 0 | 12 |
| p5-pr183-trim | 0 | 0 / 0 | 2624 | 9 | 0 / 0 | 20 |
| p5-pr183-full-bp512-recovery | 0 | 1 / 19 | 2624 | 13 | 0 / 0 | 21 |
| p5-pr183-trim-bp512-recovery | 0 | 0 / 0 | 2624 | 15 | 0 / 0 | 20 |

Table-lock waits and InnoDB row-lock waits are different counters. The background run had seven row waits totaling 2,414 ms although its table-lock-wait delta was zero. All measured jobs and HTTP requests completed successfully.

#### Data size and archiving decision

Recommend **not archiving old contests for active-workload performance on the current evidence after selecting the 512 MiB pool**. The fresh post-crash comparison is the appropriate tuned-data-size pair:

| recovered PR #183 / 512 MiB | concurrent vote / gallery / login p95 ms | large A / B completion s | physical buffer-pool reads | peak MariaDB RSS MiB | V53-to-V59 startup s |
|---|---|---|---:|---:|---:|
| full, p5-pr183-full-bp512-recovery | 26 / 153 / 53 | 15.711 / 15.589 | 21 | 698 | 113 |
| trimmed, p5-pr183-trim-bp512-recovery | 29 / 157 / 59 | 17.411 / 16.106 | 325 | 552 | 14 |

Both ran back to back after recovery, on mains, with the same suite, application, config, container limits and JVM flags, using fresh snapshot copies. All seven simulations, HTTP requests, distribution counts and concurrent operations passed in each. Their buffer-pool hit rates were 99.99987% and 99.99797%. The trimmed copy saves about 146 MiB of sampled MariaDB RSS and makes pending schema/index migration startup much faster, but demonstrates no live latency or creation-time gain at the chosen config. It also has more physical buffer-pool reads in this pair, although both rates remain very low. Ordinary restart time and production historical reads were not measured.

At 128 MiB the earlier full/trim pair did improve concurrent vote/gallery/login p95 from 43/396/91 to 21/84/35 ms, and large-round creation from about 19.8 to 14.3 seconds. That demonstrates a size effect in that earlier configuration/session. It does not justify an archive feature once the tuned back-to-back pair has no active-workload benefit. The full-data 512 MiB control itself changed from the earlier 54/481/104 ms to 26/153/53 ms after recovery; do not attribute that difference to a configuration change. These are unseeded single-suite observations under separate session conditions. Keep both completed controls and prefer the recovered pair for the tuned-size decision.

Consequently no archive layout, partitioning or compression experiment was executed, and no archive schema or archive account was created. The conditional archive branch of the runbook is not needed for the measured 512 MiB outcome. An archive could still be justified later by storage/backup requirements or a different production workload, but those requirements are absent from this brief. It must preserve past-contest lists, galleries, statistics and CSV exports, plus previous-round relationships within a contest. A separate cold schema would need an explicitly routed read-only service/pool and SELECT-only account; the normal app account must remain unable to read it. Partitioning would require reviewing global unique keys and the V58 selection/image foreign key; compression would need storage, CPU and historical-read measurements. None of those alternatives has a measured advantage in this study.

The trimmed copy keeps contests from 2024 onward. It retains shared monument data and legacy tables, then rebuilds the changed tables; it is not a production deletion script. Compare the same 2025 contest at 128 MiB and 512 MiB in the table above. Table-row estimates for InnoDB are approximate; disk-size figures and count checks are recorded separately in `trim-v53-innodb.log` and each run’s `snapshot-checks.txt`.

| table after V59 startup | full rows estimate | trimmed rows estimate | full data + indexes MiB | trimmed data + indexes MiB |
|---|---:|---:|---:|---:|
| selection | 2,987,148 | 524,543 | 1234.9 | 190.0 |
| images | 709,442 | 139,060 | 1004.6 | 114.7 |
| category_members | 1,364,880 | 136,400 | 103.3 | 10.0 |
| criteria_rate | 166,246 | 25,216 | 14.0 | 2.0 |
| monument | 134,285 | 122,305 | 111.8 | 111.8 |

The retained V53 snapshot copies were reported by du as about 2.6 GiB full and 820 MiB trimmed. After V59 startup, table data/index metadata in the table above uses the same schema for both copies. Monument data/index size stayed unchanged; differences in its row estimate are sampling noise, not deleted monuments. Startup migrating the already-converted V53 snapshot to V59 was 107 s full versus 20 s trimmed at 128 MiB.

#### Conversion timing, maintenance and deployment

The retained `convert-prodcnf` measurements from 7 October give 145.156 s total: selection 76.512 s, images 51.233 s, category_members 6.289 s, monument 3.168 s, other tables each under one second, and ANALYZE 1.130 s. Sampled datadir usage was 1,411,383,433 → 2,747,620,623 bytes, peaking at 3,326,269,480 bytes (1.314 → 2.559 GiB; peak 3.098 GiB, additional peak 1.783 GiB). MariaDB RSS peaked at 222.5 MiB. These are conversion-only historical measurements, not new October 10 timings. Existing end-to-end MyISAM/V53 migration runs started both V55.1 builds successfully in about 5.5 minutes on the laptop SSD; the new InnoDB runs also validate the migration’s no-op path. Allow a longer production maintenance window and time it on a current copy; the January dataset and laptop disk do not predict production duration.

V55.1 already exists as self-contained cherry-pickable commit `32a1253`, touching the migration and its test/fixture. It converts twelve live tables, preserves values and AUTO_INCREMENT, normalizes charset/collation, and skips already-converted tables. Seven legacy tables stay MyISAM, including `user_contest`; do not silently drop them. Conversion does not recreate foreign keys that MyISAM ignored historically. V58 explicitly adds the selection-to-image constraint and archives its fifteen historical orphan rows.

The retained `ConvertToInnoDbMigrationSpec` report dated 7 October has four tests, zero errors/failures. It covers the MyISAM failure without conversion, preservation/conversion, the second-run no-op and the full pending migration chain. The new full-suite runs add production-size startup/history and workload verification; no application or migration implementation was changed in this session.

Production sequence (instructions only; nothing was changed on a server):

1. Integrate/review PR #182 → PR #183 with V55.1 → background distribution in that order. Do not deploy the old #183/background packages without V55.1 onto MyISAM: V56 fails at startup. Verify the migration is present in the actual release; the measured packages are `prod` 705689c, `pr183v551` 76542ba (tree identical to bcdf984), and `bgdistv551` 32a1253 (application/config/build/project identical to 2a22eba).
2. Schedule maintenance outside the midnight Kyiv statistics job, stop writes/app jobs and take a consistent, restorable database backup. MyISAM needs writes paused or suitable table locking; a transaction-only backup is insufficient. Include the current configuration and establish restore readiness before conversion.
3. Check current datadir and free space, retaining the backup plus growth/copy headroom and logs. The measured conversion needed about 1.8 GiB beyond its starting datadir; reserve more for the current dataset, pending indexes, WordPress and operational margin.
4. Either let Flyway run V54–V59/60 on first startup during the window, or run the exact `conf/db/migration/default/V55_1__Convert_to_InnoDB.sql` by hand while writes are stopped, then deploy. The latter only moves conversion earlier in the window; it does not remove the remaining migration work. The migration is tested as a no-op after manual conversion.
5. Install the reviewed `bench/cnf/recommended-60-wlxjury.cnf` as `/etc/mysql/mariadb.conf.d/60-wlxjury.cnf`, restart MariaDB in the maintenance window, then start the reviewed app release and wait for successful migration/startup. Check effective variables, last successful Flyway version ordered by installed_rank, all live table engines/collations, row counts, grants restricted to the app database, and expected constraints. Smoke-test login, voting, galleries, management and complete round creation.
6. Monitor MariaDB/app RSS, host memory/swap, p95, job completion and errors alongside WordPress and nightly stats. Keep backups through acceptance. MariaDB DDL is not transactional; a failed partial migration requires recovery under the maintenance plan, not an assumed rollback.

#### Recovery and validation record

Codex crashed during `p5-pr183-trim-bp512`; its last host sample was 2026-10-10T13:50:58Z and its log ended during RoundManagementSimulation. No completion was validated. It is retained as `p5-pr183-trim-bp512-DISCARDED-codex-crash-20261010` and is excluded from every table and request total here. Recovery confirmed that no benchmark Java process or container survived. Existing validated results and commits `af4553f`, `890b91f`, `687728f`, plus the host-monitor gap checks in the working tree, were preserved.

Recovery used a new immutable `v53-innodb-trim-recovery` snapshot, verified the same re-opening and synthetic emails, and measured a fresh full-data control followed immediately by the trimmed 512 MiB run. The latter completed at 2026-10-10T19:01:14+03:00. Their raw result labels appear above. Preparation and measurement memory minima are recorded separately; neither run reached the three-sample low-memory abort condition. Subsequent reproduction now also clears VM cache before snapshot copying to reduce preparation pressure, while retaining the cache clear after loading. This preparation/diagnostic hardening was added after the measurement pair and does not change the measured suite.

The public scripts now preserve full application/stdout logs and reject incorrect synthetic-email/re-opening checks before warm-up. Earlier runs only have the warning-line summary, so missing historical exception stack context cannot be recovered. Shell/PowerShell parsing and targeted snapshot-gate checks verify the final script edits; the eleven measured suites verify the workload and the monitoring/validation path before these final diagnostic changes.
#### Runbook gaps and limits

- Fixed: string `MAX(version)` returned 9 and the old installed-rank filter produced an empty history file. Capture full history and validate successful final V53 / V59 / V60.
- Fixed: `run.sh` only checked power between simulations, lacked host-memory monitoring, allowed result reuse and could finish successfully after a failed simulation. ISO timestamps, cleanup, failure exits, label protection and the PowerShell 7 monitor now provide reproducible checks. The monitor also validates seven zero-KO reports, distribution count checks, job completion, effective buffer sizes and container limits.
- Clarified: the conversion is more than an engine change; application-package comparisons include schema migrations; client HeapWatcher output is not server heap evidence.
- Portability: the plan’s Claude-only dependency on a separate execution skill was removed; the repository commands and monitoring wrapper are the complete reproduction instructions.
- Workload gap: “Rated avg 7” produced zero qualifying images/selection rows in every run. Its filter/query and job completion were exercised, but the suite does not measure insertion of a nonempty rated-threshold round. The random voting/distribution is not seeded, and most points have one suite rather than statistical repetitions; the 512 MiB full-data repeat was made after recovery under new session conditions, do not treat small millisecond differences as established regressions.
- Workload/report gap: the concurrent “New files” operation repeats the solo top-up on the same round. In the background run it added zero images/rows, so its concurrent measurement is a no-op scan rather than a new-file insertion load. Old synchronous reports show total round rows while job reports show rows added; those count fields are not comparable. Together with the empty rated filter, the ten concurrent operations include eight nonempty new-round creations and two empty operations. Future workload coverage should provide eligible rated images and an unfilled top-up target without silently changing this comparison suite.
- Diagnostic gap: original `app-warnings.log` keeps warning lines and drops exception stack context. Background-build startup emitted scheduler heartbeat-failed warnings before migrations finished; source inspection shows poll has a table-existence guard while heartbeat does not. This is an inferred startup-order issue, not a captured root cause. Measured jobs nevertheless all completed; guard/diagnose startup before production release.
- Fidelity limits: the anonymized January snapshot has no 2026 contests; Apache/WordPress, midnight statistics, real network latency, production virtual-disk speed and thumbnail-cache population are absent. Shared database memory and missing old MyISAM foreign keys must be checked on the live deployment. No other worktree, branch, container, user process or server was changed; no push was made.
- Comparison limits: round 1721’s Commons category filter was removed in every measured copy and Wikimedia hosts resolve locally, so these runs omit the known Commons edit-page delay. The buffer-pool matrix measures PR #183; the background package was measured at 128 MiB only, so verify that later release with the chosen configuration before rollout.
- Prerequisite exception: the user explicitly authorized proceeding at 6.9 GiB instead of 8 GiB free host RAM. All runs remained on mains with AC sleep/hibernate already disabled; 10-second sample gaps show no sleep. The first InnoDB run briefly reached 753 MiB and the recovered full-data control reached 405 MiB during snapshot preparation. Both recovered before warm-up and did not meet the three-sample abort threshold. Their measured-stage minima were 3,719 and 2,660 MiB respectively. Preserve that distinction when reproducing.

Actual recorded free host RAM at run start ranged from 1.889 to 6.648 GiB, reflecting preparation/cache residency rather than a constant 6.9 GiB. Across measured stages the lowest free host RAM was 2,660 MiB; host sample gaps stayed below 11.048 seconds.

The benchmark container is stopped after each run. Original AC timeout values were already zero and were not changed. Keep raw results, snapshots, image/dists and the dump in their existing external locations until the user accepts the work; cleanup remains a separate approval step.

### Latest background-distribution follow-up: 128 versus 256 MiB (2026-10-10)

**Current production choice, updated 2026-10-11: select 256 MiB.** After reviewing the measurements, the user prioritized reduced database read demand because cached-image reads compete for disk access and the VPS shares physical storage with other tenants. This supersedes both the earlier PR #183 / 512 MiB recommendation and the initial preference for 128 MiB based on laptop latency. The measured 256 MiB midpoint reduces concurrent buffer-pool reads by about 99.5% for about 124 MiB extra sampled MariaDB RSS. Voting/login latency was unchanged and gallery latency was mixed; the production choice addresses exposure to storage contention without claiming a measured interactive latency win.

The user requested the missing midpoint using only the latest background-distribution code. Verified local branch head `2a22ebac85c96768dd35d3554d5b97c264460405`: its app, conf, build.sbt and project match the retained `bgdistv551` distribution built at `32a1253`. Both suites used that build and freshly loaded copies of the same immutable full-data `v53-innodb` snapshot. No other code version was measured in this follow-up. No branch was fetched or changed.

The seven simulations, three warm-ups, CPU/memory/swap caps, production JVM flags and other active MariaDB settings were unchanged. The pair ran immediately back to back, 21:24–22:08 Kyiv, on mains with AC sleep/hibernate already zero; no other heavy task was launched. Actual free RAM differed as memory/cache residency changed, and is recorded below. The prior explicit RAM-prerequisite exception was retained with ten-second monitoring and the same abort thresholds.

| run | HTTP requests / failures | starting free host GiB | minimum free host MiB: all / measured | maximum sample gap s | V53→V60 startup s |
| --- | --- | --- | --- | --- | --- |
| p6-bgdist-bp128 | 15367 / 0 | 4.438 | 838 / 5559 | 10.64 | 121 |
| p6-bgdist-bp256 | 15334 / 0 | 7.319 | 4032 / 4032 | 11.381 | 96 |

All **14 simulations and 30,701 HTTP requests passed**. Both pools were verified from effective variables (134,217,728 / 268,435,456 bytes), successful Flyway histories through V60 and unchanged container limits. The synthetic-email/re-opening gates passed before warm-up. All four sequential distribution steps checked expected image/selection counts; all ten concurrent operations finished in each run. Raw results, full application/stdout logs, server GC/NMT/RSS and host samples stay outside Git under `C:\scala\wlxjury-bench-work\bench\results\<label>`.

#### Latencies and completed work

Latency below is **mean / p95 / p99 in milliseconds**. Form response time and job completion are separate measures. Concurrent samples contain 3,900 votes, 2,600 gallery requests, 520 juror logins and only ten form submissions per run.

| request | 128 MiB | 256 MiB |
| --- | --- | --- |
| vote alone | 7 / 12 / 17 | 7 / 12 / 16 |
| gallery alone | 13 / 19 / 25 | 13 / 18 / 25 |
| region gallery | 19 / 38 / 55 | 18 / 33 / 43 |
| rounds list | 12 / 20 / 24 | 14 / 25 / 30 |
| edit round | 22 / 33 / 37 | 26 / 48 / 54 |
| save round | 12 / 22 / 25 | 13 / 24 / 28 |
| round statistics | 20 / 29 / 39 | 26 / 39 / 43 |
| vote during creation | 9 / 28 / 58 | 9 / 28 / 54 |
| gallery during creation | 35 / 148 / 342 | 39 / 175 / 360 |
| juror login during creation | 18 / 46 / 103 | 17 / 46 / 80 |
| concurrent form response | 125 / 213 / 248 | 128 / 197 / 197 |
| job-status response | 20 / 56 / 114 | 22 / 53 / 87 |

| sequential job: submit to done | 128 MiB s | 256 MiB s |
| --- | --- | --- |
| create round: from contest category | 4.456 | 4.038 |
| create round: from binary round | 0.704 | 0.475 |
| create round: top images of rated round | 0.287 | 0.290 |
| top up round: distribute new files | 0.524 | 0.494 |

| concurrent job: submit to done | 128 MiB s | 256 MiB s |
| --- | --- | --- |
| Large A | 24.258 | 21.118 |
| Large B | 24.213 | 21.798 |
| Region 73 | 12.644 | 12.568 |
| Region 05 | 12.762 | 12.618 |
| Region 53 | 12.787 | 12.308 |
| Region 96 | 12.477 | 12.568 |
| Top 100 | 8.429 | 8.177 |
| Top 500 | 8.504 | 8.216 |
| Rated avg 7 | 8.332 | 8.291 |
| New files | 9.447 | 9.410 |

256 MiB reduced category-round completion from 4.456 to 4.038 s (0.418 s / 9.4%). Large A/B finished 3.140 / 2.415 s earlier (12.9% / 10.0%). Small queued jobs were nearly unchanged, around 8–13 s. Isolated voting was unchanged; concurrent vote/login p95 stayed 28/46 ms. Concurrent gallery p95 was 148→175 ms, with p99 342→360 ms. This is mixed latency evidence, not proof of a regression: one unseeded suite at each size cannot establish small differences or a repeatable job-time gain. HTTP request totals differ because of polling and timed/random flows; the core concurrent vote/gallery/login counts match.

#### Memory and database counters

| measure | 128 MiB | 256 MiB |
| --- | --- | --- |
| peak sampled MariaDB RSS MiB | 268.2 | 391.8 |
| peak sampled server Java RSS MiB | 671.3 | 873.9 |
| configured principal global buffers MiB | 401 | 529 |
| concurrent physical buffer-pool reads | 9843 | 49 |
| concurrent logical buffer-pool reads | 14375107 | 16111265 |
| concurrent buffer-pool hit % | 99.93153 | 99.99970 |
| concurrent table-lock waits | 0 | 0 |
| concurrent row-lock waits / ms | 7 / 6238 | 6 / 3613 |
| concurrent disk temporary tables | 2600 | 2600 |
| concurrent slow queries | 9 | 5 |
| maximum used connections, whole suite | 18 | 21 |
| log waits / buffer wait-free, whole suite | 0 / 0 | 0 / 0 |
| server full GC, whole captured lifetime | 0 | 0 |
| server maximum heap after pause GC MiB, whole captured lifetime | 165 | 168 |
| server maximum pause GC ms, whole captured lifetime | 152.097 | 102.236 |

The added 128 MiB of configured pool cost about 124 MiB of sampled MariaDB RSS and reduced physical reads 9,843→49 (about 99.5%). That is a real database-cache benefit, but it was not a voting/gallery latency win on this SSD. No MariaDB RSS or Java RSS sample is a hard bound. Java RSS also differed (671→874 MiB); this pair does not establish the cause. GC figures above come from the **server**, include startup/warm-up, and are not client HeapWatcher measurements.

At 128 MiB the principal configured global buffers sum to 401 MiB: pool 128 + key cache 128 + Aria 128 + log buffer 16 + disabled-query-cache allocation 1. At 256 MiB that sum is 529 MiB, versus 785 MiB at 512. 128 leaves about 599 MiB within the runbook’s approximate 1,000 MiB database allowance for other/query memory; the selected 256 leaves about 471. Keep the app/WordPress/stats/OS reserves and durable defaults. WordPress, midnight stats and the thumbnail cache remain absent. A slower production disk could make the reduced reads more valuable; this benchmark does not measure that.

`recommended-60-wlxjury.cnf` now selects 256 MiB, matching the tested `bp256.cnf` setting. The observed 99.99970% buffer-pool hit rate makes this a measured way to reduce database read demand with a bounded memory increase. No 512 MiB bgdist suite was run, so this pair is not a direct 256-versus-512 comparison.

The production rationale supplied by the user adds two omitted conditions: reading cached images generates substantial filesystem traffic, and other VPS tenants can impose storage contention that is unknown and outside this application’s control. A 16 GiB laptop may retain more image files in memory than the roughly 4–4.9 GiB shared server. The suite did not populate or exercise that image-file cache. Changing neighbour load would also make one production timing comparison hard to interpret. These conditions favour reducing avoidable database reads even when local SSD timings do not improve. They do not establish a quantitative production speedup.

This is an operational choice using existing results; no additional run or artificial IO throttle was introduced. MariaDB’s physical buffer-pool read counter measures database page reads, not physical-host block IO: hypervisor/device caching and competing traffic remain unmeasured. The larger pool also leaves less RAM for filesystem/image caching, so 256 MiB is a bounded compromise rather than an instruction to maximize the pool. Observe memory/swap, database reads, storage latency and image-serving/job timings under normal production load; retain durable settings and the other shared-server reserves.

#### Runbook gaps resolved or retained

- Added the missing 256 MiB point on latest local bgdist, with a fresh 128 MiB control; removed the runbook’s presumption that 512–768 MiB is likely best. The older PR #183 matrix remains historical evidence.
- Full diagnostics now confirm the startup heartbeat issue: 11 warnings in the 128 MiB run and 9 in the 256 MiB run, each with `IllegalStateException: Connection pool is not yet initialized.(name:default)`. They occur before app readiness and stop afterwards. This replaces the earlier inference about a missing job table; scheduler heartbeat lacks the poll readiness guard. All measured jobs completed. No application source fix was made.
- Removed a leftover private `Record-BenchPhase` callback from the public monitoring wrapper. The exact old label `p5-pr183-trim` could reach an undefined function after validation; new reproduction labels do not. PowerShell parsing passed after removal. These two full suites also exercise the final pre-copy cache clear and pre-warm-up snapshot gates introduced after the recovery pair.
- One unseeded suite per size; no statistical claim. Rated-average and concurrent new-files operations still produce zero rows, leaving eight nonempty creations among ten operations. Expected sequential count checks pass, but the binary candidate count varies with random voting (2,336 versus 2,323 images).
- This is a full-data buffer-size comparison. The previous no-archive result was measured on PR #183 at 512 MiB; it must not be presented as a bgdist full-versus-trim result at the selected 256 MiB. No archive experiment or production data change was done in this follow-up. A data-size decision for this exact package/config requires a fresh matching size comparison if needed.
- January data, local SSD, omitted WordPress/stats/thumb-cache load and Commons calls remain fidelity limits. Conversion/migration and deployment instructions above are unchanged. No other worktree, user process, container, server or branch was changed; no push.

The benchmark container is stopped. Snapshots and both validated result folders are retained. AC timeouts were already zero and remain unchanged. The original completed results and recovery commits are preserved.
