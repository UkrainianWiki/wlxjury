import datetime
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name("agent-check.py")
spec = importlib.util.spec_from_file_location("agent_check", SCRIPT)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class AgentCheckTest(unittest.TestCase):
    def test_sbt_prefixed_benchmark_summary_is_retained(self):
        text = "SQL debug output\n" * 1000
        text += "[info] [pairs] page-juror threads=1 block=1 paired delta mean=1.20 ms\n"
        text += "[success] Total time: 40 s\n"
        summary = helper.summarize(text, 0)
        self.assertIn("paired delta mean=1.20 ms", summary)
        self.assertNotIn("SQL debug output", summary)

    def test_success_ignores_expected_application_errors(self):
        text = "[error] s.j.JobScheduler - simulated failure\n" * 1000
        text += "[info] Passed: Total 20, Failed 0, Errors 0, Passed 20\n"
        summary = helper.summarize(text, 0)
        self.assertIn("Passed: Total 20", summary)
        self.assertNotIn("simulated failure", summary)

    def test_failure_keeps_diagnostics_and_bounds_large_output(self):
        text = "noise\n" * 10000 + "[error] app/Foo.scala:12: type mismatch\nactual type\nexpected type\n"
        text += "[error] Compilation failed\n"
        summary = helper.summarize(text, 1)
        self.assertIn("Foo.scala:12", summary)
        self.assertIn("expected type", summary)
        self.assertIn("Compilation failed", summary)
        self.assertLess(len(summary), 6000)

    def test_nonzero_exit_and_raw_log_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "check.log"
            command = [sys.executable, str(SCRIPT), "--log", str(log), "--", sys.executable,
                       "-c", "print('raw marker'); raise SystemExit(7)"]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 7)
            self.assertIn("raw marker", log.read_text())
            self.assertIn("exit=7", result.stdout)
            again = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(again.returncode, 2)
            self.assertEqual(log.read_text().count("raw marker"), 1)


# The child starts a grandchild that writes a marker after GRANDCHILD_DELAY s unless it is killed,
# then sleeps far past the timeout.
GRANDCHILD_DELAY = 5
CHILD = r"""
import subprocess, sys, time
marker = sys.argv[1]
grandchild = "import time; time.sleep({delay}); open(r'" + marker + "', 'w').write('alive')"
subprocess.Popen([sys.executable, "-c", grandchild])
print("child started", flush=True)
time.sleep(60)
print("child finished", flush=True)
""".replace("{delay}", str(GRANDCHILD_DELAY))


def run_check(*args):
    return subprocess.run([sys.executable, str(SCRIPT)] + list(args), capture_output=True, text=True)


class TimeoutTest(unittest.TestCase):
    def test_timeout_stops_the_whole_tree_and_keeps_the_log(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "check.log"
            marker = Path(directory) / "grandchild-alive"
            started = time.monotonic()
            result = run_check("--timeout", "2", "--log", str(log), "--",
                               sys.executable, "-c", CHILD, str(marker))
            self.assertLess(time.monotonic() - started, 30)
            self.assertEqual(result.returncode, 124, result.stdout + result.stderr)
            self.assertIn("TIMED OUT after 2 s", result.stdout)
            self.assertIn("job-object" if os.name == "nt" else "process-group", result.stdout)
            self.assertIn("child started", log.read_text())
            self.assertNotIn("child finished", log.read_text())
            # The grandchild would write its marker GRANDCHILD_DELAY s after it started.
            time.sleep(GRANDCHILD_DELAY + 2)
            self.assertFalse(marker.exists(), "the grandchild survived the timeout")

    def test_zero_timeout_means_no_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "check.log"
            result = run_check("--timeout", "0", "--log", str(log), "--",
                               sys.executable, "-c", "import time; time.sleep(3); print('done')")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("done", log.read_text())
            self.assertNotIn("TIMED OUT", result.stdout)

    def test_default_timeout_is_twenty_minutes(self):
        self.assertEqual(helper.DEFAULT_TIMEOUT, 1200)


def start_check(*args):
    return subprocess.Popen([sys.executable, str(SCRIPT)] + list(args),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def wait_for(path, text, seconds=20):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if path.exists() and text in path.read_text(errors="replace"):
            return
        time.sleep(0.2)
    raise AssertionError("{!r} never appeared in {}".format(text, path))


class TreeTest(unittest.TestCase):
    """What stops the command's tree when this script is stopped, or can't make a job object."""

    @unittest.skipUnless(os.name == "nt", "a Windows job object")
    def test_a_hard_kill_of_the_script_kills_its_tree_and_leaves_a_lock_reported_as_gone(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "jvm.lock"
            log = Path(directory) / "check.log"
            marker = Path(directory) / "grandchild-alive"
            check = start_check("--lock", str(lock), "--log", str(log), "--",
                                sys.executable, "-c", CHILD, str(marker))
            wait_for(log, "child started")
            check.kill()  # TerminateProcess: no cleanup runs in the script
            check.communicate()
            time.sleep(GRANDCHILD_DELAY + 2)
            self.assertFalse(marker.exists(), "the tree outlived the script")
            self.assertTrue((lock / "owner").exists())  # stranded, as by any hard kill
            again = run_check("--lock", str(lock), "--lock-wait", "600", "--log", str(Path(directory) / "again.log"),
                              "--", sys.executable, "-c", "print('ran')")
            self.assertEqual(again.returncode, 75, again.stdout + again.stderr)
            self.assertIn("LOCK OWNER GONE", again.stderr)
            self.assertTrue((lock / "owner").exists(), "a lock was removed automatically")

    @unittest.skipUnless(os.name == "nt", "the taskkill fallback")
    def test_without_a_job_object_taskkill_stops_the_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "grandchild-alive"
            with mock.patch.object(helper, "WindowsJob", side_effect=OSError("no jobs here")), \
                    tempfile.TemporaryFile() as output:
                code, timed_out, method = helper.run_with_timeout(
                    [sys.executable, "-c", CHILD, str(marker)], output, 2, keep_awake=False)
            self.assertTrue(timed_out)
            self.assertEqual(method, "taskkill")
            time.sleep(GRANDCHILD_DELAY + 2)
            self.assertFalse(marker.exists(), "the grandchild survived taskkill /T")

    @unittest.skipIf(os.name == "nt", "POSIX signals")
    def test_sigterm_is_forwarded_to_the_group_and_the_lock_released(self):
        import signal
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "jvm.lock"
            log = Path(directory) / "check.log"
            marker = Path(directory) / "grandchild-alive"
            check = start_check("--lock", str(lock), "--log", str(log), "--",
                                sys.executable, "-c", CHILD, str(marker))
            wait_for(log, "child started")
            check.send_signal(signal.SIGTERM)
            _, err = check.communicate(timeout=30)
            self.assertEqual(check.returncode, 128 + signal.SIGTERM, err)
            self.assertIn("STOPPED by signal", err)
            self.assertFalse(lock.exists())
            time.sleep(GRANDCHILD_DELAY + 2)
            self.assertFalse(marker.exists(), "the group outlived the signal")


class KeepAwakeTest(unittest.TestCase):
    """The Windows keep-awake request, with ctypes mocked (on any platform)."""
    SET = helper.ES_CONTINUOUS | helper.ES_SYSTEM_REQUIRED
    RELEASE = helper.ES_CONTINUOUS

    def run_mocked(self, command, timeout, keep_awake=True):
        fake = mock.MagicMock()
        calls = fake.windll.kernel32.SetThreadExecutionState
        with mock.patch.object(helper, "IS_WINDOWS", True), mock.patch.dict(sys.modules, {"ctypes": fake}), \
                tempfile.TemporaryFile() as output:
            # no job object: ctypes is a mock here
            result = helper.run_with_timeout(command, output, timeout, keep_awake=keep_awake, use_job=False)
        return result, [c.args for c in calls.call_args_list]

    def test_request_is_held_during_the_run_and_released_after(self):
        (code, timed_out, _), calls = self.run_mocked([sys.executable, "-c", "pass"], 60)
        self.assertEqual((code, timed_out), (0, False))
        self.assertEqual(calls, [(self.SET,), (self.RELEASE,)])

    def test_request_is_released_on_timeout(self):
        (_, timed_out, _), calls = self.run_mocked([sys.executable, "-c", "import time; time.sleep(30)"], 1)
        self.assertTrue(timed_out)
        self.assertEqual(calls, [(self.SET,), (self.RELEASE,)])

    def test_request_is_released_on_interrupt(self):
        fake = mock.MagicMock()
        calls = fake.windll.kernel32.SetThreadExecutionState
        process = mock.MagicMock(pid=999999)
        process.wait.side_effect = KeyboardInterrupt
        with mock.patch.object(helper, "IS_WINDOWS", True), mock.patch.dict(sys.modules, {"ctypes": fake}), \
                mock.patch.object(helper.subprocess, "Popen", return_value=process), \
                mock.patch.object(helper, "kill_tree") as kill:
            with self.assertRaises(KeyboardInterrupt):
                helper.run_with_timeout(["anything"], None, 60, use_job=False)
        kill.assert_called_once_with(process, None)
        self.assertEqual([c.args for c in calls.call_args_list], [(self.SET,), (self.RELEASE,)])

    def test_allow_sleep_and_other_platforms_make_no_request(self):
        _, calls = self.run_mocked([sys.executable, "-c", "pass"], 60, keep_awake=False)
        self.assertEqual(calls, [])
        fake = mock.MagicMock()
        with mock.patch.object(helper, "IS_WINDOWS", False), mock.patch.dict(sys.modules, {"ctypes": fake}), \
                tempfile.TemporaryFile() as output:
            helper.run_with_timeout([sys.executable, "-c", "pass"], output, 60, use_job=False)
        fake.windll.kernel32.SetThreadExecutionState.assert_not_called()


class LockTest(unittest.TestCase):
    def test_lock_has_an_owner_during_the_run_and_is_removed_after(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "jvm.lock"
            log = Path(directory) / "check.log"
            show = "print(open(r'" + str(lock / "owner") + "').read())"
            result = run_check("--lock", str(lock), "--owner", "test-agent", "--log", str(log), "--",
                               sys.executable, "-c", show)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            owner = log.read_text()
            self.assertIn("owner=test-agent", owner)
            self.assertRegex(owner, r"pid=\d+")
            self.assertRegex(owner, r"started=\d{4}-\d\d-\d\dT")
            self.assertIn("timeout=1200", owner)
            self.assertIn("command=" + sys.executable + " -c", owner)
            self.assertFalse(lock.exists())

    def test_a_lock_whose_owner_process_is_gone_is_reported_not_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "jvm.lock"
            lock.mkdir()
            ended = subprocess.Popen([sys.executable, "-c", "pass"])
            ended.wait()
            (lock / "owner").write_text("owner=killed-agent\npid={}\nhost={}\nstarted={}\ntimeout=1200\n".format(
                ended.pid, helper.socket.gethostname(),
                datetime.datetime.now().astimezone().isoformat(timespec="seconds")))
            started = time.monotonic()
            result = run_check("--lock", str(lock), "--lock-wait", "600", "--log", str(Path(directory) / "c.log"),
                               "--", sys.executable, "-c", "print('ran')")
            self.assertLess(time.monotonic() - started, 30)
            self.assertEqual(result.returncode, 75, result.stdout + result.stderr)
            self.assertIn("LOCK OWNER GONE", result.stderr)
            self.assertIn("owner=killed-agent", result.stderr)
            self.assertTrue((lock / "owner").exists())

    def test_a_lock_with_a_long_timeout_is_stale_only_after_it_plus_five_minutes(self):
        self.assertEqual(helper.stale_after({}), 3600)
        self.assertEqual(helper.stale_after({"timeout": "0"}), 3600)
        self.assertEqual(helper.stale_after({"timeout": "1800"}), 3600)
        self.assertEqual(helper.stale_after({"timeout": "7200"}), 7500)
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "jvm.lock"
            lock.mkdir()
            ninety_minutes_ago = datetime.datetime.now().astimezone() - datetime.timedelta(minutes=90)
            (lock / "owner").write_text("owner=long-run\nstarted={}\ntimeout=7200\n".format(
                ninety_minutes_ago.isoformat(timespec="seconds")))
            result = run_check("--lock", str(lock), "--lock-wait", "1", "--log", str(Path(directory) / "c.log"),
                               "--", sys.executable, "-c", "print('ran')")
            self.assertEqual(result.returncode, 75, result.stdout + result.stderr)
            self.assertIn("LOCK BUSY", result.stderr)  # waited for: 90 min < 2 h + 5 min

    def test_a_failed_owner_write_removes_the_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "jvm.lock"
            with mock.patch.object(helper.Path, "write_text", side_effect=OSError("disk full")):
                with self.assertRaises(OSError):
                    helper.acquire_lock(lock, "test-agent", ["sbt"], 1)
            self.assertFalse(lock.exists())

    def test_a_missing_parent_is_a_clear_error(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "missing" / "jvm.lock"
            result = run_check("--lock", str(lock), "--log", str(Path(directory) / "c.log"),
                               "--", sys.executable, "-c", "print('ran')")
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
            self.assertIn("parent directory", result.stderr)
            self.assertNotIn("Traceback", result.stderr)

    def test_lock_is_released_after_a_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "jvm.lock"
            marker = Path(directory) / "grandchild-alive"
            result = run_check("--lock", str(lock), "--timeout", "1", "--log", str(Path(directory) / "c.log"),
                               "--", sys.executable, "-c", CHILD, str(marker))
            self.assertEqual(result.returncode, 124, result.stdout + result.stderr)
            self.assertFalse(lock.exists())

    def test_busy_lock_is_waited_for_then_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "jvm.lock"
            lock.mkdir()
            (lock / "owner").write_text(helper.lock_owner_text("other-agent", ["sbt", "test"]))
            result = run_check("--lock", str(lock), "--lock-wait", "1", "--log", str(Path(directory) / "c.log"),
                               "--", sys.executable, "-c", "print('ran')")
            self.assertEqual(result.returncode, 75, result.stdout + result.stderr)
            self.assertIn("LOCK BUSY", result.stderr)
            self.assertIn("owner=other-agent", result.stderr)
            self.assertTrue((lock / "owner").exists(), "someone else's lock was touched")

    def test_lock_older_than_an_hour_is_reported_without_waiting(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "jvm.lock"
            lock.mkdir()
            two_hours_ago = (datetime.datetime.now().astimezone() - datetime.timedelta(hours=2))
            (lock / "owner").write_text("owner=gone-agent\npid=1\nstarted={}\ncommand=sbt test\n".format(
                two_hours_ago.isoformat(timespec="seconds")))
            started = time.monotonic()
            result = run_check("--lock", str(lock), "--lock-wait", "600", "--log", str(Path(directory) / "c.log"),
                               "--", sys.executable, "-c", "print('ran')")
            self.assertLess(time.monotonic() - started, 30)
            self.assertEqual(result.returncode, 75, result.stdout + result.stderr)
            self.assertIn("STALE LOCK", result.stderr)
            self.assertIn("owner=gone-agent", result.stderr)
            self.assertTrue(lock.exists())


if __name__ == "__main__":
    unittest.main()
