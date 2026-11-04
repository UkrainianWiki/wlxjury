## Running locally

Install JDK 17 and sbt, start a MariaDB (10.6 or 10.11), and set `WLXJURY_DB_HOST`,
`WLXJURY_DB`, `WLXJURY_DB_USER` and `WLXJURY_DB_PASSWORD`.

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

The first account signed up on an empty database becomes root (`users.is_root`): open
[http://localhost:9000/signup](http://localhost:9000/signup) and sign up with an email and a
password. Later sign-ups get no role; root (or a contest's admin) adds them to contests. On a
database that already has users, make an existing account root with
`UPDATE users SET is_root = TRUE WHERE email = '...';`.

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

