#!/usr/bin/env python3
"""Single-service maintenance deployments. Python 3.8+, Linux, root, stdlib only.

No shell evaluation, passwords in argv, automatic schema repair, or implicit
post-cutover database rollback. See docs/deployment.md for initial setup.
"""
import argparse
import contextlib
import datetime
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import socket
import ssl
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile


# Text of the ErrorDocument in conf/apache/wlxjury-maintenance.conf.
MAINTENANCE_PAGE = b'undergoing maintenance'


class DeploymentError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise DeploymentError(message)


def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def atomic_write(path, data, mode=0o600):
    path = Path(path)
    temporary = path.with_name(path.name + '.tmp')
    fd = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        if os.name == 'posix':
            os.fchmod(fd, mode)
            if path.exists():
                previous = path.stat()
                os.fchown(fd, previous.st_uid, previous.st_gid)
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(str(temporary), str(path))
        # Persist the rename as well as the contents on Linux.
        if os.name == 'posix':
            directory = os.open(str(path.parent), os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    finally:
        if temporary.exists():
            temporary.unlink()


def write_json(path, value):
    atomic_write(path, json.dumps(value, indent=2) + '\n')


def read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding='utf-8'))
    except (OSError, ValueError):
        raise DeploymentError('Cannot read valid JSON: ' + str(path)) from None


def env_quote(value):
    require('\x00' not in value and '\n' not in value and '\r' not in value,
            'Runtime values must be single-line strings')
    # systemd double-quoted values: $ and backticks are literal, not expansions.
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def read_env(path):
    """Strict subset shared with init-runtime: one fully quoted value per line.

    Reject unsupported syntax instead of interpreting a systemd file as shell.
    Handles spaces, $, #, quotes, backticks and backslashes without expansion.
    """
    result = {}
    for number, line in enumerate(Path(path).read_text(encoding='utf-8').splitlines(), 1):
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        m = re.fullmatch(r'([A-Z][A-Z0-9_]*)="((?:[^"\\]|\\["\\])*)"', line)
        require(m is not None, 'Unsupported runtime.env syntax at line ' + str(number))
        key, value = m.groups()
        require(key not in result, 'Duplicate runtime variable: ' + key)
        result[key] = re.sub(r'\\(["\\])', r'\1', value)
    for key in ('WLXJURY_DB_HOST', 'WLXJURY_DB', 'WLXJURY_DB_USER', 'WLXJURY_DB_PASSWORD', 'PLAY_HTTP_SECRET_KEY'):
        require(result.get(key) and not result[key].startswith('REPLACE_ME'), 'Missing runtime value: ' + key)
    require(len(result['PLAY_HTTP_SECRET_KEY'].encode('utf-8')) >= 32, 'Signing key must be at least 32 bytes')
    return result


def legacy_value(text, key, optional=False):
    values = re.findall(r'^\s*' + re.escape(key) + r'\s*=\s*(.*?)\s*$', text, re.M)
    if optional and not values:
        return None
    require(len(values) == 1, 'Expected exactly one literal value for ' + key)
    value = values[0]
    if value.startswith('"'):
        try:
            parsed, length = json.JSONDecoder().raw_decode(value)
            require(isinstance(parsed, str) and not parsed.startswith('${'), 'Expected literal string: ' + key)
            require(not value[length:].strip() or value[length:].lstrip().startswith(('#', '//')),
                    'Unsupported legacy value: ' + key)
            return parsed
        except ValueError:
            raise DeploymentError('Unsupported legacy string: ' + key) from None
    require(re.fullmatch(r'[A-Za-z0-9_.@-]+', value) is not None and value != 'null',
            'Unsupported legacy value: ' + key)
    return value


def init_runtime(legacy_config, runtime_dir):
    """Move only supported literal credentials into a new protected file.

    Does not edit the original config, install a unit, or restart anything.
    """
    directory = Path(runtime_dir)
    require(not directory.is_symlink(), 'Runtime directory must not be a symlink')
    directory.mkdir(mode=0o755, parents=True, exist_ok=True)
    os.chmod(str(directory), 0o755)  # Apache must stat the nonsecret maintenance flag.
    target = directory / 'runtime.env'
    require(not target.exists(), 'runtime.env exists; refusing to replace credentials')
    text = Path(legacy_config).read_text(encoding='utf-8')
    url = legacy_value(text, 'db.default.url')
    m = re.fullmatch(r'jdbc:(?:mysql|mariadb)://([A-Za-z0-9_.-]+(?::[0-9]+)?)/([A-Za-z0-9_]+)(?:\?[^\s]*)?', url)
    require(m is not None, 'Unsupported legacy DB URL; configure runtime.env on the server manually')
    env = {'WLXJURY_DB_HOST': m.group(1), 'WLXJURY_DB': m.group(2),
           'WLXJURY_DB_USER': legacy_value(text, 'db.default.username'),
           'WLXJURY_DB_PASSWORD': legacy_value(text, 'db.default.password'),
           'PLAY_HTTP_SECRET_KEY': legacy_value(text, 'play.http.secret.key')}
    for key, variable in [('commons.user', 'COMMONS_USER'), ('commons.password', 'COMMONS_PASSWORD')]:
        value = legacy_value(text, key, optional=True)
        if value:
            env[variable] = value
    contents = ''.join(k + '=' + env_quote(v) + '\n' for k, v in env.items())
    # Validate completely before publishing. No credential value is printed.
    require(len(env['PLAY_HTTP_SECRET_KEY'].encode('utf-8')) >= 32, 'Existing signing key is too short')
    atomic_write(target, contents)
    print('Created protected runtime.env; original config and service are unchanged.')


def externalize_legacy(text):
    replacements = {'play.http.secret.key': '${PLAY_HTTP_SECRET_KEY}',
                    'db.default.username': '${WLXJURY_DB_USER}', 'db.default.password': '${WLXJURY_DB_PASSWORD}',
                    'commons.user': '${?COMMONS_USER}', 'commons.password': '${?COMMONS_PASSWORD}',
                    'db.default.url': '"jdbc:mariadb://"${WLXJURY_DB_HOST}"/"${WLXJURY_DB}"?useUnicode=true&characterEncoding=UTF-8"'}
    for key, value in replacements.items():
        text = re.sub(r'^\s*' + re.escape(key) + r'\s*=.*$', lambda _: key + ' = ' + value, text, flags=re.M)
    return text


# Settings whose name marks a credential, in HOCON/properties/ini/JVM-option form,
# including URL query parameters such as "&password=...".
SECRET_SETTING = re.compile(r'([A-Za-z0-9_.-]*(?:password|passwd|secret)[A-Za-z0-9_.-]*)\s*[=:]\s*(.*)', re.I)
SCANNED_CONFIG = ('.conf', '.ini', '.properties', '.cnf', '.env')


def literal_secret(line):
    """The name of a credential setting this line gives a literal value, or None."""
    for match in SECRET_SETTING.finditer(line):
        value = match.group(2).strip()
        if value and not value.startswith('${') and value not in ('""', "''", 'null'):
            return match.group(1)
    return None


def sanitize_copied_config(directory):
    """Drop commented-out credentials from a copied conf/ tree; refuse live ones.

    Comments do not change behavior, so removing them is safe. A literal value
    in a live setting cannot be externalized without guessing, so it stops the
    adoption and names the file, line and key, never the value.
    """
    found = []
    for path in sorted(directory.rglob('*')):
        if not path.is_file() or path.suffix not in SCANNED_CONFIG:
            continue
        lines = path.read_text(encoding='utf-8').splitlines(keepends=True)
        kept = [line for line in lines
                if not (line.lstrip().startswith(('#', '//')) and literal_secret(line))]
        if kept != lines:
            path.write_text(''.join(kept), encoding='utf-8')
        for number, line in enumerate(kept, 1):
            key = literal_secret(line)
            if key:
                found.append(path.relative_to(directory).as_posix() + ':' + str(number) + ' ' + key)
    require(not found, 'Adopted copy still has literal credentials (' + ', '.join(found) +
            '); adopt from a reviewed copy of the installation that references runtime.env instead')


def migration_versions(release):
    """Inspect both external config and app/dependency JAR resources before adoption."""
    names = [path.name for path in (release / 'conf' / 'db' / 'migration' / 'default').glob('V*.sql')]
    for jar in (release / 'lib').glob('*.jar'):
        with zipfile.ZipFile(str(jar)) as archive:
            names.extend(PurePosixPath(name).name for name in archive.namelist()
                         if name.startswith('db/migration/default/'))
    return [match.group(1).replace('_', '.') for name in names
            for match in [re.fullmatch(r'V([0-9]+(?:[._][0-9]+)*)__.+\.sql', name)] if match]


def version_tuple(version):
    parts = [int(part) for part in version.split('.')]
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def private_file(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), 'Expected regular protected file: ' + str(path))
    if os.name == 'posix':
        info = path.stat()
        require(info.st_uid == 0 and info.st_mode & 0o077 == 0,
                'File must be root-owned and mode 0600: ' + str(path))


class Deployment:
    def __init__(self, settings):
        self.settings = settings
        self.root = Path(settings['release_root'])
        self.releases = self.root / 'releases'
        self.current = self.root / 'current'
        self.runtime = Path(settings['runtime_dir'])
        self.backups = Path(settings['backup_root'])
        self.maintenance = self.runtime / 'maintenance'
        self.service = settings['service']
        self.port = settings['port']
        self.units = settings.get('quiesce_units', [])
        # A later drop-in than deployment.conf; it turns automatic restarts off
        # while a deployment is open, so a failed or killed start never re-runs
        # migrations against a partially migrated database on its own.
        self.restart_hold = Path(settings.get('systemd_dropin_dir', '/etc/systemd/system/' + self.service + '.d')) \
            / 'zz-deployment-hold.conf'
        self.proc = Path('/proc')
        self.verified = {}
        for path in (self.root, self.runtime, self.backups, Path(settings['database_dir']), self.restart_hold.parent):
            require(path.is_absolute() and str(path) != path.anchor, 'Use a specific absolute directory')
            require(not path.is_symlink(), 'Deployment directories must not be symlinks: ' + str(path))
        for unit in [self.service] + self.units:
            require(re.fullmatch(r'[A-Za-z0-9_.@-]+\.(?:service|timer)', unit) is not None, 'Invalid systemd unit')
        require(self.service not in self.units and len(set(self.units)) == len(self.units),
                'Quiesce units must be unique and exclude the application service')
        require(isinstance(self.port, int) and 1 <= self.port <= 65535, 'Invalid port')
        require(settings['minimum_free_bytes'] > 0 and settings['ready_timeout_seconds'] > 0,
                'Set a positive capacity reserve and startup timeout')
        require(bool(settings['maintenance_urls']), 'List every jury HTTP/HTTPS maintenance URL')
        for url in settings['maintenance_urls']:
            parsed = urllib.parse.urlparse(url)
            require(parsed.scheme in ('http', 'https') and parsed.hostname and not parsed.username,
                    'Invalid maintenance URL')
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    @contextlib.contextmanager
    def locked(self):
        import fcntl
        self.root.mkdir(mode=0o755, parents=True, exist_ok=True)
        self.releases.mkdir(mode=0o755, exist_ok=True)
        self.backups.mkdir(mode=0o700, parents=True, exist_ok=True)
        for path, mode in ((self.root, 0o755), (self.releases, 0o755), (self.backups, 0o700)):
            require(not path.is_symlink(), 'Managed directory must not be a symlink')
            require(path.stat().st_uid == 0, 'Managed directories must be root-owned')
            os.chmod(str(path), mode)
        with (self.root / 'deployment.lock').open('a') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise DeploymentError('Another deployment operation holds the lock') from None
            yield

    def run(self, args, **kwargs):
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, **kwargs)
        require(result.returncode == 0, 'Command failed: ' + str(args[0]) + ' (exit ' + str(result.returncode) + ')')
        return result.stdout

    def active(self, unit):
        state = self.run(['systemctl', 'show', unit, '-p', 'ActiveState', '--value']).decode().strip()
        return state in ('active', 'activating', 'reloading', 'deactivating')

    def stop(self, unit):
        self.run(['systemctl', 'stop', unit])
        require(not self.active(unit), 'Unit is still running: ' + unit)
        pid = self.run(['systemctl', 'show', unit, '-p', 'MainPID', '--value']).decode().strip()
        require(pid in ('0', ''), 'Unit still has a live process: ' + unit)

    def hold_restarts(self):
        self.restart_hold.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        atomic_write(self.restart_hold, '[Service]\n# Written by wlxjury-deploy while a deployment is open; '
                                        'removed when traffic reopens.\nRestart=no\n', 0o644)
        self.run(['systemctl', 'daemon-reload'])

    def release_restarts(self):
        if self.restart_hold.exists():
            self.restart_hold.unlink()
            self.run(['systemctl', 'daemon-reload'])

    def say(self, message):
        """Log durably first; a closed SSH terminal must not stop the operation."""
        try:
            stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
            fd = os.open(str(self.backups / 'operations.log'), os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            with os.fdopen(fd, 'a', encoding='utf-8') as log:
                log.write(stamp + ' ' + message + '\n')
        except OSError:
            pass
        try:
            print(message, flush=True)
        except OSError:
            pass

    def client(self, binary):
        private_file(self.runtime / 'runtime.env')
        private_file(self.runtime / 'db-admin.cnf')
        env = read_env(self.runtime / 'runtime.env')
        require(re.fullmatch(r'[A-Za-z0-9_]+', env['WLXJURY_DB']) is not None, 'Unsafe database name')
        endpoint = re.fullmatch(r'([A-Za-z0-9_.-]+)(?::([0-9]+))?', env['WLXJURY_DB_HOST'])
        require(endpoint is not None, 'Unsupported DB host')
        host, port = endpoint.groups()
        args = [binary, '--defaults-extra-file=' + str(self.runtime / 'db-admin.cnf'),
                '--host=' + host]
        # --port itself forces TCP in MariaDB's client, even if the option file
        # requested a socket. Preserve local root socket auth for bare localhost;
        # use the app's explicit TCP endpoint when a port/remote host is given.
        if port or host != 'localhost':
            args.extend(['--port=' + (port or '3306'), '--protocol=TCP'])
        return args, env

    def query(self, sql):
        args, env = self.client('mariadb')
        return self.run(args + ['--batch', '--skip-column-names', '--database=' + env['WLXJURY_DB'], '-e', sql]).decode().strip()

    def schema(self):
        require(self.query('SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0') == '0',
                'Database has a failed migration')
        return self.query('SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL '
                          'ORDER BY installed_rank DESC LIMIT 1')

    def database_bytes(self):
        size = self.query('SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES '
                          'WHERE TABLE_SCHEMA = DATABASE()')
        require(size.isdigit(), 'Cannot measure the database size')
        return int(size)

    def database_defaults(self):
        """The database's own default character set and collation, to recreate it exactly."""
        row = self.query('SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA '
                         'WHERE SCHEMA_NAME = DATABASE()').split('\t')
        require(len(row) == 2 and all(re.fullmatch(r'[A-Za-z0-9_]+', value) for value in row),
                'Cannot read the database character set')
        return row

    def end_sessions(self, env, database):
        """Before DROP DATABASE: end the stopped app's leftover sessions, such as an
        ALTER that outlived its JVM, and refuse while anyone else uses the database."""
        mine = ("ID <> CONNECTION_ID() AND HEX(USER) = '" + env['WLXJURY_DB_USER'].encode('utf-8').hex().upper() + "'")
        for session in self.query('SELECT ID FROM information_schema.PROCESSLIST WHERE ' + mine).split():
            require(session.isdigit(), 'Unexpected session ID')
            try:
                self.query('KILL ' + session)
            except DeploymentError:
                pass  # It ended on its own.
        deadline = time.monotonic() + self.settings.get('session_end_timeout_seconds', 600)
        # A killed ALTER stays listed until its rollback finishes.
        while self.query('SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ' + mine) != '0':
            require(time.monotonic() < deadline, 'Application DB sessions are still ending; maintenance remains enabled')
            time.sleep(2)
        others = self.query("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID <> CONNECTION_ID() "
                            "AND DB = '" + database + "'")
        require(others == '0', 'Other sessions use the database; inspect SHOW PROCESSLIST before restoring')

    def capacity(self, extra=0, restoring=False):
        reserve = self.settings.get('restore_minimum_free_bytes', self.settings['minimum_free_bytes']) if restoring else self.settings['minimum_free_bytes']
        require(reserve > 0, 'Set a positive restore capacity budget')
        for path in (self.root, self.backups, Path(self.settings['database_dir'])):
            require(path.exists(), 'Missing capacity-check directory: ' + str(path))
            require(shutil.disk_usage(str(path)).free >= reserve + extra,
                    'Capacity budget not met at ' + str(path))

    def identity(self, release):
        release = Path(release)
        require(release.parent.resolve() == self.releases.resolve() and not release.is_symlink(),
                'Release must be a direct, real child of the release directory')
        # Hash each release once per operation: it holds the lock, and releases are root-owned.
        key = str(release.resolve())
        if key in self.verified:
            return dict(self.verified[key])
        require((release / 'bin' / 'wlxjury').is_file(), 'Missing release launcher')
        info = read_json(release / 'wlxjury-build.json')
        require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,95}', info.get('build', '')) is not None,
                'Invalid release build identity')
        require(re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', info.get('schemaVersion', '')) is not None,
                'Invalid release schema version')
        record = read_json(release / 'deployment-release.json')
        require(record['build'] == info['build'], 'Release identity changed after preparation')
        for relative, expected in record['files'].items():
            path = release / relative
            require(not path.is_symlink() and digest(path) == expected, 'Prepared release was modified: ' + relative)
        self.verified[key] = dict(info)
        return info

    def select(self, release):
        self.identity(release)
        require(not self.current.exists() or self.current.is_symlink(), 'current must be a symlink')
        temporary = self.root / 'current.next'
        require(not temporary.exists() and not temporary.is_symlink(), 'Unexpected current.next; inspect interrupted operation')
        temporary.symlink_to(Path(release).resolve(), target_is_directory=True)
        os.replace(str(temporary), str(self.current))
        if os.name == 'posix':
            directory = os.open(str(self.root), os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)

    def record_release(self, directory, info, archive_hash):
        files = {}
        for path in directory.rglob('*'):
            require(not path.is_symlink(), 'Release cannot contain symlinks')
            if path.is_file() and path.name != 'deployment-release.json':
                files[path.relative_to(directory).as_posix()] = digest(path)
        write_json(directory / 'deployment-release.json', {'build': info['build'], 'archiveSha256': archive_hash, 'files': files})
        os.chmod(str(directory / 'deployment-release.json'), 0o644)
        self.verified.pop(str(directory.resolve()), None)

    def prepare(self, archive, expected_hash):
        require(re.fullmatch(r'[0-9a-f]{64}', expected_hash) is not None, 'Pass the full lowercase ZIP SHA-256')
        require(digest(archive) == expected_hash, 'ZIP checksum mismatch')
        with zipfile.ZipFile(archive) as bundle:
            paths = [PurePosixPath(entry.filename) for entry in bundle.infolist()]
            require(paths and len({p.parts[0] for p in paths if p.parts}) == 1, 'ZIP must have one top-level directory')
            parent = paths[0].parts[0]
            seen = set()
            size = 0
            for entry, path in zip(bundle.infolist(), paths):
                require(path.parts and not path.is_absolute() and '..' not in path.parts and
                        '\\' not in entry.filename and ':' not in entry.filename and '\x00' not in entry.filename,
                        'Unsafe ZIP path')
                require(entry.filename not in seen, 'Duplicate ZIP path')
                seen.add(entry.filename)
                mode = entry.external_attr >> 16
                require(not stat.S_ISLNK(mode), 'ZIP symlinks are not supported')
                require(entry.is_dir() or stat.S_IFMT(mode) in (0, stat.S_IFREG), 'ZIP contains a special file')
                size += entry.file_size
            try:
                info = json.loads(bundle.read(parent + '/wlxjury-build.json'))
            except (KeyError, ValueError):
                raise DeploymentError('ZIP lacks a valid build manifest; rebuild with sbt dist') from None
            require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,95}', info.get('build', '')) is not None, 'Invalid build identity')
            self.capacity(size)
            target = self.releases / (info['build'] + '-' + expected_hash[:12])
            require(not target.exists(), 'Release already exists; never overwrite a release')
            target.mkdir(mode=0o755)
            # Keep an incomplete release for diagnostics, but it is unusable until
            # the final deployment-release.json is written and verified.
            for entry, path in zip(bundle.infolist(), paths):
                relative = Path(*path.parts[1:])
                output = target / relative
                if entry.is_dir():
                    output.mkdir(mode=0o755, parents=True, exist_ok=True)
                else:
                    require(relative.parts, 'ZIP root must be a directory')
                    output.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
                    with bundle.open(entry) as source, output.open('xb') as destination:
                        shutil.copyfileobj(source, destination)
                    os.chmod(str(output), 0o755 if relative.parts[0] == 'bin' else 0o644)
            for path in [target] + list(target.rglob('*')):
                if path.is_dir():
                    os.chmod(str(path), 0o755)
            self.record_release(target, info, expected_hash)
            self.identity(target)
        self.say('Prepared ' + str(target))
        return target

    def adopt_legacy(self, source, build):
        source = Path(source).resolve()
        require((source / 'bin' / 'wlxjury').is_file(), 'Legacy installation lacks a launcher')
        require(source != self.releases and self.releases not in source.parents, 'Use prepare for managed releases')
        require(re.fullmatch(r'legacy-[A-Za-z0-9._-]+', build) is not None, 'Legacy build must start with legacy-')
        require(not self.current.exists() and not self.current.is_symlink(), 'A current release is already adopted')
        _, env = self.client('mariadb')  # Validate protected runtime/auth before copying.
        schema = self.schema()
        require(self.query('SELECT @@port') == (env['WLXJURY_DB_HOST'].partition(':')[2] or '3306'),
                'Administrative connection points at a different DB port')
        require(all(version_tuple(v) <= version_tuple(schema) for v in migration_versions(source)),
                'Installed files have pending migrations; retrieve the actual old release before adoption')
        self.capacity(sum(p.stat().st_size for d in ('bin', 'lib', 'conf') for p in (source / d).rglob('*') if p.is_file()))
        target = self.releases / build
        require(not target.exists(), 'Legacy snapshot already exists')
        target.mkdir(mode=0o700)
        os.chmod(str(target), 0o700)  # Private until the copy is verified free of credentials.
        try:
            for name in ('bin', 'lib', 'conf', 'share'):
                if (source / name).exists():
                    shutil.copytree(str(source / name), str(target / name))
            config = target / 'conf' / 'application.conf'
            text = config.read_text(encoding='utf-8')
            config.write_text(externalize_legacy(text), encoding='utf-8')
            # Package-manager conflict copies may contain old secrets; exclude them
            # from the adopted release. Originals are deliberately left untouched.
            for path in (target / 'conf').rglob('*'):
                if path.is_file() and ('.dpkg-' in path.name or path.suffix in ('.bak', '.old')):
                    path.unlink()
            sanitize_copied_config(target / 'conf')
        except BaseException:
            shutil.rmtree(str(target), ignore_errors=True)
            raise
        info = {'build': build, 'version': 'legacy', 'schemaVersion': schema, 'legacy': True}
        write_json(target / 'wlxjury-build.json', info)
        for path in target.rglob('*'):
            os.chmod(str(path), 0o755 if path.is_dir() or 'bin' in path.relative_to(target).parts else 0o644)
        os.chmod(str(target), 0o755)
        self.record_release(target, info, None)
        self.select(target)
        self.say('Adopted sanitized recovery release ' + str(target) + '; service has not been restarted.')

    def http_request(self, url):
        """(status, body, problem); status 0 with a short, secret-free problem on failure."""
        try:
            with self.http.open(url, timeout=5) as response:
                return response.status, response.read(65536), None
        except urllib.error.HTTPError as response:
            return response.code, response.read(65536), None
        except urllib.error.URLError as error:
            reason = error.reason
        except OSError as error:
            reason = error
        if isinstance(reason, ssl.SSLCertVerificationError):
            return 0, b'', 'TLS certificate verification failed'
        if isinstance(reason, ssl.SSLError):
            return 0, b'', 'TLS error'
        if isinstance(reason, socket.gaierror):
            return 0, b'', 'DNS lookup failed'
        if isinstance(reason, socket.timeout):
            return 0, b'', 'timed out'
        return 0, b'', 'connection failed'

    def http_get(self, url):
        status, body, _ = self.http_request(url)
        return status, body

    def check_maintenance(self):
        self.run(['apachectl', 'configtest'])
        for url in self.settings['maintenance_urls']:
            status, body, problem = self.http_request(url)
            require(status == 503, 'Maintenance is not HTTP 503 at ' + url + ' (' + (problem or 'HTTP ' + str(status)) + ')')
            # A vhost without the snippet also answers 503 while the app is stopped
            # (restore, resume-original), but with Apache's own error page.
            require(MAINTENANCE_PAGE in body, 'HTTP 503 at ' + url + ' is not the maintenance page; '
                                              'include wlxjury-maintenance.conf in that vhost')

    def check_public(self):
        """After the flag is removed every jury URL must reach the app again."""
        for url in self.settings['maintenance_urls']:
            status, _, problem = self.http_request(url)
            require(200 <= status < 400, 'Traffic is open but ' + url + ' does not reach the app (' +
                    (problem or 'HTTP ' + str(status)) + '); inspect routing, then rerun reopen')

    def wait_ready(self, release):
        identity = self.identity(release)
        deadline = time.monotonic() + self.settings['ready_timeout_seconds']
        path = '/auth' if identity.get('legacy') else '/health/ready'
        while time.monotonic() < deadline:
            status, body = self.http_get('http://127.0.0.1:' + str(self.port) + path)
            if status == 200 and self.active(self.service):
                if identity.get('legacy'):
                    if self.schema() == identity['schemaVersion']:
                        return
                else:
                    try:
                        actual = json.loads(body)
                        if all(actual.get(k) == identity[k] for k in ('build', 'schemaVersion')):
                            return
                    except ValueError:
                        pass
            time.sleep(2)
        raise DeploymentError('Readiness deadline exceeded; maintenance remains enabled')

    def backup(self, directory):
        args, env = self.client('mariadb-dump')
        output = directory / 'database.sql.gz'
        with output.open('xb') as destination:
            with subprocess.Popen(args + ['--lock-tables', '--quick', '--routines', '--events', '--triggers', env['WLXJURY_DB']],
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as process:
                with gzip.GzipFile(fileobj=destination, mode='wb') as compressed:
                    shutil.copyfileobj(process.stdout, compressed)
                require(process.wait() == 0, 'Database backup failed; service remains stopped')
            destination.flush()
            os.fsync(destination.fileno())
        return digest(output)

    def save(self, directory, record, phase):
        record['phase'] = phase
        write_json(directory / 'deployment.json', record)
        self.say('Deployment ' + record['id'] + ': ' + phase)

    def begin(self):
        require(not self.maintenance.exists(), 'Maintenance already enabled; inspect the interrupted deployment')
        require(stat.S_IMODE(self.runtime.stat().st_mode) & 0o005 == 0o005,
                'Runtime directory must allow Apache to traverse it (0755); protect individual secret files with 0600')
        self.capacity()
        require(self.current.is_symlink(), 'Adopt the original release first')
        previous = self.current.resolve()
        identity = self.identity(previous)
        require(self.schema() == identity['schemaVersion'], 'Current DB does not match the adopted release')
        require(self.query('SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE() '
                           "AND STATUS = 'ENABLED'") == '0', 'Coordinate enabled database events before deployment')
        _, env = self.client('mariadb')
        expected_port = env['WLXJURY_DB_HOST'].partition(':')[2] or '3306'
        require(self.query('SELECT @@port') == expected_port, 'Administrative connection points at a different DB port')
        charset, collation = self.database_defaults()
        name = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + os.urandom(4).hex()
        directory = self.backups / name
        directory.mkdir(mode=0o700)
        record = {'id': name, 'previous': str(previous), 'database': env['WLXJURY_DB'],
                  'host': env['WLXJURY_DB_HOST'], 'charset': charset, 'collation': collation,
                  'traffic_opened': False, 'units': [unit for unit in self.units if self.active(unit)]}
        self.save(directory, record, 'created')
        configs = directory / 'config'
        configs.mkdir(mode=0o700)
        for name in ('runtime.env', 'production.conf', 'logback.xml'):
            shutil.copyfile(str(self.runtime / name), str(configs / name))
            os.chmod(str(configs / name), 0o600)
        if Path('/etc/default/wlxjury').exists():
            shutil.copyfile('/etc/default/wlxjury', str(configs / 'jvm.env'))
            os.chmod(str(configs / 'jvm.env'), 0o600)
        self.maintenance.touch(mode=0o644)
        os.chmod(str(self.maintenance), 0o644)
        self.save(directory, record, 'maintenance')
        self.check_maintenance()  # Verify all hosts before stopping anything.
        self.hold_restarts()
        for unit in record['units']:
            self.stop(unit)
        self.stop(self.service)  # Also stops all V60 scheduler workers in the cgroup.
        self.save(directory, record, 'stopped')
        record['history'] = self.query('SELECT installed_rank, version, checksum, success FROM flyway_schema_history ORDER BY installed_rank')
        record['backupSha256'] = self.backup(directory)
        self.save(directory, record, 'backed-up')
        return directory, record

    def deploy(self, release):
        release = Path(release).resolve()
        candidate = self.identity(release)
        live = self.schema()
        # Flyway would refuse newer history only after the outage had started.
        require(version_tuple(candidate['schemaVersion']) >= version_tuple(live),
                'Release expects schema ' + candidate['schemaVersion'] + ' but the database is at ' + live +
                '; an older release cannot validate newer history. Nothing was changed')
        directory, record = self.begin()
        self.capacity()  # Backup growth must not consume the migration reserve.
        record['candidate'] = str(release)
        self.save(directory, record, 'selecting')
        self.select(release)
        # Persist before start: DDL might run even if systemctl reports failure.
        self.save(directory, record, 'migration-started')
        self.run(['systemctl', 'start', self.service])
        self.wait_ready(release)
        self.save(directory, record, 'ready')
        self.say('Ready under maintenance. Record: ' + str(directory))
        self.say('Smoke-test locally, then use reopen ' + directory.name)
        return directory

    def load_record(self, name):
        require(re.fullmatch(r'[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}', name) is not None, 'Invalid deployment record ID')
        directory = self.backups / name
        require(directory.parent.resolve() == self.backups.resolve() and not directory.is_symlink(), 'Invalid backup directory')
        private_file(directory / 'deployment.json')
        record = read_json(directory / 'deployment.json')
        _, env = self.client('mariadb')
        require(record['database'] == env['WLXJURY_DB'] and record['host'] == env['WLXJURY_DB_HOST'],
                'Backup DB identity differs from current runtime configuration')
        return directory, record

    @staticmethod
    def expected_release(record):
        return record['previous'] if record.get('restored') else record['candidate']

    def open_traffic(self, directory, record, expected):
        self.wait_ready(expected)
        self.release_restarts()
        # Save conservatively BEFORE traffic can resume. An interrupt in this
        # narrow interval must not allow an implicit data-losing restore.
        record['traffic_opened'] = True
        self.save(directory, record, 'opening')
        self.maintenance.unlink(missing_ok=True)
        # Still 'opening' if routing or a paused unit fails: rerun reopen once fixed.
        self.check_public()
        for unit in record['units']:
            self.run(['systemctl', 'start', unit])
        self.save(directory, record, 'complete')

    def reopen(self, name):
        directory, record = self.load_record(name)
        require(record['phase'] in ('ready', 'restored', 'opening'), 'Record is not ready to reopen')
        require(self.maintenance.exists() or (record['phase'] == 'opening' and record['traffic_opened']),
                'Maintenance flag is absent; inspect live routing')
        expected = self.expected_release(record)
        require(self.current.resolve() == Path(expected), 'Current release differs from deployment record')
        if self.maintenance.exists():
            self.check_maintenance()
        self.open_traffic(directory, record, expected)
        self.say('Traffic resumed; snapshots and previous release retained.')

    def abort(self, name):
        """Back out a deployment that stopped before its backup, such as a failed
        maintenance check: the release and database were never changed."""
        directory, record = self.load_record(name)
        require(record['phase'] in ('created', 'maintenance'),
                'Abort is only for deployments stopped before their backup; use resume-original or restore')
        previous = Path(record['previous'])
        require(self.current.resolve() == previous, 'Current release differs from deployment record')
        if not self.active(self.service):  # Interrupted right after stopping it.
            self.run(['systemctl', 'start', self.service])
        record['restored'] = True
        record['aborted'] = True
        self.open_traffic(directory, record, previous)
        self.say('Deployment aborted; the original release serves an unchanged database.')

    def restore(self, name, accept_data_loss=False):
        directory, record = self.load_record(name)
        require(not record['traffic_opened'] or accept_data_loss,
                'Traffic has resumed: restore needs --accept-data-loss after a reconciliation decision')
        require(record.get('backupSha256'), 'No completed backup; use abort or resume-original before migration')
        backup = directory / 'database.sql.gz'
        require(digest(backup) == record['backupSha256'], 'Backup checksum mismatch; no DB changes performed')
        # Check gzip CRC and complete readability BEFORE destroying the target.
        with gzip.open(str(backup), 'rb') as source:
            while source.read(1024 * 1024):
                pass
        previous = Path(record['previous'])
        self.identity(previous)
        charset, collation = record.get('charset', 'utf8mb4'), record.get('collation', 'utf8mb4_unicode_ci')
        require(all(re.fullmatch(r'[A-Za-z0-9_]+', value) for value in (charset, collation)),
                'Invalid character set in deployment record')
        # The rescue dump of post-cutover data needs room as well as the restore.
        self.capacity(extra=self.database_bytes() if record['traffic_opened'] else 0, restoring=True)
        self.maintenance.touch(mode=0o644)
        self.check_maintenance()
        self.hold_restarts()
        for unit in record['units']:
            self.stop(unit)
        self.stop(self.service)
        # Preserve current post-cutover data before an explicitly accepted
        # destructive restore; the original pre-deploy dump is never replaced.
        if record['traffic_opened']:
            rescue = directory / ('before-restore-' + os.urandom(4).hex())
            rescue.mkdir(mode=0o700)
            self.backup(rescue)
        self.save(directory, record, 'restoring')
        db = record['database']  # Validated from runtime.env by client().
        args, env = self.client('mariadb')
        self.end_sessions(env, db)
        self.query('DROP DATABASE `' + db + '`; CREATE DATABASE `' + db + '` CHARACTER SET ' + charset +
                   ' COLLATE ' + collation)
        with subprocess.Popen(args + ['--database=' + db], stdin=subprocess.PIPE, stderr=subprocess.DEVNULL,
                              stdout=subprocess.DEVNULL) as process:
            try:
                with gzip.open(str(backup), 'rb') as source:
                    shutil.copyfileobj(source, process.stdin)
            finally:
                process.stdin.close()
            require(process.wait() == 0, 'DB restore failed; maintenance remains enabled')
        require(self.query('SELECT installed_rank, version, checksum, success FROM flyway_schema_history ORDER BY installed_rank')
                == record['history'], 'Restored Flyway history does not match backup')
        for name in ('runtime.env', 'production.conf', 'logback.xml'):
            target = self.runtime / name
            mode = stat.S_IMODE(target.stat().st_mode)
            atomic_write(target, (directory / 'config' / name).read_text(encoding='utf-8'), mode)
        if (directory / 'config' / 'jvm.env').exists():
            atomic_write(Path('/etc/default/wlxjury'), (directory / 'config' / 'jvm.env').read_text(encoding='utf-8'), 0o644)
        self.select(previous)
        self.run(['systemctl', 'start', self.service])
        self.wait_ready(previous)
        record['restored'] = True
        self.save(directory, record, 'restored')
        self.say('Original DB/config/release restored. Verify locally, then reopen ' + record['id'])

    def resume_original(self, name):
        directory, record = self.load_record(name)
        require(record['phase'] in ('stopped', 'backed-up', 'selecting'),
                'Before the service was stopped use abort; once migration may have started use restore')
        require(not record['traffic_opened'], 'Traffic has resumed; use an explicit recovery decision')
        self.maintenance.touch(mode=0o644)
        self.check_maintenance()
        self.hold_restarts()
        self.stop(self.service)
        self.select(Path(record['previous']))
        self.run(['systemctl', 'start', self.service])
        self.wait_ready(Path(record['previous']))
        record['restored'] = True
        self.save(directory, record, 'restored')
        self.say('Original release resumed without DB changes; reopen ' + record['id'])

    def live_config_is_external(self, pid):
        """The JVM's effective -Dconfig.file is the last one; config.resource/url
        or a JVM options variable naming another config would override or clash."""
        process = self.proc / pid
        argv = (process / 'cmdline').read_bytes().decode('utf-8', 'replace').split('\x00')
        files = [arg for arg in argv if arg.startswith('-Dconfig.file=')]
        if not files or files[-1] != '-Dconfig.file=' + str(self.runtime / 'production.conf'):
            return False
        if any(arg.startswith(('-Dconfig.resource=', '-Dconfig.url=')) for arg in argv):
            return False
        # Only checked, never printed: the environment holds credentials.
        environment = (process / 'environ').read_bytes().decode('utf-8', 'replace').split('\x00')
        return not any(entry.startswith(('JAVA_TOOL_OPTIONS=', 'JDK_JAVA_OPTIONS=', '_JAVA_OPTIONS='))
                       and '-Dconfig.' in entry for entry in environment)

    def scrub_legacy(self, name, path):
        directory, record = self.load_record(name)
        require(record['phase'] == 'complete', 'Complete and verify external-config rollout before removing old literals')
        expected = self.expected_release(record)
        require(self.current.resolve() == Path(expected), 'Current release differs from accepted rollout')
        self.wait_ready(expected)
        pid = self.run(['systemctl', 'show', self.service, '-p', 'MainPID', '--value']).decode().strip()
        require(pid.isdigit() and int(pid) > 0, 'Service has no live process')
        require(self.live_config_is_external(pid), 'Live service is not using the external production config')
        path = Path(path).resolve()
        require(path.name == 'application.conf' and self.releases.resolve() not in path.parents,
                'Specify the original package application.conf, not a managed release')
        text = path.read_text(encoding='utf-8')
        env = read_env(self.runtime / 'runtime.env')
        for key, variable in [('play.http.secret.key', 'PLAY_HTTP_SECRET_KEY'),
                              ('db.default.username', 'WLXJURY_DB_USER'), ('db.default.password', 'WLXJURY_DB_PASSWORD')]:
            require(legacy_value(text, key) == env[variable], 'Legacy credentials differ; inspect on the server: ' + key)
        original = directory / 'config' / 'legacy-secret-config.conf'
        require(not original.exists(), 'Original config was already retained for this record')
        atomic_write(original, text)
        atomic_write(path, externalize_legacy(text), 0o640)
        self.say('Old literal credentials removed; original retained only in the protected recovery record.')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def install_signal_handlers():
    # Interrupted operations keep their durable phase record and maintenance flag.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    # A dropped SSH session must not kill a backup, migration wait or restore
    # halfway; children such as mariadb-dump inherit the ignored disposition.
    signal.signal(signal.SIGHUP, signal.SIG_IGN)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', default='/etc/wlxjury-runtime/deployment.json')
    sub = parser.add_subparsers(dest='command', required=True)
    init = sub.add_parser('init-runtime', help='Export old literal credentials locally; no restart')
    init.add_argument('--legacy-config', required=True)
    init.add_argument('--runtime-dir', default='/etc/wlxjury-runtime')
    prepare = sub.add_parser('prepare', help='Validate ZIP and extract an immutable release')
    prepare.add_argument('zip')
    prepare.add_argument('--sha256', required=True)
    adopt = sub.add_parser('adopt-legacy', help='Snapshot original installation, sanitize credentials, select without restart')
    adopt.add_argument('path')
    adopt.add_argument('--build', required=True)
    deploy = sub.add_parser('deploy', help='Backup, migrate, check readiness; leave maintenance enabled for smoke tests')
    deploy.add_argument('release')
    deploy.add_argument('--writers-reviewed', action='store_true', required=True,
                        help='Other writers/DDL jobs have been identified and coordinated')
    for name, description in (('reopen', 'Open traffic after accepted smoke tests'),
                              ('abort', 'Back out a deployment that stopped before its backup'),
                              ('resume-original', 'Restart the original release after a pre-migration failure'),
                              ('restore', 'Restore the pre-deployment database, config and release')):
        command = sub.add_parser(name, help=description)
        command.add_argument('record')
        if name == 'restore':
            command.add_argument('--accept-data-loss', action='store_true')
    scrub = sub.add_parser('scrub-legacy', help='Remove old literal credentials only after accepted external-config rollout')
    scrub.add_argument('record')
    scrub.add_argument('--legacy-config', required=True)
    args = parser.parse_args(argv)
    require(os.name == 'posix' and os.geteuid() == 0, 'Run on Linux as root')
    os.umask(0o077)
    install_signal_handlers()
    if args.command == 'init-runtime':
        init_runtime(args.legacy_config, args.runtime_dir)
        return
    private_file(args.config)
    deployment = Deployment(read_json(args.config))
    with deployment.locked():
        if args.command == 'prepare':
            deployment.prepare(args.zip, args.sha256)
        elif args.command == 'adopt-legacy':
            deployment.adopt_legacy(args.path, args.build)
        elif args.command == 'deploy':
            deployment.deploy(args.release)
        elif args.command == 'reopen':
            deployment.reopen(args.record)
        elif args.command == 'abort':
            deployment.abort(args.record)
        elif args.command == 'restore':
            deployment.restore(args.record, args.accept_data_loss)
        elif args.command == 'resume-original':
            deployment.resume_original(args.record)
        elif args.command == 'scrub-legacy':
            deployment.scrub_legacy(args.record, args.legacy_config)


if __name__ == '__main__':
    try:
        main()
    except (DeploymentError, OSError, ValueError, zipfile.BadZipFile) as error:
        # Never print an arbitrary config/SQL/driver exception containing secrets.
        message = str(error) if isinstance(error, DeploymentError) else type(error).__name__
        with contextlib.suppress(OSError):
            print('Deployment stopped: ' + message, file=sys.stderr)
            print('Inspect the deployment record. Maintenance is never cleared automatically.', file=sys.stderr)
        sys.exit(1)
