"""Rehearses wlxjury-release.sh and its recovery runbook against a real MariaDB 10.6.

Run in the disposable Dockerfile.test image as root. The script runs unmodified;
only systemd and Apache are stand-ins: a systemctl stub that really starts and
stops the selected release's launcher, a fake app in each release ZIP that
migrates on start and serves readiness, and a fake Apache that honors the
maintenance flag like wlxjury-maintenance.conf.
"""
import fcntl
import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import stat
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.request
import zipfile

SCRIPT = Path(__file__).resolve().parent / 'wlxjury-release.sh'
SERVICE = 'wlxjury.service'
# Like production's backup timer and the job it starts: both must stop.
WRITERS = ['backup.timer', 'backup.service']
MAINTENANCE_PAGE = 'The jury tool is undergoing maintenance. Please try again shortly.'

SYSTEMCTL_STUB = r'''#!/usr/bin/env python3
import os, signal, subprocess, sys, time
from pathlib import Path
state = Path(os.environ['STUB_STATE'])
args = [arg for arg in sys.argv[1:] if arg != '--quiet']
with (state / 'calls').open('a') as calls:
    calls.write(' '.join(args) + '\n')
SERVICE = 'wlxjury.service'

def alive(unit):
    if unit != SERVICE:
        return (state / (unit + '.on')).exists()
    try:
        pid = int((state / 'app.pid').read_text())
        status = Path('/proc/%d/stat' % pid).read_text()
    except (OSError, ValueError):
        return False
    return status.rsplit(') ', 1)[1].split()[0] != 'Z'

hold = Path(os.environ['STUB_HOLD'])
action, unit = args[0], (args[1] if len(args) > 1 else '')
if action == 'daemon-reload':
    with (state / 'reloads').open('a') as reloads:
        reloads.write(('held' if hold.exists() else 'released') + '\n')
elif action == 'is-active':
    sys.exit(0 if alive(unit) else 3)
elif action == 'start' and unit == SERVICE:
    if not alive(unit):
        current = Path(os.environ['STUB_CURRENT'])
        with (state / 'starts').open('a') as starts:
            starts.write(str(current.resolve()) + (' held' if hold.exists() else ' unheld') + '\n')
        log = (state / 'app.log').open('a')
        app = subprocess.Popen([str(current / 'bin' / 'wlxjury')], stdout=log, stderr=log,
                               cwd=str(current), start_new_session=True)
        (state / 'app.pid').write_text(str(app.pid))
elif action == 'start':
    (state / (unit + '.on')).touch()
elif action == 'stop' and unit == SERVICE:
    if alive(unit):
        os.kill(int((state / 'app.pid').read_text()), signal.SIGTERM)
        for _ in range(100):
            if not alive(unit):
                break
            time.sleep(0.1)
elif action == 'stop':
    (state / (unit + '.on')).unlink(missing_ok=True)
else:
    sys.exit('unsupported systemctl call: ' + ' '.join(args))
'''

FAKE_APP = r'''#!/usr/bin/env python3
"""Stand-in for bin/wlxjury: Flyway-like migration on start, then readiness."""
import json, os, subprocess, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
manifest = json.loads((Path(__file__).resolve().parent.parent / 'wlxjury-build.json').read_text())
client = ['mariadb', '--defaults-extra-file=' + os.environ['APP_ADMIN_CNF'], '--batch',
          '--skip-column-names', '--database=' + os.environ['APP_DATABASE']]

def sql(query):
    return subprocess.run(client + ['-e', query], check=True, stdout=subprocess.PIPE, text=True).stdout.strip()

def schema():
    return sql('SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1')

def key(version):
    return tuple(int(part) for part in version.split('.'))

if key(manifest['schemaVersion']) > key(schema()):
    sql('ALTER TABLE votes ENGINE=InnoDB; CREATE TABLE job (id INT PRIMARY KEY) ENGINE=InnoDB')
    if manifest.get('failMigration'):
        sys.exit('killed mid-migration: DDL applied, no history row')
    sql("INSERT INTO flyway_schema_history SELECT MAX(installed_rank) + 1, '%s', 456, 1 "
        "FROM flyway_schema_history" % manifest['schemaVersion'])

class App(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/health/ready' and not manifest.get('legacy'):
            if schema() == manifest['schemaVersion']:
                self.reply(200, json.dumps({'build': manifest['build'], 'schemaVersion': manifest['schemaVersion']}))
            else:
                self.reply(503, '{"status":"unavailable"}')
        elif self.path in ('/', '/auth'):
            self.reply(200, 'jury app ' + manifest['build'])
        else:
            self.reply(404, 'not found')

    def reply(self, status, body):
        data = body.encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass

HTTPServer(('127.0.0.1', int(os.environ['APP_PORT'])), App).serve_forever()
'''


def free_port():
    with socket.socket() as available:
        available.bind(('127.0.0.1', 0))
        return available.getsockname()[1]


class FakeApache(http.server.BaseHTTPRequestHandler):
    """Every jury vhost includes the maintenance snippet, except /no-snippet."""

    def do_GET(self):
        test = self.server.test
        if self.path in test.broken_routes:
            self.reply(502, 'Proxy Error')
        elif self.path != '/no-snippet' and test.flag.exists():
            self.reply(503, MAINTENANCE_PAGE)
        else:
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                with opener.open('http://127.0.0.1:%d/' % test.app_port, timeout=2) as response:
                    self.reply(200, response.read().decode('utf-8'))
            except OSError:
                self.reply(503, 'Service Unavailable')

    def reply(self, status, body):
        data = body.encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


@unittest.skipUnless(shutil.which('mariadbd') and shutil.which('unzip') and shutil.which('curl')
                     and hasattr(os, 'geteuid') and os.geteuid() == 0,
                     'Run as root in the Dockerfile.test image')
class ReleaseScriptTest(unittest.TestCase):
    def setUp(self):
        self.base = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, str(self.base), True)
        self.root = self.base / 'opt'
        self.runtime = self.base / 'runtime'
        self.backups = self.base / 'backups'
        self.dropin = self.base / 'systemd' / 'wlxjury.service.d'
        self.state = self.base / 'state'
        stubs = self.base / 'bin'
        for directory in (self.root / 'releases', self.runtime, self.backups, self.dropin, self.state, stubs):
            directory.mkdir(parents=True)
        self.flag = self.runtime / 'maintenance'
        self.hold = self.dropin / 'zz-deployment-hold.conf'
        self.app_port = free_port()
        self.start_database()
        (stubs / 'systemctl').write_text(SYSTEMCTL_STUB)
        (stubs / 'systemctl').chmod(0o755)
        self.broken_routes = set()
        self.apache = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FakeApache)
        self.apache.test = self
        threading.Thread(target=self.apache.serve_forever, daemon=True).start()
        self.addCleanup(self.apache.server_close)
        self.addCleanup(self.apache.shutdown)
        self.urls = ['http://127.0.0.1:%d/a' % self.apache.server_port,
                     'http://127.0.0.1:%d/b' % self.apache.server_port]
        self.write_config()
        self.env = dict(os.environ, PATH=str(stubs) + os.pathsep + os.environ['PATH'],
                        STUB_STATE=str(self.state), STUB_CURRENT=str(self.root / 'current'),
                        STUB_HOLD=str(self.hold), APP_PORT=str(self.app_port),
                        APP_ADMIN_CNF=str(self.runtime / 'db-admin.cnf'), APP_DATABASE='jury')
        # The adopted 0.12 release is running, as after the cutover.
        self.old = self.root / 'releases' / 'legacy-0.12-v53'
        self.write_release(self.old, {'build': 'legacy-0.12-v53', 'version': 'legacy',
                                      'schemaVersion': '53', 'legacy': True})
        (self.root / 'current').symlink_to(self.old)
        for unit in WRITERS:
            (self.state / (unit + '.on')).touch()
        self.systemctl('start', SERVICE)
        self.addCleanup(self.systemctl, 'stop', SERVICE)
        self.wait_for_app()
        self.original_votes = self.sql('SELECT * FROM votes ORDER BY id')

    def start_database(self):
        datadir = self.base / 'mysql'
        self.datadir = datadir
        self.socket = self.base / 'mysql.sock'
        self.db_port = free_port()
        subprocess.run(['mariadb-install-db', '--no-defaults', '--auth-root-authentication-method=normal',
                        '--datadir=' + str(datadir)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        server = subprocess.Popen(['mariadbd', '--no-defaults', '--user=root', '--datadir=' + str(datadir),
                                   '--socket=' + str(self.socket), '--bind-address=127.0.0.1',
                                   '--port=' + str(self.db_port), '--innodb-buffer-pool-size=64M',
                                   '--log-error=' + str(self.base / 'mysql.log')],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        def stop_server():
            server.terminate()
            try:
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=5)
        self.addCleanup(stop_server)
        admin = self.runtime / 'db-admin.cnf'
        admin.write_text('[client]\nuser=root\nprotocol=socket\nsocket=' + str(self.socket) + '\n')
        admin.chmod(0o600)
        (self.runtime / 'runtime.env').write_text(
            'WLXJURY_DB_HOST="localhost:%d"\nWLXJURY_DB="jury"\nWLXJURY_DB_USER="app"\n'
            'WLXJURY_DB_PASSWORD="app-password"\nPLAY_HTTP_SECRET_KEY="%s"\n' % (self.db_port, 'k' * 40))
        deadline = time.monotonic() + 30
        while subprocess.run(self.client() + ['-e', 'SELECT 1'], stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL).returncode != 0:
            self.assertLess(time.monotonic(), deadline, 'Disposable MariaDB did not start')
            time.sleep(0.2)
        subprocess.run(self.client() + ['-e', 'CREATE DATABASE jury CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci'],
                       check=True)
        self.sql("CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(20), "
                 "checksum INT, success BOOLEAN) ENGINE=InnoDB; "
                 "INSERT INTO flyway_schema_history VALUES (1, '53', 123, 1); "
                 "CREATE TABLE votes (id INT PRIMARY KEY, value VARCHAR(100)) ENGINE=MyISAM; "
                 "INSERT INTO votes VALUES (1, 'first vote'), (2, 'Україна')")

    def client(self):
        return ['mariadb', '--defaults-extra-file=' + str(self.runtime / 'db-admin.cnf'), '--batch', '--skip-column-names']

    def sql(self, query):
        return subprocess.run(self.client() + ['--database=jury', '-e', query], check=True,
                              stdout=subprocess.PIPE, text=True).stdout.strip()

    def write_config(self, urls=None):
        config = {'release_root': str(self.root), 'runtime_dir': str(self.runtime),
                  'backup_root': str(self.backups), 'database_dir': str(self.datadir),
                  'service': SERVICE, 'systemd_dropin_dir': str(self.dropin), 'port': self.app_port,
                  'ready_timeout_seconds': 10, 'minimum_free_bytes': 1,
                  'quiesce_units': WRITERS, 'maintenance_urls': urls or self.urls}
        self.config = self.runtime / 'deployment.json'
        self.config.write_text(json.dumps(config))
        self.config.chmod(0o600)

    @staticmethod
    def write_release(directory, manifest):
        (directory / 'bin').mkdir(parents=True)
        (directory / 'bin' / 'wlxjury').write_text(FAKE_APP)
        (directory / 'bin' / 'wlxjury').chmod(0o755)
        (directory / 'wlxjury-build.json').write_text(json.dumps(manifest))

    def release_zip(self, schema='60', build='0.13-test', fail=False):
        """A ZIP like sbt dist's: one top-level folder and no permission bits."""
        manifest = {'build': build, 'version': '0.13', 'schemaVersion': schema}
        if fail:
            manifest['failMigration'] = True
        archive = self.base / ('wlxjury-%s.zip' % build)
        with zipfile.ZipFile(str(archive), 'w') as bundle:
            bundle.writestr('wlxjury-0.13/bin/wlxjury', FAKE_APP)
            bundle.writestr('wlxjury-0.13/lib/app.jar', 'jar')
            bundle.writestr('wlxjury-0.13/wlxjury-build.json', json.dumps(manifest))
        return archive, hashlib.sha256(archive.read_bytes()).hexdigest()

    def release(self, *args):
        return subprocess.run(['bash', str(SCRIPT), '--config', str(self.config)] + [str(arg) for arg in args],
                              env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=120)

    def systemctl(self, *args):
        return subprocess.run([str(self.base / 'bin' / 'systemctl')] + list(args), env=self.env).returncode

    def active(self, unit):
        return self.systemctl('is-active', '--quiet', unit) == 0

    def wait_for_app(self):
        deadline = time.monotonic() + 15
        while True:
            try:
                with urllib.request.urlopen('http://127.0.0.1:%d/auth' % self.app_port, timeout=1):
                    return
            except OSError:
                self.assertLess(time.monotonic(), deadline, 'Fake app did not start')
                time.sleep(0.1)

    def operation(self):
        operations = [path for path in self.backups.iterdir() if path.is_dir()]
        self.assertEqual(len(operations), 1, operations)
        return operations[0]

    @staticmethod
    def milestones(operation):
        path = operation / 'milestones'
        return path.read_text().split() if path.exists() else []

    def calls(self):
        path = self.state / 'calls'
        return path.read_text().splitlines() if path.exists() else []

    def assertSucceeded(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def assertUnchanged(self):
        """Nothing stopped, no maintenance, no release or operation left behind."""
        self.assertFalse(self.flag.exists())
        self.assertTrue(self.active(SERVICE))
        self.assertTrue(all(self.active(unit) for unit in WRITERS))
        self.assertEqual(sorted(path.name for path in (self.root / 'releases').iterdir()), ['legacy-0.12-v53'])
        self.assertEqual(list(self.backups.iterdir()), [])
        self.assertEqual(self.sql('SELECT MAX(version) FROM flyway_schema_history'), '53')

    def test_deploy_migrates_under_maintenance_and_reopen_resumes_traffic(self):
        archive, sha = self.release_zip()
        result = self.release('deploy', archive, sha)
        self.assertSucceeded(result)
        operation = self.operation()
        self.assertEqual(self.milestones(operation), ['started', 'backup-complete', 'migration-may-start', 'ready'])
        release = self.root / 'releases' / ('0.13-test-' + sha[:12])
        self.assertEqual((self.root / 'current').resolve(), release)
        self.assertIn('wlxjury-release reopen ' + operation.name, result.stdout)
        # Migrated while closed: maintenance on, restarts held, the writers still stopped.
        self.assertEqual(self.sql('SELECT MAX(version) FROM flyway_schema_history'), '60')
        self.assertTrue(self.flag.exists())
        self.assertTrue(self.hold.exists())
        self.assertFalse(any(self.active(unit) for unit in WRITERS))
        self.assertEqual((self.state / 'starts').read_text().splitlines()[-1], str(release) + ' held')
        # A root-owned release with an executable launcher, from a ZIP without permission bits.
        launcher = (release / 'bin' / 'wlxjury').stat()
        self.assertEqual(stat.S_IMODE(launcher.st_mode), 0o755)
        self.assertEqual(launcher.st_uid, 0)
        self.assertEqual(stat.S_IMODE((release / 'lib' / 'app.jar').stat().st_mode), 0o644)
        self.assertEqual([path.name for path in (self.root / 'releases').iterdir() if path.name.startswith('.')], [])
        # The operation keeps a verified dump, a config snapshot and the previous release.
        check = subprocess.run(['sha256sum', '-c', '--quiet', 'database.sql.gz.sha256'], cwd=str(operation))
        self.assertEqual(check.returncode, 0)
        self.assertTrue((operation / 'config.tgz').is_file())
        record = (operation / 'record').read_text()
        self.assertIn('previous=' + str(self.old) + '\n', record)
        self.assertIn('writers=backup.timer backup.service\n', record)
        self.assertEqual(stat.S_IMODE(operation.stat().st_mode), 0o700)

        reopened = self.release('reopen', operation.name)
        self.assertSucceeded(reopened)
        self.assertFalse(self.flag.exists())
        self.assertFalse(self.hold.exists())
        self.assertEqual((self.state / 'reloads').read_text().splitlines()[-1], 'released')
        self.assertTrue(all(self.active(unit) for unit in WRITERS))
        self.assertEqual(self.milestones(operation)[-2:], ['traffic-may-resume', 'complete'])
        self.assertIn('Milestone: complete', (operation / 'operation.log').read_text())

    def test_runbook_restore_after_an_interrupted_migration_recovers_the_exact_database(self):
        archive, sha = self.release_zip(fail=True)
        result = self.release('deploy', archive, sha)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn('readiness deadline exceeded', result.stderr)
        self.assertIn('follow the runbook for the last milestone', result.stderr)
        operation = self.operation()
        self.assertEqual(self.milestones(operation)[-1], 'migration-may-start')
        self.assertTrue(self.flag.exists())
        self.assertTrue(self.hold.exists())
        self.assertEqual(self.sql("SELECT COUNT(*) FROM information_schema.TABLES "
                                  "WHERE TABLE_SCHEMA = 'jury' AND TABLE_NAME = 'job'"), '1')
        # Reopening refuses a release that is not ready.
        refused = self.release('reopen', operation.name)
        self.assertEqual(refused.returncode, 1)
        self.assertIn('is not ready', refused.stderr)
        self.assertTrue(self.flag.exists())

        # The tempting shortcut: the old release starts and looks ready, because the
        # interrupted migration left no history row, but its DDL already ran.
        self.systemctl('stop', SERVICE)
        previous = (operation / 'record').read_text().split('previous=')[1].split('\n')[0]
        (self.root / 'current.next').symlink_to(previous)
        os.replace(str(self.root / 'current.next'), str(self.root / 'current'))
        self.systemctl('start', SERVICE)
        self.wait_for_app()
        shortcut = self.release('reopen', operation.name)
        self.assertEqual(shortcut.returncode, 1)
        self.assertIn('a migration may have run', shortcut.stderr)
        self.assertTrue(self.flag.exists())

        # Runbook, "migration-may-start, traffic still blocked": restore, record it, reopen.
        self.systemctl('stop', SERVICE)
        subprocess.run(['sha256sum', '-c', '--quiet', 'database.sql.gz.sha256'], cwd=str(operation), check=True)
        subprocess.run(['bash', '-c', 'zcat database.sql.gz | mariadb "--defaults-extra-file=$0"',
                        str(self.runtime / 'db-admin.cnf')], cwd=str(operation), check=True)
        with (operation / 'milestones').open('a') as milestones:
            milestones.write('database-restored\n')
        self.systemctl('start', SERVICE)
        self.wait_for_app()
        self.assertSucceeded(self.release('reopen', operation.name))

        # Exactly the old database: history, data, engine and collation, without the new table.
        self.assertEqual(self.sql('SELECT MAX(version) FROM flyway_schema_history'), '53')
        self.assertEqual(self.sql('SELECT * FROM votes ORDER BY id'), self.original_votes)
        self.assertEqual(self.sql("SELECT ENGINE FROM information_schema.TABLES "
                                  "WHERE TABLE_SCHEMA = 'jury' AND TABLE_NAME = 'votes'"), 'MyISAM')
        self.assertEqual(self.sql("SELECT GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME) FROM information_schema.TABLES "
                                  "WHERE TABLE_SCHEMA = 'jury'"), 'flyway_schema_history,votes')
        self.assertEqual(self.sql("SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA "
                                  "WHERE SCHEMA_NAME = 'jury'"), 'utf8mb4_general_ci')
        self.assertFalse(self.flag.exists())
        self.assertFalse(self.hold.exists())
        self.assertEqual((self.root / 'current').resolve(), self.old)

    def test_older_release_is_refused_before_anything_changes(self):
        archive, sha = self.release_zip(schema='50', build='0.11-old')
        result = self.release('deploy', archive, sha)
        self.assertEqual(result.returncode, 1)
        self.assertIn('nothing was changed', result.stderr)
        self.assertUnchanged()
        self.assertFalse(any(call.startswith('stop') for call in self.calls()))

    def test_checksum_mismatch_and_existing_maintenance_change_nothing(self):
        archive, sha = self.release_zip()
        result = self.release('deploy', archive, 'f' * 64)
        self.assertEqual(result.returncode, 1)
        self.assertIn('checksum mismatch', result.stderr)
        self.assertUnchanged()
        self.flag.touch()
        blocked = self.release('deploy', archive, sha)
        self.assertEqual(blocked.returncode, 1)
        self.assertIn('maintenance is on', blocked.stderr)
        self.flag.unlink()
        self.assertUnchanged()

    def test_a_concurrent_operation_is_refused(self):
        archive, sha = self.release_zip()
        with (self.root / 'deployment.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.release('deploy', archive, sha)
        self.assertEqual(result.returncode, 1)
        self.assertIn('holds the lock', result.stderr)
        self.assertUnchanged()

    def test_a_vhost_without_the_maintenance_page_stops_the_deployment_before_the_app_stops(self):
        no_snippet = 'http://127.0.0.1:%d/no-snippet' % self.apache.server_port
        self.write_config(self.urls + [no_snippet])
        archive, sha = self.release_zip()
        result = self.release('deploy', archive, sha)
        self.assertEqual(result.returncode, 1)
        self.assertIn(no_snippet + ' does not show the maintenance page (HTTP 200)', result.stderr)
        operation = self.operation()
        self.assertEqual(self.milestones(operation), ['started'])
        self.assertTrue(self.active(SERVICE))
        self.assertTrue(all(self.active(unit) for unit in WRITERS))
        self.assertTrue(self.flag.exists())
        self.assertFalse(self.hold.exists())
        # A missing milestones file means "unknown", not "nothing happened".
        saved = (operation / 'milestones').read_text()
        (operation / 'milestones').unlink()
        unknown = self.release('reopen', operation.name)
        self.assertEqual(unknown.returncode, 1)
        self.assertIn('state is unknown', unknown.stderr)
        self.assertTrue(self.flag.exists())
        (operation / 'milestones').write_text(saved)
        # Runbook, "no migration-may-start" with the app still running: reopen.
        self.assertSucceeded(self.release('reopen', operation.name))
        self.assertFalse(self.flag.exists())
        self.assertEqual((self.root / 'current').resolve(), self.old)

    def test_reopen_can_be_rerun_after_a_routing_failure(self):
        archive, sha = self.release_zip()
        self.assertSucceeded(self.release('deploy', archive, sha))
        operation = self.operation()
        self.broken_routes.add('/b')
        failed = self.release('reopen', operation.name)
        self.assertEqual(failed.returncode, 1)
        self.assertIn('/b answers HTTP 502; fix routing, then rerun reopen', failed.stderr)
        self.assertEqual(self.milestones(operation)[-1], 'traffic-may-resume')
        self.assertFalse(self.flag.exists())
        self.assertFalse(any(self.active(unit) for unit in WRITERS))
        self.broken_routes.clear()
        self.assertSucceeded(self.release('reopen', operation.name))
        self.assertEqual(self.milestones(operation)[-1], 'complete')
        self.assertTrue(all(self.active(unit) for unit in WRITERS))

    def test_reopen_refuses_a_folder_outside_the_backups(self):
        result = self.release('reopen', str(self.base))
        self.assertEqual(result.returncode, 1)
        self.assertIn('not an operation folder', result.stderr)


if __name__ == '__main__':
    unittest.main()
