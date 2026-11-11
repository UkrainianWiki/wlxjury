"""Tests for common.py: which gh errors a read retries, and that writes never retry.

Standard library only (Python 3.8+), no network: subprocess.run and time.sleep are patched.
Run from scripts/review: python -B -m unittest -v test_common
"""
import pathlib
import subprocess
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I leaves the script's folder out
import common  # noqa: E402
from common import GitHub, is_transient  # noqa: E402

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
        self.assertFalse(is_transient("could not parse whereof"))


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
                self.assertIn(err[:40], str(out))
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


if __name__ == "__main__":
    unittest.main()
