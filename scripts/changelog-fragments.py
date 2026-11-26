#!/usr/bin/env python3
"""CHANGELOG fragments: one file per pull request in changes/unreleased/, folded at release.

    python3 scripts/changelog-fragments.py check
    python3 scripts/changelog-fragments.py fold [--into HEADING] [--dry-run]   # --dry-run: a diff
    python3 scripts/changelog-fragments.py guard --base REV

A pull request adds its changelog line(s) as a fragment file instead of editing CHANGELOG.md,
so open pull requests no longer conflict there. The format is in changes/unreleased/README.md:

    section: Fixes

    - Round form: the track label keeps its spaces (#209).

`check` validates every fragment. `fold` validates them all, adds their lines to CHANGELOG.md's
"## Unreleased" block (or the block whose heading starts with HEADING, e.g. "### 26.11.18"),
then deletes the fragments. Lines go under their **Section** (created in the order Features,
Fixes, Security, Operations when the block lacks it), after the lines already there, ordered by
the fragment's first PR number, fragments without one last by file name. A line already in
CHANGELOG.md is skipped (a rerun, or a hotfix's line already in its released block); "Migrations:"
lines merge into the block's one Migrations line. Nothing is written if any fragment is
malformed, so a rerun after a fix, or after a successful fold, is safe.

`guard` fails when the Unreleased block has more lines than at REV (the PR's base) and lines it
did not have there: new lines go in fragments. Rewording a line (same count) and a release moving
lines out of Unreleased pass; so would replacing one line with another, which review catches.

Exit codes: 0 ok, 1 malformed fragment or a guard failure, 2 usage or CHANGELOG.md structure.
Standard library only, Python 3.8+.
"""
import argparse
import difflib
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SECTIONS = ["Features", "Fixes", "Security", "Operations"]
UNRELEASED = "## Unreleased"
SKIPPED_FILES = {"README.md"}
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.md$")
SECTION_RE = re.compile(r"^section:\s*(.*?)\s*$")
PR_RE = re.compile(r"\(#(\d+)\)")
MIGRATIONS_RE = re.compile(r"^- Migrations:\s*(.*?)\.?\s*$")
VERSION_RE = re.compile(r"^V(\d+)$")


class FragmentError(Exception):
    pass


class StructureError(Exception):
    pass


class Fragment:
    def __init__(self, path, entries):
        self.path = path
        self.entries = entries  # [(section, line)] in file order

    @property
    def sort_key(self):
        for _, line in self.entries:
            m = PR_RE.search(line)
            if m:
                return (0, int(m.group(1)), self.path.name)
        return (1, 0, self.path.name)


def parse_fragment(path):
    """A fragment: `section: Name` lines, each followed by one or more `- ` lines; blank lines
    anywhere. Anything else is refused with the file and line number."""
    try:
        text = path.read_text(encoding="utf-8-sig")  # Windows PowerShell 5.1 writes a BOM
    except UnicodeDecodeError:
        raise FragmentError(f"{path.name}: not UTF-8 text")
    entries, section, section_line, section_has_lines = [], None, 0, True
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw.rstrip()
        if not line:
            continue
        m = SECTION_RE.match(line)
        if m:
            if not section_has_lines:
                raise FragmentError(f"{path.name}:{section_line}: section {section!r} has no '- ' line")
            name = m.group(1)
            if name not in SECTIONS:
                raise FragmentError(f"{path.name}:{n}: unknown section {name!r}; use one of {', '.join(SECTIONS)}")
            section, section_line, section_has_lines = name, n, False
            continue
        if line != line.lstrip():
            raise FragmentError(f"{path.name}:{n}: a changelog line starts at the line's start, no indent")
        if not line.startswith("- ") or not line[2:].strip():
            raise FragmentError(
                f"{path.name}:{n}: expected 'section: Name' or a '- ' line exactly as in CHANGELOG.md, got {line[:60]!r}")
        if section is None:
            raise FragmentError(f"{path.name}:{n}: a '- ' line before the first 'section: Name' line")
        mig = MIGRATIONS_RE.match(line)
        if mig:
            if section != "Operations":
                raise FragmentError(f"{path.name}:{n}: a Migrations line goes under 'section: Operations'")
            try:
                versions(mig.group(1))
            except FragmentError as e:
                raise FragmentError(f"{path.name}:{n}: {e}")
        entries.append((section, line))
        section_has_lines = True
    if not section_has_lines:
        raise FragmentError(f"{path.name}:{section_line}: section {section!r} has no '- ' line")
    if not entries:
        raise FragmentError(f"{path.name}: empty; a fragment needs 'section: Name' and at least one '- ' line")
    return Fragment(path, entries)


def load_fragments(folder):
    """Every fragment of the folder, sorted; all errors together in one FragmentError."""
    if not folder.is_dir():
        return []
    fragments, errors = [], []
    for path in sorted(folder.iterdir()):
        if path.name in SKIPPED_FILES or path.name.startswith("."):
            continue
        if not path.is_file() or not NAME_RE.match(path.name):
            errors.append(f"{path.name}: not a fragment; name it after the branch, e.g. fix-track-names.md")
            continue
        try:
            fragments.append(parse_fragment(path))
        except FragmentError as e:
            errors.append(str(e))
    if errors:
        raise FragmentError("\n".join(errors))
    return sorted(fragments, key=lambda f: f.sort_key)


def heading_level(line):
    m = re.match(r"^(#+) ", line)
    return len(m.group(1)) if m else 0


def find_block(lines, heading):
    """(start, end): the heading's line and the index after its block's last line."""
    # The heading, then the line's end, a space or "(": "### 26.11.1" is not "### 26.11.17"
    matches = [i for i, l in enumerate(lines)
               if heading_level(l) and l.startswith(heading) and l[len(heading):len(heading) + 1] in ("", " ", "(")]
    if len(matches) != 1:
        raise StructureError(f"CHANGELOG.md has {len(matches)} headings {heading!r} (followed by a space, '(' "
                             f"or nothing); expected one")
    start = matches[0]
    level = heading_level(lines[start])
    end = start + 1
    while end < len(lines) and not (heading_level(lines[end]) and heading_level(lines[end]) <= level):
        end += 1
    return start, end


def section_marker(name):
    return f"**{name}**"


def section_range(lines, start, end, name):
    """(header index, index after its last non-blank line) of a **name** group in the block, or None."""
    marker = section_marker(name)
    for i in range(start + 1, end):
        if lines[i].strip() == marker:
            j = i + 1
            while j < end and lines[j].strip() not in {section_marker(s) for s in SECTIONS} and not heading_level(lines[j]):
                j += 1
            while j > i + 1 and not lines[j - 1].strip():
                j -= 1
            return i, j
    return None


def versions(text):
    out = []
    for token in re.split(r"[,\s]+", text):
        if not token:
            continue
        m = VERSION_RE.match(token)
        if not m:
            raise FragmentError(f"Migrations line has {token!r}; write it as '- Migrations: V116, V117.'")
        out.append(int(m.group(1)))
    return out


def fold_into(text, fragments, heading=UNRELEASED):
    """CHANGELOG.md's text with the fragments' lines added, and the lines skipped as already there."""
    lines = text.split("\n")
    existing = set(lines)
    skipped = []
    for fragment in fragments:
        for section, line in fragment.entries:
            start, end = find_block(lines, heading)
            mig = MIGRATIONS_RE.match(line)
            if mig:
                new = versions(mig.group(1))
                rng = section_range(lines, start, end, section)
                if rng:
                    for i in range(rng[0] + 1, rng[1]):
                        old = MIGRATIONS_RE.match(lines[i])
                        if old:
                            merged = sorted(set(versions(old.group(1))) | set(new))
                            lines[i] = "- Migrations: " + ", ".join(f"V{v}" for v in merged) + "."
                            break
                    else:
                        lines.insert(rng[1], "- Migrations: " + ", ".join(f"V{v}" for v in sorted(set(new))) + ".")
                    continue
                line = "- Migrations: " + ", ".join(f"V{v}" for v in sorted(set(new))) + "."
            if line in existing:
                skipped.append((fragment.path.name, line))
                continue
            rng = section_range(lines, start, end, section)
            if rng:
                lines.insert(rng[1], line)
            else:
                # Before the first later group of the block, or at the block's end
                order = SECTIONS.index(section)
                at = None
                for later in SECTIONS[order + 1:]:
                    r = section_range(lines, start, end, later)
                    if r and (at is None or r[0] < at):
                        at = r[0]
                if at is None:
                    at = end
                    while at > start + 1 and not lines[at - 1].strip():
                        at -= 1
                    lines[at:at] = ["", section_marker(section), "", line]
                    if at + 4 >= len(lines) or lines[at + 4].strip():
                        lines.insert(at + 4, "")
                else:
                    lines[at:at] = [section_marker(section), "", line, ""]
            existing.add(line)
    return "\n".join(lines), skipped


def unreleased_lines(text):
    lines = text.split("\n")
    start, end = find_block(lines, UNRELEASED)
    return [l for l in lines[start + 1:end] if l.startswith("- ")]


def cmd_check(folder):
    fragments = load_fragments(folder)
    print(f"{len(fragments)} fragment(s) OK in {folder}")
    return 0


def cmd_fold(folder, changelog, heading, dry_run):
    fragments = load_fragments(folder)
    text = changelog.read_text(encoding="utf-8")
    new_text, skipped = fold_into(text, fragments, heading)
    for name, line in skipped:
        print(f"{name}: already in CHANGELOG.md, skipped: {line[:80]}")
    if dry_run:
        diff = difflib.unified_diff(text.splitlines(True), new_text.splitlines(True), "CHANGELOG.md", "CHANGELOG.md")
        sys.stdout.flush()
        sys.stdout.buffer.write("".join(diff).encode("utf-8"))  # UTF-8 also on a Windows console
        sys.stdout.buffer.flush()
        return 0
    if new_text != text:
        write_atomically(changelog, new_text)
    for fragment in fragments:
        fragment.path.unlink()
    print(f"Folded {len(fragments)} fragment(s) into {heading!r} of {changelog.name}")
    return 0


def write_atomically(path, text):
    """A temporary file in the same folder, then os.replace: CHANGELOG.md is never half written."""
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        with tmp.open("w", encoding="utf-8", newline="\n") as f:  # LF, as in the repository
            f.write(text)
        os.replace(str(tmp), str(path))
    finally:
        if tmp.exists():
            tmp.unlink()


def cmd_guard(changelog, base):
    try:
        base_text = subprocess.run(["git", "show", f"{base}:CHANGELOG.md"], cwd=str(ROOT), check=True,
                                   stdout=subprocess.PIPE, universal_newlines=True, encoding="utf-8").stdout
    except subprocess.CalledProcessError:
        raise StructureError(f"cannot read CHANGELOG.md at {base}")
    return guard(base_text, changelog.read_text(encoding="utf-8"))


def guard(base_text, head_text):
    # New lines only when Unreleased has more lines than at the base: rewording a line (a typo
    # fix) keeps the count and passes; a release moving lines out lowers it.
    base_lines, head_lines = unreleased_lines(base_text), unreleased_lines(head_text)
    before = set(base_lines)
    added = [l for l in head_lines if l not in before] if len(head_lines) > len(base_lines) else []
    if not added:
        print("CHANGELOG.md: no line added under Unreleased")
        return 0
    print("CHANGELOG.md: this PR adds line(s) under Unreleased. Put them in a fragment file in "
          "changes/unreleased/ instead (changes/unreleased/README.md) and leave CHANGELOG.md as on master:")
    for line in added:
        print(f"  {line[:100]}")
    return 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check", help="validate the fragments")
    fold = sub.add_parser("fold", help="add the fragments to CHANGELOG.md and delete them")
    fold.add_argument("--into", default=UNRELEASED, metavar="HEADING",
                      help="the block whose heading starts with this (default: %(default)r)")
    fold.add_argument("--dry-run", action="store_true", help="print the change to CHANGELOG.md as a diff, change nothing")
    g = sub.add_parser("guard", help="fail if CHANGELOG.md's Unreleased gained a line since REV")
    g.add_argument("--base", required=True, metavar="REV")
    args = parser.parse_args(argv)
    folder = args.root / "changes" / "unreleased"
    changelog = args.root / "CHANGELOG.md"
    try:
        if args.command == "check":
            return cmd_check(folder)
        if args.command == "fold":
            return cmd_fold(folder, changelog, args.into, args.dry_run)
        return cmd_guard(changelog, args.base)
    except FragmentError as e:
        print(f"Malformed changelog fragment(s), nothing changed:\n{e}", file=sys.stderr)
        return 1
    except StructureError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
