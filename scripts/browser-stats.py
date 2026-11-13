"""Aggregate browser family/major versions of the jury sites' visitors from Apache's
other_vhosts_access logs (vhost_combined). Prints counts only: no IPs, no user agents.
A visitor is a distinct (client, user agent) pair; bots and non-browser clients are skipped.
Only requests of the last DAYS days (default 14, the first argument) count; the output says
which span the logs covered and warns when they don't reach back that far."""
import collections, datetime, glob, gzip, hashlib, re, sys

JURY = re.compile(r"^(jury\.[^:\s]+)")
LINE = re.compile(r'^(\S+) (\S+) \S+ \S+ \[([^\]]+)\] "(?:GET|POST) (\S+)[^"]*" (\d{3}) \S+ "[^"]*" "([^"]*)"')
# pages that answer 200 only to a signed-in user (signed out they redirect to the login page)
SIGNED_IN = re.compile(r"^/(gallery|large|byrate|rounds?|roundstat|jury|users|contests?|admin|filelist|thumb_urls|criteria)")
BOT = re.compile(r"bot|crawl|spider|slurp|curl|wget|python|java/|go-http|okhttp|monitor|headless|preview|scan", re.I)
TIME = "%d/%b/%Y:%H:%M:%S %z"  # Apache's %t, e.g. 13/Nov/2026:10:00:00 +0200 (English month names)

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

def parse_time(text):
    try:
        return datetime.datetime.strptime(text, TIME)
    except ValueError:
        return None

def collect(lines, cutoff):
    """Visitors of the window (key -> ((family, major), mobile)), and the oldest and newest
    request times in the logs (any line with a readable time, before the filters)."""
    visitors, oldest, newest = {}, None, None
    for line in lines:
        m = LINE.match(line)
        if not m: continue
        when = parse_time(m.group(3))
        if when is None: continue
        oldest = when if oldest is None or when < oldest else oldest
        newest = when if newest is None or when > newest else newest
        if when < cutoff or not JURY.match(m.group(1)): continue
        ua = m.group(6)
        if not ua or BOT.search(ua): continue
        if m.group(5) != "200" or not SIGNED_IN.match(m.group(4)): continue
        key = hashlib.sha1((m.group(2) + "\0" + ua).encode()).hexdigest()
        visitors[key] = (family(ua), mobile(ua))
    return visitors, oldest, newest

def report(visitors, days, cutoff, oldest, newest):
    if oldest is None:
        return ["no readable requests in the logs"]
    out = [f"logs cover {oldest:%Y-%m-%d %H:%M %z} to {newest:%Y-%m-%d %H:%M %z}; window: the last {days} days "
           f"(from {cutoff:%Y-%m-%d %H:%M %z})"]
    if oldest > cutoff:
        out.append(f"WARNING: the logs start after the window does: {(newest - oldest).days} days of data, not {days}")
    by_major = collections.Counter(); by_family = collections.Counter(); mob = collections.Counter()
    for (fam, major), is_mobile in visitors.values():
        by_major[(fam, major)] += 1; by_family[fam] += 1; mob["mobile" if is_mobile else "desktop"] += 1
    total = len(visitors)
    out.append(f"visitors (distinct client+UA, {days} days): {total}")
    out.append(f"desktop/mobile: {dict(mob)}")
    for fam, n in by_family.most_common():
        # every major seen, newest first: the oldest ones decide when a compatibility floor can move
        majors = sorted(((m, c) for (f, m), c in by_major.items() if f == fam), reverse=True)
        out.append(f"{fam}: {n} ({100*n/total:.1f}%)  majors: " + ", ".join(f"{m}:{c}" for m, c in majors))
    return out

def lines_of(paths):
    for path in paths:
        opener = gzip.open if path.endswith(".gz") else open
        with opener(path, "rt", errors="replace") as f:
            yield from f

def main(argv):
    days = int(argv[1]) if len(argv) > 1 else 14
    cutoff = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=days)
    paths = sorted(glob.glob("/var/log/apache2/other_vhosts_access.log*"))
    visitors, oldest, newest = collect(lines_of(paths), cutoff)
    print("\n".join(report(visitors, days, cutoff, oldest, newest)))

if __name__ == "__main__":
    main(sys.argv)
