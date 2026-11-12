"""Tests of voted_at_from_logs with synthetic log lines (documentation addresses only).

    python -m unittest discover -s scripts/backfill
"""
import collections
import gzip
import io
import os
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


if __name__ == '__main__':
    unittest.main()
