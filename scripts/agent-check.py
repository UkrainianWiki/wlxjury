#!/usr/bin/env python3
"""Run a check with a full disk log and bounded, failure-focused console output."""
import argparse
import datetime
import os
from pathlib import Path
import re
import subprocess
import sys
import time


ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
SUMMARY = re.compile(
    r"Passed: Total|Error: Total|Total time:|Compilation failed|compilation failed|"
    r"All package validations passed|Your package is ready|"
    r"^Test Suites:|^Tests:|^FAILED \(|^OK$|\[pairs\]|\[bench\] written"
)
FAILURE = re.compile(
    r"==> X |\[error\]\s+[x!] |\[error\].*\.(?:scala|java):\d+|"
    r"^FAIL(?:ED)?\b|^ERROR:|^Traceback \("
)


def summarize(text, exit_code=None, max_lines=40, max_chars=6000):
    lines = ANSI.sub("", text).splitlines()
    summaries = [line for line in lines if SUMMARY.search(line)]
    selected = summaries[-12:]
    if exit_code != 0:
        anchors = [i for i, line in enumerate(lines) if FAILURE.search(line)]
        indices = set()
        for i in anchors[:3]:
            indices.update(range(max(0, i - 1), min(len(lines), i + 9)))
        detail = [lines[i] for i in sorted(indices)] if anchors else lines[-20:]
        selected = detail + selected
    # Application error logs in successful failure-path tests are not test failures.
    if not selected:
        selected = ["No recognized summary; inspect the full log if needed."]
    unique = list(dict.fromkeys(selected))
    result = "\n".join(unique[:max_lines])
    if len(unique) > max_lines or len(result) > max_chars:
        result = result[:max_chars] + "\n[Summary truncated; full output is in the log.]"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path)
    parser.add_argument("--summarize", type=Path, help="Summarize an existing log without running anything")
    parser.add_argument("--exit-code", type=int, help="Known exit code for an existing log")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.summarize:
        if args.command or args.log:
            parser.error("--summarize cannot be combined with a command or --log")
        print(summarize(args.summarize.read_text(encoding="utf-8", errors="replace"), args.exit_code))
        return 0
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command or args.exit_code is not None:
        parser.error("supply -- COMMAND, or use --summarize with an optional --exit-code")
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    log = args.log or Path("target/checks") / (stamp + "-" + str(os.getpid()) + ".log")
    log.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    try:
        # No shell interpolation; never overwrite evidence from an earlier check.
        with log.open("xb") as output:
            code = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, shell=False).returncode
    except OSError as error:
        print("Could not run check: " + str(error), file=sys.stderr)
        return 2
    print("exit={} elapsed={:.1f}s log={}".format(code, time.monotonic() - started, log.resolve()))
    print(summarize(log.read_text(encoding="utf-8", errors="replace"), code))
    return code if code >= 0 else 128 - code


if __name__ == "__main__":
    sys.exit(main())
