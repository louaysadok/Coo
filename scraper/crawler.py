"""The crawl loop: breadth-first, polite, cancellable, and bounded."""
from __future__ import annotations

import time
from collections import deque
from typing import Callable
from urllib.parse import urlsplit

from .fetch import Fetcher
from .parse import parse_html
from .robots import RobotsCache
from .store import Store
from .urlutil import normalize

MAX_PAGES_LIMIT = 5_000
MAX_DEPTH_LIMIT = 10
MIN_DELAY = 0.25
MAX_DELAY = 60.0


def validate_settings(url: str, max_pages: int, max_depth: int,
                      delay: float, same_site: bool) -> dict:
    """Clean and check user input. Raises ValueError with a readable message."""
    start = normalize(url or "")
    if start is None:
        raise ValueError("Enter a full http:// or https:// address.")
    if not 1 <= max_pages <= MAX_PAGES_LIMIT:
        raise ValueError(f"Max pages must be between 1 and {MAX_PAGES_LIMIT}.")
    if not 0 <= max_depth <= MAX_DEPTH_LIMIT:
        raise ValueError(f"Max depth must be between 0 and {MAX_DEPTH_LIMIT}.")
    if not MIN_DELAY <= delay <= MAX_DELAY:
        raise ValueError(f"Delay must be between {MIN_DELAY} and {MAX_DELAY} seconds.")
    return {"start_url": start, "max_pages": max_pages, "max_depth": max_depth,
            "delay": float(delay), "same_site": bool(same_site)}


def crawl(store: Store, job_id: int, fetcher: Fetcher, robots: RobotsCache,
          clock: Callable[[], float] = time.time) -> None:
    job = store.get_job(job_id)
    if job is None:
        return
    start = job["start_url"]
    site_host = urlsplit(start).hostname
    store.update_job(job_id, status="running", started_at=clock(), error=None)

    queue: deque[tuple[str, int]] = deque([(start, 0)])
    seen = {start}
    fetched = 0
    status, error = "completed", None
    try:
        while queue:
            if store.cancel_requested(job_id):
                status = "cancelled"
                break
            if fetched >= job["max_pages"]:
                break
            url, depth = queue.popleft()

            if not fetcher.public_check(url):
                store.add_page(job_id, url=url, depth=depth,
                               fetched_at=clock(), error="Blocked: address is not public")
                continue
            if not robots.allowed(url):
                store.add_page(job_id, url=url, depth=depth,
                               fetched_at=clock(), error="Blocked by robots.txt")
                continue

            delay = max(job["delay"], robots.crawl_delay(url))
            res = fetcher.get(url, min_delay=delay)
            fetched += 1
            page = {
                "url": url,
                "final_url": res.final_url or url,
                "depth": depth,
                "status_code": res.status,
                "content_type": res.content_type,
                "fetched_at": clock(),
                "title": "",
                "description": "",
                "text": "",
                "error": res.error,
            }
            if res.error is None and res.is_html:
                parsed = parse_html(res.text, res.final_url or url)
                page.update(title=parsed["title"], description=parsed["description"],
                            text=parsed["text"])
                if depth < job["max_depth"]:
                    for link in parsed["links"]:
                        if job["same_site"] and urlsplit(link).hostname != site_host:
                            continue
                        if link in seen:
                            continue
                        seen.add(link)
                        queue.append((link, depth + 1))
            store.add_page(job_id, **page)
            store.update_job(job_id, pages_fetched=fetched)
    except Exception as exc:  # keep the worker alive and record the reason
        status, error = "failed", f"{type(exc).__name__}: {exc}"

    store.update_job(job_id, status=status, error=error, pages_fetched=fetched,
                     finished_at=clock())
