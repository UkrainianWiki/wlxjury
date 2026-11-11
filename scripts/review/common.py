"""Shared helpers for the review tools: gh, the repository, paths, the stack order.

Python 3.8+, stdlib only. gh is taken from --gh, else the GH environment variable, else `gh` on
PATH. State and inputs live under target/review/ (ignored by git) unless a flag says otherwise;
every state file can be rebuilt from GitHub (README.md).
"""
import json
import os
import pathlib
import re
import shutil
import subprocess
import time

REPO = "UkrainianWiki/wlxjury"
# The account that posts our replies; a thread it already answered is skipped.
OURS = "ukwikidev"
ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_DIR = ROOT / "target" / "review"


def add_common_args(ap):
    ap.add_argument("--gh", default=None, help="path to gh (default: $GH, else gh on PATH)")
    ap.add_argument("--repo", default=REPO, help=f"owner/name (default {REPO})")
    return ap


def gh_path(flag=None):
    path = flag or os.environ.get("GH") or shutil.which("gh")
    if not path:
        raise SystemExit("gh not found: pass --gh or set GH (e.g. /c/Users/ilya/tools/gh/bin/gh.exe)")
    return path


# A dropped or refused connection, a DNS failure, a timeout or a gateway error: worth retrying a
# read. Windows' gh says "dial tcp …: connectex: A connection attempt failed …" (2026-11-10/11).
TRANSIENT = re.compile(
    r"error connecting|timeout|timed out|dial tcp|connectex|connection attempt failed|"
    r"connection reset|connection refused|no such host|temporary failure in name resolution|"
    r"\beof\b|tls handshake|HTTP 50[234]\b", re.I)


def is_transient(err):
    """Whether gh's stderr `err` reports a network failure (retry a read) rather than an answer:
    GitHub answering 4xx (401, 403, 404, 422, a rate limit) is never transient."""
    if re.search(r"HTTP 4\d\d\b|rate limit", err, re.I):
        return False
    return bool(TRANSIENT.search(err))


class GitHub:
    """Thin wrapper over `gh api`. Reads retry network errors; writes never retry blindly."""

    def __init__(self, gh, repo, log=print):
        self.gh, self.repo, self.log = gh, repo, log

    def run(self, *args, write=False):
        for attempt in range(20):
            out = subprocess.run([self.gh, *args], capture_output=True)
            if out.returncode == 0:
                return out.stdout.decode("utf-8")
            err = out.stderr.decode("utf-8", "replace").strip()
            if write or not is_transient(err):
                raise RuntimeError(f"gh {' '.join(args[:3])}: {err[:300]}")
            self.log(f"network error, retrying in 30 s ({attempt + 1}/20)")
            time.sleep(30)
        raise RuntimeError(f"gh {' '.join(args[:3])}: network still failing")

    def get(self, path):
        return json.loads(self.run("api", f"repos/{self.repo}/{path}"))

    def get_all(self, path):
        """Every page of a list endpoint (100 per page)."""
        items, page = [], 1
        sep = "&" if "?" in path else "?"
        while True:
            batch = self.get(f"{path}{sep}per_page=100&page={page}")
            items += batch
            if len(batch) < 100:
                return items
            page += 1

    def post(self, path, body_file):
        return json.loads(self.run("api", "-X", "POST", f"repos/{self.repo}/{path}", "-F", f"body=@{body_file}", write=True))

    def patch(self, path, body_file):
        return json.loads(self.run("api", "-X", "PATCH", f"repos/{self.repo}/{path}", "-F", f"body=@{body_file}", write=True))

    def comment(self, pr, body):
        """An issue comment with a literal body (e.g. `@coderabbitai review`)."""
        return self.run("pr", "comment", str(pr), "--repo", self.repo, "--body", body, write=True)

    def pulls(self, state="open"):
        out = self.run("pr", "list", "-R", self.repo, "--state", state, "--limit", "200",
                       "--json", "number,title,headRefName,baseRefName")
        return json.loads(out)


def stack_order(pulls, base="master", prefix="stack/"):
    """The stacked PRs (head branches `stack/…`) in order, following each base branch from `base`."""
    by_base = {}
    for p in pulls:
        if not p["headRefName"].startswith(prefix):
            continue
        by_base.setdefault(p["baseRefName"], []).append(p)
    order, branch = [], base
    while branch in by_base:
        nxt = sorted(by_base[branch], key=lambda p: p["number"])
        if len(nxt) > 1:
            raise SystemExit(f"two PRs are based on {branch}: {[p['number'] for p in nxt]}")
        order.append(nxt[0])
        branch = nxt[0]["headRefName"]
    return order


def read_json(path, default):
    path = pathlib.Path(path)
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def write_json(path, data):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=1, ensure_ascii=False), encoding="utf-8")
