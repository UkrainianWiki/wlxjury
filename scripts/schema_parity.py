#!/usr/bin/env python3
"""Compare a database's columns with a freshly migrated one's (the schema the tests run on).

The tests build every database from the migrations, V1 onward. Production's tables are older than
Flyway in places (Play evolutions from 2015), so a column can differ there, and the tests never see
it: production's users.password stayed NOT NULL while V2 makes it nullable, and from 26.11 every
add of a new person failed on production only (V98). This script finds such columns before a
release, on the production copy used for the rehearsal.

1. Dump the columns of each database with the same query (--print-query prints it):

     python scripts/schema_parity.py --print-query > columns.sql
     mariadb -N -B wlxjury < columns.sql > target.tsv      # the production copy (or production)
     mariadb -N -B fresh   < columns.sql > fresh.tsv       # a database migrated from empty

   A fresh database: an empty MariaDB of production's version that the release's app has started
   against once (db.default.migration.auto), or the tests' (SharedTestDb).

2. Compare:

     python scripts/schema_parity.py fresh.tsv target.tsv [--all] [--ignore monument.id]

   A problem (exit status 1): a column NOT NULL in the target but nullable in the fresh database.
   The code may write NULL into it (the tests pass), the target refuses it. With --all, the other
   differences are listed too (type, default, extra, NOT NULL only in fresh), as notes: they don't
   change the exit status. Tables and columns only one side has are ignored (a backup table on
   production, a migration not yet applied). --ignore TABLE.COLUMN (repeatable) leaves out a column
   checked by hand: production's monument.id is NOT NULL (an old key), and the app always writes
   it (MonumentJdbc binds Monument.id, a String). Standard library only.
"""
import argparse
import sys

QUERY = (
    "SELECT TABLE_NAME, COLUMN_NAME, IS_NULLABLE, IFNULL(COLUMN_DEFAULT, '(none)'), COLUMN_TYPE, EXTRA "
    "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
    "ORDER BY TABLE_NAME, COLUMN_NAME;"
)

FIELDS = ("nullable", "default", "type", "extra")


def load(lines):
    """{(table, column): {nullable: bool, default, type, extra}} from the query's tab-separated rows."""
    columns = {}
    for number, line in enumerate(lines, 1):
        line = line.rstrip("\r\n")
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 5:
            raise ValueError(f"line {number}: expected 6 tab-separated fields, got {len(parts)}")
        parts += [""] * (6 - len(parts))
        table, column, nullable, default, ctype, extra = parts[:6]
        if nullable not in ("YES", "NO"):
            raise ValueError(f"line {number}: IS_NULLABLE is {nullable!r}, not YES or NO")
        columns[(table.lower(), column.lower())] = {
            "nullable": nullable == "YES", "default": default, "type": ctype.lower(), "extra": extra.lower()}
    return columns


def compare(fresh, target, ignore=()):
    """(problems, notes): lists of strings. Only columns both sides have are compared, but those
    in `ignore` ("table.column")."""
    problems, notes = [], []
    ignored = {tuple(name.lower().split(".", 1)) for name in ignore}
    for key in sorted((set(fresh) & set(target)) - ignored):
        f, t = fresh[key], target[key]
        name = "%s.%s" % key
        if f["nullable"] and not t["nullable"]:
            problems.append(f"{name}: NOT NULL in the target, nullable in fresh (a NULL the code writes is refused)")
        elif t["nullable"] and not f["nullable"]:
            notes.append(f"{name}: nullable in the target, NOT NULL in fresh")
        for field in ("type", "default", "extra"):
            if f[field] != t[field]:
                notes.append(f"{name}: {field} {t[field]!r} in the target, {f[field]!r} in fresh")
    return problems, notes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("fresh", nargs="?", help="columns of a freshly migrated database (TSV)")
    parser.add_argument("target", nargs="?", help="columns of the database to check (TSV)")
    parser.add_argument("--all", action="store_true", help="also list the other differences, as notes")
    parser.add_argument("--ignore", action="append", default=[], metavar="TABLE.COLUMN",
                        help="leave out a column checked by hand (repeatable)")
    parser.add_argument("--print-query", action="store_true", help="print the query that dumps the columns")
    args = parser.parse_args(argv)
    if args.print_query:
        print(QUERY)
        return 0
    if not args.fresh or not args.target:
        parser.error("give the fresh and the target column files, or --print-query")
    with open(args.fresh, encoding="utf-8") as f:
        fresh = load(f)
    with open(args.target, encoding="utf-8") as f:
        target = load(f)
    if not fresh or not target:
        print("schema_parity: a column file is empty", file=sys.stderr)
        return 2
    bad = [name for name in args.ignore if "." not in name]
    if bad:
        parser.error("--ignore takes TABLE.COLUMN: " + ", ".join(bad))
    problems, notes = compare(fresh, target, args.ignore)
    for p in problems:
        print("PROBLEM " + p)
    if args.all:
        for n in notes:
            print("note    " + n)
    shared = len(set(fresh) & set(target))
    ignored = f" ({len(args.ignore)} ignored)" if args.ignore else ""
    print(f"{shared} shared columns compared{ignored}: {len(problems)} problem(s), {len(notes)} other difference(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
