"""robots.txt handling, following the usual conventions:

* 2xx  -> parse the file and obey it
* 4xx  -> no robots.txt, everything allowed
* 5xx / network error -> treat the whole site as off-limits (conservative)
"""
from __future__ import annotations

import threading
from urllib.parse import urlsplit
from urllib.robotparser import RobotFileParser

from .fetch import Fetcher

AGENT_TOKEN = "PoliteScraper"


class RobotsCache:
    def __init__(self, fetcher: Fetcher) -> None:
        self.fetcher = fetcher
        self._cache: dict[str, RobotFileParser] = {}
        self._lock = threading.Lock()

    def _origin(self, url: str) -> str:
        parts = urlsplit(url)
        return f"{parts.scheme}://{parts.netloc}"

    def _parser(self, url: str) -> RobotFileParser:
        origin = self._origin(url)
        with self._lock:
            if origin in self._cache:
                return self._cache[origin]
        rp = RobotFileParser()
        res = self.fetcher.get(origin + "/robots.txt", force_read=True)
        if res.error is None and res.status is not None and 200 <= res.status < 300:
            rp.parse(res.text.splitlines())
        elif res.status is not None and 400 <= res.status < 500:
            rp.parse(["User-agent: *", "Allow: /"])
        else:
            rp.parse(["User-agent: *", "Disallow: /"])
        with self._lock:
            self._cache[origin] = rp
        return rp

    def allowed(self, url: str) -> bool:
        return self._parser(url).can_fetch(AGENT_TOKEN, url)

    def crawl_delay(self, url: str) -> float:
        value = self._parser(url).crawl_delay(AGENT_TOKEN)
        try:
            return float(value) if value is not None else 0.0
        except (TypeError, ValueError):
            return 0.0
