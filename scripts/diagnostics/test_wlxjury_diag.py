"""Tests of wlxjury-diag.py on synthetic API answers and log lines (standard library only)."""
import datetime
import gzip
import importlib.util
import io
import os
import pathlib
import stat
import tempfile
import unittest
import urllib.request
from contextlib import redirect_stderr, redirect_stdout

_spec = importlib.util.spec_from_file_location("wlxjury_diag", str(pathlib.Path(__file__).with_name("wlxjury-diag.py")))
diag = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(diag)

NOW = datetime.datetime(2026, 11, 20, 12, 0, 0)
TOKEN = "wlxj_" + "A" * 43


def log_line(when, message, level="WARN", logger="security.authz"):
    return f"{when:%Y-%m-%d %H:%M:%S},123 [{level}] {logger} - {message}\n"


def denial(contest_id, ref="1a2b3c4d"):
    return (f"authorization_denied ref={ref} accountId=812 method=GET path=/gallery/round/9 currentContestId=466 "
            f"targetContestId={contest_id} resource=round:9 requiredRoles=\"jury\" accountRoles=\"jury\" "
            f"targetContestMembershipState=active targetContestRoles=\"jury\" reason=missing_role")


def job(id_, status="done", kind="commons_image_import", superseded=False, error=None, reference=None):
    return {"id": id_, "kind": kind, "status": status, "createdAt": "2026-11-20T10:00:00Z", "finishedAt": None,
            "superseded": superseded, "error": error, "reference": reference, "text": None}


def contest(id_, name="Wiki Loves Monuments", year=2026, country="Nepal", retired=False):
    return {"id": id_, "name": name, "year": year, "country": country, "retired": retired}


RECENT = {
    "hours": 24,
    "contests": [
        {"contest": contest(466), "outcome": "fail",
         "jobs": [job(812, "failed", error="job.error.unexpected", reference="3fa9c1d2"), job(813)],
         "roundChanges": [{"id": 1213, "number": 2, "status": "paused", "changedAt": "2026-11-20T09:00:00Z"}]},
        {"contest": contest(603, "Wiki Loves Earth", 2026, "Ukraine"), "outcome": "pass", "jobs": [job(900)],
         "roundChanges": []},
    ],
    "otherJobs": [job(1, "failed", kind="round_image_check")],
}

HEALTH = {
    "contest": contest(466),
    "outcome": "fail",
    "files": 32,
    "recentActivity": True,
    "checks": [
        {"code": "H1", "title": "The contest and its import sources", "outcome": "warn",
         "hints": [{"outcome": "warn", "key": "health.h1.warn", "args": [3], "text": "Import sources that name another contest: 3"}]},
        {"code": "H2", "title": "Files by country", "outcome": "pass",
         "hints": [{"outcome": "pass", "key": "health.h2.pass", "args": [], "text": "Every file is in a country."}]},
        {"code": "H7", "title": "Empty or tiny rounds", "outcome": "fail",
         "hints": [{"outcome": "fail", "key": "health.h7.empty", "args": [2], "text": "Round 2 has no files.\x1b[2J"}]},
    ],
    "imports": [{"jobId": 812, "status": "done", "mode": "import", "source": "Category:X", "seen": 12, "inserted": 12,
                 "linked": 0, "missing": 0, "notInCountries": 0, "createdAt": "2026-11-20T10:00:00Z",
                 "finishedAt": None, "error": None, "reference": None, "creator": "Nepal Admin", "creatorMember": True}],
    "rounds": [{"id": 1213, "number": 1, "status": "active", "files": 32, "jurors": 3, "distributing": False,
                "flagged": ["H8"]}],
}


class FakeApi:
    def __init__(self, answers):
        self.answers, self.calls = answers, []

    def get(self, path, query=None):
        self.calls.append((path, query))
        return self.answers[path]


class TextTest(unittest.TestCase):
    def test_control_characters_are_stripped(self):
        self.assertEqual(diag.clean("a\x1b[2Jb‮c\x00"), "a [2Jb c ")
        self.assertEqual(diag.clean("x؜y z w⁦v"), "x y z w v")

    def test_since(self):
        self.assertEqual(diag.parse_since("24h"), 24)
        self.assertEqual(diag.parse_since("3d"), 72)
        self.assertEqual(diag.parse_since("6"), 6)
        for bad in ("0h", "31d", "1w", "", "-1h"):
            with self.assertRaises(Exception):
                diag.parse_since(bad)


class ConnectionTest(unittest.TestCase):
    def test_only_loopback_http(self):
        self.assertEqual(diag.check_url("http://127.0.0.1:9000/"), "http://127.0.0.1:9000")
        self.assertEqual(diag.check_url("http://[::1]:9000"), "http://[::1]:9000")
        for bad in ("https://127.0.0.1:9000", "http://jury.wle.org.ua", "http://10.0.0.1:9000",
                    "http://localhost:9000", "http://127.0.0.1:9000/api", "http://user@127.0.0.1:9000"):
            with self.assertRaises(diag.DiagError, msg=bad):
                diag.check_url(bad)

    @unittest.skipUnless(os.name == "posix", "file modes")
    def test_the_token_file_must_be_private(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "token")
            with open(path, "w") as f:
                f.write(TOKEN + "\n")
            os.chmod(path, 0o644)
            with self.assertRaises(diag.DiagError):
                diag.read_token(path)
            os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
            self.assertEqual(diag.read_token(path), TOKEN)

    def test_a_missing_or_malformed_token_file(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(diag.DiagError):
                diag.read_token(os.path.join(d, "none"))
            path = os.path.join(d, "token")
            with open(path, "w") as f:
                f.write("not a token")
            os.chmod(path, 0o600)
            with self.assertRaises(diag.DiagError):
                diag.read_token(path)

    def test_the_request_carries_the_token_and_the_jury_host_and_follows_no_redirect(self):
        seen = []

        class Opener:
            def open(self, request, timeout):
                seen.append(request)
                return io.BytesIO(b'{"hours": 24}')

        api = diag.Api("http://127.0.0.1:9000", "jury.wle.org.ua", TOKEN, opener=Opener())
        self.assertEqual(api.get("diagnostics/recent", {"hours": 24}), {"hours": 24})
        request = seen[0]
        self.assertEqual(request.full_url, "http://127.0.0.1:9000/api/diagnostics/recent?hours=24")
        self.assertEqual(request.get_header("Authorization"), "Bearer " + TOKEN)
        self.assertEqual(request.get_header("Host"), "jury.wle.org.ua")
        handler = diag._NoRedirect()
        self.assertIsNone(handler.redirect_request(request, None, 302, "Found", {}, "http://evil.example/"))

    def test_the_real_opener_uses_no_proxy_even_with_proxy_variables_set(self):
        names = ("http_proxy", "HTTP_PROXY", "https_proxy", "HTTPS_PROXY", "no_proxy", "NO_PROXY")
        saved = {k: os.environ.get(k) for k in names}
        try:
            for k in names:
                os.environ.pop(k, None)
            os.environ["http_proxy"] = "http://proxy.example:3128"
            os.environ["HTTP_PROXY"] = "http://proxy.example:3128"
            api = diag.Api("http://127.0.0.1:9000", "jury.wle.org.ua", TOKEN)
            handlers = api.opener.handlers
            proxies = [h for h in handlers if isinstance(h, urllib.request.ProxyHandler)]
            # an empty ProxyHandler opens nothing, so the opener keeps none; the default one would
            self.assertEqual(proxies, [])
            self.assertTrue(any(isinstance(h, urllib.request.ProxyHandler) and h.proxies
                                for h in urllib.request.build_opener().handlers))
            self.assertFalse(any(hasattr(h, "http_proxy_open") or hasattr(h, "proxy_open") for h in handlers))
            self.assertTrue(any(isinstance(h, diag._NoRedirect) for h in handlers))
            self.assertFalse(any(type(h) is urllib.request.HTTPRedirectHandler for h in handlers))
        finally:
            for k, v in saved.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v

    def test_an_error_names_the_status_and_reference_never_the_token(self):
        text = diag.api_error(403, b'{"error": "forbidden", "reference": "1a2b3c4d"}')
        self.assertIn("403", text)
        self.assertIn("1a2b3c4d", text)
        self.assertNotIn(TOKEN, text)
        self.assertIn("401", diag.api_error(401, b"not json"))


class LogTest(unittest.TestCase):
    def test_denials_of_the_window_by_contest(self):
        cutoff = NOW - datetime.timedelta(hours=24)
        lines = [
            log_line(NOW - datetime.timedelta(hours=30), denial(466)),
            log_line(NOW - datetime.timedelta(hours=2), denial(466)),
            log_line(NOW - datetime.timedelta(hours=1), denial(466)),
            log_line(NOW - datetime.timedelta(hours=1), denial(603)),
            log_line(NOW, "round_access_refused reason=round_stopped roundId=9 accountId=812 contestId=466",
                     level="INFO", logger="round.access"),
            log_line(NOW, "authorization_denied ref=x accountId=1 method=GET path=/api/contests currentContestId=none "
                          "targetContestId=unknown resource=api reason=token_not_local"),
            "\tat controllers.Secured.something(Secured.scala:1) targetContestId=999\n",
            log_line(NOW, "job_done job=1 kind=x contestId=466", level="INFO", logger="services.jobs"),
        ]
        self.assertEqual(diag.denials(lines, cutoff), {466: 3, 603: 1})

    def test_the_rotated_logs_of_the_window_gzip_included(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "application.log"), "w") as f:
                f.write(log_line(NOW - datetime.timedelta(hours=1), denial(466)))
            with gzip.open(os.path.join(d, "application.2026-11-19.0.log.gz"), "wt") as f:
                f.write(log_line(NOW - datetime.timedelta(hours=20), denial(466)))
            with gzip.open(os.path.join(d, "application.2026-11-01.0.log.gz"), "wt") as f:
                f.write(log_line(NOW - datetime.timedelta(days=19), denial(466)))
            with open(os.path.join(d, "other.log"), "w") as f:
                f.write(log_line(NOW, denial(1)))
            counts, unreadable = diag.log_denials(d, 24, now=NOW)
            self.assertEqual(counts, {466: 2})
            self.assertEqual(unreadable, [])

    def test_a_missing_log_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            counts, unreadable = diag.log_denials(d, 24, now=NOW)
            self.assertEqual(counts, {})
            self.assertEqual(unreadable, [os.path.join(d, "application.log")])


class RecentTest(unittest.TestCase):
    def test_the_worst_first_denials_added_and_one_next_command_last(self):
        lines = diag.recent_lines(RECENT, {603: 4, 777: 1}, [], 24)
        self.assertTrue(lines[0].startswith("Activity in the last 24 h: 3 contests"))
        self.assertTrue(lines[1].startswith("FAIL 466 Wiki Loves Monuments 2026 Nepal:"), lines[1])
        self.assertIn("commons_image_import #812 failed: job.error.unexpected ref 3fa9c1d2", lines[1])
        self.assertIn("round status 2->paused", lines[1])
        self.assertTrue(lines[2].startswith("WARN 603 Wiki Loves Earth 2026 Ukraine:"), lines[2])
        self.assertIn("4 denials in the log", lines[2])
        self.assertTrue(lines[3].startswith("WARN 777 contest 777 (no app activity): 1 denial in the log"), lines[3])
        self.assertIn("round_image_check #1", lines[4])
        self.assertEqual(lines[-1], "wlxjury-diag contest 466")
        self.assertEqual(sum(1 for line in lines if line.startswith("wlxjury-diag")), 1)

    def test_nothing_in_the_window_suggests_a_wider_one(self):
        lines = diag.recent_lines({"hours": 24, "contests": [], "otherJobs": []}, {}, ["/var/log/wlxjury/application.log"], 24)
        self.assertIn("(log not read: /var/log/wlxjury/application.log)", lines)
        self.assertEqual(lines[-1], "wlxjury-diag recent --since 72h")

    def test_the_widest_window_empty_suggests_the_log_not_the_same_command(self):
        lines = diag.recent_lines({"hours": 720, "contests": [], "otherJobs": []}, {}, [], 720,
                                  log_dir="/var/log/wlxjury")
        self.assertEqual(lines[-1], "tail -n 20 /var/log/wlxjury/application.log")
        lines = diag.recent_lines({"hours": 300, "contests": [], "otherJobs": []}, {}, [], 300)
        self.assertEqual(lines[-1], "wlxjury-diag recent --since 720h")

    def test_the_command_asks_the_app_for_the_window(self):
        api = FakeApi({"diagnostics/recent": RECENT})
        with tempfile.TemporaryDirectory() as d, redirect_stdout(io.StringIO()) as out:
            code = diag.main(["recent", "--since", "2d", "--log-dir", d], api=api)
        self.assertEqual(code, 0)
        self.assertEqual(api.calls, [("diagnostics/recent", {"hours": 48})])
        self.assertEqual(out.getvalue().splitlines()[-1], "wlxjury-diag contest 466")


class ContestTest(unittest.TestCase):
    def test_warnings_and_failures_first_cleaned_then_the_verbose_command(self):
        lines = diag.contest_lines(HEALTH)
        self.assertEqual(lines[0], "Contest 466: Wiki Loves Monuments 2026 Nepal, 32 files, 1 round: FAIL")
        self.assertEqual(lines[1], "FAIL H7 Empty or tiny rounds: Round 2 has no files. [2J")
        self.assertEqual(lines[2], "WARN H1 The contest and its import sources: Import sources that name another contest: 3")
        self.assertEqual(lines[3], "1 check without a warning")
        self.assertEqual(lines[-1], "wlxjury-diag contest 466 --verbose")
        self.assertFalse(any("\x1b" in line for line in lines))

    def test_verbose_lists_rounds_and_imports_names_only_when_asked(self):
        lines = diag.contest_lines(HEALTH, verbose=True)
        self.assertTrue(any(line.startswith("PASS H2 Files by country") for line in lines))
        self.assertTrue(any("round 1 (id 1213): active, 32 files, 3 jurors flagged H8" in line for line in lines))
        self.assertFalse(any("Nepal Admin" in line for line in lines))
        self.assertTrue(any("by Nepal Admin" in line for line in diag.contest_lines(HEALTH, verbose=True, names=True)))
        self.assertEqual(lines[-1], "grep -h 'targetContestId=466 ' /var/log/wlxjury/application.log | tail -20")

    def test_a_retired_contest_is_marked(self):
        retired = dict(HEALTH, contest=contest(466, retired=True))
        self.assertIn("[retired]", diag.contest_lines(retired)[0])

    def test_an_api_error_is_one_line_with_exit_code_2(self):
        class Refusing:
            def get(self, path, query=None):
                raise diag.DiagError(diag.api_error(403, b'{"error": "forbidden", "reference": "1a2b3c4d"}'))

        with redirect_stdout(io.StringIO()) as out, redirect_stderr(io.StringIO()) as err:
            code = diag.main(["contest", "466"], api=Refusing())
        self.assertEqual(code, 2)
        self.assertEqual(out.getvalue(), "")
        self.assertEqual(len(err.getvalue().splitlines()), 1)



def summary(id_, verdict="move", moves=1, active=(), r4=False, name="Wiki Loves Earth", year=2020, country="Nepal"):
    return {"contestId": id_, "name": name, "year": year, "country": country, "retired": False, "seriesId": None,
            "verdict": verdict, "rounds": 9, "clusters": 3, "kinds": {"own": 1, "r1": 1, "r4": 1},
            "actions": {"stay": 1, "move": moves, "choose": 1}, "mixed": 0, "empty": 0, "moves": moves,
            "r4Host": r4, "activeToMove": list(active), "signs": ["round.source"], "filesToLink": 20, "jurorsToAdd": 1,
            "onlyMovedJurors": 0, "unusedInSource": 20, "rowsToMove": 3}


REUSE = {
    "scan": {"jobId": 900, "status": "done", "phase": "scan", "done": 286, "total": 286,
             "createdAt": "2026-11-20T10:00:00Z", "finishedAt": "2026-11-20T10:02:00Z"},
    "resultsOf": 900,
    "totals": {"scanned": 286, "clean": 270, "verdicts": {"choose": 1, "move": 2}, "r4Hosts": 1,
               "kinds": {"own": 3, "r1": 3, "r4": 3}, "actions": {"stay": 3, "move": 3, "choose": 3}, "mixed": 0,
               "empty": 0, "moves": 3, "activeToMove": 1, "filesToLink": 60, "jurorsToAdd": 3, "onlyMovedJurors": 0,
               "unusedInSource": 60, "rowsToMove": 9},
    "contests": [summary(535, moves=1, name="Wiki Loves Earth\x1b[2J", year=2022, country="Philippines"),
                 summary(466, verdict="choose", moves=2, active=[1900], r4=True)],
}

CLUSTER = {"index": 2, "identity": "Wiki Loves Earth 2026 in Bhutan", "kind": "r4", "action": "move", "mixed": False,
           "empty": False, "disagreements": [], "activeRounds": [1900],
           "target": {"seriesId": None, "seriesName": "Wiki Loves Earth in Bhutan", "seriesType": "wle", "place": "bt",
                      "year": 2026, "contests": []},
           "buckets": {"matching": 965, "foreign": 0, "unclassified": 0},
           "signals": {"s1Year": 2026, "s2": [{"identity": "Wiki Loves Earth 2026 in Bhutan", "files": 965}],
                       "s2ManyCountries": False, "s3Year": 2026, "s4From": None, "s4To": None, "s5": [],
                       "s5r": [{"round": 1900, "source": "Category:Images from Wiki Loves Earth 2026 in Bhutan",
                                "identity": "Wiki Loves Earth 2026 in Bhutan"}], "s6": []},
           "jurors": {"total": 1, "toAdd": 1, "deleted": 0, "protectedAccounts": 0, "conflict": 0, "already": 0,
                      "notHostMembers": 0, "inOtherClusters": 0},
           "runners": {"total": 1, "hostAdmins": 1}, "votes": 0, "comments": 0, "jobs": 1,
           "rounds": [{"id": 1900, "number": 48, "status": "active", "active": True, "category": None, "files": 965,
                       "jurors": 1, "votes": 0, "comments": 0, "jobs": 1}]}

REUSE_CONTEST = {"contest": contest(466, "Wiki Loves Earth", 2020, "Nepal"), "verdict": "choose",
                 "identity": "Wiki Loves Earth 2020 in Nepal", "national": True, "r4Host": True, "signs": ["round.source"],
                 "eras": True, "moves": 8, "jurorsToAdd": 3, "onlyMovedJurors": 1, "unusedInSource": 7000,
                 "clusters": [dict(CLUSTER, index=1, identity="Wiki Loves Earth 2020 in Nepal", kind="own", action="stay",
                                   target=None, activeRounds=[]),
                              CLUSTER,
                              dict(CLUSTER, index=3, action="choose", disagreements=["s2:country"], activeRounds=[])]}


class ReuseTest(unittest.TestCase):
    def test_no_scan_points_to_roots_page(self):
        lines = diag.reuse_lines({"scan": None, "totals": None, "contests": []})
        self.assertIn("/admin/contests/reuse", lines[0])
        self.assertEqual(lines[-1], "wlxjury-diag reuse")

    def test_the_totals_then_the_most_demanding_contests_cleaned_and_the_first_as_next(self):
        lines = diag.reuse_lines(REUSE)
        self.assertEqual(lines[0], "Reuse scan #900: done, pass 2 of 2 (reports), 286 of 286 contests, "
                                   "finished 2026-11-20T10:02:00Z")
        self.assertEqual(lines[1], "286 contests scanned, 270 with nothing to report; choose 1, move 2")
        self.assertIn("3 moves, 1 R4 hosts, 1 active rounds to move", lines[3])
        self.assertTrue(lines[4].startswith("CHOOSE 466 Wiki Loves Earth 2020 Nepal: 3 clusters (move 2, choose 1, stay 1), "
                                            "2 moves, active 1900 [R4 host]"))
        self.assertTrue(lines[5].startswith("MOVE   535 Wiki Loves Earth [2J 2022 Philippines"))
        self.assertFalse(any("\x1b" in line for line in lines))
        self.assertEqual(lines[-1], "wlxjury-diag reuse 466")

    def test_a_running_scan_shows_its_progress_and_the_last_finished_scans_results(self):
        running = dict(REUSE, scan=dict(REUSE["scan"], jobId=901, status="running", phase="eras", done=12,
                                        finishedAt=None))
        lines = diag.reuse_lines(running)
        self.assertEqual(lines[0], "Reuse scan #901: running, pass 1 of 2 (eras), 12 of 286 contests")
        self.assertEqual(lines[1], "The results below are scan #900's, the latest finished.")
        self.assertEqual(lines[-1], "wlxjury-diag reuse 466")
        first = dict(running, resultsOf=None, totals=None, contests=[])
        self.assertEqual(diag.reuse_lines(first)[-2:], ["No finished scan yet: the totals come with the first.",
                                                       "wlxjury-diag reuse"])

    def test_a_contests_clusters_then_the_verbose_command(self):
        lines = diag.reuse_contest_lines(REUSE_CONTEST)
        self.assertEqual(lines[0], "Contest 466: Wiki Loves Earth 2020 Nepal: CHOOSE, 3 clusters, 8 moves, 3 jurors to add, "
                                   "7000 files left to moved rounds only [R4 host]")
        self.assertEqual(lines[2], "   2 rounds 1900 (1): MOVE r4 Wiki Loves Earth 2026 in Bhutan -> Wiki Loves Earth in Bhutan "
                                   "2026 (a new contest); files 965/0/0, jurors 1 [active 1900]")
        self.assertIn("[disagree s2:country]", lines[3])
        self.assertEqual(lines[-1], "wlxjury-diag reuse 466 --verbose")
        verbose = diag.reuse_contest_lines(REUSE_CONTEST, verbose=True)
        self.assertTrue(any("S5r 1900: Category:Images from Wiki Loves Earth 2026 in Bhutan" in line for line in verbose))
        self.assertTrue(any("jurors: 1 to add, 0 deleted" in line for line in verbose))
        self.assertEqual(verbose[-1], "wlxjury-diag contest 466")

    def test_the_command_reads_the_scan_or_the_contest(self):
        api = FakeApi({"diagnostics/reuse": REUSE, "diagnostics/reuse/466": REUSE_CONTEST})
        with redirect_stdout(io.StringIO()) as out:
            self.assertEqual(diag.main(["reuse"], api=api), 0)
            self.assertEqual(diag.main(["reuse", "466", "--verbose"], api=api), 0)
        self.assertEqual(api.calls, [("diagnostics/reuse", None), ("diagnostics/reuse/466", None)])
        self.assertIn("wlxjury-diag contest 466", out.getvalue())


if __name__ == "__main__":
    unittest.main()
