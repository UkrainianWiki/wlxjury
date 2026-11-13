"""Tests of schema_parity.py (synthetic column dumps). Standard library only."""
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import schema_parity

UTF8 = "\tutf8mb4\tutf8mb4_general_ci"
NONE = "\t(none)\t(none)"

FRESH = (
    "users\tpassword\tYES\tNULL\tvarchar(255)\t" + UTF8 + "\n"
    "users\tcreated_at\tNO\tcurrent_timestamp()\ttimestamp\t" + NONE + "\n"
    "vote\tjury_id\tNO\t(none)\tint(11)\t" + NONE + "\n"
    "round\tname\tYES\tNULL\tvarchar(255)\t" + UTF8 + "\n"
)
PRODUCTION = (
    "users\tpassword\tNO\t(none)\tvarchar(255)\t" + UTF8 + "\n"
    "users\tcreated_at\tNO\tcurrent_timestamp()\ttimestamp\ton update current_timestamp()" + NONE + "\n"
    "vote\tjury_id\tYES\tNULL\tint(11)\t" + NONE + "\n"
    "round\tname\tYES\tNULL\tvarchar(255)\t\tutf8mb3\tutf8mb3_general_ci\n"
    "b1_email_v70\temail\tNO\t(none)\tvarchar(190)\t" + UTF8 + "\n"
)


class SchemaParityTest(unittest.TestCase):

    def test_flags_a_column_not_null_only_in_the_target(self):
        problems, notes = schema_parity.compare(
            schema_parity.load(FRESH.splitlines()), schema_parity.load(PRODUCTION.splitlines()))
        self.assertEqual(len(problems), 1)
        self.assertIn("users.password: NOT NULL in the target", problems[0])
        self.assertTrue(any(n.startswith("vote.jury_id: nullable in the target") for n in notes))
        self.assertTrue(any(n.startswith("users.created_at: extra") for n in notes))
        self.assertFalse(any("b1_email_v70" in n for n in notes + problems))

    def test_notes_character_set_and_collation_differences(self):
        problems, notes = schema_parity.compare(
            schema_parity.load(FRESH.splitlines()), schema_parity.load(PRODUCTION.splitlines()))
        self.assertIn("round.name: charset 'utf8mb3' in the target, 'utf8mb4' in fresh", notes)
        self.assertIn("round.name: collation 'utf8mb3_general_ci' in the target, 'utf8mb4_general_ci' in fresh",
                      notes)
        self.assertFalse(any("round.name" in p for p in problems))

    def test_same_schema_has_no_differences(self):
        columns = schema_parity.load(FRESH.splitlines())
        self.assertEqual(schema_parity.compare(columns, columns), ([], []))

    def test_refuses_a_malformed_dump(self):
        with self.assertRaises(ValueError):
            schema_parity.load(["users\tpassword\tmaybe\tNULL\tvarchar(255)\t" + UTF8])
        with self.assertRaisesRegex(ValueError, "line 1: expected 8 tab-separated fields .* got 1"):
            schema_parity.load(["just one field"])
        # the query of the first version (6 fields): told to dump again
        with self.assertRaisesRegex(ValueError, "got 6; dump it with --print-query"):
            schema_parity.load(["users\tpassword\tYES\tNULL\tvarchar(255)\t"])

    def write(self, d, name, text):
        path = os.path.join(d, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        return path

    def test_main_exit_status_and_output(self):
        with tempfile.TemporaryDirectory() as d:
            fresh, target = self.write(d, "fresh.tsv", FRESH), self.write(d, "target.tsv", PRODUCTION)
            out = io.StringIO()
            with redirect_stdout(out):
                status = schema_parity.main([fresh, target])
            self.assertEqual(status, 1)
            self.assertIn("PROBLEM users.password", out.getvalue())
            self.assertNotIn("note", out.getvalue())
            out = io.StringIO()
            with redirect_stdout(out):
                self.assertEqual(schema_parity.main([fresh, fresh, "--all"]), 0)
            self.assertIn("0 problem(s)", out.getvalue())

    def test_a_malformed_or_missing_dump_exits_2_with_a_message(self):
        with tempfile.TemporaryDirectory() as d:
            fresh = self.write(d, "fresh.tsv", FRESH)
            bad = self.write(d, "bad.tsv", "users\tpassword\n")
            for args, said in (([fresh, bad], "bad.tsv: line 1: expected 8"),
                               ([fresh, os.path.join(d, "missing.tsv")], "missing.tsv")):
                out, err = io.StringIO(), io.StringIO()
                with redirect_stdout(out), redirect_stderr(err):
                    self.assertEqual(schema_parity.main(args), 2)
                self.assertIn(said, err.getvalue())
                self.assertNotIn("Traceback", err.getvalue())
                self.assertEqual(out.getvalue(), "")

    def test_ignores_a_column_checked_by_hand(self):
        problems, _ = schema_parity.compare(
            schema_parity.load(FRESH.splitlines()), schema_parity.load(PRODUCTION.splitlines()),
            ignore=["Users.Password"])
        self.assertEqual(problems, [])

    def test_prints_the_query(self):
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(schema_parity.main(["--print-query"]), 0)
        self.assertIn("information_schema.COLUMNS", out.getvalue())
        self.assertIn("COLLATION_NAME", out.getvalue())


if __name__ == "__main__":
    unittest.main()
