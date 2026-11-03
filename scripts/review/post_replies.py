"""Post the triage replies for one PR, idempotently.

Bodies are files in <dir>/bodies/ (write them with reply_bodies.py):
  <pr>-<comment id>.md   a reply in the thread of that inline review comment;
  <pr>-<name>.md         any other name (e.g. `summary`, `stack-review`): one issue comment.

A body is skipped when it is recorded in <dir>/replies.json, or, with that file missing, when
GitHub already has it: for a thread, a reply by ukwikidev (`in_reply_to_id`); for an issue
comment, one by ukwikidev carrying the body's marker (`<!-- wlxjury-review:<pr>:<name> -->`,
added on posting) or starting with the body's first line. So replies.json can be deleted and is
rebuilt as replies are posted. 5 s between posts (CodeRabbit answers replies, and its chat
messages per hour are limited).

Usage: python -I post_replies.py PR [--dir target/review/triage] [--dry-run]
"""
import argparse
import os
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I leaves the script's folder out
from common import DEFAULT_DIR, OURS, GitHub, add_common_args, gh_path, read_json, write_json


def marker(pr, name):
    return f"<!-- wlxjury-review:{pr}:{name} -->"


def first_line(text):
    return next((line.strip() for line in text.splitlines() if line.strip() and not line.startswith("<!--")), "")


def main():
    ap = add_common_args(argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter))
    ap.add_argument("pr", help="PR number")
    ap.add_argument("--dir", default=str(DEFAULT_DIR / "triage"), help="triage folder (default %(default)s)")
    ap.add_argument("--dry-run", action="store_true", help="list what would be posted; post nothing")
    a = ap.parse_args()
    pr, d = a.pr, pathlib.Path(a.dir)
    hub = GitHub(gh_path(a.gh), a.repo)
    state_path = d / "replies.json"
    state = read_json(state_path, {})

    threads = {c.get("in_reply_to_id") for c in hub.get_all(f"pulls/{pr}/comments") if c["user"]["login"] == OURS}
    issue = [c["body"] or "" for c in hub.get_all(f"issues/{pr}/comments") if c["user"]["login"] == OURS]

    bodies = d / "bodies"
    files = sorted(f for f in os.listdir(bodies) if f.startswith(pr + "-") and f.endswith(".md")) if bodies.is_dir() else []
    if not files:
        print(f"no bodies for #{pr} in {bodies}")
    first = True
    for f in files:
        name = f[len(pr) + 1:-3]
        key, path = f"{pr}:{name}", bodies / f
        text = path.read_text(encoding="utf-8")
        inline = name.isdigit()
        if key in state:
            print("skip (recorded)", key); continue
        if inline and int(name) in threads:
            print("skip (already on GitHub)", key); continue
        if not inline and any(marker(pr, name) in b or (first_line(text) and b.strip().startswith(first_line(text))) for b in issue):
            print("skip (already on GitHub)", key); continue
        if a.dry_run:
            print("would post", key); continue
        if not inline and marker(pr, name) not in text:
            # the marker lets a later run find this comment without replies.json
            path = d / "bodies-posted" / f
            path.parent.mkdir(parents=True, exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="\n") as out:
                out.write(text.rstrip("\n") + "\n\n" + marker(pr, name) + "\n")
        if not first:
            time.sleep(5)
        first = False
        if inline:
            res = hub.post(f"pulls/{pr}/comments/{name}/replies", path)
        else:
            res = hub.post(f"issues/{pr}/comments", path)
        state[key] = {"id": res["id"], "url": res["html_url"]}
        write_json(state_path, state)
        print("posted", key, res["html_url"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
