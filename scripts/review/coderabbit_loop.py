"""Request CodeRabbit reviews on the stacked PRs one at a time, waiting out its limits.

For each PR in --first..--last (in number order) without a CodeRabbit review: post
`@coderabbitai review` and wait for CodeRabbit's answer. Only two things answer a request:
  - a CodeRabbit review submitted after it (the review is done);
  - CodeRabbit's reply to the command saying it was limited, skipped or refused.
The acknowledgement ("Action performed: Review triggered", later edited to "Review finished") and
the summary comment's "Currently processing" edit mean the review is running: keep waiting.

"Review finished" with no review submitted after the request (FINISHED_GRACE after the edit):
CodeRabbit's reviews are incremental and it has reviewed these commits before (seen on #215 and
#217, 2026-11-10, where it submitted no review object at all). The loop then posts
`@coderabbitai full review` once for that PR and waits for it like any request (same limits,
retries and timeouts; the retries start again from 0). A command reply saying "already reviewed"
or "no new commits" is handled the same way. If the full review also ends without a review, the
PR is recorded as "no review available" and the loop goes on with the next PR.

Limits:
  - "Action not completed … Review rate limited." (the reply this plan sends; no time in it): wait
    LIMIT_SPACING (50 min, the spacing measured on 2026-11-03) and ask again, up to LIMIT_RETRIES
    times, then stop;
  - "available in N minutes" / "Please wait **N minutes and S seconds**" (review or chat-message
    limit): wait that long plus 2 minutes and ask again (it counts as a retry too).
Exits after --batch completed reviews (a completed full review counts; a PR with no review
available does not), so they can be triaged before it is started again, and on
a quota/plan message, too many limit retries, a stated wait over 3 hours, no reply to the command
in 30 minutes, no review 3 hours after the request, or anything unexpected.

Exit codes: 0 batch done or all PRs done; 2 no answer; 3 limit (too many retries or too long a
wait); 4 unexpected answer.

Only CodeRabbit's replies to a command count: issue comments created after the request that carry
`<!-- This is an auto-generated reply by CodeRabbit -->` and either the
`<!-- CodeRabbit review command invocation: … -->` marker or a "Rate Limit Exceeded" heading. The
walkthrough/summary comment (`<!-- This is an auto-generated comment: summarize by coderabbit.ai -->`)
is never matched: it is about the PR, and may mention rate limits as a finding.

Progress is saved to --state (default target/review/requests.json). The file is only a log: with
it missing, the loop rebuilds what it needs from GitHub. A PR that has any CodeRabbit review is
skipped, and a PR whose latest `@coderabbitai` request by us has no answer yet is treated as
already requested (it waits instead of posting again); after a limit reply it waits out the
spacing from that reply before posting again (the same command: `review` or `full review`).
A latest `@coderabbitai review` that finished without a review gets `@coderabbitai full review`;
a latest `@coderabbitai full review` that finished without a review is "no review available".

Without --post it only reads GitHub and prints what it would do next (--dry-run is accepted and
means the same); it goes past PRs with no review available and stops at the first PR it would
post on or wait for. --self-test checks the reply matching against sample comments, and the
full-review path against a simulated CodeRabbit, and exits.
"""
import argparse
import datetime
import re
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))  # -I leaves the script's folder out
from common import DEFAULT_DIR, OURS, GitHub, add_common_args, gh_path, read_json, write_json

POLL = 60
NO_ANSWER = 30 * 60          # no reply to the command at all in this long: stop
MAX_WAIT = 3 * 3600          # no review this long after the request, or a stated wait over this: stop
LIMIT_SPACING = 50 * 60      # "Review rate limited." states no time: wait this long, then ask again
LIMIT_RETRIES = 4
FINISHED_GRACE = 5 * 60      # "Review finished" but no review object yet: wait this long for it

SUMMARY_MARKER = "<!-- This is an auto-generated comment: summarize by coderabbit.ai -->"
REPLY_MARKER = "<!-- This is an auto-generated reply by CodeRabbit -->"
COMMAND_MARKER = "<!-- CodeRabbit review command invocation"
REVIEW = "@coderabbitai review"
FULL = "@coderabbitai full review"
NO_REVIEW = "no review available"


def now():
    return datetime.datetime.now(datetime.timezone.utc)


def ts(s):
    return datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))


def log(msg):
    print(f"{now():%H:%M:%S} {msg}", flush=True)


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
    """What a reply to our command says: 'limit', 'quota', 'skip', 'full' (ask for a full review),
    'finished', 'processing' or 'unexpected'."""
    t = text(body)
    # the boilerplate note on every command reply mentions "incremental": leave it out
    t = re.sub(r"> ?Note: CodeRabbit is an incremental review system.*?(paused\.|$)", "", t, flags=re.I).strip()
    if re.search(r"monthly|plan quota|upgrade your plan|quota", t, re.I):
        return "quota"
    if re.search(r"rate limited|rate limit exceeded|review limit reached|available in \W*\d+ ?minute|please wait", t, re.I):
        return "limit"
    if re.search(r"review (skipped|failed)|no reviewable|too large", t, re.I):
        return "skip"
    if re.search(r"already reviewed|incremental|no new commits", t, re.I):
        return "full"
    if re.search(r"review (finished|completed)", t, re.I):
        return "finished"
    if re.search(r"review triggered|currently processing|action performed", t, re.I):
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


def finished_since(reply):
    """When a "Review finished" reply got that text: its last edit."""
    return ts(reply.get("updated_at") or reply["created_at"])


def no_review(ans):
    """A request that ended without a review: "Review finished" with none, or "already reviewed"."""
    return ans[0] == "finished" or (ans[0] == "reply" and ans[1] == "full")


class Loop:
    def __init__(self, hub, state_path, dry, clock=None, logger=None):
        self.hub, self.state_path, self.dry = hub, state_path, dry
        self.state = read_json(state_path, {}) if state_path else {}
        self.now = clock.now if clock else now
        self.sleep = clock.sleep if clock else time.sleep
        self.log = logger or log

    def save(self):
        if not self.dry and self.state_path:
            write_json(self.state_path, self.state)

    def reviews_after(self, n, since):
        return [r for r in rabbit(self.hub.get_all(f"pulls/{n}/reviews"))
                if r.get("submitted_at") and (since is None or ts(r["submitted_at"]) > since)]

    def latest_request(self, n):
        """(asked, command, reply): our latest `@coderabbitai` request, its command (REVIEW or
        FULL) and CodeRabbit's latest reply to it (None if no reply yet), or None if we never asked."""
        comments = self.hub.get_all(f"issues/{n}/comments")
        ours = [c for c in comments if c["user"]["login"] == OURS and (c["body"] or "").strip().startswith("@coderabbitai")]
        if not ours:
            return None
        asked = ts(ours[-1]["created_at"])
        replies = command_replies(comments, asked)
        return asked, request_command(ours[-1]["body"]), (replies[-1] if replies else None)

    def wait_answer(self, n, since):
        """Wait for CodeRabbit's answer to the request posted at `since`: ('review', id, count),
        ('reply', kind, body) for a limit/quota/skip/full/unexpected reply, ('finished',) for
        "Review finished" with no review FINISHED_GRACE after that edit, or ('timeout', why).
        Never past since + MAX_WAIT."""
        hard = since + datetime.timedelta(seconds=MAX_WAIT)
        while True:
            if self.now() >= hard:
                return ("timeout", "no review 3 hours after the request")
            self.sleep(POLL)
            reviews = self.reviews_after(n, since)
            if reviews:
                inline = [c for c in rabbit(self.hub.get_all(f"pulls/{n}/comments")) if ts(c["created_at"]) > since]
                return ("review", reviews[-1]["id"], len(inline))
            replies = command_replies(self.hub.get_all(f"issues/{n}/comments"), since)
            if not replies:
                if self.now() >= since + datetime.timedelta(seconds=NO_ANSWER):
                    return ("timeout", "no reply to the command in 30 minutes")
                continue
            body = replies[-1]["body"]
            kind = classify(body)
            if kind == "processing":
                continue
            if kind == "finished":
                if self.now() >= finished_since(replies[-1]) + datetime.timedelta(seconds=FINISHED_GRACE):
                    return ("finished",)
                continue
            return ("reply", kind, body)

    def reviewed_now(self, n, e, ans):
        e.update(outcome="reviewed", review_id=ans[1], inline=ans[2])
        self.save()
        self.log(f"#{n}: reviewed, {ans[2]} inline comments")

    def one(self, n):
        """Returns 'done' (a new review, full or not), 'skip' (reviewed before, or skipped by
        CodeRabbit), 'none' (no review available: the full review ended without one), or an exit
        code to stop with (0 in a dry run: the next step would post or wait)."""
        e = self.state.setdefault(str(n), {"pr": n, "posts": []})
        if self.reviews_after(n, None):
            if e.get("outcome") != "reviewed":
                e.update(outcome="reviewed", note="has a CodeRabbit review on GitHub")
                self.save()
            if self.dry:
                self.log(f"#{n}: has a CodeRabbit review, skipping")
            return "skip"
        req = self.latest_request(n)
        retries, pause, command, ans = 0, 0, REVIEW, None
        if req:
            asked, command, reply = req
            kind = classify(reply["body"]) if reply else "processing"
            if kind == "finished" and self.now() >= finished_since(reply) + datetime.timedelta(seconds=FINISHED_GRACE):
                ans = ("finished",)
            elif kind == "full":
                ans = ("reply", "full", reply["body"])
            elif kind in ("processing", "finished"):
                state = "no reply yet" if not reply else text(reply["body"])[:60]
                if self.dry:
                    self.log(f"#{n}: would wait: our {command} at {asked:%H:%M:%S} is still processing ({state})")
                    return 0
                self.log(f"#{n}: our {command} at {asked:%H:%M:%S} is still processing, waiting for it")
                ans = self.wait_answer(n, asked)
                if ans[0] == "review":
                    self.reviewed_now(n, e, ans)
                    return "done"
                if not no_review(ans):
                    self.log(f"#{n}: the earlier request ended with {ans[0]}, posting {command} again")
                    ans = None
            elif kind == "limit":
                retries = 1
                pause = max(0, (ts(reply["created_at"]) + datetime.timedelta(seconds=limit_wait(reply["body"])) - self.now()).total_seconds())
                if self.dry:
                    at = self.now() + datetime.timedelta(seconds=pause)
                    self.log(f"#{n}: limit reply at {ts(reply['created_at']):%H:%M:%S}; would post {command} at {at:%H:%M:%S}")
                    return 0
            elif kind == "skip":
                e.update(outcome="skipped", message=text(reply["body"])[:600]); self.save()
                self.log(f"#{n}: skipped by CodeRabbit: {text(reply['body'])[:120]}")
                return "skip"
        if ans is None and self.dry:
            self.log(f"#{n}: would post {command}")
            return 0
        while True:
            if ans is None:
                if pause:
                    e["outcome"] = "waiting"; self.save()
                    self.log(f"#{n}: limit, waiting {pause / 60:.0f} min (retry {retries} of {LIMIT_RETRIES})")
                    self.sleep(pause)
                    pause = 0
                self.hub.comment(n, command)
                # GitHub's clock may lag ours by a few seconds; the reply comes later than that
                since = self.now() - datetime.timedelta(seconds=10)
                e.setdefault("posts", []).append({"command": command, "at": f"{self.now():%Y-%m-%dT%H:%M:%SZ}"})
                e["outcome"] = "pending"
                self.save()
                self.log(f"#{n}: posted {command}")
                ans = self.wait_answer(n, since)
            ans, answer = None, ans
            if answer[0] == "review":
                self.reviewed_now(n, e, answer)
                return "done"
            if answer[0] == "timeout":
                e.update(outcome="timeout"); self.save()
                self.log(f"#{n}: {answer[1]}, stopping")
                return 2
            if no_review(answer):
                why = ("finished without a review" if answer[0] == "finished"
                       else f"was refused as already reviewed ({text(answer[2])[:80]})")
                if command == FULL:
                    e.update(outcome=NO_REVIEW, message=f"{FULL} {why}"); self.save()
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
                wait = limit_wait(body)
                if wait > MAX_WAIT:
                    e.update(outcome="limit"); self.save()
                    self.log(f"#{n}: CodeRabbit asks to wait {wait / 60:.0f} min, over 3 hours, stopping: {msg[:160]}")
                    return 3
                if retries >= LIMIT_RETRIES:
                    e.update(outcome="limit"); self.save()
                    self.log(f"#{n}: still limited after {retries} retries {LIMIT_SPACING // 60} min apart "
                             f"(last reply: {msg[:120]}), stopping; start the loop again later")
                    return 3
                retries += 1
                pause = wait
                continue
            if kind == "skip":
                e.update(outcome="skipped"); self.save()
                self.log(f"#{n}: skipped: {msg[:120]}")
                return "skip"
            e.update(outcome="quota" if kind == "quota" else "unexpected"); self.save()
            self.log(f"#{n}: {'a quota or plan message' if kind == 'quota' else 'unexpected answer'}, stopping: {msg[:200]}")
            return 4


def self_test():
    """Checks the reply matching against sample comments (bodies as CodeRabbit sent them on #193
    and #191, 2026-11-03, and #217, 2026-11-10), and the full-review path against a simulated
    CodeRabbit (no GitHub, no sleeping)."""
    note = ("\n\n> Note: CodeRabbit is an incremental review system and does not re-review already reviewed "
            "commits. This command is applicable only when automatic reviews are paused.\n\n")

    def reply(summary, line, invocation=True):
        inv = "<!-- CodeRabbit review command invocation: v2:abc -->\n" if invocation else ""
        return ("<!-- coderabbit-ai-generated:v1 -->\n\n" + REPLY_MARKER + "\n" + inv +
                f"<details>\n<summary>{summary}</summary>\n<dl>\n<dd>\n\n{line}{note}</dd>\n</dl>\n</details>")

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
    check("classify: finished", classify(finished), "finished")
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
    # the reply on #217 (2026-11-10): "Review finished." with the note, and no review submitted
    check("classify: finished with the incremental note (#217)",
          classify(reply("Action performed", "Review finished.")), "finished")
    check("classify: already reviewed", classify(reply("Action not completed", "Already reviewed these commits.")), "full")
    check("request command: review", request_command("@coderabbitai review"), REVIEW)
    check("request command: full review", request_command(" @coderabbitai  full review\n"), FULL)
    check("request command: other", request_command("@coderabbitai resolve"), REVIEW)
    check("no review: finished", no_review(("finished",)), True)
    check("no review: limit reply", no_review(("reply", "limit", limited)), False)

    # The full-review path against a simulated CodeRabbit: every command gets "Review triggered."
    # 7 s later, edited to "Review finished." 10 minutes later; a review (submitted just before that
    # edit) comes only for `full review` on the PRs in `full_reviews`.
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
        def __init__(self, clock, full_reviews):
            self.clock, self.full_reviews, self.items, self.posted = clock, set(full_reviews), [], []

        def add(self, path, item, start, end=None):
            self.items.append((start, end, path, item))

        def get_all(self, path):
            t = self.clock.t
            return [dict(i) for s, e, p, i in self.items if p == path and s <= t and (e is None or t < e)]

        def answer(self, n, body, at, review):
            def sec(s):
                return at + datetime.timedelta(seconds=s)
            path = f"issues/{n}/comments"
            self.add(path, {"user": us, "body": body, "created_at": fmt(at), "updated_at": fmt(at)}, at)
            self.add(path, {"user": bot, "body": triggered, "created_at": fmt(sec(7)), "updated_at": fmt(sec(7))},
                     sec(7), sec(600))
            self.add(path, {"user": bot, "body": finished, "created_at": fmt(sec(7)), "updated_at": fmt(sec(600))},
                     sec(600))
            if review:
                self.add(f"pulls/{n}/reviews", {"user": bot, "id": 900 + n, "submitted_at": fmt(sec(590))}, sec(590))
                self.add(f"pulls/{n}/comments", {"user": bot, "created_at": fmt(sec(590))}, sec(590))

        def comment(self, n, body):
            self.posted.append((n, body))
            self.answer(n, body, self.clock.t, body == FULL and n in self.full_reviews)

    def case(full_reviews=(), earlier=(), dry=False, last=1, batch=5):
        """earlier: (pr, command, seconds ago) requests already on GitHub (state rebuilt from them)."""
        clock = Clock()
        hub = Rabbit(clock, full_reviews)
        for pr, command, ago in earlier:
            hub.answer(pr, command, clock.t - datetime.timedelta(seconds=ago), False)
        lines = []
        loop = Loop(hub, None, dry, clock=clock, logger=lines.append)
        code = run(loop, 1, last, batch)
        return code, hub.posted, {k: v.get("outcome") for k, v in loop.state.items()}, lines

    code, posted, outcomes, _ = case(full_reviews=[1])
    check("full review: review finished without one, full review done",
          (code, posted, outcomes), (0, [(1, REVIEW), (1, FULL)], {"1": "reviewed"}))
    code, posted, outcomes, lines = case()
    check("full review: also no review, recorded and the loop goes on",
          (code, posted, outcomes, any(NO_REVIEW in x for x in lines), lines[-1]),
          (0, [(1, REVIEW), (1, FULL)], {"1": NO_REVIEW}, True, "all PRs done"))
    code, posted, outcomes, _ = case(full_reviews=[1, 2, 3], last=3, batch=2)
    check("full review: completed full reviews count toward --batch",
          (code, posted[-1], len(posted)), (0, (2, FULL), 4))
    code, posted, outcomes, _ = case(full_reviews=[2, 3], last=3, batch=1)
    check("full review: no review available does not count toward --batch",
          (code, posted, outcomes),
          (0, [(1, REVIEW), (1, FULL), (2, REVIEW), (2, FULL)], {"1": NO_REVIEW, "2": "reviewed"}))
    code, posted, outcomes, _ = case(full_reviews=[1], earlier=[(1, REVIEW, 3600)])
    check("rebuild: a finished review request gets a full review",
          (code, posted, outcomes), (0, [(1, FULL)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case(full_reviews=[1], earlier=[(1, REVIEW, 11 * 60)])
    check("rebuild: a review request finished just now: wait out the grace, then full review",
          (code, posted, outcomes), (0, [(1, FULL)], {"1": "reviewed"}))
    code, posted, outcomes, _ = case(earlier=[(1, FULL, 3600)])
    check("rebuild: a finished full review request is no review available, nothing posted",
          (code, posted, outcomes), (0, [], {"1": NO_REVIEW}))
    code, posted, _, lines = case(earlier=[(1, REVIEW, 3600)], dry=True)
    check("dry run: would post a full review",
          (code, posted, lines[-1]), (0, [], f"#1: {REVIEW} finished without a review; would post {FULL}"))
    code, posted, _, lines = case(earlier=[(1, FULL, 3600)], dry=True, last=2)
    check("dry run: past no review available to the next PR",
          (code, posted, lines[-1]), (0, [], f"#2: would post {REVIEW}"))
    code, posted, _, lines = case(earlier=[(1, FULL, 60)], dry=True)
    check("dry run: a full review request still processing: would wait",
          (code, posted, lines[-1].split(" at ")[0]), (0, [], f"#1: would wait: our {FULL}"))
    bad = [x for x in checks if not x[1]]
    for name, ok, g, w in checks:
        print(f"{'ok  ' if ok else 'FAIL'} {name}" + ("" if ok else f": got {g!r}, want {w!r}"))
    print(f"{len(checks) - len(bad)}/{len(checks)} checks passed")
    return 1 if bad else 0


def main():
    ap = add_common_args(argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter))
    ap.add_argument("--first", type=int, help="first PR number")
    ap.add_argument("--last", type=int, help="last PR number")
    ap.add_argument("--batch", type=int, default=5, help="stop after this many new reviews (default 5)")
    ap.add_argument("--state", default=str(DEFAULT_DIR / "requests.json"), help="progress log (default %(default)s)")
    ap.add_argument("--post", "--write", dest="post", action="store_true", help="post requests to GitHub; without it, only print the next action")
    ap.add_argument("--dry-run", action="store_true", help="the default: print the next action, post nothing")
    ap.add_argument("--self-test", action="store_true", help="check the reply matching on sample comments and exit")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.first is None or a.last is None:
        ap.error("--first and --last are required")
    if a.post and a.dry_run:
        ap.error("--post and --dry-run contradict each other")
    return run(Loop(GitHub(gh_path(a.gh), a.repo, log), a.state, not a.post), a.first, a.last, a.batch)


def run(loop, first, last, batch):
    """PRs first..last in order; stops after `batch` new reviews (full ones included; a PR with no
    review available is not counted) or with the exit code a PR returns."""
    done = 0
    for n in range(first, last + 1):
        e = loop.state.get(str(n), {})
        if e.get("outcome") == "skipped":
            continue
        r = loop.one(n)
        if r in ("skip", "none"):
            continue
        if r != "done":
            return r
        done += 1
        if done >= batch:
            loop.log(f"{done} reviews done, handing back for triage")
            return 0
    loop.log("all PRs done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
