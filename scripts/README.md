# scripts/

## run-with-prod-dump.sh

Dumps the production DB, restores it into a local MariaDB 10.6 Docker container on port 3307, and starts the sbt dev server against it — so you can test the current branch against real production data without touching the live database.

**Requirements:** Docker (the script uses `mariadb:10.6` image — no local `mysql` client needed), a `.env` file at the repo root with `WLXJURY_DB_HOST`, `WLXJURY_DB`, `WLXJURY_DB_USER`, `WLXJURY_DB_PASSWORD`.

**Usage:**

```bash
# Full flow: dump prod → restore locally → run sbt
./scripts/run-with-prod-dump.sh

# Re-use the cached dump from /tmp/wlxjury-prod.sql.gz (faster for repeat runs)
./scripts/run-with-prod-dump.sh --skip-dump

# Dump and restore only — leave container running for manual inspection
./scripts/run-with-prod-dump.sh --dump-only

# Skip dump and restore entirely — use the already-running local container
./scripts/run-with-prod-dump.sh --reuse-container
```

The container (`wlxjury-local-dev`) is left running after the script exits. To remove it:

```bash
docker rm -f wlxjury-local-dev
```
