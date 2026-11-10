#!/usr/bin/env python3
"""Writes the courtesy credits of the photo lists (browser-test/docs-photos-*.json) into the
Credits section of docs/user/images/README.md, between its two `<!-- credits -->` markers:
Commons file name, author, licence, contest, year and country, grouped by list.

  python browser-test/docs-photos-credits.py
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
README = HERE.parent / "docs" / "user" / "images" / "README.md"
MARK = "<!-- credits -->"
LISTS = [
    ("wlm-ua", "Wiki Loves Monuments, Ukraine"),
    ("wlm-intl", "Wiki Loves Monuments, other countries"),
    ("wle-ua", "Wiki Loves Earth, Ukraine"),
    ("wle-intl", "Wiki Loves Earth, other countries"),
]

lines = []
for name, title in LISTS:
    photos = json.loads((HERE / f"docs-photos-{name}.json").read_text(encoding="utf-8"))["photos"]
    lines += [f"### {title} (`{name}`, {len(photos)} photos)", ""]
    for p in sorted(photos, key=lambda p: (p["country"], p["title"])):
        # Commons' Artist field can carry line breaks and no-break spaces: one Markdown line each
        author = " ".join(p["author"].split())
        lines.append(f"- {p['title'][5:]}: {author}, {p['license']}, {p['year']}, {p['country']}")
    lines.append("")

text = README.read_text(encoding="utf-8")
if text.count(MARK) != 2:
    sys.exit(f"{README} needs exactly two {MARK} markers around its credits; found {text.count(MARK)}")
head, _, rest = text.partition(MARK)
_, _, tail = rest.partition(MARK)
README.write_text(f"{head}{MARK}\n\n" + "\n".join(lines) + f"\n{MARK}{tail}", encoding="utf-8")
print(f"credits of {len(LISTS)} lists written to {README}")
