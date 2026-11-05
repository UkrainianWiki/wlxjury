#!/usr/bin/env python3
"""Print a Markdown summary of the Scala (scoverage) and JS (Jest) coverage reports.

CI's coverage job appends the output to $GITHUB_STEP_SUMMARY. Missing reports are listed
as missing, not as an error: the job's test step already decides whether the run failed.

    python3 scripts/coverage-summary.py [--scoverage PATH] [--jest PATH]
        [--json PATH --head-sha SHA [--pr NUMBER]]

With --json it also writes the totals the PR comment shows (coverage-comment.yml reads them
with scripts/coverage_comment.py, which validates the same format):

    {"version": 1, "head_sha": "<40 hex>", "pr": 263 or null,
     "scala": {"statements": 90.59, "branches": 86.7} or null,
     "js": {"lines": 96.8, "branches": 91.58} or null}

A section is null when its report is missing or unreadable.
"""
import argparse
import json
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

SCOVERAGE = Path("target/scala-2.13/scoverage-report/scoverage.xml")
JEST = Path("target/jest-coverage/coverage-summary.json")
SHA_RE = re.compile(r"\A[0-9a-f]{40}\Z")
READ_ERRORS = (OSError, ET.ParseError, KeyError, TypeError, ValueError, json.JSONDecodeError)


def scala_rows(path):
    """Statement and branch coverage from scoverage.xml's root element."""
    root = ET.parse(path).getroot()
    total = int(root.get("statement-count", 0))
    invoked = int(root.get("statements-invoked", 0))
    return [
        ("Statements", float(root.get("statement-rate", 0)), f"{invoked} / {total}"),
        ("Branches", float(root.get("branch-rate", 0)), ""),
    ]


def jest_rows(path):
    """Lines, statements, functions and branches from Jest's json-summary."""
    total = json.loads(path.read_text(encoding="utf-8"))["total"]
    rows = []
    for key in ("lines", "statements", "functions", "branches"):
        metric = total[key]
        pct = metric["pct"] if isinstance(metric["pct"], (int, float)) else 0.0
        rows.append((key.capitalize(), float(pct), f"{metric['covered']} / {metric['total']}"))
    return rows


def table(title, path, read):
    out = [f"### {title}", ""]
    if not path.is_file():
        return out + [f"No report at `{path}`.", ""]
    try:
        rows = read(path)
    except READ_ERRORS as error:
        return out + [f"Could not read `{path}`: {type(error).__name__}.", ""]
    out += ["| Metric | Coverage | Covered / total |", "|---|---:|---:|"]
    out += [f"| {name} | {pct:.2f}% | {counts} |" for name, pct, counts in rows]
    return out + [""]


def _section(path, read, wanted):
    """The wanted metrics (row name -> JSON key) of one report as percentages, or None."""
    if not path.is_file():
        return None
    try:
        rows = {name: pct for name, pct, _ in read(path)}
        section = {key: round(float(rows[name]), 2) for name, key in wanted}
    except READ_ERRORS:
        return None
    if not all(math.isfinite(v) and 0 <= v <= 100 for v in section.values()):
        return None
    return section


def summary_data(scoverage, jest, head_sha, pr):
    """The --json document. head_sha and pr are checked as the comment's parser checks them."""
    if not SHA_RE.match(head_sha or ""):
        raise ValueError("--head-sha must be a commit SHA (40 lowercase hex digits)")
    if pr is not None and (type(pr) is not int or pr < 1):
        raise ValueError("--pr must be a positive number")
    return {
        "version": 1,
        "head_sha": head_sha,
        "pr": pr,
        "scala": _section(scoverage, scala_rows, [("Statements", "statements"), ("Branches", "branches")]),
        "js": _section(jest, jest_rows, [("Lines", "lines"), ("Branches", "branches")]),
    }


def _pr_number(text):
    """--pr's value: empty (a push or a manual run) is no PR."""
    if text == "":
        return None
    if not re.fullmatch(r"[1-9][0-9]{0,8}", text):
        raise argparse.ArgumentTypeError("a PR number, or empty")
    return int(text)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--scoverage", type=Path, default=SCOVERAGE)
    parser.add_argument("--jest", type=Path, default=JEST)
    parser.add_argument("--json", type=Path, help="also write the totals as JSON to this file")
    parser.add_argument("--head-sha", default="", help="the commit measured (with --json)")
    parser.add_argument("--pr", type=_pr_number, default=None, help="the PR number, or empty")
    args = parser.parse_args(argv)
    if args.json is not None:
        try:
            data = summary_data(args.scoverage, args.jest, args.head_sha, args.pr)
        except ValueError as error:
            parser.error(str(error))
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(data, sort_keys=True) + "\n", encoding="utf-8")
    lines = ["## Test coverage", ""]
    lines += table("Scala (scoverage)", args.scoverage, scala_rows)
    lines += table("JavaScript (Jest, V8)", args.jest, jest_rows)
    lines.append("Reports: artifact `coverage-reports-*` (scoverage and Cobertura XML, lcov and its HTML); "
                 "on pushes and manual runs also `coverage-html-*` (the Scala HTML report).")
    sys.stdout.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
