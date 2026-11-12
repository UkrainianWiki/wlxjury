"""Tests of voted_at_from_logs with synthetic log lines (documentation addresses only).

    python -m unittest discover -s scripts/backfill
"""
import collections
import contextlib
import gzip
import io
import os
import shlex
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voted_at_from_logs as bf  # noqa: E402

HOST = 'jury.example.org:443'
UA_A = 'TestAgent/1.0 (A)'
UA_B = 'TestAgent/1.0 (B)'


def line(when, method, path, status, ref='-', ip='192.0.2.10', ua=UA_A, log='other_vhosts_access.log'):
    ref = ref if ref == '-' else 'https://jury.example.org' + ref
    return '{} {} {} - - [{}] "{} {} HTTP/1.1" {} 123 "{}" "{}"\n'.format(
        log, HOST, ip, when, method, path, status, ref, ua)


def vote(when, rnd, page, sel, ref='-', ep='large', status=None, **kw):
    path = '/{}/round/{}/pageid/{}/select/{}'.format(ep, rnd, page, sel) + ('?sort=0' if ep == 'large' else '')
    return line(when, 'POST', path, status or (303 if ep == 'large' else 200), ref=ref, **kw)


def row(id, rnd, jury, page, rate, voted_at=None, table='vote'):
    return bf.Row(table, id, rnd, jury, page, rate, voted_at)


def plan_of(lines, rows, crit=(), files=None, **kw):
    stats = collections.Counter()
    src = [(0, l) for l in lines] if files is None else [(fi, l) for fi, ls in enumerate(files) for l in ls]
    events = bf.parse_events(src, stats, salt='test')
    return bf.make_plan(events, bf.DbData(rows, crit), **kw), stats


def times(plan):
    return {r.id: bf.utc_sql(t) for r, t in plan.updates}


class TimeTest(unittest.TestCase):
    def test_offsets_to_utc_with_milliseconds(self):
        self.assertEqual(bf.utc_sql(bf.parse_apache_time('05/Nov/2026:10:11:12 +0100')), '2026-11-05 09:11:12.000')
        self.assertEqual(bf.utc_sql(bf.parse_apache_time('25/Oct/2026:01:30:00 +0300')), '2026-10-24 22:30:00.000')
        self.assertEqual(bf.utc_sql(bf.parse_apache_time('31/Dec/2026:22:00:00 -0230')), '2027-01-01 00:30:00.000')

    def test_same_instant_in_two_offsets_orders_by_utc(self):
        # the later request by the clock is earlier in UTC: latest wins by UTC, not by local time
        lines = [vote('05/Nov/2026:10:00:30 +0100', 1, 100, 1),   # 09:00:30 UTC
                 vote('05/Nov/2026:11:00:10 +0200', 1, 100, -1)]  # 09:00:10 UTC
        plan, _ = plan_of(lines, [row(7, 1, 50, 100, 1)])
        self.assertEqual(times(plan), {7: '2026-11-05 09:00:30.000'})

    def test_rotation_index(self):
        self.assertEqual(bf.rotation_index('other_vhosts_access.log'), 0)
        self.assertEqual(bf.rotation_index('other_vhosts_access.log.1'), 1)
        self.assertEqual(bf.rotation_index('other_vhosts_access.log.12.gz'), 12)


class AttributionTest(unittest.TestCase):
    def test_single_vote_row(self):
        plan, _ = plan_of([vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1)], [row(7, 1, 50, 100, 1)])
        self.assertEqual(times(plan), {7: '2026-11-05 09:00:00.000'})
        self.assertEqual(plan.req['attributed: single vote row'], 1)

    def test_referer_juror_among_several(self):
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 100, 3, ep='rate', ref='/gallery/round/1/user/51/page/2'),
                 vote('05/Nov/2026:10:00:05 +0100', 1, 100, 4, ref='/large/round/1/user/52/region/all/pageid/100')]
        plan, _ = plan_of(lines, [row(7, 1, 50, 100, 0), row(8, 1, 51, 100, 3), row(9, 1, 52, 100, 4)])
        self.assertEqual(times(plan), {8: '2026-11-05 09:00:00.000', 9: '2026-11-05 09:00:05.000'})
        self.assertEqual(plan.req['attributed: referer juror'], 2)

    def test_several_vote_rows_without_referer_is_ambiguous(self):
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1, ref='/query')]
        plan, _ = plan_of(lines, [row(7, 1, 50, 100, 1), row(8, 1, 51, 100, 1)])
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.req['skipped: ambiguous juror'], 1)

    def test_referer_juror_without_vote_row_is_skipped(self):
        # the single row is another juror's: the voter's own row is gone, not that juror's vote
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1, ref='/large/round/1/user/99/pageid/100')]
        plan, _ = plan_of(lines, [row(7, 1, 50, 100, 1)])
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.req['skipped: vote row missing (referer juror has none)'], 1)

    def test_no_vote_rows_failed_requests_and_criteria(self):
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 999, 1),                    # no vote row
                 vote('05/Nov/2026:10:00:01 +0100', 1, 100, 1, status=404),        # not voted
                 vote('05/Nov/2026:10:00:02 +0100', 1, 100, 1, ep='rate', status=409),
                 line('05/Nov/2026:10:00:03 +0100', 'GET', '/large/round/1/pageid/100/select/1', 404),
                 vote('05/Nov/2026:10:00:04 +0100', 2, 100, 3)]                    # criteria round
        plan, _ = plan_of(lines, [row(7, 1, 50, 100, 1), row(8, 2, 50, 100, 9)], crit=[2])
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.req['vote requests'], 4)
        self.assertEqual(plan.req['skipped: vote row missing (file has none in the round)'], 1)
        self.assertEqual(plan.req['skipped: request failed (status)'], 2)
        self.assertEqual(plan.req['skipped: criteria round (a criterion, not the vote, was rated)'], 1)

    def test_region_and_query_forms(self):
        lines = [line('05/Nov/2026:10:00:00 +0100', 'POST',
                      '/large/round/1/region/80-361/pageid/100/select/-1?filter=1&sort=0', 303)]
        plan, _ = plan_of(lines, [row(7, 1, 50, 100, -1)])
        self.assertEqual(times(plan), {7: '2026-11-05 09:00:00.000'})

    def test_session_attribution_only_with_the_flag(self):
        lines = [line('05/Nov/2026:10:00:00 +0100', 'GET', '/gallery/round/1/user/51/page/1', 200),
                 vote('05/Nov/2026:10:05:00 +0100', 1, 100, 1),
                 # another browser on the same address
                 vote('05/Nov/2026:10:05:00 +0100', 1, 101, 1, ua=UA_B)]
        rows = [row(7, 1, 50, 100, 1), row(8, 1, 51, 100, 1), row(9, 1, 50, 101, 1), row(10, 1, 51, 101, 1)]
        plan, _ = plan_of(lines, rows)
        self.assertEqual(plan.updates, [])
        plan, _ = plan_of(lines, rows, session_attribution=True)
        self.assertEqual(times(plan), {8: '2026-11-05 09:05:00.000'})
        self.assertEqual(plan.req['attributed: IP+UA session (optional)'], 1)
        self.assertEqual(plan.req['skipped: ambiguous juror'], 1)

    def test_session_tie_does_not_cross_a_sign_in(self):
        lines = [line('05/Nov/2026:10:00:00 +0100', 'GET', '/gallery/round/1/user/51/page/1', 200),
                 line('05/Nov/2026:10:01:00 +0100', 'POST', '/auth', 303),
                 vote('05/Nov/2026:10:05:00 +0100', 1, 100, 1)]
        plan, _ = plan_of(lines, [row(7, 1, 50, 100, 1), row(8, 1, 51, 100, 1)], session_attribution=True)
        self.assertEqual(plan.updates, [])


class LatestWinsTest(unittest.TestCase):
    rows = [row(7, 1, 50, 100, 2)]

    def test_latest_by_time_not_by_extract_order(self):
        # the extract lists the current log first, then older rotations
        lines = [vote('06/Nov/2026:10:00:00 +0100', 1, 100, 2),
                 vote('05/Nov/2026:10:00:00 +0100', 1, 100, 5, log='other_vhosts_access.log.1')]
        plan, _ = plan_of(lines, self.rows)
        self.assertEqual(times(plan), {7: '2026-11-06 09:00:00.000'})

    def test_same_second_log_order(self):
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 100, 5), vote('05/Nov/2026:10:00:00 +0100', 1, 100, 2)]
        plan, _ = plan_of(lines, self.rows)
        self.assertEqual(len(plan.updates), 1)
        plan, _ = plan_of(list(reversed(lines)), self.rows)
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.rows['skipped: value mismatch (latest request set another value)'], 1)

    def test_same_second_older_rotation_first(self):
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 100, 2),
                 vote('05/Nov/2026:10:00:00 +0100', 1, 100, 5, log='other_vhosts_access.log.1')]
        plan, _ = plan_of(lines, self.rows)
        self.assertEqual(len(plan.updates), 1)

    def test_value_mismatch(self):
        plan, _ = plan_of([vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1),
                           vote('05/Nov/2026:10:00:09 +0100', 1, 100, 5)], self.rows)
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.rows['skipped: value mismatch (latest request set another value)'], 1)

    def test_unrated_now(self):
        plan, _ = plan_of([vote('05/Nov/2026:10:00:00 +0100', 1, 100, 0)], [row(7, 1, 50, 100, 0)])
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.rows['skipped: unrated now (rate 0)'], 1)

    def test_run_start_option(self):
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1),
                 vote('05/Nov/2026:10:00:10 +0100', 1, 100, 2),
                 vote('05/Nov/2026:10:00:20 +0100', 1, 100, 2)]
        plan, _ = plan_of(lines, self.rows)
        self.assertEqual(times(plan), {7: '2026-11-05 09:00:20.000'})
        self.assertEqual(plan.diag['same value voted again after the change (latest vs run start differ)'], 1)
        plan, _ = plan_of(lines, self.rows, run_start=True)
        self.assertEqual(times(plan), {7: '2026-11-05 09:00:10.000'})


class OverlapTest(unittest.TestCase):
    def test_overlapping_extracts_count_lines_once(self):
        a = vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1)
        b = vote('05/Nov/2026:10:00:30 +0100', 1, 100, -1)
        c = vote('05/Nov/2026:10:01:00 +0100', 1, 100, 1)
        # the second extract was made after a rotation: the same lines under another file name
        renamed = [x.replace('other_vhosts_access.log ', 'other_vhosts_access.log.1 ') for x in (a, b)]
        plan, stats = plan_of(None, [row(7, 1, 50, 100, 1)], files=[[a, b], renamed + [c]])
        self.assertEqual(stats['lines'], 3)
        self.assertEqual(stats['lines_duplicate'], 2)
        self.assertEqual(plan.req['vote requests'], 3)
        self.assertEqual(times(plan), {7: '2026-11-05 09:01:00.000'})

    def test_repeated_line_within_one_extract_is_kept(self):
        a = vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1)
        _, stats = plan_of(None, [row(7, 1, 50, 100, 1)], files=[[a, a], [a]])
        self.assertEqual(stats['lines'], 2)
        self.assertEqual(stats['lines_duplicate'], 1)

    def test_reads_gzip_and_plain_files(self):
        d = tempfile.mkdtemp()
        try:
            p1, p2 = os.path.join(d, 'a.gz'), os.path.join(d, 'b.txt')
            with gzip.open(p1, 'wt', encoding='utf-8') as f:
                f.write(vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1))
            with open(p2, 'w', encoding='utf-8') as f:
                f.write(vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1))
            stats = collections.Counter()
            events = bf.parse_events(bf.read_lines([p1, p2]), stats, salt='t')
            self.assertEqual(len(events), 1)
            self.assertEqual(stats['lines_duplicate'], 1)
        finally:
            shutil.rmtree(d)


class SqlTest(unittest.TestCase):
    def test_rerun_guard_and_layout(self):
        lines = [vote('05/Nov/2026:10:00:0{} +0100'.format(i), 1, 100 + i, 1) for i in range(3)]
        rows = [row(7, 1, 50, 100, 1), row(8, 1, 50, 101, 1, voted_at='2026-11-12 10:00:00.000'),
                row(9, 1, 50, 102, 1, table='vote_aside')]
        plan, _ = plan_of(lines, rows)
        self.assertEqual(plan.rows['matched'], 2)
        self.assertEqual(plan.rows['skipped: already timed'], 1)
        sql = bf.render_sql(plan, batch=1, header=['test'])
        lines = sql.splitlines()
        self.assertEqual(lines[0], "SET time_zone = '+00:00';")
        self.assertIn("UPDATE vote SET voted_at = '2026-11-05 09:00:00.000' "
                      "WHERE id = 7 AND rate = 1 AND voted_at IS NULL;", lines)
        self.assertIn("UPDATE vote_aside SET voted_at = '2026-11-05 09:00:02.000' "
                      "WHERE id = 9 AND rate = 1 AND voted_at IS NULL;", lines)
        self.assertEqual(sql.count('START TRANSACTION;'), 2)
        self.assertEqual(sql.count('COMMIT;'), 2)
        self.assertTrue(lines[-1].startswith('--'))
        self.assertTrue(all(l.startswith('--') for l in lines[lines.index('-- summary: 2 updates in 2 transactions'):]))
        self.assertNotIn('192.0.2', sql)
        self.assertNotIn('TestAgent', sql)

    def test_check_after_applying(self):
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1), vote('05/Nov/2026:10:00:01 +0100', 1, 101, 1),
                 vote('05/Nov/2026:10:00:02 +0100', 1, 102, 1)]
        applied = [row(7, 1, 50, 100, 1, voted_at='2026-11-05 09:00:00.000'),
                   row(8, 1, 50, 101, 1, voted_at='2026-11-12 08:00:00.123'),
                   row(9, 1, 50, 102, 1)]
        plan, _ = plan_of(lines, applied)
        c = bf.check(plan, bf.DbData(applied))
        self.assertEqual(c, collections.Counter({'has the time from the logs': 1,
                                                 'has another time (voted again in the app)': 1,
                                                 'still without a time': 1}))

    def test_db_output_parsing(self):
        out = ('vote\t7\t1\t50\t100\t1\t\n'
               'vote\t8\t1\t51\t100\t-1\t2026-11-12 10:00:00.000\n'
               'vote_aside\t9\t1\t52\t100\t2\t\n'
               'round\t1\t0\nround\t2\t1\n')
        db = bf.parse_db_output(out)
        self.assertEqual(sorted(db.cand[(1, 100)]), [50, 51, 52])
        self.assertIsNone(db.cand[(1, 100)][50].voted_at)
        self.assertEqual(db.cand[(1, 100)][51].voted_at, '2026-11-12 10:00:00.000')
        self.assertEqual(db.cand[(1, 100)][52].table, 'vote_aside')
        self.assertEqual(db.criteria_rounds, {2})
        self.assertIn("SET time_zone = '+00:00';", bf.db_query_sql({3, 1}))
        self.assertIn('round_id IN (1,3)', bf.db_query_sql({3, 1}))

    def test_report_has_counts_only(self):
        plan, _ = plan_of([vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1)], [row(7, 1, 50, 100, 1)])
        buf = io.StringIO()
        bf.report(plan, out=buf)
        text = buf.getvalue()
        self.assertIn('2026-11-05', text)
        self.assertNotIn('192.0.2', text)
        self.assertNotIn('TestAgent', text)



class LiveAndAsideTest(unittest.TestCase):
    def test_live_row_wins_over_aside_row(self):
        rows = [row(7, 1, 50, 100, 1), row(70, 1, 50, 100, -1, table='vote_aside')]
        for order in (rows, list(reversed(rows))):
            db = bf.DbData(order)
            self.assertEqual(db.cand[(1, 100)][50].id, 7)
            self.assertEqual(db.live_and_aside, 1)
        plan, _ = plan_of([vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1)], rows)
        self.assertEqual([(r.table, r.id) for r, _ in plan.updates], [('vote', 7)])
        self.assertEqual(plan.diag['juror with a live and an aside row of a file (the live one used)'], 1)
        applied = [row(7, 1, 50, 100, 1, voted_at='2026-11-05 09:00:00.000'),
                   row(70, 1, 50, 100, -1, table='vote_aside')]
        self.assertEqual(bf.check(plan, bf.DbData(applied)), collections.Counter({'has the time from the logs': 1}))


class MoreAttributionTest(unittest.TestCase):
    def test_criteria_query_is_skipped(self):
        lines = [line('05/Nov/2026:10:00:00 +0100', 'POST', '/large/round/1/pageid/100/select/3?criteria=5&sort=0', 303)]
        plan, _ = plan_of(lines, [row(7, 1, 50, 100, 3)])
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.req['skipped: criteria round (a criterion, not the vote, was rated)'], 1)

    def test_referer_of_another_round_is_ignored(self):
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1, ref='/gallery/round/2/user/51/page/1')]
        plan, _ = plan_of(lines, [row(7, 1, 50, 100, 1)])   # the single row decides
        self.assertEqual(times(plan), {7: '2026-11-05 09:00:00.000'})
        self.assertEqual(plan.diag['referer of another round (ignored)'], 1)
        plan, _ = plan_of(lines, [row(7, 1, 50, 100, 1), row(8, 1, 51, 100, 1)])
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.req['skipped: ambiguous juror'], 1)

    def test_later_unattributed_request_skips_the_row(self):
        rows = [row(7, 1, 50, 100, 1), row(8, 1, 51, 100, 0)]
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1, ref='/gallery/round/1/user/50/page/1'),
                 vote('05/Nov/2026:10:00:30 +0100', 1, 100, 1, ip='198.51.100.7')]   # no referer
        plan, _ = plan_of(lines, rows)
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.rows['skipped: a later unattributed request on the file'], 1)
        earlier = [lines[0], lines[1].replace('10:00:30', '09:59:30')]
        plan, _ = plan_of(earlier, rows)
        self.assertEqual(len(plan.updates), 1)   # the unattributed one came first

    def test_plain_and_ipv6_lines(self):
        plain = vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1).split(' ', 1)[1]
        v6 = vote('05/Nov/2026:10:00:05 +0100', 1, 101, 1, ip='2001:db8::1')
        v6plain = vote('05/Nov/2026:10:00:09 +0100', 1, 102, 1, ip='2001:db8::2').split(' ', 1)[1]
        rows = [row(7, 1, 50, 100, 1), row(8, 1, 50, 101, 1), row(9, 1, 50, 102, 1)]
        plan, stats = plan_of([plain, v6, v6plain], rows)
        self.assertEqual(stats['lines_unparsed'], 0)
        self.assertEqual(sorted(times(plan)), [7, 8, 9])

    def test_other_vhosts_are_left_out(self):
        stats = collections.Counter()
        lines = [(0, vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1)),
                 (0, vote('05/Nov/2026:10:00:01 +0100', 1, 100, 1).replace(HOST, 'jury-test.example.org:443'))]
        events = bf.parse_events(lines, stats, salt='t', vhosts={'jury.example.org'})
        self.assertEqual(len(events), 1)
        self.assertEqual(stats['lines_other_vhost'], 1)


class FailedVoteTest(unittest.TestCase):
    rows = [row(7, 1, 50, 100, 1)]
    ref = '/large/round/1/user/50/pageid/100'

    def plan(self, after):
        return plan_of([vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1, ref=self.ref)] + after, self.rows)[0]

    def test_login_page_next_means_signed_out(self):
        plan = self.plan([line('05/Nov/2026:10:00:01 +0100', 'GET', '/auth', 200)])
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.req['skipped: failed (login page next: signed out)'], 1)

    def test_gallery_without_query_next_means_closed_round(self):
        plan = self.plan([line('05/Nov/2026:10:00:00 +0100', 'GET', '/gallery/round/1/user/50/page/1', 200)])
        self.assertEqual(plan.updates, [])
        self.assertEqual(plan.req['skipped: failed (sent to the gallery: round closed or juror stopped)'], 1)

    def test_the_votes_own_redirects_keep_it(self):
        for nxt in ('/large/round/1/user/50/pageid/101?sort=0',
                    '/gallery/round/1/user/50/page/1?filter=unrated&sort=0'):
            plan = self.plan([line('05/Nov/2026:10:00:01 +0100', 'GET', nxt, 200)])
            self.assertEqual(len(plan.updates), 1, nxt)

    def test_only_the_same_session_and_within_the_window(self):
        plan = self.plan([line('05/Nov/2026:10:00:01 +0100', 'GET', '/auth', 200, ua=UA_B),
                          line('05/Nov/2026:10:00:30 +0100', 'GET', '/auth', 200)])
        self.assertEqual(len(plan.updates), 1)

    def test_gallery_vote_with_login_after(self):
        lines = [vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1, ep='rate', ref='/gallery/round/1/user/50/page/1'),
                 line('05/Nov/2026:10:00:02 +0100', 'GET', '/auth?returnTo=%2Fgallery', 200)]
        plan, _ = plan_of(lines, self.rows)
        self.assertEqual(plan.updates, [])

    def test_before_the_cutover_a_last_file_vote_to_the_gallery_is_kept(self):
        # 6fa5b56f sent a vote on the last unrated file to the gallery without sort=
        def plan(when, nxt_when):
            return plan_of([vote(when, 1, 100, 1, ref=self.ref),
                            line(nxt_when, 'GET', '/gallery/round/1/user/50/page/1', 200)], self.rows)[0]
        self.assertEqual(len(plan('01/Nov/2026:10:00:00 +0100', '01/Nov/2026:10:00:01 +0100').updates), 1)
        self.assertEqual(len(plan('02/Nov/2026:15:00:59 +0100', '02/Nov/2026:15:01:00 +0100').updates), 1)
        self.assertEqual(plan('02/Nov/2026:15:01:00 +0100', '02/Nov/2026:15:01:01 +0100').updates, [])
        self.assertEqual(bf.utc_sql(bf.NEXT_PAGE_RULE_FROM), '2026-11-02 14:01:00.000')

    def test_login_page_right_after_a_sign_in_or_out_is_not_a_failed_vote(self):
        for post in ('/logout', '/auth'):
            plan = self.plan([line('05/Nov/2026:10:00:01 +0100', 'POST', post, 303),
                              line('05/Nov/2026:10:00:01 +0100', 'GET', '/auth', 200)])
            self.assertEqual(len(plan.updates), 1, post)
        # another browser's sign-out does not count
        plan = self.plan([line('05/Nov/2026:10:00:01 +0100', 'POST', '/logout', 303, ua=UA_B),
                          line('05/Nov/2026:10:00:01 +0100', 'GET', '/auth', 200)])
        self.assertEqual(plan.updates, [])


class ExtractOrderTest(unittest.TestCase):
    def test_same_second_across_extracts_the_later_extract_last(self):
        # the older extract's line is in the current log, the newer one's in a rotated log:
        # rotation numbers are not comparable across extracts, the extracts' order is
        x = vote('05/Nov/2026:10:00:00 +0100', 1, 100, 5)
        y = vote('05/Nov/2026:10:00:00 +0100', 1, 100, 2, log='other_vhosts_access.log.1', ip='192.0.2.11')
        plan, _ = plan_of(None, [row(7, 1, 50, 100, 2)], files=[[x], [y]])
        self.assertEqual(len(plan.updates), 1)

    def test_coverage(self):
        d = 86400
        self.assertEqual(bf.coverage_problems({0: [0, 10 * d], 1: [5 * d, 20 * d]}, 2), [])
        gap = bf.coverage_problems({0: [0, 10 * d], 1: [11 * d, 20 * d]}, 2)
        self.assertEqual(len(gap), 1)
        self.assertIn('gap from 1970-01-11 00:00:00 to 1970-01-12 00:00:00', gap[0])
        self.assertIn('oldest first', bf.coverage_problems({0: [5 * d, 20 * d], 1: [0, 10 * d]}, 2)[0])
        self.assertIn('no log lines', bf.coverage_problems({0: [0, d]}, 2)[0])

    def test_gaps_between_rotation_files_of_one_extract_warn(self):
        h = 3600
        log = 'other_vhosts_access.log'
        night = {(0, log + '.2.gz'): [0, 20 * h], (0, log + '.1'): [29 * h, 44 * h], (0, log): [53 * h, 60 * h]}
        self.assertEqual(bf.file_gap_warnings(night), [])   # quiet nights of 9 h
        missing = {(0, log + '.3.gz'): [0, 20 * h], (0, log + '.1'): [53 * h, 60 * h], (1, log): [0, h]}
        w = bf.file_gap_warnings(missing)
        self.assertEqual(len(w), 2)
        self.assertIn('extract 1: no lines from rotation file(s) 2', w[0])
        self.assertIn('33.0 h without lines between other_vhosts_access.log.3.gz and other_vhosts_access.log.1', w[1])


class BatchTest(unittest.TestCase):
    def sql(self, n):
        plan = bf.Plan()
        plan.updates = [(row(i, 1, 50, i, 1), 1793000000) for i in range(1, n + 1)]
        return bf.render_sql(plan), bf.render_revert_sql(plan)

    def test_batch_boundary(self):
        for n, tx in ((1000, 1), (1001, 2), (0, 0)):
            sql, revert = self.sql(n)
            self.assertEqual(sql.count('START TRANSACTION;'), tx, n)
            self.assertEqual(sql.count('COMMIT;'), tx, n)
            self.assertEqual(sql.count('UPDATE vote '), n)
            self.assertEqual(revert.count('COMMIT;'), tx, n)
        sql, revert = self.sql(1001)
        lines = sql.splitlines()
        self.assertEqual(lines.index('COMMIT;') - lines.index('START TRANSACTION;'), 1001)
        self.assertEqual(revert.splitlines()[0], "SET time_zone = '+00:00';")
        self.assertIn("UPDATE vote SET voted_at = NULL WHERE id = 1 AND voted_at = '2026-10-26 07:33:20.000';", revert)


STUB = '''
import sys
out, code, argv_file = sys.argv[1], int(sys.argv[2]), sys.argv[3]
open(argv_file, 'w').write('\\n'.join(sys.argv[4:]) + '\\n' + sys.stdin.read())
if code:
    sys.stderr.write("ERROR 1054 (42S22): Unknown column 'voted_at' in 'field list'\\n")
    sys.exit(code)
sys.stdout.write(open(out).read())
'''


class MainTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.d)
        self.stub = self.path('stub.py', STUB)
        self.db_out = self.path('db.tsv', 'vote\t7\t1\t50\t100\t1\t\nvote\t8\t1\t51\t100\t2\t\nround\t1\t0\n')
        self.ex1 = self.path('a.log', vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1, ref='/gallery/round/1/user/50/page/1')
                             + vote('06/Nov/2026:10:00:00 +0100', 1, 100, 2, ref='/gallery/round/1/user/51/page/1'))
        self.ex2 = self.path('b.log', vote('06/Nov/2026:10:00:00 +0100', 1, 100, 2, ref='/gallery/round/1/user/51/page/1',
                                           log='other_vhosts_access.log.1')
                             + vote('07/Nov/2026:10:00:00 +0100', 1, 100, 9, ref='/gallery/round/1/user/52/page/1'))
        self.argv_file = os.path.join(self.d, 'argv.txt')

    def path(self, name, text):
        p = os.path.join(self.d, name)
        with open(p, 'w', encoding='utf-8') as f:
            f.write(text)
        return p

    def run_main(self, *args, code=0):
        client = ' '.join(shlex.quote(a) for a in (sys.executable, self.stub, self.db_out, str(code), self.argv_file))
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = bf.main(['--client', client, '--defaults-extra-file', '/etc/x.cnf', '--db', 'jurydb',
                          '--vhosts', 'jury.example.org'] + list(args))
        return rc, buf.getvalue()

    def test_writes_sql_and_undo_and_prints_counts_only(self):
        out = os.path.join(self.d, 'v.sql')
        rc, stdout = self.run_main('--out', out, self.ex1, self.ex2)
        self.assertEqual(rc, 0)
        with open(self.argv_file) as f:
            argv = f.read()
        self.assertTrue(argv.startswith('--defaults-extra-file=/etc/x.cnf\n--batch\n--skip-column-names\njurydb\n'))
        self.assertIn('round_id IN (1)', argv)
        with open(out) as f:
            sql = f.read()
        self.assertIn("UPDATE vote SET voted_at = '2026-11-05 09:00:00.000' WHERE id = 7 AND rate = 1", sql)
        self.assertIn("UPDATE vote SET voted_at = '2026-11-06 09:00:00.000' WHERE id = 8 AND rate = 2", sql)
        with open(os.path.join(self.d, 'v.revert.sql')) as f:
            self.assertIn("SET voted_at = NULL WHERE id = 8 AND voted_at = '2026-11-06 09:00:00.000';", f.read())
        self.assertIn('Extract 1: 2026-11-05 09:00:00 to 2026-11-06 09:00:00 UTC', stdout)
        self.assertIn('1 duplicates across extracts', stdout)
        for text in (stdout, sql):
            self.assertNotIn('192.0.2', text)
            self.assertNotIn('TestAgent', text)
            self.assertNotIn('jury.example.org', text)

    def test_client_failure_stops_with_its_message(self):
        with self.assertRaises(SystemExit) as e:
            self.run_main('--dry-run', self.ex1, code=1)
        self.assertIn('mariadb failed (1)', str(e.exception.code))
        self.assertIn('run this after the release with V97', str(e.exception.code))

    def test_gap_stops_unless_allowed(self):
        late = self.path('c.log', vote('20/Nov/2026:10:00:00 +0100', 1, 100, 1))
        with self.assertRaises(SystemExit) as e:
            self.run_main('--dry-run', self.ex1, late)
        self.assertIn('--allow-gap', str(e.exception.code))
        rc, stdout = self.run_main('--dry-run', '--allow-gap', self.ex1, late)
        self.assertEqual(rc, 0)
        self.assertIn('Coverage: gap from 2026-11-06 09:00:00 to 2026-11-20 09:00:00 UTC', stdout)

    def test_newest_first_is_refused(self):
        with self.assertRaises(SystemExit):
            self.run_main('--dry-run', self.ex2, self.ex1)

    def test_existing_undo_is_kept_unless_forced(self):
        out = os.path.join(self.d, 'v.sql')
        self.run_main('--out', out, self.ex1)
        with self.assertRaises(SystemExit) as e:
            self.run_main('--out', out, self.ex1)
        self.assertIn('--force', str(e.exception.code))
        rc, _ = self.run_main('--out', out, '--force', self.ex1)
        self.assertEqual(rc, 0)
        rc, _ = self.run_main('--out', out, '--dry-run', self.ex1)   # writes nothing: no refusal
        self.assertEqual(rc, 0)

    def test_other_vhosts_are_counted_in_the_report(self):
        other = self.path('o.log', vote('05/Nov/2026:10:00:00 +0100', 1, 100, 1).replace(HOST, 'www.example.org:443'))
        _, stdout = self.run_main('--dry-run', '--allow-gap', self.ex1, other)
        self.assertIn('1 other vhosts', stdout)

    def test_run_start_default_is_one_constant(self):
        _, stdout = self.run_main('--dry-run', self.ex1)
        self.assertIn('Time of a row: ' + ('the first of the last same-value requests (--run-start)'
                                           if bf.RUN_START_DEFAULT else 'the latest request'), stdout)
        _, stdout = self.run_main('--dry-run', '--run-start', self.ex1)
        self.assertIn('Time of a row: the first of the last same-value requests', stdout)
        _, stdout = self.run_main('--dry-run', '--latest', self.ex1)
        self.assertIn('Time of a row: the latest request', stdout)

if __name__ == '__main__':
    unittest.main()
