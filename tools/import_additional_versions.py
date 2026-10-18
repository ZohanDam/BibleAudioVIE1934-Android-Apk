#!/usr/bin/env python3
"""Import authorized VIE2010 and NIV text, preserving the VIE1925 audio mapping."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import closing
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import time
import urllib.error
import urllib.request

from import_youversion_text import parse_chapter, PARSER_REVISION


VERSIONS = [
    ("VIE1925", 193, "Kinh Th\u00e1nh Ti\u1ebfng Vi\u1ec7t 1925", "vi",
     "Vietnamese 1925 translation - Public Domain", "https://www.bible.com/versions/193"),
    ("VIE2010", 151, "Kinh Th\u00e1nh Ti\u1ebfng Vi\u1ec7t B\u1ea3n Hi\u1ec7u \u0110\u00ednh 2010", "vi",
     "\u00a9 2010 United Bible Societies.", "https://www.bible.com/versions/151"),
    ("NIV", 111, "New International Version", "en",
     "Holy Bible, New International Version\u00ae, NIV\u00ae. Copyright \u00a9 1973, 1978, 1984, 2011 by Biblica, Inc.\u00ae Used by permission. All rights reserved worldwide.",
     "https://www.bible.com/versions/111"),
]


def fetch(item: dict, edition: tuple, cache: Path) -> dict:
    code, version_id = edition[:2]
    reference = f'{item["bookCode"]}.{item["chapter"]}'
    directory = cache / code
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / f"{reference}.json"
    if target.exists():
        result = json.loads(target.read_text(encoding="utf-8"))
        if (result.get("version") == version_id and
                result.get("parser_revision") == PARSER_REVISION and
                result.get("reference") == reference):
            return result
    raw = directory / f"{reference}.html.gz"
    url = f"https://www.bible.com/bible/{version_id}/{reference}.{code}"
    for attempt in range(4):
        try:
            if raw.exists():
                payload = gzip.decompress(raw.read_bytes())
            else:
                request = urllib.request.Request(url, headers={
                    "User-Agent": "NgheKinhThanh1925/2.16 (authorized text import)",
                    "Accept-Encoding": "gzip",
                })
                with urllib.request.urlopen(request, timeout=35) as response:
                    payload = response.read()
                    if response.headers.get("Content-Encoding") == "gzip":
                        payload = gzip.decompress(payload)
            raw.write_bytes(gzip.compress(payload))
            result = parse_chapter(payload.decode("utf-8"), reference, version_id)
            result["url"] = url
            result["html_sha256"] = hashlib.sha256(payload).hexdigest()
            target.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
            return result
        except (urllib.error.URLError, TimeoutError):
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)
    raise AssertionError("Unreachable")


def build(database: Path, manifest: list[dict], results: dict) -> None:
    expected = {(c["bookCode"], c["chapter"]) for c in manifest}
    for edition in VERSIONS[1:]:
        code = edition[0]
        entries = results[code]
        if len(entries) != 1189 or {tuple(k.split(".")) for k in entries} != {
                (book, str(chapter)) for book, chapter in expected}:
            raise ValueError(f"Incomplete chapters: {code}")
        if sum(len(value["verses"]) for value in entries.values()) < 30000:
            raise ValueError(f"Unexpectedly few verses: {code}")
    temporary = database.with_suffix(".versions.tmp.db")
    shutil.copyfile(database, temporary)
    with closing(sqlite3.connect(temporary)) as connection:
        audio_before = list(connection.execute("SELECT * FROM chapters ORDER BY global_order"))
        connection.executescript("""
            DROP TABLE IF EXISTS version_verses;
            DROP TABLE IF EXISTS version_books;
            DROP TABLE IF EXISTS text_versions;
            CREATE TABLE text_versions (
                code TEXT PRIMARY KEY, source_id INTEGER NOT NULL,
                title TEXT NOT NULL, language TEXT NOT NULL,
                copyright TEXT NOT NULL, source_url TEXT NOT NULL,
                display_order INTEGER NOT NULL
            );
            CREATE TABLE version_books (
                version_code TEXT NOT NULL, book_code TEXT NOT NULL,
                name TEXT NOT NULL,
                PRIMARY KEY(version_code, book_code)
            );
            CREATE TABLE version_verses (
                version_code TEXT NOT NULL, book_code TEXT NOT NULL,
                chapter_number INTEGER NOT NULL, verse_number TEXT NOT NULL,
                verse_order INTEGER NOT NULL, text TEXT NOT NULL,
                PRIMARY KEY(version_code, book_code, chapter_number, verse_number)
            );
            CREATE INDEX version_verses_chapter
                ON version_verses(version_code, book_code, chapter_number, verse_order);
            INSERT INTO version_books SELECT 'VIE1925', code, name FROM books;
            INSERT INTO version_verses
                SELECT 'VIE1925', book_code, chapter_number, verse_number, rowid, text FROM verses;
        """)
        connection.executemany("INSERT INTO text_versions VALUES (?, ?, ?, ?, ?, ?, ?)",
                               [(*edition, index) for index, edition in enumerate(VERSIONS)])
        for edition in VERSIONS[1:]:
            code = edition[0]
            names = {}
            rows = []
            for item in manifest:
                reference = f'{item["bookCode"]}.{item["chapter"]}'
                entry = results[code][reference]
                if entry.get("book_name"):
                    names.setdefault(item["bookCode"], entry["book_name"])
                rows.extend((code, item["bookCode"], item["chapter"], number, index, text)
                            for index, (number, text) in enumerate(entry["verses"]))
            if len(names) != 66 or any(not row[-1].strip() for row in rows):
                raise ValueError(f"Missing book names or verse text: {code}")
            connection.executemany("INSERT INTO version_books VALUES (?, ?, ?)",
                                   [(code, book, name) for book, name in names.items()])
            connection.executemany("INSERT INTO version_verses VALUES (?, ?, ?, ?, ?, ?)", rows)
            print(f"{code}: 66 books, 1189 chapters, {len(rows)} verse entries", flush=True)
        connection.execute("INSERT OR REPLACE INTO metadata VALUES ('text_versions','VIE1925,VIE2010,NIV')")
        connection.execute("INSERT OR REPLACE INTO metadata VALUES ('additional_text_permission','Authorization stated by app owner')")
        connection.execute("PRAGMA user_version=3")
        connection.commit()
        if connection.execute("PRAGMA integrity_check").fetchone() != ("ok",):
            raise ValueError("SQLite integrity check failed")
        if audio_before != list(connection.execute("SELECT * FROM chapters ORDER BY global_order")):
            raise ValueError("Audio mapping changed")
        connection.execute("VACUUM")
    temporary.replace(database)
    print("Offline multi-version database validated and saved", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--sample", type=int, default=0)
    parser.add_argument("--workers", type=int, default=6)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if len(manifest) != 1189:
        raise ValueError("Expected 1189 canonical audio chapters")
    items = manifest[:args.sample] if args.sample else manifest
    results = {edition[0]: {} for edition in VERSIONS[1:]}
    errors = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(fetch, item, edition, args.cache): edition[0]
                   for edition in VERSIONS[1:] for item in items}
        for count, future in enumerate(as_completed(futures), 1):
            code = futures[future]
            try:
                entry = future.result()
                results[code][entry["reference"]] = entry
            except Exception as error:
                errors.append(f"{code}: {error}")
                print(errors[-1], flush=True)
            if count % 100 == 0 or count == len(futures):
                print(f"Validated {count}/{len(futures)} added chapters", flush=True)
    if errors:
        raise ValueError(f"{len(errors)} failures; rerun to resume: {errors[:5]}")
    if not args.sample:
        build(args.database, manifest, results)


if __name__ == "__main__":
    main()
