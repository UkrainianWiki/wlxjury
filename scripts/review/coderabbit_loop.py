"""Request CodeRabbit reviews on the stacked PRs one at a time, waiting out its limits.

Modes (README.md, "Running the loop"):
  --post            watch: runs until stopped. Each pass lists the open stacked PRs (`stack/…` head
                    branches in stack order, from --first up to --last or the stack top), requests
                    a review on every PR that needs one, one at a time, then idles (--idle, 30 min)
                    and starts the next pass, so new PRs and changed diffs are picked up. Reviews
                    with comments go to the triage queue (--dir/triage-queue.json, `--status`);
                    the loop never stops for triage.
  --post --batch N  one pass, stopping after N completed reviews (manual use, as before);
  --post --once     one pass;
  (no --post)       reads GitHub and prints what it would do next, then stops (--dry-run); it
                    reads --dir's halt, post budget, unpark requests and queues, writes nothing.
  --status, --stop, --resume, --triaged N…, --queue-add N…: see main().

Which PRs need a review:
  - never reviewed: no CodeRabbit review and no summary marked "reviewed";
  - reviewed, but the PR's own diff changed since: the diff against its base (the PR's files and
    their +/- lines, not the head sha) differs from the one recorded when the review was asked
    for. A merge of the base into the stack branch moves the head but leaves the own diff alone,
    so it costs nothing; a fix pushed to the PR changes it and gets `@coderabbitai review` (an
    incremental review of the new commits; never a full review). The diff is fetched only when
    the head moved since it was last recorded. A PR reviewed before diffs were recorded (or with
    the state file lost) takes its current diff as the reviewed one: nothing is re-requested on
    a guess. --no-rereview turns re-requests off.

For each such PR: post `@coderabbitai review` and wait for CodeRabbit's answer. Only two things
answer a request:
  - a CodeRabbit review submitted after it (the review is done);
  - CodeRabbit's reply to the command saying it was limited, skipped or refused.
The acknowledgement ("Action performed: Review triggered", later edited to "Review finished") and
the summary comment's "Currently processing" edit mean the review is running: keep waiting.

"Review finished" with no review submitted after the request, FINISHED_GRACE (5 min) after that
edit, has two meanings:
  - clean: CodeRabbit reviewed the PR and found nothing, so it submits no review object. Only the
    summary comment shows it: edited after the request to "No actionable comments were generated
    in the recent review", with `<!-- final_review_risk_coverage:{…"coveredCommitId":"<head
    sha>","kind":"reviewed"} -->` naming the PR's current head (#215 and #217, 2026-11-10: the
    summary edit came 4–5 s before the "Review finished" edit). Recorded as "clean" (reviewed,
    0 comments, nothing to triage) and counted toward --batch.
  - otherwise (no such summary edit): for a first review the loop posts `@coderabbitai full
    review` once and waits for it like any request; if that also ends without a review, the PR
    is recorded as "no review available". A command reply saying "already reviewed" or "no new
    commits" (outside the blockquoted note) is handled the same way. For a re-review after a diff
    change it is recorded as "nothing new" (no full review: that would post every comment again).

Waits, never exits (a pass goes on with the next PR where a PR has to wait):
  - "Review rate limited." (no time stated): wait LIMIT_SPACING (50 min) and ask again, without a
    limit on the retries; "available in N minutes" / "Please wait **N minutes and S seconds**":
    wait that long plus 2 minutes, however long that is;
  - no reply to the command in 30 minutes, or no review 3 hours after the request (45 minutes
    when asked again): the PR is deferred and asked again later (30 min, doubling to 6 h), and
    the pass goes on;
  - an unexpected answer: deferred 6 h; a quota or plan message: the PR is deferred and the whole
    loop pauses --quota-pause (6 h), then goes on;
  - GitHub or network errors (a read already retries 10 min): the pass restarts after 1, 2, 4 …
    up to 30 min; gh not signed in (HTTP 401): after --auth-pause (1 h);
  - the times above are wall-clock and checked every minute, so a laptop that slept through a
    wait goes on at once on waking; the 30-min and 3-h answer timeouts count awake time only.
Only `--stop` (or Ctrl+C) ends watch mode. A second copy exits at once (--dir/loop.lock).

Limits on what it posts (so a reworded or broken CodeRabbit can't make it post all day):
  - a PR deferred PR_CAP (3) times is parked: put in the triage queue, never asked again until
    `--unpark N`;
  - no usable answer (no reply, no review, an unexpected answer, a quota message) on BREAKER (3)
    different PRs in a row trips the circuit breaker; so does a reply saying reviews are paused
    or not allowed (HALT). Halted, it posts nothing until `--resume` (flagged in --status; a
    --batch/--once run exits 5);
  - at most --posts-per-day (60) `@coderabbitai` comments per UTC day, limit retries included
    (about 2 per review at ~26 reviews a day); past it, it waits for the next day.

Times compared with GitHub's (a request and its replies, reviews) are GitHub's: the request's time
is the posted comment's created_at, a settled PR's is the review's submitted_at or the summary's
edit.

Only CodeRabbit's replies to a command count: issue comments created after the request that carry
`<!-- This is an auto-generated reply by CodeRabbit -->` and either the
`<!-- CodeRabbit review command invocation: … -->` marker or a "Rate Limit Exceeded" heading. The
walkthrough/summary comment (`<!-- This is an auto-generated comment: summarize by coderabbit.ai -->`)
is never matched as a reply: it is about the PR, and may mention rate limits as a finding. Only
its `final_review_risk_coverage` marker is read, for the clean case above.

Progress is saved to --state (default --dir/requests.json). Without it the loop rebuilds what it
needs from GitHub, except the recorded diffs (see above). A PR whose latest `@coderabbitai`
request by us (after its last review, for a re-review) has no answer yet is treated as already
requested: it waits instead of posting again. After a limit reply it waits out the spacing from
that reply before posting again (the same command). Our latest request counts only if it was about
the current head: a PR whose head changed after its last request is treated as never requested and
gets a plain `@coderabbitai review`. Each post records the head sha and the diff in the state; the
request is stale if that post recorded another head, else if a finished request's summary edit
after it covers another `sourceCommitId`, else if it is older than the head commit's committer
date (the commits API).

A PR settled before diffs were recorded (the old state, or found reviewed or skipped on GitHub)
takes its current diff as reviewed and is never asked for on a guess; if its head moved after that
review (the review's commit_id, the summary's covered commit or our post's head differs from the
head), it is listed in --dir/check.json ("check by hand" in --status; `--checked N` clears it).

Files live in --dir (default %LOCALAPPDATA%/wlxjury-review, outside every checkout); `--post`
copies the old target/review state of this checkout there once, `--migrate-from DIR` another.

--self-test checks the reply and summary matching against sample comments, and the loop against a
simulated CodeRabbit (no GitHub, no sleeping), and exits.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import pathlib
import shutil
import sys
import time
import traceback

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I leaves the script's folder out
from common import (DEFAULT_DIR, OURS, GitHub, Lock, add_common_args, gh_path, is_auth, lock_held,  # noqa: E402
                    loop_dir, read_json, stack_order, update_json, write_json)

POLL = 60
NO_ANSWER = 30 * 60          # no reply to the command at all in this long (awake): defer the PR
MAX_WAIT = 3 * 3600          # no review this long (awake) after the request: defer the PR
REASK_WAIT = 45 * 60         # the same, for a request asked again after a deferral
LIMIT_SPACING = 50 * 60      # "Review rate limited." states no time: wait this long, then ask again
LIMIT_RETRIES = 4            # after this many refusals in a row the log says so (and it goes on)
FINISHED_GRACE = 5 * 60      # "Review finished" but no review object yet: wait this long for it
RETRY_FIRST, RETRY_MAX = 30 * 60, 6 * 3600   # a deferred PR (no answer): first wait, doubling to this
UNEXPECTED_RETRY = 6 * 3600  # an unexpected answer: ask again after this
QUOTA_PAUSE = 6 * 3600       # a quota or plan message: the whole loop pauses this long
AUTH_PAUSE = 3600            # gh not signed in: pause this long, then try again
ERROR_FIRST, ERROR_MAX = 60, 30 * 60  # GitHub/network errors: restart the pass after these
IDLE = 30 * 60               # watch: between passes with nothing to do
PR_CAP = 3                   # deferrals (no answer, unexpected, quota) of one PR before it is parked
BREAKER = 3                  # bad outcomes on this many different PRs in a row: halt until --resume
POSTS_PER_DAY = 60           # our `@coderabbitai` comments per UTC day (limit retries included)
# CodeRabbit refusing to review at all (not a rate limit): halt and flag, never ask again by itself.
# Read on the reply without its blockquotes (the boilerplate note says "…when automatic reviews
# are paused").
HALT = (r"reviews? (are |is |have been |has been )?(paused|disabled)|paused (the )?reviews?|"
        r"not (enabled|authori[sz]ed|available) (for|on) this (repo|repository|organi[sz]ation)|"
        r"(subscription|trial) (has )?(expired|ended)|no (active )?seat")

SUMMARY_MARKER = "<!-- This is an auto-generated comment: summarize by coderabbit.ai -->"
REPLY_MARKER = "<!-- This is an auto-generated reply by CodeRabbit -->"
COMMAND_MARKER = "<!-- CodeRabbit review command invocation"
REVIEW = "@coderabbitai review"
FULL = "@coderabbitai full review"
NO_REVIEW = "no review available"
CLEAN = "clean"              # reviewed at the head with 0 comments: only the summary says so
NOTHING_NEW = "nothing new"  # a re-review after a diff change that ended without a review
# outcomes after which a PR needs nothing until its own diff changes
SETTLED = ("reviewed", CLEAN, NO_REVIEW, "skipped", NOTHING_NEW)
PARKED = "parked"            # asked PR_CAP times without a usable answer: not asked again (--unpark)


class Stop(Exception):
    """--stop was requested (the stop file exists)."""


def now():
    return datetime.datetime.now(datetime.timezone.utc)


def ts(s):
    return datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))


def iso(t):
    return f"{t:%Y-%m-%dT%H:%M:%SZ}"


def log(msg):
    print(f"{now():%Y-%m-%d %H:%M:%S}Z {msg}", flush=True)


def diff_fingerprint(files):
    """A PR's own diff (`pulls/N/files`: the diff against the merge base with its base branch) as
    a short hash of each file's name, status and +/- lines. Context lines and hunk positions are
    left out, so a change elsewhere in a file (merged in from the base) does not count; a file
    with no patch (binary, too large) counts by its blob sha, so a merge of the base that changes
    such a file counts as a change too (accepted: rare, and costs one review)."""
    h = hashlib.sha256()
    for f in sorted(files, key=lambda f: f["filename"]):
        h.update(f"{f['filename']}\0{f.get('status')}\0{f.get('previous_filename') or ''}\n".encode("utf-8"))
        patch = f.get("patch")
        if patch is None:
            h.update(f"blob {f.get('sha')}\n".encode("utf-8"))
            continue
        for line in patch.splitlines():
            if line[:1] in "+-":
                h.update((line + "\n").encode("utf-8"))
    return h.hexdigest()[:16]
def rabbit(items):
    return [i for i in items if "coderabbit" in (i.get("user") or {}).get("login", "")]


def text(body):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", body or "")).strip()


def command_replies(comments, since):
    """CodeRabbit's replies to a command, created after `since`, oldest first."""
    out = []
    for c in rabbit(comments):
        body = c.get("body") or ""
        if SUMMARY_MARKER in body or REPLY_MARKER not in body or ts(c["created_at"]) <= since:
            continue
        if COMMAND_MARKER in body or re.match(r"#+\s*Rate Limit Exceeded", text(body), re.I):
            out.append(c)
    return sorted(out, key=lambda c: c["created_at"])


def classify(body):
    """What a reply to our command says: 'halt' (reviews paused or not allowed: stop asking),
    'limit', 'quota', 'skip', 'finished', 'processing', 'full' (refused as already reviewed: ask
    for a full review) or 'unexpected'.

    The boilerplate note on every command reply ("> Note: CodeRabbit is an incremental review
    system and does not re-review already reviewed commits …") is a blockquote: 'finished',
    'processing' and 'full' are read with every blockquote line left out, so a reworded note can't
    turn a reply into 'full', and 'finished'/'processing' are tested before 'full'."""
    t = text(body)
    t = re.sub(r"> ?Note: CodeRabbit is an incremental review system.*?(paused\.|$)", "", t, flags=re.I).strip()
    main = text(re.sub(r"(?m)^[ \t]*>.*$", "", body or ""))
    if re.search(HALT, main, re.I):
        return "halt"
    if re.search(r"monthly|plan quota|upgrade your plan|quota", t, re.I):
        return "quota"
    if re.search(r"rate limited|rate limit exceeded|review limit reached|available in \W*\d+ ?minute|please wait", t, re.I):
        return "limit"
    if re.search(r"review (skipped|failed)|no reviewable|too large", t, re.I):
        return "skip"
    if re.search(r"review (finished|completed)", main, re.I):
        return "finished"
    if re.search(r"review triggered|currently processing", main, re.I):
        return "processing"
    if re.search(r"\b(already been reviewed|already reviewed|no new commits)\b", main, re.I):
        return "full"
    if re.search(r"action performed", main, re.I):
        return "processing"
    return "unexpected"


def stated_wait(body):
    """Seconds a limit reply asks us to wait, or None if it states no time."""
    m = re.search(r"(?:available in|wait)\W*(\d+)\s*minutes?(?:\W+and\W+(\d+)\s*seconds?)?", text(body), re.I)
    if not m:
        return None
    return int(m.group(1)) * 60 + int(m.group(2) or 0)


def limit_wait(body):
    s = stated_wait(body)
    return LIMIT_SPACING if s is None else s + 120


def request_command(body):
    """Which command one of our `@coderabbitai` comments is: FULL or REVIEW."""
    return FULL if re.match(r"@coderabbitai\s+full\s+review\b", (body or "").strip(), re.I) else REVIEW


def coverage(body):
    """The summary comment's `final_review_risk_coverage` marker as a dict, or None."""
    m = re.search(r"final_review_risk_coverage:\s*(\{.*?\})\s*-->", body or "")
    if not m:
        return None
    try:
        return json.loads(m.group(1))
    except ValueError:
        return None


def clean_summary(comments, head):
    """CodeRabbit's summary comment if its coverage marker says it reviewed `head` (the PR's head
    sha), else None. With no review object, that is a review with 0 comments ("No actionable
    comments were generated in the recent review"; #215 and #217, 2026-11-10)."""
    for c in reversed(rabbit(comments)):
        body = c.get("body") or ""
        if SUMMARY_MARKER not in body:
            continue
        cov = coverage(body) or {}
        if head and cov.get("coveredCommitId") == head and cov.get("kind") == "reviewed":
            return c
    return None


def edited(c):
    """A comment's last edit (for a "Review finished" reply: when it got that text)."""
    return ts(c.get("updated_at") or c["created_at"])


def no_review(ans):
    """A request that ended without a review: "Review finished" with none, or "already reviewed"."""
    return ans[0] == "finished" or (ans[0] == "reply" and ans[1] == "full")


def reviewed_summary(comments):
    """CodeRabbit's latest summary comment whose coverage marker says "reviewed", at any commit."""
    for c in reversed(rabbit(comments)):
        body = c.get("body") or ""
        if SUMMARY_MARKER in body and (coverage(body) or {}).get("kind") == "reviewed":
            return c
    return None


class Loop:
    def __init__(self, hub, state_path, dry, clock=None, logger=None, folder=None, rereview=True,
                 quota_pause=QUOTA_PAUSE, posts_per_day=POSTS_PER_DAY):
        """`folder` (--dir) holds the triage queue, the check list, the control file (circuit
        breaker, post budget, unpark requests), the status file and the stop file; None in the
        self-test (all kept in memory)."""
        self.hub, self.state_path, self.dry, self.folder = hub, state_path, dry, folder
        self.rereview, self.quota_pause, self.posts_per_day = rereview, quota_pause, posts_per_day
        self.state = read_json(state_path, {}) if state_path else {}
        self.mem = {"triage-queue.json": {}, "loop-control.json": {}, "check.json": {}}
        if folder and dry:
            # a dry run reads the real halt, budget, unpark requests and queues, and changes only
            # its in-memory copy
            self.mem = {name: read_json(folder / name, {}) for name in self.mem}
        self.now = clock.now if clock else now
        self.sleep = clock.sleep if clock else time.sleep
        self.log = logger or log
        self.st = {}
        self.paused_until = None
        if hasattr(hub, "stop_check"):
            hub.stop_check = self.check_stop  # gh's read retries end on --stop too

    # -- files -----------------------------------------------------------------------------
    def save(self):
        if not self.dry and self.state_path:
            write_json(self.state_path, self.state)

    def shared(self, name, change=None):
        """A JSON file other commands change too (--triaged, --queue-add, --checked, --resume,
        --unpark): re-read under its lock before every change. In memory without a folder; in a
        dry run, a copy read from the folder at the start (nothing is written)."""
        if self.folder and not self.dry:
            if change is None:
                return read_json(self.folder / name, {})
            return update_json(self.folder / name, change, {})
        if change:
            change(self.mem[name])
        return self.mem[name]

    @property
    def queue(self):
        return self.shared("triage-queue.json")

    def control(self, change=None):
        return self.shared("loop-control.json", change)

    def status(self, **kw):
        """What the loop is doing, for --status (--dir/loop-status.json)."""
        self.st.update(kw, updated=iso(self.now()))
        if not self.dry and self.folder:
            write_json(self.folder / "loop-status.json", self.st)

    def check_stop(self):
        if self.folder and (self.folder / "loop.stop").exists():
            raise Stop()

    def sleep_until(self, deadline):
        """Sleeps in steps of at most POLL toward a wall-clock time: a laptop that slept past it
        goes on at once on waking (Windows' timed waits stop counting while it sleeps), and
        --stop is noticed within a minute."""
        while True:
            self.check_stop()
            left = (deadline - self.now()).total_seconds()
            if left <= 0:
                return
            self.sleep(min(left, POLL))

    def enqueue(self, n, kind, review_id=None, inline=None):
        """A review with comments to triage, or a parked PR (--status lists the queue, --triaged
        clears it)."""
        def add(queue):
            q = queue.setdefault(str(n), {"pr": n, "reviews": []})
            q["reviews"].append({"kind": kind, "review_id": review_id, "inline": inline, "at": iso(self.now())})
            q["triaged"] = False
        self.shared("triage-queue.json", add)

    def flag_check(self, n, why):
        """A PR to look at by hand (--status lists them, --checked clears them): its head moved
        after a review recorded before diffs were, so pushes since may be unreviewed. Never asked
        for automatically."""
        self.shared("check.json", lambda d: d.__setitem__(str(n), {"pr": n, "why": why, "at": iso(self.now())}))
        self.log(f"#{n}: to check by hand: {why}")

    # -- repost limits ---------------------------------------------------------------------
    def halted(self):
        """The circuit breaker's record if it is tripped (cleared only by --resume), else None."""
        return self.control().get("breaker")

    def trip(self, reason):
        self.control(lambda c: c.update(breaker={"at": iso(self.now()), "reason": reason}, streak=[]))
        self.log(f"HALTED: {reason}; nothing is posted until `coderabbit_loop.py --resume`")
        self.status(phase="halted (needs --resume)", until=None, halted=reason)

    def good(self):
        """CodeRabbit answered normally (a review, a clean or skipped one, a limit): the streak of
        bad outcomes starts again."""
        if self.control().get("streak"):
            self.control(lambda c: c.update(streak=[]))

    def bad(self, n, why):
        """No answer, an unexpected one or a quota message on PR n: after BREAKER different PRs in
        a row, the whole loop halts (something is wrong beyond one PR)."""
        streak = self.control(lambda c: c.update(streak=(c.get("streak") or []) + [n]))["streak"]
        if len(set(streak)) >= BREAKER:
            self.trip(f"{len(set(streak))} PRs in a row without a usable answer "
                      f"({', '.join(f'#{x}' for x in sorted(set(streak)))}; last: {why})")
            return True
        return False

    def spend_post(self, n):
        """Counts one post against the daily budget (UTC day) and returns False; if the budget is
        used up, waits for the next day instead, counts nothing and returns True (the caller looks
        at the PR again, then calls this again)."""
        day = f"{self.now():%Y-%m-%d}"
        used = (self.control().get("posts") or {}).get(day, 0)
        if used >= self.posts_per_day:
            tomorrow = datetime.datetime.combine(self.now().date() + datetime.timedelta(days=1),
                                                 datetime.time(0, 5), tzinfo=datetime.timezone.utc)
            self.log(f"#{n}: the post budget ({self.posts_per_day} a day) is used, waiting until {tomorrow:%Y-%m-%d %H:%M}Z")
            self.status(phase="post budget used", pr=n, until=iso(tomorrow))
            self.sleep_until(tomorrow)
            return True

        def count(c):
            posts = {k: v for k, v in (c.get("posts") or {}).items() if k >= f"{self.now() - datetime.timedelta(days=7):%Y-%m-%d}"}
            posts[day] = posts.get(day, 0) + 1
            c["posts"] = posts
        self.control(count)
        return False

    # -- GitHub ----------------------------------------------------------------------------
    def reviews_after(self, n, since):
        return [r for r in rabbit(self.hub.get_all(f"pulls/{n}/reviews"))
                if r.get("submitted_at") and (since is None or ts(r["submitted_at"]) > since)]

    def inline_after(self, n, since):
        return len([c for c in rabbit(self.hub.get_all(f"pulls/{n}/comments")) if ts(c["created_at"]) > since])

    def head(self, n):
        return self.hub.get(f"pulls/{n}")["head"]["sha"]

    def fingerprint(self, n):
        return diff_fingerprint(self.hub.get_all(f"pulls/{n}/files"))

    def head_date(self, sha):
        """The head commit's committer date: a rebase, an amend or a new commit sets it, so our
        request older than it was about an earlier head."""
        return ts(self.hub.get(f"commits/{sha}")["commit"]["committer"]["date"])

    def posted_at(self, out):
        """GitHub's created_at of the comment `gh pr comment` just posted (it prints the comment's
        URL), so replies are compared on GitHub's clock; our clock minus 10 s if that fails."""
        m = re.search(r"issuecomment-(\d+)", out or "")
        if m:
            try:
                return ts(self.hub.get(f"issues/comments/{m.group(1)}")["created_at"])
            except (RuntimeError, KeyError, ValueError):
                pass
        return self.now() - datetime.timedelta(seconds=10)

    def stale(self, e, req, comments, sha):
        """Why our latest request `req` was about an older head than `sha`, or None. In order:
          - the state's record of that post names a head: stale if it is another one, never if it
            is this one (then nothing else is asked);
          - the summary comment, edited after the request, covers a `sourceCommitId` other than
            the head (works without state);
          - the request is older than the head commit's committer date (works without state)."""
        asked = req[0]
        post = self.post_for(e, asked)
        if post.get("head"):
            return None if post["head"] == sha else f"the state recorded head {post['head'][:8]} for it"
        finished = req[2] is not None and classify(req[2]["body"]) == "finished"
        for c in (reversed(rabbit(comments)) if finished else []):
            # only once finished: a "Currently processing" edit keeps the old coverage
            if SUMMARY_MARKER in (c.get("body") or ""):
                src = (coverage(c["body"]) or {}).get("sourceCommitId")
                if src and src != sha and edited(c) > asked:
                    return f"the summary edited after it covers {src[:8]}"
                break
        committed = self.head_date(sha)
        if asked < committed:
            return f"older than the head commit's date {committed:%Y-%m-%d %H:%M:%S}"
        return None

    @staticmethod
    def post_for(e, asked):
        """The state's record of our post made at `asked` (within 2 minutes), or {}."""
        post = (e.get("posts") or [{}])[-1]
        if asked is not None and post.get("at") and abs((ts(post["at"]) - asked).total_seconds()) <= 120:
            return post
        return {}

    def latest_request(self, comments, after=None):
        """(asked, command, reply): our latest `@coderabbitai` request among a PR's issue comments
        (made after `after`, if given), its command (REVIEW or FULL) and CodeRabbit's latest reply
        to it (None if no reply yet), or None if we never asked."""
        ours = [c for c in comments if c["user"]["login"] == OURS and (c["body"] or "").strip().startswith("@coderabbitai")
                and (after is None or ts(c["created_at"]) > after)]
        if not ours:
            return None
        asked = ts(ours[-1]["created_at"])
        replies = command_replies(comments, asked)
        return asked, request_command(ours[-1]["body"]), (replies[-1] if replies else None)

    def wait_answer(self, n, since, max_wait=MAX_WAIT):
        """Wait for CodeRabbit's answer to the request posted at `since` (GitHub's time):
        ('review', id, count, submitted_at), ('reply', kind, body) for a halt/limit/quota/skip/
        full/unexpected reply, ('timeout', why), or, for "Review finished" with no review
        FINISHED_GRACE after that edit: ('clean', edited) if the summary comment was edited after
        `since` to say it reviewed the PR's head (0 comments), else ('finished',).

        The timeouts count awake time: wall-clock time since the request, plus at most 3 polls
        per poll, so a laptop asleep for hours does not time a running review out on waking."""
        self.status(phase="waiting for CodeRabbit's answer", pr=n, since=iso(since), until=None)
        awake = max(0.0, (self.now() - since).total_seconds())
        prev = self.now()
        while True:
            if awake >= max_wait:
                return ("timeout", f"no review {max_wait // 60} minutes after the request")
            self.check_stop()
            self.sleep(POLL)
            t = self.now()
            awake += min(max(0.0, (t - prev).total_seconds()), 3 * POLL)
            prev = t
            reviews = self.reviews_after(n, since)
            if reviews:
                return ("review", reviews[-1]["id"], self.inline_after(n, since), ts(reviews[-1]["submitted_at"]))
            comments = self.hub.get_all(f"issues/{n}/comments")
            replies = command_replies(comments, since)
            if not replies:
                if awake >= min(NO_ANSWER, max_wait):
                    return ("timeout", "no reply to the command in 30 minutes")
                continue
            body = replies[-1]["body"]
            kind = classify(body)
            if kind == "processing":
                continue
            if kind == "finished":
                if self.now() >= edited(replies[-1]) + datetime.timedelta(seconds=FINISHED_GRACE):
                    s = clean_summary(comments, self.head(n))
                    return ("clean", edited(s)) if s and edited(s) > since else ("finished",)
                continue
            return ("reply", kind, body)

    # -- outcomes --------------------------------------------------------------------------
    def settle(self, n, e, outcome, post=None, at=None, **extra):
        """Records a final outcome and the diff it is about: the one recorded with our post when
        there is one, else the current diff. The PR needs nothing more until that diff changes.
        `at` (GitHub's time of the answer: the review's submitted_at, the summary's edit) is
        where a later re-review starts looking; our clock if not given."""
        post = post or {}
        fp, head = post.get("fp"), post.get("head")
        if not fp:
            fp, head = self.fingerprint(n), self.head(n)
        for k in ("retry_at", "defer_req", "defer_command", "timeouts", "rereview_after"):
            e.pop(k, None)
        e.update(outcome=outcome, reviewed_fp=fp, fp_head=head, reviewed_at=iso(at or self.now()), **extra)
        self.save()
        if outcome != NO_REVIEW:
            self.good()

    def adopt(self, n, e, sha, ref, outcome, **extra):
        """A PR reviewed before diffs were recorded (old state, or rebuilt from GitHub): its current
        diff counts as reviewed, nothing is posted. If its head moved after that review (`ref`, the
        reviewed or requested commit, is not the head), pushes since may be unreviewed: flagged for
        a check by hand, not requested on a guess."""
        self.settle(n, e, outcome, post={"fp": self.fingerprint(n), "head": sha}, **extra)
        if ref and ref != sha:
            self.flag_check(n, f"{outcome} at {ref[:8]}, head now {sha[:8]}: pushes since may be unreviewed")

    def defer(self, n, e, asked, command, why, wait, outcome):
        """The PR is asked again (the same command) `wait` seconds from now and the pass goes on;
        after PR_CAP deferrals it is parked instead. Counts toward the circuit breaker."""
        k = e.get("timeouts", 0) + 1
        at = self.now() + datetime.timedelta(seconds=wait)
        e.update(outcome=outcome, timeouts=k, defer_req=iso(asked), retry_at=iso(at), defer_command=command,
                 message=why[:600])
        if k >= PR_CAP:
            e.update(outcome=PARKED, parked_why=why[:600])
            for key in ("retry_at",):
                e.pop(key, None)
            self.save()
            self.enqueue(n, f"parked after {k} tries: {why[:200]}")
            self.log(f"#{n}: {why}; parked after {k} tries (not asked again; --unpark {n} after a look)")
        else:
            self.save()
            self.log(f"#{n}: {why}; asking again after {at:%Y-%m-%d %H:%M}Z (deferral {k}), going on with the next PR")
        return "halt" if self.bad(n, why) else "defer"

    # -- one PR ----------------------------------------------------------------------------
    def one(self, n):
        """Returns 'done' (a new review, full or not, or a clean one with 0 comments), 'skip'
        (nothing to do: reviewed at this diff, parked, or skipped by CodeRabbit), 'none' (no review
        available, or nothing new on a re-review), 'defer' (no answer or an unexpected one: asked
        again later), 'pause' (a quota message: the loop pauses until self.paused_until), 'halt'
        (the circuit breaker tripped: nothing more until --resume), or 0 in a dry run when the
        next step would post or wait."""
        e = self.state.setdefault(str(n), {"pr": n, "posts": []})
        if self.halted():
            return "halt"
        unpark = self.control().get("unpark") or []
        if n in unpark:
            for k in ("timeouts", "parked_why"):
                e.pop(k, None)
            e.update(outcome="unparked", retry_at=iso(self.now()))  # due now: the request is posted again
            self.save()
            self.control(lambda c: c.update(unpark=[x for x in (c.get("unpark") or []) if x != n]))
            self.log(f"#{n}: unparked, asking again")
        if e.get("outcome") == PARKED:
            if self.dry:
                self.log(f"#{n}: parked ({e.get('parked_why', '')[:80]}), skipping")
            return "skip"
        pr = self.hub.get(f"pulls/{n}")
        if pr.get("state", "open") != "open":
            if self.dry:
                self.log(f"#{n}: {pr.get('state')}, skipping")
            return "skip"
        sha = pr["head"]["sha"]
        if e.get("outcome") in SETTLED and not e.get("reviewed_fp"):
            # settled by the loop before diffs were recorded: never asked again on a guess
            post = (e.get("posts") or [{}])[-1]
            ref = post.get("head")
            if e["outcome"] == "reviewed":
                reviews = self.reviews_after(n, None)
                ref = (reviews[-1].get("commit_id") if reviews else None) or ref
            self.adopt(n, e, sha, ref, e["outcome"])
            self.log(f"#{n}: {e['outcome']} before diffs were recorded, taking this diff as reviewed")
            return "skip"
        if e.get("outcome") in SETTLED:
            if not self.rereview or sha == e.get("fp_head"):
                if self.dry:
                    self.log(f"#{n}: {e['outcome']} at this diff, skipping")
                return "skip"
            fp = self.fingerprint(n)
            if fp == e["reviewed_fp"]:
                e["fp_head"] = sha
                self.save()
                self.log(f"#{n}: new head {sha[:8]}, its own diff is unchanged since the review, skipping")
                return "skip"
            self.log(f"#{n}: its own diff changed since the review ({e['outcome']} at {e.get('reviewed_at')}), "
                     f"asking for a new review")
            e.update(outcome="changed", rereview_after=e.get("reviewed_at") or iso(self.now()))
            self.save()
        # a re-review looks only at what happened after the last review (kept until it settles)
        after = ts(e["rereview_after"]) if e.get("rereview_after") else None
        asked_before = bool(e.get("posts")) and e.get("outcome") not in SETTLED
        reviews = self.reviews_after(n, after)
        post = (e.get("posts") or [{}])[-1]
        post_at = ts(post["at"]) - datetime.timedelta(seconds=10) if asked_before and post.get("at") else None
        new = [r for r in reviews if post_at is None or ts(r["submitted_at"]) > post_at]
        if new and (after is not None or asked_before):
            # our request was answered while the loop was not watching
            since = post_at or after
            return self.reviewed_now(n, e, ("review", new[-1]["id"], self.inline_after(n, since),
                                            ts(new[-1]["submitted_at"])), post, rereview=after is not None)
        if reviews and after is None and not asked_before:
            # reviewed before diffs were recorded (or by hand): this diff counts as reviewed
            self.adopt(n, e, sha, reviews[-1].get("commit_id"), "reviewed", note="has a CodeRabbit review on GitHub")
            self.log(f"#{n}: has a CodeRabbit review, skipping")
            return "skip"
        comments = self.hub.get_all(f"issues/{n}/comments")
        s = clean_summary(comments, sha)
        if s and (after is None or edited(s) > after):
            self.settle(n, e, CLEAN, post={"fp": self.fingerprint(n), "head": sha}, at=edited(s), inline=0,
                        note="reviewed, 0 comments (clean): the summary on GitHub covers the head")
            self.log(f"#{n}: reviewed at its head with 0 comments (clean), skipping")
            return "skip"
        req = self.latest_request(comments, after)
        if after is None:
            s = reviewed_summary(comments)
            if s and (req is None or (req[2] is not None and classify(req[2]["body"]) == "finished"
                                      and edited(s) >= req[0])):
                # a clean review of an earlier head, before diffs were recorded
                self.adopt(n, e, sha, (coverage(s["body"]) or {}).get("coveredCommitId"), CLEAN, inline=0,
                           note="reviewed, 0 comments (clean) at an earlier head, before diffs were recorded")
                self.log(f"#{n}: reviewed with 0 comments (clean) at an earlier head, taking this diff as reviewed")
                return "skip"
        if req:
            why = self.stale(e, req, comments, sha)
            if why and after is None and req[2] is not None and classify(req[2]["body"]) == "skip":
                # skipped (too large) at an older head: not asked again on a guess
                self.adopt(n, e, sha, self.post_for(e, req[0]).get("head") or "an older head", "skipped",
                           message=text(req[2]["body"])[:600])
                self.log(f"#{n}: skipped by CodeRabbit at an older head, taking this diff as skipped")
                return "skip"
            if why:
                # a request about an older head says nothing about this one: start again with `review`
                self.log(f"#{n}: our {req[1]} at {req[0]:%Y-%m-%d %H:%M:%S} was about an older head than "
                         f"{sha[:8]} ({why}), treating the PR as never requested")
                req = None
        retries, pause, command, ans, asked = 0, 0, REVIEW, None, None
        if req and e.get("defer_req") and req[0] <= ts(e["defer_req"]) + datetime.timedelta(seconds=120) \
                and (req[2] is None or classify(req[2]["body"]) in ("processing", "quota", "unexpected", "halt")):
            # deferred (or halted and resumed): ask again once its time has come
            if e.get("retry_at") and self.now() < ts(e["retry_at"]):
                self.log(f"#{n}: deferred ({e.get('outcome')}), asking again after {e['retry_at']}")
                return "defer" if not self.dry else "skip"
            command, req = req[1], None
            self.log(f"#{n}: deferral over, asking again")
        max_wait = REASK_WAIT if e.get("timeouts") else MAX_WAIT
        if req:
            asked, command, reply = req
            kind = classify(reply["body"]) if reply else "processing"
            if kind == "finished" and self.now() >= edited(reply) + datetime.timedelta(seconds=FINISHED_GRACE):
                ans = ("finished",)  # not clean: a clean summary at the head was skipped above
            elif kind in ("full", "quota", "unexpected", "halt"):
                ans = ("reply", kind, reply["body"])
            elif kind in ("processing", "finished"):
                state = "no reply yet" if not reply else text(reply["body"])[:60]
                if self.dry:
                    if self.now() - asked >= datetime.timedelta(seconds=max_wait):
                        self.log(f"#{n}: our {command} at {asked:%Y-%m-%d %H:%M} got no review ({state}); "
                                 f"would defer it and ask again")
                        return "defer"
                    self.log(f"#{n}: would wait: our {command} at {asked:%H:%M:%S} is still processing ({state})")
                    return 0
                self.log(f"#{n}: our {command} at {asked:%H:%M:%S} is still processing, waiting for it")
                ans = self.wait_answer(n, asked, max_wait)  # handled below like the answer to a new post
            elif kind == "limit":
                retries = 1
                pause = max(0, (ts(reply["created_at"]) + datetime.timedelta(seconds=limit_wait(reply["body"])) - self.now()).total_seconds())
                if self.dry:
                    at = self.now() + datetime.timedelta(seconds=pause)
                    self.log(f"#{n}: limit reply at {ts(reply['created_at']):%H:%M:%S}; would post {command} at {at:%H:%M:%S}")
                    return 0
            elif kind == "skip":
                self.settle(n, e, "skipped", post=self.post_for(e, asked), at=edited(reply), message=text(reply["body"])[:600])
                self.log(f"#{n}: skipped by CodeRabbit: {text(reply['body'])[:120]}")
                return "skip"
        if ans is None and self.dry:
            self.log(f"#{n}: would post {command}")
            return 0
        while True:
            if ans is None:
                if pause:
                    e["outcome"] = "waiting"; self.save()
                    until = self.now() + datetime.timedelta(seconds=pause)
                    note = f", still limited after {retries - 1} retries" if retries > LIMIT_RETRIES else ""
                    self.log(f"#{n}: limit, waiting {pause / 60:.0f} min until {until:%H:%M}Z (retry {retries}{note})")
                    self.status(phase="rate limited", pr=n, until=iso(until))
                    self.sleep_until(until)
                    pause = 0
                if self.halted():
                    return "halt"
                pr = None
                while pr is None:
                    pr = self.hub.get(f"pulls/{n}")  # still open? (it may have been merged meanwhile)
                    if pr.get("state", "open") != "open":
                        self.log(f"#{n}: {pr.get('state')} meanwhile, not posting")
                        return "skip"
                    if self.spend_post(n):
                        pr = None  # waited for the next day: look again before posting
                head = pr["head"]["sha"]
                fp = self.fingerprint(n) if self.rereview else None
                out = self.hub.comment(n, command)
                asked = self.posted_at(out)
                e.setdefault("posts", []).append({"command": command, "at": iso(asked), "head": head, "fp": fp})
                e["outcome"] = "pending"
                self.save()
                self.log(f"#{n}: posted {command}")
                ans = self.wait_answer(n, asked, max_wait)
            ans, answer = None, ans
            post = self.post_for(e, asked)
            if answer[0] == "review":
                return self.reviewed_now(n, e, answer, post, rereview=after is not None)
            if answer[0] == "clean":
                self.settle(n, e, CLEAN, post=post, at=answer[1], inline=0,
                            note="reviewed, 0 comments (clean): the summary covers the head")
                self.log(f"#{n}: reviewed, 0 comments (clean): CodeRabbit found nothing, nothing to triage")
                return "done"
            if answer[0] == "timeout":
                k = e.get("timeouts", 0)
                return self.defer(n, e, asked, command, answer[1], min(RETRY_FIRST * 2 ** k, RETRY_MAX), "timeout")
            if no_review(answer):
                why = ("finished without a review" if answer[0] == "finished"
                       else f"was refused as already reviewed ({text(answer[2])[:80]})")
                if after is not None:
                    self.settle(n, e, NOTHING_NEW, post=post, message=f"{command} {why}")
                    self.log(f"#{n}: {NOTHING_NEW}: the re-review {why}, going on with the next PR")
                    return "none"
                if command == FULL:
                    self.settle(n, e, NO_REVIEW, post=post, message=f"{FULL} {why}")
                    self.log(f"#{n}: {NO_REVIEW}: {FULL} {why}, going on with the next PR")
                    return "none"
                if self.dry:
                    self.log(f"#{n}: {REVIEW} {why}; would post {FULL}")
                    return 0
                self.log(f"#{n}: {REVIEW} {why}, asking for a full review")
                command, retries = FULL, 0
                continue
            kind, body = answer[1], answer[2]
            msg = text(body)
            e["message"] = msg[:600]
            if kind == "limit":
                self.good()
                retries += 1
                pause = limit_wait(body)
                continue
            if kind == "skip":
                self.settle(n, e, "skipped", post=post, message=msg[:600])
                self.log(f"#{n}: skipped: {msg[:120]}")
                return "skip"
            if kind == "halt":
                e.update(outcome="halted", defer_req=iso(asked), defer_command=command)
                e.pop("retry_at", None)
                self.save()
                self.trip(f"#{n}: CodeRabbit refuses reviews: {msg[:200]}")
                return "halt"
            if kind == "quota":
                r = self.defer(n, e, asked, command, f"a quota or plan message ({msg[:160]})", self.quota_pause, "quota")
                self.paused_until = self.now() + datetime.timedelta(seconds=self.quota_pause)
                return "halt" if r == "halt" else "pause"
            return self.defer(n, e, asked, command, f"unexpected answer ({msg[:200]})", UNEXPECTED_RETRY, "unexpected")

    def reviewed_now(self, n, e, ans, post, rereview=False):
        self.settle(n, e, "reviewed", post=post, at=ans[3], review_id=ans[1], inline=ans[2])
        self.enqueue(n, "re-review" if rereview else "review", ans[1], ans[2])
        self.log(f"#{n}: reviewed, {ans[2]} inline comments{' (re-review)' if rereview else ''}; queued for triage")
        return "done"


def self_test():
    """Checks the reply matching against sample comments (bodies as CodeRabbit sent them on #193
    and #191, 2026-11-03, and #217, 2026-11-10), and the full-review path against a simulated
    CodeRabbit (no GitHub, no sleeping)."""
    note = ("\n\n> Note: CodeRabbit is an incremental review system and does not re-review already reviewed "
            "commits. This command is applicable only when automatic reviews are paused.\n\n")

    def reply(summary, line, invocation=True, note=note):
        inv = "<!-- CodeRabbit review command invocation: v2:abc -->\n" if invocation else ""
        return ("<!-- coderabbit-ai-generated:v1 -->\n\n" + REPLY_MARKER + "\n" + inv +
                f"<details>\n<summary>{summary}</summary>\n<dl>\n<dd>\n<br>\n\n{line}{note}</dd>\n</dl>\n</details>")

    limited = reply("Action not completed", "Review rate limited.")
    triggered = reply("Action performed", "Review triggered.")
    finished = reply("Action performed", "Review finished.")
    chat = ("<!-- coderabbit-ai-generated:v1 -->\n\n" + REPLY_MARKER + "\n### Rate Limit Exceeded\n\n"
            "`@ukwikidev` have exceeded the limit for the number of chat messages per hour. "
            "Please wait **7 minutes and 9 seconds** before sending another message.")
    summary = ("<!-- coderabbit-ai-generated:v1 -->\n" + SUMMARY_MARKER + "\n"
               "> [!NOTE]\n> Currently processing new changes in this PR.\n"
               "- Add rate limited retries; review limit reached is handled. Please wait 5 minutes.")
    chat_answer = ("<!-- coderabbit-ai-generated:v1 -->\n\n" + REPLY_MARKER + "\n"
                   "`@ukwikidev` Good point: the rate limited path should wait 5 minutes.")
    bot, us = {"login": "coderabbitai[bot]"}, {"login": OURS}
    since = ts("2026-11-03T18:47:38Z")

    def c(body, created, user=bot):
        return {"user": user, "body": body, "created_at": created, "updated_at": "2026-11-03T19:07:32Z"}

    checks = []

    def check(name, got, want):
        checks.append((name, got == want, got, want))

    check("classify: rate limited", classify(limited), "limit")
    check("classify: triggered", classify(triggered), "processing")
    check("classify: finished (the reply on #215 and #217)", classify(finished), "finished")
    check("classify: chat limit", classify(chat), "limit")
    check("classify: skipped", classify(reply("Action not completed", "Review skipped: too large.")), "skip")
    check("stated wait: none", stated_wait(limited), None)
    check("stated wait: bold minutes and seconds", stated_wait(chat), 7 * 60 + 9)
    check("stated wait: bold minutes", stated_wait("Next included review available in **12 minutes**."), 12 * 60)
    check("stated wait: plain", stated_wait("Next included review available in 12 minutes"), 12 * 60)
    check("limit wait: no time", limit_wait(limited), LIMIT_SPACING)
    check("limit wait: stated", limit_wait(chat), 7 * 60 + 9 + 120)
    comments = [
        c(summary, "2026-11-03T08:47:28Z"),                      # summary, edited after the request
        c("@coderabbitai review", "2026-11-03T18:47:38Z", us),
        c(triggered, "2026-11-03T18:47:45Z"),
        c(chat_answer, "2026-11-03T18:50:00Z"),                  # a chat answer, not a command reply
        c(limited, "2026-11-03T17:59:42Z"),                      # a reply to an earlier request
    ]
    got = command_replies(comments, since)
    check("command replies: only the acknowledgement", [classify(x["body"]) for x in got], ["processing"])
    check("command replies: summary never matched", any(SUMMARY_MARKER in x["body"] for x in got), False)
    check("command replies: chat limit matched",
          [classify(x["body"]) for x in command_replies([c(chat, "2026-11-03T18:47:44Z")], since)], ["limit"])
    check("command replies: nothing after the request",
          command_replies([c(summary, "2026-11-03T08:47:28Z"), c(limited, "2026-11-03T17:59:42Z")], since), [])
    reworded = "\n\n> Note: CodeRabbit reviews incrementally and won't look at already reviewed commits again.\n\n"
    check("classify: reworded note, triggered", classify(reply("Action performed", "Review triggered.", note=reworded)),
          "processing")
    check("classify: reworded note, finished", classify(reply("Action performed", "Review finished.", note=reworded)),
          "finished")
    check("classify: reworded note, rate limited",
          classify(reply("Action not completed", "Review rate limited.", note=reworded)), "limit")
    check("classify: already reviewed (explicit)",
          classify(reply("Action not completed", "These commits have already been reviewed.")), "full")
    head = "48fdd8faf81bfa2f588ac414a9560a11d3f3250c"

    def summary_at(sha, kind="reviewed"):
        return ("<!-- coderabbit-ai-generated:v1 -->\n\n" + SUMMARY_MARKER + "\n<!-- recent_review_status_start -->\n\n"
                "No actionable comments were generated in the recent review.\n\n<!-- recent_review_status_end -->\n"
                '<!-- final_review_risk_coverage:{"sourceCommitId":"%s","coveredCommitId":"%s","kind":"%s"} -->'
                % (sha, sha, kind))
    check("clean summary: at the head", clean_summary([c(summary_at(head), "2026-11-03T08:58:04Z")], head) is not None, True)
    check("clean summary: an older commit", clean_summary([c(summary_at("a79c0961"), "2026-11-03T08:58:04Z")], head), None)
    check("clean summary: not kind reviewed",
          clean_summary([c(summary_at(head, "skipped"), "2026-11-03T08:58:04Z")], head), None)
    check("clean summary: a reply, not the summary",
          clean_summary([c(summary_at(head).replace(SUMMARY_MARKER, REPLY_MARKER), "2026-11-03T08:58:04Z")], head), None)
    check("request command: review", request_command("@coderabbitai review"), REVIEW)
    check("request command: full review", request_command(" @coderabbitai  full review\n"), FULL)
    check("request command: other", request_command("@coderabbitai resolve"), REVIEW)
    check("no review: finished", no_review(("finished",)), True)
    check("no review: limit reply", no_review(("reply", "limit", limited)), False)

    # The loop against a simulated CodeRabbit. Each post gets the next outcome of its PR's script
    # ("finished" once the script runs out): every outcome but "limit" and "silent" gets "Review
    # triggered." 7 s later, edited to "Review finished." at 600 s.
    #   finished   no review;
    #   review     a review (and one inline comment) at 590 s;
    #   review@N   a review N s after the "Review finished." edit;
    #   clean      the summary edited at 596 s to cover the head ("No actionable comments"), no review;
    #   clean+review@N   both;
    #   limit      "Review rate limited." at 7 s;  silent   no reply at all.
    #   limit@N    "Review rate limited. Next review available in N minutes." at 7 s;
    #   quota      a monthly quota message at 7 s;
    #   late@N     no reply to the command, a review N s after the request (CodeRabbit was slow);
    #   stuck      "Review triggered." at 7 s and nothing more;
    #   paused     "Reviews are paused for this repository." at 7 s;
    #   skipped    "Review skipped: 110 files exceed the limit of 100." at 7 s.
    # GitHub's timestamps are the simulated clock plus `skew` (GitHub's clock behind ours if < 0).
    def fmt(t):
        return f"{t:%Y-%m-%dT%H:%M:%SZ}"

    class Clock:
        def __init__(self):
            self.t = ts("2026-11-10T04:36:51Z")

        def now(self):
            return self.t

        def sleep(self, s):
            self.t += datetime.timedelta(seconds=s)

    class Rabbit:
        def __init__(self, clock, script):
            self.clock, self.items, self.posted = clock, [], []
            self.script = {n: list(outs) for n, outs in script.items()}
            self.summaries = {}
            self.heads = {}  # pr -> sha other than `head`; commit sha -> committer date
            self.dates = {head: clock.t - datetime.timedelta(days=8)}
            self.diffs = {}   # pr -> the +line of its one-file diff (default "v1")
            self.failures = 0  # the next this many reads raise like a dropped network after gh's retries
            self.reads = 0
            self.skew = datetime.timedelta(0)
            self.cids = {}    # posted comment id -> its created_at
            self.closed = set()  # PRs merged or closed

        def add(self, path, item, start, end=None):
            entry = [start, end, path, item]
            self.items.append(entry)
            return entry

        def push(self, n, ago, diff=None):
            """A new head commit on PR n, committed `ago` seconds before now; `diff` changes the
            PR's own diff (else it is a merge of the base: the head moves, the diff stays)."""
            k = len([x for x in self.dates if x.startswith(f"{n:04d}")])
            sha = f"{n:04d}" + "e" * (36 - len(str(k))) + (str(k) if k else "e")
            self.heads[n] = sha
            self.dates[sha] = self.clock.t - datetime.timedelta(seconds=ago)
            if diff:
                self.diffs[n] = diff

        def read(self):
            self.reads += 1
            if self.failures:
                self.failures -= 1
                raise RuntimeError("gh api repos/o/r/pulls/1: network still failing")

        def get(self, path):
            self.read()
            m = re.match(r"pulls/(\d+)$", path)
            if m:
                n = int(m.group(1))
                return {"head": {"sha": self.heads.get(n, head)}, "state": "closed" if n in self.closed else "open"}
            m = re.match(r"issues/comments/(\d+)$", path)
            if m:
                return {"created_at": self.cids[int(m.group(1))]}
            sha = path.split("/")[-1]
            return {"commit": {"committer": {"date": fmt(self.dates[sha])}}}

        def get_all(self, path):
            self.read()
            m = re.match(r"pulls/(\d+)/files$", path)
            if m:
                return [{"filename": "app/A.scala", "status": "modified", "sha": "b1",
                         "patch": f"@@ -1,3 +1,3 @@\n ctx\n-old\n+{self.diffs.get(int(m.group(1)), 'v1')}"}]
            t = self.clock.t
            return [dict(i) for s, e, p, i in self.items if p == path and s <= t and (e is None or t < e)]

        def summary(self, n, sha, at, marker=True):
            """The summary comment, created 2026-11-03 and edited at `at` to cover `sha`; without
            `marker`, as on a PR CodeRabbit never reviewed (no coverage marker; #245, #270, #300)."""
            old = self.summaries.get(n)
            if old:
                old[1] = at
            created = "2026-11-03T08:58:04Z"
            body = summary_at(sha) if marker else SUMMARY_MARKER + "\nReview skipped: base branch is not the default."
            self.summaries[n] = self.add(f"issues/{n}/comments", {"user": bot, "body": body,
                                         "created_at": created, "updated_at": fmt(at + self.skew)}, at)

        def answer(self, n, body, at, outcome):
            def sec(s):
                return at + datetime.timedelta(seconds=s)

            def g(s):  # GitHub's timestamp s seconds after the request
                return fmt(sec(s) + self.skew)
            path = f"issues/{n}/comments"
            self.add(path, {"user": us, "body": body, "created_at": g(0), "updated_at": g(0)}, at)
            if outcome == "silent":
                return
            lines = {"paused": ("Action not completed", "Reviews are paused for this repository."),
                     "skipped": ("Action not completed", "Review skipped: 110 files exceed the limit of 100."),
                     "stuck": ("Action performed", "Review triggered.")}
            if outcome in lines:
                self.add(path, {"user": bot, "body": reply(*lines[outcome]), "created_at": g(7), "updated_at": g(7)}, sec(7))
                return
            m = re.match(r"(limit|quota)@?(\d*)$", outcome)
            if m and outcome != "limit":
                line = ("You have used your monthly review quota. Upgrade your plan to continue."
                        if m.group(1) == "quota" else f"Review rate limited. Next review available in {m.group(2)} minutes.")
                body = reply("Action not completed", line)
                self.add(path, {"user": bot, "body": body, "created_at": g(7), "updated_at": g(7)}, sec(7))
                return
            m = re.match(r"late@(\d+)$", outcome)
            if m:
                rv = sec(int(m.group(1)))
                self.add(f"pulls/{n}/reviews", {"user": bot, "id": 900 + n + len(self.items), "submitted_at": fmt(rv + self.skew)}, rv)
                self.add(f"pulls/{n}/comments", {"user": bot, "created_at": fmt(rv + self.skew)}, rv)
                return
            if outcome == "limit":
                self.add(path, {"user": bot, "body": limited, "created_at": g(7), "updated_at": g(7)}, sec(7))
                return
            self.add(path, {"user": bot, "body": triggered, "created_at": g(7), "updated_at": g(7)},
                     sec(7), sec(600))
            self.add(path, {"user": bot, "body": finished, "created_at": g(7), "updated_at": g(600)},
                     sec(600))
            if outcome.startswith("clean"):
                self.summary(n, self.heads.get(n, head), sec(596))
            m = re.search(r"review(?:@(\d+))?$", outcome)
            if m:
                rv = sec(590) if m.group(1) is None else sec(600 + int(m.group(1)))
                self.add(f"pulls/{n}/reviews", {"user": bot, "id": 900 + n + len(self.items), "submitted_at": fmt(rv + self.skew),
                                                "commit_id": self.heads.get(n, head)}, rv)
                self.add(f"pulls/{n}/comments", {"user": bot, "created_at": fmt(rv + self.skew)}, rv)

        def comment(self, n, body):
            self.posted.append((n, body))
            outs = self.script.get(n) or []
            self.answer(n, body, self.clock.t, outs.pop(0) if outs else "finished")
            cid = 5000 + len(self.cids)
            self.cids[cid] = fmt(self.clock.t + self.skew)
            return f"https://github.com/o/r/pull/{n}#issuecomment-{cid}\n"

    box = []

    def case(script=None, earlier=(), dry=False, last=1, batch=5, pushes=None, state=None, setup=None, passes=None,
             between=None, folder=None, rereview=True, posts_per_day=POSTS_PER_DAY, skew=0):
        """earlier: (pr, command, seconds ago, outcome) requests already on GitHub (the state is
        rebuilt from them); pushes: {pr: seconds ago} new head commits after them. Every PR starts
        with a summary comment without a coverage marker. passes: run watch mode for that many
        passes instead of one --batch pass; between(hub, clock, loop) runs at each idle sleep."""
        clock = Clock()
        hub = Rabbit(clock, script or {})
        hub.skew = datetime.timedelta(seconds=skew)
        for pr in range(1, last + 1):
            hub.summary(pr, "a79c09613e71867f369019f6dc428c5634b5c0ea", clock.t - datetime.timedelta(days=7), marker=False)
        for pr, command, ago, outcome in earlier:
            hub.answer(pr, command, clock.t - datetime.timedelta(seconds=ago), outcome)
        for pr, ago in (pushes or {}).items():
            hub.push(pr, ago)
        if setup:
            setup(hub, clock)
        lines = []
        loop = Loop(hub, None, dry, clock=clock, logger=lines.append, folder=folder, rereview=rereview,
                    posts_per_day=posts_per_day)
        loop.state = state(clock) if state else {}
        box[:] = [loop, hub, clock]
        if passes:
            if between:
                def idle_hook(sec, sleep=clock.sleep):
                    if loop.st.get("phase") == "idle" and not loop.st.get("hooked"):
                        loop.st["hooked"] = True
                        between(hub, clock, loop)
                    if loop.st.get("phase") != "idle":
                        loop.st.pop("hooked", None)
                    sleep(sec)
                loop.sleep = idle_hook
            code = watch(loop, lambda: list(range(1, last + 1)), passes=passes)
        else:
            code = run(loop, range(1, last + 1), batch)
        return code, hub.posted, {k: v.get("outcome") for k, v in loop.state.items()}, lines

    def logged(lines, s):
        return any(s in x for x in lines)

    code, posted, outcomes, _ = case({1: ["finished", "review"]})
    check("full review: review finished without one, full review done",
          (code, posted, outcomes), (0, [(1, REVIEW), (1, FULL)], {"1": "reviewed"}))
    code, posted, outcomes, lines = case()
    check("full review: also no review, recorded and the loop goes on",
          (code, posted, outcomes, logged(lines, NO_REVIEW), lines[-1]),
          (0, [(1, REVIEW), (1, FULL)], {"1": NO_REVIEW}, True, "all PRs done"))
    code, posted, outcomes, _ = case({n: ["finished", "review"] for n in (1, 2, 3)}, last=3, batch=2)
    check("full review: completed full reviews count toward --batch",
          (code, posted[-1], len(posted)), (0, (2, FULL), 4))
    code, posted, outcomes, _ = case({2: ["finished", "review"], 3: ["review"]}, last=3, batch=1)
    check("full review: no review available does not count toward --batch",
          (code, posted, outcomes),
          (0, [(1, REVIEW), (1, FULL), (2, REVIEW), (2, FULL)], {"1": NO_REVIEW, "2": "reviewed"}))
    code, posted, outcomes, lines = case({1: ["clean"]}, last=2, batch=1)
    check("clean: summary-only \"No actionable comments\" counts as a review, no full review",
          (code, posted, outcomes, logged(lines, "0 comments (clean)"), lines[-1]),
          (0, [(1, REVIEW)], {"1": CLEAN}, True, "1 reviews done, handing back for triage"))
    code, posted, outcomes, _ = case({2: ["review"]}, earlier=[(1, REVIEW, 3600, "clean")], last=2, batch=1)
    check("clean: rebuilt from GitHub, skipped like a reviewed PR and not counted",
          (code, posted, outcomes), (0, [(2, REVIEW)], {"1": CLEAN, "2": "reviewed"}))
    code, posted, outcomes, _ = case({1: ["clean+review@120"]})
    check("grace: a review 2 min after \"Review finished\" (and a clean summary) is the review",
          (code, posted, outcomes), (0, [(1, REVIEW)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case({1: ["review@400"]})
    check("grace: a review after the grace answers the full review",
          (code, posted, outcomes), (0, [(1, REVIEW), (1, FULL)], {"1": "reviewed"}))
    code, posted, outcomes, lines = case({1: ["limit"] * 4 + ["finished", "limit", "review"]})
    check("limit: the full review gets a fresh retry budget",
          (code, posted, outcomes, any("#1: limit, waiting 50 min" in x and "(retry 1)" in x for x in lines[9:])),
          (0, [(1, REVIEW)] * 5 + [(1, FULL)] * 2, {"1": "reviewed"}, True))
    code, posted, outcomes, lines = case({1: ["review"]}, earlier=[(1, REVIEW, 7200, "finished"), (1, FULL, 600, "limit")])
    check("restart: a limited full review waits out the rest and posts full review again",
          (code, posted, outcomes, logged(lines, "waiting 40 min until")),
          (0, [(1, FULL)], {"1": "reviewed"}, True))
    code, posted, outcomes, lines = case({1: ["review"]}, earlier=[(1, REVIEW, 3, "limit")])
    check("restart: an earlier request answered with a limit while waiting: pause, then post",
          (code, posted, outcomes, logged(lines, "waiting 50 min until")),
          (0, [(1, REVIEW)], {"1": "reviewed"}, True))
    code, posted, outcomes, lines = case(earlier=[(1, REVIEW, 3, "silent")])
    check("restart: an earlier request with no reply is deferred (not an exit), nothing posted",
          (code, posted, outcomes, logged(lines, "asking again after")), (0, [], {"1": "timeout"}, True))
    code, posted, outcomes, _ = case(earlier=[(1, REVIEW, 7200, "finished"), (1, FULL, 3, "review")])
    check("restart: an unanswered full review is waited for, not posted again",
          (code, posted, outcomes), (0, [], {"1": "reviewed"}))
    code, posted, outcomes, _ = case(earlier=[(1, REVIEW, 7200, "finished"), (1, FULL, 3, "finished")])
    check("restart: an unanswered full review that ends without one: no review available",
          (code, posted, outcomes), (0, [], {"1": NO_REVIEW}))
    code, posted, outcomes, _ = case({1: ["review"]}, earlier=[(1, REVIEW, 3600, "finished")])
    check("rebuild: a finished review request gets a full review",
          (code, posted, outcomes), (0, [(1, FULL)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case({1: ["review"]}, earlier=[(1, REVIEW, 11 * 60, "finished")])
    check("rebuild: a review request finished just now: wait out the grace, then full review",
          (code, posted, outcomes), (0, [(1, FULL)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case(earlier=[(1, FULL, 3600, "finished")])
    check("rebuild: a finished full review request is no review available, nothing posted",
          (code, posted, outcomes), (0, [], {"1": NO_REVIEW}))
    code, posted, outcomes, _ = case({1: ["review"]}, earlier=[(1, REVIEW, 7200, "finished")], pushes={1: 3600})
    check("new head: after a finished review, post review (not full review)",
          (code, posted, outcomes), (0, [(1, REVIEW)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case({1: ["review"]}, earlier=[(1, REVIEW, 9000, "finished"), (1, FULL, 7200, "finished")],
                                     pushes={1: 3600})
    check("new head: after a full review with no review available, post review again",
          (code, posted, outcomes), (0, [(1, REVIEW)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case({1: ["clean"]}, earlier=[(1, REVIEW, 7200, "clean")], pushes={1: 3600})
    check("new head: a clean review at an earlier head with no recorded diff counts as reviewed",
          (code, posted, outcomes), (0, [], {"1": CLEAN}))
    code, posted, _, lines = case(earlier=[(1, FULL, 7200, "finished")], pushes={1: 3600}, dry=True)
    check("new head: dry run says why and would post review",
          (code, posted, "older than the head" in lines[0], lines[-1]), (0, [], True, f"#1: would post {REVIEW}"))
    code, posted, outcomes, _ = case(earlier=[(1, FULL, 600, "finished")], pushes={1: 3600})
    check("new head: a request after the head commit still counts",
          (code, posted, outcomes), (0, [], {"1": NO_REVIEW}))
    # a commit made 2.5 h ago, pushed after our request 2 h ago: its committer date can't tell
    def posted_at(head_sha):
        return lambda clock: {"1": {"pr": 1, "outcome": "pending", "posts": [
            {"command": REVIEW, "at": fmt(clock.t - datetime.timedelta(seconds=7195)), "head": head_sha}]}}
    code, posted, outcomes, _ = case({1: ["review"]}, earlier=[(1, REVIEW, 7200, "finished")], pushes={1: 9000},
                                     state=posted_at(head))
    check("new head: the state's post recorded another head: post review",
          (code, posted, outcomes), (0, [(1, REVIEW)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case({1: ["review"]}, earlier=[(1, REVIEW, 7200, "finished")], pushes={1: 9000},
                                     state=posted_at("0001" + "e" * 36))
    check("new head: the state's post recorded this head: the request counts (full review)",
          (code, posted, outcomes), (0, [(1, FULL)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case({1: ["review"]}, earlier=[(1, REVIEW, 7200, "finished")], pushes={1: 9000})
    check("new head: without state or summary, an older commit date can't tell (full review)",
          (code, posted, outcomes), (0, [(1, FULL)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case({1: ["review"]}, earlier=[(1, REVIEW, 7200, "clean")], pushes={1: 9000})
    check("new head: without state, a clean review at an older commit counts as reviewed (no guessing)",
          (code, posted, outcomes), (0, [], {"1": CLEAN}))
    code, posted, outcomes, _ = case(earlier=[(1, REVIEW, 60, "review")], setup=lambda hub, clock: hub.summary(
        1, "a79c09613e71867f369019f6dc428c5634b5c0ea", clock.t - datetime.timedelta(seconds=30)))
    check("new head: a processing request with an old-coverage summary edit is waited for",
          (code, posted, outcomes), (0, [], {"1": "reviewed"}))
    code, posted, _, lines = case(earlier=[(1, REVIEW, 3600, "finished")], dry=True)
    check("dry run: would post a full review",
          (code, posted, lines[-1]), (0, [], f"#1: {REVIEW} finished without a review; would post {FULL}"))
    code, posted, _, lines = case(earlier=[(1, REVIEW, 3600, "clean")], dry=True, last=2)
    check("dry run: a clean PR is skipped",
          (code, posted, lines), (0, [], ["#1: reviewed at its head with 0 comments (clean), skipping",
                                          f"#2: would post {REVIEW}"]))
    code, posted, _, lines = case(earlier=[(1, FULL, 3600, "finished")], dry=True, last=2)
    check("dry run: past no review available to the next PR",
          (code, posted, lines[-1]), (0, [], f"#2: would post {REVIEW}"))
    code, posted, _, lines = case(earlier=[(1, FULL, 60, "finished")], dry=True)
    check("dry run: a full review request still processing: would wait",
          (code, posted, lines[-1].split(" at ")[0]), (0, [], f"#1: would wait: our {FULL}"))

    # --- continuous (watch) mode, waits instead of exits, resume, diff-based re-requests ---
    code, posted, outcomes, lines = case({n: ["review"] for n in (1, 2, 3)}, last=3, passes=1)
    check("watch: no stop after N reviews, every PR requested in one pass, all queued for triage",
          (code, posted, outcomes, sorted(box[0].queue), logged(lines, "handing back")),
          (0, [(1, REVIEW), (2, REVIEW), (3, REVIEW)], {"1": "reviewed", "2": "reviewed", "3": "reviewed"},
           ["1", "2", "3"], False))
    code, posted, outcomes, lines = case({1: ["clean"], 2: ["finished", "finished"]}, last=2, passes=1)
    check("watch: clean and no-review PRs are not queued for triage",
          (outcomes, box[0].queue), ({"1": CLEAN, "2": NO_REVIEW}, {}))
    code, posted, outcomes, lines = case({1: ["silent", "review"], 2: ["review"]}, last=2, passes=2)
    check("watch: no reply in 30 min defers the PR, the pass goes on, the next pass asks again",
          (code, posted, outcomes, logged(lines, "asking again after"), logged(lines, "deferral over")),
          (0, [(1, REVIEW), (2, REVIEW), (1, REVIEW)], {"1": "reviewed", "2": "reviewed"}, True, True))
    code, posted, outcomes, lines = case({1: ["silent", "silent", "review"]}, passes=4)
    check("watch: repeated timeouts back off (30, then 60 min)",
          (posted, outcomes, [x.split("(")[-1] for x in lines if "going on with the next PR" in x]),
          ([(1, REVIEW)] * 3, {"1": "reviewed"}, ["deferral 1), going on with the next PR",
                                                 "deferral 2), going on with the next PR"]))
    code, posted, outcomes, lines = case({1: ["review"]}, passes=1, setup=lambda hub, clock: setattr(hub, "failures", 3))
    check("watch: GitHub/network errors become waits (1, 2, 4 min), then the pass runs",
          (code, posted, outcomes, logged(lines, "network still failing; trying again at"),
           len([x for x in lines if x.startswith("error")])),
          (0, [(1, REVIEW)], {"1": "reviewed"}, True, 3))
    code, posted, outcomes, lines = case({1: ["limit@300", "review"]})
    check("limit: a stated wait over 3 hours is waited out, not an exit",
          (code, posted, outcomes, logged(lines, "waiting 302 min")), (0, [(1, REVIEW)] * 2, {"1": "reviewed"}, True))
    code, posted, outcomes, lines = case({1: ["limit"] * 6 + ["review"]})
    check("limit: more than 4 refusals in a row keep it waiting, not an exit",
          (code, len(posted), outcomes, logged(lines, "still limited after 4 retries")), (0, 7, {"1": "reviewed"}, True))
    code, posted, outcomes, lines = case({1: ["quota", "review"]}, passes=2)
    check("quota: watch mode pauses 6 h and then asks again",
          (code, posted, outcomes, logged(lines, "paused until")), (0, [(1, REVIEW)] * 2, {"1": "reviewed"}, True))
    code, posted, outcomes, _ = case({1: ["quota"]})
    check("quota: --batch/--once stop with 4", (code, outcomes), (4, {"1": "quota"}))

    def jump_once(hub, clock, secs=4 * 3600):
        sleep, done = clock.sleep, []

        def jumpy(s):
            sleep(s + (0 if done else secs))
            done.append(1)
        clock.sleep = jumpy
    code, posted, outcomes, lines = case({1: ["late@14700"]}, setup=jump_once)
    check("laptop sleep: 4 h asleep during a wait is not a 30-min no-reply timeout",
          (code, posted, outcomes), (0, [(1, REVIEW)], {"1": "reviewed"}))

    def fp_of(line):
        return diff_fingerprint([{"filename": "app/A.scala", "status": "modified", "sha": "b1",
                                  "patch": f"@@ -9,3 +9,3 @@\n other ctx\n-old\n+{line}"}])
    check("diff fingerprint: hunk positions and context lines don't count",
          fp_of("v1"), diff_fingerprint([{"filename": "app/A.scala", "status": "modified", "sha": "b2",
                                           "patch": "@@ -1,3 +1,3 @@\n ctx\n-old\n+v1"}]))
    check("diff fingerprint: a changed line counts", fp_of("v1") == fp_of("v2"), False)

    def settled(outcome="reviewed", ago=86400):
        return lambda clock: {"1": {"pr": 1, "outcome": outcome, "reviewed_fp": fp_of("v1"), "fp_head": head,
                                    "reviewed_at": fmt(clock.t - datetime.timedelta(seconds=ago)), "posts": []}}
    code, posted, outcomes, lines = case({1: ["review"]}, earlier=[(1, REVIEW, 90000, "review")], pushes={1: 60},
                                         state=settled())
    check("re-review: a merge commit moved the head, the own diff is the same: nothing posted",
          (posted, outcomes, logged(lines, "own diff is unchanged")), ([], {"1": "reviewed"}, True))
    check("re-review: the new head is recorded, so the next pass does not fetch the diff again",
          box[0].state["1"]["fp_head"], box[1].heads[1])
    code, posted, outcomes, lines = case({1: ["review"]}, earlier=[(1, REVIEW, 90000, "review")], state=settled())
    check("re-review: the head did not move: skipped with one read",
          (posted, outcomes, box[1].reads), ([], {"1": "reviewed"}, 1))
    for prev in ("reviewed", CLEAN, NO_REVIEW, "skipped"):
        code, posted, outcomes, lines = case({1: ["review"]}, earlier=[(1, REVIEW, 90000, "review")],
                                             setup=lambda hub, clock: hub.push(1, 60, diff="v2"), state=settled(prev))
        check(f"re-review: the own diff changed after {prev}: post review (never full review), queue it",
              (posted, outcomes, [r["kind"] for r in box[0].queue.get("1", {}).get("reviews", [])]),
              ([(1, REVIEW)], {"1": "reviewed"}, ["re-review"]))
    code, posted, outcomes, lines = case({1: ["finished"]}, earlier=[(1, REVIEW, 90000, "review")],
                                         setup=lambda hub, clock: hub.push(1, 60, diff="v2"), state=settled())
    check("re-review: finished without a review is 'nothing new', no full review",
          (posted, outcomes, box[0].queue), ([(1, REVIEW)], {"1": NOTHING_NEW}, {}))
    code, posted, outcomes, lines = case({1: ["review"]}, earlier=[(1, REVIEW, 90000, "review")],
                                         setup=lambda hub, clock: hub.push(1, 60, diff="v2"), state=settled(),
                                         rereview=False)
    check("re-review: --no-rereview leaves a changed diff alone", (posted, outcomes), ([], {"1": "reviewed"}))

    def midway(clock):
        st = settled()(clock)
        st["1"].update(outcome="pending", rereview_after=fmt(clock.t - datetime.timedelta(seconds=86400)),
                       posts=[{"command": REVIEW, "at": fmt(clock.t - datetime.timedelta(seconds=60)),
                               "head": "0001" + "e" * 36, "fp": fp_of("v2")}])
        return st
    code, posted, outcomes, lines = case(earlier=[(1, REVIEW, 90000, "review"), (1, REVIEW, 60, "review")],
                                         setup=lambda hub, clock: hub.push(1, 120, diff="v2"), state=midway)
    check("resume: a re-review requested before a restart is waited for, not posted again or "
          "answered by the old review",
          (posted, outcomes, logged(lines, "still processing, waiting for it"), box[0].state["1"]["reviewed_fp"]),
          ([], {"1": "reviewed"}, True, fp_of("v2")))
    code, posted, outcomes, lines = case(earlier=[(1, REVIEW, 90000, "review")], passes=2,
                                         between=lambda hub, clock, loop: hub.push(1, 0, diff="v2"),
                                         script={1: ["review"]})
    check("resume: a PR reviewed before diffs were recorded takes its diff as reviewed (not queued); "
          "a later change of it is re-requested in the next pass",
          (posted, outcomes, [r["kind"] for r in box[0].queue["1"]["reviews"]]),
          ([(1, REVIEW)], {"1": "reviewed"}, ["re-review"]))

    def old_pending(clock):
        return {"1": {"pr": 1, "outcome": "pending",
                      "posts": [{"command": REVIEW, "at": fmt(clock.t - datetime.timedelta(seconds=7200)), "head": head}]}}
    code, posted, outcomes, lines = case(earlier=[(1, REVIEW, 7200, "review")], state=old_pending)
    check("resume: a review that came while the loop was down is recorded and queued",
          (posted, outcomes, sorted(box[0].queue)), ([], {"1": "reviewed"}, ["1"]))
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        folder = pathlib.Path(tmp)
        code, posted, outcomes, lines = case({1: ["review"]}, last=1, passes=5, folder=folder,
                                             between=lambda hub, clock, loop: (folder / "loop.stop").write_text("x"))
        check("stop: the stop file ends watch mode at the next sleep",
              (code, posted, lines[-1], read_json(folder / "loop-status.json", {}).get("phase"),
               sorted(read_json(folder / "triage-queue.json", {}))),
              (0, [(1, REVIEW)], "stop requested (--resume clears it), exiting", "stopped", ["1"]))

    # --- repost limits, halts, clocks, migration flags, shared files (review of 3881109b) ---
    check("classify: reviews paused is a halt", classify(reply("Action not completed", "Reviews are paused for this repository.")),
          "halt")
    check("classify: the note's 'automatic reviews are paused' is not a halt", classify(triggered), "processing")
    code, posted, outcomes, lines = case(last=3, script={1: ["silent"], 2: ["silent"], 3: ["silent"]}, batch=None)
    check("breaker: no answer on 3 different PRs in a row halts everything (exit 5 in a manual run)",
          (code, posted, logged(lines, "HALTED: 3 PRs in a row"), box[0].halted() is not None),
          (5, [(1, REVIEW), (2, REVIEW), (3, REVIEW)], True, True))
    code, posted, outcomes, lines = case(last=4, script={1: ["silent"], 2: ["review"], 3: ["silent"], 4: ["silent"]},
                                         batch=None)
    check("breaker: a normal answer in between starts the count again", (code, len(posted), box[0].halted()), (0, 4, None))
    code, posted, outcomes, lines = case(last=3, script={1: ["silent"], 2: ["silent"], 3: ["silent"]}, passes=3)
    check("breaker: watch mode halts too (the self-test returns 5; a real loop waits for --resume)",
          (code, len(posted)), (5, 3))
    code, posted, outcomes, lines = case({1: ["silent"] * 5}, passes=5)
    check("per-PR cap: parked after 3 tries without an answer, queued for a look, not asked again",
          (len(posted), outcomes, [r["kind"][:20] for r in box[0].queue["1"]["reviews"]], box[0].halted()),
          (3, {"1": PARKED}, ["parked after 3 tries"], None))
    loop = box[0]
    loop.control(lambda c: c.update(unpark=[1]))
    box[1].script[1] = ["review"]
    check("per-PR cap: --unpark asks again", (run(loop, [1]), box[1].posted[-1], loop.state["1"]["outcome"]),
          (0, (1, REVIEW), "reviewed"))
    code, posted, outcomes, lines = case({1: ["stuck", "stuck", "review"]}, passes=4)
    check("re-ask: a review triggered but never delivered waits 3 h the first time, 45 min after a deferral",
          (len(posted), outcomes["1"], [x.split(": ")[1].split(";")[0] for x in lines if "minutes after the request" in x]),
          (3, "reviewed", ["no review 180 minutes after the request", "no review 45 minutes after the request"]))
    code, posted, outcomes, lines = case({n: ["review"] for n in (1, 2, 3)}, last=3, passes=1, posts_per_day=2)
    check("budget: past the daily post budget it waits for the next UTC day",
          (len(posted), logged(lines, "post budget (2 a day) is used"), box[2].t.day), (3, True, 11))
    code, posted, outcomes, lines = case({1: ["paused", "review"]}, batch=None)
    check("halt: 'Reviews paused' halts at once, nothing more posted", (code, posted, outcomes), (5, [(1, REVIEW)], {"1": "halted"}))
    loop = box[0]
    loop.control(lambda c: c.update(breaker=None))
    check("halt: after --resume the same request is asked again", (run(loop, [1]), len(box[1].posted), loop.state["1"]["outcome"]),
          (0, 2, "reviewed"))
    code, posted, outcomes, lines = case({1: ["limit", "review"]}, skew=-90)
    check("clock: GitHub 90 s behind: the limit reply 7 s after the post is still seen (post time from GitHub)",
          (code, posted, outcomes, logged(lines, "limit, waiting"), box[0].state["1"]["posts"][0]["at"]),
          (0, [(1, REVIEW)] * 2, {"1": "reviewed"}, True, "2026-11-10T04:35:21Z"))
    code, posted, outcomes, lines = case({1: ["review"]}, skew=120)
    check("clock: GitHub 2 min ahead: the review is found", (posted, outcomes), ([(1, REVIEW)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case({1: ["review"]}, earlier=[(1, REVIEW, 7200, "finished")], pushes={1: 9000},
                                     state=posted_at(head), setup=lambda hub, clock: hub.push(1, 9000) or hub.heads.update({1: head}))
    check("stale: the state's post names this head: the request counts, the committer date is not consulted",
          (posted, outcomes), ([(1, FULL)], {"1": "reviewed"}))

    def legacy(outcome):
        return lambda clock: {"1": {"pr": 1, "outcome": outcome, "posts": [
            {"command": REVIEW, "at": fmt(clock.t - datetime.timedelta(days=2)), "head": head}]}}
    for outcome in ("reviewed", "skipped", CLEAN):
        code, posted, outcomes, lines = case({1: ["review"]}, earlier=[(1, REVIEW, 2 * 86400, "review")],
                                             pushes={1: 3600}, state=legacy(outcome))
        check(f"migration: {outcome} before diffs, head moved since: not posted, listed to check by hand",
              (posted, outcomes, sorted(box[0].shared("check.json"))), ([], {"1": outcome}, ["1"]))
    code, posted, outcomes, lines = case({1: ["review"]}, earlier=[(1, REVIEW, 2 * 86400, "review")], state=legacy("reviewed"))
    check("migration: reviewed before diffs at the current head: nothing to check",
          (posted, box[0].shared("check.json")), ([], {}))
    code, posted, outcomes, lines = case(earlier=[(1, REVIEW, 7200, "skipped")], pushes={1: 3600})
    check("migration: skipped (too large) at an older head, no state: not asked again on a guess",
          (posted, outcomes, sorted(box[0].shared("check.json"))), ([], {"1": "skipped"}, ["1"]))
    with tempfile.TemporaryDirectory() as tmp:
        folder = pathlib.Path(tmp)
        code, posted, outcomes, lines = case({1: ["review"]}, folder=folder)
        mark_queue(folder, [2], False, out=lambda x: None)  # --queue-add from another process
        box[0].enqueue(3, "review", 7, 1)
        mark_queue(folder, [1], True, out=lambda x: None)    # --triaged
        q = read_json(folder / "triage-queue.json", {})
        check("queue: the loop and --queue-add/--triaged don't lose each other's entries",
              ({k: v.get("triaged") for k, v in q.items()}, sorted(p.name for p in folder.glob("*.tmp"))),
              ({"1": True, "2": False, "3": False}, []))
    with tempfile.TemporaryDirectory() as tmp:
        folder = pathlib.Path(tmp)
        write_json(folder / "loop-control.json", {"breaker": {"at": "2026-11-10T00:00:00Z", "reason": "test"},
                                                  "posts": {"2026-11-10": 3}})
        code, posted, outcomes, lines = case({1: ["review"]}, dry=True, folder=folder, batch=None)
        check("dry run: reads the real control file (a halt is reported, nothing changed on disk)",
              (code, posted, logged(lines, "halted: test"), read_json(folder / "loop-control.json", {})["posts"]),
              (5, [], True, {"2026-11-10": 3}))

    def close_2_tomorrow(hub, clock):
        sleep = clock.sleep

        def s(sec):
            sleep(sec)
            if clock.t.day == 11:
                hub.closed.add(2)
        clock.sleep = s
    code, posted, outcomes, lines = case({1: ["review"], 2: ["review"]}, last=2, posts_per_day=1, batch=None,
                                         setup=close_2_tomorrow)
    check("budget: a PR merged while waiting for the next day's budget is not posted on",
          (code, posted, logged(lines, "#2: closed meanwhile, not posting")), (0, [(1, REVIEW)], True))
    bad = [x for x in checks if not x[1]]
    for name, ok, g, w in checks:
        print(f"{'ok  ' if ok else 'FAIL'} {name}" + ("" if ok else f": got {g!r}, want {w!r}"))
    print(f"{len(checks) - len(bad)}/{len(checks)} checks passed")
    return 1 if bad else 0




def discover(hub, first=None, last=None):
    """The open stacked PRs in stack order (head branches `stack/…`, following the bases from
    master), limited to first..last. If the bases don't form one chain, the stack PRs by number."""
    pulls = hub.pulls()
    try:
        order = [p["number"] for p in stack_order(pulls)]
    except SystemExit:
        order = sorted(p["number"] for p in pulls if p["headRefName"].startswith("stack/"))
    return [n for n in order if (first is None or n >= first) and (last is None or n <= last)]


def run(loop, prs, batch=None):
    """One pass over `prs` in order; stops after `batch` new reviews (full ones included; a PR
    with no review available is not counted), at a quota message (4), when halted (5), on --stop
    (0), or in a dry run at the first PR it would post on or wait for (0)."""
    done = 0
    try:
        for n in prs:
            r = loop.one(n)
            if r in ("skip", "none", "defer"):
                continue
            if r == "pause":
                loop.log(f"a quota or plan message: stopping (watch mode would pause until {loop.paused_until:%Y-%m-%d %H:%M}Z)")
                return 4
            if r == "halt":
                loop.log(f"halted: {(loop.halted() or {}).get('reason')}; `--resume` clears it")
                return 5
            if r != "done":
                return r
            done += 1
            if batch and done >= batch:
                loop.log(f"{done} reviews done, handing back for triage")
                return 0
    except Stop:
        loop.log("stop requested (--resume clears it), exiting")
        return 0
    loop.log("all PRs done")
    return 0


def watch(loop, prs, idle=IDLE, auth_pause=AUTH_PAUSE, passes=None):
    """Passes over `prs()` (called each pass: new PRs join) until --stop, `passes` (the self-test)
    or Ctrl+C. Between passes it idles `idle` seconds, less if a deferred PR is due sooner. An
    error (GitHub, the network after the reads' own retries, anything unexpected) restarts the
    pass after 1, 2, 4 … 30 minutes; gh not signed in after `auth_pause`. While the circuit
    breaker is tripped it posts nothing and checks every minute for --resume (the self-test
    returns 5 instead)."""
    errors, count = 0, 0
    while True:
        try:
            if loop.halted():
                reason = loop.halted().get("reason")
                if passes:
                    loop.log(f"halted: {reason}")
                    return 5
                loop.status(phase="halted (needs --resume)", pr=None, until=None, halted=reason)
                while loop.halted():
                    loop.sleep_until(loop.now() + datetime.timedelta(seconds=POLL))
                loop.log("resumed (--resume cleared the halt)")
                loop.status(halted=None)
            if loop.paused_until and loop.now() < loop.paused_until:
                loop.log(f"paused until {loop.paused_until:%Y-%m-%d %H:%M}Z (a quota or plan message)")
                loop.status(phase="paused (quota or plan message)", pr=None, until=iso(loop.paused_until))
                loop.sleep_until(loop.paused_until)
            loop.paused_until = None
            loop.status(phase="pass", pr=None, until=None, paused_until=None)
            todo = prs()
            loop.status(prs=f"#{todo[0]}..#{todo[-1]} ({len(todo)})" if todo else "none")
            outcomes = {}
            for n in todo:
                loop.status(phase="checking", pr=n, until=None)
                r = loop.one(n)
                outcomes[r] = outcomes.get(r, 0) + 1
                if r == "pause":
                    loop.status(paused_until=iso(loop.paused_until))
                    break
                if r == "halt":
                    break
            errors = 0
            count += 1
            if "pause" in outcomes or "halt" in outcomes:
                continue
            due = [ts(e["retry_at"]) for e in loop.state.values() if isinstance(e, dict) and e.get("retry_at")
                   and (not todo or e.get("pr") in todo)]
            nxt = min([loop.now() + datetime.timedelta(seconds=idle)] + due)
            nxt = max(nxt, loop.now() + datetime.timedelta(seconds=POLL))
            summary = ", ".join(f"{v} {k}" for k, v in sorted(outcomes.items(), key=lambda x: str(x[0])))
            loop.log(f"pass {count} over {len(todo)} PRs done ({summary or 'nothing'}); next at {nxt:%H:%M}Z")
            loop.status(phase="idle", pr=None, until=iso(nxt), last_pass=f"{iso(loop.now())}: {summary}", error=None)
            if passes and count >= passes:
                return 0
            loop.sleep_until(nxt)
        except Stop:
            loop.log("stop requested (--resume clears it), exiting")
            loop.status(phase="stopped", pr=None, until=None)
            return 0
        except Exception as ex:  # noqa: BLE001 - every failure becomes a wait
            errors += 1
            wait = auth_pause if is_auth(ex) else min(ERROR_FIRST * 2 ** (errors - 1), ERROR_MAX)
            until = loop.now() + datetime.timedelta(seconds=wait)
            where = traceback.extract_tb(ex.__traceback__)[-1]
            loop.log(f"error ({type(ex).__name__} at {pathlib.Path(where.filename).name}:{where.lineno}): {str(ex)[:300]}; "
                     f"{'gh is not signed in; ' if is_auth(ex) else ''}trying again at {until:%H:%M}Z")
            loop.status(phase="error", until=iso(until), error=f"{iso(loop.now())}: {str(ex)[:300]}")
            if passes and errors > 20:
                return 1
            try:
                loop.sleep_until(until)
            except Stop:
                loop.log("stop requested (--resume clears it), exiting")
                loop.status(phase="stopped", pr=None, until=None)
                return 0


MIGRATED = ("requests.json", "triage-queue.json", "loop.args", "loop.log")


def migrate(folder, source, out=print):
    """Copies the loop's files from an older folder (a checkout's target/review) into `folder`,
    once: only if `folder` has no requests.json yet. Returns whether it copied."""
    source = pathlib.Path(source)
    if (folder / "requests.json").exists() or not (source / "requests.json").exists():
        return False
    folder.mkdir(parents=True, exist_ok=True)
    copied = []
    for name in MIGRATED:
        if (source / name).exists() and not (folder / name).exists():
            shutil.copy2(source / name, folder / name)
            copied.append(name)
    (folder / "migrated-from.txt").write_text(f"{source}\n{iso(now())}\n{', '.join(copied)}\n", encoding="utf-8")
    out(f"copied {', '.join(copied)} from {source} to {folder} (the old folder is left as it was)")
    return True


def running_line(folder):
    st = read_json(folder / "loop-status.json", {})
    if lock_held(folder / "loop.lock"):
        return True, f"a loop is running in {folder} (pid {st.get('pid')}, started {st.get('started')})"
    return False, f"no loop holds the lock in {folder}"


def stop_loop(folder, wait=120, out=print, sleep=time.sleep):
    """Sets the stop flag, then waits up to `wait` seconds until no loop holds the lock in
    `folder` (a loop notices the flag within a minute: at its next poll, or between gh's read
    retries). 0 once none runs, 1 if one still does."""
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "loop.stop").write_text(iso(now()) + "\n", encoding="utf-8")
    running, line = running_line(folder)
    out(f"stop flag set ({folder / 'loop.stop'}); {line}")
    waited = 0
    while running and waited < wait:
        step = min(5, wait - waited)
        sleep(step)
        waited += step
        running, _ = running_line(folder)
        if running and waited % 15 == 0:
            out(f"  still running after {waited} s ...")
    if running:
        out(f"the loop still holds the lock after {waited} s; run --status, or --stop again later")
        return 1
    out(f"no loop is running in {folder}" + (f" (it exited after {waited} s)" if waited else "")
        + "; the flag keeps the scheduled task from starting one until --resume")
    return 0


def print_status(folder, out=print):
    """Whether the loop runs, what it does, the halt, the budget, the deferred and parked PRs, the
    PRs to check by hand and the triage queue."""
    st = read_json(folder / "loop-status.json", {})
    running, line = running_line(folder)
    stop = (folder / "loop.stop").exists()
    out(f"loop: {'running' if running else 'not running'}: {line}" + ("; STOP requested: `--resume` clears it" if stop else ""))
    control = read_json(folder / "loop-control.json", {})
    if control.get("breaker"):
        out(f"HALTED since {control['breaker']['at']}: {control['breaker']['reason']} (`--resume` clears it)")
    if control.get("unpark"):
        out("unpark requested: " + ", ".join(f"#{n}" for n in control["unpark"]))
    if st:
        what = st.get("phase", "?") + (f" on #{st['pr']}" if st.get("pr") else "")
        if st.get("until"):
            left = (ts(st["until"]) - now()).total_seconds() / 60
            what += f" until {st['until']} ({left:.0f} min{' ago' if left < 0 else ''})"
        out(f"{'now' if running else 'last'}: {what} (status written {st.get('updated')})")
        for k, label in (("prs", "PRs"), ("last_pass", "last pass"), ("paused_until", "paused until"),
                         ("error", "last error")):
            if st.get(k):
                out(f"  {label}: {st[k]}")
    posts = control.get("posts") or {}
    out(f"posts today (UTC): {posts.get(f'{now():%Y-%m-%d}', 0)}" + (f"; earlier: {', '.join(f'{k} {v}' for k, v in sorted(posts.items())[-4:-1])}" if len(posts) > 1 else ""))
    state = read_json(folder / "requests.json", {})
    for n, e in sorted((int(k), e) for k, e in state.items() if isinstance(e, dict) and e.get("retry_at")):
        out(f"deferred: #{n} {e.get('outcome')} (x{e.get('timeouts', 1)}), asking again after {e['retry_at']}")
    parked = sorted(int(k) for k, e in state.items() if isinstance(e, dict) and e.get("outcome") == PARKED)
    for n in parked:
        out(f"parked: #{n}: {state[str(n)].get('parked_why', '')[:160]} (`--unpark {n}` asks again)")
    open_ = sorted(int(k) for k, e in state.items() if isinstance(e, dict) and e.get("outcome") in ("pending", "waiting", "changed", "halted"))
    if open_:
        out("in progress: " + ", ".join(f"#{n} {state[str(n)]['outcome']}" for n in open_))
    check = read_json(folder / "check.json", {})
    if check:
        out(f"check by hand ({len(check)}; `--checked N…` clears):")
        for n, c in sorted((int(k), c) for k, c in check.items()):
            out(f"  #{n}: {c['why']}")
    queue = read_json(folder / "triage-queue.json", {})
    todo = sorted((int(k), q) for k, q in queue.items() if not q.get("triaged"))
    out(f"triage queue: {len(todo)} PR(s)" + ("" if todo else " (empty)"))
    for n, q in todo:
        rv = q["reviews"][-1]
        more = f", {len(q['reviews'])} entries" if len(q["reviews"]) > 1 else ""
        out(f"  #{n}: {rv['kind']} at {rv['at']}" + (f", {rv['inline']} inline comments" if rv.get("inline") is not None else "") + more)
    log_path = folder / "loop.log"
    if log_path.exists():
        lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-5:]
        out("log (last lines):")
        for line in lines:
            out("  " + line[:200])
    return 0


def mark_queue(folder, prs, triaged, out=print):
    def change(queue):
        for n in prs:
            q = queue.setdefault(str(n), {"pr": n, "reviews": []})
            if triaged:
                q["triaged"] = True
                q["triaged_at"] = iso(now())
            else:
                q["reviews"].append({"kind": "added by hand", "review_id": None, "inline": None, "at": iso(now())})
                q["triaged"] = False
    update_json(folder / "triage-queue.json", change, {})
    out(("marked triaged: " if triaged else "queued: ") + ", ".join(f"#{n}" for n in prs))
    return 0


def main():
    if hasattr(sys.stdout, "reconfigure"):  # CodeRabbit's text in a log on Windows' code page
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    ap = add_common_args(argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter))
    ap.add_argument("--first", type=int, help="first PR number (default: the bottom of the stack)")
    ap.add_argument("--last", type=int, help="last PR number (default: the top of the stack, read every pass)")
    ap.add_argument("--batch", type=int, help="one pass, stopping after this many new reviews (manual use)")
    ap.add_argument("--once", action="store_true", help="one pass, then exit")
    ap.add_argument("--dir", default=str(loop_dir()),
                    help="state, log, queue, status, lock and stop files (default: $WLX_REVIEW_DIR, else %(default)s)")
    ap.add_argument("--state", help="progress file (default: DIR/requests.json)")
    ap.add_argument("--migrate-from", metavar="OLD_DIR",
                    help=f"copy {', '.join(MIGRATED)} from an older folder into --dir if it has no state yet, and exit")
    ap.add_argument("--idle", type=int, default=IDLE // 60, help="watch: minutes between passes (default %(default)s)")
    ap.add_argument("--quota-pause", type=int, default=QUOTA_PAUSE // 3600, help="hours to pause on a quota message (default %(default)s)")
    ap.add_argument("--auth-pause", type=int, default=AUTH_PAUSE // 60, help="minutes to pause when gh is not signed in (default %(default)s)")
    ap.add_argument("--posts-per-day", type=int, default=POSTS_PER_DAY,
                    help="most `@coderabbitai` comments per UTC day, limit retries included (default %(default)s)")
    ap.add_argument("--no-rereview", action="store_true", help="never re-request a reviewed PR whose own diff changed")
    ap.add_argument("--post", "--write", dest="post", action="store_true", help="post requests to GitHub; without it, only print the next action")
    ap.add_argument("--dry-run", action="store_true", help="the default: print the next action, post nothing")
    ap.add_argument("--status", action="store_true", help="print whether the loop runs, what it waits for, the halt, the budget and the queues")
    ap.add_argument("--stop", action="store_true",
                    help="ask a running loop to exit and keep the scheduled task from starting it; waits until it has exited")
    ap.add_argument("--stop-wait", type=int, default=120, metavar="SECONDS",
                    help="--stop: how long to wait for the loop to exit (default %(default)s)")
    ap.add_argument("--resume", action="store_true", help="clear --stop and a halt (loop_task.py launch then starts it)")
    ap.add_argument("--unpark", type=int, nargs="+", metavar="N", help="ask parked PRs again")
    ap.add_argument("--triaged", type=int, nargs="+", metavar="N", help="mark these PRs' reviews as triaged")
    ap.add_argument("--queue-add", type=int, nargs="+", metavar="N", help="put these PRs in the triage queue")
    ap.add_argument("--checked", type=int, nargs="+", metavar="N", help="clear these PRs from the check-by-hand list")
    ap.add_argument("--self-test", action="store_true", help="check the reply matching and the loop on a simulated CodeRabbit, and exit")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    folder = folder_of(a)
    if a.migrate_from:
        if not migrate(folder, folder_of(argparse.Namespace(dir=a.migrate_from))):
            print(f"nothing copied: {folder} has a requests.json already, or {a.migrate_from} has none")
        return 0
    if a.status:
        return print_status(folder)
    if a.stop:
        return stop_loop(folder, a.stop_wait)
    if a.resume:
        stop = folder / "loop.stop"
        had_stop = stop.exists()
        if had_stop:
            stop.unlink()
        breaker = update_json(folder / "loop-control.json", lambda c: c.update(breaker=None, streak=[]), {}) if folder.exists() else {}
        running, line = running_line(folder)
        print(f"{'stop flag cleared' if had_stop else 'no stop flag'}; halt cleared; {line}"
              + ("" if running else "; `loop_task.py launch` starts it"))
        return 0
    if a.unpark:
        update_json(folder / "loop-control.json",
                    lambda c: c.update(unpark=sorted(set(c.get("unpark") or []) | set(a.unpark))), {})
        print("unpark requested: " + ", ".join(f"#{n}" for n in a.unpark) + " (the loop asks them again on its next pass)")
        return 0
    if a.triaged or a.queue_add:
        return mark_queue(folder, a.triaged or a.queue_add, bool(a.triaged))
    if a.checked:
        update_json(folder / "check.json", lambda d: [d.pop(str(n), None) for n in a.checked], {})
        print("cleared from the check list: " + ", ".join(f"#{n}" for n in a.checked))
        return 0
    if a.post and a.dry_run:
        ap.error("--post and --dry-run contradict each other")
    if a.post and DEFAULT_DIR.resolve() != folder:
        migrate(folder, DEFAULT_DIR, out=log)  # this checkout's target/review, the old default
    hub = GitHub(gh_path(a.gh), a.repo, log)
    loop = Loop(hub, a.state or folder / "requests.json", not a.post, folder=folder, rereview=not a.no_rereview,
                quota_pause=a.quota_pause * 3600, posts_per_day=a.posts_per_day)

    def prs():
        return discover(hub, a.first, a.last)
    if not a.post:
        return run(loop, prs())
    lock = Lock(folder / "loop.lock")
    if not lock.acquire(wait=5):  # a --status probe holds it for a moment
        st = read_json(folder / "loop-status.json", {})
        log(f"already running (pid {st.get('pid')}), exiting")
        return 0
    try:
        if (folder / "loop.stop").exists():
            log("stop requested (--resume clears it), not starting")
            return 0
        old = read_json(folder / "loop-status.json", {})
        loop.st = {"pid": os.getpid(), "started": iso(now()), "mode": "batch" if a.batch else "once" if a.once else "watch",
                   "last_pass": old.get("last_pass")}
        if old.get("paused_until") and ts(old["paused_until"]) > now():
            loop.paused_until = ts(old["paused_until"])
        mode = loop.st["mode"]
        log(f"started ({mode}, pid {os.getpid()}, PRs {a.first or 'stack bottom'}..{a.last or 'stack top'}, "
            f"{a.posts_per_day} posts a day at most, dir {folder})")
        if mode != "watch":
            code = run(loop, prs(), a.batch)
            loop.status(phase=f"exited ({code})", pr=None, until=None)
            return code
        return watch(loop, prs, idle=a.idle * 60, auth_pause=a.auth_pause * 60)
    finally:
        lock.release()


def folder_of(a):
    return pathlib.Path(a.dir).resolve()


if __name__ == "__main__":
    sys.exit(main())
