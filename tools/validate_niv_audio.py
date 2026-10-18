"""Check every NIV audio URL with HEAD; no recordings are downloaded."""
import concurrent.futures
import json
import sqlite3
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "niv-audio-cache"


def probe(row):
    order, url = row
    target = CACHE / f"head_{order:04d}.json"
    if target.exists():
        cached = json.loads(target.read_text(encoding="utf-8"))
        if cached["url"] == url:
            return cached["bytes"]
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, method="HEAD")
            with urllib.request.urlopen(request, timeout=30) as response:
                size = int(response.headers["Content-Length"])
                if response.status != 200 or size <= 1024 or response.headers.get_content_type() != "audio/mpeg":
                    raise ValueError(f"Invalid audio: {order}")
                target.write_text(json.dumps({"url": url, "bytes": size}), encoding="utf-8")
                return size
        except Exception:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def main():
    CACHE.mkdir(exist_ok=True)
    with sqlite3.connect(ROOT / "app/src/main/assets/bible.db") as db:
        rows = db.execute("SELECT global_order,audio_url FROM audio_chapters WHERE version_code='NIV' ORDER BY global_order").fetchall()
    assert len(rows) == 1189
    sizes = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        for size in pool.map(probe, rows):
            sizes.append(size)
            if len(sizes) % 100 == 0:
                print(f"NIV CDN HEAD checks: {len(sizes)}/1189", flush=True)
    total = sum(sizes)
    with sqlite3.connect(ROOT / "app/src/main/assets/bible.db") as db:
        db.execute("INSERT OR REPLACE INTO metadata(key,value) VALUES ('niv_audio_bytes',?)", (str(total),))
    print(f"PASS: 1189 audio/mpeg resources available; total {total} bytes ({total/1_000_000_000:.2f} GB). No recordings downloaded.", flush=True)


if __name__ == "__main__":
    main()
