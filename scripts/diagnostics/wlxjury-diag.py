#!/usr/bin/env python3
"""wlxjury-diag: what happened and what could be wrong, for the maintainer over ssh (support
diagnostics plan, docs/plans/2026-11-18-support-diagnostics.md §3.4; D-1b). Run as an ordinary
account on the server, no sudo, no database login. Standard library only.

  wlxjury-diag recent [--since 24h]        contests with activity in the window, the worst first
  wlxjury-diag contest 466 [--verbose]     a contest's health report
  wlxjury-diag reuse [466] [--verbose]     the reuse dry-run report: the latest scan, or a contest's clusters

It reads the app's own API on loopback with a personal API token of the read-only, server-only
scope (created on the profile page, kept in ~/.config/wlxjury-diag/token, mode 600), and the app
log (/var/log/wlxjury/application.log*, gzip included) for the denials. Every command prints a few
lines and ends with exactly one next command, filled with the ids it found.

Settings: --url (default http://127.0.0.1:9000, loopback only: the token is never sent anywhere
else), the jury host name the app answers to (--host, else $WLXJURY_DIAG_HOST, else
~/.config/wlxjury-diag/host), the token file (--token-file, else $WLXJURY_DIAG_TOKEN_FILE), the
log directory (--log-dir). A command is a function in COMMANDS, with its parser and runner, and
reads an endpoint under /api (the contest series plan's `reuse`: /api/diagnostics/reuse).
"""
import argparse
import datetime
import glob
import gzip
import ipaddress
import json
import os
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request

CONFIG_DIR = os.path.join(os.path.expanduser("~"), ".config", "wlxjury-diag")
DEFAULT_URL = "http://127.0.0.1:9000"
DEFAULT_LOG_DIR = "/var/log/wlxjury"
TIMEOUT = 60
OUTCOMES = ["pass", "info", "warn", "fail"]


class DiagError(Exception):
    """A problem to report in one line, with exit code 2."""


# ── text ────────────────────────────────────────────────────────────────────

# C0 and C1 controls, the line and paragraph separators, and the bidirectional controls that reorder a terminal line
_CONTROL = re.compile("[\x00-\x1f\x7f-\x9f\u061c\u200e\u200f\u2028\u2029\u202a-\u202e\u2066-\u2069]")


def clean(value):
    """The app's text (contest names, sources, hint texts) as printable: control characters
    replaced by a space."""
    return _CONTROL.sub(" ", str(value))


def parse_since(text):
    """`24h`, `3d` or a number of hours: the window in hours, 1 to 720 (the app's bounds)."""
    m = re.fullmatch(r"\s*(\d+)\s*([hd]?)\s*", text or "")
    if not m:
        raise argparse.ArgumentTypeError("use hours or days, e.g. 24h or 3d")
    hours = int(m.group(1)) * (24 if m.group(2) == "d" else 1)
    if not 1 <= hours <= 720:
        raise argparse.ArgumentTypeError("the window is 1 hour to 30 days")
    return hours


# ── the app's API ───────────────────────────────────────────────────────────

def check_url(url):
    """The base URL, if it is plain HTTP to a literal loopback address: the token never leaves
    the server (the app would refuse it from anywhere else anyway)."""
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname or ""
    try:
        loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        loopback = False
    if parts.scheme != "http" or not loopback or parts.path not in ("", "/") or parts.query or parts.username:
        raise DiagError(f"--url must be http:// to a loopback address, e.g. {DEFAULT_URL}")
    return url.rstrip("/")


def read_token(path):
    """The token from its file, which only its owner may read (mode 600)."""
    try:
        info = os.stat(path)
    except OSError:
        raise DiagError(f"no token file at {path}: create a read-only, server-only API token on your "
                        "profile page and save it there (mode 600)") from None
    if os.name == "posix" and info.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise DiagError(f"{path} is readable by others: chmod 600 {path}")
    with open(path, encoding="utf-8") as f:
        token = f.read().strip()
    if not re.fullmatch(r"wlxj_[A-Za-z0-9_-]{43}", token):
        raise DiagError(f"{path} doesn't hold an API token (wlxj_...)")
    return token


def read_host(args):
    host = args.host or os.environ.get("WLXJURY_DIAG_HOST")
    if not host:
        path = os.path.join(CONFIG_DIR, "host")
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                host = f.read().strip()
    if not host or not re.fullmatch(r"[A-Za-z0-9.-]+(:\d+)?", host):
        raise DiagError("name the jury host the app answers to: --host, $WLXJURY_DIAG_HOST or "
                        f"{os.path.join(CONFIG_DIR, 'host')} (one of WLXJURY_ALLOWED_HOSTS)")
    return host


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Never follow a redirect: it would carry the token elsewhere."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def build_opener():
    """The opener of every request: no proxy, whatever http_proxy / HTTP_PROXY say (the token
    would go to the proxy), and no redirect."""
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect)


class Api:
    def __init__(self, url, host, token, opener=None):
        self.url, self.host, self.token = url, host, token
        self.opener = opener or build_opener()

    def get(self, path, query=None):
        """The JSON of a GET of `path` under /api, or a DiagError naming the status."""
        target = self.url + "/api/" + path.lstrip("/")
        if query:
            target += "?" + urllib.parse.urlencode(query)
        request = urllib.request.Request(target, headers={
            "Authorization": "Bearer " + self.token, "Host": self.host, "Accept": "application/json",
            "Accept-Language": "en", "User-Agent": "wlxjury-diag"})
        try:
            with self.opener.open(request, timeout=TIMEOUT) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise DiagError(api_error(e.code, e.read())) from None
        except (urllib.error.URLError, OSError) as e:
            raise DiagError(f"the app at {self.url} didn't answer ({clean(getattr(e, 'reason', e))})") from None


def api_error(status, body):
    try:
        data = json.loads(body.decode("utf-8"))
    except (ValueError, UnicodeDecodeError, AttributeError):
        data = {}
    code = clean(data.get("error", "")) if isinstance(data, dict) else ""
    reference = clean(data.get("reference", "")) if isinstance(data, dict) else ""
    hints = {
        401: "the token is unknown, revoked or expired: create a new one on your profile page",
        403: "refused" + (f" (reference {reference}: grep {reference} {DEFAULT_LOG_DIR}/application.log)" if reference else ""),
        404: "not found",
        400: "the app refused the request (a Host it doesn't answer to?)",
    }
    return f"HTTP {status} {code}: {hints.get(status, 'unexpected answer')}"


# ── the log ─────────────────────────────────────────────────────────────────

LOG_TIME = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")
DENIAL = re.compile(r"\bauthorization_denied\b.*?\btargetContestId=(\d+)\b")
ROUND_REFUSED = re.compile(r"\bround_access_refused\b.*?\bcontestId=(\d+)\b")
ROTATED = re.compile(r"application\.(\d{4}-\d{2}-\d{2})\.\d+\.log(\.gz)?$")


def log_files(log_dir, cutoff):
    """The app log and its rotated files of the window (a rotated file of a day before the
    cutoff's is skipped), oldest first."""
    files = []
    for path in glob.glob(os.path.join(log_dir, "application*.log*")):
        m = ROTATED.search(os.path.basename(path))
        if m:
            day = datetime.date.fromisoformat(m.group(1))
            if day < cutoff.date():
                continue
        elif os.path.basename(path) != "application.log":
            continue
        files.append(path)
    return sorted(files, key=lambda p: (os.path.basename(p) == "application.log", p))


def read_lines(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            yield line


def denials(lines, cutoff):
    """Denials by contest id in the window: `authorization_denied` by its target contest and a
    juror's `round_access_refused`, from lines with the log's time (the server's local time) at
    or after `cutoff`. A line without a time (a stack trace) is skipped."""
    counts = {}
    for line in lines:
        t = LOG_TIME.match(line)
        if not t:
            continue
        try:
            when = datetime.datetime.strptime(t.group(1), "%Y-%m-%d %H:%M:%S")
        except ValueError:
            continue
        if when < cutoff:
            continue
        m = DENIAL.search(line) or ROUND_REFUSED.search(line)
        if m:
            key = int(m.group(1))
            counts[key] = counts.get(key, 0) + 1
    return counts


def log_denials(log_dir, hours, now=None):
    """Denials by contest id in the last `hours`, and the log files that couldn't be read."""
    cutoff = (now or datetime.datetime.now()) - datetime.timedelta(hours=hours)
    counts, unreadable = {}, []
    for path in log_files(log_dir, cutoff):
        try:
            for key, n in denials(read_lines(path), cutoff).items():
                counts[key] = counts.get(key, 0) + n
        except OSError:
            unreadable.append(path)
    if not glob.glob(os.path.join(log_dir, "application*.log*")):
        unreadable.append(os.path.join(log_dir, "application.log"))
    return counts, unreadable


# ── recent ──────────────────────────────────────────────────────────────────

def worse(a, b):
    return a if OUTCOMES.index(a) >= OUTCOMES.index(b) else b


def contest_name(ref):
    if ref.get("name") is None:
        return f"contest {ref['id']} (not found)"
    retired = " [retired]" if ref.get("retired") else ""
    return clean(f"{ref['name']} {ref.get('year', '')} {ref.get('country', '')}".strip()) + retired


def recent_rows(report, denial_counts):
    """The contests of the app's report and of the log's denials as (outcome, id, name, details),
    the worst first: a denial counts as a warning."""
    rows = {}
    for c in report.get("contests", []):
        ref = c["contest"]
        jobs = c.get("jobs", [])
        failed = [j for j in jobs if j["status"] == "failed" and not j.get("superseded")]
        details = []
        if jobs:
            details.append(f"{len(jobs)} job{'s' if len(jobs) != 1 else ''}")
        for j in failed[:3]:
            ref_text = f" ref {clean(j['reference'])}" if j.get("reference") else ""
            details.append(f"{clean(j['kind'])} #{j['id']} failed: {clean(j.get('error') or '?')}{ref_text}")
        changes = c.get("roundChanges", [])
        if changes:
            details.append("round status " + ", ".join(f"{r['number']}->{clean(r['status'])}" for r in changes[:5]))
        rows[ref["id"]] = [c["outcome"], ref["id"], contest_name(ref), details]
    for contest_id, n in denial_counts.items():
        row = rows.setdefault(contest_id, ["pass", contest_id, f"contest {contest_id} (no app activity)", []])
        row[0] = worse(row[0], "warn")
        row[3].append(f"{n} denial{'s' if n != 1 else ''} in the log")
    order = {id_: i for i, id_ in enumerate(c["contest"]["id"] for c in report.get("contests", []))}
    return sorted(rows.values(), key=lambda r: (-OUTCOMES.index(r[0]), order.get(r[1], len(order)), r[1]))


def recent_lines(report, denial_counts, unreadable, hours, limit=15, log_dir=DEFAULT_LOG_DIR):
    rows = recent_rows(report, denial_counts)
    out = [f"Activity in the last {hours} h: {len(rows)} contest{'s' if len(rows) != 1 else ''} "
           "(jobs and round status changes from the app, denials from the log)"]
    for outcome, contest_id, name, details in rows[:limit]:
        out.append(f"{outcome.upper():4} {contest_id} {name}: " + ("; ".join(details) or "activity"))
    if len(rows) > limit:
        out.append(f"... and {len(rows) - limit} more")
    other = [j for j in report.get("otherJobs", []) if j["status"] == "failed" and not j.get("superseded")]
    if other:
        out.append("Jobs of no contest failed: " + ", ".join(f"{clean(j['kind'])} #{j['id']}" for j in other[:5]))
    for path in unreadable:
        out.append(f"(log not read: {path})")
    worst = next((r for r in rows if r[0] in ("fail", "warn")), rows[0] if rows else None)
    if worst:
        out.append(f"wlxjury-diag contest {worst[1]}")
    elif hours < 720:
        out.append(f"wlxjury-diag recent --since {min(hours * 3, 720)}h")
    else:
        # the widest window was empty: the log's latest lines, not the same command again
        out.append(f"tail -n 20 {log_dir}/application.log")
    return out


def run_recent(args, api):
    report = api.get("diagnostics/recent", {"hours": args.since})
    counts, unreadable = log_denials(args.log_dir, args.since)
    return recent_lines(report, counts, unreadable, args.since, log_dir=args.log_dir)


# ── contest ─────────────────────────────────────────────────────────────────

def contest_lines(health, verbose=False, names=False):
    ref = health["contest"]
    rounds = health.get("rounds", [])
    out = [f"Contest {ref['id']}: {contest_name(ref)}, {health.get('files', 0)} files, "
           f"{len(rounds)} round{'s' if len(rounds) != 1 else ''}: {health['outcome'].upper()}"]
    checks = sorted(health.get("checks", []), key=lambda c: -OUTCOMES.index(c["outcome"]))
    shown = [c for c in checks if verbose or c["outcome"] in ("warn", "fail")]
    for c in shown:
        for h in c.get("hints", []):
            if verbose or h["outcome"] in ("warn", "fail"):
                out.append(f"{h['outcome'].upper():4} {c['code']} {clean(c['title'])}: {clean(h['text'])}")
    quiet = len(checks) - len(shown)
    if quiet:
        out.append(f"{quiet} check{'s' if quiet != 1 else ''} without a warning")
    if verbose:
        for r in rounds:
            flagged = f" flagged {','.join(r['flagged'])}" if r.get("flagged") else ""
            out.append(f"  round {r['number']} (id {r['id']}): {clean(r['status'])}, {r['files']} files, "
                       f"{r['jurors']} jurors{flagged}")
        for i in health.get("imports", []):
            who = f" by {clean(i['creator'])}" if names and i.get("creator") else ""
            out.append(f"  import #{i['jobId']} {clean(i['status'])} {clean(i['source'])!r}: seen {i['seen']}, "
                       f"inserted {i['inserted']}, linked {i['linked']}, not in countries {i['notInCountries']}{who}")
    # D-1c's `log --contest` takes this line's place
    out.append(f"wlxjury-diag contest {ref['id']} --verbose" if not verbose
               else f"grep -h 'targetContestId={ref['id']} ' {DEFAULT_LOG_DIR}/application.log | tail -20")
    return out


def run_contest(args, api):
    return contest_lines(api.get(f"contests/{args.id}/health"), args.verbose, args.names)


# ── reuse ───────────────────────────────────────────────────────────────────

VERDICTS = ["choose", "move", "leave", "signs", "clean"]
ACTIONS = ["move", "choose", "leave", "stay"]
REUSE_PAGE = "/admin/contests/reuse"


def counts(mapping, order):
    """`{"move": 2, "stay": 1}` as "move 2, stay 1", in the order given, then any others."""
    keys = [k for k in order if mapping.get(k)] + sorted(k for k in mapping if k not in order and mapping[k])
    return ", ".join(f"{clean(k)} {mapping[k]}" for k in keys)


def reuse_lines(report, limit=15):
    scan, totals = report.get("scan"), report.get("totals")
    if not scan:
        return ["No reuse scan yet: root starts one on the jury site's " + REUSE_PAGE + ".",
                "wlxjury-diag reuse"]
    finished = f", finished {clean(scan['finishedAt'])}" if scan.get("finishedAt") else ""
    phase = "pass 1 of 2 (eras)" if scan.get("phase") == "eras" else "pass 2 of 2 (reports)"
    out = [f"Reuse scan #{scan['jobId']}: {clean(scan['status'])}, {phase}, {scan['done']} of {scan['total']} contests"
           f"{finished}"]
    results = report.get("resultsOf")
    if results is None:
        out.append("No finished scan yet: the totals come with the first.")
        out.append("wlxjury-diag reuse")
        return out
    if results != scan["jobId"]:
        out.append(f"The results below are scan #{results}'s, the latest finished.")
    t = totals or {}
    out.append(f"{t.get('scanned', 0)} contests scanned, {t.get('clean', 0)} with nothing to report; "
               f"{counts(t.get('verdicts', {}), VERDICTS) or 'none reported'}")
    out.append(f"clusters: {counts(t.get('kinds', {}), ['own', 'r1', 'r2', 'r4', 'unknown']) or 'none'}; "
               f"{counts(t.get('actions', {}), ACTIONS) or 'no proposals'}; R3 mixed {t.get('mixed', 0)}, "
               f"empty {t.get('empty', 0)}")
    out.append(f"{t.get('moves', 0)} moves, {t.get('r4Hosts', 0)} R4 hosts, {t.get('activeToMove', 0)} active rounds to "
               f"move, {t.get('filesToLink', 0)} files to link, {t.get('jurorsToAdd', 0)} jurors to add, "
               f"{t.get('unusedInSource', 0)} files left to moved rounds only")
    rows = sorted(report.get("contests", []),
                  key=lambda c: (VERDICTS.index(c["verdict"]) if c["verdict"] in VERDICTS else len(VERDICTS),
                                 -c.get("moves", 0), c["contestId"]))
    for c in rows[:limit]:
        name = clean(f"{c['name']} {c['year']} {c['country']}")
        flags = (" [R4 host]" if c.get("r4Host") else "") + (" [retired]" if c.get("retired") else "")
        active = f", active {', '.join(str(r) for r in c['activeToMove'])}" if c.get("activeToMove") else ""
        out.append(f"{c['verdict'].upper():6} {c['contestId']} {name}: {c['clusters']} clusters "
                   f"({counts(c.get('actions', {}), ACTIONS)}), {c.get('moves', 0)} moves{active}{flags}")
    if len(rows) > limit:
        out.append(f"... and {len(rows) - limit} more")
    if scan["status"] in ("queued", "running") and not rows:
        out.append("wlxjury-diag reuse")
    elif rows:
        out.append(f"wlxjury-diag reuse {rows[0]['contestId']}")
    else:
        out.append("wlxjury-diag recent")
    return out


def reuse_contest_lines(report, verbose=False):
    ref = report["contest"]
    clusters = report.get("clusters", [])
    flags = " [R4 host]" if report.get("r4Host") else ""
    out = [f"Contest {ref['id']}: {contest_name(ref)}: {report['verdict'].upper()}, {len(clusters)} cluster"
           f"{'s' if len(clusters) != 1 else ''}, {report.get('moves', 0)} moves, {report.get('jurorsToAdd', 0)} jurors to "
           f"add, {report.get('unusedInSource', 0)} files left to moved rounds only{flags}"]
    if not report.get("eras"):
        out.append("(no scan has measured the eras yet: S1 and S3 not shown)")
    for c in clusters:
        rounds = c.get("rounds", [])
        span = f"{rounds[0]['id']}-{rounds[-1]['id']}" if len(rounds) > 1 else (str(rounds[0]["id"]) if rounds else "-")
        b = c["buckets"]
        target = ""
        if c.get("target"):
            t = c["target"]
            where = ("contest " + ", ".join(f"#{i}" for i in t["contests"])) if t.get("contests") else "a new contest"
            target = f" -> {clean(t['seriesName'])} {t['year']} ({where})"
        notes = []
        if c.get("mixed"):
            notes.append("R3 mixed")
        if c.get("empty"):
            notes.append("empty")
        if c.get("activeRounds"):
            notes.append("active " + ", ".join(str(r) for r in c["activeRounds"]))
        if c.get("disagreements"):
            notes.append("disagree " + ", ".join(clean(d) for d in c["disagreements"]))
        note = f" [{'; '.join(notes)}]" if notes else ""
        out.append(f"  {c['index']:>2} rounds {span} ({len(rounds)}): {c['action'].upper()} {clean(c['kind'])} "
                   f"{clean(c['identity'])}{target}; files {b['matching']}/{b['foreign']}/{b['unclassified']}, "
                   f"jurors {c['jurors']['total']}{note}")
        if verbose:
            s = c.get("signals", {})
            named = "; ".join(f"{clean(n['identity'])} ({n['files']})" for n in s.get("s2", [])) or "-"
            filters = "; ".join(f"{r['round']}: {clean(r['source'])}" for r in s.get("s5r", [])) or "-"
            out.append(f"       S5r {filters} | S2 {named} | S1 {s.get('s1Year') or '-'} | S3 {s.get('s3Year') or '-'}")
            j = c["jurors"]
            out.append(f"       jurors: {j['toAdd']} to add, {j['deleted']} deleted, {j['protectedAccounts']} protected, "
                       f"{j['conflict']} with another role there, {j['already']} there already, {j['notHostMembers']} "
                       f"not members here; ran by {c['runners']['total']} ({c['runners']['hostAdmins']} admins here)")
    out.append(f"wlxjury-diag reuse {ref['id']} --verbose" if not verbose else f"wlxjury-diag contest {ref['id']}")
    return out


def run_reuse(args, api):
    if args.id is None:
        return reuse_lines(api.get("diagnostics/reuse"))
    return reuse_contest_lines(api.get(f"diagnostics/reuse/{args.id}"), args.verbose)


# ── the commands ────────────────────────────────────────────────────────────

def add_recent(p):
    p.add_argument("--since", type=parse_since, default=24, help="the window: hours or days, e.g. 24h, 3d (24h)")
    p.add_argument("--log-dir", default=DEFAULT_LOG_DIR, help=f"the app log's directory ({DEFAULT_LOG_DIR})")


def add_contest(p):
    p.add_argument("id", type=int, help="the contest's id")
    p.add_argument("--verbose", action="store_true", help="every check, round and import")
    p.add_argument("--names", action="store_true", help="with --verbose: who started each import")


def add_reuse(p):
    p.add_argument("id", type=int, nargs="?", help="a contest's id: its clusters (else the latest scan)")
    p.add_argument("--verbose", action="store_true", help="with an id: each cluster's signals and jurors")


COMMANDS = {
    "recent": (add_recent, run_recent, "contests with activity in the window, the worst first"),
    "contest": (add_contest, run_contest, "a contest's health report"),
    "reuse": (add_reuse, run_reuse, "the reuse dry-run report: the latest scan, or a contest's clusters"),
}


def parser():
    p = argparse.ArgumentParser(prog="wlxjury-diag", description="WLX Jury support diagnostics (read-only)")
    p.add_argument("--url", default=DEFAULT_URL, help=f"the app, on loopback ({DEFAULT_URL})")
    p.add_argument("--host", help="the jury host name to send (else $WLXJURY_DIAG_HOST, else ~/.config/wlxjury-diag/host)")
    p.add_argument("--token-file", default=os.environ.get("WLXJURY_DIAG_TOKEN_FILE", os.path.join(CONFIG_DIR, "token")))
    sub = p.add_subparsers(dest="command", required=True)
    for name, (add, _, help_text) in COMMANDS.items():
        add(sub.add_parser(name, help=help_text))
    return p


def main(argv=None, api=None):
    args = parser().parse_args(argv)
    try:
        if api is None:
            api = Api(check_url(args.url), read_host(args), read_token(args.token_file))
        for line in COMMANDS[args.command][1](args, api):
            print(line)
        return 0
    except DiagError as e:
        print(f"wlxjury-diag: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
