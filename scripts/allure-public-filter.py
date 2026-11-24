#!/usr/bin/env python3
"""Reduce an Allure results directory to what may be published on GitHub Pages.

    python3 scripts/allure-public-filter.py RESULTS_DIR

Kept: the *-result.json and *-container.json files, and image attachments (PNG, JPEG: the
failure screenshots). Everything else is deleted: traces (network logs, page HTML, request
headers), videos, text and JSON attachments (logs, error context), environment.properties,
executor.json and any other file. Failure stack traces (`statusTrace`, `statusDetails.trace`) are
dropped, a failure message (`statusMessage`, `statusDetails.message`) is cut to its first line, and
the `host` label (the runner's name) is removed. The references to a deleted attachment are removed from the
result files, at any depth of their steps. Exit code 1 if the directory is missing or holds no
result file, so an empty report is never published.
"""
import json
from pathlib import Path
import sys

KEEP_TYPES = {"image/png", "image/jpeg"}
KEEP_SUFFIXES = {".png", ".jpg", ".jpeg"}


def _first_line(text):
    return text.split("\n", 1)[0] if isinstance(text, str) else text


def _scrub(node):
    """Drop stack traces, cut messages to one line and drop the host label, at any depth."""
    if isinstance(node, dict):
        node.pop("statusTrace", None)
        node.pop("trace", None)
        for key in ("statusMessage", "message"):
            if key in node:
                node[key] = _first_line(node[key])
        labels = node.get("labels")
        if isinstance(labels, list):
            node["labels"] = [l for l in labels if not (isinstance(l, dict) and l.get("name") == "host")]
        for value in node.values():
            _scrub(value)
    elif isinstance(node, list):
        for item in node:
            _scrub(item)


def _filter_attachments(node, kept):
    """Drop attachments that are not images, in this node and its steps; return the count dropped."""
    dropped = 0
    if isinstance(node, dict):
        attachments = node.get("attachments")
        if isinstance(attachments, list):
            keep = [a for a in attachments
                    if isinstance(a, dict) and a.get("type") in KEEP_TYPES
                    and Path(str(a.get("source", ""))).suffix.lower() in KEEP_SUFFIXES]
            dropped += len(attachments) - len(keep)
            node["attachments"] = keep
            kept.update(a["source"] for a in keep)
        for value in node.values():
            if isinstance(value, (dict, list)):
                dropped += _filter_attachments(value, kept)
    elif isinstance(node, list):
        for item in node:
            dropped += _filter_attachments(item, kept)
    return dropped


def filter_results(directory):
    """Filter the directory in place; return (results, attachments kept, files deleted)."""
    directory = Path(directory)
    if not directory.is_dir():
        raise SystemExit(f"allure-public-filter: {directory} is not a directory")
    kept = set()
    results = 0
    for path in sorted(directory.glob("*-result.json")) + sorted(directory.glob("*-container.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        _filter_attachments(data, kept)
        _scrub(data)
        path.write_text(json.dumps(data), encoding="utf-8")
        results += path.name.endswith("-result.json")
    deleted = 0
    for path in sorted(directory.rglob("*")):
        if not path.is_file():
            continue
        is_json = path.parent == directory and path.name.endswith(("-result.json", "-container.json"))
        if is_json or (path.parent == directory and path.name in kept):
            continue
        path.unlink()
        deleted += 1
    return results, len(kept), deleted


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    results, kept, deleted = filter_results(argv[1])
    print(f"allure-public-filter: {results} results, {kept} image attachments kept, {deleted} files deleted")
    return 0 if results else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
