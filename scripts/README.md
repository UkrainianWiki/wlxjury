# scripts/

Other folders: [`deployment/`](deployment/) (the release tools and their tests,
[deployment.md](../docs/deployment.md)), [`review/`](review/README.md) (CodeRabbit requests and
review triage) and [`backfill/`](backfill/) (the one-off `vote.voted_at` backfill from the access
logs, [deployment.md](../docs/deployment.md#one-off-vote-times-from-the-access-logs-after-v97)).

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

## coverage-summary.py, coverage_comment.py, coveralls-paths.py

Coverage reporting ([testing practices](../docs/testing/practices.md) §9.5), standard library
only. `coverage-summary.py` writes the Coverage job's summary and, with `--json`, the totals for
the PR comment; `coverage_comment.py` is `coverage-comment.yml`'s step, which validates that
file and creates or updates the PR's comment through `gh`; `coveralls-paths.py` writes the
reports' copies for Coveralls with repository-relative paths.

```bash
python scripts/coverage-summary.py --json target/coverage-summary.json --head-sha SHA [--pr N]
python scripts/coveralls-paths.py             # target/coveralls/cobertura.xml and lcov.info
python -B -m unittest discover -s scripts -p test_coverage_reports.py
```
