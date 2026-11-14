"""Shared helpers for the review tools: gh, the repository, paths, the stack order.

Python 3.8+, stdlib only. gh is taken from --gh, else the GH environment variable, else `gh` on
PATH. Inputs and outputs live under target/review/ (ignored by git) unless a flag says otherwise;
the CodeRabbit loop keeps its files in loop_dir(), outside the checkout. Every state file can be
rebuilt from GitHub (README.md).
"""
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

REPO = "UkrainianWiki/wlxjury"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
# The account that posts our replies; a thread it already answered is skipped.
OURS = "ukwikidev"
ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_DIR = ROOT / "target" / "review"
GH_TIMEOUT = 300  # seconds for one gh call: a hung gh would otherwise block the loop for good


def loop_dir():
    """The CodeRabbit loop's folder (state, log, queue, lock, stop flag, installed scripts):
    $WLX_REVIEW_DIR, else %LOCALAPPDATA%/wlxjury-review (~/.local/state/wlxjury-review elsewhere).
    Outside every checkout, so removing a worktree or `sbt clean` loses nothing."""
    env = os.environ.get("WLX_REVIEW_DIR")
    if env:
        return pathlib.Path(env)
    base = os.environ.get("LOCALAPPDATA") or str(pathlib.Path.home() / ".local" / "state")
    return pathlib.Path(base) / "wlxjury-review"


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


AUTH = re.compile(r"HTTP 401\b|bad credentials|gh auth login|authentication required|not logged in", re.I)


def is_auth(err):
    """Whether an error says gh is not signed in or its token was refused: a long pause, not a retry."""
    return bool(AUTH.search(str(err)))


def is_transient(err):
    """Whether gh's stderr `err` reports a network failure (retry a read) rather than an answer:
    GitHub answering 4xx (401, 403, 404, 422, a rate limit) is never transient."""
    if re.search(r"HTTP 4\d\d\b|rate limit", err, re.I):
        return False
    return bool(TRANSIENT.search(err))


class GitHub:
    """Thin wrapper over `gh api`. Reads retry network errors; writes never retry blindly."""

    def __init__(self, gh, repo, log=print, stop_check=None):
        """stop_check: called before each retry wait; it raises to end the retries (--stop)."""
        self.gh, self.repo, self.log, self.stop_check = gh, repo, log, stop_check

    def run(self, *args, write=False):
        for attempt in range(20):
            try:
                # no console window per call when started without one (the scheduled task)
                out = subprocess.run([self.gh, *args], capture_output=True, creationflags=NO_WINDOW,
                                     timeout=GH_TIMEOUT)
            except subprocess.TimeoutExpired:
                # run() has killed it. A write may or may not have gone through: never repeated
                # here (the loop's next pass finds a posted comment and waits for it)
                if write:
                    raise RuntimeError(f"gh {' '.join(args[:3])}: timed out after {GH_TIMEOUT} s")
                err = f"timed out after {GH_TIMEOUT} s"
            else:
                if out.returncode == 0:
                    return out.stdout.decode("utf-8")
                err = out.stderr.decode("utf-8", "replace").strip()
                if write or not is_transient(err):
                    raise RuntimeError(f"gh {' '.join(args[:3])}: {err[:300]}")
            if self.stop_check:
                self.stop_check()
            self.log(f"network error ({err[:80]}), retrying in 30 s ({attempt + 1}/20)")
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
        out = self.run("pr", "list", "-R", self.repo, "--state", state, "--limit", "500",
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
    """The file's JSON, or `default` if it is missing. A file cut short (a power loss while it was
    written by an older version) is kept as <name>.corrupt (.corrupt.1, … if that exists) and
    `default` returned: the review state files can be rebuilt from GitHub."""
    path = pathlib.Path(path)
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        aside, k = path.with_name(path.name + ".corrupt"), 0
        while aside.exists():
            k += 1
            aside = path.with_name(f"{path.name}.corrupt.{k}")
        path.replace(aside)
        return default


def write_json(path, data):
    """Writes a temporary file (a name of its own per process and call), flushes it to disk and
    renames it over `path`, so a power loss leaves the old or the new file, never half of one.
    Windows refuses the rename while a reader has the file open: tried again for up to 5 s."""
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.{os.getpid()}.{time.monotonic_ns()}.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(json.dumps(data, indent=1, ensure_ascii=False))
        f.flush()
        os.fsync(f.fileno())
    for attempt in range(50):
        try:
            os.replace(tmp, path)
            return
        except PermissionError:
            if attempt == 49:
                tmp.unlink()
                raise
            time.sleep(0.1)


class Lock:
    """An exclusive lock on a file, held while the process lives: the OS drops it when the process
    ends, however it ends (a crash, a kill, a power loss), so a stale lock never blocks a start."""

    def __init__(self, path):
        self.path, self.f = pathlib.Path(path), None

    def try_once(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        f = open(self.path, "a+")
        try:
            if sys.platform == "win32":
                import msvcrt
                f.seek(0)
                msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            f.close()
            return False
        self.f = f
        return True

    def acquire(self, wait=0.0):
        """True if this process now holds the lock, False if another holder still has it after
        `wait` seconds (tried every 0.1 s: a --status probe holds it for a moment)."""
        end = time.monotonic() + wait
        while True:
            if self.try_once():
                return True
            if time.monotonic() >= end:
                return False
            time.sleep(0.1)

    def release(self):
        if self.f is None:
            return
        try:
            if sys.platform == "win32":
                import msvcrt
                self.f.seek(0)
                msvcrt.locking(self.f.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.f.fileno(), fcntl.LOCK_UN)
        finally:
            self.f.close()
            self.f = None


def lock_held(path):
    """Whether another process holds the lock at `path` (e.g. the loop is running)."""
    lock = Lock(path)
    if lock.try_once():
        lock.release()
        return False
    return True


def update_json(path, change, default):
    """Read-modify-write of a JSON file shared by processes (the loop and the --triaged /
    --queue-add / --resume commands): re-read under <path>.lock, `change(data)` edits it in place,
    written whole. Returns the data written."""
    path = pathlib.Path(path)
    lock = Lock(path.with_name(path.name + ".lock"))
    if not lock.acquire(wait=30):
        raise RuntimeError(f"{path}: still locked after 30 s")
    try:
        data = read_json(path, default)
        change(data)
        write_json(path, data)
        return data
    finally:
        lock.release()
