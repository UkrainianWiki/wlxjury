"""Tests of scripts/check-private-strings.py with synthetic rules only; never reads a real list."""
import importlib.util
import os
import unittest

_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "check-private-strings.py")
_spec = importlib.util.spec_from_file_location("check_private_strings", _path)
cps = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cps)


class DeepRegexTest(unittest.TestCase):
    def test_deeply_nested_regex_is_a_refused_line_not_a_crash(self):
        deep = "(" * 5000 + "x" + ")" * 5000
        rules = cps.Rules("name\tneutralword\nname\t%s\n" % deep, "t")
        self.assertEqual(["line 2: the regex is too large or too deeply nested to compile"], rules.bad)
        self.assertEqual(1, len(rules.patterns))

    def test_deep_allow_and_skip_path_lines_are_refused_too(self):
        deep = "(" * 5000 + ")" * 5000
        rules = cps.Rules("allow\t%s\nskip-path\t%s\nname\tneutralword\n" % (deep, deep), "t")
        self.assertEqual(2, len(rules.bad))
        self.assertEqual("name", rules.line_hit("a neutralword here"))

    def test_problem_text_never_quotes_the_pattern(self):
        rules = cps.Rules("name\t" + "(" * 5000 + "secretish" + ")" * 5000 + "\n", "t")
        self.assertNotIn("secretish", "".join(rules.problems()))

    def test_huge_repeat_count_is_refused_and_others_kept(self):
        rules = cps.Rules("name\tneutralword\nname\ta{99999999999}\n", "t")
        self.assertEqual(["line 2: the regex is too large or too deeply nested to compile"], rules.bad)
        self.assertEqual(1, len(rules.patterns))
        self.assertNotIn("99999999999", "".join(rules.problems()))


if __name__ == "__main__":
    unittest.main()
