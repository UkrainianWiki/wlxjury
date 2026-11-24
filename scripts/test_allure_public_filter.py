import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "allure_public_filter", Path(__file__).with_name("allure-public-filter.py"))
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)


class AllurePublicFilterTest(unittest.TestCase):
    def test_keeps_screenshots_and_drops_traces_logs_and_other_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            result = {"name": "t", "attachments": [
                {"name": "trace", "source": "a-attachment.zip", "type": "application/zip"},
                {"name": "log", "source": "b-attachment.txt", "type": "text/plain"}],
                "steps": [{"attachments": [
                    {"name": "shot", "source": "c-attachment.png", "type": "image/png"},
                    {"name": "fake", "source": "e-attachment.html", "type": "image/png"}]}]}
            (d / "r-result.json").write_text(json.dumps(result))
            for name in ["a-attachment.zip", "b-attachment.txt", "c-attachment.png", "e-attachment.html",
                         "environment.properties", "executor.json"]:
                (d / name).write_bytes(b"x")
            self.assertEqual(mod.filter_results(d), (1, 1, 5))
            self.assertEqual(sorted(p.name for p in d.iterdir()), ["c-attachment.png", "r-result.json"])
            out = json.loads((d / "r-result.json").read_text())
            self.assertEqual(out["attachments"], [])
            self.assertEqual([a["source"] for a in out["steps"][0]["attachments"]], ["c-attachment.png"])

    def test_no_results_is_a_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(mod.main(["x", tmp]), 1)


if __name__ == "__main__":
    unittest.main()
