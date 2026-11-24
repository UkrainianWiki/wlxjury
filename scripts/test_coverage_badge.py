import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

_spec = importlib.util.spec_from_file_location("coverage_badge", Path(__file__).with_name("coverage-badge.py"))
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)


class CoverageBadgeTest(unittest.TestCase):
    def test_number_formatting_rounds_half_up(self):
        self.assertEqual(mod.format_pct(91.13), "91%")
        self.assertEqual(mod.format_pct(91.5), "92%")
        self.assertEqual(mod.format_pct(99.6), "100%")
        self.assertEqual(mod.format_pct(0), "0%")

    def test_colour_thresholds(self):
        for pct, color in [(100, "#4c1"), (95, "#4c1"), (94.99, "#97ca00"), (90, "#97ca00"), (89.9, "#a4a61d"),
                           (80, "#a4a61d"), (79.9, "#dfb317"), (70, "#dfb317"), (69.9, "#fe7d37"),
                           (50, "#fe7d37"), (49.9, mod.RED), (0, mod.RED)]:
            self.assertEqual(mod.color_for(pct), color, pct)

    def test_colour_follows_the_rounded_percent_shown(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / "s.xml").write_text('<scoverage statement-rate="94.99"/>')
            self.assertEqual(mod.main(["x", "coverage", str(d / "s.xml"), str(d / "o.svg")]), 0)
            text = (d / "o.svg").read_text()
            self.assertIn("95%", text)
            self.assertIn("#4c1", text)

    def test_svg_is_well_formed_and_escapes(self):
        svg = mod.badge("a<b", "1&2", "#4c1")
        root = ET.fromstring(svg)
        self.assertTrue(root.tag.endswith("svg"))
        self.assertIn("a&lt;b", svg)

    def test_coverage_command_reads_the_xml_and_refuses_a_bad_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / "s.xml").write_text('<scoverage statement-rate="91.13" branch-rate="86.3"/>')
            self.assertEqual(mod.main(["x", "coverage", str(d / "s.xml"), str(d / "o.svg")]), 0)
            text = (d / "o.svg").read_text()
            self.assertIn("coverage", text)
            self.assertIn("91%", text)
            self.assertIn("#97ca00", text)
            (d / "bad.xml").write_text("<scoverage/>")
            self.assertEqual(mod.main(["x", "coverage", str(d / "bad.xml"), str(d / "b.svg")]), 1)
            self.assertFalse((d / "b.svg").exists())
            self.assertEqual(mod.main(["x", "coverage", str(d / "missing.xml"), str(d / "m.svg")]), 1)


if __name__ == "__main__":
    unittest.main()
