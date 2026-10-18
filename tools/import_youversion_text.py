#!/usr/bin/env python3
"""Import YouVersion VIE1925 (193), matching the bundled audio version."""

from __future__ import annotations

import argparse
import concurrent.futures
import gzip
import hashlib
import json
import re
import shutil
import sqlite3
import time
import urllib.request
from contextlib import closing
from pathlib import Path

from bs4 import BeautifulSoup


VERSION = 193
PARSER_REVISION = 2


def parse_chapter(document: str, reference: str, version_id: int = VERSION) -> dict:
    soup = BeautifulSoup(document, "html.parser")
    version = soup.find("div", attrs={"data-vid": str(version_id)})
    if version is None:
        raise ValueError(f"Missing version {version_id} content: {reference}")
    chapter = version.find(attrs={"data-usfm": reference})
    if chapter is None:
        raise ValueError(f"Wrong chapter: {reference}")
    verses = {}
    footnoted_numbers = set()
    for verse in chapter.find_all(attrs={"data-usfm": True}):
        keys = verse["data-usfm"].split("+")
        if not all(key.startswith(reference + ".") for key in keys):
            continue
        numbers = [key[len(reference) + 1:] for key in keys]
        if len(numbers) == 1:
            number = numbers[0]
        else:
            label = verse.find(class_=lambda c: c and c.endswith("_label"), recursive=False)
            number = label.get_text(strip=True) if label else f"{numbers[0]}-{numbers[-1]}"
        if not re.fullmatch(r"\d+(?:[-,]\d+)?", number):
            continue
        if not any(c.endswith("_verse") or c == "verse" for c in verse.get("class", [])):
            continue
        fragments = verses.setdefault(number, [])
        if verse.find(class_=lambda c: c and c.endswith("_note")) is not None:
            footnoted_numbers.add(number)
        for content in verse.find_all(class_=lambda c: c and (c.endswith("_content") or c == "content")):
            # Ignore nested content and editorial notes, retaining only Scripture.
            parents = list(content.parents)
            parents = parents[:parents.index(verse)]
            if any(any(c.endswith("_note") or c.endswith("_content") for c in p.get("class", [])) for p in parents):
                continue
            fragments.append(content.get_text())
    rows = [(number, re.sub(r"\s+", " ", " ".join(parts)).strip()) for number, parts in verses.items()]
    omitted = [number for number, text in rows if not text]
    # NIV represents some omitted verses as footnote-only markers.
    if version_id == 111 and omitted and set(omitted) <= footnoted_numbers:
        rows = [(number, text) for number, text in rows if text]
    if not rows or any(not text for _, text in rows):
        raise ValueError(f"Empty verses: {reference}: {[number for number, text in rows if not text]}")
    heading = version.find_previous("h1")
    name = heading.get_text(" ", strip=True).rsplit(" ", 1)[0] if heading else None
    return {"reference": reference, "version": version_id, "parser_revision": PARSER_REVISION,
            "book_name": name, "verses": rows, "omitted_verses": omitted}


def fetch_chapter(item: dict, cache: Path) -> dict:
    reference = f'{item["bookCode"]}.{item["chapter"]}'
    target = cache / f"{reference}.json"
    if target.exists():
        data = json.loads(target.read_text(encoding="utf-8"))
        if data["reference"] == reference and data["version"] == VERSION and data.get("parser_revision") == PARSER_REVISION:
            return data
    url = f"https://www.bible.com/bible/{VERSION}/{reference}.VIE1925"
    for attempt in range(4):
        try:
            request = urllib.request.Request(url, headers={
                "User-Agent": "NgheKinhThanh1925/2.15 (VIE1925 text import)",
                "Accept-Encoding": "gzip",
            })
            with urllib.request.urlopen(request, timeout=35) as response:
                payload = response.read()
                if response.headers.get("Content-Encoding") == "gzip":
                    payload = gzip.decompress(payload)
            result = parse_chapter(payload.decode("utf-8"), reference)
            (cache / f"{reference}.html.gz").write_bytes(gzip.compress(payload))
            result["url"] = url
            result["html_sha256"] = hashlib.sha256(payload).hexdigest()
            target.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
            return result
        except Exception:
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)
    raise AssertionError("Unreachable")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--sample", type=int, default=0)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    assert len(manifest) == 1189
    assert all("version_id=193" in item["url"] for item in manifest)
    args.cache.mkdir(parents=True, exist_ok=True)
    items = manifest[:args.sample] if args.sample else manifest
    results = {}
    errors = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(fetch_chapter, item, args.cache): item for item in items}
        for index, future in enumerate(concurrent.futures.as_completed(futures), 1):
            item = futures[future]
            try:
                results[item["globalOrder"]] = future.result()
            except Exception as error:
                errors.append(f'{item["bookCode"]}.{item["chapter"]}: {error}')
                print(errors[-1], flush=True)
            if index % 50 == 0 or index == len(items):
                print(f"Validated {index}/{len(items)} chapters", flush=True)
    if errors:
        raise ValueError(f"{len(errors)} failed chapters; rerun to retry using the cache: {errors[:5]}")
    if args.sample:
        print("Sample import validated", flush=True)
        return
    rows = []
    names = {}
    for item in manifest:
        result = results[item["globalOrder"]]
        if result["book_name"]:
            names.setdefault(item["bookCode"], result["book_name"])
        rows.extend((item["bookCode"], item["chapter"], number, text) for number, text in result["verses"])
    assert len(results) == 1189 and len(names) == 66
    staging = args.database.with_suffix(".youversion.tmp.db")
    shutil.copyfile(args.database, staging)
    with closing(sqlite3.connect(staging)) as connection:
        old_verses = {row[:3]: row[3] for row in connection.execute(
            "SELECT book_code, chapter_number, verse_number, text FROM verses")}
        # The source combines some verses and numbers others in adjacent chapters.
        # Validate against the source chapter inventory, not eBible numbering.
        if len(rows) < 30000:
            raise ValueError(f"Unexpectedly few verse entries: {len(rows)}")
        if len({row[:3] for row in rows}) != len(rows):
            raise ValueError("Duplicate verse entries")
        if {(row[0], row[1]) for row in rows} != {(item["bookCode"], item["chapter"]) for item in manifest}:
            raise ValueError("Incomplete chapter coverage")
        changed = sum(old_verses.get(row[:3]) != row[3] for row in rows)
        connection.execute("DELETE FROM verses")
        connection.executemany("INSERT INTO verses VALUES (?, ?, ?, ?)", rows)
        connection.executemany("UPDATE books SET name=? WHERE code=?", [(name, code) for code, name in names.items()])
        metadata = {
            "title": "Kinh Th\u00e1nh Ti\u1ebfng Vi\u1ec7t 1925",
            "source": "https://www.bible.com/versions/193-vie1925-kinh-thanh-tieng-viet-1925",
            "text_source": "YouVersion / Bible.com",
            "youversion_version_id": "193",
            "text_revision": "youversion-193-v1",
            "verse_count": str(len(rows)),
            "license": "Public Domain (Vietnamese 1925 translation)",
        }
        connection.executemany("INSERT OR REPLACE INTO metadata VALUES (?, ?)", metadata.items())
        connection.execute("PRAGMA user_version=2")
        connection.commit()
        assert connection.execute("PRAGMA integrity_check").fetchone() == ("ok",)
        assert connection.execute("SELECT COUNT(*) FROM chapters").fetchone() == (1189,)
        connection.execute("VACUUM")
    staging.replace(args.database)
    print(f"Imported 66 books, 1189 chapters, {len(rows)} verses; {changed} verses differ from previous text", flush=True)


if __name__ == "__main__":
    main()
