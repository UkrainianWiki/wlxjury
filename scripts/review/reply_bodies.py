"""Write the reply bodies for a triaged batch of PRs, and print the triage doc's record rows.

The input is a JSON list, one object per reply (write it by hand or from a script):
  {"pr": 185, "id": 4453227355, "class": 1, "label": "ImageController.scala:90 round ownership",
   "text": "Fixed later in the stack: #206 (`398fc43d`), ..."}
"id" is the inline review comment's id, or a name such as "summary" for one issue comment
(replies to remarks outside the diff). "class" is the triage class (1 fixed later, 2 planned,
3 new entry, 4 not applicable; README.md); "label" is the short text for the record table, and
both may be left out for a summary. The bodies go to <dir>/bodies/<pr>-<id>.md, where
post_replies.py reads them.

Usage: python -I reply_bodies.py REPLIES.json [--dir target/review/triage] [--table]
"""
import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I leaves the script's folder out
from common import DEFAULT_DIR, read_json

CLASSES = {1: "fixed later", 2: "planned", 3: "new entry", 4: "not applicable"}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("replies", help="JSON list of replies")
    ap.add_argument("--dir", default=str(DEFAULT_DIR / "triage"), help="triage folder (default %(default)s)")
    ap.add_argument("--table", action="store_true", help="print one record row per PR")
    a = ap.parse_args()
    replies = read_json(a.replies, None)
    if replies is None:
        raise SystemExit(f"missing {a.replies}")
    bodies = pathlib.Path(a.dir) / "bodies"
    bodies.mkdir(parents=True, exist_ok=True)
    for r in replies:
        if r.get("class") is not None and r["class"] not in CLASSES:
            raise SystemExit(f"#{r['pr']} {r['id']}: unknown class {r['class']}")
        with open(bodies / f"{r['pr']}-{r['id']}.md", "w", encoding="utf-8", newline="\n") as f:
            f.write(r["text"].rstrip("\n") + "\n")
    print(f"{len(replies)} bodies in {bodies}", file=sys.stderr)
    if a.table:
        for pr in sorted({r["pr"] for r in replies}):
            rows = [r for r in replies if r["pr"] == pr and r.get("class") is not None]
            print(f"| #{pr} | " + "; ".join(f"{r['label']}: {CLASSES[r['class']]}" for r in rows) + " |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
