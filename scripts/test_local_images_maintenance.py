"""Tests of scripts/local-images-maintenance.sh on a temporary cache directory.

Standard library only. Needs bash with GNU find and date (skipped otherwise).
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).with_name("local-images-maintenance.sh")
DAY = 86400


def has_gnu_tools():
    bash = shutil.which("bash")
    if not bash:
        return False
    probe = subprocess.run(
        [bash, "-c", "find . -maxdepth 0 -printf '' && date -d '-1 days' +%s"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return probe.returncode == 0


@unittest.skipUnless(has_gnu_tools(), "needs bash with GNU find and date")
class LocalImagesMaintenanceTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.cache = Path(self._tmp.name) / "cache"
        self.cache.mkdir()

    def make(self, relative, size, age_days):
        path = self.cache / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"x" * size)
        when = time.time() - age_days * DAY
        os.utime(path, (when, when))
        return path

    def run_script(self, *args, env_path=None):
        cmd = ["bash", "./" + SCRIPT.name] + list(args)
        env = dict(os.environ)
        env.pop("WLXJURY_THUMBS_LOCAL_PATH", None)
        if env_path is not None:
            env["WLXJURY_THUMBS_LOCAL_PATH"] = env_path
        return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              universal_newlines=True, env=env, cwd=str(SCRIPT.parent))

    def test_help_and_no_arguments(self):
        helped = self.run_script("help")
        self.assertEqual(helped.returncode, 0)
        self.assertIn("prune (--days N | --months N)", helped.stdout)
        self.assertEqual(self.run_script().returncode, 2)

    def test_report_csv_totals_by_modification_month(self):
        self.make("a/one.jpg", 100, 1)
        self.make("a/two.jpg", 50, 1)
        self.make("b/old.jpg", 10, 400)
        result = self.run_script("report", "--csv", "--path", self.cache.as_posix())
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.splitlines()
        self.assertEqual(lines[0], "month,files,bytes")
        self.assertEqual(lines[-1], "TOTAL,3,160")
        self.assertEqual(len(lines), 4)

    def test_report_reads_the_path_from_the_environment(self):
        self.make("x.jpg", 5, 1)
        result = self.run_script("report", "--csv", env_path=self.cache.as_posix())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("TOTAL,1,5", result.stdout)

    def test_dry_run_deletes_nothing(self):
        old = self.make("a/old.jpg", 10, 100)
        result = self.run_script("prune", "--days", "30", "--dry-run", "--path", self.cache.as_posix())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(old.exists())
        self.assertIn("would delete", result.stdout)
        self.assertIn("Would delete 1 file(s)", result.stdout)

    def test_prune_deletes_only_old_files_and_empty_directories(self):
        old = self.make("gone/deep/old.jpg", 10, 100)
        new = self.make("kept/new.jpg", 20, 1)
        result = self.run_script("prune", "--days", "30", "--quiet", "--path", self.cache.as_posix())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(old.exists())
        self.assertFalse((self.cache / "gone").exists())
        self.assertTrue(new.exists())
        self.assertTrue(self.cache.is_dir())
        self.assertIn("Deleted 1 file(s)", result.stdout)
        self.assertNotIn("old.jpg", result.stdout)

    def test_prune_by_months(self):
        old = self.make("old.jpg", 1, 100)
        new = self.make("new.jpg", 1, 5)
        result = self.run_script("prune", "--months", "2", "--path", self.cache.as_posix())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(old.exists())
        self.assertTrue(new.exists())

    def test_awkward_file_names(self):
        names = ["with space.jpg", "tab\there.jpg", "line\nbreak.jpg", "-dash.jpg", "quote'\".jpg"]
        old = [self.make("dir/" + name, 3, 100) for name in names]
        new = self.make("dir/new one.jpg", 3, 1)
        result = self.run_script("prune", "--days", "30", "--quiet", "--path", self.cache.as_posix())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Deleted 5 file(s)", result.stdout)
        for path in old:
            self.assertFalse(path.exists(), repr(path.name))
        self.assertTrue(new.exists())

    def test_symlinks_are_neither_followed_nor_deleted(self):
        outside = Path(self._tmp.name) / "outside"
        outside.mkdir()
        precious = outside / "precious.jpg"
        precious.write_bytes(b"data")
        old = time.time() - 100 * DAY
        os.utime(precious, (old, old))
        try:
            (self.cache / "link").symlink_to(outside, target_is_directory=True)
            (self.cache / "filelink.jpg").symlink_to(precious)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks not available")
        result = self.run_script("prune", "--days", "30", "--path", self.cache.as_posix())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(precious.exists())
        self.assertTrue((self.cache / "filelink.jpg").is_symlink())
        self.assertTrue((self.cache / "link").is_symlink())

    def test_usage_errors_exit_2_and_delete_nothing(self):
        old = self.make("old.jpg", 1, 100)
        cases = [
            ("prune", "--path", self.cache.as_posix()),
            ("prune", "--days", "0", "--path", self.cache.as_posix()),
            ("prune", "--days", "x", "--path", self.cache.as_posix()),
            ("prune", "--days", "1", "--months", "1", "--path", self.cache.as_posix()),
            ("prune", "--days"),
            ("prune", "--bogus"),
            ("report", "--days", "1", "--path", self.cache.as_posix()),
            ("prune", "--days", "1", "--csv", "--path", self.cache.as_posix()),
            ("prune", "--days", "1", "--path", (self.cache / "missing").as_posix()),
            ("prune", "--days", "1", "--path", "/"),
            ("nope",),
        ]
        for args in cases:
            result = self.run_script(*args)
            self.assertEqual(result.returncode, 2, "%s: %s" % (args, result.stderr))
            self.assertIn("Error:", result.stderr)
        self.assertTrue(old.exists())


if __name__ == "__main__":
    unittest.main()
