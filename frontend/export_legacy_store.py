import json
import sqlite3
from pathlib import Path

source = Path(__file__).with_name("local_store.db")
target = Path(__file__).with_name("public") / "legacy-documents.json"
target.parent.mkdir(parents=True, exist_ok=True)

with sqlite3.connect(source) as connection:
    records = connection.execute("SELECT id, text FROM documents ORDER BY inserted_at DESC").fetchall()

target.write_text(
    json.dumps([{"id": doc_id, "text": text or ""} for doc_id, text in records], ensure_ascii=False),
    encoding="utf-8",
)
print(f"Exported {len(records)} legacy documents to {target}")
