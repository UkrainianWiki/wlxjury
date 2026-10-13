"""Failure-path tests; run in disposable Linux (no real services or DB required)."""
import contextlib
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
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
                             maintenance_urls=['http://jury.test/', 'https://jury.test/'])
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

    def run(self, args, **kwargs):
        self.events.append(tuple(args))
        if args[0] == 'systemctl':
            action, unit = args[1:3]
            if action == 'stop':
                self.running.discard(unit)
            elif action == 'start':
                self.running.add(unit)
                if unit == self.service:
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
        directories = list(self.tool.backups.iterdir())
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
        argv = ('java\0-Dconfig.file=' + str(self.tool.runtime / 'production.conf') + '\0').encode()
        with patch.object(Path, 'read_bytes', return_value=argv):
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


if __name__ == '__main__':
    unittest.main()
