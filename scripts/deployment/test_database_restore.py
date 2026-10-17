"""Real MariaDB 10.6 mixed-engine dump/restore test. No systemd or production access.

Run in the disposable Dockerfile.test image. Tests own and stop their DB process.
"""
import contextlib
import gzip
import io
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import unittest
import urllib.error
import urllib.request
import zipfile

from test_deployment import Fixture, deploy


class LocalDatabaseDeployment(deploy.Deployment):
    def __init__(self, settings):
        super().__init__(settings)
        self.running = {self.service}

    def run(self, args, **kwargs):
        if args[0] == 'systemctl':
            if args[1] == 'daemon-reload':
                return b''
            action, unit = args[1:3]
            if action == 'stop':
                self.running.discard(unit)
            elif action == 'start':
                self.running.add(unit)
                if unit == self.service and self.current.resolve().name == 'new':
                    self.query('ALTER TABLE votes ENGINE=InnoDB; '
                               "INSERT INTO flyway_schema_history VALUES (2, '60', 456, 1)")
            elif action == 'show':
                if 'MainPID' in args:
                    return b'1' if unit in self.running else b'0'
                return b'active' if unit in self.running else b'inactive'
            return b''
        return super().run(args, **kwargs)

    def check_maintenance(self):
        deploy.require(self.maintenance.exists(), 'Expected maintenance before DB mutation')

    def check_public(self):
        deploy.require(not self.maintenance.exists(), 'Expected maintenance to be off')

    def http_get(self, url):
        identity = self.identity(self.current.resolve())
        if self.schema() != identity['schemaVersion']:
            return 503, b''
        return 200, json.dumps(identity).encode('utf-8')


@unittest.skipUnless(shutil.which('mariadbd') and shutil.which('mariadb-install-db'), 'Disposable Linux MariaDB image required')
class DatabaseFixture(Fixture):
    def setUp(self):
        super().setUp()
        self.settings['quiesce_units'] = []
        self.tool = LocalDatabaseDeployment(self.settings)
        self.socket = self.base / 'mysql.sock'
        with socket.socket() as available:
            available.bind(('127.0.0.1', 0))
            self.port = available.getsockname()[1]
        values = deploy.read_env(self.tool.runtime / 'runtime.env')
        values['WLXJURY_DB_HOST'] = 'localhost:' + str(self.port)
        deploy.atomic_write(self.tool.runtime / 'runtime.env', ''.join(k + '=' + deploy.env_quote(v) + '\n' for k, v in values.items()))
        datadir = self.base / 'mysql'
        subprocess.run(['mariadb-install-db', '--no-defaults', '--auth-root-authentication-method=normal',
                        '--datadir=' + str(datadir)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.server = subprocess.Popen(['mariadbd', '--no-defaults', '--user=root', '--datadir=' + str(datadir),
                                        '--socket=' + str(self.socket), '--pid-file=' + str(self.base / 'mysql.pid'),
                                        '--bind-address=127.0.0.1', '--port=' + str(self.port), '--innodb-buffer-pool-size=64M',
                                        '--log-error=' + str(self.base / 'mysql.log')],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(self.stop_server)
        deploy.atomic_write(self.tool.runtime / 'db-admin.cnf',
                            '[client]\nuser=root\nprotocol=socket\nsocket=' + str(self.socket) + '\n')
        deadline = time.monotonic() + 30
        args, _ = self.tool.client('mariadb')
        while time.monotonic() < deadline:
            ready = subprocess.run(args + ['-e', 'SELECT 1'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                break
            time.sleep(0.2)
        else:
            log = (self.base / 'mysql.log').read_text() if (self.base / 'mysql.log').exists() else 'no server log'
            client = subprocess.run(args + ['-e', 'SELECT 1'], capture_output=True, text=True)
            self.fail('Disposable MariaDB did not start: ' + log + '\nclient: ' + client.stderr)
        self.tool.run(args + ['-e', 'CREATE DATABASE jury CHARACTER SET utf8mb4'])
        self.tool.query("CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(20), "
                        "checksum INT, success BOOLEAN) ENGINE=InnoDB; "
                        "INSERT INTO flyway_schema_history VALUES (1, '53', 123, 1); "
                        "CREATE TABLE votes (id INT PRIMARY KEY, value VARCHAR(100)) ENGINE=MyISAM; "
                        "INSERT INTO votes VALUES (1, 'first vote'), (2, 'Україна')")
        self.old = self.release('old', '53')
        self.new = self.release('new', '60')
        self.tool.select(self.old)

    def stop_server(self):
        if self.server.poll() is None:
            self.server.terminate()
            try:
                self.server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.server.kill()
                self.server.wait(timeout=5)


class DatabaseRestoreTest(DatabaseFixture):

    def test_mixed_engine_backup_restores_old_data_engine_history_and_release(self):
        original = self.tool.query('SELECT * FROM votes ORDER BY id')
        defaults = self.tool.database_defaults()
        with contextlib.redirect_stdout(io.StringIO()):
            directory = self.tool.deploy(self.new)
            self.assertEqual(self.tool.schema(), '60')
            self.assertEqual(self.tool.query("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='votes'"), 'InnoDB')
            self.tool.query("INSERT INTO votes VALUES (3, 'deployment smoke test')")
            self.tool.restore(directory.name)
        self.assertEqual(self.tool.schema(), '53')
        self.assertEqual(self.tool.query('SELECT * FROM votes ORDER BY id'), original)
        self.assertEqual(self.tool.query("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='votes'"), 'MyISAM')
        self.assertEqual(self.tool.current.resolve(), self.old)
        self.assertTrue(self.tool.maintenance.exists())
        # The database's own default, not a fixed utf8mb4_unicode_ci.
        self.assertEqual(self.tool.database_defaults(), defaults)
        self.assertEqual(defaults[1], 'utf8mb4_general_ci')
        with gzip.open(str(directory / 'database.sql.gz'), 'rt') as snapshot:
            self.assertIn('flyway_schema_history', snapshot.read())

    def test_restore_ends_a_session_the_stopped_app_left_behind(self):
        args, _ = self.tool.client('mariadb')
        self.tool.run(args, input=b"CREATE USER 'app'@'localhost' IDENTIFIED BY 'sleeper'; "
                                  b"GRANT ALL PRIVILEGES ON jury.* TO 'app'@'localhost';")
        options = self.base / 'app.cnf'
        deploy.atomic_write(options, '[client]\nuser=app\npassword=sleeper\nsocket=' + str(self.socket) + '\n')
        with contextlib.redirect_stdout(io.StringIO()):
            directory = self.tool.deploy(self.new)
        # Stands in for an ALTER that outlived its JVM.
        leftover = subprocess.Popen(['mariadb', '--defaults-file=' + str(options), '--database=jury',
                                     '-e', 'SELECT SLEEP(600)'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(leftover.kill)
        deadline = time.monotonic() + 15
        while self.tool.query("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER = 'app'") != '1':
            self.assertLess(time.monotonic(), deadline, 'Leftover session did not start')
            time.sleep(0.2)
        started = time.monotonic()
        with contextlib.redirect_stdout(io.StringIO()):
            self.tool.restore(directory.name)
        self.assertLess(time.monotonic() - started, 60)
        self.assertIsNotNone(leftover.wait(timeout=10))
        self.assertEqual(self.tool.schema(), '53')

    def test_post_cutover_restore_ends_leftover_locks_before_the_rescue_dump(self):
        args, _ = self.tool.client('mariadb')
        self.tool.run(args, input=b"CREATE USER 'app'@'localhost' IDENTIFIED BY 'sleeper'; "
                                  b"GRANT ALL PRIVILEGES ON jury.* TO 'app'@'localhost'; "
                                  b"SET GLOBAL lock_wait_timeout=2;")
        options = self.base / 'app.cnf'
        deploy.atomic_write(options, '[client]\nuser=app\npassword=sleeper\nsocket=' + str(self.socket) + '\n')
        with contextlib.redirect_stdout(io.StringIO()):
            directory = self.tool.deploy(self.new)
            self.tool.reopen(directory.name)
        self.tool.query("INSERT INTO votes VALUES (3, 'post-cutover locked vote')")
        # A table lock survives the stopped app; a rescue dump must not wait on it.
        # The low metadata-lock timeout bounds the test if cleanup regresses.
        leftover = subprocess.Popen(['mariadb', '--defaults-file=' + str(options), '--database=jury',
                                     '-e', 'LOCK TABLES votes WRITE; SELECT SLEEP(600)'],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def stop_leftover():
            if leftover.poll() is None:
                leftover.kill()
            leftover.wait(timeout=10)
        self.addCleanup(stop_leftover)
        deadline = time.monotonic() + 15
        while self.tool.query("SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                             "WHERE USER='app' AND INFO LIKE 'SELECT SLEEP%'") != '1':
            self.assertLess(time.monotonic(), deadline, 'Leftover lock did not start')
            time.sleep(0.2)
        with contextlib.redirect_stdout(io.StringIO()):
            self.tool.restore(directory.name, accept_data_loss=True)
        self.assertIsNotNone(leftover.wait(timeout=10))
        rescue = list(directory.glob('before-restore-*/database.sql.gz'))
        self.assertEqual(len(rescue), 1)
        with gzip.open(str(rescue[0]), 'rt') as snapshot:
            self.assertIn('post-cutover locked vote', snapshot.read())
        self.assertEqual(self.tool.schema(), '53')
        self.assertEqual(self.tool.query('SELECT COUNT(*) FROM votes'), '2')
        self.assertTrue(self.tool.maintenance.exists())


class PackagedReleaseTest(DatabaseFixture):
    def test_real_zip_starts_in_production_and_rejects_missing_signing_key(self):
        archive = Path('/workspace/target/universal/wlxjury-0.13.zip')
        if not archive.is_file():
            self.skipTest('Run sbt dist before the packaged-release smoke test')
        with zipfile.ZipFile(str(archive)) as bundle:
            deployment_only = [name for name in bundle.namelist()
                               if any('/conf/' + part + '/' in name for part in ('wlxjury', 'apache', 'systemd'))]
        self.assertEqual(deployment_only, [], 'Server setup files belong in the checkout, not the release')
        with contextlib.redirect_stdout(io.StringIO()):
            release = self.tool.prepare(archive, deploy.digest(archive))
        env = deploy.read_env(self.tool.runtime / 'runtime.env')
        env['WLXJURY_DB_HOST'] = '127.0.0.1:' + str(self.port)
        password = "'" + env['WLXJURY_DB_PASSWORD'].replace('\\', '\\\\').replace("'", "\\'") + "'"
        args, _ = self.tool.client('mariadb')
        # Synthetic credentials enter through stdin, never argv.
        self.tool.run(args, input=("DROP DATABASE jury; CREATE DATABASE jury CHARACTER SET utf8mb4; "
                                  "CREATE USER 'app'@'localhost' IDENTIFIED BY " + password + "; "
                                  "GRANT ALL PRIVILEGES ON jury.* TO 'app'@'localhost';").encode())
        environment = os.environ.copy()
        environment.update(env)
        environment['JAVA_OPTS'] = '-Xmx512m -Dfile.encoding=UTF-8'
        command = [str(release / 'bin' / 'wlxjury'),
                   '-Dconfig.file=/workspace/conf/wlxjury/production.conf',
                   '-Dlogger.file=/workspace/conf/wlxjury/logback.xml',
                   '-Dhttp.address=127.0.0.1', '-Dhttp.port=9000',
                   '-Dplay.mailer.mock=true', '-Dpidfile.path=' + str(self.base / 'play.pid'),
                   '-Dwlxjury.thumbs.local-path=' + str(self.base / 'images')]
        missing = environment.copy()
        missing.pop('PLAY_HTTP_SECRET_KEY')
        failed = subprocess.run(command, env=missing, cwd=str(release), capture_output=True, timeout=30)
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn(b'PLAY_HTTP_SECRET_KEY', failed.stdout + failed.stderr)
        console = self.base / 'app-console.log'
        with console.open('wb') as output:
            app = subprocess.Popen(command, env=environment, cwd=str(release), stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline and app.poll() is None:
                    # Use a real HTTP client, not the lifecycle fixture's fake.
                    status, body = deploy.Deployment.http_get(self.tool, 'http://127.0.0.1:9000/health/ready')
                    if status == 200:
                        self.assertEqual(json.loads(body)['build'], self.tool.identity(release)['build'])
                        self.assertEqual(json.loads(body)['schemaVersion'], '60')
                        self.assertEqual(self.tool.schema(), '60')
                        # Through Apache the request carries the client's address:
                        # readiness stays local to the server.
                        proxied = urllib.request.Request('http://127.0.0.1:9000/health/ready',
                                                         headers={'X-Forwarded-For': '203.0.113.5'})
                        with self.assertRaises(urllib.error.HTTPError) as remote:
                            self.tool.http.open(proxied, timeout=5)
                        self.assertEqual(remote.exception.code, 404)
                        break
                    time.sleep(1)
                else:
                    self.fail('Packaged production app did not become ready: ' + console.read_text()[-10000:])
            finally:
                app.terminate()
                try:
                    app.wait(timeout=40)
                except subprocess.TimeoutExpired:
                    app.kill()
                    app.wait(timeout=5)
        self.assertNotIn('heartbeat failed', console.read_text())

    def test_localhost_preserves_explicit_socket_authentication(self):
        values = deploy.read_env(self.tool.runtime / 'runtime.env')
        values['WLXJURY_DB_HOST'] = 'localhost'
        deploy.atomic_write(self.tool.runtime / 'runtime.env', ''.join(k + '=' + deploy.env_quote(v) + '\n' for k, v in values.items()))
        self.assertEqual(self.tool.schema(), '53')
        self.assertEqual(self.tool.query('SELECT @@port'), str(self.port))
        # A working socket to a different port must not back up the wrong server
        # when application config still expects localhost's default TCP port.
        with self.assertRaises(deploy.DeploymentError):
            self.tool.begin()
        self.assertFalse(self.tool.maintenance.exists())

    def test_explicit_post_cutover_restore_preserves_rescue_snapshot(self):
        with contextlib.redirect_stdout(io.StringIO()):
            directory = self.tool.deploy(self.new)
            self.tool.reopen(directory.name)
            self.tool.query("INSERT INTO votes VALUES (3, 'post-cutover vote')")
            with self.assertRaises(deploy.DeploymentError):
                self.tool.restore(directory.name)
            self.assertEqual(self.tool.schema(), '60')
            self.tool.restore(directory.name, accept_data_loss=True)
        rescue = list(directory.glob('before-restore-*/database.sql.gz'))
        self.assertEqual(len(rescue), 1)
        with gzip.open(str(rescue[0]), 'rt') as snapshot:
            self.assertIn('post-cutover vote', snapshot.read())
        self.assertEqual(self.tool.schema(), '53')
        self.assertEqual(self.tool.query('SELECT COUNT(*) FROM votes'), '2')
        self.assertTrue(self.tool.maintenance.exists())


if __name__ == '__main__':
    unittest.main()
