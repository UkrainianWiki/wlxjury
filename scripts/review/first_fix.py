"""Find the first stacked PR whose tip no longer has a reported problem (triage class 1).

Walks the stack's PR tips in order from --from (a PR number), reads PATH at each tip and prints
only the transitions: `bug` (BUG_REGEX matches), `nobug`, `absent` (no such file), and with
--fixed-regex `bug+fix` / `nobug-nofix`. The first `nobug` after `bug` names the PR that fixed
it, which the reply cites ("Fixed later in the stack: #N (`sha`)").

The stack order is read from GitHub (open PRs, following each base branch from master) and the
tips from `origin/<head branch>` in --git (fetch first). Regexes are Python, multiline and dotall.

Usage: python -I first_fix.py --from 185 PATH BUG_REGEX [--fixed-regex R] [--git C:/scala/wlxjury-refresh]
"""
import argparse
import re
import subprocess
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I leaves the script's folder out
from common import ROOT, GitHub, add_common_args, gh_path, stack_order


def main():
    ap = add_common_args(argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter))
    ap.add_argument("path", help="file path in the tree")
    ap.add_argument("bug", help="regex that matches while the problem is there")
    ap.add_argument("--from", dest="start", type=int, default=0, help="first PR number to check")
    ap.add_argument("--fixed-regex", default=None, help="regex that matches once it is fixed")
    ap.add_argument("--git", default=str(ROOT), help="a clone with origin/stack/* fetched (default: this one)")
    a = ap.parse_args()
    bug = re.compile(a.bug, re.S | re.M)
    fixed = re.compile(a.fixed_regex, re.S | re.M) if a.fixed_regex else None
    stack = stack_order(GitHub(gh_path(a.gh), a.repo).pulls())
    prev = None
    for i, p in enumerate(stack, 1):
        if p["number"] < a.start:
            continue
        tip = subprocess.run(["git", "-C", a.git, "rev-parse", f"origin/{p['headRefName']}"],
                             capture_output=True, text=True).stdout.strip()
        if not tip:
            print(f"{i:02d} #{p['number']} origin/{p['headRefName']} not fetched")
            prev = None
            continue
        r = subprocess.run(["git", "-C", a.git, "show", f"{tip}:{a.path}"], capture_output=True)
        if r.returncode:
            st = "absent"
        else:
            txt = r.stdout.decode("utf-8", "replace")
            st = "bug" if bug.search(txt) else "nobug"
            if fixed and st == "nobug" and not fixed.search(txt):
                st = "nobug-nofix"
            if fixed and st == "bug" and fixed.search(txt):
                st = "bug+fix"
        if st != prev:
            print(f"{i:02d} #{p['number']} {tip[:8]} {st}")
        prev = st
    return 0


if __name__ == "__main__":
    sys.exit(main())
