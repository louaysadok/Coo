"""SQLite storage for jobs, pages and favourites."""
from __future__ import annotations

import sqlite3
import threading
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    start_url       TEXT NOT NULL,
    max_pages       INTEGER NOT NULL,
    max_depth       INTEGER NOT NULL,
    delay           REAL NOT NULL,
    same_site       INTEGER NOT NULL,
    status          TEXT NOT NULL,
    pages_fetched   INTEGER NOT NULL DEFAULT 0,
    error           TEXT,
    cancel_requested INTEGER NOT NULL DEFAULT 0,
    created_at      REAL NOT NULL,
    started_at      REAL,
    finished_at     REAL
);
CREATE TABLE IF NOT EXISTS pages (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id       INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    url          TEXT NOT NULL,
    final_url    TEXT,
    depth        INTEGER NOT NULL DEFAULT 0,
    status_code  INTEGER,
    content_type TEXT,
    fetched_at   REAL,
    title        TEXT,
    description  TEXT,
    text         TEXT,
    error        TEXT,
    UNIQUE(job_id, url)
);
CREATE INDEX IF NOT EXISTS idx_pages_job ON pages(job_id);
CREATE TABLE IF NOT EXISTS favorites (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    kind       TEXT NOT NULL CHECK (kind IN ('page', 'job')),
    ref_id     INTEGER NOT NULL,
    created_at REAL NOT NULL,
    UNIQUE(kind, ref_id)
);
"""

JOB_COLUMNS = {
    "status", "pages_fetched", "error", "cancel_requested",
    "started_at", "finished_at",
}
PAGE_COLUMNS = (
    "url", "final_url", "depth", "status_code", "content_type", "fetched_at",
    "title", "description", "text", "error",
)


class Store:
    def __init__(self, path: str | Path) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.path = str(path)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.executescript(SCHEMA)
        self._conn.commit()

    # ---- low-level helpers -------------------------------------------------
    def _write(self, sql: str, params: tuple = ()) -> sqlite3.Cursor:
        with self._lock:
            cur = self._conn.execute(sql, params)
            self._conn.commit()
            return cur

    def _rows(self, sql: str, params: tuple = ()) -> list[dict[str, Any]]:
        with self._lock:
            return [dict(r) for r in self._conn.execute(sql, params).fetchall()]

    def _row(self, sql: str, params: tuple = ()) -> dict[str, Any] | None:
        rows = self._rows(sql, params)
        return rows[0] if rows else None

    # ---- jobs --------------------------------------------------------------
    def create_job(self, *, start_url: str, max_pages: int, max_depth: int,
                   delay: float, same_site: bool, created_at: float) -> int:
        cur = self._write(
            "INSERT INTO jobs (start_url, max_pages, max_depth, delay, same_site,"
            " status, created_at) VALUES (?, ?, ?, ?, ?, 'queued', ?)",
            (start_url, max_pages, max_depth, delay, int(same_site), created_at),
        )
        return int(cur.lastrowid)

    def update_job(self, job_id: int, **fields: Any) -> None:
        bad = set(fields) - JOB_COLUMNS
        if bad:
            raise ValueError(f"unknown job fields: {bad}")
        if not fields:
            return
        assignments = ", ".join(f"{k} = ?" for k in fields)
        self._write(f"UPDATE jobs SET {assignments} WHERE id = ?",
                    (*fields.values(), job_id))

    def get_job(self, job_id: int) -> dict[str, Any] | None:
        return self._row("SELECT * FROM jobs WHERE id = ?", (job_id,))

    def list_jobs(self) -> list[dict[str, Any]]:
        return self._rows("SELECT * FROM jobs ORDER BY id DESC")

    def cancel_requested(self, job_id: int) -> bool:
        row = self._row("SELECT cancel_requested FROM jobs WHERE id = ?", (job_id,))
        return bool(row and row["cancel_requested"])

    def request_cancel(self, job_id: int) -> None:
        self._write("UPDATE jobs SET cancel_requested = 1 WHERE id = ?", (job_id,))

    def delete_job(self, job_id: int) -> None:
        with self._lock:
            self._conn.execute(
                "DELETE FROM favorites WHERE kind = 'page' AND ref_id IN"
                " (SELECT id FROM pages WHERE job_id = ?)", (job_id,))
            self._conn.execute("DELETE FROM favorites WHERE kind = 'job' AND ref_id = ?",
                               (job_id,))
            self._conn.execute("DELETE FROM jobs WHERE id = ?", (job_id,))
            self._conn.commit()

    def mark_interrupted(self) -> int:
        """Jobs left 'running' by a previous process crash are marked failed."""
        cur = self._write(
            "UPDATE jobs SET status = 'failed', error = 'interrupted (app restarted)',"
            " finished_at = COALESCE(finished_at, started_at)"
            " WHERE status IN ('queued', 'running')")
        return cur.rowcount

    # ---- pages -------------------------------------------------------------
    def add_page(self, job_id: int, **fields: Any) -> None:
        cols = [c for c in PAGE_COLUMNS if c in fields]
        placeholders = ", ".join("?" for _ in cols)
        self._write(
            f"INSERT OR IGNORE INTO pages (job_id, {', '.join(cols)})"
            f" VALUES (?, {placeholders})",
            (job_id, *(fields[c] for c in cols)),
        )

    def list_pages(self, job_id: int, q: str = "", limit: int = 50,
                   offset: int = 0) -> tuple[list[dict[str, Any]], int]:
        like = f"%{q.strip()}%" if q.strip() else None
        where = "job_id = ?"
        params: list[Any] = [job_id]
        if like:
            where += " AND (url LIKE ? OR title LIKE ? OR description LIKE ? OR text LIKE ?)"
            params += [like, like, like, like]
        total = self._row(f"SELECT COUNT(*) AS n FROM pages WHERE {where}",
                          tuple(params))["n"]
        rows = self._rows(
            f"SELECT id, url, final_url, depth, status_code, title, description, error"
            f" FROM pages WHERE {where} ORDER BY depth, id LIMIT ? OFFSET ?",
            (*params, limit, offset))
        return rows, total

    def get_page(self, page_id: int) -> dict[str, Any] | None:
        return self._row("SELECT * FROM pages WHERE id = ?", (page_id,))

    def all_pages(self, job_id: int | None = None) -> list[dict[str, Any]]:
        if job_id is None:
            return self._rows("SELECT * FROM pages ORDER BY job_id, id")
        return self._rows("SELECT * FROM pages WHERE job_id = ? ORDER BY id", (job_id,))

    # ---- favourites --------------------------------------------------------
    def toggle_favorite(self, kind: str, ref_id: int, now: float) -> bool:
        """Flip a favourite. Returns True if it is now a favourite."""
        if kind not in ("page", "job"):
            raise ValueError("kind must be 'page' or 'job'")
        with self._lock:
            row = self._conn.execute(
                "SELECT id FROM favorites WHERE kind = ? AND ref_id = ?",
                (kind, ref_id)).fetchone()
            if row:
                self._conn.execute("DELETE FROM favorites WHERE id = ?", (row["id"],))
                state = False
            else:
                self._conn.execute(
                    "INSERT INTO favorites (kind, ref_id, created_at) VALUES (?, ?, ?)",
                    (kind, ref_id, now))
                state = True
            self._conn.commit()
            return state

    def favorite_ids(self, kind: str) -> set[int]:
        return {r["ref_id"] for r in self._rows(
            "SELECT ref_id FROM favorites WHERE kind = ?", (kind,))}

    def list_favorites(self, kind: str) -> list[dict[str, Any]]:
        if kind == "job":
            return self._rows(
                "SELECT j.*, f.created_at AS favorited_at FROM favorites f"
                " JOIN jobs j ON j.id = f.ref_id WHERE f.kind = 'job'"
                " ORDER BY f.created_at DESC")
        return self._rows(
            "SELECT p.id, p.url, p.title, p.description, p.job_id, p.status_code,"
            " p.fetched_at, f.created_at AS favorited_at FROM favorites f"
            " JOIN pages p ON p.id = f.ref_id WHERE f.kind = 'page'"
            " ORDER BY f.created_at DESC")

    # ---- backup ------------------------------------------------------------
    def backup_to(self, dest: str | Path) -> None:
        """Consistent online copy of the whole database (SQLite backup API)."""
        with self._lock:
            target = sqlite3.connect(str(dest))
            try:
                self._conn.backup(target)
            finally:
                target.close()

    def export_dict(self) -> dict[str, Any]:
        return {
            "jobs": self._rows("SELECT * FROM jobs ORDER BY id"),
            "pages": self.all_pages(),
            "favorites": self._rows("SELECT * FROM favorites ORDER BY id"),
        }

    def close(self) -> None:
        with self._lock:
            self._conn.close()
