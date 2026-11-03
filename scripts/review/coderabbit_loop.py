"""Request CodeRabbit reviews on the stacked PRs one at a time, waiting out its limits.

For each PR in --first..--last (in number order) without a CodeRabbit review: post
`@coderabbitai review`, wait for CodeRabbit's answer, and on an "available in N minutes" (review
limit) or "please wait N minutes" (chat-message limit) answer wait N+2 minutes and ask again.
Exits after --batch completed reviews, so they can be triaged before it is started again, and on
a quota/plan message, a wait over 3 hours, no answer in 30 minutes, or anything unexpected.

Exit codes: 0 batch done or all PRs done; 2 no answer; 3 limit wait too long; 4 unexpected answer.

Progress is saved to --state (default target/review/requests.json). The file is only a log: with
it missing, the loop rebuilds what it needs from GitHub. A PR that has any CodeRabbit review is
skipped, and a PR whose latest `@coderabbitai` request by us is newer than CodeRabbit's last
answer is treated as already requested (it waits instead of posting again).

--dry-run reads GitHub and prints what the loop would do next; it posts nothing.
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
NO_ANSWER = 30 * 60
MAX_WAIT = 3 * 3600


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


class Loop:
    def __init__(self, hub, state_path, dry):
        self.hub, self.state_path, self.dry = hub, state_path, dry
        self.state = read_json(state_path, {})

    def save(self):
        if not self.dry:
            write_json(self.state_path, self.state)

    def reviewed(self, n):
        return bool(rabbit(self.hub.get_all(f"pulls/{n}/reviews")))

    def pending_request(self, n):
        """Our latest `@coderabbitai` request if CodeRabbit hasn't answered since, else None."""
        comments = self.hub.get_all(f"issues/{n}/comments")
        ours = [c for c in comments if c["user"]["login"] == OURS and c["body"].strip().startswith("@coderabbitai")]
        if not ours:
            return None
        asked = ts(ours[-1]["created_at"])
        answered = [c for c in rabbit(comments) if ts(c["updated_at"]) > asked]
        return None if answered else asked

    def wait_answer(self, n, since):
        """CodeRabbit's first activity on PR n after `since`: ('review', id, count) or ('text', body)."""
        deadline = time.time() + NO_ANSWER
        while time.time() < deadline:
            time.sleep(POLL)
            reviews = [r for r in rabbit(self.hub.get_all(f"pulls/{n}/reviews"))
                       if r.get("submitted_at") and ts(r["submitted_at"]) > since]
            if reviews:
                inline = [c for c in rabbit(self.hub.get_all(f"pulls/{n}/comments")) if ts(c["created_at"]) > since]
                return ("review", reviews[-1]["id"], len(inline))
            for c in rabbit(self.hub.get_all(f"issues/{n}/comments")):
                if ts(c["updated_at"]) > since:
                    t = text(c["body"])
                    # "Review triggered" / "Currently processing": a review is running, keep waiting
                    if re.search(r"review triggered|currently processing", t, re.I):
                        deadline = max(deadline, time.time() + NO_ANSWER)
                        continue
                    if re.search(r"review limit reached|rate limited|rate limit exceeded|quota|try again", t, re.I):
                        return ("text", t)
                    if re.search(r"review (skipped|failed)|no reviewable|too large", t, re.I):
                        return ("text", t)
        return ("timeout",)

    def reviewed_now(self, n, e, ans):
        e.update(outcome="reviewed", review_id=ans[1], inline=ans[2])
        self.save()
        log(f"#{n}: reviewed, {ans[2]} inline comments")

    def one(self, n):
        """Returns 'done' (a new review), 'skip' (reviewed before, or skipped by CodeRabbit), or an
        exit code to stop with."""
        e = self.state.setdefault(str(n), {"pr": n, "posts": []})
        if self.reviewed(n):
            if e.get("outcome") != "reviewed":
                e.update(outcome="reviewed", note="has a CodeRabbit review on GitHub")
                self.save()
            return "skip"
        asked = self.pending_request(n)
        if self.dry:
            log(f"#{n}: would {'wait for the request posted at %s' % asked if asked else 'post @coderabbitai review'}")
            return 0
        if asked:
            log(f"#{n}: our request at {asked:%H:%M:%S} has no answer yet, waiting for it")
            ans = self.wait_answer(n, asked)
            if ans[0] == "review":
                self.reviewed_now(n, e, ans)
                return "done"
            log(f"#{n}: the earlier request gave {ans[0]}, posting again")
        command, waited, refused_full = "@coderabbitai review", 0, False
        while True:
            since = now()
            self.hub.comment(n, command)
            e.setdefault("posts", []).append({"command": command, "at": f"{since:%Y-%m-%dT%H:%M:%SZ}"})
            e["outcome"] = "pending"
            self.save()
            log(f"#{n}: posted {command}")
            ans = self.wait_answer(n, since)
            if ans[0] == "review":
                self.reviewed_now(n, e, ans)
                return "done"
            if ans[0] == "timeout":
                e.update(outcome="timeout"); self.save()
                log(f"#{n}: no answer in 30 min, stopping")
                return 2
            msg = ans[1]
            e["message"] = msg[:600]
            # "Next included review available in N minutes" (review quota) or
            # "Please wait N minutes and S seconds" (chat-message limit, e.g. after triage replies)
            m = re.search(r"(?:available in|wait\W*) ?(\d+) minute", msg, re.I)
            if m and not re.search(r"monthly|plan quota|upgrade your plan", msg, re.I):
                mins = int(m.group(1)) + 2
                if waited + mins * 60 > MAX_WAIT:
                    e.update(outcome="limit"); self.save()
                    log(f"#{n}: wait over 3 hours, stopping")
                    return 3
                e["outcome"] = "waiting"; self.save()
                log(f"#{n}: limit, waiting {mins} min")
                time.sleep(mins * 60)
                waited += mins * 60
                continue
            if re.search(r"incremental|already reviewed", msg, re.I) and not refused_full and "limit" not in msg.lower():
                command, refused_full = "@coderabbitai full review", True
                continue
            if re.search(r"review skipped|no reviewable|too large", msg, re.I):
                e.update(outcome="skipped"); self.save()
                log(f"#{n}: skipped: {msg[:120]}")
                return "skip"
            if re.search(r"limit", msg, re.I) and not m:
                # a limit message without a stated time: wait 30 minutes once, then stop
                if waited:
                    e.update(outcome="limit"); self.save()
                    log(f"#{n}: limit without a time, stopping")
                    return 3
                time.sleep(30 * 60)
                waited += 30 * 60
                continue
            e.update(outcome="unexpected"); self.save()
            log(f"#{n}: unexpected answer, stopping: {msg[:200]}")
            return 4


def main():
    ap = add_common_args(argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter))
    ap.add_argument("--first", type=int, required=True, help="first PR number")
    ap.add_argument("--last", type=int, required=True, help="last PR number")
    ap.add_argument("--batch", type=int, default=5, help="stop after this many new reviews (default 5)")
    ap.add_argument("--state", default=str(DEFAULT_DIR / "requests.json"), help="progress log (default %(default)s)")
    ap.add_argument("--dry-run", action="store_true", help="print the next action; post nothing")
    a = ap.parse_args()
    loop = Loop(GitHub(gh_path(a.gh), a.repo, log), a.state, a.dry_run)
    done = 0
    for n in range(a.first, a.last + 1):
        e = loop.state.get(str(n), {})
        if e.get("outcome") == "skipped":
            continue
        r = loop.one(n)
        if r == "skip":
            continue
        if r != "done":
            return r
        done += 1
        if done >= a.batch:
            log(f"{done} reviews done, handing back for triage")
            return 0
    log("all PRs done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
