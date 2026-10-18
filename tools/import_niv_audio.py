"""Import public YouVersion NIV recording links, never the audio files."""
import concurrent.futures
import json
import sqlite3
import time
import urllib.request
from pathlib import Path
from urllib.parse import urlparse, parse_qs

from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "niv-audio-cache"
DATABASE = ROOT / "app/src/main/assets/bible.db"


def extract_audio(document):
    soup = BeautifulSoup(document, "html.parser")
    for script in soup.find_all("script", type="application/ld+json"):
        value = json.loads(script.string or script.get_text())
        for item in value if isinstance(value, list) else [value]:
            if item.get("@type") == "AudioObject":
                url = item["contentUrl"]
                parsed = urlparse(url)
                if (parsed.scheme != "https" or
                        parsed.netloc != "audio-bible-cdn.youversionapi.com" or
                        not parsed.path.startswith("/3/32k/") or
                        parse_qs(parsed.query).get("version_id") != ["111"]):
                    raise ValueError(f"Unexpected NIV recording: {url}")
                return url
    raise ValueError("No AudioObject in chapter page")


def fetch(chapter):
    order, book, number = chapter
    target = CACHE / f"{order:04d}_{book}_{number:03d}.json"
    if target.exists():
        item = json.loads(target.read_text(encoding="utf-8"))
    else:
        reference = f"{book}.{number}.NIV"
        page = f"https://www.bible.com/audio-bible/111/{reference}"
        for attempt in range(4):
            try:
                request = urllib.request.Request(page, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(request, timeout=40) as response:
                    url = extract_audio(response.read().decode("utf-8"))
                item = {"order": order, "book": book, "chapter": number,
                        "url": url, "source": page}
                target.write_text(json.dumps(item, ensure_ascii=False), encoding="utf-8")
                break
            except Exception:
                if attempt == 3:
                    raise
                time.sleep(2 ** attempt)
    parsed = urlparse(item["url"])
    if not parsed.path.startswith(f"/3/32k/{book}/{number}-"):
        raise ValueError(f"Chapter mismatch: {target}")
    return item


def main():
    CACHE.mkdir(exist_ok=True)
    with sqlite3.connect(DATABASE) as db:
        chapters = db.execute("SELECT global_order,book_code,chapter_number FROM chapters ORDER BY global_order").fetchall()
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        for item in pool.map(fetch, chapters):
            results.append(item)
            if len(results) % 50 == 0:
                print(f"NIV audio links: {len(results)}/{len(chapters)}", flush=True)
    if len(results) != 1189 or len({item["url"] for item in results}) != 1189:
        raise ValueError("Incomplete or duplicate audio links")
    # A range probe verifies the actual stream without fetching a whole recording.
    for index in (0, 928, 1188):
        item = results[index]
        request = urllib.request.Request(item["url"], headers={"Range": "bytes=0-1023"})
        with urllib.request.urlopen(request, timeout=40) as response:
            sample = response.read(1024)
            if response.status not in (200, 206) or len(sample) != 1024:
                raise ValueError(f"Unusable audio: {item['source']}")
            print(f"Stream probe: {item['book']} {item['chapter']}: {response.status}, {response.headers.get('Content-Type')}", flush=True)
    with sqlite3.connect(DATABASE) as db:
        db.execute("""CREATE TABLE IF NOT EXISTS audio_chapters (
            version_code TEXT NOT NULL, global_order INTEGER NOT NULL,
            book_code TEXT NOT NULL, chapter_number INTEGER NOT NULL,
            audio_filename TEXT NOT NULL, audio_url TEXT NOT NULL,
            PRIMARY KEY(version_code,global_order),
            UNIQUE(version_code,book_code,chapter_number))""")
        db.execute("DELETE FROM audio_chapters WHERE version_code='NIV'")
        db.executemany("INSERT INTO audio_chapters VALUES ('NIV',?,?,?,?,?)", [
            (item["order"], item["book"], item["chapter"],
             f"NIV_{item['order']:04d}_{item['book']}_{item['chapter']:03d}.mp3", item["url"])
            for item in results])
        db.execute("PRAGMA user_version=4")
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    print("NIV audio metadata imported: 66 books, 1189 chapters; no audio bundled.", flush=True)


if __name__ == "__main__":
    main()
