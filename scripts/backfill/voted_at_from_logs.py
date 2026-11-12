#!/usr/bin/env python3
"""One-off backfill of vote.voted_at (V97) from the jury vhosts' Apache access logs.

Votes cast before V97 have no time. The only record of them is the access log (rotated after
14 days), so this script reads extracts of it and writes a SQL file of guarded UPDATEs:

    UPDATE vote SET voted_at = '<UTC time>' WHERE id = ? AND rate = ? AND voted_at IS NULL;

The rule (decided by the maintainer, 2026-11-12):
  - each successful vote request is attributed to a juror: CERTAIN when the file has a single
    vote row in the round (a vote only changes an existing row), or when the Referer is a page of
    the juror's own list (/gallery|large|byrate/.../user/U/... with U among the file's jurors);
    an IP + user agent session tie is optional (--session-attribution, off by default);
  - per (round, file, juror) the latest attributed request wins, by (UTC second, log order);
  - it is used only if the value it set equals the vote's current rate, and the rate is not 0,
    and no unattributed vote request came later on the same file (it may have been this juror's).
The guard (same rate, no time yet) makes the SQL safe to rerun and safe against votes changed
after the extract: the app sets voted_at on every change of the rate since V97.

A successful status does not prove a vote: the large view answers 303 also to a signed-out user
(to the login page) and in a closed round or to a stopped juror (to the gallery, no query), and
before 26.11 both vote endpoints answered success when no vote row of the user matched. So a
vote request is taken as failed when the same IP + user agent session opens the login page within
a few seconds after it (not right after a sign-in or sign-out of that session), or, for a
large-view vote from NEXT_PAGE_RULE_FROM on, when its next page is a list without the list's own
query (the closed-round redirect; the vote's own redirect carries `sort=` since then, while the
release before sent a vote on the last unrated file to the gallery without it).

Input: one or more extract files (gzip or plain), each line "<log basename> <Apache
other_vhosts_access line>" as made by the command in docs/deployment.md, GIVEN OLDEST FIRST.
Extracts may overlap (the same log lines read twice, under another rotation name): identical
Apache lines are counted once across files (within one file, repeated identical lines are
separate requests). Extracts that leave a gap in time stop the run unless --allow-gap.

The extracts hold IP addresses and user agents: this script never writes them anywhere. Its SQL
holds vote ids, rates and times only, and stdout only aggregate counts. Delete the extracts and
the SQL after use.

Database: read with the mariadb command-line client (no Python driver), e.g. on the server
    python3 voted_at_from_logs.py --defaults-extra-file /etc/wlxjury-runtime/db-admin.cnf \\
        --db wlxjury --out voted_at.sql older-extract.gz jury-votelog.gz
then review the report, apply the SQL with the same client, and run with --check. The undo
(voted_at.revert.sql, written alongside) clears exactly the times this SQL set.

Stdlib only, Python 3.8+.
"""
import argparse
import bisect
import collections
import datetime
import gzip
import hashlib
import os
import re
import shlex
import subprocess
import sys

# The time of the last of a juror's requests on a file (the agreed rule), or, with RUN_START, of
# the first of the last requests that all set the current value (the app's rule: a vote of the
# same value again keeps the time). A pending maintainer decision: flip here.
RUN_START_DEFAULT = False

# "<vhost:port> <ip> <ident> <user> [<time>] "<method> <path> <proto>" <status> <bytes> "<referer>" "<ua>""
# with the extract's "<log basename> " in front (optional here, for plain log lines)
LINE = re.compile(
    r'^(?:(\S+) )?(\S+:\d+) (\S+) \S+ \S+ \[([^\]]+)\] "(\S+) (\S+)[^"]*" (\d{3}) \S+ "([^"]*)" "([^"]*)"')
APACHE_TIME = re.compile(r'^(\d{2})/([A-Za-z]{3})/(\d{4}):(\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2})(\d{2})$')
MONTHS = {m: i + 1 for i, m in enumerate(
    ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'])}
# the jury app's names (one app, one database)
JURY_VHOSTS = ('jury.wlm.org.ua', 'jury.wikilovesmonuments.org.ua', 'jury.wikilovesearth.org.ua',
               'jury.wikilovesearth.org', 'jury.wle.org.ua')

# The vote requests (conf/routes; the same paths in 26.10 (f9c31ce5) and 26.11):
#   POST /rate/round/R/pageid/P/select/S                   gallery tiles (Csrf-Token header): 200
#   POST /large/round/R[/region/X]/pageid/P/select/S[?..]  large view buttons and keyboard: 303
#        with ?criteria=C a criterion's rate (the vote's rate is then the criteria's sum)
# What the statuses prove differs: before 26.11 (6fa5b56f, then f9c31ce5) both answered success
# also when no vote row of the user matched; since, a vote changing no row is a 404/400. A
# signed-out large view vote, or one in a closed round or by a stopped juror, is a 303 in all of
# them (see above). Where a successful large view vote goes on to differs: since f9c31ce5 to the
# next file's large view or the list's first page, both with the list's `sort=`; in 6fa5b56f a
# vote on the last unrated file went to the gallery's first page without `sort=`, as the
# closed-round redirect does. GET .../select/... is no route (404).
VOTE = re.compile(r'^/(rate|large)/round/(\d+)(?:/region/[^/]+)?/pageid/(\d+)/select/(-?\d+)/?$')
VOTE_OK = {'rate': {200}, 'large': {303}}
# a page of one juror's list: the juror whose votes it shows (and, for a juror, their own)
USER_PAGE = re.compile(r'^/(?:gallery|large|byrate)/(?:round/(\d+)/)?user/(\d+)/')
LIST_OR_LARGE = re.compile(r'^/(gallery|large|byrate)/')
ROTATION = re.compile(r'\.log(?:\.(\d+))?(?:\.gz)?$')
# a vote's redirect (or the login page) follows it within this many seconds
FOLLOW_WINDOW = 5
# The "next page is a list without sort=" rule applies from here on: f9c31ce5 (with the role
# hotfix) replaced 6fa5b56f in production at 2026-11-02 14:01Z (docs/plans/2026-11-02-roadmap.md,
# "Role-allowlist hotfix ... Deployed 2026-11-02 14:01Z"). 2026-11-02 14:01:00 UTC:
NEXT_PAGE_RULE_FROM = 1793628060
# inside one extract, consecutive rotation files further apart than this get a warning (the
# jury's quiet nights leave gaps of several hours; a missing daily file leaves a day)
FILE_GAP_WARN = 12 * 3600

SQL_TIME = '%Y-%m-%d %H:%M:%S'


def parse_apache_time(s):
    """'05/Nov/2026:10:11:12 +0100' -> UTC epoch seconds (the month names are Apache's English
    ones, whatever the locale)."""
    m = APACHE_TIME.match(s)
    if not m:
        raise ValueError('bad time ' + s)
    d, mon, y, hh, mm, ss, sign, oh, om = m.groups()
    off = datetime.timedelta(hours=int(oh), minutes=int(om))
    tz = datetime.timezone(off if sign == '+' else -off)
    t = datetime.datetime(int(y), MONTHS[mon.title()], int(d), int(hh), int(mm), int(ss), tzinfo=tz)
    return int(t.timestamp())


def utc_sql(t):
    """UTC epoch seconds -> a TIMESTAMP(3) literal (for a session at time_zone '+00:00')."""
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).strftime(SQL_TIME) + '.000'


def rotation_index(basename):
    """other_vhosts_access.log -> 0, .log.1 -> 1, .log.7.gz -> 7: higher is older."""
    m = ROTATION.search(basename or '')
    return int(m.group(1)) if m and m.group(1) else 0


def local_path(url):
    return re.sub(r'^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*', '', url).split('?')[0].split('#')[0]


class Event(object):
    """kind: vote; page (a GET of a list or large view; `user` when it is one juror's); login (the
    login page); signin (a sign-in or sign-out POST)."""
    __slots__ = ('kind', 't', 'order', 'sess', 'round', 'page', 'sel', 'ep', 'status',
                 'criteria', 'ref_user', 'ref_round', 'user', 'target', 'sorted')

    def __init__(self, **kw):
        for k in self.__slots__:
            setattr(self, k, kw.get(k))


def read_lines(paths):
    """Yields (file index, line) of each extract; gzip or plain text."""
    for i, path in enumerate(paths):
        with open(path, 'rb') as probe:
            gz = probe.read(2) == b'\x1f\x8b'
        opener = gzip.open if gz else open
        with opener(path, 'rt', encoding='utf-8', errors='replace') as f:
            for line in f:
                yield i, line


def parse_events(lines, stats, salt=None, vhosts=None, spans=None, file_spans=None):
    """Vote requests, list pages and sign-in/out requests from extract lines, deduplicated
    across files. The sessions (IP + user agent) are salted hashes kept in memory only.
    `spans` (if given) gets each extract's first and last UTC time: {file index: [min, max]};
    `file_spans` each rotation file's in each extract: {(file index, log basename): [min, max]}."""
    salt = salt if salt is not None else os.urandom(16).hex()
    kept = collections.Counter()      # digest of the Apache line -> times kept so far
    seen_in = collections.Counter()   # (file, digest) -> times seen in that file
    events = []
    for n, (fi, raw) in enumerate(lines):
        line = raw.rstrip('\r\n')
        m = LINE.match(line)
        if not m:
            stats['lines_unparsed'] += 1
            continue
        base, vhost, ip, when, meth, path, status, ref, ua = m.groups()
        try:
            t = parse_apache_time(when)
        except (ValueError, KeyError):
            stats['lines_unparsed'] += 1
            continue
        if spans is not None:
            s = spans.setdefault(fi, [t, t])
            s[0], s[1] = min(s[0], t), max(s[1], t)
        if file_spans is not None:
            s = file_spans.setdefault((fi, base or ''), [t, t])
            s[0], s[1] = min(s[0], t), max(s[1], t)
        if vhosts is not None and vhost.rsplit(':', 1)[0].lower() not in vhosts:
            stats['lines_other_vhost'] += 1
            continue
        apache = line[len(base) + 1:] if base else line
        digest = hashlib.sha1(apache.encode('utf-8', 'replace')).digest()
        seen_in[(fi, digest)] += 1
        if seen_in[(fi, digest)] <= kept[digest]:
            stats['lines_duplicate'] += 1   # read before from another extract
            continue
        kept[digest] += 1
        stats['lines'] += 1
        stats['tz ' + when[-5:]] += 1
        # same second: the extracts oldest first, in one an older log file first, then the
        # file's own order (completion order)
        order = (t, fi, -rotation_index(base), n)
        sess = hashlib.sha1((salt + '\t' + ip + '\t' + ua).encode('utf-8', 'replace')).hexdigest()[:16]
        status = int(status)
        rpath = local_path(ref)
        rm = USER_PAGE.match(rpath)
        ref_user = int(rm.group(2)) if rm else None
        ref_round = int(rm.group(1)) if rm and rm.group(1) else None
        p = path.split('?')[0]
        query = path.split('?', 1)[1] if '?' in path else ''
        v = VOTE.match(p)
        if v and meth == 'POST':
            ep, rnd, page, sel = v.groups()
            events.append(Event(kind='vote', t=t, order=order, sess=sess, round=int(rnd), page=int(page),
                                sel=int(sel), ep=ep, status=status,
                                criteria=bool(re.search(r'(^|&)criteria=', query)),
                                ref_user=ref_user, ref_round=ref_round))
            continue
        lv = LIST_OR_LARGE.match(p)
        if lv and meth == 'GET' and status < 400:
            u = USER_PAGE.match(p)
            events.append(Event(kind='page', t=t, order=order, sess=sess, user=int(u.group(2)) if u else None,
                                target='large' if lv.group(1) == 'large' else 'list',
                                sorted=bool(re.search(r'(^|&)sort=', query))))
            continue
        if meth == 'GET' and p == '/auth':
            events.append(Event(kind='login', t=t, order=order, sess=sess))
            continue
        if meth == 'POST' and p in ('/auth', '/logout', '/logout/everywhere') and 300 <= status < 400:
            events.append(Event(kind='signin', t=t, order=order, sess=sess))
    events.sort(key=lambda e: e.order)
    return events


def coverage_problems(spans, count):
    """The extracts' order and gaps: they must be given oldest first, each starting no later
    than the latest time of those before it."""
    problems = []
    covered = None
    for i in range(count):
        if i not in spans:
            problems.append('extract {} has no log lines'.format(i + 1))
            continue
        first, last = spans[i]
        if covered is not None:
            prev = max((spans[j] for j in range(i) if j in spans), key=lambda s: s[0])
            if first < prev[0]:
                problems.append('extract {} starts before extract {}: give them oldest first'.format(i + 1, i))
            elif first > covered:
                problems.append('gap from {} to {} UTC between extract {} and the ones before'.format(
                    utc_sql(covered)[:19], utc_sql(first)[:19], i + 1))
        covered = last if covered is None else max(covered, last)
    return problems


def file_gap_warnings(file_spans, threshold=FILE_GAP_WARN):
    """Inside each extract: rotation files missing from the numbering (a file without one jury
    line is left out too, so a warning only), and consecutive files further apart than
    `threshold` between one's last line and the next one's first."""
    warnings = []
    for fi in sorted({k[0] for k in file_spans}):
        files = sorted(((rotation_index(b), b, s) for (f, b), s in file_spans.items() if f == fi), reverse=True)
        idx = [i for i, _, _ in files]
        missing = sorted(set(range(min(idx), max(idx) + 1)) - set(idx)) if idx else []
        if missing:
            warnings.append('extract {}: no lines from rotation file(s) {}'.format(fi + 1, ', '.join(map(str, missing))))
        for (_, b1, s1), (_, b2, s2) in zip(files, files[1:]):
            if s2[0] - s1[1] > threshold:
                warnings.append('extract {}: {:.1f} h without lines between {} and {} ({} to {} UTC)'.format(
                    fi + 1, (s2[0] - s1[1]) / 3600, b1, b2, utc_sql(s1[1])[:19], utc_sql(s2[0])[:19]))
    return warnings


class Row(object):
    __slots__ = ('table', 'id', 'round', 'jury', 'page', 'rate', 'voted_at')

    def __init__(self, table, id, round, jury, page, rate, voted_at):
        self.table, self.id, self.round, self.jury, self.page = table, id, round, jury, page
        self.rate, self.voted_at = rate, voted_at


class DbData(object):
    """The vote rows (vote and vote_aside) of the rounds voted in, by (round, file), and the
    rounds with criteria. A juror's live row wins over one aside of the same file (a file moved
    aside, distributed again and voted on): the live row is the vote the app shows."""

    def __init__(self, rows=(), criteria_rounds=(), rounds=()):
        self.cand = collections.defaultdict(dict)   # (round, page) -> {jury: Row}
        self.live_and_aside = 0
        for r in rows:
            cur = self.cand[(r.round, r.page)].get(r.jury)
            if cur is not None and cur.table != r.table:
                self.live_and_aside += 1
            if cur is None or (cur.table != 'vote' and r.table == 'vote'):
                self.cand[(r.round, r.page)][r.jury] = r
        self.criteria_rounds = set(criteria_rounds)
        self.rounds = set(rounds)


def db_query_sql(round_ids):
    ids = ','.join(str(int(r)) for r in sorted(round_ids)) or 'NULL'
    sel = ("SELECT '{t}', id, round_id, jury_id, page_id, rate, IFNULL(voted_at, '') FROM {t} "
           "WHERE round_id IN ({ids}) AND jury_id IS NOT NULL AND page_id IS NOT NULL;")
    return ("SET time_zone = '+00:00';\n" + sel.format(t='vote', ids=ids) + '\n' + sel.format(t='vote_aside', ids=ids) +
            "\nSELECT 'round', id, IFNULL(has_criteria, 0) FROM round WHERE id IN ({ids});\n".format(ids=ids))


def parse_db_output(text):
    rows, crit, rounds = [], [], []
    for line in text.splitlines():
        f = line.split('\t')
        if f[0] in ('vote', 'vote_aside') and len(f) == 7:
            rows.append(Row(f[0], int(f[1]), int(f[2]), int(f[3]), int(f[4]), int(f[5]), f[6] or None))
        elif f[0] == 'round' and len(f) == 3:
            rounds.append(int(f[1]))
            if f[2] not in ('0', ''):
                crit.append(int(f[1]))
    return DbData(rows, crit, rounds)


def mariadb(args, sql):
    cmd = shlex.split(args.client)
    if args.defaults_extra_file:
        cmd.append('--defaults-extra-file=' + args.defaults_extra_file)  # must be the first option
    cmd += ['--batch', '--skip-column-names', args.db]
    p = subprocess.run(cmd, input=sql, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       universal_newlines=True, encoding='utf-8')
    if p.returncode != 0:
        msg = p.stderr.strip()
        if 'voted_at' in msg:
            msg += '\n(vote.voted_at is missing: run this after the release with V97)'
        sys.exit('mariadb failed (%d): %s' % (p.returncode, msg))
    return p.stdout


def load_db(args, round_ids):
    return parse_db_output(mariadb(args, db_query_sql(round_ids)))


class Plan(object):
    def __init__(self):
        self.req = collections.Counter()    # per vote request
        self.rows = collections.Counter()   # per (round, file, juror) with an attributed request
        self.diag = collections.Counter()
        self.updates = []                   # (Row, epoch seconds), each with rate == row.rate != 0
        self.timed = []                     # (Row, epoch seconds) whose time is set already (--check)


def failed_votes(events):
    """Vote requests with a successful status that the session's next requests show failed:
    {id(event): reason}."""
    by_sess = collections.defaultdict(list)
    signins = collections.defaultdict(list)
    for e in events:
        if e.kind in ('page', 'login'):
            by_sess[e.sess].append(e)
        elif e.kind == 'signin':
            signins[e.sess].append(e)
    keys = {s: [e.order for e in l] for s, l in by_sess.items()}
    signin_keys = {s: [e.order for e in l] for s, l in signins.items()}

    def after_signin(login):
        # the login page a sign-in or sign-out of the session just led to, not a failed vote
        l = signins.get(login.sess, [])
        k = bisect.bisect_left(signin_keys.get(login.sess, []), login.order)
        return k > 0 and login.t - l[k - 1].t <= FOLLOW_WINDOW
    out = {}
    for e in events:
        if e.kind != 'vote' or e.status not in VOTE_OK[e.ep]:
            continue
        l = by_sess.get(e.sess, [])
        k = bisect.bisect_right(keys.get(e.sess, []), e.order)
        after = []
        while k < len(l) and l[k].t - e.t <= FOLLOW_WINDOW:
            after.append(l[k])
            k += 1
        if any(a.kind == 'login' and not after_signin(a) for a in after):
            out[id(e)] = 'skipped: failed (login page next: signed out)'
        elif (e.ep == 'large' and e.t >= NEXT_PAGE_RULE_FROM and after and after[0].kind == 'page'
              and after[0].target == 'list' and not after[0].sorted):
            out[id(e)] = 'skipped: failed (sent to the gallery: round closed or juror stopped)'
    return out


def session_ties(events, attributed, window):
    """Per session: sorted (t, user) ties from page GETs of a juror's list and from certainly
    attributed votes, and its sign-in/out times (a tie never crosses one)."""
    ties = collections.defaultdict(list)
    bounds = collections.defaultdict(list)
    for e in events:
        if e.kind == 'page' and e.user is not None:
            ties[e.sess].append((e.order, e.user))
        elif e.kind == 'signin':
            bounds[e.sess].append(e.order)
        elif e.kind == 'vote' and id(e) in attributed:
            ties[e.sess].append((e.order, attributed[id(e)]))

    def users_near(e):
        b = bounds.get(e.sess, [])
        k = bisect.bisect_left(b, e.order)
        lo_bound = b[k - 1] if k > 0 else None
        hi_bound = b[k] if k < len(b) else None
        out = set()
        for order, user in ties.get(e.sess, []):
            if abs(order[0] - e.t) > window:
                continue
            if (lo_bound is not None and order < lo_bound) or (hi_bound is not None and order > hi_bound):
                continue
            out.add(user)
        return out
    return users_near


def make_plan(events, db, session_attribution=False, session_window=1800, run_start=RUN_START_DEFAULT):
    """Attributes the vote requests and chooses each vote row's time. `run_start` takes, of the
    juror's last requests that all set the current value, the first (the app's semantics: a vote
    of the same value again keeps the time) instead of the latest (the agreed rule)."""
    plan = Plan()
    attributed = {}      # id(event) -> juror
    pending = []
    votes = [e for e in events if e.kind == 'vote']
    failed = failed_votes(events)
    for e in votes:
        plan.req['vote requests'] += 1
        if e.status not in VOTE_OK[e.ep]:
            plan.req['skipped: request failed (status)'] += 1
            continue
        if id(e) in failed:
            plan.req[failed[id(e)]] += 1
            continue
        if e.criteria or e.round in db.criteria_rounds:
            plan.req['skipped: criteria round (a criterion, not the vote, was rated)'] += 1
            continue
        cand = db.cand.get((e.round, e.page), {})
        if not cand:
            plan.req['skipped: vote row missing (file has none in the round)'] += 1
            continue
        ref_user = e.ref_user
        if ref_user is not None and e.ref_round is not None and e.ref_round != e.round:
            plan.diag['referer of another round (ignored)'] += 1
            ref_user = None
        if ref_user is not None:
            if ref_user in cand:
                attributed[id(e)] = ref_user
                plan.req['attributed: referer juror'] += 1
                if len(cand) == 1:
                    plan.diag['referer agrees with the single vote row'] += 1
            else:
                # the list's juror has no vote of the file in the round: theirs was removed
                plan.req['skipped: vote row missing (referer juror has none)'] += 1
            continue
        if len(cand) == 1:
            attributed[id(e)] = next(iter(cand))
            plan.req['attributed: single vote row'] += 1
            continue
        pending.append(e)

    if pending and session_attribution:
        near = session_ties(events, attributed, session_window)
        still = []
        for e in pending:
            users = near(e)
            hit = users & set(db.cand[(e.round, e.page)])
            if len(hit) == 1 and len(users) == 1:
                attributed[id(e)] = hit.pop()
                plan.req['attributed: IP+UA session (optional)'] += 1
            else:
                still.append(e)
        pending = still
    plan.req['skipped: ambiguous juror'] += len(pending)
    if db.live_and_aside:
        plan.diag['juror with a live and an aside row of a file (the live one used)'] += db.live_and_aside

    by_key = collections.defaultdict(list)
    unattributed_at = collections.defaultdict(list)   # (round, page) -> orders of unknown requests
    for e in votes:
        j = attributed.get(id(e))
        if j is not None:
            by_key[(e.round, e.page, j)].append(e)
    for e in pending:
        unattributed_at[(e.round, e.page)].append(e.order)

    for (rnd, page, j), evs in sorted(by_key.items()):
        evs.sort(key=lambda e: e.order)
        last = evs[-1]
        row = db.cand[(rnd, page)][j]
        if row.rate == 0:
            plan.rows['skipped: unrated now (rate 0)'] += 1
            continue
        if last.sel != row.rate:
            plan.rows['skipped: value mismatch (latest request set another value)'] += 1
            continue
        if any(o > last.order for o in unattributed_at.get((rnd, page), [])):
            plan.rows['skipped: a later unattributed request on the file'] += 1
            continue
        k = len(evs) - 1
        while k > 0 and evs[k - 1].sel == last.sel:
            k -= 1
        chosen = evs[k] if run_start else last
        if k < len(evs) - 1:
            plan.diag['same value voted again after the change (latest vs run start differ)'] += 1
        if row.voted_at:
            plan.rows['skipped: already timed'] += 1
            plan.timed.append((row, chosen.t))
            continue
        plan.rows['matched'] += 1
        if row.table != 'vote':
            plan.diag['matched in vote_aside'] += 1
        plan.updates.append((row, chosen.t))
    plan.updates.sort(key=lambda u: (u[0].table, u[0].id))
    return plan


def render_sql(plan, batch=1000, header=()):
    out = ["SET time_zone = '+00:00';"]
    out += ['-- ' + h for h in header]
    out.append('-- guarded: only a vote of the same rate without a time is changed (safe to rerun)')
    for i in range(0, len(plan.updates), batch):
        out.append('START TRANSACTION;')
        for row, t in plan.updates[i:i + batch]:
            out.append("UPDATE {} SET voted_at = '{}' WHERE id = {} AND rate = {} AND voted_at IS NULL;".format(
                row.table, utc_sql(t), row.id, row.rate))
        out.append('COMMIT;')
    out.append('-- summary: {} updates in {} transactions'.format(
        len(plan.updates), (len(plan.updates) + batch - 1) // batch))
    for title, c in (('requests', plan.req), ('vote rows', plan.rows)):
        for k, n in sorted(c.items()):
            out.append('--   {}: {}: {}'.format(title, k, n))
    return '\n'.join(out) + '\n'


def render_revert_sql(plan, batch=1000):
    """The undo: clears a time only where it is still the one the backfill set."""
    out = ["SET time_zone = '+00:00';",
           '-- undo of the voted_at backfill: a time the app set since is kept']
    for i in range(0, len(plan.updates), batch):
        out.append('START TRANSACTION;')
        for row, t in plan.updates[i:i + batch]:
            out.append("UPDATE {} SET voted_at = NULL WHERE id = {} AND voted_at = '{}';".format(
                row.table, row.id, utc_sql(t)))
        out.append('COMMIT;')
    out.append('-- summary: {} reverts'.format(len(plan.updates)))
    return '\n'.join(out) + '\n'


def revert_path(out):
    root, ext = os.path.splitext(out)
    return root + '.revert' + (ext or '.sql')


def check(plan, db):
    """After applying: how many of the planned rows have the planned time now."""
    c = collections.Counter()
    for row, t in plan.updates + plan.timed:
        cur = db.cand[(row.round, row.page)][row.jury]
        if not cur.voted_at:
            c['still without a time'] += 1
        elif cur.voted_at[:19] == utc_sql(t)[:19]:
            c['has the time from the logs'] += 1
        else:
            c['has another time (voted again in the app)'] += 1
    return c


def report(plan, extra=(), out=None):
    out = out or sys.stdout

    def p(*a):
        print(*a, file=out)
    for line in extra:
        p(line)
    for title, c in (('Vote requests', plan.req), ('Vote rows (round, file, juror) with an attributed request', plan.rows),
                     ('Notes', plan.diag)):
        p(title + ':')
        for k, n in sorted(c.items()):
            p('  {:<72} {:>8}'.format(k, n))
    days = collections.Counter(utc_sql(t)[:10] for _, t in plan.updates)
    p('Times to set, per UTC day:')
    for d in sorted(days):
        p('  {}  {:>8}'.format(d, days[d]))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('extracts', nargs='+', help='extract files (gzip or plain), OLDEST FIRST; they may overlap')
    ap.add_argument('--db', default='wlxjury', help='database name (default wlxjury)')
    ap.add_argument('--defaults-extra-file', help='mariadb option file with the login')
    ap.add_argument('--client', default='mariadb', help="client command (default 'mariadb'), e.g. "
                    "'docker exec -i CONTAINER mariadb'")
    ap.add_argument('--out', help='the SQL file to write (required unless --dry-run or --check); '
                    'its undo goes next to it as NAME.revert.sql')
    ap.add_argument('--batch', type=int, default=1000, help='UPDATEs per transaction (default 1000)')
    ap.add_argument('--dry-run', action='store_true', help='report only, write no SQL')
    ap.add_argument('--check', action='store_true', help='after applying: report how many rows have the time')
    ap.add_argument('--allow-gap', action='store_true', help='run although the extracts leave a gap in time')
    ap.add_argument('--force', action='store_true', help='replace an existing undo file (NAME.revert.sql)')
    ap.add_argument('--vhosts', default=','.join(JURY_VHOSTS),
                    help='the jury vhosts, comma-separated (default: %(default)s)')
    ap.add_argument('--session-attribution', action='store_true',
                    help='also attribute by IP + user agent session (off by default; a few votes)')
    ap.add_argument('--session-window', type=int, default=30, help='session tie window in minutes (default 30)')
    ap.add_argument('--run-start', dest='run_start', action='store_true', default=RUN_START_DEFAULT,
                    help='time of the first of the last same-value requests (the app\'s rule)' +
                    (' (default)' if RUN_START_DEFAULT else ''))
    ap.add_argument('--latest', dest='run_start', action='store_false',
                    help='time of the latest request (the agreed rule)' + ('' if RUN_START_DEFAULT else ' (default)'))
    args = ap.parse_args(argv)
    if not (args.out or args.dry_run or args.check):
        ap.error('--out is required unless --dry-run or --check')
    if args.out and not (args.dry_run or args.check) and os.path.exists(revert_path(args.out)) and not args.force:
        sys.exit('{} exists: the undo of an earlier run, whose applied rows a new run would leave out. Apply or '
                 'keep it, write elsewhere (--out), or rerun with --force to replace it.'.format(revert_path(args.out)))

    stats = collections.Counter()
    spans = {}
    file_spans = {}
    vhosts = {h.strip().lower() for h in args.vhosts.split(',') if h.strip()}
    events = parse_events(read_lines(args.extracts), stats, vhosts=vhosts, spans=spans, file_spans=file_spans)
    extra = ['Extract {}: {} to {} UTC'.format(i + 1, utc_sql(spans[i][0])[:19], utc_sql(spans[i][1])[:19])
             if i in spans else 'Extract {}: no log lines'.format(i + 1) for i in range(len(args.extracts))]
    for line in extra:
        print(line)
    problems = coverage_problems(spans, len(args.extracts))
    for pr in problems:
        print('Coverage: ' + pr)
    for w in file_gap_warnings(file_spans):
        print('Coverage warning: ' + w)
    if problems and not args.allow_gap:
        sys.exit('The extracts do not cover one span of time (above): add the missing extract, give them oldest '
                 'first, or rerun with --allow-gap to accept the gap.')
    rounds = {e.round for e in events if e.kind == 'vote'}
    db = load_db(args, rounds)
    plan = make_plan(events, db, args.session_attribution, args.session_window * 60, args.run_start)
    vt = [e.t for e in events if e.kind == 'vote']
    extra = ['Extract lines: {} kept, {} duplicates across extracts, {} other vhosts, {} unparsed; offsets {}'.format(
        stats['lines'], stats['lines_duplicate'], stats['lines_other_vhost'], stats['lines_unparsed'],
        ', '.join('{} {}'.format(k[3:], n) for k, n in sorted(stats.items()) if k.startswith('tz ')))]
    if vt:
        extra.append('Vote requests from {} to {} UTC, {} rounds'.format(utc_sql(min(vt))[:19], utc_sql(max(vt))[:19], len(rounds)))
    extra.append('Time of a row: ' + ('the first of the last same-value requests (--run-start)' if args.run_start
                                      else 'the latest request'))
    report(plan, extra)
    if args.check:
        c = check(plan, db)
        print('Check (rows the logs give a time):')
        for k, n in sorted(c.items()):
            print('  {:<72} {:>8}'.format(k, n))
        return 0
    if args.dry_run:
        return 0
    header = ['voted_at backfill from {} extract(s): {}'.format(len(args.extracts), ', '.join(os.path.basename(x) for x in args.extracts)),
              'generated {} UTC'.format(datetime.datetime.now(datetime.timezone.utc).strftime(SQL_TIME))]
    if args.session_attribution:
        header.append('with IP+UA session attribution')
    header.append('time of a row: ' + ('the first of the last same-value requests' if args.run_start else 'the latest request'))
    if problems:
        header.append('extracts with a gap, accepted (--allow-gap)')
    with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
        f.write(render_sql(plan, args.batch, header))
    with open(revert_path(args.out), 'w', encoding='utf-8', newline='\n') as f:
        f.write(render_revert_sql(plan, args.batch))
    print('Wrote {} UPDATEs to {}, the undo to {}'.format(len(plan.updates), args.out, revert_path(args.out)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
