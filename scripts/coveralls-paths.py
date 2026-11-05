#!/usr/bin/env python3
"""Write Coveralls' copies of the coverage reports, with paths relative to the repository root.

Coveralls matches a report's files to the repository by path. scoverage's Cobertura XML names
each file relative to one of its <source> directories (`ErrorHandler.scala`, under `app/`), and
Jest's lcov relative to npm's working directory, javascript-test/ (`../public/javascripts/x.js`).
This writes target/coveralls/cobertura.xml and target/coveralls/lcov.info with `app/...` and
`public/...` paths; a path it can't place on an existing file is left as it is.

    python3 scripts/coveralls-paths.py [--root DIR]
"""
import argparse
from pathlib import Path, PurePosixPath
import sys
import xml.etree.ElementTree as ET

COBERTURA = Path("target/scala-2.13/coverage-report/cobertura.xml")
LCOV = Path("target/jest-coverage/lcov.info")
OUT = Path("target/coveralls")
# npm runs Jest in javascript-test/ (build.sbt, jsTest), so lcov's paths start there
LCOV_BASES = ("javascript-test", ".")


def _relative(root, candidate):
    """candidate (a file) as a POSIX path relative to root, or None if it isn't a file under it."""
    try:
        path = candidate.resolve()
        if not path.is_file():
            return None
        return PurePosixPath(*path.relative_to(root.resolve()).parts).as_posix()
    except (OSError, ValueError):
        return None


def cobertura_with_repo_paths(xml_text, root):
    """The Cobertura XML with every class's filename relative to root (when it is found)."""
    tree = ET.ElementTree(ET.fromstring(xml_text))
    sources = [Path(s.text.strip()) for s in tree.iter("source") if s.text and s.text.strip()]
    for node in tree.iter("class"):
        name = node.get("filename")
        if not name:
            continue
        for source in sources + [root]:
            base = source if source.is_absolute() else root / source
            found = _relative(root, base / name)
            if found:
                node.set("filename", found)
                break
    return ET.tostring(tree.getroot(), encoding="unicode")


def lcov_with_repo_paths(text, root):
    """The lcov tracefile with every SF: path relative to root (when it is found)."""
    out = []
    for line in text.splitlines():
        if line.startswith("SF:"):
            name = line[3:]
            for base in LCOV_BASES:
                found = _relative(root, root / base / name)
                if found:
                    line = "SF:" + found
                    break
        out.append(line)
    return "\n".join(out) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args(argv)
    root = args.root
    (root / OUT).mkdir(parents=True, exist_ok=True)
    cobertura = (root / COBERTURA).read_text(encoding="utf-8")
    (root / OUT / "cobertura.xml").write_text(
        '<?xml version="1.0"?>\n' + cobertura_with_repo_paths(cobertura, root) + "\n", encoding="utf-8")
    lcov = (root / LCOV).read_text(encoding="utf-8")
    (root / OUT / "lcov.info").write_text(lcov_with_repo_paths(lcov, root), encoding="utf-8")
    print(f"wrote {OUT / 'cobertura.xml'} and {OUT / 'lcov.info'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
