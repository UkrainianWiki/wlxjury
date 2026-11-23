## React UI (Contests)

### Dev workflow

Two convenience scripts start everything (Play + Vite) in one command:

| Script | DB source |
|---|---|
| `./run-dev.sh` | credentials from `.env` (same as production) |
| `./scripts/run-with-prod-dump.sh` | dumps production DB, restores into a local Docker container |

Both scripts install `ui/node_modules` on first run if missing, start the Vite dev server in the background, and kill it automatically when Play exits.

```bash
./run-dev.sh                                    # local .env DB
./scripts/run-with-prod-dump.sh                 # fresh prod dump → local container
./scripts/run-with-prod-dump.sh --skip-dump     # re-use last dump
./scripts/run-with-prod-dump.sh --reuse-container  # skip dump+restore entirely
```

- Play (HTML pages): [http://localhost:9000](http://localhost:9000)
- Tapir REST API + Swagger UI: [http://localhost:9001/api/contests](http://localhost:9001/api/contests) · [http://localhost:9001/api/docs](http://localhost:9001/api/docs)
- React UI (hot-reload, proxies /api to 9001): [http://localhost:5173](http://localhost:5173)

**Running manually (if you need separate terminals):**

Terminal 1 — Play backend:
```bash
WLXJURY_DB_HOST=... WLXJURY_DB=... WLXJURY_DB_USER=... WLXJURY_DB_PASSWORD=... sbt run
```

Terminal 2 — Vite dev server:
```bash
cd ui && npm run dev
```

### Tests

```bash
cd ui && npm test          # run once (CI mode)
cd ui && npm run test:watch  # watch mode for development
```

Tests are also run as part of `sbt test` via the `uiTest` task.

### Deploying (production build)

1. Build the UI:
   ```bash
   cd ui && npm run build
   ```
   This outputs static assets to `ui/dist/`.

2. Copy to the Play public directory before running `sbt dist`:
   ```bash
   cp -r ui/dist/* public/ui/
   ```

3. Package and deploy as normal:
   ```bash
   sbt dist
   ```

Play serves the built UI at `/ui` via the routes added to `conf/routes`. The API at `/api/*` continues to be served by Tapir/Pekko HTTP on the same port.

---

## Running locally

Install JDK 17 and sbt, start a MariaDB (10.6 or 10.11), and set `WLXJURY_DB_HOST`,
`WLXJURY_DB`, `WLXJURY_DB_USER` and `WLXJURY_DB_PASSWORD`.

The app creates the tables (Flyway migrations), not the database or its user. Create them once
as MariaDB's root, with the names and password of those variables (here `wlxjury`):

```sql
CREATE DATABASE wlxjury CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER 'wlxjury'@'localhost' IDENTIFIED BY 'your-password';
GRANT ALL PRIVILEGES ON wlxjury.* TO 'wlxjury'@'localhost';
```

If MariaDB runs in a container, or with `skip_name_resolve`, the app's connection doesn't come
from `localhost`: create the user and grant to `'wlxjury'@'%'` (or the client's host or address)
instead.

`sbt run` refuses to start while migrations are pending, as a new database's are: start it as
`sbt -Ddb.default.migration.auto=true run` to apply them (`db.Migrations`).

scalawiki `0.7.0-SNAPSHOT` must be published locally first: `build.sbt` resolves it from
`~/.ivy2/local` (or `~/.m2`), not from a repository. Check out `UkrainianWiki/scalawiki` at the
commit in `project/scalawiki.sha` and run, in that checkout,
`sbt http-extensions/publishLocal scalawiki-core/publishLocal scalawiki-wlx/publishLocal`, as
CI's `.github/actions/scalawiki` does ([integrations practices](docs/integrations/practices.md)
§2.1).

Then run the application with sbt

`sbt run`

the terminal will have the following output

```
$ sbt run
[info] Set current project to wlxjury

--- (Running the application, auto-reloading is enabled) ---

[info] p.c.s.NettyServer - Listening for HTTP on /0:0:0:0:0:0:0:0:9000

(Server started, use Ctrl+D to stop and go back to the console...)
```

Now open [http://localhost:9000](http://localhost:9000)

### Creating the root admin

There is no sign-up. On an empty database, set `WLXJURY_SETUP_TOKEN` (32 characters or more),
open [http://localhost:9000/setup](http://localhost:9000/setup) and create the first account,
root (`users.is_root`), with the token, an email and a password; root (or a contest's admin)
invites the others ([deployment](docs/deployment.md), "Accounts, invitations and the first
account"). On a database that already has users, make an existing account root with
`UPDATE users SET is_root = TRUE WHERE email = '...';`.

### Running with docker-compose

The app and MariaDB in containers, without a local database:

```bash
sbt Docker/publishLocal            # the image wlxjury:local, after scalawiki's publishLocal above
scripts/docker/generate-env.sh     # random secrets in .env, kept on reruns
docker compose up -d --wait        # http://localhost:9000/setup, with WLXJURY_SETUP_TOKEN from .env
docker compose down                # down -v also deletes the database
```

What it sets, its limits and its CI test: [deployment](docs/deployment.md), "Run with
docker-compose". A published image (queued) will make the sbt step unnecessary for people who
only run the app.

## Development

### Auto-reloading
Interpreted languages like Python or PHP always run the latest source code that was modified by developer. That is not the case for compiled language like Java or Scala. However Play Framework supports auto-reloading if the source code changes and you try to open some web-page again. On new http request if source code is modified, changed files will be recompiled and the application will be reloaded.

### sbt Triggered compilation
The drawback of autoreloading is that recompilation will only happen on new page request. Using [triggered execution](https://www.scala-sbt.org/1.x/docs/Triggered-Execution.html) sbt can automatically recompile in the background the moment you save updated source code. To do that run sbt with command `~run`

`sbt ~run`

Application will start and will compile the changes in the background.

You can read more on sbt commands in the [sbt](https://www.scala-sbt.org/1.x/docs/Running.html) and [Play](https://www.playframework.com/documentation/3.0.x/PlayConsole) documentation. The app uses Play 3.0 and sbt 1.x.

### IDEs
One of the best IDEs for Scala is [Intellij IDEA](https://en.wikipedia.org/wiki/IntelliJ_IDEA). It has free and open-source Community edition and Scala plugin. Paid (or [available for free for open-source project communities like Wikimedia](https://lists.wikimedia.org/pipermail/wikitech-l/2016-May/085558.html)) Ultimate edition also provides additional support for Play Framework features like [template](https://www.playframework.com/documentation/3.0.x/ScalaTemplates) and [route](https://www.playframework.com/documentation/3.0.x/ScalaRouting) files.

[Metals](https://scalameta.org/metals/) brings Scala IDE features to VS Code, Vim, Emacs and other editors through the language server protocol.

Read more in the [Play](https://www.playframework.com/documentation/3.0.x/IDE) and [IntelliJ IDEA](https://www.jetbrains.com/help/idea/getting-started-with-play-2-x.html) documentation. Note that JetBrains IDE documentation on Play support assumes Ultimate edition, but parts of it related to Scala and Sbt features apply also for Community edition.

