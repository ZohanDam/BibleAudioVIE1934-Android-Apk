#!/usr/bin/env python3
"""Validate every offline chapter against its imported source cache."""

import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    with closing(sqlite3.connect(args.database)) as current, closing(sqlite3.connect(args.baseline)) as old:
        assert current.execute("PRAGMA integrity_check").fetchone() == ("ok",)
        expected = set(old.execute("SELECT book_code, chapter_number FROM chapters"))
        assert len(expected) == 1189
        for table in ("books", "chapters", "verses"):
            assert list(current.execute(f"SELECT * FROM {table} ORDER BY rowid")) == list(
                old.execute(f"SELECT * FROM {table} ORDER BY rowid")), table
        versions = list(current.execute("SELECT code, source_id FROM text_versions ORDER BY display_order"))
        assert versions == [("VIE1925", 193), ("VIE2010", 151), ("NIV", 111)]
        for code, source_id in versions:
            names = dict(current.execute(
                "SELECT book_code, name FROM version_books WHERE version_code=?", (code,)))
            assert len(names) == 66 and all(name.strip() for name in names.values())
            chapters = set(current.execute(
                "SELECT DISTINCT book_code, chapter_number FROM version_verses WHERE version_code=?", (code,)))
            assert chapters == expected, code
            count = 0
            for book, chapter in sorted(expected):
                verses = current.execute(
                    "SELECT verse_number, text FROM version_verses "
                    "WHERE book_code=? AND chapter_number=? AND version_code=? ORDER BY verse_order",
                    (book, chapter, code)).fetchall()
                assert verses and all(number and text.strip() and "\ufffd" not in text for number, text in verses)
                if code == "VIE1925":
                    original = old.execute(
                        "SELECT verse_number, text FROM verses WHERE book_code=? AND chapter_number=? ORDER BY rowid",
                        (book, chapter)).fetchall()
                else:
                    cached = json.loads((args.cache / code / f"{book}.{chapter}.json").read_text(encoding="utf-8"))
                    assert cached["version"] == source_id
                    original = [tuple(row) for row in cached["verses"]]
                assert verses == original, (code, book, chapter)
                count += len(verses)
            print(f"{code}: 66 books, 1189 chapters, {count} verse entries verified offline", flush=True)
        plan = current.execute(
            "EXPLAIN QUERY PLAN SELECT verse_number, text FROM version_verses "
            "WHERE book_code=? AND chapter_number=? AND version_code=? ORDER BY verse_order",
            ("GEN", 1, "NIV")).fetchall()
        assert any("USING INDEX version_verses_chapter" in row[-1] for row in plan), plan
        print("All source comparisons passed; original audio/text preserved; indexed lookups verified")


if __name__ == "__main__":
    main()
