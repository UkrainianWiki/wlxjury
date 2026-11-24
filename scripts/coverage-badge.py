#!/usr/bin/env python3
"""Write a static SVG badge, with no third-party service (used by pages.yml).

    python3 scripts/coverage-badge.py coverage SCOVERAGE_XML OUT.svg
    python3 scripts/coverage-badge.py static LABEL VALUE COLOR OUT.svg

`coverage` reads the statement rate of a scoverage.xml (a percentage) and writes
"coverage NN%", coloured by threshold: under 50 red, 50 orange, 70 yellow, 80 yellow-green,
90 green, 95 bright green. Exit code 1 if the XML has no readable rate, so no wrong badge is
published.
"""
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

COLORS = [(95, "#4c1"), (90, "#97ca00"), (80, "#a4a61d"), (70, "#dfb317"), (50, "#fe7d37")]
RED = "#e05d44"
GREY = "#555"


def color_for(pct):
    for threshold, color in COLORS:
        if pct >= threshold:
            return color
    return RED


def format_pct(pct):
    """Whole percent, rounded half up (91.5 is 92%, not Python's banker's 92 or 91)."""
    return f"{int(pct + 0.5)}%"


def _width(text):
    return 10 + 7 * len(text)


def badge(label, value, color):
    lw, vw = _width(label), _width(value)
    total = lw + vw
    label, value = escape(label), escape(value)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{total}" height="20" role="img" '
        f'aria-label="{label}: {value}">'
        f'<title>{label}: {value}</title>'
        f'<rect width="{lw}" height="20" fill="{GREY}"/>'
        f'<rect x="{lw}" width="{vw}" height="20" fill="{color}"/>'
        '<g fill="#fff" font-family="Verdana,DejaVu Sans,sans-serif" font-size="11" text-anchor="middle">'
        f'<text x="{lw / 2}" y="14">{label}</text>'
        f'<text x="{lw + vw / 2}" y="14">{value}</text>'
        "</g></svg>\n"
    )


def statement_rate(path):
    return float(ET.parse(path).getroot().get("statement-rate"))


def main(argv):
    try:
        if len(argv) == 4 and argv[1] == "coverage":
            pct = statement_rate(argv[2])
            if not 0 <= pct <= 100:
                raise ValueError(pct)
            svg = badge("coverage", format_pct(pct), color_for(pct))
            out = argv[3]
        elif len(argv) == 6 and argv[1] == "static":
            svg, out = badge(argv[2], argv[3], argv[4]), argv[5]
        else:
            print(__doc__, file=sys.stderr)
            return 2
    except (OSError, ET.ParseError, TypeError, ValueError) as e:
        print(f"coverage-badge: no readable coverage rate ({type(e).__name__})", file=sys.stderr)
        return 1
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(svg)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
