"""Aggregate browser family/major versions of the jury sites' visitors from Apache's
other_vhosts_access logs (vhost_combined). Prints counts only: no IPs, no user agents.
A visitor is a distinct (client, user agent) pair; bots and non-browser clients are skipped."""
import collections, glob, gzip, hashlib, re, sys

JURY = re.compile(r"^(jury\.[^:\s]+)")
LINE = re.compile(r'^(\S+) (\S+) \S+ \S+ \[[^\]]+\] "(?:GET|POST) (\S+)[^"]*" (\d{3}) \S+ "[^"]*" "([^"]*)"')
# pages that answer 200 only to a signed-in user (signed out they redirect to the login page)
SIGNED_IN = re.compile(r"^/(gallery|large|byrate|rounds?|roundstat|jury|users|contests?|admin|filelist|thumb_urls|criteria)")
BOT = re.compile(r"bot|crawl|spider|slurp|curl|wget|python|java/|go-http|okhttp|monitor|headless|preview|scan", re.I)

def family(ua):
    m = re.search(r"Edg/(\d+)", ua)
    if m: return "Edge", int(m.group(1))
    m = re.search(r"OPR/(\d+)", ua)
    if m: return "Opera", int(m.group(1))
    m = re.search(r"Firefox/(\d+)", ua)
    if m: return "Firefox", int(m.group(1))
    m = re.search(r"(?:Chrome|CriOS)/(\d+)", ua)
    if m: return "Chrome", int(m.group(1))
    m = re.search(r"Version/(\d+)[.\d]* (?:Mobile/\S+ )?Safari/", ua)
    if m: return "Safari", int(m.group(1))
    return "Other", 0

def mobile(ua):
    return bool(re.search(r"Mobi|Android|iPhone|iPad", ua))

visitors = {}
for path in sorted(glob.glob("/var/log/apache2/other_vhosts_access.log*")):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", errors="replace") as f:
        for line in f:
            m = LINE.match(line)
            if not m or not JURY.match(m.group(1)): continue
            ua = m.group(5)
            if not ua or BOT.search(ua): continue
            if m.group(4) != "200" or not SIGNED_IN.match(m.group(3)): continue
            key = hashlib.sha1((m.group(2) + "\0" + ua).encode()).hexdigest()
            visitors[key] = (family(ua), mobile(ua))

by_major = collections.Counter(); by_family = collections.Counter(); mob = collections.Counter()
for (fam, major), is_mobile in visitors.values():
    by_major[(fam, major)] += 1; by_family[fam] += 1; mob["mobile" if is_mobile else "desktop"] += 1

total = len(visitors)
print("visitors (distinct client+UA, 14 days):", total)
print("desktop/mobile:", dict(mob))
for fam, n in by_family.most_common():
    majors = sorted(((m, c) for (f, m), c in by_major.items() if f == fam), reverse=True)
    print(f"{fam}: {n} ({100*n/total:.1f}%)  majors: " + ", ".join(f"{m}:{c}" for m, c in majors[:12]))
