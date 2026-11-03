"""Rewrite stack commit ids in our posted replies on one PR, after the stack was rebuilt.

Reads every comment by ukwikidev on the PR from GitHub (thread replies and issue comments; no
local state needed) and replaces each backticked short id `old` with `new` from the map. Only
comments that change are edited, 5 s apart; the new bodies are saved under <dir>/bodies-v2/.

The map is JSON: {"prs": [{"old_tip": SHA, "new_tip": SHA}, ...],
                  "commits": [{"old": SHA, "new": SHA}, ...]} (either list may be empty).
Ids are compared on their first 8 characters, as the replies cite them. The map is applied in
one pass over the original body, so A->B and B->C turn A into B (not C) and B into C.

Edits only with --post (or --write); without it (or with --dry-run) it lists what would change.

Usage: python -I edit_replies.py PR MAP.json [--dir target/review/triage] [--post]
"""
import argparse
import pathlib
import re
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I leaves the script's folder out
from common import DEFAULT_DIR, OURS, GitHub, add_common_args, gh_path, read_json


def rewrite(body, sub):
    """Replace each backticked id in `sub` with its new id, in one pass over `body`."""
    if not sub:
        return body
    pattern = re.compile("`(" + "|".join(re.escape(o) for o in sub) + ")`")
    return pattern.sub(lambda m: f"`{sub[m.group(1)]}`", body)


def main():
    ap = add_common_args(argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter))
    ap.add_argument("pr", nargs="?", help="PR number")
    ap.add_argument("map", nargs="?", help="old-to-new commit map (JSON)")
    ap.add_argument("--dir", default=str(DEFAULT_DIR / "triage"), help="where the new bodies go (default %(default)s)")
    ap.add_argument("--post", "--write", dest="write", action="store_true",
                    help="edit the comments on GitHub; without it, only list what would change")
    ap.add_argument("--dry-run", action="store_true", help="the default: list what would change, edit nothing")
    ap.add_argument("--self-test", action="store_true", help="check the rewrite on a sample and exit")
    a = ap.parse_args()
    if a.self_test:
        got = rewrite("`aaaaaaaa` then `bbbbbbbb`", {"aaaaaaaa": "bbbbbbbb", "bbbbbbbb": "cccccccc"})
        want = "`bbbbbbbb` then `cccccccc`"
        print("ok" if got == want else f"FAIL: got {got!r}, want {want!r}")
        return 0 if got == want else 1
    if a.pr is None or a.map is None:
        ap.error("PR and MAP are required")
    if a.write and a.dry_run:
        ap.error("--post and --dry-run contradict each other")
    hub = GitHub(gh_path(a.gh), a.repo)
    m = read_json(a.map, None)
    if m is None:
        raise SystemExit(f"missing map {a.map}")
    sub = {p["old_tip"][:8]: p["new_tip"][:8] for p in m.get("prs", [])}
    sub.update({c["old"][:8]: c["new"][:8] for c in m.get("commits", []) if "old" in c and "new" in c})

    ours = [("pulls/comments", c) for c in hub.get_all(f"pulls/{a.pr}/comments") if c["user"]["login"] == OURS]
    ours += [("issues/comments", c) for c in hub.get_all(f"issues/{a.pr}/comments") if c["user"]["login"] == OURS]
    out_dir = pathlib.Path(a.dir) / "bodies-v2"
    first = True
    for kind, c in ours:
        body = c["body"] or ""
        new = rewrite(body, sub)
        label = f"{a.pr}:{kind.split('/')[0]}:{c['id']}"
        if new == body:
            print("unchanged", label); continue
        if not a.write:
            print("would edit", label, c["html_url"]); continue
        out_dir.mkdir(parents=True, exist_ok=True)
        path = out_dir / f"{a.pr}-{c['id']}.md"
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(new)
        if not first:
            time.sleep(5)
        first = False
        r = hub.patch(f"{kind}/{c['id']}", path)
        print("edited", label, r["html_url"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
