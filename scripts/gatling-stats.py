#!/usr/bin/env python3
"""Per-request statistics of a Gatling run's js/stats.json: n, mean, std, p50, p75, p95, p99, max (ms).

Usage: python scripts/gatling-stats.py target/gatling/<simulation>-<timestamp> [...]
"""
import json
import os
import sys


def walk(node, out):
    s = node.get("stats")
    if s:
        out.append(s)
    for c in (node.get("contents") or {}).values():
        walk(c, out)


for d in sys.argv[1:]:
    path = os.path.join(d, "js", "stats.json")
    root = json.load(open(path, encoding="utf-8"))
    rows = []
    walk(root, rows)
    print(d)
    print("| request | n | KO | mean | std | p50 | p75 | p95 | p99 | max |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for s in rows:
        g = lambda k: s[k]["total"]
        print(f"| {s['name']} | {g('numberOfRequests')} | {s['numberOfRequests']['ko']} | {g('meanResponseTime')} | "
              f"{g('standardDeviation')} | {g('percentiles1')} | {g('percentiles2')} | {g('percentiles3')} | "
              f"{g('percentiles4')} | {g('maxResponseTime')} |")
