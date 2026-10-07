# Production-like Benchmark, Storage Engine and MariaDB Configuration Plan (October 2026)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan phase by phase.

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
