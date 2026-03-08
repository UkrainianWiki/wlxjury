# Docker-Based Image Cache Tests Design

Date: 2026-03-07

## Goal

End-to-end tests that verify:
- **a)** Apache serves locally-cached files and proxies uncached ones to Wikimedia Commons
- **b)** The jury tool triggers caching via HTTP endpoints and the resulting images are served at all sizes

---

## Infrastructure

### `conf/apache/Dockerfile`

Builds from `httpd:2.4`. Enables the four modules required by `jury-images.conf` by uncommenting
their `LoadModule` lines in `httpd.conf`, then includes `jury-images.conf` and sets `ServerName`.

```dockerfile
FROM httpd:2.4
RUN sed -i \
    -e 's/#LoadModule proxy_module/LoadModule proxy_module/' \
    -e 's/#LoadModule proxy_http_module/LoadModule proxy_http_module/' \
    -e 's/#LoadModule rewrite_module/LoadModule rewrite_module/' \
    -e 's/#LoadModule headers_module/LoadModule headers_module/' \
    /usr/local/apache2/conf/httpd.conf \
 && echo 'Include /usr/local/apache2/conf/extra/jury-images.conf' \
    >> /usr/local/apache2/conf/httpd.conf \
 && echo 'ServerName localhost' >> /usr/local/apache2/conf/httpd.conf
```

### `docker-compose.test.yml`

Two services: Apache (built from the Dockerfile above) and MariaDB. A fixed bind-mount path
`/tmp/wlxjury-jury-images` is shared between the test JVM (which writes cached images) and
Apache (which serves them).

```yaml
services:
  apache:
    build:
      context: .
      dockerfile: conf/apache/Dockerfile
    volumes:
      - ./conf/apache/jury-images.conf:/usr/local/apache2/conf/extra/jury-images.conf:ro
      - /tmp/wlxjury-jury-images:/var/www/jury-images
    ports:
      - "80"

  mariadb:
    image: mariadb:10.6
    environment:
      MARIADB_DATABASE: wlxjury_test
      MARIADB_USER: wlxjury_user
      MARIADB_PASSWORD: wlxjury_password
      MARIADB_RANDOM_ROOT_PASSWORD: "yes"
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      timeout: 20s
      retries: 10
```

Running the stack manually: `docker compose -f docker-compose.test.yml up --build`

---

## Shared Test Helper: `CommonsImageFetcher`

Extract `fetchImagesFromCommons` from `LocalImageCacheServiceIntegrationSpec` into a shared
trait `CommonsImageFetcher` in the `services` package (test sources). Both the integration spec
and the new Docker spec mix it in.

---

## Test Class: `LocalImageCacheServiceDockerSpec`

**File:** `test/services/LocalImageCacheServiceDockerSpec.scala`
**Package:** `services`
**Gating:** `skipAllUnless(sys.props.get("docker.tests").contains("true"))`
**Run command:** `sbt -Ddocker.tests=true "testOnly services.LocalImageCacheServiceDockerSpec"`

### Container setup

Uses `DockerComposeContainer` (from `com.dimafeng.testcontainers`) pointing at
`docker-compose.test.yml`. Exposes the Apache port and MariaDB port.

### Play `TestServer`

Started via `play.api.test.TestServer` with configuration overrides:
- `wlxjury.thumbs.host` → `<apache-container-host>:<apache-port>`
- `wlxjury.thumbs.local-path` → `/tmp/wlxjury-jury-images`
- DB config pointing to the MariaDB container

### Test (a): Apache serving

**Before caching** — GET a real thumb URL through Apache. Assert: HTTP 200 (proxied from
Wikimedia Commons). Proves the `RewriteRule [P]` fallback works.

**After caching** — Run full caching flow (see below), then GET the same URL again. Assert:
HTTP 200 + `Content-Type: image/jpeg` + `Cache-Control: public, max-age=2592000` header.
Proves the locally-cached file is served by Apache's `Alias` directive, not proxied.

**Bogus path** — GET `/wikipedia/commons/thumb/x/xx/nosuchfile.jpg/999px-nosuchfile.jpg`.
Assert: non-200 response. Proves Apache is not silently swallowing errors.

### Test (b): Jury tool end-to-end

1. Fetch 50 real images from `Category:Images from Wiki Loves Earth 2021 in Armenia` via
   `fetchImagesFromCommons` (reused from `CommonsImageFetcher`).
2. Insert images into MariaDB via `imageDao.batchInsert`.
3. Create a contest and an admin user in MariaDB.
4. Log in via `POST /login` to obtain a Play session cookie.
5. `POST /contest/:id/images/cache/start` with the session cookie.
6. Poll `GET /contest/:id/images/cache/status` (parsing the `CacheProgress` JSON) until
   `running == false`, with a 90-second timeout.
7. Assert `done == images.size` (100% success, matching the integration test standard).
8. For each image at each target height (`Global.targetHeights`), compute the expected URL
   via `Global.legacyThumbUlr` (which now points to Apache). HTTP-GET each URL and assert:
   HTTP 200 + `Content-Type: image/jpeg`.

---

## Gating Strategy

| Test class | System property | Hits network | Starts Docker |
|---|---|---|---|
| `LocalImageCacheServiceSpec` | none | no | no |
| `LocalImageCacheServiceIntegrationSpec` | `-Dintegration=true` | yes (Wikimedia) | no |
| `LocalImageCacheServiceDockerSpec` | `-Ddocker.tests=true` | yes (Wikimedia) | yes |
