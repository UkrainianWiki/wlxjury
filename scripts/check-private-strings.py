#!/usr/bin/env python3
"""Refuse private strings in commits: text listed in a private-strings file kept outside the
repository (CONTRIBUTING.md, "Private strings"). Python 3.8+, stdlib only.

  check-private-strings.py --staged          the staged files (pre-commit hook)
  check-private-strings.py --message FILE    a commit message and the author/committer identity
                                             (commit-msg hook)
  check-private-strings.py --range A..B      lines added in A...B, and each commit's identities
                                             and message in A..B (CI)
  check-private-strings.py --all [--rev R]   every file of R (default HEAD)
  check-private-strings.py --self-test       the generic tests, plus the list's own samples

The list is read from, in order: --patterns FILE; the file named by $WLXJURY_PRIVATE_STRINGS_FILE
(both must exist); the content of $WLXJURY_PRIVATE_STRINGS (CI, from a secret); the default file
(%APPDATA%\\wlxjury\\private-strings on Windows, else $XDG_CONFIG_HOME/wlxjury/private-strings or
~/.config/wlxjury/private-strings). Files are UTF-8, with or without a byte order mark. One rule per
line, `CATEGORY<TAB>REGEX` (category ^[a-z][a-z0-9-]{0,30}$, Python regex syntax), `#` comments;
`allow` rules are removed from a line before the others are tried, `skip-path` rules name files that
aren't read. A line that isn't a valid rule is refused by its line number, never its text. Samples
for --self-test sit next to the file, in FILE.samples: `hit<TAB>TEXT` or `pass<TAB>TEXT`.

A hit prints the file and line (or the commit) and the rule's category, never the text it matched,
and exits 1. Without a list, or with one that has no active rule (other than `allow` and
`skip-path`), the check warns and passes, unless --require-patterns is given (CI), which then fails,
as it does on a refused line.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile

SKIP_PATH = [re.compile(r"^data/.*\.csv$")]      # public data files, never read
SCISSORS = re.compile(r"^# -+ >8 -+$")
CATEGORY = re.compile(r"^[a-z][a-z0-9-]{0,30}$")


class Rules:
    """A list's rules. A line that isn't `CATEGORY<TAB>REGEX` with a valid category and a regex
    that compiles is refused: `bad` names its line number only, never its text (a message of
    re.error quotes parts of the pattern). A list without one active rule (a category other than
    `allow` and `skip-path`) counts as no list."""

    def __init__(self, text, source):
        self.source = source
        self.patterns, self.allow, self.skip, self.bad = [], [], list(SKIP_PATH), []
        for n, line in enumerate(text.lstrip("\ufeff").splitlines(), 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            if "\t" not in line:
                self.bad.append("line %d: not CATEGORY<TAB>REGEX" % n)
                continue
            cat, rx = line.split("\t", 1)
            if not CATEGORY.fullmatch(cat):
                self.bad.append("line %d: the category isn't %s" % (n, CATEGORY.pattern))
                continue
            if not rx:
                self.bad.append("line %d: empty regex" % n)
                continue
            try:
                compiled = re.compile(rx)
            except re.error as e:
                self.bad.append("line %d: the regex doesn't compile (position %s)"
                                % (n, "?" if e.pos is None else e.pos))
                continue
            except RecursionError:
                # the regex parser recurses per nesting level; a deeply nested group is a refused
                # line like any other, not a crash with a traceback
                self.bad.append("line %d: the regex is nested too deeply to compile" % n)
                continue
            if cat == "allow":
                self.allow.append(compiled)
            elif cat == "skip-path":
                self.skip.append(compiled)
            else:
                self.patterns.append((cat, compiled))

    def problems(self):
        return ["check-private-strings: %s: %s" % (self.source, b) for b in self.bad]

    def skipped(self, path):
        return any(rx.search(path) for rx in self.skip)

    def line_hit(self, line):
        for rx in self.allow:
            line = rx.sub(" ", line)
        for cat, rx in self.patterns:
            if rx.search(line):
                return cat
        return None


def read_samples(content):
    """((line number, kind, text) of each sample, refused lines by number). A sample is
    `hit<TAB>TEXT` (the list must find it) or `pass<TAB>TEXT` (it must not)."""
    samples, bad = [], []
    for n, line in enumerate(content.lstrip("\ufeff").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        kind, tab, sample = line.partition("\t")
        if not tab or kind not in ("hit", "pass"):
            bad.append("samples line %d: not hit<TAB>TEXT or pass<TAB>TEXT" % n)
        else:
            samples.append((n, kind, sample))
    return samples, bad


def read_file(path):
    with open(path, encoding="utf-8-sig") as f:
        return f.read()


def default_file():
    if os.name == "nt" and os.environ.get("APPDATA"):
        return os.path.join(os.environ["APPDATA"], "wlxjury", "private-strings")
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.join(os.path.expanduser("~"), ".config")
    return os.path.join(base, "wlxjury", "private-strings")


def load_rules(path_arg):
    """(Rules, samples file or None), or (None, None) when no list is found. A file named by
    --patterns or $WLXJURY_PRIVATE_STRINGS_FILE must exist."""
    for path in (path_arg, os.environ.get("WLXJURY_PRIVATE_STRINGS_FILE")):
        if path:
            if not os.path.isfile(path):
                raise SystemExit("check-private-strings: no such file: %s" % path)
            return Rules(read_file(path), path), path + ".samples"
    content = os.environ.get("WLXJURY_PRIVATE_STRINGS")
    if content and content.strip():
        return Rules(content, "$WLXJURY_PRIVATE_STRINGS"), None
    path = default_file()
    if os.path.isfile(path):
        return Rules(read_file(path), path), path + ".samples"
    return None, None


def git(*args, ok=(0,)):
    r = subprocess.run(["git", "-c", "core.quotepath=false", *args], capture_output=True)
    if r.returncode not in ok:
        sys.exit("check-private-strings: git %s failed (%d): %s"
                 % (" ".join(args[:2]), r.returncode, r.stderr.decode("utf-8", "replace").strip()))
    return r


def text(b):
    return b.decode("utf-8", "surrogateescape")


def scan(rules, where, content, hits):
    """Each line of content with a hit, as `where:line: category` (never the text itself)."""
    for i, line in enumerate(content.split("\n"), 1):
        cat = rules.line_hit(line)
        if cat:
            hits.append("%s:%d: %s" % (where, i, cat))


def binary(b):
    return b"\0" in b[:8000]


def check_all(rules, rev, hits):
    out = git("ls-tree", "-r", "-z", "--name-only", rev).stdout
    for path in (text(p) for p in out.split(b"\0") if p):
        if rules.skipped(path):
            continue
        b = git("show", "%s:%s" % (rev, path)).stdout
        if not binary(b):
            scan(rules, path, text(b), hits)


def check_staged(rules, hits):
    out = git("diff", "--cached", "--name-only", "-z", "--diff-filter=ACMR").stdout
    for path in (text(p) for p in out.split(b"\0") if p):
        if rules.skipped(path):
            continue
        b = git("show", ":" + path).stdout
        if not binary(b):
            scan(rules, path, text(b), hits)


def message_text(raw):
    """A commit message as git will store it. Comment lines are dropped, and nothing after the
    scissors line counts, only when the file has git's comment template: a message given with -m
    (`#406 by ...`) is checked whole."""
    lines = raw.split("\n")
    if not any(line.startswith("# Please enter") or SCISSORS.match(line) for line in lines):
        return raw
    kept = []
    for line in lines:
        if SCISSORS.match(line):
            break
        if not line.startswith("#"):
            kept.append(line)
    return "\n".join(kept)


def check_message(rules, path, hits):
    raw = open(path, encoding="utf-8", errors="surrogateescape").read()
    scan(rules, "commit message", message_text(raw), hits)
    for var in ("GIT_AUTHOR_IDENT", "GIT_COMMITTER_IDENT"):
        r = git("var", var, ok=(0, 128))
        if r.returncode == 0:
            scan(rules, var, text(r.stdout).rsplit(" ", 2)[0], hits)


def added_lines(diff):
    """(path, line number, text) of every added line of a unified diff with -U0, parsed by hunk
    header counts, so a content line that starts with `+++` is never taken for a file header."""
    lines = diff.split("\n")
    i, path = 0, None
    while i < len(lines):
        d = lines[i]
        if d.startswith("diff --git "):
            path = None
        elif d.startswith("+++ ") and path is None:
            path = d[6:] if d.startswith("+++ b/") else ""
        elif d.startswith("@@"):
            m = re.match(r"@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", d)
            old_n = int(m.group(1)) if m.group(1) is not None else 1
            new_at, new_n = int(m.group(2)), int(m.group(3)) if m.group(3) is not None else 1
            i += 1
            while (old_n > 0 or new_n > 0) and i < len(lines):
                h = lines[i]
                if h.startswith("+"):
                    if path:
                        yield path, new_at, h[1:]
                    new_at += 1
                    new_n -= 1
                elif h.startswith("-"):
                    old_n -= 1
                elif h.startswith(" "):
                    old_n -= 1
                    new_n -= 1
                    new_at += 1
                i += 1
            continue
        i += 1


def check_range(rules, rng, hits):
    if ".." not in rng or "..." in rng:
        sys.exit("check-private-strings: --range takes A..B")
    a, b = rng.split("..", 1)
    for c in text(git("rev-list", "%s..%s" % (a, b)).stdout).split():
        short = c[:12]
        scan(rules, "commit %s identity" % short,
             text(git("log", "-1", "--format=%an <%ae>%n%cn <%ce>", c).stdout), hits)
        scan(rules, "commit %s message" % short, text(git("log", "-1", "--format=%B", c).stdout), hits)
    diff = text(git("diff", "-U0", "--no-color", "--no-ext-diff", "--no-renames", "%s...%s" % (a, b)).stdout)
    for path, n, line in added_lines(diff):
        if rules.skipped(path):
            continue
        cat = rules.line_hit(line)
        if cat:
            hits.append("%s:%d: %s" % (path, n, cat))


GENERIC_RULES = (
    "# neutral rules for the generic self-test\n"
    "name\t(?<![A-Za-z])(?i:samplename)(?![a-z])\n"
    "address\t(?i)\\bsample@example\\.invalid\\b\n"
    "allow\t(?i)org\\.samplename\\b\n"
    "skip-path\t^fixtures/\n"
)


def self_test(rules, samples):
    """The generic logic with neutral rules, then the list's own samples when it has any."""
    g = Rules(GENERIC_RULES, "generic")
    bad, count = [], 0
    for s, want in [("by Samplename K.", "name"), ("SAMPLENAME123", "name"), ("samplenames", None),
                    ("package org.samplename.x", None), ("mail sample@example.invalid", "address"),
                    ("nothing here", None)]:
        count += 1
        if g.line_hit(s) != want:
            bad.append("generic: %r gave %r, expected %r" % (s, g.line_hit(s), want))
    count += 1
    if not g.skipped("fixtures/a.txt") or not g.skipped("data/x.csv") or g.skipped("app/x.scala"):
        bad.append("generic: skip paths")
    count += 1
    msg = message_text("Subject\n\n# Please enter the commit message for your changes.\n# Samplename\n"
                       "body\n# ------------------------ >8 ------------------------\nSamplename")
    if g.line_hit(msg) or "body" not in msg:
        bad.append("generic: message comments or scissors not dropped")
    count += 1
    if not g.line_hit(message_text("#406 by Samplename")):
        bad.append("generic: a -m message starting with # was not checked")
    count += 1
    diff = ("diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1,0 +1,2 @@\n+++ not a header Samplename\n+ok\n"
            "diff --git a/g b/g\n--- a/g\n+++ b/g\n@@ -3 +3 @@\n-old\n+new Samplename\n")
    got = [(p, k) for p, k, line in added_lines(diff) if g.line_hit(line)]
    if got != [("f", 1), ("g", 3)]:
        bad.append("generic: added lines parsed wrongly: %r" % got)
    count += 1
    hits = []
    scan(g, "f", "x Samplename y", hits)
    if hits != ["f:1: name"]:
        bad.append("generic: a hit must name the place and category only: %r" % hits)
    count += list_format_tests(bad)
    private = 0
    if rules is not None:
        bad.extend(rules.problems())
        if not rules.patterns:
            bad.append("check-private-strings: %s: no active rules, so it counts as no list" % rules.source)
        if samples and os.path.isfile(samples):
            listed, refused = read_samples(read_file(samples))
            bad.extend(refused)
            for n, kind, sample in listed:
                private += 1
                found = rules.line_hit(sample) is not None
                if found != (kind == "hit"):
                    bad.append("samples line %d (%s) %s" % (n, kind, "not found" if kind == "hit" else "wrongly found"))
    for b in bad:
        print(b)
    print("self-test: %d generic checks, %d samples of the list, %d failures" % (count, private, len(bad)))
    return 1 if bad else 0


def list_format_tests(bad):
    """Neutral tests of reading a list and its samples; returns the number of checks."""
    count = 0

    def check(ok, what):
        nonlocal count
        count += 1
        if not ok:
            bad.append("generic: " + what)

    # a list without an active rule counts as no list
    for content in ["", "# a comment\n\n   \n", "name\tneutral(\nname neutral\n", "allow\tx\nskip-path\ty\n"]:
        check(not Rules(content, "t").patterns, "a list without active rules has some: %r" % content)
    # a refused line names its line number only, never its text or re.error's message
    for content, where in [("name\tneutralword\\q\n", "line 1: the regex doesn't compile (position 11)"),
                           ("# c\nname\tok\nname\tneutralword(\n", "line 3: the regex doesn't compile (position 11)"),
                           ("name neutralword\n", "line 1: not CATEGORY<TAB>REGEX"),
                           ("name\t\n", "line 1: empty regex")]:
        r = Rules(content, "t")
        check(r.problems() == ["check-private-strings: t: " + where], "refused line: %r gave %r" % (content, r.bad))
        check(not any("neutral" in m or "\\q" in m for m in r.problems()), "a refused line's text was printed")
    for cat in ["Name", "1name", "na_me", " name", "name ", "a" * 32, "na.me"]:
        r = Rules(cat + "\tneutralword\n", "t")
        check(r.bad == ["line 1: the category isn't %s" % CATEGORY.pattern] and not r.patterns,
              "category %r was not refused" % cat)
    r = Rules("a" * 31 + "\tx\nmy-cat2\ty\n", "t")
    check(not r.bad and len(r.patterns) == 2, "valid categories were refused: %r" % r.bad)
    # samples: hit or pass, a tab; others refused by line number
    listed, refused = read_samples("\ufeffhit\tSamplename\nhit Samplename\nmaybe\tSamplename\n\n# c\npass\tnothing\n")
    check([(n, k) for n, k, _ in listed] == [(1, "hit"), (6, "pass")], "samples read wrongly: %r" % listed)
    check(refused == ["samples line 2: not hit<TAB>TEXT or pass<TAB>TEXT",
                      "samples line 3: not hit<TAB>TEXT or pass<TAB>TEXT"], "samples refused wrongly: %r" % refused)
    # files are read as UTF-8 with or without a byte order mark; --require-patterns fails on an
    # empty list, the hooks warn and pass
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "list")
        with open(path, "w", encoding="utf-8-sig") as f:
            f.write("name\t(?i:samplename)\n")
        with open(path + ".samples", "w", encoding="utf-8-sig") as f:
            f.write("hit\tSamplename\n")
        r, samples = load_rules(path)
        check(not r.bad and [c for c, _ in r.patterns] == ["name"], "a list with a BOM: %r" % r.bad)
        check(read_samples(read_file(samples)) == ([(1, "hit", "Samplename")], []), "samples with a BOM")
        empty = os.path.join(d, "empty")
        with open(empty, "w", encoding="utf-8") as f:
            f.write("# comments only\n")
        env = {k: v for k, v in os.environ.items() if not k.startswith("WLXJURY_PRIVATE_STRINGS")}
        for args, want in [(["--require-patterns"], 2), ([], 0)]:
            run = subprocess.run([sys.executable, os.path.abspath(__file__), "--staged", "--patterns", empty] + args,
                                 capture_output=True, env=env, cwd=d)
            check(run.returncode == want and b"no active rules" in run.stdout + run.stderr,
                  "an empty list with %r exited %d" % (args, run.returncode))
    return count


def warn_once():
    r = git("rev-parse", "--git-common-dir", ok=(0, 128))
    marker = os.path.join(text(r.stdout).strip(), "wlxjury-private-strings-warned") if r.returncode == 0 else None
    if marker and os.path.exists(marker):
        return
    sys.stderr.write("check-private-strings: no private-strings list (CONTRIBUTING.md, \"Private strings\"); "
                     "not checked. This warning is shown once per clone.\n")
    if marker:
        open(marker, "w").close()


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--staged", action="store_true")
    g.add_argument("--message", metavar="FILE")
    g.add_argument("--range", metavar="A..B")
    g.add_argument("--all", action="store_true")
    g.add_argument("--self-test", action="store_true")
    ap.add_argument("--rev", default="HEAD")
    ap.add_argument("--patterns", metavar="FILE")
    ap.add_argument("--require-patterns", action="store_true",
                    help="fail when no list is available (CI)")
    a = ap.parse_args()
    rules, samples = load_rules(a.patterns)
    if a.self_test:
        return self_test(rules, samples)
    if rules is not None:
        for m in rules.problems():
            sys.stderr.write(m + "\n")
    if rules is None or not rules.patterns:
        if rules is None and not a.require_patterns:
            warn_once()
        elif rules is not None:
            sys.stderr.write("check-private-strings: %s has no active rules, so it counts as no list; not checked.\n"
                             % rules.source)
        if a.require_patterns:
            print("check-private-strings: no private-strings list; CI needs the PRIVATE_STRINGS secret")
            return 2
        return 0
    hits = []
    if a.staged:
        check_staged(rules, hits)
    elif a.message:
        check_message(rules, a.message, hits)
    elif a.range:
        check_range(rules, a.range, hits)
    else:
        check_all(rules, a.rev, hits)
    for h in hits:
        print(h)
    if hits:
        print("check-private-strings: %d hit(s); see CONTRIBUTING.md, \"Private strings\"" % len(hits))
        return 1
    if rules.bad and a.require_patterns:
        print("check-private-strings: %d refused line(s) in the list; CI needs every line valid" % len(rules.bad))
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
