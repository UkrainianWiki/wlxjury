"""List one PR's CodeRabbit review threads and whether we have answered them (read-only).

Prints one line per CodeRabbit inline comment that starts a thread: id, file:line, answered or
open, and the comment's first words; then CodeRabbit's reviews with remarks outside the diff
(their body), which get one `summary` reply. With --save, the raw comments and reviews are saved
to <dir>/raw/c<pr>.json and r<pr>.json for writing the triage.

Usage: python -I threads.py PR [--open-only] [--save] [--dir target/review/triage]
"""
import argparse
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I leaves the script's folder out
from common import DEFAULT_DIR, OURS, GitHub, add_common_args, gh_path, write_json


def short(body, n=90):
    t = re.sub(r"\s+", " ", re.sub(r"<[^>]+>|[*_`]", " ", body or "")).strip()
    return t[:n]


def main():
    ap = add_common_args(argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter))
    ap.add_argument("pr", help="PR number")
    ap.add_argument("--open-only", action="store_true", help="only threads without our reply")
    ap.add_argument("--save", action="store_true", help="save the raw JSON under <dir>/raw/")
    ap.add_argument("--dir", default=str(DEFAULT_DIR / "triage"), help="triage folder (default %(default)s)")
    a = ap.parse_args()
    hub = GitHub(gh_path(a.gh), a.repo)
    comments = hub.get_all(f"pulls/{a.pr}/comments")
    reviews = hub.get_all(f"pulls/{a.pr}/reviews")
    if a.save:
        raw = pathlib.Path(a.dir) / "raw"
        write_json(raw / f"c{a.pr}.json", comments)
        write_json(raw / f"r{a.pr}.json", reviews)
    answered = {c.get("in_reply_to_id") for c in comments if c["user"]["login"] == OURS}
    roots = [c for c in comments if "coderabbit" in c["user"]["login"] and not c.get("in_reply_to_id")]
    for c in roots:
        done = c["id"] in answered
        if a.open_only and done:
            continue
        line = c.get("line") or c.get("original_line")
        print(f"{c['id']} {c['path']}:{line} {'answered' if done else 'OPEN'} {short(c['body'])}")
    for r in reviews:
        if "coderabbit" in r["user"]["login"] and re.search(r"outside (the )?diff|Outside diff", r.get("body") or ""):
            print(f"review {r['id']} ({r['submitted_at']}): has remarks outside the diff (one summary reply)")
    print(f"{len(roots)} threads, {sum(c['id'] in answered for c in roots)} answered", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
