#!/usr/bin/env python3
"""Post or update the coverage comment on a PR after a Coverage run (coverage-comment.yml).

Runs in a `workflow_run` job, which has a token that can write PR comments. So it never runs
the PR's code: it reads the GitHub event (GITHUB_EVENT_PATH) and, through the runner's `gh`,
the Coverage run's `coverage-summary-attempt-N` artifact, which the PR's code could have
written. That file is data: parse_summary accepts only the format coverage-summary.py --json
writes (known keys, a 40-hex SHA, a PR number, percentages between 0 and 100) and refuses the
rest. Nothing read from it, the event or the API reaches a shell or a `${{ }}` expression.

The comment is the one with MARKER written by github-actions[bot], updated in place. The base
numbers come from the latest successful Coverage run on the PR's base branch.

    python3 -I scripts/coverage_comment.py          # in the workflow, with GH_TOKEN set
    python3 -m unittest scripts/test_coverage_reports.py
"""
import io
import json
import math
import os
import re
import subprocess
import sys
from urllib.parse import urlencode
import zipfile

MARKER = "<!-- wlxjury-coverage-comment -->"
BOT = "github-actions[bot]"
SUMMARY_PREFIX = "coverage-summary-attempt-"
SUMMARY_FILE = "coverage-summary.json"
MAX_ZIP_BYTES = 64 * 1024
MAX_JSON_BYTES = 16 * 1024
BASE_RUNS_EXAMINED = 10

SHA_RE = re.compile(r"\A[0-9a-f]{40}\Z")
REPO_RE = re.compile(r"\A[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}\Z")
SECTIONS = {"scala": ("statements", "branches"), "js": ("lines", "branches")}
TOP_KEYS = {"version", "head_sha", "pr", "scala", "js"}
ROWS = [("Scala statements", "scala", "statements"), ("Scala branches", "scala", "branches"),
        ("JS lines", "js", "lines"), ("JS branches", "js", "branches")]
CONCLUSIONS = {"success", "failure", "neutral", "cancelled", "skipped", "timed_out",
               "action_required", "stale", "startup_failure"}


class SummaryError(ValueError):
    """The artifact's file is not what coverage-summary.py --json writes."""


def _no_duplicates(pairs):
    keys = [k for k, _ in pairs]
    if len(keys) != len(set(keys)):
        raise SummaryError("duplicate key")
    return dict(pairs)


def _refuse_constant(name):
    raise SummaryError(f"{name} is not a number here")


def _is_int(value):
    return type(value) is int  # bool is an int subclass: refused


def _percent(value):
    if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 100:
        raise SummaryError("a percentage must be a number from 0 to 100")
    return float(value)


def parse_summary(raw):
    """The validated summary (dict) from the artifact's bytes, or SummaryError."""
    if not isinstance(raw, (bytes, bytearray)) or len(raw) > MAX_JSON_BYTES:
        raise SummaryError("not a small file")
    try:
        data = json.loads(raw.decode("utf-8"), object_pairs_hook=_no_duplicates,
                          parse_constant=_refuse_constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SummaryError(f"not JSON: {type(error).__name__}") from None
    if not isinstance(data, dict) or set(data) != TOP_KEYS:
        raise SummaryError("unexpected keys")
    if not _is_int(data["version"]) or data["version"] != 1:
        raise SummaryError("unknown version")
    if not isinstance(data["head_sha"], str) or not SHA_RE.match(data["head_sha"]):
        raise SummaryError("head_sha is not a commit SHA")
    pr = data["pr"]
    if pr is not None and (not _is_int(pr) or not 1 <= pr < 10 ** 9):
        raise SummaryError("pr is not a PR number")
    out = {"version": 1, "head_sha": data["head_sha"], "pr": pr}
    for section, keys in SECTIONS.items():
        value = data[section]
        if value is None:
            out[section] = None
        elif isinstance(value, dict) and set(value) == set(keys):
            out[section] = {key: _percent(value[key]) for key in keys}
        else:
            raise SummaryError(f"{section} has unexpected keys")
    return out


def read_summary_zip(blob):
    """The summary from an artifact's zip: only SUMMARY_FILE is read, with a size cap."""
    if len(blob) > MAX_ZIP_BYTES:
        raise SummaryError("artifact too large")
    try:
        with zipfile.ZipFile(io.BytesIO(blob)) as archive:
            info = archive.getinfo(SUMMARY_FILE)
            if info.file_size > MAX_JSON_BYTES:
                raise SummaryError("summary too large")
            with archive.open(info) as member:
                raw = member.read(MAX_JSON_BYTES + 1)
    except (zipfile.BadZipFile, KeyError, RuntimeError, NotImplementedError, OSError) as error:
        raise SummaryError(f"not the summary artifact: {type(error).__name__}") from None
    return parse_summary(raw)


def safe_ref(name):
    """A branch name for a code span: anything but [A-Za-z0-9._/-] becomes '?'."""
    text = re.sub(r"[^A-Za-z0-9._/-]", "?", str(name))[:100]
    return text or "?"


def _value(summary, section, key):
    part = summary.get(section) if summary else None
    return None if part is None else part[key]


def render_comment(head, base, run_url, conclusion, base_ref, base_note):
    """The comment's Markdown. head is a validated summary; base one or None."""
    lines = [MARKER, "### Test coverage", ""]
    lines += ["| | This PR | Base | Change |", "|---|---:|---:|---:|"]
    for label, section, key in ROWS:
        now, before = _value(head, section, key), _value(base, section, key)
        cell_now = "n/a" if now is None else f"{now:.2f} %"
        cell_before = "n/a" if before is None else f"{before:.2f} %"
        change = "" if now is None or before is None else f"{now - before:+.2f}"
        lines.append(f"| {label} | {cell_now} | {cell_before} | {change} |")
    lines.append("")
    lines.append(f"Commit `{head['head_sha'][:7]}`, base `{safe_ref(base_ref)}` ({base_note}). "
                 f"[Run summary]({run_url})")
    if conclusion != "success":
        lines.append("")
        lines.append(f"The Coverage run ended with `{conclusion}`: the numbers may be partial.")
    return "\n".join(lines) + "\n"


class Gh:
    """The GitHub API through the runner's preinstalled `gh` (GH_TOKEN from the job)."""

    def _run(self, args, body=None):
        result = subprocess.run(["gh", "api", "-H", "Accept: application/vnd.github+json"] + args,
                                input=body, capture_output=True, check=False, timeout=120)
        if result.returncode != 0:
            raise RuntimeError(f"gh api {args[-1]} failed ({result.returncode})")
        return result.stdout

    def get_json(self, path):
        return json.loads(self._run([path]).decode("utf-8"))

    def get_bytes(self, path):
        return self._run([path])

    def send(self, method, path, payload):
        return json.loads(self._run(["--method", method, "--input", "-", path],
                                    json.dumps(payload).encode("utf-8")).decode("utf-8"))


def summary_of_run(api, repo, run_id, attempt=None):
    """The validated summary of a run: its attempt's artifact, else (attempt None) the newest."""
    listing = api.get_json(f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100")
    found = []
    for artifact in listing.get("artifacts", []):
        name = artifact.get("name")
        if not isinstance(name, str) or not name.startswith(SUMMARY_PREFIX):
            continue
        number = name[len(SUMMARY_PREFIX):]
        if not number.isdigit() or artifact.get("expired") is not False:
            continue
        if not _is_int(artifact.get("id")) or not _is_int(artifact.get("size_in_bytes")):
            continue
        if attempt is None or int(number) == attempt:
            found.append((int(number), artifact))
    if not found:
        return None
    _, artifact = max(found, key=lambda pair: pair[0])
    if artifact["size_in_bytes"] > MAX_ZIP_BYTES:
        raise SummaryError("artifact too large")
    return read_summary_zip(api.get_bytes(f"repos/{repo}/actions/artifacts/{artifact['id']}/zip"))


def base_summary(api, repo, workflow_id, base_ref, base_sha):
    """(summary or None, note): the latest successful Coverage run on the base branch."""
    query = urlencode({"branch": base_ref, "status": "success", "per_page": BASE_RUNS_EXAMINED})
    runs = api.get_json(f"repos/{repo}/actions/workflows/{workflow_id}/runs?{query}")
    for run in runs.get("workflow_runs", [])[:BASE_RUNS_EXAMINED]:
        head_repo = (run.get("head_repository") or {}).get("full_name")
        sha = run.get("head_sha")
        if head_repo != repo or run.get("head_branch") != base_ref or not _is_int(run.get("id")):
            continue
        if not isinstance(sha, str) or not SHA_RE.match(sha):
            continue
        try:
            summary = summary_of_run(api, repo, run["id"])
        except (SummaryError, RuntimeError, ValueError):
            continue
        if summary is None or summary["head_sha"] != sha:
            continue
        note = f"at `{sha[:7]}`" + ("" if sha == base_sha else ", the branch's latest run")
        return summary, note
    return None, "no base run"


def upsert_comment(api, repo, number, body):
    """Update the bot's comment with MARKER on the PR, or create one."""
    for page in range(1, 31):
        comments = api.get_json(f"repos/{repo}/issues/{number}/comments?per_page=100&page={page}")
        for comment in comments:
            user = (comment.get("user") or {}).get("login")
            if user == BOT and MARKER in (comment.get("body") or "") and _is_int(comment.get("id")):
                api.send("PATCH", f"repos/{repo}/issues/comments/{comment['id']}", {"body": body})
                return "updated"
        if len(comments) < 100:
            break
    api.send("POST", f"repos/{repo}/issues/{number}/comments", {"body": body})
    return "created"


def run(event, env, api, log=print):
    """Do the job for one workflow_run event. Returns a short outcome for the log."""
    repo = env.get("GITHUB_REPOSITORY", "")
    server = env.get("GITHUB_SERVER_URL", "https://github.com")
    if not REPO_RE.match(repo) or not re.fullmatch(r"https://[A-Za-z0-9.-]+", server):
        return "skipped: unexpected repository or server"
    wr = event.get("workflow_run") or {}
    if wr.get("event") != "pull_request":
        return "skipped: not a pull_request run"
    if (wr.get("head_repository") or {}).get("full_name") != repo:
        return "skipped: the PR's branch is not in this repository"
    run_id, attempt, workflow_id = wr.get("id"), wr.get("run_attempt"), wr.get("workflow_id")
    head_sha, conclusion = wr.get("head_sha"), wr.get("conclusion")
    if not all(_is_int(v) and v > 0 for v in (run_id, attempt, workflow_id)):
        return "skipped: unexpected run fields"
    if not isinstance(head_sha, str) or not SHA_RE.match(head_sha) or conclusion not in CONCLUSIONS:
        return "skipped: unexpected run fields"
    numbers = sorted({pr.get("number") for pr in wr.get("pull_requests") or []
                      if _is_int(pr.get("number")) and (pr.get("head") or {}).get("sha") == head_sha})
    if not numbers:
        return "skipped: no PR of this repository at the run's commit"

    head = summary_of_run(api, repo, run_id, attempt)
    if head is None:
        return "skipped: the run has no coverage summary"
    if head["head_sha"] != head_sha:
        raise SummaryError("the summary is of another commit")
    if head["pr"] is not None:
        if head["pr"] not in numbers:
            raise SummaryError("the summary is of another PR")
        numbers = [head["pr"]]

    run_url = f"{server}/{repo}/actions/runs/{run_id}"
    outcomes = []
    for number in numbers:
        pr = api.get_json(f"repos/{repo}/pulls/{number}")
        if pr.get("state") != "open":
            outcomes.append(f"#{number}: closed")
            continue
        if (pr.get("head") or {}).get("sha") != head_sha:
            outcomes.append(f"#{number}: has newer commits")
            continue
        if ((pr.get("head") or {}).get("repo") or {}).get("full_name") != repo:
            outcomes.append(f"#{number}: from a fork")
            continue
        base = pr.get("base") or {}
        base_ref, base_sha = base.get("ref"), base.get("sha")
        if not isinstance(base_ref, str) or not base_ref:
            outcomes.append(f"#{number}: no base branch")
            continue
        base_data, note = base_summary(api, repo, workflow_id, base_ref, base_sha)
        body = render_comment(head, base_data, run_url, conclusion, base_ref, note)
        outcomes.append(f"#{number}: {upsert_comment(api, repo, number, body)}")
    return "; ".join(outcomes)


def main():
    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path:
        print("GITHUB_EVENT_PATH is not set", file=sys.stderr)
        return 2
    with open(path, encoding="utf-8") as handle:
        event = json.load(handle)
    try:
        outcome = run(event, os.environ, Gh())
    except SummaryError as error:
        # The artifact isn't what the Coverage job writes: say so, post nothing
        print(f"::warning::coverage summary refused: {error}")
        return 0
    print(outcome)
    return 0


if __name__ == "__main__":
    sys.exit(main())
