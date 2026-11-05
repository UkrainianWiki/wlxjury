#!/usr/bin/env python3
"""Run a check with a full disk log and bounded, failure-focused console output.

A run has a time limit (--timeout, default 1200 s; 0 means none). When it runs out, the whole
process tree is stopped (on Windows `taskkill /T /F`, which takes the sbt launcher JVM and its
forked test JVM; elsewhere the process group), the log is kept, a "TIMED OUT after N s" line is
printed and the exit code is 124. The deadline is checked against time.monotonic() in short
polls, so a run whose host slept past the deadline is stopped soon after the host wakes.

--lock takes the shared sbt lock directory (default C:/scala/.sbt-jvm.lock), writes an `owner`
file inside it (who, PID, host, start time, working directory, command) and removes both at exit,
a timeout included. A lock held by someone else is waited for (--lock-wait); a lock older than an
hour is reported and not waited for (exit 75), since its owner may be gone.
"""
import argparse
import datetime
import getpass
import os
from pathlib import Path
import re
import socket
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

TIMEOUT_EXIT = 124
LOCK_EXIT = 75
DEFAULT_TIMEOUT = 1200
DEFAULT_LOCK = "C:/scala/.sbt-jvm.lock" if os.name == "nt" else "/c/scala/.sbt-jvm.lock"
STALE_LOCK_SECONDS = 3600
POLL_SECONDS = 2.0


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


def kill_tree(process):
    """Stops the process and every descendant (the sbt launcher and its forked JVMs)."""
    if os.name == "nt":
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(process.pid)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        import signal
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def run_with_timeout(command, output, timeout):
    """Returns (exit code, timed out). The child gets its own process group, so the tree can go."""
    kwargs = {"stdout": output, "stderr": subprocess.STDOUT, "shell": False}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    process = subprocess.Popen(command, **kwargs)
    deadline = time.monotonic() + timeout if timeout > 0 else None
    try:
        while True:
            # Short waits re-read the monotonic clock, so host sleep counts toward the deadline.
            wait = POLL_SECONDS if deadline is None else max(0.05, min(POLL_SECONDS, deadline - time.monotonic()))
            try:
                return process.wait(timeout=wait), False
            except subprocess.TimeoutExpired:
                if deadline is not None and time.monotonic() >= deadline:
                    kill_tree(process)
                    return process.returncode, True
    except BaseException:
        # Ctrl+C or an error here must not leave the JVMs running.
        kill_tree(process)
        raise


def lock_owner_text(owner, command):
    return "".join("{}={}\n".format(k, v) for k, v in [
        ("owner", owner),
        ("pid", os.getpid()),
        ("host", socket.gethostname()),
        ("started", datetime.datetime.now().astimezone().isoformat(timespec="seconds")),
        ("cwd", os.getcwd()),
        ("command", " ".join(command).replace("\n", " ")),
    ])


def read_owner(lock):
    try:
        return (lock / "owner").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return "(no owner file)\n"


def lock_age(lock):
    """Seconds since the lock was taken: the owner file's `started`, else the directory's mtime."""
    for line in read_owner(lock).splitlines():
        if line.startswith("started="):
            try:
                started = datetime.datetime.fromisoformat(line[len("started="):].strip())
                if started.tzinfo is None:
                    started = started.astimezone()
                return (datetime.datetime.now().astimezone() - started).total_seconds()
            except ValueError:
                break
    try:
        return time.time() - lock.stat().st_mtime
    except OSError:
        return 0.0


def acquire_lock(lock, owner, command, wait_seconds, stale_seconds=STALE_LOCK_SECONDS):
    """Returns the owner text written, or None after reporting a busy or stale lock."""
    deadline = time.monotonic() + wait_seconds
    reported = False
    while True:
        try:
            lock.mkdir()
        except FileExistsError:
            age = lock_age(lock)
            if age > stale_seconds:
                print("STALE LOCK: {} is {:.0f} min old; not waiting. Report it; remove it only if "
                      "its owner is verifiably gone.\n{}".format(lock, age / 60, read_owner(lock)),
                      file=sys.stderr, end="")
                return None
            if time.monotonic() >= deadline:
                print("LOCK BUSY: gave up waiting for {} after {:g} s.\n{}".format(
                    lock, wait_seconds, read_owner(lock)), file=sys.stderr, end="")
                return None
            if not reported:
                print("Waiting for {} ({:.0f} s old), held by:\n{}".format(lock, age, read_owner(lock)), end="")
                sys.stdout.flush()
                reported = True
            time.sleep(min(15.0, max(0.1, deadline - time.monotonic())))
            continue
        text = lock_owner_text(owner, command)
        (lock / "owner").write_text(text, encoding="utf-8")
        return text


def release_lock(lock, text):
    """Removes the lock only if its owner file is still the one this run wrote."""
    if read_owner(lock) != text:
        print("Lock {} no longer has this run's owner file; left in place.".format(lock), file=sys.stderr)
        return
    try:
        (lock / "owner").unlink()
        lock.rmdir()
    except OSError as error:
        print("Could not release lock {}: {}".format(lock, error), file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--log", type=Path)
    parser.add_argument("--summarize", type=Path, help="Summarize an existing log without running anything")
    parser.add_argument("--exit-code", type=int, help="Known exit code for an existing log")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT,
                        help="Seconds before the process tree is stopped (exit 124); 0 means no limit. "
                             "Default %(default)s")
    parser.add_argument("--lock", nargs="?", const=DEFAULT_LOCK, type=Path,
                        help="Take this lock directory for the run (default " + DEFAULT_LOCK + ")")
    parser.add_argument("--lock-wait", type=float, default=3600,
                        help="Seconds to wait for a held lock before giving up (exit 75). Default %(default)s")
    parser.add_argument("--owner", default=os.environ.get("AGENT_CHECK_OWNER"),
                        help="Who takes the lock (default $AGENT_CHECK_OWNER, else user@host)")
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
    if args.timeout < 0:
        parser.error("--timeout must be 0 (no limit) or positive")
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    log = args.log or Path("target/checks") / (stamp + "-" + str(os.getpid()) + ".log")
    log.parent.mkdir(parents=True, exist_ok=True)
    owner_text = None
    if args.lock:
        owner = args.owner or "{}@{}".format(getpass.getuser(), socket.gethostname())
        owner_text = acquire_lock(args.lock, owner, command, args.lock_wait)
        if owner_text is None:
            return LOCK_EXIT
    try:
        started = time.monotonic()
        try:
            # No shell interpolation; never overwrite evidence from an earlier check.
            with log.open("xb") as output:
                code, timed_out = run_with_timeout(command, output, args.timeout)
        except OSError as error:
            print("Could not run check: " + str(error), file=sys.stderr)
            return 2
        elapsed = time.monotonic() - started
        if timed_out:
            print("TIMED OUT after {:g} s (process tree stopped) elapsed={:.1f}s log={}".format(
                args.timeout, elapsed, log.resolve()))
            print(summarize(log.read_text(encoding="utf-8", errors="replace"), TIMEOUT_EXIT))
            return TIMEOUT_EXIT
        print("exit={} elapsed={:.1f}s log={}".format(code, elapsed, log.resolve()))
        print(summarize(log.read_text(encoding="utf-8", errors="replace"), code))
        return code if code >= 0 else 128 - code
    finally:
        if owner_text is not None:
            release_lock(args.lock, owner_text)


if __name__ == "__main__":
    sys.exit(main())
