"""Tests of schema_parity.py (synthetic column dumps). Standard library only."""
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout

import schema_parity

FRESH = (
    "users\tpassword\tYES\tNULL\tvarchar(255)\t\n"
    "users\tcreated_at\tNO\tcurrent_timestamp()\ttimestamp\t\n"
    "vote\tjury_id\tNO\t(none)\tint(11)\t\n"
    "round\tname\tYES\tNULL\tvarchar(255)\t\n"
)
PRODUCTION = (
    "users\tpassword\tNO\t(none)\tvarchar(255)\t\n"
    "users\tcreated_at\tNO\tcurrent_timestamp()\ttimestamp\ton update current_timestamp()\n"
    "vote\tjury_id\tYES\tNULL\tint(11)\t\n"
    "round\tname\tYES\tNULL\tvarchar(255)\t\n"
    "b1_email_v70\temail\tNO\t(none)\tvarchar(190)\t\n"
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

    def test_same_schema_has_no_differences(self):
        columns = schema_parity.load(FRESH.splitlines())
        self.assertEqual(schema_parity.compare(columns, columns), ([], []))

    def test_refuses_a_malformed_dump(self):
        with self.assertRaises(ValueError):
            schema_parity.load(["users\tpassword\tmaybe\tNULL\tvarchar(255)\t"])
        with self.assertRaises(ValueError):
            schema_parity.load(["just one field"])

    def test_main_exit_status_and_output(self):
        with tempfile.TemporaryDirectory() as d:
            fresh, target = os.path.join(d, "fresh.tsv"), os.path.join(d, "target.tsv")
            with open(fresh, "w", encoding="utf-8") as f:
                f.write(FRESH)
            with open(target, "w", encoding="utf-8") as f:
                f.write(PRODUCTION)
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


if __name__ == "__main__":
    unittest.main()
