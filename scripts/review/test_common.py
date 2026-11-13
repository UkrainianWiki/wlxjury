"""Tests for common.py: which gh errors a read retries, that writes never retry, the state files
(written whole or not at all) and the loop's lock; and for loop_task.py's quick exits and task XML.
The loop's behaviour is in `coderabbit_loop.py --self-test` (a simulated CodeRabbit).

Standard library only (Python 3.8+), no network: subprocess.run and time.sleep are patched.
Run from scripts/review: python -B -m unittest -v test_common
"""
import pathlib
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I leaves the script's folder out
import common  # noqa: E402
from common import GitHub, Lock, is_auth, is_transient, lock_held, read_json, write_json  # noqa: E402
import loop_task  # noqa: E402

WINDOWS_DROP = (
    'Get "https://api.github.com/repos/UkrainianWiki/wlxjury/issues/234/comments?per_page=100&page=1": '
    "dial tcp 10.0.0.1:443: connectex: A connection attempt failed because the connected party did not "
    "properly respond after a period of time, or established connection failed because connected host "
    "has failed to respond.")
LINUX_TIMEOUT = ('Get "https://api.github.com/repos/UkrainianWiki/wlxjury/pulls/229": '
                 "dial tcp 140.82.121.6:443: i/o timeout")
NOT_FOUND = "gh: Not Found (HTTP 404)"
UNPROCESSABLE = "gh: Validation Failed (HTTP 422)"
RATE_LIMIT = ("gh: API rate limit exceeded for user ID 1234. If you reach out to GitHub Support for help, "
              "please include the request ID ABCD:1234. (HTTP 403)")


def done(code, stderr=b"", stdout=b""):
    return subprocess.CompletedProcess([], code, stdout=stdout, stderr=stderr)


class IsTransientTest(unittest.TestCase):
    def test_network_failures(self):
        for err in [WINDOWS_DROP, LINUX_TIMEOUT,
                    "gh: HTTP 502: Bad Gateway (https://api.github.com/graphql)",
                    "HTTP 503: Service Unavailable", "gh: Gateway Timeout (HTTP 504)",
                    "error connecting to api.github.com",
                    "read tcp 10.0.0.2:51234->140.82.121.6:443: wsarecv: connection reset by peer",
                    "dial tcp: lookup api.github.com: no such host",
                    "dial tcp: lookup api.github.com: Temporary failure in name resolution",
                    'Post "https://api.github.com/graphql": unexpected EOF',
                    "net/http: TLS handshake timeout",
                    "context deadline exceeded (Client.Timeout exceeded while awaiting headers)",
                    "connect: connection refused", "the operation timed out"]:
            with self.subTest(err=err):
                self.assertTrue(is_transient(err))

    def test_answers_are_not_transient(self):
        for err in [NOT_FOUND, UNPROCESSABLE, RATE_LIMIT,
                    "gh: Bad credentials (HTTP 401)",
                    "gh: Resource not accessible by integration (HTTP 403)",
                    "gh: You have exceeded a secondary rate limit. Please wait a few minutes. (HTTP 403)",
                    "unknown flag: --nope", ""]:
            with self.subTest(err=err):
                self.assertFalse(is_transient(err))

    def test_eof_only_as_a_word(self):
        self.assertTrue(is_transient("unexpected EOF"))
        self.assertFalse(is_transient("could not parse whereof"))
        self.assertFalse(is_transient("EOFX in the response"))

    def test_only_gateway_errors_among_5xx(self):
        self.assertTrue(is_transient("HTTP 502: Bad Gateway"))
        self.assertFalse(is_transient("HTTP 5020"))
        # deliberate: a 500 is GitHub failing on this request, not the network; it raises
        self.assertFalse(is_transient("gh: Server Error (HTTP 500)"))


class RunTest(unittest.TestCase):
    def setUp(self):
        self.logged = []
        self.hub = GitHub("gh", "o/r", log=self.logged.append)

    def run_with(self, *results, write=False):
        with mock.patch.object(common.subprocess, "run", side_effect=list(results)) as run, \
                mock.patch.object(common.time, "sleep") as sleep:
            try:
                return self.hub.run("api", "repos/o/r/pulls/1", write=write), run, sleep
            except RuntimeError as e:
                return e, run, sleep

    def test_read_retries_a_transient_error(self):
        out, run, sleep = self.run_with(done(1, WINDOWS_DROP.encode()), done(1, WINDOWS_DROP.encode()),
                                        done(0, stdout=b'{"ok": true}'))
        self.assertEqual(out, '{"ok": true}')
        self.assertEqual(run.call_count, 3)
        self.assertEqual(sleep.call_count, 2)
        self.assertEqual(self.logged, ["network error, retrying in 30 s (1/20)",
                                       "network error, retrying in 30 s (2/20)"])

    def test_read_raises_at_once_on_an_answer(self):
        for err in [NOT_FOUND, UNPROCESSABLE, RATE_LIMIT]:
            with self.subTest(err=err):
                out, run, sleep = self.run_with(done(1, err.encode()))
                self.assertIsInstance(out, RuntimeError)
                self.assertEqual(str(out), f"gh api repos/o/r/pulls/1: {err}")
                self.assertEqual((run.call_count, sleep.call_count), (1, 0))

    def test_write_never_retries(self):
        out, run, sleep = self.run_with(done(1, WINDOWS_DROP.encode()), write=True)
        self.assertIsInstance(out, RuntimeError)
        self.assertIn("connectex", str(out))
        self.assertEqual((run.call_count, sleep.call_count), (1, 0))
        self.assertEqual(self.logged, [])

    def test_read_gives_up_after_20_attempts(self):
        out, run, sleep = self.run_with(*[done(1, b"HTTP 502: Bad Gateway")] * 20)
        self.assertIsInstance(out, RuntimeError)
        self.assertIn("network still failing", str(out))
        self.assertEqual((run.call_count, sleep.call_count), (20, 20))


class IsAuthTest(unittest.TestCase):
    def test_auth_failures(self):
        for err in ["gh: Bad credentials (HTTP 401)", "To get started with GitHub CLI, please run:  gh auth login",
                    "gh: Requires authentication (HTTP 401)"]:
            with self.subTest(err=err):
                self.assertTrue(is_auth(err))

    def test_other_errors(self):
        for err in [NOT_FOUND, RATE_LIMIT, WINDOWS_DROP, "gh: Resource not accessible by integration (HTTP 403)"]:
            with self.subTest(err=err):
                self.assertFalse(is_auth(err))


class JsonFilesTest(unittest.TestCase):
    def test_write_then_read(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "sub" / "state.json"
            write_json(path, {"1": {"outcome": "reviewed", "text": "Ukrainian: Київ"}})
            write_json(path, {"2": {"outcome": "clean"}})
            self.assertEqual(read_json(path, {}), {"2": {"outcome": "clean"}})
            self.assertEqual(sorted(p.name for p in path.parent.iterdir()), ["state.json"])  # no .tmp left

    def test_missing_is_the_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(read_json(pathlib.Path(tmp) / "none.json", {"d": 1}), {"d": 1})

    def test_a_file_cut_short_is_kept_aside_and_the_default_returned(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "requests.json"
            path.write_text('{"218": {"outcome": "cle', encoding="utf-8")
            self.assertEqual(read_json(path, {}), {})
            self.assertFalse(path.exists())
            self.assertTrue((pathlib.Path(tmp) / "requests.json.corrupt").exists())


class LockTest(unittest.TestCase):
    def test_one_holder(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "loop.lock"
            self.assertFalse(lock_held(path))
            first, second = Lock(path), Lock(path)
            self.assertTrue(first.acquire())
            try:
                self.assertTrue(lock_held(path))
                self.assertFalse(second.acquire())
            finally:
                first.release()
            self.assertFalse(lock_held(path))
            self.assertTrue(second.acquire())
            second.release()


class LoopTaskTest(unittest.TestCase):
    def launch(self, folder):
        out = []
        with mock.patch.object(loop_task.subprocess, "Popen") as popen:
            code = loop_task.launch(folder, out=out.append)
        return code, out, popen

    def test_launch_does_nothing_while_running(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = pathlib.Path(tmp)
            lock = Lock(folder / "loop.lock")
            self.assertTrue(lock.acquire())
            try:
                code, out, popen = self.launch(folder)
            finally:
                lock.release()
            self.assertEqual((code, out, popen.called), (0, ["running already"], False))

    def test_launch_does_nothing_after_stop(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = pathlib.Path(tmp)
            (folder / "loop.stop").write_text("x")
            code, out, popen = self.launch(folder)
            self.assertEqual((code, popen.called), (0, False))
            self.assertIn("stopped", out[0])

    def test_launch_starts_the_daemon_with_the_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = pathlib.Path(tmp)
            code, out, popen = self.launch(folder)
            self.assertEqual(code, 0)
            cmd, kw = popen.call_args[0][0], popen.call_args[1]
            self.assertTrue(cmd[1].endswith("loop-daemon.sh"))
            self.assertEqual(kw["env"]["REVIEW_DIR"], folder.as_posix())

    def test_task_xml(self):
        for logon in (True, False):
            with self.subTest(logon=logon):
                xml = loop_task.task_xml(pathlib.Path("C:/x/target/review"), 30, logon)
                root = ET.fromstring(xml.split("?>", 1)[1])
                ns = {"t": "http://schemas.microsoft.com/windows/2004/02/mit/task"}
                self.assertEqual(root.find("t:Triggers/t:TimeTrigger/t:Repetition/t:Interval", ns).text, "PT30M")
                self.assertEqual(root.find("t:Triggers/t:LogonTrigger", ns) is not None, logon)
                settings = {e.tag.split("}")[1]: e.text for e in root.find("t:Settings", ns)}
                self.assertEqual((settings["DisallowStartIfOnBatteries"], settings["StopIfGoingOnBatteries"],
                                  settings["StartWhenAvailable"], settings["MultipleInstancesPolicy"]),
                                 ("false", "false", "true", "IgnoreNew"))
                self.assertIn("launch --dir", root.find("t:Actions/t:Exec/t:Arguments", ns).text)


if __name__ == "__main__":
    unittest.main()
