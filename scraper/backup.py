"""Backups: a zip with a consistent DB copy plus a readable JSON export.

Usage:
    python -m scraper.backup                 # writes to ./backups, keeps last 10
    python -m scraper.backup --dir /mnt/usb --keep 30
"""
from __future__ import annotations

import argparse
import json
import tempfile
import time
import zipfile
from pathlib import Path

from .store import Store

DEFAULT_DATA_DIR = Path("data")


def make_backup_zip(store: Store, dest_dir: str | Path, keep: int = 10) -> Path:
    dest_dir = Path(dest_dir)
    dest_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out = dest_dir / f"scraper-backup-{stamp}.zip"
    with tempfile.TemporaryDirectory() as tmp:
        db_copy = Path(tmp) / "scraper.db"
        store.backup_to(db_copy)
        json_copy = Path(tmp) / "export.json"
        json_copy.write_text(json.dumps(store.export_dict(), indent=2, default=str),
                             encoding="utf-8")
        with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            zf.write(db_copy, "scraper.db")
            zf.write(json_copy, "export.json")
    prune_backups(dest_dir, keep)
    return out


def prune_backups(dest_dir: Path, keep: int) -> list[Path]:
    """Delete the oldest backup zips so that at most `keep` remain."""
    if keep < 1:
        return []
    files = sorted(dest_dir.glob("scraper-backup-*.zip"))
    removed = files[:-keep] if len(files) > keep else []
    for f in removed:
        f.unlink()
    return removed


def main() -> None:
    parser = argparse.ArgumentParser(description="Create a backup of the scraper data.")
    parser.add_argument("--db", default=str(DEFAULT_DATA_DIR / "scraper.db"))
    parser.add_argument("--dir", default="backups")
    parser.add_argument("--keep", type=int, default=10)
    args = parser.parse_args()
    store = Store(args.db)
    try:
        out = make_backup_zip(store, args.dir, args.keep)
    finally:
        store.close()
    print(f"Backup written: {out}")


if __name__ == "__main__":
    main()
