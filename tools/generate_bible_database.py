#!/usr/bin/env python3
"""Build the bundled VIE1925 SQLite database from eBible USFM."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sqlite3
import tempfile
import uuid
import zipfile
from pathlib import Path


BOOK_CODE_RE = re.compile(r"\\id\s+([0-9A-Z]{3})")
CHAPTER_RE = re.compile(r"\\c\s+(\d+)")
VERSE_RE = re.compile(r"\\v\s+([0-9]+(?:[-,][0-9]+)?)\s+(.*)")
MARKER_RE = re.compile(r"\\[a-z0-9]+\*?")
SPACE_RE = re.compile(r"\s+")


def clean_text(value: str) -> str:
    return SPACE_RE.sub(" ", MARKER_RE.sub("", value)).strip()


def parse_usfm(text: str, source: str) -> tuple[str, dict[int, list[tuple[str, str]]]]:
    code_match = BOOK_CODE_RE.search(text)
    if not code_match:
        raise ValueError(f"Missing book code in {source}")

    code = code_match.group(1)
    chapters: dict[int, list[tuple[str, str]]] = {}
    chapter = 0
    current_verse: tuple[str, str] | None = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        chapter_match = CHAPTER_RE.fullmatch(line)
        if chapter_match:
            chapter = int(chapter_match.group(1))
            chapters.setdefault(chapter, [])
            current_verse = None
            continue

        verse_match = VERSE_RE.fullmatch(line)
        if verse_match and chapter:
            number = verse_match.group(1)
            value = clean_text(verse_match.group(2))
            current_verse = (number, value)
            chapters[chapter].append(current_verse)
            continue

        if current_verse and line and not line.startswith("\\"):
            number, value = current_verse
            value = clean_text(f"{value} {line}")
            chapters[chapter][-1] = (number, value)
            current_verse = (number, value)

    return code, chapters


def build_database(manifest_path: Path, usfm_zip: Path, output_path: Path) -> None:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if len(manifest) != 1189:
        raise ValueError(f"Expected 1189 audio chapters, found {len(manifest)}")

    books: list[dict[str, object]] = []
    by_code: dict[str, list[dict[str, object]]] = {}
    for chapter in manifest:
        by_code.setdefault(chapter["bookCode"], []).append(chapter)
    for order, (code, chapters) in enumerate(by_code.items(), start=1):
        first = chapters[0]
        books.append(
            {
                "book_order": order,
                "code": code,
                "name": first["bookName"],
                "testament": first["testament"],
                "chapter_count": len(chapters),
            }
        )
    if len(books) != 66:
        raise ValueError(f"Expected 66 books, found {len(books)}")

    parsed: dict[str, dict[int, list[tuple[str, str]]]] = {}
    with zipfile.ZipFile(usfm_zip) as archive:
        for name in sorted(item for item in archive.namelist() if item.endswith(".usfm")):
            text = archive.read(name).decode("utf-8-sig")
            code, chapters = parse_usfm(text, name)
            parsed[code] = chapters

    if set(parsed) != set(by_code):
        missing = sorted(set(by_code) - set(parsed))
        extra = sorted(set(parsed) - set(by_code))
        raise ValueError(f"USFM mismatch; missing={missing}, extra={extra}")

    verse_count = 0
    for code, audio_chapters in by_code.items():
        text_chapters = parsed[code]
        expected = set(range(1, len(audio_chapters) + 1))
        if set(text_chapters) != expected:
            raise ValueError(f"Chapter mismatch for {code}")
        if any(not text_chapters[number] for number in expected):
            raise ValueError(f"Empty chapter found in {code}")
        verse_count += sum(len(verses) for verses in text_chapters.values())

    output_path.parent.mkdir(parents=True, exist_ok=True)
    staging_path = Path(tempfile.gettempdir()) / f"vie1925-{uuid.uuid4().hex}.db"
    staging_path.unlink(missing_ok=True)
    connection = sqlite3.connect(staging_path)
    try:
        connection.executescript(
            """
            PRAGMA journal_mode=DELETE;
            PRAGMA synchronous=OFF;
            CREATE TABLE metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE books (
                book_order INTEGER PRIMARY KEY,
                code TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                testament TEXT NOT NULL,
                chapter_count INTEGER NOT NULL
            );
            CREATE TABLE chapters (
                global_order INTEGER PRIMARY KEY,
                book_code TEXT NOT NULL,
                chapter_number INTEGER NOT NULL,
                audio_filename TEXT NOT NULL,
                audio_url TEXT NOT NULL,
                UNIQUE(book_code, chapter_number)
            );
            CREATE TABLE verses (
                book_code TEXT NOT NULL,
                chapter_number INTEGER NOT NULL,
                verse_number TEXT NOT NULL,
                text TEXT NOT NULL,
                PRIMARY KEY(book_code, chapter_number, verse_number)
            );
            CREATE INDEX verses_chapter
                ON verses(book_code, chapter_number);
            """
        )
        connection.executemany(
            "INSERT INTO metadata(key, value) VALUES (?, ?)",
            [
                ("title", "Kinh Thánh Tiếng Việt 1923/1925"),
                ("abbreviation", "VIE1925"),
                ("source", "https://ebible.org/details.php?id=vie1934"),
                ("license", "Public Domain"),
                ("verse_count", str(verse_count)),
            ],
        )
        connection.executemany(
            """
            INSERT INTO books(book_order, code, name, testament, chapter_count)
            VALUES (:book_order, :code, :name, :testament, :chapter_count)
            """,
            books,
        )
        connection.executemany(
            """
            INSERT INTO chapters(
                global_order, book_code, chapter_number, audio_filename, audio_url
            ) VALUES (?, ?, ?, ?, ?)
            """,
            [
                (
                    item["globalOrder"],
                    item["bookCode"],
                    item["chapter"],
                    item["filename"],
                    item["url"],
                )
                for item in manifest
            ],
        )
        verse_rows = []
        for book in books:
            code = str(book["code"])
            for chapter_number, verses in parsed[code].items():
                for verse_number, text in verses:
                    verse_rows.append((code, chapter_number, verse_number, text))
        connection.executemany(
            """
            INSERT INTO verses(book_code, chapter_number, verse_number, text)
            VALUES (?, ?, ?, ?)
            """,
            verse_rows,
        )
        connection.commit()
        connection.execute("VACUUM")
    finally:
        connection.close()

    output_path.unlink(missing_ok=True)
    output_path.with_name(f"{output_path.name}-journal").unlink(missing_ok=True)
    shutil.copyfile(staging_path, output_path)
    staging_path.unlink(missing_ok=True)
    print(
        f"Wrote {output_path} with 66 books, 1189 chapters, "
        f"{verse_count} verses ({output_path.stat().st_size} bytes)"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--usfm-zip", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    build_database(args.manifest, args.usfm_zip, args.output)


if __name__ == "__main__":
    main()
