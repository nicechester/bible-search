"""
Generate Bible embeddings using MLX on Apple Silicon.
Saves to SQLite format compatible with SqliteEmbeddingStore.java.

Usage:
    pip install mlx sentence-transformers numpy
    python scripts/generate_embeddings.py
"""

import json
import sqlite3
import struct
import time
import uuid
from pathlib import Path

import mlx.core as mx
import numpy as np
from sentence_transformers import SentenceTransformer

# --- Config ---
MODEL_NAME = "dragonkue/bge-m3-ko"
BIBLE_KRV = "src/main/resources/bible/bible_krv.json"
BIBLE_ASV = "src/main/resources/bible/bible_asv.json"
OUTPUT_DB  = "src/main/resources/embeddings/bible-embeddings.db"
BATCH_SIZE = 256


def load_verses(json_path: str, version: str) -> list[dict]:
    with open(json_path, encoding="utf-8") as f:
        data = json.load(f)

    verses = []
    for book in data["books"]:
        book_name = book["bookName"]
        book_short = book["bookShort"]
        for chapter in book["chapters"]:
            ch = chapter["chapter"]
            for v in chapter["verses"]:
                text = v["text"].strip()
                if text:
                    verses.append({
                        "text": text,
                        "key": f"{version}:{book_short}:{ch}:{v['verse']}",
                    })
    return verses


def floats_to_bytes(vec: np.ndarray) -> bytes:
    return struct.pack(f"<{len(vec)}f", *vec.tolist())


def init_db(db_path: str) -> sqlite3.Connection:
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS embeddings (
            id       TEXT PRIMARY KEY,
            text     TEXT NOT NULL,
            metadata TEXT,
            embedding BLOB NOT NULL
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_id ON embeddings(id)")
    conn.commit()
    return conn


def insert_batch(conn: sqlite3.Connection, texts: list[str], refs: list[str], vecs: np.ndarray):
    rows = [
        (str(uuid.uuid4()), text, ref, floats_to_bytes(vec))
        for text, ref, vec in zip(texts, refs, vecs)
    ]
    conn.executemany("INSERT OR REPLACE INTO embeddings (id, text, metadata, embedding) VALUES (?,?,?,?)", rows)
    conn.commit()


def main():
    print(f"Loading model: {MODEL_NAME}")
    # sentence-transformers handles MLX acceleration transparently on Apple Silicon
    model = SentenceTransformer(MODEL_NAME, device="mps")

    print("Loading Bible verses...")
    verses = load_verses(BIBLE_KRV, "KRV") + load_verses(BIBLE_ASV, "ASV")
    total = len(verses)
    print(f"Total verses: {total:,}")

    conn = init_db(OUTPUT_DB)

    start = time.time()
    for i in range(0, total, BATCH_SIZE):
        batch = verses[i:i + BATCH_SIZE]
        texts = [v["text"] for v in batch]
        refs  = [v["key"]  for v in batch]

        vecs = model.encode(texts, batch_size=BATCH_SIZE, normalize_embeddings=True, show_progress_bar=False)
        insert_batch(conn, texts, refs, vecs)

        elapsed = time.time() - start
        done = i + len(batch)
        rate = done / elapsed
        remaining = (total - done) / rate if rate > 0 else 0
        print(f"\r[{done:>6}/{total}] {rate:.0f} verses/s  ETA {remaining/60:.1f}min", end="", flush=True)

    print(f"\nDone! {total:,} embeddings saved to {OUTPUT_DB}")
    print(f"Total time: {(time.time()-start)/60:.1f} min")

    conn.execute("PRAGMA optimize")
    conn.close()


if __name__ == "__main__":
    main()
