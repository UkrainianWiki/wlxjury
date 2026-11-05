#!/usr/bin/env python3
"""Run a check with a full disk log and bounded, failure-focused console output.

A run has a time limit (--timeout, default 1200 s; 0 means none). When it runs out, the whole
process tree is stopped, the log is kept, a "TIMED OUT after N s" line is printed and the exit
code is 124. On Windows the command starts suspended in a Job Object with
JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE (the sbt launcher JVM, its forked test JVM, anything they
start): a timeout terminates the job, and so does this script's own death, a hard kill included;
`taskkill /T /F` only if the job can't be made (the exit line says which: tree=job-object or
tree=taskkill). Elsewhere the command gets its own process group: SIGTERM, SIGHUP or SIGINT to
this script is forwarded to that group, which is then killed (exit 128 + the signal). The
deadline is checked against time.monotonic() in short polls: on Windows that is
GetTickCount64(), which counts sleep and hibernation, so a run the host slept past is stopped
soon after it wakes; Linux's CLOCK_MONOTONIC doesn't count a suspend.

On Windows, while the command runs, the waiting thread holds a keep-awake request
(SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED), released at the end, a timeout or
an interrupt included), so idle sleep (e.g. on battery after a power cut) doesn't stop a long run.
The display may sleep; lid and power-button actions and critical-battery hibernation still
apply. --allow-sleep opts out.

--lock takes the shared sbt lock directory (default C:/scala/.sbt-jvm.lock), writes an `owner`
file inside it (who, PID, host, start time, timeout, working directory, command) and removes both
at exit, a timeout included. A lock held by someone else is waited for (--lock-wait). Not waited
for, but reported (exit 75), and never removed: a lock whose owner file names a PID on this host
that no longer runs (its run was killed), and one older than max(an hour, its timeout + 5 min).
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
    r"^Test Suites:|^Tests:|^FAILED \(|^OK(?: \(.*\))?$|\[pairs\]|\[bench\] written"
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


IS_WINDOWS = os.name == "nt"  # the keep-awake request (tests patch it)
ON_WINDOWS = os.name == "nt"  # processes, job objects, pids
ES_CONTINUOUS = 0x80000000
ES_SYSTEM_REQUIRED = 0x00000001
CREATE_SUSPENDED = 0x00000004
JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000
JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
STILL_ACTIVE = 259
ERROR_ACCESS_DENIED = 5


class Terminated(BaseException):
    """SIGTERM, SIGHUP or SIGINT to this script (POSIX): forwarded to the child's group."""

    def __init__(self, signum):
        super().__init__(signum)
        self.signum = signum


def _kernel32():
    import ctypes
    from ctypes import wintypes
    k = ctypes.WinDLL("kernel32", use_last_error=True)
    k.CreateJobObjectW.restype = wintypes.HANDLE
    k.CreateJobObjectW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p]
    k.SetInformationJobObject.restype = wintypes.BOOL
    k.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    k.AssignProcessToJobObject.restype = wintypes.BOOL
    k.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
    k.TerminateJobObject.restype = wintypes.BOOL
    k.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
    k.CloseHandle.restype = wintypes.BOOL
    k.CloseHandle.argtypes = [wintypes.HANDLE]
    k.OpenProcess.restype = wintypes.HANDLE
    k.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    k.GetExitCodeProcess.restype = wintypes.BOOL
    k.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    return k


class WindowsJob:
    """A Job Object with JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE holding the child and everything it
    starts: terminating it stops exactly that tree, and so does this script's death (its handle,
    not inherited by the child, is the job's only one). Raises OSError if it can't be made."""

    def __init__(self):
        import ctypes
        from ctypes import wintypes

        class IoCounters(ctypes.Structure):
            _fields_ = [(n, ctypes.c_ulonglong) for n in (
                "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]

        class BasicLimits(ctypes.Structure):
            _fields_ = [("PerProcessUserTimeLimit", ctypes.c_int64), ("PerJobUserTimeLimit", ctypes.c_int64),
                        ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                        ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                        ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD),
                        ("SchedulingClass", wintypes.DWORD)]

        class ExtendedLimits(ctypes.Structure):
            _fields_ = [("BasicLimitInformation", BasicLimits), ("IoInfo", IoCounters),
                        ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                        ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]

        self.k = _kernel32()
        self.handle = self.k.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        info = ExtendedLimits()
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.k.SetInformationJobObject(self.handle, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                                              ctypes.byref(info), ctypes.sizeof(info)):
            error = ctypes.WinError(ctypes.get_last_error())
            self.close()
            raise error

    def assign(self, process_handle):
        import ctypes
        if not self.k.AssignProcessToJobObject(self.handle, process_handle):
            raise ctypes.WinError(ctypes.get_last_error())

    def terminate(self):
        if self.handle:
            self.k.TerminateJobObject(self.handle, 1)

    def close(self):
        """Closing the last handle kills what is left in the job (KILL_ON_JOB_CLOSE)."""
        if self.handle:
            self.k.CloseHandle(self.handle)
            self.handle = None


def _resume(process):
    """Resumes a child created suspended (it was suspended so that it joins the job before it
    can start anything)."""
    import ctypes
    from ctypes import wintypes
    ntdll = ctypes.WinDLL("ntdll")
    ntdll.NtResumeProcess.restype = ctypes.c_long
    ntdll.NtResumeProcess.argtypes = [wintypes.HANDLE]
    status = ntdll.NtResumeProcess(int(process._handle))
    if status != 0:
        raise OSError("NtResumeProcess failed: status {:#x}".format(status & 0xFFFFFFFF))


def pid_alive(pid):
    """Whether a process with this id runs on this host (an id reused since reads as alive)."""
    if ON_WINDOWS:
        import ctypes
        from ctypes import wintypes
        k = _kernel32()
        handle = k.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not handle:
            return ctypes.get_last_error() == ERROR_ACCESS_DENIED
        try:
            code = wintypes.DWORD()
            return bool(k.GetExitCodeProcess(handle, ctypes.byref(code))) and code.value == STILL_ACTIVE
        finally:
            k.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def kill_tree(process, job=None):
    """Stops the process and every descendant (the sbt launcher and its forked JVMs): the job
    object's tree on Windows (`taskkill /T` if there is none), the process group elsewhere."""
    if job is not None:
        job.terminate()
    elif ON_WINDOWS:
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


class KeepAwake:
    """While open, asks Windows not to idle-sleep (SetThreadExecutionState, from the calling
    thread, which must be the one that waits). The display may still sleep (no
    ES_DISPLAY_REQUIRED), and lid or power-button actions and the critical-battery hibernate
    still apply. A no-op elsewhere, or when ctypes can't reach kernel32."""

    def __init__(self, enabled=True):
        self.kernel32 = None
        if enabled and IS_WINDOWS:
            try:
                import ctypes
                self.kernel32 = ctypes.windll.kernel32
            except (ImportError, AttributeError, OSError):
                self.kernel32 = None

    def __enter__(self):
        if self.kernel32 is not None:
            self.kernel32.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)
        return self

    def __exit__(self, *exc):
        if self.kernel32 is not None:
            self.kernel32.SetThreadExecutionState(ES_CONTINUOUS)
        return False


def run_with_timeout(command, output, timeout, keep_awake=True, use_job=True):
    """Returns (exit code, timed out, how the tree is stopped: "job-object", "taskkill" or
    "process-group"). On Windows the child runs in a job object (taskkill /T if one can't be
    made, or use_job is False) and the host is kept from idle sleep while it runs, unless
    keep_awake is False; elsewhere it gets a process group of its own."""
    with KeepAwake(keep_awake):
        return _run_with_timeout(command, output, timeout, use_job)


def _start(command, output, use_job):
    """Starts the command; returns (process, job or None, method)."""
    kwargs = {"stdout": output, "stderr": subprocess.STDOUT, "shell": False}
    if not ON_WINDOWS:
        kwargs["start_new_session"] = True
        return subprocess.Popen(command, **kwargs), None, "process-group"
    kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP | (CREATE_SUSPENDED if use_job else 0)
    process = subprocess.Popen(command, **kwargs)
    if not use_job:
        return process, None, "taskkill"
    job = None
    try:
        try:
            job = WindowsJob()
            job.assign(int(process._handle))
        except OSError as error:
            if job is not None:
                job.close()
            job = None
            print("agent-check: no job object ({}); a timeout stops the tree with taskkill /T".format(error),
                  file=sys.stderr)
    finally:
        try:
            _resume(process)
        except BaseException:
            kill_tree(process, job)
            if job is not None:
                job.close()
            raise
    return process, job, "job-object" if job is not None else "taskkill"


def _run_with_timeout(command, output, timeout, use_job=True):
    process, job, method = _start(command, output, use_job)
    deadline = time.monotonic() + timeout if timeout > 0 else None
    try:
        while True:
            # Short waits re-read the monotonic clock: GetTickCount64() on Windows (Python 3.8
            # here), which per Microsoft's docs goes on counting in sleep and hibernation, so a
            # run the host slept past is stopped once it wakes; CLOCK_MONOTONIC on Linux, which
            # doesn't count a suspend.
            wait = POLL_SECONDS if deadline is None else max(0.05, min(POLL_SECONDS, deadline - time.monotonic()))
            try:
                return process.wait(timeout=wait), False, method
            except subprocess.TimeoutExpired:
                if deadline is not None and time.monotonic() >= deadline:
                    kill_tree(process, job)
                    return process.returncode, True, method
    except Terminated as stop:
        # forwarded first, so the tree can stop cleanly; then killed
        try:
            os.killpg(process.pid, stop.signum)
            process.wait(timeout=10)
        except (ProcessLookupError, PermissionError, subprocess.TimeoutExpired):
            pass
        kill_tree(process, job)
        raise
    except BaseException:
        # Ctrl+C or an error here must not leave the JVMs running.
        kill_tree(process, job)
        raise
    finally:
        if job is not None:
            job.close()  # stragglers the command left in the job go with it


class LockError(Exception):
    """The lock can't be taken at all (e.g. its parent directory is missing)."""


def lock_owner_text(owner, command, timeout=0):
    return "".join("{}={}\n".format(k, v) for k, v in [
        ("owner", owner),
        ("pid", os.getpid()),
        ("host", socket.gethostname()),
        ("started", datetime.datetime.now().astimezone().isoformat(timespec="seconds")),
        ("timeout", "{:g}".format(timeout)),
        ("cwd", os.getcwd()),
        ("command", " ".join(command).replace("\n", " ")),
    ])


def owner_fields(lock):
    fields = {}
    for line in read_owner(lock).splitlines():
        key, sep, value = line.partition("=")
        if sep:
            fields.setdefault(key.strip(), value.strip())
    return fields


def stale_after(fields, minimum=STALE_LOCK_SECONDS):
    """A lock is stale past max(an hour, its run's timeout + 5 min); an hour with no timeout."""
    try:
        timeout = float(fields.get("timeout", "0"))
    except ValueError:
        timeout = 0
    return max(minimum, timeout + 300) if timeout > 0 else minimum


def owner_gone(fields):
    """The owner file names a process on this host that is no longer running."""
    if fields.get("host") != socket.gethostname():
        return False
    try:
        pid = int(fields.get("pid", ""))
    except ValueError:
        return False
    return not pid_alive(pid)


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


def acquire_lock(lock, owner, command, wait_seconds, stale_seconds=STALE_LOCK_SECONDS, timeout=0):
    """Returns the owner text written, or None after reporting a busy, stale or abandoned lock.
    Never removes someone else's lock: the user decides."""
    deadline = time.monotonic() + wait_seconds
    reported = False
    while True:
        try:
            lock.mkdir()
        except FileNotFoundError:
            raise LockError("can't take the lock {}: its parent directory {} doesn't exist".format(lock, lock.parent))
        except FileExistsError:
            fields = owner_fields(lock)
            if owner_gone(fields):
                print("LOCK OWNER GONE: {} names pid {} on this host, which is not running (a run killed "
                      "without releasing it). Not removed and not waited for: report it; the user decides.\n{}"
                      .format(lock, fields.get("pid"), read_owner(lock)), file=sys.stderr, end="")
                return None
            age = lock_age(lock)
            if age > stale_after(fields, stale_seconds):
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
        text = lock_owner_text(owner, command, timeout)
        try:
            (lock / "owner").write_text(text, encoding="utf-8")
        except BaseException:
            # a lock without its owner file would be held with nobody to ask
            try:
                (lock / "owner").unlink()
            except OSError:
                pass
            lock.rmdir()
            raise
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
    parser.add_argument("--allow-sleep", action="store_true",
                        help="Don't keep Windows from idle sleep while the command runs")
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
    if not ON_WINDOWS:
        import signal

        def forward(signum, _frame):
            raise Terminated(signum)

        for name in ("SIGTERM", "SIGHUP", "SIGINT"):
            signal.signal(getattr(signal, name), forward)
    try:
        if args.lock:
            owner = args.owner or "{}@{}".format(getpass.getuser(), socket.gethostname())
            try:
                owner_text = acquire_lock(args.lock, owner, command, args.lock_wait, timeout=args.timeout)
            except LockError as error:
                print("agent-check: " + str(error), file=sys.stderr)
                return 2
            if owner_text is None:
                return LOCK_EXIT
        started = time.monotonic()
        try:
            # No shell interpolation; never overwrite evidence from an earlier check.
            with log.open("xb") as output:
                code, timed_out, method = run_with_timeout(command, output, args.timeout,
                                                           keep_awake=not args.allow_sleep)
        except OSError as error:
            print("Could not run check: " + str(error), file=sys.stderr)
            return 2
        elapsed = time.monotonic() - started
        if timed_out:
            print("TIMED OUT after {:g} s (process tree stopped: {}) elapsed={:.1f}s log={}".format(
                args.timeout, method, elapsed, log.resolve()))
            print(summarize(log.read_text(encoding="utf-8", errors="replace"), TIMEOUT_EXIT))
            return TIMEOUT_EXIT
        print("exit={} elapsed={:.1f}s tree={} log={}".format(code, elapsed, method, log.resolve()))
        print(summarize(log.read_text(encoding="utf-8", errors="replace"), code))
        return code if code >= 0 else 128 - code
    except Terminated as stop:
        print("STOPPED by signal {}: the command's process group was sent it, then killed; log={}".format(
            stop.signum, log.resolve()), file=sys.stderr)
        return 128 + stop.signum
    finally:
        if owner_text is not None:
            release_lock(args.lock, owner_text)


if __name__ == "__main__":
    sys.exit(main())
