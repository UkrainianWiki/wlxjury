import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("agent-check.py")
spec = importlib.util.spec_from_file_location("agent_check", SCRIPT)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class AgentCheckTest(unittest.TestCase):
    def test_sbt_prefixed_benchmark_summary_is_retained(self):
        text = "SQL debug output\n" * 1000
        text += "[info] [pairs] page-juror threads=1 block=1 paired delta mean=1.20 ms\n"
        text += "[success] Total time: 40 s\n"
        summary = helper.summarize(text, 0)
        self.assertIn("paired delta mean=1.20 ms", summary)
        self.assertNotIn("SQL debug output", summary)

    def test_success_ignores_expected_application_errors(self):
        text = "[error] s.j.JobScheduler - simulated failure\n" * 1000
        text += "[info] Passed: Total 20, Failed 0, Errors 0, Passed 20\n"
        summary = helper.summarize(text, 0)
        self.assertIn("Passed: Total 20", summary)
        self.assertNotIn("simulated failure", summary)

    def test_failure_keeps_diagnostics_and_bounds_large_output(self):
        text = "noise\n" * 10000 + "[error] app/Foo.scala:12: type mismatch\nactual type\nexpected type\n"
        text += "[error] Compilation failed\n"
        summary = helper.summarize(text, 1)
        self.assertIn("Foo.scala:12", summary)
        self.assertIn("expected type", summary)
        self.assertIn("Compilation failed", summary)
        self.assertLess(len(summary), 6000)

    def test_nonzero_exit_and_raw_log_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "check.log"
            command = [sys.executable, str(SCRIPT), "--log", str(log), "--", sys.executable,
                       "-c", "print('raw marker'); raise SystemExit(7)"]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 7)
            self.assertIn("raw marker", log.read_text())
            self.assertIn("exit=7", result.stdout)
            again = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(again.returncode, 2)
            self.assertEqual(log.read_text().count("raw marker"), 1)


if __name__ == "__main__":
    unittest.main()
