#!/usr/bin/env python3
"""Summarise a timing log (modules.RequestTimingFilter, one JSON line per request).

Usage: python scripts/timing-summary.py LOG.jsonl [--queries] [--group REGEX=NAME ...]
       [--skip-seconds N] [--labels REGEX]

Per label (the request's X-Timing-Label, else its path with ids replaced): requests, total,
the time outside the action (waiting: queueing for a pool, routing, filters), the queue wait
for the blocking pool, the action's work, the templates' rendering (without their queries)
and the queries' time, as p50/p95/mean in ms. With --queries also, per label group, every
statement: calls per request and its time per request (p50/p95/mean ms).

--group maps labels to groups for the query tables (first matching regex wins), e.g.
  --group '^gallery=gallery' --group '^large=large'
"""
import argparse
import json
import math
import re
import statistics
from collections import defaultdict

NAMES = [
    (r"from users .*email", "users by email (session user)"),
    (r"user_contest", "user_contest (memberships)"),
    (r"from users", "users (other)"),
    (r"from rounds", "rounds"),
    (r"from contest_jury", "contest_jury"),
    (r"ROW_NUMBER\(\).*group by s\.page_id", "organiser position (grouped ROW_NUMBER)"),
    (r"ROW_NUMBER\(\)", "position/imageRank (ROW_NUMBER)"),
    (r"^select count\(\*\) from selection", "list.count (COUNT(*))"),
    (r"^select COUNT\(DISTINCT s\.page_id\) from selection", "organiser list.count (COUNT DISTINCT)"),
    (r"select count\(t\.pi_on_i\)", "list.count (subquery)"),
    (r"^select sum\(s\.rate\) as rate, count\(s\.rate\) as rate_count, i\.", "organiser files (grouped)"),
    (r"AS region, s\.rate, COUNT", "jurorCounts (region, rate)"),
    (r"AS region, SUM\(p\.hit\)", "organiser regionCounts"),
    (r"AS region, SUM\(", "regionCounts"),
    (r"SELECT DISTINCT", "byRegionStat"),
    (r"SELECT s\.rate, count\(N\)", "rateDistribution"),
    (r"from \(select s\.id", "files page (deferred join)"),
    (r"from selection s STRAIGHT_JOIN images i .* LIMIT", "files window (LIMIT)"),
    (r"from selection s STRAIGHT_JOIN images i", "files (no limit)"),
    (r"from comment", "comments"),
    (r"from monument", "monument"),
    (r"criteria_rate", "criteria_rate"),
    (r"^update selection", "vote update"),
    (r"from selection", "selection (other)"),
]


def name_of(sql):
    for pattern, name in NAMES:
        if re.search(pattern, sql, re.IGNORECASE):
            return name
    return sql[:90]


def label_of(rec):
    if rec.get("label"):
        return rec["label"]
    path = re.sub(r"\?.*", "", rec["path"])
    return rec["method"] + " " + re.sub(r"/\d+", "/N", path)


def p(xs, q):
    s = sorted(xs)
    if not s:
        return float("nan")
    return s[max(0, math.ceil(q * len(s)) - 1)]


def fmt(xs):
    if not xs:
        return "-"
    return f"{p(xs, .5):.0f} / {p(xs, .95):.0f} / {statistics.mean(xs):.0f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--queries", action="store_true")
    ap.add_argument("--group", action="append", default=[])
    ap.add_argument("--labels", default=None, help="only labels matching this regex")
    ap.add_argument("--skip-seconds", type=float, default=0, help="leave out the first N s (warm-up)")
    ap.add_argument("--drop-over-ms", type=float, default=None,
                    help="leave out requests longer than this (e.g. a laptop sleeping), counted")
    args = ap.parse_args()

    recs = [json.loads(l) for l in open(args.log, encoding="utf-8") if l.strip()]
    if not recs:
        print("empty log")
        return
    t0 = min(r["at"] for r in recs)
    recs = [r for r in recs if r["at"] - t0 >= args.skip_seconds * 1000]
    recs = [r for r in recs if r.get("label") != "warmup" and r.get("label") != "login"]
    if args.drop_over_ms is not None:
        dropped = [r for r in recs if r["totalMs"] > args.drop_over_ms]
        for r in dropped:
            print(f"dropped: {label_of(r)} {r['totalMs']:.0f} ms")
        recs = [r for r in recs if r["totalMs"] <= args.drop_over_ms]
    if args.labels:
        recs = [r for r in recs if re.search(args.labels, label_of(r))]

    by_label = defaultdict(list)
    for r in recs:
        by_label[label_of(r)].append(r)

    print(f"{args.log}: {len(recs)} requests, {len(by_label)} labels; p50 / p95 / mean ms")
    head = ("label", "n", "total", "outside action", "queued", "action", "render (net)", "queries", "q/req")
    print("| " + " | ".join(head) + " |")
    print("|" + "---|" * len(head))
    for label in sorted(by_label):
        rs = by_label[label]
        total = [r["totalMs"] for r in rs]
        work = [r["workMs"] for r in rs]
        outside = [r["totalMs"] - r["workMs"] for r in rs]
        queued = [r["queuedMs"] for r in rs if r.get("queuedMs") is not None]
        render = [r["renderMs"] - r["renderQueryMs"] for r in rs]
        q = [r["queryMs"] for r in rs]
        nq = statistics.mean(sum(x[1] for x in r["queries"]) for r in rs)
        print(f"| {label} | {len(rs)} | {fmt(total)} | {fmt(outside)} | {fmt(queued)} | {fmt(work)} | "
              f"{fmt(render)} | {fmt(q)} | {nq:.1f} |")

    if args.queries:
        groups = [(re.compile(g.split("=", 1)[0]), g.split("=", 1)[1]) for g in args.group]

        def group_of(label):
            for rx, name in groups:
                if rx.search(label):
                    return name
            return label

        by_group = defaultdict(list)
        for label, rs in by_label.items():
            by_group[group_of(label)].extend(rs)
        for g in sorted(by_group):
            rs = by_group[g]
            per = defaultdict(lambda: [[0] * len(rs), [0.0] * len(rs)])
            for i, r in enumerate(rs):
                for sql, n, ms in r["queries"]:
                    k = name_of(sql)
                    per[k][0][i] += n
                    per[k][1][i] += ms
            print(f"\n{g}: {len(rs)} requests; time per request p50 / p95 / mean ms")
            print("| query | calls/req | ms/req |")
            print("|---|---|---|")
            for k, (calls, ms) in sorted(per.items(), key=lambda kv: -sum(kv[1][1])):
                print(f"| {k} | {statistics.mean(calls):.2f} | {fmt(ms)} |")
            render = [r["renderMs"] - r["renderQueryMs"] for r in rs]
            other = [r["workMs"] - r["queryMs"] - (r["renderMs"] - r["renderQueryMs"]) for r in rs]
            print(f"| (render, net of its queries) | | {fmt(render)} |")
            print(f"| (action, neither queries nor render) | | {fmt(other)} |")


if __name__ == "__main__":
    main()
