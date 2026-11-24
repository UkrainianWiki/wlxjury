import json, glob, statistics as st, sys, os, re
D = sys.argv[1]
med = lambda a: st.median(a) if a else float('nan')
f0 = lambda x: f"{x:.0f}"
out = []

def lv(path):
    d = json.load(open(path)); m = d['meta']; rows = [r for r in d['rows'] if not r['warm']]
    V = m['variants']; nv = len(V)
    blocks = [rows[i:i + nv] for i in range(0, len(rows), nv)]
    return m, rows, V, blocks

out.append("## Large view: keypress to next file's main image painted (ms)\n")
out.append("| round | link | variant | median | min-max | paired delta vs A (median, range) | faster than A in | interactive median |")
out.append("|---|---|---|---|---|---|---|---|")
for p in sorted(glob.glob(D + '/lv-*.json')):
    m, rows, V, blocks = lv(p)
    prof = os.path.basename(p)[3:-5].split('-', 1)[1]
    link = {'rtt0': 'RTT 0, unlimited', 'rtt80': 'RTT 80, unlimited', 'rtt80-8mbit': 'RTT 80, 8 Mbit/s', 'h2-rtt80': 'h2, RTT 80', 'h2-rtt80-8mbit': 'h2, RTT 80, 8 Mbit/s'}[prof]
    for v in V:
        rr = [r for r in rows if r['variant'] == v]
        t = [r['total_painted'] for r in rr]
        if v == 'A':
            d = '-'; k = '-'
        else:
            ds = []
            for b in blocks:
                a = [r for r in b if r['variant'] == 'A']; x = [r for r in b if r['variant'] == v]
                if a and x: ds.append(x[0]['total_painted'] - a[0]['total_painted'])
            d = f"{med(ds):+.0f} ({min(ds):+.0f}..{max(ds):+.0f})"; k = f"{sum(1 for x in ds if x < 0)} of {len(ds)}"
        out.append(f"| {m['round']} | {link} | {v} | {f0(med(t))} | {f0(min(t))}-{f0(max(t))} | {d} | {k} | {f0(med([r['total_interactive'] for r in rr]))} |")

out.append("\n## Large view breakdown, variant A, medians (ms from keypress)\n")
out.append("| round | link | keypress to form submit | vote POST + redirect | next page request to first byte | HTML download | HTML end | CSS/JS end | DOMContentLoaded | main image: response end | main image: duration | strip: images | strip: last image end | painted | server POST total | server GET total | server GET render | server GET queries |")
out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for p in sorted(glob.glob(D + '/lv-*.json')):
    m, rows, V, blocks = lv(p)
    prof = os.path.basename(p)[3:-5].split('-', 1)[1]
    rr = [r for r in rows if r['variant'] == 'A']
    g = lambda f: med([f(r) for r in rr])
    sp = [r['server_post']['total'] for r in rr if r['server_post']]; sg = [r['server_get'] for r in rr if r['server_get']]
    th = [r['thumbs']['end_from_t0'] for r in rr if r['thumbs']['end_from_t0']]
    out.append(f"| {m['round']} | {prof} | {g(lambda r: r['key_to_navstart']):.0f} | {g(lambda r: r['redirect']):.0f} | {g(lambda r: r['page_ttfb']):.0f} | {g(lambda r: r['page_dl']):.0f} | {g(lambda r: r['html_end_from_t0']):.0f} | {g(lambda r: r['resources_end_from_t0']):.0f} | {g(lambda r: r['dcl_from_t0']):.0f} | {g(lambda r: r['main']['end']):.0f} | {g(lambda r: r['main']['dur']):.0f} | {g(lambda r: r['thumbs']['n']):.0f} | {f0(med(th)) if th else '-'} | {g(lambda r: r['total_painted']):.0f} | {f0(med(sp))} | {f0(med([x['total'] for x in sg]))} | {f0(med([x['render'] for x in sg]))} | {f0(med([x['query'] for x in sg]))} |")

# pages
out.append("\n## Gallery and statistics pages\n")
for T in (0, 80):
    p = f"{D}/pages-rtt{T}.json"
    if not os.path.exists(p): continue
    d = json.load(open(p))
    out.append(f"\n### RTT {T} ms (medians of {d['meta']['reps']} loads)\n")
    out.append("| page | HTML KB | changed vs previous page KB | server total | server queries | server render | TTFB | HTML end | DCL | load | LCP | images (n) | DOM nodes |")
    out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for s in d['scenarios']:
        rr = s['rows']; sv = [r['server'] for r in rr if r['server']]
        ch = f"{s['htmlChangedBytes']/1024:.0f}" if s['htmlChangedBytes'] is not None else '-'
        out.append(f"| {s['name']} | {s['htmlBytes']/1024:.0f} | {ch} | {f0(med([x['total'] for x in sv])) if sv else '-'} | {f0(med([x['query'] for x in sv])) if sv else '-'} | {f0(med([x['render'] for x in sv])) if sv else '-'} | {f0(med([r['ttfb'] for r in rr]))} | {f0(med([r['htmlEnd'] for r in rr]))} | {f0(med([r['dcl'] for r in rr]))} | {f0(med([r['load'] for r in rr]))} | {f0(med([r['lcp'] for r in rr if r['lcp']])) if any(r['lcp'] for r in rr) else '-'} | {f0(med([r['nimg'] for r in rr]))} | {f0(med([r['dom'] for r in rr]))} |")

# per-query attribution at RTT 0
p = f"{D}/pages-rtt0.json"
if os.path.exists(p):
    d = json.load(open(p))
    out.append("\n### Queries per page (RTT 0 run; median ms per load, count per load; statements over 3 ms)\n")
    for s in d['scenarios']:
        agg = {}
        sv = [r['server'] for r in s['rows'] if r['server']]
        for x in sv:
            for sql, n, ms in x['queries']:
                agg.setdefault(sql, []).append((n, ms))
        out.append(f"\n**{s['name']}** (server total {f0(med([x['total'] for x in sv])) if sv else '-'} ms, queries {f0(med([x['query'] for x in sv])) if sv else '-'} ms, {sum(len(v) and med([a for a, _ in v]) for v in agg.values()):.0f} statements)\n")
        rows = sorted(((med([ms for _, ms in v]), med([n for n, _ in v]), sql) for sql, v in agg.items()), reverse=True)
        for ms, n, sql in rows[:6]:
            if ms < 3: continue
            short = re.sub(r'\s+', ' ', sql)
            short = re.sub(r'^select .*? from ', 'select ... from ', short)[:150]
            out.append(f"- {ms:.0f} ms x{n:.0f}: `{short}`")
print("\n".join(out))
