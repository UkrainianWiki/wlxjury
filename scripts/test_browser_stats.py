"""Tests of browser-stats.py on synthetic vhost_combined lines (standard library only)."""
import datetime
import importlib.util
import pathlib
import unittest

_spec = importlib.util.spec_from_file_location("browser_stats", str(pathlib.Path(__file__).with_name("browser-stats.py")))
stats = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(stats)

CHROME = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{}.0.0.0 Safari/537.36"
NOW = datetime.datetime(2026, 11, 13, 12, 0, tzinfo=datetime.timezone.utc)


def line(when, client="198.51.100.7", ua=CHROME.format(130), path="/gallery/round/1", status=200):
    return (f'jury.wle.org.ua:443 {client} - - [{when:%d/%b/%Y:%H:%M:%S %z}] "GET {path} HTTP/1.1" {status} 512 '
            f'"-" "{ua}"\n')


class BrowserStatsTest(unittest.TestCase):
    def test_only_requests_of_the_window_count(self):
        cutoff = NOW - datetime.timedelta(days=14)
        lines = [line(NOW - datetime.timedelta(days=20), client="203.0.113.1", ua=CHROME.format(90)),
                 line(NOW - datetime.timedelta(days=2))]
        visitors, oldest, newest = stats.collect(lines, cutoff)
        self.assertEqual([v for v in visitors.values()], [(("Chrome", 130), False)])
        self.assertEqual((oldest, newest), (NOW - datetime.timedelta(days=20), NOW - datetime.timedelta(days=2)))
        self.assertFalse(any("WARNING" in row for row in stats.report(visitors, 14, cutoff, oldest, newest)))

    def test_a_short_log_is_reported(self):
        cutoff = NOW - datetime.timedelta(days=14)
        visitors, oldest, newest = stats.collect([line(NOW - datetime.timedelta(days=3)), line(NOW)], cutoff)
        rows = stats.report(visitors, 14, cutoff, oldest, newest)
        self.assertTrue(any(row.startswith("WARNING") and "3 days of data, not 14" in row for row in rows), rows)

    def test_every_major_version_is_listed(self):
        cutoff = NOW - datetime.timedelta(days=14)
        lines = [line(NOW, client=f"198.51.100.{n}", ua=CHROME.format(100 + n)) for n in range(13)]
        visitors, oldest, newest = stats.collect(lines, cutoff)
        chrome = [row for row in stats.report(visitors, 14, cutoff, oldest, newest) if row.startswith("Chrome:")]
        self.assertEqual(len(chrome), 1)
        self.assertIn("100:1", chrome[0])  # the oldest of 13 majors, which [:12] cut off
        self.assertIn("112:1", chrome[0])


if __name__ == "__main__":
    unittest.main()
