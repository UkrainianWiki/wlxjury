#!/usr/bin/env python3
"""The story coverage matrix: which tests cite each story of docs/testing/stories.md.

Reads the catalogue (docs/testing/stories.md), the Playwright tags in browser-test/tests/*.js
('@J-21'), the bracketed IDs in the names of the Scala specs under test/ ("... [J-27]"), and the
headings of the guides the catalogue links. Writes the matrix to stdout, target/story-coverage.md
and $GITHUB_STEP_SUMMARY when set (testing practices §9.4).

Fails (exit 1) on: an ID in a test (a fixme or skipped one too) that the catalogue lacks or
retired; a malformed ID (J-7, X-01, j-01, J01), in a tag, a Scala name or a catalogue row; a tag
string not attached to a test's or a describe's `tag`; a Scala ID outside a [..] group; a
duplicate catalogue row; a guide section link without its #anchor, and a guide or notes link to a
missing file or heading; and a vacuous run: no tag in the browser tests or the Scala specs, or a
raw count of the quoted tags ('@J-21') and the [J-27 groups that differs from what was parsed.
Reports, without failing: the stories no test cites, those cited by one layer only, the tests
inside test.fixme or test.skip (not counted as covering) and the guide sections no story links.

    python3 scripts/story-coverage.py [--root DIR] [--out PATH] [--no-summary]
    python3 scripts/story-coverage.py --self-test

Python standard library only. Static: what ran and passed is the Allure report's job.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile

CATALOGUE = Path("docs/testing/stories.md")
BROWSER_TESTS = Path("browser-test/tests")
SCALA_TESTS = Path("test")
OUT = Path("target/story-coverage.md")
SELF_TEST_DATA = Path(__file__).resolve().parent / "testdata" / "story-coverage"

ROLES = {"J": "Juror", "O": "Organizer", "A": "Admin", "R": "Root", "S": "System and security"}
STRICT_ID = re.compile(r"^[JOARS]-\d{2}$")
# what looks like a story ID, valid or not: a capital, a hyphen, then digits (J-7, X-01, J-021)
ID_SHAPED = re.compile(r"^[A-Z]-\d+$")
# what looks like a story ID loosely: any case, the hyphen optional (J-01, j-01, J01, J-1, X-01)
LOOSE_ID = re.compile(r"^[A-Za-z]-?\d+$")
# a catalogue row: its first cell looks like an ID, loosely (a malformed one is an error)
ROW = re.compile(r"^\|\s*([A-Za-z]-?\d+\w*)\s*\|(.*)\|\s*$")
# a Markdown link: its target, and its anchor if any
LINK = re.compile(r"\]\(([^)#\s]*)(?:#([^)\s]*))?\)")
HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
PART = "*Part:*"
RETIRED = "(retired)"


# ---------------------------------------------------------------------------- the catalogue

class Story:
    def __init__(self, sid, text, guide, notes, line):
        self.id, self.text, self.guide, self.notes, self.line = sid, text, guide, notes, line
        self.retired = RETIRED in notes
        self.part = PART in notes
        self.links = LINK.findall(guide)          # (target, anchor), the guide section
        self.note_links = LINK.findall(notes)     # (target, anchor), any
        self.browser = []  # (spec file, title)
        self.scala = []    # (spec file, line, name)


def slug(heading):
    """GitHub's anchor of a heading: lower case, markup and punctuation dropped, spaces to -."""
    text = re.sub(r"`([^`]*)`", r"\1", heading)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = text.strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def anchors(path):
    """The heading anchors of a Markdown file (a repeated one gets -1, -2 ... as on GitHub)."""
    seen, out, fenced = {}, [], False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        m = None if fenced else HEADING.match(line)
        if not m:
            continue
        base = slug(m.group(2))
        n = seen.get(base, 0)
        seen[base] = n + 1
        out.append((len(m.group(1)), m.group(2), base if n == 0 else f"{base}-{n}"))
    return out


def read_catalogue(path, errors):
    stories = {}
    for no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        m = ROW.match(line)
        if not m:
            continue
        sid = m.group(1)
        cells = [c.strip() for c in m.group(2).split(" | ")]
        where = f"{path.as_posix()}:{no}"
        if not STRICT_ID.match(sid):
            errors.append(f"{where}: malformed story ID {sid!r} (want J-, O-, A-, R- or S- and two digits)")
            continue
        if len(cells) != 3:
            errors.append(f"{where}: {sid}: want 4 cells (ID, Story, Guide section, Notes), found {len(cells) + 1}")
            continue
        if sid in stories:
            errors.append(f"{where}: duplicate story ID {sid} (first on line {stories[sid].line})")
            continue
        stories[sid] = Story(sid, cells[0], cells[1], cells[2], no)
    return stories


# ---------------------------------------------------------------------------- browser tests

class Tok:
    __slots__ = ("kind", "value", "pos")

    def __init__(self, kind, value, pos):
        self.kind, self.value, self.pos = kind, value, pos


REGEX_BEFORE = set("(,=:[!&|?{};+-*%<>~^")


def js_tokens(src):
    """Identifiers, strings (template literals as their raw text) and punctuation of a JS file;
    comments and regex literals are skipped. Enough for the tests' call structure, not a parser."""
    toks, i, n = [], 0, len(src)
    braces = []  # 'code' or 'template' per open {, to resume a template after its ${...}

    def template(i, tok):
        """Scans a template literal's text from i into tok (its raw text, ${...} included, set
        when it ends); returns the next index."""
        while i < n:
            c = src[i]
            if c == "\\":
                i += 2
            elif c == "`":
                tok.value = src[tok.pos:i]
                return i + 1
            elif c == "$" and src.startswith("${", i):
                braces.append(("template", tok))
                return i + 2
            else:
                i += 1
        tok.value = src[tok.pos:]
        return i

    while i < n:
        c = src[i]
        if c.isspace():
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c in "'\"":
            j = i + 1
            while j < n and src[j] != c and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            toks.append(Tok("str", re.sub(r"\\(.)", r"\1", src[i + 1:j]), i))
            i = j + 1
        elif c == "`":
            tok = Tok("str", "", i + 1)
            toks.append(tok)
            i = template(i + 1, tok)
        elif c == "/" and (not toks or (toks[-1].kind == "punct" and toks[-1].value in REGEX_BEFORE)
                           or (toks[-1].kind == "ident" and toks[-1].value in ("return", "typeof"))):
            j, klass = i + 1, False
            while j < n and src[j] != "\n":
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "[":
                    klass = True
                elif src[j] == "]":
                    klass = False
                elif src[j] == "/" and not klass:
                    break
                j += 1
            i = j + 1
            while i < n and (src[i].isalnum() or src[i] == "_"):
                i += 1
        elif c.isalpha() or c in "_$":
            j = i
            while j < n and (src[j].isalnum() or src[j] in "_$"):
                j += 1
            toks.append(Tok("ident", src[i:j], i))
            i = j
        elif c.isdigit():
            j = i
            while j < n and (src[j].isalnum() or src[j] in "._"):
                j += 1
            toks.append(Tok("num", src[i:j], i))
            i = j
        elif c == "{":
            braces.append(("code", None))
            toks.append(Tok("punct", c, i))
            i += 1
        elif c == "}":
            kind, tok = braces.pop() if braces else ("code", None)
            if kind == "template":
                i = template(i + 1, tok)
            else:
                toks.append(Tok("punct", c, i))
                i += 1
        else:
            toks.append(Tok("punct", c, i))
            i += 1
    return toks


OPEN, CLOSE = "([{", ")]}"


def matching(toks, i):
    """The index of the bracket closing toks[i]."""
    depth = 0
    for j in range(i, len(toks)):
        t = toks[j]
        if t.kind == "punct" and t.value in OPEN:
            depth += 1
        elif t.kind == "punct" and t.value in CLOSE:
            depth -= 1
            if depth == 0:
                return j
    return len(toks) - 1


def split_args(toks, lo, hi):
    """The top-level arguments of the call whose ( is at lo and ) at hi, as token index ranges."""
    args, depth, start = [], 0, lo + 1
    for j in range(lo + 1, hi):
        t = toks[j]
        if t.kind == "punct" and t.value in OPEN:
            depth += 1
        elif t.kind == "punct" and t.value in CLOSE:
            depth -= 1
        elif t.kind == "punct" and t.value == "," and depth == 0:
            args.append((start, j))
            start = j + 1
    if start < hi:
        args.append((start, hi))
    return args


ATTACHED = set()  # the tokens read as a test's or a describe's tag


def tag_values(toks, lo, hi):
    """The strings of an options object's `tag` (a string or an array of them), else []."""
    if not (toks[lo].kind == "punct" and toks[lo].value == "{"):
        return []
    j = lo + 1
    while j < hi:
        t = toks[j]
        if t.kind == "punct" and t.value in "([{":
            j = matching(toks, j) + 1
            continue
        if t.kind == "ident" and t.value == "tag" and toks[j + 1].value == ":":
            v = toks[j + 2]
            if v.kind == "str":
                ATTACHED.add(id(v))
                return [v.value]
            if v.value == "[":
                end = matching(toks, j + 2)
                strs = [x for x in toks[j + 3:end] if x.kind == "str"]
                ATTACHED.update(id(x) for x in strs)
                return [x.value for x in strs]
        j += 1
    return []


TEST_NAMES = ("test", "setup")
SKIPPING = ("fixme", "skip")
TEST_MODS = SKIPPING + ("only", "fail", "slow")
DESCRIBE_MODS = SKIPPING + ("only", "serial", "parallel")


def browser_tests(path):
    """The tests of a spec file: (title, tags, skipped), describes' tags included, and every
    tag-shaped string in the file (for the ID checks)."""
    src = path.read_text(encoding="utf-8")
    toks = js_tokens(src)
    ATTACHED.clear()  # ids of this file's tokens, alive while toks is
    calls = []  # (kind, title, tags, lo, hi, skipped)
    marks = []  # positions of an unconditional test.fixme() / test.skip() inside a body
    i = 0
    while i < len(toks):
        t = toks[i]
        if t.kind == "ident" and t.value in TEST_NAMES and (i == 0 or toks[i - 1].value != "."):
            parts, j = [t.value], i + 1
            while j + 1 < len(toks) and toks[j].value == "." and toks[j + 1].kind == "ident":
                parts.append(toks[j + 1].value)
                j += 2
            if j < len(toks) and toks[j].value == "(":
                hi = matching(toks, j)
                args = split_args(toks, j, hi)
                mods = parts[1:]
                first = toks[args[0][0]] if args else None
                titled = first is not None and first.kind == "str"
                # a declaration's last argument is its body: a function
                body = bool(args) and len(args) >= 2 and any(
                    (x.kind == "ident" and x.value in ("async", "function"))
                    or (x.value == "=" and k + 1 < hi and toks[k + 1].value == ">")
                    for k, x in ((k, toks[k]) for k in range(args[-1][0], min(args[-1][1], args[-1][0] + 40))))
                kind = None
                if mods[:1] == ["describe"] and all(m in DESCRIBE_MODS for m in mods[1:]):
                    kind = "describe"
                elif all(m in TEST_MODS for m in mods) and body:
                    kind = "test"
                if kind:
                    # a title in a variable (a loop's) is shown as its source: ${name}
                    title = first.value if titled else ("${%s}" % src[first.pos:toks[args[0][1] - 1].pos + len(toks[args[0][1] - 1].value)]
                                                        if kind == "test" else "")
                    tags = []
                    for lo, ahi in args:
                        tags += tag_values(toks, lo, ahi)
                    calls.append((kind, title, tags, j, hi, any(m in SKIPPING for m in mods)))
                elif len(mods) == 1 and mods[0] in SKIPPING:
                    # test.fixme() or test.fixme(true, 'why'): the enclosing test or describe
                    if not args or (toks[args[0][0]].value == "true" and args[0][1] - args[0][0] == 1):
                        marks.append(j)
            i = j
            continue
        i += 1

    tests = []
    for kind, title, tags, lo, hi, skipped in calls:
        if kind != "test":
            continue
        outer = [c for c in calls if c[0] == "describe" and c[3] < lo and hi < c[4]]
        all_tags = [x for c in outer for x in c[2]] + tags
        skipped = skipped or any(c[5] for c in outer) or any(lo < m < hi for m in marks)
        # a fixme mark directly in a describe's body skips its tests
        skipped = skipped or any(c[3] < m < c[4] and not any(t[0] == "test" and t[3] < m < t[4] for t in calls)
                                 for c in outer for m in marks)
        name = " › ".join([c[1] for c in outer if c[1]] + [title])
        tests.append((name, all_tags, skipped))
    shaped = [(src.count("\n", 0, t.pos) + 1, t.value, id(t) in ATTACHED) for t in toks
              if t.kind == "str" and t.value.startswith("@") and LOOSE_ID.match(t.value[1:])]
    # the cross-check: the quoted story tags in the raw text, comments included, which the
    # tokenizer must all have seen (else it misread the file, and the matrix would miss tags)
    raw = len(RAW_JS_TAG.findall(src))
    return tests, shaped, raw


# ---------------------------------------------------------------------------- Scala specs

SCALA_STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
# a bracket group of IDs, loosely (any case, the hyphen optional: [J-27, S-01], [j-27], [J27])
BRACKET = re.compile(r"\[\s*([A-Za-z]-?\d+(?:\s*,\s*[A-Za-z]-?\d+)*)\s*\]")
# an ID outside any bracket group (a bracket left open or closed only, or parentheses)
STRAY_ID = re.compile(r"(?<![\w-])[JOARS]-\d{1,3}(?![\w-])")
# the cross-check: a story ID right after a [ anywhere on a code line
RAW_SCALA_TAG = re.compile(r"\[\s*[A-Za-z]-?\d")
RAW_JS_TAG = re.compile(r"(['\"`])@[A-Za-z]-?\d+\1")


def scala_tags(path):
    """(line, example or block name, [IDs, as written]) for each string literal with a bracket
    group of IDs; (line, name, text) for an ID outside one; and the raw count of [X-NN starts on
    the file's code lines (comments left out), for the cross-check."""
    out, stray, raw = [], [], 0
    for no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.lstrip()
        if stripped.startswith(("//", "*", "/*")):
            continue
        raw += len(RAW_SCALA_TAG.findall(line))
        for lit in SCALA_STRING.finditer(line):
            text = lit.group(1)
            ids = [x.strip() for b in BRACKET.finditer(text) for x in b.group(1).split(",")]
            if ids:
                out.append((no, text, ids))
            for m in STRAY_ID.finditer(BRACKET.sub(" ", text)):
                stray.append((no, text, m.group(0)))
    return out, stray, raw


# ---------------------------------------------------------------------------- the matrix

def collect(root):
    errors, notes = [], []
    cat_path = root / CATALOGUE
    if not cat_path.is_file():
        return None, [f"{CATALOGUE.as_posix()}: missing"], notes, {}
    stories = read_catalogue(cat_path, errors)
    rel = lambda p: p.relative_to(root).as_posix()

    # the links of the Guide section and Notes cells: the file exists, the anchor is a heading;
    # a guide section link needs its anchor
    guides = {}
    for s in stories.values():
        where = f"{CATALOGUE.as_posix()}:{s.line}: {s.id}"
        for cell, links in (("guide section", s.links), ("notes", s.note_links)):
            for target, anchor in links:
                if re.match(r"^[a-z][a-z0-9+.-]*:", target):
                    if cell == "guide section":
                        errors.append(f"{where}: the guide section links {target}, not a guide in this repository")
                    continue  # an external link in the notes: not checked here
                if cell == "guide section" and not anchor:
                    errors.append(f"{where}: the guide section link {target or '(empty)'} has no #anchor")
                    continue
                path = (cat_path.parent / target).resolve() if target else cat_path.resolve()
                if path not in guides:
                    guides[path] = anchors(path) if path.is_file() else None
                if guides[path] is None:
                    errors.append(f"{where}: {cell}: {target} not found")
                elif anchor and anchor not in {a for _, _, a in guides[path]}:
                    errors.append(f"{where}: {cell}: {target}#{anchor} has no such heading")

    def cite(sid, where, add):
        if not STRICT_ID.match(sid):
            errors.append(f"{where}: malformed story ID {sid!r}")
        elif sid not in stories:
            errors.append(f"{where}: {sid} is not in {CATALOGUE.as_posix()}")
        elif stories[sid].retired:
            errors.append(f"{where}: {sid} is retired")
        else:
            add(stories[sid])

    skipped, browser_tags, scala_count, browser_raw, scala_raw = [], 0, 0, 0, 0
    for spec in sorted((root / BROWSER_TESTS).glob("*.js")):
        tests, shaped, raw = browser_tests(spec)
        browser_raw += raw
        for name, tags, skip in tests:
            for tag in tags:
                if not tag.startswith("@") or not LOOSE_ID.match(tag[1:]):
                    continue  # another kind of tag (@slow)
                browser_tags += 1
                where = f"{rel(spec)}: {name!r}"
                if skip:
                    # checked as any tag, listed, not counted as covering
                    cite(tag[1:], where, lambda s, n=name: skipped.append((s.id, f"{spec.name}: {n}")))
                    continue
                cite(tag[1:], where, lambda s, n=name: s.browser.append((spec.name, n)))
        for line, value, attached in shaped:
            if not attached:
                errors.append(f"{rel(spec)}:{line}: {value} is not attached to a test or a describe "
                              "(a tag must be a string in its `tag` option)")
        if raw != len(shaped):
            errors.append(f"{rel(spec)}: {raw} quoted story tags in the text, {len(shaped)} read by the "
                          "parser (a tag in a comment, or a file the parser misreads)")

    for spec in sorted((root / SCALA_TESTS).rglob("*.scala")):
        found, stray, raw = scala_tags(spec)
        scala_raw += raw
        for line, name, ids in found:
            for sid in ids:
                scala_count += 1
                cite(sid, f"{rel(spec)}:{line}", lambda s, n=name, ln=line: s.scala.append((spec.stem, ln, n)))
        for line, name, text in stray:
            errors.append(f"{rel(spec)}:{line}: {text} outside a [..] group in {name!r} (write [{text}])")
        if raw != sum(1 for _, n, _ in found for _ in BRACKET.finditer(n)):
            errors.append(f"{rel(spec)}: {raw} [ID groups on its code lines, "
                          f"{sum(1 for _, n, _ in found for _ in BRACKET.finditer(n))} in names "
                          "(an ID group outside a name's string, or one the parser misreads)")

    if browser_tags == 0:
        errors.append(f"no story tag in {BROWSER_TESTS.as_posix()}/*.js: the matrix would be vacuous")
    if scala_count == 0:
        errors.append(f"no story ID in the Scala specs under {SCALA_TESTS.as_posix()}/: the matrix would be vacuous")
    if browser_raw == 0 or scala_raw == 0:
        errors.append("the raw cross-check found no story IDs in the "
                      + ("browser tests" if browser_raw == 0 else "Scala specs") + ": the matrix would be vacuous")

    linked = {(p, a) for s in stories.values() for t, a in s.links
              for p in [(cat_path.parent / t).resolve() if t else cat_path.resolve()]}
    unlinked = [(p, title) for p, heads in guides.items() if heads
                for level, title, a in heads if level == 3 and (p, a) not in linked]
    extra = {"counts": (browser_tags, browser_raw, scala_count, scala_raw), "skipped": skipped, "unlinked": [(rel(p) if root.resolve() in p.parents else p.name, t) for p, t in unlinked]}
    return stories, errors, notes, extra


def state(s):
    if s.retired:
        return "retired"
    if not s.browser and not s.scala:
        return "uncovered"
    return "partly covered" if s.part else "covered"


def md_escape(text):
    return text.replace("|", "\\|")


def render(stories, errors, notes, extra):
    out = ["## Story coverage", ""]
    if errors:
        out += [f"**{len(errors)} error(s):**", ""] + [f"- {md_escape(e)}" for e in errors] + [""]
    if stories is None:
        return "\n".join(out)
    live = [s for s in stories.values() if not s.retired]
    count = lambda pred, xs=live: sum(1 for s in xs if pred(s))
    retired = len(stories) - len(live)
    out += [f"{len(live)} stories: **{count(lambda s: state(s) == 'covered')} covered**, "
            f"**{count(lambda s: state(s) == 'partly covered')} partly covered** (*Part:* in their notes), "
            f"**{count(lambda s: state(s) == 'uncovered')} uncovered**"
            + (f"; {retired} retired" if retired else "") + ". Static: a tag says a test cites the "
            "story, not that it passed (the Allure report).", ""]
    out += ["| Role | Stories | Covered | Partly | Uncovered | Browser and Scala | Browser only | Scala only |",
            "|---|---|---|---|---|---|---|---|"]
    for r, role in ROLES.items():
        xs = [s for s in live if s.id[0] == r]
        if not xs:
            continue
        out.append("| {} | {} | {} | {} | {} | {} | {} | {} |".format(
            role, len(xs), count(lambda s: state(s) == "covered", xs), count(lambda s: state(s) == "partly covered", xs),
            count(lambda s: state(s) == "uncovered", xs), count(lambda s: s.browser and s.scala, xs),
            count(lambda s: s.browser and not s.scala, xs), count(lambda s: s.scala and not s.browser, xs)))
    out.append("")

    uncovered = [s for s in live if state(s) == "uncovered"]
    if uncovered:
        out += ["### Uncovered", ""]
        for r, role in ROLES.items():
            ids = [f"{s.id} {s.text}" for s in uncovered if s.id[0] == r]
            if ids:
                out.append(f"- **{role}:** " + "; ".join(md_escape(x) for x in ids))
        out.append("")

    out += ["### The matrix", "", "| ID | Story | State | Browser tests | Scala specs |", "|---|---|---|---|---|"]
    for s in stories.values():
        browser = "<br>".join(md_escape(f"{f}: {t}") for f, t in s.browser) or "–"
        specs = {}
        for spec, _, _ in s.scala:
            specs[spec] = specs.get(spec, 0) + 1
        scala = ", ".join(f"`{k}` ({v})" if v > 1 else f"`{k}`" for k, v in specs.items()) or "–"
        out.append(f"| {s.id} | {md_escape(s.text)} | {state(s)} | {browser} | {scala} |")
    out.append("")

    if extra["skipped"]:
        out += ["### Tests in test.fixme or test.skip (not counted as covering)", ""]
        out += [f"- {sid}: {md_escape(t)}" for sid, t in extra["skipped"]] + [""]
    if extra["unlinked"]:
        out += ["### Guide sections no story links (drift, plan §7.1)", ""]
        out += [f"- {p}: {t}" for p, t in extra["unlinked"]] + [""]
    if notes:
        out += ["### Notes", ""] + [f"- {md_escape(n)}" for n in notes] + [""]
    return "\n".join(out)


def run(root, out_path, summary):
    stories, errors, notes, extra = collect(root)
    text = render(stories, errors, notes, extra)
    if out_path is not None:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(text + "\n", encoding="utf-8")
    if summary and os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(text + "\n")
    return stories, errors, text


# ---------------------------------------------------------------------------- self-test

def self_test():
    """The fixture under scripts/testdata/story-coverage/: J-01 known and cited by both layers
    (and once from a fixme test), J-02 retired and cited, J-99 unknown; then variants that must
    fail: no tags, a malformed ID, a duplicate row, a dead anchor; and the clean case passing."""
    failures = []

    def check(cond, what):
        if not cond:
            failures.append(what)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / "repo"
        shutil.copytree(SELF_TEST_DATA, root)
        stories, errors, text = run(root, None, False)
        joined = "\n".join(errors)
        check(len(errors) == 3, f"three errors on the fixture, got {errors}")
        check("J-99 is not in" in joined, "the unknown J-99 is an error")
        check(sum("J-02 is retired" in e for e in errors) == 2, "the retired J-02, cited by a browser test and a spec")
        check(stories["J-01"].browser == [("a.spec.js", "a juror › signs in")], f"J-01's browser test, got {stories['J-01'].browser}")
        check([n for _, _, n in stories["J-01"].scala] == ["a juror signs in [J-01]"], f"J-01's spec, got {stories['J-01'].scala}")
        check("J-01: a.spec.js: a juror › is drawn from the first frame" in text, "the fixme test is listed as skipped")
        check(state(stories["J-03"]) == "uncovered" and state(stories["J-01"]) == "covered", "J-01 covered, J-03 uncovered")
        check(state(stories["J-04"]) == "partly covered", "J-04 partly covered (its *Part:* note)")
        check("@slow" not in joined, "a tag that is not a story ID is ignored")
        check("Unlinked section" in text, "the guide section no story links is reported")

        spec = root / "browser-test/tests/a.spec.js"
        scala = root / "test/ASpec.scala"
        cat = root / CATALOGUE
        orig = {p: p.read_text(encoding="utf-8") for p in (spec, scala, cat)}

        def variant(edit, expect, what):
            for p, t in orig.items():
                p.write_text(t, encoding="utf-8")
            edit()
            _, errs, _ = run(root, None, False)
            check(any(expect in e for e in errs), f"{what}: want an error with {expect!r}, got {errs}")

        def clean():
            spec.write_text(orig[spec].replace("'@J-02', ", "").replace("'@J-99'", "'@J-01'"), encoding="utf-8")
            scala.write_text(orig[scala].replace(" [J-02]", ""), encoding="utf-8")

        for p, t in orig.items():
            p.write_text(t, encoding="utf-8")
        clean()
        _, errs, _ = run(root, None, False)
        check(errs == [], f"the clean fixture passes, got {errs}")

        variant(lambda: (clean(), spec.write_text(orig[spec].replace("@", "@x"), encoding="utf-8")),
                "no story tag in", "a browser suite without tags")
        variant(lambda: (clean(), scala.write_text(re.sub(r" \[[^\]]*\]", "", orig[scala]), encoding="utf-8")),
                "no story ID in the Scala specs", "Scala specs without tags")
        variant(lambda: (clean(), scala.write_text(orig[scala].replace("[J-01]", "[J-1]"), encoding="utf-8")),
                "malformed story ID 'J-1'", "a malformed ID in a spec")
        variant(lambda: (clean(), spec.write_text(orig[spec].replace("'@J-99'", "'@X-01'"), encoding="utf-8")),
                "malformed story ID 'X-01'", "an unknown role in a tag")
        variant(lambda: (clean(), cat.write_text(orig[cat] + "| J-01 | Again | – | |\n", encoding="utf-8")),
                "duplicate story ID J-01", "a duplicate row")
        variant(lambda: (clean(), cat.write_text(orig[cat].replace("#signing-in", "#signing-out"), encoding="utf-8")),
                "has no such heading", "a dead anchor")

        # the review's cases (2026-11-04), each on the clean fixture
        clean_spec = orig[spec].replace("'@J-02', ", "").replace("'@J-99'", "'@J-01'")
        clean_scala = orig[scala].replace(" [J-02]", "")
        def put(p, text):
            return lambda: (clean(), p.write_text(text, encoding="utf-8"))
        cases = [
            (spec, clean_spec.replace("test('is drawn from the first frame', async",
                                      "test('is drawn from the first frame', { tag: '@J-98' }, async"),
             "J-98 is not in", "an unknown ID in a fixme test"),
            (spec, clean_spec + "\nconst TAGS = ['@J-01'];\n", "is not attached", "a tag string outside a tag option"),
            (spec, clean_spec + "\n// as '@J-01' does\n", "quoted story tags in the text", "a tag the parser can't see"),
            (spec, clean_spec.replace("'@J-01'", "'@j-01'"), "malformed story ID 'j-01'", "a lowercase tag"),
            (cat, orig[cat] + "| J05 | Again | – | |\n", "malformed story ID 'J05'", "a row's ID without its hyphen"),
            (cat, orig[cat] + "| j-05 | Again | – | |\n", "malformed story ID 'j-05'", "a row's ID in lower case"),
            (cat, orig[cat].replace("(../user/juror-guide.md#signing-in)", "(../user/juror-guide.md)"),
             "has no #anchor", "a guide section link without its anchor"),
            (cat, orig[cat].replace("| *Part:* by rate only |", "| *Part:* by rate only, [see](../user/missing.md) |"),
             "notes: ../user/missing.md not found", "a notes link to a missing file"),
            (cat, orig[cat].replace("| *Part:* by rate only |", "| *Part:* by rate only, [see](../user/juror-guide.md#nope) |"),
             "notes: ../user/juror-guide.md#nope has no such heading", "a notes link to a missing heading"),
            (scala, clean_scala.replace("[J-01]", "[j-01]"), "malformed story ID 'j-01'", "a lowercase Scala ID"),
            (scala, clean_scala.replace("[J-01]", "J-01]"), "outside a [..] group", "a Scala ID without its ["),
            (scala, clean_scala.replace("[J-01]", "[J-01"), "outside a [..] group", "a Scala ID without its ]"),
            (scala, clean_scala.replace("}\n", "  def f = g[J-01]()\n}\n", 1)[::1],
             "ID groups on its code lines", "a Scala ID outside a name"),
        ]
        for p, text, expect, what in cases:
            check(text != orig[p] and text != (clean_spec if p == spec else clean_scala if p == scala else orig[cat]),
                  f"{what}: the case changes the fixture")
            variant(put(p, text), expect, what)

    for f in failures:
        print(f"FAIL: {f}")
    print(f"story-coverage self-test: {'ok' if not failures else f'{len(failures)} failure(s)'}")
    return 1 if failures else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--out", type=Path, default=None, help=f"default: <root>/{OUT.as_posix()}")
    parser.add_argument("--no-summary", action="store_true", help="don't append to $GITHUB_STEP_SUMMARY")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    _, errors, text = run(args.root, args.out or args.root / OUT, not args.no_summary)
    sys.stdout.buffer.write((text + "\n").encode("utf-8"))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
