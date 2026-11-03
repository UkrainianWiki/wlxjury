#!/usr/bin/env python3
"""Print a Markdown summary of the Scala (scoverage) and JS (Jest) coverage reports.

CI's coverage job appends the output to $GITHUB_STEP_SUMMARY. Missing reports are listed
as missing, not as an error: the job's test step already decides whether the run failed.

    python3 scripts/coverage-summary.py [--scoverage PATH] [--jest PATH]
"""
import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

SCOVERAGE = Path("target/scala-2.13/scoverage-report/scoverage.xml")
JEST = Path("target/jest-coverage/coverage-summary.json")


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
    except (ET.ParseError, KeyError, ValueError, json.JSONDecodeError) as error:
        return out + [f"Could not read `{path}`: {type(error).__name__}.", ""]
    out += ["| Metric | Coverage | Covered / total |", "|---|---:|---:|"]
    out += [f"| {name} | {pct:.2f}% | {counts} |" for name, pct, counts in rows]
    return out + [""]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--scoverage", type=Path, default=SCOVERAGE)
    parser.add_argument("--jest", type=Path, default=JEST)
    args = parser.parse_args(argv)
    lines = ["## Test coverage", ""]
    lines += table("Scala (scoverage)", args.scoverage, scala_rows)
    lines += table("JavaScript (Jest, V8)", args.jest, jest_rows)
    lines.append("Reports: artifact `coverage-reports-*` (scoverage and Cobertura XML, lcov and its HTML); "
                 "on pushes and manual runs also `coverage-html-*` (the Scala HTML report).")
    sys.stdout.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
