#!/usr/bin/env python3
"""Checks the links of the user guide (docs/user). Python 3.8+, standard library only, no build.

  docs-links.py                     check docs/user, and the links into it from the files below
  docs-links.py --legacy-rev REV    also: every anchor of the old single-page guides at REV (their
                                    explicit ids and heading slugs) is still on the same page
  docs-links.py --self-test         the checks on a small fixture, each failure seen once

Fails on:
- a relative link or <img src> whose file doesn't exist;
- a #anchor (in the page, or to another Markdown page of the repository) that is neither an
  explicit <a id="…"> of the target page nor one of its heading slugs (GitHub's, -1 for a repeat);
- an explicit id twice in one page, or in two topic pages;
- a ## or ### heading of a topic page without an explicit <a id="…"> on the line above.

The topic pages are docs/user/*.md except the contents pages (admin-guide.md, organizer-guide.md,
juror-guide.md), README.md and whats-new-*.md. Code blocks are skipped; headings inside HTML
comments make no anchor, but links inside them are checked. The links into docs/user are also
checked in README.md, docs/README.md and docs/testing/stories.md.
"""
import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

USER = Path("docs/user")
CONTENTS = {"admin-guide.md", "organizer-guide.md", "juror-guide.md", "README.md"}
INBOUND = [Path("README.md"), Path("docs/README.md"), Path("docs/testing/stories.md")]
LEGACY = ["admin-guide.md", "organizer-guide.md", "juror-guide.md"]

HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
ID = re.compile(r'<a\s+id="([^"]+)"\s*>\s*</a>')
ID_LINE = re.compile(r'^\s*(?:<a\s+id="[^"]+"\s*>\s*</a>\s*)+$')
MD_LINK = re.compile(r"!?\[(?:[^\[\]]|\[[^\]]*\])*\]\(\s*<?([^)\s>]*)>?(?:\s+\"[^\"]*\")?\s*\)")
IMG = re.compile(r"<img\s[^>]*?src=\"([^\"]+)\"")
EXTERNAL = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")


def slug(heading):
    """GitHub's anchor of a heading: lower case, markup and punctuation dropped, spaces to -."""
    text = re.sub(r"`([^`]*)`", r"\1", heading)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = text.strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


class Page:
    """A Markdown page: its lines with their state (in a code block, in a comment)."""

    def __init__(self, text):
        self.lines = []  # (number, text, in_code, in_comment)
        code = comment = False
        for n, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith("```"):
                self.lines.append((n, line, True, comment))
                code = not code
                continue
            start_comment = comment
            if not code:
                rest = line
                while True:
                    if comment:
                        i = rest.find("-->")
                        if i < 0:
                            break
                        comment, rest = False, rest[i + 3:]
                    else:
                        i = rest.find("<!--")
                        if i < 0:
                            break
                        comment, rest = True, rest[i + 4:]
            # a line that starts in a comment, or opens one, is the comment's
            self.lines.append((n, line, code, start_comment or (not code and "<!--" in line)))
        self._anchors = None

    def headings(self):
        """(line number, level, text, ids on the lines just above) of the rendered headings."""
        out = []
        for k, (n, line, code, comment) in enumerate(self.lines):
            m = None if code or comment else HEADING.match(line)
            if not m:
                continue
            ids, j = [], k - 1
            while j >= 0 and ID_LINE.match(self.lines[j][1]) and not self.lines[j][3]:
                ids = ID.findall(self.lines[j][1]) + ids
                j -= 1
            out.append((n, len(m.group(1)), m.group(2), ids))
        return out

    def ids(self):
        """(line number, id) of the explicit ids outside code blocks and comments."""
        return [(n, i) for n, line, code, comment in self.lines if not code and not comment
                for i in ID.findall(line)]

    def anchors(self):
        if self._anchors is None:
            seen, out = {}, {i for _, i in self.ids()}
            for _, _, text, _ in self.headings():
                base = slug(text)
                k = seen.get(base, 0)
                seen[base] = k + 1
                out.add(base if k == 0 else f"{base}-{k}")
            self._anchors = out
        return self._anchors

    def links(self):
        """(line number, target) of the Markdown links and <img src> outside code blocks."""
        out = []
        for n, line, code, _ in self.lines:
            if code:
                continue
            line = re.sub(r"`[^`]*`", "", line)
            out += [(n, t) for t in MD_LINK.findall(line)] + [(n, t) for t in IMG.findall(line)]
        return out


def rel(path, root):
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def check(root, legacy_rev=None, legacy_texts=None):
    """The errors of the guide under root (a repository checkout)."""
    errors, pages, counts = [], {}, {"pages": 0, "links": 0}

    def page(path):
        key = path.resolve()
        if key not in pages:
            pages[key] = Page(path.read_text(encoding="utf-8")) if path.is_file() else None
        return pages[key]

    user = root / USER
    files = sorted(user.rglob("*.md"))
    if not files:
        return [f"no Markdown page under {USER.as_posix()}"], counts
    topics = [f for f in files if f.parent == user and f.name not in CONTENTS
              and not f.name.startswith("whats-new-")]

    def check_links(path, only_into_user):
        p = page(path)
        for n, target in p.links():
            if not target or EXTERNAL.match(target) or target.startswith("//"):
                continue
            file_part, _, anchor = target.partition("#")
            dest = (path.parent / file_part).resolve() if file_part else path.resolve()
            if only_into_user and user.resolve() not in dest.parents:
                continue
            counts["links"] += 1
            where = f"{rel(path, root)}:{n}"
            if root.resolve() not in dest.parents and dest != root.resolve():
                errors.append(f"{where}: {target} leaves the repository")
                continue
            if not dest.exists():
                errors.append(f"{where}: {target}: no such file")
                continue
            if anchor and dest.suffix == ".md":
                if anchor not in page(dest).anchors():
                    errors.append(f"{where}: {target}: no such anchor in {rel(dest, root)}")

    for f in files:
        counts["pages"] += 1
        check_links(f, False)
        seen = {}
        for n, i in page(f).ids():
            if i in seen:
                errors.append(f"{rel(f, root)}:{n}: the id {i} is also on line {seen[i]}")
            seen.setdefault(i, n)
    for f in INBOUND:
        if (root / f).is_file():
            check_links(root / f, True)

    owner = {}
    for f in topics:
        for n, i in page(f).ids():
            if i in owner and owner[i][0] != f:
                errors.append(f"{rel(f, root)}:{n}: the id {i} is also in {rel(owner[i][0], root)}:{owner[i][1]}")
            owner.setdefault(i, (f, n))
        for n, level, text, ids in page(f).headings():
            if level >= 2 and not ids:
                errors.append(f"{rel(f, root)}:{n}: the heading \"{text}\" has no <a id> on the line above")

    if legacy_rev or legacy_texts:
        for name in LEGACY:
            if legacy_texts is not None:
                text = legacy_texts.get(name)
            else:
                r = subprocess.run(["git", "-C", str(root), "show", f"{legacy_rev}:{(USER / name).as_posix()}"],
                                   capture_output=True)
                if r.returncode != 0:
                    errors.append(f"{legacy_rev}:{(USER / name).as_posix()}: can't be read")
                    continue
                text = r.stdout.decode("utf-8")
            if text is None:
                continue
            now = page(user / name)
            if now is None:
                errors.append(f"{(USER / name).as_posix()}: missing, but {name} had anchors")
                continue
            for a in sorted(Page(text).anchors() - now.anchors()):
                errors.append(f"{(USER / name).as_posix()}: the old anchor #{a} is gone")
    return errors, counts


def self_test():
    failures = []

    def expect(cond, what):
        if not cond:
            failures.append(what)

    good = {
        "docs/user/README.md": "# Guide\n\n[Topic](topic.md) and [a part](topic.md#t-part).\n",
        "docs/user/admin-guide.md": "# Admin\n\n- <a id=\"a-old\"></a><a id=\"old-heading\"></a>[Old](topic.md#t-part)\n",
        "docs/user/organizer-guide.md": "# Organizer\n",
        "docs/user/juror-guide.md": "# Juror\n",
        "docs/user/topic.md": ("<a id=\"t-top\"></a>\n# Topic\n\n<a id=\"t-part\"></a>\n## A part\n\n"
                               "<img src=\"images/x.png\" alt=\"x\">\n\nSee [the part](#t-part), [the heading](#a-part),"
                               " [Old](admin-guide.md#old-heading) and [deploy](../deployment.md).\n\n"
                               "<!-- not offered yet\n\n## Hidden\n\n[ok](#t-part)\n-->\n\n```\n## not a heading [x](nope.md)\n```\n"),
        "docs/user/images/x.png": "",
        "docs/deployment.md": "# Deployment\n",
        "docs/README.md": "[guide](user/topic.md#t-part), [elsewhere](other.md#nope)\n",
    }
    legacy = {"admin-guide.md": "# Admin\n\n<a id=\"a-old\"></a>\n## Old heading\n"}

    def run(files, legacy_texts=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, text in files.items():
                p = root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text, encoding="utf-8")
            return check(root, legacy_texts=legacy_texts)[0]

    errs = run(good, legacy)
    expect(errs == [], f"the clean fixture passes, got {errs}")

    def variant(name, old, new, expected, what, legacy_texts=None):
        files = dict(good)
        if old not in files[name]:
            failures.append(f"fixture edit not found: {what}")
            return
        files[name] = files[name].replace(old, new, 1)
        errs = run(files, legacy_texts)
        expect(any(expected in e for e in errs), f"{what}: expected {expected!r}, got {errs}")

    variant("docs/user/topic.md", "(../deployment.md)", "(../nope.md)", "no such file", "a missing file")
    variant("docs/user/topic.md", "images/x.png", "images/y.png", "no such file", "a missing image")
    variant("docs/user/topic.md", "(#t-part)", "(#t-nope)", "no such anchor", "a missing anchor in the page")
    variant("docs/user/README.md", "topic.md#t-part", "topic.md#gone", "no such anchor", "a missing anchor in another page")
    variant("docs/user/topic.md", "[ok](#t-part)", "[ok](#hidden)", "no such anchor",
            "a heading in a comment makes no anchor")
    variant("docs/user/topic.md", "<a id=\"t-part\"></a>\n## A part", "## A part", "has no <a id>",
            "a topic heading without an id")
    variant("docs/user/topic.md", "## A part\n", "## A part\n\n<a id=\"t-part\"></a>\n", "is also on line",
            "an id twice in a page")
    files = dict(good, **{"docs/user/other.md": "<a id=\"t-part\"></a>\n# Other\n"})
    errs = run(files)
    expect(any("is also in" in e for e in errs), f"an id in two topic pages, got {errs}")
    variant("docs/README.md", "user/topic.md#t-part", "user/topic.md#nope", "no such anchor",
            "an inbound link to a missing anchor")
    variant("docs/user/admin-guide.md", "<a id=\"a-old\"></a>", "", "old anchor #a-old is gone",
            "a legacy anchor dropped", legacy)

    if failures:
        print("self-test FAILED:\n  " + "\n  ".join(failures))
        return 1
    print("self-test passed")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--legacy-rev", help="also check that the old guides' anchors at this revision remain")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--root", default=".", help=argparse.SUPPRESS)
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    errors, counts = check(Path(args.root), legacy_rev=args.legacy_rev)
    for e in errors:
        print(e)
    if errors:
        print(f"{len(errors)} problem(s) in the user guide's links")
        return 1
    print(f"docs/user: {counts['pages']} pages, {counts['links']} links checked"
          + (f", the anchors of {args.legacy_rev} kept" if args.legacy_rev else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
