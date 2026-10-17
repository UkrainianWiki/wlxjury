"""Failure-path tests; run in disposable Linux (no real services or DB required)."""
import contextlib
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import signal
import ssl
import stat
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

spec = importlib.util.spec_from_file_location('deploy', Path(__file__).with_name('wlxjury-deploy.py'))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


def runtime_values():
    return {'WLXJURY_DB_HOST': 'localhost:3307', 'WLXJURY_DB': 'jury', 'WLXJURY_DB_USER': 'app',
            'WLXJURY_DB_PASSWORD': 'spaces $ # ` " \\ remain literal',
            'PLAY_HTTP_SECRET_KEY': 'retain-existing-signing-key-at-least-32-bytes'}


class Fixture(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.settings = dict(release_root=str(self.base / 'app'), runtime_dir=str(self.base / 'runtime'),
                             backup_root=str(self.base / 'backups'), database_dir=str(self.base / 'mysql'),
                             service='wlxjury.service', port=9000, ready_timeout_seconds=1,
                             minimum_free_bytes=1, quiesce_units=['backup.timer', 'backup.service'],
                             maintenance_urls=['http://jury.test/', 'https://jury.test/'],
                             systemd_dropin_dir=str(self.base / 'systemd' / 'wlxjury.service.d'),
                             session_end_timeout_seconds=1)
        for name in ('app', 'runtime', 'backups', 'mysql'):
            (self.base / name).mkdir()
        (self.base / 'app' / 'releases').mkdir()
        env = ''.join(k + '=' + deploy.env_quote(v) + '\n' for k, v in runtime_values().items())
        deploy.atomic_write(self.base / 'runtime' / 'runtime.env', env)
        for name in ('production.conf', 'logback.xml'):
            (self.base / 'runtime' / name).write_text('original ' + name)
        deploy.atomic_write(self.base / 'runtime' / 'db-admin.cnf', '[client]\nuser=root\n')
        self.tool = deploy.Deployment(self.settings)

    def archive(self, entries=None):
        path = self.base / 'release.zip'
        if entries is None:
            entries = {'wlxjury-0.13/bin/wlxjury': '#!/bin/sh\n',
                       'wlxjury-0.13/lib/app.jar': 'jar',
                       'wlxjury-0.13/conf/application.conf': 'unmodified defaults',
                       'wlxjury-0.13/wlxjury-build.json': json.dumps({'build': '0.13-v60', 'schemaVersion': '60'})}
        with zipfile.ZipFile(str(path), 'w') as bundle:
            for key, value in entries.items():
                bundle.writestr(key, value)
        return path

    def release(self, name, schema):
        directory = self.tool.releases / name
        (directory / 'bin').mkdir(parents=True)
        (directory / 'bin' / 'wlxjury').write_text('launcher')
        info = {'build': name, 'schemaVersion': schema}
        (directory / 'wlxjury-build.json').write_text(json.dumps(info))
        self.tool.record_release(directory, info, None)
        return directory


class ConfigurationTest(Fixture):
    def test_runtime_values_round_trip_without_expansion(self):
        self.assertEqual(deploy.read_env(self.tool.runtime / 'runtime.env'), runtime_values())

    def test_reject_shell_code_and_duplicate_values(self):
        path = self.tool.runtime / 'runtime.env'
        for invalid in ('export SECRET="abc"', 'SECRET=$(touch bad)', 'SECRET="one"\nSECRET="two"'):
            path.write_text(invalid)
            with self.assertRaises(deploy.DeploymentError):
                deploy.read_env(path)
        self.assertFalse((self.base / 'bad').exists())

    def test_export_legacy_keeps_signing_key_and_does_not_edit_input(self):
        original = ('db.default.url="jdbc:mariadb://localhost:3307/jury?useSSL=false"\n'
                    'db.default.username="app"\n'
                    'db.default.password=' + json.dumps(runtime_values()['WLXJURY_DB_PASSWORD']) + '\n'
                    'play.http.secret.key=' + json.dumps(runtime_values()['PLAY_HTTP_SECRET_KEY']) + '\n')
        path = self.base / 'legacy.conf'
        path.write_text(original)
        destination = self.base / 'exported'
        with contextlib.redirect_stdout(io.StringIO()) as output:
            deploy.init_runtime(path, destination)
        self.assertEqual(deploy.read_env(destination / 'runtime.env'), runtime_values())
        self.assertEqual(path.read_text(), original)
        self.assertNotIn(runtime_values()['WLXJURY_DB_PASSWORD'], output.getvalue())
        with self.assertRaises(deploy.DeploymentError):
            deploy.init_runtime(path, destination)

    def test_unsupported_hocon_does_not_publish_partial_credentials(self):
        legacy = self.base / 'legacy.conf'
        legacy.write_text('db.default.url=${SOME_ENV}\n')
        destination = self.base / 'unsupported'
        with self.assertRaises(deploy.DeploymentError):
            deploy.init_runtime(legacy, destination)
        self.assertFalse((destination / 'runtime.env').exists())


class ArtifactTest(Fixture):
    def test_single_archive_root_is_stripped_and_config_is_unmodified(self):
        archive = self.archive()
        result = self.tool.prepare(archive, deploy.digest(archive))
        self.assertTrue((result / 'bin' / 'wlxjury').is_file())
        self.assertFalse((result / 'wlxjury-0.13').exists())
        self.assertEqual((result / 'conf' / 'application.conf').read_text(), 'unmodified defaults')
        self.assertEqual(self.tool.identity(result)['schemaVersion'], '60')
        with self.assertRaises(deploy.DeploymentError):
            self.tool.prepare(archive, deploy.digest(archive))

    def test_wrong_checksum_rejects_before_extraction(self):
        archive = self.archive()
        with self.assertRaises(deploy.DeploymentError):
            self.tool.prepare(archive, '0' * 64)
        self.assertEqual(list(self.tool.releases.iterdir()), [])

    def test_traversal_and_symlink_archives_reject_before_extraction(self):
        for entry in ('wlxjury/../../outside', '/outside', 'wlxjury/..\\outside'):
            archive = self.archive({entry: 'bad'})
            with self.assertRaises(deploy.DeploymentError):
                self.tool.prepare(archive, deploy.digest(archive))
        archive = self.base / 'symlink.zip'
        with zipfile.ZipFile(str(archive), 'w') as bundle:
            entry = zipfile.ZipInfo('wlxjury/link')
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            bundle.writestr(entry, '/outside')
        with self.assertRaises(deploy.DeploymentError):
            self.tool.prepare(archive, deploy.digest(archive))
        self.assertEqual(list(self.tool.releases.iterdir()), [])

    def test_prepared_release_tampering_is_detected(self):
        release = self.release('old', '53')
        (release / 'bin' / 'wlxjury').write_text('changed')
        with self.assertRaises(deploy.DeploymentError):
            self.tool.identity(release)

    @unittest.skipUnless(os.name == 'posix', 'Linux file permissions')
    def test_release_is_service_readable_even_with_private_umask(self):
        old = os.umask(0o077)
        try:
            archive = self.archive()
            release = self.tool.prepare(archive, deploy.digest(archive))
            self.assertEqual(stat.S_IMODE(release.stat().st_mode), 0o755)
            self.assertEqual(stat.S_IMODE((release / 'bin').stat().st_mode), 0o755)
            self.assertEqual(stat.S_IMODE((release / 'bin' / 'wlxjury').stat().st_mode), 0o755)
        finally:
            os.umask(old)


class FakeDeployment(deploy.Deployment):
    def __init__(self, settings):
        super().__init__(settings)
        self.running = {self.service, 'backup.timer'}
        self.events = []
        self.live_schema = '53'
        self.live_history = '1\t53\t123\t1'
        self.fail_backup = False
        self.fail_readiness = False
        self.maintenance_ok = True
        self.public_ok = True
        self.defaults = 'latin1\tlatin1_swedish_ci'
        self.sessions = []
        self.other_sessions = 0
        self.size = 0
        self.held_at_start = []

    def run(self, args, **kwargs):
        self.events.append(tuple(args))
        if args[0] == 'systemctl':
            if args[1] == 'daemon-reload':
                return b''
            action, unit = args[1:3]
            if action == 'stop':
                self.running.discard(unit)
            elif action == 'start':
                self.running.add(unit)
                if unit == self.service:
                    self.held_at_start.append('Restart=no' in self.restart_hold.read_text()
                                              if self.restart_hold.exists() else False)
                    self.live_schema = self.identity(self.current.resolve())['schemaVersion']
                    self.live_history = '1\t' + self.live_schema + '\t123\t1'
            elif action == 'show':
                if 'MainPID' in args:
                    return b'1' if unit in self.running else b'0'
                return b'active' if unit in self.running else b'inactive'
        return b''

    def query(self, sql):
        self.events.append(('sql', sql))
        if sql == 'SELECT @@port':
            return '3307'
        if 'SCHEMATA' in sql:
            return self.defaults
        if 'SUM(DATA_LENGTH' in sql:
            return str(self.size)
        if sql.startswith('KILL '):
            self.sessions.remove(sql[5:])
            return ''
        if 'PROCESSLIST' in sql:
            if 'HEX(USER)' not in sql:
                return str(self.other_sessions)
            return str(len(self.sessions)) if 'COUNT(*)' in sql else '\n'.join(self.sessions)
        if 'COUNT(*)' in sql:
            return '0'
        if 'installed_rank, version' in sql:
            return self.live_history
        return self.live_schema

    def schema(self):
        return self.live_schema

    def check_maintenance(self):
        self.events.append(('maintenance-check',))
        deploy.require(self.maintenance_ok and self.maintenance.exists(), 'Maintenance coverage failed')

    def check_public(self):
        self.events.append(('public-check',))
        deploy.require(self.public_ok and not self.maintenance.exists(), 'Public routing failed')

    def wait_ready(self, release):
        self.events.append(('ready', str(release)))
        deploy.require(not self.fail_readiness, 'Readiness failed')

    def backup(self, directory):
        self.events.append(('backup',))
        deploy.require(self.service not in self.running, 'Backup ran with writers alive')
        deploy.require(not self.fail_backup, 'Backup failed')
        with gzip.open(str(directory / 'database.sql.gz'), 'wb') as output:
            output.write(b'consistent snapshot with Flyway history')
        return deploy.digest(directory / 'database.sql.gz')


@unittest.skipUnless(os.name == 'posix', 'Lifecycle tests use real Linux symlinks')
class FailurePathTest(Fixture):
    def setUp(self):
        super().setUp()
        self.tool = FakeDeployment(self.settings)
        self.old = self.release('old', '53')
        self.new = self.release('new', '60')
        self.tool.select(self.old)

    def record(self):
        directories = [path for path in self.tool.backups.iterdir() if path.is_dir()]
        self.assertEqual(len(directories), 1)
        return directories[0], deploy.read_json(directories[0] / 'deployment.json')

    def test_success_leaves_maintenance_until_explicit_smoke_test_acceptance(self):
        directory = self.tool.deploy(self.new)
        self.assertTrue(self.tool.maintenance.exists())
        self.assertNotIn('backup.timer', self.tool.running)
        self.assertEqual(self.tool.current.resolve(), self.new)
        stop = self.tool.events.index(('systemctl', 'stop', 'wlxjury.service'))
        backup = self.tool.events.index(('backup',))
        start = self.tool.events.index(('systemctl', 'start', 'wlxjury.service'))
        self.assertLess(stop, backup)
        self.assertLess(backup, start)
        self.tool.reopen(directory.name)
        self.assertFalse(self.tool.maintenance.exists())
        self.assertIn('backup.timer', self.tool.running)
        self.assertTrue(deploy.read_json(directory / 'deployment.json')['traffic_opened'])

    def test_backup_failure_cannot_switch_release_or_reopen_traffic(self):
        self.tool.fail_backup = True
        with self.assertRaises(deploy.DeploymentError):
            self.tool.deploy(self.new)
        directory, record = self.record()
        self.assertEqual(record['phase'], 'stopped')
        self.assertTrue(self.tool.maintenance.exists())
        self.assertEqual(self.tool.current.resolve(), self.old)
        self.assertNotIn(self.tool.service, self.tool.running)
        with self.assertRaises(deploy.DeploymentError):
            self.tool.reopen(directory.name)
        self.tool.resume_original(directory.name)
        self.tool.reopen(directory.name)
        self.assertFalse(self.tool.maintenance.exists())

    def test_maintenance_coverage_failure_leaves_original_service_running(self):
        self.tool.maintenance_ok = False
        with self.assertRaises(deploy.DeploymentError):
            self.tool.deploy(self.new)
        self.assertIn(self.tool.service, self.tool.running)
        self.assertEqual(self.tool.current.resolve(), self.old)
        self.assertNotIn(('backup',), self.tool.events)

    def test_abort_backs_out_a_maintenance_coverage_failure_without_a_restart(self):
        self.tool.maintenance_ok = False
        with self.assertRaises(deploy.DeploymentError):
            self.tool.deploy(self.new)
        directory, record = self.record()
        self.assertEqual(record['phase'], 'maintenance')
        # Coverage is still broken: resume-original is not the way out here.
        with self.assertRaises(deploy.DeploymentError):
            self.tool.resume_original(directory.name)
        self.tool.events.clear()
        self.tool.abort(directory.name)
        self.assertFalse(self.tool.maintenance.exists())
        self.assertNotIn(('systemctl', 'stop', 'wlxjury.service'), self.tool.events)
        self.assertNotIn(('systemctl', 'start', 'wlxjury.service'), self.tool.events)
        record = deploy.read_json(directory / 'deployment.json')
        self.assertEqual(record['phase'], 'complete')
        self.assertTrue(record['aborted'])
        self.assertTrue(record['traffic_opened'])
        with self.assertRaises(deploy.DeploymentError):
            self.tool.restore(directory.name, accept_data_loss=True)
        self.tool.maintenance_ok = True
        self.tool.deploy(self.new)  # The next attempt is not blocked by a leftover flag.

    def test_abort_starts_a_service_stopped_before_its_phase_was_saved(self):
        original = self.tool.save
        def interrupt(path, record, phase):
            if phase == 'stopped':
                raise KeyboardInterrupt()
            original(path, record, phase)
        with patch.object(self.tool, 'save', interrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.tool.deploy(self.new)
        directory, record = self.record()
        self.assertEqual(record['phase'], 'maintenance')
        self.assertNotIn(self.tool.service, self.tool.running)
        self.tool.abort(directory.name)
        self.assertIn(self.tool.service, self.tool.running)
        self.assertIn('backup.timer', self.tool.running)
        self.assertEqual(self.tool.current.resolve(), self.old)
        self.assertFalse(self.tool.restart_hold.exists())

    def test_abort_refuses_once_the_backup_phase_was_reached(self):
        self.tool.fail_backup = True
        with self.assertRaises(deploy.DeploymentError):
            self.tool.deploy(self.new)
        directory, _ = self.record()
        with self.assertRaises(deploy.DeploymentError):
            self.tool.abort(directory.name)
        self.assertTrue(self.tool.maintenance.exists())

    def test_automatic_restarts_are_held_until_traffic_reopens(self):
        directory = self.tool.deploy(self.new)
        self.assertEqual(self.tool.held_at_start, [True])
        self.assertIn(('systemctl', 'daemon-reload'), self.tool.events)
        self.assertTrue(self.tool.restart_hold.exists())
        self.assertEqual(stat.S_IMODE(self.tool.restart_hold.stat().st_mode), 0o644)
        self.tool.reopen(directory.name)
        self.assertFalse(self.tool.restart_hold.exists())

    def test_restarts_stay_held_after_a_failed_migration_and_through_restore(self):
        self.tool.fail_readiness = True
        with self.assertRaises(deploy.DeploymentError):
            self.tool.deploy(self.new)
        directory, _ = self.record()
        self.assertTrue(self.tool.restart_hold.exists())
        self.tool.fail_readiness = False
        with patch.object(deploy.subprocess, 'Popen', self.restore_process()):
            self.tool.restore(directory.name)
        self.assertEqual(self.tool.held_at_start, [True, True])
        self.assertTrue(self.tool.restart_hold.exists())
        self.tool.reopen(directory.name)
        self.assertFalse(self.tool.restart_hold.exists())

    def test_public_routing_failure_keeps_the_record_open_for_a_retry(self):
        directory = self.tool.deploy(self.new)
        self.tool.public_ok = False
        with self.assertRaises(deploy.DeploymentError):
            self.tool.reopen(directory.name)
        record = deploy.read_json(directory / 'deployment.json')
        self.assertEqual(record['phase'], 'opening')
        self.assertTrue(record['traffic_opened'])
        self.assertNotIn('backup.timer', self.tool.running)
        self.tool.public_ok = True
        self.tool.reopen(directory.name)
        self.assertEqual(deploy.read_json(directory / 'deployment.json')['phase'], 'complete')
        self.assertIn('backup.timer', self.tool.running)

    def test_failed_paused_unit_start_can_be_retried(self):
        directory = self.tool.deploy(self.new)
        original = self.tool.run
        def failing(args, **kwargs):
            if tuple(args[:3]) == ('systemctl', 'start', 'backup.timer'):
                raise deploy.DeploymentError('Command failed: systemctl (exit 1)')
            return original(args, **kwargs)
        with patch.object(self.tool, 'run', failing):
            with self.assertRaises(deploy.DeploymentError):
                self.tool.reopen(directory.name)
        self.assertEqual(deploy.read_json(directory / 'deployment.json')['phase'], 'opening')
        self.tool.reopen(directory.name)
        self.assertIn('backup.timer', self.tool.running)

    def test_an_older_release_is_refused_before_maintenance(self):
        older = self.release('older', '50')
        with self.assertRaises(deploy.DeploymentError) as error:
            self.tool.deploy(older)
        self.assertIn('older release', str(error.exception))
        self.assertFalse(self.tool.maintenance.exists())
        self.assertEqual(list(self.tool.backups.iterdir()), [])
        self.assertFalse(any(event[:2] == ('systemctl', 'stop') for event in self.tool.events))

    def test_same_schema_redeployment_is_allowed(self):
        same = self.release('same', '53')
        self.tool.deploy(same)
        self.assertEqual(self.tool.current.resolve(), same)

    def test_restore_recreates_the_original_character_set_and_ends_leftover_sessions(self):
        directory = self.tool.deploy(self.new)
        self.assertEqual(deploy.read_json(directory / 'deployment.json')['collation'], 'latin1_swedish_ci')
        self.tool.sessions = ['17']  # E.g. an ALTER that outlived the stopped JVM.
        with patch.object(deploy.subprocess, 'Popen', self.restore_process()):
            self.tool.restore(directory.name)
        sql = [event[1] for event in self.tool.events if event[0] == 'sql']
        kill = sql.index('KILL 17')
        drop = next(i for i, statement in enumerate(sql) if 'DROP DATABASE' in statement)
        self.assertLess(kill, drop)
        self.assertIn('CHARACTER SET latin1 COLLATE latin1_swedish_ci', sql[drop])
        user = runtime_values()['WLXJURY_DB_USER'].encode().hex().upper()
        self.assertTrue(any("HEX(USER) = '" + user + "'" in statement for statement in sql))

    def test_restore_refuses_without_dropping_while_others_use_the_database(self):
        directory = self.tool.deploy(self.new)
        self.tool.other_sessions = 1  # An operator's console in the database.
        with self.assertRaises(deploy.DeploymentError):
            self.tool.restore(directory.name)
        self.assertFalse(any('DROP DATABASE' in e[-1] for e in self.tool.events))
        self.assertTrue(self.tool.maintenance.exists())

    def test_restore_gives_up_on_sessions_that_do_not_end(self):
        directory = self.tool.deploy(self.new)
        self.tool.sessions = ['17']
        original = self.tool.query
        def unkillable(sql):
            if sql.startswith('KILL '):
                self.tool.events.append(('sql', sql))
                return ''
            return original(sql)
        with patch.object(self.tool, 'query', unkillable), patch.object(deploy.time, 'sleep'):
            with self.assertRaises(deploy.DeploymentError) as error:
                self.tool.restore(directory.name)
        self.assertIn('still ending', str(error.exception))
        self.assertFalse(any('DROP DATABASE' in e[-1] for e in self.tool.events))

    def test_records_without_a_character_set_keep_the_previous_default(self):
        directory = self.tool.deploy(self.new)
        record = deploy.read_json(directory / 'deployment.json')
        del record['charset'], record['collation']
        deploy.write_json(directory / 'deployment.json', record)
        with patch.object(deploy.subprocess, 'Popen', self.restore_process()):
            self.tool.restore(directory.name)
        self.assertTrue(any('COLLATE utf8mb4_unicode_ci' in e[-1] for e in self.tool.events if e[0] == 'sql'))

    def test_rescue_snapshot_space_is_checked_before_anything_stops(self):
        directory = self.tool.deploy(self.new)
        self.tool.reopen(directory.name)
        self.tool.size = 1 << 60
        self.tool.events.clear()
        with self.assertRaises(deploy.DeploymentError) as error:
            self.tool.restore(directory.name, accept_data_loss=True)
        self.assertIn('Capacity', str(error.exception))
        self.assertFalse(self.tool.maintenance.exists())
        self.assertFalse(any(event[:2] == ('systemctl', 'stop') for event in self.tool.events))

    def test_rescue_snapshot_refuses_while_other_database_sessions_are_active(self):
        directory = self.tool.deploy(self.new)
        self.tool.reopen(directory.name)
        self.tool.other_sessions = 1
        self.tool.events.clear()
        with self.assertRaises(deploy.DeploymentError) as error:
            self.tool.restore(directory.name, accept_data_loss=True)
        self.assertIn('Other sessions', str(error.exception))
        self.assertIn('rerun restore', str(error.exception))
        self.assertFalse(any(event[0] == 'backup' for event in self.tool.events))
        self.assertFalse(any('DROP DATABASE' in e[-1] for e in self.tool.events))
        self.assertEqual(list(directory.glob('before-restore-*')), [])
        self.assertTrue(self.tool.maintenance.exists())
        # The way out the message names: once those sessions end, restore completes.
        self.tool.other_sessions = 0
        with patch.object(deploy.subprocess, 'Popen', self.restore_process()):
            self.tool.restore(directory.name, accept_data_loss=True)
        self.assertEqual(deploy.read_json(directory / 'deployment.json')['phase'], 'restored')
        self.assertEqual(len(list(directory.glob('before-restore-*'))), 1)

    def test_release_files_are_hashed_once_per_operation(self):
        hashed = []
        original = deploy.digest
        def counting(path):
            hashed.append(Path(path))
            return original(path)
        self.tool.verified.clear()
        with patch.object(deploy, 'digest', counting):
            self.tool.deploy(self.new)
        self.assertEqual(hashed.count(self.new / 'bin' / 'wlxjury'), 1)
        self.assertEqual(hashed.count(self.old / 'bin' / 'wlxjury'), 1)

    def live_process(self, argv, environment=('PATH=/usr/bin',)):
        process = self.base / 'proc' / '1'
        process.mkdir(parents=True, exist_ok=True)
        (process / 'cmdline').write_bytes(('\0'.join(argv) + '\0').encode())
        (process / 'environ').write_bytes(('\0'.join(environment) + '\0').encode())
        self.tool.proc = self.base / 'proc'

    def accepted_rollout(self):
        directory = self.tool.deploy(self.new)
        self.tool.reopen(directory.name)
        source = self.base / 'package' / 'conf' / 'application.conf'
        source.parent.mkdir(parents=True)
        values = runtime_values()
        source.write_text('db.default.username="app"\n'
                          'db.default.password=' + json.dumps(values['WLXJURY_DB_PASSWORD']) + '\n'
                          'play.http.secret.key=' + json.dumps(values['PLAY_HTTP_SECRET_KEY']) + '\n')
        return directory, source

    def test_scrub_requires_the_effective_config_to_be_external(self):
        directory, source = self.accepted_rollout()
        external = '-Dconfig.file=' + str(self.tool.runtime / 'production.conf')
        text = source.read_text()
        for argv, environment in (
                (['java', external, '-Dconfig.file=/usr/share/wlxjury/conf/application.conf'], ()),
                (['java', external, '-Dconfig.resource=application.conf'], ()),
                (['java', '-Dconfig.file=/usr/share/wlxjury/conf/application.conf'], ()),
                (['java', external], ('_JAVA_OPTIONS=-Dconfig.file=/tmp/other.conf',))):
            self.live_process(argv, environment)
            with self.assertRaises(deploy.DeploymentError):
                self.tool.scrub_legacy(directory.name, source)
            self.assertEqual(source.read_text(), text)
        # The last -Dconfig.file wins, so an earlier package default is harmless.
        self.live_process(['java', '-Dconfig.file=/usr/share/wlxjury/conf/application.conf', external],
                          ('JAVA_OPTS=-Xmx1g',))
        self.tool.scrub_legacy(directory.name, source)
        self.assertIn('${WLXJURY_DB_PASSWORD}', source.read_text())

    def restore_process(test):
        class RestoreProcess:
            def __init__(self, *args, **kwargs):
                self.stdin = io.BytesIO()
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def wait(self):
                test.tool.live_schema = '53'
                test.tool.live_history = '1\t53\t123\t1'
                return 0
        return RestoreProcess

    def test_migration_startup_failure_requires_restore_and_keeps_traffic_blocked(self):
        self.tool.fail_readiness = True
        with self.assertRaises(deploy.DeploymentError):
            self.tool.deploy(self.new)
        directory, record = self.record()
        self.assertEqual(record['phase'], 'migration-started')
        self.assertTrue(self.tool.maintenance.exists())
        self.assertEqual(self.tool.live_schema, '60')
        with self.assertRaises(deploy.DeploymentError):
            self.tool.resume_original(directory.name)
        with self.assertRaises(deploy.DeploymentError):
            self.tool.reopen(directory.name)

    def test_tampered_backup_fails_before_database_drop(self):
        directory = self.tool.deploy(self.new)
        (directory / 'database.sql.gz').write_bytes(b'tampered')
        with self.assertRaises(deploy.DeploymentError):
            self.tool.restore(directory.name)
        self.assertFalse(any('DROP DATABASE' in e[-1] for e in self.tool.events))

    def test_post_cutover_restore_requires_data_loss_decision(self):
        directory = self.tool.deploy(self.new)
        self.tool.reopen(directory.name)
        before = list(self.tool.events)
        with self.assertRaises(deploy.DeploymentError):
            self.tool.restore(directory.name)
        self.assertEqual(self.tool.events, before)
        self.assertFalse(self.tool.maintenance.exists())

    def test_interruption_during_open_marks_possible_post_cutover_writes(self):
        directory = self.tool.deploy(self.new)
        original = self.tool.save
        def interrupt(path, record, phase):
            original(path, record, phase)
            if phase == 'opening':
                raise KeyboardInterrupt()
        with patch.object(self.tool, 'save', interrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.tool.reopen(directory.name)
        self.assertTrue(self.tool.maintenance.exists())
        self.assertTrue(deploy.read_json(directory / 'deployment.json')['traffic_opened'])
        with self.assertRaises(deploy.DeploymentError):
            self.tool.restore(directory.name)

    def test_reopen_can_finish_after_flag_removed_but_completion_record_failed(self):
        directory = self.tool.deploy(self.new)
        original = self.tool.save
        def interrupt(path, record, phase):
            if phase == 'complete':
                raise KeyboardInterrupt()
            original(path, record, phase)
        with patch.object(self.tool, 'save', interrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.tool.reopen(directory.name)
        self.assertFalse(self.tool.maintenance.exists())
        self.assertEqual(deploy.read_json(directory / 'deployment.json')['phase'], 'opening')
        self.tool.reopen(directory.name)
        self.assertEqual(deploy.read_json(directory / 'deployment.json')['phase'], 'complete')

    def test_record_and_database_identity_must_match(self):
        directory = self.tool.deploy(self.new)
        env = runtime_values()
        env['WLXJURY_DB'] = 'another_database'
        deploy.atomic_write(self.tool.runtime / 'runtime.env', ''.join(k + '=' + deploy.env_quote(v) + '\n' for k, v in env.items()))
        with self.assertRaises(deploy.DeploymentError):
            self.tool.restore(directory.name)
        self.assertFalse(any('DROP DATABASE' in e[-1] for e in self.tool.events))

    def test_restore_before_reopening_restores_snapshot_config_and_old_release(self):
        directory = self.tool.deploy(self.new)
        self.tool.runtime.joinpath('production.conf').write_text('changed config')
        class RestoreProcess:
            def __init__(self, *args, **kwargs):
                self.stdin = io.BytesIO()
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def wait(process):
                self.tool.live_schema = '53'
                self.tool.live_history = '1\t53\t123\t1'
                return 0
        with patch.object(deploy.subprocess, 'Popen', RestoreProcess):
            self.tool.restore(directory.name)
        self.assertEqual(self.tool.current.resolve(), self.old)
        self.assertEqual((self.tool.runtime / 'production.conf').read_text(), 'original production.conf')
        self.assertTrue(self.tool.maintenance.exists())
        self.assertEqual(deploy.read_json(directory / 'deployment.json')['phase'], 'restored')
        self.tool.reopen(directory.name)
        self.assertFalse(self.tool.maintenance.exists())

    def test_lock_excludes_concurrent_deployment(self):
        with self.tool.locked():
            with self.assertRaises(deploy.DeploymentError):
                with self.tool.locked():
                    self.fail('second operation obtained deployment lock')

    def test_legacy_scrub_requires_accepted_external_config_and_retains_private_original(self):
        values = runtime_values()
        source = self.base / 'package' / 'conf' / 'application.conf'
        source.parent.mkdir(parents=True)
        text = ('db.default.url="jdbc:mariadb://localhost:3307/jury"\n'
                'db.default.username="app"\n'
                'db.default.password=' + json.dumps(values['WLXJURY_DB_PASSWORD']) + '\n'
                'play.http.secret.key=' + json.dumps(values['PLAY_HTTP_SECRET_KEY']) + '\n')
        source.write_text(text)
        directory = self.tool.deploy(self.new)
        with self.assertRaises(deploy.DeploymentError):
            self.tool.scrub_legacy(directory.name, source)
        self.assertEqual(source.read_text(), text)
        self.tool.reopen(directory.name)
        self.live_process(['java', '-Dconfig.file=' + str(self.tool.runtime / 'production.conf')])
        self.tool.scrub_legacy(directory.name, source)
        self.assertNotIn(json.dumps(values['WLXJURY_DB_PASSWORD']), source.read_text())
        self.assertIn('${WLXJURY_DB_PASSWORD}', source.read_text())
        retained = directory / 'config' / 'legacy-secret-config.conf'
        self.assertEqual(retained.read_text(), text)
        self.assertEqual(stat.S_IMODE(retained.stat().st_mode), 0o600)


@unittest.skipUnless(os.name == 'posix', 'Legacy adoption selects a Linux symlink')
class LegacyAdoptionTest(Fixture):
    def test_adopt_sanitizes_snapshot_without_modifying_original_or_restarting(self):
        self.tool = FakeDeployment(self.settings)
        source = self.base / 'package'
        for name in ('bin', 'lib', 'conf'):
            (source / name).mkdir(parents=True)
        (source / 'bin' / 'wlxjury').write_text('launcher')
        with zipfile.ZipFile(str(source / 'lib' / 'app.jar'), 'w') as archive:
            archive.writestr('db/migration/default/V53__existing.sql', 'SELECT 1;')
        text = ('db.default.password="legacy-private"\nplay.http.secret.key="old-private-key"\n'
                'db.default.username="olduser"\ndb.default.url="jdbc:mariadb://localhost/jury"\n')
        (source / 'conf' / 'application.conf').write_text(text)
        (source / 'conf' / 'application.conf.dpkg-dist').write_text('another-private-copy')
        self.tool.adopt_legacy(source, 'legacy-0.12-v53')
        target = self.tool.current.resolve()
        self.assertEqual((source / 'conf' / 'application.conf').read_text(), text)
        self.assertNotIn('legacy-private', (target / 'conf' / 'application.conf').read_text())
        self.assertFalse((target / 'conf' / 'application.conf.dpkg-dist').exists())
        self.assertTrue(self.tool.identity(target)['legacy'])
        self.assertEqual(self.tool.identity(target)['schemaVersion'], '53')
        self.assertFalse(any(event[0] == 'systemctl' for event in self.tool.events))

    LEGACY = ('db.default.password="legacy-private"\nplay.http.secret.key="old-private-key"\n'
              'db.default.username="olduser"\ndb.default.url="jdbc:mariadb://localhost/jury"\n')

    def legacy_package(self, text, extra=None):
        self.tool = FakeDeployment(self.settings)
        source = self.base / 'package'
        if source.exists():
            shutil.rmtree(str(source))
        for name in ('bin', 'lib', 'conf'):
            (source / name).mkdir(parents=True)
        (source / 'bin' / 'wlxjury').write_text('launcher')
        (source / 'conf' / 'application.conf').write_text(text)
        for name, contents in (extra or {}).items():
            (source / 'conf' / name).write_text(contents)
        return source

    def test_adoption_refuses_credentials_it_cannot_externalize_and_removes_the_copy(self):
        for text, extra in ((self.LEGACY + 'play.mailer.password = "mail-private"\n', {}),
                            (self.LEGACY + 'db {\n  default {\n    password: "nested-private"\n  }\n}\n', {}),
                            (self.LEGACY + 'play.http.secret {\n  key = "object-private"\n}\n', {}),
                            (self.LEGACY + 'mail {\n  password = "unclosed-private"\n', {}),
                            (self.LEGACY, {'application.ini': '-Dsmtp.password=ini-private\n'}),
                            (self.LEGACY, {'mail.properties': 'mail.password properties-private\n'})):
            source = self.legacy_package(text, extra)
            with self.assertRaises(deploy.DeploymentError) as error:
                self.tool.adopt_legacy(source, 'legacy-0.12-v53')
            self.assertNotIn('private', str(error.exception))
            self.assertIn('literal credentials', str(error.exception))
            self.assertEqual(list(self.tool.releases.iterdir()), [])
            self.assertFalse(self.tool.current.is_symlink())
            self.assertEqual((source / 'conf' / 'application.conf').read_text(), text)

    def test_adoption_drops_commented_credentials_and_keeps_other_comments(self):
        source = self.legacy_package('# db.default.password = "commented-private"\n'
                                     '# The secret key is used to secure cryptographics functions.\n' + self.LEGACY)
        self.tool.adopt_legacy(source, 'legacy-0.12-v53')
        copied = (self.tool.current.resolve() / 'conf' / 'application.conf').read_text()
        self.assertNotIn('private', copied)
        self.assertIn('# The secret key is used', copied)
        self.assertEqual(stat.S_IMODE(self.tool.current.resolve().stat().st_mode), 0o755)

    def test_adoption_drops_the_commented_line_itself_when_strings_hold_other_line_breaks(self):
        # U+2028 is a line break to str.splitlines but not to HOCON.
        source = self.legacy_package(self.LEGACY + 'note = "a' + chr(0x2028) + 'b"\n'
                                     '# play.mailer.password = "commented-private"\nkeep = 1\n')
        self.tool.adopt_legacy(source, 'legacy-0.12-v53')
        copied = (self.tool.current.resolve() / 'conf' / 'application.conf').read_text(encoding='utf-8')
        self.assertNotIn('private', copied)
        self.assertIn('note = "a' + chr(0x2028) + 'b"\nkeep = 1\n', copied)

    def test_adoption_accepts_the_shipped_application_conf(self):
        # Its jury greeting holds "password: {{Password}}" inside a """ string.
        shipped = Path(__file__).resolve().parents[2] / 'conf'
        # Older packages also shipped Apache files in conf/; they are not HOCON.
        source = self.legacy_package((shipped / 'application.conf').read_text(encoding='utf-8'),
                                     {'jury-images.conf': (shipped / 'apache' / 'jury-images.conf').read_text()})
        self.tool.adopt_legacy(source, 'legacy-0.12-v53')
        copied = (self.tool.current.resolve() / 'conf' / 'application.conf').read_text(encoding='utf-8')
        self.assertIn('and password: {{Password}}', copied)
        self.assertIn('play.http.secret.key = ${PLAY_HTTP_SECRET_KEY}', copied)
        self.assertNotIn('changeme', copied)

    def test_adoption_refuses_quoted_credentials_before_publishing_the_copy(self):
        setting = 'play.mailer { "password": "quoted-private" }'
        source = self.legacy_package(self.LEGACY + setting + '\n')
        with self.assertRaises(deploy.DeploymentError) as error:
            self.tool.adopt_legacy(source, 'legacy-0.12-v53')
        self.assertIn('literal credentials', str(error.exception))
        self.assertNotIn('quoted-private', str(error.exception))
        self.assertEqual(list(self.tool.releases.iterdir()), [])
        self.assertFalse(self.tool.current.is_symlink())
        self.assertIn(setting, (source / 'conf' / 'application.conf').read_text())

    def test_pending_migration_in_installed_jar_blocks_adoption_before_selection(self):
        self.tool = FakeDeployment(self.settings)
        source = self.base / 'package'
        for name in ('bin', 'lib', 'conf'):
            (source / name).mkdir(parents=True)
        (source / 'bin' / 'wlxjury').write_text('launcher')
        with zipfile.ZipFile(str(source / 'lib' / 'app.jar'), 'w') as archive:
            archive.writestr('db/migration/default/V55_1__conversion.sql', 'ALTER TABLE votes ENGINE=InnoDB;')
        with self.assertRaises(deploy.DeploymentError):
            self.tool.adopt_legacy(source, 'legacy-0.12-v53')
        self.assertFalse(self.tool.current.is_symlink())
        self.assertEqual(list(self.tool.releases.iterdir()), [])


class HttpProbeTest(Fixture):
    def test_tls_failure_is_named_in_the_maintenance_error(self):
        failure = urllib.error.URLError(ssl.SSLCertVerificationError('certificate has expired'))
        with patch.object(self.tool, 'run', return_value=b''), \
                patch.object(self.tool.http, 'open', side_effect=failure):
            with self.assertRaises(deploy.DeploymentError) as error:
                self.tool.check_maintenance()
        self.assertIn('TLS certificate verification failed', str(error.exception))

    def test_dns_failure_is_named(self):
        failure = urllib.error.URLError(deploy.socket.gaierror(-2, 'Name or service not known'))
        with patch.object(self.tool, 'run', return_value=b''), \
                patch.object(self.tool.http, 'open', side_effect=failure):
            with self.assertRaises(deploy.DeploymentError) as error:
                self.tool.check_maintenance()
        self.assertIn('DNS lookup failed', str(error.exception))

    def test_maintenance_page_is_required_not_just_any_503(self):
        page = deploy.MAINTENANCE_PAGE
        def respond(body):
            def open_url(*args, **kwargs):
                raise urllib.error.HTTPError('http://jury.test/', 503, 'Unavailable', {}, io.BytesIO(body))
            return open_url
        with patch.object(self.tool, 'run', return_value=b''):
            # Apache's own 503 for a stopped app in a vhost without the snippet.
            with patch.object(self.tool.http, 'open', side_effect=respond(b'<h1>Service Unavailable</h1>')):
                with self.assertRaises(deploy.DeploymentError) as error:
                    self.tool.check_maintenance()
            self.assertIn('not the maintenance page', str(error.exception))
            with patch.object(self.tool.http, 'open', side_effect=respond(b'The jury tool is ' + page + b'.')):
                self.tool.check_maintenance()

    def test_the_tool_and_the_apache_snippet_agree_on_the_page(self):
        snippet = Path(__file__).resolve().parents[2] / 'conf' / 'apache' / 'wlxjury-maintenance.conf'
        if not snippet.exists():
            self.skipTest('Run from a checkout')
        page = [line for line in snippet.read_text().splitlines() if line.startswith('ErrorDocument 503')]
        self.assertEqual(len(page), 1)
        self.assertIn(deploy.MAINTENANCE_PAGE.decode(), page[0])

    def test_wrong_status_is_reported(self):
        response = urllib.error.HTTPError('http://jury.test/', 301, 'Moved', {}, io.BytesIO(b''))
        with patch.object(self.tool, 'run', return_value=b''), \
                patch.object(self.tool.http, 'open', side_effect=response):
            with self.assertRaises(deploy.DeploymentError) as error:
                self.tool.check_maintenance()
        self.assertIn('HTTP 301', str(error.exception))

    def test_public_check_accepts_redirects_and_rejects_maintenance(self):
        redirect = urllib.error.HTTPError('http://jury.test/', 303, 'See Other', {}, io.BytesIO(b''))
        with patch.object(self.tool.http, 'open', side_effect=redirect):
            self.tool.check_public()
        unavailable = urllib.error.HTTPError('http://jury.test/', 503, 'Unavailable', {}, io.BytesIO(b''))
        with patch.object(self.tool.http, 'open', side_effect=unavailable):
            with self.assertRaises(deploy.DeploymentError) as error:
                self.tool.check_public()
        self.assertIn('HTTP 503', str(error.exception))


class OperatorSessionTest(Fixture):
    def test_progress_survives_a_closed_terminal_and_is_logged(self):
        class ClosedTerminal(io.StringIO):
            def write(self, text):
                raise OSError(5, 'Input/output error')
        with patch.object(deploy.sys, 'stdout', ClosedTerminal()):
            self.tool.say('Deployment x: backed-up')
        log = self.tool.backups / 'operations.log'
        self.assertIn('Deployment x: backed-up', log.read_text())
        if os.name == 'posix':
            self.assertEqual(stat.S_IMODE(log.stat().st_mode), 0o600)

    @unittest.skipUnless(os.name == 'posix', 'POSIX signals')
    def test_hangup_is_ignored_and_termination_exits(self):
        previous = {number: signal.getsignal(number) for number in (signal.SIGHUP, signal.SIGTERM)}
        try:
            deploy.install_signal_handlers()
            self.assertIs(signal.getsignal(signal.SIGHUP), signal.SIG_IGN)
            with self.assertRaises(SystemExit) as code:
                signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
            self.assertEqual(code.exception.code, 143)
        finally:
            for number, handler in previous.items():
                signal.signal(number, handler)


class CredentialScanTest(unittest.TestCase):
    @staticmethod
    def hocon(text):
        return deploy.config_credentials(text + '\n', '.conf')

    def test_literal_hocon_credentials_are_found_in_every_form(self):
        escaped = '"pass' + chr(92) + 'u0077ord": "escaped"'
        for text in ('play.mailer.password = "mail"', 'db {\n  default {\n    password: "nested"\n  }\n}',
                     '  smtp.passwd=abc', 'application.secret="old"', 'play.mailer { "password": "quoted" }',
                     '"play.http.secret.key" = "quoted"', '"play"."http"."secret"."key" = "quoted"', escaped,
                     'play.http.secret { key = "same-line" }', 'play.http.secret {\n  key = "next-line"\n}',
                     '{ play { http { secret { key = "deep" } } } }', '"pass"word = "joined"',
                     'pass"word" = "joined"', 'password += "appended"', 'passwords = ["listed"]',
                     'password = ${?X}"suffix"', 'password = """triple"""',
                     'db.default.url="jdbc:mariadb://h/db?user=a&password=b"'):
            found, _ = self.hocon(text)
            self.assertTrue(found, text)
            self.assertNotIn('unreadable HOCON', [key for _, key in found], text)

    def test_hocon_references_strings_and_prose_are_not_credentials(self):
        for text in ('play.http.secret.key = ${PLAY_HTTP_SECRET_KEY}', 'commons.password = ${?COMMONS_PASSWORD}',
                     '"play.http.secret.key" = ${PLAY_HTTP_SECRET_KEY}', '"commons"."password" = ${?COMMONS_PASSWORD}',
                     '"password": ""', 'db.default.password = null', 'play.http.secret { key = ${?KEY} }',
                     'greeting = """Hello,\nwith login: {{Login}} and password: {{Password}}\n"""',
                     'hint = "password: {{Password}}"', 'include "secrets.conf"',
                     '# The secret key is used to secure cryptographics functions.',
                     'db.default.username = "app"', 'play.filters.csrf.token.name = "csrfToken"'):
            self.assertEqual(self.hocon(text), ([], set()), text)

    def test_strings_with_raw_tabs_are_still_read_as_hocon(self):
        self.assertEqual(self.hocon('greeting = "login:\t{{Login}}, password:\t{{Password}}"'), ([], set()))

    def test_commented_hocon_credentials_are_dropped_and_trailing_ones_refused(self):
        found, dropped = self.hocon('# db.default.password = "old"\n// play.mailer { "password": "old" }\n'
                                    'db.default.password = ${X} # was password = "old"')
        self.assertEqual(dropped, {1, 2})
        self.assertEqual(found, [(3, 'password in a comment')])

    def test_conf_files_that_are_not_hocon_are_scanned_line_by_line_without_crashing(self):
        bad_escape = chr(92) + 'q'
        for text, found in (('password = "unterminated', [(1, 'password')]),
                            ('db {\n  password = "unclosed"', [(2, 'password')]),
                            ('ok = 1\npattern = "' + chr(92) + 'd+": x', []), ('a = ]', []),
                            ('# see "C:' + bad_escape + '": here', []),
                            ('RewriteEngine On\nErrorDocument 503 "The jury tool is undergoing maintenance."', [])):
            self.assertEqual(self.hocon(text)[0], found, text)
        self.assertEqual(self.hocon('[Service]\n# Environment=SMTP_PASSWORD=old\nEnvironment=SMTP_PASSWORD=abc'),
                         ([(3, 'SMTP_PASSWORD')], {2}))

    def test_shipped_apache_and_systemd_conf_files_have_no_findings(self):
        conf = Path(__file__).resolve().parents[2] / 'conf'
        for path in sorted(conf.glob('apache/*.conf')) + sorted(conf.glob('systemd/**/*.conf')):
            self.assertEqual(deploy.config_credentials(path.read_text(encoding='utf-8'), '.conf'), ([], set()),
                             path.name)

    def test_line_formats_are_scanned_line_by_line(self):
        self.assertEqual(deploy.config_credentials('-Dsmtp.password=abc\n-J-Xmx1g\n', '.ini'),
                         ([(1, '-Dsmtp.password')], set()))
        self.assertEqual(deploy.config_credentials('# mail.password=old\nmail.password abc\n', '.properties'),
                         ([(2, 'mail.password')], {1}))
        self.assertEqual(deploy.config_credentials('[client]\npassword = abc\n', '.cnf'), ([(2, 'password')], set()))
        self.assertEqual(deploy.config_credentials('WLXJURY_DB_PASSWORD=${X}\n', '.env'), ([], set()))


if __name__ == '__main__':
    unittest.main()
