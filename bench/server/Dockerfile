# The production server for benchmarks (docs/plans/2026-10-07-prod-like-benchmark.md):
# Ubuntu 24.04 with the Ubuntu packages of MariaDB 10.11 and OpenJDK 17, production's
# active /etc/mysql files, and the jury tool started as wlxjury.service starts it.
# The server before the move to 24.04 (22.04, MariaDB 10.6.22) is Dockerfile.jammy.
#
#   docker build -t wlxjury-bench-server bench/server
#
# Run it with the server's limits, by count only (bench/scripts/bench.sh start):
#   --cpuset-cpus=0,1 --memory=4900m --memory-swap=6900m
# MariaDB and the app share them, as on the server.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8

# mariadb-server: the build the server gets, 1:10.11.14-0ubuntu0.24.04.1 (checked below, so
# a newer archive build fails the image instead of changing the measurements unnoticed).
# openjdk-17-jdk-headless: production's JRE plus jcmd (NMT summaries, as used on the server).
ARG MARIADB_VERSION=10.11.14-0ubuntu0.24.04.1
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      "mariadb-server=1:${MARIADB_VERSION}" "mariadb-client=1:${MARIADB_VERSION}" \
      openjdk-17-jdk-headless procps tzdata ca-certificates curl \
 && dpkg -l | grep -E 'mariadb-server-core|libmariadb3' | grep -c "${MARIADB_VERSION}" | grep -qx 2 \
 && rm -rf /var/lib/apt/lists/* \
 && rm -rf /var/lib/mysql/* \
 && useradd --system --home-dir /usr/share/wlxjury --shell /usr/sbin/nologin wlxjury \
 && mkdir -p /var/log/wlxjury /run/wlxjury /opt/dists /bench/cnf \
 && chown wlxjury:wlxjury /var/log/wlxjury /run/wlxjury

# Production's active MariaDB files (mariadb.cnf -> conf.d/, mariadb.conf.d/), without
# debian.cnf. The package's own copies are replaced, not merged. 24.04's package files
# differ from 22.04's only in bind-address = 127.0.0.1 (production's has none; the
# benchmark needs the port) and the [mariadb-10.11] section name.
RUN rm -rf /etc/mysql/conf.d /etc/mysql/mariadb.conf.d /etc/mysql/mariadb.cnf
COPY mysql-conf/ /etc/mysql/
RUN chmod -R go-w /etc/mysql

# /etc/default/wlxjury (JAVA_OPTS, MALLOC_ARENA_MAX) and the benchmark's app config overlay.
COPY default-wlxjury /etc/default/wlxjury
COPY bench.conf /etc/wlxjury/bench.conf
COPY entrypoint.sh /usr/local/bin/bench-entrypoint
RUN chmod 755 /usr/local/bin/bench-entrypoint && chmod 644 /etc/default/wlxjury /etc/wlxjury/bench.conf

EXPOSE 9000 3306
VOLUME /var/lib/mysql
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/bench-entrypoint"]
