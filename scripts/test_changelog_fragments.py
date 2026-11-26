import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

_spec = importlib.util.spec_from_file_location("changelog_fragments", Path(__file__).with_name("changelog-fragments.py"))
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)

CHANGELOG = """# Changelog

Intro.

## Unreleased (on master, not yet deployed)

Merged after the 26.11.17 build.

**Features**

- Old feature (#205).

**Operations**

- Health line.
- Migrations: V116.

## Deployed releases

### 26.11.17.2 (2026-11-26)

**Fixes**

- Shipped hotfix line (#224).

### 26.11.17 (2026-11-26)

**Fixes**

- Older fix (#192).
"""


class Repo:
    """A temporary root with CHANGELOG.md and changes/unreleased/."""

    def __init__(self, changelog=CHANGELOG):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.folder = self.root / "changes" / "unreleased"
        self.folder.mkdir(parents=True)
        (self.folder / "README.md").write_text("# not a fragment\n", encoding="utf-8")
        self.changelog = self.root / "CHANGELOG.md"
        self.changelog.write_text(changelog, encoding="utf-8")

    def add(self, name, text):
        (self.folder / name).write_text(text, encoding="utf-8")

    def run(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = mod.main(["--root", str(self.root)] + list(args))
        return code, out.getvalue(), err.getvalue()

    def text(self):
        return self.changelog.read_text(encoding="utf-8")

    def fragments(self):
        return sorted(p.name for p in self.folder.iterdir() if p.name != "README.md")

    def close(self):
        self.tmp.cleanup()


class FoldTest(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()

    def tearDown(self):
        self.repo.close()

    def unreleased(self):
        text = self.repo.text()
        return text[text.index("## Unreleased"):text.index("## Deployed releases")]

    def test_lines_go_under_their_section_after_the_existing_ones_and_fragments_are_deleted(self):
        self.repo.add("feat-b.md", "section: Features\n\n- New feature (#240).\n")
        self.assertEqual(self.repo.run("fold")[0], 0)
        self.assertIn("- Old feature (#205).\n- New feature (#240).\n\n**Operations**", self.unreleased())
        self.assertEqual(self.repo.fragments(), [])
        self.assertTrue((self.repo.folder / "README.md").exists())

    def test_order_is_by_pr_number_then_file_name_for_fragments_without_one(self):
        self.repo.add("a-late.md", "section: Fixes\n\n- Fix of PR 250 (#250).\n")
        self.repo.add("z-early.md", "section: Fixes\n\n- Fix of PR 99 (#99).\n")
        self.repo.add("b-nopr.md", "section: Fixes\n\n- Fix b without PR.\n")
        self.repo.add("a-nopr.md", "section: Fixes\n\n- Fix a without PR.\n")
        self.repo.run("fold")
        u = self.unreleased()
        order = [u.index(s) for s in ["(#99)", "(#250)", "Fix a without", "Fix b without"]]
        self.assertEqual(order, sorted(order))

    def test_a_missing_section_is_created_in_the_standard_order(self):
        self.repo.add("fix.md", "section: Fixes\n\n- A fix (#241).\n")
        self.repo.add("sec.md", "section: Security\n\n- A security line (#242).\n")
        self.repo.run("fold")
        u = self.unreleased()
        self.assertIn("- Old feature (#205).\n\n**Fixes**\n\n- A fix (#241).\n\n**Security**\n\n"
                      "- A security line (#242).\n\n**Operations**", u)

    def test_a_section_after_the_last_one_goes_at_the_blocks_end(self):
        repo = Repo(CHANGELOG.replace("**Operations**\n\n- Health line.\n- Migrations: V116.\n\n", ""))
        try:
            repo.add("ops.md", "section: Operations\n\n- New ops line (#243).\n")
            repo.run("fold")
            self.assertIn("- Old feature (#205).\n\n**Operations**\n\n- New ops line (#243).\n\n## Deployed releases",
                          repo.text())
        finally:
            repo.close()

    def test_an_empty_unreleased_block_gets_its_first_section(self):
        repo = Repo("# Changelog\n\n## Unreleased (on master, not yet deployed)\n\n## Deployed releases\n\n### 1\n")
        try:
            repo.add("f.md", "section: Fixes\n\n- First (#1).\n")
            repo.run("fold")
            self.assertEqual(repo.text(), "# Changelog\n\n## Unreleased (on master, not yet deployed)\n\n"
                                          "**Fixes**\n\n- First (#1).\n\n## Deployed releases\n\n### 1\n")
        finally:
            repo.close()

    def test_one_fragment_can_carry_several_sections_and_lines(self):
        self.repo.add("multi.md", "section: Features\n- F1 (#244).\n- F2 (#244).\n\nsection: Operations\n- O1 (#244).\n")
        self.repo.run("fold")
        u = self.unreleased()
        self.assertIn("- Old feature (#205).\n- F1 (#244).\n- F2 (#244).\n", u)
        self.assertIn("- Migrations: V116.\n- O1 (#244).\n", u)

    def test_migrations_merge_into_the_blocks_one_line(self):
        self.repo.add("m1.md", "section: Operations\n\n- Migrations: V118.\n")
        self.repo.add("m2.md", "section: Operations\n\n- Migrations: V117, V116\n")
        self.repo.run("fold")
        u = self.unreleased()
        self.assertIn("- Migrations: V116, V117, V118.\n", u)
        self.assertEqual(u.count("Migrations"), 1)

    def test_into_a_release_block(self):
        self.repo.add("hotfix.md", "section: Fixes\n\n- Hotfix (#250).\n")
        self.assertEqual(self.repo.run("fold", "--into", "### 26.11.17.2")[0], 0)
        self.assertIn("- Shipped hotfix line (#224).\n- Hotfix (#250).\n\n### 26.11.17 ", self.repo.text())
        self.assertNotIn("Hotfix (#250)", self.unreleased())

    def test_an_unknown_or_ambiguous_heading_is_refused_and_nothing_changes(self):
        self.repo.add("f.md", "section: Fixes\n\n- X (#1).\n")
        for heading in ["### 27.01", "### 26.11", "### 26.11.1", "### 26.11.17."]:
            code, _, err = self.repo.run("fold", "--into", heading)
            self.assertEqual(code, 2, heading)
            self.assertIn("expected one", err)
        self.assertEqual(self.repo.text(), CHANGELOG)
        self.assertEqual(self.repo.fragments(), ["f.md"])

    def test_into_matches_the_whole_version_not_a_prefix(self):
        # "### 26.11.17" is the 26.11.17 block, not 26.11.17.2 above it
        self.repo.add("f.md", "section: Fixes\n\n- Into 17 (#251).\n")
        self.assertEqual(self.repo.run("fold", "--into", "### 26.11.17")[0], 0)
        self.assertIn("- Older fix (#192).\n- Into 17 (#251).\n", self.repo.text())

    def test_a_fragment_with_a_bom_is_read(self):
        # Windows PowerShell 5.1's Out-File writes UTF-8 with a BOM
        (self.repo.folder / "bom.md").write_bytes("section: Fixes\n\n- With BOM (#4).\n".encode("utf-8-sig"))
        self.assertEqual(self.repo.run("check")[0], 0)
        self.assertEqual(self.repo.run("fold")[0], 0)
        self.assertIn("- With BOM (#4).\n", self.unreleased())

    def test_a_failed_write_leaves_the_changelog_and_fragments_as_they_were(self):
        self.repo.add("f.md", "section: Fixes\n\n- X (#1).\n")
        with mock.patch.object(mod.os, "replace", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.repo.run("fold")
        self.assertEqual(self.repo.text(), CHANGELOG)
        self.assertEqual(self.repo.fragments(), ["f.md"])
        self.assertEqual([p.name for p in self.repo.root.iterdir() if p.name.endswith(".tmp")], [])

    def test_idempotent_a_second_fold_changes_nothing(self):
        self.repo.add("f.md", "section: Fixes\n\n- X (#1).\n")
        self.repo.run("fold")
        once = self.repo.text()
        self.assertEqual(self.repo.run("fold")[0], 0)
        self.assertEqual(self.repo.text(), once)

    def test_a_line_already_in_the_changelog_is_skipped(self):
        # A rerun after a fold whose deletes failed, or a hotfix's fragment whose line shipped
        self.repo.add("again.md", "section: Fixes\n\n- Shipped hotfix line (#224).\n")
        code, out, _ = self.repo.run("fold")
        self.assertEqual(code, 0)
        self.assertIn("already in CHANGELOG.md", out)
        self.assertEqual(self.repo.text(), CHANGELOG)
        self.assertEqual(self.repo.fragments(), [])

    def test_dry_run_prints_and_changes_nothing(self):
        self.repo.add("f.md", "section: Fixes\n\n- X (#1).\n")
        buf = io.BytesIO()
        out = io.TextIOWrapper(buf, encoding="utf-8")
        with mock.patch.object(mod.sys, "stdout", out):
            code = mod.main(["--root", str(self.repo.root), "fold", "--dry-run"])
            printed = buf.getvalue().decode("utf-8")
        self.assertEqual(code, 0)
        self.assertIn("+- X (#1).", printed)
        self.assertEqual(self.repo.text(), CHANGELOG)
        self.assertEqual(self.repo.fragments(), ["f.md"])

    def test_crlf_fragment_is_read(self):
        (self.repo.folder / "crlf.md").write_bytes(b"section: Fixes\r\n\r\n- Windows line (#3).\r\n")
        self.repo.run("fold")
        self.assertIn("- Windows line (#3).\n", self.unreleased())


class MalformedTest(unittest.TestCase):
    CASES = {
        "empty.md": ("", "empty"),
        "nosection.md": ("- A line (#1).\n", "before the first 'section: Name'"),
        "unknown.md": ("section: Bugfixes\n- A (#1).\n", "unknown section 'Bugfixes'"),
        "nolines.md": ("section: Fixes\n\n", "has no '- ' line"),
        "nolines-between.md": ("section: Fixes\nsection: Features\n- A (#1).\n", "has no '- ' line"),
        "prose.md": ("section: Fixes\nJust some text\n", "expected 'section: Name' or a '- ' line"),
        "heading.md": ("## Unreleased\nsection: Fixes\n- A.\n", "expected 'section: Name'"),
        "indent.md": ("section: Fixes\n  - A (#1).\n", "no indent"),
        "bare-dash.md": ("section: Fixes\n- \n", "expected 'section: Name' or a '- ' line"),
        "mig-section.md": ("section: Fixes\n- Migrations: V1.\n", "goes under 'section: Operations'"),
        "mig-format.md": ("section: Operations\n- Migrations: V1 and V2.\n", "'and'"),
    }

    def test_each_malformed_fragment_is_refused_with_its_line_and_nothing_changes(self):
        for name, (text, message) in self.CASES.items():
            with self.subTest(name):
                repo = Repo()
                try:
                    repo.add("good.md", "section: Fixes\n\n- Good (#1).\n")
                    repo.add(name, text)
                    for args in (["check"], ["fold"]):
                        code, _, err = repo.run(*args)
                        self.assertEqual(code, 1, args)
                        self.assertIn(name, err)
                        self.assertIn(message, err)
                    self.assertEqual(repo.text(), CHANGELOG)
                    self.assertEqual(repo.fragments(), sorted(["good.md", name]))
                finally:
                    repo.close()

    def test_a_badly_named_file_is_refused(self):
        repo = Repo()
        try:
            repo.add("fix.txt", "section: Fixes\n- A (#1).\n")
            code, _, err = repo.run("check")
            self.assertEqual(code, 1)
            self.assertIn("fix.txt: not a fragment", err)
        finally:
            repo.close()

    def test_all_errors_are_reported_at_once(self):
        repo = Repo()
        try:
            repo.add("a.md", "")
            repo.add("b.md", "- x\n")
            _, _, err = repo.run("check")
            self.assertIn("a.md", err)
            self.assertIn("b.md", err)
        finally:
            repo.close()

    def test_check_passes_on_good_fragments_and_an_empty_folder(self):
        repo = Repo()
        try:
            self.assertEqual(repo.run("check")[0], 0)
            repo.add("good.md", "section: Fixes\n\n- Good (#1).\n")
            code, out, _ = repo.run("check")
            self.assertEqual(code, 0)
            self.assertIn("1 fragment(s) OK", out)
        finally:
            repo.close()


class GuardTest(unittest.TestCase):
    def test_a_new_line_under_unreleased_fails(self):
        head = CHANGELOG.replace("- Old feature (#205).\n", "- Old feature (#205).\n- Sneaked in (#300).\n")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(mod.guard(CHANGELOG, head), 1)
        self.assertIn("Sneaked in", out.getvalue())

    def test_a_reworded_line_passes_and_a_reword_plus_a_new_line_fails(self):
        typo = CHANGELOG.replace("- Health line.", "- Health line, fixed typo.")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(mod.guard(CHANGELOG, typo), 0)
            both = typo.replace("- Old feature (#205).\n", "- Old feature (#205).\n- Sneaked in (#300).\n")
            self.assertEqual(mod.guard(CHANGELOG, both), 1)
        self.assertIn("Sneaked in", out.getvalue())

    def test_unchanged_or_lines_moved_out_or_released_blocks_edited_pass(self):
        released = CHANGELOG.replace("- Old feature (#205).\n", "").replace(
            "### 26.11.17.2", "### 26.11.18 (2026-11-27)\n\n**Features**\n\n- Old feature (#205).\n\n### 26.11.17.2")
        deploy_record = CHANGELOG.replace("- Older fix (#192).", "- Older fix, reworded (#192).")
        with contextlib.redirect_stdout(io.StringIO()):
            for head in [CHANGELOG, released, deploy_record]:
                self.assertEqual(mod.guard(CHANGELOG, head), 0)

    def test_the_real_changelog_has_one_unreleased_block(self):
        text = (Path(__file__).resolve().parent.parent / "CHANGELOG.md").read_text(encoding="utf-8")
        mod.unreleased_lines(text)

    def test_the_real_fragments_are_well_formed(self):
        mod.load_fragments(Path(__file__).resolve().parent.parent / "changes" / "unreleased")


if __name__ == "__main__":
    unittest.main()
