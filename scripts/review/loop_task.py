"""Start the CodeRabbit loop detached, and keep it started with a Windows scheduled task.

  install     copy the loop's scripts from this checkout into DIR/bin (DIR: --dir, default
              %LOCALAPPDATA%/wlxjury-review) with a VERSION file naming the commit. The task runs
              that copy: worktrees are removed after a merge and the followups tree changes with
              every stack rebuild, so a task pointing into a checkout would break or change code
              under a running loop. Updating the loop = running `install` (or `register`) again
              from the checkout to use; a running loop keeps its code until it restarts.
  launch      start DIR/bin/loop-daemon.sh (this file's folder's, when run from a checkout)
              through Git Bash, detached and without a window, unless the loop is running (its
              lock) or stopped (`coderabbit_loop.py --stop`). Quiet and quick when there is
              nothing to do: the scheduled task runs it every --every minutes.
  register    install, then create (or replace) the scheduled task: at logon and every --every
              minutes (missed runs start when the laptop wakes up), runs
              `pythonw.exe -I DIR/bin/loop_task.py launch --dir DIR` as the current user, only
              while signed in, on battery too, never elevated, no run-time limit. If Windows
              refuses the task with the logon trigger (elevation; any error, whatever the
              language), it registers the repeating trigger alone (the loop then starts within
              --every minutes of logon). --print writes and shows the XML and the command and
              registers nothing.
  unregister  delete the task.
  show        print the task's state (schtasks /Query).

Python 3.8+, stdlib only; Windows for register/unregister/show, any OS for install and launch.
"""
import argparse
import datetime
import os
import pathlib
import shutil
import subprocess
import sys
from xml.sax.saxutils import escape

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I leaves the script's folder out
from common import lock_held, loop_dir  # noqa: E402

TASK = "wlxjury CodeRabbit loop"
HERE = pathlib.Path(__file__).resolve().parent
GIT_BASH = [r"C:\Program Files\Git\bin\bash.exe", r"C:\Program Files (x86)\Git\bin\bash.exe"]
INSTALLED = ("common.py", "coderabbit_loop.py", "loop_task.py", "loop-daemon.sh")


def bash_path():
    for p in [os.environ.get("BASH_EXE")] + GIT_BASH:
        if p and pathlib.Path(p).exists():
            return p
    return shutil.which("bash") or "bash"


def console_python():
    """python.exe next to the running interpreter (the task runs pythonw.exe, which has no console)."""
    exe = pathlib.Path(sys.executable)
    if exe.name.lower() == "pythonw.exe" and (exe.parent / "python.exe").exists():
        return str(exe.parent / "python.exe")
    return str(exe)


def windowless_python():
    """pythonw.exe next to this interpreter: the task starts it with no console window."""
    exe = pathlib.Path(sys.executable).with_name("pythonw.exe")
    if not exe.exists():
        raise SystemExit(f"{exe} not found: the task needs pythonw.exe (it comes with the python.org installer)")
    return exe


def launch(folder, out=print, here=HERE):
    if lock_held(folder / "loop.lock"):
        out("running already")
        return 0
    if (folder / "loop.stop").exists():
        out("stopped (`coderabbit_loop.py --resume` clears it), not starting")
        return 0
    folder.mkdir(parents=True, exist_ok=True)
    # forward slashes: Git Bash takes C:/… as is; LOOP_PYTHON replaces python (the tests use echo)
    env = dict(os.environ, REVIEW_DIR=folder.as_posix(), PYTHON=os.environ.get("LOOP_PYTHON") or console_python())
    flags = 0
    if sys.platform == "win32":
        # a hidden console that gh.exe and python.exe share (no window per call), its own process
        # group (Ctrl+C in the starting terminal does not reach it), out of the task's job object
        flags = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP | 0x01000000  # BREAKAWAY_FROM_JOB
    cmd = [bash_path(), str(here / "loop-daemon.sh")]
    try:
        p = subprocess.Popen(cmd, env=env, creationflags=flags, stdin=subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True)
    except OSError:
        if not flags:
            raise
        # the job may not allow breaking away; the task has no run-time limit, so it is not killed
        p = subprocess.Popen(cmd, env=env, creationflags=flags & ~0x01000000, stdin=subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True)
    out(f"started (bash pid {p.pid}, {here}); log: {folder / 'loop.log'}")
    return 0


def version_of(src):
    """The commit (and whether scripts/review had changes) of the checkout at `src`."""
    def git(*args):
        r = subprocess.run(["git", "-C", str(src), *args], capture_output=True)
        return r.stdout.decode("utf-8", "replace").strip() if r.returncode == 0 else ""
    head = git("rev-parse", "HEAD") or "unknown (not a git checkout)"
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    dirty = git("status", "--porcelain", "--", ".")
    return f"{head} {branch}{' + uncommitted changes' if dirty else ''} from {src}"


def install(folder, src=HERE, out=print):
    """Copies the loop's scripts into folder/bin; returns that folder."""
    bin_ = folder / "bin"
    if src.resolve() == bin_.resolve():
        out(f"running from {bin_} already, nothing to copy")
        return bin_
    bin_.mkdir(parents=True, exist_ok=True)
    for name in INSTALLED:
        shutil.copy2(src / name, bin_ / name)
    version = version_of(src)
    (bin_ / "VERSION").write_text(f"{version}\ninstalled {datetime.datetime.now():%Y-%m-%d %H:%M}\n", encoding="utf-8")
    out(f"installed {', '.join(INSTALLED)} into {bin_} ({version})")
    return bin_


def user_id():
    dom, user = os.environ.get("USERDOMAIN"), os.environ.get("USERNAME")
    return f"{dom}\\{user}" if dom else user


def task_xml(folder, every, logon=True, pythonw=None, script=None):
    pythonw = pythonw or pathlib.Path(sys.executable).with_name("pythonw.exe")
    script = script or folder / "bin" / "loop_task.py"
    args = f'-I "{script}" launch --dir "{folder}"'
    start = datetime.datetime.now().replace(second=0, microsecond=0).isoformat()
    who = escape(user_id() or "")
    logon_trigger = (f"    <LogonTrigger>\n      <Enabled>true</Enabled>\n      <UserId>{who}</UserId>\n"
                     f"    </LogonTrigger>\n") if logon else ""
    return f"""<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>Starts the wlxjury CodeRabbit review loop (scripts/review, installed in {escape(str(folder / 'bin'))}) if it is not running.</Description>
  </RegistrationInfo>
  <Triggers>
{logon_trigger}    <TimeTrigger>
      <Repetition>
        <Interval>PT{every}M</Interval>
        <StopAtDurationEnd>false</StopAtDurationEnd>
      </Repetition>
      <StartBoundary>{start}</StartBoundary>
      <Enabled>true</Enabled>
    </TimeTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>{who}</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>7</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>{escape(str(pythonw))}</Command>
      <Arguments>{escape(args)}</Arguments>
      <WorkingDirectory>{escape(str(folder))}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"""


def schtasks(*args):
    out = subprocess.run(["schtasks.exe", *args], capture_output=True)
    text = (out.stdout + out.stderr).decode("mbcs" if sys.platform == "win32" else "utf-8", "replace").strip()
    return out.returncode, text


def register(folder, every, show_only, run=schtasks, out=print):
    pythonw = windowless_python()
    xml_path = folder / "loop-task.xml"
    folder.mkdir(parents=True, exist_ok=True)
    if not show_only:
        install(folder, out=out)
    for logon in (True, False):
        xml_path.write_text(task_xml(folder, every, logon, pythonw), encoding="utf-16")
        cmd = ["/Create", "/TN", TASK, "/XML", str(xml_path), "/F"]
        if show_only:
            out(xml_path.read_text(encoding="utf-16"))
            out("would install the scripts into " + str(folder / "bin") + ", then run:")
            out("schtasks.exe " + " ".join(f'"{a}"' if " " in a else a for a in cmd))
            return 0
        code, text = run(*cmd)
        out(text)
        if code == 0:
            out(f"registered '{TASK}'" + ("" if logon else " without the logon trigger (refused): "
                                         f"the loop starts within {every} min of logon"))
            return 0
        if logon:
            out("refused with the logon trigger; trying the repeating trigger alone")
    return code


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("action", choices=["install", "launch", "register", "unregister", "show"])
    ap.add_argument("--dir", default=str(loop_dir()),
                    help="the loop's folder (default: $WLX_REVIEW_DIR, else %(default)s)")
    ap.add_argument("--every", type=int, default=30, help="register: minutes between launch attempts (default 30)")
    ap.add_argument("--print", dest="show_only", action="store_true",
                    help="register: write the XML to DIR/loop-task.xml and print it and the command; install and register nothing")
    a = ap.parse_args()
    folder = pathlib.Path(a.dir).resolve()
    if a.action == "install":
        install(folder)
        return 0
    if a.action == "launch":
        return launch(folder)
    if a.action == "register":
        return register(folder, a.every, a.show_only)
    if a.action == "unregister":
        code, text = schtasks("/Delete", "/TN", TASK, "/F")
        print(text)
        return code
    code, text = schtasks("/Query", "/TN", TASK, "/V", "/FO", "LIST")
    print(text)
    return code


if __name__ == "__main__":
    sys.exit(main())
