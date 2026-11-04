# scripts/

Other folders: [`deployment/`](deployment/) (the release tools and their tests,
[deployment.md](../docs/deployment.md)) and [`review/`](review/README.md) (CodeRabbit requests and
review triage).

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

## story-coverage.py

The story coverage matrix ([testing practices](../docs/testing/practices.md) §9.4): which browser
tests (Playwright tags, `'@J-21'`) and Scala specs (`[J-27]` in a name) cite each story of
[`docs/testing/stories.md`](../docs/testing/stories.md). Python standard library only, no build;
a step of `ci.yml`. Fails on an unknown, retired or malformed ID, a duplicate row or a dead guide
anchor; reports the uncovered stories.

```bash
python scripts/story-coverage.py              # the matrix: stdout, target/story-coverage.md
python scripts/story-coverage.py --self-test  # on scripts/testdata/story-coverage/
```
